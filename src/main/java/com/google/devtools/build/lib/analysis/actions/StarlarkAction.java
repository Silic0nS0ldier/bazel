// Copyright 2019 The Bazel Authors. All rights reserved.
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

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.ResourceSetOrBuilder;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.CoreOptions.OutputPathsMode;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.StarlarkAction.Code;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ByteString;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/** A Starlark specific SpawnAction. */
public class StarlarkAction extends SpawnAction {

  private StarlarkAction(
      ActionOwner owner,
      NestedSet<Artifact> tools,
      NestedSet<Artifact> inputs,
      Iterable<Artifact> outputs,
      ResourceSetOrBuilder resourceSetOrBuilder,
      CommandLines commandLines,
      ActionEnvironment env,
      ImmutableMap<String, String> executionInfo,
      CharSequence progressMessage,
      String mnemonic,
      OutputPathsMode outputPathsMode) {
    super(
        owner,
        tools,
        inputs,
        outputs,
        resourceSetOrBuilder,
        commandLines,
        env,
        executionInfo,
        progressMessage,
        mnemonic,
        outputPathsMode);
  }

  /** Constructor for serialization. */
  private StarlarkAction(
      ActionOwner owner,
      NestedSet<Artifact> tools,
      NestedSet<Artifact> inputs,
      Object rawOutputs,
      ResourceSetOrBuilder resourceSetOrBuilder,
      CommandLines commandLines,
      ActionEnvironment env,
      ImmutableSortedMap<String, String> sortedExecutionInfo,
      CharSequence progressMessage,
      String mnemonic,
      OutputPathsMode outputPathsMode) {
    super(
        owner,
        tools,
        inputs,
        rawOutputs,
        resourceSetOrBuilder,
        commandLines,
        env,
        sortedExecutionInfo,
        progressMessage,
        mnemonic,
        outputPathsMode);
  }

  @VisibleForTesting
  public Optional<Artifact> getUnusedInputsList() {
    return Optional.empty();
  }

  @Override
  public NestedSet<Artifact> getInputFilesForExtraAction(
      ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    return getInputs();
  }

  private static FailureDetail createFailureDetail(String message, Code detailedCode) {
    return FailureDetail.newBuilder()
        .setMessage(message)
        .setStarlarkAction(FailureDetails.StarlarkAction.newBuilder().setCode(detailedCode))
        .build();
  }

  @SafeVarargs
  private static NestedSet<Artifact> createInputs(NestedSet<Artifact>... inputsLists) {
    NestedSetBuilder<Artifact> nestedSetBuilder = NestedSetBuilder.newBuilder(Order.STABLE_ORDER);
    for (NestedSet<Artifact> inputs : inputsLists) {
      nestedSetBuilder.addTransitive(inputs);
    }
    return nestedSetBuilder.build();
  }

  /** Builder class to construct {@link StarlarkAction} instances. */
  public static class Builder extends SpawnAction.Builder {

    private Optional<Artifact> unusedInputsList = Optional.empty();
    private Optional<Action> shadowedAction = Optional.empty();
    private ImmutableList<FileSelectionSpec> inputSelections = ImmutableList.of();

    @CanIgnoreReturnValue
    public Builder setUnusedInputsList(Optional<Artifact> unusedInputsList) {
      this.unusedInputsList = unusedInputsList;
      return this;
    }

