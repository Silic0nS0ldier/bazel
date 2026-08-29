# Copy action benchmark

Harness for the **Benchmarks** section of the Copy Action proposal
(`//2024-08-14-copy-action.md`). It compares three ways to copy files/trees and
emits one Markdown table per mechanism, matching the proposal's layout.

Mechanisms (one table each):

| id  | table title           | mechanism                        | mnemonic    |
|-----|-----------------------|----------------------------------|-------------|
| `S` | Per-Artifact Spawns   | one `cp` spawn per input         | `CopySpawn` |
| `B` | Batched Spawns        | a single spawn copying every input | `CopyBatch` |
| `C` | Copy Action           | the built-in `ctx.actions.copy`  | `Copy`      |

Axes:

* **Content** — the four input kinds (each ≈800 MiB), to contrast source vs
  generated and file vs directory copying:
  * `src_files` — 200 × 4 MiB **source** files;
  * `gen_files` — 200 × 4 MiB **generated** files (each produced by a `make_file`
    action, so under BwoB the input is remote-backed);
  * `src_dirs` — 10 **source** directories, 20 × 4 MiB each;
  * `gen_dirs` — 10 **generated** tree artifacts, 20 × 4 MiB each (`make_tree`).
* **Strategy**: `local` (execute locally, cache to a local `--disk_cache`),
  `remote` (real remote execution + caching against a locally-hosted
  [NativeLink](https://github.com/TraceMachina/nativelink) REAPI server, with
  BwoB — `--remote_download_outputs=minimal`).
* **Cache**: `Cold`, `10% miss`, `1 miss`, `Warm`.

A *source directory* is a file-type artifact whose directoriness is unknowable at
analysis time; the copy action accepts one wherever the output is declared as a
directory and validates the input at execution, so `src_dirs` + Copy Action is
measured like every other cell. A source directory tracks only one aggregate
fingerprint (no per-child digests), so the copy computes per-child digests at
execution time: physically copying and digesting outputs locally, or — under
BwoB — hashing and uploading each contained file so the output stays
unmaterialised (OUT=0), mirroring `src_files`. (The harness still probes each
mechanism with a cheap `--nobuild` analysis check and reports `—` for any
scenario a mechanism cannot analyze, rather than running a doomed build per
repeat.) The interesting cache/upload contrast is `src_files` vs `gen_files`: a
source-file copy has no cache footprint of its own, whereas a generated-file
copy's input arrives via the CAS.

The **Copy Action** table annotates each value with its change versus the
per-artifact (S) and batched (B) spawns, e.g. `1.33 s (-1.37 s, -155 ms)`.

## Prerequisites

The copy action is gated behind `--experimental_copy_action`, so the harness
needs a Bazel built from this branch:

```sh
bazel build //src:bazel      # produces bazel-bin/src/bazel (the default --bazel)
```

`uv` runs the script and installs its small dependencies (`tabulate`,
`humanize`) automatically from the inline PEP 723 metadata — no manual setup.

**NativeLink** backs the `remote` strategy — a single self-hostable binary that
provides a full REAPI cluster (CAS + action cache + scheduler + worker) in one
process. It is acquired reproducibly via [DotSlash](https://dotslash-cli.com):
the committed [`nativelink`](nativelink) manifest pins the v1.6.1 release by
sha256, and running it fetches, verifies, caches, and execs the binary on first
use — no manually-placed binary. You only need `dotslash` on `PATH` (and network
access on the first `remote` run). Nothing to install for `--strategies local`.

The harness writes NativeLink's config, starts/stops it, and wipes+restarts it
per cell for a cold cache; spawns then really execute on the worker
(`Execute` + `ActionResult` + CAS), while the copy action runs in-process.

To bump the pinned version, regenerate the manifest — e.g. with Canva's
generator (`//tools/dotslash`), or by hand: update each platform's release URL,
`size`, and `digest` (sha256 of the release `.tar.gz`).

Run on a **copy-on-write filesystem** (btrfs/APFS) so OUT reflects reflink
sharing. On this devbox that is `/mnt/ephemeral` (btrfs), the default
`--scratch-root`.

## Usage

```sh
# Smoke test (tiny workloads, 2 repeats) — validates the harness end to end:
uv run benchmarks/copy_action/bench.py --quick

# Full proposal matrix (see "Runtime"), averaged over 10 runs, to a directory:
uv run benchmarks/copy_action/bench.py --out /tmp/copy-bench

# Slices:
uv run benchmarks/copy_action/bench.py --scenarios files_200x4MiB --strategies local
uv run benchmarks/copy_action/bench.py --impls S,C --states cold,warm --repeats 3
```

Key flags (`--help` for all):

* `--bazel PATH` — Bazel binary (default `bazel-bin/src/bazel`).
* `--scenarios`, `--strategies`, `--impls`, `--states` — comma-separated filters.
* `--repeats N` — runs per cell; the reported value is the **mean** (default 10).
* `--nativelink PATH` — NativeLink launcher for the `remote` strategy (default:
  the committed `./nativelink` DotSlash manifest; may also be a plain binary).
  `--nativelink-port` sets its gRPC port (default 50051; worker API uses port+10).
* `--scratch-root DIR` — parent for this run's scratch (default
  `/mnt/ephemeral/bazel-copy-bench`; keep on a CoW filesystem). Each run gets a
  unique `run-XXXX/` subdir there, removed on exit — so repeated or concurrent
  runs never collide on a shared path. `--keep-scratch` retains it for debugging;
  `--workdir` / `--output-base-root` override the individual locations.
* `--out DIR` — write the Markdown tables to `DIR/results.md` (created if
  needed) instead of stdout.
* **Raw per-run metrics** (always written, no flag): every individual run's
  metrics as CSV at `${out}/raw.csv` (falling back to `<scratch-root>/raw.csv`
  when `--out` isn't given), so the reported averages can be audited.
* **Introspection** (always on, no flag): every `bazel` invocation the harness
  makes (measured builds, baselines, cleans, probes, …) has its artifacts stored
  in a single flat directory, `${out}/introspect` (falling back to
  `<scratch-root>/introspect` when `--out` isn't given) — wiped at the start of
  each run, so it always reflects just the run you made. Each invocation's files
  share one filename prefix — a global sequence number, the cell
  (`scenario-strategy-state-impl-repNN`), and the phase, e.g.
  `00042-src_files-local-cold-S-rep01-measured` — distinguished by suffix:
  `.invocation.json` (argv, context, exit code, wall time) and `.stdout.txt`
  (combined stdout+stderr) for every invocation; `.bep.jsonl` (the BEP stream),
  `.exec.log.zst` (compact execution log, readable with
  `//src/tools/execlog:parser`) and `.profile.json.gz` (the JSON trace command
  profile — load it in `chrome://tracing`/Perfetto) additionally for `build`
  invocations; and `.metrics.json` (the exact figures fed to the tables and raw
  CSV) for measured builds. This instrumentation (execution log, profile, and BEP
  on otherwise-untraced builds) adds overhead to every measured build, so treat
  introspected numbers as a tool for understanding behaviour, not headline
  figures.

Progress goes to stderr: a per-scenario `preparing workspace (… of inputs)…`
line, one `[n/total] …` line per measured cell, and — when a scenario finishes —
a `--- <key>: collected in <elapsed> (<phase breakdown>) ---` line, closing with
a final `total phase breakdown` line. The breakdown times the harness's own
phases (measured `build` vs warmup: `baseline`, `clean`, `nl-reset`, …) and
reports an `overhead/build` ratio, so it is easy to see how much wall time is the
builds themselves versus warmup. The tables go to stdout (or `--out DIR`'s
`results.md`). Paste them into the proposal's Benchmarks section.

## Metrics

Reported per cell, averaged over `--repeats` runs, with scale-aware units
(ns/µs/ms/s for time; B/KiB/MiB/GiB for sizes):

* **Cache Misses** — actions executed for the mechanism's mnemonic, from the
  build's BEP `BuildMetrics.actionSummary.actionData`. Per-mechanism; for tree
  scenarios it **excludes** the `MakeTree` input-generation spawns.
* **Wall** — wall-clock time of the measured build (client-side).
* **CPU Time** — total server CPU (`BuildMetrics.timingMetrics.cpuTimeInMs`).
* **Peak RSS** — peak RSS of the Bazel server, polled from
  `/proc/<server_pid>/status` during the build (true peak may be higher between
  samples).
* **AC** — **action-cache** space occupied: `du` of the `ac` store of the local
  `--disk_cache` (local strategy) or of NativeLink's cache (remote). For the copy
  action it stays 0 — it is not a spawn, so there is no `ActionResult`.
* **CAS** — **content store** space occupied: `du` of the `cas` store, likewise.
  A spawn duplicates its output into the cache; the copy action adds ~no new blob
  (the output shares the input's digest). *For tree scenarios the CAS also holds
  the generated trees — see caveats.*
* **OUT** — disk used by the mechanism's output tree (`bazel-bin/<target>`), as
  allocated blocks (`du -s`). This is exact for the benchmark's distinct-content
  scenarios (no two output files share an extent, so there is nothing to
  double-count). btrfs extent accounting (`btrfs filesystem du`) would additionally
  dedup reflinks but proved unreliable — it reports 0 for some freshly-built spawn
  outputs (uncommitted extents) even after a `sync` — so it is not used.
* **BEP** — total build-event-protocol events produced, with
  `--build_event_publish_all_actions` so per-action events are emitted (otherwise
  Bazel publishes only a handful of structural events regardless of action count).
* **BEP Size** — byte size of the BEP stream. A per-artifact spawn and a copy emit
  the same event *count*, but the spawn's events carry command lines and inputs,
  so BEP Size surfaces the volume difference (spawn > copy > batch).

## How cache states are prepared

Each cell is set up **once** and then measured `--repeats` times. The footprint
metrics (AC/CAS/OUT and the per-mnemonic counts) are deterministic, so only the
*first* build runs against the precise cache state below — it alone contributes
them. The remaining repeats re-execute cheaply, purely to resample the noisy
timing metrics (wall/CPU/RSS); their cache footprints intentionally drift and are
discarded. This reproduces the figures a full reset-per-repeat would give while
turning O(cells × repeats) warmup into O(cells) — one reset, plus one baseline
build for the non-cold states, per cell instead of per run.

The reset returns the cell to cold: `bazel clean` plus wiping the strategy's
cache — for `local`, delete the `--disk_cache`; for `remote`, stop NativeLink,
wipe its stores, and restart it (a running server holds its store index in
memory, so the stores can't be wiped in place; Bazel's gRPC channel reconnects
automatically):

* **Cold** — reset; then each repeat rewrites *every* input to brand-new content
  and builds, so every action re-executes. (A cache left warm by the previous
  repeat would otherwise turn the rebuild into a hit and mis-time it — for
  `remote` and for `local`'s `--disk_cache` alike.) The first build's footprint,
  taken against the freshly-reset empty cache, is the from-cold absolute footprint.
* **Warm** — reset, build once (unmeasured baseline); then each repeat rebuilds
  as-is against the populated cache (every action a hit, 0 misses).
* **1 miss / 10% miss** — reset, build the baseline; then each repeat rewrites
  1 / 10% of the inputs with brand-new content and builds, so exactly those
  actions re-run.

Rewrites carry a monotonic revision stamp, so no content is ever repeated across
the run (always a genuine miss). For file scenarios an input is a source file (or
a `make_file` seed for generated files); for tree scenarios it is a `make_tree`
seed, so rewriting it regenerates exactly that artifact and forces its copy to
re-run.

## Runtime & caveats

* The full matrix is `4 scenarios × 2 strategies × 4 states × 3 mechanisms`, and
  every non-cold cell also does an (unmeasured) baseline build, all averaged over
  `--repeats` (default 10). The `remote` cells also restart NativeLink per cell.
  Budget on the order of **30–50 min**; use `--quick` or narrow the axes while
  iterating.
* **CPU/RSS/AC/CAS are whole-build figures.** For tree scenarios they include tree
  generation (the `MakeTree` spawns); **Cache Misses and OUT are scoped to the
  copy mechanism only** (`bazel-bin/<target>` excludes the generated trees).
* **`local` mirrors a typical dev setup** — local execution with a `--disk_cache`.
  AC/CAS then show that a spawn duplicates its output into the cache (CAS ≈ output
  size) while the copy action, not being a spawn, adds nothing (AC=0, CAS=0).
* **`remote` runs real remote execution** on NativeLink's worker — spawns do an
  `Execute` + `ActionResult` + CAS round-trips, so AC/CAS reflect genuine remote
  cache occupancy. Under BwoB, spawn outputs are **not** materialised locally
  (OUT≈0). Point `--nativelink` elsewhere to use a different build.
* **The copy action defers materialisation (OUT=0) under BwoB for every input
  kind.** Generated inputs (`gen_files`, `gen_dirs`) are remote-backed, so the
  copy is metadata-only. Source inputs (`src_files`, `src_dirs`) are hashed and
  proactively uploaded to the CAS instead of copied locally (for directories,
  per contained file — a source directory tracks only one aggregate fingerprint,
  so per-child digests are computed at copy time), so OUT is likewise 0 with the
  upload showing up as CAS.
* **Peak RSS is polled**, so the true peak between 20 ms samples may be higher; it
  is also dominated by shared JVM/heap state at these sizes and is the least
  discriminating metric.
* **OUT is output-tree occupancy (allocated blocks)** — it does not credit
  reflink sharing. On btrfs the copy action reflinks its source (writes ~0 new
  blocks) while a sandboxed `cp` writes fresh blocks, but `btrfs filesystem du`
  was inconsistent for freshly-built outputs, so that extent-level view is not
  reported. Ask if you want a reflink "new blocks" column.

## Files

* `defs.bzl` — the `copy`/`spawn`/`batch` rules for files and trees, plus
  `make_tree` for generating tree-artifact inputs.
* `bench.py` — a `uv run` script (PEP 723 inline deps): workspace generation,
  cache-state control, metric collection, and table rendering.
* `nativelink` — a DotSlash manifest pinning the NativeLink RBE server used by
  the `remote` strategy; run it (or let the harness) to fetch + exec NativeLink.
