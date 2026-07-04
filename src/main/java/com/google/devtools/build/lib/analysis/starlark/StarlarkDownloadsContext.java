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
package com.google.devtools.build.lib.analysis.starlark;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.Tuple;

/**
 * The context object passed to a rule's {@code downloads} callback.
 *
 * <p>Deliberately restricted: it exposes only the target's label and its non-configurable
 * attributes, so that the set of declared downloads is a pure function of loading-phase
 * information, independent of the build configuration.
 */
public final class StarlarkDownloadsContext implements StarlarkValue {

  private static final String NO_SUCH_ATTRIBUTE_ERROR =
      "no such attribute '%s' (either it is not defined or it is configurable; only"
          + " configurable = False attributes are available to the downloads callback)";

  /** A single download declared via {@code ctx.download()}. */
  public static final class Declaration {
    private final String path;
    private final ImmutableList<URI> urls;
    private final String integrity;
    private final String canonicalId;
    private final boolean executable;

    private Declaration(
        String path,
        ImmutableList<URI> urls,
        String integrity,
        String canonicalId,
        boolean executable) {
      this.path = path;
      this.urls = urls;
      this.integrity = integrity;
      this.canonicalId = canonicalId;
      this.executable = executable;
    }

    public String getPath() {
      return path;
    }

    public ImmutableList<URI> getUrls() {
      return urls;
    }

    public String getIntegrity() {
      return integrity;
    }

    public String getCanonicalId() {
      return canonicalId;
    }

    public boolean isExecutable() {
      return executable;
    }
  }

  private final Label label;
  private final StructImpl attrObject;
  private final LinkedHashMap<String, Declaration> declarations = new LinkedHashMap<>();

  public StarlarkDownloadsContext(Rule rule) {
    this.label = rule.getLabel();
    NonconfigurableAttributeMapper mapper = NonconfigurableAttributeMapper.of(rule);
    LinkedHashMap<String, Object> attrs = new LinkedHashMap<>();
    for (Attribute attribute : rule.getRuleClassObject().getAttributeProvider().getAttributes()) {
      if (attribute.isConfigurable() || attribute.isLateBound()) {
        continue;
      }
      Object value = mapper.get(attribute.getName(), attribute.getType());
      attrs.put(
          attribute.getPublicName(),
          value == null ? Starlark.NONE : Attribute.valueToStarlark(value));
    }
    attrs.put("name", rule.getName());
    this.attrObject = StructProvider.STRUCT.create(attrs, NO_SUCH_ATTRIBUTE_ERROR);
  }

  @StarlarkMethod(name = "label", structField = true, doc = "The label of the target.")
  public Label getLabel() {
    return label;
  }

  @StarlarkMethod(
      name = "attr",
      structField = true,
      doc =
          "A struct of the target's non-configurable attribute values (attributes declared with"
              + " <code>configurable = False</code>, plus <code>name</code>). Configurable"
              + " attributes are not accessible: download declarations must be independent of the"
              + " build configuration.")
  public StructImpl getAttr() {
    return attrObject;
  }

