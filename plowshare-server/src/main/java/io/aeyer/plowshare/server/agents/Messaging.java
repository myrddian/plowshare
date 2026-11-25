package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.messaging.MessageRefused.Code;
import io.aeyer.plowshare.server.messaging.MessageToolCodec;

/** Per-run messaging binding and protection of private participant history. */
public interface Messaging {
  SendMessageTool tool(RunExtras.Context context);

  /** Read destinations with the same source context as a new send. */
  default AgentListTool directory(RunExtras.Context context, boolean canSend) {
    return new AgentListTool(
        (request, home) ->
            MessageToolCodec.refusal(Code.MESSAGING_UNAVAILABLE, "Messaging is unavailable."));
  }

  /** True when this message transport owns and durably queued the approval continuation. */
  default boolean continueApproved(RunApproval approval, String utterance) {
    return false;
  }

  /** Settles a denied original budget request without creating or paying for another turn. */
  default boolean budgetDenied(RunApproval approval, String text) {
    return false;
  }

  /** Settles an interrupted recovery on its original message without replaying tool effects. */
  default boolean budgetInterrupted(RunApproval approval, String text) {
    return false;
  }

  default AgentTool protect(AgentTool tool, AgentDefinition definition, String conversation) {
    return tool;
  }
}
