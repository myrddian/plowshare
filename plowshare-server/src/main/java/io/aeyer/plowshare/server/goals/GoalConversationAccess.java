package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.util.List;
import java.util.UUID;

/**
 * Current Goal ownership and content visibility for existing conversation routes and queued
 * replies.
 */
public interface GoalConversationAccess {
  enum Visibility {
    ORDINARY,
    READABLE,
    RESTRICTED,
    UNAVAILABLE
  }

  /** Evidence from an already materialized reply, never a reason to reread the reply payload. */
  record Disclosure(UUID goal, boolean readable, List<UUID> namedListeners) {
    public Disclosure {
      namedListeners = List.copyOf(namedListeners);
    }

    public static Disclosure forStatus(GoalAgents.Status status) {
      return new Disclosure(
          status.goal(),
          status.readable(),
          status.relayListeners().stream()
              .filter(wake -> wake.topic() != null)
              .map(GoalAgents.RelayWake::binding)
              .toList());
    }
  }

  /** An ordinary conversation keeps the owning surface's normal access policy. */
  Visibility visibility(String account, String conversation);

  default boolean requireLog(String account, String conversation) {
    var access = visibility(account, conversation);
    if (access == Visibility.RESTRICTED || access == Visibility.UNAVAILABLE)
      throw new NotFoundFault("Goal Agent conversation is unavailable");
    return access == Visibility.READABLE;
  }

  /** Validate a bounded follow selection before replacing any existing subscriptions. */
  void requireLogs(String account, List<String> conversations);

  /**
   * Check current exact ownership, retained input visibility and disclosed Relay labels in a batch.
   */
  void requireDisclosure(String account, String project, List<Disclosure> goals);
}
