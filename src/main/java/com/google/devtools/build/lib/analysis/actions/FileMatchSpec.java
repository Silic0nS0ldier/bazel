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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.UnixGlob;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;

/**
 * The immutable payload shared by {@link MatchedFile} and {@link MatchedFiles}: the sources,
 * patterns, optional {@code filter} callback, cardinality contract, and flags of a {@code
 * ctx.actions.match_*} call.
 *
 * <p>A spec is a pure, execution-time-resolved view over content that already has a generating
 * action; it creates no action and moves no bytes. {@link #resolve} produces the ordered set of
 * concrete children given an {@link InputMetadataProvider}, and is deterministic for a fixed set of
 * source-tree contents (so callers may memoise it).
 *
 * <p>Two specs are structurally equal, so identical declarations dedupe and shared actions compare
 * equal.
 */
@AutoCodec
public final class FileMatchSpec {

  /** What a match resolves to, and how it is enforced. */
  public enum Cardinality {
    /** Exactly one regular file ({@code match_file}). */
    SINGLE_FILE,
    /** Exactly one directory ({@code match_directory}). */
    SINGLE_DIRECTORY,
    /** A set ({@code match_files}). */
    SET
  }

  private final ImmutableList<Object> sources;
  private final ImmutableList<String> include;
  private final ImmutableList<String> exclude;
  @Nullable private final StarlarkFunction filter;
  private final StarlarkSemantics semantics;
  private final Cardinality cardinality;
  private final boolean excludeDirectories;
  private final boolean allowEmpty;

  @AutoCodec.Instantiator
  public FileMatchSpec(
      ImmutableList<Object> sources,
      ImmutableList<String> include,
      ImmutableList<String> exclude,
      @Nullable StarlarkFunction filter,
      StarlarkSemantics semantics,
      Cardinality cardinality,
      boolean excludeDirectories,
      boolean allowEmpty) {
    this.sources = sources;
    this.include = include;
    this.exclude = exclude;
    this.filter = filter;
    this.semantics = semantics;
    this.cardinality = cardinality;
    this.excludeDirectories = excludeDirectories;
    this.allowEmpty = allowEmpty;
  }

  public Cardinality getCardinality() {
    return cardinality;
  }

  public ImmutableList<Object> getSources() {
    return sources;
  }

  public ImmutableList<String> getInclude() {
    return include;
  }

  public ImmutableList<String> getExclude() {
    return exclude;
  }

  public boolean excludesDirectories() {
    return excludeDirectories;
  }

  public boolean allowsEmpty() {
    return allowEmpty;
  }

  /**
   * A stable display string for the {@code filter} callback ({@code <module label>%<function
   * name>}), or the empty string when there is no filter. Used by aquery structured output.
   */
  public String getFilterDisplay() {
    if (filter == null) {
      return "";
    }
    return BazelModuleContext.of(filter.getModule()).label() + "%" + filter.getName();
  }

  /**
   * The distinct root tree artifacts backing this match (transitively through match
   * sources), used to declare the consuming action's scheduling dependencies.
   */
  public ImmutableSet<SpecialArtifact> getSourceTreeArtifacts() {
    LinkedHashSet<SpecialArtifact> trees = new LinkedHashSet<>();
    collectSourceTrees(sources, trees);
    return ImmutableSet.copyOf(trees);
  }

  private static void collectSourceTrees(
      ImmutableList<Object> sources, LinkedHashSet<SpecialArtifact> out) {
    for (Object source : sources) {
      switch (source) {
        case SpecialArtifact tree -> out.add(tree.isSubTreeArtifact() ? tree.getParent() : tree);
        case MatchedFile sf -> collectSourceTrees(sf.getSpec().sources, out);
        case MatchedFiles fs -> collectSourceTrees(fs.getSpec().sources, out);
        default -> {}
      }
    }
  }

  /** A pattern-matched candidate: the artifact plus the source position and match path it got. */
  private record Candidate(Artifact artifact, int sourceIndex, String matchPath) {}

