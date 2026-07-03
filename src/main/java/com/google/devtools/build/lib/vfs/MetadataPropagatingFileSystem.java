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

package com.google.devtools.build.lib.vfs;

import java.io.IOException;

/**
 * A {@link FileSystem} that can copy a file whose content is tracked as metadata (e.g. backed by a
 * remote CAS blob) by propagating the metadata instead of moving bytes.
 *
 * <p>Implemented by action filesystems that virtualize remote content under Build without the
 * Bytes, allowing copy-like actions to complete without downloading their input or writing their
 * output.
 */
public interface MetadataPropagatingFileSystem {

  /**
   * Copies the file at {@code source} to {@code target} by propagating its tracked metadata, if
   * the source's content is tracked without local bytes.
   *
   * <p>On success, the target reports the same content metadata (digest, size, remote location) as
   * the source, and no bytes are materialized.
   *
   * @return true if the copy completed as a metadata-only operation; false if the source is backed
   *     by local bytes (or the target cannot hold metadata-only content), in which case the caller
   *     should fall back to a physical copy.
   */
  boolean copyFileByMetadata(PathFragment source, PathFragment target) throws IOException;
}
