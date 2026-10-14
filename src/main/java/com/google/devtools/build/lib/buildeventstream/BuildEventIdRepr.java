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
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Memory-efficient identifier of a build event, converted to a {@link BuildEventId} proto only
 * when the event is serialized.
 *
 * <p>Since event identifiers need to be created before the actual event, they are highly
 * structured so that equal identifiers can easily be generated.
 */
public sealed interface BuildEventIdRepr {
  BuildEventIdRepr BUILD_STARTED = new BuildStartedId();
  BuildEventIdRepr UNSTRUCTURED_COMMAND_LINE = new UnstructuredCommandLineId();
  BuildEventIdRepr WORKSPACE_STATUS = new WorkspaceStatusId();
  BuildEventIdRepr OPTIONS_PARSED = new OptionsParsedId();
  BuildEventIdRepr BUILD_FINISHED = new BuildFinishedId();
  BuildEventIdRepr BUILD_TOOL_LOGS = new BuildToolLogsId();
  BuildEventIdRepr BUILD_METRICS = new BuildMetricsId();
  BuildEventIdRepr WORKSPACE_CONFIG = new WorkspaceConfigId();
  BuildEventIdRepr BUILD_METADATA = new BuildMetadataId();
  BuildEventIdRepr CONVENIENCE_SYMLINKS_IDENTIFIED = new ConvenienceSymlinksIdentifiedId();
  BuildEventIdRepr EXEC_REQUEST = new ExecRequestId();

  BuildEventId toProto();

  private static BuildEventId.ConfigurationId configurationIdProto(String id) {
    return BuildEventId.ConfigurationId.newBuilder().setId(id).build();
  }

  record UnknownBuildEventId(String details) implements BuildEventIdRepr {
    @Override
    public BuildEventId toProto() {
      return BuildEventId.newBuilder()
          .setUnknown(BuildEventId.UnknownBuildEventId.newBuilder().setDetails(details))
          .build();
    }
  }

  record ProgressId(int opaqueCount) implements BuildEventIdRepr {
    @Override
    public BuildEventId toProto() {
      return BuildEventId.newBuilder()
          .setProgress(BuildEventId.ProgressId.newBuilder().setOpaqueCount(opaqueCount))
          .build();
    }
  }

  /** Prefer {@link #BUILD_STARTED}. */
  record BuildStartedId() implements BuildEventIdRepr {
    private static final BuildEventId PROTO =
        BuildEventId.newBuilder()
            .setStarted(BuildEventId.BuildStartedId.getDefaultInstance())
            .build();

    @Override
    public BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #UNSTRUCTURED_COMMAND_LINE}. */
  record UnstructuredCommandLineId() implements BuildEventIdRepr {
    private static final BuildEventId PROTO =
        BuildEventId.newBuilder()
            .setUnstructuredCommandLine(BuildEventId.UnstructuredCommandLineId.getDefaultInstance())
            .build();

    @Override
    public BuildEventId toProto() {
      return PROTO;
    }
  }

  record StructuredCommandLineId(String commandLineLabel) implements BuildEventIdRepr {
    @Override
    public BuildEventId toProto() {
      return BuildEventId.newBuilder()
          .setStructuredCommandLine(
              BuildEventId.StructuredCommandLineId.newBuilder()
                  .setCommandLineLabel(commandLineLabel))
          .build();
    }
  }

  /** Prefer {@link #WORKSPACE_STATUS}. */
  record WorkspaceStatusId() implements BuildEventIdRepr {
    private static final BuildEventId PROTO =
        BuildEventId.newBuilder()
            .setWorkspaceStatus(BuildEventId.WorkspaceStatusId.getDefaultInstance())
            .build();

    @Override
    public BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #OPTIONS_PARSED}. */
  record OptionsParsedId() implements BuildEventIdRepr {
    private static final BuildEventId PROTO =
        BuildEventId.newBuilder()
            .setOptionsParsed(BuildEventId.OptionsParsedId.getDefaultInstance())
            .build();

    @Override
    public BuildEventId toProto() {
      return PROTO;
    }
  }

  record FetchId(String url, BuildEventId.FetchId.Downloader downloader)
      implements BuildEventIdRepr {
    @Override
    public BuildEventId toProto() {
      return BuildEventId.newBuilder()
          .setFetch(BuildEventId.FetchId.newBuilder().setUrl(url).setDownloader(downloader))
          .build();
    }
  }

  record ConfigurationId(String id) implements BuildEventIdRepr {
    /** Checksum used for the null configuration (e.g. source files). */
    public static final String NULL_CHECKSUM = "none";

    public static final ConfigurationId NULL = new ConfigurationId(NULL_CHECKSUM);

