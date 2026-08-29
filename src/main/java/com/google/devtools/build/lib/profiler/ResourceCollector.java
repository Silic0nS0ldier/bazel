// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.profiler;

import static java.util.stream.Collectors.groupingBy;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skybridge.ScOnly;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** Monitors a number of counter series collectors and logs them in the profile as a time series. */
@ScOnly
public class ResourceCollector {

  // TODO(twerth): Make these configurable.
  private static final Duration BUCKET_DURATION = Duration.ofSeconds(1);
  private static final Duration COLLECT_SLEEP_INTERVAL = Duration.ofMillis(200);

  private volatile boolean stopCollection;
  private volatile boolean profilingStarted;

  private final ConcurrentLinkedQueue<CounterSeriesCollector> collectors =
      new ConcurrentLinkedQueue<>();

  @GuardedBy("this")
  @Nullable
  private Map<CounterSeriesTask, TimeSeries> timeSeries;

  private Stopwatch stopwatch;

  private int metricsBatchSize = 0;

  @Nullable private Collector collector = null;

  public ResourceCollector() {}

  /**
   * Sets the number of collection intervals to buffer before flushing counters to the profile. A
   * value of 0 buffers until {@link #stop}.
   */
  public void setMetricsBatchSize(int metricsBatchSize) {
    this.metricsBatchSize = metricsBatchSize;
  }

  public void start() {
    Preconditions.checkState(collector == null);
    collector = new Collector();
    collector.setDaemon(true);
    collector.start();
  }

  public void registerCounterSeriesCollector(CounterSeriesCollector collector) {
    collectors.add(collector);
  }

  public void unregisterCounterSeriesCollector(CounterSeriesCollector collector) {
    collectors.remove(collector);
  }

  /** Thread that does the collection. */
  private class Collector extends Thread {

    Collector() {
      super("collect-local-resources");
    }

    @Override
    public void run() {
      synchronized (ResourceCollector.this) {
        timeSeries = new LinkedHashMap<>();
      }

      stopwatch = Stopwatch.createStarted();
      int currentBatchSize = 0;
      Duration startTime = stopwatch.elapsed();
      Duration previousElapsed = stopwatch.elapsed();
      profilingStarted = true;
      while (!stopCollection) {
        try {
          Thread.sleep(COLLECT_SLEEP_INTERVAL.toMillis());
        } catch (InterruptedException e) {
          return;
        }
        currentBatchSize++;
        Duration nextElapsed = stopwatch.elapsed();
        double deltaNanos = nextElapsed.minus(previousElapsed).toNanos();
        Duration finalPreviousElapsed = previousElapsed;
        synchronized (ResourceCollector.this) {
          for (var collector : collectors) {
            collector.collect(
                deltaNanos,
                (type, value) ->
                    addRange(type, startTime, finalPreviousElapsed, nextElapsed, value));
          }
        }

        if (metricsBatchSize > 0 && currentBatchSize >= metricsBatchSize) {
          // need to join profiler thread
          logCollectedData();
          currentBatchSize = 0;
        }

        previousElapsed = nextElapsed;
      }
    }
  }

  public synchronized void stop() {
    if (collector != null) {
      Preconditions.checkArgument(!stopCollection);
      stopCollection = true;
      collector.interrupt();
      try {
        collector.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      logCollectedData();
      collectors.clear();
      collector = null;
      stopCollection = false;
      profilingStarted = false;
      timeSeries = null;
    }
  }

  synchronized void logCollectedData() {
    if (!profilingStarted) {
      return;
    }
    long endTimeNanos = System.nanoTime();
    long elapsedNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
    long startTimeNanos = endTimeNanos - elapsedNanos;
    Duration profileStart = Duration.ofNanos(startTimeNanos);
    int len = (int) (elapsedNanos / BUCKET_DURATION.toNanos()) + 1;

    Map<String, List<Map.Entry<CounterSeriesTask, TimeSeries>>> stackedTaskGroups =
        timeSeries.entrySet().stream().collect(groupingBy(e -> e.getKey().laneName()));

    for (var taskGroup : stackedTaskGroups.values()) {
      ImmutableMap.Builder<CounterSeriesTask, double[]> stackedCounters =
          ImmutableMap.builderWithExpectedSize(taskGroup.size());
      for (var task : taskGroup) {
        var series = task.getValue();
        stackedCounters.put(task.getKey(), series.toDoubleArray(len));
        series.clear();
      }
      Profiler.instance()
          .logCounters(stackedCounters.buildOrThrow(), profileStart, BUCKET_DURATION);
    }
  }

  private void addRange(
      CounterSeriesTask type,
      Duration startTime,
      Duration previousElapsed,
      Duration nextElapsed,
      double value) {
    synchronized (this) {
      if (timeSeries == null) {
        return;
      }
      var series =
          timeSeries.computeIfAbsent(
              type, unused -> Profiler.instance().createTimeSeries(startTime, BUCKET_DURATION));
      series.addRange(previousElapsed, nextElapsed, value);
    }
  }
}
