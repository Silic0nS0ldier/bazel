---
created: 2026-07-27
last updated: 2026-07-27
status: Draft
title: Tree Artifact Selection — Implementation Plan
authors:
  - Silic0nS0ldier
---

This document maps the [Tree Artifact Selection proposal](2026-07-27-tree-artifact-selection.md) onto Bazel's actual machinery.
All paths are relative to `src/main/java/com/google/devtools/build/lib/` unless stated otherwise; line references are against the tree as of 2026-07-27.

# Strategy

Two largely independent workstreams, mirroring the proposal's tiers;

- **Picks** are a Starlark surface over an existing internal concept.
  `TreeFileArtifact` already models "a file inside a tree artifact", already has the right equality semantics, and most consuming machinery already handles it.
  The work is an API, one relaxed invariant, one reframed error, and extension of the declarative surfaces (runfiles, completion, BEP) that have never seen a *standalone* child.
- **Selections** are new machinery, but every load-bearing mechanism has a battle-tested precedent: input discovery (`CppCompileAction` header pruning, `EnhancedStarlarkAction`), scheduling dependencies, execution-time Starlark evaluation (`StarlarkMapActionTemplate`), and late-expanding non-file input kinds (Fileset).
  The work is composing them, not inventing.

Everything ships behind a single flag and in independently landable phases; each phase leaves the tree green and the feature coherent for the surface area it covers.

## Key mechanism decisions

| Proposal concept | Engine mechanism |
|---|---|
| Pick (`pick_file`/`pick_directory`) | Analysis-time `TreeFileArtifact` via `TreeFileArtifact.createTreeOutput` (`actions/Artifact.java:1131`); directory picks reuse the subtree `SpecialArtifact` representation that `template_ctx.declare_subdirectory` introduced |
| Pick existence check | The existing missing-child check in `ActionExecutionFunction.getAndCheckInputSkyValue` (`skyframe/ActionExecutionFunction.java:1124`), reframed from `NONDETERMINISTIC_TREE_ARTIFACT` |
| Selection resolution point | Input discovery: source trees in `getSchedulingDependencies()`, resolution in `discoverInputs()`, concrete children installed via `updateInputs()` — the `CppCompileAction` pattern (`rules/cpp/CppCompileAction.java:1256`, `:634`, `:1204`) |
| Selection cache granularity | The discovered-inputs action cache path (`actions/ActionCacheChecker.java:491-512`, `actions/cache/ActionCache.java` `discoveredInputPaths`) — cache entries list and digest only resolved children |
| `filter` callback evaluation | The `StarlarkMapActionTemplate.generateActionsForInputArtifacts` model (`analysis/actions/StarlarkMapActionTemplate.java:206-268`): full `StarlarkThread.create`, debug print handler, `EvalException` → action failure |
| `filter` identity in action keys | Function name + `BazelModuleContext.bzlTransitiveDigest` (`StarlarkMapActionTemplate.java:337-338`) — **not** evaluate-at-fingerprint, which cannot see tree children at analysis time (the `b/160181927` soundness hole in `StarlarkCustomCommandLine.java:520-526`) |
| Top-level-def restriction | `StarlarkActionFactory.validateIsTopLevelStarlarkFunction` (`analysis/starlark/StarlarkActionFactory.java:1083`) |
| Args expansion | A new value case alongside directory expansion in `StarlarkCustomCommandLine.VectorArg.maybeExpandDirectories` (`analysis/starlark/StarlarkCustomCommandLine.java:372-432`) |
| aquery placeholder | A new interned `file_selections` table following the `Known*`/`BaseCache` pattern (`skyframe/actiongraph/v2/`), plus a text-renderer branch mirroring the existing `(TreeArtifact)` suffix (`query2/aquery/ActionGraphTextOutputFormatterCallback.java:246-250`) |

## Flag

`--experimental_tree_artifact_selection`, following `map_directory`'s precedent exactly: a `CoreOptions` build option (`analysis/config/CoreOptions.java:797` for the model, `experimental_allow_map_directory`), `NON_CONFIGURABLE`, checked imperatively at the top of each new `StarlarkActionFactory` method rather than via `@StarlarkMethod(enableOnlyWithFlag)`.
Rationale: the feature affects action execution (input discovery, spawn construction), which is configuration territory rather than pure language surface, and it keeps all five functions behind one switch.

# Phase 0 — Groundwork

**Deliverables:** the flag; skeleton types; documentation stubs.

