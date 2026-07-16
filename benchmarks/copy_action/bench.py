#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["tabulate>=0.9", "humanize>=4"]
# ///
"""Benchmark harness for the proposed `ctx.actions.copy` action.

Compares three copy mechanisms across the scenarios, cache states, and strategies
in the Copy Action proposal's Benchmarks section, and prints one Markdown table
per mechanism (matching the proposal's layout):

  * Per-Artifact Spawns - one `cp` spawn per input.            Mnemonic CopySpawn.
  * Batched Spawns      - a single spawn copying every input.  Mnemonic CopyBatch.
  * Copy Action         - the built-in `ctx.actions.copy`.     Mnemonic Copy.

Run it with uv (installs the small deps automatically):

    uv run benchmarks/copy_action/bench.py            # full matrix
    uv run benchmarks/copy_action/bench.py --quick    # tiny smoke test

The harness needs a Bazel built from this branch (the copy action is gated behind
--experimental_copy_action). By default it uses ./bazel-bin/src/bazel; override
with --bazel.

Metrics per cell. The noisy timing metrics (Wall/CPU/Peak RSS) are the mean over
--repeats runs (default 10); the deterministic footprint/count metrics (Cache
Misses, AC, CAS, OUT, BEP, BEP Size) are taken from the first run, the only one
measured against the exact cache setup (see "How cache states are prepared" in
the README). Metrics:
  * Cache Misses - actions executed for the mechanism's mnemonic, from the BEP
                   `BuildMetrics`. Per-mechanism; excludes `MakeTree` generation.
  * Wall         - wall-clock time of the measured build (client-side).
  * CPU Time     - total server CPU time (BEP `timingMetrics.cpuTimeInMs`).
  * Peak RSS     - peak RSS of the Bazel server, polled from /proc/<pid>/status
                   during the build (true peak may be higher between samples).
  * AC           - action-cache space occupied (the `ac` store of the local
                   --disk_cache, or of the NativeLink cache under remote).
  * CAS          - content store space occupied (the `cas` store, likewise).
  * OUT          - disk used by the mechanism's output tree (bazel-bin/<target>),
                   as allocated blocks (`du`). Exact for the distinct-content
                   scenarios here (no intra-tree reflink duplicates to collapse);
                   see README for why btrfs extent accounting is not used.
  * BEP          - total build-event-protocol events produced.
  * BEP Size     - byte size of the BEP stream. A per-artifact spawn and a copy
                   emit the same number of events, but the spawn's carry command
                   lines/inputs, so BEP Size shows the volume difference.

The Copy Action table additionally annotates each value with its change versus
the per-artifact (S) and batched (B) spawns, e.g. "1.29 s (-3.27 s, -0.34 s)".

Strategies:
  * local  - execute locally and cache to a local --disk_cache (a typical
             developer setup); outputs materialise locally. The `ac`/`cas`
             subtrees of the disk cache give the AC/CAS figures — showing that a
             spawn duplicates its output into the cache while the (non-spawn) copy
             action adds nothing.
  * remote - real remote execution + caching against a locally-hosted NativeLink
             REAPI server, with Build without the Bytes
             (`--remote_download_outputs=minimal`). Spawns run on the NativeLink
             worker (an `Execute` call + `ActionResult` + CAS round-trips); the
             non-spawn copy action still runs in-process locally. NativeLink's
             on-disk `ac`/`cas` stores give the AC/CAS space figures. NativeLink
             is acquired reproducibly via the committed DotSlash manifest
             (./nativelink) — fetched, hash-verified, and cached on first use.

Caveats:
  * CPU/RSS/AC/CAS are whole-build figures; for tree scenarios they include tree
    generation. Cache Misses and OUT are scoped to the copy mechanism only.
  * AC/CAS are absolute footprints; every cell first returns the cache to cold
    (local: wipe the disk cache; remote: stop NativeLink, wipe its stores, and
    restart — Bazel's gRPC channel reconnects), so figures are per-cell.
  * Under remote + BwoB the copy action stays metadata-only (OUT=0) for every
    input kind: remote-backed inputs (generated artifacts) adopt the existing
    digests, and source files/directories have their content hashed and
    proactively uploaded to the CAS instead of copied locally (for directories,
    per contained file).

Individual run metrics are also always written to a CSV (<out or
scratch-root>/raw.csv, no flag) so the reported averages can be audited.

For deeper introspection, every bazel invocation the harness makes always has its
artifacts stored in a single flat directory (<out or scratch-root>/introspect,
wiped at the start of each run): argv/exit code (<prefix>.invocation.json), combined
stdout+stderr (<prefix>.stdout.txt), and — for builds — the BEP stream
(<prefix>.bep.jsonl), compact execution log (<prefix>.exec.log.zst) and gzipped
JSON command profile (<prefix>.profile.json.gz); measured builds also store
their parsed metrics (<prefix>.metrics.json). Each invocation's files share one
filename prefix (a sequence number plus its cell context and phase). This
instrumentation adds overhead to the measured builds, so treat introspected
numbers as a tool for understanding behaviour, not as headline figures.
"""

import argparse
import contextlib
import csv
import json
import os
import shutil
import socket
import statistics
import subprocess
import sys
import tempfile
import threading
import time
from collections import defaultdict

from humanize import naturalsize
from tabulate import tabulate

# Phase timing, so the harness's own overhead is auditable ("benchmarking the
# benchmarker"). `timed(label)` accumulates wall time by label; the driver
# snapshots it per scenario and prints a breakdown, separating time spent on the
# measured builds from warmup overhead (resets, baselines, sizing).
_TIMING = defaultdict(float)


@contextlib.contextmanager
def timed(label):
    t0 = time.monotonic()
    try:
        yield
    finally:
        _TIMING[label] += time.monotonic() - t0

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_BAZEL = os.path.join(REPO_ROOT, "bazel-bin", "src", "bazel")
# DotSlash manifest committed alongside this script; running it fetches + verifies
# the pinned NativeLink release on first use and caches it (reproducible, no
# manually-placed binary).
DEFAULT_NATIVELINK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "nativelink")

KIB = 1024
MIB = 1024 * 1024

# Per-run metric fields recorded to the raw CSV (so the averages can be audited).
RAW_KEYS = ("ok", "mis", "wall", "cpu_ms", "rss_kb", "ac_bytes", "cas_bytes",
            "out_bytes", "bep_events", "bep_bytes")

