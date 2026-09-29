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

import com.google.devtools.build.lib.exec.Protos.ExecLogEntry;
import com.google.devtools.build.lib.util.io.AsynchronousMessageOutputStream;
import com.google.devtools.build.lib.util.io.AsynchronousMessageOutputStream.PreparedMessage;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.concurrent.GuardedBy;

/**
 * Writes {@link CompactSpawnLogContext} entries, assigning IDs that are consecutive in the order
 * entries appear in the log, as {@link SpawnLogReconstructor} requires.
 *
 * <p>Safe to call from many threads at once. Assigning an ID and handing the entry to the output
 * stream must happen atomically, so they share a lock; everything else, including serialization,
 * happens outside it, so that the lock stays cheap to hold even for very large entries.
 */
final class EntryWriter {

  /**
   * The ID of no entry. In the log, an entry with this ID can't be referenced and a reference with
   * it means "none" (e.g. an empty input set).
   */
  static final int NO_ID = 0;

  private final AsynchronousMessageOutputStream<ExecLogEntry> out;

  @GuardedBy("this")
  private int nextId = 1;

  EntryWriter(AsynchronousMessageOutputStream<ExecLogEntry> out) {
    this.out = out;
  }

  /**
   * Writes an entry that other entries won't reference.
   *
   * <p>Doesn't take the lock: such an entry doesn't affect ID assignment, and callers only write it
   * after every entry it references.
   */
  void writeWithoutId(ExecLogEntry.Builder entry) {
    checkArgument(entry.getId() == NO_ID, "entry already has an ID: %s", entry);
    out.write(entry.build());
  }

  /**
   * Assigns the next ID to an entry and writes it.
   *
   * @return the ID, which is never {@link #NO_ID}
   */
  int writeWithId(ExecLogEntry.Builder entry) {
    checkArgument(entry.getId() == NO_ID, "entry already has an ID: %s", entry);
    // Serialize the (possibly very large) entry outside the lock.
    byte[] bodyWithoutId = entry.build().toByteArray();
    synchronized (this) {
      int id = nextId++;
      out.writePrepared(new EntryWithId(id, bodyWithoutId));
      return id;
    }
  }

  /**
   * An entry serialized without its ID, which is added when it's written.
   *
   * <p>This relies on serialized protocol buffer messages merging when written back to back: the
   * entry is written as a message holding only the ID followed by the rest of the entry. The two
   * set disjoint fields, so the result is exactly as long as serializing the entry with its ID.
   */
  private record EntryWithId(int id, byte[] bodyWithoutId)
      implements PreparedMessage<ExecLogEntry> {
    @Override
    public void writeDelimitedTo(OutputStream out) throws IOException {
      byte[] idPart = ExecLogEntry.newBuilder().setId(id).build().toByteArray();
      CodedOutputStream cos = CodedOutputStream.newInstance(out, /* bufferSize= */ 16);
      cos.writeUInt32NoTag(idPart.length + bodyWithoutId.length);
      cos.writeRawBytes(idPart);
      cos.flush();
      out.write(bodyWithoutId);
    }
  }

  /** Closes the output stream once all pending writes have completed. */
  void close() throws IOException {
    out.close();
  }
}
