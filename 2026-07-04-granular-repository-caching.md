---
created: 2026-07-04
last updated: 2026-07-04
status: Draft
reviewers: []
title: Granular Repository Caching
authors:
  - Silic0nS0ldier
discussion thread: TBD
---

# Abstract

Repository fetching is cached at whole-repository granularity: either a fetched repository
directory can be reused as-is, or the repository rule reruns from scratch — re-downloading,
re-extracting and re-executing everything it does. This proposal makes the *individual
operations* a repository rule performs (`execute`, `download`, `download_and_extract`,
`extract`, `file`) cacheable through the standard disk and remote caches, using a trust model
compatible with hardened remote execution deployments where action cache entries may only be
produced by the remote execution service.

The repository rule's implementation function still runs on every fetch; what changes is that
its expensive operations become cache lookups. A change to one input of a repository (say, a
patch file used by `http_archive`) reruns the fetch, but the download is served from the CAS
and the extraction replays from the cache — only the patch step does real work. `ctx.execute`
becomes a genuine remote-executable action whose result — including all file changes in the
repository directory — is produced (and cached) by the remote execution service, never
fabricated by the client.

# Background

## How repository fetching is cached today

Several mechanisms exist, all of them either whole-repository or download-only:

* **The repository (download) cache** (`--repository_cache`) stores downloaded files
  content-addressed by their declared checksum. It avoids re-downloading, locally.
* **The repo contents cache** (`--repo_contents_cache`) stores entire fetched repository
  directories keyed by a "predeclared inputs hash" (rule definition, attributes, starlark
  semantics, …) plus recorded inputs. On a hit, the whole fetch is skipped; on any miss, the
  whole rule reruns and every operation inside it does real work.
* **The remote repo contents cache** (`--experimental_remote_repo_contents_cache`) extends the
  same design to the remote cache: repository directories are stored as action cache entries
  for a synthetic action, uploaded by the client.
* **`--experimental_repo_remote_exec`** allows `ctx.execute` in rules marked `remotable = True`
  to run remotely, but supports no output files — nothing the command writes reaches the
  repository directory — which excludes nearly all real uses.
* **`--remote_downloader`** delegates downloads to a Remote Asset API service, which resolves
  an integrity checksum to a CAS blob.

Extraction — often the most expensive part of a fetch after the download — is never cached
anywhere: `DecompressorValue` re-extracts on every fetch.

## The trust constraint

Hardened remote execution deployments commonly reject action cache writes from clients: an AC
entry maps an action key to arbitrary output digests, and nothing about it is verifiable after
the fact, so a compromised or malicious client could poison the cache for every other user.
In such deployments AC entries are only accepted from the remote execution service itself
(which produced the outputs and is trusted), while CAS writes remain open to clients because
CAS entries are content-addressed and therefore self-verifying.

This constraint is what rules out the remote repo contents cache in these environments: its
entries are AC entries fabricated by the client (gated on `--remote_upload_local_results`).
With uploads disabled, the cache is never populated and provides nothing.

## Why whole-repository granularity is not enough

Even where the repo contents cache is usable, its granularity makes misses expensive:

* Changing any predeclared or recorded input — a patch file, an attribute, an environment
  variable — invalidates the entire entry. The rule re-downloads and re-extracts artifacts
  whose inputs did not change.
* Repositories that are not reproducible (or not marked as such) are never cached at all,
  yet typically contain expensive deterministic sub-steps.
* Nothing short of a whole-repository hit helps another machine: there is no sharing of
  partial work.

# Proposal

A new flag, `--experimental_granular_repository_caching`, makes individual `repository_ctx`
operations participate in the disk and remote caches. Each class of operation uses the caching
mechanism whose trust requirements it can actually satisfy:

| Operation | Mechanism | Remote cache write | Remote cache read |
|---|---|---|---|
| `ctx.execute` | Real REAPI action: repository directory in, repository directory out | Only by the remote execution service | Yes (AC) |
| `ctx.download`, download half of `download_and_extract` | CAS blob insert of integrity-verified content | Yes — content-addressed, self-verifying | Via `--remote_downloader` (Remote Asset API) |
| `ctx.file` | CAS blob insert of locally-known content | Yes — content-addressed | (future: lazy materialization) |
| `ctx.extract`, extraction half of `download_and_extract` (remote executor available) | Real REAPI action running a bundled portable extractor | Only by the remote execution service | Yes (AC) |
| `ctx.extract`, extraction half of `download_and_extract` (no remote executor) | AC entry for a synthetic action keyed by archive digest + parameters | Only with `--remote_upload_local_results` | Yes (AC) |

