package io.aeyer.plowshare.server.agents;

/** Resolves the effective harness profile of a model binding before an alias selects a prompt. */
@FunctionalInterface
public interface AgentGuidance {
  /** No assigned guidance; aliases use their unqualified or least-guided candidate. */
  AgentGuidance NONE = model -> null;

  /**
   * @return the effective profile, or null when no profile applies
   * @throws IllegalStateException if the binding can route to models with different profiles;
   *     choosing a prompt before that routing decision would be ambiguous
   */
  String profileFor(String model);
}
