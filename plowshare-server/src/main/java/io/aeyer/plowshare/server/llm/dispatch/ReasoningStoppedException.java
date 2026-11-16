package io.aeyer.plowshare.server.llm.dispatch;

/**
 * A reasoning-only stream exceeded its configured limit. This is not a connection retry: the
 * provider attempt failed and was cancelled. Only the agent runtime may claim a new model call. No
 * partial answer or tool-call fragment may have been emitted when this failure is raised.
 */
public final class ReasoningStoppedException extends LlmException {
  private final ReasoningPolicy policy;

  public ReasoningStoppedException(ReasoningPolicy policy, String reason) {
    super("Reasoning-only generation stopped: " + reason);
    this.policy = policy;
  }

  public ReasoningPolicy policy() {
    return policy;
  }
}