Repository rules with `local = True` are exempt from all of the above: they declare a
dependency on the local system, so their operations run locally and uncached, as today.

Everything below degrades gracefully: when an operation cannot be represented in the cache
model, it transparently falls back to today's local, uncached behavior. Enabling the flag
never makes a previously-working fetch fail (bugs excepted).

## `ctx.execute` as a cacheable action

When the flag is enabled (and a remote executor is configured), `ctx.execute` is modeled as an
ordinary remote execution API action:

* The current state of the repository directory is staged as the action's input tree, under a
  `repo/` prefix in the input root, and `Command.working_directory` is set to `repo`.
* The entire working directory is declared as the action's output
  (`output_directories = [""]`, an REAPI-documented special value that captures the working
  directory tree including inputs). The post-action repository directory state *is* the
  action result: files the command created, modified **and deleted** all replay correctly.
* `Label` arguments are staged as auxiliary inputs outside the `repo/` prefix (so they cannot
  collide with captured repository contents) and rewritten to `../`-relative paths; `path`
  arguments inside the repository are rewritten repository-relative.
* The action key is the standard REAPI action digest — command, environment, input tree,
  platform (`exec_properties`), timeout. Lookup goes through the combined disk/remote cache;
  on a miss the action executes remotely and the *remote service* produces the AC entry. The
  client never writes one.
* On a hit (or after execution) the captured tree is synced into the local repository
  directory, skipping downloads for files whose digests already match (typically the
  unmodified inputs).

Like the existing `remotable = True` support, the command sees only explicitly passed
environment variables — forwarding the inherited repository environment would both defeat
caching and leak host state.

### Fallbacks

The following cases fall back to local (sandboxed where possible, see below) uncached
execution:

* `local = True` repository rules (never even attempted).
* A working directory other than the repository root (REAPI output capture is relative to the
  working directory, so a command running in a subdirectory cannot capture the whole
  repository).
* `path` arguments referring outside the repository directory (they reference the local
  system).
* A repository directory state that cannot be represented as an input tree: not yet created,
  empty (REAPI requires the working directory to exist in the input tree), containing empty
  directories (commands observe them — e.g. `tar -C <dir>` into a directory created by a
  preceding `mkdir` operation — but a file-based input tree cannot express them), dangling
  symlinks, special files.
* No remote executor configured. (A future refinement could execute locally and write the
  result to the disk cache only, mirroring the extraction cache below.)

### Local execution is sandboxed

When a granular-caching-eligible `ctx.execute` runs locally — no remote executor, or one of
the fallbacks above — it runs inside a sandbox where the platform offers one, so that it
maintains the same basic property as its remotely executed counterpart: only declared inputs
are visible. Declared inputs are the repository directory, paths passed as label or `path`
arguments (made visible read-only at their real paths), explicitly passed environment
variables, and the operating system. Plain-string arguments are opaque, exactly as they are
for remote execution: a command that reads an absolute path smuggled in as a string fails the
same way locally as it would remotely, instead of silently depending on undeclared state.

* **Linux** uses the hermetic `linux-sandbox` (a true allow-list). The repository directory
  is staged via hardlinks under the sandbox root *at its own absolute path*, so after the
  sandbox pivots its root the repository is visible — and writable — at its real path, and
  absolute references to it in arguments or the environment work unchanged. Results are moved
  back afterwards. Because the sandbox operates on real files rather than an REAPI input
  tree, it also covers states the remote path cannot represent (empty directories,
  working-directory overrides).
* **macOS** uses `sandbox-exec` operating on the repository directory in place: writes are
  confined to it (and temporary directories), and reads of the workspace, the output base
  (i.e. other repositories) and the user's home directory are denied except for declared
  inputs. This is a deny-list rather than an allow-list — a fully hermetic default-deny
  Seatbelt profile is not practical — but it hides the undeclared inputs that matter in
  practice.
* Elsewhere (or when sandboxing is unusable, e.g. no user namespaces in a container), the
  command runs unsandboxed as today. Availability is probed once per server.

## Downloads and file writes as CAS inserts

A download verified against a user-provided checksum/integrity, and any `ctx.file` write, is
content whose digest the client can legitimately vouch for. After verification (or the write),
the blob is inserted into the disk and remote CAS if not already present. This is safe in
locked-down deployments precisely because the CAS is content-addressed.

