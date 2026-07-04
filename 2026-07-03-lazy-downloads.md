---
created: 2026-07-03
last updated: 2026-07-04
status: Draft
reviewers: []
title: Lazy Downloads
authors:
  - Silic0nS0ldier
discussion thread: TBD
---

# Abstract

The only sanctioned way to bring remote files into a build is through repository rules and module extensions, which download eagerly during the fetch/loading phase and always materialise content on the local machine.
This proposal introduces a first-class download primitive for regular rules.
Downloads are _declared_ deterministically alongside the rule definition, and _executed_ lazily during the execution phase like actions.

This yields;
- A download that is not needed never happens.
- A download whose content is already present in the content-addressed download cache or a vendor directory is skipped.
- With the [Remote Asset API](https://github.com/bazelbuild/remote-apis/blob/main/build/bazel/remote/asset/v1/remote_asset.proto) plus remote execution, downloaded content need never touch the local machine.

Because download declarations are independent of build configuration, the complete set of downloads is computable from the loading phase alone.
This makes vendoring cheap and sound.
`bazel vendor` can enumerate and fetch every declared download into a directory without configuring or analysing anything, while targeted vendoring (`bazel vendor <patterns>`) fetches exactly the downloads the configured build would consume.
Either way, builds can then run entirely offline.

# Background

## How downloads work today

All remote file acquisition flows through `repository_ctx.download` and `repository_ctx.download_and_extract`, invoked from repository rules (usually via module extensions).
This has several structural consequences.

**Downloads are eager relative to their consumers.**
A repository is fetched the first time anything in it is needed, and fetching a repository means running the whole repository rule.
Every file it downloads is downloaded, whether or not the requesting build needs it.
Even `bazel query` can force fetches of repositories irrelevant to the result ([#13190](https://github.com/bazelbuild/bazel/issues/13190)).
Granularity can be recovered by splitting into one repository per file (as `http_file` and rules like `rules_js`'s npm import extension do), at the cost of thousands of repositories, lockfile bloat, and per-repository memory and invalidation overhead.
See [rules_js#2138](https://github.com/aspect-build/rules_js/issues/2138) (server memory blowup from generated npm repositories) and [rules_js#2769](https://github.com/aspect-build/rules_js/issues/2769) (whole-lockfile retranslation on any change).

**Content always lands on the local machine.**
Repository contents must exist on local disk before analysis of consuming targets can complete.
With remote execution and Build without the Bytes this is pure waste.
The file is only ever needed as an _input to a remote action_, which requires just a digest reference to a CAS blob.
`--remote_downloader` lets a Remote Asset service perform the download, but the result is still materialised into the local repository directory.
Reported as [#12197](https://github.com/bazelbuild/bazel/issues/12197) (multi-gigabyte `external/` trees downloaded locally only to be re-uploaded) and, for `http_file` specifically, [#22366](https://github.com/bazelbuild/bazel/issues/22366).

**Caches are consulted too late.**
The repository cache avoids _re_-downloading by checksum, but plays no part in deciding whether a download is needed, and identical content declared by two repositories is fetched twice when the fetches race ([#12420](https://github.com/bazelbuild/bazel/issues/12420)).
Because acquisition runs arbitrary repository-rule code, Bazel also cannot promise not to observably re-execute it ([#4533](https://github.com/bazelbuild/bazel/issues/4533)).

**Vendoring operates at repository granularity.**
`bazel vendor` (from the [Offline & Vendor Modes proposal](https://docs.google.com/document/d/1P9WwRvpGLi9Tw-AKN7dZ2AeRmfVsl_-lH-N9g3UkVMI)) snapshots entire repository directories, including generated files that are cheap to recreate, and must run the repository rules to do so.
Because the vendored set depends on configuration and command flags, it can be computed wrongly ([#26107](https://github.com/bazelbuild/bazel/issues/26107)), is much larger than needed ([#22684](https://github.com/bazelbuild/bazel/issues/22684)), and still leaks network access ([#26806](https://github.com/bazelbuild/bazel/issues/26806)).

## Prior art

- [Remote Downloads](2020-01-14-remote-downloads.md) defined the Remote Asset API (`Fetcher`/`Pusher`) that this proposal builds on.
- [A true repository cache](https://docs.google.com/document/d/1ZScqiIQi9l7_8eikbsGI-rjupdbCI7wgm1RYD76FJfM/edit) moves the repository cache towards content-addressed storage; the download store proposed here is a natural extension.
- [Credential Helpers for Bazel](2022-06-07-bazel-credential-helpers.md) provides the authentication mechanism reused here unchanged.

# Proposal

Add an optional `downloads` callback to `rule()`.
The callback declares the target's downloads from non-configurable attributes only.
The rule implementation consumes the resulting artifacts via `ctx.downloads`.

```starlark
def _impl(ctx):
    downloadable_file = ctx.downloads[ctx.attr.path]
    # ... use as an ordinary File ...

def _downloads(ctx):
    ctx.download(
        path = ctx.attr.path,
        urls = [ctx.attr.url],
        integrity = ctx.attr.integrity,
    )

foo = rule(
    implementation = _impl,
    attrs = {...},
    downloads = _downloads,
)
```

## Declaring downloads

The `downloads` callback receives a restricted context object exposing;

- `ctx.label`, the target's label.
- `ctx.attr`, containing **only** attributes declared with `configurable = False`.
  Accessing a configurable attribute is an evaluation error.
- `ctx.download(path, urls, integrity, canonical_id = "", executable = False)`, declaring one download.
  - `path` (string, mandatory): the key under which the rule implementation retrieves the artifact, unique within the target, and the trailing component of the artifact's exec path.
    No declared path may be a prefix of another (each becomes a file in a shared output directory); this is validated at declaration time.
  - `urls` (list of strings, mandatory): candidate URLs, tried in order; all must serve identical content.
  - `integrity` (string, mandatory): a [Subresource Integrity](https://www.w3.org/TR/SRI/) checksum (`sha256-`, `sha384-`, or `sha512-`).
    Exactly one checksum; the multi-checksum form of the SRI specification is rejected, since the checksum is the download's cache key and identity (the analogous relaxation for repository rules is tracked as [#15758](https://github.com/bazelbuild/bazel/issues/15758) and could be revisited for both APIs together).
    There is deliberately no unpinned mode.
    The checksum is the download's identity, and everything else in this proposal (cache skipping, vendoring, determinism) hangs off it.
    The history of checksum-less remote-downloader caching serving stale content ([#23932](https://github.com/bazelbuild/bazel/issues/23932), [#26763](https://github.com/bazelbuild/bazel/issues/26763)) is a cautionary precedent this design excludes by construction.
  - `canonical_id` (string): if non-empty, restricts download cache hits to entries recorded with the same canonical ID, matching the semantics of the repository rule download API.
    Use it to force a refetch when the same content is republished under a new logical identity.
  - `executable` (bool): whether the resulting artifact is marked executable.

Note that `configurable = False` is currently only accepted on symbolic macro attributes; rule definitions reject it.
This proposal lifts that restriction for rule attributes.
`attr.*(configurable = False)` becomes legal in `rule()` and marks the attribute non-configurable (`select()` on it is a loading-phase error), exactly as it already does for native rule attributes and macro attributes.
The relaxation applies to all rule definitions once the flag is enabled, not only rules that declare downloads.
It is independently useful and there is no reason to special-case it.

Under rule extension (`rule(parent = ...)`), `downloads` composes the same way `initializer` does.
Every callback in the chain runs, proceeding from child to ancestor, and all declarations merge into the single `ctx.downloads` map, which every implementation in the chain observes (including the parent implementation invoked via `ctx.super()`).
The callbacks share one declaration namespace: a `path` declared by more than one callback in the chain is an error, exactly as within a single callback.
There is deliberately no mechanism for a child to replace an ancestor's callback;

- An ancestor's implementation and its declarations are a matched pair.
  Severing them would make `ctx.super()` fail at a distance.
- The extension point is data, not code.
  A child that needs an ancestor's download to differ overrides the non-configurable attribute values the ancestor's callback reads; the ancestor's callback re-runs against the child's values.

Initializers and downloads callbacks are ordered globally: all initializers in the chain run first (child to ancestor, the existing semantics), then downloads callbacks observe the final attribute values.
An initializer may therefore compute the attributes a downloads callback reads.

The callback deliberately has no access to the configuration, toolchains, dependencies, or the filesystem.
Given a target definition, the set of declared downloads is therefore a pure function of loading-phase information.
This is the property that makes offline enumeration (vendoring, auditing) sound without analysis.

Configuration-dependent needs (e.g. a per-platform toolchain binary) are handled by declaring _all_ variants and having the rule implementation pick which one to consume.

```starlark
def _downloads(ctx):
    for os, arch, integrity in ctx.attr.platforms:
        ctx.download(
            path = "{}-{}".format(os, arch),
            urls = [_url(ctx.attr.version, os, arch)],
            integrity = integrity,
        )

def _impl(ctx):
    binary = ctx.downloads[_platform_key(ctx)]  # others are never fetched
    ...
```

Laziness makes over-declaration free at build time; unconsumed variants are never fetched.
Vendoring extracts either set depending on the mode (see [Vendoring](#vendoring)).
Targeted vendoring fetches only what the configured build would consume, while full-mirror vendoring intentionally fetches every declared variant, which is exactly what an offline mirror needs.

### Aspects, subrules, and symbolic macros

Downloads belong to targets, because targets are the loading-phase unit; no dedicated declaration surface exists for aspects, subrules, or symbolic macros.

- **Aspects** must not declare downloads.
  Which aspects apply to which targets is not a loading-phase fact (it depends on `--aspects`, on propagation along attributes, and on configuration), so aspect-declared downloads would silently escape declared-set enumeration and under-vendor offline mirrors.
  An aspect that needs fetched content points an implicit label attribute at a download-declaring rule target, like any other tool dependency.
- **Subrules** bundle their dependencies through implicit attributes, and those attributes can point at download-declaring rule targets.
  A subrule-level declaration surface would be strictly worse: subrule attribute values are definition-time constants, so every target instantiating the subrule would declare identical content, producing one artifact per consumer where a single shared target produces one artifact total.
- **Symbolic macros** expand into targets at loading time; a macro that wants downloads creates a download-declaring target.

## Download artifacts

Each declaration produces a _download artifact_: an ordinary `File` usable as an action input, in runfiles, providers, `DefaultInfo`, etc.
Conceptually it is a generated file whose generating action is a built-in `Download` action owned by the declaring target.

Because declarations are configuration-independent, download artifacts live under a configuration-free output root.
In the context of the execution root's existing layout, default layout first;

```
<output base>/execroot/_main/                    ← the execroot ("_main" appears only here
├── <main repository source files>                  and as the runfiles workspace directory)
├── external/<canonical repo>/…                  ← external repository source files
└── bazel-out/
    ├── stable-status.txt                        ← workspace status files (configuration-free)
    ├── volatile-status.txt
    ├── _tmp/…                                   ← temporary directory
    ├── <configuration mnemonic>/                ← e.g. k8-fastbuild; one per configuration
    │   ├── bin/<package>/…                      ← configured derived artifacts
    │   │   └── external/<canonical repo>/<package>/…
    │   ├── genfiles/…
    │   └── testlogs/…
    └── downloads/                               ← download artifacts (configuration-free)
        ├── <package>/<target name>/<path>
        └── external/<canonical repo>/<package>/<target name>/<path>
```

Under `--experimental_sibling_repository_layout`, external repository source files become siblings of the main repository's directory, and external derived roots move inside `bazel-out` under the repository name.
Download roots move with them;

```
<output base>/execroot/
├── _main/                                       ← the main repository's execroot
│   ├── <main repository source files>
│   └── bazel-out/
│       ├── stable-status.txt
│       ├── volatile-status.txt
│       ├── _tmp/…
│       ├── <configuration mnemonic>/            ← main repository configured artifacts
│       │   ├── bin/<package>/…
│       │   ├── genfiles/…
│       │   └── testlogs/…
│       ├── <canonical repo>/                    ← external repositories' derived roots
│       │   ├── <configuration mnemonic>/
│       │   │   └── bin/<package>/…
│       │   └── downloads/                       ← external download artifacts, no
│       │       └── <package>/<target name>/<path>  external/ prefix (repo is in the root)
│       └── downloads/                           ← main repository download artifacts
│           └── <package>/<target name>/<path>
└── <canonical repo>/…                           ← external repository source files (siblings)
```

Within their roots, download artifacts follow the same repository conventions as every other derived artifact under each layout.
Under the default layout, no prefix for the main repository and an `external/<canonical repo>` prefix for external ones (mirroring `bin/`).
Under the sibling layout, the repository name in the root itself.
This is load-bearing for runfiles.
`Artifact#getRunfilesPath` understands precisely these two shapes, so a download artifact in runfiles lands at `<workspace>/<package>/<target name>/<path>` (or under the declaring repository's own runfiles directory for external declarations) like any other derived file, identically under both layouts.
A repository-first layout with a `_main` placeholder was considered and rejected.
`_main` is not used anywhere inside `bazel-out`, and no existing machinery (runfiles mapping, path stripping, tooling that parses exec paths) would understand it.

The `downloads` directory name shares its namespace with configuration mnemonics, the status files, and (under the sibling layout) canonical repository names.
Collisions do not arise in practice because mnemonics always contain a `-` (e.g. `k8-fastbuild`) and canonical repository names always contain a `+` (e.g. `m+`, `rules_jvm_external++maven+maven`), the same argument by which `_tmp` and the status files already coexist there.

Physical storage is content-addressed (see [Caching](#caching-and-invalidation)); the exec-path location is a hardlink or symlink into that store.
Identical declarations (same `integrity`) across any number of targets and configurations share one blob and at most one fetch.
The exact layout is an implementation detail.
The load-bearing requirements are that the exec path is stable across configurations and derived from the declaring label plus `path`.

In the rule implementation, `ctx.downloads` is an immutable string-keyed mapping from declared `path` to `File`.

## Execution semantics

A `Download` action executes only when its artifact is actually demanded, as an input to an action that runs or as a requested top-level output.
Loading and analysis never block on downloads, and a target whose downloads are declared but not consumed in a given build fetches nothing.

When a `Download` action does execute, resolution proceeds in cost order, stopping at the first success;

1. **Output tree / action cache.** Artifact already materialised with matching digest: no-op.
2. **Vendor directory.** If `--vendor_dir` is set and contains the blob (keyed by integrity), link it in.
3. **Local download store.** The content-addressed store shared with the repository cache; hit means link, no network.
   A non-empty `canonical_id` restricts hits to entries recorded under the same ID, exactly as in the repository rule download API.
4. **Remote Asset API.** When `--remote_downloader` is configured, issue `Fetcher.FetchBlob` with the declared `urls` and a `checksum.sri` qualifier carrying `integrity`.
   The service downloads into the remote CAS and returns the blob's `Digest`.
   Under Build without the Bytes (`--remote_download_outputs=minimal`) this step is hoisted ahead of step 3 and completes with metadata only.
   The content never reaches the local machine, which is the intended steady state for remote-execution builds.
   A failure here falls back to the materialising steps rather than failing the action.
5. **Local HTTP download.** Fall back to fetching `urls` in order with the existing downloader machinery (credential helpers, retries, proxies).
   Verify `integrity` and populate the local download store.

The integrity checksum identifies _content_; it says nothing about the digest function a remote cache uses.
Any REAPI `Digest` for the blob therefore comes only from the Remote Asset service (step 4), which responds in the server's digest function.
Resolution never attempts to derive a CAS digest from `integrity` itself (see [Alternatives Considered](#alternatives-considered)).

Integrity verification is unconditional at every network boundary.
A mismatch is a permanent, non-retryable action failure attributed to the declaring target, reporting the URL, expected, and actual checksums.

Failure tolerance is mirrors plus retries, nothing more.
The downloader machinery already retries transient errors and falls through the `urls` list in order.
There is deliberately no `allow_fail` equivalent at the rule level.
An optional download makes the action's output undefined, and unlike repository rules (where `allow_fail` supports probing during workspace setup) a rule has no loading-phase logic that could react to the failure.

Downloads are not spawns.
They have no execution platform, no strategy, and are not sandboxed.
They execute on action-execution threads with concurrency bounded additionally by `--http_max_parallel_downloads`.

Progress reporting mirrors repository fetches: the action's progress line names the URL currently being attempted, with byte counts, updating in place across mirror fallback and retries.
This dynamic detail is UI-only.
BEP's action model has no notion of a retry, so it receives what it can represent: one `Fetch` event per URL attempt (success or failure, as for repository fetches) and one action execution event; the action's static progress message does not embed a URL, since BEP consumers observe it once while the attempted URL can change.

Offline semantics follow existing conventions.
With `--repository_disable_download`, steps 4 and 5 are disabled and a download that reaches them fails with an error naming the cause.

## Vendoring

A target's declarations define two distinct, well-defined download sets, and the vendor command extracts one or the other depending on the mode;

- The **declared set**: everything passed to `ctx.download(...)`.
  A pure loading-phase product, independent of configuration, with all `select()` branches and platform variants included.
- The **consumed set**: the download artifacts reachable in the action graph from the requested targets' default outputs and runfiles under a concrete configuration.
  An analysis product, exactly the downloads a `bazel build` of the same patterns with the same flags would execute.

`bazel vendor` has three modes, and lazy downloads slot into each with the semantics that mode already has for repositories;

- **`bazel vendor <target patterns>`: consumed set (configured semantics).**
  The command already analyses the patterns under the current configuration to decide which _repositories_ to vendor; the same analysis yields the download actions reachable from the requested outputs, and exactly those are fetched.
  Nothing unused is downloaded: unselected `select()` branches and unconsumed platform variants contribute nothing.
  The tradeoff is inherited from the mode itself: the result depends on build flags (the [#26107](https://github.com/bazelbuild/bazel/issues/26107) class), in exchange for exact parity with what a build would fetch.
  Because the store is content-addressed, targeted runs compose by accumulation.
  Vendoring the same patterns once per platform (`--platforms=...`) merges into one store with nothing fetched twice; this is the multi-platform story for targeted vendoring.
- **`bazel vendor` (no arguments): declared set (loading semantics).**
  Without lazy downloads this mode vendors every repository in the module dependency graph without loading a single package.
  Covering rule downloads extends it: load every package of the main repository and of each repository in the module graph, evaluate the `downloads` callbacks of every rule target, and fetch the union of declared downloads.
  This is the full-mirror mode; it is configuration-free by construction and cannot be perturbed by rc files or command flags.
- **`bazel vendor --repo=@foo`: declared set, scoped (loading semantics).**
  Vendors the repository itself as before, plus the declared downloads of every target defined in `@foo`.

Both loading-semantics modes rest on the determinism property.
Because a `downloads` callback reads only non-configurable attributes, its declarations can be enumerated by loading packages alone, with no configuration and no analysis.

Blobs land in a content-addressed store;

```
<vendor_dir>/downloads/<sri algorithm>/<hex digest>
<vendor_dir>/downloads/MANIFEST
```

The store is keyed purely by content, so it is stable across target renames and refactors, deduplicates shared blobs, cannot collide, and merges across vendor runs of any mode.
The `MANIFEST` records, for each blob, the URLs and declaring labels observed at vendor time.
Provenance for mirroring and supply-chain auditing, not consumed by resolution.

A build with `--vendor_dir` set consults the store at step 2 of resolution.
`--vendor_dir` plus offline mode yields a fully network-isolated build; a missing blob at that point is a clear, actionable error ("re-run `bazel vendor`").
Note one consequence of consumed-set semantics: a download declared but consumed by no action and exposed by no requested output is only picked up by the loading-semantics modes.

Vendoring composes with the Remote Asset path.
An organisation can vendor for airgapped CI while developer builds resolve the same declarations against a Fetch service.

A serviceable form of the consumed-set workflow is expressible without new commands: building the targets with `--repository_cache` pointed at a workspace directory populates a content-addressed layout, and `--repository_disable_download` then yields the offline build.
What dedicated `bazel vendor` support adds is fetching without executing the build, the declared-set modes (which need no analysis at all), and the provenance `MANIFEST`.

## Caching and invalidation

The **integrity checksum is the identity** of a download.
`urls` are acquisition hints only.
Concretely, the `Download` action's key is computed from `integrity`, `canonical_id`, and `executable`; the URL list is deliberately excluded.

- Changing `urls` while `integrity` is unchanged invalidates nothing; already-resolved artifacts stay valid (mirroring the repository cache semantics).
  Swapping in a mirror list is free.
- Changing `integrity` yields a new identity: consumers re-execute, and the old blob ages out of caches.
- Changing `canonical_id` re-executes the download and bypasses download cache entries recorded under other IDs.
  Since the content is still pinned by `integrity`, consumers only re-execute if the bytes actually differ (which would then fail verification).

Downloaded blobs live in the shared content-addressed download store alongside repository cache content, subject to the same garbage collection ([Garbage collection for the disk cache](https://docs.google.com/document/d/16aGm4u9EgW199M1WjjbVbVCJSfa8RApWPcKnZYnVbrI)).
The store's growth and multi-user sharing issues ([#22516](https://github.com/bazelbuild/bazel/issues/22516), [#17709](https://github.com/bazelbuild/bazel/issues/17709), [#13848](https://github.com/bazelbuild/bazel/issues/13848)) gain priority as it becomes a first-class build-time cache.

Download execution is deduplicated by `integrity` across all declaring targets.
Concurrent downloads of the same content are serialised in the shared download manager, so the first fetch populates the content-addressed store and the rest hit it, and N targets declaring the same blob cost one fetch and one cache entry.
Because the deduplication lives in the download manager rather than in the download action layer, repository fetches of the same content participate too, fixing the long-standing duplicate-concurrent-fetch behaviour of repository rules ([#12420](https://github.com/bazelbuild/bazel/issues/12420)) as a side effect.

## Introspection

Repository-rule downloads have `bazel mod show_repo` to expose their acquisition facts.
For lazy downloads the equivalent surface is the action graph.
`Download` actions are ordinary actions, so `bazel aquery` works on them, and the output carries the facts a build engineer would otherwise have to reverse-engineer from Starlark.
`Download` actions have no inputs and no command line by construction, so their aquery entries report the acquisition facts instead: the candidate `urls` in order, the `integrity` checksum, the canonical ID (when set), and the executable bit.
These appear in the text format and as first-class fields (`download_urls`, `download_integrity`, `download_canonical_id`, `is_executable`) in the proto/textproto/jsonproto formats.

```
$ bazel aquery 'mnemonic("Download", //...)'
action 'Downloading node_modules/left-pad/left-pad'
  Mnemonic: Download
  Target: //:node_modules/left-pad
  ActionKey: ...
  Inputs: []
  Outputs: [bazel-out/downloads/node_modules/left-pad/left-pad]
  URLs: [
    https://registry.npmjs.org/left-pad/-/left-pad-1.3.0.tgz
  ]
  Integrity: sha512-XI5MPzVNApjAyhQzphX8BkmKsKUxD4LdyK24iZeQGinBN9yTQT3bFlCBy/aVx2HrNcqQGsdot8ghrjyrvMCoEA==
  IsExecutable: false
```

Because declarations are an analysis-time product, querying requires no fetching.
The details are reported for every declared download, including ones the build would never execute.
Loading-phase enumeration, without configuring at all, is what vendoring adds (see [Open questions](#open-questions) for `bazel query` output and SBOM/BEP reporting built on it).

## Example: `npm_import`

A sketch of a package-manager rule using the API.
One target per package, no repository per package, and the tarball fetch and extraction are both lazy and remote-executable.

```starlark
# file: BUILD.bazel
load("//:npm_import.bzl", "npm_import")

npm_import(
    name = "node_modules/@canva/exec",
    package = "@canva/exec",
    version = "1.0.0",
    integrity = "sha512-...",
)

# file: npm_import.bzl
_REGISTRY = "https://registry.npmjs.org"

def _impl(ctx):
    tarball = ctx.downloads[ctx.attr.package]
    extracted = ctx.actions.declare_directory(ctx.attr.name)
    ctx.actions.run(
        executable = ctx.executable._extract,
        arguments = [tarball.path, extracted.path],
        inputs = [tarball],
        outputs = [extracted],
    )
    return [DefaultInfo(files = depset([extracted]))]

def _downloads(ctx):
    # URL derived purely from non-configurable attrs; deterministic and vendorable.
    unscoped = ctx.attr.package.rsplit("/", 1)[-1]
    ctx.download(
        path = ctx.attr.package,
        urls = ["{}/{}/-/{}-{}.tgz".format(
            _REGISTRY,
            ctx.attr.package,
            unscoped,
            ctx.attr.version,
        )],
        integrity = ctx.attr.integrity,
    )

npm_import = rule(
    implementation = _impl,
    attrs = {
        "package": attr.string(mandatory = True, configurable = False),
        "version": attr.string(mandatory = True, configurable = False),
        "integrity": attr.string(mandatory = True, configurable = False),
        "_extract": attr.label(
            default = "//tools/npm:extract",
            executable = True,
            cfg = "exec",
        ),
    },
    downloads = _downloads,
)
```

With remote execution and Build without the Bytes, a clean build of a large `node_modules` tree touches the local network only for `FetchBlob` calls and action-cache lookups.
Tarballs land in the remote CAS, extraction runs remotely, and neither tarballs nor extracted trees are downloaded unless explicitly requested.

## Out of scope

This proposal does not seek to solve;

- **Replacing repository rules.**
  Loading-phase needs (generating BUILD files, `http_archive`-style workspace bootstrap, toolchain autoconfiguration) remain repository-rule territory.
  This proposal covers content whose consumers are actions.
- **Archive extraction.**
  As the example shows, extraction is an ordinary action.
  A built-in extraction action or `FetchDirectory` support may be layered on later.
  Extraction being a regular (configured) action means an archive consumed in several configurations is extracted once per configuration; deduplicating that is the domain of the ongoing path-mapping work, not this proposal.
- **Unstable archives.**
  Content whose bytes are not stable across fetches (e.g. tarballs generated on the fly by `git archive`, whose compression can change; see [#19033](https://github.com/bazelbuild/bazel/issues/19033)) cannot be pinned by a byte-level SRI checksum and is out of scope.
  Such content needs a stable artifact (a release asset, a mirror) before it can be declared as a download.
- **Optional downloads.**
  No `allow_fail` equivalent; see [Execution semantics](#execution-semantics).
- **New authentication machinery.**
  Credential helpers and `--remote_downloader` authentication apply as-is.
- **Configuration-dependent download sets.**
  Deliberately excluded; see [Alternatives Considered](#alternatives-considered).

# Backward-compatibility

All changes are additive and opt-in;

- A new optional `rule()` parameter.
- A new field on the rule implementation context.
- A new action type.
- New subcommand behaviour for `bazel vendor`.

Existing rules, repository rules, and module extensions are unaffected.
The API ships behind `--experimental_lazy_downloads`.

The main forward-compatibility risk is the artifact layout (`bazel-out/downloads/...`) becoming load-bearing for downstream tooling.
The proposal treats the layout as unspecified, and the experimental phase should validate that nothing observable depends on it.

# Implementation status

The reference implementation covers the full design, with one exception noted below.
The API contract (declaration semantics, action key, cache identity, aquery output) is implemented as specified behind `--experimental_lazy_downloads`.

**API surface.**
`rule(downloads = ...)`, `downloads_ctx.download(...)` (including `canonical_id`), `ctx.downloads`, and `Download` actions with full aquery support.
Rule extension composes as specified: callbacks run child to ancestor after all initializers, declarations merge into one namespace, and duplicate paths across the chain are rejected.
Progress reporting is action-scoped as specified: fetch progress (URL, byte counts) renders on the download action's own progress line, with BEP receiving per-attempt `Fetch` events.

**Execution.**
Download artifacts live under the configuration-free roots laid out in [Download artifacts](#download-artifacts) (both repository layouts, correct runfiles placement).
The same declaration analysed in any number of configurations yields one exec path and, `Download` action keys being configuration-independent, deduplicates through Bazel's shared-action machinery into a single execution.
Resolution consults the vendor directory's content-addressed store first, then completes digest-only through the Remote Asset API when a remote downloader is configured under `--remote_download_outputs=minimal` (the blob lands in the remote CAS and the artifact's metadata is injected without the bytes reaching the local machine; failure falls back to a materialising download), then delegates to the shared `DownloadManager`.
Download cache, `--distdir`, URL rewriting, netrc/credential-helper authentication, retries, `--repository_disable_download`, and `--remote_downloader` behave exactly as for repository fetches.
Concurrent downloads of the same checksum are serialised inside the download manager, so content is fetched once regardless of whether the requesters are download actions, repository fetches, or a mix.

**Vendoring.**
All three `bazel vendor` modes are implemented as specified in [Vendoring](#vendoring).
Target patterns vendor the consumed set (action-graph reachability from the analysed targets' outputs and runfiles); no-argument vendoring and `--repo` vendor the declared set by loading packages and evaluating `downloads` callbacks, with no configuration involved.
Fetches go through the same `DownloadManager`, and blobs land in `<vendor_dir>/downloads/<algorithm>/<hex>` with provenance recorded in `MANIFEST`.

**Exception: no dedicated download pool.**
Downloads execute synchronously on action-execution threads (bounded additionally by `--http_max_parallel_downloads`), so a slow download occupies a `--jobs` slot.
Freeing the slot requires asynchronous completion support for non-spawn actions in the execution engine, which does not exist and is not worth building for this feature alone; if such support lands (e.g. for async spawns generally), downloads should adopt it.
Until then, download-heavy builds can raise `--jobs` and `--http_max_parallel_downloads` together.

# Alternatives Considered

## Repository rules with `--remote_downloader` and vendoring

Covers pieces of the motivation but composes poorly.
Downloads remain eager relative to the repository, content is always materialised locally, and per-file granularity requires per-file repositories (with the associated memory, lockfile, and invalidation costs).
Vendoring snapshots repository directories rather than a content-addressed blob store.

## The remote repo contents cache

Bazel 9's `--experimental_remote_repo_contents_cache` (backport demand: [#29031](https://github.com/bazelbuild/bazel/issues/29031)) attacks the adjacent problem: it caches _evaluated repository contents_ remotely so that a repository need not be re-fetched or re-evaluated on other machines.
It is complementary rather than competing.
It operates at repository granularity, keyed by the repository rule's inputs, and remains eager at loading time; content that hits the cache is still repository content, materialised (or virtualised) as a whole.
This proposal operates below that layer, at blob granularity keyed by content, and moves acquisition out of the loading phase entirely.
Repository rules that genuinely need loading-phase content keep benefiting from the contents cache; content whose consumers are actions stops needing a repository at all.
The contents cache's correctness issues with lazily materialised content and local actions ([#29656](https://github.com/bazelbuild/bazel/issues/29656)) also illustrate why this proposal keeps "artifact not materialised locally" confined to the same Build-without-the-Bytes machinery every other action output already uses, rather than inventing a second virtual-file mechanism.

## Analysis-time declaration: `ctx.actions.download()`

The most obvious API shape: declare downloads in the rule implementation like any other action.
Rejected because it destroys the determinism property.
The download set would depend on configuration (via configurable attributes, toolchains, and transitions), so enumerating "everything a build might download" would require configuring and analysing every target in every relevant configuration, and even then be incomplete for configurations not exercised.
Vendoring, offline auditing, and SBOM extraction all degrade from "cheap loading-phase query" to "best-effort analysis sweep".
It also invites URL computation from configuration state, which is exactly the nondeterminism this design excludes.

## A dedicated download rule

A built-in leaf rule (an evolution of `http_file` into a regular target) plus macros to compute URLs would also yield deterministic, lazy downloads.
Rejected as the _only_ mechanism because it forces every download to be a distinct target wired through providers.
Rulesets cannot keep acquisition as an implementation detail (every consumer's API grows a label attribute), and large dependency graphs pay a target-count and analysis-memory tax precisely where the npm-style use case needs it least.
Note the two are not exclusive; a trivial `download_file` rule falls out of this proposal's API in a few lines of Starlark.

## Downloads as ordinary spawns

Running a downloader tool via `ctx.actions.run` works but is a poor fit;

- Sandboxes and remote executors rightly forbid or discourage network access.
- Integrity verification is delegated to the tool rather than enforced by Bazel.
- The remote cache is consulted only via the action cache (keyed by the action, not the content).
- There is no Remote Asset integration, vendor-directory resolution, or shared download store.

Network-in-actions also undermines the assumptions remote execution services make about action hermeticity and retryability.

## Resolving against the remote CAS by recorded digest

A resolution step that checks the remote cache directly for the blob, keyed by a REAPI `Digest` recorded from a previous resolution, would let a build skip the Remote Asset service when the blob is already in the CAS.
Rejected as a conflation of two identities.
The SRI checksum identifies content and may use a hash function (e.g. sha512, the npm lockfile default) that differs from the remote cache's digest function, so a `Digest` can never be derived from `integrity`; it would have to be persisted and trusted from earlier runs, adding a cache-consistency problem for marginal benefit.
The Remote Asset service already fills this role: `FetchBlob` is cheap when the service has the blob cached, and its response carries the authoritative `Digest` in the server's digest function.

# Open questions

1. **Loading-phase introspection.**
   Action-graph introspection is specified above, but the deterministic download set also invites loading-phase tooling that needs no analysis: `bazel query` output for declared downloads, BEP reporting, and SBOM generation.
   Worth specifying once the core lands.
