package io.aeyer.plowshare.server.archive;

import java.util.List;

/** Read-only conversation history; callers enforce current authority for the owning scope. */
public interface TurnHistoryRepository {
  /** Existing turns for this exact conversation, oldest ordinal first; never creates work. */
  List<TurnRecord> forConversation(String conversation);
}
