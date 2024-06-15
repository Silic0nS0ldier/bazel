// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Optional;

/** The result of evaluating a single module extension (see {@link SingleExtensionEvalFunction}). */
@AutoCodec(explicitlyAllowClass = {Package.class})
@AutoValue
public abstract class SingleExtensionValue implements SkyValue {
  /**
   * Returns the repos generated by the extension. The key is the "internal name" (as specified by
   * the extension) of the repo, and the value is the the repo specs that defins the repository .
   */
  public abstract ImmutableMap<String, RepoSpec> getGeneratedRepoSpecs();

  /**
   * Returns the mapping from the canonical repo names of the repos generated by this extension to
   * their "internal name" (as specified by the extension).
   */
  public abstract ImmutableBiMap<RepositoryName, String> getCanonicalRepoNameToInternalNames();

  /**
   * Returns the information stored about the extension in the lockfile. Non-empty if and only if
   * the lockfile mode is UPDATE.
   */
  public abstract Optional<LockFileModuleExtension.WithFactors> getLockFileInfo();

  /**
   * Returns the buildozer fixup for incorrect use_repo declarations by the root module (if any).
   */
  public abstract Optional<RootModuleFileFixup> getFixup();

  @AutoCodec.Instantiator
  public static SingleExtensionValue create(
      ImmutableMap<String, RepoSpec> generatedRepoSpecs,
      ImmutableBiMap<RepositoryName, String> canonicalRepoNameToInternalNames,
      Optional<LockFileModuleExtension.WithFactors> lockFileInfo,
      Optional<RootModuleFileFixup> fixup) {
    return new AutoValue_SingleExtensionValue(
        generatedRepoSpecs, canonicalRepoNameToInternalNames, lockFileInfo, fixup);
  }

  public static Key key(ModuleExtensionId id) {
    return Key.create(id);
  }

  /**
   * Provides access to {@link SingleExtensionValue} without validating the imports for the
   * repositories generated by the extension. This is only meant for special applications such as
   * {@code bazel mod tidy}.
   */
  static EvalKey evalKey(ModuleExtensionId id) {
    return EvalKey.create(id);
  }

  /**
   * The {@link SkyKey} of a {@link SingleExtensionValue} containing the result of extension
   * evaluation.
   */
  @AutoCodec
  public static final class Key extends AbstractSkyKey<ModuleExtensionId> {
    private static final SkyKeyInterner<Key> interner = SkyKey.newInterner();

    private Key(ModuleExtensionId arg) {
      super(arg);
    }

    private static Key create(ModuleExtensionId arg) {
      return interner.intern(new Key(arg));
    }

    @VisibleForSerialization
    @AutoCodec.Interner
    static Key intern(Key key) {
      return interner.intern(key);
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.SINGLE_EXTENSION;
    }

    @Override
    public SkyKeyInterner<Key> getSkyKeyInterner() {
      return interner;
    }
  }

  /**
   * The {@link SkyKey} of an {@link SingleExtensionValue} containing the <b>unvalidated</b> result
   * of extension evaluation.
   */
  @AutoCodec
  static final class EvalKey extends AbstractSkyKey<ModuleExtensionId> {
    private static final SkyKeyInterner<EvalKey> interner = SkyKey.newInterner();

    private EvalKey(ModuleExtensionId arg) {
      super(arg);
    }

    private static EvalKey create(ModuleExtensionId arg) {
      return interner.intern(new EvalKey(arg));
    }

    @VisibleForSerialization
    @AutoCodec.Interner
    static EvalKey intern(EvalKey key) {
      return interner.intern(key);
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.SINGLE_EXTENSION_EVAL;
    }

    @Override
    public SkyKeyInterner<EvalKey> getSkyKeyInterner() {
      return interner;
    }
  }
}