# Mechanism id -> (target name / output subdir, mnemonic, table title).
MECHANISMS = {
    "S": ("spawn", "CopySpawn", "Per-Artifact Spawns"),
    "B": ("batch", "CopyBatch", "Batched Spawns"),
    "C": ("copy", "Copy", "Copy Action"),
}
MECH_ORDER = ["S", "B", "C"]

STATES = ["cold", "10pct", "1miss", "warm"]
STATE_LABEL = {"cold": "Cold", "10pct": "10% miss", "1miss": "1 miss", "warm": "Warm"}

STRATEGIES = ["local", "remote"]

# Predefined filename for the rendered Markdown tables inside --out's directory.
RESULTS_FILENAME = "results.md"


def scenarios():
    """The four input kinds — {source, generated} × {file, directory} — each ~800 MiB.

    A scenario has `origin` (source|generated), `shape` (file|dir), `units` (the
    number of things copied), `size` (bytes per file), and, for directories,
    `files_per_dir`."""
    return [
        {"key": "src_files", "label": "200 × 4 MiB source files",
         "origin": "source", "shape": "file", "units": 200, "size": 4 * MIB},
        {"key": "gen_files", "label": "200 × 4 MiB generated files",
         "origin": "generated", "shape": "file", "units": 200, "size": 4 * MIB},
        {"key": "src_dirs", "label": "10 source dirs, 20 × 4 MiB each",
         "origin": "source", "shape": "dir", "units": 10, "files_per_dir": 20, "size": 4 * MIB},
        {"key": "gen_dirs", "label": "10 generated dirs, 20 × 4 MiB each",
         "origin": "generated", "shape": "dir", "units": 10, "files_per_dir": 20, "size": 4 * MIB},
    ]


def approx_bytes(sc):
    if sc["shape"] == "file":
        return sc["units"] * sc["size"]
    return sc["units"] * sc["files_per_dir"] * sc["size"]


# ---------------------------------------------------------------------------
# Workspace generation
# ---------------------------------------------------------------------------

def write_file(path, data):
    with open(path, "wb") as f:
        f.write(data)


def rmtree_robust(path):
    """Remove a tree, tolerating (a) Bazel's read-only output directories — a
    child can only be unlinked when its parent is writable, so restore owner-write
    everywhere first — and (b) the transient ENOTEMPTY btrfs occasionally raises
    when directory metadata lags behind concurrent unlinks. No-op if absent."""
    if not os.path.exists(path):
        return
    for attempt in range(4):
        try:
            for root, dirs, _ in os.walk(path):
                for d in dirs:
                    try:
                        os.chmod(os.path.join(root, d), 0o755)
                    except OSError:
                        pass
            os.chmod(path, 0o755)
            shutil.rmtree(path)
            return
        except FileNotFoundError:
            return
        except OSError:
            if attempt == 3:
                raise
            time.sleep(0.2)


def gen_workspace(workdir, scenario):
    """Creates a self-contained Bazel workspace for `scenario` under `workdir`."""
    t0 = time.monotonic()
    print("  preparing workspace (%s of inputs)..." % fmt_bytes(approx_bytes(scenario)),
          file=sys.stderr, end="")
    sys.stderr.flush()
    if os.path.exists(workdir):
        rmtree_robust(workdir)
    os.makedirs(os.path.join(workdir, "inputs"))
    shutil.copyfile(
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "defs.bzl"),
        os.path.join(workdir, "defs.bzl"))
    write_file(os.path.join(workdir, "MODULE.bazel"), b'module(name = "copy_bench")\n')
    write_file(os.path.join(workdir, "BUILD"), _gen_inputs_and_build(workdir, scenario).encode())
    print(" done in %s" % fmt_duration(time.monotonic() - t0), file=sys.stderr)


def _write_sized(path, size, salt):
    """Write `size` bytes (a short salted header + a shared zero tail) — distinct
    content per salt, but only the small header is rebuilt per file."""
    header = ("%s\n" % salt).encode()
    with open(path, "wb") as f:
        f.write(header[:size])
        remaining = size - len(header)
        if remaining > 0:
            f.write(_ZERO_TAIL[:remaining] if remaining <= len(_ZERO_TAIL)
                    else b"\0" * remaining)


_ZERO_TAIL = b"\0" * (4 * MIB)  # shared buffer reused across file writes


def _gen_inputs_and_build(workdir, sc):
    """Writes the scenario's inputs under inputs/ and returns the BUILD content.

    The three targets (copy/spawn/batch) use one shape-specific rule pair and take
    the same `srcs`; only what `srcs` points at differs by origin (source files,
    a source directory, or generator targets)."""
    inp = os.path.join(workdir, "inputs")
    shape, origin, units, size = sc["shape"], sc["origin"], sc["units"], sc["size"]
    suffix = "files" if shape == "file" else "dirs"
    gen_lines = []      # generator target definitions (generated scenarios)
    loads = ["batch_" + suffix, "copy_" + suffix, "spawn_" + suffix]

    if shape == "file" and origin == "source":
        for i in range(units):
            _write_sized(os.path.join(inp, "u%06d.bin" % i), size, "u%06d" % i)
        srcs = 'glob(["inputs/*.bin"])'
    elif shape == "file" and origin == "generated":
        loads.append("make_file")
        for i in range(units):
            write_file(os.path.join(inp, "seed_%06d.txt" % i), ("seed-%06d-v0\n" % i).encode())
            gen_lines.append('make_file(name = "u%06d", seed = "inputs/seed_%06d.txt", size = %d)'
                             % (i, i, size))
        srcs = "[%s]" % ", ".join('":u%06d"' % i for i in range(units))
    elif shape == "dir" and origin == "source":
        fpd = sc["files_per_dir"]
        for i in range(units):
            d = os.path.join(inp, "dir_%03d" % i)
            os.makedirs(d)
            # marker.bin is the file the driver rewrites to invalidate this dir.
            _write_sized(os.path.join(d, "marker.bin"), size, "dir%03d-marker" % i)
            for j in range(fpd - 1):
                _write_sized(os.path.join(d, "f%03d.bin" % j), size, "dir%03d-f%03d" % (i, j))
        srcs = "[%s]" % ", ".join('"inputs/dir_%03d"' % i for i in range(units))
    else:  # dir + generated
        loads.append("make_tree")
        fpd = sc["files_per_dir"]
        for i in range(units):
            write_file(os.path.join(inp, "seed_%03d.txt" % i), ("seed-%03d-v0\n" % i).encode())
            gen_lines.append(
                'make_tree(name = "u%03d", seed = "inputs/seed_%03d.txt", count = %d, size = %d)'
                % (i, i, fpd, size))
        srcs = "[%s]" % ", ".join('":u%03d"' % i for i in range(units))

    lines = ['load("//:defs.bzl", %s)' % ", ".join('"%s"' % s for s in sorted(loads)), ""]
    lines += gen_lines + [""]
    for prefix in ("copy", "spawn", "batch"):
        lines.append('%s_%s(name = "%s", srcs = %s)' % (prefix, suffix, prefix, srcs))
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# Cache-state perturbation
# ---------------------------------------------------------------------------