These inserts are not primarily a download cache — they prime the CAS so that:

* subsequent `ctx.execute` actions using those files skip uploading them
  (`FindMissingBlobs` already knows them);
* extraction cache entries referencing the archive can be validated cheaply;
* future lazy materialization can reconstruct repository contents without refetching.

The *read* path for downloads is deliberately left to `--remote_downloader`: a CAS lookup
requires a full digest (hash **and size**), and the only sound service for resolving an
integrity hash to a digest is the Remote Asset API. Client-side CAS inserts cannot enable a
cold client to fetch by hash alone; see *Future work* for the Remote Asset Push option.
Downloads without an integrity are unverified and never inserted.

## Extraction as a remotely executed action

Extraction is a deterministic function of the archive contents and the extraction parameters
(`strip_prefix`, `strip_components`, `rename_files`, and the archive file name, which selects
the decompressor). Running it remotely poses a bootstrapping problem: repository operations
have no execution platform selection machinery, so there is no way to pick an extractor built
for the remote platform.

This is resolved by two decisions. First, Bazel bundles a **portable extraction utility**
(`repo-extractor`) among its embedded binaries, exactly like `process-wrapper` and
`linux-sandbox`. The utility is a **GraalVM native-image build of Bazel's own decompressors**
(`DecompressorValue` and friends) behind a small argument-parsing entry point — not a
reimplementation. Archive extraction of untrusted network input is a classic source of path
traversal and memory-safety vulnerabilities; wrapping the extensively-exercised in-tree
implementation means the remote action has byte-for-byte the semantics, the format support
(zip family, tar with gzip/xz/zstd/bzip2/brotli compression, single-file compression, ar/deb,
7z) and the hardening (escape and absolute-path rejection in `StripPrefixedPath`) of local
extraction, by construction. Second, the default remote platform for repository rule spawns
is **assumed to match the host OS** — the same assumption `--experimental_repo_remote_exec`
already makes for commands with no `exec_properties`. Under those assumptions, extraction
becomes an ordinary remote action:

* inputs: the bundled extractor, the archive (already in the CAS from the download insert),
  and the pre-extraction destination contents;
* command: `./extractor --archive ... --dest ...` plus the extraction parameters;
* output: the destination directory, captured as a `Tree`.

The action cache entry is produced by the remote execution service — never the client — so
remote extraction caching works in locked-down deployments, which the client-written entries
below cannot. On a mismatch (unsupported archive format, remote platform that cannot run the
host extractor, execution failure), extraction falls back to the local path below.
`repository_ctx.extract`'s `exec_properties`-bearing rules pass those through, so deployments
can route repository spawns to a matching worker pool explicitly.

## Extraction as a locally cached operation (no remote executor)

Without a remote executor, extraction runs locally and its results are recorded as action
cache entries for a *synthetic action* (the same technique the remote repo contents cache
uses): a constant `Command` that is never executed, with the extraction key embedded in the
`Action`'s salt, and the extracted tree as the declared output directory. Because such
entries map a key to output digests without any way to verify them, they are exactly as
trustworthy as their producer. They are therefore only written to caches the client is
trusted to write action results to:

* the **disk cache**, always — the local machine trusts itself; and
* the **remote cache**, only when `--remote_upload_local_results` is enabled — the same trust
  switch the remote repo contents cache and ordinary local action results use.

In a locked-down deployment extraction caching thus degrades to the local disk cache, which
still delivers the headline win: `clean --expunge`, changed patches, changed attributes and
non-reproducible repositories no longer re-extract archives. In a trusting deployment,
extractions are shared across machines.

Extraction *merges* into its destination, so the destination's pre-extraction state is part
of the extraction key (a fingerprint of pre-existing paths, types, file digests, symlink
targets and executable bits — trivially empty for the dominant `http_archive` pattern), and
the cached result is the merged post-extraction tree. Replay replaces the destination
contents — valid precisely because the key guarantees an identical pre-state. This also
covers rules that write files before extracting (toolchains_llvm writes its `BUILD.bazel`
first). Destinations with pathologically many pre-existing entries fall back to real
extraction rather than paying an unbounded fingerprinting cost. On replay, the tree is
materialized into a temporary sibling directory first and moved into place, so a partially
evicted CAS entry falls back to a real extraction with no side effects. Symlinks, executable
bits, and empty directories are preserved exactly.

## What does *not* change

