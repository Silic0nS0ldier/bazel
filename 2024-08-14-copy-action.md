---
created: 2024-08-14
last updated: 2026-07-15
status: Draft
reviewers: []
title: Copy Action
authors:
  - Silic0nS0ldier
---

# Abstract

Copying files and directories is a common need that is underserved in Bazel. This proposal seeks to make this a builtin capability to improve performance and simplify rule development.

Every existing solution routes a byte-for-byte copy through a spawn, paying subprocess, sandboxing, caching, and (with remote execution) network costs that are disproportionate to the work — and rule authors routinely reach for anti-patterns like [`no-remote` + `no-cache` tags](https://github.com/bazelbuild/bazel-skylib/blob/5c071b5006bb9799981d04d74a28bdee2f000d4a/rules/private/copy_common.bzl#L43-L44) to claw the overhead back, which in turn forces downloads under `--remote_download_minimal`.

This proposal introduces a built-in `ctx.actions.copy`: a non-spawn, type-preserving action whose output is *defined* to be identical in content to its input. Because the output's digest equals the input's digest, the action needs no subprocess, no execution strategy, no remote execution, no cache uploads, and — under Build without the Bytes — no bytes at all: the output is recorded as metadata referencing a blob that already exists.

Goals:

* Simplify rule development (no toolchains, `is_windows` branching, or helper binaries for a copy).
* Improve performance of copy-heavy toolchains (e.g. `rules_js`, which accommodates NodeJS's relative import resolution by copying inputs to the bin directory by default).
* Reduce remote execution and BES protocol chatter for copies.
* Remove the incentive for `no-remote`/`no-cache` workarounds that pessimise remote builds.

# Background

## Status Quo

The following actions (`ctx.actions.*`) already exist;
- `args` - An abstraction for memory-efficient argument management. May produce a file.
- `declare_directory` - Declares a directory output.
- `declare_file` - Declares a file output.
- `declare_symlink` - Declares a symlink output.
- `do_nothing` - Does nothing, only serving as an insertion point for the deprecated 'extra actions' feature.
- `expand_template` - Creates a file using a template.
- `map_directory` - An abstraction for creating actions (via `template_ctx`) based on the files within input director(y/ies).
  - `args` - Same as `ctx.actions.args`.
  - `declare_file` - Same as `ctx.actions.declare_file`.
  - `declare_subdirectory` - Declares a directory output within one of `map_directory`'s outputs.
  - `run` - Same as `ctx.actions.run`.
- `run` and `run_shell` - Runs an executable/shell script. May produce file(s), director(y/ies) and/or symlink(s).
- `symlink` - Creates a symlink.
- `template_dict` - Abstraction for `expand_template` that allows deferring evaluation of values.
- `write` - Creates a file.

Among these actions, the closest builtin analogue to copying a file is using `ctx.actions.symlink`, which depending on the scenario can lead to different behavior at runtime (e.g. NodeJS import resolution is affected, and package managers like [pnpm](https://pnpm.io/) plus integrations like [Rules JS](https://github.com/aspect-build/rules_js) rely on the observable behavioral differences). This makes it a poor subsitutite.

A true copy today requires a spawn, via `ctx.actions.run`, `ctx.actions.run_shell`, or `genrule`. Utility rulesets wrap these:

- [`@bazel_skylib`](https://github.com/bazelbuild/bazel-skylib)
  - [`copy_directory`](https://github.com/bazelbuild/bazel-skylib/blob/bac104bc6065308a043489757f2a7ffd159c7fd1/docs/copy_directory_doc.md)
  - [`copy_file`](https://github.com/bazelbuild/bazel-skylib/blob/bac104bc6065308a043489757f2a7ffd159c7fd1/docs/copy_file_doc.md)
- [`bazel_lib`](https://github.com/bazel-contrib/bazel-lib)
  - [`copy_directory` + `copy_directory_bin_action`](https://github.com/bazel-contrib/bazel-lib/blob/222a5bf32e8b6a546059cfff85fe01af7164e596/lib/copy_directory.bzl)
  - [`copy_file` + `copy_file_action`](https://github.com/bazel-contrib/bazel-lib/blob/222a5bf32e8b6a546059cfff85fe01af7164e596/lib/copy_file.bzl)
  - [`copy_to_bin` + `copy_file_to_bin_action` + `copy_files_to_bin_actions`](https://github.com/bazel-contrib/bazel-lib/blob/222a5bf32e8b6a546059cfff85fe01af7164e596/lib/copy_to_bin.bzl)
  - [`copy_to_directory` + `copy_to_directory_bin_action`](https://github.com/bazel-contrib/bazel-lib/blob/222a5bf32e8b6a546059cfff85fe01af7164e596/lib/copy_to_directory.bzl)

Because these are all spawns, they inherit spawn costs:

* **Per-copy overhead disproportionate to the task.** Process launch (or worker round trip), sandbox setup, action-cache lookups, and with remote execution an `Execute` call, an `ActionResult`, and CAS round trips — all to produce bytes the build already has.
* **Merkle tree and memory growth.** Each copy spawn contributes its tool and inputs to merkle tree construction, increasing CPU and memory costs.
* **Workarounds that punish remote builds.** Forcing copies local with `no-remote`/`no-cache` avoids remote round trips but (a) breaks down where clients are forbidden from uploading `ActionResult`s (a common hardening posture, since client-supplied results are arbitrary), and (b) forces remote-produced inputs to be downloaded under `--remote_download_minimal`, defeating Build without the Bytes.
* **Cache-key fragility.** The copy is keyed on its implementation (tool digest, command line), so ruleset upgrades invalidate every copy in the graph despite the outputs being bit-identical.
* **Batching trades one problem for another.** Batching copies into one spawn amortises overhead but destroys incrementality: in most incremental builds only one input of the batch changed, yet the whole batch re-runs (and re-uploads).

## Artifact type vs. filesystem type

Declared artifact types do not always map to the actual materialised filesystem types, at least one the surface level.
- Sandboxing
  - Under Bazel, the sandbox spawn strategy populates a directory with symlinks.
  - Under certain RBE services (e.g. EngFlow), sandboxing is implicit. No symlinks are necessary, although hardlinks may be used.
- Runfiles Directory
  - Under Bazel (Linux default), runfiles are represented as a tree of symlinks.
  - Under certain RBE services (e.g. EngFlow), runfiles content is materialised as hardlinks.

This proposal does not touch these materialisation strategies, just the resources (files, directories, symlinks) they refer to. e.g.
```
With ctx.actions.symlink
original.txt (file) -> copied.txt (symlink) -> __.runfiles/copied.txt (symlink)
                                   ^^^^^^^
With ctx.actions.copy
original.txt (file) -> copied.txt (file) -> __.runfiles/copied.txt (symlink)
                                   ^^^^
```

Another noteworthy callout is `--remote_download_symlink_template`. Any workloads which are problematic with this flag today (e.g. NodeJS canonicalising file paths before import resolution) will remain problematic with this proposal.

## Machinery this proposal builds on

* **Build without the Bytes is the default.** Since Bazel 7, `--remote_download_outputs=toplevel` is the default for remote builds, and the supporting machinery has matured: remote output metadata carries expiry, leases can be extended for long builds, and action rewinding recovers from remote cache eviction by re-running producers. This means trivial client-side actions (e.g. `write`, `symlink`) can be made to materialise lazily by default.
* **[Bazel Remote Output Service](https://docs.google.com/document/d/1W6Tqq8cndssnDI0yzFSoj95oezRKcIhU57nwLHaN1qk/edit) (approved 2025)** pushes materialisation of `bazel-out` behind a service. A metadata-only copy action composes naturally with it (the service can materialise a copy as cheaply as it likes).

# Proposal

Introduce a new built-in copy action:

```starlark
ctx.actions.copy(
    # File: a file, tree, unresolved symlink or source directory artifact
    input,
    # File: a declared file, tree or unresolved symlink artifact
    output,
    # string|None: Optional string specifying a single file or tree artifact to extract from input
    path = None,
    # string|None: Optional progress message string.
    # Defaults to "Copying %{input} to %{output}"
    progress_message = None,
)
```

with the following defined behaviours:

1. **The copy preserves artifact type.** A file artifact copies to a file artifact, a tree artifact to a tree artifact, an unresolved symlink artifact to an unresolved symlink artifact. Mismatched input/output types are an analysis-time error (with two carve-outs: `path` extraction, which copies a *child* of a tree, and source directories — see below).
2. **Output content is identical to input content.** For file and tree artifacts, byte-identical: the output's digest (and for trees, the digest of every child) equals the input's. For unresolved symlink artifacts, the tracked content *is* the target string, and the copy reproduces it verbatim. Only the name differs. File permissions — notably the executable bit — are preserved from the input, so no `is_executable` parameter is needed (unlike `write()` and `symlink()`, which create content that has no permissions to inherit).
3. **Semantics are defined at the artifact level; materialisation is invisible.** The result is a pure function of the input artifact's type and content. A file or tree artifact that happens to be materialised as a symlink on the local filesystem (a sandboxed action's input, a Build-without-the-Bytes symlink materialisation) is still a file or tree artifact: the copy follows the incidental symlink and produces content, the same observable result as if the input had been materialised as a regular file. Symlink→symlink semantics apply only to artifacts that are symlinks *by type* (`declare_symlink`), never by materialisation. Execution behaviour thus never depends on incidental filesystem state, preserving determinism.
4. **The action is not a spawn.** Like symlink actions, it has no execution strategy, no execution platform, no sandbox, and never executes remotely. Bazel performs the work in-process.
5. **Realisation of the output is deferred where possible.** When the input's content is remote-backed under Build without the Bytes, the copy completes as a metadata-only operation and the output is materialised on demand, exactly like any other remote-backed output.

## Supported input/output combinations

| Input | Output | `path` | Semantics |
|---|---|---|---|---|
| file artifact (source or generated) | `declare_file` | — | copy the file |
| tree artifact | `declare_directory` | — | recursively copy the tree |
| source directory | `declare_directory` | — | recursively copy the directory |
| unresolved symlink artifact | `declare_symlink` | — | new symlink with the identical target string |
| tree artifact or source directory | `declare_file` | required | copy the file at `path` (relative to the directory root) |
| tree artifact or source directory | `declare_directory` | required | recursively copy the sub-tree at `path` (relative to the directory root) |

`path` must be a non-empty relative path that does not escape the tree root (no leading `/`, no `..` after normalisation) — anything else is an analysis-time error. A `path` that does not name a regular file or sub-tree in the tree when the action executes (i.e. missing) is an execution error, consistent with tree contents being unknowable at analysis time.

**Source directories** are file-type artifacts, not tree artifacts: analysis performs no filesystem IO, so a source artifact that names a directory is indistinguishable from one that names a file until execution (`File.is_directory` is `False` for both — that is inherent to Bazel's analysis model, not something a copy action can change). The declared output type therefore states the caller's intent: a *source* artifact input is accepted wherever the table above says "source directory", and execution verifies that the input actually is a directory on disk — a source *file* copied to a `declare_directory` output (or a source *directory* copied to a `declare_file` output) fails the build then, consistent with `path` extraction's treatment of unknowable-at-analysis conditions. Generated non-tree artifacts are guaranteed regular files, so no such allowance exists for them.

Everything else is an **analysis-time error**, in particular:

* **Type mismatches.** File↔tree, file↔symlink, tree↔symlink (in either direction; `path` extraction and source directories excepted). Where a rule genuinely wants to convert (e.g. produce a regular file with the content an unresolved symlink *points at*) that is a different operation with different tracking requirements (Bazel does not track what lies behind an unresolved symlink), not a copy.
* **Generated file input with directory output** (composition). Assembling directories from files is a real need (e.g. `@bazel_lib`'s `copy_to_directory`) but a materially different API.

Clarifications for specific cases:

* **Inputs produced by `ctx.actions.symlink`.**
  - `symlink(output, target_file = ...)` produces a *file* (or tree) artifact whose content Bazel tracks, a the copy yields the content.
  - `symlink(output, target_path = ...)` produces an unresolved symlink artifact, and the copy yields a symlink with the same target string.
* **Symlinks inside tree artifacts.** Bazel's tree traversal already dereferences symlinks when constructing a tree artifact's metadata: children are reported as the regular files or directories they resolve to, and dangling symlinks are an error. Tree children are therefore logically regular files, and both tree copies and `path` extraction are well-defined without new symlink policy. Copying a materialised tree applies the same rule: symlinks encountered in the tree are resolved and their content copied, and a symlink that fails to resolve is an execution error — the same condition tree metadata collection already rejects.

## Execution semantics

The action's cache key is derived from the input's tracked content (its digest, or for an unresolved symlink its target string) and the output's path, so ordinary incremental machinery applies: a copy re-executes exactly when its input's content changes. It is deliberately *not* keyed on any implementation detail (no tool digest, no command line), fixing the cache-key fragility of spawn-based copies.

Execution proceeds by propagating metadata, not collecting it:

* **Local input** (bytes present in the output tree or source tree): Bazel copies the file(s) in-process and records the output's metadata as a copy of the input's. Copies run on Skyframe's executors and do not occupy local execution slots, so heavyweight spawns are never queued behind them.
* **Local input, output not materialised locally** (Build without the Bytes, e.g. copying a source file into the bin directory for `rules_js` when the copy will only be consumed remotely): the copy avoids the eager in-process write. The input's content is placed in the CAS once — a no-op if the blob is already present — and the output is recorded as a content-by-digest reference, so it occupies no local disk yet is a genuine remote-backed artifact that any consumer, local or remote, can obtain. This is gated on Bazel's own "should this output be downloaded?" decision, so an output that *will* be materialised (a top-level output, `--remote_download_outputs=all`, or a plain local build) still takes the direct in-process copy above rather than a wasteful upload-then-download. The content upload is, for now, *eager*: a source copy that is never consumed remotely still incurs one CAS upload. Deferring that upload until a consumer genuinely demands the bytes — so such copies incur no remote traffic at all — is a natural future refinement, but is not yet done because much of the surrounding input materialisation in this area is still implicitly eager (a lazily-uploaded output whose blob is absent is treated by the remote-input machinery as a lost input, which for a source has no producer to rewind).
* **Remote-backed input** (Build without the Bytes): no bytes move. The output's metadata is the input's metadata — same digest, same size, same remote expiry — under the output's own path. Materialisation is deferred until demanded (requested as a top-level output, consumed by a locally-executing action, needed by `bazel run`, etc). Since the CAS is content-addressed, the output *is* the input's blob; there is nothing to upload and nothing to download.
* **Eviction recovery**: because the output references the same digest as the input, existing lease-extension and TTL tracking cover it, and if the blob is evicted from the remote cache the existing rewinding machinery applies. The copy declares itself input-propagating (as symlink actions do), so rewinding attributes the loss through the copy, transitively through chains of copies, to the action that originally produced the bytes and re-runs it.
* **Unresolved symlink input**: the tracked metadata is the target string, so the copy is always metadata-only; materialisation (when demanded) creates the symlink directly. No digests, blobs, or caches are involved at any point, mirroring the existing unresolved-symlink action.
* **Source directory input**: Bazel's source-directory tracking (on by default in Bazel; `BAZEL_TRACK_SOURCE_DIRECTORIES`) computes an aggregate fingerprint over a recursive traversal of the directory, so the copy's cache key is sound: it re-executes exactly when any contained file is added, removed, or modified. The directory's tracked metadata is that single aggregate fingerprint — there are no per-child digests to propagate — so per-child digests are computed at execution time: in a plain local build the copied children are simply digested as ordinary outputs (the same cost a spawn-based copy pays), while under Build without the Bytes the "local input, output not materialised" path above applies per child — each contained file is hashed, its content placed in the CAS, and the output child recorded as content-by-digest, so the copied directory occupies no local disk, exactly like a spawn's remote outputs. The hashing is work a physical copy would pay anyway when digesting its outputs; the upload is the same eager, dedup-friendly upload as the single-file case (and shares its future-deferral caveat). When tracking is disabled the copy degrades to exactly the (unsound) caching that spawn-based copying of source directories has today.

The action's mnemonic is `Copy`. Introspection rides the ordinary action-graph machinery: `bazel aquery 'mnemonic("Copy", ...)'` reports the input and output, and an extracting copy additionally reports its `path` (as `CopyPath:` in the text format).

### Materialisation strategy

When bytes must exist at the output path, the default is a **real copy**. Copy-on-write clones are permitted as a transparent optimisation: a clone creates a new inode with independent content, so it is semantically indistinguishable from a copy. No dedicated reflink plumbing or feature detection is required to get this in practice, the platform file-copy APIs Bazel already uses (`java.nio.file.Files.copy`) route through `copy_file_range` on Linux and `clonefile` on macOS, which perform CoW clones on supporting filesystems and fall back to plain copies elsewhere. Dedicated `FICLONE`/ReFS-block-clone plumbing remains an option if the default path proves insufficient, but is not needed for correctness or for the common cases.

**Hardlinks are explicitly not used.** They share an inode, which has two failure modes: mutation through one path is visible through all paths (surprising when output tree checking is off), and macOS Gatekeeper [kills processes whose executable shares an inode with a previously-quarantined path](https://developer.apple.com/forums/thread/663456). An opt-in hardlink mode could be added later if a use case demands it; the principle is that the default is an actual copy, and cheaper modes are explicit opt-ins (CoW clones excepted, since they are observably equivalent).

### Remote execution and BES traffic

**A copy action is lighter on remote and BES traffic.**

* No `Execute` call: the action is not a spawn. Remote does not need to copy input bytes to executors as a result.
* No `ActionResult` lookup or upload: there is no remote action to key one on.
* (future optimisation) Upload of source files to CAS deferred until actually needed.
* Lighter BES events: Copy action events are lighter than an equivilant spawn.

Skipping the `ActionResult` may look like it forfeits value (output lifetime tracking, and sparing other clients the work of a large directory copy). It does not:

* An `ActionResult` maps an action key to output digests. For a copy, the output digests are computable *locally, from input metadata alone*; a remote round trip to fetch or store that mapping is pure overhead. Other Bazel clients "replay" the copy as a metadata-only in-process step — cheaper than a cache hit.
* For directory copies, no new CAS objects are needed either: REAPI `Tree`/`Directory` messages do not embed the root directory's own name, so the output tree's digest is identical to the input tree's, and the object already exists in the CAS.
* Output lifetime is tracked through the digests the build references (lease extension, TTL), which is indifferent to whether the reference arrived via the original producer or via a copy.

### Benchmarks

For this proposal to achieve it's goal, there needs to be a demonstratable improvement over the status quo. To that end a prototype was created and benchmarked across various scenarios on a 16-core Linux host with btrfs (copy-on-write capable).

Some notes for the results:
- All results are the average of 10 runs.
- Remote builds are run with `nativelink` (same machine) and `--remote_download_outputs=minimal`.
- Peak RSS (resident set size) is collected with polling, true peaks may be higher.
- AC and CAS reflect `--disk_cache` for local and `--remote_cache` for remote.
- OUT refers to disk space used for Bazel's output tree. Reflinks are specially handled to avoid double counting.
- BEP refers to total build event protocol events produced with `--build_event_publish_all_actions`, size is with JSON output.

**Per-Artifact Spawns**

| Content                            | Strategy | Cache    | Cache Misses | Wall   | CPU Time | Peak RSS   | AC        | CAS       | OUT       | BEP | BEP Size  |
|------------------------------------|----------|----------|--------------|--------|----------|------------|-----------|-----------|-----------|-----|-----------|
| 200 × 4 MiB source files           | local    | Cold     |          200 | 1.44 s | 7.96 s   | 1000.2 MiB | 800.0 KiB | 801.6 MiB | 800.0 MiB | 429 | 346.0 KiB |
|                                    |          | 10% miss |           20 | 196 ms | 862 ms   | 863.9 MiB  | 880.0 KiB | 881.6 MiB | 800.0 MiB |  69 | 162.6 KiB |
|                                    |          | 1 miss   |            1 | 110 ms | 149 ms   | 976.0 MiB  | 804.0 KiB | 805.6 MiB | 800.0 MiB |  31 | 143.6 KiB |
|                                    |          | Warm     |            0 | 79 ms  | 114 ms   | 1.1 GiB    | 800.0 KiB | 801.6 MiB | 800.0 MiB |  29 | 141.9 KiB |
|                                    | remote   | Cold     |          200 | 6.28 s | 7.02 s   | 1.3 GiB    | 800.0 KiB | 803.2 MiB | 0 B       | 429 | 338.7 KiB |
|                                    |          | 10% miss |           20 | 806 ms | 603 ms   | 1.3 GiB    | 892.0 KiB | 883.5 MiB | 0 B       |  69 | 158.8 KiB |
|                                    |          | 1 miss   |            1 | 257 ms | 139 ms   | 1.9 GiB    | 816.0 KiB | 807.2 MiB | 0 B       |  31 | 140.2 KiB |
|                                    |          | Warm     |            0 | 123 ms | 148 ms   | 1.5 GiB    | 812.0 KiB | 803.2 MiB | 0 B       |  29 | 138.2 KiB |
| 200 × 4 MiB generated files        | local    | Cold     |          200 | 1.94 s | 10.77 s  | 1.1 GiB    | 1.6 MiB   | 803.1 MiB | 800.0 MiB | 829 | 599.1 KiB |
|                                    |          | 10% miss |           20 | 344 ms | 949 ms   | 1001.3 MiB | 1.7 MiB   | 883.3 MiB | 800.0 MiB | 109 | 188.0 KiB |
|                                    |          | 1 miss   |            1 | 119 ms | 157 ms   | 1.1 GiB    | 1.6 MiB   | 807.1 MiB | 800.0 MiB |  33 | 145.1 KiB |
|                                    |          | Warm     |            0 | 80 ms  | 119 ms   | 1.3 GiB    | 1.6 MiB   | 803.1 MiB | 800.0 MiB |  29 | 141.9 KiB |
|                                    | remote   | Cold     |          200 | 8.28 s | 5.13 s   | 1.2 GiB    | 1.6 MiB   | 808.8 MiB | 0 B       | 829 | 588.9 KiB |
|                                    |          | 10% miss |           20 | 997 ms | 322 ms   | 938.3 MiB  | 1.7 MiB   | 889.5 MiB | 0 B       | 109 | 184.2 KiB |
|                                    |          | 1 miss   |            1 | 375 ms | 136 ms   | 1.0 GiB    | 1.6 MiB   | 812.8 MiB | 0 B       |  33 | 142.0 KiB |
|                                    |          | Warm     |            0 | 119 ms | 118 ms   | 1.1 GiB    | 1.6 MiB   | 808.8 MiB | 0 B       |  29 | 138.3 KiB |
| 10 source dirs, 20 × 4 MiB each    | local    | Cold     |           10 | 525 ms | 4.12 s   | 636.7 MiB  | 40.0 KiB  | 800.1 MiB | 800.0 MiB |  49 | 152.8 KiB |
|                                    |          | 10% miss |            1 | 171 ms | 444 ms   | 689.3 MiB  | 44.0 KiB  | 804.1 MiB | 800.0 MiB |  31 | 141.0 KiB |
|                                    |          | 1 miss   |            1 | 164 ms | 406 ms   | 721.7 MiB  | 44.0 KiB  | 804.1 MiB | 800.0 MiB |  31 | 141.0 KiB |
|                                    |          | Warm     |            0 | 68 ms  | 104 ms   | 781.7 MiB  | 40.0 KiB  | 800.1 MiB | 800.0 MiB |  29 | 139.3 KiB |
|                                    | remote   | Cold     |           10 | 1.40 s | 3.01 s   | 1.5 GiB    | 52.0 KiB  | 800.4 MiB | 0 B       |  49 | 149.1 KiB |
|                                    |          | 10% miss |            1 | 445 ms | 240 ms   | 1.4 GiB    | 56.0 KiB  | 804.5 MiB | 0 B       |  31 | 136.8 KiB |
|                                    |          | 1 miss   |            1 | 441 ms | 203 ms   | 1.5 GiB    | 56.0 KiB  | 804.5 MiB | 0 B       |  31 | 136.8 KiB |
|                                    |          | Warm     |            0 | 115 ms | 113 ms   | 1.9 GiB    | 52.0 KiB  | 800.4 MiB | 0 B       |  29 | 134.8 KiB |
| 10 generated dirs, 20 × 4 MiB each | local    | Cold     |           10 | 1.80 s | 8.33 s   | 813.6 MiB  | 80.0 KiB  | 800.2 MiB | 800.0 MiB |  69 | 165.5 KiB |
|                                    |          | 10% miss |            1 | 362 ms | 875 ms   | 732.0 MiB  | 88.0 KiB  | 880.2 MiB | 800.0 MiB |  33 | 142.6 KiB |
|                                    |          | 1 miss   |            1 | 299 ms | 799 ms   | 809.0 MiB  | 88.0 KiB  | 880.2 MiB | 800.0 MiB |  33 | 142.6 KiB |
|                                    |          | Warm     |            0 | 120 ms | 71 ms    | 850.6 MiB  | 80.0 KiB  | 800.2 MiB | 800.0 MiB |  29 | 139.5 KiB |
|                                    | remote   | Cold     |           10 | 3.03 s | 1.59 s   | 590.1 MiB  | 92.0 KiB  | 800.7 MiB | 0 B       |  69 | 161.7 KiB |
|                                    |          | 10% miss |            1 | 905 ms | 192 ms   | 793.3 MiB  | 88.0 KiB  | 880.8 MiB | 0 B       |  33 | 138.6 KiB |
|                                    |          | 1 miss   |            1 | 895 ms | 158 ms   | 951.1 MiB  | 88.0 KiB  | 880.8 MiB | 0 B       |  33 | 138.6 KiB |
|                                    |          | Warm     |            0 | 101 ms | 70 ms    | 1013.2 MiB | 80.0 KiB  | 800.7 MiB | 0 B       |  29 | 135.0 KiB |

**Batched Spawns**

| Content                            | Strategy | Cache    | Cache Misses | Wall   | CPU Time | Peak RSS   | AC        | CAS       | OUT       | BEP | BEP Size  |
|------------------------------------|----------|----------|--------------|--------|----------|------------|-----------|-----------|-----------|-----|-----------|
| 200 × 4 MiB source files           | local    | Cold     |            1 | 1.41 s | 4.93 s   | 775.9 MiB  | 4.0 KiB   | 800.0 MiB | 800.0 MiB |  31 | 146.1 KiB |
|                                    |          | 10% miss |            1 | 922 ms | 3.01 s   | 928.8 MiB  | 8.0 KiB   | 880.1 MiB | 800.0 MiB |  31 | 141.9 KiB |
|                                    |          | 1 miss   |            1 | 908 ms | 2.76 s   | 1.0 GiB    | 8.0 KiB   | 804.1 MiB | 800.0 MiB |  31 | 141.9 KiB |
|                                    |          | Warm     |            0 | 69 ms  | 102 ms   | 1.0 GiB    | 4.0 KiB   | 800.0 MiB | 800.0 MiB |  29 | 136.0 KiB |
|                                    | remote   | Cold     |            1 | 4.46 s | 3.82 s   | 1.8 GiB    | 4.0 KiB   | 800.1 MiB | 0 B       |  31 | 143.0 KiB |
|                                    |          | 10% miss |            1 | 2.00 s | 500 ms   | 2.0 GiB    | 8.0 KiB   | 880.2 MiB | 0 B       |  31 | 138.5 KiB |
|                                    |          | 1 miss   |            1 | 1.84 s | 252 ms   | 1.7 GiB    | 8.0 KiB   | 804.2 MiB | 0 B       |  31 | 138.4 KiB |
|                                    |          | Warm     |            0 | 117 ms | 131 ms   | 1.8 GiB    | 4.0 KiB   | 800.1 MiB | 0 B       |  29 | 132.4 KiB |
| 200 × 4 MiB generated files        | local    | Cold     |            1 | 1.68 s | 9.00 s   | 794.3 MiB  | 804.0 KiB | 801.6 MiB | 800.0 MiB | 431 | 399.0 KiB |
|                                    |          | 10% miss |            1 | 996 ms | 3.24 s   | 1023.9 MiB | 888.0 KiB | 881.7 MiB | 800.0 MiB |  71 | 170.9 KiB |
|                                    |          | 1 miss   |            1 | 915 ms | 2.77 s   | 1.2 GiB    | 812.0 KiB | 805.6 MiB | 800.0 MiB |  33 | 147.2 KiB |
|                                    |          | Warm     |            0 | 73 ms  | 115 ms   | 1.1 GiB    | 804.0 KiB | 801.6 MiB | 800.0 MiB |  29 | 136.1 KiB |
|                                    | remote   | Cold     |            1 | 6.79 s | 1.93 s   | 851.8 MiB  | 804.0 KiB | 804.1 MiB | 0 B       | 431 | 393.0 KiB |
|                                    |          | 10% miss |            1 | 2.31 s | 273 ms   | 984.5 MiB  | 888.0 KiB | 884.5 MiB | 0 B       |  71 | 167.5 KiB |
|                                    |          | 1 miss   |            1 | 1.98 s | 198 ms   | 1.1 GiB    | 812.0 KiB | 808.2 MiB | 0 B       |  33 | 144.2 KiB |
|                                    |          | Warm     |            0 | 117 ms | 115 ms   | 1.2 GiB    | 804.0 KiB | 804.1 MiB | 0 B       |  29 | 132.3 KiB |
| 10 source dirs, 20 × 4 MiB each    | local    | Cold     |            1 | 858 ms | 3.68 s   | 659.4 MiB  | 4.0 KiB   | 800.0 MiB | 800.0 MiB |  31 | 144.1 KiB |
|                                    |          | 10% miss |            1 | 759 ms | 2.93 s   | 742.8 MiB  | 8.0 KiB   | 804.1 MiB | 800.0 MiB |  31 | 140.0 KiB |
|                                    |          | 1 miss   |            1 | 779 ms | 2.92 s   | 888.5 MiB  | 8.0 KiB   | 804.1 MiB | 800.0 MiB |  31 | 140.0 KiB |
|                                    |          | Warm     |            0 | 69 ms  | 105 ms   | 789.4 MiB  | 4.0 KiB   | 800.0 MiB | 800.0 MiB |  29 | 137.7 KiB |
|                                    | remote   | Cold     |            1 | 2.03 s | 1.20 s   | 1.5 GiB    | 4.0 KiB   | 800.1 MiB | 0 B       |  31 | 140.3 KiB |
|                                    |          | 10% miss |            1 | 1.70 s | 246 ms   | 1.3 GiB    | 8.0 KiB   | 804.2 MiB | 0 B       |  31 | 135.7 KiB |
|                                    |          | 1 miss   |            1 | 1.70 s | 221 ms   | 1.6 GiB    | 8.0 KiB   | 804.2 MiB | 0 B       |  31 | 135.8 KiB |
|                                    |          | Warm     |            0 | 118 ms | 127 ms   | 1.4 GiB    | 4.0 KiB   | 800.1 MiB | 0 B       |  29 | 133.3 KiB |
| 10 generated dirs, 20 × 4 MiB each | local    | Cold     |            1 | 1.38 s | 7.43 s   | 696.0 MiB  | 44.0 KiB  | 800.1 MiB | 800.0 MiB |  51 | 156.8 KiB |
|                                    |          | 10% miss |            1 | 895 ms | 3.31 s   | 745.1 MiB  | 52.0 KiB  | 880.2 MiB | 800.0 MiB |  33 | 141.6 KiB |
|                                    |          | 1 miss   |            1 | 880 ms | 3.29 s   | 779.8 MiB  | 52.0 KiB  | 880.2 MiB | 800.0 MiB |  33 | 141.6 KiB |
|                                    |          | Warm     |            0 | 56 ms  | 67 ms    | 808.9 MiB  | 44.0 KiB  | 800.1 MiB | 800.0 MiB |  29 | 137.9 KiB |
|                                    | remote   | Cold     |            1 | 4.23 s | 655 ms   | 689.9 MiB  | 44.0 KiB  | 800.5 MiB | 0 B       |  51 | 153.1 KiB |
|                                    |          | 10% miss |            1 | 2.20 s | 242 ms   | 851.4 MiB  | 52.0 KiB  | 880.6 MiB | 0 B       |  33 | 137.7 KiB |
|                                    |          | 1 miss   |            1 | 2.20 s | 213 ms   | 906.0 MiB  | 52.0 KiB  | 880.6 MiB | 0 B       |  33 | 137.7 KiB |
|                                    |          | Warm     |            0 | 99 ms  | 73 ms    | 1.0 GiB    | 44.0 KiB  | 800.5 MiB | 0 B       |  29 | 133.4 KiB |

**Copy Action**

Values in brackets are the relative difference vs. per-artifact and batched spawns.

| Content                            | Strategy | Cache    | Cache Misses       | Wall                          | CPU Time                      | Peak RSS                               | AC                                   | CAS                                    | OUT                        | BEP                | BEP Size                              |
|------------------------------------|----------|----------|--------------------|-------------------------------|-------------------------------|----------------------------------------|--------------------------------------|----------------------------------------|----------------------------|--------------------|---------------------------------------|
| 200 × 4 MiB source files           | local    | Cold     | 200<br> (+0, +199) | 417 ms<br> (-1.02 s, -997 ms) | 3.17 s<br> (-4.79 s, -1.76 s) | 782.5 MiB<br> (-217.7 MiB, +6.6 MiB)   | 0 B<br> (-800.0 KiB, -4.0 KiB)       | 0 B<br> (-801.6 MiB, -800.0 MiB)       | 800.0 MiB<br> (+0 B, +0 B) | 429<br> (+0, +398) | 301.3 KiB<br> (-44.7 KiB, +155.3 KiB) |
|                                    |          | 10% miss | 20<br> (+0, +19)   | 120 ms<br> (-76 ms, -802 ms)  | 432 ms<br> (-430 ms, -2.58 s) | 955.7 MiB<br> (+91.8 MiB, +26.9 MiB)   | 0 B<br> (-880.0 KiB, -8.0 KiB)       | 0 B<br> (-881.6 MiB, -880.1 MiB)       | 800.0 MiB<br> (+0 B, +0 B) | 69<br> (+0, +38)   | 157.5 KiB<br> (-5.1 KiB, +15.6 KiB)   |
|                                    |          | 1 miss   | 1                  | 93 ms<br> (-17 ms, -815 ms)   | 152 ms<br> (+3 ms, -2.61 s)   | 1002.7 MiB<br> (+26.7 MiB, -28.2 MiB)  | 0 B<br> (-804.0 KiB, -8.0 KiB)       | 0 B<br> (-805.6 MiB, -804.1 MiB)       | 800.0 MiB<br> (+0 B, +0 B) | 31                 | 142.7 KiB<br> (-958 B, +831 B)        |
|                                    |          | Warm     | 0                  | 81 ms<br> (+2 ms, +11 ms)     | 127 ms<br> (+13 ms, +25 ms)   | 1.0 GiB<br> (-119.3 MiB, -28.0 MiB)    | 0 B<br> (-800.0 KiB, -4.0 KiB)       | 0 B<br> (-801.6 MiB, -800.0 MiB)       | 800.0 MiB<br> (+0 B, +0 B) | 29                 | 141.3 KiB<br> (-612 B, +5.3 KiB)      |
|                                    | remote   | Cold     | 200<br> (+0, +199) | 1.52 s<br> (-4.76 s, -2.94 s) | 3.67 s<br> (-3.35 s, -149 ms) | 1.8 GiB<br> (+464.2 MiB, +26.2 MiB)    | 0 B<br> (-800.0 KiB, -4.0 KiB)       | 800.0 MiB<br> (-3.2 MiB, -76.0 KiB)    | 0 B<br> (+0 B, +0 B)       | 429<br> (+0, +398) | 293.7 KiB<br> (-45.0 KiB, +150.7 KiB) |
|                                    |          | 10% miss | 20<br> (+0, +19)   | 274 ms<br> (-533 ms, -1.73 s) | 474 ms<br> (-129 ms, -26 ms)  | 1.9 GiB<br> (+635.4 MiB, -119.3 MiB)   | 0 B<br> (-892.0 KiB, -8.0 KiB)       | 880.0 MiB<br> (-3.4 MiB, -148.0 KiB)   | 0 B<br> (+0 B, +0 B)       | 69<br> (+0, +38)   | 153.5 KiB<br> (-5.3 KiB, +15.0 KiB)   |
|                                    |          | 1 miss   | 1                  | 175 ms<br> (-82 ms, -1.66 s)  | 197 ms<br> (+58 ms, -55 ms)   | 1.4 GiB<br> (-559.9 MiB, -300.5 MiB)   | 0 B<br> (-816.0 KiB, -8.0 KiB)       | 804.0 MiB<br> (-3.2 MiB, -148.0 KiB)   | 0 B<br> (+0 B, +0 B)       | 31                 | 139.1 KiB<br> (-1.1 KiB, +791 B)      |
|                                    |          | Warm     | 0                  | 123 ms<br> (-43 µs, +6 ms)    | 137 ms<br> (-11 ms, +6 ms)    | 2.1 GiB<br> (+578.2 MiB, +320.6 MiB)   | 0 B<br> (-812.0 KiB, -4.0 KiB)       | 800.0 MiB<br> (-3.2 MiB, -76.0 KiB)    | 0 B<br> (+0 B, +0 B)       | 29                 | 137.8 KiB<br> (-373 B, +5.5 KiB)      |
| 200 × 4 MiB generated files        | local    | Cold     | 200<br> (+0, +199) | 2.73 s<br> (+797 ms, +1.05 s) | 8.14 s<br> (-2.62 s, -858 ms) | 948.8 MiB<br> (-154.7 MiB, +154.5 MiB) | 800.0 KiB<br> (-800.0 KiB, -4.0 KiB) | 801.6 MiB<br> (-1.6 MiB, -28.0 KiB)    | 800.0 MiB<br> (+0 B, +0 B) | 829<br> (+0, +398) | 550.7 KiB<br> (-48.4 KiB, +151.7 KiB) |
|                                    |          | 10% miss | 20<br> (+0, +19)   | 341 ms<br> (-3 ms, -655 ms)   | 906 ms<br> (-43 ms, -2.34 s)  | 1.2 GiB<br> (+278.1 MiB, +255.5 MiB)   | 880.0 KiB<br> (-880.0 KiB, -8.0 KiB) | 881.6 MiB<br> (-1.6 MiB, -52.0 KiB)    | 800.0 MiB<br> (+0 B, +0 B) | 109<br> (+0, +38)  | 182.6 KiB<br> (-5.4 KiB, +11.7 KiB)   |
|                                    |          | 1 miss   | 1                  | 113 ms<br> (-6 ms, -803 ms)   | 155 ms<br> (-2 ms, -2.62 s)   | 1.1 GiB<br> (-4.7 MiB, -137.8 MiB)     | 804.0 KiB<br> (-804.0 KiB, -8.0 KiB) | 805.6 MiB<br> (-1.6 MiB, -52.0 KiB)    | 800.0 MiB<br> (+0 B, +0 B) | 33                 | 144.3 KiB<br> (-885 B, -3.0 KiB)      |
|                                    |          | Warm     | 0                  | 83 ms<br> (+3 ms, +10 ms)     | 125 ms<br> (+6 ms, +10 ms)    | 1.3 GiB<br> (-33.8 MiB, +125.4 MiB)    | 800.0 KiB<br> (-800.0 KiB, -4.0 KiB) | 801.6 MiB<br> (-1.6 MiB, -28.0 KiB)    | 800.0 MiB<br> (+0 B, +0 B) | 29                 | 141.4 KiB<br> (-584 B, +5.3 KiB)      |
|                                    | remote   | Cold     | 200<br> (+0, +199) | 4.38 s<br> (-3.90 s, -2.41 s) | 1.71 s<br> (-3.42 s, -216 ms) | 912.1 MiB<br> (-334.1 MiB, +60.3 MiB)  | 800.0 KiB<br> (-800.0 KiB, -4.0 KiB) | 804.0 MiB<br> (-4.7 MiB, -76.0 KiB)    | 0 B<br> (+0 B, +0 B)       | 829<br> (+0, +398) | 540.2 KiB<br> (-48.7 KiB, +147.1 KiB) |
|                                    |          | 10% miss | 20<br> (+0, +19)   | 561 ms<br> (-436 ms, -1.75 s) | 255 ms<br> (-67 ms, -18 ms)   | 1021.0 MiB<br> (+82.7 MiB, +36.4 MiB)  | 880.0 KiB<br> (-880.0 KiB, -8.0 KiB) | 884.3 MiB<br> (-5.1 MiB, -160.0 KiB)   | 0 B<br> (+0 B, +0 B)       | 109<br> (+0, +38)  | 178.6 KiB<br> (-5.5 KiB, +11.1 KiB)   |
|                                    |          | 1 miss   | 1                  | 248 ms<br> (-127 ms, -1.73 s) | 135 ms<br> (-1 ms, -63 ms)    | 1.1 GiB<br> (+118.1 MiB, -6.0 MiB)     | 804.0 KiB<br> (-804.0 KiB, -8.0 KiB) | 808.0 MiB<br> (-4.8 MiB, -156.0 KiB)   | 0 B<br> (+0 B, +0 B)       | 33                 | 141.1 KiB<br> (-851 B, -3.0 KiB)      |
|                                    |          | Warm     | 0                  | 120 ms<br> (+2 ms, +3 ms)     | 132 ms<br> (+14 ms, +17 ms)   | 1.2 GiB<br> (+30.0 MiB, -33.9 MiB)     | 800.0 KiB<br> (-800.0 KiB, -4.0 KiB) | 804.0 MiB<br> (-4.7 MiB, -76.0 KiB)    | 0 B<br> (+0 B, +0 B)       | 29                 | 137.9 KiB<br> (-411 B, +5.5 KiB)      |
| 10 source dirs, 20 × 4 MiB each    | local    | Cold     | 10<br> (+0, +9)    | 539 ms<br> (+14 ms, -319 ms)  | 1.88 s<br> (-2.25 s, -1.81 s) | 651.7 MiB<br> (+15.0 MiB, -7.7 MiB)    | 0 B<br> (-40.0 KiB, -4.0 KiB)        | 0 B<br> (-800.1 MiB, -800.0 MiB)       | 800.0 MiB<br> (+0 B, +0 B) | 49<br> (+0, +18)   | 150.0 KiB<br> (-2.8 KiB, +5.8 KiB)    |
|                                    |          | 10% miss | 1                  | 115 ms<br> (-56 ms, -644 ms)  | 264 ms<br> (-180 ms, -2.67 s) | 716.7 MiB<br> (+27.3 MiB, -26.1 MiB)   | 0 B<br> (-44.0 KiB, -8.0 KiB)        | 0 B<br> (-804.1 MiB, -804.1 MiB)       | 800.0 MiB<br> (+0 B, +0 B) | 31                 | 140.3 KiB<br> (-723 B, +379 B)        |
|                                    |          | 1 miss   | 1                  | 114 ms<br> (-50 ms, -665 ms)  | 252 ms<br> (-154 ms, -2.67 s) | 822.1 MiB<br> (+100.4 MiB, -66.4 MiB)  | 0 B<br> (-44.0 KiB, -8.0 KiB)        | 0 B<br> (-804.1 MiB, -804.1 MiB)       | 800.0 MiB<br> (+0 B, +0 B) | 31                 | 140.3 KiB<br> (-765 B, +337 B)        |
|                                    |          | Warm     | 0                  | 72 ms<br> (+4 ms, +3 ms)      | 117 ms<br> (+13 ms, +12 ms)   | 849.2 MiB<br> (+67.5 MiB, +59.8 MiB)   | 0 B<br> (-40.0 KiB, -4.0 KiB)        | 0 B<br> (-800.1 MiB, -800.0 MiB)       | 800.0 MiB<br> (+0 B, +0 B) | 29                 | 138.9 KiB<br> (-360 B, +1.2 KiB)      |
|                                    | remote   | Cold     | 10<br> (+0, +9)    | 1.36 s<br> (-42 ms, -666 ms)  | 3.84 s<br> (+830 ms, +2.63 s) | 1.9 GiB<br> (+386.5 MiB, +390.0 MiB)   | 0 B<br> (-52.0 KiB, -4.0 KiB)        | 800.0 MiB<br> (-424.0 KiB, -100.0 KiB) | 0 B<br> (+0 B, +0 B)       | 49<br> (+0, +18)   | 146.0 KiB<br> (-3.1 KiB, +5.7 KiB)    |
|                                    |          | 10% miss | 1                  | 297 ms<br> (-148 ms, -1.40 s) | 599 ms<br> (+359 ms, +353 ms) | 1.3 GiB<br> (-117.4 MiB, +16.5 MiB)    | 0 B<br> (-56.0 KiB, -8.0 KiB)        | 804.0 MiB<br> (-440.0 KiB, -144.0 KiB) | 0 B<br> (+0 B, +0 B)       | 31                 | 136.1 KiB<br> (-773 B, +378 B)        |
|                                    |          | 1 miss   | 1                  | 288 ms<br> (-153 ms, -1.41 s) | 503 ms<br> (+300 ms, +282 ms) | 1.7 GiB<br> (+247.8 MiB, +155.5 MiB)   | 0 B<br> (-56.0 KiB, -8.0 KiB)        | 804.0 MiB<br> (-444.0 KiB, -148.0 KiB) | 0 B<br> (+0 B, +0 B)       | 31                 | 135.9 KiB<br> (-927 B, +127 B)        |
|                                    |          | Warm     | 0                  | 117 ms<br> (+2 ms, -2 ms)     | 114 ms<br> (+1 ms, -13 ms)    | 1.6 GiB<br> (-281.8 MiB, +168.1 MiB)   | 0 B<br> (-52.0 KiB, -4.0 KiB)        | 800.0 MiB<br> (-420.0 KiB, -100.0 KiB) | 0 B<br> (+0 B, +0 B)       | 29                 | 134.6 KiB<br> (-134 B, +1.4 KiB)      |
| 10 generated dirs, 20 × 4 MiB each | local    | Cold     | 10<br> (+0, +9)    | 2.72 s<br> (+924 ms, +1.34 s) | 6.31 s<br> (-2.03 s, -1.12 s) | 700.7 MiB<br> (-112.9 MiB, +4.7 MiB)   | 40.0 KiB<br> (-40.0 KiB, -4.0 KiB)   | 800.1 MiB<br> (-80.0 KiB, -28.0 KiB)   | 800.0 MiB<br> (+0 B, +0 B) | 69<br> (+0, +18)   | 162.8 KiB<br> (-2.8 KiB, +6.0 KiB)    |
|                                    |          | 10% miss | 1                  | 312 ms<br> (-50 ms, -584 ms)  | 683 ms<br> (-192 ms, -2.63 s) | 837.0 MiB<br> (+105.0 MiB, +92.0 MiB)  | 44.0 KiB<br> (-44.0 KiB, -8.0 KiB)   | 880.1 MiB<br> (-84.0 KiB, -52.0 KiB)   | 800.0 MiB<br> (+0 B, +0 B) | 33                 | 141.8 KiB<br> (-727 B, +209 B)        |
|                                    |          | 1 miss   | 1                  | 399 ms<br> (+100 ms, -481 ms) | 706 ms<br> (-93 ms, -2.58 s)  | 806.5 MiB<br> (-2.5 MiB, +26.7 MiB)    | 44.0 KiB<br> (-44.0 KiB, -8.0 KiB)   | 880.1 MiB<br> (-84.0 KiB, -52.0 KiB)   | 800.0 MiB<br> (+0 B, +0 B) | 33                 | 141.8 KiB<br> (-718 B, +208 B)        |
|                                    |          | Warm     | 0                  | 58 ms<br> (-62 ms, +2 ms)     | 79 ms<br> (+8 ms, +12 ms)     | 811.7 MiB<br> (-38.8 MiB, +2.8 MiB)    | 40.0 KiB<br> (-40.0 KiB, -4.0 KiB)   | 800.1 MiB<br> (-80.0 KiB, -28.0 KiB)   | 800.0 MiB<br> (+0 B, +0 B) | 29                 | 139.1 KiB<br> (-433 B, +1.2 KiB)      |
|                                    | remote   | Cold     | 10<br> (+0, +9)    | 2.75 s<br> (-281 ms, -1.48 s) | 526 ms<br> (-1.06 s, -129 ms) | 753.1 MiB<br> (+162.9 MiB, +63.2 MiB)  | 40.0 KiB<br> (-52.0 KiB, -4.0 KiB)   | 800.4 MiB<br> (-336.0 KiB, -88.0 KiB)  | 0 B<br> (+0 B, +0 B)       | 69<br> (+0, +18)   | 158.7 KiB<br> (-3.0 KiB, +5.6 KiB)    |
|                                    |          | 10% miss | 1                  | 635 ms<br> (-270 ms, -1.57 s) | 145 ms<br> (-47 ms, -97 ms)   | 910.3 MiB<br> (+117.0 MiB, +58.9 MiB)  | 44.0 KiB<br> (-44.0 KiB, -8.0 KiB)   | 880.4 MiB<br> (-352.0 KiB, -140.0 KiB) | 0 B<br> (+0 B, +0 B)       | 33                 | 137.8 KiB<br> (-745 B, +104 B)        |
|                                    |          | 1 miss   | 1                  | 582 ms<br> (-314 ms, -1.62 s) | 116 ms<br> (-42 ms, -97 ms)   | 925.0 MiB<br> (-26.1 MiB, +19.0 MiB)   | 44.0 KiB<br> (-44.0 KiB, -8.0 KiB)   | 880.4 MiB<br> (-352.0 KiB, -140.0 KiB) | 0 B<br> (+0 B, +0 B)       | 33                 | 137.8 KiB<br> (-743 B, +105 B)        |
|                                    |          | Warm     | 0                  | 103 ms<br> (+2 ms, +4 ms)     | 93 ms<br> (+23 ms, +20 ms)    | 990.8 MiB<br> (-22.3 MiB, -34.5 MiB)   | 40.0 KiB<br> (-40.0 KiB, -4.0 KiB)   | 800.4 MiB<br> (-324.0 KiB, -88.0 KiB)  | 0 B<br> (+0 B, +0 B)       | 29                 | 134.9 KiB<br> (-160 B, +1.5 KiB)      |

## Example usage

### `@bazel_skylib`'s [`copy_directory`](https://github.com/bazelbuild/bazel-skylib/blob/bac104bc6065308a043489757f2a7ffd159c7fd1/rules/private/copy_directory_private.bzl)

```starlark
def _copy_directory_impl(ctx):
    dst = ctx.actions.declare_directory(ctx.attr.out)
    # Analysis-time error if `src` is not a tree artifact
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
* The `_exec_is_windows` attribute and `:is_windows` target are gone.
* The `no-remote`/`no-cache` execution-requirement workaround is gone, along with its Build-without-the-Bytes download penalty.

### `bazel_lib`'s [`copy_to_bin`](https://github.com/bazel-contrib/bazel-lib/blob/222a5bf32e8b6a546059cfff85fe01af7164e596/lib/private/copy_to_bin.bzl)

```starlark
def copy_file_to_bin_action(ctx, file):
    if not file.is_source:
        return file
    if ctx.label.workspace_name != file.owner.workspace_name:
        fail(_file_in_external_repo_error_msg(file))
    if ctx.label.package != file.owner.package:
        fail(_file_in_different_package_error_msg(file, ctx.label))

    if file.path.startswith("bazel-"):
        first = file.path.split("/")[0]
        suffix = first[len("bazel-"):]
        if suffix in ["testlogs", "bin", "out"]:
            print(_probably_should_git_ignore(first))

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

* Core validation logic remains unchanged.
* No helper required to copy file(s).
* No toolchain resolution is necessary.
* N/A for the macro since the the canonical implementation just fowards to the rule.

# Backward-compatibility

This proposal won't impact backward compatibility, although feature detection should be considered so that existing utility rulesets can optionally use the newer (and more efficent) API without needing to wait for supported Bazel versions to age out of the support matrix.

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

1. **Directory composition.** `copy_to_directory`-style assembly (N files/trees → one tree artifact, with path remapping) is the other high-volume copy workload. It likely wants a separate, richer API — one accepting a list of files/trees, with answers about merging, conflicts, and exclusions — rather than overloading `copy`. Deferred.
2. **Phasing of `path` extraction.** Extracting one file from a tree artifact (phase 3) is the least-demanded combination and interacts with the most machinery (tree metadata lookup at execution time). It could ship later, or be dropped if `File.tree_relative_path`-style consumption proves sufficient.
