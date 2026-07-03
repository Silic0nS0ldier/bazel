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
package com.google.devtools.build.lib.remote;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.CombinedCache.CachedActionResult;
import com.google.devtools.build.lib.remote.common.ActionKey;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext.CachePolicy;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.runtime.RepositoryCas;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/** The remote package's implementation of {@link RepositoryCas}. */
public class RemoteRepositoryCas implements RepositoryCas {

  /** Bump on backwards-incompatible changes to the extraction entry format or semantics. */
  private static final int EXTRACTION_FORMAT_VERSION = 1;

  private static final UUID GUID = UUID.fromString("8bfb00d2-0b21-4d9a-9e34-6f604c37e2a5");

  private static final Command COMMAND =
      Command.newBuilder()
          // A unique but nonsensical command that is valid on all platforms. It is never executed;
          // the cached "action" stands for an extraction performed by Bazel itself. The empty
          // output path denotes the entire working directory.
          .addArguments(GUID.toString())
          .addOutputPaths("")
          .addOutputDirectories("")
          .setPlatform(Platform.getDefaultInstance())
          .build();

  private final CombinedCache cache;
  private final DigestUtil digestUtil;
  private final String buildRequestId;
  private final String commandId;
  private final boolean acceptCached;
  private final boolean uploadLocalResults;
  private final Action baseAction;

  public RemoteRepositoryCas(
      CombinedCache cache,
      DigestUtil digestUtil,
      String buildRequestId,
      String commandId,
      boolean acceptCached,
      boolean uploadLocalResults) {
    this.cache = cache;
    this.digestUtil = digestUtil;
    this.buildRequestId = buildRequestId;
    this.commandId = commandId;
    this.acceptCached = acceptCached;
    this.uploadLocalResults = uploadLocalResults;
    this.baseAction =
        Utils.buildAction(
            digestUtil.compute(COMMAND),
            digestUtil.compute(Directory.getDefaultInstance()),
            /* platform= */ null,
            /* timeout= */ Duration.ZERO,
            /* cacheable= */ true,
            /* salt= */ null);
  }

