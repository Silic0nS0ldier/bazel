// Copyright 2023 The Bazel Authors. All rights reserved.
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
import java.util.List;
import java.util.Optional;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.StarlarkValue;

/** The interface for Starlark-defined subrules in the Build API. */
@StarlarkBuiltin(
    name = "Subrule",
    category = DocCategory.BUILTIN,
    doc =
        """
        Represents a subrule: a reusable piece of rule implementation logic that can be shared
        across multiple rules and aspects. A subrule is created via the
        <a href="../globals/bzl.html#subrule"><code>subrule()</code></a> function.
        <p>A subrule encapsulates private (implicit) attribute declarations and the
        implementation function that uses them. When a rule or aspect declares a subrule
        in its <code>subrules</code> parameter, the subrule's private attributes are
        automatically added to that rule or aspect and are invisible to end users.
        <p>A subrule may itself depend on other subrules by listing them in its own
        <code>subrules</code> parameter, in which case the declaring rule or aspect must
        only list the top-level subrule.
        <p>A <code>Subrule</code> instance is callable from within a rule or aspect
        implementation function. Calling it invokes the subrule's implementation with a
        fresh <a href="../builtins/subrule_ctx.html">subrule_ctx</a> as the first
        argument.
        """)
public interface StarlarkSubruleApi extends StarlarkValue {

  static Optional<String> getUserDefinedNameIfSubruleAttr(
      List<? extends StarlarkSubruleApi> subrules, String attributeName) {
    return subrules.stream()
        .map(s -> s.getUserDefinedNameIfSubruleAttr(attributeName))
        .flatMap(Optional::stream)
        .findFirst();
  }

  Optional<String> getUserDefinedNameIfSubruleAttr(String attrName);
}
