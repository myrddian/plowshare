package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.protocol.ToolCall;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable ownership of a model's run call while consent is pending. Effective arguments are
 * retained with their original boundaries, stdin and timeout, and are never reconstructed from
 * approval display text.
 */
public interface SuspendedRunCalls {
  /**
   * One retained call. A started call without a result has uncertain effects and must not replay.
   */
  record Call(
      String approval,
      String conversation,
      String session,
      String side,
      ToolCall tool,
      String state,
      String decision,
      String answeredBy,
      String denialReason,
      String result,
      String owner,
      boolean released) {
    public Call(
        String approval,
        String conversation,
        String session,
        String side,
        ToolCall tool,
        String state,
        String decision,
        String answeredBy,
        String denialReason,
        String result) {
      this(
          approval,
          conversation,
          session,
          side,
          tool,
          state,
          decision,
          answeredBy,
          denialReason,
          result,
          null,
          true);
    }

    public Call(
        String approval,
        String conversation,
        String session,
        String side,
        ToolCall tool,
        String state,
        String decision,
        String answeredBy,
        String denialReason,
        String result,
        String owner) {
      this(
          approval,
          conversation,
          session,
          side,
          tool,
          state,
          decision,
          answeredBy,
          denialReason,
          result,
          owner,
          true);
    }

    public Call {
      Objects.requireNonNull(approval);
      Objects.requireNonNull(conversation);
      Objects.requireNonNull(tool);
      if (!"run".equals(tool.name())) throw new IllegalArgumentException("Only run is suspended");
    }
  }

  /** Retain before the requesting turn closes and before its approval notification is released. */
  default void retain(
      String approval, String conversation, String session, String side, ToolCall tool) {
    retain(approval, conversation, session, side, null, tool);
  }

  /** The admitted execution owner is retained independently of the person who answers. */
  void retain(
      String approval,
      String conversation,
      String session,
      String side,
      String owner,
      ToolCall tool);

  /** Unacknowledged calls in their original order, including results awaiting transcript append. */
  List<Call> pending(String conversation);

  /**
   * Includes acknowledged results, so a late answer handler cannot start a redundant model turn.
   */
  Optional<Call> find(String approval);

  /** Answered calls with an unacknowledged outcome, for server-owned recovery after restart. */
  List<Call> recoverable();

  /**
   * Atomically fence execution before any effect. Only an allowed, unstarted call can be claimed.
   */
  boolean claim(String approval);

  /** Release only after approval.post has been told, including its documented fail-open path. */
  void release(String approval);

  /** Retain the final model-visible result before appending it to the conversation. */
  void complete(String approval, String result);

  /** The result has been durably appended; a retry may not dispatch the command again. */
  void acknowledged(String approval);
}
