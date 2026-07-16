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
* Eliminate — not merely reduce — remote execution and BES protocol chatter for copies.
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
    # File: a file, tree or unresovled symlink artifact
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

1. **The copy preserves artifact type.** A file artifact copies to a file artifact, a tree artifact to a tree artifact, an unresolved symlink artifact to an unresolved symlink artifact. Mismatched input/output types are an analysis-time error (with one carve-out: `path` extraction, which copies a *child* of a tree).
2. **Output content is identical to input content.** For file and tree artifacts, byte-identical: the output's digest (and for trees, the digest of every child) equals the input's. For unresolved symlink artifacts, the tracked content *is* the target string, and the copy reproduces it verbatim. Only the name differs. File permissions — notably the executable bit — are preserved from the input, so no `is_executable` parameter is needed (unlike `write()` and `symlink()`, which create content that has no permissions to inherit).
3. **Semantics are defined at the artifact level; materialisation is invisible.** The result is a pure function of the input artifact's type and content. A file or tree artifact that happens to be materialised as a symlink on the local filesystem (a sandboxed action's input, a Build-without-the-Bytes symlink materialisation) is still a file or tree artifact: the copy follows the incidental symlink and produces content, the same observable result as if the input had been materialised as a regular file. Symlink→symlink semantics apply only to artifacts that are symlinks *by type* (`declare_symlink`), never by materialisation. Execution behaviour thus never depends on incidental filesystem state, preserving determinism.
4. **The action is not a spawn.** Like symlink actions, it has no execution strategy, no execution platform, no sandbox, and never executes remotely. Bazel performs the work in-process.
5. **Realisation of the output is deferred where possible.** When the input's content is remote-backed under Build without the Bytes, the copy completes as a metadata-only operation and the output is materialised on demand, exactly like any other remote-backed output.

## Supported input/output combinations

| Input | Output | `path` | Semantics |
|---|---|---|---|---|
| file artifact (source or generated) | `declare_file` | — | copy the file |
| tree artifact | `declare_directory` | — | recursively copy the tree |
| unresolved symlink artifact | `declare_symlink` | — | new symlink with the identical target string |
| tree artifact | `declare_file` | required | copy the file at `path` (relative to the tree root) |
| tree artifact | `declare_directory` | required | recursively copy the sub-tree at `path` (relative to the tree root) |

`path` must be a non-empty relative path that does not escape the tree root (no leading `/`, no `..` after normalisation) — anything else is an analysis-time error. A `path` that does not name a regular file or sub-tree in the tree when the action executes (i.e. missing) is an execution error, consistent with tree contents being unknowable at analysis time.

Everything else is an **analysis-time error**, in particular:

