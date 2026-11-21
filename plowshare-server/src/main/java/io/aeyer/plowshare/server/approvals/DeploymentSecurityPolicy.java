package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.protocol.search.Verb;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable deployment rules and guidance from the operator's configuration root. */
public record DeploymentSecurityPolicy(
    List<ApprovalPolicy.Rule> rules, String guidance, Map<Verb, String> webFragments) {
  static final DeploymentSecurityPolicy NONE = new DeploymentSecurityPolicy(List.of(), "");

  public DeploymentSecurityPolicy(List<ApprovalPolicy.Rule> rules, String guidance) {
    this(rules, guidance, Map.of());
  }

  public DeploymentSecurityPolicy {
    rules = List.copyOf(rules);
    webFragments = Map.copyOf(webFragments);
    if (rules.stream()
        .anyMatch(rule -> rule.effect() != ApprovalPolicy.Effect.PERSONAL_READ_INTENT))
      throw new IllegalArgumentException(
          "Deployment rules supply Personal read intent. Put reviewer guidance in Markdown.");
    Objects.requireNonNull(guidance);
    if (guidance.length() > 16000)
      throw new IllegalArgumentException("Deployment security guidance exceeds 16000 characters");
  }

  public String webGuidance(Verb operation) {
    return (guidance + "\n\n" + webFragments.getOrDefault(operation, "")).strip();
  }
}
