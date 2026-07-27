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
- **Matches** are new machinery, but every load-bearing mechanism has a battle-tested precedent: input discovery (`CppCompileAction` header pruning, `EnhancedStarlarkAction`), scheduling dependencies, execution-time Starlark evaluation (`StarlarkMapActionTemplate`), and late-expanding non-file input kinds (Fileset).
  The work is composing them, not inventing.

Everything ships behind a single flag and in independently landable phases; each phase leaves the tree green and the feature coherent for the surface area it covers.

## Key mechanism decisions

| Proposal concept | Engine mechanism |
|---|---|
| Pick (`pick_file`/`pick_directory`) | Analysis-time `TreeFileArtifact` via `TreeFileArtifact.createTreeOutput` (`actions/Artifact.java:1131`); directory picks reuse the subtree `SpecialArtifact` representation that `template_ctx.declare_subdirectory` introduced |
| Pick existence check | The existing missing-child check in `ActionExecutionFunction.getAndCheckInputSkyValue` (`skyframe/ActionExecutionFunction.java:1124`), reframed from `NONDETERMINISTIC_TREE_ARTIFACT` |
| Match resolution point | Input discovery: source trees in `getSchedulingDependencies()`, resolution in `discoverInputs()`, concrete children installed via `updateInputs()` — the `CppCompileAction` pattern (`rules/cpp/CppCompileAction.java:1256`, `:634`, `:1204`) |
| Match cache granularity | The discovered-inputs action cache path (`actions/ActionCacheChecker.java:491-512`, `actions/cache/ActionCache.java` `discoveredInputPaths`) — cache entries list and digest only resolved children |
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
  - `starlarkbuildapi/MatchedFileApi.java` and `starlarkbuildapi/MatchedFilesApi.java`, `@StarlarkBuiltin`, no methods beyond documentation surface in v1.
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
- Prefix conflicts: non-issue for *output* conflict checking (which runs over action outputs only, `skyframe/ArtifactConflictFinder.java:140`, `actions/Actions.java:320`; picks are never outputs). **But input-side overlap was not free for `pick_directory`:** an origin tree and an enclosing `pick_directory` subtree taken by the same action are *nested tree artifacts*, which `ActionInputMap`'s trie explicitly forbade (a terminal node stored a bare `TreeArtifactValue` with no descendants; `ActionInputMap.java:67-69`) — the pair threw `ClassCastException` in `TrieArtifact.add`. Fixed by letting a trie node carry both a terminal `treeValue` and nested `subFolders`, promoting on demand; `findTreeArtifactNodeAtPrefix`/`forEachTreeArtifact` updated to honour it. `pick_file` overlap is free (the pick equals the origin's expanded child). See implementation-findings bullet on co-existence.

**Error semantics.**
- A missing child currently fails as `NONDETERMINISTIC_TREE_ARTIFACT` ("a previous execution produced child... but a subsequent execution did not", `ActionExecutionFunction.java:1127`).
  Add a distinct failure detail for declared picks with the proposal's message shape (missing path, tree, generating target), and a kind-mismatch check (file pick resolving to directory and vice versa) at the same point.

**Exit criteria:** a rule extracts an archive into a declared directory, picks a file from it, feeds it to a second action as input and as `executable`; missing-path and wrong-kind cases produce the specified errors; action-cache behaviour is child-granular for the consumer... **note:** it is not, in this phase — the consumer's cache key digests the child's metadata only if the child (not the tree) is the declared input, which it is here, so child-granularity holds for picks by construction.
Integration tests must cover local, sandboxed, and remote execution, plus BwoB (`--remote_download_minimal`) verifying sibling children are not fetched.

# Phase 2 — Picks on declarative surfaces *(implemented)*

What Phase 1 does not cover: picks flowing through providers into runfiles, top-level builds, and BEP.
These are the "assumes Starlark-visible artifact ⇒ analysis-declared node" surfaces the proposal's backward-compatibility section flags.
Exploration predicted most of these were "free" and only BwoB registration was a hard blocker; that held — **one code change (BwoB), the rest verified working**.

- **Completion / top-level build — free.**
  A pick in `DefaultInfo.files` builds top-level with no code change: `Artifact.key` routing plus `addToMap` parent-expansion carry it through `CompletionFunction`. (Integration test: `pickFile_asDefaultInfoFilesOutput_buildsTopLevel`.)
