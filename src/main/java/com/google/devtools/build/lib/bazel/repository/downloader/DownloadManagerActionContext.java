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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.analysis.actions.DownloadActionContext;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;

/**
 * A {@link DownloadActionContext} backed by the {@link DownloadManager}.
 *
 * <p>Delegating to the {@link DownloadManager} means download actions transparently benefit from
 * the download cache (content-addressed by checksum), {@code --distdir}, URL rewriting, netrc and
 * credential-helper authentication, retries, deduplication of concurrent downloads of identical
 * content (against repository fetches too), and — when configured — the remote downloader (Remote
 * Asset API).
 */
public final class DownloadManagerActionContext implements DownloadActionContext {
  private final DownloadManager downloadManager;
  private final ImmutableMap<String, String> clientEnv;

  public DownloadManagerActionContext(
      DownloadManager downloadManager, Map<String, String> clientEnv) {
    this.downloadManager = downloadManager;
    this.clientEnv = ImmutableMap.copyOf(clientEnv);
  }

  @Override
  public void download(
      ImmutableList<URI> urls, String integrity, String canonicalId, Path output, String context)
      throws IOException, InterruptedException {
    Checksum checksum;
    try {
      checksum = Checksum.fromSubresourceIntegrity(integrity);
    } catch (Checksum.InvalidChecksumException e) {
      throw new IOException(
          String.format("invalid integrity checksum '%s': %s", integrity, e.getMessage()), e);
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
            output,
            clientEnv,
            context,
            new Phaser(),
            /* mayHardlink= */ false);
    Path unused = downloadManager.finalizeDownload(download);
  }
}
