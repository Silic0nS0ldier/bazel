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
package com.google.devtools.build.lib.bazel.repository.starlark;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Runs local executions of granular-caching-eligible repository commands (see {@code
 * --experimental_granular_repository_caching}) inside a sandbox so that, like remotely executed
 * commands, they only observe declared inputs: the repository directory, paths passed as
 * arguments (labels and {@code path} objects), explicitly passed environment variables, and the
 * operating system.
 *
 * <p>On Linux, the hermetic {@code linux-sandbox} provides a true allow-list: the repository
 * directory is staged (via hardlinks) under the sandbox root at its own absolute path, so that
 * after the sandbox pivots its root, the repository is visible — and writable — at its real path;
 * absolute references to it in arguments or the environment work unchanged. After the command
 * completes, the staged directory's contents are moved back, preserving today's local-execution
 * semantics where a failed command's partial writes remain observable.
 *
 * <p>On macOS, {@code sandbox-exec} restricts by path without staging: writes are confined to the
 * repository directory and temporary directories, and reads of the workspace, the output base
 * (other repositories) and the user's home directory are denied except for declared inputs. This
 * is a deny-list rather than an allow-list — a fully hermetic default-deny Seatbelt profile is
 * not practical — but it hides the undeclared inputs that matter in practice.
 *
 * <p>On other platforms no sandbox is available and callers fall back to unsandboxed execution.
 */
final class RepoExecutionSandbox {

  /**
   * Directories mounted read-only into the Linux sandbox to stand in for the operating system —
   * morally equivalent to a remote execution image. Everything else on the host (the workspace,
   * other repositories, the user's home directory, ...) is invisible.
   */
  private static final ImmutableList<String> LINUX_SYSTEM_MOUNTS =
      ImmutableList.of("/bin", "/etc", "/lib", "/lib32", "/lib64", "/opt", "/run", "/sbin", "/usr");

  private static final String DARWIN_SANDBOX_EXEC = "/usr/bin/sandbox-exec";

  private static final AtomicInteger SANDBOX_INDEX = new AtomicInteger();

  @Nullable private static volatile Boolean probeResult;

  private RepoExecutionSandbox() {}

  /**
   * A prepared sandbox invocation. {@code sandboxRepoDir} is the staged repository directory to
   * move results back from, or null if the command operates on the repository directory in place.
   */
  record Prepared(
      ImmutableList<String> commandLine,
      Path scratchDir,
      @Nullable Path sandboxRepoDir,
      Path workingDirectory) {}

  /**
   * Whether a sandbox is actually usable here (e.g. user namespaces may be unavailable in
   * containerized Linux environments). Probed once per server lifetime.
   */
  static boolean isAvailable(OS os, @Nullable Path linuxSandbox, Path scratchBase)
      throws InterruptedException {
    Boolean result = probeResult;
    if (result == null) {
      synchronized (RepoExecutionSandbox.class) {
        result = probeResult;
        if (result == null) {
          result = probe(os, linuxSandbox, scratchBase);
          probeResult = result;
        }
      }
    }
    return result;
  }

  private static boolean probe(OS os, @Nullable Path linuxSandbox, Path scratchBase)
      throws InterruptedException {
    Path root = scratchBase.getRelative("probe");
    try {
      if (root.exists()) {
        root.deleteTree();
      }
      Path workDir = root.getRelative("w");
      workDir.createDirectoryAndParents();
      ImmutableList<String> commandLine;
      switch (os) {
        case LINUX -> {
          if (linuxSandbox == null) {
            return false;
          }
          commandLine =
              buildLinuxCommandLine(
                  linuxSandbox,
                  root,
                  workDir,
                  linuxSystemMounts(root.getFileSystem()),
                  Duration.ofSeconds(30),
                  ImmutableList.of("/bin/sh", "-c", "true"));
        }
        case DARWIN -> {
          if (!root.getFileSystem().getPath(DARWIN_SANDBOX_EXEC).exists()) {
            return false;
          }
          Path profile = root.getRelative("probe.sb");
          FileSystemUtils.writeContent(
              profile, StandardCharsets.UTF_8, "(version 1)\n(allow default)\n");
          commandLine =
              ImmutableList.of(
                  DARWIN_SANDBOX_EXEC, "-f", profile.getPathString(), "/bin/sh", "-c", "true");
        }
        default -> {
          return false;
        }
      }
      StarlarkExecutionResult result =
          StarlarkExecutionResult.builder(ImmutableMap.of())
              .addArguments(commandLine)
              .setDirectory(workDir.getPathFile())
              .setTimeout(30_000)
              .setQuiet(true)
              .execute();
      return result.getReturnCode() == 0;
    } catch (IOException e) {
      return false;
    } finally {
      try {
        root.deleteTree();
      } catch (IOException e) {
        // Best effort.
      }
    }
  }

