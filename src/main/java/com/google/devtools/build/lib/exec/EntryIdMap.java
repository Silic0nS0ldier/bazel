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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * A concurrent map from keys to the IDs of {@link CompactSpawnLogContext} entries, which ensures
 * that each entry is computed at most once.
 *
 * <p>Computing an entry can be expensive (digesting param files, traversing directories, recursing
 * into nested sets), so it happens without holding any lock. Requests for a key whose entry is
 * being computed by another thread wait for that computation, while requests for other keys
 * proceed in parallel. A failed computation isn't remembered: the next request for the key,
 * including one that was waiting on the failed computation, computes it again.
 *
 * <p>Nearly every lookup finds an existing entry, so lookups don't lock: with hundreds of threads
 * logging spawns at once, even a short critical section around each lookup becomes a bottleneck
 * once runnable threads outnumber cores, because handing a contended lock over means waking a
 * parked thread. Only recording an ID locks, once per key, and the map is sharded so that those
 * rarely contend. Each shard is a plain open-addressing table rather than a {@link
 * ConcurrentHashMap}, because the map may hold millions of entries and the latter uses several
 * times more memory per entry; entries being computed are few, so those are tracked in one.
 */
final class EntryIdMap {

  /** Computes an entry, writes it to the log and returns its ID. */
  @FunctionalInterface
  interface IdComputer {
    int compute() throws IOException, InterruptedException;
  }

  private static final class Shard {
    /**
     * An open-addressing hash table from keys to IDs.
     *
     * <p>Its arrays are only written while holding the shard's lock. A slot's ID is written before
     * its key is published (with release semantics), so a reader that sees a key also sees its ID.
     * Keys are never removed.
     */
    private static final class Table {
      private final Object[] keys;
      private final int[] ids;
      private final int mask;

      private Table(int capacity) {
        keys = new Object[capacity];
        ids = new int[capacity];
        mask = capacity - 1;
      }
    }

    private static final VarHandle KEYS = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final int INITIAL_CAPACITY = 16;

    // Replaced rather than modified when it grows, so that readers always see a consistent table.
    private volatile Table table = new Table(INITIAL_CAPACITY);

    @GuardedBy("this")
    private int size = 0;

    /**
     * Returns the ID recorded for {@code key}, or {@link EntryWriter#NO_ID} if there is none yet.
     *
     * <p>Doesn't lock. May miss an ID that is being recorded concurrently, so callers must check
     * again after registering an in-flight entry before acting on a miss.
     */
    private int get(Object key, int hash) {
      Table t = table;
      for (int i = hash & t.mask; ; i = (i + 1) & t.mask) {
        Object k = KEYS.getAcquire(t.keys, i);
        if (k == null) {
          return EntryWriter.NO_ID;
        }
        if (k.equals(key)) {
          return t.ids[i];
        }
      }
    }

    /** Records the ID of a key that has none. */
    @GuardedBy("this")
    private void put(Object key, int hash, int id) {
      Table t = table;
      // Keep the load factor at most 3/4, so that probing always reaches an empty slot.
      if ((size + 1) * 4 > t.keys.length * 3) {
        Table grown = new Table(t.keys.length * 2);
        for (int i = 0; i < t.keys.length; i++) {
          if (t.keys[i] != null) {
            insert(grown, t.keys[i], mix(t.keys[i]), t.ids[i]);
          }
        }
        table = t = grown;
      }
      insert(t, key, hash, id);
      size++;
    }

    private static void insert(Table t, Object key, int hash, int id) {
      int i = hash & t.mask;
      while (t.keys[i] != null) {
        i = (i + 1) & t.mask;
      }
      t.ids[i] = id;
      KEYS.setRelease(t.keys, i, key);
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

  // Entries currently being computed by some thread. Other threads requesting the same key wait for
  // the result instead of computing it again. Only holds entries while they're being computed, so
  // its memory use doesn't matter.
  private final ConcurrentHashMap<Object, InFlightEntry> inFlight = new ConcurrentHashMap<>();

  EntryIdMap() {
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
    int hash = mix(key);
    // Use the high bits: the shard's own table uses the low bits of the same hash, so using those
    // here would leave most of its slots unused.
    Shard shard = shards[hash >>> (Integer.SIZE - SHARD_BITS)];
    // The common case: the entry has already been written.
    int recorded = shard.get(key, hash);
    if (recorded != EntryWriter.NO_ID) {
      return recorded;
    }
    while (true) {
      InFlightEntry mine = new InFlightEntry();
      InFlightEntry existing = inFlight.putIfAbsent(key, mine);
      if (existing == null) {
        // The previous owner may have recorded the ID and forgotten its in-flight entry after our
        // lookup. It records the ID first, so checking again now finds it.
        int id = shard.get(key, hash);
        if (id != EntryWriter.NO_ID) {
          inFlight.remove(key, mine);
          mine.id.complete(id);
          return id;
        }
        return compute(key, hash, computer, shard, mine);
      }
      if (existing.owner == Thread.currentThread()) {
        // This thread is already computing the entry further up the stack, so waiting would
        // deadlock. This isn't expected to happen, but keys are shared between entry types (a file
        // and a runfiles tree could have the same path), so compute a separate entry as the
        // previous single-lock implementation would have.
        return computer.compute();
      }
      Integer id = await(existing);
      if (id != null) {
        return id;
      }
      // The owner failed to compute the entry. Retry, possibly becoming the owner.
    }
  }

  private static int mix(Object key) {
    return HashCommon.mix(key.hashCode());
  }

  private int compute(
      Object key, int hash, IdComputer computer, Shard shard, InFlightEntry mine)
      throws IOException, InterruptedException {
    int id;
    try {
      id = computer.compute();
      checkState(id != EntryWriter.NO_ID, "invalid ID for %s", key);
    } catch (Throwable t) {
      inFlight.remove(key, mine);
      mine.id.completeExceptionally(t);
      throw t;
    }
    // Record the ID before forgetting the in-flight entry, so that a request can't find neither
    // and compute the entry again (see getOrCompute).
    synchronized (shard) {
      shard.put(key, hash, id);
    }
    inFlight.remove(key, mine);
    mine.id.complete(id);
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
