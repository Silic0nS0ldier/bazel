// Copyright 2018 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.cmdline.Label;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkValue;

/** The interface for files in Starlark. */
@StarlarkBuiltin(
    name = "File",
    category = DocCategory.BUILTIN,
    doc =
        "This object is created during the analysis phase to represent a file or directory that "
            + "will be read or written during the execution phase. It is not an open file"
            + " handle, "
            + "and cannot be used to directly read or write file contents. Rather, you use it to "
            + "construct the action graph in a rule implementation function by passing it to "
            + "action-creating functions. See the "
            + "<a href='https://bazel.build/extending/rules#files'>Rules page</a> for more "
            + "information."
            + "" // curse google-java-format b/145078219
            + "<p>When a <code>File</code> is passed to an <a"
            + " href='../builtins/Args.html'><code>Args</code></a> object without using a"
            + " <code>map_each</code> function, it is converted to a string by taking the value of"
            + " its <code>path</code> field.")
public interface FileApi extends StarlarkValue {

  @StarlarkMethod(
      name = "dirname",
      structField = true,
      useStarlarkSemantics = true,
      doc =
          "The name of the directory containing this file. It's taken from "
              + "<a href=\"#path\">path</a> and is always relative to the execution directory.")
  String getDirnameForStarlark(StarlarkSemantics semantics);

  @StarlarkMethod(
      name = "basename",
      structField = true,
      doc = "The base name of this file. This is the name of the file inside the directory.")
  String getFilename();

  @StarlarkMethod(
      name = "extension",
      structField = true,
      doc =
          "The file extension of this file, following (not including) the rightmost period. "
              + "Empty string if the file's basename includes no periods.")
  String getExtension();

  @StarlarkMethod(
      name = "owner",
      structField = true,
      allowReturnNones = true,
      doc = "A label of a target that produces this File.")
  @Nullable
  Label getOwnerLabel();

  @StarlarkMethod(
      name = "root",
      structField = true,
      useStarlarkSemantics = true,
      doc = "The root beneath which this file resides.")
  FileRootApi getRootForStarlark(StarlarkSemantics semantics);

  @StarlarkMethod(
      name = "is_source",
      structField = true,
      doc = "Returns true if this is a source file, i.e. it is not generated.")
  boolean isSourceArtifact();

  @StarlarkMethod(
      name = "is_directory",
      structField = true,
      doc =
          "Returns true if this is a directory. This reflects the type the file was declared as"
              + " (i.e. ctx.actions.declare_directory), not its type on the filesystem, which might"
              + " differ.")
  boolean isDirectory();

  @StarlarkMethod(
      name = "is_symlink",
      structField = true,
      doc =
          "Returns true if this was declared as a symlink. This reflects the type the file was"
              + " declared as (i.e. ctx.actions.declare_symlink), not its type on the filesystem,"
              + " which might differ.")
  boolean isSymlink();

  @StarlarkMethod(
      name = "short_path",
      structField = true,
      doc =
          """
          The path of this file relative to its root. This excludes the aforementioned \
          <i>root</i>, i.e. configuration-specific fragments of the path. This is also the path \
          under which the file is mapped into a binary's runfiles, <b>relative to the runfiles \
          directory of the main repository</b> (which is the working directory when the binary \
          runs under Bazel), <b>not</b> relative to the runfiles root. As a result it does not \
          begin with the file's repository as a path segment: a file in the main repository omits \
          it entirely (e.g. <code>pkg/file</code>), while a file in an external repository takes \
          the form <code>../&lt;repo name>/pkg/file</code>. Use <a \
          href="#rlocation_path">rlocation_path</a> for the runfiles-root-relative path that \
          includes the repository segment and can be passed to a runfiles library's \
          <code>rlocation</code> function.""")
  String getRunfilesPathString();

  @StarlarkMethod(
      name = "rlocation_path",
      structField = true,
      doc =
          """
          The runfiles-relative path of this file, i.e. the path that this file can be looked up \
          under using a runfiles library's <code>rlocation</code> function when it is in the \
          runfiles of a binary. It consists of the file's repository's runfiles directory name \
          (the canonical repository name for files in an external repository, or <code>_main</code> \
          for files in the main repository) followed by the file's <a href="#short_path">short_path\
          </a>.<p>Unlike <a href="#short_path">short_path</a>, this value is the same regardless of \
          which repository the consuming target lives in, which makes it suitable for use in \
          <a href="../builtins/Args.html#add_all">Args.add_all()</a> <code>map_each</code> \
          callbacks, where <a href="../builtins/ctx.html#workspace_name">ctx.workspace_name</a> is \
          not available.""")
  String getRlocationPathString();

  @StarlarkMethod(
      name = "path",
      structField = true,
      useStarlarkSemantics = true,
      doc =
          "The execution path of this file, relative to the workspace's execution directory. It"
              + " consists of two parts, an optional first part called the <i>root</i> (see also"
              + " the <a href=\"../builtins/root.html\">root</a> module), and the second part which"
              + " is the <code>short_path</code>. The root may be empty, which it usually is for"
              + " non-generated files. For generated files it usually contains a"
              + " configuration-specific path fragment that encodes things like the target CPU"
              + " architecture that was used while building said file. Use the"
              + " <code>short_path</code> for the path under which the file is mapped if it's in"
              + " the runfiles of a binary.")
  String getExecPathStringForStarlark(StarlarkSemantics semantics);

  @StarlarkMethod(
      name = "tree_relative_path",
      structField = true,
      doc =
          "The path of this file relative to the root of the ancestor's tree, if the ancestor's <a"
              + " href=\"#is_directory\">is_directory</a> field is true."
              + " <code>tree_relative_path</code> is only available for expanded files of a"
              + " directory in an action command, i.e. <a"
              + " href=\"../builtins/Args.html#add_all\">Args.add_all()</a>. For other types of"
              + " files, it is an error to access this field.")
  String getTreeRelativePathString() throws EvalException;
}