    public ConfigurationId {
      requireNonNull(id);
    }

    public BuildEventId.ConfigurationId toConfigurationIdProto() {
      return configurationIdProto(id);
    }

    @Override
    public BuildEventId toProto() {
      return BuildEventId.newBuilder().setConfiguration(toConfigurationIdProto()).build();
    }
  }

  record TargetConfiguredId(Label label, @Nullable String aspect) implements BuildEventIdRepr {
    public TargetConfiguredId {
      requireNonNull(label);
      aspect = emptyToNull(aspect);
    }

    @Override
    public BuildEventId toProto() {
      BuildEventId.TargetConfiguredId.Builder configuredId =
          BuildEventId.TargetConfiguredId.newBuilder().setLabel(label.toString());
      if (aspect != null) {
        configuredId.setAspect(aspect);
      }
      return BuildEventId.newBuilder().setTargetConfigured(configuredId).build();
    }
  }

  record PatternExpandedId(List<String> pattern) implements BuildEventIdRepr {
    public PatternExpandedId {
      pattern = ImmutableList.copyOf(pattern);
    }

    @Override
    public BuildEventId toProto() {
      return BuildEventId.newBuilder()
          .setPattern(BuildEventId.PatternExpandedId.newBuilder().addAllPattern(pattern))
          .build();
    }
  }

  record PatternSkippedId(List<String> pattern) implements BuildEventIdRepr {
    public PatternSkippedId {
      pattern = ImmutableList.copyOf(pattern);
    }

    @Override
    public BuildEventId toProto() {
      return BuildEventId.newBuilder()
          .setPatternSkipped(BuildEventId.PatternExpandedId.newBuilder().addAllPattern(pattern))
          .build();
    }
  }

  record NamedSetOfFilesId(String id) implements BuildEventIdRepr {
    @Override
    public BuildEventId toProto() {
      return BuildEventId.newBuilder()
          .setNamedSet(BuildEventId.NamedSetOfFilesId.newBuilder().setId(id))
          .build();
    }
  }

  record TargetCompletedId(Label label, String configurationId, @Nullable String aspect)
      implements BuildEventIdRepr {
    public TargetCompletedId {
      requireNonNull(label);
      requireNonNull(configurationId);
      aspect = emptyToNull(aspect);
    }

    @Override
    public BuildEventId toProto() {
      BuildEventId.TargetCompletedId.Builder targetId =
          BuildEventId.TargetCompletedId.newBuilder()
              .setLabel(label.toString())
              .setConfiguration(configurationIdProto(configurationId));
      if (aspect != null) {
        targetId.setAspect(aspect);
      }
      return BuildEventId.newBuilder().setTargetCompleted(targetId).build();
    }
  }

  record ActionCompletedId(
      PathFragment primaryOutput, @Nullable Label label, @Nullable String configurationId)
      implements BuildEventIdRepr {
    public ActionCompletedId {
      requireNonNull(primaryOutput);
      configurationId = emptyToNull(configurationId);
    }

    @Override
    public BuildEventId toProto() {
      BuildEventId.ActionCompletedId.Builder actionId =
          BuildEventId.ActionCompletedId.newBuilder().setPrimaryOutput(primaryOutput.toString());
      if (label != null) {
        actionId.setLabel(label.toString());
      }
      if (configurationId != null) {
        actionId.setConfiguration(configurationIdProto(configurationId));
      }
      return BuildEventId.newBuilder().setActionCompleted(actionId).build();
    }
  }

  record UnconfiguredLabelId(Label label) implements BuildEventIdRepr {
    @Override
    public BuildEventId toProto() {
      return BuildEventId.newBuilder()
          .setUnconfiguredLabel(
              BuildEventId.UnconfiguredLabelId.newBuilder().setLabel(label.toString()))
          .build();
    }
  }

  record ConfiguredLabelId(Label label, String configurationId) implements BuildEventIdRepr {
    public ConfiguredLabelId {
      requireNonNull(configurationId);
    }

    @Override
    public BuildEventId toProto() {
      return BuildEventId.newBuilder()
          .setConfiguredLabel(
              BuildEventId.ConfiguredLabelId.newBuilder()
                  .setLabel(label.toString())
                  .setConfiguration(configurationIdProto(configurationId)))
          .build();
    }
  }