  /**
   * Resolves this match against the given metadata, returning the ordered members (files as
   * {@link TreeFileArtifact}s, directories as subtree {@link SpecialArtifact}s).
   *
   * @throws MatchResolutionException if the cardinality/kind contract is violated, a source's
   *     metadata is unavailable, or the {@code filter} callback misbehaves
   */
  public ImmutableList<Artifact> resolve(
      InputMetadataProvider metadataProvider, EventHandler eventHandler)
      throws MatchResolutionException, InterruptedException {
    boolean includeDirectories =
        cardinality == Cardinality.SINGLE_DIRECTORY || !excludeDirectories;

    List<Candidate> candidates = new ArrayList<>();
    for (int i = 0; i < sources.size(); i++) {
      gatherCandidates(sources.get(i), i, includeDirectories, metadataProvider, eventHandler)
          .forEach(candidates::add);
    }

    // Pattern filtering against the tree-relative match path (relative to each source's root).
    ImmutableList<String[]> includeSegs = compilePatterns(include);
    ImmutableList<String[]> excludeSegs = compilePatterns(exclude);
    List<Candidate> matched = new ArrayList<>();
    for (Candidate c : candidates) {
      String[] pathSegs = c.matchPath().split("/");
      if (matchesAny(includeSegs, pathSegs) && !matchesAny(excludeSegs, pathSegs)) {
        matched.add(c);
      }
    }

    // filter callback: receives the matched candidates as File objects, returns the subset to keep.
    List<Candidate> kept = filter == null ? matched : applyFilter(matched, eventHandler);

    // Canonical order: source position, then lexicographically by match path.
    kept.sort(
        Comparator.<Candidate>comparingInt(Candidate::sourceIndex)
            .thenComparing(Candidate::matchPath));

    checkKinds(kept);
    ImmutableList<Artifact> result =
        kept.stream().map(Candidate::artifact).collect(ImmutableList.toImmutableList());
    checkCardinality(result);
    return result;
  }

  private List<Candidate> gatherCandidates(
      Object source,
      int sourceIndex,
      boolean includeDirectories,
      InputMetadataProvider metadataProvider,
      EventHandler eventHandler)
      throws MatchResolutionException, InterruptedException {
    switch (source) {
      case SpecialArtifact special -> {
        SpecialArtifact root = special.isSubTreeArtifact() ? special.getParent() : special;
        PathFragment prefix =
            special.isSubTreeArtifact() ? special.getParentRelativePath() : PathFragment.EMPTY_FRAGMENT;
        return fromTree(root, prefix, sourceIndex, includeDirectories, metadataProvider);
      }
      case MatchedFile sf -> {
        // A match_directory result: resolve it to its one directory, then take children below it.
        ImmutableList<Artifact> resolved = sf.getSpec().resolve(metadataProvider, eventHandler);
        SpecialArtifact dir = (SpecialArtifact) resolved.get(0);
        return fromTree(
            dir.getParent(),
            dir.getParentRelativePath(),
            sourceIndex,
            includeDirectories,
            metadataProvider);
      }
      case MatchedFiles fs -> {
        // A prior set: its resolved members are the candidates, matched by their own tree-relative
        // path (relative to their own tree's root).
        List<Candidate> out = new ArrayList<>();
        for (Artifact member : fs.getSpec().resolve(metadataProvider, eventHandler)) {
          out.add(new Candidate(member, sourceIndex, treeRelative(member)));
        }
        return out;
      }
      default ->
          throw new MatchResolutionException(
              "internal error: unexpected match source " + Starlark.type(source));
    }
  }