def _inputs_for(sc):
    """(relpath, size) per copyable unit; size is None for a seed (rewritten as text)."""
    units, size = sc["units"], sc["size"]
    if sc["shape"] == "file":
        if sc["origin"] == "source":
            return [("inputs/u%06d.bin" % i, size) for i in range(units)]
        return [("inputs/seed_%06d.txt" % i, None) for i in range(units)]  # regenerates that file
    if sc["origin"] == "source":
        return [("inputs/dir_%03d/marker.bin" % i, size) for i in range(units)]  # mutates that dir
    return [("inputs/seed_%03d.txt" % i, None) for i in range(units)]  # regenerates that tree


def perturb(workdir, sc, k, revision):
    """Rewrites `k` inputs so their content (and downstream copies) changes."""
    inputs = _inputs_for(sc)
    for idx in range(min(k, sc["units"])):
        rel, size = inputs[idx]
        path = os.path.join(workdir, rel)
        if size is None:  # a seed file
            write_file(path, ("seed-%06d-v%d\n" % (idx, revision)).encode())
        else:
            _write_sized(path, size, "%s-r%d" % (rel, revision))


def perturb_count(sc, state):
    n = sc["units"]
    if state == "1miss":
        return 1
    if state == "10pct":
        return max(1, round(0.10 * n))
    return 0


# ---------------------------------------------------------------------------
# Disk sizing
# ---------------------------------------------------------------------------

def dir_bytes(path):
    """Disk used by a directory tree, in bytes (allocated blocks; 0 if missing).

    Uses `du` (block-based st_blocks), which is consistent across mechanisms and
    always available. On btrfs, `btrfs filesystem du`'s extent accounting would
    additionally dedup reflinks, but it proved unreliable here — it reports 0 for
    some freshly-built spawn outputs (uncommitted/delalloc extents) even after a
    sync. Since the benchmark's outputs are all distinct content (no two files
    share an extent), there is nothing to double-count and `du` is exact."""
    if not path or not os.path.isdir(path):
        return 0
    r = subprocess.run(["du", "-s", "--block-size=1", path], capture_output=True, text=True)
    try:
        return int(r.stdout.split()[0])
    except (ValueError, IndexError):
        return 0


# ---------------------------------------------------------------------------
# NativeLink: a local remote-execution + caching cluster (REAPI over gRPC)
# ---------------------------------------------------------------------------

# CAS + AC + scheduler + one local worker in a single process, with
# filesystem-backed stores under %(data)s so AC/CAS space is measurable and
# wipeable. Distilled from NativeLink's local_rbe_self_test.json5.
NATIVELINK_CONFIG = """{
  stores: [
    { name: "CAS_MAIN_STORE", filesystem: {
        content_path: "%(data)s/cas", temp_path: "%(data)s/cas-tmp",
        eviction_policy: { max_bytes: 21474836480 } } },
    { name: "AC_MAIN_STORE", filesystem: {
        content_path: "%(data)s/ac", temp_path: "%(data)s/ac-tmp",
        eviction_policy: { max_bytes: 4294967296 } } },
    { name: "WORKER_FAST_SLOW_STORE", fast_slow: {
        fast: { filesystem: { content_path: "%(data)s/worker", temp_path: "%(data)s/worker-tmp",
          eviction_policy: { max_bytes: 21474836480 } } },
        slow: { ref_store: { name: "CAS_MAIN_STORE" } } } },
  ],
  schedulers: [ { name: "MAIN_SCHEDULER", simple: { supported_platform_properties: {
      cpu_count: "minimum", OSFamily: "priority", "container-image": "priority" } } } ],
  workers: [ { local: {
      worker_api_endpoint: { uri: "grpc://127.0.0.1:%(worker_port)d" },
      cas_fast_slow_store: "WORKER_FAST_SLOW_STORE",
      upload_action_result: { ac_store: "AC_MAIN_STORE" },
      work_directory: "%(data)s/work",
      platform_properties: {
        cpu_count: { values: ["1"] }, OSFamily: { values: [""] },
        "container-image": { values: [""] } } } } ],
  servers: [
    { name: "local", listener: { http: { socket_address: "0.0.0.0:%(cas_port)d" } }, services: {
        cas: [ { cas_store: "CAS_MAIN_STORE" } ], ac: [ { ac_store: "AC_MAIN_STORE" } ],
        bytestream: [ { cas_store: "CAS_MAIN_STORE" } ],
        execution: [ { cas_store: "CAS_MAIN_STORE", scheduler: "MAIN_SCHEDULER" } ],
        capabilities: [ { remote_execution: { scheduler: "MAIN_SCHEDULER" } } ] } },
    { name: "worker_api", listener: { http: { socket_address: "0.0.0.0:%(worker_port)d" } },
      services: { worker_api: { scheduler: "MAIN_SCHEDULER" }, health: {} } },
  ],
  global: { max_open_files: 24576 },
}
"""


