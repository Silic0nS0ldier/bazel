
package com.google.devtools.build.lib.platform;

import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnStrategy;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.common.collect.ImmutableList;

// Special wrapper for other spawn strategies that adds a filter
final public class PlatformScopedSpawnStrategy implements SpawnStrategy {

  private final SpawnStrategy innerSpawnStrategy;
  private final Label execPlatform;

  public PlatformScopedSpawnStrategy(SpawnStrategy innerSpawnStrategy, Label execPlatform) {
    this.innerSpawnStrategy = innerSpawnStrategy;
    this.execPlatform = execPlatform;
  }

  @Override
  public ImmutableList<SpawnResult> exec(Spawn spawn, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    return this.innerSpawnStrategy.exec(spawn, actionExecutionContext);
  }

  @Override
  public boolean canExec(Spawn spawn, ActionContext.ActionContextRegistry actionContextRegistry) {
    if (this.execPlatform == spawn.getExecutionPlatform().label()) {
      return this.innerSpawnStrategy.canExec(spawn, actionContextRegistry);
    }
    return false;
  }

  @Override
  public boolean canExecWithLegacyFallback(
      Spawn spawn, ActionContext.ActionContextRegistry actionContextRegistry) {
    if (this.execPlatform == spawn.getExecutionPlatform().label()) {
      return this.innerSpawnStrategy.canExecWithLegacyFallback(spawn, actionContextRegistry);
    }
    return false;
  }
}
