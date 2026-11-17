package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.embedding.SpendingPolicy;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable, account-local external conversation names. The caller must authenticate and authorize
 * the requested project before calling and authorize the resolved target before disclosure or work.
 * Keys and stored relationships confer no permissions. There is deliberately no bind/reset API.
 */
public interface ConversationAliasRepository {
  /** Immutable resolved identity; source labels are local to the authenticated account. */
  record Key(long projectId, AccountId account, String source, String externalConversation) {
    public Key {
      if (projectId <= 0) throw new IllegalArgumentException("An existing project is required");
      Objects.requireNonNull(account);
      if (source == null
          || source.length() > 128
          || !source.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*"))
        throw new IllegalArgumentException("Invalid external conversation source");
      if (externalConversation == null || !externalConversation.matches("[\\x21-\\x7e]{1,256}"))
        throw new IllegalArgumentException("Invalid external conversation identifier");
    }
  }

  /**
   * Immutable creation-only choices, never a retained mutable allowance. Reusing these choices for
   * another key creates a distinct budget identity; an existing mapping ignores them entirely.
   * These are resolved ordinary policy choices: zero retains the operator's disabled-inference
   * default. Explicit wire limits must still pass RequestedBudget's positive-number validation
   * before this internal contract is constructed.
   */
  record Opening(Integer maxModelCalls, Integer maxTurns, boolean noBudget, boolean noTurnCap) {
    public Opening {
      if (noBudget == (maxModelCalls != null)
          || maxModelCalls != null && maxModelCalls < 0
          || maxTurns != null && (maxTurns < 1 || noTurnCap))
        throw new IllegalArgumentException("Incompatible conversation creation choices");
    }

    public Opening(Budget budget, TurnCap turnCap) {
      this(
          freshLimit(budget),
          turnCap != null && turnCap.capped() ? turnCap.turns() : null,
          !budget.capped(),
          turnCap != null && !turnCap.capped());
    }

    public Budget budget() {
      return SpendingPolicy.budget(maxModelCalls);
    }

    public TurnCap turnCap() {
      return noTurnCap ? TurnCap.none() : maxTurns == null ? null : TurnCap.of(maxTurns);
    }

    private static Integer freshLimit(Budget budget) {
      Objects.requireNonNull(budget);
      if (budget.durable() || budget.spent() != 0)
        throw new IllegalArgumentException("Creation choices cannot copy a retained allowance");
      return budget.capped() ? budget.limit() : null;
    }
  }

  /** The committed snapshot is reused by the facade; only created roots receive log.open. */
  record Opened(ConversationRecord conversation, boolean created) {
    public Opened {
      Objects.requireNonNull(conversation);
    }
  }

  /**
   * Atomic get-or-open. Alias, ordinary TURN root and allowance commit together. It must return
   * outside its transaction, before any hook/network work. Never retries a possibly committed
   * write.
   */
  Opened open(Key key, Opening opening);

  /** Resolve only; a miss has no creation side effect. Lifecycle is retained, never revived. */
  Optional<ConversationRecord> find(Key key);
}
