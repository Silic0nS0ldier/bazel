// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.exec;

import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.profiler.ProfilerTask.SPAWN_LOG;

import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import it.unimi.dsi.fastutil.HashCommon;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReferenceArray;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * A concurrent map from keys to the IDs of {@link CompactSpawnLogContext} entries, which ensures
 * that requests for the same key share one entry rather than each writing their own.
 *
 * <p>Computing an entry can be expensive (digesting param files, walking and digesting directories
 * that Skyframe has no metadata for, recursing into nested sets), so no lock is held while it runs:
 * other requests for the same key wait for it to finish, while requests for other keys proceed in
 * parallel. A failed computation isn't remembered: the next request for the key, including one
 * that was waiting on the failed computation, computes it again.
 *
 * <p>Nearly every lookup finds an existing entry, so those lookups don't lock. Only recording an
 * ID and tracking entries being computed lock, and the map is sharded so that those rarely contend.
 * Each shard is a plain open-addressing table rather than a {@link
 * java.util.concurrent.ConcurrentHashMap}, because the map may hold millions of entries and the
 * latter would use several times more memory per entry.
 */
final class CompactSpawnLogEntryIdMap {

  /** Computes an entry, writes it to the log and returns its ID. */
  @FunctionalInterface
  interface IdComputer {
    int compute() throws IOException, InterruptedException;
  }

  private static final class Shard {
    /**
     * An open-addressing hash table from keys to IDs.
     *
     * <p>Its arrays are only written while holding the shard's lock, so code holding it reads them
     * plainly. A slot's ID is written before its key is published (with release semantics), so a
     * reader that sees a key (with acquire semantics) also sees its ID. Keys are never removed.
     */
    private static final class Table {
      private final AtomicReferenceArray<Object> keys;
      private final int[] ids;

      private Table(int capacity) {
        keys = new AtomicReferenceArray<>(capacity);
        ids = new int[capacity];
      }
    }

    private static final int INITIAL_CAPACITY = 16;

    // Replaced rather than modified when it grows, so that readers always see a consistent table.
    private volatile Table table = new Table(INITIAL_CAPACITY);

    @GuardedBy("this")
    private int size = 0;

    @GuardedBy("this")
    private final HashMap<Object, InFlightEntry> inFlight = new HashMap<>();

    /**
     * Returns the ID recorded for {@code key}, or {@link CompactSpawnLogEntryWriter#NO_ID} if there
     * is none yet.
     *
     * <p>Doesn't lock. Without the shard's lock, it may miss an ID that is being recorded
     * concurrently.
     */
    private int get(Object key) {
      Table t = table;
      int mask = t.ids.length - 1;
      for (int i = hash(key) & mask; ; i = (i + 1) & mask) {
        Object k = t.keys.getAcquire(i);
        if (k == null) {
          return CompactSpawnLogEntryWriter.NO_ID;
        }
        if (k.equals(key)) {
          return t.ids[i];
        }
      }
    }

    /** Records the ID of a key that has none. */
    @GuardedBy("this")
    private void put(Object key, int id) {
      Table t = table;
      // Keep the load factor at most 3/4, so that probing always reaches an empty slot.
      if ((size + 1) * 4 > t.ids.length * 3) {
        Table grown = new Table(t.ids.length * 2);
        for (int i = 0; i < t.ids.length; i++) {
          Object k = t.keys.getPlain(i);
          if (k != null) {
            insert(grown, k, t.ids[i]);
          }
        }
        table = t = grown;
      }
      insert(t, key, id);
      size++;
    }

    private static void insert(Table t, Object key, int id) {
      int mask = t.ids.length - 1;
      int i = hash(key) & mask;
      while (t.keys.getPlain(i) != null) {
        i = (i + 1) & mask;
      }
      t.ids[i] = id;
      t.keys.setRelease(i, key);
    }
  }

  /** An entry that is being computed by {@link #owner}. */
  private static final class InFlightEntry {
    private final Thread owner = Thread.currentThread();
    private final CompletableFuture<Integer> id = new CompletableFuture<>();
  }

  // Must comfortably exceed the number of threads inside the map at once, which is bounded by cores
  // (not --jobs), while staying small enough that the shards' tables remain cache-friendly: 4096
  // shards measured slower than 256 (on 16-core machines).
  private static final int SHARD_BITS = 8;

  private final Shard[] shards = new Shard[1 << SHARD_BITS];