* **Type mismatches.** File↔tree, file↔symlink, tree↔symlink (in either direction, `path` extraction excepted). Where a rule genuinely wants to convert (e.g. produce a regular file with the content an unresolved symlink *points at*) that is a different operation with different tracking requirements (Bazel does not track what lies behind an unresolved symlink), not a copy.
* **File input with directory output** (composition). Assembling directories from files is a real need (e.g. `@bazel_lib`'s `copy_to_directory`) but a materially different API.

Clarifications for specific cases:

* **Inputs produced by `ctx.actions.symlink`.**
  - `symlink(output, target_file = ...)` produces a *file* (or tree) artifact whose content Bazel tracks, a the copy yields the content.
  - `symlink(output, target_path = ...)` produces an unresolved symlink artifact, and the copy yields a symlink with the same target string.
* **Symlinks inside tree artifacts.** Bazel's tree traversal already dereferences symlinks when constructing a tree artifact's metadata: children are reported as the regular files or directories they resolve to, and dangling symlinks are an error. Tree children are therefore logically regular files, and both tree copies and `path` extraction are well-defined without new symlink policy. Copying a materialised tree applies the same rule: symlinks encountered in the tree are resolved and their content copied, and a symlink that fails to resolve is an execution error — the same condition tree metadata collection already rejects.

## Execution semantics

The action's cache key is derived from the input's tracked content (its digest, or for an unresolved symlink its target string) and the output's path, so ordinary incremental machinery applies: a copy re-executes exactly when its input's content changes. It is deliberately *not* keyed on any implementation detail (no tool digest, no command line), fixing the cache-key fragility of spawn-based copies.

Execution proceeds by propagating metadata, not collecting it:

* **Local input** (bytes present in the output tree or source tree): Bazel copies the file(s) in-process and records the output's metadata as a copy of the input's. Copies run on Skyframe's executors and do not occupy local execution slots, so heavyweight spawns are never queued behind them.
* **Remote-backed input** (Build without the Bytes): no bytes move. The output's metadata is the input's metadata — same digest, same size, same remote expiry — under the output's own path. Materialisation is deferred until demanded (requested as a top-level output, consumed by a locally-executing action, needed by `bazel run`, etc). Since the CAS is content-addressed, the output *is* the input's blob; there is nothing to upload and nothing to download.
* **Eviction recovery**: because the output references the same digest as the input, existing lease-extension and TTL tracking cover it, and if the blob is evicted from the remote cache the existing rewinding machinery applies. The copy declares itself input-propagating (as symlink actions do), so rewinding attributes the loss through the copy, transitively through chains of copies, to the action that originally produced the bytes and re-runs it.
* **Unresolved symlink input**: the tracked metadata is the target string, so the copy is always metadata-only; materialisation (when demanded) creates the symlink directly. No digests, blobs, or caches are involved at any point, mirroring the existing unresolved-symlink action.

The action's mnemonic is `Copy`. Introspection rides the ordinary action-graph machinery: `bazel aquery 'mnemonic("Copy", ...)'` reports the input and output, and an extracting copy additionally reports its `path` (as `CopyPath:` in the text format).

### Materialisation strategy

When bytes must exist at the output path, the default is a **real copy**. Copy-on-write clones are permitted as a transparent optimisation: a clone creates a new inode with independent content, so it is semantically indistinguishable from a copy. No dedicated reflink plumbing or feature detection is required to get this in practice, the platform file-copy APIs Bazel already uses (`java.nio.file.Files.copy`) route through `copy_file_range` on Linux and `clonefile` on macOS, which perform CoW clones on supporting filesystems and fall back to plain copies elsewhere. Dedicated `FICLONE`/ReFS-block-clone plumbing remains an option if the default path proves insufficient, but is not needed for correctness or for the common cases.

**Hardlinks are explicitly not used.** They share an inode, which has two failure modes: mutation through one path is visible through all paths (surprising when output tree checking is off), and macOS Gatekeeper [kills processes whose executable shares an inode with a previously-quarantined path](https://developer.apple.com/forums/thread/663456). An opt-in hardlink mode could be added later if a use case demands it; the principle is that the default is an actual copy, and cheaper modes are explicit opt-ins (CoW clones excepted, since they are observably equivalent).

### Remote execution and BES traffic

**A copy action generates zero per-copy remote and BES traffic.**

* No `Execute` call: the action is not a spawn.
* No `ActionResult` lookup or upload: there is no remote action to key one on.
* No CAS uploads: byte-identity guarantees the output's blob exists wherever the input's does. Downstream actions reference the digest in their merkle trees as usual.
* No additional BES events: non-spawn actions do not publish per-action events by default, so 10k copies add nothing to BEP volume or event-stream backpressure.

Skipping the `ActionResult` may look like it forfeits value (output lifetime tracking, and sparing other clients the work of a large directory copy). It does not:

* An `ActionResult` maps an action key to output digests. For a copy, the output digests are computable *locally, from input metadata alone*; a remote round trip to fetch or store that mapping is pure overhead. Other Bazel clients "replay" the copy as a metadata-only in-process step — cheaper than a cache hit.
* For directory copies, no new CAS objects are needed either: REAPI `Tree`/`Directory` messages do not embed the root directory's own name, so the output tree's digest is identical to the input tree's, and the object already exists in the CAS.
* Output lifetime is tracked through the digests the build references (lease extension, TTL), which is indifferent to whether the reference arrived via the original producer or via a copy.

### Benchmarks

For this proposal to have any chance of success, it needs to demonstrate a measurable improvement over the status quo. To that end a prototype was created and benchmarked across various scenarios on a 16-core Linux host with btrfs (copy-on-write capable).

Some notes for the results:
- All results are the average of 10 runs.
- BwoB (build without the bytes aka `--remote_download_outputs=minimal`) is enabled for remote strategies.
- Remote strategy uses locally hosted `nativelink`.
- Peak RSS (resident set size) is discovered with polling, true peaks may be higher.
- AC and CAS refer to remote cache space occupied.
- OUT refers to disk space used for Bazel's output tree. Reflinks are specially handled to avoid double counting.
- BEP refers to total build event protocol events produced with `--build_event_publish_all_actions`.

**Per-Artifact Spawns**

| Content                          | Strategy   | Cache    |   Cache Misses | CPU Time   | Peak RSS   | AC        | CAS       | OUT       |   BEP |
|----------------------------------|------------|----------|----------------|------------|------------|-----------|-----------|-----------|-------|
| 200 × 4 MiB files                | local      | Cold     |            200 | 4.39 s     | 604.2 MiB  | 0 B       | 0 B       | 800.0 MiB |   429 |
|                                  |            | 10% miss |             20 | 493 ms     | 771.8 MiB  | 0 B       | 0 B       | 800.0 MiB |    69 |
|                                  |            | 1 miss   |              1 | 157 ms     | 862.1 MiB  | 0 B       | 0 B       | 800.0 MiB |    31 |
|                                  |            | Warm     |              0 | 109 ms     | 893.0 MiB  | 0 B       | 0 B       | 800.0 MiB |    29 |
|                                  | remote     | Cold     |            200 | 6.57 s     | 783.2 MiB  | 800.0 KiB | 803.2 MiB | 0 B       |   429 |
|                                  |            | 10% miss |             20 | 739 ms     | 1.3 GiB    | 880.0 KiB | 883.4 MiB | 0 B       |    69 |
|                                  |            | 1 miss   |              1 | 160 ms     | 1.6 GiB    | 804.0 KiB | 807.1 MiB | 0 B       |    31 |
|                                  |            | Warm     |              0 | 147 ms     | 1.4 GiB    | 801.2 KiB | 803.1 MiB | 0 B       |    29 |
| 10 trees, 100 x 4 MiB files each | local      | Cold     |             10 | 13.40 s    | 572.3 MiB  | 0 B       | 0 B       | 3.9 GiB   |    69 |
|                                  |            | 10% miss |              1 | 1.48 s     | 733.3 MiB  | 0 B       | 0 B       | 3.9 GiB   |    33 |
|                                  |            | 1 miss   |              1 | 1.38 s     | 774.2 MiB  | 0 B       | 0 B       | 3.9 GiB   |    33 |
|                                  |            | Warm     |              0 | 80 ms      | 818.3 MiB  | 0 B       | 0 B       | 3.9 GiB   |    29 |
|                                  | remote     | Cold     |             10 | 2.18 s     | 689.9 MiB  | 80.0 KiB  | 3.9 GiB   | 0 B       |    69 |
|                                  |            | 10% miss |              1 | 231 ms     | 959.4 MiB  | 88.0 KiB  | 4.3 GiB   | 0 B       |    33 |
|                                  |            | 1 miss   |              1 | 173 ms     | 1.0 GiB    | 88.0 KiB  | 4.3 GiB   | 0 B       |    33 |
|                                  |            | Warm     |              0 | 81 ms      | 1.0 GiB    | 80.0 KiB  | 3.9 GiB   | 0 B       |    29 |

**Batched Spawns**

| Content                          | Strategy   | Cache    |   Cache Misses | CPU Time   | Peak RSS   | AC       | CAS       | OUT       |   BEP |
|----------------------------------|------------|----------|----------------|------------|------------|----------|-----------|-----------|-------|
| 200 × 4 MiB files                | local      | Cold     |              1 | 2.90 s     | 651.8 MiB  | 0 B      | 0 B       | 800.0 MiB |    31 |
|                                  |            | 10% miss |              1 | 1.62 s     | 825.0 MiB  | 0 B      | 0 B       | 800.0 MiB |    31 |
|                                  |            | 1 miss   |              1 | 1.42 s     | 862.9 MiB  | 0 B      | 0 B       | 800.0 MiB |    31 |
|                                  |            | Warm     |              0 | 98 ms      | 902.2 MiB  | 0 B      | 0 B       | 800.0 MiB |    29 |
|                                  | remote     | Cold     |              1 | 4.75 s     | 1.5 GiB    | 4.0 KiB  | 800.1 MiB | 0 B       |    31 |
|                                  |            | 10% miss |              1 | 551 ms     | 1.7 GiB    | 8.0 KiB  | 880.2 MiB | 0 B       |    31 |
|                                  |            | 1 miss   |              1 | 186 ms     | 1.9 GiB    | 8.0 KiB  | 804.2 MiB | 0 B       |    31 |
|                                  |            | Warm     |              0 | 107 ms     | 1.9 GiB    | 4.0 KiB  | 800.1 MiB | 0 B       |    29 |
| 10 trees, 100 x 4 MiB files each | local      | Cold     |              1 | 12.57 s    | 656.3 MiB  | 0 B      | 0 B       | 3.9 GiB   |    51 |
|                                  |            | 10% miss |              1 | 7.22 s     | 752.9 MiB  | 0 B      | 0 B       | 3.9 GiB   |    33 |
|                                  |            | 1 miss   |              1 | 7.15 s     | 795.0 MiB  | 0 B      | 0 B       | 3.9 GiB   |    33 |
|                                  |            | Warm     |              0 | 72 ms      | 830.0 MiB  | 0 B      | 0 B       | 3.9 GiB   |    29 |
|                                  | remote     | Cold     |              1 | 1.21 s     | 817.7 MiB  | 44.0 KiB | 3.9 GiB   | 0 B       |    51 |
|                                  |            | 10% miss |              1 | 254 ms     | 1001.4 MiB | 52.0 KiB | 4.3 GiB   | 0 B       |    33 |
|                                  |            | 1 miss   |              1 | 242 ms     | 1022.9 MiB | 52.0 KiB | 4.3 GiB   | 0 B       |    33 |
|                                  |            | Warm     |              0 | 76 ms      | 1.0 GiB    | 44.0 KiB | 3.9 GiB   | 0 B       |    29 |

**Copy Action**

| Content                          | Strategy   | Cache    |   Cache Misses | CPU Time   | Peak RSS   | AC       | CAS      | OUT       |   BEP |
|----------------------------------|------------|----------|----------------|------------|------------|----------|----------|-----------|-------|
| 200 × 4 MiB files                | local      | Cold     |            200 | 1.92 s     | 697.2 MiB  | 0 B      | 0 B      | 800.0 MiB |   429 |
|                                  |            | 10% miss |             20 | 421 ms     | 841.9 MiB  | 0 B      | 0 B      | 800.0 MiB |    69 |
|                                  |            | 1 miss   |              1 | 125 ms     | 874.7 MiB  | 0 B      | 0 B      | 800.0 MiB |    31 |
|                                  |            | Warm     |              0 | 113 ms     | 918.8 MiB  | 0 B      | 0 B      | 800.0 MiB |    29 |
|                                  | remote     | Cold     |            200 | 3.63 s     | 1.1 GiB    | 0 B      | 32.0 KiB | 800.0 MiB |   429 |
|                                  |            | 10% miss |             20 | 439 ms     | 1.3 GiB    | 0 B      | 8.0 KiB  | 800.0 MiB |    69 |
|                                  |            | 1 miss   |              1 | 149 ms     | 1.3 GiB    | 0 B      | 8.0 KiB  | 800.0 MiB |    31 |
|                                  |            | Warm     |              0 | 139 ms     | 1.4 GiB    | 0 B      | 8.0 KiB  | 800.0 MiB |    29 |
| 10 trees, 100 x 4 MiB files each | local      | Cold     |             10 | 8.32 s     | 674.8 MiB  | 0 B      | 0 B      | 3.9 GiB   |    69 |
|                                  |            | 10% miss |              1 | 1.51 s     | 776.6 MiB  | 0 B      | 0 B      | 3.9 GiB   |    33 |
|                                  |            | 1 miss   |              1 | 1.48 s     | 792.7 MiB  | 0 B      | 0 B      | 3.9 GiB   |    33 |
|                                  |            | Warm     |              0 | 75 ms      | 832.8 MiB  | 0 B      | 0 B      | 3.9 GiB   |    29 |
|                                  | remote     | Cold     |             10 | 1.03 s     | 899.4 MiB  | 40.0 KiB | 3.9 GiB  | 0 B       |    69 |
|                                  |            | 10% miss |              1 | 135 ms     | 1010.0 MiB | 44.0 KiB | 4.3 GiB  | 0 B       |    33 |
|                                  |            | 1 miss   |              1 | 118 ms     | 1.0 GiB    | 44.0 KiB | 4.3 GiB  | 0 B       |    33 |
|                                  |            | Warm     |              0 | 72 ms      | 1.0 GiB    | 40.0 KiB | 3.9 GiB  | 0 B       |    29 |


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
