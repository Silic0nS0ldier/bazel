// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.commands;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.runtime.Command.BuildPhase.ANALYZES;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.NoBuildEvent;
import com.google.devtools.build.lib.analysis.NoBuildRequestFinishedEvent;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.actions.DownloadAction;
import com.google.devtools.build.lib.analysis.starlark.StarlarkDownloadsContext;
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue;
import com.google.devtools.build.lib.bazel.bzlmod.BazelFetchAllValue;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionValue;
import com.google.devtools.build.lib.bazel.bzlmod.VendorManager;
import com.google.devtools.build.lib.bazel.commands.RepositoryFetcher.RepositoryFetcherException;
import com.google.devtools.build.lib.bazel.commands.TargetFetcher.TargetFetcherException;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Failure;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Success;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.runtime.commands.TargetPatternsHelper;
import com.google.devtools.build.lib.runtime.commands.TestCommand;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.FetchCommand.Code;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue.RepositoryMappingResolutionException;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.TargetPatternPhaseValue;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.NodeEntry;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * Fetches external repositories into a specified directory.
 *
 * <p>This command is used to fetch external repositories into a specified directory. It can be used
 * to fetch all external repositories, a specific list of repositories or the repositories needed to
 * build a specific list of targets.
 *
 * <p>The command is used to create a vendor directory that can be used to build the project
 * offline.
 */
@Command(
    name = VendorCommand.NAME,
    buildPhase = ANALYZES,
    inheritsOptionsFrom = {TestCommand.class},
    options = {
      VendorOptions.class,
      PackageOptions.class,
      KeepGoingOption.class,
      LoadingPhaseThreadsOption.class
    },
    allowResidue = true,
    usesConfigurationOptions = true,
    help = "resource:vendor.txt",
    shortDescription =
        "Fetches external repositories into a folder specified by the flag --vendor_dir.")
public final class VendorCommand implements BlazeCommand {
  public static final String NAME = "vendor";

  private final Supplier<ImmutableMap<String, String>> nonstrictRepoEnvSupplier;
  @Nullable private VendorManager vendorManager = null;
  @Nullable private DownloadManager downloadManager;

  public VendorCommand(Supplier<ImmutableMap<String, String>> nonstrictRepoEnvSupplier) {
    this.nonstrictRepoEnvSupplier = nonstrictRepoEnvSupplier;
  }

  public void setDownloadManager(DownloadManager downloadManager) {
    this.downloadManager = downloadManager;
  }

  @Override
  public void editOptions(OptionsParser optionsParser) {
    TargetFetcher.injectNoBuildOption(optionsParser);
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    BlazeCommandResult invalidResult = validateOptions(env, options);
    if (invalidResult != null) {
      return invalidResult;
    }

    env.getEventBus()
        .post(
            new NoBuildEvent(
                env.getCommandName(),
                env.getCommandStartTime(),
                /* separateFinishedEvent= */ true,
                /* showProgress= */ true,
                env.getCommandId().toString()));

    // IS_VENDOR_COMMAND & VENDOR_DIR is already injected in "BazelRepositoryModule", we just need
    // to update this value for the delegator function to recognize this call is from VendorCommand
    env.getSkyframeExecutor()
        .injectExtraPrecomputedValues(
            ImmutableList.of(
                PrecomputedValue.injected(RepositoryDirectoryValue.IS_VENDOR_COMMAND, true)));

    BlazeCommandResult result;
    VendorOptions vendorOptions = options.getOptions(VendorOptions.class);
    LoadingPhaseThreadsOption threadsOption = options.getOptions(LoadingPhaseThreadsOption.class);
    Path vendorDirectory =
        env.getWorkspace()
            .getRelative(options.getOptions(RepositoryOptions.class).getVendorDirectory());
    this.vendorManager = new VendorManager(vendorDirectory);
    List<String> targets;
    try {
      targets = TargetPatternsHelper.readFrom(env, options);
    } catch (TargetPatternsHelper.TargetPatternsHelperException e) {
      env.getReporter().handle(Event.error(e.getMessage()));
      return BlazeCommandResult.failureDetail(e.getFailureDetail());
    }
    try {
      if (!targets.isEmpty()) {
        if (!vendorOptions.getRepos().isEmpty()) {
          return createFailedBlazeCommandResult(
              env.getReporter(), "Target patterns and --repo cannot both be specified");
        }
        result = vendorTargets(env, options, targets);
      } else if (!vendorOptions.getRepos().isEmpty()) {
        result = vendorRepos(env, threadsOption, vendorOptions.getRepos());
      } else {
        result = vendorAll(env, threadsOption);
      }
    } catch (InterruptedException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Vendor interrupted: " + e.getMessage());
    } catch (IOException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Error while vendoring repos: " + e.getMessage());
    }

    env.getEventBus()
        .post(
            new NoBuildRequestFinishedEvent(
                result.getExitCode(), env.getRuntime().getClock().currentTimeMillis()));
    return result;
  }

