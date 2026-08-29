// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.buildeventstream;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId.FetchId.Downloader;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.protobuf.util.Durations;
import java.time.Duration;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * A {@link BuildEvent} reporting that an external resource was fetched.
 *
 * <p>Events of this class will only be generated in builds that do the actual fetch, not in ones
 * that use a cached copy of the resource to download. In way, these events allow keeping track of
 * the access of external resources.
 *
 * @param error human-readable description of the failure, or null if the fetch succeeded
 * @param attempts number of attempts made against the URL, or 0 if unknown
 * @param bytesRead number of payload bytes transferred across all attempts
 * @param duration wall time spent on the URL, including retries and any wait for a download slot,
 *     or null if unknown
 */
public record FetchEvent(
    String url,
    Downloader downloader,
    boolean success,
    @Nullable String error,
    int attempts,
    long bytesRead,
    @Nullable Duration duration)
    implements BuildEvent, ExtendedEventHandler.Postable {

  /** Creates an event for a fetch about which nothing beyond its outcome is known. */
  public FetchEvent(String url, Downloader downloader, boolean success) {
    this(url, downloader, success, /* error= */ null, 0, 0, /* duration= */ null);
  }

  @Override
  public BuildEventId getEventId() {
    return BuildEventIdUtil.fetchId(url, downloader);
  }

  @Override
  public Collection<BuildEventId> getChildrenEvents() {
    return ImmutableList.of();
  }

  @Override
  public BuildEventStreamProtos.BuildEvent asStreamProto(BuildEventContext converters) {
    BuildEventStreamProtos.Fetch.Builder fetch =
        BuildEventStreamProtos.Fetch.newBuilder()
            .setSuccess(success)
            .setAttempts(attempts)
            .setBytesRead(bytesRead);
    if (error != null) {
      fetch.setError(error);
    }
    if (duration != null) {
      fetch.setDuration(Durations.fromNanos(duration.toNanos()));
    }
    return GenericBuildEvent.protoChaining(this).setFetch(fetch.build()).build();
  }
}
