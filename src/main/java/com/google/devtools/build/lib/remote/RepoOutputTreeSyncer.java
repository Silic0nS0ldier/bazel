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
package com.google.devtools.build.lib.remote;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.SymlinkNode;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Makes a local directory match an REAPI output {@link Tree}, downloading files from the
 * disk/remote CAS as needed. Part of {@code --experimental_granular_repository_caching}.
 *
 * <p>Files already present locally with matching digests are kept to avoid unnecessary downloads
 * (typically the unmodified inputs of an action); extraneous entries are deleted.
 */
final class RepoOutputTreeSyncer {

  private RepoOutputTreeSyncer() {}

  static void syncFromTree(
      CombinedCache cache,
      DigestUtil digestUtil,
      RemoteActionExecutionContext context,
      Tree tree,
      Path root)
      throws IOException, InterruptedException {
    Map<Digest, Directory> directoriesByDigest =
        Maps.newHashMapWithExpectedSize(tree.getChildrenCount());
    for (Directory child : tree.getChildrenList()) {
      directoriesByDigest.put(digestUtil.compute(child), child);
    }
    syncDirectory(cache, digestUtil, context, tree.getRoot(), directoriesByDigest, root);
  }

  private static void syncDirectory(
      CombinedCache cache,
      DigestUtil digestUtil,
      RemoteActionExecutionContext context,
      Directory dir,
      Map<Digest, Directory> directoriesByDigest,
      Path path)
      throws IOException, InterruptedException {
    path.createDirectoryAndParents();
    Map<String, Dirent> extraneous = new HashMap<>();
    for (Dirent dirent : path.readdir(Symlinks.NOFOLLOW)) {
      extraneous.put(dirent.getName(), dirent);
    }
    for (FileNode file : dir.getFilesList()) {
      Dirent existing = extraneous.remove(file.getName());
      Path filePath = path.getRelative(file.getName());
      if (existing != null) {
        if (existing.getType() == Dirent.Type.FILE
            && digestUtil.compute(filePath).equals(file.getDigest())) {
          filePath.setExecutable(file.getIsExecutable());
          continue;
        }
        deleteEntry(filePath, existing);
      }
      Utils.getFromFuture(cache.downloadFile(context, filePath, file.getDigest()));
      filePath.setExecutable(file.getIsExecutable());
    }
    for (SymlinkNode symlink : dir.getSymlinksList()) {
      Dirent existing = extraneous.remove(symlink.getName());
      Path linkPath = path.getRelative(symlink.getName());
      if (existing != null) {
        if (existing.getType() == Dirent.Type.SYMLINK
            && linkPath.readSymbolicLink().getPathString().equals(symlink.getTarget())) {
          continue;
        }
        deleteEntry(linkPath, existing);
      }
      linkPath.createSymbolicLink(PathFragment.create(symlink.getTarget()));
    }
    for (DirectoryNode dirNode : dir.getDirectoriesList()) {
      Dirent existing = extraneous.remove(dirNode.getName());
      Path dirPath = path.getRelative(dirNode.getName());
      if (existing != null && existing.getType() != Dirent.Type.DIRECTORY) {
        deleteEntry(dirPath, existing);
      }
      Directory child = directoriesByDigest.get(dirNode.getDigest());
      if (child == null) {
        throw new IOException(
            "output tree is missing directory " + dirPath + " (" + dirNode.getDigest() + ")");
      }
      syncDirectory(cache, digestUtil, context, child, directoriesByDigest, dirPath);
    }
    for (Dirent dirent : extraneous.values()) {
      deleteEntry(path.getRelative(dirent.getName()), dirent);
    }
  }

  private static void deleteEntry(Path path, Dirent dirent) throws IOException {
    if (dirent.getType() == Dirent.Type.DIRECTORY) {
      path.deleteTree();
    } else {
      path.delete();
    }
  }
}