    /** Adds file selections to be resolved to concrete children and staged as action inputs. */
    @CanIgnoreReturnValue
    public Builder addInputSelections(List<FileSelectionSpec> selections) {
      if (!selections.isEmpty()) {
        this.inputSelections =
            ImmutableList.<FileSelectionSpec>builder()
                .addAll(this.inputSelections)
                .addAll(selections)
                .build();
      }
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setShadowedAction(Optional<Action> shadowedAction) {
      this.shadowedAction = shadowedAction;
      return this;
    }

    /** Creates a SpawnAction. */
    @Override
    protected SpawnAction createSpawnAction(
        ActionOwner owner,
        NestedSet<Artifact> tools,
        NestedSet<Artifact> inputsAndTools,
        ImmutableSet<Artifact> outputs,
        ResourceSetOrBuilder resourceSetOrBuilder,
        CommandLines commandLines,
        ActionEnvironment env,
        @Nullable BuildConfigurationValue configuration,
        ImmutableMap<String, String> executionInfo,
        CharSequence progressMessage,
        String mnemonic) {
      if (unusedInputsList.isPresent()) {
        // Always download unused_inputs_list file from remote cache.
        executionInfo =
            ImmutableMap.<String, String>builderWithExpectedSize(executionInfo.size() + 1)
                .putAll(executionInfo)
                .put(
                    ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS,
                    unusedInputsList.get().getExecPathString())
                .buildOrThrow();
      }
      OutputPathsMode outputPathsMode = PathMappers.getOutputPathsMode(configuration);
      return unusedInputsList.isPresent() || shadowedAction.isPresent() || !inputSelections.isEmpty()
          ? new EnhancedStarlarkAction(
              owner,
              tools,
              inputsAndTools,
              outputs,
              resourceSetOrBuilder,
              commandLines,
              env,
              executionInfo,
              progressMessage,
              mnemonic,
              outputPathsMode,
              unusedInputsList,
              shadowedAction,
              inputSelections)
          : new StarlarkAction(
              owner,
              tools,
              inputsAndTools,
              outputs,
              resourceSetOrBuilder,
              commandLines,
              env,
              executionInfo,
              progressMessage,
              mnemonic,
              outputPathsMode);
    }
  }

  /** A {@link StarlarkAction} with {@code unused_inputs_list} and/or a shadowed action present. */
  @AutoCodec
  @VisibleForSerialization
  static final class EnhancedStarlarkAction extends StarlarkAction {
    // All the inputs of the Starlark action including those listed in the unused inputs and
    // excluding the shadowed action inputs.
    private final NestedSet<Artifact> allStarlarkActionInputs;
    // allStarlarkActionInputs plus shadowed action inputs, if present.
    private final NestedSet<Artifact> originalInputs;

    // Null when there is no shadowed action.
    @Nullable private final NestedSet<Artifact> mandatoryInputs;

    private final Optional<Artifact> unusedInputsList;
    private final Optional<Action> shadowedAction;
    // File selections consumed as action inputs; resolved to concrete children in discoverInputs.
    private final ImmutableList<FileSelectionSpec> inputSelections;
    // The distinct source tree artifacts backing inputSelections (scheduling deps, not staged).
    private final NestedSet<Artifact> selectionSourceTrees;
    private boolean inputsDiscovered = false;
    private boolean prunedInputs = false;

    EnhancedStarlarkAction(
        ActionOwner owner,
        NestedSet<Artifact> tools,
        NestedSet<Artifact> inputs,
        Iterable<Artifact> outputs,
        ResourceSetOrBuilder resourceSetOrBuilder,
        CommandLines commandLines,
        ActionEnvironment env,
        ImmutableMap<String, String> executionInfo,
        CharSequence progressMessage,
        String mnemonic,
        OutputPathsMode outputPathsMode,
        Optional<Artifact> unusedInputsList,
        Optional<Action> shadowedAction,
        ImmutableList<FileSelectionSpec> inputSelections) {
      super(
          owner,
          tools,
          shadowedAction.isPresent()
              ? createInputs(shadowedAction.get().getInputs(), inputs)
              : inputs,
          outputs,
          resourceSetOrBuilder,
          commandLines,
          env,
          executionInfo,
          progressMessage,
          mnemonic,
          outputPathsMode);
      this.allStarlarkActionInputs = inputs;
      this.originalInputs = getInputs();
      this.mandatoryInputs =
          shadowedAction.isPresent()
              ? createInputs(shadowedAction.get().getMandatoryInputs(), inputs)
              : null;
      this.unusedInputsList = unusedInputsList;
      this.shadowedAction = shadowedAction;
      this.inputSelections = inputSelections;
      this.selectionSourceTrees = collectSelectionSourceTrees(inputSelections);
    }

