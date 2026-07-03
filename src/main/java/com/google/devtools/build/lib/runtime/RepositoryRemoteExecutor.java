// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.time.Duration;
import javax.annotation.Nullable;

/** Interface to support remote execution in repository_ctx.execute(). */
public interface RepositoryRemoteExecutor {

  /**
   * Thrown by {@link #executeCacheable} when the current repository directory state cannot be
   * represented as an action input tree (e.g. it contains dangling symlinks). Callers should fall
   * back to local execution.
   */
  final class NotCacheableException extends IOException {
    public NotCacheableException(String message) {
      super(message);
    }
  }

  /** The result of a remotely executed command. */
  final class ExecutionResult {

    private final int exitCode;
    private final byte[] stdout;
    private final byte[] stderr;

    public ExecutionResult(int exitCode, byte[] stdout, byte[] stderr) {
      this.exitCode = exitCode;
      this.stdout = stdout;
      this.stderr = stderr;
    }

    public int exitCode() {
      return exitCode;
    }

    public byte[] stdout() {
      return stdout;
    }

    public byte[] stderr() {
      return stderr;
    }
  }

  /**
   * Execute a command remotely.
   *
   * @param arguments the command arguments.
   * @param inputFiles the files to upload and stage for the command. The key describes where to
   *     stage the file on the remote machine. The value is the path of the file on the host machine
   *     (where Bazel is running).
   * @param executionProperties the remote platform the command should run on.
   * @param environment any environment variables that should be set in the command's environment.
   * @param workingDirectory the working directory to run the command under. {@code ""} means that
   *     the remote system should choose.
   * @param timeout execution timeout.
   */
  ExecutionResult execute(
      ImmutableList<String> arguments,
      ImmutableSortedMap<PathFragment, Path> inputFiles,
      ImmutableMap<String, String> executionProperties,
      ImmutableMap<String, String> environment,
      String workingDirectory,
      Duration timeout)
      throws IOException, InterruptedException;

  /**
   * Executes a command as a cacheable action whose input tree contains the current state of the
   * repository directory and whose declared output is the entire repository directory after the
   * command ran (part of {@code --experimental_granular_repository_caching}).
   *
   * <p>On success (including an action cache hit), the contents of {@code repoDir} are replaced
   * with the repository directory state captured by the action, reflecting any files the command
   * created, modified or deleted. The action cache entry is produced by the remote execution
   * service (or replayed from the disk/remote cache), never uploaded by this client.
   *
   * @param arguments the command arguments. Paths referring to files inside the repository must be
   *     relative to the repository root (the action's working directory).
   * @param repoDir the local repository directory to stage as the action's working directory and
   *     to overwrite with the captured outputs.
   * @param auxiliaryInputs additional input files staged outside the repository directory in the
   *     input tree, e.g. files referenced by label arguments. The key is the path relative to the
   *     input root, the value the local path.
   * @param executionProperties the remote platform the command should run on.
   * @param environment environment variables set in the command's environment.
   * @param timeout execution timeout.
   * @return the execution result, or null if this executor does not support cacheable execution.
   * @throws NotCacheableException if the repository directory state cannot be represented as an
   *     action input tree; the caller should fall back to local execution.
   */
  @Nullable
  default ExecutionResult executeCacheable(
      ImmutableList<String> arguments,
      Path repoDir,
      ImmutableSortedMap<PathFragment, Path> auxiliaryInputs,
      ImmutableMap<String, String> executionProperties,
      ImmutableMap<String, String> environment,
      Duration timeout)
      throws IOException, InterruptedException {
    return null;
  }
}
