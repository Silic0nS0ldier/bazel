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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.CacheCapabilities;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.OutputDirectory;
import build.bazel.remote.execution.v2.SymlinkNode;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.devtools.build.lib.remote.CombinedCache.CachedActionResult;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.runtime.RepositoryCas.ExtractionKey;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link RemoteRepositoryCas}. */
@RunWith(JUnit4.class)
public class RemoteRepositoryCasTest {
  private static final DigestUtil DIGEST_UTIL =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);

  private static final ExtractionKey EXTRACTION_KEY =
      new ExtractionKey(
          "sha256:abc",
          "archive.tar.gz",
          /* stripPrefix= */ "",
          /* stripComponents= */ 0,
          /* renameFiles= */ ImmutableMap.of(),
          /* destinationFingerprint= */ "fp");

  @Mock public CombinedCache combinedCache;

  private InMemoryFileSystem fs;
  private Path file;
  private Digest digest;

  @Before
  public void setup() throws IOException {
    MockitoAnnotations.initMocks(this);
    fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    file = fs.getPath("/blob.txt");
    FileSystemUtils.writeContent(file, "content".getBytes(UTF_8));
    digest = DIGEST_UTIL.compute(file);
  }

  private RemoteRepositoryCas newRepositoryCas(boolean acceptCached, boolean uploadLocalResults) {
    return new RemoteRepositoryCas(
        combinedCache, DIGEST_UTIL, "none", "none", acceptCached, uploadLocalResults);
  }

  @Test
  public void upload_missingBlob_uploads() throws IOException, InterruptedException {
    when(combinedCache.findMissingDigests(any(), any()))
        .thenReturn(Futures.immediateFuture(ImmutableSet.of(digest)));
    when(combinedCache.uploadFile(any(), eq(digest), eq(file)))
        .thenReturn(Futures.immediateFuture(null));

    newRepositoryCas(true, true).upload(file);

    verify(combinedCache).uploadFile(any(), eq(digest), eq(file));
  }

  @Test
  public void upload_presentBlob_skipsUpload() throws IOException, InterruptedException {
    when(combinedCache.findMissingDigests(any(), any()))
        .thenReturn(Futures.immediateFuture(ImmutableSet.of()));

    newRepositoryCas(true, true).upload(file);

    verify(combinedCache, never()).uploadFile(any(), any(), any());
  }

  @Test
  public void tryReplayExtraction_hit_materializesTree() throws Exception {
    Map<Digest, byte[]> blobs = new HashMap<>();
    Digest fileDigest = digestOf(blobs, "extracted");
    Digest nestedDigest = digestOf(blobs, "nested");
    Directory subDir =
        Directory.newBuilder()
            .addFiles(
                FileNode.newBuilder()
                    .setName("nested.txt")
                    .setDigest(nestedDigest)
                    .setIsExecutable(true))
            .build();
    Directory emptyDir = Directory.getDefaultInstance();
    Tree tree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(FileNode.newBuilder().setName("file.txt").setDigest(fileDigest))
                    .addSymlinks(SymlinkNode.newBuilder().setName("link").setTarget("file.txt"))
                    .addDirectories(
                        DirectoryNode.newBuilder()
                            .setName("sub")
                            .setDigest(DIGEST_UTIL.compute(subDir)))
                    .addDirectories(
                        DirectoryNode.newBuilder()
                            .setName("empty")
                            .setDigest(DIGEST_UTIL.compute(emptyDir))))
            .addChildren(subDir)
            .addChildren(emptyDir)
            .build();
    Digest treeDigest = DIGEST_UTIL.compute(tree);

    ActionResult actionResult =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputDirectories(
                OutputDirectory.newBuilder().setPath("").setTreeDigest(treeDigest))
            .build();
    when(combinedCache.downloadActionResult(any(), any(), eq(false), eq(ImmutableSet.of())))
        .thenReturn(CachedActionResult.remote(actionResult));
    when(combinedCache.downloadBlob(any(), any(), any(), eq(treeDigest)))
        .thenReturn(Futures.immediateFuture(tree.toByteArray()));
    when(combinedCache.downloadFile(any(), any(Path.class), any(Digest.class)))
        .thenAnswer(
            invocation -> {
              FileSystemUtils.writeContent(
                  invocation.getArgument(1), blobs.get(invocation.getArgument(2)));
              return Futures.immediateFuture(null);
            });

    Path destination = fs.getPath("/out/dir");
    boolean replayed = newRepositoryCas(true, true).tryReplayExtraction(EXTRACTION_KEY, destination, /* preserveEntry= */ null);

    assertThat(replayed).isTrue();
    assertThat(readContent(destination.getRelative("file.txt"))).isEqualTo("extracted");
    assertThat(destination.getRelative("link").isSymbolicLink()).isTrue();
    assertThat(readContent(destination.getRelative("sub/nested.txt"))).isEqualTo("nested");
    assertThat(destination.getRelative("sub/nested.txt").isExecutable()).isTrue();
    assertThat(destination.getRelative("empty").isDirectory()).isTrue();
    // The temporary materialization directory is cleaned up.
    assertThat(fs.getPath("/out/dir.granular-extract-tmp").exists()).isFalse();
  }

  @Test
  public void tryReplayExtraction_hit_replacesDestinationContentsExceptPreserved()
      throws Exception {
    Map<Digest, byte[]> blobs = new HashMap<>();
    Digest fileDigest = digestOf(blobs, "merged");
    Tree tree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(FileNode.newBuilder().setName("BUILD.bazel").setDigest(fileDigest)))
            .build();
    Digest treeDigest = DIGEST_UTIL.compute(tree);

    ActionResult actionResult =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputDirectories(
                OutputDirectory.newBuilder().setPath("").setTreeDigest(treeDigest))
            .build();
    when(combinedCache.downloadActionResult(any(), any(), eq(false), eq(ImmutableSet.of())))
        .thenReturn(CachedActionResult.remote(actionResult));
    when(combinedCache.downloadBlob(any(), any(), any(), eq(treeDigest)))
        .thenReturn(Futures.immediateFuture(tree.toByteArray()));
    when(combinedCache.downloadFile(any(), any(Path.class), any(Digest.class)))
        .thenAnswer(
            invocation -> {
              FileSystemUtils.writeContent(
                  invocation.getArgument(1), blobs.get(invocation.getArgument(2)));
              return Futures.immediateFuture(null);
            });

    // The destination holds the pre-extraction state (a stale BUILD.bazel, matching the key's
    // fingerprint by contract) plus a temporary download directory that must survive.
    Path destination = fs.getPath("/out/dir");
    destination.createDirectoryAndParents();
    FileSystemUtils.writeContent(
        destination.getRelative("BUILD.bazel"), "pre-existing".getBytes(UTF_8));
    Path downloadTemp = destination.getRelative("temp123");
    downloadTemp.createDirectoryAndParents();
    FileSystemUtils.writeContent(downloadTemp.getRelative("archive.tgz"), "gz".getBytes(UTF_8));

    boolean replayed =
        newRepositoryCas(true, true).tryReplayExtraction(EXTRACTION_KEY, destination, downloadTemp);

    assertThat(replayed).isTrue();
    // The cached (merged) tree replaces the pre-existing contents...
    assertThat(readContent(destination.getRelative("BUILD.bazel"))).isEqualTo("merged");
    // ...while the preserved entry survives.
    assertThat(downloadTemp.getRelative("archive.tgz").exists()).isTrue();
  }

  @Test
  public void tryReplayExtraction_miss_returnsFalse() throws Exception {
    when(combinedCache.downloadActionResult(any(), any(), eq(false), eq(ImmutableSet.of())))
        .thenReturn(null);

    Path destination = fs.getPath("/out/dir");
    boolean replayed = newRepositoryCas(true, true).tryReplayExtraction(EXTRACTION_KEY, destination, /* preserveEntry= */ null);

    assertThat(replayed).isFalse();
    assertThat(destination.exists()).isFalse();
  }

  @Test
  public void tryReplayExtraction_noAcceptCached_skipsLookup() throws Exception {
    boolean replayed =
        newRepositoryCas(/* acceptCached= */ false, true)
            .tryReplayExtraction(EXTRACTION_KEY, fs.getPath("/out/dir"), /* preserveEntry= */ null);

    assertThat(replayed).isFalse();
    verify(combinedCache, never()).downloadActionResult(any(), any(), eq(false), any());
  }

  @Test
  public void storeExtraction_noUploadLocalResults_writesActionResultToDiskOnly()
      throws Exception {
    RemoteActionExecutionContext context =
        storeExtractionAndCaptureContext(/* uploadLocalResults= */ false);

    assertThat(context.getWriteCachePolicy().allowDiskCache()).isTrue();
    assertThat(context.getWriteCachePolicy().allowRemoteCache()).isFalse();
  }

  @Test
  public void storeExtraction_uploadLocalResults_writesActionResultToAllCaches() throws Exception {
    RemoteActionExecutionContext context =
        storeExtractionAndCaptureContext(/* uploadLocalResults= */ true);

    assertThat(context.getWriteCachePolicy().allowDiskCache()).isTrue();
    assertThat(context.getWriteCachePolicy().allowRemoteCache()).isTrue();
  }

  private RemoteActionExecutionContext storeExtractionAndCaptureContext(
      boolean uploadLocalResults) throws Exception {
    Path destination = fs.getPath("/out/dir");
    destination.createDirectoryAndParents();
    FileSystemUtils.writeContent(destination.getRelative("file.txt"), "extracted".getBytes(UTF_8));

    when(combinedCache.getRemoteCacheCapabilities())
        .thenReturn(CacheCapabilities.getDefaultInstance());
    when(combinedCache.findMissingDigests(any(), any()))
        .thenReturn(Futures.immediateFuture(ImmutableSet.of()));
    when(combinedCache.uploadActionResult(any(), any(), any()))
        .thenReturn(Futures.immediateVoidFuture());

    newRepositoryCas(true, uploadLocalResults).storeExtraction(EXTRACTION_KEY, destination);

    ArgumentCaptor<RemoteActionExecutionContext> contextCaptor =
        ArgumentCaptor.forClass(RemoteActionExecutionContext.class);
    verify(combinedCache).uploadActionResult(contextCaptor.capture(), any(), any());
    return contextCaptor.getValue();
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
