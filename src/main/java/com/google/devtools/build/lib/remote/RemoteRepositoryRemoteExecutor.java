// Copyright 2019 The Bazel Authors. All rights reserved.
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

import static com.google.devtools.build.lib.remote.util.Utils.buildAction;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
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
import com.google.devtools.build.lib.analysis.platform.PlatformUtils;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.CombinedCache.CachedActionResult;
import com.google.devtools.build.lib.remote.common.ActionKey;
import com.google.devtools.build.lib.remote.common.OperationObserver;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteExecutionClient;
import com.google.devtools.build.lib.remote.merkletree.MerkleTreeComputer;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.protobuf.Message;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.TreeSet;
import javax.annotation.Nullable;

/** The remote package's implementation of {@link RepositoryRemoteExecutor}. */
public class RemoteRepositoryRemoteExecutor implements RepositoryRemoteExecutor {

  private final RemoteExecutionCache remoteCache;
  private final RemoteExecutionClient remoteExecutor;
  private final DigestUtil digestUtil;
  private final String buildRequestId;
  private final String commandId;
  private final String workspaceName;

  private final String remoteInstanceName;
  private final boolean acceptCached;

  public RemoteRepositoryRemoteExecutor(
      RemoteExecutionCache remoteCache,
      RemoteExecutionClient remoteExecutor,
      DigestUtil digestUtil,
      String buildRequestId,
      String commandId,
      String workspaceName,
      String remoteInstanceName,
      boolean acceptCached) {
    this.remoteCache = remoteCache;
    this.remoteExecutor = remoteExecutor;
    this.digestUtil = digestUtil;
    this.buildRequestId = buildRequestId;
    this.commandId = commandId;
    this.workspaceName = workspaceName;
    this.remoteInstanceName = remoteInstanceName;
    this.acceptCached = acceptCached;
  }

