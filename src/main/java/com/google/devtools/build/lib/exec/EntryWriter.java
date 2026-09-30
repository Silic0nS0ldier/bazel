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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.exec.Protos.ExecLogEntry;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nullable;

/**
 * Writes {@link CompactSpawnLogContext} entries on a dedicated thread, assigning IDs that are
 * consecutive in the order entries appear in the log, as {@link SpawnLogReconstructor} requires.
 *
 * <p>Safe to call from many threads at once, and never blocks them. Entries are appended to a
 * lock-free linked list that the writer thread consumes in order. An entry's ID is derived from
 * its position in that list: each node records how many IDs precede it, and a node is appended by
 * a compare-and-set of its predecessor's {@code next} field from null to the node, which fixes both
 * its place in the log and its ID at once. {@code tail} only records a recent node to start from,
 * so moving it is best effort and any thread may do it. So IDs are consecutive in log order, and an
 * ID only exists once its entry is in the log, which ensures that an entry is written after every
 * entry it references.
 */
final class EntryWriter {

  /**
   * The ID of no entry. In the log, an entry with this ID can't be referenced and a reference with
   * it means "none" (e.g. an empty input set).
   */
  static final int NO_ID = 0;

  /** An entry in the log, serialized without its ID. */
  private static final class Node {
    // Written before the node is published by the compare-and-swap that appends it.
    private byte[] bodyWithoutId;
    private int id;
    // The number of IDs assigned up to and including this node.
    private int idCount;

    private volatile Node next;

    private Node(byte[] bodyWithoutId) {
      this.bodyWithoutId = bodyWithoutId;
    }
  }

  private static final VarHandle NEXT;
  private static final VarHandle TAIL;

