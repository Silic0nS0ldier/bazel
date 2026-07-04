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
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.analysis.platform.PlatformUtils;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.CombinedCache.CachedActionResult;
import com.google.devtools.build.lib.remote.common.ActionKey;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext.CachePolicy;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.remote.common.OperationObserver;
import com.google.devtools.build.lib.remote.common.RemoteExecutionClient;
import com.google.devtools.build.lib.remote.merkletree.MerkleTreeComputer;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.runtime.RepositoryCas;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
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

  /**
   * Archive extensions repo-extractor supports. As the tool is a native-image build of Bazel's
   * own decompressors, this is exactly {@code DecompressorValue}'s format table.
   */
  private static final ImmutableList<String> REMOTE_EXTRACTABLE_EXTENSIONS =
      ImmutableList.of(
          ".zip", ".jar", ".war", ".aar", ".nupkg", ".whl", ".tar", ".tar.gz", ".tgz", ".gz",
          ".tar.xz", ".txz", ".xz", ".tar.zst", ".tzst", ".zst", ".tar.bz2", ".tbz", ".bz2",
          ".ar", ".deb", ".7z", ".tar.br", ".br");

  private static final String EXTRACTOR_INPUT_PATH = "extractor";
  private static final String ARCHIVE_INPUT_DIR = "archive";
  private static final String DEST_DIR = "dest";

  private final CombinedCache cache;
  private final DigestUtil digestUtil;
  private final String buildRequestId;
  private final String commandId;
  private final boolean acceptCached;
  private final boolean uploadLocalResults;
  @Nullable private final RemoteExecutionClient remoteExecutor;
  @Nullable private final Path repoExtractor;
  private final String remoteInstanceName;
  private final String workspaceName;
  private final Action baseAction;

  public RemoteRepositoryCas(
      CombinedCache cache,
      DigestUtil digestUtil,
      String buildRequestId,
      String commandId,
      boolean acceptCached,
      boolean uploadLocalResults,
      @Nullable RemoteExecutionClient remoteExecutor,
      @Nullable Path repoExtractor,
      String remoteInstanceName,
      String workspaceName) {
    this.cache = cache;
    this.digestUtil = digestUtil;
    this.buildRequestId = buildRequestId;
    this.commandId = commandId;
    this.acceptCached = acceptCached;
    this.uploadLocalResults = uploadLocalResults;
    this.remoteExecutor = remoteExecutor;
    this.repoExtractor = repoExtractor;
    this.remoteInstanceName = remoteInstanceName;
    this.workspaceName = workspaceName;
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
      // Replace the destination contents (which the key guarantees match the pre-extraction
      // state the cached result was derived from) with the cached post-extraction tree.
      return materializeExtractionResult(context, actionResult, destination, preserveEntry);
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public boolean extractRemotely(
      Path archive,
      Path destination,
      @Nullable Path preserveEntry,
      String stripPrefix,
      int stripComponents,
      ImmutableMap<String, String> renameFiles,
      ImmutableMap<String, String> executionProperties)
      throws InterruptedException {
    if (remoteExecutor == null || repoExtractor == null || !isRemoteExtractable(archive)) {
      return false;
    }
    RemoteActionExecutionContext context =
        buildContext("repository_extraction", CachePolicy.ANY_CACHE);
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.REMOTE_EXECUTION, "extract remotely")) {
      // The action's input tree: the extractor, the archive, and the pre-extraction destination
      // contents (extraction merges into its destination). The working directory is the input
      // root so that it always exists; the extractor creates the destination directory itself.
      ImmutableSortedMap.Builder<PathFragment, Path> inputsBuilder =
          ImmutableSortedMap.naturalOrder();
      inputsBuilder.put(PathFragment.create(EXTRACTOR_INPUT_PATH), repoExtractor);
      String archiveInputPath = ARCHIVE_INPUT_DIR + "/" + archive.getBaseName();
      inputsBuilder.put(PathFragment.create(archiveInputPath), archive);
      if (!collectDestinationFiles(
          destination, PathFragment.create(DEST_DIR), preserveEntry, inputsBuilder)) {
        return false;
      }

      ImmutableList.Builder<String> args = ImmutableList.builder();
      args.add("./" + EXTRACTOR_INPUT_PATH);
      args.add("--archive", archiveInputPath);
      args.add("--dest", DEST_DIR);
      if (!stripPrefix.isEmpty()) {
        args.add("--strip-prefix", stripPrefix);
      }
      if (stripComponents > 0) {
        args.add("--strip-components", Integer.toString(stripComponents));
      }
      for (Map.Entry<String, String> rename :
          ImmutableSortedMap.copyOf(renameFiles).entrySet()) {
        args.add("--rename", rename.getKey() + "=" + rename.getValue());
      }

      Platform platform = PlatformUtils.buildPlatformProto(executionProperties);
      Command.Builder commandBuilder = Command.newBuilder().addAllArguments(args.build());
      if (platform != null) {
        commandBuilder.setPlatform(platform);
      }
      commandBuilder.addOutputPaths(DEST_DIR);
      commandBuilder.addOutputDirectories(DEST_DIR);
      Command command = commandBuilder.build();
      Digest commandDigest = digestUtil.compute(command);
      var merkleTree =
          new MerkleTreeComputer(
                  digestUtil,
                  /* remoteExecutionCache= */ null,
                  buildRequestId,
                  commandId,
                  workspaceName)
              .buildForFiles(inputsBuilder.buildOrThrow());
      Action action =
          Utils.buildAction(
              commandDigest,
              merkleTree.digest(),
              platform,
              /* timeout= */ Duration.ZERO,
              acceptCached,
              /* salt= */ null);
      ActionKey actionKey = new ActionKey(digestUtil.compute(action));

      ActionResult actionResult = null;
      CachedActionResult cachedActionResult =
          cache.downloadActionResult(
              context, actionKey, /* inlineOutErr= */ false, /* inlineOutputFiles= */
              ImmutableSet.of());
      if (cachedActionResult != null) {
        actionResult = cachedActionResult.actionResult();
      }
      if (actionResult == null || actionResult.getExitCode() != 0) {
        Map<Digest, Message> additionalInputs = Maps.newHashMapWithExpectedSize(2);
        additionalInputs.put(digestUtil.compute(action), action);
        additionalInputs.put(commandDigest, command);
        if (!(cache instanceof RemoteExecutionCache remoteExecutionCache)) {
          return false;
        }
        remoteExecutionCache.ensureInputsPresent(
            context, merkleTree, additionalInputs, /* force= */ true, /* remotePathResolver= */
            null);
        ExecuteRequest executeRequest =
            ExecuteRequest.newBuilder()
                .setActionDigest(actionKey.digest())
                .setInstanceName(remoteInstanceName)
                .setDigestFunction(digestUtil.getDigestFunction())
                .setSkipCacheLookup(!acceptCached)
                .build();
        ExecuteResponse response =
            remoteExecutor.executeRemotely(context, executeRequest, OperationObserver.NO_OP);
        actionResult = response.getResult();
        if (actionResult.getExitCode() != 0) {
          return false;
        }
      }
      if (actionResult.getOutputDirectoriesCount() != 1) {
        return false;
      }
      return materializeExtractionResult(context, actionResult, destination, preserveEntry);
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean isRemoteExtractable(Path archive) {
    String name = archive.getBaseName().toLowerCase();
    for (String extension : REMOTE_EXTRACTABLE_EXTENSIONS) {
      if (name.endsWith(extension)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Collects the pre-extraction destination contents as action inputs. Returns false when the
   * state is not representable (special files, dangling symlinks, empty directories).
   */
  private static boolean collectDestinationFiles(
      Path dir,
      PathFragment treePath,
      @Nullable Path preserveEntry,
      ImmutableSortedMap.Builder<PathFragment, Path> inputs)
      throws IOException {
    if (!dir.exists()) {
      return true;
    }
    for (Dirent dirent : dir.readdir(Symlinks.NOFOLLOW)) {
      Path child = dir.getRelative(dirent.getName());
      if (child.equals(preserveEntry)) {
        continue;
      }
      PathFragment childTreePath = treePath.getRelative(dirent.getName());
      switch (dirent.getType()) {
        case FILE -> inputs.put(childTreePath, child);
        case DIRECTORY -> {
          if (!collectDestinationFiles(child, childTreePath, preserveEntry, inputs)) {
            return false;
          }
        }
        case SYMLINK -> {
          Path resolved;
          try {
            resolved = child.resolveSymbolicLinks();
          } catch (IOException e) {
            return false;
          }
          if (resolved.isDirectory()) {
            if (!collectDestinationFiles(resolved, childTreePath, preserveEntry, inputs)) {
              return false;
            }
          } else {
            inputs.put(childTreePath, resolved);
          }
        }
        default -> {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Stages the action's captured destination tree into {@code destination}, replacing its
   * contents (except {@code preserveEntry}), via a temporary sibling directory so that failures
   * leave the destination untouched.
   */
  private boolean materializeExtractionResult(
      RemoteActionExecutionContext context,
      ActionResult actionResult,
      Path destination,
      @Nullable Path preserveEntry)
      throws InterruptedException {
    Path tempDir =
        destination
            .getParentDirectory()
            .getRelative(destination.getBaseName() + ".granular-extract-tmp");
    try {
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
