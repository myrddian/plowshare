package io.aeyer.plowshare.server.llm.accounting;

import java.time.Instant;
import java.util.Optional;

/** Durable active overrides. Writes and audit records participate in the caller's transaction. */
public interface PricingRepository {
  record PriceOverride(RateCard card, Instant updatedAt) {}

  Optional<PriceOverride> find(String route, String model);

  /** Serialize pricing changes with account-role changes until transaction completion. */
  void lockChanges();

  /** Replace the current override and append operator history atomically. */
  void save(RateCard card, String actor);
}
