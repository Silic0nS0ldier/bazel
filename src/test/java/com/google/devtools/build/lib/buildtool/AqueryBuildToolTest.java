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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Action;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.ActionGraphContainer;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.FileMatch;
import com.google.devtools.build.lib.buildtool.AqueryProcessor.AqueryActionFilterException;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.query2.aquery.ActionGraphQueryEnvironment;
import com.google.devtools.build.lib.query2.aquery.AqueryOptions;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryParser;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.commands.AqueryCommand;
import com.google.devtools.build.lib.server.FailureDetails.ActionQuery.Code;
import com.google.protobuf.ExtensionRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Integration tests for aquery. */
@RunWith(JUnit4.class)
public class AqueryBuildToolTest extends BuildIntegrationTestCase {
  private ImmutableMap<String, QueryFunction> functions;

  @Before
  public final void setFunctions() {
    ImmutableMap.Builder<String, QueryFunction> builder = ImmutableMap.builder();

    for (QueryFunction queryFunction : ActionGraphQueryEnvironment.FUNCTIONS) {
      builder.put(queryFunction.getName(), queryFunction);
    }

    for (QueryFunction queryFunction : ActionGraphQueryEnvironment.AQUERY_FUNCTIONS) {
      builder.put(queryFunction.getName(), queryFunction);
    }

    functions = builder.buildOrThrow();
    runtimeWrapper.addOptionsClass(AqueryOptions.class);
  }

  @Test
  public void testConstructor_wrongAqueryFilterFormat_throwsError() throws Exception {
    QueryExpression expr = QueryParser.parse("deps(inputs('abc', //abc))", functions);

    assertThrows(
        AqueryActionFilterException.class,
        () -> new AqueryProcessor(expr, TargetPattern.defaultParser()));
  }

  @Test
  public void testConstructor_wrongPatternSyntax_throwsError() throws Exception {
    QueryExpression expr = QueryParser.parse("inputs('*abc', //abc)", functions);

    AqueryActionFilterException thrown =
        assertThrows(
            AqueryActionFilterException.class,
            () -> new AqueryProcessor(expr, TargetPattern.defaultParser()));
    assertThat(thrown).hasMessageThat().contains("Wrong query syntax:");
  }

