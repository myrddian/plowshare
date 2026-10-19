package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.*;
import java.util.*;

/** Existing memory filing, with trusted authority independent of accounting attribution. */
public interface MemoryWrites {
  /** The exact server-selected learning window, not a model-selected source or executor. */
  record Learning(String conversation, List<EntryRecord> entries, int proposalOrdinal) {
    public Learning {
      Objects.requireNonNull(conversation);
      entries = List.copyOf(entries);
      if (entries.isEmpty()
          || entries.size() > 40
          || proposalOrdinal < 0
          || entries.stream().anyMatch(e -> !conversation.equals(e.conversationId())))
        throw new IllegalArgumentException("Exact learning window required");
    }
  }

  /** Alive remains applicable after commit; caller authority applies before content mutation. */
  record Origin(String invocation, Runnable alive, Runnable authority, Learning learning) {
    public Origin {
      Objects.requireNonNull(alive);
      Objects.requireNonNull(authority);
      if (invocation != null
          && (invocation.isBlank()
              || invocation.length() > 1024
              || invocation.codePoints().anyMatch(Character::isISOControl)))
        throw new IllegalArgumentException("Bounded memory write invocation required");
      if (learning != null && invocation == null)
        throw new IllegalArgumentException("Learning filing requires retained identity");
    }

    public static Origin request(Runnable alive) {
      return new Origin(null, alive, () -> {}, null);
    }
  }

  record Request(String account, Home home, MemoryProposal proposal, Origin origin) {
    public Request {
      Objects.requireNonNull(home);
      Objects.requireNonNull(proposal);
      Objects.requireNonNull(origin);
      if (origin.learning() == null && (account == null || account.isBlank()))
        throw new IllegalArgumentException("Authenticated memory writer required");
      if (origin.learning() != null && account != null)
        throw new IllegalArgumentException("Learning uses its own SYSTEM authority");
      // An old conversation's NULL project is unclassified execution history, not proof
      // that its extracted memories belong in deployment-wide Global knowledge.
      if (origin.learning() != null && home.isGlobal())
        throw new IllegalArgumentException(
            "Learning filing requires an explicit project or personal home");
    }
  }

  /** Optional inference was disabled/exhausted; this never indicates valid write authority. */
  final class InferenceUnavailable extends RuntimeException {
    public InferenceUnavailable(String reason) {
      super(reason);
    }
  }

  /** Provider IO is outside content/publication transactions; indexing failure retains content. */
  WriteResult write(Request request);
}
