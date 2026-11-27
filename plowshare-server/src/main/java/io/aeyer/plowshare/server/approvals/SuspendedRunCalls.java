package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.files.FileReadWindow;
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
   * One retained call. A started call without a result must not replay. Its receipt can establish a
   * more precise effect than the claim alone.
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
      boolean released,
      ToolEffectReceipt effectReceipt,
      FileReadWindow fileWindow) {
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
        String owner,
        boolean released,
        ToolEffectReceipt effectReceipt) {
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
          released,
          effectReceipt,
          null);
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
        String owner,
        boolean released) {
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
          released,
          null);
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
      if (!supported(tool.name()))
        throw new IllegalArgumentException("Unsupported retained approval tool");
    }
  }

  static boolean supported(String name) {
    return List.of(
            "run", "file_read", "file_edit", "file_delete", "file_move", "web_search", "web_fetch")
        .contains(name);
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

  /** Retain the exact recorded answer that declared the call. Null is reserved for legacy paths. */
  void retainAt(
      String approval,
      String conversation,
      String session,
      String side,
      String owner,
      ToolCall tool,
      Integer answerOrdinal);

  /** Unacknowledged calls in their original order, including results awaiting transcript append. */
  List<Call> pending(String conversation);

  /**
   * Includes acknowledged results, so a late answer handler cannot start a redundant model turn.
   */
  Optional<Call> find(String approval);

  /** Disarm continuation after the owner fenced and drained the exact conversation. */
  default void retireGoalCall(String conversation, String approval) {
    throw new UnsupportedOperationException("Goal approval retirement is unavailable");
  }

  /** Answered calls with an unacknowledged outcome, for server-owned recovery after restart. */
  List<Call> recoverable();

  /**
   * Atomically fence execution before any effect. Only an allowed, unstarted call can be claimed.
   */
  boolean claim(String approval);

  /** Retain an observation of the claimed call without changing its payload or execution fence. */
  void recordEffect(String approval, ToolEffectReceipt receipt);

  /** Release only after approval.post has been told, including its documented fail-open path. */
  void release(String approval);

  /** Retain the final model-visible result before appending it to the conversation. */
  void complete(String approval, String result);

  /** Retain both the exact payload (including empty text) and its independent window facts. */
  void completeRead(String approval, String result, FileReadWindow window);

  /** The result has been durably appended; a retry may not dispatch the command again. */
  void acknowledged(String approval);
}
