package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.protocol.Usage.*;

/** Reporting capability with no inference or accounting-write authority. */
public interface UsageReports {
  Resolved resolve(String type, Filter filter);

  Report report(String account, Resolved query);

  Audit calls(String account, Resolved query);

  AttemptPage attempts(String account, Resolved query, String call, String cursor);

  void requireConversation(String account, String conversation);

  UsageAttribution countOwner(String account, String conversation);
}
