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

Today the only sanctioned way to bring remote files into a build is through repository rules and module extensions, which download eagerly during the fetch/loading phase and always materialise content on the local machine. This proposal introduces a first-class download primitive for regular rules: downloads are *declared* deterministically alongside the rule definition, and *executed* lazily during the execution phase like actions. A download that is not needed never happens; a download whose content is already present in the content-addressed download cache or a vendor directory is skipped; and with the [Remote Asset API](https://github.com/bazelbuild/remote-apis/blob/main/build/bazel/remote/asset/v1/remote_asset.proto) plus remote execution, downloaded content may never touch the local machine at all.

Because download declarations are independent of build configuration, the complete set of downloads for any set of targets is computable from the loading phase alone. This makes vendoring cheap and sound: `bazel vendor` can enumerate and fetch every declared download into a directory, from which builds can then run entirely offline.

# Background

## How downloads work today

All remote file acquisition flows through `repository_ctx.download` and `repository_ctx.download_and_extract`, invoked from repository rules (usually via module extensions). This has several structural consequences:

1. **Downloads are eager relative to their consumers.** A repository is fetched the first time anything in it is needed, and fetching a repository means running the whole repository rule — every file it downloads is downloaded, whether or not the requesting build needs it. Even `bazel query` can force fetches of repositories irrelevant to the result ([#13190](https://github.com/bazelbuild/bazel/issues/13190)). Granularity can be recovered by splitting into one repository per file (as `http_file` and rules like `rules_js`'s npm import extension do), at the cost of thousands of repositories, lockfile bloat, and per-repository memory and invalidation overhead — see [rules_js#2138](https://github.com/aspect-build/rules_js/issues/2138) (server memory blowup from generated npm repositories) and [rules_js#2769](https://github.com/aspect-build/rules_js/issues/2769) (whole-lockfile retranslation on any change).

2. **Content always lands on the local machine.** Repository contents must exist on local disk before analysis of consuming targets can complete. With remote execution and Build without the Bytes, this is pure waste: the file is only ever needed as an *input to a remote action*, which requires just a digest reference to a CAS blob. `--experimental_remote_downloader` lets a Remote Asset service perform the download, but the result is still materialised into the local repository directory — reported as [#12197](https://github.com/bazelbuild/bazel/issues/12197) (multi-gigabyte `external/` trees downloaded locally only to be re-uploaded) and, for `http_file` specifically, [#22366](https://github.com/bazelbuild/bazel/issues/22366).

3. **Caches are consulted too late.** The repository cache avoids *re*-downloading by checksum, but plays no part in deciding whether a download is needed, and identical content declared by two repositories is fetched twice when the fetches race ([#12420](https://github.com/bazelbuild/bazel/issues/12420)). Because acquisition runs arbitrary repository-rule code, Bazel also cannot promise not to observably re-execute it ([#4533](https://github.com/bazelbuild/bazel/issues/4533)).

4. **Vendoring operates at repository granularity.** `bazel vendor` (from the [Offline & Vendor Modes proposal](https://docs.google.com/document/d/1P9WwRvpGLi9Tw-AKN7dZ2AeRmfVsl_-lH-N9g3UkVMI)) snapshots entire repository directories, including generated files that are cheap to recreate, and must run the repository rules to do so. Because the vendored set depends on configuration and command flags, it can be computed wrongly ([#26107](https://github.com/bazelbuild/bazel/issues/26107)), is much larger than needed ([#22684](https://github.com/bazelbuild/bazel/issues/22684)), and still leaks network access ([#26806](https://github.com/bazelbuild/bazel/issues/26806)).

## Prior art

* [Remote Downloads](2020-01-14-remote-downloads.md) defined the Remote Asset API (`Fetcher`/`Pusher`) that this proposal builds on.
* [A true repository cache](https://docs.google.com/document/d/1ZScqiIQi9l7_8eikbsGI-rjupdbCI7wgm1RYD76FJfM/edit) moves the repository cache towards content-addressed storage; the download store proposed here is a natural extension.
* [Credential Helpers for Bazel](2022-06-07-bazel-credential-helpers.md) provides the authentication mechanism reused here unchanged.

# Proposal

Add an optional `downloads` callback to `rule()`. The callback declares the target's downloads from non-configurable attributes only; the rule implementation consumes the resulting artifacts via `ctx.downloads`.

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

The `downloads` callback receives a restricted context object exposing:

* `ctx.label` — the target's label.
* `ctx.attr` — **only** attributes declared with `configurable = False`. Accessing a configurable attribute is an evaluation error.
* `ctx.download(path, urls, integrity, canonical_id = "", executable = False)` — declares one download.
  * `path` (string, mandatory): the key under which the rule implementation retrieves the artifact, unique within the target, and the trailing component of the artifact's exec path. No declared path may be a prefix of another (each becomes a file in a shared output directory); this is validated at declaration time.
  * `urls` (list of strings, mandatory): candidate URLs, tried in order; all must serve identical content.
  * `integrity` (string, mandatory): a [Subresource Integrity](https://www.w3.org/TR/SRI/) checksum (`sha256-`, `sha384-`, or `sha512-`). Exactly one checksum: the multi-checksum form of the SRI specification is rejected, since the checksum is the download's cache key and identity (the analogous relaxation for repository rules is tracked as [#15758](https://github.com/bazelbuild/bazel/issues/15758) and could be revisited for both APIs together). There is deliberately no unpinned mode: the checksum is the download's identity, and everything else in this proposal (cache skipping, vendoring, determinism) hangs off it. The history of checksum-less remote-downloader caching serving stale content ([#23932](https://github.com/bazelbuild/bazel/issues/23932), [#26763](https://github.com/bazelbuild/bazel/issues/26763)) is a cautionary precedent this design excludes by construction.
  * `canonical_id` (string): if non-empty, restricts download cache hits to entries recorded with the same canonical ID, matching the semantics of the repository rule download API. Use it to force a refetch when the same content is republished under a new logical identity.
  * `executable` (bool): whether the resulting artifact is marked executable.

Note that `configurable = False` is today only accepted on symbolic macro attributes; rule definitions reject it. This proposal lifts that restriction for rule attributes: `attr.*(configurable = False)` becomes legal in `rule()` and marks the attribute non-configurable (`select()` on it is a loading-phase error), exactly as it already does for native rule attributes and macro attributes. The relaxation applies to all rule definitions once the flag is enabled, not only rules that declare downloads — it is independently useful and there is no reason to special-case it.

The `downloads` parameter is currently rejected in combination with rule extension (`rule(parent = ...)`): the composition semantics (which callback runs, whether declarations merge) are deferred alongside the aspect question in [Open questions](#open-questions).

The callback deliberately has no access to the configuration, toolchains, dependencies, or the filesystem. Given a target definition, the set of declared downloads is therefore a pure function of loading-phase information — the property that makes offline enumeration (vendoring, auditing) sound without analysis.

Configuration-dependent needs (e.g. a per-platform toolchain binary) are handled by declaring *all* variants and having the rule implementation pick which one to consume:

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

Laziness makes over-declaration free at build time — unconsumed variants are never fetched — while `bazel vendor` intentionally fetches all of them, which is exactly what an offline mirror needs. The flip side is accepted: a vendor run may fetch content no build of the current workspace ever consumes (all platform variants of a toolchain, say). That is the cost of a download set that is sound without analysis, and it is no worse than today's repository-granularity vendoring, which snapshots entire repositories ([#22684](https://github.com/bazelbuild/bazel/issues/22684)).

## Download artifacts

Each declaration produces a *download artifact*: an ordinary `File` usable as an action input, in runfiles, providers, `DefaultInfo`, etc. Conceptually it is a generated file whose generating action is a built-in `Download` action owned by the declaring target.

Because declarations are configuration-independent, download artifacts live under a configuration-free output root, tentatively:

```
bazel-out/downloads/<canonical repo>/<package>/<target name>/<path>
```

Physical storage is content-addressed (see [Caching](#caching-and-invalidation)); the exec-path location is a hardlink or symlink into that store. Identical declarations (same `integrity`) across any number of targets and configurations share one blob and at most one fetch. The exact layout is an implementation detail; the load-bearing requirements are that the exec path is stable across configurations and derived from the declaring label plus `path`. (The configuration-free root is a phase-2 item; see [Implementation phases](#implementation-phases) for what the current implementation does instead.)

In the rule implementation, `ctx.downloads` is an immutable string-keyed mapping from declared `path` to `File`.

## Execution semantics

A `Download` action executes only when its artifact is actually demanded — as an input to an action that runs, or as a requested top-level output. Loading and analysis never block on downloads, and a target whose downloads are declared but not consumed in a given build fetches nothing.

When a `Download` action does execute, resolution proceeds in cost order, stopping at the first success:

1. **Output tree / action cache** — artifact already materialised with matching digest: no-op.
2. **Vendor directory** — if `--vendor_dir` is set and contains the blob (keyed by integrity), link it in.
3. **Local download store** — the content-addressed store shared with the repository cache; hit means link, no network. A non-empty `canonical_id` restricts hits to entries recorded under the same ID, exactly as in the repository rule download API.
4. **Remote Asset API** — when `--remote_downloader` is configured, issue `Fetcher.FetchBlob` with the declared `urls` and a `checksum.sri` qualifier carrying `integrity`. The service downloads into the remote CAS and returns the blob's `Digest`. Under Build without the Bytes the content never reaches the local machine; this is the intended steady state for remote-execution builds.
5. **Local HTTP download** — fall back to fetching `urls` in order with the existing downloader machinery (credential helpers, retries, proxies). Verify `integrity` and populate the local download store.

The integrity checksum identifies *content*; it says nothing about the digest function a remote cache uses. Any REAPI `Digest` for the blob therefore comes only from the Remote Asset service (step 4), which responds in the server's digest function — resolution never attempts to derive or replay a CAS digest from `integrity` itself. (An earlier draft included a direct remote-CAS presence check keyed by a previously recorded digest; it was removed as a conflation of the two identities.)

Integrity verification is unconditional at every network boundary. A mismatch is a permanent, non-retryable action failure attributed to the declaring target, reporting the URL, expected, and actual checksums.

Failure tolerance is mirrors plus retries, nothing more: the downloader machinery already retries transient errors and falls through the `urls` list in order. There is deliberately no `allow_fail` equivalent at the rule level — an optional download makes the action's output undefined, and unlike repository rules (where `allow_fail` supports probing during workspace setup) a rule has no loading-phase logic that could react to the failure.

Downloads are not spawns: they have no execution platform, no strategy, and are not sandboxed. They run on a dedicated downloader pool with their own concurrency limit (analogous to `--http_max_parallel_downloads`... exact flag naming TBD), and report progress through the standard action UI and BEP as `Download` events.

Offline semantics follow existing conventions: with network-less operation requested (e.g. `--nofetch`... exact spelling TBD during implementation), steps 4–5 are disabled and a download that reaches them fails with an error naming the vendor/cache options.

## Vendoring

Because the download set is a loading-phase product, vendoring requires neither configuration nor analysis:

```
bazel vendor //some/...:all
```

evaluates the `downloads` callbacks of every target in the pattern's transitive closure (all `select()` branches included — the closure is an over-approximation by construction) and fetches every declared download into:

```
<vendor_dir>/downloads/<sri algorithm>/<hex digest>
<vendor_dir>/downloads/MANIFEST
```

The store is keyed purely by content, so it is stable across target renames and refactors, deduplicates shared blobs, and cannot collide. The `MANIFEST` records, for each blob, the URLs and declaring labels observed at vendor time — provenance for mirroring and supply-chain auditing, not consumed by resolution.

A build with `--vendor_dir` set consults the store at step 2 of resolution. `--vendor_dir` plus offline mode yields a fully network-isolated build; a missing blob at that point is a clear, actionable error ("re-run `bazel vendor`").

Vendoring composes with the Remote Asset path: an organisation can vendor for airgapped CI while developer builds resolve the same declarations against a Fetch service.

Note that a serviceable form of this workflow is expressible without new commands: a rule can expose its declared downloads through an output group, and building that group with `--repository_cache` pointed at a workspace directory populates exactly this content-addressed layout; `--repository_disable_download` then yields the offline build. What dedicated `bazel vendor` support adds over that pattern is enumeration *without analysis* (no configuration required), coverage guaranteed by the loading-phase declaration set rather than by rule authors remembering to expose an output group, and the provenance `MANIFEST`.

## Caching and invalidation

The **integrity checksum is the identity** of a download. `urls` are acquisition hints only. Concretely, the `Download` action's key is computed from `integrity`, `canonical_id`, and `executable` — the URL list is deliberately excluded:

* Changing `urls` while `integrity` is unchanged invalidates nothing; already-resolved artifacts stay valid (mirroring today's repository cache semantics). Swapping in a mirror list is free.
* Changing `integrity` yields a new identity: consumers re-execute, and the old blob ages out of caches.
* Changing `canonical_id` re-executes the download and bypasses download cache entries recorded under other IDs; since the content is still pinned by `integrity`, consumers only re-execute if the bytes actually differ (which would then fail verification).

Downloaded blobs live in the shared content-addressed download store alongside repository cache content, subject to the same garbage collection ([Garbage collection for the disk cache](https://docs.google.com/document/d/16aGm4u9EgW199M1WjjbVbVCJSfa8RApWPcKnZYnVbrI)); the store's current growth and multi-user sharing issues ([#22516](https://github.com/bazelbuild/bazel/issues/22516), [#17709](https://github.com/bazelbuild/bazel/issues/17709), [#13848](https://github.com/bazelbuild/bazel/issues/13848)) gain priority as it becomes a first-class build-time cache.

Download execution is deduplicated by `integrity` across all declaring targets: concurrent downloads of the same content are serialized in the shared download manager so the first fetch populates the content-addressed store and the rest hit it, and N targets declaring the same blob cost one fetch and one cache entry. Because the deduplication lives in the download manager rather than in the download action layer, repository fetches of the same content participate too — fixing the long-standing duplicate-concurrent-fetch behaviour of repository rules ([#12420](https://github.com/bazelbuild/bazel/issues/12420)) as a side effect.

## Introspection

Repository-rule downloads have `bazel mod show_repo` to expose their acquisition facts. For lazy downloads the equivalent surface is the action graph: `Download` actions are ordinary actions, so `bazel aquery` works on them, and the output must carry the facts a build engineer would otherwise have to reverse-engineer from Starlark. `Download` actions have no inputs and no command line by construction, so their aquery entries report the acquisition facts instead: the candidate `urls` in order, the `integrity` checksum, the canonical ID (when set), and the executable bit — in the text format and as first-class fields (`download_urls`, `download_integrity`, `download_canonical_id`, `is_executable`) in the proto/textproto/jsonproto formats.

```
$ bazel aquery 'mnemonic("Download", //...)'
action 'Downloading _downloads/node_modules/left-pad/left-pad'
  Mnemonic: Download
  Target: //:node_modules/left-pad
  ActionKey: ...
  Inputs: []
  Outputs: [bazel-out/.../_downloads/node_modules/left-pad/left-pad]
  URLs: [
    https://registry.npmjs.org/left-pad/-/left-pad-1.3.0.tgz
  ]
  Integrity: sha512-XI5MPzVNApjAyhQzphX8BkmKsKUxD4LdyK24iZeQGinBN9yTQT3bFlCBy/aVx2HrNcqQGsdot8ghrjyrvMCoEA==
  IsExecutable: false
```

Because declarations are an analysis-time product, querying requires no fetching: the details are reported for every declared download, including ones the build would never execute. Loading-phase enumeration — without configuring at all — is what vendoring adds (see [Open questions](#open-questions) for `bazel query` output and SBOM/BEP reporting built on it).

## Example: `npm_import`

A sketch of a package-manager rule using the API. One target per package, no repository per package, and the tarball fetch + extraction are both lazy and remote-executable:

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

With remote execution and Build without the Bytes, a clean build of a large `node_modules` tree touches the local network only for `FetchBlob` calls and action-cache lookups: tarballs land in the remote CAS, extraction runs remotely, and neither tarballs nor extracted trees are downloaded unless explicitly requested.

## Non-goals

* **Replacing repository rules.** Loading-phase needs — generating BUILD files, `http_archive`-style workspace bootstrap, toolchain autoconfiguration — remain repository-rule territory. This proposal covers content whose consumers are actions.
* **Archive extraction.** As the example shows, extraction is an ordinary action. A built-in extraction action or `FetchDirectory` support may be layered on later. Extraction being a regular (configured) action means an archive consumed in several configurations is extracted once per configuration; deduplicating that is the domain of the ongoing path-mapping work, not this proposal.
* **Unstable archives.** Content whose bytes are not stable across fetches (e.g. tarballs generated on the fly by `git archive`, whose compression can change; see [#19033](https://github.com/bazelbuild/bazel/issues/19033)) cannot be pinned by a byte-level SRI checksum and is out of scope. Such content needs a stable artifact (a release asset, a mirror) before it can be declared as a download.
* **Optional downloads.** No `allow_fail` equivalent; see [Execution semantics](#execution-semantics).
* **New authentication machinery.** Credential helpers and `--remote_downloader` authentication apply as-is.
* **Configuration-dependent download sets.** Deliberately excluded; see [Alternatives](#alternatives).

# Implementation phases

The reference implementation deliberately ships the API surface first and the execution-engine integration second. The API contract above (declaration semantics, action key, cache identity, aquery output) is stable across phases; what changes is how much of the resolution chain is wired up.

**Phase 1 (the current implementation):**

* `rule(downloads = ...)`, `downloads_ctx.download(...)` (including `canonical_id`), `ctx.downloads`, and `Download` actions with full aquery support, behind `--experimental_lazy_downloads`.
* Execution delegates to the existing `DownloadManager`, so downloads share the content-addressed download cache, `--distdir`, URL rewriting, netrc/credential-helper authentication, retries, `--repository_disable_download`, and `--experimental_remote_downloader` with repository fetches. Concurrent downloads of the same checksum are serialized inside the download manager, so the content is fetched once regardless of whether the requesters are download actions, repository fetches, or a mix.
* Phase-1 simplifications, all invisible to the Starlark API:
  * Download artifacts live under the configuration's output directory rather than the configuration-free root sketched above. The same declaration consumed in multiple configurations executes once per configuration; the network is still hit at most once (the download cache serves the rest), but the blob is materialised per configuration.
  * Downloads execute synchronously on action-execution threads (bounded additionally by `--http_max_parallel_downloads`) rather than on a dedicated pool, so a slow download occupies a `--jobs` slot.
  * A remote-downloader fetch still materialises the result locally; the Build-without-the-Bytes path (digest-only completion) is not yet wired up.

**Phase 2 (execution-engine integration):** the configuration-free artifact root with cross-configuration action sharing, the dedicated download pool, and digest-only completion under Build without the Bytes via the Remote Asset API.

**Phase 3 (workflow integration):** `bazel vendor` enumeration from the loading phase, the vendor-directory resolution step, and the loading-phase introspection listed in [Open questions](#open-questions).

The experimental phase exists precisely to validate that the phase-1 simplifications are unobservable through the API contract before any of it is stabilised.

# Backward-compatibility

All changes are additive and opt-in: a new optional `rule()` parameter, a new field on the rule implementation context, a new action type, and new subcommand behaviour for `bazel vendor`/`bazel fetch`. Existing rules, repository rules, and module extensions are unaffected. The API ships behind `--experimental_lazy_downloads`.

The main forward-compatibility risk is the artifact layout (`bazel-out/downloads/...`) becoming load-bearing for downstream tooling; the proposal treats the layout as unspecified, and the experimental phase should validate that nothing observable depends on it.

# Alternatives

## Status quo: repository rules with `--experimental_remote_downloader` and vendoring

Covers pieces of the motivation but composes poorly: downloads remain eager relative to the repository, content is always materialised locally, and per-file granularity requires per-file repositories (with the associated memory, lockfile, and invalidation costs). Vendoring snapshots repository directories rather than a content-addressed blob store.

## The remote repo contents cache

Bazel 9's `--experimental_remote_repo_contents_cache` (backport demand: [#29031](https://github.com/bazelbuild/bazel/issues/29031)) attacks the adjacent problem: it caches *evaluated repository contents* remotely so that a repository need not be re-fetched or re-evaluated on other machines. It is complementary rather than competing — it operates at repository granularity, keyed by the repository rule's inputs, and remains eager at loading time; content that hits the cache is still repository content, materialised (or virtualised) as a whole. This proposal operates below that layer, at blob granularity keyed by content, and moves acquisition out of the loading phase entirely. Repository rules that genuinely need loading-phase content keep benefiting from the contents cache; content whose consumers are actions stops needing a repository at all. The contents cache's early correctness issues with lazily materialised content and local actions ([#29656](https://github.com/bazelbuild/bazel/issues/29656)) also illustrate why this proposal keeps "artifact not materialised locally" confined to the same Build-without-the-Bytes machinery every other action output already uses, rather than inventing a second virtual-file mechanism.

## Analysis-time declaration: `ctx.actions.download()`

The most obvious API shape — declare downloads in the rule implementation like any other action. Rejected because it destroys the determinism property: the download set would depend on configuration (via configurable attributes, toolchains, and transitions), so enumerating "everything a build might download" would require configuring and analysing every target in every relevant configuration — and even then be incomplete for configurations not exercised. Vendoring, offline auditing, and SBOM extraction all degrade from "cheap loading-phase query" to "best-effort analysis sweep". It also invites URL computation from configuration state, which is exactly the nondeterminism this design excludes.

## A dedicated download rule

A built-in leaf rule (an evolution of `http_file` into a regular target) plus macros to compute URLs would also yield deterministic, lazy downloads. Rejected as the *only* mechanism because it forces every download to be a distinct target wired through providers: rulesets cannot keep acquisition as an implementation detail (every consumer's API grows a label attribute), and large dependency graphs pay a target-count and analysis-memory tax precisely where the npm-style use case needs it least. Note the two are not exclusive — a trivial `download_file` rule falls out of this proposal's API in a few lines of Starlark.

## Downloads as ordinary spawns

Running a downloader tool via `ctx.actions.run` works today but is a poor fit: sandboxes and remote executors rightly forbid or discourage network access; integrity verification is delegated to the tool rather than enforced by Bazel; the remote cache is consulted only via the action cache (keyed by the action, not the content); and there is no Remote Asset integration, vendor-directory resolution, or shared download store. Network-in-actions also undermines the assumptions remote execution services make about action hermeticity and retryability.

# Open questions

1. **`initializer` interaction.** Rule initializers rewrite attribute values at loading time. The intended semantics are that the `downloads` callback observes post-initializer values (initializers are themselves loading-phase and deterministic), but the evaluation-order contract needs to be pinned down, including whether an initializer may compute the attributes a `downloads` callback reads.
2. **Artifact root layout.** Whether download artifacts warrant a dedicated configuration-free root as sketched, or should reuse an existing root with configuration factored out.
3. **Aspects and symbolic macros.** Should aspects be able to declare downloads? Deferred from the initial scope.
4. **Loading-phase introspection.** Action-graph introspection is specified above, but the deterministic download set also invites loading-phase tooling that needs no analysis: `bazel query` output for declared downloads, BEP reporting, and SBOM generation. Worth specifying once the core lands.
