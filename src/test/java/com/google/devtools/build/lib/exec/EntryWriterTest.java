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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.GcFinalization;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.exec.Protos.ExecLogEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link EntryWriter}. */
@RunWith(JUnit4.class)
public final class EntryWriterTest {
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final EntryWriter writer = new EntryWriter("test", bytes);

  @After
  public void closeWriter() throws Exception {
    // Stops the writer thread in tests that don't close the writer. Closing again is harmless.
    writer.close();
  }

  /** An entry whose serialization is roughly {@code size} bytes. */
  private static ExecLogEntry.Builder entryOfSize(int size) {
    ExecLogEntry.InputSet.Builder inputSet = ExecLogEntry.InputSet.newBuilder();
    for (int i = 0; i < size / 3; i++) {
      inputSet.addInputIds(20000 + i);
    }
    return ExecLogEntry.newBuilder().setInputSet(inputSet);
  }

  private ImmutableList<ExecLogEntry> closeAndRead() throws Exception {
    writer.close();
    ImmutableList.Builder<ExecLogEntry> entries = ImmutableList.builder();
    try (InputStream in = new ByteArrayInputStream(bytes.toByteArray())) {
      ExecLogEntry entry;
      while ((entry = ExecLogEntry.parseDelimitedFrom(in)) != null) {
        entries.add(entry);
      }
    }
    return entries.build();
  }

  @Test
  public void entriesRoundTripWithConsecutiveIds() throws Exception {
    // Enough entries for IDs to cross the 1- and 2-byte varint boundaries (128, 16384), with bodies
    // small and large enough to do the same for the length prefix.
    List<ExecLogEntry.Builder> written = new ArrayList<>();
    for (int i = 0; i < 17000; i++) {
      ExecLogEntry.Builder entry = entryOfSize(i % 1000 == 0 ? 20000 : i % 200);
      written.add(entry.clone());
      assertThat(writer.writeWithId(entry)).isEqualTo(i + 1);
    }

    ImmutableList<ExecLogEntry> read = closeAndRead();

    assertThat(read).hasSize(written.size());
    for (int i = 0; i < read.size(); i++) {
      assertThat(read.get(i)).isEqualTo(written.get(i).setId(i + 1).build());
    }
  }

  @Test
  public void entriesWithoutIdDoNotConsumeIds() throws Exception {
    ExecLogEntry.Builder spawn =
        ExecLogEntry.newBuilder().setSpawn(ExecLogEntry.Spawn.newBuilder().setMnemonic("Javac"));

    assertThat(writer.writeWithId(entryOfSize(10))).isEqualTo(1);
    writer.writeWithoutId(spawn.clone());
    assertThat(writer.writeWithId(entryOfSize(10))).isEqualTo(2);

    ImmutableList<ExecLogEntry> read = closeAndRead();
    assertThat(read.stream().map(ExecLogEntry::getId)).containsExactly(1, EntryWriter.NO_ID, 2)
        .inOrder();
    assertThat(read.get(1)).isEqualTo(spawn.build());
  }

  @Test
  public void bytesMatchWriteDelimitedTo() throws Exception {
    // IDs cross the 1-byte varint boundary (128), and body sizes cross it for the length prefix.
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    for (int i = 0; i < 200; i++) {
      ExecLogEntry.Builder entry = entryOfSize(i * 3);
      if (i % 5 == 0) {
        entry.build().writeDelimitedTo(expected);
        writer.writeWithoutId(entry);
      } else {
        ExecLogEntry.Builder withoutId = entry.clone();
        int id = writer.writeWithId(withoutId);
        entry.setId(id).build().writeDelimitedTo(expected);
      }
    }

    writer.close();

    assertThat(bytes.toByteArray()).isEqualTo(expected.toByteArray());
  }

  @Test
  public void concurrentWritesHaveConsecutiveIdsInFileOrder() throws Exception {
    int numThreads = 16;
    int perThread = 2000;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CyclicBarrier barrier = new CyclicBarrier(numThreads);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < numThreads; t++) {
      int thread = t;
      futures.add(
          executor.submit(
              () -> {
                barrier.await();
                for (int i = 0; i < perThread; i++) {
                  // Interleave entries with and without IDs, of varying sizes.
                  if (i % 3 == 0) {
                    writer.writeWithoutId(entryOfSize(i % 500));
                  } else {
                    writer.writeWithId(entryOfSize((thread * 37 + i) % 3000));
                  }
                }
                return null;
              }));
    }
    for (Future<?> future : futures) {
      future.get();
    }
    executor.shutdown();

