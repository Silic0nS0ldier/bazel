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
package com.google.devtools.build.lib.bazel;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.analysis.actions.DownloadActionContext;
import com.google.devtools.build.lib.bazel.bzlmod.VendorManager;
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.remote.downloader.GrpcRemoteDownloader;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import javax.annotation.Nullable;

/**
 * A {@link DownloadActionContext} backed by the vendor directory's download store, the Remote
 * Asset API, and the {@link DownloadManager}.
 *
 * <p>Resolution follows the cheapest path first: the content-addressed vendor store (when {@code
 * --vendor_dir} is set), then — under Build without the Bytes with a remote downloader configured
 * — a digest-only fetch through the Remote Asset API that never materializes the content locally,
 * then the {@link DownloadManager}, which consults the download cache and {@code --distdir}
 * before the network. Delegating to the {@link DownloadManager} means download actions
 * transparently benefit from URL rewriting, netrc and credential-helper authentication, retries,
 * deduplication of concurrent downloads of identical content (against repository fetches too),
 * and — when configured — the remote downloader (Remote Asset API).
 */
public final class DownloadManagerActionContext implements DownloadActionContext {
  private final DownloadManager downloadManager;
  private final ImmutableMap<String, String> clientEnv;
  @Nullable private final VendorManager vendorManager;

  // Non-null only when a remote downloader is configured and outputs need not be materialized
  // locally (--remote_download_outputs=minimal): resolution then completes with metadata only.
  @Nullable private final GrpcRemoteDownloader digestOnlyDownloader;

  public DownloadManagerActionContext(
      DownloadManager downloadManager,
      Map<String, String> clientEnv,
      Optional<Path> vendorDirectory,
      @Nullable GrpcRemoteDownloader digestOnlyDownloader) {
    this.downloadManager = downloadManager;
    this.clientEnv = ImmutableMap.copyOf(clientEnv);
    this.vendorManager = vendorDirectory.map(VendorManager::new).orElse(null);
    this.digestOnlyDownloader = digestOnlyDownloader;
  }

  @Override
  public void download(
      ImmutableList<URI> urls,
      String integrity,
      String canonicalId,
      boolean executable,
      Artifact output,
      ActionExecutionContext actionExecutionContext)
      throws IOException, InterruptedException {
    Checksum checksum;
    try {
      checksum = Checksum.fromSubresourceIntegrity(integrity);
    } catch (Checksum.InvalidChecksumException e) {
      throw new IOException(
          String.format("invalid integrity checksum '%s': %s", integrity, e.getMessage()), e);
    }
    Path outputPath = actionExecutionContext.getInputPath(output);
    if (resolveFromVendorDirectory(checksum, outputPath)) {
      return;
    }
    if (resolveToRemoteDigest(urls, checksum, canonicalId, output, actionExecutionContext)) {
      return;
    }
    // The calling action already runs on an execution-phase thread, so the download runs
    // synchronously on it; overall parallelism is bounded by action execution parallelism and the
    // downloader's own connection limits.
    Future<Path> download =
        downloadManager.startDownload(
            MoreExecutors.newDirectExecutorService(),
            urls,
            /* headers= */ ImmutableMap.of(),
            /* authHeaders= */ ImmutableMap.of(),
            Optional.of(checksum),
            canonicalId,
            /* type= */ Optional.empty(),
            outputPath,
            clientEnv,
            /* context= */ output.getOwnerLabel().toString(),
            new Phaser(),
            /* mayHardlink= */ false);
    Path unused = downloadManager.finalizeDownload(download);
  }

  /**
   * Attempts to resolve the download to a remote-only file: a {@code FetchBlob} call places the
   * content in the remote CAS and returns its digest, which is injected as the output's metadata
   * without the bytes ever reaching the local machine (Build without the Bytes).
   *
   * <p>Returns true on success. Failure falls back to a materializing download: the Remote Asset
   * service is an optimisation, not a correctness requirement.
   */
  private boolean resolveToRemoteDigest(
      ImmutableList<URI> urls,
      Checksum checksum,
      String canonicalId,
      Artifact output,
      ActionExecutionContext actionExecutionContext)
      throws InterruptedException {
    if (digestOnlyDownloader == null) {
      return false;
    }
    Digest digest;
    try {
      digest =
          digestOnlyDownloader.fetchBlobDigest(
              urls,
              Optional.of(checksum),
              canonicalId,
              actionExecutionContext.getEventHandler(),
              /* context= */ output.getOwnerLabel().toString());
    } catch (IOException e) {
      actionExecutionContext
          .getEventHandler()
          .handle(
              Event.warn(
                  String.format(
                      "Remote Asset fetch of %s failed, falling back to a local download: %s",
                      output.getOwnerLabel(), e.getMessage())));
      return false;
    }
    actionExecutionContext
        .getOutputMetadataStore()
        .injectFile(
            output,
            FileArtifactValue.createForRemoteFileWithMaterializationData(
                DigestUtil.toBinaryDigest(digest),
                digest.getSizeBytes(),
                /* locationIndex= */ 1,
                /* expirationTime= */ null));
    return true;
  }

  /**
   * Attempts to resolve the download from the vendor directory's content-addressed store.
   *
   * <p>Returns true on success. The store is keyed by content, but the copy is verified against
   * the checksum anyway to guard against a corrupted store.
   */
  private boolean resolveFromVendorDirectory(Checksum checksum, Path outputPath)
      throws IOException, InterruptedException {
    if (vendorManager == null) {
      return false;
    }
    Path vendored = vendorManager.lookupDownload(checksum);
    if (vendored == null) {
      return false;
    }
    Path parent = outputPath.getParentDirectory();
    if (parent != null) {
      parent.createDirectoryAndParents();
    }
    FileSystemUtils.copyFile(vendored, outputPath);
    String actual = DownloadCache.getChecksum(checksum.getKeyType(), outputPath);
    if (!actual.equals(checksum.toString())) {
      throw new IOException(
          String.format(
              "vendored download %s is corrupt: content hashes to %s but the store entry claims"
                  + " %s; delete it and re-run `bazel vendor`",
              vendored, actual, checksum));
    }
    return true;
  }
}
