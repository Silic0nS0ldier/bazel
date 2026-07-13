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

package com.google.devtools.build.lib.remote;

import static java.nio.charset.StandardCharsets.UTF_8;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadValidationRecordStore;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext.CachePolicy;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.protobuf.ByteString;
import java.io.IOException;

/**
 * Download validation records in the disk/remote CAS.
 *
 * <p>Records are content-addressed blobs: presence (via {@code FindMissingBlobs}) means the (URL,
 * checksum) pair the record describes has been validated somewhere in the fleet. CAS writes are
 * legal even in deployments that reserve action cache writes to the remote execution service.
 */
final class DownloadValidationRecordStoreImpl implements DownloadValidationRecordStore {

  private final CombinedCache cache;
  private final String buildRequestId;
  private final String commandId;
  private final boolean acceptCached;

  DownloadValidationRecordStoreImpl(
      CombinedCache cache, String buildRequestId, String commandId, boolean acceptCached) {
    this.cache = cache;
    this.buildRequestId = buildRequestId;
    this.commandId = commandId;
    this.acceptCached = acceptCached;
  }

  private RemoteActionExecutionContext buildContext() {
    var metadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "download-validation-record");
    return RemoteActionExecutionContext.create(metadata)
        .withReadCachePolicy(acceptCached ? CachePolicy.ANY_CACHE : CachePolicy.NO_CACHE)
        // Record blobs are content-addressed, so writing them is safe for both caches.
        .withWriteCachePolicy(CachePolicy.ANY_CACHE);
  }

  @Override
  public boolean hasRecord(String recordDocument) throws InterruptedException {
    Digest digest = cache.digestUtil.compute(recordDocument.getBytes(UTF_8));
    try {
      return Utils.getFromFuture(
              cache.findMissingDigests(buildContext(), ImmutableList.of(digest)))
          .isEmpty();
    } catch (IOException e) {
      // Availability failure: report absent so the caller revalidates rather than skips.
      return false;
    }
  }

  @Override
  public void putRecord(String recordDocument) throws IOException, InterruptedException {
    byte[] bytes = recordDocument.getBytes(UTF_8);
    Digest digest = cache.digestUtil.compute(bytes);
    Utils.getFromFuture(cache.uploadBlob(buildContext(), digest, ByteString.copyFrom(bytes)));
  }
}
