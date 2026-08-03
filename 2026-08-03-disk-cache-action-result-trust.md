---
created: 2026-08-03
last updated: 2026-08-03
status: Draft
reviewers: []
title: Disk Cache Action Result Trust
authors:
  - Silic0nS0ldier
---

# Abstract

When a disk cache and Build without the Bytes are used together, disk cache action results are routinely discarded because the outputs they reference were (by design) never downloaded.
Every such discard costs a remote cache round trip.
This proposal allows action results in the disk cache to be served on a time-bounded trust that referenced blobs remain available remotely, mirroring the trust Bazel already extends to the remote cache and relying on the same recovery machinery when that trust is violated.

# Background

## Build without the Bytes and the Disk Cache

With `--remote_download_outputs=minimal` (BwoB; the sibling mode `toplevel` has been the default for remote builds since Bazel 7) action outputs are not downloaded unless needed.
Output metadata is recorded instead, and content is fetched on demand;
- when a local action consumes a remote-backed input;
- when a file is required locally (e.g. requested top-level outputs, `--remote_download_regex`).

When `--disk_cache` is also set, the disk cache is consulted before the remote cache.
A disk cache action result is only served if every blob it references is present in the local CAS.
This integrity check protects against dangling references (e.g. after garbage collection), but under BwoB it fails by design;
referenced blobs were never downloaded, so nearly every disk lookup degrades into a remote `GetActionResult` round trip
(the remote result is then written back to the disk cache, only for the same rejection to repeat once metadata must be revalidated).

