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
package com.google.devtools.build.lib.actions;

/**
 * Opt-in flag controlling whether {@link Artifact.SourceArtifact#isDirectory()} -- and therefore
 * the Starlark {@code File.is_directory} field -- reflects whether a source path is a directory on
 * the filesystem.
 *
 * <p>Uses a system property that can be set via a startup flag (e.g. {@code
 * --host_jvm_args=-DBAZEL_SOURCE_DIRECTORY_IS_DIRECTORY=1}). Toggling it therefore causes a server
 * restart and discards Skyframe state. This is required rather than a per-invocation build flag
 * because {@code Artifact#isDirectory} is consumed pervasively -- across analysis, command-line
 * expansion, execution, remote execution, prefetching, and the build event stream -- in contexts
 * that hold no {@link net.starlark.java.eval.StarlarkSemantics}, and its value must be consistent
 * across all of them (and across cached Skyframe/remote state).
 *
 * <p>Defaults to {@code false}, preserving the historical (if flawed) behavior in which source
 * artifacts are always reported as regular files, never directories. Flipping {@link
 * Artifact.SourceArtifact#isDirectory()} for source directories is a breaking change, so it is
 * strictly opt-in.
 *
 * <p>Note: enabling this without also tracking source directories (see {@code
 * TrackSourceDirectoriesFlag}) is inconsistent -- {@link
 * com.google.devtools.build.lib.skyframe.ArtifactFunction} produces regular-file metadata for an
 * untracked source directory, so {@code is_directory} would report {@code true} for an artifact
 * whose metadata is that of a file. Enable both together.
 */
public final class SourceDirectoryIsDirectoryFlag {
  private static final boolean SOURCE_DIRECTORY_IS_DIRECTORY =
      switch (System.getProperty("BAZEL_SOURCE_DIRECTORY_IS_DIRECTORY", "")) {
        case "1" -> true;
        default -> false;
      };

  public static boolean sourceDirectoryIsDirectory() {
    return SOURCE_DIRECTORY_IS_DIRECTORY;
  }

  // Private constructor to prevent instantiation.
  private SourceDirectoryIsDirectoryFlag() {}
}
