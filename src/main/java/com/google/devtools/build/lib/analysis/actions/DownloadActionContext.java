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
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import java.io.IOException;
import java.net.URI;

/** Strategy executing {@link DownloadAction}s. */
public interface DownloadActionContext extends ActionContext {

  /**
   * Resolves the content identified by {@code integrity} into the action's primary output.
   *
   * <p>Implementations must verify the content against {@code integrity} (a Subresource Integrity
   * checksum) at every network boundary and are expected to consult content-addressed stores (the
   * vendor directory, the download cache) before touching the network. An implementation may
   * resolve the output without materializing it locally by injecting metadata into the execution
   * context's output metadata store (Build without the Bytes).
   *
   * @param urls candidate URLs, tried in order; all must serve identical content
   * @param integrity a Subresource Integrity checksum pinning the content
   * @param canonicalId if non-empty, restrict download cache hits to entries recorded with the
   *     same canonical ID
   * @param executable whether the output is marked executable
   * @param action the executing download action; its primary output is the artifact to resolve,
   *     and progress is attributed to it
   * @param actionExecutionContext the executing action's context
   */
  void download(
      ImmutableList<URI> urls,
      String integrity,
      String canonicalId,
      boolean executable,
      ActionExecutionMetadata action,
      ActionExecutionContext actionExecutionContext)
      throws IOException, InterruptedException;
}
