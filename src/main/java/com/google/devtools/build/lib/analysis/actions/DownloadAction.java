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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.net.URI;
import javax.annotation.Nullable;

/**
 * An action that materializes a lazily declared download.
 *
 * <p>The download is content-addressed by a Subresource Integrity checksum, making the action
 * hermetic: given the same key, the output bytes are always identical. Execution is delegated to
 * the registered {@link DownloadActionContext}, which consults content-addressed caches before
 * fetching from the network.
 */
public final class DownloadAction extends AbstractAction {
  private static final String GUID = "e2f57f1c-32e0-4c9e-a5da-f7872bd06067";

  private final ImmutableList<URI> urls;
  private final String integrity;
  private final String canonicalId;
  private final boolean executable;

  public DownloadAction(
      ActionOwner owner,
      Artifact output,
      ImmutableList<URI> urls,
      String integrity,
      String canonicalId,
      boolean executable) {
    super(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), ImmutableSet.of(output));
    this.urls = urls;
    this.integrity = integrity;
    this.canonicalId = canonicalId;
    this.executable = executable;
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    DownloadActionContext downloadActionContext =
        actionExecutionContext.getContext(DownloadActionContext.class);
    if (downloadActionContext == null) {
      throw createException(
          "no download strategy is registered in this build environment", /* cause= */ null);
    }
    try {
      downloadActionContext.download(
          urls, integrity, canonicalId, executable, getPrimaryOutput(), actionExecutionContext);
      // The strategy may have resolved the output without materializing it locally (Build without
      // the Bytes); the executable bit only applies to a file that exists.
      Path outputPath = actionExecutionContext.getInputPath(getPrimaryOutput());
      if (executable && outputPath.exists()) {
        outputPath.setExecutable(true);
      }
    } catch (IOException e) {
      throw createException(
          String.format(
              "failed to download %s (from %s): %s", getPrimaryOutput().prettyPrint(), urls, e.getMessage()),
          e);
    }
    return ActionResult.EMPTY;
  }

  private ActionExecutionException createException(String message, @Nullable Throwable cause) {
    DetailedExitCode code =
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setExecution(Execution.newBuilder().setCode(Execution.Code.DOWNLOAD_FAILURE))
                .build());
    return cause == null
        ? new ActionExecutionException(message, this, /* catastrophe= */ false, code)
        : new ActionExecutionException(message, cause, this, /* catastrophe= */ false, code);
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable InputMetadataProvider inputMetadataProvider,
      Fingerprint fp) {
    // The integrity checksum is the identity of the download; urls are acquisition hints only.
    // Deliberately excluded from the key so that changing mirrors invalidates nothing.
    fp.addString(GUID);
    fp.addString(integrity);
    fp.addString(canonicalId);
    fp.addBoolean(executable);
  }

  @Override
  public String getMnemonic() {
    return "Download";
  }

  /** Returns the candidate URLs of this download, in the order they are tried. */
  public ImmutableList<URI> getUrls() {
    return urls;
  }

  /** Returns the Subresource Integrity checksum pinning the content of this download. */
  public String getIntegrity() {
    return integrity;
  }

  /**
   * Returns the canonical ID restricting download cache hits, or the empty string if cache hits
   * are unrestricted.
   */
  public String getCanonicalId() {
    return canonicalId;
  }

  /** Returns whether the output is marked executable after the download. */
  public boolean isExecutable() {
    return executable;
  }

  @Override
  protected String getRawProgressMessage() {
    return "Downloading " + getPrimaryOutput().prettyPrint();
  }

  @Override
  public ImmutableMap<String, String> getExecProperties() {
    return ImmutableMap.of();
  }
}
