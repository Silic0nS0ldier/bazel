// Copyright 2022 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.common.collect.ImmutableList;

/**
 * Generated when incorrect use_repo calls are detected in the root module file according to {@link
 * ModuleExtensionMetadata} and contains the buildozer commands required to bring the root module
 * file into the expected state.
 *
 * @param buildozerCommands The buildozer commands required to bring the root module file into the
 *     expected state.
 */
public record RootModuleFileFixup(
    ImmutableList<String> buildozerCommands, ModuleExtensionUsage usage) {

  /** A human-readable message describing the fixup after it has been applied. */
  public String getSuccessMessage() {
    String extensionId = usage.getExtensionBzlFile() + "%" + usage.getExtensionName();
    return usage
        .getIsolationKey()
        .map(
            key ->
                String.format(
                    "Updated use_repo calls for isolated usage '%s' of %s",
                    key.getUsageExportedName(), extensionId))
        .orElseGet(() -> String.format("Updated use_repo calls for %s", extensionId));
  }
}
