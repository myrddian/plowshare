package io.aeyer.plowshare.server.agents;

/** Capability ceiling for internal model-only tasks: bounded judgments, with no escalation. */
public final class SystemModelTasks {
  private SystemModelTasks() {}

  /** Fail before inference if configuration would give a SYSTEM model-only worker capabilities. */
  public static void requireModelOnly(AgentDefinition definition) {
    if (!definition.tools().isEmpty()
        || !definition.calls().isEmpty()
        || !definition.scopes().isEmpty()
        || !definition.orchestrations().isEmpty()
        || !definition.skills().isEmpty()
        || definition.board()
        || definition.reviewWith() != null)
      throw new IllegalStateException(
          "SYSTEM document workers cannot use tools, delegate, or acquire capabilities");
  }
}
