package io.aeyer.plowshare.server.agents.digests;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "plowshare.memory", ignoreUnknownFields = false)
public final class MemoryProperties {
  private int navigationBudget = 32;
  private int digestBudget = 100;

  public int getNavigationBudget() {
    return navigationBudget;
  }

  public void setNavigationBudget(int value) {
    navigationBudget = positive(value);
  }

  public int getDigestBudget() {
    return digestBudget;
  }

  public void setDigestBudget(int value) {
    digestBudget = positive(value);
  }

  private static int positive(int value) {
    if (value < 1) throw new IllegalArgumentException("Memory budgets must be positive");
    return value;
  }
}
