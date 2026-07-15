---
created: 2026-07-10
last updated: 2026-07-15
status: Draft
reviewers: []
title: Download Validation
authors:
  - Silic0nS0ldier
discussion thread: TBD
---

# Abstract

Bazel treats a declared checksum as the canonical identity of a download.
Every cache layer (download cache, distdir, repository contents cache) serves content by checksum before any URL is contacted,
so a repository definition whose URL has been updated without updating the checksum keeps silently serving the old bytes,
and a mirror that never received a file is never noticed until the day the cache entry is gone.
This proposal adds fetch-integrated validation of download URLs:
each URL selected by policy is fetched and checked against the declared checksum at most once,
with successful validations recorded as small content-addressed records in the download cache and in the disk/remote CAS
so that the entire fleet skips revalidation of anything already proven.
The mechanism sits in the shared download layer,
covering repository rules, module extensions, registry downloads, and the `Download` actions of the sibling [Lazy Downloads](2026-07-03-lazy-downloads.md) proposal.
Each repository fetch additionally records a manifest of its downloads — carried into repository contents cache entries —
and enforcement is a universe-scoped fetch:
`bazel fetch --validate //app/...` verifies the downloads that universe depends on,
fetching only what the universe itself needs and never re-running implementation functions.
Because records live in the CAS and never in the action cache,
the design works in deployments where clients may not upload action results.

# Background

## Checksum-first caching masks definition rot

When a checksum is supplied, `DownloadManager` consults caches by checksum before touching the network;
the destination file itself, the download cache, and each distdir are all checked first,
and only on a complete miss is any URL contacted.
The repository contents cache sits a layer above and can skip the repository rule (and therefore the download call) entirely.

This is the correct behaviour for reproducibility — content identity is the checksum —
but it means declared URLs are effectively dead configuration once the content is cached anywhere.
Several real failure classes hide behind this;

* a dependency is bumped to a new URL but the checksum update is forgotten, and every machine with the old bytes cached keeps using them;
* a mirror is missing a file (never uploaded, or pruned), and the fall-through to upstream masks it until upstream also breaks;
* a URL is simply wrong (typo, moved host) and nobody notices until a cold-cache fetch fails at the worst possible time.

## Existing partial mitigation: default canonical IDs

`http_archive` and related rules in `@bazel_tools//tools/build_defs/repo` default `canonical_id` to a URL-derived string
(disabled via `BAZEL_HTTP_RULES_URLS_AS_DEFAULT_CANONICAL_ID=0`),
which restricts download cache hits to entries previously fetched from the same URL list.
This catches the "URL changed, checksum stale" case, but only partially;

* it applies solely to rules that opt in — direct `repository_ctx.download`/`download_and_extract` calls inside rulesets receive no protection;
* it gates only the local download cache, so protection is per-machine and lost on ephemeral CI;
* it exercises only the URL that wins the fetch, so mirror fall-through configurations remain untested;
* it works by cache-busting (forcing a refetch) rather than by validating, so an organisation pays the miss on every URL churn with no durable record of the result.

## Deployment constraint: no client-written action results

Some remote cache deployments only accept action cache writes from the remote execution service (an anti-tampering measure),
while CAS writes from clients remain permitted because CAS entries are content-addressed and self-verifying.
Any shared "already validated" record must therefore avoid the action cache,
or be produced by remote execution — which would in turn require executors to have network egress to every mirror and upstream host.
This proposal takes the CAS route.

## Prior art

