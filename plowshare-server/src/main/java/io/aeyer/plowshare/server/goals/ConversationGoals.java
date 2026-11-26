package io.aeyer.plowshare.server.goals;

import java.util.UUID;

/** Controls for an explicitly authorized objective in an ordinary conversation. */
public interface ConversationGoals {
  enum State {
    READY,
    RUNNING,
    PAUSING,
    PAUSED,
    BLOCKED,
    STOPPING,
    COMPLETE,
    CANCELLED
  }

  enum Action {
    PAUSE,
    RESUME,
    CANCEL
  }

  enum Reason {
    OPERATOR_PAUSED,
    ACTIVATION_LIMIT,
    MODEL_ALLOWANCE,
    NO_PROGRESS,
    TURN_FAILED,
    AUTHORITY_OR_SOURCE_CHANGED,
    INELIGIBLE_CONVERSATION,
    AGENT_BLOCKED,
    UNCONFIRMED_TURN,
    STOP_REQUESTED
  }

  record Start(
      String conversation,
      UUID requestId,
      String agent,
      String objective,
      String criteria,
      Integer maxActivations) {}

  record Control(
      String conversation, UUID requestId, UUID goal, int version, Integer maxActivations) {}

  record Status(
      UUID goal,
      String conversation,
      String agent,
      String objective,
      String criteria,
      State state,
      int version,
      int activations,
      int maxActivations,
      String lastJob,
      Integer lastTurn,
      Reason reason,
      String detail,
      Integer modelCallsSpent,
      Integer modelCallLimit) {}

  Status start(String actor, Start request);

  /**
   * Returns the latest goal or the exact receipt for a caller-owned request in this conversation.
   */
  Status get(String actor, String conversation, UUID requestId);

  Status control(String actor, Action action, Control request);
}
