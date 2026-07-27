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
 * An opaque handle to a set of files (and, optionally, directories) dynamically resolved from one
 * or more directory artifacts at execution time.
 *
 * <p>Returned by {@code ctx.actions.select_files}. Because the resolved members' exec paths are
 * unknown until the source trees' contents are known, a {@code FileSelection} is not a collection
 * of {@link FileApi}s and may appear only on surfaces that already tolerate execution-time
 * resolution: action inputs and {@code Args}.
 */
@StarlarkBuiltin(
    name = "FileSelection",
    category = DocCategory.BUILTIN,
    doc =
        "An opaque handle to a set of files (and, optionally, directories) dynamically resolved "
            + "from one or more directory artifacts at execution time. Returned by "
            + "<a href=\"#select_files\"><code>select_files</code></a>. It is not a collection of "
            + "<a href=\"../builtins/File.html\"><code>File</code></a>s: it may be used as an "
            + "action input and expanded in <a href=\"../builtins/Args.html\"><code>Args</code></a>"
            + " via <code>add_all</code>/<code>add_joined</code>, but not as an output, in "
            + "<code>DefaultInfo</code>, in runfiles, or in a depset.")
public interface FileSelectionApi extends StarlarkValue {}