* The repository rule implementation function still runs on every fetch. Granular caching
  operates strictly *within* a fetch; Skyframe invalidation, marker files and recorded inputs
  are untouched.
* Repository rules do not need to be reproducible, declare anything new, or change at all.
  Non-deterministic rules simply see lower hit rates on their `execute` steps.
* With the flag disabled (the default), no behavior changes whatsoever.

# Design notes

* The implementation surfaces two internal interfaces, injected the same way the existing
  repository remote executor is (via `RepositoryRemoteHelpersFactory`):
  * `RepositoryRemoteExecutor.executeCacheable(...)` — the repository-directory-in/out action
    described above;
  * `RepositoryCas` — verified blob inserts (`upload`) plus the extraction cache
    (`tryReplayExtraction` / `storeExtraction`).
* Cache policies are expressed through the existing `CombinedCache` read/write policy
  mechanism: CAS inserts write anywhere; extraction stores write disk-always,
  remote-only-if-trusted; AC entries for `execute` are never client-written at all.
* The extraction key is `(archive hash, archive base name, strip_prefix, strip_components,
  rename_files, destination pre-state fingerprint)` plus a format version, fingerprinted into
  the synthetic action's salt. The archive hash reuses the user-declared checksum when present
  and is otherwise computed.
* Replayed trees are synchronized rather than blindly re-materialized: files already on disk
  with matching digests are kept, extraneous entries are deleted. The same code path stages
  `execute` outputs and extraction replays.

# Experience report

The implementation was exercised against real rulesets using a local `remote_worker` as both
remote cache and remote executor, with `--experimental_granular_repository_caching` enabled
and the repo contents cache disabled:

* **rules_nodejs** (Node.js 20 toolchain): the toolchain archive extraction replays across
  `clean --expunge`; the replayed tree is byte-for-byte usable (`bin/node` runs, npm symlink
  farm intact).
* **rules_rust** (Rust 1.84 toolchain, `rust_binary` build): 16 distinct archive extractions
  replay on a post-expunge rebuild, including the ~430 MB rustc distribution and crate
  archives; compile/link actions were remote cache hits.
* **toolchains_llvm** (LLVM 17 distribution): this exercise motivated a design change. The
  repository rule writes its `BUILD.bazel` *before* calling `download_and_extract`, so an
  earlier "destination must be empty" restriction silently disabled caching for the 6.5 GB
  (extracted) distribution. Making the destination pre-state part of the extraction key
  removed the restriction; the distribution now stores and replays. Ruleset archives shared
  with other workspaces replayed from the common remote cache on first fetch.
* **rules_js** (`npm_translate_lock` + `npm_import`): this exercise found a real defect.
  `npm_import` runs `mkdir -p package` followed by `tar -xf package.tgz -C package`; the
  empty `package/` directory was silently dropped from the staged input tree, so the remote
  `tar` failed with a nonzero exit that the repository rule treats as fatal. The fix makes
  empty directories a detected not-cacheable condition with a local fallback, after which the
  build passes: the `mkdir` runs as a cached remote action (its captured output correctly
  materializes the empty directory locally), the `tar` falls back to local execution, and the
  surrounding download/extraction caching still applies. `npm_translate_lock`'s own `execute`
  calls run before any repository content exists and fall back for that reason.

No repository rule required modification. The fallback paths were exercised organically and
behave as designed. (Other incompatibilities encountered were unrelated ruleset-vs-Bazel@HEAD
API issues.)

With remote extraction and sandboxed local execution enabled, the same rulesets were
revalidated in a fully locked-down configuration (`--noremote_upload_local_results`):

* The Node.js 20 and LLVM 17 distributions (PAX-format `.tar.xz`) extract **via remote
  action** through the bundled extractor; the resulting `node` and `clang` binaries run.
  A post-expunge Node toolchain fetch completes in ~6 seconds (remote AC hit + tree
  materialization).
* A rules_js `npm_translate_lock` build shows the full decision matrix in one fetch: 21
  operations as remote actions, 4 local executions inside the hermetic sandbox (including
  `npm_import`'s `tar -C <empty dir>`, which the sandbox handles because it stages real
  directories), and 3 explicit fallbacks with logged reasons.
* The hermeticity property is tested end-to-end: a sandboxed command reading an undeclared
  absolute path fails, while the same file passed as a `path()` argument is visible.

# Performance considerations

* **Repository contents are materialized eagerly.** Replays download the full tree from the
  CAS. Integration with the remote repo contents cache's lazy overlay file system is future
  work; the two features compose naturally since both store REAPI `Tree`s.
