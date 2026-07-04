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
package com.google.devtools.build.lib.runtime;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * Interface for caching granular repository operation results in the disk/remote cache as part of
 * {@code --experimental_granular_repository_caching}.
 *
 * <p>Unlike action cache entries, CAS entries are self-verifying (content-addressed), so clients
 * may insert them even in deployments where clients are not trusted to write action results. This
 * is used to prime the CAS with verified downloads and known file writes so that subsequent
 * cacheable repository actions do not need to upload them and repository contents can eventually
 * be reconstructed without refetching.
 *
 * <p>Extraction results, by contrast, are recorded as action cache entries for a synthetic action
 * (they map an archive digest plus extraction parameters to an output tree, which is not
 * self-verifying). Implementations must therefore only write them to caches the client is trusted
 * to write action results to: always the disk cache, and the remote cache only when the client is
 * allowed to upload local results. In locked-down deployments extraction caching thus degrades to
 * the local disk cache.
 */
public interface RepositoryCas {

  /**
   * Inserts the given file's content into the CAS if not already present. Blocks until the upload
   * completes.
   *
   * <p>Callers are responsible for only uploading content whose integrity has been established
   * (e.g. a download verified against a user-provided checksum, or content produced locally).
   */
  void upload(Path file) throws IOException, InterruptedException;

  /**
   * Identifies a cacheable extraction: the archive content, all parameters influencing the
   * extraction result, and the state of the destination directory before extraction (extraction
   * merges into its destination, so the cached result is only valid for an identical pre-state).
   *
   * @param archiveHash hash of the archive contents, prefixed with the hash type (e.g. {@code
   *     "sha256:..."}).
   * @param archiveBaseName the archive file name, which drives decompressor selection.
   * @param destinationFingerprint fingerprint of the destination directory contents before
   *     extraction (paths, types, file digests, symlink targets, executable bits).
   */
  record ExtractionKey(
      String archiveHash,
      String archiveBaseName,
      String stripPrefix,
      int stripComponents,
      ImmutableMap<String, String> renameFiles,
      String destinationFingerprint) {}

  /**
   * Attempts to replay a previously cached extraction by replacing the contents of {@code
   * destination} (whose current state must match the key's destination fingerprint) with the
   * cached post-extraction tree. {@code preserveEntry}, if given, names a direct entry of the
   * destination to leave in place (e.g. a temporary download directory). Returns whether the
   * replay succeeded; on failure, {@code destination} is left untouched and the caller should
   * perform the extraction itself.
   */
  boolean tryReplayExtraction(ExtractionKey key, Path destination, @Nullable Path preserveEntry)
      throws InterruptedException;

  /**
   * Records a completed extraction, keyed by {@code key} (whose destination fingerprint must
   * describe the pre-extraction state), with the post-extraction destination contents rooted at
   * {@code destination} as the result.
   */
  void storeExtraction(ExtractionKey key, Path destination) throws IOException, InterruptedException;

  /**
   * Attempts to perform the extraction as a remote execution action, using the extraction utility
   * bundled with Bazel and assuming the default remote platform matches the host OS. The action's
   * inputs are the extractor, the archive and the pre-extraction destination contents; its output
   * is the post-extraction destination, which is staged into {@code destination} on success
   * (replacing its contents, except {@code preserveEntry}). The action cache entry is produced by
   * the remote execution service, never the client, so this works in deployments where clients
   * may not upload action results.
   *
   * <p>Returns whether the extraction was performed (or replayed from the cache); on false, the
   * destination is left untouched and the caller should extract by other means.
   */
  boolean extractRemotely(
      Path archive,
      Path destination,
      @Nullable Path preserveEntry,
      String stripPrefix,
      int stripComponents,
      ImmutableMap<String, String> renameFiles,
      ImmutableMap<String, String> executionProperties)
      throws InterruptedException;
}
