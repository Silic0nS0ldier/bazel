// Copyright 2020 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.OutputDirectory;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.util.concurrent.Futures;
import com.google.devtools.build.lib.remote.CombinedCache.CachedActionResult;
import com.google.devtools.build.lib.remote.common.RemoteExecutionClient;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor.ExecutionResult;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor.NotCacheableException;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link com.google.devtools.build.lib.remote.RemoteRepositoryRemoteExecutor}. */
@RunWith(JUnit4.class)
public class RemoteRepositoryRemoteExecutorTest {
  public static final DigestUtil DIGEST_UTIL =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);

  @Mock public RemoteExecutionCache remoteCache;

  @Mock public RemoteExecutionClient remoteExecutor;

  private RemoteRepositoryRemoteExecutor repoExecutor;

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
    repoExecutor =
        new RemoteRepositoryRemoteExecutor(
            remoteCache,
            remoteExecutor,
            DIGEST_UTIL,
            "none",
            "none",
            TestConstants.WORKSPACE_NAME,
            /* remoteInstanceName= */ "foo",
            /* acceptCached= */ true);
  }

  @Test
  public void testZeroExitCodeFromCache() throws IOException, InterruptedException {
    // Test that an ActionResult with exit code zero is accepted as cached.

    ActionResult cachedResult = ActionResult.newBuilder().setExitCode(0).build();
    when(remoteCache.downloadActionResult(
            any(),
            any(),
            /* inlineOutErr= */ eq(true),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(CachedActionResult.remote(cachedResult));

    ExecutionResult executionResult =
        repoExecutor.execute(
            ImmutableList.of("/bin/bash", "-c", "exit 0"),
            /* inputFiles= */ ImmutableSortedMap.of(),
            /* executionProperties= */ ImmutableMap.of(),
            /* environment= */ ImmutableMap.of(),
            /* workingDirectory= */ null,
            /* timeout= */ Duration.ZERO);

    verify(remoteCache)
        .downloadActionResult(
            any(), any(), anyBoolean(), /* inlineOutputFiles= */ eq(ImmutableSet.of()));
    // Don't fallback to execution
    verify(remoteExecutor, never()).executeRemotely(any(), any(), any());

    assertThat(executionResult.exitCode()).isEqualTo(0);
  }

  @Test
  public void testNoneZeroExitCodeFromCache() throws IOException, InterruptedException {
    // Test that an ActionResult with a none-zero exit code is not accepted as cached.

    ActionResult cachedResult = ActionResult.newBuilder().setExitCode(1).build();
    when(remoteCache.downloadActionResult(
            any(),
            any(),
            /* inlineOutErr= */ eq(true),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(CachedActionResult.remote(cachedResult));

    ExecuteResponse response = ExecuteResponse.newBuilder().setResult(cachedResult).build();
    when(remoteExecutor.executeRemotely(any(), any(), any())).thenReturn(response);

    ExecutionResult executionResult =
        repoExecutor.execute(
            ImmutableList.of("/bin/bash", "-c", "exit 1"),
            /* inputFiles= */ ImmutableSortedMap.of(),
            /* executionProperties= */ ImmutableMap.of(),
            /* environment= */ ImmutableMap.of(),
            /* workingDirectory= */ null,
            /* timeout= */ Duration.ZERO);

    verify(remoteCache)
        .downloadActionResult(
            any(), any(), anyBoolean(), /* inlineOutputFiles= */ eq(ImmutableSet.of()));
    // Fallback to execution
    verify(remoteExecutor).executeRemotely(any(), any(), any());

    assertThat(executionResult.exitCode()).isEqualTo(1);
  }

  @Test
  public void testInlineStdoutStderr() throws IOException, InterruptedException {
    // Test that inline stdout/stderr responses are returned in execution results.

    byte[] stdout = "hello".getBytes(StandardCharsets.UTF_8);
    byte[] stderr = "world".getBytes(StandardCharsets.UTF_8);
    ActionResult cachedResult =
        ActionResult.newBuilder()
            .setExitCode(0)
            .setStdoutRaw(ByteString.copyFrom(stdout))
            .setStderrRaw(ByteString.copyFrom(stderr))
            .build();
    when(remoteCache.downloadActionResult(
            any(),
            any(),
            /* inlineOutErr= */ eq(true),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(CachedActionResult.remote(cachedResult));

    ExecuteResponse response = ExecuteResponse.newBuilder().setResult(cachedResult).build();
    when(remoteExecutor.executeRemotely(any(), any(), any())).thenReturn(response);

    ExecutionResult executionResult =
        repoExecutor.execute(
            ImmutableList.of("/bin/bash", "-c", "echo hello"),
            /* inputFiles= */ ImmutableSortedMap.of(),
            /* executionProperties= */ ImmutableMap.of(),
            /* environment= */ ImmutableMap.of(),
            /* workingDirectory= */ null,
            /* timeout= */ Duration.ZERO);

    verify(remoteCache)
        .downloadActionResult(
            any(),
            any(),
            /* inlineOutErr= */ eq(true),
            /* inlineOutputFiles= */ eq(ImmutableSet.of()));

    assertThat(executionResult.exitCode()).isEqualTo(0);
    assertThat(executionResult.stdout()).isEqualTo(stdout);
    assertThat(executionResult.stderr()).isEqualTo(stderr);
  }

  @Test
  public void executeCacheable_cacheHit_stagesRepoOutputs()
      throws IOException, InterruptedException {
    InMemoryFileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path repoDir = fs.getPath("/repo");
    repoDir.createDirectoryAndParents();
    FileSystemUtils.writeContent(repoDir.getRelative("deleted.txt"), "gone".getBytes(UTF_8));
    FileSystemUtils.writeContent(repoDir.getRelative("same.txt"), "same".getBytes(UTF_8));

    Map<Digest, byte[]> blobs = new HashMap<>();
    Digest newDigest = digestOf(blobs, "new");
    Digest sameDigest = digestOf(blobs, "same");
    Digest keepDigest = digestOf(blobs, "keep");
    Directory subDir =
        Directory.newBuilder()
            .addFiles(FileNode.newBuilder().setName("keep.txt").setDigest(keepDigest))
            .build();
    Tree tree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(FileNode.newBuilder().setName("new.txt").setDigest(newDigest))
                    .addFiles(FileNode.newBuilder().setName("same.txt").setDigest(sameDigest))
                    .addDirectories(
                        DirectoryNode.newBuilder()
                            .setName("sub")
                            .setDigest(DIGEST_UTIL.compute(subDir))))
            .addChildren(subDir)
            .build();
    Digest treeDigest = DIGEST_UTIL.compute(tree);

    ActionResult cachedResult =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputDirectories(
                OutputDirectory.newBuilder().setPath("").setTreeDigest(treeDigest))
            .build();
    when(remoteCache.downloadActionResult(
            any(),
            any(),
            /* inlineOutErr= */ eq(true),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(CachedActionResult.remote(cachedResult));
    when(remoteCache.downloadBlob(any(), any(), any(), eq(treeDigest)))
        .thenReturn(Futures.immediateFuture(tree.toByteArray()));
    when(remoteCache.downloadFile(any(), any(Path.class), any(Digest.class)))
        .thenAnswer(
            invocation -> {
              FileSystemUtils.writeContent(
                  invocation.getArgument(1), blobs.get(invocation.getArgument(2)));
              return Futures.immediateFuture(null);
            });

    ExecutionResult executionResult =
        repoExecutor.executeCacheable(
            ImmutableList.of("/bin/bash", "-c", "true"),
            repoDir,
            /* auxiliaryInputs= */ ImmutableSortedMap.of(),
            /* executionProperties= */ ImmutableMap.of(),
            /* environment= */ ImmutableMap.of(),
            /* timeout= */ Duration.ZERO);

    // Don't fall back to execution.
    verify(remoteExecutor, never()).executeRemotely(any(), any(), any());
    assertThat(executionResult.exitCode()).isEqualTo(0);
    // The repository directory now matches the captured output tree.
    assertThat(readContent(repoDir.getRelative("new.txt"))).isEqualTo("new");
    assertThat(readContent(repoDir.getRelative("same.txt"))).isEqualTo("same");
    assertThat(readContent(repoDir.getRelative("sub/keep.txt"))).isEqualTo("keep");
    assertThat(repoDir.getRelative("deleted.txt").exists()).isFalse();
    // Unchanged files aren't re-downloaded.
    verify(remoteCache, never())
        .downloadFile(any(), eq(repoDir.getRelative("same.txt")), any(Digest.class));
  }

  @Test
  public void executeCacheable_cacheMiss_executesRemotelyAndStagesRepoOutputs()
      throws IOException, InterruptedException {
    InMemoryFileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path repoDir = fs.getPath("/repo");
    repoDir.createDirectoryAndParents();
    FileSystemUtils.writeContent(repoDir.getRelative("input.txt"), "input".getBytes(UTF_8));

    Map<Digest, byte[]> blobs = new HashMap<>();
    Digest outputDigest = digestOf(blobs, "output");
    Tree tree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(FileNode.newBuilder().setName("output.txt").setDigest(outputDigest)))
            .build();
    Digest treeDigest = DIGEST_UTIL.compute(tree);

    ActionResult actionResult =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputDirectories(
                OutputDirectory.newBuilder().setPath("").setTreeDigest(treeDigest))
            .build();
    when(remoteCache.downloadActionResult(
            any(),
            any(),
            /* inlineOutErr= */ eq(true),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(null);
    when(remoteExecutor.executeRemotely(any(), any(), any()))
        .thenReturn(ExecuteResponse.newBuilder().setResult(actionResult).build());
    when(remoteCache.downloadBlob(any(), any(), any(), eq(treeDigest)))
        .thenReturn(Futures.immediateFuture(tree.toByteArray()));
    when(remoteCache.downloadFile(any(), any(Path.class), any(Digest.class)))
        .thenAnswer(
            invocation -> {
              FileSystemUtils.writeContent(
                  invocation.getArgument(1), blobs.get(invocation.getArgument(2)));
              return Futures.immediateFuture(null);
            });

    ExecutionResult executionResult =
        repoExecutor.executeCacheable(
            ImmutableList.of("/bin/bash", "-c", "true"),
            repoDir,
            /* auxiliaryInputs= */ ImmutableSortedMap.of(),
            /* executionProperties= */ ImmutableMap.of(),
            /* environment= */ ImmutableMap.of(),
            /* timeout= */ Duration.ZERO);

    verify(remoteCache).ensureInputsPresent(any(), any(), any(), anyBoolean(), any());
    verify(remoteExecutor).executeRemotely(any(), any(), any());
    assertThat(executionResult.exitCode()).isEqualTo(0);
    assertThat(readContent(repoDir.getRelative("output.txt"))).isEqualTo("output");
    // The action captured the whole repository directory, so files absent from the output tree
    // were deleted by the command.
    assertThat(repoDir.getRelative("input.txt").exists()).isFalse();
  }

  @Test
  public void executeCacheable_emptyRepoDir_isNotCacheable() throws IOException {
    InMemoryFileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path repoDir = fs.getPath("/repo");
    repoDir.createDirectoryAndParents();

    assertThrows(
        NotCacheableException.class,
        () ->
            repoExecutor.executeCacheable(
                ImmutableList.of("/bin/bash", "-c", "true"),
                repoDir,
                /* auxiliaryInputs= */ ImmutableSortedMap.of(),
                /* executionProperties= */ ImmutableMap.of(),
                /* environment= */ ImmutableMap.of(),
                /* timeout= */ Duration.ZERO));
  }

  @Test
  public void executeCacheable_emptySubdirectory_isNotCacheable() throws IOException {
    // Commands observe empty directories (e.g. `tar -C <dir>` after a preceding `mkdir`
    // operation, as done by rules_js's npm_import), but the file-based input tree cannot
    // represent them.
    InMemoryFileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path repoDir = fs.getPath("/repo");
    repoDir.createDirectoryAndParents();
    FileSystemUtils.writeContent(repoDir.getRelative("archive.tgz"), "gz".getBytes(UTF_8));
    repoDir.getRelative("package").createDirectoryAndParents();

    assertThrows(
        NotCacheableException.class,
        () ->
            repoExecutor.executeCacheable(
                ImmutableList.of("tar", "-xf", "archive.tgz", "-C", "package"),
                repoDir,
                /* auxiliaryInputs= */ ImmutableSortedMap.of(),
                /* executionProperties= */ ImmutableMap.of(),
                /* environment= */ ImmutableMap.of(),
                /* timeout= */ Duration.ZERO));
  }

  @Test
  public void executeCacheable_missingRepoDir_isNotCacheable() {
    // When execute() is the first operation of a repo rule, the repository directory has not been
    // created yet.
    InMemoryFileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path repoDir = fs.getPath("/repo");

    assertThrows(
        NotCacheableException.class,
        () ->
            repoExecutor.executeCacheable(
                ImmutableList.of("/bin/bash", "-c", "true"),
                repoDir,
                /* auxiliaryInputs= */ ImmutableSortedMap.of(),
                /* executionProperties= */ ImmutableMap.of(),
                /* environment= */ ImmutableMap.of(),
                /* timeout= */ Duration.ZERO));
  }

  private static Digest digestOf(Map<Digest, byte[]> blobs, String content) {
    byte[] bytes = content.getBytes(UTF_8);
    Digest digest = DIGEST_UTIL.compute(bytes);
    blobs.put(digest, bytes);
    return digest;
  }

  private static String readContent(Path path) throws IOException {
    return new String(FileSystemUtils.readContent(path), UTF_8);
  }
}
