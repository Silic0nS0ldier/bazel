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
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.net.URI;

/** Strategy executing {@link DownloadAction}s. */
public interface DownloadActionContext extends ActionContext {

  /**
   * Downloads the content identified by {@code integrity} from one of {@code urls} into {@code
   * output}.
   *
   * <p>Implementations must verify the downloaded content against {@code integrity} (a Subresource
   * Integrity checksum) and are expected to consult content-addressed caches before touching the
   * network.
   *
   * @param urls candidate URLs, tried in order; all must serve identical content
   * @param integrity a Subresource Integrity checksum pinning the content
   * @param canonicalId if non-empty, restrict download cache hits to entries recorded with the
   *     same canonical ID
   * @param output the path to materialize the content at
   * @param context a human-readable description of the requester, for progress reporting
   */
  void download(
      ImmutableList<URI> urls, String integrity, String canonicalId, Path output, String context)
      throws IOException, InterruptedException;
}
