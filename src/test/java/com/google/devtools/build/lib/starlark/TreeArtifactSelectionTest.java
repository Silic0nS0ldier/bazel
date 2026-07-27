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
 * Integration tests for the tree artifact selection API ({@code ctx.actions.pick_file},
 * {@code pick_directory}, and the {@code select_*} family).
 *
 * <p>Picks are exercised end-to-end (they resolve to genuine {@code TreeFileArtifact}s that flow
 * through the existing input-staging machinery). The {@code select_*} tests cover the analysis-time
 * surface: flag guarding, source typing, dedup, and the top-level-def contract.
 */
@RunWith(JUnit4.class)
public final class TreeArtifactSelectionTest extends BuildIntegrationTestCase {

  @Before
  public void writeExtractRule() throws Exception {
    // A dependency rule that extracts an "archive" into a tree artifact with a known layout:
    //   bin/data          -> "hello"
    //   lib/deep/other    -> "deepworld"
    write(
        "test/extract.bzl",
        """
        def _extract_impl(ctx):
            out = ctx.actions.declare_directory(ctx.attr.name + "_tree")
            ctx.actions.run_shell(
                outputs = [out],
                command = (
                    "mkdir -p {d}/bin {d}/lib/deep; " +
                    "printf hello > {d}/bin/data; " +
                    "printf deepworld > {d}/lib/deep/other"
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
  public void pickDirectory_notYetSupported_failsWithActionableError() throws Exception {
    // v1 does not support consuming a picked subdirectory as an action input (subtree-input
    // routing is unimplemented). The API rejects it with a message pointing at the alternatives.
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            headers = ctx.actions.pick_directory(tree, "lib/deep")
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1()).contains("pick_directory() is not yet supported");
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
  public void selectFile_regularFileSource_fails() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            f = ctx.actions.declare_file(ctx.attr.name + ".txt")
            ctx.actions.write(f, "x")
            sel = ctx.actions.select_file(sources = [f], include = ["**"])
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1()).contains("is not a directory artifact");
  }

  @Test
  public void selectFiles_duplicateSource_fails() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.select_files(sources = [tree, tree])
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1()).contains("duplicate source");
  }

  @Test
  public void selectFiles_selectFileResultAsSource_fails() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            inner = ctx.actions.select_file(sources = [tree], include = ["bin/data"])
            sel = ctx.actions.select_files(sources = [inner])
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1())
        .contains("a select_file result may not be a source");
  }

  @Test
  public void selectFiles_nonTopLevelFilter_fails() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.select_files(sources = [tree], filter = lambda c: c)
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1()).contains("top-level def statement");
  }

  @Test
  public void selectFiles_invalidGlobPattern_fails() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.select_files(sources = [tree], include = ["a/../b"])
            return [DefaultInfo(files = depset([tree]))]
        """);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:consume"));

    assertThat(recordingOutErr.errAsLatin1()).contains("invalid 'include' pattern");
  }

  @Test
  public void selectFile_reprIsStablePlaceholder() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.select_file(sources = [tree], include = ["bin/*"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.write(out, repr(sel))
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    String repr = readOutput("test/consume.out");
    assertThat(repr).startsWith("select_file(sources = [");
    assertThat(repr).contains("include = [\"bin/*\"]");
    assertThat(repr).endsWith(")");
  }

  @Test
  public void selectFile_defaultIncludeOmittedFromRepr() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.select_file(sources = [tree])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.write(out, repr(sel))
            return [DefaultInfo(files = depset([out]))]
        """);

    buildTarget("//test:consume");

    // The default include = ["**"] is not rendered.
    assertThat(readOutput("test/consume.out")).doesNotContain("include");
  }

  // --- Selections resolved as action inputs (execution-time) -------------------------------------

  @Test
  public void selectFile_stagesResolvedFileAsInput() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    // The tree itself is not an input; only the selection is. If the resolved child is staged, it
    // appears at its exec path (tree.path + "/bin/data") and the copy succeeds.
    writeConsumeRule(
        """
            sel = ctx.actions.select_file(sources = [tree], include = ["bin/data"])
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
  public void selectFiles_stagesAllMatchedFiles() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.select_files(sources = [tree], include = ["**"])
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
  public void selectFiles_filterNarrowsResolvedSet() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    write(
        "test/consume.bzl",
        """
        def _only_data(candidates):
            return [c for c in candidates if c.basename == "data"]

        def _consume_impl(ctx):
            tree = ctx.attr.src[DefaultInfo].files.to_list()[0]
            sel = ctx.actions.select_files(
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
  public void selectFile_noMatch_failsConsumingAction() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.select_file(sources = [tree], include = ["bin/missing"])
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
  public void selectFile_ambiguous_failsConsumingAction() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.select_file(sources = [tree], include = ["**"])
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
  public void selectFiles_emptyDisallowed_failsConsumingAction() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.select_files(sources = [tree], include = ["nope/**"])
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
  public void selectFiles_allowEmpty_succeedsWithNoInputs() throws Exception {
    addOptions("--experimental_tree_artifact_selection");
    writeConsumeRule(
        """
            sel = ctx.actions.select_files(
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
}