- Add `experimental_tree_artifact_selection` to `CoreOptions` and a `BuildConfigurationValue` accessor (model: `allowMapDirectory()`, `analysis/config/BuildConfigurationValue.java:793`).
- Define the two Starlark value types following the `ExpandedDirectory` pattern (`starlarkbuildapi/ExpandedDirectoryApi.java`; impl at `StarlarkMapActionTemplate.java:455`);
  - `starlarkbuildapi/SelectedFileApi.java` and `starlarkbuildapi/FileSelectionApi.java`, `@StarlarkBuiltin`, no methods beyond documentation surface in v1.
  - Implementations under `analysis/actions/`, immutable, `repr(Printer)` emitting the stable placeholder form specified in the proposal (return-only types need no global registration).
  - `@AutoCodec` serialization for both (they will be embedded in actions and must survive analysis caching / Skymeld serialization).

# Phase 1 — Picks as action inputs

The core loop: create a pick at analysis time, consume it as an action input, fail well when it is missing.
The exploration confirmed the artifact layer mostly permits this already; the blockers are one precondition, one owner nuance, and one error message.

**API layer.**
- `pick_file`/`pick_directory` on `StarlarkActionFactoryApi`/`StarlarkActionFactory`, validating the path (relative, normalized, no uplevels — the base `TreeFileArtifact` constructor already enforces this, `Artifact.java:1196`) and the parent (tree artifact or subtree artifact; reject source directories and regular files at the call site).
- `pick_file` returns a `TreeFileArtifact`; `pick_directory` returns a subtree `SpecialArtifact` (the `isSubTreeArtifact()` representation), so nested picks and `map_directory` interop follow from existing handling (`ActionInputMap.getInputMetadataChecked` already walks the grandparent chain for subtrees, `actions/ActionInputMap.java:239-241`).

**Artifact layer.**
- `TreeFileArtifact.createTreeOutput` requires `parent.hasGeneratingActionKey()` (`Artifact.java:1134`).
  True for trees obtained from dependencies; **false during the declaring target's own analysis** (generating action keys are assigned after rule implementation returns, in `Actions.assignOwnersAndThrowIfConflict`).
  Picking from a tree the same rule just declared is a first-class proposal use case, so this precondition must be relaxed: allow construction with a deferred owner that binds when the parent's key is assigned (the same lifecycle `DerivedArtifact.setGeneratingActionKey` already manages), and update the class Javadoc contract (`Artifact.java:1102-1104`) that currently prohibits analysis-time creation.
- Owner correctness is load-bearing everywhere downstream: the child must be owned by the producing tree action so that `isChildOfDeclaredDirectory()` (`Artifact.java:1222`) holds and the analysis-time pick compares `.equals()` to the execution-time child `ActionOutputMetadataStore.createTreeOutput` produces (`skyframe/ActionOutputMetadataStore.java:279`).
  This equality is what makes metadata lookup work with zero new plumbing.

**Consumption path (verified working, needs tests not code).**
- Skyframe routing: `Artifact.key()` sends a `TreeFileArtifact` to its generating action's `ActionLookupData` (`Artifact.java:176-182`), so no `ArtifactFunction` change.
- Metadata: `ActionInputMapHelper.addToMap` auto-expands the parent tree into the consumer's input map when the input `isChildOfDeclaredDirectory()` (`skyframe/ActionInputMapHelper.java:46-50`); child metadata then resolves from `TreeArtifactValue.getChildValues()` (`ActionInputMap.java:247`).
- Staging: `SpawnInputExpander.addInputs` already has the `TreeFileArtifact` case (`exec/SpawnInputExpander.java:177-189`); sandbox and prefetcher consume the expanded mapping, and the prefetcher's partial-tree machinery (`remote/AbstractActionInputPrefetcher.java:501-524`) fetches only the child.
- Prefix conflicts: non-issue — conflict checking runs over action *outputs* only (`skyframe/ArtifactConflictFinder.java:140`, `actions/Actions.java:320`), and picks are never outputs.

**Error semantics.**
- A missing child currently fails as `NONDETERMINISTIC_TREE_ARTIFACT` ("a previous execution produced child... but a subsequent execution did not", `ActionExecutionFunction.java:1127`).
  Add a distinct failure detail for declared picks with the proposal's message shape (missing path, tree, generating target), and a kind-mismatch check (file pick resolving to directory and vice versa) at the same point.

