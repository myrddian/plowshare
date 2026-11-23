package io.aeyer.plowshare.server.approvals;

import java.util.List;

/** Receipt summaries for the rows of an already authorized Trajectory page. */
public interface TrajectoryToolReceipts {
  record Recorded(
      String invocationId,
      int answerOrdinal,
      String callId,
      Integer resultOrdinal,
      ToolEffectReceipt receipt) {}

  List<Recorded> forPage(
      String conversation, List<Integer> answerOrdinals, List<Integer> resultOrdinals);
}
