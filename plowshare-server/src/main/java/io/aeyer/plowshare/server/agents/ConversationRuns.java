package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.Origin;
import java.util.Objects;

/** The ordinary run owner reached with already-resolved, authorized conversation metadata. */
public interface ConversationRuns {
  /** Stable project identity and authenticated alias owner accompany the canonical row snapshot. */
  record Resolved(ConversationRecord conversation, long projectId, String owner) {
    public Resolved {
      Objects.requireNonNull(conversation);
      if (projectId <= 0
          || owner == null
          || owner.isBlank()
          || conversation.home().isGlobal()
          || conversation.origin() != Origin.TURN
          || conversation.parentId() != null
          || conversation.agent() != null)
        throw new IllegalArgumentException("An ordinary project conversation is required");
    }
  }

  /**
   * Reuses metadata for routing; Turn reads/claims current state and authorizes its current Home
   * before allowance access. A resolved snapshot is never a continuing work permit.
   */
  Runs.Started startResolved(Runs.Ask ask, Runs.Shown shown, Resolved resolved);
}
