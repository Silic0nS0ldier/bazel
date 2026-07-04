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
package com.google.devtools.build.lib.bazel.repository.starlark;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RepoExecutionSandbox} staging and result handling. */
@RunWith(JUnit4.class)
public class RepoExecutionSandboxTest {

  private InMemoryFileSystem fs;
  private Path repoDir;
  private Path scratchBase;
  private Path linuxSandbox;

  @Before
  public void setUp() throws IOException {
    fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    repoDir = fs.getPath("/output_base/external/my_repo");
    repoDir.createDirectoryAndParents();
    scratchBase = fs.getPath("/output_base/granular-repo-sandbox");
    linuxSandbox = fs.getPath("/embedded/linux-sandbox");
  }

  private RepoExecutionSandbox.Prepared prepare(ImmutableSet<Path> readOnlyInputs)
      throws IOException {
    return RepoExecutionSandbox.prepare(
        OS.LINUX,
        linuxSandbox,
        scratchBase,
        repoDir,
        fs.getPath("/workspace"),
        PathFragment.EMPTY_FRAGMENT,
        readOnlyInputs,
        ImmutableList.of("/bin/true"),
        Duration.ofSeconds(600));
  }

  @Test
  public void prepare_stagesRepoAtItsOwnPathUnderSandboxRoot() throws IOException {
    FileSystemUtils.writeContent(repoDir.getRelative("file.txt"), "content".getBytes(UTF_8));
    repoDir.getRelative("sub").createDirectoryAndParents();
    FileSystemUtils.writeContent(repoDir.getRelative("sub/nested.txt"), "nested".getBytes(UTF_8));
    repoDir.getRelative("empty").createDirectoryAndParents();
    repoDir.getRelative("link").createSymbolicLink(PathFragment.create("file.txt"));

    RepoExecutionSandbox.Prepared prepared = prepare(ImmutableSet.of());

    // The repository is staged at its own absolute path below the sandbox root, so that after the
    // sandbox pivots its root the repository is visible at its real path.
    assertThat(prepared.sandboxRepoDir().asFragment().getPathString())
        .isEqualTo(
            prepared.scratchDir().asFragment().getPathString()
                + repoDir.asFragment().getPathString());
    assertThat(readContent(prepared.sandboxRepoDir().getRelative("file.txt")))
        .isEqualTo("content");
    assertThat(readContent(prepared.sandboxRepoDir().getRelative("sub/nested.txt")))
        .isEqualTo("nested");
    assertThat(prepared.sandboxRepoDir().getRelative("empty").isDirectory()).isTrue();
    assertThat(prepared.sandboxRepoDir().getRelative("link").isSymbolicLink()).isTrue();
    assertThat(prepared.workingDirectory()).isEqualTo(prepared.sandboxRepoDir());
  }

  @Test
  public void prepare_commandLineWrapsCommandHermetically() throws IOException {
    FileSystemUtils.writeContent(repoDir.getRelative("file.txt"), "content".getBytes(UTF_8));
    Path input = fs.getPath("/workspace/tool.sh");
    input.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(input, "#!/bin/sh".getBytes(UTF_8));

    RepoExecutionSandbox.Prepared prepared = prepare(ImmutableSet.of(input));

    assertThat(prepared.commandLine().get(0)).isEqualTo(linuxSandbox.getPathString());
    assertThat(prepared.commandLine())
        .containsAtLeast("-h", prepared.scratchDir().getPathString())
        .inOrder();
    // Declared inputs outside the repository are mounted (read-only) at their real path.
    assertThat(prepared.commandLine()).containsAtLeast("-M", "/workspace/tool.sh").inOrder();
    // The command itself comes last.
    assertThat(prepared.commandLine().reverse().get(0)).isEqualTo("/bin/true");
  }

  @Test
  public void moveResultsBack_replacesRepoContentsWithSandboxResults() throws IOException {
    FileSystemUtils.writeContent(repoDir.getRelative("input.txt"), "input".getBytes(UTF_8));
    FileSystemUtils.writeContent(repoDir.getRelative("deleted.txt"), "gone".getBytes(UTF_8));

    RepoExecutionSandbox.Prepared prepared = prepare(ImmutableSet.of());
    // Simulate the command's file changes in the sandboxed repository.
    prepared.sandboxRepoDir().getRelative("deleted.txt").delete();
    FileSystemUtils.writeContent(
        prepared.sandboxRepoDir().getRelative("created.txt"), "new".getBytes(UTF_8));

    RepoExecutionSandbox.moveResultsBack(prepared, repoDir);

    assertThat(readContent(repoDir.getRelative("input.txt"))).isEqualTo("input");
    assertThat(readContent(repoDir.getRelative("created.txt"))).isEqualTo("new");
    assertThat(repoDir.getRelative("deleted.txt").exists()).isFalse();
  }

  @Test
  public void prepare_missingRepoDir_stagesEmptyDirectory() throws IOException {
    Path missingRepoDir = fs.getPath("/output_base/external/not_yet_created");

    RepoExecutionSandbox.Prepared prepared =
        RepoExecutionSandbox.prepare(
            OS.LINUX,
            linuxSandbox,
            scratchBase,
            missingRepoDir,
            fs.getPath("/workspace"),
            PathFragment.EMPTY_FRAGMENT,
            ImmutableSet.of(),
            ImmutableList.of("/bin/true"),
            Duration.ofSeconds(600));

    assertThat(prepared.sandboxRepoDir().isDirectory()).isTrue();
    assertThat(prepared.sandboxRepoDir().getDirectoryEntries()).isEmpty();
  }

  private static String readContent(Path path) throws IOException {
    return new String(FileSystemUtils.readContent(path), UTF_8);
  }
}