  CompactSpawnLogEntryIdMap() {
    for (int i = 0; i < shards.length; i++) {
      shards[i] = new Shard();
    }
  }

  /**
   * Returns the ID recorded for {@code key}, calling {@code computer} to obtain one if there is
   * none.
   *
   * <p>The ID only becomes visible to other callers once {@code computer} returns. If {@code
   * computer} writes the entry before returning, any caller that obtains the ID from this map may
   * therefore reference it.
   *
   * <p>{@code computer} may request IDs for other keys. If it (transitively) requests {@code key}
   * itself, the nested request is computed without deduplication rather than deadlocking. Requests
   * that wait on each other across threads aren't detected, so they must not form a cycle.
   */
  int getOrCompute(Object key, IdComputer computer) throws IOException, InterruptedException {
    Shard shard = getShard(key);
    // Nearly every request finds an ID recorded earlier, so look for one without locking first.
    int recorded = shard.get(key);
    if (recorded != CompactSpawnLogEntryWriter.NO_ID) {
      return recorded;
    }
    Integer id;
    do {
      InFlightEntry inFlight;
      boolean isOwner = false;
      synchronized (shard) {
        int recordedUnderLock = shard.get(key);
        if (recordedUnderLock != CompactSpawnLogEntryWriter.NO_ID) {
          return recordedUnderLock;
        }
        inFlight = shard.inFlight.get(key);
        if (inFlight == null) {
          inFlight = new InFlightEntry();
          shard.inFlight.put(key, inFlight);
          isOwner = true;
        }
      }

      if (isOwner) {
        return compute(key, computer, shard, inFlight);
      }
      if (inFlight.owner == Thread.currentThread()) {
        // Waiting on this thread's own computation would deadlock. It's unlikely, but keys are
        // shared between entry types (a file and a runfiles tree could have the same path), so
        // compute a separate entry, as a single reentrant lock would.
        try (SilentCloseable c = Profiler.instance().profile(SPAWN_LOG, "logEntry/reentrant")) {
          return computer.compute();
        }
      }
      id = await(inFlight);
      // A null ID means the owner failed, and it has already stopped tracking the entry, so the
      // next attempt finds a recorded ID, another owner, or becomes the owner itself.
    } while (id == null);
    return id;
  }

  private static int hash(Object key) {
    return HashCommon.mix(key.hashCode());
  }

  private Shard getShard(Object key) {
    // Use the high bits: the shard's own hash table uses the low bits of the same mixed hash, so
    // using those here would leave most of its slots unused.
    return shards[hash(key) >>> (Integer.SIZE - SHARD_BITS)];
  }

  private static int compute(Object key, IdComputer computer, Shard shard, InFlightEntry inFlight)
      throws IOException, InterruptedException {
    long startTimeNanos = Profiler.instance().nanoTimeMaybe();
    int id;
    try {
      id = computer.compute();
      checkState(id != CompactSpawnLogEntryWriter.NO_ID, "invalid ID for %s", key);
    } catch (Throwable t) {
      synchronized (shard) {
        shard.inFlight.remove(key);
      }
      inFlight.id.completeExceptionally(t);
      // The entry will be computed again by the next request for it, whether it's waiting now or
      // comes later, so make the wasted work visible in the profile: waiters discard this failure,
      // and the owner's caller may not report it (e.g. an interrupt).
      Profiler.instance().logSimpleTask(startTimeNanos, SPAWN_LOG, "logEntry/failed");
      throw t;
    }
    // Record the ID before forgetting the in-flight entry, atomically, so that no concurrent
    // request can find neither and compute the entry again.
    synchronized (shard) {
      shard.put(key, id);
      shard.inFlight.remove(key);
    }
    inFlight.id.complete(id);
    return id;
  }

  /**
   * Waits for another thread to compute an entry.
   *
   * @return the entry ID, or null if the other thread failed to compute it
   */
  @Nullable
  private static Integer await(InFlightEntry inFlight) throws InterruptedException {
    try (SilentCloseable c = Profiler.instance().profile(SPAWN_LOG, "logEntry/wait")) {
      return inFlight.id.get();
    } catch (ExecutionException e) {
      // The owner's failure isn't necessarily ours: it may have been interrupted, and computers
      // capture caller-specific state (the requesting spawn's input metadata and filesystem), so
      // an entry that one caller fails to compute may succeed for another. Let the caller retry.
      return null;
    }
  }
}
