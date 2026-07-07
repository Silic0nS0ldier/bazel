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
package com.google.devtools.build.lib.bazel.repository.decompressor;

import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.Path;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Command-line entry point around Bazel's built-in archive decompressors, compiled to a native
 * binary ({@code repo-extractor}) and bundled among Bazel's embedded tools.
 *
 * <p>Used by {@code --experimental_granular_repository_caching} to run repository archive
 * extractions as remote execution actions (the extractor and the archive are the action's
 * inputs; the extracted tree is its output), so that the action cache entry is produced by the
 * remote execution service rather than the client. Because this wraps {@link DecompressorValue}
 * directly, remote extraction has exactly the semantics — and the hardening — of Bazel's local
 * extraction.
 *
 * <p>Usage: {@code repo-extractor --archive <file> --dest <dir> [--strip-prefix <prefix>]
 * [--strip-components <n>] [--rename <from>=<to>]...}
 */
public final class RepoExtractorMain {

  private RepoExtractorMain() {}

  public static void main(String[] args) {
    try {
      run(args);
    } catch (Throwable e) {
      System.err.println("repo-extractor: " + e);
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static void run(String[] args) throws Exception {
    String archive = null;
    String dest = null;
    String stripPrefix = "";
    int stripComponents = 0;
    Map<String, String> renameFiles = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case "--archive" -> archive = next(args, ++i, arg);
        case "--dest" -> dest = next(args, ++i, arg);
        case "--strip-prefix" -> stripPrefix = next(args, ++i, arg);
        case "--strip-components" -> stripComponents = Integer.parseInt(next(args, ++i, arg));
        case "--rename" -> {
          String pair = next(args, ++i, arg);
          int eq = pair.indexOf('=');
          if (eq < 0) {
            throw new IllegalArgumentException("--rename expects <from>=<to>");
          }
          renameFiles.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        default -> throw new IllegalArgumentException("unknown argument: " + arg);
      }
    }
    if (archive == null || dest == null) {
      throw new IllegalArgumentException("--archive and --dest are required");
    }

    FileSystem fs = new JavaIoFileSystem(DigestHashFunction.SHA256);
    Path archivePath = fs.getPath(new File(archive).getAbsolutePath());
    Path destinationPath = fs.getPath(new File(dest).getAbsolutePath());
    destinationPath.createDirectoryAndParents();

    var unused =
        DecompressorValue.decompress(
            DecompressorDescriptor.builder()
                .setContext("repo-extractor")
                .setArchivePath(archivePath)
                .setDestinationPath(destinationPath)
                .setPrefix(stripPrefix)
                .setStripComponents(stripComponents)
                .setRenameFiles(renameFiles)
                .build(),
            /* forceDecompressorType= */ Optional.empty());
  }

  private static String next(String[] args, int i, String arg) {
    if (i >= args.length) {
      throw new IllegalArgumentException("missing value for " + arg);
    }
    return args[i];
  }
}
