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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileContentsProxy;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.MetadataPropagatingFileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SymlinkTargetType;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Action that copies its input artifact to its output artifact.
 *
 * <p>The copy preserves artifact type: a file artifact copies to a file artifact, a tree artifact
 * to a tree artifact, and an unresolved symlink artifact to an unresolved symlink artifact (with
 * the identical target string). The output's content is defined to be identical to the input's;
 * only the name differs.
 *
 * <p>Semantics are defined at the artifact level: a file or tree artifact that happens to be
 * materialized as a symlink on the filesystem is dereferenced, so the result never depends on
 * incidental filesystem state. Only artifacts that are symlinks <em>by type</em> receive
 * symlink-copy semantics.
 *
 * <p>This is not a spawn: it has no execution strategy, no execution platform and never executes
 * remotely. Bazel performs the copy in-process.
 */
public final class CopyAction extends AbstractAction {
  private static final String GUID = "48d21261-7d05-4d63-98d3-8b1ff0f92924";

  private final String progressMessage;

  /** If set, the tree-relative path of the file or sub-tree to extract from the input. */
  @Nullable private final PathFragment path;

  private CopyAction(
      ActionOwner owner,
      Artifact input,
      Artifact output,
      String progressMessage,
      @Nullable PathFragment path) {
    super(
        owner,
        NestedSetBuilder.create(Order.STABLE_ORDER, input),
        ImmutableSet.of(output));
    this.progressMessage = progressMessage;
    this.path = path;
  }

  /**
   * Creates a copy action.
   *
   * <p>The input and output must be of the same artifact type; this is expected to have been
   * validated by the caller at analysis time.
   */
  public static CopyAction create(
      ActionOwner owner, Artifact input, Artifact output, String progressMessage) {
    Preconditions.checkArgument(
        input.isTreeArtifact() == output.isTreeArtifact()
            && input.isSymlink() == output.isSymlink(),
        "copy input %s and output %s must be of the same artifact type",
        input,
        output);
    return new CopyAction(owner, input, output, progressMessage, /* path= */ null);
  }

  /**
   * Creates a copy action that extracts a file or sub-tree from a tree artifact input.
   *
   * <p>A file output extracts the regular file at {@code path}; a tree output recursively extracts
   * the sub-tree rooted at {@code path}. The path must be a normalized relative path; this is
   * expected to have been validated by the caller at analysis time.
   */
  public static CopyAction createExtracting(
      ActionOwner owner,
      Artifact input,
      Artifact output,
      PathFragment path,
      String progressMessage) {
    Preconditions.checkArgument(
        input.isTreeArtifact(), "copy input %s must be a tree artifact to use path", input);
    Preconditions.checkArgument(
        !output.isSymlink(),
        "copy output %s must be a file or tree artifact to use path",
        output);
    Preconditions.checkArgument(
        !path.isAbsolute() && !path.containsUplevelReferences() && !path.isEmpty(),
        "copy path %s must be a normalized relative path",
        path);
    return new CopyAction(owner, input, output, progressMessage, path);
  }

