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
package com.google.devtools.build.lib.analysis.actions;

import com.google.devtools.build.lib.starlarkbuildapi.SelectedFileApi;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * Opaque handle returned by {@code ctx.actions.select_file} and {@code
 * ctx.actions.select_directory}: resolves to exactly one file or one directory at execution time.
 *
 * @see FileSelectionSpec
 */
public final class SelectedFile implements SelectedFileApi {

  private final FileSelectionSpec spec;

  public SelectedFile(FileSelectionSpec spec) {
    this.spec = spec;
  }

  public FileSelectionSpec getSpec() {
    return spec;
  }

  public boolean isDirectory() {
    return spec.getCardinality() == FileSelectionSpec.Cardinality.SINGLE_DIRECTORY;
  }

  @Override
  public boolean isImmutable() {
    return true;
  }

  @Override
  public void repr(Printer printer, StarlarkSemantics semantics) {
    StringBuilder sb = new StringBuilder();
    spec.appendPlaceholder(sb);
    printer.append(sb.toString());
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SelectedFile that && spec.equals(that.spec);
  }

  @Override
  public int hashCode() {
    return spec.hashCode();
  }
}
