---
created: 2026-07-27
last updated: 2026-07-27
status: Draft
reviewers: []
title: Tree Artifact Selection
authors:
  - Silic0nS0ldier
discussion thread: TBD
---

# Abstract

Tree artifacts are the natural output type for actions whose output structure is unknowable at analysis time, archive extraction being the canonical case.
They are also nearly opaque: a rule holding a tree artifact cannot reference a file inside it, cannot narrow it, and cannot hand a piece of it to another action without copying the piece out with a spawn.
This proposal adds two complementary primitives;

- **Picks** — `ctx.actions.pick_file(directory, path)` and `ctx.actions.pick_directory(directory, path)` return a genuine `File` for a statically known path inside a directory artifact.
  The exec path is fully determined at analysis time; only existence is deferred to execution.
- **Matches** — `ctx.actions.match_file`, `ctx.actions.match_directory`, and `ctx.actions.match_files` return opaque handles to dynamically resolved contents (glob patterns and/or a Starlark callback) of one or more directory artifacts.
  The single forms resolve to exactly one file or directory; the plural form resolves to a set.
  Resolution happens at execution time, when the source trees' contents are known.

Picks behave like ordinary derived files everywhere (`inputs`, `executable`, `Args`, providers, `DefaultInfo`, runfiles) and remain fully visible to `aquery`, `cquery`, and BEP.
Matches are confined to surfaces that already tolerate execution-time resolution — action inputs and `Args`, plus (for a single selected file) `executable` and `tools` — which keeps every declarative surface well-defined by construction: `aquery` renders a match as a structured placeholder, and `cquery`'s file-oriented outputs never encounter one.

Together with [Lazy Downloads](2026-07-03-lazy-downloads.md) this closes the loop on moving acquisition out of repository rules: download an archive lazily, extract it with an ordinary action, and consume individual files from the result — without a repository rule, without a spawn per file, and without materialising the parts of the tree the build never touches.

# Background

## Status quo

A tree artifact (`ctx.actions.declare_directory`) is a single `File` whose contents are captured when its generating action completes.
At analysis time the contents are unknown, and the Starlark API offers no way to defer the question.
What exists today;