  /**
   * Returns the tree-relative path of the extracted file or sub-tree, or null for a whole-artifact
   * copy.
   */
  @Nullable
  public PathFragment getPath() {
    return path;
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException {
    Artifact input = getPrimaryInput();
    Artifact output = getPrimaryOutput();
    Path inputPath = actionExecutionContext.getInputPath(input);
    Path outputPath = actionExecutionContext.getInputPath(output);

    // Under Build without the Bytes, the action filesystem can complete the copy by propagating
    // remote metadata: the output adopts the input's digest without downloading the input or
    // writing any bytes. Falls back to a physical copy for locally-backed content.
    MetadataPropagatingFileSystem metadataFs =
        actionExecutionContext.getActionFileSystem()
                instanceof MetadataPropagatingFileSystem propagatingFs
            ? propagatingFs
            : null;

    try {
      if (path != null) {
        // Extract the file or sub-tree at `path` from the tree artifact input. The declared output
        // type fixes which one is expected; a mismatch (including a missing path) is an error.
        Path childPath = inputPath.getRelative(path);
        if (output.isTreeArtifact()) {
          if (!childPath.isDirectory(Symlinks.FOLLOW)) {
            throw extractionError("no such directory in the directory", input, output);
          }
          // The output directory has already been created empty prior to execution.
          copyTreeDereferencing(metadataFs, childPath, outputPath);
          maybeInjectTreeMetadata(
              actionExecutionContext, input, (SpecialArtifact) output, outputPath, path);
        } else {
          if (!childPath.isFile(Symlinks.FOLLOW)) {
            throw extractionError("no such file in the directory", input, output);
          }
          copyFile(metadataFs, childPath, outputPath);
        }
      } else if (output.isSymlink()) {
        // Unresolved symlink: reproduce the target string verbatim. The target is tracked
        // metadata, so the input's materialization need not be consulted (or even exist).
        FileArtifactValue inputMetadata =
            actionExecutionContext.getInputMetadataProvider().getInputMetadata(input);
        PathFragment target =
            inputMetadata != null && inputMetadata.getType() == FileStateType.SYMLINK
                ? PathFragment.create(inputMetadata.getUnresolvedSymlinkTarget())
                : inputPath.readSymbolicLink();
        outputPath.createSymbolicLink(target, SymlinkTargetType.UNSPECIFIED);
      } else if (output.isTreeArtifact()) {
        // The output directory has already been created empty prior to execution.
        copyTreeDereferencing(metadataFs, inputPath, outputPath);
        maybeInjectTreeMetadata(
            actionExecutionContext,
            input,
            (SpecialArtifact) output,
            outputPath,
            /* subPath= */ null);
      } else {
        copyFile(metadataFs, inputPath, outputPath);
        maybeInjectOutputMetadata(actionExecutionContext, input, output, outputPath);
      }
    } catch (IOException e) {
      String message =
          String.format(
              "failed to copy '%s' to '%s' due to I/O error: %s",
              input.getExecPathString(), output.getExecPathString(), e.getMessage());
      throw new ActionExecutionException(
          message, e, this, false, createDetailedExitCode(message));
    }

    return ActionResult.EMPTY;
  }

  /** Builds an execution error for a {@code path} extraction whose target is missing or mistyped. */
  private ActionExecutionException extractionError(String reason, Artifact input, Artifact output) {
    String message =
        String.format(
            "failed to copy '%s' from '%s' to '%s': %s",
            path, input.getExecPathString(), output.getExecPathString(), reason);
    return new ActionExecutionException(message, this, false, createDetailedExitCode(message));
  }

  /**
   * For a plain local file copy, injects the output's metadata instead of letting Bazel re-read and
   * re-hash the just-written output: the output's content is identical to the input's, so its digest
   * is the input's digest. Only the (cheap) content proxy is taken from the output's stat. This
   * spares a full re-read of every output, which dominates for large files.
   *
   * <p>Skipped when an action filesystem is present (it performs its own metadata propagation, see
   * {@link MetadataPropagatingFileSystem}) or when the input's digest is unavailable.
   */
  private static void maybeInjectOutputMetadata(
      ActionExecutionContext ctx, Artifact input, Artifact output, Path outputPath)
      throws IOException {
    if (ctx.getActionFileSystem() != null) {
      return;
    }
    FileArtifactValue inputMetadata = ctx.getInputMetadataProvider().getInputMetadata(input);
    if (inputMetadata == null) {
      return;
    }
    byte[] digest = inputMetadata.getDigest();
    if (digest == null) {
      return;
    }
    // Match the read-only output permissions Bazel applies before computing a (ctime-based) proxy,
    // so the injected proxy stays consistent with the on-disk state across incremental builds.
    outputPath.chmod(0555);
    FileContentsProxy proxy;
    try {
      proxy = FileContentsProxy.create(outputPath.stat(Symlinks.NOFOLLOW));
    } catch (IOException e) {
      return; // Fall back to filesystem-derived metadata.
    }
    ctx.getOutputMetadataStore()
        .injectFile(
            output, FileArtifactValue.createForNormalFile(digest, proxy, inputMetadata.getSize()));
  }

  /**
   * Tree-artifact analogue of {@link #maybeInjectOutputMetadata}: builds the output tree's metadata
   * from the input tree's per-child digests (identical content) plus the copied children's content
   * proxies, sparing a re-read + re-hash of every child. Falls back to filesystem-derived metadata
   * (by not injecting) if any child's digest is unavailable.
   *
   * <p>When {@code subPath} is set (a sub-tree extraction), only the input children under that path
   * are injected, rebased onto the output tree's root; the rest are ignored.
   */
  private static void maybeInjectTreeMetadata(
      ActionExecutionContext ctx,
      Artifact input,
      SpecialArtifact output,
      Path outputPath,
      @Nullable PathFragment subPath)
      throws IOException {
    if (ctx.getActionFileSystem() != null) {
      return;
    }
    TreeArtifactValue inputTree = ctx.getInputMetadataProvider().getTreeMetadata(input);
    if (inputTree == null) {
      return;
    }
    TreeArtifactValue.Builder builder = TreeArtifactValue.newBuilder(output);
    for (Map.Entry<TreeFileArtifact, FileArtifactValue> entry :
        inputTree.getChildValues().entrySet()) {
      PathFragment relPath = entry.getKey().getParentRelativePath();
      if (subPath != null) {
        if (!relPath.startsWith(subPath)) {
          continue;
        }
        relPath = relPath.relativeTo(subPath);
      }
      FileArtifactValue childMetadata = entry.getValue();
      byte[] digest = childMetadata.getDigest();
      if (digest == null) {
        return;
      }
      Path childPath = outputPath.getRelative(relPath);
      childPath.chmod(0555);
      FileContentsProxy proxy;
      try {
        proxy = FileContentsProxy.create(childPath.stat(Symlinks.NOFOLLOW));
      } catch (IOException e) {
        return;
      }
      builder.putChild(
          TreeFileArtifact.createTreeOutput(output, relPath),
          FileArtifactValue.createForNormalFile(digest, proxy, childMetadata.getSize()));
    }
    ctx.getOutputMetadataStore().injectTree(output, builder.build());
  }

  /**
   * Copies a single file, propagating metadata instead of bytes where the filesystem supports it
   * and the source is not locally backed.
   *
   * <p>The physical fallback ({@link FileSystemUtils#copyFile}) follows a symlinked source
   * (materialization is invisible at the artifact level) and preserves permissions, including the
   * executable bit.
   */
  private static void copyFile(
      @Nullable MetadataPropagatingFileSystem metadataFs, Path from, Path to) throws IOException {
    if (metadataFs != null && metadataFs.copyFileByMetadata(from.asFragment(), to.asFragment())) {
      return;
    }
    FileSystemUtils.copyFile(from, to);
  }

  /**
   * Recursively copies the contents of {@code from} into the existing directory {@code to},
   * dereferencing any symlinks encountered.
   *
   * <p>Tree artifact contents are logically regular files and directories; symlinks in the
   * materialized tree are incidental, so the copy resolves them rather than reproducing them.
   */
  private static void copyTreeDereferencing(
      @Nullable MetadataPropagatingFileSystem metadataFs, Path from, Path to) throws IOException {
    for (Dirent dirent : from.readdir(Symlinks.FOLLOW)) {
      Path fromChild = from.getChild(dirent.getName());
      Path toChild = to.getChild(dirent.getName());
      switch (dirent.getType()) {
        case FILE -> copyFile(metadataFs, fromChild, toChild);
        case DIRECTORY -> {
          toChild.createDirectory();
          copyTreeDereferencing(metadataFs, fromChild, toChild);
        }
        default ->
            throw new IOException(
                String.format(
                    "cannot copy '%s': unsupported file type or dangling symlink", fromChild));
      }
    }
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable InputMetadataProvider inputMetadataProvider,
      Fingerprint fp) {
    fp.addString(GUID);
    if (path != null) {
      fp.addPath(path);
    }
  }

  @Override
  public String describeKey() {
    return String.format("GUID: %s\npath: %s\n", GUID, path);
  }

  @Override
  public String getMnemonic() {
    return "Copy";
  }

  @Override
  protected String getRawProgressMessage() {
    return progressMessage;
  }

  @Override
  public boolean mayInsensitivelyPropagateInputs() {
    return true;
  }

  private static DetailedExitCode createDetailedExitCode(String message) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setCopyAction(
                FailureDetails.CopyAction.newBuilder()
                    .setCode(FailureDetails.CopyAction.Code.COPY_IO_EXCEPTION))
            .build());
  }

  @Override
  public PlatformInfo getExecutionPlatform() {
    return PlatformInfo.EMPTY_PLATFORM_INFO;
  }

  @Override
  public ImmutableMap<String, String> getExecProperties() {
    // CopyAction is platform agnostic.
    return ImmutableMap.of();
  }
}