- **Runfiles — free.**
  An executable rule with a pick in `ctx.runfiles(files=[pick])` builds, and `SourceManifestAction` lists the child at its tree-relative runfiles path (`<workspace>/…/archive_tree/bin/data`) — no `Runfiles.java` change needed; the child's `getRootRelativePath()` already yields the correct runfiles position, and its metadata is present via the parent tree's `TreeArtifactValue` in the completion input map. (Test: `pickFile_inRunfiles_landsAtChildRunfilesPath` asserts the manifest entry.)
- **BwoB top-level download — one code change.**
  `RemoteOutputChecker.shouldDownloadOutput` already understood tree children, but registration was blocked: `ConcurrentArtifactPathTrie.add` rejects a `TreeFileArtifact` (`remote/ConcurrentArtifactPathTrie.java:41-46`) because a child's exec path has its tree's exec path as a prefix, violating the trie's no-prefix invariant. **Fix:** a `childPathsToDownload` exact-match side set on `RemoteOutputChecker`; `addOutputToDownload` routes a `TreeFileArtifact` there instead of the trie, and `shouldDownloadOutput` consults it before the trie. `bazel build //x:pick` then downloads the picked child and only the child, and the whole tree is not wholesale-marked. (Unit test: `RemoteOutputCheckerTest.shouldDownloadOutput_standaloneTreeChild_downloadsOnlyThatChild`. **Follow-up:** end-to-end coverage on the remote fixture — `BuildWithoutTheBytesIntegrationTestBase` — is not yet added; the download *decision* logic is unit-verified, but the full fetch pipeline under `--remote_download_minimal` is not.)