    ImmutableList<ExecLogEntry> read = closeAndRead();

    assertThat(read).hasSize(numThreads * perThread);
    int expectedId = 1;
    for (ExecLogEntry entry : read) {
      if (entry.getId() != EntryWriter.NO_ID) {
        assertThat(entry.getId()).isEqualTo(expectedId++);
      }
    }
  }

  @Test
  public void rejectsEntryWithId() {
    assertThrows(
        IllegalArgumentException.class, () -> writer.writeWithId(entryOfSize(10).setId(5)));
    assertThrows(
        IllegalArgumentException.class, () -> writer.writeWithoutId(entryOfSize(10).setId(5)));
  }

  @Test
  public void writesAfterWriterWasIdle() throws Exception {
    assertThat(writer.writeWithId(entryOfSize(10))).isEqualTo(1);
    // Long enough for the writer thread to catch up and start waiting for more entries.
    Thread.sleep(20);
    assertThat(writer.writeWithId(entryOfSize(10))).isEqualTo(2);

    assertThat(closeAndRead().stream().map(ExecLogEntry::getId)).containsExactly(1, 2).inOrder();
  }

  @Test
  public void writeFailureIsReportedOnClose() throws Exception {
    EntryWriter failing =
        new EntryWriter(
            "failing",
            new OutputStream() {
              @Override
              public void write(int b) throws IOException {
                throw new IOException("disk full");
              }

              @Override
              public void close() throws IOException {
                throw new IOException("close failed");
              }
            });
    failing.writeWithId(entryOfSize(10));
    failing.writeWithId(entryOfSize(10));

    IOException e = assertThrows(IOException.class, failing::close);
    assertThat(e).hasMessageThat().isEqualTo("disk full");
    assertThat(e.getSuppressed()).hasLength(1);
    assertThat(e.getSuppressed()[0]).hasMessageThat().isEqualTo("close failed");
  }

  @Test
  public void closeWaitsForTheWriterWhenInterrupted() throws Exception {
    // The writer thread blocks on its first write until released, so it's still writing when the
    // interrupted close() starts.
    CountDownLatch released = new CountDownLatch(1);
    EntryWriter blocked =
        new EntryWriter(
            "blocked",
            new OutputStream() {
              @Override
              public void write(int b) {
                Uninterruptibles.awaitUninterruptibly(released);
                bytes.write(b);
              }

              @Override
              public void write(byte[] b, int off, int len) {
                Uninterruptibles.awaitUninterruptibly(released);
                bytes.write(b, off, len);
              }
            });
    blocked.writeWithId(entryOfSize(10));
    Thread releaser =
        new Thread(
            () -> {
              Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(100));
              released.countDown();
            });
    releaser.start();

    Thread.currentThread().interrupt();
    try {
      blocked.close();
    } finally {
      // Also clears the interrupt, so that it doesn't leak into later tests.
      assertThat(Thread.interrupted()).isTrue();
    }

    assertThat(released.getCount()).isEqualTo(0);
    ExecLogEntry entry =
        ExecLogEntry.parseDelimitedFrom(new ByteArrayInputStream(bytes.toByteArray()));
    assertThat(entry.getId()).isEqualTo(1);
    releaser.join();
  }

  @Test
  public void writerThreadIgnoresInterrupts() throws Exception {
    Thread writerThread =
        Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.getName().equals("exec-log-writer:test"))
            .findFirst()
            .orElseThrow();

    writerThread.interrupt();
    // An idle writer clears the interrupt before waiting again, instead of spinning with it set.
    while (writerThread.isInterrupted()) {
      Thread.sleep(1);
    }

    assertThat(writer.writeWithId(entryOfSize(10))).isEqualTo(1);
    assertThat(closeAndRead()).hasSize(1);
  }

  @Test
  public void writtenEntriesCanBeGarbageCollected() throws Exception {
    // Before anything is written, the tail is the node the list starts from.
    WeakReference<Object> start = new WeakReference<>(writer.tailForTesting());
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    for (int i = 0; i < 1000; i++) {
      ExecLogEntry.Builder entry = entryOfSize(100);
      entry.clone().setId(i + 1).build().writeDelimitedTo(expected);
      writer.writeWithId(entry);
    }
    while (bytes.size() < expected.size()) {
      Thread.sleep(1);
    }

    // The writer is still open but has written everything, so it should only retain its last node.
    GcFinalization.awaitClear(start);
  }

  @Test
  public void rejectsWritesAfterClose() throws Exception {
    writer.close();
    assertThrows(IllegalStateException.class, () -> writer.writeWithId(entryOfSize(10)));
  }
}
