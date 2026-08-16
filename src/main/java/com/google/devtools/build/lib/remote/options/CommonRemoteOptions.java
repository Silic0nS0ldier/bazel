// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.options;

import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Converters.RegexPatternConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsClass;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.RegexPatternOption;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/** Options for remote execution and distributed caching that shared between Bazel and Blaze. */
@OptionsClass
public abstract class CommonRemoteOptions extends OptionsBase {
  @Option(
      name = "remote_download_regex",
      oldName = "experimental_remote_download_regex",
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      converter = RegexPatternConverter.class,
      help =
          "Force remote build outputs whose path matches this pattern to be downloaded,"
              + " irrespective of --remote_download_outputs. Multiple patterns may be specified by"
              + " repeating this flag.")
  public abstract List<RegexPatternOption> getRemoteDownloadRegex();

  // The TTL declared here is no longer enforced. Expired metadata used to force re-downloads and
  // invalidate action cache entries, but both behaviors were removed in favor of optimistically
  // reusing remote metadata and relying on build/action rewinding to recover when contents are
  // actually gone. Evidence;
  // - commit 23d03e1a06: expired outputs are no longer eagerly downloaded under BwoB.
  // - commit 50ca1b6147 (https://github.com/bazelbuild/bazel/issues/26140): expired metadata no
  //   longer invalidates action cache entries.
  // - commit 64d8f68237: the remaining TTL check was gated behind
  //   RemoteOutputChecker#setCheckMetadataTtl, which has no production callers.
  // Today this value only determines the (advisory) expiration stamped on remote output metadata
  // and the refresh cadence of --experimental_remote_cache_lease_extension. The avoidance of
  // repeated GetActionResult calls in incremental builds is unconditional and does not consult
  // this value.
  @Option(
      name = "experimental_remote_cache_ttl",
      defaultValue = "3h",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.EXECUTION},
      converter = RemoteDurationConverter.class,
      help =
          "The assumed minimal TTL of blobs in the remote cache after their digests are recently"
              + " referenced e.g. by an ActionResult or FindMissingBlobs. Bazel uses this value to"
              + " set the expiration time recorded on remote output metadata and to derive the"
              + " refresh frequency of --experimental_remote_cache_lease_extension. The TTL is not"
              + " otherwise enforced: Bazel optimistically keeps using remote metadata past its"
              + " expiration and relies on build or action rewinding to recover if contents have"
              + " actually been evicted. The value should be set slightly less than the real TTL"
              + " since there is a gap between when the server returns the digests and when Bazel"
              + " receives them.")
  public abstract Duration getRemoteCacheTtl();

  /** Returns the specified duration. Assumes seconds if unitless. */
  public static class RemoteDurationConverter extends Converter.Contextless<Duration> {

    private static final Pattern UNITLESS_REGEX = Pattern.compile("^[0-9]+$");

    @Override
    public Duration convert(String input) throws OptionsParsingException {
      if (UNITLESS_REGEX.matcher(input).matches()) {
        input += "s";
      }
      return new Converters.DurationConverter().convert(input, /* conversionContext= */ null);
    }

    @Override
    public String getTypeDescription() {
      return "An immutable length of time.";
    }
  }
}