**Exit criteria:** a rule extracts an archive into a declared directory, picks a file from it, feeds it to a second action as input and as `executable`; missing-path and wrong-kind cases produce the specified errors; action-cache behaviour is child-granular for the consumer... **note:** it is not, in this phase — the consumer's cache key digests the child's metadata only if the child (not the tree) is the declared input, which it is here, so child-granularity holds for picks by construction.
Integration tests must cover local, sandboxed, and remote execution, plus BwoB (`--remote_download_minimal`) verifying sibling children are not fetched.

# Phase 2 — Picks on declarative surfaces

What Phase 1 does not cover: picks flowing through providers into runfiles, top-level builds, and BEP.
These are the "assumes Starlark-visible artifact ⇒ analysis-declared node" surfaces the proposal's backward-compatibility section flags.

- **Runfiles.**
  `analysis/Runfiles.java` has no child-of-tree concept; execution-time expansion already handles `TreeFileArtifact` (`SpawnInputExpander.java:177`), but manifest generation (`SourceManifestAction`), `Runfiles.fingerprint` (`Runfiles.java:884`), and runfiles-tree construction need auditing so a standalone child (a) lands at the right runfiles path, and (b) has its parent tree's metadata available wherever expansion happens.
  The invariant to establish: a pick in runfiles implies a dependency on (though not staging of) its parent tree's `TreeArtifactValue`.
- **Completion / top-level build.**
  `CompletionFunction` tolerates a child in an output group mechanically (`Artifact.key` routing plus `addToMap` parent expansion, `skyframe/CompletionFunction.java:192-206`), but nothing populates output groups with children today; add tests and fix fallout.
- **BwoB top-level download.**
  `RemoteOutputChecker.shouldDownloadOutput` already understands tree children (`remote/RemoteOutputChecker.java:310-320`), but registration is blocked: `ConcurrentArtifactPathTrie.add` throws on `TreeFileArtifact` (`remote/ConcurrentArtifactPathTrie.java:41-46`) and the trie's no-prefix invariant conflicts with "child path under registered tree path".
  Add an exact-child-paths side set consulted by `shouldDownloadOutput` before the prefix trie, so `bazel build //x:pick` materialises the child and only the child.
- **BEP.**
  `CompletionContext.visitArtifacts` handles children only via parent-tree expansion; a standalone child takes the plain-artifact branch (`actions/CompletionContext.java:103`) and needs its `FileArtifactValue` present in the completion `ActionInputMap` — verify the Phase 1 `addToMap` behaviour covers completion contexts, fix if not.
- **cquery.**
  Expected free (`query2/cquery/FilesOutputFormatterCallback.java` traverses `Artifact`s only); add coverage, no code anticipated.

**Exit criteria:** a pick in `DefaultInfo.files`, in an executable rule's runfiles, and as a test data dependency behaves like an ordinary file under all download modes; BEP consumers see it as a normal `File`.

# Phase 3 — Selection core: types, resolution, inputs

The heart of the dynamic tier: `select_file` / `select_directory` / `select_files` resolving during input discovery.

**API layer.**
- Three methods on `StarlarkActionFactory`; shared validation (sources are directory artifacts / selections, patterns are valid globs, `filter` passes `validateIsTopLevelStarlarkFunction`).
- The selection spec object (sources, include, exclude, filter reference, cardinality, flags) is the payload of both Starlark types; specs are structurally comparable so identical declarations dedupe.

**Resolution engine** (new, `analysis/actions/` or `actions/`):
- Given specs plus an `InputMetadataProvider`, produce ordered `ImmutableList<TreeFileArtifact>`;
  1. children from `getTreeMetadata(source).getChildren()` per source (selection sources recurse; `select_directory` sources contribute children below the resolved directory),
  2. glob matching against `getTreeRelativePathString()` — reuse the pattern compiler behind package globbing rather than writing a new matcher,
  3. `filter` evaluation on the model of `StarlarkMapActionTemplate.java:233-258`: fresh `Mutability`, `StarlarkThread.create` with the captured `StarlarkSemantics`, debug print handler, candidates presented as the `TreeFileArtifact`s themselves (they already expose `tree_relative_path`), result validated as a subset,
  4. ordering, cardinality (`exactly one` / `allow_empty`), and kind checks, with the proposal's error messages.
- Memoise per (spec, source-metadata) within a build; Skyframe's per-action discovery memoisation makes cross-action caching an optimisation, not a correctness need.

