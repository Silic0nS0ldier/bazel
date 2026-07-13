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

package com.google.devtools.build.lib.bazel.repository.downloader;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * A download manifest: the checksum-bearing downloads observed during a repository fetch (or
 * module extension evaluation), recorded so later validation passes can exercise the URLs without
 * re-running the implementation function.
 *
 * <p>Deliberately recorded facts only: original (pre-rewrite) URLs and the checksum in Subresource
 * Integrity form. No credentials or header values are ever persisted; a download issued with
 * explicit headers is only marked as such (its response may depend on values the manifest must not
 * hold, so it is validated inline only).
 *
 * <p>Serialised as a line-oriented, tab-separated format. Tabs cannot appear in valid URLs or any
 * other recorded field.
 */
public final class DownloadManifest {

  private static final String HEADER = "bazel download manifest v1";

  /** One checksum-bearing download call. */
  public record Entry(
      ImmutableList<String> urls, String integrity, boolean allowFail, boolean hasHeaders) {

    public static Entry of(
        List<URI> urls, Checksum checksum, boolean allowFail, boolean hasHeaders) {
      return new Entry(
          urls.stream().map(URI::toString).collect(ImmutableList.toImmutableList()),
          checksum.toSubresourceIntegrity(),
          allowFail,
          hasHeaders);
    }
  }

  private DownloadManifest() {}

  public static String serialize(List<Entry> entries) {
    StringBuilder builder = new StringBuilder(HEADER).append('\n');
    for (Entry entry : entries) {
      builder
          .append(entry.integrity())
          .append('\t')
          .append(entry.allowFail())
          .append('\t')
          .append(entry.hasHeaders());
      for (String url : entry.urls()) {
        builder.append('\t').append(url);
      }
      builder.append('\n');
    }
    return builder.toString();
  }

  /** Parses a serialised manifest; empty if the content is not a known manifest format. */
  public static Optional<ImmutableList<Entry>> parse(String content) {
    var entries = ImmutableList.<Entry>builder();
    boolean headerSeen = false;
    for (String line : Splitter.on('\n').split(content)) {
      if (line.isEmpty()) {
        continue;
      }
      if (!headerSeen) {
        if (!line.equals(HEADER)) {
          return Optional.empty();
        }
        headerSeen = true;
        continue;
      }
      List<String> fields = Splitter.on('\t').splitToList(line);
      if (fields.size() < 3) {
        return Optional.empty();
      }
      entries.add(
          new Entry(
              ImmutableList.copyOf(fields.subList(3, fields.size())),
              fields.get(0),
              Boolean.parseBoolean(fields.get(1)),
              Boolean.parseBoolean(fields.get(2))));
    }
    if (!headerSeen) {
      return Optional.empty();
    }
    return Optional.of(entries.build());
  }
}
