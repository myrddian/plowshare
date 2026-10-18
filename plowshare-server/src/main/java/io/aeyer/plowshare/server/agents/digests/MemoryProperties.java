package io.aeyer.plowshare.server.agents.digests;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.embedding.SpendingPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit operation policies; absent configuration is unlimited, zero disables inference. */
@ConfigurationProperties(prefix = "plowshare.memory", ignoreUnknownFields = false)
public final class MemoryProperties {
  private String navigationBudget = "unlimited";
  private String digestBudget = "unlimited";

  public String getNavigationBudget() {
    return navigationBudget;
  }

  public void setNavigationBudget(String value) {
    SpendingPolicy.limit(value, "plowshare.memory.navigation-budget");
    navigationBudget = value;
  }

  public void setNavigationBudget(int value) {
    setNavigationBudget(Integer.toString(value));
  }

  public String getDigestBudget() {
    return digestBudget;
  }

  public void setDigestBudget(String value) {
    SpendingPolicy.limit(value, "plowshare.memory.digest-budget");
    digestBudget = value;
  }

  public void setDigestBudget(int value) {
    setDigestBudget(Integer.toString(value));
  }

  public Integer navigationLimit() {
    return SpendingPolicy.limit(navigationBudget, "plowshare.memory.navigation-budget");
  }

  public Integer digestLimit() {
    return SpendingPolicy.limit(digestBudget, "plowshare.memory.digest-budget");
  }

  public Budget navigationAllowance() {
    return SpendingPolicy.budget(navigationLimit());
  }

  public Budget digestAllowance() {
    return SpendingPolicy.budget(digestLimit());
  }
}
