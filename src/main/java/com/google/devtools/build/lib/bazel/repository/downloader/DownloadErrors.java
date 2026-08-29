// Copyright 2025 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.repository.downloader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Renders download failures for humans, including their causes and suppressed exceptions. */
public final class DownloadErrors {

  private DownloadErrors() {}

  /**
   * Formats {@code failure} together with its cause chain and its suppressed exceptions.
   *
   * <p>A failed download attaches the individual per-URL and per-attempt failures to the thrown
   * exception as suppressed exceptions. Those carry most of the diagnostic value, but none of it is
   * shown by {@link Throwable#toString}, which is all that ends up in a Bazel error message.
   */
  public static String describe(Throwable failure) {
    return describe(failure, "");
  }

  /** As {@link #describe(Throwable)}, but prefixes every line with {@code indent}. */
  public static String describe(Throwable failure, String indent) {
    StringBuilder description = new StringBuilder();
    append(failure, indent, "", Collections.newSetFromMap(new IdentityHashMap<>()), description);
    return description.toString();
  }

  private static void append(
      Throwable failure,
      String indent,
      String label,
      Set<Throwable> seen,
      StringBuilder description) {
    description.append(indent).append(label);
    if (!seen.add(failure)) {
      description.append("[circular reference to ").append(failure.getClass().getName()).append(']');
      return;
    }
    description.append(failure);

    String nestedIndent = indent + "  ";
    if (failure.getCause() != null) {
      description.append('\n');
      append(failure.getCause(), nestedIndent, "caused by: ", seen, description);
    }
    appendSuppressed(failure, nestedIndent, seen, description);
  }

  /**
   * Appends the suppressed exceptions of {@code failure}, collapsing runs of identically rendered
   * ones. Retrying downloaders routinely produce a dozen copies of the same failure, one per
   * attempt.
   */
  private static void appendSuppressed(
      Throwable failure, String indent, Set<Throwable> seen, StringBuilder description) {
    List<String> rendered = new ArrayList<>();
    for (Throwable suppressed : failure.getSuppressed()) {
      StringBuilder child = new StringBuilder();
      append(suppressed, indent, "also: ", seen, child);
      rendered.add(child.toString());
    }
    for (int i = 0; i < rendered.size(); ) {
      int end = i + 1;
      while (end < rendered.size() && rendered.get(end).equals(rendered.get(i))) {
        end++;
      }
      description.append('\n').append(rendered.get(i));
      if (end - i > 1) {
        description.append(" (x").append(end - i).append(')');
      }
      i = end;
    }
  }
}
