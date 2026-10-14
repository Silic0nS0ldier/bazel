// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.buildeventstream;

import static com.google.common.base.Strings.emptyToNull;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Memory-efficient identifier of a build event, converted to a {@link
 * BuildEventStreamProtos.BuildEventId} proto only when the event is serialized.
 *
 * <p>Since event identifiers need to be created before the actual event, they are highly structured
 * so that equal identifiers can easily be generated.
 */
public sealed interface BuildEventId {
  BuildEventId BUILD_STARTED = new BuildStartedId();
  BuildEventId UNSTRUCTURED_COMMAND_LINE = new UnstructuredCommandLineId();
  BuildEventId WORKSPACE_STATUS = new WorkspaceStatusId();
  BuildEventId OPTIONS_PARSED = new OptionsParsedId();
  BuildEventId BUILD_FINISHED = new BuildFinishedId();
  BuildEventId BUILD_TOOL_LOGS = new BuildToolLogsId();
  BuildEventId BUILD_METRICS = new BuildMetricsId();
  BuildEventId WORKSPACE_CONFIG = new WorkspaceConfigId();
  BuildEventId BUILD_METADATA = new BuildMetadataId();
  BuildEventId CONVENIENCE_SYMLINKS_IDENTIFIED = new ConvenienceSymlinksIdentifiedId();
  BuildEventId EXEC_REQUEST = new ExecRequestId();

  BuildEventStreamProtos.BuildEventId toProto();

  private static BuildEventStreamProtos.BuildEventId.ConfigurationId configurationIdProto(
      String id) {
    return BuildEventStreamProtos.BuildEventId.ConfigurationId.newBuilder().setId(id).build();
  }

