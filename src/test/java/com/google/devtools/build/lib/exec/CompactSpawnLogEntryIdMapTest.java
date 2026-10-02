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

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link CompactSpawnLogEntryIdMap}. */
@RunWith(JUnit4.class)
public final class CompactSpawnLogEntryIdMapTest {
  private final CompactSpawnLogEntryIdMap map = new CompactSpawnLogEntryIdMap();
  private final AtomicInteger nextId = new AtomicInteger(1);
  private final ExecutorService executor = Executors.newCachedThreadPool();

  @After
  public void shutdown() {
    executor.shutdownNow();
  }

  @Test
  public void computesOncePerKey() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    CompactSpawnLogEntryIdMap.IdComputer computer =
        () -> {
          calls.incrementAndGet();
          return nextId.getAndIncrement();
        };

    int a = map.getOrCompute("a", computer);
    int b = map.getOrCompute("b", computer);

    assertThat(a).isNotEqualTo(b);
    assertThat(map.getOrCompute("a", computer)).isEqualTo(a);
    assertThat(map.getOrCompute("b", computer)).isEqualTo(b);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  public void concurrentRequestsWaitForOwner() throws Exception {
    CountDownLatch ownerStarted = new CountDownLatch(1);
    CountDownLatch releaseOwner = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    CompactSpawnLogEntryIdMap.IdComputer computer =
        () -> {
          calls.incrementAndGet();
          ownerStarted.countDown();
          releaseOwner.await();
          return 42;
        };

    Future<Integer> owner = executor.submit(() -> map.getOrCompute("key", computer));
    ownerStarted.await();
    List<Future<Integer>> waiters = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      waiters.add(submitAndAwaitWaiting(() -> map.getOrCompute("key", computer)));
    }
    releaseOwner.countDown();

    assertThat(owner.get(10, SECONDS)).isEqualTo(42);
    for (Future<Integer> waiter : waiters) {
      assertThat(waiter.get(10, SECONDS)).isEqualTo(42);
    }
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  public void otherKeysProceedWhileEntryIsInFlight() throws Exception {
    CountDownLatch ownerStarted = new CountDownLatch(1);
    CountDownLatch releaseOwner = new CountDownLatch(1);
    Future<Integer> owner =
        executor.submit(
            () ->
                map.getOrCompute(
                    "slow",
                    () -> {
                      ownerStarted.countDown();
                      releaseOwner.await();
                      return 1;
                    }));
    ownerStarted.await();

    // Must not block behind the in-flight entry.
    assertThat(map.getOrCompute("fast", () -> 2)).isEqualTo(2);

    releaseOwner.countDown();
    assertThat(owner.get(10, SECONDS)).isEqualTo(1);
  }

  @Test
  public void failureIsNotRemembered() throws Exception {
    assertThrows(
        IOException.class,
        () ->
            map.getOrCompute(
                "key",
                () -> {
                  throw new IOException("boom");
                }));

    assertThat(map.getOrCompute("key", () -> 7)).isEqualTo(7);
  }

  @Test
  public void waiterRetriesAfterOwnerFails() throws Exception {
    CountDownLatch ownerStarted = new CountDownLatch(1);
    CountDownLatch releaseOwner = new CountDownLatch(1);
    Future<Integer> owner =
        executor.submit(
            () ->
                map.getOrCompute(
                    "key",
                    () -> {
                      ownerStarted.countDown();
                      releaseOwner.await();
                      throw new InterruptedException("owner interrupted");
                    }));
    ownerStarted.await();
    CountDownLatch waiterComputed = new CountDownLatch(1);
    Future<Integer> waiter =
        submitAndAwaitWaiting(
            () ->
                map.getOrCompute(
                    "key",
                    () -> {
                      waiterComputed.countDown();
                      return 5;
                    }));

    releaseOwner.countDown();

    // The owner's failure is its own; the waiter computes the entry itself.
    assertThat(waiter.get(10, SECONDS)).isEqualTo(5);
    assertThat(waiterComputed.getCount()).isEqualTo(0);
    var e = assertThrows(ExecutionException.class, () -> owner.get(10, SECONDS));
    assertThat(e).hasCauseThat().isInstanceOf(InterruptedException.class);
    assertThat(map.getOrCompute("key", () -> 99)).isEqualTo(5);
  }

  @Test
  public void recursiveRequestForSameKeyIsComputedSeparately() throws Exception {
    int outer =
        map.getOrCompute(
            "key",
            () -> {
              // Waiting for the outer computation would deadlock.
              assertThat(map.getOrCompute("key", () -> 1)).isEqualTo(1);
              return 2;
            });

    assertThat(outer).isEqualTo(2);
    assertThat(map.getOrCompute("key", () -> -1)).isEqualTo(2);
  }

  @Test
  public void recursiveRequestsForOtherKeysSucceed() throws Exception {
    int parent =
        map.getOrCompute(
            "parent",
            () -> {
              int child = map.getOrCompute("child", nextId::getAndIncrement);
              assertThat(map.getOrCompute("child", () -> -1)).isEqualTo(child);
              return nextId.getAndIncrement();
            });

    assertThat(map.getOrCompute("parent", () -> -1)).isEqualTo(parent);
  }

  @Test
  public void concurrentRequestsWhileTablesGrow() throws Exception {
    int numKeys = 200_000;
    int numThreads = 16;
    String[] keys = new String[numKeys];
    for (int i = 0; i < numKeys; i++) {
      keys[i] = "key" + i;
    }
    AtomicIntegerArray computations = new AtomicIntegerArray(numKeys);
    int[][] seen = new int[numThreads][numKeys];
    CyclicBarrier barrier = new CyclicBarrier(numThreads);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < numThreads; t++) {
      int thread = t;
      futures.add(
          executor.submit(
              () -> {
                barrier.await();
                // Each thread visits every key, in a different order, so that lookups race with
                // inserts and with the tables growing.
                for (int j = 0; j < numKeys; j++) {
                  int k = (int) ((j * 7919L + thread * 104729L) % numKeys);
                  seen[thread][k] =
                      map.getOrCompute(
                          keys[k],
                          () -> {
                            computations.incrementAndGet(k);
                            return nextId.getAndIncrement();
                          });
                }
                return null;
              }));
    }
    for (Future<?> future : futures) {
      future.get(60, SECONDS);
    }

    for (int k = 0; k < numKeys; k++) {
      assertThat(computations.get(k)).isEqualTo(1);
      for (int t = 1; t < numThreads; t++) {
        assertThat(seen[t][k]).isEqualTo(seen[0][k]);
      }
    }
  }

  /**
   * Submits a request and returns once it's blocked waiting for another thread's computation.
   *
   * <p>A request can only enter {@link Thread.State#WAITING} by waiting for an in-flight entry:
   * contention on the map's internal locks shows up as {@link Thread.State#BLOCKED} instead.
   */
  private Future<Integer> submitAndAwaitWaiting(Callable<Integer> request) throws Exception {
    AtomicReference<Thread> thread = new AtomicReference<>();
    Future<Integer> future =
        executor.submit(
            () -> {
              thread.set(Thread.currentThread());
              return request.call();
            });
    while (thread.get() == null || thread.get().getState() != Thread.State.WAITING) {
      if (future.isDone()) {
        throw new AssertionError("request completed without waiting: " + future.get());
      }
      Thread.sleep(1);
    }
    return future;
  }
}
