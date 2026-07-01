// high level representation of `BuildEventId` protos that are optimised for low memory usage

package com.google.devtools.build.lib.buildeventstream;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.cmdline.Label;
import java.util.List;
import javax.annotation.Nullable;

public sealed interface BuildEventIdRepr {
    BuildEventId toProto();

    private static BuildEventId.ConfigurationId configurationIdProto(String id) {
        return BuildEventId.ConfigurationId.newBuilder().setId(id).build();
    }

    record UnknownBuildEventId(String details) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.UnknownBuildEventId unknownId = BuildEventId.UnknownBuildEventId.newBuilder().setDetails(details).build();
            return BuildEventId.newBuilder().setUnknown(unknownId).build();
        }
    }

    record ProgressId(Integer opaqueCount) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.ProgressId progressId = BuildEventId.ProgressId.newBuilder().setOpaqueCount(opaqueCount).build();
            return BuildEventId.newBuilder().setProgress(progressId).build();
        }
    }

    record BuildStartedId() implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            return BuildEventId.newBuilder().setStarted(BuildEventId.BuildStartedId.getDefaultInstance()).build();
        }
    }

    record UnstructuredCommandLineId() implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            return BuildEventId.newBuilder().setUnstructuredCommandLine(BuildEventId.UnstructuredCommandLineId.getDefaultInstance()).build();
        }
    }

    record StructuredCommandLineId(String commandLineLabel) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.StructuredCommandLineId commandLineId = BuildEventId.StructuredCommandLineId.newBuilder().setCommandLineLabel(commandLineLabel).build();
            return BuildEventId.newBuilder().setStructuredCommandLine(commandLineId).build();
        }
    }

    record WorkspaceStatusId() implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            return BuildEventId.newBuilder().setWorkspaceStatus(BuildEventId.WorkspaceStatusId.getDefaultInstance()).build();
        }
    }

    record OptionsParsedId() implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            return BuildEventId.newBuilder().setOptionsParsed(BuildEventId.OptionsParsedId.getDefaultInstance()).build();
        }
    }

    record FetchId(String url, BuildEventId.FetchId.Downloader downloader) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.FetchId fetchId = BuildEventId.FetchId.newBuilder().setUrl(url).setDownloader(downloader).build();
            return BuildEventId.newBuilder().setFetch(fetchId).build();
        }
    }

    record ConfigurationId(@Nullable String id) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.ConfigurationId.Builder configurationId = BuildEventId.ConfigurationId.newBuilder();
            if (id != null) {
                configurationId.setId(id);
            }
            return BuildEventId.newBuilder().setConfiguration(configurationId).build();
        }
    }

    record TargetConfiguredId(Label label, @Nullable String aspect) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.TargetConfiguredId.Builder configuredId = BuildEventId.TargetConfiguredId.newBuilder().setLabel(label.toString());
            if (aspect != null) {
                configuredId.setAspect(aspect);
            }
            return BuildEventId.newBuilder().setTargetConfigured(configuredId).build();
        }
    }

    record PatternExpandedId(List<String> pattern) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.PatternExpandedId patternId = BuildEventId.PatternExpandedId.newBuilder().addAllPattern(pattern).build();
            return BuildEventId.newBuilder().setPattern(patternId).build();
        }
    }

    record PatternSkippedId(List<String> pattern) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.PatternExpandedId patternId = BuildEventId.PatternExpandedId.newBuilder().addAllPattern(pattern).build();
            return BuildEventId.newBuilder().setPatternSkipped(patternId).build();
        }
    }

    record NamedSetOfFilesId(String id) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.NamedSetOfFilesId namedSetId = BuildEventId.NamedSetOfFilesId.newBuilder().setId(id).build();
            return BuildEventId.newBuilder().setNamedSet(namedSetId).build();
        }
    }

    record TargetCompletedId(Label label, String configurationId, @Nullable String aspect) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.TargetCompletedId.Builder targetId = BuildEventId.TargetCompletedId.newBuilder()
                    .setLabel(label.toString())
                    .setConfiguration(configurationIdProto(configurationId));
            if (aspect != null) {
                targetId.setAspect(aspect);
            }
            return BuildEventId.newBuilder().setTargetCompleted(targetId).build();
        }
    }

    record ActionCompletedId(String primaryOutput, @Nullable Label label, @Nullable String configurationId) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.ActionCompletedId.Builder actionId = BuildEventId.ActionCompletedId.newBuilder().setPrimaryOutput(primaryOutput);
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
            BuildEventId.UnconfiguredLabelId labelId = BuildEventId.UnconfiguredLabelId.newBuilder().setLabel(label.toString()).build();
            return BuildEventId.newBuilder().setUnconfiguredLabel(labelId).build();
        }
    }

    record ConfiguredLabelId(Label label, String configurationId) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.ConfiguredLabelId labelId = BuildEventId.ConfiguredLabelId.newBuilder()
                    .setLabel(label.toString())
                    .setConfiguration(configurationIdProto(configurationId))
                    .build();
            return BuildEventId.newBuilder().setConfiguredLabel(labelId).build();
        }
    }

    record TestResultId(Label label, String configurationId, int run, int shard, int attempt) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.TestResultId resultId = BuildEventId.TestResultId.newBuilder()
                    .setLabel(label.toString())
                    .setConfiguration(configurationIdProto(configurationId))
                    .setRun(run)
                    .setShard(shard)
                    .setAttempt(attempt)
                    .build();
            return BuildEventId.newBuilder().setTestResult(resultId).build();
        }
    }

    record TestProgressId(Label label, String configurationId, int run, int shard, int attempt, int opaqueCount) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.TestProgressId progressId = BuildEventId.TestProgressId.newBuilder()
                    .setLabel(label.toString())
                    .setConfiguration(configurationIdProto(configurationId))
                    .setRun(run)
                    .setShard(shard)
                    .setAttempt(attempt)
                    .setOpaqueCount(opaqueCount)
                    .build();
            return BuildEventId.newBuilder().setTestProgress(progressId).build();
        }
    }

    record TestSummaryId(Label label, String configurationId) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.TestSummaryId summaryId = BuildEventId.TestSummaryId.newBuilder()
                    .setLabel(label.toString())
                    .setConfiguration(configurationIdProto(configurationId))
                    .build();
            return BuildEventId.newBuilder().setTestSummary(summaryId).build();
        }
    }

    record TargetSummaryId(Label label, String configurationId) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.TargetSummaryId summaryId = BuildEventId.TargetSummaryId.newBuilder()
                    .setLabel(label.toString())
                    .setConfiguration(configurationIdProto(configurationId))
                    .build();
            return BuildEventId.newBuilder().setTargetSummary(summaryId).build();
        }
    }

    record BuildFinishedId() implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            return BuildEventId.newBuilder().setBuildFinished(BuildEventId.BuildFinishedId.getDefaultInstance()).build();
        }
    }

    record BuildToolLogsId() implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            return BuildEventId.newBuilder().setBuildToolLogs(BuildEventId.BuildToolLogsId.getDefaultInstance()).build();
        }
    }

    record BuildMetricsId() implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            return BuildEventId.newBuilder().setBuildMetrics(BuildEventId.BuildMetricsId.getDefaultInstance()).build();
        }
    }

    record WorkspaceConfigId() implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            return BuildEventId.newBuilder().setWorkspace(BuildEventId.WorkspaceConfigId.getDefaultInstance()).build();
        }
    }

    record BuildMetadataId() implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            return BuildEventId.newBuilder().setBuildMetadata(BuildEventId.BuildMetadataId.getDefaultInstance()).build();
        }
    }

    record ConvenienceSymlinksIdentifiedId() implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            return BuildEventId.newBuilder().setConvenienceSymlinksIdentified(BuildEventId.ConvenienceSymlinksIdentifiedId.getDefaultInstance()).build();
        }
    }

    record ExecRequestId() implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            return BuildEventId.newBuilder().setExecRequest(BuildEventId.ExecRequestId.getDefaultInstance()).build();
        }
    }

    record SkyValueUploadedId(String key) implements BuildEventIdRepr {
        @Override
        public BuildEventId toProto() {
            BuildEventId.SkyValueUploadedId uploadedId = BuildEventId.SkyValueUploadedId.newBuilder().setKey(key).build();
            return BuildEventId.newBuilder().setSkyvalueUploaded(uploadedId).build();
        }
    }
}