  /**
   * Produces candidates from a tree artifact below {@code prefix}: files always, and directories
   * (as subtree artifacts) when {@code includeDirectories}. The match path is relative to {@code
   * prefix}.
   */
  private List<Candidate> fromTree(
      SpecialArtifact root,
      PathFragment prefix,
      int sourceIndex,
      boolean includeDirectories,
      InputMetadataProvider metadataProvider)
      throws MatchResolutionException {
    TreeArtifactValue tree = metadataProvider.getTreeMetadata(root);
    if (tree == null) {
      throw new MatchResolutionException(
          String.format(
              "metadata for source tree artifact %s (produced by %s) is not available",
              root.getExecPathString(), root.getArtifactOwner().getLabel()));
    }
    List<Candidate> out = new ArrayList<>();
    LinkedHashSet<PathFragment> directoryPaths = new LinkedHashSet<>();
    String prefixString = prefix.isEmpty() ? "" : prefix.getPathString() + "/";
    for (TreeFileArtifact child : tree.getChildren()) {
      String treeRel = child.getTreeRelativePathString();
      if (!prefixString.isEmpty() && !treeRel.startsWith(prefixString)) {
        continue;
      }
      String matchPath = treeRel.substring(prefixString.length());
      if (matchPath.isEmpty()) {
        continue;
      }
      out.add(new Candidate(child, sourceIndex, matchPath));
      if (includeDirectories) {
        // Record every proper ancestor directory of this child, relative to the source root.
        PathFragment childTreeRel = PathFragment.create(treeRel).getParentDirectory();
        while (childTreeRel != null
            && !childTreeRel.isEmpty()
            && childTreeRel.startsWith(prefix)
            && !childTreeRel.equals(prefix)) {
          directoryPaths.add(childTreeRel);
          childTreeRel = childTreeRel.getParentDirectory();
        }
      }
    }
    if (includeDirectories) {
      for (PathFragment dirPath : directoryPaths) {
        SpecialArtifact subtree =
            SpecialArtifact.createSubTreeArtifact(root, dirPath, root.getGeneratingActionKey());
        out.add(
            new Candidate(subtree, sourceIndex, dirPath.relativeTo(prefix).getPathString()));
      }
    }
    return out;
  }

