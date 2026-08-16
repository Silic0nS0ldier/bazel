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

import static com.google.common.truth.Truth.assertThat;

import build.bazel.remote.execution.v2.Digest;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.time.Duration;
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DiskCacheTrust}. */
@RunWith(JUnit4.class)
public class DiskCacheTrustTest {

  private static final String IDENTITY_A = "grpcs://a.example.com|instance";
  private static final String IDENTITY_B = "grpcs://b.example.com|instance";
  private static final Duration LEASE = Duration.ofHours(1);

  private final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final Path root = fs.getPath("/disk_cache");

  @Test
  public void freshCache_trustsOnRecencyAlone() throws Exception {
    var trust = DiskCacheTrust.create(root, IDENTITY_A, LEASE, new DiskCacheTrust.ServerState());

    assertThat(trust.mayTrust(Instant.now().minus(Duration.ofMinutes(5)))).isTrue();
    assertThat(trust.mayTrust(Instant.now().minus(Duration.ofHours(2)))).isFalse();
  }

  @Test
  public void unboundedLease_trustsRegardlessOfAge() throws Exception {
    var trust =
        DiskCacheTrust.create(
            root, IDENTITY_A, /* lease= */ null, new DiskCacheTrust.ServerState());

    assertThat(trust.mayTrust(Instant.EPOCH.plusMillis(1))).isTrue();
  }

  @Test
  public void newIdentityOnEstablishedCache_startsWithoutUnearnedTrust() throws Exception {
    var unused = DiskCacheTrust.create(root, IDENTITY_A, LEASE, new DiskCacheTrust.ServerState());
    Instant beforeCreation = Instant.now().minus(Duration.ofMinutes(5));

    var trustB = DiskCacheTrust.create(root, IDENTITY_B, LEASE, new DiskCacheTrust.ServerState());

    // Validation recency predating identity B's record must not be attributed to it.
    assertThat(trustB.mayTrust(beforeCreation)).isFalse();
    assertThat(trustB.mayTrust(Instant.now().plus(Duration.ofSeconds(5)))).isTrue();

    // Identity A's trust is unaffected.
    var trustA = DiskCacheTrust.create(root, IDENTITY_A, LEASE, new DiskCacheTrust.ServerState());
    assertThat(trustA.mayTrust(beforeCreation)).isTrue();
  }

  @Test
  public void firstViolation_suspendsWithoutPersisting() throws Exception {
    var serverState = new DiskCacheTrust.ServerState();
    var trust = DiskCacheTrust.create(root, IDENTITY_A, LEASE, serverState);

    trust.reportTrustedOriginViolation();

    // Suspended in memory for this server.
    assertThat(trust.mayTrust(Instant.now())).isFalse();
    // Same server state (e.g. next build in the same server) stays suspended.
    var sameServer = DiskCacheTrust.create(root, IDENTITY_A, LEASE, serverState);
    assertThat(sameServer.mayTrust(Instant.now())).isFalse();
    // A fresh server sees no persisted epoch: established trust survives.
    var freshServer =
        DiskCacheTrust.create(root, IDENTITY_A, LEASE, new DiskCacheTrust.ServerState());
    assertThat(freshServer.mayTrust(Instant.now().minus(Duration.ofMinutes(5)))).isTrue();
  }

  @Test
  public void continuedViolations_advanceEpochPersistently() throws Exception {
    var serverState = new DiskCacheTrust.ServerState();
    var trust = DiskCacheTrust.create(root, IDENTITY_A, LEASE, serverState);
    Instant beforeViolations = Instant.now().minus(Duration.ofMinutes(5));

    trust.reportTrustedOriginViolation();
    trust.reportTrustedOriginViolation();

    // A fresh server sees the persisted epoch: only newer validation is trusted.
    var freshServer =
        DiskCacheTrust.create(root, IDENTITY_A, LEASE, new DiskCacheTrust.ServerState());
    assertThat(freshServer.mayTrust(beforeViolations)).isFalse();
    assertThat(freshServer.mayTrust(Instant.now().plus(Duration.ofSeconds(5)))).isTrue();
  }

  @Test
  public void revokeAllNow_advancesEpochImmediately() throws Exception {
    var trust = DiskCacheTrust.create(root, IDENTITY_A, LEASE, new DiskCacheTrust.ServerState());
    Instant beforeRevocation = Instant.now().minus(Duration.ofMinutes(5));

    trust.revokeAllNow();

    var freshServer =
        DiskCacheTrust.create(root, IDENTITY_A, LEASE, new DiskCacheTrust.ServerState());
    assertThat(freshServer.mayTrust(beforeRevocation)).isFalse();
  }

  @Test
  public void revocationScopedToIdentity() throws Exception {
    var trustA = DiskCacheTrust.create(root, IDENTITY_A, LEASE, new DiskCacheTrust.ServerState());
    var trustB = DiskCacheTrust.create(root, IDENTITY_B, LEASE, new DiskCacheTrust.ServerState());
    Instant afterBothCreated = Instant.now().plus(Duration.ofSeconds(5));

    trustA.revokeAllNow();

    assertThat(trustB.mayTrust(afterBothCreated)).isTrue();
  }

  @Test
  public void trustedServeDigests_sharedViaServerState() throws Exception {
    var serverState = new DiskCacheTrust.ServerState();
    var trust = DiskCacheTrust.create(root, IDENTITY_A, LEASE, serverState);
    Digest digest = Digest.newBuilder().setHash("0123456789abcdef").setSizeBytes(42).build();

    trust.recordTrustedServeDigest(digest);

    var sameServer = DiskCacheTrust.create(root, IDENTITY_A, LEASE, serverState);
    assertThat(sameServer.isTrustedOrigin(digest)).isTrue();
    assertThat(sameServer.isTrustedOrigin(Digest.getDefaultInstance())).isFalse();
  }
}
