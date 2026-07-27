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
package com.google.devtools.build.lib.starlark;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.io.RecordingOutErr;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Integration tests for the tree artifact match API ({@code ctx.actions.pick_file},
 * {@code pick_directory}, and the {@code match_*} family).
 *
 * <p>Picks are exercised end-to-end (they resolve to genuine {@code TreeFileArtifact}s that flow
 * through the existing input-staging machinery). The {@code match_*} tests cover the analysis-time
 * surface: flag guarding, source typing, dedup, and the top-level-def contract.
 */
@RunWith(JUnit4.class)
public final class TreeArtifactSelectionTest extends BuildIntegrationTestCase {

  @Before
  public void writeExtractRule() throws Exception {
    // A dependency rule that extracts an "archive" into a tree artifact with a known layout:
    //   bin/data              -> "hello"
    //   lib/deep/other        -> "deepworld"
    //   pkg/sub/deep/leaf.txt -> "buried"   (for multi-layer pick_directory nesting)
    write(
        "test/extract.bzl",
        """
        def _extract_impl(ctx):
            out = ctx.actions.declare_directory(ctx.attr.name + "_tree")
            ctx.actions.run_shell(
                outputs = [out],
                command = (
                    "mkdir -p {d}/bin {d}/lib/deep {d}/pkg/sub/deep; " +
                    "printf hello > {d}/bin/data; " +
                    "printf deepworld > {d}/lib/deep/other; " +
                    "printf buried > {d}/pkg/sub/deep/leaf.txt"
                ).format(d = out.path),
            )
            return [DefaultInfo(files = depset([out]))]

        extract = rule(implementation = _extract_impl)
        """);
    write(
        "test/BUILD",
        """
        load(":extract.bzl", "extract")
        load(":consume.bzl", "consume")

        extract(name = "archive")

        consume(
            name = "consume",
            src = ":archive",
        )
        """);
  }

  private void writeConsumeRule(String body) throws Exception {
    write(
        "test/consume.bzl",
        "def _consume_impl(ctx):\n"
            + "    tree = ctx.attr.src[DefaultInfo].files.to_list()[0]\n"
            + body
            + "\n"
            + "consume = rule(\n"
            + "    implementation = _consume_impl,\n"
            + "    attrs = {\"src\": attr.label()},\n"
            + ")\n");
  }

  private String readOutput(String rootRelativePath) throws Exception {
    Artifact artifact =
        getArtifacts("//test:consume").stream()
            .filter(a -> a.getRootRelativePathString().equals(rootRelativePath))
            .findFirst()
            .orElseThrow();
    Path execRoot = directories.getExecRoot(TestConstants.WORKSPACE_NAME);
    Path path = execRoot.getRelative(artifact.getExecPath());
    return new String(FileSystemUtils.readContentAsLatin1(path));
  }

  @Test
  public void pickFile_flowsChildContentsToConsumer() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            pick = ctx.actions.pick_file(tree, "bin/data")
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [pick],
                outputs = [out],
                command = "cp %s %s" % (pick.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("hello");
  }

  // --- Phase 2: picks on declarative surfaces (top-level build, runfiles, BEP) ------------------

