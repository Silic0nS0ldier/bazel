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

  /**
   * Copies a file whose content is byte-identical to a local file (e.g. a source) that is not
   * tracked as remote content: uploads that content to the CAS (from {@code resolvedSource}, a
   * no-op if already present) and records {@code target} as content-by-digest — without writing the
   * output's bytes to the local output tree.
   *
   * <p>Unlike {@link #copyFileByMetadata}, the digest and size are supplied by the caller (which has
   * them from the input's metadata). This lets {@code ctx.actions.copy} avoid an eager local copy
   * under Build without the Bytes even when copying a local input, while keeping the output a
   * genuine remote-backed artifact whose blob is available to any consumer. (The content upload is
   * eager; deferring it until a remote consumer demands it is a possible future improvement.)
   *
   * @param resolvedSource an absolute, symlink-free path to the file supplying the content
   * @return true if handled; false if {@code target} is not an output this filesystem tracks, in
   *     which case the caller should fall back to a physical copy
   */
  boolean copyFileBackedBySource(
      PathFragment target, byte[] digest, long size, PathFragment resolvedSource)
      throws IOException;

  /**
   * Tree analogue of {@link #copyFileBackedBySource}: recursively copies the local directory at
   * {@code resolvedSource} (e.g. a source directory) to the output tree rooted at {@code target}
   * by uploading each contained file's content to the CAS and recording the corresponding output
   * as content-by-digest — without writing any bytes to the local output tree.
   *
   * <p>Unlike the single-file variant, no digests are supplied: a source directory's tracked
   * metadata is one aggregate fingerprint, so each contained file is hashed here — the same cost a
   * physical copy pays when the copied outputs are digested. Symlinks are dereferenced; a dangling
   * symlink is an error, matching physical tree copying.
   *
   * @param resolvedSource an absolute, symlink-free path to the directory supplying the content
   * @return true if handled; false if {@code target} is not an output this filesystem tracks or
   *     would be materialized locally anyway, in which case the caller should fall back to a
   *     physical copy
   */
  boolean copyTreeBackedBySource(PathFragment target, PathFragment resolvedSource)
      throws IOException;
}
