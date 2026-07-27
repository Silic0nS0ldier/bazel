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
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetOutputTree;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.MissingDepExecException;
import com.google.devtools.build.lib.actions.RunfilesArtifactValue;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * An {@link InputMetadataProvider} that additionally serves selection resolution results captured
 * during input discovery.
 *
 * <p>All metadata queries delegate to the wrapped provider; only {@link FileSelectionResolver}
 * lookups are answered from the stored map. Consuming actions wrap their execution context's
 * provider with this before spawn construction so command-line expansion can render selections.
 */
public final class SelectionResolvingInputMetadataProvider
    implements InputMetadataProvider, FileSelectionResolver {

  private final InputMetadataProvider delegate;
  private final ImmutableMap<FileSelectionSpec, ImmutableList<Artifact>> resolvedSelections;

  public SelectionResolvingInputMetadataProvider(
      InputMetadataProvider delegate,
      ImmutableMap<FileSelectionSpec, ImmutableList<Artifact>> resolvedSelections) {
    this.delegate = delegate;
    this.resolvedSelections = resolvedSelections;
  }

  @Override
  @Nullable
  public ImmutableList<Artifact> getResolvedSelection(FileSelectionSpec spec) {
    return resolvedSelections.get(spec);
  }

  @Override
  @Nullable
  public FileArtifactValue getInputMetadataChecked(ActionInput input)
      throws InterruptedException, IOException, MissingDepExecException {
    return delegate.getInputMetadataChecked(input);
  }

  @Override
  @Nullable
  public TreeArtifactValue getTreeMetadata(ActionInput input) {
    return delegate.getTreeMetadata(input);
  }

  @Override
  @Nullable
  public TreeArtifactValue getEnclosingTreeMetadata(PathFragment execPath) {
    return delegate.getEnclosingTreeMetadata(execPath);
  }

  @Override
  @Nullable
  public FilesetOutputTree getFileset(ActionInput input) {
    return delegate.getFileset(input);
  }

  @Override
  public Map<Artifact, FilesetOutputTree> getFilesets() {
    return delegate.getFilesets();
  }

  @Override
  @Nullable
  public RunfilesArtifactValue getRunfilesMetadata(ActionInput input) {
    return delegate.getRunfilesMetadata(input);
  }

  @Override
  public ImmutableList<RunfilesTree> getRunfilesTrees() {
    return delegate.getRunfilesTrees();
  }

  @Override
  @Nullable
  public ActionInput getInput(PathFragment execPath) {
    return delegate.getInput(execPath);
  }
}