  @Override
  public void upload(Path file) throws IOException, InterruptedException {
    RemoteActionExecutionContext context = buildContext("repository_blob", CachePolicy.ANY_CACHE);

    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.UPLOAD_TIME, "upload repository blob")) {
      Digest digest = digestUtil.compute(file);
      ImmutableSet<Digest> missing =
          Utils.getFromFuture(cache.findMissingDigests(context, ImmutableList.of(digest)));
      if (missing.isEmpty()) {
        return;
      }
      Utils.getFromFuture(cache.uploadFile(context, digest, file));
    }
  }

  @Override
  public boolean tryReplayExtraction(ExtractionKey key, Path destination, @Nullable Path preserveEntry)
      throws InterruptedException {
    if (!acceptCached) {
      return false;
    }
    // Cache hits from either the disk or the remote cache are trusted: the disk cache is local,
    // and remote entries can only have been written by a trusted party (see storeExtraction).
    // Write-through of remote hits to the disk cache is intentional.
    RemoteActionExecutionContext context =
        buildContext("repository_extraction", CachePolicy.ANY_CACHE);
    ActionKey actionKey = actionKey(key);
    // Materialize into a temporary sibling first so that a failure (e.g. a partially evicted CAS
    // entry) leaves the destination untouched and the caller can fall back to extracting.
    Path tempDir =
        destination
            .getParentDirectory()
            .getRelative(destination.getBaseName() + ".granular-extract-tmp");
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.REMOTE_CACHE_CHECK, "replay extraction")) {
      CachedActionResult cachedActionResult =
          cache.downloadActionResult(
              context, actionKey, /* inlineOutErr= */ false, /* inlineOutputFiles= */
              ImmutableSet.of());
      if (cachedActionResult == null) {
        return false;
      }
      ActionResult actionResult = cachedActionResult.actionResult();
      if (actionResult.getExitCode() != 0 || actionResult.getOutputDirectoriesCount() != 1) {
        return false;
      }
      byte[] treeBytes =
          Utils.getFromFuture(
              cache.downloadBlob(
                  context,
                  "<extraction tree>",
                  /* execPath= */ null,
                  actionResult.getOutputDirectories(0).getTreeDigest()));
      Tree tree = Tree.parseFrom(treeBytes);
      if (tempDir.exists()) {
        tempDir.deleteTree();
      }
      RepoOutputTreeSyncer.syncFromTree(cache, digestUtil, context, tree, tempDir);
      // Replace the destination contents (which the key guarantees match the pre-extraction
      // state the cached result was derived from) with the cached post-extraction tree.
      destination.createDirectoryAndParents();
      for (Path entry : destination.getDirectoryEntries()) {
        if (!entry.equals(preserveEntry)) {
          if (entry.isDirectory(Symlinks.NOFOLLOW)) {
            entry.deleteTree();
          } else {
            entry.delete();
          }
        }
      }
      for (Path entry : tempDir.getDirectoryEntries()) {
        entry.renameTo(destination.getRelative(entry.getBaseName()));
      }
      tempDir.deleteTree();
      return true;
    } catch (IOException e) {
      try {
        tempDir.deleteTree();
      } catch (IOException cleanupException) {
        // Fall through; the leftover temporary directory is harmless.
      }
      return false;
    }
  }

  @Override
  public void storeExtraction(ExtractionKey key, Path destination)
      throws IOException, InterruptedException {
    // Extraction entries are action-cache-shaped and not self-verifying, so only write them to
    // caches this client is trusted to write action results to: always the disk cache, and the
    // remote cache only when uploading local results is allowed. In locked-down deployments
    // extraction caching thus degrades to the local disk cache.
    RemoteActionExecutionContext context =
        buildContext(
            "repository_extraction",
            uploadLocalResults ? CachePolicy.ANY_CACHE : CachePolicy.DISK_CACHE_ONLY);
    ActionKey actionKey = actionKey(key);
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.UPLOAD_TIME, "store extraction")) {
      var unused =
          UploadManifest.create(
                  cache.getRemoteCacheCapabilities(),
                  digestUtil,
                  RemotePathResolver.createDefault(destination),
                  actionKey,
                  actionWithSalt(key),
                  COMMAND,
                  ImmutableList.of(destination),
                  /* outErr= */ null,
                  /* exitCode= */ 0,
                  /* startTime= */ Instant.now(),
                  /* wallTimeInMs= */ 0,
                  /* preserveExecutableBit= */ true)
              .upload(context, cache, NullEventHandler.INSTANCE);
    } catch (ExecException e) {
      throw new IOException(e);
    }
  }

  private RemoteActionExecutionContext buildContext(String actionId, CachePolicy writePolicy) {
    RequestMetadata metadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, actionId);
    return RemoteActionExecutionContext.create(metadata).withWriteCachePolicy(writePolicy);
  }

  private ActionKey actionKey(ExtractionKey key) {
    return new ActionKey(digestUtil.compute(actionWithSalt(key)));
  }

  private Action actionWithSalt(ExtractionKey key) {
    // The key is embedded in the Action's salt so that the Command message stays constant.
    Fingerprint fingerprint = new Fingerprint();
    fingerprint.addInt(EXTRACTION_FORMAT_VERSION);
    fingerprint.addString(key.archiveHash());
    fingerprint.addString(key.archiveBaseName());
    fingerprint.addString(key.stripPrefix());
    fingerprint.addInt(key.stripComponents());
    fingerprint.addInt(key.renameFiles().size());
    for (Map.Entry<String, String> entry :
        ImmutableSortedMap.copyOf(key.renameFiles()).entrySet()) {
      fingerprint.addString(entry.getKey());
      fingerprint.addString(entry.getValue());
    }
    fingerprint.addString(key.destinationFingerprint());
    return baseAction.toBuilder()
        .setSalt(ByteString.copyFrom(fingerprint.digestAndReset()))
        .build();
  }
}
