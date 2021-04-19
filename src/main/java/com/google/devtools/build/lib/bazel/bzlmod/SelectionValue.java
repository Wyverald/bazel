package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

@AutoValue
public abstract class SelectionValue implements SkyValue {

  @AutoCodec
  public static final SkyKey KEY = () -> SkyFunctions.SELECTION;

  public static SelectionValue create(String rootModuleName,
      ImmutableMap<ModuleKey, Module> depGraph) {
    return new AutoValue_SelectionValue(rootModuleName, depGraph);
  }

  public abstract String getRootModuleName();

  public abstract ImmutableMap<ModuleKey, Module> getDepGraph();

}
