package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.protocol.ToolCall;
import java.util.List;

/** Once-only execution journal for file mutations admitted without a human question. */
public interface DirectFileCalls {
  record Pending(String invocation, ToolCall tool, ToolEffectReceipt receipt, String result) {}

  /** Create the durable fence. A duplicate call fails before it can dispatch again. */
  String claimDirect(String conversation, int answerOrdinal, ToolCall tool, String owner);

  /** Calls through the stopped turn being resumed, in invocation order. */
  List<Pending> pendingDirect(String conversation, int stoppedTurn);

  void recordEffect(String invocation, ToolEffectReceipt receipt);

  void complete(String invocation, String result);

  void acknowledged(String invocation);
}