  private ExecutionResult downloadOutErr(RemoteActionExecutionContext context, ActionResult result)
      throws IOException, InterruptedException {
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.REMOTE_DOWNLOAD, "download stdout/stderr")) {
      byte[] stdout = new byte[0];
      if (!result.getStdoutRaw().isEmpty()) {
        stdout = result.getStdoutRaw().toByteArray();
      } else if (result.hasStdoutDigest()) {
        stdout =
            Utils.getFromFuture(
                remoteCache.downloadBlob(
                    context, "<stdout>", /* execPath= */ null, result.getStdoutDigest()));
      }

      byte[] stderr = new byte[0];
      if (!result.getStderrRaw().isEmpty()) {
        stderr = result.getStderrRaw().toByteArray();
      } else if (result.hasStderrDigest()) {
        stderr =
            Utils.getFromFuture(
                remoteCache.downloadBlob(
                    context, "<stderr>", /* execPath= */ null, result.getStderrDigest()));
      }

      return new ExecutionResult(result.getExitCode(), stdout, stderr);
    }
  }

  @Override
  public ExecutionResult execute(
      ImmutableList<String> arguments,
      ImmutableSortedMap<PathFragment, Path> inputFiles,
      ImmutableMap<String, String> executionProperties,
      ImmutableMap<String, String> environment,
      String workingDirectory,
      Duration timeout)
      throws IOException, InterruptedException {
    RequestMetadata metadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "repository_rule");
    RemoteActionExecutionContext context = RemoteActionExecutionContext.create(metadata);

    Platform platform = PlatformUtils.buildPlatformProto(executionProperties);

    Command.Builder commandBuilder = Command.newBuilder().addAllArguments(arguments);
    // Sorting the environment pairs by variable name.
    TreeSet<String> variables = new TreeSet<>(environment.keySet());
    for (String var : variables) {
      commandBuilder.addEnvironmentVariablesBuilder().setName(var).setValue(environment.get(var));
    }
    if (platform != null) {
      commandBuilder.setPlatform(platform);
    }
    if (workingDirectory != null) {
      commandBuilder.setWorkingDirectory(workingDirectory);
    }

    Command command = commandBuilder.build();
    Digest commandHash = digestUtil.compute(command);
    var merkleTree =
        new MerkleTreeComputer(
                digestUtil,
                /* remoteExecutionCache= */ null,
                buildRequestId,
                commandId,
                workspaceName)
            .buildForFiles(inputFiles);
    Action action =
        buildAction(
            commandHash, merkleTree.digest(), platform, timeout, acceptCached, /* salt= */ null);
    Digest actionDigest = digestUtil.compute(action);
    ActionKey actionKey = new ActionKey(actionDigest);
    CachedActionResult cachedActionResult;
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.REMOTE_CACHE_CHECK, "check cache hit")) {
      cachedActionResult =
          remoteCache.downloadActionResult(
              context,
              actionKey,
              /* inlineOutErr= */ true,
              /* inlineOutputFiles= */ ImmutableSet.of());
    }
    ActionResult actionResult = null;
    if (cachedActionResult != null) {
      actionResult = cachedActionResult.actionResult();
    }
    if (actionResult == null || actionResult.getExitCode() != 0) {
      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.UPLOAD_TIME, "upload missing inputs")) {
        Map<Digest, Message> additionalInputs = Maps.newHashMapWithExpectedSize(2);
        additionalInputs.put(actionDigest, action);
        additionalInputs.put(commandHash, command);

        remoteCache.ensureInputsPresent(
            context,
            merkleTree,
            additionalInputs,
            /* force= */ true,
            /* remotePathResolver= */ null);
      }

      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.REMOTE_EXECUTION, "execute remotely")) {
        ExecuteRequest executeRequest =
            ExecuteRequest.newBuilder()
                .setActionDigest(actionDigest)
                .setInstanceName(remoteInstanceName)
                .setDigestFunction(digestUtil.getDigestFunction())
                .setSkipCacheLookup(!acceptCached)
                .build();

        ExecuteResponse response =
            remoteExecutor.executeRemotely(context, executeRequest, OperationObserver.NO_OP);
        actionResult = response.getResult();
      }
    }
    return downloadOutErr(context, actionResult);
  }

  /**
   * The path under which the repository directory is staged in the action's input root. Kept out
   * of the input root itself so that auxiliary inputs (files referenced by label arguments) cannot
   * end up inside the captured repository contents.
   */
  private static final String REPO_DIR_INPUT_PATH = "repo";

  @Override
  @Nullable
  public ExecutionResult executeCacheable(
      ImmutableList<String> arguments,
      Path repoDir,
      ImmutableSortedMap<PathFragment, Path> auxiliaryInputs,
      ImmutableMap<String, String> executionProperties,
      ImmutableMap<String, String> environment,
      Duration timeout)
      throws IOException, InterruptedException {
    RequestMetadata metadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "repository_rule");
    RemoteActionExecutionContext context = RemoteActionExecutionContext.create(metadata);

    Platform platform = PlatformUtils.buildPlatformProto(executionProperties);

    Command.Builder commandBuilder = Command.newBuilder().addAllArguments(arguments);
    // Sorting the environment pairs by variable name.
    TreeSet<String> variables = new TreeSet<>(environment.keySet());
    for (String var : variables) {
      commandBuilder.addEnvironmentVariablesBuilder().setName(var).setValue(environment.get(var));
    }
    if (platform != null) {
      commandBuilder.setPlatform(platform);
    }
    commandBuilder.setWorkingDirectory(REPO_DIR_INPUT_PATH);
    // The empty string is an REAPI-documented special value that captures the entire working
    // directory tree, including inputs. This is what makes the repository directory the action's
    // output.
    commandBuilder.addOutputDirectories("");

    Command command = commandBuilder.build();
    Digest commandHash = digestUtil.compute(command);

    ImmutableSortedMap.Builder<PathFragment, Path> inputsBuilder =
        ImmutableSortedMap.naturalOrder();
    PathFragment repoDirInputPath = PathFragment.create(REPO_DIR_INPUT_PATH);
    for (PathFragment auxiliaryInputPath : auxiliaryInputs.keySet()) {
      if (auxiliaryInputPath.startsWith(repoDirInputPath)) {
        // Would collide with the staged repository directory.
        throw new NotCacheableException(
            "input file " + auxiliaryInputPath + " collides with the staged repository directory");
      }
    }
    inputsBuilder.putAll(auxiliaryInputs);
    if (repoDir.isDirectory()) {
      collectRepoFiles(repoDir, repoDirInputPath, inputsBuilder);
    }
    ImmutableSortedMap<PathFragment, Path> inputs = inputsBuilder.buildOrThrow();
    if (inputs.keySet().stream().noneMatch(p -> p.startsWith(repoDirInputPath))) {
      // REAPI requires the working directory to exist in the input tree, which an empty (or not
      // yet created) repository directory would not. TODO(Silic0nS0ldier): support this by staging
      // under the input root once auxiliary input collisions are handled differently.
      throw new NotCacheableException("repository directory is empty");
    }

    var merkleTree =
        new MerkleTreeComputer(
                digestUtil,
                /* remoteExecutionCache= */ null,
                buildRequestId,
                commandId,
                workspaceName)
            .buildForFiles(inputs);
    Action action =
        buildAction(
            commandHash, merkleTree.digest(), platform, timeout, acceptCached, /* salt= */ null);
    Digest actionDigest = digestUtil.compute(action);
    ActionKey actionKey = new ActionKey(actionDigest);
    CachedActionResult cachedActionResult;
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.REMOTE_CACHE_CHECK, "check cache hit")) {
      cachedActionResult =
          remoteCache.downloadActionResult(
              context,
              actionKey,
              /* inlineOutErr= */ true,
              /* inlineOutputFiles= */ ImmutableSet.of());
    }
    ActionResult actionResult = null;
    if (cachedActionResult != null) {
      actionResult = cachedActionResult.actionResult();
    }
    if (actionResult == null || actionResult.getExitCode() != 0) {
      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.UPLOAD_TIME, "upload missing inputs")) {
        Map<Digest, Message> additionalInputs = Maps.newHashMapWithExpectedSize(2);
        additionalInputs.put(actionDigest, action);
        additionalInputs.put(commandHash, command);

        remoteCache.ensureInputsPresent(
            context,
            merkleTree,
            additionalInputs,
            /* force= */ true,
            /* remotePathResolver= */ null);
      }

      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.REMOTE_EXECUTION, "execute remotely")) {
        ExecuteRequest executeRequest =
            ExecuteRequest.newBuilder()
                .setActionDigest(actionDigest)
                .setInstanceName(remoteInstanceName)
                .setDigestFunction(digestUtil.getDigestFunction())
                .setSkipCacheLookup(!acceptCached)
                .build();

        ExecuteResponse response =
            remoteExecutor.executeRemotely(context, executeRequest, OperationObserver.NO_OP);
        actionResult = response.getResult();
      }
    }
    stageRepoOutputs(context, actionResult, repoDir);
    return downloadOutErr(context, actionResult);
  }

  /**
   * Collects the files of the repository directory for staging in the action's input tree.
   *
   * <p>Symlinks are followed, staging the target's contents; the command observes the same file
   * contents either way. TODO(Silic0nS0ldier): preserve symlinks in the input tree.
   *
   * <p>Empty directories are not representable in the file-based input tree, but commands do
   * observe them (e.g. `tar -C <empty dir>` after a preceding `mkdir` operation, as done by
   * rules_js's npm_import), so their presence makes the state not cacheable.
   * TODO(Silic0nS0ldier): support empty directories in the input tree.
   */
  private static void collectRepoFiles(
      Path dir, PathFragment treePath, ImmutableSortedMap.Builder<PathFragment, Path> inputs)
      throws IOException {
    boolean empty = true;
    for (Dirent dirent : dir.readdir(Symlinks.NOFOLLOW)) {
      empty = false;
      Path child = dir.getRelative(dirent.getName());
      PathFragment childTreePath = treePath.getRelative(dirent.getName());
      switch (dirent.getType()) {
        case FILE -> inputs.put(childTreePath, child);
        case DIRECTORY -> collectRepoFiles(child, childTreePath, inputs);
        case SYMLINK -> {
          Path resolved;
          try {
            resolved = child.resolveSymbolicLinks();
          } catch (IOException e) {
            throw new NotCacheableException(
                "unresolvable symlink in repository directory: " + child + ": " + e.getMessage());
          }
          if (resolved.isDirectory()) {
            collectRepoFiles(resolved, childTreePath, inputs);
          } else {
            inputs.put(childTreePath, resolved);
          }
        }
        default ->
            throw new NotCacheableException("special file in repository directory: " + child);
      }
    }
    if (empty && !treePath.equals(PathFragment.create(REPO_DIR_INPUT_PATH))) {
      throw new NotCacheableException("empty directory in repository directory: " + dir);
    }
  }

  /**
   * Replaces the contents of {@code repoDir} with the repository directory state captured by the
   * action.
   */
  private void stageRepoOutputs(
      RemoteActionExecutionContext context, ActionResult actionResult, Path repoDir)
      throws IOException, InterruptedException {
    if (actionResult.getOutputDirectoriesCount() != 1) {
      if (actionResult.getExitCode() != 0) {
        // Failed actions may not capture outputs. Unlike local execution, side effects of the
        // failed command are not observable in the repository directory in that case.
        return;
      }
      throw new IOException(
          "expected exactly one output directory in the action result, got "
              + actionResult.getOutputDirectoriesCount());
    }
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.REMOTE_DOWNLOAD, "download repo directory")) {
      byte[] treeBytes =
          Utils.getFromFuture(
              remoteCache.downloadBlob(
                  context,
                  "<output tree>",
                  /* execPath= */ null,
                  actionResult.getOutputDirectories(0).getTreeDigest()));
      RepoOutputTreeSyncer.syncFromTree(
          remoteCache, digestUtil, context, Tree.parseFrom(treeBytes), repoDir);
    }
  }
}