  private List<Candidate> applyFilter(List<Candidate> matched, EventHandler eventHandler)
      throws MatchResolutionException, InterruptedException {
    ImmutableList<Artifact> candidateArtifacts =
        matched.stream().map(Candidate::artifact).collect(ImmutableList.toImmutableList());
    Object returnValue;
    try (Mutability mu = Mutability.create("file match filter")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, semantics);
      thread.setPrintHandler(Event.makeDebugPrintHandler(eventHandler));
      returnValue =
          Starlark.call(
              thread,
              filter,
              ImmutableList.of(StarlarkList.immutableCopyOf(candidateArtifacts)),
              /* kwargs= */ ImmutableMap.of());
    } catch (EvalException e) {
      throw new MatchResolutionException(
          String.format("filter function %s failed: %s", filter.getName(), e.getMessage()));
    }
    if (!(returnValue instanceof StarlarkList<?> keptList)) {
      throw new MatchResolutionException(
          String.format(
              "filter function %s must return a list, got %s",
              filter.getName(), Starlark.type(returnValue)));
    }
    // Map each returned File back to its candidate; reject foreign results.
    ImmutableSet<Artifact> allowed = ImmutableSet.copyOf(candidateArtifacts);
    List<Candidate> kept = new ArrayList<>();
    for (Object element : keptList) {
      if (!(element instanceof Artifact artifact) || !allowed.contains(artifact)) {
        throw new MatchResolutionException(
            String.format(
                "filter function %s returned %s, which was not among the candidates",
                filter.getName(), Starlark.repr(element, semantics)));
      }
      // Find the candidate carrying this artifact (order re-imposed afterwards).
      for (Candidate c : matched) {
        if (c.artifact().equals(artifact)) {
          kept.add(c);
          break;
        }
      }
    }
    return kept;
  }

  private void checkKinds(List<Candidate> kept) throws MatchResolutionException {
    for (Candidate c : kept) {
      boolean isDirectory = c.artifact().isTreeArtifact();
      if (cardinality == Cardinality.SINGLE_FILE && isDirectory) {
        throw new MatchResolutionException(
            String.format("match_file matched a directory: %s", c.matchPath()));
      }
      if (cardinality == Cardinality.SINGLE_DIRECTORY && !isDirectory) {
        throw new MatchResolutionException(
            String.format("match_directory matched a regular file: %s", c.matchPath()));
      }
    }
  }

  private void checkCardinality(ImmutableList<Artifact> result)
      throws MatchResolutionException {
    switch (cardinality) {
      case SINGLE_FILE, SINGLE_DIRECTORY -> {
        if (result.isEmpty()) {
          throw new MatchResolutionException(
              describe() + " resolved to no matches, but exactly one is required");
        }
        if (result.size() > 1) {
          throw new MatchResolutionException(
              describe()
                  + " resolved to more than one match; narrow the pattern or add a filter. Matches: "
                  + Artifact.asExecPaths(result));
        }
      }
      case SET -> {
        if (result.isEmpty() && !allowEmpty) {
          throw new MatchResolutionException(
              describe()
                  + " resolved to no matches. Set allow_empty = True to permit an empty match.");
        }
      }
    }
  }

  private static String treeRelative(Artifact member) {
    return member instanceof TreeFileArtifact tfa
        ? tfa.getTreeRelativePathString()
        : member.getParentRelativePath().getPathString();
  }

  private static ImmutableList<String[]> compilePatterns(ImmutableList<String> patterns) {
    return patterns.stream().map(p -> p.split("/")).collect(ImmutableList.toImmutableList());
  }

  private static boolean matchesAny(ImmutableList<String[]> patternSegs, String[] pathSegs) {
    for (String[] pattern : patternSegs) {
      if (UnixGlob.matches(pattern, pathSegs)) {
        return true;
      }
    }
    return false;
  }

  /** Contributes this match's identity to a consuming action's key. */
  public void addToFingerprint(ActionKeyContext actionKeyContext, Fingerprint fp) {
    fp.addString(cardinality.name());
    fp.addBoolean(excludeDirectories);
    fp.addBoolean(allowEmpty);
    fp.addStrings(include);
    fp.addStrings(exclude);
    for (Object source : sources) {
      switch (source) {
        case Artifact artifact -> fp.addPath(artifact.getExecPath());
        case MatchedFile sf -> sf.getSpec().addToFingerprint(actionKeyContext, fp);
        case MatchedFiles fs -> fs.getSpec().addToFingerprint(actionKeyContext, fp);
        default -> {}
      }
    }
    if (filter != null) {
      // Identity, not evaluation: the callback body cannot see tree children at analysis time
      // (the b/160181927 soundness hole). Name + module digest keys the callback; the resolved-set
      // digest on the input side keeps the cache honest.
      fp.addString(filter.getName());
      fp.addBytes(BazelModuleContext.of(filter.getModule()).bzlTransitiveDigest());
    }
  }

  /** Renders the stable single-line placeholder form used by {@code repr}, aquery, and errors. */
  public void appendPlaceholder(StringBuilder sb) {
    sb.append(functionName()).append("(sources = [");
    for (int i = 0; i < sources.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(sourceString(sources.get(i)));
    }
    sb.append("]");
    if (!include.equals(ImmutableList.of("**"))) {
      sb.append(", include = ");
      appendStringList(sb, include);
    }
    if (!exclude.isEmpty()) {
      sb.append(", exclude = ");
      appendStringList(sb, exclude);
    }
    if (filter != null) {
      sb.append(", filter = ")
          .append(BazelModuleContext.of(filter.getModule()).label())
          .append("%")
          .append(filter.getName());
    }
    sb.append(")");
  }

  private static void appendStringList(StringBuilder sb, ImmutableList<String> values) {
    sb.append("[");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append("\"").append(values.get(i)).append("\"");
    }
    sb.append("]");
  }

  private String functionName() {
    return switch (cardinality) {
      case SINGLE_FILE -> "match_file";
      case SINGLE_DIRECTORY -> "match_directory";
      case SET -> "match_files";
    };
  }

  private static String sourceString(Object source) {
    if (source instanceof Artifact artifact) {
      return artifact.getExecPathString();
    }
    StringBuilder sb = new StringBuilder();
    if (source instanceof MatchedFile sf) {
      sf.getSpec().appendPlaceholder(sb);
    } else if (source instanceof MatchedFiles fs) {
      fs.getSpec().appendPlaceholder(sb);
    }
    return sb.toString();
  }

  private String describe() {
    StringBuilder sb = new StringBuilder();
    appendPlaceholder(sb);
    return sb.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FileMatchSpec that)) {
      return false;
    }
    return cardinality == that.cardinality
        && excludeDirectories == that.excludeDirectories
        && allowEmpty == that.allowEmpty
        && sources.equals(that.sources)
        && include.equals(that.include)
        && exclude.equals(that.exclude)
        && java.util.Objects.equals(filter, that.filter);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(
        cardinality, excludeDirectories, allowEmpty, sources, include, exclude, filter);
  }

  /** Thrown when a match cannot be resolved; converted to an action failure by the consumer. */
  public static final class MatchResolutionException extends Exception {
    public MatchResolutionException(String message) {
      super(message);
    }
  }
}