For workloads dominated by cache hits, these round trips are the main remaining per-action network cost.
A [code comment](https://github.com/bazelbuild/bazel/blob/8fafe31c6b/src/main/java/com/google/devtools/build/lib/remote/CombinedCache.java#L240-L246) anticipates solving this with a lease service that tracks blob liveness;
this proposal offers a solution that requires no additional infrastructure.

## Existing Lifetime Mechanisms

Several mechanisms already reason about how long cached content remains available, and not all of them work as documented;

- **Server-side retention.**
  The remote cache's actual eviction policy, and the REAPI expectation that a served action result references live blobs.
  This is the ground truth that every client-side mechanism approximates.
- **`--experimental_remote_cache_ttl` (default 3h).**
  Declares the minimal TTL of blobs after their digests are recently referenced.
  Its documented role ("Bazel does several optimizations based on the blobs' TTL") no longer matches the implementation;
  expired metadata once forced re-downloads and invalidated action cache entries, but both behaviours were removed in favour of optimistic reuse backed by recovery, with the expectation that
  "build or action rewinding will take care of rerunning the actions needed to produce the file"
  ([source](https://github.com/bazelbuild/bazel/blob/8fafe31c6b/src/main/java/com/google/devtools/build/lib/remote/RemoteOutputChecker.java#L370-L374); commits `23d03e1a06` and `50ca1b6147`, the latter fixing [#26140](https://github.com/bazelbuild/bazel/issues/26140); the residual check survives only as a test hook with no production callers, commit `64d8f68237`).
  Today the value stamps an advisory expiration on remote output metadata and sets the lease extension cadence;
  the avoidance of repeated `GetActionResult` calls in incremental builds is unconditional optimism, not TTL arithmetic.
- **Per-output expiration metadata.**
  Recorded when outputs are accepted without downloading, persisted in the local action cache, and advanced by lease extension;
  nothing else reads it.
- **`--experimental_remote_cache_lease_extension`.**
  The only active keep-alive;
  periodically re-references remote-backed outputs via `FindMissingBlobs` (refreshing their server-side TTL) and advances their recorded expiration.
  It runs only while a build is in progress, is unavailable when `--rewind_lost_inputs` is enabled, and when it discovers an already-missing blob it merely declines to extend it — invalidation is left to recovery.
- **Reactive correction.**
  The recovery machinery described below — the only lifetime mechanism that is actually enforced.
  A blob's client-side lifetime ends when its absence is proven, not when a clock expires.

The direction of travel is clear;
time-based enforcement has been progressively dismantled in favour of optimistic reuse with reactive correction, and declared TTLs have been downgraded to advisory bookkeeping.
The disk cache integrity check is the last place where the client still enforces liveness pessimistically, despite serving results that originated from the same remote.
This proposal moves the disk cache onto the model the rest of the stack already uses — trust optimistically, bound the damage with a time window, correct reactively — reusing the declared TTL contract and the existing recovery machinery rather than introducing a parallel lifetime system.

## Recovery Machinery

Bazel can already recover when a blob referenced by an accepted action result turns out to be unavailable;
- a missing blob discovered while fetching the outputs of the action result itself is treated as a cache miss, and the action simply executes;
- a missing blob discovered later (e.g. prefetching inputs for a downstream action) raises a lost input, recovered by action rewinding (`--rewind_lost_inputs`) or by automatic invocation retries (`--experimental_remote_cache_eviction_retries`, default 5);
- digests confirmed missing are tracked, and the next cache lookup referencing one ignores the cached result and re-executes instead, regenerating and re-uploading the blobs;
  retry limits (`--experimental_remote_cache_eviction_retries`, `--experimental_max_repeated_lost_inputs`) bound pathological cases.

This machinery is agnostic to which cache supplied the action result.

## Disk Cache Garbage Collection

Disk cache entries record their last store or retrieval time via `mtime`, which drives garbage collection
(`--experimental_disk_cache_gc_max_size`, `--experimental_disk_cache_gc_max_age`, or an external process).
On a served action result the entry and its referenced blobs are all marked as recently used, entry first,
so that collection in least-recently-used order cannot create dangling references.
Any tooling that manages disk cache content is already expected to preserve these semantics.

# Proposal

Introduce a flag declaring how long a disk cache action result may be trusted after it was last validated (defined under [Trust Evaluation](#trust-evaluation));

```
--experimental_disk_cache_action_result_trust=off|remote-ttl|<duration>|unbounded
```

- `off` (default) preserves current behaviour;
  action results referencing locally missing blobs are ignored.
- `remote-ttl` trusts for the value of `--experimental_remote_cache_ttl`.
  This is the recommended setting, as the claim being trusted — "blobs referenced by this action result remain available remotely" — is the guarantee that flag describes for entries validated against the remote.
- `<duration>` trusts for an explicit duration, for operators whose remote cache retains blobs for longer (or shorter) than the remote TTL suggests.
- `unbounded` trusts indefinitely, for operators whose remote cache never evicts within the lifetime of a disk cache entry.

Example;

```ini
# //.bazelrc
common --disk_cache=~/.cache/bazel-disk
common --remote_cache=grpcs://cache.example.com
common --remote_download_outputs=minimal
common --experimental_disk_cache_action_result_trust=remote-ttl
```

## Lookup Behaviour

The integrity check always runs, and its side effects are preserved;
referenced blobs that are present locally continue to be marked as recently used.
Trust changes only what happens when the check finds blobs missing;

1. The action result is read from the disk cache.
2. Referenced blobs are checked for local presence.
3. All present: served as today.
4. Some missing: the entry's trust is evaluated (see below);
   - trusted: the action result is served, and is treated like a remote cache hit — outputs not selected for download are recorded as remote-backed metadata, and later fetches fall back to the remote CAS for locally missing content;
   - not trusted: the entry is ignored and lookup falls back to the remote cache, whose result (if any) is written back to the disk cache — re-validating the entry (unchanged behaviour).

Trust never changes how fetch failures are handled — only whether the lookup accepts the entry.
When trust is enabled, an action result entry is never marked more recently used than its locally present referenced blobs,
preserving the ordering that protects least-recently-used collection from creating dangling references.

## Trust Evaluation

An action result with locally missing blobs is trusted when all of the following hold;
- the remote cache is readable for the spawn under evaluation
  (its effective read policy, accounting for `--noremote_accept_cached` and per-spawn tags such as `no-remote-cache`);
  without a remote CAS to fall back to, the premise of the trust does not hold;
- the entry was validated after the trust epoch of the invocation's remote identity (see below);
- trust for that identity is not suspended by an unresolved violation (see below);
- the time since the entry was last validated is within the configured trust duration.

An entry counts as validated when;
- it is written back after a remote cache hit (a remote reference, matching the `--experimental_remote_cache_ttl` guarantee);
- it is written after local execution whose result was also accepted for upload to the remote cache;
- its integrity check passes in full (every referenced blob present locally — local evidence only, see [Risks](#risks)).

An entry served on trust is deliberately **not** marked as recently used, and does not count as validated;
otherwise every trusted serve would extend its own trust, making the configured duration meaningless.
The remote fallback taken once trust lapses re-validates the entry and restores its recency in one step.
For the same reason, output metadata recorded from a trusted serve carries an expiry measured from the entry's last validation rather than from the serve itself,
so staleness does not compound across incremental builds.

> [!NOTE]
> Validation recency is observed via the entry's `mtime`, consistent with how the disk cache already tracks recency for garbage collection.
> This makes mtime preservation a hard requirement for environments that populate disk caches externally (see [Externally Managed Disk Caches](#externally-managed-disk-caches)).

## Trust Epoch and Remote Identity

The disk cache carries trust state comprising one record per remote cache identity;
- the identity — at least the canonical cache endpoint and `--remote_instance_name`, excluding credentials and transport configuration;
- a trust epoch — a point in time before which no validation is trusted for that identity.

The state is persistent, shared by every process using the cache, and must not be subject to garbage collection
(a practical constraint on placement, as collectors in existing Bazel versions spare only their own bookkeeping locations).
An invocation consults and updates only the record matching its configured remote;
concurrent builds against different remotes (e.g. separate workspaces sharing one disk cache) therefore do not contend, and neither degrades the other's trust.
Invocations with trust disabled, or without a readable remote cache, leave the state untouched.

A record is created the first time an identity is used;
- with no epoch when the trust state is empty (a fresh cache, or one restored without its trust state) — trust is evaluated on validation recency alone;
- with an epoch of the creation time when other identities are already recorded — the cache demonstrably served another remote, so existing validation recency is not attributed to the new identity.

This makes switching remote cache backends safe without manual intervention;
the new identity starts with no unearned trust and entries re-validate against it organically, while the previous identity's record is retained so switching back preserves its accumulated trust.

Entries do not record which remote validated them;
an entry validated against one remote may therefore be trusted by an invocation against another whose epoch predates that validation.
Such misplaced trust is bounded by recovery, which advances the affected identity's epoch.

**Proven violations suspend, then revoke, trust.**
Revocation is evidence-gated so that an isolated violation does not erode legitimately established trust.
When recovery is triggered by a blob that was accepted on trust;
1. trust for the identity is suspended in memory for the remainder of the server's builds — lookups fall back to remote re-validation, which is strictly more conservative than trusting;
2. the persistent epoch is advanced only if violations continue (e.g. a further trusted-origin loss surfaces), indicating the remote has genuinely lost content rather than a one-off eviction or misplaced cross-identity trust.

A written epoch advance revokes all outstanding trust for that remote at once — persistently, surviving server restarts — so a systemic problem
(e.g. a remote cache wipe) costs at most one round of recovery before lookups degrade gracefully to re-validation, without disturbing trust held against other remotes.
An isolated incident instead costs one recovery round plus re-validated lookups for the remainder of the session, leaving persistent trust intact;
the entries proven stale are corrected by recovery itself, as their re-execution or re-validation overwrites them.

Revocation scope is controlled by;

```
--experimental_disk_cache_action_result_trust_revocation=precise|all|off
```

- `precise` (default) applies the evidence-gated behaviour above to losses that entered the build via a trusted serve.
  A genuine remote eviction of a conventionally validated result does not void the disk cache's trust, and neither does an isolated trusted-origin violation.
- `all` advances the epoch immediately on any lost input.
  Suits operators who consider any eviction a signal of remote cache distress.
- `off` relies on per-digest tracking and natural trust expiry alone.

Distinguishing trusted-origin blobs requires provenance tracking, which is maintained for the lifetime of the server process.
A violation whose provenance is unknown — such as one surfacing via metadata persisted by an earlier server — is treated as trusted-origin, keeping revocation conservative.

## Interactions

### Recovery

Trust converts a per-lookup network cost into an occasional recovery cost, so a recovery mechanism should be available.
The defaults already provide one (`--experimental_remote_cache_eviction_retries=5`);
action rewinding (`--rewind_lost_inputs`) recovers with less waste where enabled.
When trust is enabled but both mechanisms are disabled, a warning is emitted.

Existing recovery behaviour needs no changes;
a digest confirmed missing already causes the next lookup referencing it to ignore the cached result and re-execute regardless of which cache supplied it, and retry limits bound the remaining pathological cases.

### Garbage Collection

Because trusted serves do not refresh recency, an entry that is only ever served on trust ages towards collection;
its `mtime` lags by at most the trust duration for entries in active use.
Collection of such an entry costs one remote round trip to re-validate — the status quo cost — making this self-correcting.

Operators should configure `--experimental_disk_cache_gc_max_age` (where set) to exceed the trust duration;
a warning is emitted when it does not.

### Remote Lease Extension

`--experimental_remote_cache_lease_extension` periodically re-references remote-backed outputs, extending their server-side TTL during long sessions.
Outputs accepted via trusted serves are remote-backed metadata like any other and benefit equally.
The two features are complementary;
trust removes lookup round trips, lease extension keeps the trusted claim true for longer
(often for the very blobs disk entries reference, since both describe the same outputs).
Note that lease extension is currently unavailable in combination with `--rewind_lost_inputs`.
Lease extension also discovers already-missing blobs, which it currently only declines to extend;
feeding these discoveries into trust revocation is a possible future refinement.

### Externally Managed Disk Caches

Environments that populate disk caches out-of-band (e.g. CI cache restore, dev machine warming) must preserve entry mtimes;
`tar` does so by default, `rsync` requires `-t`, and object storage download tooling frequently does not.
This requirement is not new — garbage collection ordering already depends on mtime fidelity — but the stakes rise from suboptimal eviction to unearned trust.
Restore tooling that resets mtimes grants fresh trust to entries of unknown age;
recovery bounds the damage, but such environments should run their first build with trust `off` or fix their tooling.
A restored cache older than the trust duration is safe;
everything re-validates, costing round trips only.
Restore tooling should also carry the trust state alongside the entries;
a cache restored without it forfeits recorded revocations and remote identities, not trust itself.

### Caches That Do Not Guarantee Blob Liveness

Some remote caches (notably HTTP caches) do not ensure referenced blobs exist when serving an action result ([#18696](https://github.com/bazelbuild/bazel/issues/18696)).
Against such backends, validation is a weaker signal and violations may occur within the trust duration.
The recovery path is identical;
operators of such backends should prefer shorter durations.

### Residual Remote Reads

A trusted serve eliminates the `GetActionResult` round trip, but not every remote read;
- tree artifact outputs require their directory metadata blob, fetched remotely when absent locally;
- non-empty stdout/stderr is always fetched;
- outputs selected for download (e.g. `toplevel` outputs, `--remote_download_regex` matches) are fetched from the remote when absent locally.

All are equally true of remote cache hits today, and disk CAS write-through means repeat lookups often have these blobs locally.

## Out of Scope

This proposal does not seek to solve;

- Tracking blob liveness via a dedicated lease service, per the [existing code comment](https://github.com/bazelbuild/bazel/blob/8fafe31c6b/src/main/java/com/google/devtools/build/lib/remote/CombinedCache.java#L240-L246).
  Trust reduces the need for one; a future lease service could supersede or refine trust evaluation without changing this surface.
- Trusting disk cache entries when no remote cache is configured.
  Without a remote CAS the referenced content is unavailable, and a "successful" build could produce unmaterialisable outputs.
- Per-entry provenance (recording *which* remote validated an entry, or whether its blobs were ever uploaded remotely).
  See [Risks](#risks).
- Changes to remote cache trust semantics (`--experimental_remote_cache_ttl` behaviour is unchanged).

## Risks

**Trust anchored on local evidence.**
A full integrity pass extends an entry's validation recency without referencing the remote,
so an entry whose blobs sat in the local CAS for months can, once they are collected, be served on trust the remote never re-affirmed.
Collection ordering mitigates this — an entry is never fresher than its locally present blobs, so least-recently-used collection removes the entry no later than the blobs whose absence would invoke trust — but external interference with mtimes can still produce it.
Relatedly, a disk cache accumulates entries whose blobs were never uploaded remotely;
purely local builds sharing the cache, read-only remote caches, `--noremote_upload_local_results`, and `no-remote-cache`-tagged actions all produce them.
Counting local execution as validation only when the result was accepted for remote upload closes most of that population, with the integrity-pass gap above remaining.
The window is narrow — while the blobs remain local the integrity check passes and trust is not consulted — and violations recover through the standard path.
Closing it fully requires per-entry provenance, deferred until field data shows it matters.

**Mixed-version and mixed-mode cache sharing.**
Bazel versions predating this proposal (and processes running with trust `off`) mark action result entries as recently used on every retrieval, including retrievals whose integrity check fails.
Shared caches therefore have entry recency inflated towards last *use* rather than last *validation*, weakening the trust anchor.
The effect is bounded by how often such processes touch entries that trusting processes rely on, and the correctness backstop is unchanged.

**Violation storms.**
A remote cache incident (mass eviction, data loss) surfaces as a burst of lost inputs on machines holding trusting disk caches.
Each machine pays one round of recovery, revokes trust via its epoch, and degrades to re-validation.
Recovery from any lost input also discards all remote-backed metadata from Bazel's caches, so the retry re-validates every cached action, not just the affected one;
with the epoch freshly advanced, that re-validation runs against the remote, restoring trust as it proceeds.

# Backward-compatibility

All changes are opt-in and additive;
the default (`off`) preserves current behaviour exactly.
The trust state is new bookkeeping inside the disk cache that older Bazel versions ignore;
its absence forfeits recorded revocations and remote identities, not trust itself.
No disk cache entry format changes are made;
caches remain fully shareable across Bazel versions and trust configurations, with the mixed-mode caveat noted in [Risks](#risks).

# Alternatives Considered

## Removing the Integrity Check

An earlier shape of this idea skipped the integrity check entirely (`full|<duration>|none`).
Rejected because the check's side effects are load-bearing;
- present blobs are marked recently used, preserving garbage collection's no-dangling-references ordering for content that is local;
- running the check first confines trust to entries whose blobs are actually missing (the BwoB shape), so purely local usage retains today's semantics and the [provenance risk](#risks) stays narrow.

The check is cheap (local `stat` calls), so retaining it costs little.

## Lease Service

A lease service tracking blob liveness (as the existing code comment envisions) would give precise, per-blob answers rather than a time-based heuristic.
It also requires standing infrastructure, integration surface, and its own availability story.
Trust delivers most of the benefit with none of the infrastructure, and does not preclude a lease service later;
the two can coexist, with a lease service simply providing a better trust oracle.

## Remote Existence Checks Instead of Trust

On integrity check failure, Bazel could issue a `FindMissingBlobs` call for the referenced digests instead of falling back to `GetActionResult`.
This also refreshes the blobs' server-side TTL, and batches well.
Rejected as the primary mechanism because it retains a per-lookup network dependency — the cost this proposal exists to remove — and adds no trust window for subsequent builds.
It may have value later as a cheaper re-validation step once trust lapses.

## Per-Entry Validation Records

Recording validation time and remote identity inside each entry (or a sidecar) would decouple trust from mtime and close the provenance gap.
A format-compatible embedding even exists;
`ActionResult.execution_metadata.auxiliary_metadata` is a `repeated google.protobuf.Any`, so a validation record could be attached at write-back while the entry remains a well-formed `ActionResult` that any Bazel version parses unchanged.
Rejected nonetheless;
- every entry grows and every write-back pays serialisation on top of today's byte-for-byte store — overhead running counter to the proposal's goal;
- the field is specified for worker-provided execution details, and client bookkeeping written into it would surprise tooling that inspects cache content;
- sidecar variants double entry counts, and concurrent writers would need coordination the current layout avoids;
- mtime anchoring plus the trust epoch covers the same needs with acceptable precision, with evidence-gated revocation containing the cost of the residual imprecision.

Worth revisiting if field data shows provenance-related violations are common.

## Filesystem `atime` as the Recency Signal

Using `atime` to separate "last used" from "last validated" (`mtime`) was considered and rejected;
the disk cache already deliberately avoids `atime` as it is unreliable (`noatime`/`relatime` mounts) and externally perturbed.