- **Whole-tree consumption.**
  A tree artifact can be an action input, in which case the entire tree is staged (and, under Build without the Bytes, the needed children fetched).
  There is no way to declare "this action consumes `bin/protoc` from that tree", so the action's input set, Merkle tree, and invalidation footprint are the whole tree.
  Large trees are a known scalability pain point ([#16899](https://github.com/bazelbuild/bazel/issues/16899), [#17009](https://github.com/bazelbuild/bazel/issues/17009), [#17804](https://github.com/bazelbuild/bazel/issues/17804)).
- **Command-line expansion.**
  `Args` expands tree artifacts into their children at execution time, and `map_each` (with `expand_directories = True`) can transform each child, observing `File.tree_relative_path`.
  This solves stringification only.
  It cannot narrow the staged input set, cannot produce a `File` handle, and offers no cross-file logic (each child is mapped independently).
- **Per-child action generation.**
  `ctx.actions.map_directory` (from [#21031](https://github.com/bazelbuild/bazel/issues/21031)) defers *action creation* to execution time: a top-level Starlark function receives the expanded children and creates actions over them.
  This is the right tool for transforming a tree (compile every `.m` file), but a heavyweight answer to referencing one: obtaining a single file costs an action template, a spawn, and a copy, and the result must land inside a declared output directory of the template.
- **Ecosystem workarounds.**
  [`bazel-lib`'s `directory_path`](https://github.com/bazel-contrib/bazel-lib/blob/222a5bf32e8b6a546059cfff85fe01af7164e596/lib/directory_path.bzl) standardises a `DirectoryPathInfo` provider — literally a (tree artifact, relative path) pair — because "otherwise there is no way to give a Bazel label for it".
  Since no engine support exists, every consumer that needs a real file (e.g. an `executable`) pays a copy spawn to extract it, and every consumer that accepts the provider must be taught about it individually.
  [`bazel-skylib`'s `match_file`](https://github.com/bazelbuild/bazel-skylib/blob/5c071b5006bb9799981d04d74a28bdee2f000d4a/rules/match_file.bzl) predates it and operates on the analysis-time file list, so it cannot see inside a tree artifact at all.

## Why lazy downloads sharpen this

[Lazy Downloads](2026-07-03-lazy-downloads.md) deliberately scopes archive extraction out: extraction is an ordinary action producing a tree artifact.
That decision is only tenable if tree artifacts are usable, and the flagship use cases immediately stress the gap;

- An npm package tarball extracts to a tree; the package's `bin` entry is one file inside it.
- A toolchain archive extracts to a tree; the compiler binary, the sysroot directory, and the default linker scripts are specific paths inside it.
- A downloaded SDK contains thousands of files; a given action needs three of them.

Today each of these forces a choice between repository rules (eager, local, the thing lazy downloads exists to avoid), copy spawns per referenced file, or passing whole trees around and paying staging and invalidation costs proportional to the archive rather than to the need.

## Prior art

- `ctx.actions.map_directory` and its `template_ctx` establish the execution-time Starlark callback pattern (top-level function, restricted environment) and `ExpandedDirectory` established children exposure as `File` objects.
- `Args.map_each` establishes that execution-time expansion can feed action cache keys soundly: the expanded command line is digested after expansion.
- Tree file artifacts already exist internally (`TreeFileArtifact`): native machinery (C++ action templates, tree expansion, the input prefetcher) routinely addresses individual children, including partial staging ([#16333](https://github.com/bazelbuild/bazel/issues/16333)).
  This proposal is, in large part, a Starlark surface for an existing internal concept.
- `Fileset` (Google-internal, never documented externally, since removed from Bazel) was a mechanism for late-bound file collections; its lesson (a parallel artifact universe with special cases everywhere) informs the restraint here.
- The [Copy Action](https://github.com/bazelbuild/proposals/pull/396) and [Artifact Alias](https://github.com/bazelbuild/proposals/blob/7b6fc2d20723489693c1a28f751c3f3ba33168f2/designs/2026-07-20-artifact-alias.md) proposals both need a way to *name* tree-child content before they can copy or alias it; see [Interaction with related proposals](#interaction-with-related-proposals).

# Proposal

Two tiers, split by what is knowable at analysis time.

The split is load-bearing.
A statically known path yields a statically known exec path, so the result can be a real `File` and every declarative surface (queries, BEP, providers) keeps working unmodified.
A dynamically resolved result cannot be a `File` no matter how the API is shaped — even a *single* dynamically selected file has an unknowable exec path — so it gets an opaque type whose permitted uses are exactly the surfaces that already tolerate execution-time resolution.
Collapsing the tiers into one API would force the static case down to the dynamic tier's restrictions for no benefit.

## Picks

```starlark
node = ctx.actions.pick_file(extracted, "bin/node")
headers = ctx.actions.pick_directory(extracted, "include/node")
```

- `directory` (`File`, mandatory): a directory artifact — a tree artifact, or another `pick_directory` result.
  Source directories are rejected (see [Out of scope](#out-of-scope)).
- `path` (string, mandatory): a non-empty relative path below `directory`.
  Validated at declaration time: no leading `/`, no `.` or `..` segments, no trailing `/`.

Both return a `File`;

- Its exec path is `directory`'s exec path joined with `path`; `path`, `dirname`, `basename`, `extension`, `root`, and `owner` all follow from that and from `directory`.
- `is_directory` is `False` for `pick_file` and `True` for `pick_directory`.
  The caller states which kind it expects; the claim is verified at execution.
- `tree_relative_path` is `path`, composed through nested picks — a pick two levels down from the root tree artifact reports its full path below that root, matching the field's existing behaviour for expanded children.
- No new action is created.
  The pick's generating action is the tree artifact's generating action; equality and hashing are structural, so picking the same path from the same tree twice yields the same artifact.

Because a pick is a `File` with an analysis-time exec path, it is usable everywhere a derived file is;

- As an action input, including as `executable` or in `tools`.
  The executable bit comes from the tree child itself, as captured by the extraction action.
- In `Args`, via any of `add`, `add_all`, `add_joined`; the rendered path is known at analysis time.
- In `DefaultInfo` (`files`, `executable`, runfiles), `OutputGroupInfo`, and arbitrary providers, including inside depsets.
- As the `target_file` of `ctx.actions.symlink`.
- As a source for further picks (`pick_directory` results) and for matches (see below).

The one thing a pick cannot be is an action *output*: its content is owned by the tree's generating action.

**Deferred validation.**
Existence and kind are checked when the pick is first consumed: staged as an input, materialised as a requested top-level output, or placed into a built runfiles tree.
A pick that resolves to nothing fails the consuming action with an error naming the missing path, the tree artifact, and the tree's generating target;

```
ERROR: /app/BUILD:12:1: PickedFile bin/node does not exist in tree artifact
bazel-out/downloads-extract/bin/external/nodejs+/node_dist (produced by @nodejs//:extract)
```

A pick that is declared but never consumed is never validated, consistent with the laziness of everything else in this design.
Kind mismatches (a `pick_file` resolving to a directory, or vice versa) fail identically.

**Scheduling and staging.**
An action consuming a pick depends on the tree's generating action, exactly as if it consumed the whole tree.
Its *input set*, however, contains only the picked child: the Merkle tree references one child, the action cache key digests one child, sibling changes inside the tree do not invalidate the consumer, and under Build without the Bytes only that child is fetched for local execution (the prefetcher already supports partial tree staging, [#16333](https://github.com/bazelbuild/bazel/issues/16333)).
A `pick_directory` stages its subtree.

## Matches

```starlark
# Exactly one file, path unknown until the archive is extracted.
node = ctx.actions.match_file(
    sources = [extracted],
    include = ["node-*/bin/node"],
)

# Exactly one directory.
root = ctx.actions.match_directory(
    sources = [extracted],
    include = ["node-*"],
)

# A set of files.
libs = ctx.actions.match_files(
    sources = [extracted_a, extracted_b],
    include = ["lib/**/*.so"],
    exclude = ["lib/**/debug/**"],
)
```

Three functions share one parameter shape and differ in cardinality contract and result type;

- `ctx.actions.match_file(sources, include = ["**"], exclude = [], filter = None)` returns a `MatchedFile`: an opaque handle that resolves to **exactly one regular file**.
- `ctx.actions.match_directory(sources, include = ["**"], exclude = [], filter = None)` returns a `MatchedFile` that resolves to **exactly one directory**.
- `ctx.actions.match_files(sources, include = ["**"], exclude = [], filter = None, exclude_directories = True, allow_empty = False)` returns a `MatchedFiles`: an opaque handle that resolves to a **set**.

All results are opaque, immutable values; the shared parameters;

- `sources` (sequence, mandatory): directory artifacts (tree artifacts, `pick_directory` results), `MatchedFile`s from `match_directory`, and/or `MatchedFiles`s.
  Order is significant (it defines result and disambiguation order) and duplicates are rejected.
  Accepting prior results as sources makes refinement free: a broad match can be narrowed, and a dynamically located root can be selected *within*, without re-stating anything.
- `include` / `exclude` (lists of strings): glob patterns with the semantics of package `glob()` (`*` within a path segment, `**` across segments), matched against paths relative to each source's root.
  The default `include = ["**"]` selects everything, which makes the pure-callback form natural.
- `filter` (callable): a Starlark function receiving the list of pattern-matched candidates as `File` objects (sorted; `tree_relative_path` available on each) and returning the subset to keep, as a list.
  Running after pattern matching, it composes with `include`/`exclude` rather than competing with them.
  It must be a top-level `def` (no lambdas or closures), the same restriction `map_directory`'s `implementation` carries and for the same reason: the function must be nameable for introspection and stable across evaluations.
  It must be pure; returning a `File` that was not among the candidates is an error.
  Cross-file logic is the reason `filter` receives the whole list rather than one file at a time — "keep the highest version", "take the first match per basename", and overlay-style resolution are all single-pass list functions.
  For the single forms, `filter` is the disambiguator: when patterns alone cannot guarantee one match, the callback narrows the field.

And the `match_files`-only parameters;

- `exclude_directories` (bool): when `False`, patterns may also match directories, which join the result as directory handles; consuming one stages its subtree, like a `pick_directory` (as *action inputs*, directory members are deferred in v1 — see [Implementation findings](#implementation-findings-v1-prototype)).
  The default matches package `glob()`.
- `allow_empty` (bool): when `False` (the default, matching `--incompatible_disallow_empty_glob`), a match resolving to zero files fails the consuming action.

The single forms have no `allow_empty`: an optional single file is incoherent (there is nothing for a consumer to stage), so **exactly one** is the contract.
Resolution to zero candidates or to more than one fails the consuming action, the latter listing the surviving matches so the fix (a tighter pattern or a `filter`) is evident.
`match_file` resolving to a directory, or `match_directory` to a regular file, fails identically to the equivalent pick mismatch.

Two degenerate cases fall out rather than needing features;

- `match_file([a, b, c], include = ["bin/tool"])` expresses "the `bin/tool` from whichever of these trees has one" — the multi-source single-file need that picks deliberately do not cover, because *which parent wins* is not an analysis-time fact (with more than one provider, the exactly-one contract forces an explicit `filter` rather than a silent precedence rule).
- `match_file([ctx.actions.match_directory([tree], include = ["node-*"])], include = ["bin/node"])` is "pick inside a root I had to find first": static picks stay static, and dynamic roots compose through `sources`.

**Resolution.**
A match resolves when a consuming action needs its input set (or executable), after all source trees' generating actions have completed;

1. Each source contributes its captured children (for a match source, its own resolved result; for a `match_directory` source, the children below the resolved directory).
2. `include`/`exclude` patterns filter by tree-relative path.
3. `filter`, if present, maps the candidate list to the kept list.
4. The result is ordered by source position, then lexicographically by tree-relative path; the cardinality contract (`exactly one`, or `allow_empty`) is enforced.

Resolution is deterministic given the sources' metadata, so it is memoised: any number of actions consuming the same match against the same tree contents resolve it once.
Two sources may both contain `lib/a.so`; a resolved *set* then simply contains both children (distinct artifacts, distinct exec paths).
Duplicate *rendered* strings on a command line, or collisions under a downstream materialisation, are the consumer's concern, as they are for any other input list.

**Where matches may appear.**
Both types share the base surfaces;

- **Action inputs** (`ctx.actions.run`, `run_shell`): in the `inputs` sequence alongside `File`s and depsets.
  The action's staged inputs, Merkle tree, prefetch set, and action cache key are computed from the *resolved* files — exactly as if they had been declared directly.
  A sibling change that does not alter the resolved result (paths and digests) does not invalidate the consumer.
- **`Args`**: a `MatchedFiles` via `add_all` / `add_joined` expands to the resolved files at execution time, in resolution order, with `map_each`, `format_each`, `before_each`, `uniquify`, and friends applying to the expansion; a `MatchedFile` may additionally be passed to `add` (and to `map_each`-style formatting) as a single value, rendering its resolved exec path.
  Passing a match to `Args` does not by itself add files as inputs (matching the existing behaviour of `Args` versus `inputs`); a match is typically passed to both.
- **Custom provider fields**, as a plain value or inside lists/dicts, so rules can forward a match to consumers (e.g. an extraction rule exposing "my headers", or a toolchain rule exposing "the compiler") without materialising anything.
  Matches may not be placed in depsets; see [Open questions](#open-questions).

A `MatchedFile` from `match_file` is additionally accepted where a single runnable file is;

- As the **`executable`** of `ctx.actions.run`.
  Spawn construction already happens at execution time; the resolved file becomes argv[0] and joins the staged inputs.
  *(The executable-bit precheck originally specified here is dropped — see [Implementation findings](#implementation-findings-v1-prototype); a non-executable file fails at spawn time.)*
  Since a bare `File` executable carries no runfiles today, a `MatchedFile` executable behaves identically: no runfiles.
- In **`tools`**, staging the resolved file (again with no implied runfiles).

A `MatchedFile` from `match_directory` is additionally accepted as a **source** to the `match_*` functions, enabling matching below a dynamically located root.
It is deliberately *not* accepted by `pick_file`/`pick_directory`: picks promise analysis-time exec paths, and a pick below a dynamic root cannot keep that promise.
"Pick an exact path under a dynamic root" is spelled `match_file(sources = [root], include = ["<the path>"])`, which is the same operation with the honest (deferred) type.

Everywhere else — `outputs`, `DefaultInfo`, `OutputGroupInfo`, runfiles, `ctx.actions.symlink` — any match is a type error at analysis time.
These exclusions are what make the introspection story below hold by construction rather than by caveat.
When a genuine `File` is needed from a dynamic result, materialise it (see [Interaction with related proposals](#interaction-with-related-proposals)).

## Execution semantics

Neither primitive creates an action, spawns anything, or moves bytes.
Both are resolved views over content that already has a generating action;

- **Dependency edges** are those of the sources' generating actions.
  Consuming a pick or match orders the consumer after the tree's producer, never differently from consuming the tree itself.
- **Input pruning** is the material change.
  Today `tree → consumer` means the whole tree is staged, uploaded into Merkle trees, digested into cache keys, and (under BwoB, for local execution) downloaded.
  With picks and matches the consumer's footprint is the resolved subset.
  For the SDK-archive case this converts costs proportional to the archive into costs proportional to use.
- **Action cache keys** digest the resolved set (path plus content digest per member), which is precisely the treatment declared inputs get today.
  A `filter` body change invalidates consumers only if it changes the resolved set — the same observable-effect keying that `map_each` command lines already have.
- **Errors** (missing pick, empty or ambiguous match, kind mismatch, `filter` returning foreign files) fail the consuming action, attributed with the pick/match spec, the source trees, and their generating targets.
- **Concurrency**: resolution is pure metadata work on the execution thread; the callback runs in a restricted Starlark environment (no `ctx`, no I/O), like `map_each` and `map_directory` implementations.

## Introspection

The guiding rule: **picks are ordinary files everywhere; matches appear only where a placeholder is honest.**

**`bazel aquery`.**
A pick input or output reference prints as its exec path, indistinguishable from any other file, because nothing about it is unknown.
A match — in an action's inputs, as its executable, or in a command line — prints as a structured placeholder carrying everything Bazel knows before execution;

```
$ bazel aquery '//app:compile_native'
action 'Compiling app'
  Mnemonic: SdkCompile
  Executable: match_file(sources = [bazel-out/downloads-extract/bin/external/sdk+/dist],
                          include = ["sdk-*/bin/cc"])
  ...
  Inputs: [bazel-out/k8-fastbuild/bin/app/main.c,
           match_files(sources = [bazel-out/downloads-extract/bin/external/sdk+/sysroot],
                        include = ["lib/**/*.so"],
                        exclude = ["lib/**/debug/**"],
                        filter = @rules_sdk//sdk:defs.bzl%_newest_only)]
```

Command lines built from match-backed `Args` render the same placeholder tokens in place of the expansion (including argv[0] when the executable is a `MatchedFile`).
The proto/textproto/jsonproto formats gain a `file_selections` table (id, cardinality, source artifact ids, include, exclude, filter function label, flags) referenced from `inputs`, from `executable`, and from command-line fragments, mirroring how tree artifacts are represented pre-expansion today.
`--skyframe_state` and post-execution aquery may additionally report resolved sets when available, but the placeholder form is the contract.

**`bazel cquery`.**
Handled by construction rather than by rendering: every cquery surface that enumerates files (`--output=files`, label outputs, `--output_groups`) only ever traverses `File`s, and matches cannot reach those surfaces.
Picks appear with concrete exec paths.
The one place a match can surface is `--output=starlark` touching a custom provider; `repr()` of a `MatchedFile` or `MatchedFiles` is a stable single-line form of the same placeholder aquery uses.
No cquery output format needs to learn anything new.

**BEP.**
Picks can appear in `NamedSetOfFiles` (they carry concrete paths, and materialise on request like any BwoB output).
Matches cannot appear, because they cannot enter `DefaultInfo` or output groups.

## Interaction with related proposals

**[Lazy Downloads](2026-07-03-lazy-downloads.md).**
The consuming half of the story this proposal completes.
Download an archive (lazy, checksum-identified, remote-executable), extract it with an ordinary action (tree artifact), then pick the binary and select the libraries.
No repository rule, and no step in the chain materialises more than the build actually touches.

**[Copy Action](https://github.com/bazelbuild/proposals/pull/396).**
Complementary in both directions.
Matches eliminate the copies that exist only to *reference* tree content (the `DirectoryPathInfo` + copy-spawn pattern).
Conversely, the copy action is the natural materialisation escape hatch: `ctx.actions.copy(input = <MatchedFiles>, output = <declared directory>)` turns a dynamic match into a genuine tree artifact, and `ctx.actions.copy(input = <MatchedFile>, output = <declared file>)` gives a dynamically located file a static exec path and a genuine `File` — for when the result is required in a `DefaultInfo`, a runfiles entry, or an API boundary that demands `File`s.
One native action either way, rather than a spawn per file.
This proposal reserves match-typed `input` support as a copy-action extension rather than specifying it here.

**[Artifact Alias](https://github.com/bazelbuild/proposals/blob/7b6fc2d20723489693c1a28f751c3f3ba33168f2/designs/2026-07-20-artifact-alias.md).**
Also complementary: `ctx.actions.alias(output = out, actual = <pick>)` would give tree-child content a second, analysis-time exec path with no bytes moved — useful when the child must appear at a canonical location.
Note the reduced need, though: much of what motivates re-homing a tree child today is that a child cannot be referenced *in place*; picks remove that pressure.
This proposal takes no position between copy and alias; it supplies both with a well-defined way to name their tree-child sources, which neither currently has.

## Example: npm package, end to end

Continuing the `npm_import` sketch from [Lazy Downloads](2026-07-03-lazy-downloads.md#example-npm_import): the tarball download and extraction are unchanged; picks and matches replace what would otherwise be `directory_path` targets and copy spawns.

```starlark
def _npm_import_impl(ctx):
    tarball = ctx.downloads[ctx.attr.package]
    extracted = ctx.actions.declare_directory(ctx.attr.name)
    ctx.actions.run(
        executable = ctx.executable._extract,
        arguments = [tarball.path, extracted.path],
        inputs = [tarball],
        outputs = [extracted],
    )

    # The package's bin entry: one file inside the tree, usable as an executable.
    bin = ctx.actions.pick_file(extracted, "package/" + ctx.attr.bin_path) if ctx.attr.bin_path else None

    # Type declarations for downstream type-checkers: a dynamic subset,
    # forwarded without materialising anything.
    types = ctx.actions.match_files(
        sources = [extracted],
        include = ["package/**/*.d.ts", "package/**/*.d.mts"],
        allow_empty = True,
    )

    return [
        DefaultInfo(files = depset([extracted]), executable = bin),
        NpmPackageInfo(directory = extracted, types = types),
    ]
```

A consumer type-checks against the declarations of many packages without staging any package's full contents;

```starlark
def _ts_check_impl(ctx):
    type_inputs = [dep[NpmPackageInfo].types for dep in ctx.attr.deps]
    args = ctx.actions.args()
    for sel in type_inputs:
        args.add_all(sel)
    ctx.actions.run(
        executable = ctx.executable._tsc,
        arguments = [args],
        inputs = ctx.files.srcs + type_inputs,
        outputs = [...],
    )
```

And the toolchain case, exercising both tiers.
Node.js archives extract to a versioned root (`node-v22.1.0-linux-x64/...`), which the rule locates dynamically rather than reconstructing from attributes;

```starlark
def _node_toolchain_impl(ctx):
    dist = ctx.attr.dist[DefaultInfo].files.to_list()[0]  # extracted archive (tree artifact)
    root = ctx.actions.match_directory(sources = [dist], include = ["node-*"])
    return [platform_common.ToolchainInfo(
        node = ctx.actions.match_file(sources = [root], include = ["bin/node"]),
        headers = ctx.actions.match_directory(sources = [root], include = ["include/node"]),
    )]

def _run_js_impl(ctx):
    toolchain = ctx.toolchains["//node:toolchain_type"]
    out = ctx.actions.declare_file(ctx.label.name + ".out")
    ctx.actions.run(
        executable = toolchain.node,  # MatchedFile as executable
        arguments = [ctx.file.entry.path, out.path],
        inputs = [ctx.file.entry],
        outputs = [out],
    )
    return [DefaultInfo(files = depset([out]))]
```

Under remote execution with Build without the Bytes, a build using this toolchain fetches the Node.js archive into the CAS, extracts it remotely, and stages `bin/node` (and nothing else) into the actions that run it.
No repository rule ran, and the archive never touched the local machine.

## Out of scope

This proposal does not seek to solve;

- **Materialisation.**
  Turning a match into a real tree artifact, or re-homing a pick to a new exec path, belongs to the copy action and artifact alias proposals respectively; see [Interaction with related proposals](#interaction-with-related-proposals).
- **Source directories.**
  Picks and matches operate on directory *artifacts*, whose contents Bazel captures and digests.
  Source directories lack that metadata contract; if they ever gain it, extending these APIs is natural but separate.
- **Dynamic outputs.**
  Nothing here lets an action's *output* set be dynamic beyond what tree artifacts and `map_directory` already provide.
  Matches describe existing content; they do not declare new content.
- **Matches in runfiles, output groups, and depsets.**
  Deliberately excluded from the initial surface; see [Open questions](#open-questions).
- **Fileset revival.**
  Matches are per-consumer resolved input views, not a new artifact kind in the graph; the restriction list exists precisely to avoid re-growing Fileset's special-case surface.

# Backward-compatibility

All changes are additive and opt-in;

- Five new functions — `ctx.actions.pick_file`, `ctx.actions.pick_directory`, `ctx.actions.match_file`, `ctx.actions.match_directory`, `ctx.actions.match_files` — and two new types, `MatchedFile` and `MatchedFiles`.
- New optional input, executable, and tool kinds on existing action-creation and `Args` APIs.
- A new aquery proto table and placeholder rendering.

No existing rule, provider, or query output changes shape.
The API ships behind `--experimental_tree_artifact_selection`.

Picks reuse the internal tree-file-artifact machinery, so the main implementation risks are places that assume "Starlark-visible artifact ⇒ analysis-declared node" (artifact conflict checking, runfiles mapping, top-level output completion) and places that assume an action's input set is closed under "contains tree ⇒ contains all of it" (input prefetching, sandbox staging, Merkle tree construction).
The experimental phase should exercise both under local, sandboxed, and remote strategies before graduation.

# Implementation findings (v1 prototype)

A prototype (behind `--experimental_tree_artifact_selection`) implements picks as action inputs and on the declarative surfaces (runfiles, top-level build, BEP), and *file* matches (`match_file`, and `match_files`/`match_file` over regular files) as action inputs, resolved during input discovery.
Building it confirmed the declarative-surface claims (first bullet) and surfaced four constraints that revise claims above and scope the first release.

- **"Picks behave like ordinary derived files everywhere" held up — with one download-path fix.**
  A pick in `DefaultInfo.files`, in an executable rule's runfiles, and as a top-level output all work with no code change beyond Phase 1: `Artifact.key` routing plus completion-time `addToMap` parent-expansion carry the child through `CompletionFunction`, `SourceManifestAction` places it at its tree-relative runfiles path, and BEP emits it as an ordinary output `File` (not a directory).
  The one exception was **Build without the Bytes top-level download**: registering a tree child for download threw, because `RemoteOutputChecker`'s prefix trie forbids a path that is a prefix of another (a child's exec path sits under its tree's). Fixed with an exact-match side set for standalone tree-child outputs, so `bazel build //x:pick` fetches the picked child and only it.
  (aquery structured output is now implemented too — see the aquery finding below.)

- **Subtree-input routing had to be built; `pick_directory` now works, dynamic directory members remain deferred.**
  A directory pick or a directory-typed match member is represented as a subtree `SpecialArtifact`, and routing one as an action input initially failed: `ArtifactFunction` resolved a subtree's metadata to `null`, because the tree's generating action records a `TreeArtifactValue` only for the *root* tree.
  The gap turned out to be Skyframe-only — `ActionInputMap` and staging already understood subtree inputs (from `map_directory`) — and was closed by teaching `ArtifactFunction` to serve a subtree as a *derived sub-view*: the subtree's node depends on the root tree's own artifact node and filters/re-parents the children beneath the picked path.
  **`pick_directory` therefore works as specified** (consuming one stages exactly its subtree), including picks below picks and template-generated roots; a pick of a path with no files under it fails the consumer with the missing-path error (tree artifacts do not record empty directories, so "missing" and "empty" are indistinguishable — the error says so).
  Still deferred: **directory members of a *match* as action inputs** — those surface as discovered inputs at execution time, a different admission path than a declared input (`match_directory` results remain usable as *sources* and everywhere else).
  The materialisation escape hatch (copy action) covers that remaining case.

- **A pick can co-exist with its origin, but that required lifting a tree-artifact nesting ban in `ActionInputMap`.**
  A pick's exec path lies underneath its origin's, so a consumer may legitimately take both the origin (the whole tree, or an enclosing `pick_directory`) and the pick as inputs — a natural pattern, and one this proposal implicitly promises by treating picks as ordinary files.
  For `pick_file` this is free: the pick collapses to the very child the origin tree expands to (same exec path, root, and owner — hence `equals` — so they dedupe).
  For `pick_directory` it was not: the subtree and its enclosing tree are two *nested tree artifacts*, and `ActionInputMap`'s trie was built on the standing invariant that tree artifacts never nest (it stored a bare `TreeArtifactValue` at a terminal trie node, with no room for descendants) — adding both threw `ClassCastException`.
  Fixed by letting a trie node carry both a terminal tree value and nested children; the common non-nested case is untouched, and prefix lookups return the shallowest (superset) enclosing tree.
  Overlapping stagings then resolve harmlessly (colliding exec paths carry byte-identical content, last-writer-wins).
  This is the one place picks were *not* already "ordinary files everywhere"; it is now covered by unit tests on the data structure and integration tests on the co-existence and multi-layer-nesting patterns.

- **Source trees must be *declared inputs*, not merely scheduling dependencies, to be resolvable during discovery.**
  The proposal envisioned exposing source trees only as scheduling dependencies (built, metadata-available, unstaged).
  In practice the Skyframe-backed metadata provider's `getTreeMetadata` returns `null`, so tree metadata is available during input discovery only for trees in the action's input map.
  The prototype therefore declares source trees as ordinary inputs so discovery can read their metadata, then **prunes them in `discoverInputs`**, leaving only the resolved children in the staged input set and the action cache key.
  The observable end state (footprint proportional to use) matches the proposal; the mechanism is prune-after-declare rather than scheduling-dependency.

- **Reusing a resolved match inside `Args` (or as an `executable`) needed a provider-wrapping mechanism, not the sketched re-resolution.**
  Command-line expansion receives an `InputMetadataProvider` but no handle to the consuming action, and once the source trees are pruned their metadata is absent from that provider — so a match appearing in `Args` can neither re-resolve (no tree metadata) nor read the discovery result (no action handle) directly.
  The prototype resolves this without new expansion-API plumbing: the consuming action captures per-match resolution results during input discovery, and at spawn construction hands expansion an `InputMetadataProvider` *wrapper* that additionally serves those results (expansion discovers the capability via an `instanceof` check).
  With that in place `Args` (`add`, `add_all`, `add_joined`, including `map_each` over resolved files), `executable`, and `tools` all work as specified;
  a match referenced in `Args` without also being an input fails expansion with an actionable error (matching the `Args`-versus-`inputs` contract above), while `executable`/`tools` matches are registered as input matches automatically.
  Two consequences diverge slightly from the text above;
  - action keys digest the match's *spec identity* (sources, patterns, flags, filter name + module digest) in place of the expansion, so keys are computable before metadata exists — invalidation of the resolved set rides on the discovered-inputs cache path instead;
  - the executable-bit check on a `MatchedFile` executable is dropped: `FileArtifactValue` does not track an executable bit, and Bazel stages action outputs (including tree children) executable, so the check has nothing sound to read. A non-executable selected file fails at spawn time with the OS error.
  Matches in *depsets* passed to `add_all` are rejected at analysis time: depset fingerprinting is memoised per nested set, which cannot accommodate per-action resolution.

- **aquery structured output landed as designed.**
  `analysis_v2.proto` gained a `FileMatch` message (cardinality, source-tree artifact ids, include/exclude patterns, filter display label, flags), an `ActionGraphContainer.file_matches` table, and `Action.file_match_ids`; a consuming action lists the matches it draws on, deduped across actions by spec identity.
  Command lines keep placeholder *strings* in `Action.arguments` (there is no command-line-fragment table to hang structure off), and there is no separate executable pointer — an executable/tool match is registered as an input match, so it shows up in `file_match_ids` like the rest.
  Verified for `--output=proto` and `--output=textproto`; the human-readable `--output=text` renders a `FileMatches:` block of placeholders (code in place, not golden-tested).

# Open questions

- **Naming (resolved).**
  The dynamic family was originally `select_*`, which had two problems: `select` and `pick` are synonyms, so the names gave no signal about the tiers sitting on opposite sides of the analysis/execution boundary; and `select` collides with configurable-attribute `select()`, an unrelated concept that appears constantly in the same `.bzl` files.
  It is now `match_*` (`match_file`/`match_directory`/`match_files`), returning `MatchedFile`/`MatchedFiles` — a verb that names the actual interface (glob patterns plus a `filter` predicate) and is semantically distant from `pick` (exact, static).
  `pick_file`/`pick_directory` still deliberately echo `declare_file`/`declare_directory`.
  `glob_*` and `find_*` were the other candidates; `glob_*` risked implying eager loading-time behaviour like BUILD-file `glob()`, and `match_*` reads most naturally across the singular (`match_file`) and set (`match_files`) forms.
  The umbrella feature name and flag stay "tree artifact selection" — "selection" as the *area* (picking or matching parts of a tree) is fine; the problem was only the dynamic-tier *verb*.
- **Matches in depsets.**
  Forwarding via plain provider fields covers the known use cases, but transitive aggregation (a dependency chain each contributing matches) would want depset participation.
  Admitting an opaque non-`File` into depsets that eventually feed `inputs` widens the blast radius considerably; deferred until demanded by evidence.
- **Matches in runfiles.**
  Runfiles trees are built late enough that filtered runfiles are implementable, and "runtime data subset of an extracted archive" is a plausible ask.
  Deferred; the copy-action materialisation route covers it meanwhile.
- **Eager validation option.**
  Should a rule be able to request that picks be validated when the tree is built (via the `_validation` output group) rather than when first consumed?
  Cheap to add later; omitted for now to keep the laziness story uniform.
- **`filter` and persisted state.**
  The callback's identity must survive server restarts for memoisation and placeholder rendering (`bzl file % function name`, as `map_directory` requires top-level functions for).
  Whether the *body* should contribute to any digest, or only its observable output (the position taken above, matching `map_each`), deserves review scrutiny.

# Alternatives Considered

## Analysis-time expansion

Let a rule ask for a tree artifact's contents during analysis, restarting analysis when execution produces the metadata (the shape sketched in various "peek at outputs" discussions).
Rejected: it inverts phase ordering, makes analysis results depend on execution (breaking `cquery`'s contract far more deeply than an opaque value does), serialises the build around metadata round trips, and is incompatible with analysis caching across configurations.
The entire design space of this proposal exists to avoid this.

## Standardising `DirectoryPathInfo` natively

Bless the (tree, relative path) pair as a builtin provider, as `bazel-lib` does in Starlark.
Rejected as insufficient: without engine support a provider is inert — every consumer still needs bespoke handling, obtaining a real `File` still costs a copy spawn, there is no multi-file form, and no input pruning.
Picks are exactly this pair *with* engine support; the provider falls out as unnecessary once the pair is a `File`.

## `map_directory` as the only answer

Route all tree-content access through action templates.
Rejected: it conflates referencing with transforming.
Obtaining one file costs an action template plus a copy spawn per file, outputs must live inside the template's declared output directories (so the result is another tree, recursing the problem for single-file needs), and template outputs cannot be an `executable` or feed providers as individual files.
`map_directory` remains the right tool for its actual job — per-child action generation — and matches deliberately reuse its callback conventions.

## Matching as an action producing a filtered tree

Make `match_files` register a native action that links matched files into a fresh tree artifact.
Rejected as the *primitive*: the result is still opaque (no file handles, so no `executable`, no single-file `Args` placement), every consumer pays materialisation whether needed or not, and an action node per match bloats the graph.
It also already exists as composition: a match piped through the copy action yields precisely this, for the cases that want it.

## Requiring rulesets to declare archive contents

Push the problem to data: have rules consume package-manager lockfiles or side-channel listings and `declare_file` every extracted path (the `rules_js` approach).
Works when a trustworthy manifest exists, and nothing in this proposal stops it — analysis-time knowledge always beats deferral.
Rejected as the general answer: most archives carry no manifest, listings drift from actual contents (a build-breaking class of error this proposal's deferred validation reports precisely), and declaring tens of thousands of files per archive recreates the analysis-memory costs that tree artifacts exist to avoid.