  record TestResultId(Label label, String configurationId, int run, int shard, int attempt)
      implements BuildEventIdRepr {
    public TestResultId {
      requireNonNull(configurationId);
    }

    @Override
    public BuildEventId toProto() {
      return BuildEventId.newBuilder()
          .setTestResult(
              BuildEventId.TestResultId.newBuilder()
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
      implements BuildEventIdRepr {
    public TestProgressId {
      requireNonNull(configurationId);
    }

    @Override
    public BuildEventId toProto() {
      return BuildEventId.newBuilder()
          .setTestProgress(
              BuildEventId.TestProgressId.newBuilder()
                  .setLabel(label.toString())
                  .setConfiguration(configurationIdProto(configurationId))
                  .setRun(run)
                  .setShard(shard)
                  .setAttempt(attempt)
                  .setOpaqueCount(opaqueCount))
          .build();
    }
  }

  record TestSummaryId(Label label, String configurationId) implements BuildEventIdRepr {
    public TestSummaryId {
      requireNonNull(configurationId);
    }

    @Override
    public BuildEventId toProto() {
      return BuildEventId.newBuilder()
          .setTestSummary(
              BuildEventId.TestSummaryId.newBuilder()
                  .setLabel(label.toString())
                  .setConfiguration(configurationIdProto(configurationId)))
          .build();
    }
  }

  record TargetSummaryId(Label label, String configurationId) implements BuildEventIdRepr {
    public TargetSummaryId {
      requireNonNull(configurationId);
    }

    @Override
    public BuildEventId toProto() {
      return BuildEventId.newBuilder()
          .setTargetSummary(
              BuildEventId.TargetSummaryId.newBuilder()
                  .setLabel(label.toString())
                  .setConfiguration(configurationIdProto(configurationId)))
          .build();
    }
  }

  /** Prefer {@link #BUILD_FINISHED}. */
  record BuildFinishedId() implements BuildEventIdRepr {
    private static final BuildEventId PROTO =
        BuildEventId.newBuilder()
            .setBuildFinished(BuildEventId.BuildFinishedId.getDefaultInstance())
            .build();

    @Override
    public BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #BUILD_TOOL_LOGS}. */
  record BuildToolLogsId() implements BuildEventIdRepr {
    private static final BuildEventId PROTO =
        BuildEventId.newBuilder()
            .setBuildToolLogs(BuildEventId.BuildToolLogsId.getDefaultInstance())
            .build();

    @Override
    public BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #BUILD_METRICS}. */
  record BuildMetricsId() implements BuildEventIdRepr {
    private static final BuildEventId PROTO =
        BuildEventId.newBuilder()
            .setBuildMetrics(BuildEventId.BuildMetricsId.getDefaultInstance())
            .build();

    @Override
    public BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #WORKSPACE_CONFIG}. */
  record WorkspaceConfigId() implements BuildEventIdRepr {
    private static final BuildEventId PROTO =
        BuildEventId.newBuilder()
            .setWorkspace(BuildEventId.WorkspaceConfigId.getDefaultInstance())
            .build();

    @Override
    public BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #BUILD_METADATA}. */
  record BuildMetadataId() implements BuildEventIdRepr {
    private static final BuildEventId PROTO =
        BuildEventId.newBuilder()
            .setBuildMetadata(BuildEventId.BuildMetadataId.getDefaultInstance())
            .build();

    @Override
    public BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #CONVENIENCE_SYMLINKS_IDENTIFIED}. */
  record ConvenienceSymlinksIdentifiedId() implements BuildEventIdRepr {
    private static final BuildEventId PROTO =
        BuildEventId.newBuilder()
            .setConvenienceSymlinksIdentified(
                BuildEventId.ConvenienceSymlinksIdentifiedId.getDefaultInstance())
            .build();

    @Override
    public BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Prefer {@link #EXEC_REQUEST}. */
  record ExecRequestId() implements BuildEventIdRepr {
    private static final BuildEventId PROTO =
        BuildEventId.newBuilder()
            .setExecRequest(BuildEventId.ExecRequestId.getDefaultInstance())
            .build();

    @Override
    public BuildEventId toProto() {
      return PROTO;
    }
  }

  /** Holds the two halves of a {@code PackedFingerprint}, hex-encoded only in {@link #toProto}. */
  record SkyValueUploadedId(long lo, long hi) implements BuildEventIdRepr {
    // Must match PackedFingerprint.toHex().
    private static final VarHandle LONG_ARRAY_HANDLE =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private static final HexFormat HEX_FORMAT = HexFormat.of().withLowerCase();

    @Override
    public BuildEventId toProto() {
      byte[] bytes = new byte[16];
      LONG_ARRAY_HANDLE.set(bytes, 0, lo);
      LONG_ARRAY_HANDLE.set(bytes, 8, hi);
      return BuildEventId.newBuilder()
          .setSkyvalueUploaded(
              BuildEventId.SkyValueUploadedId.newBuilder().setKey(HEX_FORMAT.formatHex(bytes)))
          .build();
    }
  }
}