  /**
   * Prepares a sandbox for running the given command against {@code repoDir}, or returns null if
   * no sandbox is available on this platform.
   *
   * @param workingDirRelative the working directory relative to the repository directory.
   * @param readOnlyInputs declared input paths outside the repository directory to make visible
   *     (read-only) at their real paths.
   */
  @Nullable
  static Prepared prepare(
      OS os,
      @Nullable Path linuxSandbox,
      Path scratchBase,
      Path repoDir,
      Path workspaceRoot,
      PathFragment workingDirRelative,
      Set<Path> readOnlyInputs,
      List<String> args,
      Duration timeout)
      throws IOException {
    Path scratchDir = scratchBase.getRelative("sb-" + SANDBOX_INDEX.incrementAndGet());
    if (scratchDir.exists()) {
      scratchDir.deleteTree();
    }
    return switch (os) {
      case LINUX ->
          linuxSandbox == null
              ? null
              : prepareLinux(
                  linuxSandbox, scratchDir, repoDir, workingDirRelative, readOnlyInputs, args,
                  timeout);
      case DARWIN ->
          prepareDarwin(
              scratchDir, repoDir, workspaceRoot, workingDirRelative, readOnlyInputs, args);
      default -> null;
    };
  }

  private static Prepared prepareLinux(
      Path linuxSandbox,
      Path scratchDir,
      Path repoDir,
      PathFragment workingDirRelative,
      Set<Path> readOnlyInputs,
      List<String> args,
      Duration timeout)
      throws IOException {
    // Stage the repository at its own absolute path below the sandbox root: after the sandbox
    // pivots, it is visible at its real path.
    Path sandboxRepoDir = scratchDir.getRelative(repoDir.asFragment().toRelative());
    stageTree(repoDir, sandboxRepoDir);
    scratchDir.getRelative("tmp").createDirectoryAndParents();

    ImmutableSortedMap.Builder<Path, Path> mounts = ImmutableSortedMap.naturalOrder();
    mounts.putAll(linuxSystemMounts(repoDir.getFileSystem()));
    for (Path input : readOnlyInputs) {
      if (!input.exists() || input.startsWith(repoDir) || underLinuxSystemMounts(input)) {
        continue;
      }
      mounts.put(input, input);
    }

    Path workingDirectory = sandboxRepoDir.getRelative(workingDirRelative);
    workingDirectory.createDirectoryAndParents();
    ImmutableList<String> commandLine =
        buildLinuxCommandLine(
            linuxSandbox, scratchDir, workingDirectory, mounts.buildOrThrow(), timeout, args);
    return new Prepared(commandLine, scratchDir, sandboxRepoDir, workingDirectory);
  }

  /**
   * Prepares a {@code sandbox-exec} invocation. The command operates on the repository directory
   * in place; the Seatbelt profile confines writes to it (and temporary directories) and denies
   * reads of the workspace, the output base and the user's home directory, except for declared
   * inputs.
   */
  private static Prepared prepareDarwin(
      Path scratchDir,
      Path repoDir,
      Path workspaceRoot,
      PathFragment workingDirRelative,
      Set<Path> readOnlyInputs,
      List<String> args)
      throws IOException {
    scratchDir.createDirectoryAndParents();
    Path outputBase = repoDir.getParentDirectory().getParentDirectory();
    StringBuilder profile = new StringBuilder();
    profile.append("(version 1)\n");
    profile.append("(allow default)\n");
    // Confine writes to the repository directory and temporary directories.
    profile.append("(deny file-write*)\n");
    profile.append("(allow file-write*\n");
    appendSubpath(profile, repoDir);
    profile.append("  (subpath \"/tmp\")\n");
    profile.append("  (subpath \"/private/tmp\")\n");
    profile.append("  (subpath \"/private/var/tmp\")\n");
    profile.append("  (subpath \"/dev\")\n");
    profile.append(")\n");
    // Hide the classic undeclared inputs: the workspace, other repositories (the output base) and
    // the user's home directory.
    profile.append("(deny file-read*\n");
    appendSubpath(profile, outputBase);
    appendSubpath(profile, workspaceRoot);
    String home = System.getProperty("user.home");
    if (home != null && !home.isEmpty()) {
      profile.append("  (subpath ").append(quoteSbplString(home)).append(")\n");
    }
    profile.append(")\n");
    // Re-allow the repository directory and declared inputs (later rules win).
    profile.append("(allow file-read*\n");
    appendSubpath(profile, repoDir);
    for (Path input : readOnlyInputs) {
      if (input.exists()) {
        appendSubpath(profile, input);
      }
    }
    profile.append(")\n");

    Path profilePath = scratchDir.getRelative("repo.sb");
    FileSystemUtils.writeContent(profilePath, StandardCharsets.UTF_8, profile.toString());

    Path workingDirectory = repoDir.getRelative(workingDirRelative);
    workingDirectory.createDirectoryAndParents();
    ImmutableList<String> commandLine =
        ImmutableList.<String>builder()
            .add(DARWIN_SANDBOX_EXEC, "-f", profilePath.getPathString())
            .addAll(args)
            .build();
    return new Prepared(commandLine, scratchDir, /* sandboxRepoDir= */ null, workingDirectory);
  }

