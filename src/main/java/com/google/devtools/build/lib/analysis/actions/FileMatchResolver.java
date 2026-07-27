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
package com.google.devtools.build.lib.analysis.actions;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import javax.annotation.Nullable;

/**
 * Supplies the results of file match resolution to command-line expansion.
 *
 * <p>Matches are resolved once, during input discovery, against source-tree metadata that is no
 * longer available at spawn construction (the source trees are pruned from the inputs after
 * resolution). The consuming action exposes the stored results by handing spawn construction an
 * {@link InputMetadataProvider} that also implements this interface; expansion code discovers the
 * capability via an {@code instanceof} check.
 */
public interface FileMatchResolver {

  /**
   * Returns the artifacts a match resolved to during input discovery, or {@code null} if the
   * given spec is not among the consuming action's input matches.
   */
  @Nullable
  ImmutableList<Artifact> getResolvedMatch(FileMatchSpec spec);
}