* External validators that parse repository declarations out of the module graph and fetch-check them exist,
  but identifying repository rule types from the outside is fragile ([#24692](https://github.com/bazelbuild/bazel/issues/24692)),
  `repository_ctx.download` call sites inside rulesets are invisible to them,
  and they lack a sound store for "already validated" state.
* [Remote Downloads](https://github.com/bazelbuild/proposals/blob/main/designs/2020-01-14-remote-downloads.md) defined the Remote Asset API used by `--experimental_remote_downloader`;
  a Remote Asset service validates the URL it fetches server-side, but only the URL it happens to fetch, and only for clients routed through it.
* [Lazy Downloads](2026-07-03-lazy-downloads.md) (sibling proposal) moves action-consumed downloads out of repository rules and deliberately excludes `urls` from the `Download` action's key,
  making URL declarations pure acquisition hints that no cache layer ever revisits —
  the masking effect in its sharpest form, and the reason the two designs are built to compose (see [Lazy download actions](#lazy-download-actions)).

# Proposal

Add download validation to the fetch pipeline, behind new flags.
When enabled, every download with a declared checksum passing through `DownloadManager`
(`download`/`download_and_extract` from repository rules and module extensions, the `http_*` rules, Bazel module registry downloads, and lazy `Download` actions)
checks that each policy-selected URL has a validation record,
exercises the URLs that do not,
and records the outcome so no machine ever repeats a validation the fleet has already performed.

## When validation runs

Validation has no scheduler of its own;
it fires at the two moments Bazel already touches downloads, and only there;

* **Whenever a download executes.**
  Any `DownloadManager` call with a declared checksum — a repository rule or module extension mid-fetch, a registry download, a lazy `Download` action executing — validates its policy-selected URLs as part of that download.
  A fetch served from the repository contents cache executes no downloads,
  and instead validates from the manifest delivered with the entry (see [coexistence](#coexistence-with-the-repository-contents-cache)).
  This is the passive path: an ordinary `bazel build` that happens to fetch a stale repository validates that repository's downloads in passing.
* **When `bazel fetch --validate` runs.**
  A universe-scoped [enforcement fetch](#enforcement-bazel-fetch---validate):
  absent repositories of the universe are fetched as fetch already does (inline validation covering them),
  and up-to-date repositories are validated from their [download manifests](#validating-already-fetched-repositories-download-manifests) —
  implementation functions never re-run, and nothing is fetched that the universe does not itself need.
  [Lazy download declarations](#lazy-download-actions) validate as validation actions during ordinary builds.

Nothing else triggers validation.
A fully warm build — repositories materialised, download cache populated, `Download` action outputs present — performs no validation work at all
(whether manifest checks should also run opportunistically during builds is left as an open question).
Enforcement therefore means running `bazel fetch --validate` over a universe, as a CI job would.

## Validation records

A validation record asserts "this URL served content matching this checksum".
Its identity is the tuple;

* the effective URL — after `--experimental_downloader_config` rewriting, since that is what a fetch in this environment actually contacts;
* the declared checksum, normalised to Subresource Integrity form;
* a record format version, so the scheme can be revised without trusting stale records.

The record is a small deterministic document, for example;

```
bazel download validation record v1
uri: https://mirror.example.com/openjdk/21.0.3/openjdk-21.0.3_linux-x64.tar.gz
integrity: sha256-Zm9yIGRlbW9uc3RyYXRpb24gcHVycG9zZXMgb25seSE=
```

Because the content is deterministic, its digest is computable offline,
and "has this validation been performed" reduces to a content-addressed existence check.

Records are kept in two stores;

* **Download cache (local).**
  A marker file named by the record digest, stored alongside the checksum-keyed content entry,
  exactly as `canonical_id` markers (`id-<hash>`) are stored today.
  Markers share the content entry's lifecycle and are collected with it.
* **Disk and remote CAS (shared).**
  The record document is inserted as a CAS blob;
  presence (via `FindMissingBlobs`, batched across a fetch's URLs) means validated.
  This is legal in deployments that forbid client action cache writes,
  and read access alone is enough for a machine to benefit from records written elsewhere.

CAS eviction naturally bounds a shared record's lifetime,
which doubles as an organic revalidation cadence for URLs that may rot over time.
No timestamps appear in the record itself; determinism is what makes the scheme work.

## Fetch pipeline behaviour

With validation enabled, `DownloadManager` performs the following for a download with a declared checksum;

1. Rewrite URLs as today, producing the effective URL list.
2. Select the effective URLs matching the validation policy (see flags below).
3. For each selected URL, check for a validation record: the local marker file in the download cache first (a file stat), then disk/remote CAS presence.
4. URLs with records are considered validated and add no work.
5. URLs without records must be exercised in this fetch;
   * if the content itself is not cached, the first unvalidated URL doubles as the content fetch, verified against the checksum as today;
   * every other unvalidated URL is fetched and verified against the declared checksum, with the bytes discarded (the content is already identical by definition) and mismatching transfers aborted early where possible (see below);
   * fetches use the same downloader, credentials, headers, and retry machinery as ordinary downloads.
6. Each success writes the record to all available stores (the download cache's marker file always; CAS insertion best-effort — failures warn, never fail the fetch).
7. The normal checksum-first content flow then proceeds unchanged.

In the steady state every selected URL has a record and step 3 is the only addition:
a local file stat plus one batched `FindMissingBlobs` call.
A new (URL, checksum) pair — precisely the thing a repository update creates — has no record anywhere,
so it is exercised exactly once, fleet-wide, at the first fetch that sees it.

Concurrent fetches within one server instance deduplicate in-flight validations by record digest.

## Early mismatch detection

A validation fetch usually has reference content available:
the masking effect this proposal counters exists precisely because the checksum-keyed caches already hold bytes for the declared checksum,
and the download cache verifies an entry against its checksum on read.
When a verified reference is present, a mismatching URL can be rejected long before the transfer completes;

* if the response advertises a `Content-Length` that differs from the reference size, the URL fails after headers, before any body bytes are transferred
  (applicable only to identity transfer and content encoding — a compressed response reports the compressed size, and a chunked response reports none);
* otherwise the body is compared byte-for-byte against the reference as it streams, and the connection is dropped at the first divergence —
  for the common "URL now points at a newer version" mistake this typically triggers within the first kilobytes of a transfer that might otherwise be gigabytes.

Byte equality with a verified reference of equal length is equivalent to a checksum match, so no separate hash pass is needed.
Only failing URLs get cheaper:
a successful validation must observe every byte regardless of technique.
When no reference content is available locally, the fetch streams through a hash check exactly as an ordinary download does.

An aborted transfer cannot report the actual checksum of the remote content,
so early-detected mismatches are diagnosed by what is known —
the advertised size against the expected size, or the offset of first divergence —
alongside the URL and expected checksum.
These optimisations do not apply when fetching is delegated to a Remote Asset service, which transfers content on its own side of the wire.

## Failure handling

The two failure classes are deliberately treated differently;

* **Checksum mismatch is always an error.**
  The bytes are in hand and disagree with the declaration — this is deterministic evidence of the exact bug this proposal exists to catch,
  and is reported with the URL, the expected checksum, and the actual checksum
  (mirroring the existing mismatch diagnostic, which already suggests the likely cause).
* **Fetch failure (unreachable host, 404, exhausted retries) is governed by mode.**
  In `strict` mode it fails the fetch; in `tolerant` mode it warns and continues.
  This split exists because a fetch failure on a machine with cached content may be transient network trouble,
  which developers should not be blocked on, while CI enforcing mirror health wants a hard failure.

Downloads made with `allow_fail = True` are exempt from strict-mode fetch-failure errors —
the rule has declared that these URLs are expected to be unreliable —
but a checksum mismatch on bytes actually received still errors.

## Flags

```
--repository_download_validation=off|tolerant|strict   (default: off)
--repository_download_validation_urls=<regex>          (repeatable; default: match all)
```

`--repository_download_validation_urls` selects which effective URLs require validation in this environment;
multiple occurrences union.
This is what makes coverage composable per environment:
records are keyed by URL, so environments with different policies (and different rewriter configurations) build up disjoint record sets that never conflict.

An organisation mirroring its dependencies might configure;

```
# .bazelrc — everyone: passive protection, records are almost always already present
common --repository_download_validation=tolerant

# Merge queue builds: a changed definition is fetched by the build anyway;
# strict mode makes its inline validation gating
common:presubmit --repository_download_validation=strict
common:presubmit --repository_download_validation_urls=https://mirror\.example\.com/.*
```

The same flags govern enforcement fetches, composed via rc configs (e.g. `bazel fetch --validate --config=presubmit //app/...`).

Both flags would incubate under the `--experimental_` prefix.
Neither participates in repository fingerprinting:
validation changes no fetch output, so toggling it must not invalidate fetched repositories.

## Validating already-fetched repositories: download manifests

Validation as described so far runs when downloads run,
and a repository that is already materialised (or served whole from the repository contents cache) performs no downloads.
Re-executing every repository rule to rediscover its downloads (`bazel fetch --all --force`) works,
but pays the full cost of implementation functions — dependency resolution logic, `ctx.execute` calls, extraction — to recover facts the previous fetch already knew.

Instead, fetching records those facts.
Each repository fetch (and each module extension evaluation) writes a **download manifest**:
one entry per checksum-bearing `download`/`download_and_extract` call, recording;

* the original (pre-rewrite) URLs, in declaration order — rewriting is environment configuration, not a property of the definition, so the current rewriter is applied at validation time;
* the checksum, normalised to Subresource Integrity form;
* `allow_fail`, for failure-mode handling;
* whether the call carried explicit request `headers` (see below).

Manifests are written unconditionally, not only when validation is enabled:
they are cheap, additive, and recording from day one means an organisation's manifests already exist by the time it first enables validation.
The manifest is recorded alongside the repository's marker file and shares its lifecycle:
written on fetch success, replaced on refetch, discarded on invalidation.
It travels with the repository wherever the marker's recorded inputs do —
repository contents cache entries and vendored repositories carry it —
so a repository restored from a cache still knows its downloads without having run anything.

Manifests deliberately persist no secrets.
Authentication is re-derived at validation time from the live configuration (netrc, credential helpers, rewriter auth mappings), exactly as a fresh fetch would derive it.
A download issued with explicit `headers` is only *marked* as such:
its response may depend on header values the manifest must not hold,
so it is validated inline during real fetches and skipped, with a diagnostic note, by manifest-driven passes.
Downloads without a declared checksum do not appear at all — there is no declaration to validate against.

### Coexistence with the repository contents cache

A repository contents cache hit (local or remote) skips the implementation function entirely,
which without care would mean no download calls, no inline validation, and no manifest ever produced.
The manifest resolves this by being part of the cached entry:
it is recorded as an output of the fetch alongside the recorded inputs,
so an entry written by a validation-aware fetch delivers its manifest with the hit.
A cache-hit fetch then validates inline from the delivered manifest — record checks, plus exercising anything unvalidated — with the implementation function never running.
[Enforcement fetches](#enforcement-bazel-fetch---validate) read a checked repository's manifest from its contents cache entry without materialising anything;
remote cache hits deliver their manifest into the output base, where the same by-name lookup finds it.

An entry without a manifest (written by an older Bazel, or with validation disabled) cannot be validated;
in `strict` mode such an entry is treated as a cache miss —
the resulting real fetch validates inline and repopulates the cache with a manifest-bearing entry, a one-time self-healing cost —
while `tolerant` mode accepts the entry with a diagnostic note.
In deployments where the remote contents cache is unavailable because clients may not write action results,
the design carries over to fetch results produced via remote execution:
the manifest is an ordinary output of the remotely executed fetch.

## Enforcement: `bazel fetch --validate`

Enforcement is a universe-scoped fetch;

```
bazel fetch --validate --config=mirror //app/... //tools/toolchains/...
```

`bazel fetch <patterns>` already computes exactly the right universe —
the repositories the named targets need, discovered natively by Skyframe with no analysis-ordering machinery —
and already embodies the right cost model: absent repositories are fetched, present ones are not.
`--validate` strengthens the contract from "make the universe's external content present" to "present and correct";

* an absent repository is fetched as today, with inline validation covering its downloads as they happen;
* an up-to-date repository is validated from its manifest — recorded URLs through the current rewriter configuration into the validation engine, records checked, unvalidated URLs exercised — with the implementation function never re-run;
* a repository restored from the contents cache validates from the manifest delivered with the entry.

The checked set is easy to state: **the downloads the named universe depends on**.
No stale output-base history (a removed dependency is simply not in any universe),
no co-invocation subtleties,
and no new command surface — a flag on the command whose subject was always the universe's external content.
Nothing is fetched that the universe does not itself need,
and anything it does fetch was needed regardless: coverage establishment is the fetch the universe already implies.

Validation work is proportional to novelty, not universe size:
a fully validated universe costs manifest reads plus record presence checks,
and only definitions without a record anywhere in the fleet cause network traffic.

The division of labour with inline validation still holds;

* a changed definition is invalidated and fetched by the next build that needs it regardless — inline validation covers the changed set with no cost attributable to enforcement;
* the enforcement fetch exists for the *unchanged* set (mirror rot, evicted records), validated from manifests without re-running anything;
* a repository outside every named universe is never validated and costs nothing — content used only by other pipelines is covered by naming it in theirs.

### Reporting

Enforcement results are emitted as machine-readable output via;

```
--repository_download_validation_junit=<path>
```

writing JUnit XML in the dialect `bazel test` produces, because that is what CI systems already ingest for per-item results.
Results nest download-first, URL-second;

* one `<testsuite>` per download, named by its declaring context (canonical repository, module extension, or target label for lazy declarations) plus its integrity checksum;
* one `<testcase>` per candidate URL, with `classname` carrying the declaring context and `time` the transfer duration.

Outcomes map onto the JUnit vocabulary;

* **pass** — the URL was exercised and served matching content,
  or a validation record already covered it
  (a pass with zero time and the record source noted in `<system-out>`, on the same precedent as cached `bazel test` results reporting as passing);
* **`<failure>`** — a checksum mismatch, always fatal, carrying the diagnostic from [early mismatch detection](#early-mismatch-detection);
* **`<error>`** — a fetch failure that is fatal in the current configuration (`strict` mode, no `allow_fail`);
* **`<skipped>`** — the URL was not, or could not be, meaningfully exercised, with the reason in the message;
  a fetch failure that is non-fatal in the current configuration (`tolerant` mode, or a download declared with `allow_fail = True`);
  a URL excluded by the URL policy;
  a manifest entry marked as carrying explicit headers (inline-only validation).

The enforcement fetch fails when any case records a `<failure>` or `<error>`; skipped cases never fail it,
so `tolerant` runs stay green with skip counts while `strict` runs gate.
Validation is deduplicated by (URL, checksum) but reporting is not:
a shared outcome appears under every declaring suite, so each download reads as a complete unit.

```xml
<testsuites name="download-validation">
  <testsuite name="@@zlib+ (sha256-Zm9v…)" tests="3" failures="1" errors="0" skipped="1">
    <testcase classname="@@zlib+" name="https://mirror.example.com/zlib/zlib-1.3.1.tar.gz" time="0.412">
      <failure message="checksum mismatch">expected sha256-Zm9v…; stream diverged from the reference at byte 512 of 1381768</failure>
    </testcase>
    <testcase classname="@@zlib+" name="https://zlib.net/zlib-1.3.1.tar.gz" time="0">
      <system-out>validation record present (remote CAS)</system-out>
    </testcase>
    <testcase classname="@@zlib+" name="https://fallback.example.org/zlib-1.3.1.tar.gz" time="1.204">
      <skipped message="fetch failed (connection timed out); non-fatal in tolerant mode"/>
    </testcase>
  </testsuite>
</testsuites>
```

## Lazy download actions

The [Lazy Downloads](2026-07-03-lazy-downloads.md) proposal moves action-consumed content out of repository rules:
downloads are declared on rule targets via a loading-phase callback and executed on demand as `Download` actions,
resolving through the vendor directory, the download store, the Remote Asset API, and plain HTTP in cost order.
Its caching stance sharpens the problem this proposal addresses:
the `Download` action's key deliberately excludes `urls`, so changing them invalidates nothing at all.
That is the correct caching decision — the checksum is the identity — and it makes URL declarations pure hints that only a deliberate validation mechanism will ever revisit.
The two proposals are complementary halves of one position:
identity belongs to the checksum; validation is what keeps the acquisition hints true.

Both fetch paths share `DownloadManager`, so lazy downloads inherit validation at the same integration point, with the same records, flags, and URL policy;

* a `Download` action that executes checks records for its policy-selected URLs and exercises the unvalidated ones,
  regardless of which resolution step supplies the content —
  a vendor-directory or download-store hit does not skip validation, any more than a download cache hit does for a repository fetch;
* validation configuration does not enter the action key,
  matching its exclusion from repository fingerprints: toggling or retargeting validation invalidates no actions;
* a `Download` action that does not execute (output already materialised with matching digest) performs no validation,
  exactly as an already-materialised repository does not.

Declared downloads need no manifest — the declaration *is* the manifest, a loading-phase fact of the declaring target.
Their validation surface is Bazel's existing validation-action machinery:
a download-declaring target emits a validation action into the `_validation` output group,
checking its declared (URL, integrity) pairs through the same engine and records,
and running alongside whatever builds the target under `--run_validations` —
implicit, invocation-scoped, owned by the declaring target, with nothing to plant and nothing to schedule.
Ruleset macros can pair declaring targets with validation tests or expose per-package suites,
and reachability is native — the tests sit in the ordinary build graph and are selected by ordinary target patterns.
Neither the `Download` actions nor the content fetches run for validation:
the test exercises URLs through the validation engine and records, exactly as the repository tests do.

## Interaction with `--experimental_remote_downloader`

When downloads are delegated to a Remote Asset service, the client never contacts URLs itself.
Validation fetches issue a `FetchBlob` request for the single URL under test with the existing `checksum.sri` qualifier,
and set the `bazel.canonical_id` qualifier to the record identity
so that the asset service's own URI-keyed cache cannot satisfy the request from a fetch that predates the record scheme.
The returned digest is compared against the declared checksum as usual.
Validation records still gate the whole exchange, so the asset service is asked at most once per record.

## Non-goals

This proposal does not seek to solve;

* validation of downloads without a declared checksum (trust-on-first-use cases have no declaration to validate against);
* validity of non-download repository operations (`git_repository`, `repository_ctx.execute` side effects, patches);
* verification that extraction or the wider repository output is correct — this is download-layer only;
* detection of content changes behind an already-validated (URL, checksum) pair — revalidation cadence is supplied by cache eviction and scheduled enforcement runs, not by the record scheme;
* attestation against malicious cache clients — a CAS record asserts that *some* client with CAS write access performed the validation, which addresses definition rot, not adversaries (see Alternatives).

# Implementation status

The reference implementation covers the validation engine and download manifests; the test surface is not yet implemented.

**Engine.**
`--experimental_repository_download_validation={off,tolerant,strict}` and `--experimental_repository_download_validation_urls=<regex>` as specified.
Inline validation runs in `DownloadManager` ahead of any checksum-first cache consultation, covering `download`/`download_and_extract` from repository rules and module extensions.
Validation fetches deduplicate concurrent attempts by record digest,
verify by explicit hash comparison (cleanly separating fetch failures from mismatches),
populate the download cache on success (so the first validation fetch doubles as the content fetch, observed as exactly one network request in the integration test),
and honour `allow_fail` — fetch failures warn even under `strict`,
while a mismatch on received bytes still fails the download, which `allow_fail` call sites observe as a failed download rather than a build error.
Integration coverage: `//src/test/py/bazel:download_validation_test`
(stale-checksum masking demonstrated then caught, record skip across `clean --expunge`, tolerant/strict fetch-failure split, mirror fall-through, URL policy, `allow_fail`, manifest contents).

**Records.**
Download cache marker files (`validated-<record digest>` beside the checksum-keyed content entry) as specified,
plus the shared store: record documents inserted as disk/remote CAS blobs with presence checked via `FindMissingBlobs`,
wired through the same factory that provides the remote repository helpers.
Remote hits are localised as download cache markers;
insertion is best-effort and never fails a fetch;
store availability failures report absent, so the safe consequence is revalidation.
Integration-tested against a local remote worker: with local markers deleted and the output base expunged, the remote record alone prevents revalidation.

**Manifests.**
Written by every repository fetch as `@<canonical name>.downloads` beside the marker file, cleared and replaced with it.
Local repository contents cache entries carry the manifest as `<entry>.downloads` beside the recorded inputs file
(moved out of the output base together with the marker, deleted with the entry on GC),
so a repository restored from the cache still knows its downloads.
Not yet implemented: carriage in vendored repositories.

**Not yet implemented.**
The `--validate` fetch mode and its JUnit report;
validation actions for lazy download declarations;
atomic (write-then-rename) manifest writes;
manifests for module extension evaluations and remote contents cache manifest delivery;
registry download validation;
the Remote Asset validation path;
early mismatch detection — validation currently reads full transfers and therefore reports the actual checksum on mismatch,
incidentally providing the diagnostic that the early-abort open question trades away.

# Backward-compatibility

The feature defaults to `off`, with no behavioural change when disabled.
Records are purely additive artefacts in existing stores:
marker files in the download cache follow the established `canonical_id` marker pattern,
and CAS blobs are ordinary content-addressed entries requiring no server-side changes.
Download manifests are additive files with marker lifecycle;
repositories fetched without them (older Bazel, validation disabled) simply lack them,
and their validation tests report skipped rather than failing — the next real fetch produces one.
`bazel fetch` gains the `--validate` flag and the JUnit report flag,
and lazy declarations emit ordinary validation actions;
nothing is generated and no names are reserved.
Enabling validation can fail fetches that previously succeeded — that is its purpose — but only ever by surfacing a URL/checksum disagreement or (in strict mode) an unfetchable URL.
No repository fingerprint, lockfile, or marker file format changes are involved.

# Alternatives

## External validation tooling

Enumerate repository declarations from `bazel mod` output and fetch-check them out-of-band.
Rejected: repository rule types are hard to identify externally ([#24692](https://github.com/bazelbuild/bazel/issues/24692)),
downloads issued from inside ruleset implementations are invisible,
and the approach has no sound skip-tracking store
(repurposing test caching as a validation marker was the least-bad option found, and it is not a good one).
Fetch integration sees every `DownloadManager` call by construction.

## Extending default canonical IDs

Make URL-derived canonical IDs mandatory and universal.
Rejected: it still gates only the local download cache (per-machine, no shared record),
still validates only the winning URL,
and pays for URL churn with a full refetch on every machine rather than a single fleet-wide fetch-and-discard.

## Validation as a remote-executed action

Model "fetch URL, compare checksum" as a REAPI action executed remotely,
so the action cache entry is service-written and carries stronger trust.
Rejected for now: it requires remote executors to have network egress to every mirror and upstream (commonly firewalled),
there is no platform-selection story for repository-time work,
and the action is inherently unhermetic (the network is an undeclared input).
The record scheme does not preclude adding service-attested records later.

## Remote Asset API as the sole validator

Route all downloads through a Remote Asset service and treat its fetches as validation.
Rejected as the general answer: it depends on cache vendor support,
covers only environments configured to use it,
and the service's URI-keyed cache itself masks staleness unless actively busted.
It is instead supported as a backend for validation fetches where deployed.

## Client-written action cache entries

Store validation results as synthetic action results, as the remote repository contents cache does.
Rejected: violates the deployment constraint this design must satisfy —
action cache writes are reserved to the remote execution service in the deployments of interest.

## Enforcement by fetching

Drive enforcement through the fetch pipeline itself:
`bazel fetch --all` to visit every repository (materialising any that are absent), or `--force` to re-run implementation functions and rediscover downloads.
Rejected: repository fetches are inherently expensive —
implementation functions run dependency resolution, `ctx.execute`, and extraction, and materialisation moves whole trees —
and that cost is paid to recover facts a previous fetch already observed.
An enforcement pass that forces fetching is unusable at exactly the scale it is needed.
Fetching remains the explicit escape hatch for bringing a not-yet-fetched repository into validation coverage.

## Enforcement without a universe

Drive enforcement from recorded metadata alone:
a walk of every manifest in the output base and the contents caches, behind a flag, a command, or a test rule.
Rejected: the walked universe is output-base *history* rather than workspace state,
so manifests of removed dependencies linger indefinitely and fail strict runs as false positives,
and under interleaved analysis and execution the walk races fetching.
A named universe states what will be checked, and the fetch command computes it natively.

## Test-rule enforcement surfaces

Model enforcement as `bazel test` targets:
either a Bazel-generated synthetic repository with one validation test per repository,
or a workspace-planted `download_validation_test` rule whose checked set comes from the configured graph.
Both were earlier forms of this design, and each teaches a rejection;

* the synthetic repository needs generation machinery, a reserved repository name, and module-graph enumeration;
* the planted rule deletes those but strains the test model —
  its action must wait for analysis to complete and traverse the invocation's graph (novel, impure machinery),
  a standalone suite invocation checks nothing without a `universe` attribute,
  and the universe-plus-co-invocation split makes "what will be checked" hard to state.

Every property the test interface was chosen for — CI-ingestible per-item reports, scoped invocations, rc-config policy — is available on the fetch surface,
whose command owns universe computation and phasing natively.

## Spawn-based validation tests

Implement the validation tests as ordinary spawns running a Bazel-shipped validator tool.
Rejected: sandboxes and remote executors rightly restrict network access,
the downloader environment (rewriter configuration, credential helpers, netrc, the Remote Asset path) would have to be exported into and re-implemented by the tool,
and test caching would shadow state the record scheme already tracks at finer grain.
Native execution keeps validation inside the machinery that owns those concerns,
at the cost of the novel non-spawn test action.

## Loading-time enumeration of validation targets

Generate a repository with one validation target per *download*, baking URLs and checksums into BUILD files.
Rejected: download facts are not loading-phase data for arbitrary repository rules, so generation must either
depend on the subject repositories (forcing the fetches validation must avoid),
read manifests from the output base (impure loading state with undefined invalidation — nothing regenerates the targets when a repository refetches),
or parse repository definitions (the external-validator problem, [#24692](https://github.com/bazelbuild/bazel/issues/24692)).
Execution-time manifest discovery avoids all three:
nothing is generated, and the download facts are read when the test runs.

## Recording download facts in the lockfile

Persist per-repository download facts in `MODULE.bazel.lock` so any machine can validate without fetching — or even without a warm output base.
Rejected: downloads are observed at fetch time, after the lockfile is written,
so this demands a write-back cycle the lockfile deliberately does not have;
it grows a shared, merge-conflict-prone artefact by one entry per download;
and repositories outside the Bzlmod lockfile's purview are not represented at all.
The output-base manifest yields the same skip-refetch property with fetch-local lifecycle and no shared-artefact cost,
at the price of requiring each enforcement environment to have fetched (or cache-restored) the repositories once.

## Deriving validation targets from the module registry

For registry-sourced module archives, `source.json` already states URLs and integrity,
so a validator could read the registry without touching repositories at all.
Rejected as the mechanism: it covers only module source archives,
missing every download issued inside ruleset implementation functions — the coverage gap that motivated fetch integration in the first place.
Registry-declared downloads are covered regardless, through the manifests of the fetches they configure.

## Declarative downloads for repository rules

A `downloads`-callback equivalent on `repository_rule()`, mirroring [Lazy Downloads](2026-07-03-lazy-downloads.md),
would make repository download sets a loading-phase product and manifests unnecessary.
Rejected as a requirement: repository rules routinely compute URLs inside their implementation functions (resolution logic, probing, `ctx.execute` output),
so a declaration callback cannot express the general case,
and where it can, migrating the download to a rule-level lazy declaration is the better end state anyway.
Manifests capture what actually happened, which works for every repository rule as written today;
a future declarative surface would simply produce its manifest at loading time.

# Open questions

* Whether registry downloads (`downloadAndReadOneUrlForBzlmod`) should be included from the start or follow once the mechanism settles;
  they flow through the same manager and the cost of inclusion is small.
* Whether `tolerant` should be the eventual default once records are widely populated,
  giving every user passive protection at near-zero cost.
* Whether the URL selection policy needs exclusion patterns in addition to inclusion patterns
  (Java regex negative lookahead makes exclusion expressible today, if awkwardly).
* Whether an early-aborted mismatch should optionally complete the transfer (perhaps bounded by size) to report the actual checksum,
  which is the value a developer needs to fix the declaration when the URL is the intended one.
* Whether manifest-driven validation of up-to-date repositories should also run opportunistically during ordinary builds
  (the record checks are cheap; the concern is surprising network traffic outside explicitly validating commands).
