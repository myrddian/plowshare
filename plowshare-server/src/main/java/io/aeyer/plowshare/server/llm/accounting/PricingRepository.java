package io.aeyer.plowshare.server.llm.accounting;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Durable active overrides. Writes and audit records participate in the caller's transaction. */
public interface PricingRepository {
  record PriceOverride(RateCard card, Instant updatedAt) {}

  record Key(String route, String model) {
    public Key {
      Objects.requireNonNull(route);
      Objects.requireNonNull(model);
      if (route.isBlank() || model.isBlank())
        throw new IllegalArgumentException("Exact pricing identity required");
    }
  }

  Optional<PriceOverride> find(String route, String model);

  /** Exact requested overrides, read in bounded groups; absence preserves configuration pricing. */
  Map<Key, PriceOverride> findAll(List<Key> keys);

  /** Serialize pricing changes with account-role changes until transaction completion. */
  void lockChanges();

  /** Replace the current override and append operator history atomically. */
  PriceOverride save(RateCard card, String actor);
}
