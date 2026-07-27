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
package com.google.devtools.build.lib.starlarkbuildapi;

import com.google.devtools.build.docgen.annot.DocCategory;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.StarlarkValue;

/**
 * An opaque handle to a single file or directory dynamically resolved from one or more directory
 * artifacts at execution time.
 *
 * <p>Returned by {@code ctx.actions.select_file} (resolves to exactly one regular file) and {@code
 * ctx.actions.select_directory} (resolves to exactly one directory). Because the resolved exec path
 * is unknown until the source trees' contents are known, a {@code SelectedFile} is not a {@link
 * FileApi} and may appear only on surfaces that already tolerate execution-time resolution: action
 * inputs, {@code Args}, and (for a file) an action's {@code executable} or {@code tools}.
 */
@StarlarkBuiltin(
    name = "SelectedFile",
    category = DocCategory.BUILTIN,
    doc =
        "An opaque handle to a single file or directory dynamically resolved from one or more "
            + "directory artifacts at execution time. Returned by "
            + "<a href=\"#select_file\"><code>select_file</code></a> and "
            + "<a href=\"#select_directory\"><code>select_directory</code></a>. It is not a "
            + "<a href=\"../builtins/File.html\"><code>File</code></a>: it may be used as an action "
            + "input, in <a href=\"../builtins/Args.html\"><code>Args</code></a>, and (for a file) "
            + "as an action's <code>executable</code> or in <code>tools</code>, but not as an "
            + "output, in <code>DefaultInfo</code>, in runfiles, or in a depset.")
public interface SelectedFileApi extends StarlarkValue {}
