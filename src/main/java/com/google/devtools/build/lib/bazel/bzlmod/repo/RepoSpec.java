package com.google.devtools.build.lib.bazel.bzlmod.repo;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;

public class RepoSpec {
  private final String ruleClass;
  private final ImmutableMap<String, Object> attributes;

  public RepoSpec(String ruleClass, ImmutableMap<String, Object> attributes) {
    this.ruleClass = ruleClass;
    this.attributes = attributes;
  }

  public String getRuleClass() {
    return ruleClass;
  }

  public ImmutableMap<String, Object> getAttributes() {
    return attributes;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RepoSpec repoSpec = (RepoSpec) o;
    return Objects.equal(ruleClass, repoSpec.ruleClass) &&
        Objects.equal(attributes, repoSpec.attributes);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(ruleClass, attributes);
  }

  @Override
  public String toString() {
    return "RepoSpec{" +
        "ruleClass='" + ruleClass + '\'' +
        ", attributes=" + attributes +
        '}';
  }
}