  @Test
  public void testDmpActionGraphFromSkyframe_wrongOutputFormat_returnsFailure() throws Exception {
    addOptions("--output=text");
    CommandEnvironment env = runtimeWrapper.newCommand(AqueryCommand.class);
    AqueryProcessor aqueryProcessor = new AqueryProcessor(null, TargetPattern.defaultParser());
    BlazeCommandResult result = aqueryProcessor.dumpActionGraphFromSkyframe(env);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getDetailedExitCode().getFailureDetail().getActionQuery().getCode())
        .isEqualTo(Code.SKYFRAME_STATE_PREREQ_UNMET);
  }

  @Test
  public void testAquerySkyframeStateProtoNotCutoff() throws Exception {
    // First, prepare and run the build.
    write(
        "x/BUILD",
        """
        genrule(
            name = "x",
            srcs = ["in"],
            # This has the length 10, so it will include a 0x0a / newline character
            # that triggers the cutoff.
            outs = ["1234567890"],
            cmd = "touch $(OUTS)",
        )
        """);
    write("x/in", "");
    buildTarget("//x");

    // Then, run aquery and dump the action graph as of the previous skyframe state.
    addOptions("--output=proto", "--skyframe_state");
    CommandEnvironment env = runtimeWrapper.newCommand(AqueryCommand.class);
    ByteArrayOutputStream stdout = captureStdout(env);

    AqueryProcessor aqueryProcessor = new AqueryProcessor(null, TargetPattern.defaultParser());
    BlazeCommandResult result = aqueryProcessor.dumpActionGraphFromSkyframe(env);
    assertThat(result.isSuccess()).isTrue();

    // Test whether stdout is a valid proto.
    assertThat(stdout.size()).isGreaterThan(0);
    ActionGraphContainer actionGraphContainer =
        ActionGraphContainer.parseFrom(stdout.toByteArray(), ExtensionRegistry.getEmptyRegistry());
    assertThat(actionGraphContainer.getActionsList()).isNotEmpty();
  }

  @Test
  public void testAqueryProgressMessage() throws Exception {
    write(
        "x/BUILD",
        """
        genrule(
            name = "x",
            srcs = ["in"],
            outs = ["out"],
            cmd = "touch $(OUTS)",
        )
        """);
    write("x/in", "");
    buildTarget("//x");

    addOptions("--output=proto", "--skyframe_state");
    CommandEnvironment env = runtimeWrapper.newCommand(AqueryCommand.class);
    ByteArrayOutputStream stdout = captureStdout(env);

    AqueryProcessor aqueryProcessor = new AqueryProcessor(null, TargetPattern.defaultParser());
    BlazeCommandResult result = aqueryProcessor.dumpActionGraphFromSkyframe(env);
    assertThat(result.isSuccess()).isTrue();

    ActionGraphContainer actionGraphContainer =
        ActionGraphContainer.parseFrom(stdout.toByteArray(), ExtensionRegistry.getEmptyRegistry());
    Action genruleAction =
        actionGraphContainer.getActionsList().stream()
            .filter(action -> action.getMnemonic().equals("Genrule"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No Genrule action found in the action graph."));

    assertThat(genruleAction.getProgressMessage()).contains("Executing genrule //x:x");
  }

  private void writeMatchFixture() throws Exception {
    write(
        "x/defs.bzl",
        """
        def _extract_impl(ctx):
            out = ctx.actions.declare_directory(ctx.attr.name + "_tree")
            ctx.actions.run_shell(
                outputs = [out],
                command = "mkdir -p %s/bin && echo -n hi > %s/bin/data" % (out.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]

        extract = rule(implementation = _extract_impl)

        def _consume_impl(ctx):
            tree = ctx.attr.src[DefaultInfo].files.to_list()[0]
            sel = ctx.actions.match_files(sources = [tree], include = ["bin/*"])
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            ctx.actions.run_shell(
                inputs = [sel],
                outputs = [out],
                command = "cat %s/bin/data > %s" % (tree.path, out.path),
            )
            return [DefaultInfo(files = depset([out]))]

        consume = rule(
            implementation = _consume_impl,
            attrs = {"src": attr.label()},
        )
        """);
    write(
        "x/BUILD",
        """
        load(":defs.bzl", "extract", "consume")

        extract(name = "archive")
        consume(name = "consume", src = ":archive")
        """);
  }

  @Test
  public void aquery_fileMatch_proto_referencesSourceTreeAndPatterns() throws Exception {
    writeMatchFixture();
    addOptions("--experimental_tree_artifact_selection");
    buildTarget("//x:consume");

    addOptions("--output=proto", "--skyframe_state");
    CommandEnvironment env = runtimeWrapper.newCommand(AqueryCommand.class);
    ByteArrayOutputStream stdout = captureStdout(env);
    BlazeCommandResult result =
        new AqueryProcessor(null, TargetPattern.defaultParser()).dumpActionGraphFromSkyframe(env);
    assertThat(result.isSuccess()).isTrue();

    ActionGraphContainer container =
        ActionGraphContainer.parseFrom(stdout.toByteArray(), ExtensionRegistry.getEmptyRegistry());

    assertThat(container.getFileMatchesList()).hasSize(1);
    FileMatch fileMatch = container.getFileMatches(0);
    assertThat(fileMatch.getCardinality()).isEqualTo("SET");
    assertThat(fileMatch.getIncludeList()).containsExactly("bin/*");
    assertThat(fileMatch.getExcludeDirectories()).isTrue();
    assertThat(fileMatch.getSourceArtifactIdsList()).isNotEmpty();
    assertThat(fileMatch.getFilterFunction()).isEmpty();

    // The consuming action references the match.
    Action consumeAction =
        container.getActionsList().stream()
            .filter(a -> !a.getFileMatchIdsList().isEmpty())
            .findFirst()
            .orElseThrow(() -> new AssertionError("No action with file_match_ids found."));
    assertThat(consumeAction.getFileMatchIdsList()).containsExactly(fileMatch.getId());
  }

  @Test
  public void aquery_fileMatch_textprotoOutput_serializesFields() throws Exception {
    writeMatchFixture();
    addOptions("--experimental_tree_artifact_selection");
    buildTarget("//x:consume");

    // textproto goes through the streamed v2 proto handler, exercising serialization of the new
    // file_matches table and Action.file_match_ids field.
    addOptions("--output=textproto", "--skyframe_state");
    CommandEnvironment env = runtimeWrapper.newCommand(AqueryCommand.class);
    ByteArrayOutputStream stdout = captureStdout(env);
    BlazeCommandResult result =
        new AqueryProcessor(null, TargetPattern.defaultParser()).dumpActionGraphFromSkyframe(env);
    assertThat(result.isSuccess()).isTrue();

    String text = stdout.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(text).contains("file_matches {");
    assertThat(text).contains("cardinality: \"SET\"");
    assertThat(text).contains("include: \"bin/*\"");
    assertThat(text).contains("file_match_ids:");
  }

  private ByteArrayOutputStream captureStdout(CommandEnvironment env) {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    env.getReporter()
        .addHandler(
            event -> {
              if (event.getKind().equals(EventKind.STDOUT)) {
                try {
                  stdout.write(event.getMessageBytes());
                } catch (IOException e) {
                  throw new IllegalStateException(e);
                }
              }
            });
    return stdout;
  }
}
