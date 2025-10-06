// Copyright 2025 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails;
import java.io.IOException;

public class CopyAction extends AbstractAction {
  private final String GUID = ""; // TODO(b/377851343): generate a real GUID

  private final TargetType targetType;
  private final String progressMessage;
  private final Artifact input;
  private final Artifact output;

  enum TargetType {
    FILE,
    EXECUTABLE,
    DIRECTORY
  }

  public static CopyAction copyFile(
      ActionOwner owner,
      Artifact input,
      Artifact output,
      Boolean isExecutable,
      String progressMessage
  ) {
    return new CopyAction(
        owner,
        input,
        output,
        progressMessage,
        isExecutable ? TargetType.EXECUTABLE : TargetType.FILE);
  }

  public static CopyAction copyDirectory(
      ActionOwner owner,
      Artifact input,
      Artifact output,
      String progressMessage
  ) {
    return new CopyAction(owner, input, output, progressMessage, TargetType.DIRECTORY);
  }

  private CopyAction(
      ActionOwner owner,
      Artifact input,
      Artifact output,
      String progressMessage,
      TargetType targetType) {
    super(
      owner,
      NestedSetBuilder.create(Order.STABLE_ORDER, input),
      ImmutableSet.of(output));
    this.targetType = targetType;
    this.progressMessage = progressMessage;
    this.input = input;
    this.output = output;
  }

	@Override
	public String getMnemonic() {
		return "Copy";
	}
	
	@Override
	protected void computeKey(
      ActionKeyContext actionKeyContext,
      InputMetadataProvider inputMetadataProvider,
      Fingerprint fp) {
    fp.addString(GUID);
	}

  private void maybeVerifyInputIsExecutable(ActionExecutionContext actionExecutionContext) {

  }

  @Override
  public final ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    maybeVerifyInputIsExecutable(actionExecutionContext);

    Path inputPath = actionExecutionContext.getInputPath(input);
    Path outputPath = actionExecutionContext.getInputPath(output);

    try {
      inputPath.copyTo(outputPath);
    } catch (IOException e) {
      String message =
          String.format(
                "failed to copy '%s' to '%s' due to I/O error: %s",
                input.getExecPathString(), output.getExecPathString(), e.getMessage());
      DetailedExitCode code = DetailedExitCode.of(
          FailureDetail.newBuilder()
              .setMessage(message)
              .setCopyAction(
                  FailureDetails.CopyAction.newBuilder()
                      .setCode(FailureDetails.CopyAction.Code.IO_ERROR))
              .build());
    }

    throw new UnsupportedOperationException("execute not implemented");
  }
}
