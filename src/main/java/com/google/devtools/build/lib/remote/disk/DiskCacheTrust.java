// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote.disk;

import static java.nio.charset.StandardCharsets.UTF_8;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Trust state for disk cache action results whose referenced blobs are missing locally (the normal
 * state under Build without the Bytes), governed by
 * {@code --experimental_disk_cache_action_result_trust}.
 *
 * <p>An action result may be served despite locally missing blobs if it was validated recently
 * enough that the blobs can be assumed to remain available in the remote CAS. Validation recency is
 * anchored on the entry's mtime, which is reset whenever the entry is written (local execution
 * upload, remote cache write-back) or its integrity check passes in full. Serving on trust
 * deliberately does not refresh the mtime, so trust cannot extend itself.
 *
 * <p>The persistent state is one record per remote cache identity, stored under {@code gc/trust}
 * (excluded from garbage collection alongside the GC lock). An invocation only consults and
 * updates the record for its own identity, so concurrent builds against different remotes sharing
 * one disk cache do not degrade each other's trust. Each record carries a trust epoch: a point in
 * time before which no validation is trusted for that identity.
 *
 * <p>Revocation is evidence-gated: the first proven violation (a blob accepted on trust later
 * found missing remotely) only suspends trust in memory for the remainder of the server's builds,
 * which degrades lookups to remote re-validation. The persistent epoch is advanced only if
 * violations continue, indicating a systemic problem rather than a one-off eviction or misplaced
 * cross-identity trust.
 */
public final class DiskCacheTrust {

  private static final String TRUST_DIR = "gc/trust";

  /**
   * Server-lifetime trust state, shared across commands (like the known-missing digest tracking in
   * RemoteExecutionService). Holds per-identity violation counts (a nonzero count suspends trust)
   * and the digests that entered builds via trusted serves, used to attribute lost inputs to
   * trusted-origin metadata.
   */
  public static final class ServerState {
    private final ConcurrentHashMap<String, AtomicInteger> violationsByIdentity =
        new ConcurrentHashMap<>();
    private final Set<Digest> trustedServeDigests = ConcurrentHashMap.newKeySet();

    private AtomicInteger violations(String identity) {
      return violationsByIdentity.computeIfAbsent(identity, unused -> new AtomicInteger());
    }
  }

  private final Path trustDir;
  private final Path recordPath;
  private final String identity;
  @Nullable private final Duration lease;
  private final ServerState serverState;

  // Null means no epoch: all validation recency within the lease is trusted.
  @Nullable private volatile Instant epoch;

  private DiskCacheTrust(
      Path trustDir,
      Path recordPath,
      String identity,
      @Nullable Duration lease,
      ServerState serverState,
      @Nullable Instant epoch) {
    this.trustDir = trustDir;
    this.recordPath = recordPath;
    this.identity = identity;
    this.lease = lease;
    this.serverState = serverState;
    this.epoch = epoch;
  }

  /**
   * Loads or creates the trust record for the given remote cache identity.
   *
   * @param cacheRoot the root of the disk cache
   * @param identity the remote cache identity (canonical endpoint and instance name)
   * @param lease how long a validation remains trusted, or null for unbounded
   */
  public static DiskCacheTrust create(
      Path cacheRoot, String identity, @Nullable Duration lease, ServerState serverState)
      throws IOException {
    Path trustDir = cacheRoot.getRelative(TRUST_DIR);
    trustDir.createDirectoryAndParents();
    Path recordPath =
        trustDir.getChild(Hashing.sha256().hashString(identity, UTF_8).toString().substring(0, 16));

    Instant epoch = readEpoch(recordPath);
    if (epoch == null && !recordPath.exists()) {
      // A new identity on a cache that demonstrably served another remote must not inherit the
      // existing entries' validation recency; one on a fresh cache (or one restored without its
      // trust state) trusts on recency alone.
      epoch = hasOtherRecords(trustDir, recordPath) ? Instant.now() : null;
      writeRecord(trustDir, recordPath, identity, epoch);
    }
    return new DiskCacheTrust(trustDir, recordPath, identity, lease, serverState, epoch);
  }

  /** Returns whether an entry last validated at the given time may be served on trust. */
  public boolean mayTrust(Instant lastValidated) {
    if (isSuspended()) {
      return false;
    }
    Instant epoch = this.epoch;
    if (epoch != null && !lastValidated.isAfter(epoch)) {
      return false;
    }
    if (lease == null) {
      return true;
    }
    return !lastValidated.plus(lease).isBefore(Instant.now());
  }

  /** Records that the given digest entered the build via a trusted serve. */
  public void recordTrustedServeDigest(Digest digest) {
    serverState.trustedServeDigests.add(digest);
  }

  /** Returns whether the given lost digest entered a build via a trusted serve. */
  public boolean isTrustedOrigin(Digest digest) {
    return serverState.trustedServeDigests.contains(digest);
  }

  /**
   * Reports a proven violation of trusted-origin metadata. The first violation suspends trust in
   * memory for the remainder of the server's builds; continued violations advance the persistent
   * epoch, revoking all outstanding trust for this identity.
   */
  public void reportTrustedOriginViolation() {
    if (serverState.violations(identity).incrementAndGet() >= 2) {
      advanceEpoch();
    }
  }

  /** Immediately and persistently revokes all trust for this identity (revocation mode 'all'). */
  public void revokeAllNow() {
    serverState.violations(identity).incrementAndGet();
    advanceEpoch();
  }

  private boolean isSuspended() {
    return serverState.violations(identity).get() > 0;
  }

  private synchronized void advanceEpoch() {
    Instant now = Instant.now();
    epoch = now;
    try {
      writeRecord(trustDir, recordPath, identity, now);
    } catch (IOException e) {
      // The in-memory epoch still applies for this server; persistence is best-effort.
    }
  }

  @Nullable
  private static Instant readEpoch(Path recordPath) throws IOException {
    byte[] content;
    try {
      content = FileSystemUtils.readContent(recordPath);
    } catch (FileNotFoundException e) {
      return null;
    }
    String[] lines = new String(content, UTF_8).split("\n", -1);
    if (lines.length < 2 || lines[1].isEmpty() || lines[1].equals("-")) {
      return null;
    }
    try {
      return Instant.ofEpochMilli(Long.parseLong(lines[1].trim()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static boolean hasOtherRecords(Path trustDir, Path recordPath) throws IOException {
    for (Dirent dirent : trustDir.readdir(Symlinks.NOFOLLOW)) {
      if (dirent.getType().equals(Dirent.Type.FILE)
          && !dirent.getName().contains(".tmp")
          && !trustDir.getChild(dirent.getName()).equals(recordPath)) {
        return true;
      }
    }
    return false;
  }

  private static void writeRecord(
      Path trustDir, Path recordPath, String identity, @Nullable Instant epoch) throws IOException {
    Path temp = trustDir.getChild(recordPath.getBaseName() + ".tmp" + UUID.randomUUID());
    byte[] content =
        (identity + "\n" + (epoch == null ? "-" : Long.toString(epoch.toEpochMilli())) + "\n")
            .getBytes(UTF_8);
    try (OutputStream out = temp.getOutputStream()) {
      out.write(content);
    }
    temp.renameTo(recordPath);
  }
}