**Consuming-action integration** (`analysis/actions/StarlarkAction.java`):
- When any input is a selection, build the enhanced variant (the `EnhancedStarlarkAction` split already exists for `unused_inputs_list`/shadowed actions, `StarlarkAction.java:223-521`);
  - `getSchedulingDependencies()` returns the selections' source trees — built and metadata-available, but not staged and not in the cache key (`actions/ActionAnalysisMetadata.java:145-152`; Skyframe edges via `ActionExecutionFunction.getInputDepKeys`, `:449-493`).
  - `discoversInputs()` returns true; `discoverInputs()` runs the resolution engine against the now-available tree metadata and installs mandatory inputs + resolved children via `updateInputs()`.
  - `getAllowedDerivedInputs()` returns mandatory inputs plus source trees (the cache uses it to re-resolve stored child paths, `ActionCacheChecker.getCachedInputs`, `:747`).
- The discovered-inputs action cache path then does the rest: entries list resolved children (`ActionCache.Entry.discoveredInputPaths`) and digest exactly them, so sibling churn outside the resolved set does not invalidate — the proposal's invalidation-granularity promise, inherited rather than built.
- Sandbox, prefetch, and remote all consume post-discovery `getInputs()`; children flow as in Phase 1.
  One remote-specific verification: `MerkleTreeComputer` handles individual `TreeFileArtifact` inputs correctly and its per-tree subtree cache (`remote/merkletree/MerkleTreeComputer.java:747-801`, keyed on aggregate tree metadata) is never consulted for a pruned subset.

**Action key.**
`computeKey` contributions per selection: sources' exec paths, include/exclude, cardinality/flags, and filter identity as function name + `bzlTransitiveDigest` — deliberately *not* evaluation-based (unsound before metadata exists) and deliberately including the callback digest (body edits invalidate; resolved-set digests then keep the cache honest on the input side).

**Exit criteria:** selections as `inputs` work under local/sandbox/remote/BwoB; changing an unselected sibling does not re-execute the consumer (the granularity test); empty/ambiguous/kind failures match the proposal; incremental correctness under `--track_incremental_state` and analysis-cache round trips (types serialize).

# Phase 4 — Args integration

- New case in `StarlarkCustomCommandLine.VectorArg` alongside directory expansion (`maybeExpandDirectories`, `:372`): a selection value resolves through the same engine using the `InputMetadataProvider` available at `expand(...)` time (`:899`), then flows into existing formatting (`map_each`, `format_each`, `uniquify`).
  Mirror the "directory must be an input" invariant and its `CommandLineExpansionException` (`:410-418`): a selection in `Args` whose sources are not scheduling deps of the action is an execution error.
- `SelectedFile` accepted by `args.add` as a scalar, rendering its resolved exec path.
- Fingerprinting (`VectorArg.addToFingerprint`, `:452`): digest the selection *spec* (as in Phase 3), never the expansion — this sidesteps rather than inherits the `b/160181927` caveat.
- aquery `--include_commandline` renders the placeholder token where the expansion would sit (interim: `repr` form; structured form arrives in Phase 6).

# Phase 5 — `SelectedFile` as executable and in tools

- `StarlarkActionFactory.run` accepts a `SelectedFile` for `executable`, storing it on the enhanced action; the resolved child (available post-discovery, since resolution happened in `discoverInputs`) supplies argv[0] at spawn construction (`SpawnAction.getSpawn`, `analysis/actions/SpawnAction.java:363-389`) and joins the staged inputs.
- Executable-bit check against the child's `FileArtifactValue` at resolution time, failing with the standard resolution error format.
- `tools` entries likewise (plain staging, no runfiles — matching bare-`File` tools).
- Progress message and `describeKey` render the placeholder until resolution.

# Phase 6 — aquery structured output

Mechanical per the exploration; the pattern is regular but touches many files.

- `src/main/protobuf/analysis_v2.proto`: `FileSelection` message (id, cardinality, source artifact ids, include, exclude, filter function label, flags); `repeated FileSelection file_selections` in `ActionGraphContainer`; `repeated uint32 file_selection_ids` (and `selected_executable_id`) on `Action`.
  Command lines keep placeholder *strings* in `Action.arguments` — there is no command-line-fragment table today and this plan does not introduce one.
- New `KnownFileSelections extends BaseCache` (`skyframe/actiongraph/v2/`), `outputFileSelection` on `AqueryOutputHandler` and its three implementations (`StreamedConsumingOutputHandler`, `MonolithicOutputHandler`, `AqueryConsumingOutputHandler`), wiring in `ActionGraphDump.dumpSingleAction` (`:272-281` vicinity).
- Text format: placeholder branch in `ActionGraphTextOutputFormatterCallback.writeText` (`:229-254`), following the `(TreeArtifact)` suffix precedent.

