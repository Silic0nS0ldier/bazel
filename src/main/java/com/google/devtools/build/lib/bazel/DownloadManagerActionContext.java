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
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionProgressEvent;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.analysis.actions.DownloadActionContext;
import com.google.devtools.build.lib.bazel.bzlmod.VendorManager;
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
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
 *
 * <p>Fetch progress is reported on the download action's own progress line, mirroring how
 * repository fetches report the URL currently being attempted.
 */
public final class DownloadManagerActionContext implements DownloadActionContext {

  // A single progress id: the action's progress line updates in place as attempts move between
  // URLs (mirror fallback, retries).
  private static final String PROGRESS_ID = "download";

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
      ActionExecutionMetadata action,
      ActionExecutionContext actionExecutionContext)
      throws IOException, InterruptedException {
    Checksum checksum;
    try {
      checksum = Checksum.fromSubresourceIntegrity(integrity);
    } catch (Checksum.InvalidChecksumException e) {
      throw new IOException(
          String.format("invalid integrity checksum '%s': %s", integrity, e.getMessage()), e);
    }
    Path outputPath = actionExecutionContext.getInputPath(action.getPrimaryOutput());
    if (resolveFromVendorDirectory(checksum, outputPath)) {
      return;
    }
    ExtendedEventHandler progressEventHandler =
        progressBridge(action, actionExecutionContext.getEventHandler());
    if (resolveToRemoteDigest(
        urls, checksum, canonicalId, action, actionExecutionContext, progressEventHandler)) {
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
            /* context= */ action.getOwner().getLabel().toString(),
            new Phaser(),
            /* mayHardlink= */ false,
            progressEventHandler);
    Path unused = downloadManager.finalizeDownload(download);
  }

  /**
   * Returns an event handler that renders fetch progress on the given action's own progress line
   * and forwards everything else (warnings, BEP fetch events) unchanged.
   *
   * <p>This mirrors repository fetch reporting: the line names the URL currently being attempted
   * and updates in place across mirror fallback and retries. The translation is UI-only; BEP
   * continues to receive one event per fetch attempt ({@code FetchEvent}) and one event per action
   * execution, since its action model has no notion of a retry.
   */
  private static ExtendedEventHandler progressBridge(
      ActionExecutionMetadata action, ExtendedEventHandler delegate) {
    return new ExtendedEventHandler() {
      @Override
      public void handle(Event event) {
        delegate.handle(event);
      }

      @Override
      public void post(Postable postable) {
        if (postable instanceof FetchProgress progress) {
          String message =
              progress.getProgress().isEmpty()
                  ? progress.getResourceIdentifier()
                  : progress.getResourceIdentifier() + "; " + progress.getProgress();
          delegate.post(
              ActionProgressEvent.create(action, PROGRESS_ID, message, progress.isFinished()));
        } else {
          delegate.post(postable);
        }
      }
    };
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
      ActionExecutionMetadata action,
      ActionExecutionContext actionExecutionContext,
      ExtendedEventHandler progressEventHandler)
      throws InterruptedException {
    if (digestOnlyDownloader == null) {
      return false;
    }
    Artifact output = action.getPrimaryOutput();
    Digest digest;
    try {
      digest =
          digestOnlyDownloader.fetchBlobDigest(
              urls,
              Optional.of(checksum),
              canonicalId,
              progressEventHandler,
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