  record UnknownBuildEventId(String details) implements BuildEventId {
    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setUnknown(
              BuildEventStreamProtos.BuildEventId.UnknownBuildEventId.newBuilder()
                  .setDetails(details))
          .build();
    }
  }

  record ProgressId(int opaqueCount) implements BuildEventId {
    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setProgress(
              BuildEventStreamProtos.BuildEventId.ProgressId.newBuilder()
                  .setOpaqueCount(opaqueCount))
          .build();
    }
  }

  /** Prefer {@link #BUILD_STARTED}. */
  record BuildStartedId() implements BuildEventId {
    private static final BuildEventStreamProtos.BuildEventId PROTO =
        BuildEventStreamProtos.BuildEventId.newBuilder()
            .setStarted(BuildEventStreamProtos.BuildEventId.BuildStartedId.getDefaultInstance())
            .build();

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #UNSTRUCTURED_COMMAND_LINE}. */
  record UnstructuredCommandLineId() implements BuildEventId {
    private static final BuildEventStreamProtos.BuildEventId PROTO =
        BuildEventStreamProtos.BuildEventId.newBuilder()
            .setUnstructuredCommandLine(
                BuildEventStreamProtos.BuildEventId.UnstructuredCommandLineId.getDefaultInstance())
            .build();

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return PROTO;
    }
  }

  record StructuredCommandLineId(String commandLineLabel) implements BuildEventId {
    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setStructuredCommandLine(
              BuildEventStreamProtos.BuildEventId.StructuredCommandLineId.newBuilder()
                  .setCommandLineLabel(commandLineLabel))
          .build();
    }
  }

  /** Prefer {@link #WORKSPACE_STATUS}. */
  record WorkspaceStatusId() implements BuildEventId {
    private static final BuildEventStreamProtos.BuildEventId PROTO =
        BuildEventStreamProtos.BuildEventId.newBuilder()
            .setWorkspaceStatus(
                BuildEventStreamProtos.BuildEventId.WorkspaceStatusId.getDefaultInstance())
            .build();

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #OPTIONS_PARSED}. */
  record OptionsParsedId() implements BuildEventId {
    private static final BuildEventStreamProtos.BuildEventId PROTO =
        BuildEventStreamProtos.BuildEventId.newBuilder()
            .setOptionsParsed(
                BuildEventStreamProtos.BuildEventId.OptionsParsedId.getDefaultInstance())
            .build();

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return PROTO;
    }
  }

  record FetchId(String url, BuildEventStreamProtos.BuildEventId.FetchId.Downloader downloader)
      implements BuildEventId {
    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setFetch(
              BuildEventStreamProtos.BuildEventId.FetchId.newBuilder()
                  .setUrl(url)
                  .setDownloader(downloader))
          .build();
    }
  }

  record ConfigurationId(String id) implements BuildEventId {
    /** Checksum used for the null configuration (e.g. source files). */
    public static final String NULL_CHECKSUM = "none";

    public static final ConfigurationId NULL = new ConfigurationId(NULL_CHECKSUM);

    public ConfigurationId {
      requireNonNull(id);
    }

    public BuildEventStreamProtos.BuildEventId.ConfigurationId toConfigurationIdProto() {
      return configurationIdProto(id);
    }

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setConfiguration(toConfigurationIdProto())
          .build();
    }
  }

  record TargetConfiguredId(Label label, @Nullable String aspect) implements BuildEventId {
    public TargetConfiguredId {
      requireNonNull(label);
      aspect = emptyToNull(aspect);
    }

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      BuildEventStreamProtos.BuildEventId.TargetConfiguredId.Builder configuredId =
          BuildEventStreamProtos.BuildEventId.TargetConfiguredId.newBuilder()
              .setLabel(label.toString());
      if (aspect != null) {
        configuredId.setAspect(aspect);
      }
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setTargetConfigured(configuredId)
          .build();
    }
  }

  record PatternExpandedId(List<String> pattern) implements BuildEventId {
    public PatternExpandedId {
      pattern = ImmutableList.copyOf(pattern);
    }

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setPattern(
              BuildEventStreamProtos.BuildEventId.PatternExpandedId.newBuilder()
                  .addAllPattern(pattern))
          .build();
    }
  }

  record PatternSkippedId(List<String> pattern) implements BuildEventId {
    public PatternSkippedId {
      pattern = ImmutableList.copyOf(pattern);
    }

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setPatternSkipped(
              BuildEventStreamProtos.BuildEventId.PatternExpandedId.newBuilder()
                  .addAllPattern(pattern))
          .build();
    }
  }

  record NamedSetOfFilesId(String id) implements BuildEventId {
    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setNamedSet(BuildEventStreamProtos.BuildEventId.NamedSetOfFilesId.newBuilder().setId(id))
          .build();
    }
  }

  record TargetCompletedId(Label label, String configurationId, @Nullable String aspect)
      implements BuildEventId {
    public TargetCompletedId {
      requireNonNull(label);
      requireNonNull(configurationId);
      aspect = emptyToNull(aspect);
    }

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      BuildEventStreamProtos.BuildEventId.TargetCompletedId.Builder targetId =
          BuildEventStreamProtos.BuildEventId.TargetCompletedId.newBuilder()
              .setLabel(label.toString())
              .setConfiguration(configurationIdProto(configurationId));
      if (aspect != null) {
        targetId.setAspect(aspect);
      }
      return BuildEventStreamProtos.BuildEventId.newBuilder().setTargetCompleted(targetId).build();
    }
  }

  record ActionCompletedId(
      PathFragment primaryOutput, @Nullable Label label, @Nullable String configurationId)
      implements BuildEventId {
    public ActionCompletedId {
      requireNonNull(primaryOutput);
      configurationId = emptyToNull(configurationId);
    }

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      BuildEventStreamProtos.BuildEventId.ActionCompletedId.Builder actionId =
          BuildEventStreamProtos.BuildEventId.ActionCompletedId.newBuilder()
              .setPrimaryOutput(primaryOutput.toString());
      if (label != null) {
        actionId.setLabel(label.toString());
      }
      if (configurationId != null) {
        actionId.setConfiguration(configurationIdProto(configurationId));
      }
      return BuildEventStreamProtos.BuildEventId.newBuilder().setActionCompleted(actionId).build();
    }
  }

  record UnconfiguredLabelId(Label label) implements BuildEventId {
    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setUnconfiguredLabel(
              BuildEventStreamProtos.BuildEventId.UnconfiguredLabelId.newBuilder()
                  .setLabel(label.toString()))
          .build();
    }
  }

  record ConfiguredLabelId(Label label, String configurationId) implements BuildEventId {
    public ConfiguredLabelId {
      requireNonNull(configurationId);
    }

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setConfiguredLabel(
              BuildEventStreamProtos.BuildEventId.ConfiguredLabelId.newBuilder()
                  .setLabel(label.toString())
                  .setConfiguration(configurationIdProto(configurationId)))
          .build();
    }
  }

  record TestResultId(Label label, String configurationId, int run, int shard, int attempt)
      implements BuildEventId {
    public TestResultId {
      requireNonNull(configurationId);
    }

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setTestResult(
              BuildEventStreamProtos.BuildEventId.TestResultId.newBuilder()
                  .setLabel(label.toString())
                  .setConfiguration(configurationIdProto(configurationId))
                  .setRun(run)
                  .setShard(shard)
                  .setAttempt(attempt))
          .build();
    }
  }

  record TestProgressId(
      Label label, String configurationId, int run, int shard, int attempt, int opaqueCount)
      implements BuildEventId {
    public TestProgressId {
      requireNonNull(configurationId);
    }

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setTestProgress(
              BuildEventStreamProtos.BuildEventId.TestProgressId.newBuilder()
                  .setLabel(label.toString())
                  .setConfiguration(configurationIdProto(configurationId))
                  .setRun(run)
                  .setShard(shard)
                  .setAttempt(attempt)
                  .setOpaqueCount(opaqueCount))
          .build();
    }
  }

  record TestSummaryId(Label label, String configurationId) implements BuildEventId {
    public TestSummaryId {
      requireNonNull(configurationId);
    }

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setTestSummary(
              BuildEventStreamProtos.BuildEventId.TestSummaryId.newBuilder()
                  .setLabel(label.toString())
                  .setConfiguration(configurationIdProto(configurationId)))
          .build();
    }
  }

  record TargetSummaryId(Label label, String configurationId) implements BuildEventId {
    public TargetSummaryId {
      requireNonNull(configurationId);
    }

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setTargetSummary(
              BuildEventStreamProtos.BuildEventId.TargetSummaryId.newBuilder()
                  .setLabel(label.toString())
                  .setConfiguration(configurationIdProto(configurationId)))
          .build();
    }
  }

  /** Prefer {@link #BUILD_FINISHED}. */
  record BuildFinishedId() implements BuildEventId {
    private static final BuildEventStreamProtos.BuildEventId PROTO =
        BuildEventStreamProtos.BuildEventId.newBuilder()
            .setBuildFinished(
                BuildEventStreamProtos.BuildEventId.BuildFinishedId.getDefaultInstance())
            .build();

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #BUILD_TOOL_LOGS}. */
  record BuildToolLogsId() implements BuildEventId {
    private static final BuildEventStreamProtos.BuildEventId PROTO =
        BuildEventStreamProtos.BuildEventId.newBuilder()
            .setBuildToolLogs(
                BuildEventStreamProtos.BuildEventId.BuildToolLogsId.getDefaultInstance())
            .build();

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #BUILD_METRICS}. */
  record BuildMetricsId() implements BuildEventId {
    private static final BuildEventStreamProtos.BuildEventId PROTO =
        BuildEventStreamProtos.BuildEventId.newBuilder()
            .setBuildMetrics(
                BuildEventStreamProtos.BuildEventId.BuildMetricsId.getDefaultInstance())
            .build();

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #WORKSPACE_CONFIG}. */
  record WorkspaceConfigId() implements BuildEventId {
    private static final BuildEventStreamProtos.BuildEventId PROTO =
        BuildEventStreamProtos.BuildEventId.newBuilder()
            .setWorkspace(
                BuildEventStreamProtos.BuildEventId.WorkspaceConfigId.getDefaultInstance())
            .build();

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #BUILD_METADATA}. */
  record BuildMetadataId() implements BuildEventId {
    private static final BuildEventStreamProtos.BuildEventId PROTO =
        BuildEventStreamProtos.BuildEventId.newBuilder()
            .setBuildMetadata(
                BuildEventStreamProtos.BuildEventId.BuildMetadataId.getDefaultInstance())
            .build();

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #CONVENIENCE_SYMLINKS_IDENTIFIED}. */
  record ConvenienceSymlinksIdentifiedId() implements BuildEventId {
    private static final BuildEventStreamProtos.BuildEventId PROTO =
        BuildEventStreamProtos.BuildEventId.newBuilder()
            .setConvenienceSymlinksIdentified(
                BuildEventStreamProtos.BuildEventId.ConvenienceSymlinksIdentifiedId
                    .getDefaultInstance())
            .build();

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #EXEC_REQUEST}. */
  record ExecRequestId() implements BuildEventId {
    private static final BuildEventStreamProtos.BuildEventId PROTO =
        BuildEventStreamProtos.BuildEventId.newBuilder()
            .setExecRequest(BuildEventStreamProtos.BuildEventId.ExecRequestId.getDefaultInstance())
            .build();

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Holds the two halves of a {@code PackedFingerprint}, hex-encoded only in {@link #toProto}. */
  record SkyValueUploadedId(long lo, long hi) implements BuildEventId {
    // Must match PackedFingerprint.toHex().
    private static final VarHandle LONG_ARRAY_HANDLE =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private static final HexFormat HEX_FORMAT = HexFormat.of().withLowerCase();

    @Override
    public BuildEventStreamProtos.BuildEventId toProto() {
      byte[] bytes = new byte[16];
      LONG_ARRAY_HANDLE.set(bytes, 0, lo);
      LONG_ARRAY_HANDLE.set(bytes, 8, hi);
      return BuildEventStreamProtos.BuildEventId.newBuilder()
          .setSkyvalueUploaded(
              BuildEventStreamProtos.BuildEventId.SkyValueUploadedId.newBuilder()
                  .setKey(HEX_FORMAT.formatHex(bytes)))
          .build();
    }
  }
}
