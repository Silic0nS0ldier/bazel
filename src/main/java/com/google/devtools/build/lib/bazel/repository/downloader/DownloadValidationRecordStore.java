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

package com.google.devtools.build.lib.bazel.repository.downloader;

import java.io.IOException;

/**
 * A shared, content-addressed store of download validation records.
 *
 * <p>Records are small deterministic documents (see {@link DownloadValidator#recordDocument});
 * presence of a record's blob means the (URL, checksum) pair it describes has been validated. This
 * is implemented against the disk/remote CAS: content-addressed writes are legal even in
 * deployments that forbid client action cache writes.
 */
public interface DownloadValidationRecordStore {

  /**
   * Returns whether the record exists in the store. Availability failures report {@code false}:
   * the safe consequence is revalidation, never a skipped validation.
   */
  boolean hasRecord(String recordDocument) throws InterruptedException;

  /** Inserts the record. */
  void putRecord(String recordDocument) throws IOException, InterruptedException;
}