# Phase 7 — Hardening and graduation

- **Rewinding:** lost-input recovery for resolved children must rewind the tree's generating action; the recent tree-artifact CAS-miss rewinding fixes ([#30065](https://github.com/bazelbuild/bazel/issues/30065), [#30103](https://github.com/bazelbuild/bazel/issues/30103)) are the adjacent, freshly exercised machinery — add selection/pick cases to those regression suites.
- **Shared actions / conflict checking:** two configured targets declaring identical actions with selections must compare equal (spec-based `computeKey` gives this; test it).
- **Skymeld:** interleaved analysis/execution exercises the deferred-owner binding from Phase 1 and discovery scheduling from Phase 3 under concurrency; dedicated tests.
- **Path mapping:** selections resolve to children whose paths may be mapped (`PathMapper` flows through both `SpawnInputExpander` and `StarlarkCustomCommandLine`); verify placeholder fingerprinting composes with `--experimental_output_paths=strip`.
- **Memory:** interned selection specs; confirm no retained `InputMetadataProvider` references after resolution (the `clearInputMetadataProvider` lesson, `StarlarkCustomCommandLine.java:1292`).
- Flag flip plan: experimental → `--incompatible`-free graduation once rules_js/toolchain-style consumers validate against the prototype, mirroring the lazy-downloads PR process.

# Test strategy

- **Unit:** artifact-layer tests for deferred-owner `TreeFileArtifact` creation and equality with execution-time children; resolution-engine tests (patterns, filter contract violations, ordering, cardinality); `StarlarkActionFactory` validation tests per function.
- **Analysis tests:** type errors on forbidden surfaces (`DefaultInfo`, depsets, outputs); provider forwarding; `repr` stability.
- **Execution integration** (Java integration tests + shell tests): the Phase-1/3 exit-criteria scenarios across strategies — local, `linux-sandbox`, remote (`build_bazel_remote_execution` test fixture), BwoB minimal/toplevel; incrementality assertions via action-count metrics (sibling-churn non-invalidation is *the* headline behaviour and needs a direct test).
- **Query:** aquery text + proto golden tests; cquery `--output=files` / `--output=starlark`; BEP golden for picks in output groups.
- **Benchmark:** an extracted-SDK scenario (thousands-of-children tree, three-file consumer) comparing whole-tree input vs selection on clean/incremental builds — the evidence for the graduation case, and for the copy-action/alias positioning debate.

# Sequencing and independence

```
Phase 0 ──► Phase 1 ──► Phase 2        (picks: usable end-to-end after 1; declaratively complete after 2)
     └────► Phase 3 ──► Phase 4 ──► Phase 6 ──► Phase 7
                   └──► Phase 5 ──────┘
```

Phases 1-2 (picks) deliver standalone value and de-risk the artifact-layer invariant changes before selections build on the same child-handling paths.
Phase 3 is the largest single unit; 4 and 5 are parallel once it lands.
A minimal credible prototype for proposal review is Phases 0, 1, and 3 with `select_file`-as-executable pulled forward from Phase 5 (the lazy-downloads toolchain demo needs exactly that slice).

# Open implementation questions

- **Deferred owner binding** (Phase 1) is the only invariant change in `Artifact.java`, the most central class in Bazel; the alternative — restricting picks to *dependency* trees in v1 and lifting the same-target restriction later — halves the risk at real expressiveness cost (the extract-then-pick-in-one-rule pattern).
  Decide after prototyping the binding hook.
- **Where resolution results live for `Args`/executable reuse:** discovery installs children as inputs, but the per-selection resolved lists must also reach spawn construction and command-line expansion; likely a small per-action resolved-selections map on the enhanced action instance, populated during discovery.
  Needs care under action rewinding (re-discovery must repopulate).
- **`getSchedulingDependencies()` composition:** the enhanced Starlark action already uses it for shadowed actions (`StarlarkAction.java:316-321`); merging shadowed-action deps with selection sources is straightforward but the interaction is untested territory.
- **Subtree picks vs `pick_directory` representation:** reusing subtree `SpecialArtifact`s assumes `map_directory`'s subtree support is general enough for arbitrary nesting; if it proves template-expansion-specific, `pick_directory` needs its own artifact flavour, which would ripple through `ActionInputMap`'s grandparent walk.
  Validate first in Phase 1.