class NativeLink:
    """Manages a local NativeLink RBE server for the `remote` strategy.

    Real remote execution and caching over gRPC — spawns run on the worker (an
    `Execute` call + `ActionResult` + CAS round-trips), while the non-spawn copy
    action still runs locally. Stores are on-disk so AC/CAS space is measurable."""

    def __init__(self, binary, data_dir, cas_port, worker_port):
        self.binary = binary
        self.data_dir = data_dir
        self.cas_port = cas_port
        self.worker_port = worker_port
        self.config_path = os.path.join(data_dir, "config.json5")
        self.log_path = os.path.join(data_dir, "server.log")
        self._proc = None

    def endpoint(self):
        return "grpc://127.0.0.1:%d" % self.cas_port

    def _listening(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(0.3)
            return s.connect_ex(("127.0.0.1", self.cas_port)) == 0

    def _wait(self, listening, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self._listening() == listening:
                return True
            time.sleep(0.2)
        return False

    def start(self):
        os.makedirs(self.data_dir, exist_ok=True)
        with open(self.config_path, "w") as f:
            f.write(NATIVELINK_CONFIG % {"data": self.data_dir, "cas_port": self.cas_port,
                                         "worker_port": self.worker_port})
        logf = open(self.log_path, "w")
        self._proc = subprocess.Popen([self.binary, self.config_path],
                                      stdout=logf, stderr=subprocess.STDOUT)
        if not self._wait(listening=True):
            raise RuntimeError("NativeLink failed to listen on :%d — see %s"
                               % (self.cas_port, self.log_path))

    def stop(self):
        if self._proc is not None:
            self._proc.terminate()
            try:
                self._proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self._proc.kill()
                self._proc.wait(timeout=5)
            self._proc = None
        self._wait(listening=False)  # let the port free before any rebind

    def reset(self):
        """Return to a cold cache: stop, wipe the on-disk stores, restart. A
        running server keeps its store index in memory, so the stores cannot be
        wiped in place — a restart is required. Bazel's gRPC channel reconnects
        to the fresh server on its next RPC."""
        self.stop()
        for sub in ("cas", "ac", "worker", "cas-tmp", "ac-tmp", "worker-tmp", "work"):
            shutil.rmtree(os.path.join(self.data_dir, sub), ignore_errors=True)
        self.start()

    def cache_bytes(self):
        """(ac_bytes, cas_bytes) currently occupied by the on-disk stores."""
        return (dir_bytes(os.path.join(self.data_dir, "ac")),
                dir_bytes(os.path.join(self.data_dir, "cas")))


# ---------------------------------------------------------------------------
# Bazel invocation
# ---------------------------------------------------------------------------

class Introspector:
    """Stores every bazel invocation's artifacts in a single flat directory, for
    offline introspection. Always on (no flag): the directory is wiped and
    recreated at the start of each run (see `main`), so it reflects only the run
    just made.

    Every invocation gets one filename prefix — a global sequence number plus its
    cell context and phase, e.g. `00042-src_files-local-cold-S-rep01-measured` —
    shared by all of its output files (same directory, distinguished by suffix):

      * .invocation.json - argv, context, phase, exit code, wall time
      * .stdout.txt      - combined stdout + stderr
      and for `build` invocations additionally:
      * .bep.jsonl       - the build-event-protocol stream
      * .exec.log.zst    - the compact execution log (--execution_log_compact_file)
      * .profile.json.gz - the gzipped JSON command profile (--profile)
      and for measured builds:
      * .metrics.json    - the run's parsed metrics, as reported to the tables/CSV

    Note the instrumentation itself (BEP for every build, execution log, profile)
    adds overhead to every measured build; introspected numbers are for
    understanding behaviour, not headline figures."""

    def __init__(self, root):
        self.root = os.path.abspath(root)
        self.seq = 0
        os.makedirs(self.root, exist_ok=True)

    def new_prefix(self, context, phase):
        self.seq += 1
        return os.path.join(self.root, "%05d-%s-%s" % (self.seq, context or "global", phase))


class Bazel:
    def __init__(self, binary, workdir, output_base, strategy, introspector, disk_cache=None,
                 nativelink=None):
        self.binary = binary
        self.workdir = workdir
        self.output_base = output_base
        self.strategy = strategy
        self.disk_cache = disk_cache      # local strategy: a real --disk_cache
        self.nativelink = nativelink      # remote strategy: NativeLink RBE
        self.introspector = introspector  # per-invocation artifact store (mandatory)
        self.context = ""                 # current cell label, set by the driver
        self.last_inv_prefix = None       # introspection filename prefix of the last invocation
        self.last_bep_path = None         # BEP file of the last build invocation
        self._server_pid = None
        self._bin_path = None

    def bin_path(self):
        """`bazel-bin`, cached. A mechanism's outputs live under bazel-bin/<target>."""
        if self._bin_path is None:
            r = self.run(["info", "bazel-bin"], phase="info")
            lines = r.stdout.strip().splitlines() if r.returncode == 0 else []
            self._bin_path = lines[-1] if lines else os.path.join(
                self.output_base, "execroot", "_main", "bazel-out", "k8-fastbuild", "bin")
        return self._bin_path

    def cache_bytes(self):
        """(ac_bytes, cas_bytes) occupied by the strategy's cache.

        local  -> the `ac`/`cas` subtrees of the --disk_cache (a typical Bazel
                  setup caches locally-executed actions to disk).
        remote -> NativeLink's on-disk stores."""
        if self.strategy == "remote":
            return self.nativelink.cache_bytes()
        if self.disk_cache:
            return (dir_bytes(os.path.join(self.disk_cache, "ac")),
                    dir_bytes(os.path.join(self.disk_cache, "cas")))
        return (0, 0)

    def _startup(self):
        return [self.binary, "--output_base=" + self.output_base]

    def _strategy_flags(self):
        if self.strategy == "local":
            # Typical local setup: execute locally, cache to a local disk cache.
            return ["--disk_cache=" + self.disk_cache]
        # Real remote execution + caching via NativeLink, with Build without the
        # Bytes. cpu_count matches the worker's advertised platform property.
        return ["--remote_executor=" + self.nativelink.endpoint(),
                "--remote_download_outputs=minimal",
                "--remote_default_exec_properties=cpu_count=1"]

    def run(self, args, phase=None):
        """Runs one bazel invocation. Its artifacts are always stored under a
        shared filename prefix (see Introspector): argv/exit code
        (.invocation.json), combined stdout+stderr (.stdout.txt), and for builds
        the BEP, compact execution log, and command profile (the BEP flag is
        redirected there; exec log/profile flags added)."""
        phase = phase or (args[0] if args else "misc")
        self.last_bep_path = next(
            (a.split("=", 1)[1] for a in args if a.startswith("--build_event_json_file=")), None)
        prefix = self.introspector.new_prefix(self.context, phase)
        if args and args[0] == "build":
            bep = prefix + ".bep.jsonl"
            if self.last_bep_path is None:
                args = args + ["--build_event_json_file=" + bep]
            else:
                args = [("--build_event_json_file=" + bep)
                        if a.startswith("--build_event_json_file=") else a for a in args]
            self.last_bep_path = bep
            args = args + [
                "--execution_log_compact_file=" + prefix + ".exec.log.zst",
                "--profile=" + prefix + ".profile.json.gz",
            ]
        self.last_inv_prefix = prefix
        cmd = self._startup() + args
        t0 = time.monotonic()
        r = subprocess.run(cmd, cwd=self.workdir, text=True,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        write_file(prefix + ".stdout.txt", (r.stdout or "").encode())
        with open(prefix + ".invocation.json", "w") as f:
            json.dump({"argv": cmd, "context": self.context, "phase": phase,
                       "exit_code": r.returncode,
                       "wall_s": round(time.monotonic() - t0, 3)}, f, indent=2)
        return r

    def build(self, target, bep_path=None, phase="build"):
        args = ["build", "//:" + target, "--experimental_copy_action"] + self._strategy_flags()
        if bep_path:
            # Publish every action so the BEP event count reflects per-action volume
            # (the regime the proposal's BEP comparison uses); otherwise Bazel emits
            # only a handful of structural events regardless of the action count.
            args += ["--build_event_json_file=" + bep_path, "--build_event_publish_all_actions"]
        return self.run(args, phase=phase)

    def analysis_ok(self, target):
        """Whether `target` passes loading + analysis (a cheap `--nobuild`), so
        the driver can report a mechanism that is an analysis error for a scenario
        as `—` without running — and spamming the log with — a doomed build per
        repeat. (No current scenario/mechanism combination fails analysis; this
        guards benchmark configurations against older Bazels and future rules.)"""
        r = self.run(["build", "//:" + target, "--experimental_copy_action", "--nobuild"],
                     phase="probe-" + target)
        return r.returncode == 0

    def clean(self):
        self.run(["clean"], phase="clean")

    def cold_reset(self):
        """Cold state: wipe the local output tree and the strategy's cache, so
        OUT/AC/CAS reflect a first build rather than hits against a warm cache."""
        with timed("clean"):
            self.clean()
        if self.strategy == "remote":
            with timed("nl-reset"):
                self.nativelink.reset()
        elif self.disk_cache and os.path.isdir(self.disk_cache):
            with timed("cache-wipe"):
                shutil.rmtree(self.disk_cache, ignore_errors=True)

    def shutdown(self):
        self.run(["shutdown"], phase="shutdown")

    def server_pid(self):
        if self._server_pid is None:
            r = self.run(["info", "server_pid"], phase="info")
            try:
                self._server_pid = int(r.stdout.strip().splitlines()[-1])
            except (ValueError, IndexError):
                self._server_pid = 0
        return self._server_pid


def read_vm_rss_kb(pid):
    try:
        with open("/proc/%d/status" % pid) as f:
            for line in f:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1])
    except OSError:
        pass
    return 0


class RssSampler(threading.Thread):
    def __init__(self, pid, interval=0.02):
        super().__init__(daemon=True)
        self.pid = pid
        self.interval = interval
        self.peak_kb = 0
        self._stop_event = threading.Event()

    def run(self):
        while not self._stop_event.is_set():
            self.peak_kb = max(self.peak_kb, read_vm_rss_kb(self.pid))
            time.sleep(self.interval)

    def stop(self):
        self._stop_event.set()
        self.join(timeout=2)
        self.peak_kb = max(self.peak_kb, read_vm_rss_kb(self.pid))
        return self.peak_kb


# ---------------------------------------------------------------------------
# Measurement
# ---------------------------------------------------------------------------

def parse_bep(bep_path, mnemonic):
    """From the BEP: per-mnemonic executed actions, CPU ms, and total event count."""
    mis, cpu_ms, events = 0, 0, 0
    try:
        with open(bep_path) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                events += 1
                try:
                    ev = json.loads(line)
                except ValueError:
                    continue
                bm = ev.get("buildMetrics")
                if not bm:
                    continue
                for ad in bm.get("actionSummary", {}).get("actionData", []):
                    if ad.get("mnemonic") == mnemonic:
                        mis += int(ad.get("actionsExecuted", 0))
                cpu_ms = int(bm.get("timingMetrics", {}).get("cpuTimeInMs", 0))
    except OSError:
        pass
    return {"mis": mis, "cpu_ms": cpu_ms, "bep_events": events}


def measure(bazel, target, mnemonic, bep_dir, footprint=True):
    """Runs one measured build. `footprint=False` skips the disk-footprint sizing
    (AC/CAS/OUT), used for the timing-only resample repeats where the cache has
    intentionally drifted — see `collect_runs`."""
    bep_path = os.path.join(bep_dir, "bep.json")
    if os.path.exists(bep_path):
        os.remove(bep_path)
    pid = bazel.server_pid()
    sampler = RssSampler(pid) if pid else None
    if sampler:
        sampler.start()
    t0 = time.monotonic()
    with timed("build"):
        result = bazel.build(target, bep_path=bep_path, phase="measured")
    wall = time.monotonic() - t0
    peak_rss_kb = sampler.stop() if sampler else 0
    # Capture before the sizing calls below make further (info) invocations.
    bep_path = bazel.last_bep_path or bep_path
    inv_prefix = bazel.last_inv_prefix
    metrics = parse_bep(bep_path, mnemonic)
    metrics["wall"] = wall
    metrics["rss_kb"] = peak_rss_kb
    metrics["bep_bytes"] = os.path.getsize(bep_path) if os.path.exists(bep_path) else 0
    if footprint:
        # Absolute footprints after the build.
        with timed("sizing"):
            metrics["out_bytes"] = dir_bytes(os.path.join(bazel.bin_path(), target))
            metrics["ac_bytes"], metrics["cas_bytes"] = bazel.cache_bytes()
    else:
        metrics["out_bytes"] = metrics["ac_bytes"] = metrics["cas_bytes"] = None
    metrics["ok"] = result.returncode == 0
    if not metrics["ok"]:
        metrics["log"] = (result.stdout or "")[-2000:]
    # The same figures that feed the tables/raw CSV, alongside the run's
    # BEP/exec log/profile, so a stored invocation is self-describing.
    with open(inv_prefix + ".metrics.json", "w") as f:
        json.dump({"context": bazel.context, **metrics}, f, indent=2)
    return metrics


def collect_runs(bazel, scenario, target, mnemonic, state, bep_dir, next_rev, repeats):
    """Measures one cell (state × mechanism) over `repeats` runs from a SINGLE setup.

    The footprint metrics (AC/CAS/OUT and the per-mnemonic counts) are
    deterministic, so only the first build needs to run against the precise
    cold/warm/miss cache setup — it alone contributes them. The remaining repeats
    re-execute cheaply to resample the noisy timing metrics (wall/CPU/RSS):

      * cold — perturb *every* input to fresh content each repeat, forcing a full
        re-execution (a warm cache would otherwise turn the rebuild into a hit and
        mis-time it, both locally via --disk_cache and remotely);
      * miss — perturb the miss-set (1 or 10%) to fresh content, so exactly those
        actions re-run, as in the real partial-miss build;
      * warm — rebuild as-is against the populated cache (every action a hit).

    The first build likewise perturbs (cold/miss) but runs against the just-reset
    cache, so its footprint equals the from-cold absolute footprint measured by the
    previous per-repeat scheme. This turns O(cells × repeats) warmup into O(cells):
    one reset (+ one baseline for the non-cold states) per cell instead of per run."""
    k = perturb_count(scenario, state)
    base_ctx = bazel.context
    bazel.context = base_ctx + "-setup"
    bazel.cold_reset()
    if state != "cold":
        with timed("baseline"):
            # populate outputs + cache for the canonical content
            bazel.build(target, phase="baseline")
    runs = []
    for rep in range(repeats):
        bazel.context = "%s-rep%02d" % (base_ctx, rep + 1)
        if state == "cold":
            perturb(bazel.workdir, scenario, scenario["units"], next_rev())
        elif state != "warm":
            perturb(bazel.workdir, scenario, k, next_rev())
        m = measure(bazel, target, mnemonic, bep_dir, footprint=(rep == 0))
        runs.append(m)
        if not m.get("ok"):
            break
    bazel.context = base_ctx
    return runs


# Timing metrics are noisy → averaged over repeats; footprint/count metrics are
# deterministic → taken from the first run (the only one measured against the
# correct cache setup; see `collect_runs`).
_TIMING_KEYS = ("wall", "cpu_ms", "rss_kb")
_FOOTPRINT_KEYS = ("mis", "bep_events", "bep_bytes", "ac_bytes", "cas_bytes", "out_bytes")


def aggregate(runs):
    """Combine a cell's repeats: mean timing, first-run footprint/counts."""
    ok = [r for r in runs if r.get("ok")]
    if not ok:
        return runs[0] if runs else None
    agg = {"ok": True}
    for key in _TIMING_KEYS:
        agg[key] = statistics.fmean(r[key] for r in ok)
    for key in _FOOTPRINT_KEYS:
        agg[key] = ok[0][key]
    return agg


# ---------------------------------------------------------------------------
# Formatting & rendering
# ---------------------------------------------------------------------------

def fmt_count(x):
    return "—" if x is None else "%d" % round(x)


def fmt_bytes(x):
    if x is None:
        return "—"
    return naturalsize(x, binary=True, format="%.1f").replace("Bytes", "B").replace("Byte", "B")


def fmt_duration(seconds):
    """Scale-aware duration: s / ms / µs / ns."""
    if seconds is None:
        return "—"
    if seconds >= 1:
        return "%.2f s" % seconds
    ms = seconds * 1e3
    if ms >= 1:
        return "%.0f ms" % ms
    us = seconds * 1e6
    if us >= 1:
        return "%.0f µs" % us
    return "%.0f ns" % (seconds * 1e9)


def _signed(fmt, delta):
    """Signed magnitude, e.g. '-3.27 s' / '+80.0 MiB' / '+0 B'."""
    return ("-" if delta < 0 else "+") + fmt(abs(delta))


def fmt_elapsed(seconds):
    """Coarse elapsed wall time for progress lines: '2m 34s' / '48s'."""
    seconds = round(seconds)
    return "%dm %02ds" % (seconds // 60, seconds % 60) if seconds >= 60 else "%ds" % seconds


# Phase labels split into "measured builds" vs "warmup overhead", so the
# breakdown makes plain how much time is the thing we care about (`build`) versus
# harness overhead. Order is display order; unknown labels sort last.
_BUILD_PHASES = ("build",)
_PHASE_ORDER = ("build", "baseline", "server-warm", "clean", "nl-reset",
                "cache-wipe", "sizing", "genws")


def fmt_breakdown(timing):
    """Render a phase-time dict as 'build 41s, nl-reset 2m 10s, …', measured
    builds first, plus an overhead:build ratio so creep is obvious at a glance."""
    parts = [(k, timing[k]) for k in _PHASE_ORDER if timing.get(k)]
    parts += [(k, v) for k, v in sorted(timing.items()) if k not in _PHASE_ORDER and v]
    body = ", ".join("%s %s" % (k, fmt_elapsed(v)) for k, v in parts)
    build = sum(timing.get(k, 0.0) for k in _BUILD_PHASES)
    overhead = sum(v for k, v in timing.items() if k not in _BUILD_PHASES)
    if build:
        body += "  [overhead/build %.1f×]" % (overhead / build)
    return body


# Each metric: (header, extract value, format value, format a signed delta).
METRICS = [
    ("Cache Misses", lambda m: m["mis"], fmt_count, lambda d: "%+d" % round(d)),
    ("Wall", lambda m: m["wall"], fmt_duration, lambda d: _signed(fmt_duration, d)),
    ("CPU Time", lambda m: m["cpu_ms"] / 1000.0, fmt_duration, lambda d: _signed(fmt_duration, d)),
    ("Peak RSS", lambda m: m["rss_kb"] * 1024, fmt_bytes, lambda d: _signed(fmt_bytes, d)),
    ("AC", lambda m: m["ac_bytes"], fmt_bytes, lambda d: _signed(fmt_bytes, d)),
    ("CAS", lambda m: m["cas_bytes"], fmt_bytes, lambda d: _signed(fmt_bytes, d)),
    ("OUT", lambda m: m["out_bytes"], fmt_bytes, lambda d: _signed(fmt_bytes, d)),
    ("BEP", lambda m: m["bep_events"], fmt_count, lambda d: "%+d" % round(d)),
    ("BEP Size", lambda m: m["bep_bytes"], fmt_bytes, lambda d: _signed(fmt_bytes, d)),
]
HEADERS = ["Content", "Strategy", "Cache"] + [h for h, _, _, _ in METRICS]


def _ok(m):
    return m is not None and m.get("ok", False)


def _cells(m, others=()):
    """Formatted metric cells for `m`. For each `others` (a spawn baseline), append
    the signed delta (this − baseline) so the Copy Action table shows the change
    vs the per-artifact and batched spawns, e.g. '1.29 s (-3.27 s, -0.34 s)'."""
    if not _ok(m):
        return ["—"] * len(METRICS)
    cells = []
    for _, val, fval, fdelta in METRICS:
        s = fval(val(m))
        if others:
            deltas = [(fdelta(val(m) - val(o)) if _ok(o) else "—") for o in others]
            s += "<br> (%s)" % ", ".join(deltas)
        cells.append(s)
    return cells


def render(results, scenario_list, strategies, states, impls):
    out = []
    for impl in impls:
        out.append("**%s**\n" % MECHANISMS[impl][2])
        rows = []
        for sc in scenario_list:
            first_content = True
            for strat in strategies:
                first_strat = True
                for state in states:
                    cell = results.get(sc["key"], {}).get(strat, {}).get(state, {})
                    m = cell.get(impl)
                    # The Copy Action table annotates each value with its change
                    # versus the per-artifact (S) and batched (B) spawns.
                    others = ([cell.get("S"), cell.get("B")] if impl == "C" else [])
                    rows.append([sc["label"] if first_content else "",
                                 strat if first_strat else "",
                                 STATE_LABEL[state]] + _cells(m, others))
                    first_content = first_strat = False
        out.append(tabulate(rows, headers=HEADERS, tablefmt="github"))
        out.append("")
    return "\n".join(out)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--bazel", default=DEFAULT_BAZEL, help="Bazel built from this branch.")
    p.add_argument("--scratch-root", default="/mnt/ephemeral/bazel-copy-bench",
                   help="Parent for this run's scratch. A unique run-XXXX/ subdir "
                        "(generated workspaces + output bases) is created here and "
                        "removed on exit — so concurrent or repeated runs never "
                        "collide on a shared path. Keep it on a copy-on-write "
                        "filesystem so OUT reflects reflink sharing.")
    p.add_argument("--keep-scratch", action="store_true",
                   help="Keep the run's scratch dir instead of removing it on exit.")
    p.add_argument("--workdir", default="",
                   help="Override the generated-workspaces dir (default: under the "
                        "unique run dir in --scratch-root).")
    p.add_argument("--output-base-root", default="",
                   help="Override the per-(scenario,strategy) output-base parent "
                        "(default: likewise under the run dir).")
    p.add_argument("--nativelink", default=DEFAULT_NATIVELINK,
                   help="DotSlash manifest (or binary) backing the 'remote' strategy's RBE "
                        "server; defaults to the committed ./nativelink manifest.")
    p.add_argument("--nativelink-port", type=int, default=50051,
                   help="Client-facing gRPC port for NativeLink (worker API uses port+10).")
    p.add_argument("--scenarios", default="", help="Comma-separated scenario keys (default: all).")
    p.add_argument("--strategies", default=",".join(STRATEGIES), help="local,remote")
    p.add_argument("--impls", default=",".join(MECH_ORDER), help="S,B,C")
    p.add_argument("--states", default=",".join(STATES), help="cold,10pct,1miss,warm")
    p.add_argument("--repeats", type=int, default=10, help="Runs per cell, averaged.")
    p.add_argument("--out", default="", metavar="DIR",
                   help="Write the Markdown tables to DIR/%s (created if needed) instead of "
                        "stdout." % RESULTS_FILENAME)
    p.add_argument("--quick", action="store_true", help="Tiny scenarios + 2 repeats for a smoke test.")
    args = p.parse_args()

    if not os.path.exists(args.bazel):
        sys.exit("Bazel binary not found: %s (build //src:bazel first, or pass --bazel)"
                 % args.bazel)

    scen = scenarios()
    repeats = args.repeats
    if args.quick:
        scen = [
            {"key": "src_files", "label": "10 × 4 MiB source files",
             "origin": "source", "shape": "file", "units": 10, "size": 4 * MIB},
            {"key": "gen_files", "label": "10 × 4 MiB generated files",
             "origin": "generated", "shape": "file", "units": 10, "size": 4 * MIB},
            {"key": "src_dirs", "label": "2 source dirs, 5 × 4 MiB each",
             "origin": "source", "shape": "dir", "units": 2, "files_per_dir": 5, "size": 4 * MIB},
            {"key": "gen_dirs", "label": "2 generated dirs, 5 × 4 MiB each",
             "origin": "generated", "shape": "dir", "units": 2, "files_per_dir": 5, "size": 4 * MIB},
        ]
        repeats = min(repeats, 2)
    if args.scenarios:
        wanted = set(args.scenarios.split(","))
        scen = [s for s in scen if s["key"] in wanted]
    strategies = [s for s in STRATEGIES if s in args.strategies.split(",")]
    impls = [m for m in MECH_ORDER if m in args.impls.split(",")]
    states = [s for s in STATES if s in args.states.split(",")]

    # Isolate this run's scratch in a unique dir so repeated or concurrent runs
    # never collide on a shared path (the root cause of stale-state cleanup
    # failures). Overriding both --workdir and --output-base-root opts out.
    run_scratch = None
    os.makedirs(args.scratch_root, exist_ok=True)
    if args.workdir and args.output_base_root:
        workdir_root, ob_root = args.workdir, args.output_base_root
    else:
        run_scratch = tempfile.mkdtemp(prefix="run-", dir=args.scratch_root)
        workdir_root = args.workdir or os.path.join(run_scratch, "ws")
        ob_root = args.output_base_root or os.path.join(run_scratch, "ob")
    os.makedirs(ob_root, exist_ok=True)

    nativelink = None
    if "remote" in strategies:
        if not os.path.exists(args.nativelink):
            sys.exit("NativeLink launcher not found: %s (expected the committed DotSlash "
                     "manifest; pass --nativelink or drop the remote strategy)." % args.nativelink)
        nativelink = NativeLink(args.nativelink,
                                os.path.join(ob_root, "nativelink"),
                                cas_port=args.nativelink_port,
                                worker_port=args.nativelink_port + 10)

    # Always on, single flat directory under --out (falling back to
    # --scratch-root when --out isn't given, so introspection has somewhere to
    # go even when the tables are just printed to stdout). Not removed by
    # --keep-scratch's absence — it's the point of introspection that it
    # survives — and wiped at the start of each run so it reflects only the run
    # just made, rather than accumulating stale files from differently-shaped runs.
    out_dir = args.out or args.scratch_root
    os.makedirs(out_dir, exist_ok=True)
    introspect_dir = os.path.join(out_dir, "introspect")
    rmtree_robust(introspect_dir)
    introspector = Introspector(introspect_dir)

    total_cells = len(scen) * len(strategies) * len(states) * len(impls)
    if run_scratch:
        print("scratch: %s%s" % (run_scratch,
              "" if args.keep_scratch else " (removed on exit; keep with --keep-scratch)"),
              file=sys.stderr)
    print("introspection: per-invocation artifacts (BEP, stdout, execution log, command "
          "profile, metrics) stored under %s" % introspector.root, file=sys.stderr)
    print("averaging %d run(s) per cell over %d cells%s" % (repeats, total_cells,
          "; remote strategy backed by NativeLink RBE" if nativelink else ""), file=sys.stderr)

    results = {}
    raw_rows = []
    # Monotonic revision stamp for perturbations, so every rewrite produces content
    # never seen before (a genuine cache miss) across the whole run.
    revision = [0]

    def next_rev():
        revision[0] += 1
        return revision[0]

    cell = 0
    try:
        for sc in scen:
            results.setdefault(sc["key"], {})
            ws = os.path.join(workdir_root, sc["key"])
            print("=== scenario %s ===" % sc["key"], file=sys.stderr)
            sc_t0 = time.monotonic()
            sc_timing0 = dict(_TIMING)
            with timed("genws"):
                gen_workspace(ws, sc)
            for strat in strategies:
                results[sc["key"]].setdefault(strat, {})
                ob = os.path.join(ob_root, "%s-%s" % (sc["key"], strat))
                disk_cache = os.path.join(ob_root, "diskcache-%s" % sc["key"])
                # Bring NativeLink up only while the remote strategy runs (it would
                # otherwise idle through the whole local strategy).
                if strat == "remote":
                    nativelink.start()
                bazel = Bazel(args.bazel, ws, ob, strat, disk_cache=disk_cache,
                              nativelink=nativelink if strat == "remote" else None,
                              introspector=introspector)
                bazel.context = "%s-%s" % (sc["key"], strat)
                # Start the server (JVM boot) and cache its pid outside any timed
                # build. A full warm-up build would only be thrown away by the
                # first cell's cold reset, so an `info` call suffices.
                with timed("server-warm"):
                    bazel.server_pid()
                # Skip mechanisms that fail analysis for this scenario (copy on a
                # source directory) up front — one --nobuild instead of a doomed
                # build (× repeats × states); they render as `—`.
                supported = {}
                for impl in impls:
                    supported[impl] = bazel.analysis_ok(MECHANISMS[impl][0])
                    if not supported[impl]:
                        print("  %s (%s): analysis error for this scenario — reporting —"
                              % (impl, MECHANISMS[impl][2]), file=sys.stderr)
                for state in states:
                    results[sc["key"]][strat].setdefault(state, {})
                    for impl in impls:
                        target, mnemonic, _ = MECHANISMS[impl]
                        cell += 1
                        if not supported[impl]:
                            results[sc["key"]][strat][state][impl] = None
                            continue
                        bazel.context = "%s-%s-%s-%s" % (sc["key"], strat, state, impl)
                        runs = collect_runs(bazel, sc, target, mnemonic, state, ob,
                                            next_rev, repeats)
                        for rep, m in enumerate(runs):
                            raw_rows.append({"scenario": sc["key"], "strategy": strat,
                                             "state": state, "impl": impl, "run": rep + 1,
                                             **{k: m.get(k) for k in RAW_KEYS}})
                        if runs and not runs[-1].get("ok"):
                            print("  ! %s/%s/%s/%s FAILED\n%s"
                                  % (sc["key"], strat, state, impl, runs[-1].get("log", "")),
                                  file=sys.stderr)
                        agg = aggregate(runs)
                        results[sc["key"]][strat][state][impl] = agg
                        print("  [%d/%d] %-16s %-6s %-8s %s: MIS=%s wall=%s CPU=%s RSS=%s "
                              "AC=%s CAS=%s OUT=%s BEP=%s/%s"
                              % (cell, total_cells, sc["key"], strat, state, impl,
                                 fmt_count(agg["mis"]), fmt_duration(agg["wall"]),
                                 fmt_duration(agg["cpu_ms"] / 1000.0), fmt_bytes(agg["rss_kb"] * 1024),
                                 fmt_bytes(agg["ac_bytes"]), fmt_bytes(agg["cas_bytes"]),
                                 fmt_bytes(agg["out_bytes"]), fmt_count(agg["bep_events"]),
                                 fmt_bytes(agg["bep_bytes"])), file=sys.stderr)
                bazel.shutdown()
                if strat == "remote":
                    nativelink.stop()
            sc_spent = {k: _TIMING[k] - sc_timing0.get(k, 0.0) for k in _TIMING}
            print("--- %s: collected in %s (%s) ---"
                  % (sc["key"], fmt_elapsed(time.monotonic() - sc_t0),
                     fmt_breakdown(sc_spent)), file=sys.stderr)
    finally:
        if nativelink:
            nativelink.stop()

    print("total phase breakdown: %s" % fmt_breakdown(_TIMING), file=sys.stderr)

    table = render(results, scen, strategies, states, impls)
    if args.out:
        os.makedirs(args.out, exist_ok=True)
        out_path = os.path.join(args.out, RESULTS_FILENAME)
        with open(out_path, "w") as f:
            f.write(table + "\n")
        print("wrote %s" % out_path, file=sys.stderr)
    else:
        print("\n" + table)

    # Always written (no flag), alongside introspect/ under --out (falling back
    # to --scratch-root when --out isn't given), so every run's per-run figures
    # are there to audit the reported averages against.
    raw_path = os.path.join(out_dir, "raw.csv")
    with open(raw_path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["scenario", "strategy", "state", "impl", "run"]
                           + list(RAW_KEYS))
        w.writeheader()
        w.writerows(raw_rows)
    print("wrote %d raw run rows to %s" % (len(raw_rows), raw_path), file=sys.stderr)

    # Remove this run's scratch now that the outputs (--out, raw.csv, introspect/)
    # are written. Left in place on an exception above (never reached) so a
    # failed run stays debuggable.
    if run_scratch and not args.keep_scratch:
        rmtree_robust(run_scratch)


if __name__ == "__main__":
    main()
