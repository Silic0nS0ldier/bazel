// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.vfs.PathFragment;
import javax.annotation.Nullable;

/** Definition of a symlink in the output tree of a Fileset rule. */
@AutoValue
public abstract class FilesetOutputSymlink {

  /** Final name of the symlink relative to the Fileset's output directory. */
  public abstract PathFragment getName();

  /**
   * Target of the symlink.
   *
   * <p>This path is one of the following:
   *
   * <ol>
   *   <li>Relative to the execution root, in which case {@link #isRelativeToExecRoot} will return
   *       {@code true}.
   *   <li>An absolute path to the source tree.
   *   <li>A relative path that should be considered relative to the link.
   * </ol>
   */
  public abstract PathFragment getTargetPath();

  /**
   * Return the best effort metadata about the target. Currently this will be a FileStateValue for
   * source targets. For generated targets we try to return a FileArtifactValue when possible, or
   * else this will be a synthetic digest of the target.
   */
  public abstract HasDigest getMetadata();

  /** Returns {@code true} if this symlink is relative to the execution root. */
  public abstract boolean isRelativeToExecRoot();

  /**
   * If this symlink points to a file inside a tree artifact, returns that file's {@linkplain
   * Artifact#getParent parent} tree artifact. Otherwise, returns {@code null}.
   */
  @Nullable
  public abstract SpecialArtifact getEnclosingTreeArtifact();

  /**
   * Reconstitutes the original target path of this symlink.
   *
   * <p>This method essentially performs the inverse of what is done in {@link #create}. If the
   * execution root was stripped originally, it is re-prepended.
   */
  public final PathFragment reconstituteTargetPath(PathFragment execRoot) {
    return isRelativeToExecRoot() ? execRoot.getRelative(getTargetPath()) : getTargetPath();
  }

  @Override
  public final String toString() {
    if (getMetadata() == HasDigest.EMPTY) {
      return String.format(
          "FilesetOutputSymlink(%s -> %s)",
          getName().getPathString(), getTargetPath().getPathString());
    } else {
      return String.format(
          "FilesetOutputSymlink(%s -> %s | metadataHash=%s)",
          getName().getPathString(), getTargetPath().getPathString(), getMetadata());
    }
  }

  @VisibleForTesting
  public static FilesetOutputSymlink createForTesting(
      PathFragment name, PathFragment target, PathFragment execRoot) {
    return create(name, target, HasDigest.EMPTY, execRoot, /* enclosingTreeArtifact= */ null);
  }

  @VisibleForTesting
  public static FilesetOutputSymlink createAlreadyRelativizedForTesting(
      PathFragment name, PathFragment target, boolean isRelativeToExecRoot) {
    return createAlreadyRelativized(
        name, target, HasDigest.EMPTY, isRelativeToExecRoot, /* enclosingTreeArtifact= */ null);
  }

  /**
   * Creates a {@link FilesetOutputSymlink}.
   *
   * <p>To facilitate cross-device sharing, {@code target} will have the machine-local {@code
   * execRoot} stripped if necessary. If this happens, {@link #isRelativeToExecRoot} will return
   * {@code true}.
   *
   * @param name relative path under the Fileset's output directory, including FilesetEntry.destdir
   *     with and FilesetEntry.strip_prefix applied (if applicable)
   * @param target relative or absolute value of the link
   * @param metadata metadata corresponding to the target.
   * @param execRoot the execution root
   * @param enclosingTreeArtifact if {@code target} is a tree artifact file, its {@linkplain
   *     Artifact#getParent parent} tree artifact, otherwise {@code null}
   */
  public static FilesetOutputSymlink create(
      PathFragment name,
      PathFragment target,
      HasDigest metadata,
      PathFragment execRoot,
      @Nullable SpecialArtifact enclosingTreeArtifact) {
    boolean isRelativeToExecRoot = false;
    // Check if the target is under the execution root. This is not always the case because the
    // target may point to a source artifact or it may point to another symlink, in which case the
    // target path is already relative.
    if (target.startsWith(execRoot)) {
      target = target.relativeTo(execRoot);
      isRelativeToExecRoot = true;
    }
    return createAlreadyRelativized(
        name, target, metadata, isRelativeToExecRoot, enclosingTreeArtifact);
  }

  /**
   * Same as {@link #create}, except assumes that {@code target} already had the execution root
   * stripped if necessary.
   */
  public static FilesetOutputSymlink createAlreadyRelativized(
      PathFragment name,
      PathFragment target,
      HasDigest metadata,
      boolean isRelativeToExecRoot,
      @Nullable SpecialArtifact enclosingTreeArtifact) {
    checkArgument(
        enclosingTreeArtifact == null || enclosingTreeArtifact.isTreeArtifact(),
        enclosingTreeArtifact);
    return new AutoValue_FilesetOutputSymlink(
        name, target, metadata, isRelativeToExecRoot, enclosingTreeArtifact);
  }
}
