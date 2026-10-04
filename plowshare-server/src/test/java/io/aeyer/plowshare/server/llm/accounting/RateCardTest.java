package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.accounting.CostResult.Kind;
import io.aeyer.plowshare.server.llm.accounting.CostResult.Reason;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.CacheRelation;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.Source;
import io.aeyer.plowshare.server.llm.dispatch.Lane;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RateCardTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void cached_input_is_subtracted_once_and_reasoning_is_already_in_output() throws Exception {
    RateCard price = price(new RateCard.Rates(decimal("1"), decimal("4"), decimal("0.25"), null));
    UsageObservation usage =
        UsageNormalizer.openAi(
            mapper.readTree(
                """
                {"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,
                 "prompt_tokens_details":{"cached_tokens":80},
                 "completion_tokens_details":{"reasoning_tokens":12}}
                """));
    CostResult cost = price.quote(usage, Lane.CHAT);
    assertEquals(Kind.ESTIMATED, cost.kind());
    assertAmount("0.000120", cost);
    assertEquals("USD", cost.currency());
    assertEquals(price.version(), cost.priceVersion());
    assertTrue(cost.reasons().isEmpty());
  }

  @Test
  void embeddings_need_no_output_price_or_output_zero() {
    RateCard price = price(new RateCard.Rates(decimal("1.25"), null, null, null));
    CostResult cost = price.quote(TokenUsage.of(7, null, 7).observation(), Lane.EMBEDDING);
    assertEquals(Kind.ESTIMATED, cost.kind());
    assertAmount("0.00000875", cost);
    assertTrue(cost.amountDecimal().startsWith("0.00000875"));
  }

  @Test
  void flat_input_pricing_does_not_require_unknown_cache_splits() {
    RateCard price = price(new RateCard.Rates(decimal("1"), decimal("4"), null, null));
    CostResult cost = price.quote(TokenUsage.of(100, 20, 120).observation(), Lane.CHAT);
    assertEquals(Kind.ESTIMATED, cost.kind());
    assertAmount("0.000180", cost);
  }

  @Test
  void a_discounted_price_requires_the_measured_split_and_known_subset_relationship()
      throws Exception {
    RateCard price = price(new RateCard.Rates(decimal("1"), decimal("4"), decimal("0.25"), null));
    UsageObservation missing =
        UsageNormalizer.openAi(
            mapper.readTree(
                """
                {"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}
                """));
    CostResult cost = price.quote(missing, Lane.CHAT);
    assertEquals(Kind.PARTIAL, cost.kind());
    assertEquals(List.of(Reason.MISSING_CACHE_READ), cost.reasons());
    assertAmount("0.000080", cost);
    CostResult unknownRelation = price.quote(TokenUsage.of(100, 20, 120).observation(), Lane.CHAT);
    assertEquals(List.of(Reason.UNKNOWN_CACHE_RELATION), unknownRelation.reasons());
  }

  @Test
  void disjoint_cache_write_read_and_uncached_input_are_priced_separately() {
    UsageObservation usage =
        new UsageObservation(
            100L,
            20L,
            120L,
            30L,
            10L,
            12L,
            Source.PROVIDER,
            CacheRelation.DISJOINT_INPUT_SUBSETS,
            UsageObservation.VERSION,
            Map.of(),
            List.of());
    RateCard price =
        price(new RateCard.Rates(decimal("1"), decimal("4"), decimal("0.25"), decimal("1.5")));
    assertAmount("0.0001625", price.quote(usage, Lane.CHAT));
    UsageObservation unknownWrite =
        new UsageObservation(
            100L,
            20L,
            120L,
            30L,
            null,
            null,
            Source.PROVIDER,
            CacheRelation.DISJOINT_INPUT_SUBSETS,
            UsageObservation.VERSION,
            Map.of(),
            List.of());
    assertEquals(
        List.of(Reason.MISSING_CACHE_WRITE), price.quote(unknownWrite, Lane.CHAT).reasons());
  }

  @Test
  void missing_prices_usage_and_output_rates_cannot_be_complete_free_calls() {
    assertEquals(Kind.UNKNOWN, CostResult.unpriced().kind());
    assertNull(CostResult.unpriced().amount());
    RateCard price = price(new RateCard.Rates(decimal("1"), null, null, null));
    CostResult noUsage = price.quote(UsageObservation.UNKNOWN, Lane.CHAT);
    assertEquals(Kind.UNKNOWN, noUsage.kind());
    assertNull(noUsage.amount());
    CostResult subtotal = price.quote(TokenUsage.of(100, 20, 120).observation(), Lane.CHAT);
    assertEquals(Kind.PARTIAL, subtotal.kind());
    assertEquals(List.of(Reason.MISSING_OUTPUT_RATE), subtotal.reasons());
    assertAmount("0.000100", subtotal);
    CostResult inputOnly = price.quote(TokenUsage.of(100, null, 100).observation(), Lane.CHAT);
    assertEquals(List.of(Reason.MISSING_OUTPUT), inputOnly.reasons());
    assertEquals(Kind.PARTIAL, inputOnly.kind());
  }

  @Test
  void invalid_or_estimated_quantities_are_not_booked_as_provider_usage() {
    RateCard price = price(new RateCard.Rates(decimal("1"), decimal("4"), null, null));
    CostResult invalid = price.quote(TokenUsage.of(100, 20, 999).observation(), Lane.CHAT);
    assertEquals(Kind.UNKNOWN, invalid.kind());
    assertEquals(List.of(Reason.INVALID_USAGE), invalid.reasons());
    UsageObservation preflight =
        new UsageObservation(
            100L,
            null,
            null,
            null,
            null,
            null,
            Source.TOKENIZER,
            CacheRelation.UNSPECIFIED,
            UsageObservation.VERSION,
            Map.of(),
            List.of());
    assertEquals(
        List.of(Reason.NO_PROVIDER_USAGE), price.quote(preflight, Lane.EMBEDDING).reasons());
  }

  @Test
  void configured_zero_and_included_modes_explain_zero_even_when_usage_is_missing() {
    for (RateCard.Mode mode : List.of(RateCard.Mode.ZERO_RATE, RateCard.Mode.INCLUDED)) {
      RateCard card =
          new RateCard(
              "v1", "local", "model", mode, "USD", null, null, null, List.of(), null, "operator");
      CostResult cost = card.quote(UsageObservation.UNKNOWN, Lane.CHAT);
      assertEquals(mode == RateCard.Mode.ZERO_RATE ? Kind.ZERO_RATE : Kind.INCLUDED, cost.kind());
      assertAmount("0", cost);
      assertTrue(cost.reasons().isEmpty());
    }
  }

  @Test
  void tiers_use_this_attempts_input_and_replace_the_entire_rate_set() {
    var tiers = new ArrayList<RateCard.Tier>();
    tiers.add(new RateCard.Tier(100L, new RateCard.Rates(decimal("2"), decimal("8"), null, null)));
    RateCard card =
        new RateCard(
            "v1",
            "local",
            "model",
            RateCard.Mode.TOKEN,
            "USD",
            null,
            null,
            new RateCard.Rates(decimal("1"), decimal("4"), null, null),
            tiers,
            null,
            "operator");
    tiers.clear();
    assertAmount("0.000139", card.quote(TokenUsage.of(99, 10, 109).observation(), Lane.CHAT));
    assertAmount("0.000280", card.quote(TokenUsage.of(100, 10, 110).observation(), Lane.CHAT));
    CostResult unknownTier = card.quote(TokenUsage.of(null, 10, null).observation(), Lane.CHAT);
    assertEquals(Kind.UNKNOWN, unknownTier.kind());
    assertEquals(List.of(Reason.TIER_INPUT_UNKNOWN), unknownTier.reasons());
    assertThrows(UnsupportedOperationException.class, () -> card.tiers().clear());
  }

  @Test
  void per_attempt_fee_is_known_even_if_token_cost_is_unknown() {
    RateCard card =
        new RateCard(
            "v1",
            "local",
            "model",
            RateCard.Mode.TOKEN,
            "USD",
            null,
            null,
            new RateCard.Rates(decimal("1"), decimal("4"), null, null),
            List.of(),
            decimal("0.01"),
            "operator");
    assertAmount("0.01018", card.quote(TokenUsage.of(100, 20, 120).observation(), Lane.CHAT));
    CostResult partial = card.quote(UsageObservation.UNKNOWN, Lane.CHAT);
    assertEquals(Kind.PARTIAL, partial.kind());
    assertAmount("0.01", partial);
    assertEquals(List.of(Reason.NO_PROVIDER_USAGE), partial.reasons());
  }

  @Test
  void verified_reported_charge_replaces_estimates_and_keeps_its_currency() {
    RateCard card = price(new RateCard.Rates(decimal("1"), decimal("4"), null, null));
    CostResult cost =
        card.quote(
            TokenUsage.of(100, 20, 120).observation(),
            Lane.CHAT,
            new CostResult.ReportedCharge(decimal("0.2"), "AUD"));
    assertEquals(Kind.REPORTED, cost.kind());
    assertEquals("AUD", cost.currency());
    assertAmount("0.2", cost);
  }

  @Test
  void arithmetic_handles_quantities_beyond_javascripts_safe_integer_without_rounding() {
    RateCard card = price(new RateCard.Rates(decimal("0.0000001"), null, null, null));
    UsageObservation usage =
        new UsageObservation(
            9_007_199_254_740_993L,
            null,
            null,
            null,
            null,
            null,
            Source.PROVIDER,
            CacheRelation.UNSPECIFIED,
            UsageObservation.VERSION,
            Map.of(),
            List.of());
    CostResult cost = card.quote(usage, Lane.EMBEDDING);
    assertAmount("900.7199254740993", cost);
    assertEquals("900.7199254740993", cost.amountDecimal());
  }

  @Test
  void versions_hash_semantic_decimal_values_and_change_with_price_or_route() {
    RateCard card = price(new RateCard.Rates(decimal("1"), decimal("4"), null, null));
    assertEquals(
        card.version(),
        price(new RateCard.Rates(decimal("1.0"), decimal("4.00"), null, null)).version());
    assertNotEquals(
        card.version(),
        price(new RateCard.Rates(decimal("2"), decimal("4"), null, null)).version());
    RateCard differentRoute =
        new RateCard(
            "v1",
            "other",
            "model",
            RateCard.Mode.TOKEN,
            "USD",
            null,
            null,
            card.rates(),
            List.of(),
            null,
            "operator");
    assertNotEquals(card.version(), differentRoute.version());
    assertEquals(64, card.version().length());
  }

  @Test
  void invalid_rates_currencies_tiers_and_contradictory_modes_are_refused() {
    assertThrows(
        IllegalArgumentException.class, () -> new RateCard.Rates(decimal("-1"), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RateCard(
                "v1",
                "route",
                "model",
                RateCard.Mode.TOKEN,
                "usd",
                null,
                null,
                null,
                List.of(),
                null,
                "operator"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RateCard(
                "v1",
                "route",
                "model",
                RateCard.Mode.TOKEN,
                "ZZZ",
                null,
                null,
                null,
                List.of(),
                null,
                "operator"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RateCard(
                "v1",
                "route",
                "model",
                RateCard.Mode.ZERO_RATE,
                "USD",
                null,
                null,
                new RateCard.Rates(decimal("1"), null, null, null),
                List.of(),
                null,
                "operator"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RateCard(
                "v1",
                "route",
                "model",
                RateCard.Mode.TOKEN,
                "USD",
                null,
                null,
                null,
                List.of(
                    new RateCard.Tier(100, new RateCard.Rates(decimal("1"), null, null, null)),
                    new RateCard.Tier(50, new RateCard.Rates(decimal("2"), null, null, null))),
                null,
                "operator"));
  }

  private static RateCard price(RateCard.Rates rates) {
    return new RateCard(
        "v1",
        "local",
        "model",
        RateCard.Mode.TOKEN,
        "USD",
        null,
        null,
        rates,
        List.of(),
        null,
        "operator");
  }

  @Test
  void cost_labels_cannot_claim_complete_unknown_or_nonzero_included_spend() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CostResult(
                Kind.UNKNOWN, BigDecimal.ZERO, "USD", null, List.of(Reason.MISSING_PRICE)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CostResult(Kind.ZERO_RATE, BigDecimal.ONE, "USD", "version", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CostResult(Kind.INCLUDED, BigDecimal.ONE, "USD", "version", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CostResult(Kind.PARTIAL, BigDecimal.ZERO, "USD", "version", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CostResult(Kind.UNKNOWN, null, "usd", null, List.of(Reason.MISSING_PRICE)));
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  private static void assertAmount(String expected, CostResult actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual.amount()));
  }
}
