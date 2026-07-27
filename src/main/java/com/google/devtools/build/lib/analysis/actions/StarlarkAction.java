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

  /**
   * The file matches ({@code ctx.actions.match_*}) this action consumes as inputs, executable, or
   * tools. Empty unless the action carries matches (see {@link EnhancedStarlarkAction}). Exposed
   * for aquery structured output.
   */
  public ImmutableList<FileMatchSpec> getInputMatchSpecs() {
    return ImmutableList.of();
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
    private ImmutableList<FileMatchSpec> inputMatchSpecs = ImmutableList.of();

    @CanIgnoreReturnValue
    public Builder setUnusedInputsList(Optional<Artifact> unusedInputsList) {
      this.unusedInputsList = unusedInputsList;
      return this;
    }

    /**
     * Adds file matches to be resolved to concrete children and staged as action inputs.
     *
     * <p>Each match's source trees are declared as inputs so their metadata is available when
     * the action resolves matches during input discovery; {@code discoverInputs} then prunes
     * the trees, replacing them with only the resolved children.
     */
    @CanIgnoreReturnValue
    public Builder addInputMatchSpecs(List<FileMatchSpec> matches) {
      if (!matches.isEmpty()) {
        this.inputMatchSpecs =
            ImmutableList.<FileMatchSpec>builder()
                .addAll(this.inputMatchSpecs)
                .addAll(matches)
                .build();
        for (FileMatchSpec spec : matches) {
          addInputs(ImmutableList.<Artifact>copyOf(spec.getSourceTreeArtifacts()));
        }
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
      return unusedInputsList.isPresent() || shadowedAction.isPresent() || !inputMatchSpecs.isEmpty()
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
              inputMatchSpecs)
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
    // File matches consumed as action inputs; resolved to concrete children in discoverInputs.
    private final ImmutableList<FileMatchSpec> inputMatchSpecs;
    // The distinct source tree artifacts backing inputMatchSpecs (scheduling deps, not staged).
    private final NestedSet<Artifact> matchSourceTrees;
    // Per-spec resolution results captured in discoverInputs so spawn construction (command line
    // expansion, executable rendering) can consume them after the source trees are pruned.
    // Transient execution state: repopulated on re-discovery (incremental builds, rewinding) and
    // deliberately not serialized.
    @Nullable
    private volatile ImmutableMap<FileMatchSpec, ImmutableList<Artifact>> resolvedMatches;
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
        ImmutableList<FileMatchSpec> inputMatchSpecs) {
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
      this.inputMatchSpecs = inputMatchSpecs;
      this.matchSourceTrees = collectMatchSourceTrees(inputMatchSpecs);
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
        ImmutableList<FileMatchSpec> inputMatchSpecs) {
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
      this.inputMatchSpecs = inputMatchSpecs;
      this.matchSourceTrees = collectMatchSourceTrees(inputMatchSpecs);
    }

    private static NestedSet<Artifact> collectMatchSourceTrees(
        ImmutableList<FileMatchSpec> inputMatchSpecs) {
      NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
      for (FileMatchSpec spec : inputMatchSpecs) {
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
    public ImmutableList<FileMatchSpec> getInputMatchSpecs() {
      return inputMatchSpecs;
    }

    @Override
    public boolean isShareable() {
      return unusedInputsList.isEmpty();
    }

    @Override
    public boolean discoversInputs() {
      return unusedInputsList.isPresent()
          || !inputMatchSpecs.isEmpty()
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
      if (matchSourceTrees.isEmpty()) {
        return base;
      }
      return createInputs(base, matchSourceTrees);
    }

    @Nullable
    @Override
    public NestedSet<Artifact> discoverInputs(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException, InterruptedException {
      // Resolve any file matches against the now-available source-tree metadata into concrete
      // children. Only the resolved children join the inputs; the source trees are not staged.
      NestedSet<Artifact> resolvedMatchChildren = resolveInputMatches(actionExecutionContext);

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
                inputsWithoutMatchSourceTrees(),
                resolvedMatchChildren));
        return NestedSetBuilder.wrap(
            Order.STABLE_ORDER, Sets.difference(getInputs().toSet(), oldInputs.toSet()));
      }
      // Otherwise, we need to "re-discover" all the original inputs: the unused ones that were
      // removed might now be needed. The match source trees are pruned, leaving only the
      // resolved children — so the consumer's footprint is the resolved subset, not the whole tree.
      NestedSet<Artifact> discovered =
          resolvedMatchChildren.isEmpty()
              ? allStarlarkActionInputs
              : createInputs(inputsWithoutMatchSourceTrees(), resolvedMatchChildren);
      updateInputs(discovered);
      return discovered;
    }

    /**
     * {@link #allStarlarkActionInputs} with the match source trees removed. The trees are
     * declared inputs only so their metadata is available during discovery; once matches resolve
     * to concrete children, the trees themselves are neither staged nor part of the cache key.
     */
    private NestedSet<Artifact> inputsWithoutMatchSourceTrees() {
      if (matchSourceTrees.isEmpty()) {
        return allStarlarkActionInputs;
      }
      ImmutableSet<Artifact> trees = ImmutableSet.copyOf(matchSourceTrees.toList());
      NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
      for (Artifact input : allStarlarkActionInputs.toList()) {
        if (!trees.contains(input)) {
          builder.add(input);
        }
      }
      return builder.build();
    }

    /**
     * Resolves each input match against the source trees' now-available metadata, returning the
     * union of resolved children. A resolution failure (empty/ambiguous/kind/filter error) fails
     * this action with the match's diagnostic.
     */
    private NestedSet<Artifact> resolveInputMatches(
        ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException, InterruptedException {
      if (inputMatchSpecs.isEmpty()) {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
      }
      NestedSetBuilder<Artifact> children = NestedSetBuilder.stableOrder();
      Map<FileMatchSpec, ImmutableList<Artifact>> resolvedBySpec = Maps.newLinkedHashMap();
      for (FileMatchSpec spec : inputMatchSpecs) {
        try {
          ImmutableList<Artifact> resolved =
              spec.resolve(
                  actionExecutionContext.getInputMetadataProvider(),
                  actionExecutionContext.getEventHandler());
          for (Artifact member : resolved) {
            // Directory members (subtree artifacts) surface only at execution time, after the
            // Skyframe input-request phase — unlike a pick_directory, which is declared at
            // analysis time and routed as a regular input. Staging them here would need the
            // discovered-input machinery to admit late subtree nodes; fail clearly until then.
            if (member.isTreeArtifact()) {
              throw new FileMatchSpec.MatchResolutionException(
                  "directory members of a match are not yet supported as action inputs (from "
                      + member.getExecPathString()
                      + "); match regular files, pick the directory statically with"
                      + " pick_directory, or materialise it with a copy action");
            }
            children.add(member);
          }
          // Duplicate specs (e.g. the same match in inputs and as executable) resolve
          // identically; keeping the first is sufficient.
          resolvedBySpec.putIfAbsent(spec, resolved);
        } catch (FileMatchSpec.MatchResolutionException e) {
          throw new ActionExecutionException(
              e.getMessage(),
              this,
              /* catastrophe= */ false,
              DetailedExitCode.of(
                  createFailureDetail(e.getMessage(), Code.FILE_MATCH_RESOLUTION_FAILURE)));
        }
      }
      this.resolvedMatches = ImmutableMap.copyOf(resolvedBySpec);
      return children.build();
    }

    @Override
    public Spawn getSpawn(ActionExecutionContext actionExecutionContext)
        throws CommandLineExpansionException, InterruptedException {
      if (inputMatchSpecs.isEmpty()) {
        return super.getSpawn(actionExecutionContext);
      }
      // discoverInputs always runs before execution for this action (discoversInputs() is true
      // whenever inputMatchSpecs is non-empty), so the resolution results are available. Wrapping
      // the metadata provider makes them reachable from command line expansion, which has no
      // handle on the action itself.
      ImmutableMap<FileMatchSpec, ImmutableList<Artifact>> resolved = resolvedMatches;
      if (resolved == null) {
        throw new IllegalStateException(
            "input matches were not resolved before spawn construction: " + describe());
      }
      return super.getSpawn(
          actionExecutionContext.withInputMetadataProvider(
              new MatchResolvingInputMetadataProvider(
                  actionExecutionContext.getInputMetadataProvider(), resolved)));
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
      // Digest the match specs' identity (sources, patterns, cardinality/flags, filter name +
      // module digest) rather than their resolved expansion, which is unsound before metadata
      // exists. The discovered-inputs cache path keys the resolved set on the input side.
      fp.addInt(inputMatchSpecs.size());
      for (FileMatchSpec spec : inputMatchSpecs) {
        spec.addToFingerprint(actionKeyContext, fp);
      }
    }
  }
}
