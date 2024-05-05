// Copyright 2024 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.platform;

import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnStrategy;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.common.collect.ImmutableList;

/** Wraps a spawn strategy with a execution platform filter. */
final public class PlatformFilteredSpawnStrategy implements SpawnStrategy {

  private final SpawnStrategy innerSpawnStrategy;
  private final ImmutableList<Label> forbiddenExecPlatforms;

  public PlatformFilteredSpawnStrategy(SpawnStrategy innerSpawnStrategy, ImmutableList<Label> forbiddenExecPlatforms) {
    this.innerSpawnStrategy = innerSpawnStrategy;
    this.forbiddenExecPlatforms = forbiddenExecPlatforms;
  }

  @Override
  public ImmutableList<SpawnResult> exec(Spawn spawn, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    return this.innerSpawnStrategy.exec(spawn, actionExecutionContext);
  }

  private boolean isSpawnAllowed(Spawn spawn) {
    return !this.forbiddenExecPlatforms.contains(spawn.getExecutionPlatform().label());
  }

  @Override
  public boolean canExec(Spawn spawn, ActionContext.ActionContextRegistry actionContextRegistry) {
    if (this.isSpawnAllowed(spawn)) {
      return this.innerSpawnStrategy.canExec(spawn, actionContextRegistry);
    }
    return false;
  }

  @Override
  public boolean canExecWithLegacyFallback(
      Spawn spawn, ActionContext.ActionContextRegistry actionContextRegistry) {
    if (this.isSpawnAllowed(spawn)) {
      return this.innerSpawnStrategy.canExecWithLegacyFallback(spawn, actionContextRegistry);
    }
    return false;
  }
}