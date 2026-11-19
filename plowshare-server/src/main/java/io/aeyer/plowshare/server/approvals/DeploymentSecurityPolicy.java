package io.aeyer.plowshare.server.approvals;

import java.util.List;
import java.util.Objects;

/** Immutable deployment rules and guidance from the operator's configuration root. */
public record DeploymentSecurityPolicy(List<ApprovalPolicy.Rule> rules, String guidance) {
  static final DeploymentSecurityPolicy NONE = new DeploymentSecurityPolicy(List.of(), "");

  public DeploymentSecurityPolicy {
    rules = List.copyOf(rules);
    if (rules.stream()
        .anyMatch(rule -> rule.effect() != ApprovalPolicy.Effect.PERSONAL_READ_INTENT))
      throw new IllegalArgumentException(
          "Deployment rules supply Personal read intent. Put reviewer guidance in Markdown.");
    Objects.requireNonNull(guidance);
    if (guidance.length() > 16000)
      throw new IllegalArgumentException("Deployment security guidance exceeds 16000 characters");
  }
}