* **`execute` uploads the repository directory as inputs.** For a repository containing a
  large toolchain this is a substantial first-time upload (subsequent ones dedupe via
  `FindMissingBlobs`, helped by the CAS inserts of downloads). The action key also requires
  hashing the repository tree per `execute` call.
* **Extraction stores upload the extracted tree.** With `--remote_upload_local_results`
  disabled this is disk-only and cheap; enabled, it is comparable to what the remote repo
  contents cache uploads today, but at sub-repository granularity and shareable across
  differing repository definitions that extract the same archive.

# Backward compatibility

The feature is opt-in behind an experimental flag and default-off. When enabled, observable
differences beyond caching are:

* A cacheable `ctx.execute` sees only explicitly passed environment variables (matching the
  existing `remotable = True` semantics) and runs on the remote platform. Commands that rely
  on undeclared host state — absolute host paths, inherited environment, tools discovered
  outside the repository — will behave differently or fail, in the same way they would under
  `--experimental_repo_remote_exec`. This is considered a defect in the repository rule
  rather than something to be bug-for-bug compatible with; the fallbacks catch the detectable
  cases.
* A failed remote `ctx.execute` does not leave partial file writes in the repository
  directory (the action's outputs are only staged on completion), whereas a failed local
  command may.
* The flag itself does not participate in repository invalidation: toggling it does not
  refetch already-fetched repositories.

# Future work

* **Lazy materialization**: inject replayed trees into the remote external overlay file
  system instead of materializing eagerly, sharing machinery with
  `--experimental_remote_repo_contents_cache`.
* **Remote Asset Push**: after a verified download, push the `checksum.sri → digest` mapping
  so cold clients can resolve downloads from the CAS. Such mappings are self-verifying when
  keyed by the checksum itself, so this plausibly fits the locked-down trust model, but Bazel
  currently implements only the Fetch half of the Remote Asset API.
* **Disk-cache entries for locally executed `execute`**: when no remote executor is
  configured, execute locally and record the result in the disk cache (the extraction cache
  already establishes the policy pattern).
* **Module extensions**: `module_ctx` operations are currently exempt; downloads and
  extractions there would benefit identically.
* **Working directories below the repository root**, and **symlink preservation** in
  `execute` input trees (symlinks are currently followed; dangling ones fall back).
* **Cross-repository tool references**: `ctx.execute` referencing a tool in another
  repository currently falls back to local execution. Label arguments already support
  staging; extending that to `path()` results pointing at other repositories would make
  patterns like "run node from the toolchain repo" cacheable.

# Alternatives considered

* **Extend the remote repo contents cache.** Its entries are client-fabricated AC entries, so
  it cannot serve deployments that reject client AC writes — the motivating constraint. Its
  whole-repository granularity also makes misses maximally expensive. The two features
  compose rather than compete: contents-cache hits skip the fetch entirely; granular caching
  accelerates the fetches that do run.
* **Requiring the remote platform to provide an extractor** (e.g. `tar` on `PATH`). Rejected:
  output determinism would depend on the worker image's tool versions, and format support
  would be unknowable. Bundling a pinned extractor keeps the action's behavior a function of
  its inputs. The residual host-OS/remote-platform match assumption is surfaced rather than
  hidden: mismatched deployments fall back to local extraction (or route repository spawns
  via `exec_properties`).
* **A bespoke portable extractor** (a fresh C++ implementation of tar/zip was prototyped).
  Rejected in favor of the native-image build of the existing Java decompressors: archive
  parsing of untrusted input is a prime vulnerability class (path traversal, RCE), and the
  in-tree implementation has years of hardening and real-world exposure. Reusing it also
  makes local/remote semantic agreement a construction-time guarantee rather than a
  conformance-testing obligation. The cost is a native-image step in Bazel's own build
  (GraalVM is already a build dependency for turbine) and a ~30 MB embedded binary; three
  small accommodations were needed (build-time initialization of the `String`-internals
  helpers, making the tar marker charset enumerable so the image builder can bake it, and a
  JNI configuration for zstd-jni).
* **A bespoke extraction cache outside the REAPI model** (e.g. files under
  `--repository_cache`). Storing REAPI `Tree`s in the standard disk/remote caches reuses
  existing storage, garbage collection, and the CAS blobs already present from downloads and
  `execute` actions, and keeps the door open for lazy materialization.
