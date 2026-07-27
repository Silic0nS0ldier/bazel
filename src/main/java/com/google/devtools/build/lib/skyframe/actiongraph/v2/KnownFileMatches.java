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
package com.google.devtools.build.lib.skyframe.actiongraph.v2;

import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.FileMatch;
import com.google.devtools.build.lib.analysis.actions.FileMatchSpec;
import java.io.IOException;

/** Cache for {@code ctx.actions.match_*} declarations ({@link FileMatchSpec}) in the action graph. */
public class KnownFileMatches extends BaseCache<FileMatchSpec, FileMatch> {

  private final KnownArtifacts knownArtifacts;

  KnownFileMatches(AqueryOutputHandler aqueryOutputHandler, KnownArtifacts knownArtifacts) {
    super(aqueryOutputHandler);
    this.knownArtifacts = knownArtifacts;
  }

  @Override
  FileMatch createProto(FileMatchSpec spec, int id) throws IOException, InterruptedException {
    FileMatch.Builder builder =
        FileMatch.newBuilder()
            .setId(id)
            .setCardinality(spec.getCardinality().name())
            .addAllInclude(spec.getInclude())
            .addAllExclude(spec.getExclude())
            .setFilterFunction(spec.getFilterDisplay())
            .setExcludeDirectories(spec.excludesDirectories())
            .setAllowEmpty(spec.allowsEmpty());
    for (SpecialArtifact sourceTree : spec.getSourceTreeArtifacts()) {
      builder.addSourceArtifactIds(knownArtifacts.dataToIdAndStreamOutputProto(sourceTree));
    }
    return builder.build();
  }

  @Override
  void toOutput(FileMatch fileMatchProto) throws IOException {
    aqueryOutputHandler.outputFileMatch(fileMatchProto);
  }
}