    @AutoCodec.Instantiator
    @VisibleForSerialization
    EnhancedStarlarkAction(
        ActionOwner owner,
        NestedSet<Artifact> tools,
        NestedSet<Artifact> allStarlarkActionInputs,
        Object rawOutputs,
        ResourceSetOrBuilder resourceSetOrBuilder,
        CommandLines commandLines,
        ActionEnvironment environment,
        ImmutableSortedMap<String, String> sortedExecutionInfo,
        CharSequence progressMessage,
        String mnemonic,
        OutputPathsMode outputPathsMode,
        Optional<Artifact> unusedInputsList,
        Optional<Action> shadowedAction,
        ImmutableList<FileSelectionSpec> inputSelections) {
      super(
          owner,
          tools,
          shadowedAction.isPresent()
              ? createInputs(shadowedAction.get().getInputs(), allStarlarkActionInputs)
              : allStarlarkActionInputs,
          rawOutputs,
          resourceSetOrBuilder,
          commandLines,
          environment,
          sortedExecutionInfo,
          progressMessage,
          mnemonic,
          outputPathsMode);
      this.allStarlarkActionInputs = allStarlarkActionInputs;
      this.originalInputs = getInputs();
      this.mandatoryInputs =
          shadowedAction.isPresent()
              ? createInputs(shadowedAction.get().getMandatoryInputs(), allStarlarkActionInputs)
              : null;
      this.unusedInputsList = unusedInputsList;
      this.shadowedAction = shadowedAction;
      this.inputSelections = inputSelections;
      this.selectionSourceTrees = collectSelectionSourceTrees(inputSelections);
    }

    private static NestedSet<Artifact> collectSelectionSourceTrees(
        ImmutableList<FileSelectionSpec> inputSelections) {
      NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
      for (FileSelectionSpec spec : inputSelections) {
        builder.addAll(spec.getSourceTreeArtifacts());
      }
      return builder.build();
    }

