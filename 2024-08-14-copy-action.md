---
created: 2024-08-14
last updated: 2026-07-03
status: Draft
reviewers: []
title: Copy Action
authors:
  - Silic0nS0ldier
discussion thread: TBD
---

# Abstract

Copying files and directories is a common need that is underserved in Bazel. Every existing solution routes a byte-for-byte copy through a spawn, paying subprocess, sandboxing, caching, and (with remote execution) network costs that are disproportionate to the work — and rule authors routinely reach for anti-patterns like [`no-remote` + `no-cache` tags](https://github.com/bazelbuild/bazel-skylib/blob/5c071b5006bb9799981d04d74a28bdee2f000d4a/rules/private/copy_common.bzl#L43-L44) to claw the overhead back, which in turn forces downloads under `--remote_download_minimal`.

This proposal introduces a built-in `ctx.actions.copy`: a non-spawn, type-preserving action whose output is *defined* to be identical in content to its input. Because the output's digest equals the input's digest, the action needs no subprocess, no execution strategy, no remote execution, no cache uploads, and — under Build without the Bytes — no bytes at all: the output is recorded as metadata referencing a blob that already exists.

Goals:

* Simplify rule development (no toolchains, `is_windows` branching, or helper binaries for a copy).
* Improve performance of copy-heavy toolchains (e.g. `rules_js`, which accommodates NodeJS's relative import resolution by copying inputs to the bin directory by default).
* Eliminate — not merely reduce — remote execution and BES protocol chatter for copies.
* Remove the incentive for `no-remote`/`no-cache` workarounds that pessimise remote builds.

# Background

## Artifact type vs. filesystem type

The semantics below are defined at the **artifact** level, so the distinction matters and is worth stating up front. Bazel artifacts are typed: *file* artifacts (`declare_file`, and all source files), *tree* artifacts (`declare_directory`), and *unresolved symlink* artifacts (`declare_symlink`). The filesystem representation of an artifact may not match its type: a file or tree artifact can legitimately be *materialised* as a symlink (sandbox strategies, Build without the Bytes symlink materialisation, `--remote_download_symlink_template`), and Bazel already models this — output metadata can record a digest together with a resolved path, independent of how the path is reached.

Consequently, everything in this proposal is specified against artifact type and artifact content (digest). Filesystem state is an implementation detail of materialisation.

## Existing API surfaces

Among the existing `ctx.actions.*` APIs, the closest analogue to a copy is `ctx.actions.symlink`. It is a poor substitute: symlinks are observable at runtime, and ecosystems attach meaning to them — NodeJS import resolution changes across symlinks, and package managers like [pnpm](https://pnpm.io/) (and rulesets like [rules_js](https://github.com/aspect-build/rules_js)) depend on those behavioural differences. A symlink is not a copy.

A true copy today requires a spawn, via `ctx.actions.run`, `ctx.actions.run_shell`, or `genrule`. Utility rulesets wrap these:

* Skylib
  * [`copy_directory`](https://github.com/bazelbuild/bazel-skylib/blob/5c071b5006bb9799981d04d74a28bdee2f000d4a/docs/copy_directory_doc.md)
  * [`copy_file`](https://github.com/bazelbuild/bazel-skylib/blob/5c071b5006bb9799981d04d74a28bdee2f000d4a/docs/copy_file_doc.md)
* Aspect's bazel-lib
  * [`copy_directory` + `copy_directory_action`](https://github.com/aspect-build/bazel-lib/blob/5d09fc1b8352ef276dd4dd873b3dc5b0f5482f19/docs/copy_directory.md)
  * [`copy_file` + `copy_file_action`](https://github.com/aspect-build/bazel-lib/blob/5d09fc1b8352ef276dd4dd873b3dc5b0f5482f19/docs/copy_file.md)
  * [`copy_to_bin` + `copy_file_to_bin_action` + `copy_files_to_bin_actions`](https://github.com/aspect-build/bazel-lib/blob/5d09fc1b8352ef276dd4dd873b3dc5b0f5482f19/docs/copy_to_bin.md)
  * [`copy_to_directory` + `copy_to_directory_bin_action`](https://github.com/aspect-build/bazel-lib/blob/5d09fc1b8352ef276dd4dd873b3dc5b0f5482f19/docs/copy_to_directory.md)

Because these are spawns, they inherit spawn costs:

* **Per-copy overhead disproportionate to the task.** Process launch (or worker round trip), sandbox setup, action-cache lookups, and with remote execution an `Execute` call, an `ActionResult`, and CAS round trips — all to produce bytes the build already has.
* **Merkle tree and memory growth.** Each copy spawn contributes its tool and inputs to merkle tree construction, increasing CPU and memory costs, particularly for remote builds.
* **Workarounds that punish remote builds.** Forcing copies local with `no-remote`/`no-cache` avoids the remote round trips but (a) breaks down where clients are forbidden from uploading `ActionResult`s (a common hardening posture, since client-supplied results are arbitrary), and (b) forces remote-produced inputs to be downloaded under `--remote_download_minimal`, defeating Build without the Bytes.
* **Cache-key fragility.** The copy is keyed on its implementation (tool digest, command line), so ruleset upgrades invalidate every copy in the graph despite the outputs being bit-identical.
* **Batching trades one problem for another.** Batching copies into one spawn amortises overhead but destroys incrementality: in most incremental builds only one input of the batch changed, yet the whole batch re-runs (and re-uploads).

## Machinery this proposal builds on

* **Build without the Bytes is the default.** Since Bazel 7, `--remote_download_outputs=toplevel` is the default for remote builds, and the supporting machinery has matured: remote output metadata carries expiry, leases can be extended for long builds, and action rewinding recovers from remote cache eviction by re-running producers. "Outputs that exist only as digests" is now a first-class, battle-tested state for an artifact — exactly the state a copy output should be able to start in.
* **[Bazel Remote Output Service](https://docs.google.com/document/d/1W6Tqq8cndssnDI0yzFSoj95oezRKcIhU57nwLHaN1qk/edit) (approved 2025)** pushes materialisation of `bazel-out` behind a service. A metadata-only copy action composes naturally with it (the service can materialise a copy as cheaply as it likes); a spawn-based copy does not.
* **The ruleset landscape cannot improve on its own.** Skylib and bazel-lib copy rules remain spawn-based with `no-remote`/`no-cache` defaults, and `rules_js` copies sources to the bin directory by default. The problem is structural: rulesets cannot do better than a spawn with the API Bazel offers them, which is the gap this proposal closes.

# Proposal

Introduce a new built-in copy action:

```starlark
ctx.actions.copy(
    input,        # File: a file artifact (source or generated), tree artifact,
                  # or unresolved symlink artifact
    output,       # File: declared via declare_file, declare_directory, or
                  # declare_symlink — matching the input's artifact type
    path = None,  # optional string: extract a single file from a tree artifact input
    progress_message = None,  # optional string, as on symlink()/run();
                              # defaults to "Copying %{input} to %{output}"
)
```

with the following defined behaviours:

1. **The copy preserves artifact type.** A file artifact copies to a file artifact, a tree artifact to a tree artifact, an unresolved symlink artifact to an unresolved symlink artifact. Mismatched input/output types are an analysis-time error (with one carve-out: `path` extraction, which copies a *file child* of a tree — type is preserved for the entity being copied).
2. **Output content is identical to input content.** For file and tree artifacts, byte-identical: the output's digest (and for trees, the digest of every child) equals the input's. For unresolved symlink artifacts, the tracked content *is* the target string, and the copy reproduces it verbatim. Only the name differs. File permissions — notably the executable bit — are preserved from the input, so no `is_executable` parameter is needed (unlike `write()` and `symlink()`, which create content that has no permissions to inherit).
3. **Semantics are defined at the artifact level; materialisation is invisible.** The result is a pure function of the input artifact's type and content. A file or tree artifact that happens to be materialised as a symlink on the local filesystem — a sandboxed action's input, a Build-without-the-Bytes symlink materialisation — is still a file or tree artifact: the copy follows the incidental symlink and produces content, the same observable result as if the input had been materialised as a regular file. Symlink→symlink semantics apply only to artifacts that are symlinks *by type* (`declare_symlink`), never by materialisation. Execution behaviour thus never depends on incidental filesystem state, preserving determinism.
4. **The action is not a spawn.** Like symlink actions, it has no execution strategy, no execution platform, no sandbox, and never executes remotely. Bazel performs the work in-process.
5. **Realisation of the output is deferred where possible.** When the input's content is remote-backed under Build without the Bytes, the copy completes as a metadata-only operation and the output is materialised on demand, exactly like any other remote-backed output.

## Supported input/output combinations

| Input | Output | `path` | Semantics | Phase |
|---|---|---|---|---|
| file artifact (source or generated) | `declare_file` | — | copy the file | 1 |
| tree artifact | `declare_directory` | — | copy the tree, child-for-child | 2 |
| unresolved symlink artifact | `declare_symlink` | — | new symlink with the identical target string | 2 |
| tree artifact | `declare_file` | required | copy the file child at `path` (relative to the tree root) | 3 |

`path` must be a non-empty relative path that does not escape the tree root (no leading `/`, no `..` after normalisation) — anything else is an analysis-time error. A `path` that does not name a regular file in the tree when the action executes — missing, or a directory — is an execution error, consistent with tree contents being unknowable at analysis time.

Symlink→symlink is trivial to specify and implement — the tracked metadata of an unresolved symlink artifact is its target string, and materialisation is a single `symlink(2)` call, precisely what the existing unresolved-symlink action already does — but demand for it is expected to be low, hence phase 2 rather than 1.

Everything else is an **analysis-time error**, in particular:

* **Type mismatches.** File↔tree, file↔symlink, tree↔symlink (in either direction, `path` extraction excepted). Where a rule genuinely wants to convert — e.g. produce a regular file with the content an unresolved symlink *points at* — that is a different operation with different tracking requirements (Bazel does not track what lies behind an unresolved symlink), not a copy.
* **Source directories.** They remain unsound as inputs in general (Bazel does not track their contents), and this proposal does not want to extend that unsoundness into a new API. Skylib's `copy_directory` support for source directories keeps its spawn-based implementation; see Open questions.
* **File input with directory output** (composition). Assembling directories from files is a real need (`copy_to_directory`) but a materially different API; see Open questions.

Clarifications for specific cases:

* **Inputs produced by `ctx.actions.symlink`.** `symlink(output, target_file = ...)` produces a *file* (or tree) artifact whose content Bazel tracks — so it is an ordinary phase‑1/2 input, and the copy yields the content. `symlink(output, target_path = ...)` produces an unresolved symlink artifact, and the copy yields a symlink with the same target string.
* **Symlinks inside tree artifacts.** Bazel's tree traversal already dereferences symlinks when constructing a tree artifact's metadata: children are reported as the regular files or directories they resolve to, and dangling symlinks are an error. Tree children are therefore logically regular files, and both tree copies and `path` extraction are well-defined without new symlink policy. Copying a materialised tree applies the same rule: symlinks encountered in the tree are resolved and their content copied, and a symlink that fails to resolve is an execution error — the same condition tree metadata collection already rejects.
* **Source files materialised as symlinks.** Source artifacts are file-type artifacts regardless of filesystem representation; behaviour 2 applies.

The combinations are specified as separate, explicit cases rather than one polymorphic operation, and each phase is independently shippable — file→file alone already carries most of the value.

## Execution semantics

The action's cache key is derived from the input's tracked content (its digest, or for an unresolved symlink its target string) and the output's path, so ordinary incremental machinery applies: a copy re-executes exactly when its input's content changes. It is deliberately *not* keyed on any implementation detail (no tool digest, no command line), fixing the cache-key fragility of spawn-based copies.

Execution proceeds by propagating metadata, not collecting it:

* **Local input** (bytes present in the output tree or source tree): Bazel copies the file(s) in-process — no subprocess — and records the output's metadata as a copy of the input's. Copies run on Skyframe's executors and do not occupy local execution slots, so heavyweight spawns are never queued behind them.
* **Remote-backed input** (Build without the Bytes): no bytes move. The output's metadata is the input's metadata — same digest, same size, same remote expiry — under the output's own path. Materialisation is deferred until demanded (requested as a top-level output, consumed by a locally-executing action, needed by `bazel run`). Since the CAS is content-addressed, the output *is* the input's blob; there is nothing to upload and nothing to download.
* **Eviction recovery**: because the output references the same digest as the input, existing lease-extension and TTL tracking cover it, and if the blob is evicted from the remote cache the existing rewinding machinery applies — the copy declares itself input-propagating (as symlink actions do), so rewinding attributes the loss through the copy, transitively through chains of copies, to the action that originally produced the bytes and re-runs it.
* **Unresolved symlink input**: the tracked metadata is the target string, so the copy is always metadata-only; materialisation (when demanded) creates the symlink directly. No digests, blobs, or caches are involved at any point, mirroring the existing unresolved-symlink action.

The action's mnemonic is `Copy`. Introspection rides the ordinary action-graph machinery: `bazel aquery 'mnemonic("Copy", ...)'` reports the input and output, and an extracting copy additionally reports its `path` (as `CopyPath:` in the text format).

### Materialisation strategy

When bytes must exist at the output path, the default is a **real copy**. Copy-on-write clones are permitted as a transparent optimisation: a clone creates a new inode with independent content, so it is semantically indistinguishable from a copy. No dedicated reflink plumbing or feature detection is required to get this in practice — the platform file-copy APIs Bazel already uses (`java.nio.file.Files.copy`) route through `copy_file_range` on Linux and `clonefile` on macOS, which perform CoW clones on supporting filesystems and fall back to plain copies elsewhere. Dedicated `FICLONE`/ReFS-block-clone plumbing remains an option if the default path proves insufficient, but is not needed for correctness or for the common cases.

**Hardlinks are explicitly not used.** They share an inode, which has two failure modes: mutation through one path is visible through all paths (surprising when output tree checking is off), and macOS Gatekeeper [kills processes whose executable shares an inode with a previously-quarantined path](https://developer.apple.com/forums/thread/663456). An opt-in hardlink mode could be added later if a use case demands it; the principle is that the default is an actual copy, and cheaper modes are explicit opt-ins — CoW clones excepted, since they are observably equivalent.

### Remote execution and BES traffic

**A copy action generates zero per-copy remote and BES traffic.**

* No `Execute` call: the action is not a spawn.
* No `ActionResult` lookup or upload: there is no remote action to key one on.
* No CAS uploads: byte-identity guarantees the output's blob exists wherever the input's does. Downstream actions reference the digest in their merkle trees as usual.
* No additional BES events: non-spawn actions do not publish per-action events by default, so 10k copies add nothing to BEP volume or event-stream backpressure.

Skipping the `ActionResult` may look like it forfeits value — output lifetime tracking, and sparing other clients the work of a large directory copy. It does not:

* An `ActionResult` maps an action key to output digests. For a copy, the output digests are computable *locally, from input metadata alone*; a remote round trip to fetch or store that mapping is pure overhead. Other Bazel clients "replay" the copy as a metadata-only in-process step — cheaper than a cache hit.
* For directory copies, no new CAS objects are needed either: REAPI `Tree`/`Directory` messages do not embed the root directory's own name, so the output tree's digest is identical to the input tree's, and the object already exists in the CAS.
* Output lifetime is tracked through the digests the build references (lease extension, TTL), which is indifferent to whether the reference arrived via the original producer or via a copy.

### Measured performance

A prototype implementation was benchmarked against the status quo (a per-file spawn running `cp`, mirroring skylib/bazel-lib) on identical output graphs, cold builds, median of three trials, on a 16-core Linux host with a btrfs (copy-on-write capable) output tree. Both mechanisms produced byte-identical outputs.

| Scenario | Metric | Spawn (status quo) | `actions.copy` | Improvement |
|---|---|---|---|---|
| 8000 × 1 KiB files, local | wall | 11.9 s | 4.4 s | **2.7× faster** |
| | CPU | 110 s | 34 s | **3.2× less** |
| | disk written | 94 MB | 18 MB | **5.2× less** |
| | retained heap | 46 MB | 40 MB | 1.15× less |
| 200 × 4 MiB files, local | execution CPU | 6.1 s | 0.4 s | **15× less** |
| | disk written | 802 MB | 0.6 MB | **>1000× less** (reflink) |
| 60 tree artifacts (100 files each) | wall | 4.2 s | 3.9 s | 1.08× faster |
| | peak RSS | 850 MB | 651 MB | 1.3× less |
| | output storage | 1.9 MB | 0.6 MB | **3.3× less** (reflink) |
| 8000 × 1 KiB, Build without the Bytes | wall | 10.9 s | 4.7 s | **2.3× faster** |
| | disk written | 86 MB | 18 MB | **4.8× less** |

Two implementation notes fall out of the benchmark:

* **Output metadata is injected, not recomputed.** A naive implementation lets Bazel read back and re-hash every output to compute its digest — but a copy already knows the output digest equals the input's. Injecting the output metadata (digest from the input, content proxy from a single stat of the output; the tree case injects per-child metadata analogously) eliminates that re-hash. This is what collapses large-file execution CPU by ~8× on top of the no-spawn saving, and it mirrors the metadata propagation that `SymlinkAction` already performs.
* **Copy-on-write is automatic where available.** Because the physical fallback routes through the platform file-copy APIs (`copy_file_range`/`clonefile`), copies on a CoW filesystem write essentially no new blocks — the >1000× drop in bytes written for large files, and the ~3× smaller on-disk footprint for trees.

The per-action *retained* footprint is also smaller (a `CopyAction` carries no command line, environment, or tool inputs — ~32 bytes of core state versus a spawn action's command-line objects), though at these scales the difference is dominated by shared artifact state. Per-action BEP event volume, when all actions are published, is ~14% smaller for copies (their events carry no command line); the larger remote-protocol saving (no `Execute`/`ActionResult`/CAS round trips) is structural rather than a matter of degree.

### Comparison against batched copy spawns

Because a per-file spawn is so expensive, rulesets commonly *batch* — one spawn copies many files (e.g. bazel-lib's `copy_to_directory`) — trading cache granularity for amortised overhead. The relevant comparison is therefore against both a per-file spawn and a single batched spawn, across cache states. Same 8000 × 1 KiB workload; the Bazel server is already running before every measured build (execution isolated from JVM/startup cost); median of 5 runs, `[min–max]` in brackets.

| Cache state | metric | per-file copy action | batched spawn | per-file spawn |
|---|---|---|---|---|
| **Cold** (outputs wiped) | wall | **1.11 s** `[1.01–1.62]` | 2.03 s `[1.96–2.36]` | 8.33 s `[8.31–8.86]` |
| | bytes written | **12.5 MB** | 38.3 MB | 88 MB |
| | total CPU | 6.6 s | **4.1 s** | 74 s |
| **Warm** (no change) | wall | 0.50 s | 0.49 s | 0.47 s |
| | copies re-run | 0 | 0 | 0 |
| **Partial** (1 input changed) | wall | **0.48 s** | 1.72 s | 0.50 s |
| | bytes written | **536 KB** | 34.2 MB | 552 KB |
| | actions re-run | 1 copy | whole batch | 1 spawn |
| **Partial** (1% changed) | wall | **0.53 s** | 1.75 s | 0.60 s |
| | bytes written | **1.1 MB** | 34.2 MB | 1.8 MB |
| | actions re-run | 80 copies | whole batch | 80 spawns |

Reading the table by regime:

* **Cold**: the copy action is fastest on wall time — it parallelises across cores, whereas the batched spawn is a single serial process — and writes the least (reflink). The batched spawn's *one* advantage anywhere is ~1.6× lower cold total CPU (one action node versus 8000), the intrinsic cost of fine granularity.
* **Warm**: all three are a wash — every mechanism is fully cached and nothing re-runs. The copy action carries no penalty for having 8000 separate cache entries.
* **Partial**: the decisive regime. A one-file edit re-runs exactly one copy (536 KB written) but re-runs the *entire* batched spawn (34.2 MB, ~3.6× the wall time) — and the batch pays that same full cost whether 1 or 80 files changed. The per-file spawn is equally granular but, as the cold row shows, ruinous to build from scratch.

The copy action is the only mechanism that is both granular (partial rebuilds scale with what changed) and cheap cold — today rulesets must give up one to get the other. The same granularity argument extends to a shared disk/remote action cache: a batched spawn is one coarse entry invalidated by any change, while per-file copies are independently cacheable — and cheap enough to simply re-run (metadata-only under Build without the Bytes) rather than depend on the cache at all.

## Implementation challenges

The implementation cost concentrates in three places, and accepting this proposal means accepting these work items:

1. **Deferred materialisation currently applies only to outputs that already exist remotely** because remote execution or a remote cache hit recorded them. A copy action needs to place an output into that state by *adoption* — taking its input's (possibly remote-backed) metadata as its own — without any remote system having seen the action.
2. **Output metadata collection is architecturally "stat the filesystem".** There is no existing way for an action to declare "my output's metadata is my input's metadata". The metadata-injection paths used by remote execution (file and tree injection) and the resolved-symlink metadata wrapper are precedents, but wiring a non-spawn, metadata-propagating action type into action execution, the action cache, and the various filesystem layers is the bulk of the work.
3. **Loss attribution through copies.** A copy's lost output is recovered by re-running the *input's* producer, potentially through a chain of copies. This rides an existing contract rather than new machinery: copy actions declare that they may insensitively propagate their inputs — the same declaration symlink actions make — which action rewinding interprets as "re-running this action alone cannot recreate the bytes", transitively rewinding the action's derived inputs and their producers. Digest identity provides a second, independent net: build-level eviction retries invalidate by lost digest, and a copy's output shares its input's digest, so the byte producer and every copy of a lost blob are covered by construction.

None of these are believed insurmountable, and they overlap substantially with machinery the Remote Output Service integration also wants; but they, not the Starlark surface, are where the complexity lives.

Crucially, all three are **severable from the API**. An initial implementation can execute copies against the filesystem and collect output metadata the ordinary way — the existing non-spawn actions (symlink, unresolved symlink, file write) provide all the structure this needs, and a working file/tree/symlink implementation with integration test coverage is a modest amount of code on top of them. Every observable semantic of this proposal holds in that form, including the zero remote/BES traffic guarantee (that property comes from not being a spawn, not from metadata propagation). What the interim form lacks is only the Build-without-the-Bytes behaviour: a remote-backed input must be materialised locally before it can be copied, which is precisely the download pressure the metadata-adoption work then removes. This stages delivery — Starlark surface and local semantics first, metadata propagation as a follow-up that changes performance, not behaviour.

## Example usage

### Skylib's [`copy_directory`](https://github.com/bazelbuild/bazel-skylib/blob/5c071b5006bb9799981d04d74a28bdee2f000d4a/rules/private/copy_directory_private.bzl)

```starlark
def _copy_directory_impl(ctx):
    # Initial scope: src must be a tree artifact (see Open questions for
    # source directories).
    dst = ctx.actions.declare_directory(ctx.attr.out)
    ctx.actions.copy(ctx.file.src, dst)

    files = depset(direct = [dst])
    runfiles = ctx.runfiles(files = [dst])

    return [DefaultInfo(files = files, runfiles = runfiles)]

copy_directory = rule(
    implementation = _copy_directory_impl,
    provides = [DefaultInfo],
    attrs = {
        "src": attr.label(mandatory = True, allow_single_file = True),
        # Cannot declare out as an output here, because there's no API for
        # declaring TreeArtifact outputs.
        "out": attr.string(mandatory = True),
    },
)
```

Compared to the canonical implementation:

* The implementation is significantly smaller.
* Both spawn branches (POSIX shell and `cmd.exe`) are replaced by one `copy` call.
* The `is_windows` attribute and the macro that exists solely to set it are gone.
* The `no-remote`/`no-cache` execution-requirement workaround is gone, along with its Build-without-the-Bytes download penalty.

### Aspect bazel-lib's [`copy_to_bin`](https://github.com/bazel-contrib/bazel-lib/blob/5d09fc1b8352ef276dd4dd873b3dc5b0f5482f19/lib/private/copy_file.bzl)

```starlark
def copy_file_to_bin_action(ctx, file):
    if not file.is_source:
        return file
    if ctx.label.workspace_name != file.owner.workspace_name:
        fail(_file_in_external_repo_error_msg(file))
    if ctx.label.package != file.owner.package:
        fail(_file_in_different_package_error_msg(file, ctx.label))

    dst = ctx.actions.declare_file(file.basename, sibling = file)
    ctx.actions.copy(file, dst)
    return dst

def copy_files_to_bin_actions(ctx, files):
    return [copy_file_to_bin_action(ctx, file) for file in files]

def _copy_to_bin_impl(ctx):
    files = copy_files_to_bin_actions(ctx, ctx.files.srcs)
    return DefaultInfo(
        files = depset(files),
        runfiles = ctx.runfiles(files = files),
    )

copy_to_bin = rule(
    implementation = _copy_to_bin_impl,
    provides = [DefaultInfo],
    attrs = {
        "srcs": attr.label_list(mandatory = True, allow_files = True),
    },
)
```

Compared to the canonical implementation:

* The validation logic is unchanged; the copy mechanics shrink to one call.
* No toolchain resolution is necessary.
* For `rules_js`-scale usage (every source file of every npm package copied to bin), the per-file spawn overhead — the dominant cost — disappears entirely, and under remote execution so does every associated `Execute`/`ActionResult`/CAS round trip.

## Feature detection

Per [Bazel feature detection in Starlark](https://docs.google.com/document/d/1HJf3gMYIrzmTRqbD4nWXH2eJRHXjLrOU0mmIeZplUzY/edit#), rulesets can adopt the API without dropping support for older Bazel versions:

```starlark
if hasattr(ctx.actions, "copy"):
    ctx.actions.copy(src, dst)
else:
    _legacy_spawn_copy(ctx, src, dst)
```

This matters in practice: skylib and bazel-lib support multiple Bazel major versions, so a built-in that only helps the newest release would otherwise wait years for adoption.

# Backward-compatibility

All changes are additive: a new member on `ctx.actions`, a new non-spawn action type, and new aquery/action-graph output for it. No existing rule, flag, or output changes behaviour. The API ships behind `--experimental_copy_action` until the semantics — in particular the deferred-materialisation behaviours — have soaked; while the flag is unset, `ctx.actions.copy` is absent entirely, which is also what the `hasattr` feature-detection idiom above keys off.

One ecosystem-level caution rather than a compatibility break: rulesets replacing spawn-based copies with `ctx.actions.copy` will invalidate their users' action caches once (the copy actions are keyed differently). This is a one-time cost identical to any ruleset implementation change today — and the new keying removes that class of invalidation going forward.

# Alternatives

## Status quo: spawn-based copy rules

Rejected for the structural reasons in Background: rulesets cannot express "this output is that input, renamed" through a spawn, so every mitigation (local-only tags, batching, workers) trades one cost for another.

## `ctx.actions.symlink`

Not a copy. Symlinks are runtime-observable and ecosystems (NodeJS resolution, pnpm layouts) assign them meaning; substituting symlinks where copies are required changes behaviour.

## Hardlink-based outputs

Rejected as a default materialisation: shared inodes leak mutations across paths and trip macOS Gatekeeper for executables (see Materialisation strategy). Possible later as explicit opt-in. CoW clones capture most of the benefit without the semantic hazards and are permitted transparently.

## Server-side short-circuiting of copy spawns

A remote execution service can recognise known copy actions and synthesise the `ActionResult` without scheduling execution — BuildBuddy has explored this for the `rules_js`/`rules_oci` workloads. This helps deployed fleets today, but as a general solution it is inverted: it requires each RBE implementation to maintain heuristics over command lines it does not control, still costs the client an `Execute` round trip and an `ActionResult` per copy, and does nothing for local builds, disk-cache builds, or organisations whose remotes forbid such synthesis. A built-in action removes the traffic rather than optimising it, and benefits every execution mode. The two are complementary during the transition window (old Bazel versions, unmigrated rulesets).

## Persistent worker / batched copy spawns

Workers amortise process launch but keep every other spawn cost (cache round trips, merkle trees, BES events) and add worker lifecycle complexity. Batching amortises overhead at the cost of incrementality — a batch re-runs (and re-uploads) when any one input changes. Both are optimisations of the wrong primitive.

# Open questions

1. **Source directories.** Skylib's `copy_directory` accepts source directories, which Bazel still does not soundly track as inputs. This proposal excludes them rather than extending the unsoundness into a new API — but that leaves the most-copied thing in some repositories (vendored `node_modules`-style trees) on the spawn path. If source directories ever become sound (tracked) inputs, tree-artifact copy semantics extend to them directly.
2. **Directory composition.** `copy_to_directory`-style assembly (N files/trees → one tree artifact, with path remapping) is the other high-volume copy workload. It likely wants a separate, richer API — one accepting a list of files/trees, with answers about merging, conflicts, and exclusions — rather than overloading `copy`. Deferred.
3. **Phasing of `path` extraction.** Extracting one file from a tree artifact (phase 3) is the least-demanded combination and interacts with the most machinery (tree metadata lookup at execution time). It could ship later, or be dropped if `File.tree_relative_path`-style consumption proves sufficient.