  @Nullable
  private BlazeCommandResult validateOptions(CommandEnvironment env, OptionsParsingResult options) {
    if (options.getOptions(RepositoryOptions.class).getVendorDirectory() == null) {
      return createFailedBlazeCommandResult(
          env.getReporter(),
          Code.OPTIONS_INVALID,
          "You cannot run the vendor command without specifying --vendor_dir");
    }
    if (!options.getOptions(PackageOptions.class).getFetch()) {
      return createFailedBlazeCommandResult(
          env.getReporter(),
          Code.OPTIONS_INVALID,
          "You cannot run the vendor command with --nofetch");
    }
    return null;
  }

  private BlazeCommandResult vendorAll(
      CommandEnvironment env, LoadingPhaseThreadsOption threadsOption)
      throws InterruptedException, IOException {
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setParallelism(threadsOption.getThreads())
            .setEventHandler(env.getReporter())
            .build();

    SkyKey fetchKey = BazelFetchAllValue.key(/* configureEnabled= */ false);
    EvaluationResult<SkyValue> evaluationResult =
        env.getSkyframeExecutor().prepareAndGet(ImmutableSet.of(fetchKey), evaluationContext);
    if (evaluationResult.hasError()) {
      Exception e = evaluationResult.getError().getException();
      return createFailedBlazeCommandResult(
          env.getReporter(),
          e != null ? e.getMessage() : "Unexpected error during fetching all external deps.");
    }

    BazelFetchAllValue fetchAllValue = (BazelFetchAllValue) evaluationResult.get(fetchKey);
    env.getReporter().handle(Event.info("Vendoring all external repositories..."));
    vendor(env, fetchAllValue.reposToVendor());

    // Declared-set (loading) semantics: every download declared by any rule target in the main
    // repository or any repository of the module graph, with no configuration involved. Module
    // repositories excluded from repo vendoring (e.g. local path overrides) still declare
    // downloads of remote content, so they are included here.
    Set<RepositoryName> reposToLoad = new LinkedHashSet<>(fetchAllValue.reposToVendor());
    BazelDepGraphValue depGraphValue =
        (BazelDepGraphValue)
            env.getSkyframeExecutor().getEvaluator().getExistingValue(BazelDepGraphValue.KEY);
    if (depGraphValue != null) {
      reposToLoad.addAll(depGraphValue.getCanonicalRepoNameLookup().keySet());
    }
    ImmutableList.Builder<String> patterns = ImmutableList.builder();
    patterns.add("//...");
    for (RepositoryName repo : reposToLoad) {
      if (!repo.isMain()) {
        patterns.add("@@" + repo.getName() + "//...");
      }
    }
    vendorDownloads(env, collectDeclaredDownloads(env, patterns.build()).values());

    env.getReporter().handle(Event.info("All external dependencies vendored successfully."));
    return BlazeCommandResult.success();
  }