  static {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      NEXT = lookup.findVarHandle(Node.class, "next", Node.class);
      TAIL = lookup.findVarHandle(EntryWriter.class, "tail", Node.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  // How long the writer thread waits before checking for new entries again once it has caught up.
  // Waiting with a timeout means appending never needs to wake it up.
  private static final long MIN_IDLE_WAIT_NANOS = TimeUnit.MICROSECONDS.toNanos(50);
  private static final long MAX_IDLE_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

  // The last node appended, or one of its recent predecessors (appenders help it catch up). Only
  // the writer thread knows the start of the list, so that written nodes can be garbage collected.
  //
  // Read through TAIL.getAcquire, since appending only needs acquire ordering (a plain volatile
  // read asks for more). Error Prone can't see VarHandle accesses, so it reports it as unused.
  @SuppressWarnings("unused")
  private volatile Node tail;

  private volatile boolean closed = false;
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final OutputStream out;
  // Buffers writes to out: entries are written in several small pieces, and each write to a
  // compressor is a native call. Only used by the writer thread.
  private final CodedOutputStream coded;
  private final Thread writerThread;

  // The last node the writer thread has written, and the only reference to where it is in the list,
  // so that nodes before it can be garbage collected. A local variable or the thread's Runnable
  // would keep the start of the list reachable for as long as the thread runs. Only used by the
  // writer thread, after its initial value.
  private Node written;

  /** Creates a writer that writes to {@code out} and closes it when {@link #close} is called. */
  EntryWriter(String name, OutputStream out) {
    this.out = out;
    this.coded = CodedOutputStream.newInstance(out, /* bufferSize= */ 64 * 1024);
    Node start = new Node(/* bodyWithoutId= */ null);
    this.tail = start;
    this.written = start;
    this.writerThread = new Thread(this::writeAll, "exec-log-writer:" + name);
    writerThread.start();
  }

  /**
   * Writes an entry that other entries won't reference.
   *
   * <p>Callers must only write it after every entry it references, which holds as long as it's
   * written after the calls that returned their IDs.
   */
  void writeWithoutId(ExecLogEntry.Builder entry) {
    checkArgument(entry.getId() == NO_ID, "entry already has an ID: %s", entry);
    append(entry.build().toByteArray(), /* withId= */ false);
  }

  /**
   * Assigns the next ID to an entry and writes it.
   *
   * @return the ID, which is never {@link #NO_ID}
   */
  int writeWithId(ExecLogEntry.Builder entry) {
    checkArgument(entry.getId() == NO_ID, "entry already has an ID: %s", entry);
    return append(entry.build().toByteArray(), /* withId= */ true);
  }

  private int append(byte[] bodyWithoutId, boolean withId) {
    checkState(!closed, "writing to a closed exec log");
    Node node = new Node(bodyWithoutId);
    while (true) {
      Node last = (Node) TAIL.getAcquire(this);
      Node next = last.next;
      if (next != null) {
        // Another thread appended a node but hasn't advanced the tail yet; help it.
        TAIL.compareAndSet(this, last, next);
        continue;
      }
      // The node isn't visible to other threads until the compare-and-swap below succeeds, so it
      // can be updated freely on every attempt.
      node.idCount = last.idCount + (withId ? 1 : 0);
      node.id = withId ? node.idCount : NO_ID;
      // Linking the node appends it; advancing the tail afterwards is only an optimization.
      if (NEXT.compareAndSet(last, null, node)) {
        TAIL.compareAndSet(this, last, node);
        return node.id;
      }
    }
  }

  /** Runs on the writer thread: writes nodes in list order until closed and caught up. */
  private void writeAll() {
    long idleWaitNanos = MIN_IDLE_WAIT_NANOS;
    try {
      while (true) {
        Node next = written.next;
        if (next == null) {
          if (closed) {
            // Appends happen before close, so anything appended is visible by now.
            next = written.next;
            if (next == null) {
              break;
            }
          } else {
            // Caught up, so there's time to spare: don't leave written entries in the buffer.
            flush();
            // Every entry must be written before close() returns, so interrupts are ignored. The
            // flag is cleared because parkNanos returns immediately while it's set.
            Thread.interrupted();
            LockSupport.parkNanos(this, idleWaitNanos);
            idleWaitNanos = Math.min(idleWaitNanos * 2, MAX_IDLE_WAIT_NANOS);
            continue;
          }
        }
        idleWaitNanos = MIN_IDLE_WAIT_NANOS;
        if (failure.get() == null) {
          try {
            writeDelimited(next);
          } catch (IOException | RuntimeException e) {
            // The log may now end in a partial entry, so later entries are dropped rather than
            // written after it. close() reports the failure.
            failure.compareAndSet(null, e);
          }
        }
        next.bodyWithoutId = null;
        written = next;
      }
    } finally {
      flush();
      try {
        out.close();
      } catch (IOException | RuntimeException e) {
        failure.compareAndSet(null, e);
      }
    }
  }

  /**
   * Writes a node in the same format as {@link ExecLogEntry#writeDelimitedTo}.
   *
   * <p>The entry is written as its ID field followed by the rest of the entry. The ID is field 1,
   * which a full serialization writes first, so this produces exactly the bytes of {@link
   * ExecLogEntry#writeDelimitedTo} for the entry with its ID.
   */
  private void writeDelimited(Node node) throws IOException {
    boolean hasId = node.id != NO_ID;
    int idSize =
        hasId ? CodedOutputStream.computeUInt32Size(ExecLogEntry.ID_FIELD_NUMBER, node.id) : 0;
    coded.writeUInt32NoTag(idSize + node.bodyWithoutId.length);
    if (hasId) {
      coded.writeUInt32(ExecLogEntry.ID_FIELD_NUMBER, node.id);
    }
    coded.writeRawBytes(node.bodyWithoutId);
  }

  /** Writes buffered entries to the output stream, unless a failure means they're dropped. */
  private void flush() {
    if (failure.get() == null) {
      try {
        coded.flush();
      } catch (IOException | RuntimeException e) {
        failure.compareAndSet(null, e);
      }
    }
  }

  @VisibleForTesting
  Object tailForTesting() {
    return tail;
  }

  /**
   * Waits for all entries to be written, then closes the output stream.
   *
   * <p>Must only be called once all writes have returned.
   *
   * @throws IOException if writing or closing failed
   */
  void close() throws IOException {
    closed = true;
    LockSupport.unpark(writerThread);
    Uninterruptibles.joinUninterruptibly(writerThread);
    @Nullable Throwable t = failure.get();
    if (t instanceof IOException e) {
      throw e;
    }
    if (t instanceof RuntimeException e) {
      throw e;
    }
  }
}
