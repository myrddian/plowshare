package io.aeyer.plowshare.server.llm.accounting;

import java.time.Instant;
import java.util.Optional;

/** Supplies immutable admission prices; already admitted calls retain their selected card. */
public interface InferencePrices {
  Optional<RateCard> select(String billingRoute, String model, Instant admission);

  PricingCatalog.Route routeFor(String pool);
}