  @Test
  public void pickFile_asDefaultInfoFilesOutput_buildsTopLevel() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    // The pick itself is the target's output (no consuming action). Building the target top-level
    // must materialise the child.
    writeConsumeRule(
        """
            pick = ctx.actions.pick_file(tree, "bin/data")
            return [DefaultInfo(files = depset([pick]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/archive_tree/bin/data")).isEqualTo("hello");
  }

  @Test
  public void pickFile_inRunfiles_landsAtChildRunfilesPath() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule("    return [DefaultInfo()]"); // make package 'test' loadable
    // An executable rule with a pick in its runfiles. The runfiles input manifest (produced by
    // SourceManifestAction) must list the child at its tree-relative runfiles path.
    write(
        "run/rules.bzl",
        """
        def _runner_impl(ctx):
            tree = ctx.attr.src[DefaultInfo].files.to_list()[0]
            pick = ctx.actions.pick_file(tree, "bin/data")
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.write(script, "#!/bin/sh\\ntrue\\n", is_executable = True)
            return [DefaultInfo(
                executable = script,
                runfiles = ctx.runfiles(files = [pick]),
            )]

        runner = rule(
            implementation = _runner_impl,
            executable = True,
            attrs = {"src": attr.label()},
        )
        """);
    write(
        "run/BUILD",
        """
        load(":rules.bzl", "runner")
        runner(name = "runner", src = "//test:archive")
        """);

    buildTarget("//run:runner");

    com.google.devtools.build.lib.analysis.RunfilesSupport runfilesSupport =
        getConfiguredTarget("//run:runner")
            .getProvider(com.google.devtools.build.lib.analysis.FilesToRunProvider.class)
            .getRunfilesSupport();
    Artifact manifest = runfilesSupport.getRunfilesInputManifest();
    Path execRoot = directories.getExecRoot(TestConstants.WORKSPACE_NAME);
    String manifestText =
        new String(FileSystemUtils.readContentAsLatin1(execRoot.getRelative(manifest.getExecPath())));
    assertThat(manifestText).contains(TestConstants.WORKSPACE_NAME + "/test/archive_tree/bin/data");
  }

  @Test
  public void pickFile_execPathIsTreePathJoinedWithPickPath() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    // Write the pick's exec path into the output so we can assert its analysis-time value.
    writeConsumeRule(
        """
            pick = ctx.actions.pick_file(tree, "bin/data")
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [pick],
                outputs = [out],
                command = "printf %s > %s" % (pick.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).endsWith("/test/archive_tree/bin/data");
  }