  private static void appendSubpath(StringBuilder profile, Path path) {
    profile.append("  (subpath ").append(quoteSbplString(path.getPathString())).append(")\n");
  }

  private static String quoteSbplString(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /**
   * Replaces the contents of {@code repoDir} with the post-execution contents of the staged
   * repository directory, if the sandbox used staging.
   */
  static void moveResultsBack(Prepared prepared, Path repoDir) throws IOException {
    if (prepared.sandboxRepoDir() == null) {
      return;
    }
    repoDir.createDirectoryAndParents();
    for (Path entry : repoDir.getDirectoryEntries()) {
      if (entry.isDirectory(Symlinks.NOFOLLOW)) {
        entry.deleteTree();
      } else {
        entry.delete();
      }
    }
    if (!prepared.sandboxRepoDir().exists()) {
      return;
    }
    for (Path entry : prepared.sandboxRepoDir().getDirectoryEntries()) {
      entry.renameTo(repoDir.getRelative(entry.getBaseName()));
    }
  }

  static void cleanup(Prepared prepared) {
    try {
      prepared.scratchDir().deleteTree();
    } catch (IOException e) {
      // The leftover directory is harmless and will be cleaned up with the output base.
    }
  }

  private static ImmutableList<String> buildLinuxCommandLine(
      Path linuxSandbox,
      Path sandboxRoot,
      Path workingDirectory,
      ImmutableSortedMap<Path, Path> bindMounts,
      Duration timeout,
      List<String> args) {
    return LinuxSandboxCommandLineBuilder.commandLineBuilder(linuxSandbox)
        .setHermeticSandboxPath(sandboxRoot)
        .setWorkingDirectory(workingDirectory)
        .setBindMounts(bindMounts)
        .setTimeout(timeout)
        .setKillDelay(Duration.ofSeconds(15))
        .setUseFakeHostname(true)
        .buildForCommand(args);
  }

  private static ImmutableSortedMap<Path, Path> linuxSystemMounts(FileSystem fs) {
    ImmutableSortedMap.Builder<Path, Path> mounts = ImmutableSortedMap.naturalOrder();
    for (String systemDir : LINUX_SYSTEM_MOUNTS) {
      Path path = fs.getPath(systemDir);
      if (path.exists()) {
        mounts.put(path, path);
      }
    }
    return mounts.buildOrThrow();
  }

  private static boolean underLinuxSystemMounts(Path path) {
    for (String systemDir : LINUX_SYSTEM_MOUNTS) {
      if (path.startsWith(path.getFileSystem().getPath(systemDir))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Stages a directory tree via hardlinks (directories and symlinks are recreated). Cheap, but
   * note that a command mutating a staged file in place writes through to the original; commands
   * conventionally create new files, and the contents are moved back wholesale afterwards anyway.
   */
  private static void stageTree(Path from, Path to) throws IOException {
    to.createDirectoryAndParents();
    if (!from.exists()) {
      return;
    }
    for (Dirent dirent : from.readdir(Symlinks.NOFOLLOW)) {
      Path child = from.getRelative(dirent.getName());
      Path target = to.getRelative(dirent.getName());
      switch (dirent.getType()) {
        case FILE -> child.createHardLink(target);
        case DIRECTORY -> stageTree(child, target);
        case SYMLINK -> target.createSymbolicLink(child.readSymbolicLink());
        default ->
            throw new IOException("cannot stage special file into sandbox: " + child);
      }
    }
  }
}
