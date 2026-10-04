package io.aeyer.plowshare.server.agents;

/** Per-run messaging binding and protection of private participant history. */
public interface Messaging {
  SendMessageTool tool(RunExtras.Context context);

  /** True when this message transport owns and durably queued the approval continuation. */
  default boolean continueApproved(
      io.aeyer.plowshare.server.approvals.RunApproval approval, String utterance) {
    return false;
  }

  default AgentTool protect(AgentTool tool, AgentDefinition definition, String conversation) {
    return tool;
  }
}
