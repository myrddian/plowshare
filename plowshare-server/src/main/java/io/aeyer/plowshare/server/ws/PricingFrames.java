package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.llm.accounting.PricingAdministration;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Pricing administration is operator-only and uses the authenticated event socket. */
@Component
public final class PricingFrames implements FrameArea {
  private final PricingAdministration pricing;

  public PricingFrames(PricingAdministration pricing) {
    this.pricing = pricing;
  }

  private static PricingAdministration.Change change(Map<String, Object> payload) {
    for (String key :
        java.util.List.of(
            "billingRoute",
            "model",
            "expectedVersion",
            "mode",
            "currency",
            "requestFee",
            "source")) {
      if (payload.containsKey(key) && !(payload.get(key) instanceof String))
        throw new io.aeyer.plowshare.server.faults.CallerFault(key + " must be text");
    }
    if (payload.containsKey("rates")) rates(payload.get("rates"));
    if (payload.containsKey("tiers")) {
      if (!(payload.get("tiers") instanceof java.util.List<?> tiers) || tiers.size() > 100)
        throw new io.aeyer.plowshare.server.faults.CallerFault(
            "tiers must be an array with at most 100 entries");
      for (Object value : tiers) {
        if (!(value instanceof Map<?, ?> tier)
            || !(tier.get("fromInputTokens") instanceof Number threshold)
            || threshold.doubleValue() != threshold.longValue()
            || threshold.longValue() < 0
            || threshold.doubleValue() > 9007199254740991L)
          throw new io.aeyer.plowshare.server.faults.CallerFault(
              "Tier thresholds must be nonnegative safe integers");
        rates(tier.get("rates"));
      }
    }
    return Payloads.as(payload, PricingAdministration.Change.class, FrameTypes.ADMIN_PRICING_SET);
  }

  private static void rates(Object value) {
    if (!(value instanceof Map<?, ?> fields)
        || fields.keySet().stream()
            .anyMatch(
                key ->
                    !java.util.Set.of("input", "output", "cacheRead", "cacheWrite").contains(key))
        || fields.values().stream().anyMatch(amount -> !(amount instanceof String)))
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Rates need decimal strings for input, output, cacheRead or cacheWrite");
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.ADMIN_PRICING_LIST,
        (p, a) -> Outcome.ok(pricing.list(a.requireHandle(FrameTypes.ADMIN_PRICING_LIST))),
        FrameTypes.ADMIN_PRICING_SET,
        (p, a) ->
            Outcome.ok(pricing.set(a.requireHandle(FrameTypes.ADMIN_PRICING_SET), change(p))));
  }
}
