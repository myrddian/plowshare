package io.aeyer.plowshare.server.events;

import java.util.UUID;

/** Source-bound notices for one Goal's authenticated human return route. */
public interface GoalInbox {
  enum Kind {
    RESULT,
    QUESTION,
    BLOCKED,
    CANCELLED
  }

  /**
   * Record a bounded reference to the exact Goal log. The identity is stable across retries. The
   * owning Goal operation supplies the account and binds delivery to its retained return intent.
   * Detailed content stays in the authorized Goal conversation.
   */
  String publish(String account, UUID goal, String conversation, UUID intent, Kind kind);

  /**
   * Withdraw the exact question from this account's Inbox without acknowledging or answering it.
   */
  void settleQuestion(String account, UUID goal, UUID intent);
}
