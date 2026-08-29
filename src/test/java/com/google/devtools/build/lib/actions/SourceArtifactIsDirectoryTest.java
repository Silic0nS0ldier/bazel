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
package com.google.devtools.build.lib.actions;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link Artifact.SourceArtifact#isDirectory()} with the opt-in {@link
 * SourceDirectoryIsDirectoryFlag} enabled.
 *
 * <p>The flag is read once at class-load time from the {@code BAZEL_SOURCE_DIRECTORY_IS_DIRECTORY}
 * system property, so this lives in its own test target that sets it via {@code jvm_flags}. The
 * default (disabled) behavior is pinned by {@code ArtifactTest#isDirectory_sourceArtifact_*}.
 */
@RunWith(JUnit4.class)
public final class SourceArtifactIsDirectoryTest {

  private final Scratch scratch = new Scratch();

  private ArtifactRoot sourceRoot() throws Exception {
    return ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/src")));
  }

  @Test
  public void sanityCheck_flagEnabled() {
    // Guards against silently testing the wrong mode if the jvm_flags wiring regresses.
    assertThat(SourceDirectoryIsDirectoryFlag.sourceDirectoryIsDirectory()).isTrue();
  }

  @Test
  public void sourceDirectory_isDirectory() throws Exception {
    ArtifactRoot root = sourceRoot();
    Artifact dir = ActionsTestUtil.createArtifact(root, scratch.dir("/src/some_dir"));

    assertThat(dir.isDirectory()).isTrue();
  }

  @Test
  public void sourceFile_isNotDirectory() throws Exception {
    ArtifactRoot root = sourceRoot();
    Artifact file = ActionsTestUtil.createArtifact(root, scratch.file("/src/some_file"));

    assertThat(file.isDirectory()).isFalse();
  }

  @Test
  public void symlinkToDirectory_isDirectory() throws Exception {
    ArtifactRoot root = sourceRoot();
    Path realDir = scratch.dir("/src/real_dir");
    Path link = scratch.getFileSystem().getPath("/src/link_to_dir");
    link.createSymbolicLink(realDir);
    Artifact linkArtifact = ActionsTestUtil.createArtifact(root, link);

    // Symlinks are followed, matching FileValue semantics.
    assertThat(linkArtifact.isDirectory()).isTrue();
  }

  @Test
  public void symlinkToFile_isNotDirectory() throws Exception {
    ArtifactRoot root = sourceRoot();
    Path realFile = scratch.file("/src/real_file");
    Path link = scratch.getFileSystem().getPath("/src/link_to_file");
    link.createSymbolicLink(realFile);
    Artifact linkArtifact = ActionsTestUtil.createArtifact(root, link);

    assertThat(linkArtifact.isDirectory()).isFalse();
  }

  @Test
  public void missingPath_isNotDirectory() throws Exception {
    ArtifactRoot root = sourceRoot();
    // Never created on disk; a stat error must degrade to "not a directory" rather than throw,
    // exactly as a deleted source does.
    Artifact missing = ActionsTestUtil.createArtifact(root, scratch.resolve("/src/does_not_exist"));

    assertThat(missing.isDirectory()).isFalse();
  }

  @Test
  public void danglingSymlink_isNotDirectory() throws Exception {
    ArtifactRoot root = sourceRoot();
    Path link = scratch.getFileSystem().getPath("/src/dangling");
    link.createSymbolicLink(scratch.getFileSystem().getPath("/src/nonexistent_target"));
    Artifact linkArtifact = ActionsTestUtil.createArtifact(root, link);

    assertThat(linkArtifact.isDirectory()).isFalse();
  }

  @Test
  public void derivedArtifacts_unaffected() throws Exception {
    // The flag only changes source artifacts; derived directory-ness stays declaration-based.
    ArtifactRoot derivedRoot =
        ArtifactRoot.asDerivedRoot(scratch.dir("/base/exec"), RootType.OUTPUT, "root");

    Artifact regular = ActionsTestUtil.createArtifact(derivedRoot, "out/file");
    Artifact tree = ActionsTestUtil.createTreeArtifactWithGeneratingAction(derivedRoot, "out/tree");

    assertThat(regular.isDirectory()).isFalse();
    assertThat(tree.isDirectory()).isTrue();
  }
}
