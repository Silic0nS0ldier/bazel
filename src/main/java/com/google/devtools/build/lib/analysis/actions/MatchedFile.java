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
package com.google.devtools.build.lib.analysis.actions;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.ExecutionResolvedArgument;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.starlarkbuildapi.MatchedFileApi;
import javax.annotation.Nullable;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * Opaque handle returned by {@code ctx.actions.match_file} and {@code
 * ctx.actions.match_directory}: resolves to exactly one file or one directory at execution time.
 *
 * @see FileMatchSpec
 */
@AutoCodec
public final class MatchedFile implements MatchedFileApi, ExecutionResolvedArgument {

  private final FileMatchSpec spec;

  @AutoCodec.Instantiator
  public MatchedFile(FileMatchSpec spec) {
    this.spec = spec;
  }

  public FileMatchSpec getSpec() {
    return spec;
  }

  public boolean isDirectory() {
    return spec.getCardinality() == FileMatchSpec.Cardinality.SINGLE_DIRECTORY;
  }

  /**
   * Renders the resolved file's (possibly path-mapped) exec path, used when this match is an
   * action's {@code executable}.
   *
   * <p>Resolution results are only reachable when the provider implements {@link
   * FileMatchResolver} — the consuming action wraps its provider before spawn construction,
   * and the match is auto-registered as an input match, so a lookup miss can only mean
   * analysis-time rendering (fingerprinting, aquery, progress messages). Those render the stable
   * placeholder form; the placeholder is also this argument's fingerprint contribution, while the
   * filter callback's identity is separately digested via the action's input-match specs.
   */
  @Override
  public String expandToCommandLine(
      @Nullable InputMetadataProvider inputMetadataProvider, PathMapper pathMapper)
      throws CommandLineExpansionException {
    if (inputMetadataProvider instanceof FileMatchResolver resolver) {
      ImmutableList<Artifact> resolved = resolver.getResolvedMatch(spec);
      if (resolved != null) {
        if (resolved.size() != 1) {
          throw new CommandLineExpansionException(
              "file match used as executable resolved to " + resolved.size() + " files");
        }
        return pathMapper.getMappedExecPathString(resolved.get(0));
      }
    }
    StringBuilder sb = new StringBuilder();
    spec.appendPlaceholder(sb);
    return sb.toString();
  }

  @Override
  public boolean isImmutable() {
    return true;
  }

  @Override
  public void repr(Printer printer, StarlarkSemantics semantics) {
    StringBuilder sb = new StringBuilder();
    spec.appendPlaceholder(sb);
    printer.append(sb.toString());
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof MatchedFile that && spec.equals(that.spec);
  }

  @Override
  public int hashCode() {
    return spec.hashCode();
  }
}
