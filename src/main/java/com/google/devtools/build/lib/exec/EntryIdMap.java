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
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
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
 * <p>This is a sharded primitive map rather than a {@link java.util.concurrent.ConcurrentHashMap}
 * because it may hold millions of entries and the latter would use several times more memory per
 * entry. A single lock isn't an option either: nearly every lookup finds an existing entry, and
 * with hundreds of threads logging spawns at once, even a short critical section around each
 * lookup becomes a bottleneck.
 */
final class EntryIdMap {

  /** Computes an entry, writes it to the log and returns its ID. */
  @FunctionalInterface
  interface IdComputer {
    int compute() throws IOException, InterruptedException;
  }

  private static final class Shard {
    // Use a specialized map for minimal memory footprint.
    @GuardedBy("this")
    private final Object2IntOpenHashMap<Object> ids = new Object2IntOpenHashMap<>();

    @GuardedBy("this")
    private final HashMap<Object, InFlightEntry> inFlight = new HashMap<>();
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
    Shard shard = getShard(key);
    while (true) {
      InFlightEntry inFlight;
      boolean isOwner = false;
      synchronized (shard) {
        int id = shard.ids.getOrDefault(key, EntryWriter.NO_ID);
        if (id != EntryWriter.NO_ID) {
          return id;
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
        // This thread is already computing the entry further up the stack, so waiting would
        // deadlock. This isn't expected to happen, but keys are shared between entry types (a file
        // and a runfiles tree could have the same path), so compute a separate entry as the
        // previous single-lock implementation would have.
        return computer.compute();
      }
      Integer id = await(inFlight);
      if (id != null) {
        return id;
      }
      // The owner failed to compute the entry. Retry, possibly becoming the owner.
    }
  }

  private Shard getShard(Object key) {
    // Use the high bits: the shard's own hash table uses the low bits of the same mixed hash, so
    // using those here would leave most of its slots unused.
    return shards[HashCommon.mix(key.hashCode()) >>> (Integer.SIZE - SHARD_BITS)];
  }

  private static int compute(Object key, IdComputer computer, Shard shard, InFlightEntry inFlight)
      throws IOException, InterruptedException {
    int id;
    try {
      id = computer.compute();
      checkState(id != EntryWriter.NO_ID, "invalid ID for %s", key);
    } catch (Throwable t) {
      synchronized (shard) {
        shard.inFlight.remove(key);
      }
      inFlight.id.completeExceptionally(t);
      throw t;
    }
    // Record the ID before forgetting the in-flight entry, atomically, so that no concurrent
    // request can find neither and compute the entry again.
    synchronized (shard) {
      shard.ids.put(key, id);
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