  @StarlarkMethod(
      name = "download",
      doc =
          "Declares a download. The downloaded file is available in the rule implementation"
              + " function as <code>ctx.downloads[path]</code>. The download is lazy: it is only"
              + " fetched when the file is actually needed by the build, and content-addressed"
              + " caches are consulted before the network.",
      parameters = {
        @Param(
            name = "path",
            named = true,
            doc =
                "The key under which the rule implementation retrieves the downloaded file, unique"
                    + " within the target. Also used as the trailing component of the file's"
                    + " output path."),
        @Param(
            name = "urls",
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            doc =
                "Candidate http(s) URLs, tried in order. All must serve identical content."),
        @Param(
            name = "integrity",
            named = true,
            doc =
                "A Subresource Integrity checksum (<code>sha256-</code>, <code>sha384-</code> or"
                    + " <code>sha512-</code>) pinning the content of the download. Exactly one"
                    + " checksum must be given; the multi-checksum form of the SRI specification"
                    + " is not accepted."),
        @Param(
            name = "canonical_id",
            named = true,
            defaultValue = "''",
            doc =
                "If non-empty, restrict download cache hits to entries that were added to the"
                    + " cache with the same canonical ID. Matches the semantics of the"
                    + " repository rule download API."),
        @Param(
            name = "executable",
            named = true,
            defaultValue = "False",
            doc = "Whether the downloaded file is marked executable."),
      })
  public void download(
      String path, Sequence<?> urls, String integrity, String canonicalId, boolean executable)
      throws EvalException {
    PathFragment pathFragment = PathFragment.create(path);
    if (path.isEmpty()
        || pathFragment.isAbsolute()
        || pathFragment.containsUplevelReferences()) {
      throw Starlark.errorf(
          "path must be a non-empty relative path without uplevel references, got '%s'", path);
    }
    if (declarations.containsKey(path)) {
      throw Starlark.errorf("download path '%s' is declared more than once", path);
    }
    // Each declared path becomes a file in the same output directory, so no path may also be
    // needed as a directory. Detect it here rather than letting the artifact prefix conflict
    // check produce a less actionable error at the end of analysis.
    for (String existing : declarations.keySet()) {
      PathFragment existingFragment = PathFragment.create(existing);
      if (pathFragment.startsWith(existingFragment) || existingFragment.startsWith(pathFragment)) {
        throw Starlark.errorf(
            "download path '%s' conflicts with download path '%s': no download path may be a"
                + " prefix of another",
            path, existing);
      }
    }
    ImmutableList<String> urlStrings =
        ImmutableList.copyOf(Sequence.cast(urls, String.class, "urls"));
    if (urlStrings.isEmpty()) {
      throw Starlark.errorf("urls must contain at least one URL");
    }
    ImmutableList.Builder<URI> uris = ImmutableList.builder();
    for (String url : urlStrings) {
      URI uri;
      try {
        uri = new URI(url);
      } catch (URISyntaxException e) {
        throw Starlark.errorf("invalid URL '%s': %s", url, e.getMessage());
      }
      if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
        throw Starlark.errorf("unsupported URL scheme in '%s': only http and https are allowed", url);
      }
      uris.add(uri);
    }
    validateIntegrity(integrity);
    declarations.put(
        path, new Declaration(path, uris.build(), integrity, canonicalId, executable));
  }

  private static void validateIntegrity(String integrity) throws EvalException {
    if (integrity.chars().anyMatch(Character::isWhitespace)) {
      throw Starlark.errorf(
          "invalid integrity '%s': exactly one checksum must be given (the multi-checksum form of"
              + " the SRI specification is not supported)",
          integrity);
    }
    int dash = integrity.indexOf('-');
    if (dash <= 0) {
      throw Starlark.errorf(
          "invalid integrity '%s': must be a Subresource Integrity checksum such as"
              + " 'sha256-<base64>'",
          integrity);
    }
    String algorithm = integrity.substring(0, dash);
    int expectedLength =
        switch (algorithm) {
          case "sha256" -> 32;
          case "sha384" -> 48;
          case "sha512" -> 64;
          default -> -1;
        };
    if (expectedLength == -1) {
      throw Starlark.errorf(
          "invalid integrity '%s': unsupported algorithm '%s' (supported: sha256, sha384,"
              + " sha512)",
          integrity, algorithm);
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(integrity.substring(dash + 1));
    } catch (IllegalArgumentException e) {
      throw Starlark.errorf("invalid integrity '%s': checksum is not valid base64", integrity);
    }
    if (decoded.length != expectedLength) {
      throw Starlark.errorf(
          "invalid integrity '%s': decoded checksum has %d bytes, expected %d for %s",
          integrity, decoded.length, expectedLength, algorithm);
    }
  }

  /** Returns the declarations recorded so far, keyed by path, in declaration order. */
  public Map<String, Declaration> getDeclarations() {
    return declarations;
  }

  /**
   * Evaluates a rule's {@code downloads} callback and returns the declarations, keyed by path.
   *
   * <p>Requires only the {@link Rule} (a loading-phase product) and the Starlark semantics: the
   * declared download set is a pure function of loading-phase information, so this is usable both
   * during analysis (to register {@code DownloadAction}s) and from loading-phase-only tooling such
   * as {@code bazel vendor}.
   */
  public static ImmutableMap<String, Declaration> evaluate(
      Rule rule, StarlarkFunction downloadsCallback, StarlarkSemantics semantics)
      throws EvalException, InterruptedException {
    StarlarkDownloadsContext downloadsContext = new StarlarkDownloadsContext(rule);
    try (Mutability mu = Mutability.create("downloads callback")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, semantics);
      Starlark.call(
          thread, downloadsCallback, Tuple.of(downloadsContext), ImmutableMap.of());
    }
    return ImmutableMap.copyOf(downloadsContext.getDeclarations());
  }

  @Override
  public void repr(Printer printer, StarlarkSemantics semantics) {
    printer.append("<downloads_ctx for ").append(label.toString()).append(">");
  }
}