- **BEP — free.**
  A pick as a top-level output emits in the BEP as an ordinary output `File` (file:// URI, length, digest) via the plain-artifact branch of `CompletionContext.visitArtifacts` — *not* as a directory output — with its `FileArtifactValue` available from the completion `ActionInputMap`. (Test in `TargetCompleteEventTest.pickedTreeChild_appearsAsNormalOutputFile`.)
- **cquery — free (by construction, not separately tested).**
  `--output=files`/`--output=starlark` traverse `Artifact`s only and never inspect tree-child-ness; a pick appears as its exec path. No code, and no dedicated cquery harness added.

**Exit criteria — met** for `DefaultInfo.files`, runfiles, and BEP under local execution; the BwoB download decision is unit-covered but the remote-fixture end-to-end and a `sh_test`-style test-data-dependency scenario remain as test follow-ups.

# Phase 3 — Match core: types, resolution, inputs

The heart of the dynamic tier: `match_file` / `match_directory` / `match_files` resolving during input discovery.

**API layer.**
- Three methods on `StarlarkActionFactory`; shared validation (sources are directory artifacts / matches, patterns are valid globs, `filter` passes `validateIsTopLevelStarlarkFunction`).
- The match spec object (sources, include, exclude, filter reference, cardinality, flags) is the payload of both Starlark types; specs are structurally comparable so identical declarations dedupe.

**Resolution engine** (new, `analysis/actions/` or `actions/`):
- Given specs plus an `InputMetadataProvider`, produce ordered `ImmutableList<TreeFileArtifact>`;
  1. children from `getTreeMetadata(source).getChildren()` per source (match sources recurse; `match_directory` sources contribute children below the resolved directory),
  2. glob matching against `getTreeRelativePathString()` — reuse the pattern compiler behind package globbing rather than writing a new matcher,
  3. `filter` evaluation on the model of `StarlarkMapActionTemplate.java:233-258`: fresh `Mutability`, `StarlarkThread.create` with the captured `StarlarkSemantics`, debug print handler, candidates presented as the `TreeFileArtifact`s themselves (they already expose `tree_relative_path`), result validated as a subset,
  4. ordering, cardinality (`exactly one` / `allow_empty`), and kind checks, with the proposal's error messages.
- Memoise per (spec, source-metadata) within a build; Skyframe's per-action discovery memoisation makes cross-action caching an optimisation, not a correctness need.

**Consuming-action integration** (`analysis/actions/StarlarkAction.java`):
- When any input is a match, build the enhanced variant (the `EnhancedStarlarkAction` split already exists for `unused_inputs_list`/shadowed actions, `StarlarkAction.java:223-521`);
  - Source trees are declared as ordinary inputs, **not** merely scheduling dependencies. *(Corrected during implementation: `SkyframeInputMetadataProvider.getTreeMetadata` returns `null`, so a tree that is only a scheduling dependency has no metadata available during `discoverInputs`. Trees must be in the action's input map — i.e. declared inputs — to be resolvable. They are then pruned in `discoverInputs`, so they are not staged and not in the cache key.)*
  - `discoversInputs()` returns true; `discoverInputs()` runs the resolution engine against the now-available tree metadata and installs the non-tree inputs + resolved children via `updateInputs()`, pruning the declared source trees.
  - `getAllowedDerivedInputs()` returns mandatory inputs plus source trees (the cache uses it to re-resolve stored child paths, `ActionCacheChecker.getCachedInputs`, `:747`).
- The discovered-inputs action cache path then does the rest: entries list resolved children (`ActionCache.Entry.discoveredInputPaths`) and digest exactly them, so sibling churn outside the resolved set does not invalidate — the proposal's invalidation-granularity promise, inherited rather than built.
- Sandbox, prefetch, and remote all consume post-discovery `getInputs()`; children flow as in Phase 1.
  One remote-specific verification: `MerkleTreeComputer` handles individual `TreeFileArtifact` inputs correctly and its per-tree subtree cache (`remote/merkletree/MerkleTreeComputer.java:747-801`, keyed on aggregate tree metadata) is never consulted for a pruned subset.

**Action key.**
`computeKey` contributions per match: sources' exec paths, include/exclude, cardinality/flags, and filter identity as function name + `bzlTransitiveDigest` — deliberately *not* evaluation-based (unsound before metadata exists) and deliberately including the callback digest (body edits invalidate; resolved-set digests then keep the cache honest on the input side).

**Exit criteria:** matches as `inputs` work under local/sandbox/remote/BwoB; changing an unselected sibling does not re-execute the consumer (the granularity test); empty/ambiguous/kind failures match the proposal; incremental correctness under `--track_incremental_state` and analysis-cache round trips (types serialize).

# Phase 4 — Args integration *(implemented)*

The blocker originally recorded here — `expand(...)` (`:899`) receives only an `InputMetadataProvider`, no action handle, and pruned source trees have no metadata in it — dissolved without new expansion-API plumbing, via a **capability-carrying provider wrapper**;

- The enhanced action stores per-spec resolution results (a `volatile ImmutableMap<FileMatchSpec, ImmutableList<Artifact>>`, populated in `discoverInputs`, deliberately transient: re-discovery repopulates it under rewinding, and cache hits never expand).
- `EnhancedStarlarkAction.getSpawn` wraps the context's provider in `MatchResolvingInputMetadataProvider` (delegates all metadata queries, additionally implements the new `FileMatchResolver` interface); `ActionExecutionContext.withInputMetadataProvider` was made public for this.
- `StarlarkCustomCommandLine` resolves matches through an `instanceof FileMatchResolver` check: vector values are spliced with resolved artifacts *before* directory expansion and `map_each` (so `map_each` sees real `File`s); scalar `add`/`add(format=)` values resolve to the single artifact and ride the existing `DerivedArtifact` path-mapping cases.
- **Not-an-input error**: a resolver miss (match in `Args` but never registered on the action) throws `CommandLineExpansionException` with a pointer at `inputs` — the moral equivalent of the "directory must be an input" invariant (`:410-418`).
- **Fingerprinting**: match values contribute `UUID + position + spec.addToFingerprint(...)` and are excluded from value-stream digesting and `map_each` fingerprint application — spec identity, never expansion, sidestepping `b/160181927`. Identical at analysis time (null provider) and execution-time `getKey`, so keys stay phase-consistent.
- **Placeholder rendering**: with a null provider (aquery `--include_commandline`, `describeKey`, progress args, extra-action spawn info) matches render their stable placeholder string; placeholders bypass `map_each` (batch-splitting around them) since the callback expects `File`s.
- **Depsets**: `add_all(depset-of-matches)` is rejected at analysis time (`Depset.getElementClass()` yields the `@StarlarkBuiltin` API interface — check assignability against `MatchedFileApi`/`MatchedFilesApi`, not the impl classes). Depset fingerprinting memoises per nested set and cannot host per-action resolution.
- `Args.add` rejects `MatchedFiles` (multi-valued) and directory-typed `MatchedFile`s.
- Build note: the match value types moved out of `analysis_cluster` into a fine-grained `analysis:actions/file_selection` target — `starlark_custom_command_line` and `starlark/args` sit *below* the cluster and would otherwise cycle.

# Phase 5 — `MatchedFile` as executable and in tools *(implemented)*

- `SpawnAction.Builder.setExecutable(MatchedFile)` stores the match as the raw `executableArg`; `CommandLines.SingletonCommandLine` gained a case for the new `lib.actions.ExecutionResolvedArgument` interface (which `MatchedFile` implements), rendering the resolved child's (path-mapped) exec path as argv[0] at expansion. With a null or non-resolver provider it renders the placeholder — safe because `AbstractCommandLine.addToFingerprint` fingerprints with a null provider, and the true spawn path always has the wrapper (the factory auto-registers the executable's spec as an input match, forcing the enhanced action).
- The executable-bit precheck is **dropped**: `FileArtifactValue` carries no executable bit and Bazel stages outputs (tree children included) executable; a genuinely non-executable file fails at spawn time with the OS error. Proposal updated.
- `tools` accepts `MatchedFile`/`MatchedFiles`; both register as input matches (staged, no runfiles — matching bare-`File` tools).
- Progress message and `describeKey` render the placeholder via the same null-provider path as Phase 4.

# Phase 6 — aquery structured output

Mechanical per the exploration; the pattern is regular but touches many files.

- `src/main/protobuf/analysis_v2.proto`: `MatchedFiles` message (id, cardinality, source artifact ids, include, exclude, filter function label, flags); `repeated MatchedFiles file_selections` in `ActionGraphContainer`; `repeated uint32 file_selection_ids` (and `selected_executable_id`) on `Action`.
  Command lines keep placeholder *strings* in `Action.arguments` — there is no command-line-fragment table today and this plan does not introduce one.
- New `KnownMatchedFiless extends BaseCache` (`skyframe/actiongraph/v2/`), `outputMatchedFiles` on `AqueryOutputHandler` and its three implementations (`StreamedConsumingOutputHandler`, `MonolithicOutputHandler`, `AqueryConsumingOutputHandler`), wiring in `ActionGraphDump.dumpSingleAction` (`:272-281` vicinity).
- Text format: placeholder branch in `ActionGraphTextOutputFormatterCallback.writeText` (`:229-254`), following the `(TreeArtifact)` suffix precedent.

# Phase 7 — Hardening and graduation

- **Rewinding:** lost-input recovery for resolved children must rewind the tree's generating action; the recent tree-artifact CAS-miss rewinding fixes ([#30065](https://github.com/bazelbuild/bazel/issues/30065), [#30103](https://github.com/bazelbuild/bazel/issues/30103)) are the adjacent, freshly exercised machinery — add match/pick cases to those regression suites.
- **Shared actions / conflict checking:** two configured targets declaring identical actions with matches must compare equal (spec-based `computeKey` gives this; test it).
- **Skymeld:** interleaved analysis/execution exercises the deferred-owner binding from Phase 1 and discovery scheduling from Phase 3 under concurrency; dedicated tests.
- **Path mapping:** matches resolve to children whose paths may be mapped (`PathMapper` flows through both `SpawnInputExpander` and `StarlarkCustomCommandLine`); verify placeholder fingerprinting composes with `--experimental_output_paths=strip`.
- **Memory:** interned match specs; confirm no retained `InputMetadataProvider` references after resolution (the `clearInputMetadataProvider` lesson, `StarlarkCustomCommandLine.java:1292`).
- Flag flip plan: experimental → `--incompatible`-free graduation once rules_js/toolchain-style consumers validate against the prototype, mirroring the lazy-downloads PR process.

# Test strategy

- **Unit:** artifact-layer tests for deferred-owner `TreeFileArtifact` creation and equality with execution-time children; resolution-engine tests (patterns, filter contract violations, ordering, cardinality); `StarlarkActionFactory` validation tests per function.
- **Analysis tests:** type errors on forbidden surfaces (`DefaultInfo`, depsets, outputs); provider forwarding; `repr` stability.
- **Execution integration** (Java integration tests + shell tests): the Phase-1/3 exit-criteria scenarios across strategies — local, `linux-sandbox`, remote (`build_bazel_remote_execution` test fixture), BwoB minimal/toplevel; incrementality assertions via action-count metrics (sibling-churn non-invalidation is *the* headline behaviour and needs a direct test).
- **Query:** aquery text + proto golden tests; cquery `--output=files` / `--output=starlark`; BEP golden for picks in output groups.
- **Benchmark:** an extracted-SDK scenario (thousands-of-children tree, three-file consumer) comparing whole-tree input vs match on clean/incremental builds — the evidence for the graduation case, and for the copy-action/alias positioning debate.

# Sequencing and independence

```
Phase 0 ──► Phase 1 ──► Phase 2        (picks: usable end-to-end after 1; declaratively complete after 2)
     └────► Phase 3 ──► Phase 4 ──► Phase 6 ──► Phase 7
                   └──► Phase 5 ──────┘
```

Phases 1-2 (picks) deliver standalone value and de-risk the artifact-layer invariant changes before matches build on the same child-handling paths.
Phase 3 is the largest single unit; 4 and 5 are parallel once it lands.
A minimal credible prototype for proposal review is Phases 0, 1, and 3 with `match_file`-as-executable pulled forward from Phase 5 (the lazy-downloads toolchain demo needs exactly that slice).

# Open implementation questions

- **Deferred owner binding** (Phase 1) is the only invariant change in `Artifact.java`, the most central class in Bazel; the alternative — restricting picks to *dependency* trees in v1 and lifting the same-target restriction later — halves the risk at real expressiveness cost (the extract-then-pick-in-one-rule pattern).
  Decide after prototyping the binding hook.
- **Where resolution results live for `Args`/executable reuse:** discovery installs children as inputs, but the per-match resolved lists must also reach spawn construction and command-line expansion; likely a small per-action resolved-matches map on the enhanced action instance, populated during discovery.
  Needs care under action rewinding (re-discovery must repopulate).
  *(Resolved — see Phase 4. The map lives on the enhanced action as sketched; the missing link — expansion has no action handle — closed by wrapping the `InputMetadataProvider` the action already controls at `getSpawn` time with a `FileMatchResolver`-capable delegate, discovered by expansion via `instanceof`. No `CommandLines.expand` signature change was needed. Rewinding is covered because the field is transient and re-discovery repopulates it before any re-execution.)*
- **`getSchedulingDependencies()` composition:** the enhanced Starlark action already uses it for shadowed actions (`StarlarkAction.java:316-321`); merging shadowed-action deps with match sources is straightforward but the interaction is untested territory.
- **Subtree picks vs `pick_directory` representation:** reusing subtree `SpecialArtifact`s assumes `map_directory`'s subtree support is general enough for arbitrary nesting; if it proves template-expansion-specific, `pick_directory` needs its own artifact flavour, which would ripple through `ActionInputMap`'s grandparent walk.
  Validate first in Phase 1.
  *(Resolved — the gap was narrower than the original validation suggested. Routing a subtree as a standalone input NPE'd in `ArtifactFunction.compute` (`ActionExecutionValue.getTreeArtifactValue(subtree)` is `null`; the generating action records only the root tree's value), but everything below Skyframe — `ActionInputMap`'s grandparent walk, `SpawnInputExpander`, prefetch — already handled subtree inputs from the `map_directory` flow. The fix is a single branch in `ArtifactFunction`: a subtree node depends on the root tree's own artifact node (`env.getValue(Artifact.key(root))`, which transparently covers template-generated roots) and derives a sub-view, matching children by exec path (the root's aggregated value can hold children parented to template-output subtrees, so parent-relative paths are not reliable) and re-parenting onto the subtree unless already so parented. An empty sub-view — missing path or fileless directory, indistinguishable in tree metadata — is rejected at the consumer with the missing-path error, in `ActionExecutionFunction.getAndCheckInputSkyValue`. `pick_directory` is consequently live; directory-typed match members remain deferred because they arrive through input discovery rather than declared-input request, a separate admission path.)*
