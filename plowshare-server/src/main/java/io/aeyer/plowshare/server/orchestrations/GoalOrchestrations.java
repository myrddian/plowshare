package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Orchestration-owned starts and observations for one exact retained Goal wait. */
public interface GoalOrchestrations {
  int MAX_INLINE_RESULT = 16000;

  record Caller(String conversation, AgentDefinition agent, Admission authority) {
    public Caller {
      Objects.requireNonNull(conversation);
      Objects.requireNonNull(agent);
      Objects.requireNonNull(authority);
    }
  }

  /** Immutable identity retained by the Goal, also usable for cleanup after grant loss. */
  record Owner(String conversation, String agent, String executor, UUID admission) {
    public Owner {
      Objects.requireNonNull(conversation);
      Objects.requireNonNull(agent);
      Objects.requireNonNull(executor);
      Objects.requireNonNull(admission);
    }
  }

  record Definition(String name, String hash) {}

  record Request(UUID invocation, String name, String request, String expectedHash) {
    public Request {
      Objects.requireNonNull(invocation);
      requireName(name);
      if (request == null
          || request.isBlank()
          || request.length() > 16000
          || request.indexOf('\0') >= 0)
        throw new CallerFault("A bounded orchestration request is required");
      Objects.requireNonNull(expectedHash);
    }
  }

  /**
   * A run snapshot. The state is the owner's wire name and endedAt identifies its terminal attempt.
   * The result carries at most one code point past the inline limit, so an oversized result stays
   * in its authorized source log and the caller can direct the reader there.
   */
  record Run(String id, String conversation, String state, Instant endedAt, String result) {}

  /** Only definitions in both the admitted agent and its current scoped grants are eligible. */
  Optional<Definition> granted(Caller caller, String name);

  /**
   * Start the granted definition under the caller's admission and original invocation key. The
   * Goal's fence locks and checks its armed wait inside the start transaction, before the owner
   * accepts the receipt. It runs no external work.
   */
  String start(Caller caller, Request request, Runnable requireArmedWait);

  /** Read the exact start receipt and current outcome under live grants and log disclosure. */
  Optional<Run> inspect(Caller caller, UUID invocation, String name, String hash);

  /** Resolve current definition grants outside any Goal SQL transaction. */
  boolean stillGranted(Caller caller, String name, String hash);

  /** Recheck the exact terminal attempt and disclosure without rereading its result text. */
  boolean stillTerminal(Caller caller, UUID invocation, String name, String hash, Run expected);

  /** Cleanup of the exact child of an already authorized Goal cancellation. */
  boolean cancel(Owner owner, UUID invocation, String name, String hash);

  private static void requireName(String name) {
    if (name == null
        || name.isBlank()
        || name.length() > 1024
        || !name.equals(name.strip())
        || name.chars().anyMatch(Character::isISOControl))
      throw new CallerFault("An exact orchestration name is required");
  }
}
