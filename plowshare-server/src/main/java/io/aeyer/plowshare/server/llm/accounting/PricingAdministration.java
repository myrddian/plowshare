package io.aeyer.plowshare.server.llm.accounting;

import java.time.Instant;
import java.util.List;

/** Human-admin pricing contract. Rate amounts are decimal strings in currency units per million. */
public interface PricingAdministration {
  public record Rates(String input, String output, String cacheRead, String cacheWrite) {}

  public record Tier(long fromInputTokens, Rates rates) {}

  public record Card(
      String revision,
      RateCard.Mode mode,
      String currency,
      Rates rates,
      List<Tier> tiers,
      String requestFee,
      String source,
      Instant validFrom,
      Instant validUntil) {}

  public record Entry(
      String billingRoute,
      String model,
      List<String> pools,
      String version,
      String origin,
      Card card,
      List<Card> configured,
      Instant updatedAt) {}

  public record Change(
      String billingRoute,
      String model,
      String expectedVersion,
      String mode,
      String currency,
      Rates rates,
      List<Tier> tiers,
      String requestFee,
      String source) {}

  List<Entry> list(String actor);

  /** Reject a stale version; apply only to future admissions, never to booked costs or retries. */
  Entry set(String actor, Change change);
}
