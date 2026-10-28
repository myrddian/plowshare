package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.protocol.Usage.*;

/**
 * Authorized usage snapshots. Reads recheck current ownership/membership, including signed cursors.
 */
public interface UsageReportRepository {
  Report report(String account, Resolved query);

  /** Same live policy and snapshot as report; background admission yields to foreground reads. */
  default Report refresh(String account, Resolved query) {
    return report(account, query);
  }

  Audit calls(String account, Resolved query);

  AttemptPage attempts(String account, Resolved query, String call, String cursor);

  void requireConversation(String account, String conversation);

  UsageAttribution countOwner(String account, String conversation);
}
