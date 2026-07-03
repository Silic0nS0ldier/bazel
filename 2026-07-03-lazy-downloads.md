---
created: 2026-07-03
last updated: 2026-07-03
status: Draft
reviewers: []
title: Lazy Downloads
authors:
  - Silic0nS0ldier
discussion thread: TBD
---

# Abstract

Today the only sanctioned way to bring remote files into a build is through repository rules and module extensions, which download eagerly during the fetch/loading phase and always materialise content on the local machine. This proposal introduces a first-class download primitive for regular rules: downloads are *declared* deterministically alongside the rule definition, and *executed* lazily during the execution phase like actions. A download that is not needed never happens; a download whose content is already present in the disk cache, remote cache (as a CAS blob), or a vendor directory is skipped; and with the [Remote Asset API](https://github.com/bazelbuild/remote-apis/blob/main/build/bazel/remote/asset/v1/remote_asset.proto) plus remote execution, downloaded content may never touch the local machine at all.

Because download declarations are independent of build configuration, the complete set of downloads for any set of targets is computable from the loading phase alone. This makes vendoring cheap and sound: `bazel vendor` can enumerate and fetch every declared download into a directory, from which builds can then run entirely offline.

# Background

## How downloads work today

All remote file acquisition flows through `repository_ctx.download` and `repository_ctx.download_and_extract`, invoked from repository rules (usually via module extensions). This has several structural consequences:

1. **Downloads are eager relative to their consumers.** A repository is fetched the first time anything in it is needed, and fetching a repository means running the whole repository rule — every file it downloads is downloaded, whether or not the requesting build needs it. Granularity can be recovered by splitting into one repository per file (as `http_file` and rules like `rules_js`'s npm import extension do), at the cost of thousands of repositories, lockfile bloat, and per-repository memory and invalidation overhead.

2. **Content always lands on the local machine.** Repository contents must exist on local disk before analysis of consuming targets can complete. With remote execution and Build without the Bytes, this is pure waste: the file is only ever needed as an *input to a remote action*, which requires just a digest reference to a CAS blob. `--experimental_remote_downloader` lets a Remote Asset service perform the download, but the result is still materialised into the local repository directory.

3. **Caches are consulted too late, and the wrong ones.** The repository cache avoids *re*-downloading by checksum, but plays no part in deciding whether a download is needed. A file already present in the remote cache as a CAS blob (because a previous build uploaded it) is still downloaded locally.

4. **Vendoring operates at repository granularity.** `bazel vendor` (from the [Offline & Vendor Modes proposal](https://docs.google.com/document/d/1P9WwRvpGLi9Tw-AKN7dZ2AeRmfVsl_-lH-N9g3UkVMI)) snapshots entire repository directories, including generated files that are cheap to recreate, and must run the repository rules to do so.

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
* `ctx.download(path, urls, integrity, executable = False)` — declares one download.
  * `path` (string, mandatory): the key under which the rule implementation retrieves the artifact, unique within the target, and the trailing component of the artifact's exec path.
  * `urls` (list of strings, mandatory): candidate URLs, tried in order; all must serve identical content.
  * `integrity` (string, mandatory): a [Subresource Integrity](https://www.w3.org/TR/SRI/) checksum (`sha256-`, `sha384-`, or `sha512-`). There is deliberately no unpinned mode: the checksum is the download's identity, and everything else in this proposal (cache skipping, vendoring, CAS interop, determinism) hangs off it.
  * `executable` (bool): whether the resulting artifact is marked executable.

Note that `configurable = False` is today only accepted on symbolic macro attributes; rule definitions reject it. This proposal lifts that restriction for rule attributes: `attr.*(configurable = False)` becomes legal in `rule()` and marks the attribute non-configurable (`select()` on it is a loading-phase error), exactly as it already does for native rule attributes and macro attributes.

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

Laziness makes over-declaration free at build time — unconsumed variants are never fetched — while `bazel vendor` intentionally fetches all of them, which is exactly what an offline mirror needs.

## Download artifacts

Each declaration produces a *download artifact*: an ordinary `File` usable as an action input, in runfiles, providers, `DefaultInfo`, etc. Conceptually it is a generated file whose generating action is a built-in `Download` action owned by the declaring target.

Because declarations are configuration-independent, download artifacts live under a configuration-free output root, tentatively:

```
bazel-out/downloads/<canonical repo>/<package>/<target name>/<path>
```

Physical storage is content-addressed (see [Caching](#caching-and-invalidation)); the exec-path location is a hardlink or symlink into that store. Identical declarations (same `integrity`) across any number of targets and configurations share one blob and at most one fetch. The exact layout is an implementation detail; the load-bearing requirements are that the exec path is stable across configurations and derived from the declaring label plus `path`.

In the rule implementation, `ctx.downloads` is an immutable string-keyed mapping from declared `path` to `File`.

## Execution semantics

A `Download` action executes only when its artifact is actually demanded — as an input to an action that runs, or as a requested top-level output. Loading and analysis never block on downloads, and a target whose downloads are declared but not consumed in a given build fetches nothing.

When a `Download` action does execute, resolution proceeds in cost order, stopping at the first success:

1. **Output tree / action cache** — artifact already materialised with matching digest: no-op.
2. **Vendor directory** — if `--vendor_dir` is set and contains the blob (keyed by integrity), link it in.
3. **Local download store** — the content-addressed store shared with the repository cache; hit means link, no network.
4. **Remote cache CAS presence check** — when a remote cache is configured and the blob's REAPI `Digest` is already known (recorded from a previous resolution; a `Digest` is hash plus size, so it cannot be derived from `integrity` alone): if the blob exists remotely and outputs need not be materialised locally (Build without the Bytes), resolution completes with metadata only. The artifact participates in remote action execution as a digest reference, exactly like any other remote-only output.
5. **Remote Asset API** — when `--remote_downloader` is configured, issue `Fetcher.FetchBlob` with the declared `urls` and a `checksum.sri` qualifier carrying `integrity`. The service downloads into the remote CAS and returns the digest. Under Build without the Bytes the content never reaches the local machine; this is the intended steady state for remote-execution builds.
6. **Local HTTP download** — fall back to fetching `urls` in order with the existing downloader machinery (credential helpers, retries, proxies). Verify `integrity`, populate the local download store, and upload to the remote cache if one is configured (respecting `--remote_upload_local_results`).

Integrity verification is unconditional at every network boundary. A mismatch is a permanent, non-retryable action failure attributed to the declaring target, reporting the URL, expected, and actual checksums.

Downloads are not spawns: they have no execution platform, no strategy, and are not sandboxed. They run on a dedicated downloader pool with their own concurrency limit (analogous to `--http_max_parallel_downloads`... exact flag naming TBD), and report progress through the standard action UI and BEP as `Download` events.

Offline semantics follow existing conventions: with network-less operation requested (e.g. `--nofetch`... exact spelling TBD during implementation), steps 5–6 are disabled and a download that reaches them fails with an error naming the vendor/cache options.

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

The **integrity checksum is the identity** of a download. `urls` are acquisition hints only:

* Changing `urls` while `integrity` is unchanged invalidates nothing; already-resolved artifacts stay valid (mirroring today's repository cache semantics).
* Changing `integrity` yields a new identity: consumers re-execute, and the old blob ages out of caches.

Downloaded blobs live in the shared content-addressed download store alongside repository cache content, subject to the same garbage collection ([Garbage collection for the disk cache](https://docs.google.com/document/d/16aGm4u9EgW199M1WjjbVbVCJSfa8RApWPcKnZYnVbrI)).

In Skyframe, download execution is keyed by `integrity` and shared across all declaring targets, so N targets declaring the same blob cost one fetch and one cache entry.

## Introspection

Repository-rule downloads have `bazel mod show_repo` to expose their acquisition facts. For lazy downloads the equivalent surface is the action graph: `Download` actions are ordinary actions, so `bazel aquery` works on them, and the output must carry the facts a build engineer would otherwise have to reverse-engineer from Starlark. `Download` actions have no inputs and no command line by construction, so their aquery entries report the acquisition facts instead: the candidate `urls` in order, the `integrity` checksum, and the executable bit — in the text format and as first-class fields (`download_urls`, `download_integrity`, `is_executable`) in the proto/textproto/jsonproto formats.

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
* **Archive extraction.** As the example shows, extraction is an ordinary action. A built-in extraction action or `FetchDirectory` support may be layered on later.
* **New authentication machinery.** Credential helpers and `--remote_downloader` authentication apply as-is.
* **Configuration-dependent download sets.** Deliberately excluded; see [Alternatives](#alternatives).

# Backward-compatibility

All changes are additive and opt-in: a new optional `rule()` parameter, a new field on the rule implementation context, a new action type, and new subcommand behaviour for `bazel vendor`/`bazel fetch`. Existing rules, repository rules, and module extensions are unaffected. The API ships behind `--experimental_lazy_downloads`.

The main forward-compatibility risk is the artifact layout (`bazel-out/downloads/...`) becoming load-bearing for downstream tooling; the proposal treats the layout as unspecified, and the experimental phase should validate that nothing observable depends on it.

# Alternatives

## Status quo: repository rules with `--experimental_remote_downloader` and vendoring

Covers pieces of the motivation but composes poorly: downloads remain eager relative to the repository, content is always materialised locally, per-file granularity requires per-file repositories (with the associated memory, lockfile, and invalidation costs), and the remote cache is never consulted before downloading. Vendoring snapshots repository directories rather than a content-addressed blob store.

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