    @Override
    public NestedSet<Artifact> getSchedulingDependencies() {
      return shadowedAction.isPresent()
          ? shadowedAction.get().getSchedulingDependencies()
          : NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    @Override
    public Optional<Artifact> getUnusedInputsList() {
      return unusedInputsList;
    }

    @Override
    public boolean isShareable() {
      return unusedInputsList.isEmpty();
    }

    @Override
    public boolean discoversInputs() {
      return unusedInputsList.isPresent()
          || !inputSelections.isEmpty()
          || (shadowedAction.isPresent() && shadowedAction.get().discoversInputs());
    }

    @Override
    public boolean prunedInputs() {
      return prunedInputs;
    }

    @Override
    public NestedSet<Artifact> getOriginalInputs() {
      return originalInputs;
    }

    @Override
    protected boolean inputsDiscovered() {
      return inputsDiscovered;
    }

    @Override
    protected void setInputsDiscovered(boolean inputsDiscovered) {
      this.inputsDiscovered = inputsDiscovered;
    }

    @Override
    public NestedSet<Artifact> getMandatoryInputs() {
      return mandatoryInputs != null ? mandatoryInputs : getInputs();
    }

    @Override
    public NestedSet<Artifact> getAllowedDerivedInputs() {
      // Source trees must be allowed so the discovered-inputs action cache can re-resolve stored
      // child paths against them.
      NestedSet<Artifact> base =
          shadowedAction.isPresent()
              ? createInputs(shadowedAction.get().getAllowedDerivedInputs(), getInputs())
              : getInputs();
      if (selectionSourceTrees.isEmpty()) {
        return base;
      }
      return createInputs(base, selectionSourceTrees);
    }

    @Nullable
    @Override
    public NestedSet<Artifact> discoverInputs(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException, InterruptedException {
      // Resolve any file selections against the now-available source-tree metadata into concrete
      // children. Only the resolved children join the inputs; the source trees are not staged.
      NestedSet<Artifact> resolvedSelectionChildren = resolveInputSelections(actionExecutionContext);

      // If the Starlark action shadows another action and the shadowed action discovers its inputs,
      // we get the shadowed action's discovered inputs and append it to the Starlark action inputs.
      if (shadowedAction.isPresent() && shadowedAction.get().discoversInputs()) {
        Action shadowedActionObj = shadowedAction.get();

        NestedSet<Artifact> oldInputs = getInputs();
        NestedSet<Artifact> inputFilesForExtraAction =
            shadowedActionObj.getInputFilesForExtraAction(actionExecutionContext);
        if (inputFilesForExtraAction == null) {
          return null;
        }
        updateInputs(
            createInputs(
                shadowedActionObj.getInputs(),
                inputFilesForExtraAction,
                inputsWithoutSelectionSourceTrees(),
                resolvedSelectionChildren));
        return NestedSetBuilder.wrap(
            Order.STABLE_ORDER, Sets.difference(getInputs().toSet(), oldInputs.toSet()));
      }
      // Otherwise, we need to "re-discover" all the original inputs: the unused ones that were
      // removed might now be needed. The selection source trees are pruned, leaving only the
      // resolved children — so the consumer's footprint is the resolved subset, not the whole tree.
      NestedSet<Artifact> discovered =
          resolvedSelectionChildren.isEmpty()
              ? allStarlarkActionInputs
              : createInputs(inputsWithoutSelectionSourceTrees(), resolvedSelectionChildren);
      updateInputs(discovered);
      return discovered;
    }

    /**
     * {@link #allStarlarkActionInputs} with the selection source trees removed. The trees are
     * declared inputs only so their metadata is available during discovery; once selections resolve
     * to concrete children, the trees themselves are neither staged nor part of the cache key.
     */
    private NestedSet<Artifact> inputsWithoutSelectionSourceTrees() {
      if (selectionSourceTrees.isEmpty()) {
        return allStarlarkActionInputs;
      }
      ImmutableSet<Artifact> trees = ImmutableSet.copyOf(selectionSourceTrees.toList());
      NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
      for (Artifact input : allStarlarkActionInputs.toList()) {
        if (!trees.contains(input)) {
          builder.add(input);
        }
      }
      return builder.build();
    }

    /**
     * Resolves each input selection against the source trees' now-available metadata, returning the
     * union of resolved children. A resolution failure (empty/ambiguous/kind/filter error) fails
     * this action with the selection's diagnostic.
     */
    private NestedSet<Artifact> resolveInputSelections(
        ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException, InterruptedException {
      if (inputSelections.isEmpty()) {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
      }
      NestedSetBuilder<Artifact> children = NestedSetBuilder.stableOrder();
      for (FileSelectionSpec spec : inputSelections) {
        try {
          for (Artifact member :
              spec.resolve(
                  actionExecutionContext.getInputMetadataProvider(),
                  actionExecutionContext.getEventHandler())) {
            // Directory members (subtree artifacts) cannot yet be staged as standalone action
            // inputs — the same v1 limitation as pick_directory. Fail clearly rather than crash.
            if (member.isTreeArtifact()) {
              throw new FileSelectionSpec.SelectionResolutionException(
                  "directory members of a selection are not yet supported as action inputs (from "
                      + member.getExecPathString()
                      + "); select regular files, or materialise the directory with a copy action");
            }
            children.add(member);
          }
        } catch (FileSelectionSpec.SelectionResolutionException e) {
          throw new ActionExecutionException(
              e.getMessage(),
              this,
              /* catastrophe= */ false,
              DetailedExitCode.of(
                  createFailureDetail(e.getMessage(), Code.FILE_SELECTION_RESOLUTION_FAILURE)));
        }
      }
      return children.build();
    }

    private InputStream getUnusedInputListInputStream(
        ActionExecutionContext actionExecutionContext, List<SpawnResult> spawnResults)
        throws IOException, ExecException {

      // Check if the file is in-memory.
      // Note: SpawnActionContext guarantees that the first list entry exists and corresponds to the
      // executed spawn.
      Artifact unusedInputsListArtifact = unusedInputsList.get();
      ByteString content = spawnResults.get(0).getInMemoryOutput(unusedInputsListArtifact);
      if (content != null) {
        return content.newInput();
      }
      // Fallback to reading from disk.
      try {
        return actionExecutionContext
            .getPathResolver()
            .toPath(unusedInputsListArtifact)
            .getInputStream();
      } catch (FileNotFoundException e) {
        String message =
            "Action did not create expected output file listing unused inputs: "
                + unusedInputsListArtifact.getExecPathString();
        throw new UserExecException(
            e, createFailureDetail(message, Code.UNUSED_INPUT_LIST_FILE_NOT_FOUND));
      }
    }

    @Override
    protected void afterExecute(
        ActionExecutionContext actionExecutionContext,
        List<SpawnResult> spawnResults,
        PathMapper pathMapper)
        throws ExecException {
      if (unusedInputsList.isEmpty()) {
        return;
      }

      // Initialized lazily in case there are no unused inputs.
      Map<String, Artifact> usedInputsByMappedPath = null;

      boolean sawUnusedInput = false;

      // Bazel encodes file system paths as raw bytes stored in a Latin-1 encoded string, so we need
      // to make sure to also decode the unused input list as Latin-1.
      try (BufferedReader br =
          new BufferedReader(
              new InputStreamReader(
                  getUnusedInputListInputStream(actionExecutionContext, spawnResults),
                  ISO_8859_1))) {
        String line;
        while ((line = br.readLine()) != null) {
          line = line.trim();
          if (line.isEmpty()) {
            continue;
          }
          if (usedInputsByMappedPath == null) {
            // Get all the action's inputs after execution which will include the shadowed action
            // discovered inputs.
            ImmutableList<Artifact> allInputs = getInputs().toList();
            usedInputsByMappedPath = Maps.newHashMapWithExpectedSize(allInputs.size());
            for (Artifact input : allInputs) {
              usedInputsByMappedPath.put(pathMapper.getMappedExecPathString(input), input);
            }
          }
          if (usedInputsByMappedPath.remove(line) != null) {
            sawUnusedInput = true;
          }
        }
      } catch (IOException e) {
        throw new EnvironmentalExecException(
            e,
            createFailureDetail("Unused inputs read failure", Code.UNUSED_INPUT_LIST_READ_FAILURE));
      }

      prunedInputs = sawUnusedInput;
      if (sawUnusedInput) {
        updateInputs(NestedSetBuilder.wrap(Order.STABLE_ORDER, usedInputsByMappedPath.values()));
      }
    }

    @Override
    Spawn getSpawnForExtraActionSpawnInfo()
        throws CommandLineExpansionException, InterruptedException {
      if (shadowedAction.isPresent()) {
        return this.getSpawnForExtraActionSpawnInfo(
            createInputs(shadowedAction.get().getInputs(), allStarlarkActionInputs));
      }
      return this.getSpawnForExtraActionSpawnInfo(allStarlarkActionInputs);
    }

    @Nullable
    @Override
    public NestedSet<Artifact> getInputFilesForExtraAction(
        ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException, InterruptedException {
      if (shadowedAction.isEmpty()) {
        return allStarlarkActionInputs;
      }
      NestedSet<Artifact> inputFilesForExtraAction =
          shadowedAction.get().getInputFilesForExtraAction(actionExecutionContext);
      if (inputFilesForExtraAction == null) {
        return null;
      }
      return createInputs(inputFilesForExtraAction, allStarlarkActionInputs);
    }

    @Override
    public ImmutableMap<String, String> getEffectiveEnvironment(
        Map<String, String> clientEnv, PathMapper pathMapper) throws CommandLineExpansionException {
      ActionEnvironment env = getEnvironment();
      Map<String, String> environment = Maps.newLinkedHashMapWithExpectedSize(env.estimatedSize());

      if (shadowedAction.isPresent()) {
        // Put all the variables of the shadowed action's environment
        environment.putAll(shadowedAction.get().getEffectiveEnvironment(clientEnv, pathMapper));
      }

      // This order guarantees that the Starlark action can overwrite any variable in its shadowed
      // action environment with a new value.
      env.resolve(environment, clientEnv);
      return ImmutableMap.copyOf(environment);
    }

    @Override
    protected void computeKey(
        ActionKeyContext actionKeyContext,
        @Nullable InputMetadataProvider inputMetadataProvider,
        Fingerprint fp)
        throws CommandLineExpansionException, InterruptedException {
      super.computeKey(actionKeyContext, inputMetadataProvider, fp);
      // Digest the selection specs' identity (sources, patterns, cardinality/flags, filter name +
      // module digest) rather than their resolved expansion, which is unsound before metadata
      // exists. The discovered-inputs cache path keys the resolved set on the input side.
      fp.addInt(inputSelections.size());
      for (FileSelectionSpec spec : inputSelections) {
        spec.addToFingerprint(actionKeyContext, fp);
      }
    }
  }
}
