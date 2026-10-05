package io.aeyer.plowshare.server.orchestrations;

import java.time.Instant;
import java.util.UUID;

/** Atomic, account-owned recovery of failed root runs. Receipts survive later failures. */
public interface OrchestrationRecovery {
  /**
   * Claims a failed root and clears only its terminal fields. A repeated key for the same run
   * returns false, even if it has since failed again; a key bound to another run is refused.
   * Competing keys cannot both claim the same failure. The failureAt timestamp fences a stale claim
   * from a later failed attempt. No remote work occurs in this transaction.
   */
  boolean claim(String id, String account, UUID requestId, Instant failureAt);

  /** Checks a prior receipt without changing the run; rejects keys bound to another run. */
  boolean received(String id, String account, UUID requestId);
}