  @Test
  public void pickDirectory_stagesSubtreeAsInput() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    // Only the picked subtree is an input; its files are staged at their original exec paths
    // (under the root tree's path), so the copy of lib/deep/other succeeds.
    writeConsumeRule(
        """
            sub = ctx.actions.pick_directory(tree, "lib")
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [sub],
                outputs = [out],
                command = "cp %s/deep/other %s" % (sub.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("deepworld");
  }

  @Test
  public void pickFile_fromPickedDirectory_collapsesToRootChild() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sub = ctx.actions.pick_directory(tree, "lib/deep")
            pick = ctx.actions.pick_file(sub, "other")
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [pick],
                outputs = [out],
                command = "cp %s %s" % (pick.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("deepworld");
  }

  @Test
  public void pickDirectory_missingPath_failsConsumingAction() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sub = ctx.actions.pick_directory(tree, "nonexistent")
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [sub],
                outputs = [out],
                command = "touch %s" % out.path,
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(e.getDetailedExitCode().getFailureDetail().getMessage())
        .contains("does not exist in tree artifact");
  }

  // --- Picks co-existing with their origins, and overlapping inputs ------------------------------
  //
  // A pick's exec path lies underneath its origin tree's exec path, so a consumer may end up with
  // both the origin (whole tree or an enclosing picked directory) and the pick as inputs, which
  // stage to overlapping exec paths. These tests assert that such overlap is benign: the build
  // succeeds (no double-staging/conflict error) and every path resolves to the correct content.

  @Test
  public void pickFile_coexistsWithOriginTreeInSameAction() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    // The whole tree and a picked child of it are both inputs. The picked child's exec path is the
    // same child the tree expands to (pick_file collapses to a root child), so they must dedupe
    // rather than collide.
    writeConsumeRule(
        """
            pick = ctx.actions.pick_file(tree, "bin/data")
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [tree, pick],
                outputs = [out],
                command = "cat %s %s/lib/deep/other %s > %s" % (
                    pick.path, tree.path, pick.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    // pick (hello) + tree's lib/deep/other (deepworld) + pick again (hello).
    assertThat(readOutput("test/consume.out")).isEqualTo("hellodeepworldhello");
  }

  @Test
  public void pickDirectory_coexistsWithOriginTreeInSameAction() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    // The whole tree and a picked subdirectory of it are both inputs. The subtree's children are
    // re-parented onto the subtree yet stage to the same exec paths as the tree's own children,
    // so the two overlap; the build must still succeed.
    writeConsumeRule(
        """
            sub = ctx.actions.pick_directory(tree, "lib")
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [tree, sub],
                outputs = [out],
                command = "cat %s/bin/data %s/deep/other > %s" % (
                    tree.path, sub.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("hellodeepworld");
  }

  @Test
  public void pickFile_coexistsWithEnclosingPickedDirectory() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    // A picked directory and a picked file from *within* it are both inputs — overlapping again,
    // and reached via two different origin artifacts (the subtree and the root tree).
    writeConsumeRule(
        """
            sub = ctx.actions.pick_directory(tree, "lib")
            pick = ctx.actions.pick_file(tree, "lib/deep/other")
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [sub, pick],
                outputs = [out],
                command = "cat %s/deep/other %s > %s" % (sub.path, pick.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("deepworlddeepworld");
  }

  @Test
  public void nestedPickDirectory_twoLayers_thenPickFile() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    // pick_directory of a pick_directory: the second layer collapses onto the root tree, and the
    // final pick_file collapses too, so the consumed child is a plain root child.
    writeConsumeRule(
        """
            a = ctx.actions.pick_directory(tree, "lib")
            b = ctx.actions.pick_directory(a, "deep")
            pick = ctx.actions.pick_file(b, "other")
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [pick],
                outputs = [out],
                command = "cp %s %s" % (pick.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("deepworld");
  }

  @Test
  public void nestedPickDirectory_threeLayers_stagesDeepSubtree() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    // Three pick_directory layers (pkg -> sub -> deep), consumed as a subtree input. All layers
    // collapse onto the root tree; the deepest subtree stages exactly its one buried file.
    writeConsumeRule(
        """
            a = ctx.actions.pick_directory(tree, "pkg")
            b = ctx.actions.pick_directory(a, "sub")
            c = ctx.actions.pick_directory(b, "deep")
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [c],
                outputs = [out],
                command = "cp %s/leaf.txt %s" % (c.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("buried");
  }

  @Test
  public void nestedPickDirectory_deepSubtreeCoexistsWithRootTree() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    // A deeply-nested picked subtree and the whole origin tree, both inputs — distant overlap
    // (the subtree's exec path is several directories below the tree root).
    writeConsumeRule(
        """
            deep = ctx.actions.pick_directory(
                ctx.actions.pick_directory(tree, "pkg"), "sub")
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [tree, deep],
                outputs = [out],
                command = "cat %s/bin/data %s/deep/leaf.txt > %s" % (
                    tree.path, deep.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("helloburied");
  }

  @Test
  public void pickFile_directPathAndViaNestedDirectories_shareExecPathAndCoexist() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    // The same file reached two ways: directly from the root tree, and via nested pick_directory
    // layers. Both must collapse to the identical exec path (so they are interchangeable and
    // dedupe as inputs). The rule writes both paths for a direct equality assertion.
    writeConsumeRule(
        """
            direct = ctx.actions.pick_file(tree, "pkg/sub/deep/leaf.txt")
            via = ctx.actions.pick_file(
                ctx.actions.pick_directory(
                    ctx.actions.pick_directory(tree, "pkg"), "sub/deep"),
                "leaf.txt")
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [direct, via],
                outputs = [out],
                command = "printf '%s|%s' > %s" % (direct.path, via.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    String output = readOutput("test/consume.out");
    String[] paths = output.split("\\|");
    assertThat(paths).hasLength(2);
    assertThat(paths[0]).endsWith("/test/archive_tree/pkg/sub/deep/leaf.txt");
    assertThat(paths[1]).isEqualTo(paths[0]);
  }

  @Test
  public void pickFile_missingPath_failsConsumingAction() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            pick = ctx.actions.pick_file(tree, "bin/nonexistent")
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [pick],
                outputs = [out],
                command = "cp %s %s" % (pick.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);
    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(e.getDetailedExitCode().getFailureDetail().getMessage())
        .contains("does not exist in tree artifact");
  }

  @Test
  public void pickFile_withoutFlag_fails() throws Exception {
    writeConsumeRule(
        """
            pick = ctx.actions.pick_file(tree, "bin/data")
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1())
        .contains("--experimental_tree_artifact_selection");
  }

  @Test
  public void pickFile_invalidPath_fails() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            pick = ctx.actions.pick_file(tree, "../escape")
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1()).contains("normalized relative path");
  }

  @Test
  public void matchFile_regularFileSource_fails() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            f = ctx.actions.declare_file(ctx.attr.name + ".txt")
            ctx.actions.write(f, "x")
            sel = ctx.actions.match_file(sources = [f], include = ["**"])
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1()).contains("is not a directory artifact");
  }

  @Test
  public void matchFiles_duplicateSource_fails() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.match_files(sources = [tree, tree])
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1()).contains("duplicate source");
  }

  @Test
  public void matchFiles_matchFileResultAsSource_fails() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            inner = ctx.actions.match_file(sources = [tree], include = ["bin/data"])
            sel = ctx.actions.match_files(sources = [inner])
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1())
        .contains("a match_file result may not be a source");
  }

  @Test
  public void matchFiles_nonTopLevelFilter_fails() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.match_files(sources = [tree], filter = lambda c: c)
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1()).contains("top-level def statement");
  }

  @Test
  public void matchFiles_invalidGlobPattern_fails() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.match_files(sources = [tree], include = ["a/../b"])
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1()).contains("invalid 'include' pattern");
  }

  @Test
  public void matchFile_reprIsStablePlaceholder() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.match_file(sources = [tree], include = ["bin/*"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.write(out, repr(sel))
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    String repr = readOutput("test/consume.out");
    assertThat(repr).startsWith("match_file(sources = [");
    assertThat(repr).contains("include = [\"bin/*\"]");
    assertThat(repr).endsWith(")");
  }

  @Test
  public void matchFile_defaultIncludeOmittedFromRepr() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.match_file(sources = [tree])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.write(out, repr(sel))
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    // The default include = ["**"] is not rendered.
    assertThat(readOutput("test/consume.out")).doesNotContain("include");
  }

  // --- Matches resolved as action inputs (execution-time) -------------------------------------

  @Test
  public void matchFile_stagesResolvedFileAsInput() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    // The tree itself is not an input; only the match is. If the resolved child is staged, it
    // appears at its exec path (tree.path + "/bin/data") and the copy succeeds.
    writeConsumeRule(
        """
            sel = ctx.actions.match_file(sources = [tree], include = ["bin/data"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [sel],
                outputs = [out],
                command = "cp %s/bin/data %s" % (tree.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("hello");
  }

  @Test
  public void matchFiles_stagesAllMatchedFiles() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.match_files(sources = [tree], include = ["**"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [sel],
                outputs = [out],
                command = "cat %s/bin/data %s/lib/deep/other > %s" % (
                    tree.path, tree.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("hellodeepworld");
  }

  @Test
  public void matchFiles_filterNarrowsResolvedSet() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    write(
        "test/consume.bzl",
        """
        def _only_data(candidates):
            return [c for c in candidates if c.basename == "data"]

        def _consume_impl(ctx):
            tree = ctx.attr.src[DefaultInfo].files.to_list()[0]
            sel = ctx.actions.match_files(
                sources = [tree], include = ["**"], filter = _only_data)
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [sel],
                outputs = [out],
                command = "cp %s/bin/data %s" % (tree.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]

        consume = rule(
            implementation = _consume_impl,
            attrs = {"src": attr.label()},
        )
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("hello");
  }

  @Test
  public void matchFile_noMatch_failsConsumingAction() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.match_file(sources = [tree], include = ["bin/missing"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [sel],
                outputs = [out],
                command = "touch %s" % out.path,
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(e.getDetailedExitCode().getFailureDetail().getMessage())
        .contains("resolved to no matches");
  }

  @Test
  public void matchFile_ambiguous_failsConsumingAction() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.match_file(sources = [tree], include = ["**"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [sel],
                outputs = [out],
                command = "touch %s" % out.path,
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(e.getDetailedExitCode().getFailureDetail().getMessage())
        .contains("resolved to more than one match");
  }

  @Test
  public void matchFiles_emptyDisallowed_failsConsumingAction() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.match_files(sources = [tree], include = ["nope/**"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [sel],
                outputs = [out],
                command = "touch %s" % out.path,
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(e.getDetailedExitCode().getFailureDetail().getMessage())
        .contains("resolved to no matches");
  }

  @Test
  public void matchFiles_allowEmpty_succeedsWithNoInputs() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.match_files(
                sources = [tree], include = ["nope/**"], allow_empty = True)
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [sel],
                outputs = [out],
                command = "printf ok > %s" % out.path,
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("ok");
  }

  // --- Matches on command lines (Args) ---------------------------------------------------------

  @Test
  public void argsAddAll_expandsSelectionToResolvedPaths() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    // The command receives the resolved children's exec paths as arguments and cats them, in
    // resolution order (sorted by tree-relative path: bin/data, then lib/deep/other).
    writeConsumeRule(
        """
            sel = ctx.actions.match_files(sources = [tree], include = ["bin/**", "lib/**"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            args = ctx.actions.args()
            args.add_all([sel])
            ctx.actions.run_shell(
                inputs = [sel],
                outputs = [out],
                arguments = [args],
                command = 'cat "$@" > %s' % out.path,
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("hellodeepworld");
  }

  @Test
  public void argsAdd_scalarMatchedFile_rendersResolvedPath() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.match_file(sources = [tree], include = ["bin/data"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            args = ctx.actions.args()
            args.add(sel)
            ctx.actions.run_shell(
                inputs = [sel],
                outputs = [out],
                arguments = [args],
                command = 'cp "$1" %s' % out.path,
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("hello");
  }

  @Test
  public void argsAddAll_mapEachRunsOnResolvedFiles() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    write(
        "test/consume.bzl",
        """
        def _basename(f):
            return f.basename

        def _consume_impl(ctx):
            tree = ctx.attr.src[DefaultInfo].files.to_list()[0]
            sel = ctx.actions.match_files(sources = [tree], include = ["**"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            args = ctx.actions.args()
            args.add_all([sel], map_each = _basename)
            ctx.actions.run_shell(
                inputs = [sel],
                outputs = [out],
                arguments = [args],
                command = 'echo "$@" > %s' % out.path,
            )
            return [DefaultInfo(files = depset([out]))]

        consume = rule(
            implementation = _consume_impl,
            attrs = {"src": attr.label()},
        )
        """);

    buildTarget("//test:consume");

    String output = readOutput("test/consume.out");
    assertThat(output).contains("data");
    assertThat(output).contains("other");
  }

  @Test
  public void argsSelection_notAnInput_failsWithActionableError() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    // The match appears on the command line but not in inputs: its resolution never runs, so
    // expansion fails with a pointer at the missing 'inputs' entry. The tree is passed as a plain
    // input so the action is otherwise well-formed.
    writeConsumeRule(
        """
            sel = ctx.actions.match_files(sources = [tree], include = ["**"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            args = ctx.actions.args()
            args.add_all([sel])
            ctx.actions.run_shell(
                inputs = [tree],
                outputs = [out],
                arguments = [args],
                command = "touch %s" % out.path,
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(e.getDetailedExitCode().getFailureDetail().getMessage())
        .contains("is not an input of the action");
  }

  @Test
  public void argsAdd_fileMatch_failsAtAnalysis() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.match_files(sources = [tree])
            args = ctx.actions.args()
            args.add(sel)
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1()).contains("Use Args#add_all or Args#add_joined");
  }

  @Test
  public void argsAddAll_depsetOfMatches_failsAtAnalysis() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.match_file(sources = [tree], include = ["bin/data"])
            args = ctx.actions.args()
            args.add_all(depset([sel]))
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1())
        .contains("depsets of file matches cannot be added to Args");
  }

  // --- MatchedFile as executable / in tools ------------------------------------------------------

  private void writeToolExtractFixture() throws Exception {
    // An extract rule whose tree contains an executable script at bin/tool.sh that writes a fixed
    // marker to the path given as its first argument.
    write(
        "test/extract.bzl",
        """
        def _extract_impl(ctx):
            out = ctx.actions.declare_directory(ctx.attr.name + "_tree")
            ctx.actions.run_shell(
                outputs = [out],
                command = (
                    "mkdir -p {d}/bin; " +
                    "printf '#!/bin/sh\\nprintf toolran > $1' > {d}/bin/tool.sh; " +
                    "chmod +x {d}/bin/tool.sh"
                ).format(d = out.path),
            )
            return [DefaultInfo(files = depset([out]))]

        extract = rule(implementation = _extract_impl)
        """);
  }

  @Test
  public void matchedFileAsExecutable_runsResolvedTool() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeToolExtractFixture();
    // The executable match is auto-registered as an input match; no 'inputs' needed.
    writeConsumeRule(
        """
            sel = ctx.actions.match_file(sources = [tree], include = ["bin/tool.sh"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run(
                executable = sel,
                arguments = [out.path],
                outputs = [out],
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("toolran");
  }

  @Test
  public void selectedDirectoryAsExecutable_failsAtAnalysis() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.match_directory(sources = [tree], include = ["bin"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run(
                executable = sel,
                arguments = [out.path],
                outputs = [out],
            )
            return [DefaultInfo(files = depset([out]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1())
        .contains("'executable' must be a match from match_file");
  }

  @Test
  public void matchedFileInTools_stagesResolvedFile() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeToolExtractFixture();
    writeConsumeRule(
        """
            sel = ctx.actions.match_file(sources = [tree], include = ["bin/tool.sh"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                tools = [sel],
                outputs = [out],
                command = "%s/bin/tool.sh %s" % (tree.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("toolran");
  }

  // --- Phase 7: hardening (Skymeld, path mapping) -----------------------------------------------
  // Invalidation-granularity (a sibling change not re-executing the consumer) is a cross-build,
  // action-cache property, so it is tested in BuildWithoutTheBytesIntegrationTestBase via
  // restartServer(), not here where in-memory Skyframe re-runs the discovering action on any
  // source-tree change.

  @Test
  public void matchInput_underSkymeld_resolvesCorrectly() throws Exception {
    addOptions(
        "--experimental_tree_artifact_selection",
        "--experimental_merged_skyframe_analysis_execution");
    // Interleaved analysis/execution exercises the input-discovery scheduling of the match under
    // concurrency.
    writeConsumeRule(
        """
            sel = ctx.actions.match_files(sources = [tree], include = ["**"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [sel],
                outputs = [out],
                command = "cat %s/bin/data %s/lib/deep/other > %s" % (tree.path, tree.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("hellodeepworld");
  }

  @Test
  public void matchInput_underPathStripping_resolvesAndRenders() throws Exception {
    addOptions(
        "--experimental_tree_artifact_selection", "--experimental_output_paths=strip");
    // The resolved child's path flows through both staging and command-line expansion under path
    // mapping; the scalar match_file on the command line must render the (mapped) path and the
    // action must still find its input.
    writeConsumeRule(
        """
            sel = ctx.actions.match_file(sources = [tree], include = ["bin/data"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            args = ctx.actions.args()
            args.add(sel)
            ctx.actions.run_shell(
                inputs = [sel],
                outputs = [out],
                arguments = [args],
                command = 'cp "$1" %s' % out.path,
            )
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    assertThat(readOutput("test/consume.out")).isEqualTo("hello");
  }
}