  private BlazeCommandResult vendorRepos(
      CommandEnvironment env, LoadingPhaseThreadsOption threadsOption, List<String> repos)
      throws InterruptedException, IOException {
    ImmutableMap<RepositoryName, RepositoryDirectoryValue> repositoryNamesAndValues;
    try {
      repositoryNamesAndValues = RepositoryFetcher.fetchRepos(repos, env, threadsOption);
    } catch (RepositoryMappingResolutionException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Invalid repo name: " + e.getMessage(), e.getDetailedExitCode());
    } catch (RepositoryFetcherException e) {
      return createFailedBlazeCommandResult(env.getReporter(), e.getMessage());
    }

    // Split repos to found and not found, vendor found ones and report others
    ImmutableList.Builder<RepositoryName> reposToVendor = ImmutableList.builder();
    ImmutableList.Builder<RepositoryName> resolvedRepos = ImmutableList.builder();
    List<String> notFoundRepoErrors = new ArrayList<>();
    for (Entry<RepositoryName, RepositoryDirectoryValue> entry :
        repositoryNamesAndValues.entrySet()) {
      switch (entry.getValue()) {
        case Success s -> {
          resolvedRepos.add(entry.getKey());
          if (!s.excludeFromVendoring()) {
            reposToVendor.add(entry.getKey());
          }
        }
        case Failure(String errorMsg) -> notFoundRepoErrors.add(errorMsg);
      }
    }

    env.getReporter().handle(Event.info("Vendoring repositories..."));
    vendor(env, reposToVendor.build());

    // Declared-set (loading) semantics, scoped to the requested repos: every download declared
    // by any rule target defined in them. Repos excluded from repo vendoring (e.g. local path
    // overrides) still declare downloads of remote content, so all resolved repos are loaded.
    ImmutableList<String> patterns =
        resolvedRepos.build().stream()
            .map(repo -> "@@" + repo.getName() + "//...")
            .collect(toImmutableList());
    vendorDownloads(env, collectDeclaredDownloads(env, patterns).values());

    if (!notFoundRepoErrors.isEmpty()) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Vendoring some repos failed with errors: " + notFoundRepoErrors);
    }
    env.getReporter().handle(Event.info("All requested repos vendored successfully."));
    return BlazeCommandResult.success();
  }

  private BlazeCommandResult vendorTargets(
      CommandEnvironment env, OptionsParsingResult options, List<String> targets)
      throws InterruptedException, IOException {
    // Call fetch which runs build to have the targets graph and configuration set
    BuildResult buildResult;
    try {
      buildResult = TargetFetcher.fetchTargets(env, options, targets);
    } catch (TargetFetcherException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), Code.QUERY_EVALUATION_ERROR, e.getMessage());
    }

    // Traverse the graph created from build to collect repos and vendor them
    ImmutableList<SkyKey> targetKeys =
        buildResult.getActualTargets().stream()
            .map(ConfiguredTarget::getLookupKey)
            .collect(toImmutableList());
    InMemoryGraph inMemoryGraph = env.getSkyframeExecutor().getEvaluator().getInMemoryGraph();
    ImmutableSet<RepositoryName> reposToVendor = collectReposFromTargets(inMemoryGraph, targetKeys);

    env.getReporter().handle(Event.info("Vendoring dependencies for targets..."));
    vendor(env, reposToVendor.asList());

    // Consumed-set (configured) semantics: exactly the downloads a build of the same targets
    // with the same flags would execute — the download actions reachable in the action graph
    // from the requested targets' default outputs and runfiles.
    vendorDownloads(env, collectConsumedDownloads(env, buildResult).values());

    env.getReporter()
        .handle(
            Event.info(
                "All external dependencies for the requested targets vendored successfully."));
    return BlazeCommandResult.success();
  }

  private ImmutableSet<RepositoryName> collectReposFromTargets(
      InMemoryGraph inMemoryGraph, ImmutableList<SkyKey> targetKeys) throws InterruptedException {
    ImmutableSet.Builder<RepositoryName> repos = ImmutableSet.builder();
    Queue<SkyKey> nodes = new ArrayDeque<>(targetKeys);
    Set<SkyKey> visited = new HashSet<>();
    while (!nodes.isEmpty()) {
      SkyKey key = nodes.remove();
      visited.add(key);
      NodeEntry nodeEntry = inMemoryGraph.get(null, Reason.VENDOR_EXTERNAL_REPOS, key);
      if (nodeEntry.getValue() instanceof RepositoryDirectoryValue.Success repoDirValue
          && !repoDirValue.excludeFromVendoring()) {
        repos.add((RepositoryName) key.argument());
      }
      for (SkyKey depKey : nodeEntry.getDirectDeps()) {
        if (!visited.contains(depKey)) {
          nodes.add(depKey);
        }
      }
    }
    return repos.build();
  }

  /** A download to fetch into the vendor directory's content-addressed store. */
  private record DownloadToVendor(
      String integrity, ImmutableList<URI> urls, String canonicalId, String declaringLabel) {}

  /**
   * Collects the consumed download set of the analysed targets: the {@link DownloadAction}s
   * reachable in the action graph from the targets' default outputs and runfiles. This is exactly
   * the set a build of the same targets with the same flags would execute.
   *
   * <p>Generating actions are resolved from the already-evaluated {@link ActionLookupValue}s in
   * the in-memory graph; nothing new is analysed.
   */
  private ImmutableMap<String, DownloadToVendor> collectConsumedDownloads(
      CommandEnvironment env, BuildResult buildResult) throws InterruptedException {
    InMemoryGraph inMemoryGraph = env.getSkyframeExecutor().getEvaluator().getInMemoryGraph();
    Map<String, DownloadToVendor> downloads = new LinkedHashMap<>();
    Set<Artifact> visited = new HashSet<>();
    ArrayDeque<Artifact> queue = new ArrayDeque<>();
    for (ConfiguredTarget configuredTarget : buildResult.getActualTargets()) {
      FileProvider fileProvider = configuredTarget.getProvider(FileProvider.class);
      if (fileProvider != null) {
        queue.addAll(fileProvider.getFilesToBuild().toList());
      }
      RunfilesProvider runfilesProvider = configuredTarget.getProvider(RunfilesProvider.class);
      if (runfilesProvider != null) {
        queue.addAll(runfilesProvider.getDefaultRunfiles().getAllArtifacts().toList());
      }
    }
    while (!queue.isEmpty()) {
      Artifact artifact = queue.remove();
      if (!(artifact instanceof Artifact.DerivedArtifact derived) || !visited.add(artifact)) {
        continue;
      }
      ActionLookupData generatingActionKey = derived.getGeneratingActionKey();
      NodeEntry nodeEntry =
          inMemoryGraph.get(
              null, Reason.VENDOR_EXTERNAL_REPOS, generatingActionKey.getActionLookupKey());
      if (nodeEntry == null || !(nodeEntry.getValue() instanceof ActionLookupValue actions)) {
        continue;
      }
      ActionAnalysisMetadata action = actions.getActions().get(generatingActionKey.getActionIndex());
      if (action instanceof DownloadAction downloadAction) {
        downloads.putIfAbsent(
            downloadAction.getIntegrity(),
            new DownloadToVendor(
                downloadAction.getIntegrity(),
                downloadAction.getUrls(),
                downloadAction.getCanonicalId(),
                downloadAction.getOwner().getLabel().toString()));
      } else {
        queue.addAll(action.getInputs().toList());
      }
    }
    return ImmutableMap.copyOf(downloads);
  }

  /**
   * Collects the declared download set of every rule target matched by the given patterns, by
   * loading the packages and evaluating the rules' {@code downloads} callbacks. Requires no
   * configuration and no analysis.
   */
  private ImmutableMap<String, DownloadToVendor> collectDeclaredDownloads(
      CommandEnvironment env, ImmutableList<String> patterns)
      throws IOException, InterruptedException {
    OptionsParsingResult options = env.getOptions();
    LoadingPhaseThreadsOption threadsOption = options.getOptions(LoadingPhaseThreadsOption.class);
    boolean keepGoing = options.getOptions(KeepGoingOption.class).getKeepGoing();
    StarlarkSemantics semantics =
        Objects.requireNonNull(options.getOptions(BuildLanguageOptions.class))
            .toStarlarkSemantics();
    TargetPatternPhaseValue patternValue;
    try {
      patternValue =
          env.getSkyframeExecutor()
              .loadTargetPatternsWithoutFilters(
                  env.getReporter(),
                  patterns,
                  env.getRelativeWorkingDirectory(),
                  threadsOption.getThreads(),
                  keepGoing);
    } catch (TargetParsingException e) {
      throw new IOException(
          "Failed to load targets while enumerating declared downloads: " + e.getMessage(), e);
    }
    Map<String, DownloadToVendor> downloads = new LinkedHashMap<>();
    for (Target target : patternValue.getTargets(env.getReporter(), env.getPackageManager())) {
      if (!(target instanceof Rule rule)) {
        continue;
      }
      ImmutableMap<String, StarlarkDownloadsContext.Declaration> declarations;
      try {
        declarations = StarlarkDownloadsContext.evaluate(rule, semantics);
      } catch (EvalException e) {
        throw new IOException(
            String.format(
                "error evaluating downloads callback of %s: %s",
                rule.getLabel(), e.getMessageWithStack()),
            e);
      }
      for (StarlarkDownloadsContext.Declaration declaration : declarations.values()) {
        downloads.putIfAbsent(
            declaration.getIntegrity(),
            new DownloadToVendor(
                declaration.getIntegrity(),
                declaration.getUrls(),
                declaration.getCanonicalId(),
                rule.getLabel().toString()));
      }
    }
    return ImmutableMap.copyOf(downloads);
  }

  /**
   * Fetches the given downloads into the vendor directory's content-addressed store and records
   * their provenance in the store's MANIFEST.
   *
   * <p>Blobs already present are skipped: the store is keyed by content, so presence implies
   * identity. Fetching goes through the {@link DownloadManager}, sharing the download cache,
   * URL rewriting, authentication, and remote downloader configuration with repository fetches.
   */
  private void vendorDownloads(CommandEnvironment env, Collection<DownloadToVendor> downloads)
      throws IOException, InterruptedException {
    if (downloads.isEmpty()) {
      return;
    }
    Objects.requireNonNull(vendorManager);
    Objects.requireNonNull(downloadManager);
    env.getReporter()
        .handle(Event.info(String.format("Vendoring %d download(s)...", downloads.size())));
    List<String> manifestEntries = new ArrayList<>();
    for (DownloadToVendor download : downloads) {
      Checksum checksum;
      try {
        checksum = Checksum.fromSubresourceIntegrity(download.integrity());
      } catch (Checksum.InvalidChecksumException e) {
        throw new IOException(
            String.format(
                "invalid integrity checksum '%s' declared by %s: %s",
                download.integrity(), download.declaringLabel(), e.getMessage()),
            e);
      }
      manifestEntries.add(
          download.integrity()
              + " "
              + download.declaringLabel()
              + " "
              + download.urls().stream().map(URI::toString).collect(Collectors.joining(" ")));
      if (vendorManager.lookupDownload(checksum) != null) {
        continue;
      }
      Path target = vendorManager.getDownloadPath(checksum);
      Objects.requireNonNull(target.getParentDirectory()).createDirectoryAndParents();
      Path temporary = target.replaceName(target.getBaseName() + ".fetching");
      try {
        Future<Path> future =
            downloadManager.startDownload(
                MoreExecutors.newDirectExecutorService(),
                download.urls(),
                /* headers= */ ImmutableMap.of(),
                /* authHeaders= */ ImmutableMap.of(),
                Optional.of(checksum),
                download.canonicalId(),
                /* type= */ Optional.empty(),
                temporary,
                env.getClientEnv(),
                /* context= */ download.declaringLabel(),
                new Phaser(),
                /* mayHardlink= */ false);
        Path unused = downloadManager.finalizeDownload(future);
        vendorManager.vendorDownload(checksum, temporary);
      } catch (IOException e) {
        throw new IOException(
            String.format(
                "Failed to vendor download declared by %s (from %s): %s",
                download.declaringLabel(), download.urls(), e.getMessage()),
            e);
      } finally {
        temporary.delete();
      }
    }
    vendorManager.updateDownloadManifest(manifestEntries);
  }

  /**
   * Copies the fetched repos from the external cache into the vendor directory, unless the repo is
   * ignored or was already vendored and up-to-date
   */
  private void vendor(CommandEnvironment env, ImmutableList<RepositoryName> reposToVendor)
      throws IOException, InterruptedException {
    Objects.requireNonNull(vendorManager);

    // 1. Vendor registry files
    BazelModuleResolutionValue moduleResolutionValue =
        (BazelModuleResolutionValue)
            env.getSkyframeExecutor()
                .getEvaluator()
                .getExistingValue(BazelModuleResolutionValue.KEY);
    ImmutableMap<String, Optional<Checksum>> registryFiles =
        Objects.requireNonNull(moduleResolutionValue).getRegistryFileHashes();

    // vendorPathToURL is a map of
    //  key: a vendor path string converted to lower case
    //  value: a URL string
    // This map is for detecting potential rare vendor path conflicts, such as:
    //  http://foo.bar.com/BCR vs http://foo.bar.com/bcr => conflict vendor paths on
    // case-insensitive system
    //  http://foo.bar.com/bcr vs http://foo.bar.com:8081/bcr => conflict vendor path because port
    // number is ignored in vendor path
    // The user has to update the Bazel registries this if such conflicts occur.
    Map<String, String> vendorPathToUrl = new HashMap<>();
    for (Entry<String, Optional<Checksum>> entry : registryFiles.entrySet()) {
      URI url = URI.create(entry.getKey());
      if (Objects.equals(url.getScheme(), "file")) {
        continue;
      }

      String outputPath = vendorManager.getVendorPathForUrl(url).getPathString();
      String outputPathLowerCase = outputPath.toLowerCase(Locale.ROOT);
      if (vendorPathToUrl.containsKey(outputPathLowerCase)) {
        String previousUrl = vendorPathToUrl.get(outputPathLowerCase);
        throw new IOException(
            String.format(
                "Vendor paths conflict detected for registry URLs:\n"
                    + "    %s => %s\n"
                    + "    %s => %s\n"
                    + "Their output paths are either the same or only differ by case, which will"
                    + " cause conflict on case insensitive file systems, please fix by changing the"
                    + " registry URLs!",
                previousUrl,
                vendorManager.getVendorPathForUrl(URI.create(previousUrl)).getPathString(),
                entry.getKey(),
                outputPath));
      }

      Optional<Checksum> checksum = entry.getValue();
      if (!vendorManager.isUrlVendored(url)
          // Only vendor a registry URL when its checksum exists, otherwise the URL should be
          // recorded as "not found" in moduleResolutionValue.getRegistryFileHashes()
          && checksum.isPresent()) {
        try {
          vendorManager.vendorRegistryUrl(
              url,
              downloadManager.downloadAndReadOneUrlForBzlmod(
                  url, nonstrictRepoEnvSupplier.get(), checksum));
        } catch (IOException e) {
          throw new IOException(
              String.format(
                  "Failed to vendor registry URL %s at %s: %s", url, outputPath, e.getMessage()),
              e.getCause());
        }
      }

      vendorPathToUrl.put(outputPathLowerCase, entry.getKey());
    }

    // 2. Vendor repos
    Path externalPath =
        env.getDirectories()
            .getOutputBase()
            .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION);
    vendorManager.vendorRepos(externalPath, env.getDirectories().getWorkspace(), reposToVendor);

    // 3. Invalidate RepositoryDirectoryValue for vendored repos.
    env.getSkyframeExecutor()
        .getEvaluator()
        .delete(
            k ->
                k.functionName().equals(SkyFunctions.REPOSITORY_DIRECTORY)
                    && reposToVendor.contains(k.argument()));
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, Code fetchCommandCode, String message) {
    return createFailedBlazeCommandResult(
        reporter,
        message,
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setFetchCommand(
                    FailureDetails.FetchCommand.newBuilder().setCode(fetchCommandCode).build())
                .build()));
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, String errorMessage) {
    return createFailedBlazeCommandResult(
        reporter, errorMessage, InterruptedFailureDetails.detailedExitCode(errorMessage));
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, String message, DetailedExitCode exitCode) {
    reporter.handle(Event.error(message));
    return BlazeCommandResult.detailedExitCode(exitCode);
  }
}
