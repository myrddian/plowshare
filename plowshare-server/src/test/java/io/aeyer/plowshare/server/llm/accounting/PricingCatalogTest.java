package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.LlmConfig;
import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.PriceProperties;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PricingCatalogTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    @Test
    void exact_route_and_wire_model_are_required_and_family_mapping_does_not_price_an_alias() {
        LlmProperties properties = properties();
        properties.getPools().getFirst().setModelFamilies(Map.of("production-chat", "gpt-family"));
        PriceProperties price = price("local", "production-chat", "1");
        properties.setPricing(Map.of("contract", price));
        PricingCatalog catalog = PricingCatalog.from(properties);
        assertTrue(catalog.select("local", "production-chat", NOW).isPresent());
        assertTrue(catalog.select("other", "production-chat", NOW).isEmpty());
        assertTrue(catalog.select("local", "gpt-family", NOW).isEmpty());
        assertTrue(catalog.select("local", "production-chat-v2", NOW).isEmpty());
        assertEquals("gpt-family", catalog.routeFor("pool").modelFamilies().get("production-chat"));
        assertEquals("local", catalog.routeFor("pool").billingRoute());
    }

    @Test
    void existing_pool_names_are_route_defaults_without_implicit_public_rates() {
        LlmProperties properties = properties();
        properties.getPools().getFirst().setBillingRoute(null);
        PricingCatalog catalog = PricingCatalog.from(properties);
        assertEquals("pool", catalog.routeFor("pool").billingRoute());
        assertTrue(catalog.select("pool", "production-chat", NOW).isEmpty());
    }

    @Test
    void admission_snapshot_survives_binding_changes() {
        LlmProperties properties = properties();
        PriceProperties price = price("local", "production-chat", "1");
        properties.setPricing(Map.of("contract", price));
        PricingCatalog catalog = PricingCatalog.from(properties);
        RateCard admitted = catalog.select("local", "production-chat", NOW).orElseThrow();
        price.setRatesPerMillion(Map.of("input", new BigDecimal("99")));
        properties.getPools().getFirst().setBillingRoute("new-route");
        properties.setPricing(Map.of());
        assertEquals(new BigDecimal("1"), admitted.rates().input());
        assertEquals(admitted, catalog.select("local", "production-chat", NOW).orElseThrow());
        assertEquals("local", catalog.routeFor("pool").billingRoute());
    }

    @Test
    void validity_is_half_open_and_overlapping_versions_are_refused() {
        RateCard before = card("v1", "route", null, NOW);
        RateCard after = card("v2", "route", NOW, null);
        var cards = new ArrayList<>(List.of(before, after));
        PricingCatalog catalog = new PricingCatalog(cards);
        cards.clear();
        assertEquals(before, catalog.select("route", "model", NOW.minusNanos(1)).orElseThrow());
        assertEquals(after, catalog.select("route", "model", NOW).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> new PricingCatalog(List.of(before, card("v3", "route", NOW.minusSeconds(1), null))));
        // The same model on another billing route is a separate contract.
        RateCard other = card("v3", "other", null, null);
        PricingCatalog independent = new PricingCatalog(List.of(before, other));
        assertEquals(other, independent.select("other", "model", NOW).orElseThrow());
    }

    @Test
    void configuration_rejects_missing_routes_unserved_models_and_unknown_rate_categories() {
        for (PriceProperties invalid : List.of(price("typo-route", "production-chat", "1"),
                price("local", "typo-model", "1"), price("local", "production-chat", "-1"))) {
            LlmProperties properties = properties();
            properties.setPricing(Map.of("bad-contract", invalid));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> PricingCatalog.from(properties));
            assertTrue(failure.getMessage().contains("plowshare.llm.pricing.bad-contract"));
        }
        LlmProperties properties = properties();
        PriceProperties unknown = price("local", "production-chat", "1");
        unknown.setRatesPerMillion(Map.of("imput", BigDecimal.ONE));
        properties.setPricing(Map.of("typo", unknown));
        assertThrows(IllegalStateException.class, () -> PricingCatalog.from(properties));
        properties.setPricing(Map.of());
        properties.getPools().getFirst().setModelFamilies(Map.of("unserved", "family"));
        assertThrows(IllegalStateException.class, () -> PricingCatalog.from(properties));
        properties.getPools().getFirst().setModelFamilies(Map.of());
        properties.getPools().getFirst().setBillingRoute(" ");
        assertThrows(IllegalStateException.class, () -> PricingCatalog.from(properties));
    }

    @Test
    void top_level_prices_bind_with_decimal_rates_cache_categories_and_tiers() {
        runner().withPropertyValues(values(base(), new String[] {
            "plowshare.llm.pricing.contract.revision=v1",
            "plowshare.llm.pricing.contract.billing-route=local",
            "plowshare.llm.pricing.contract.model=production-chat",
            "plowshare.llm.pricing.contract.mode=token",
            "plowshare.llm.pricing.contract.currency=USD",
            "plowshare.llm.pricing.contract.rates-per-million.input=1.25",
            "plowshare.llm.pricing.contract.rates-per-million.cache-read=0.25",
            "plowshare.llm.pricing.contract.tiers[0].from-input-tokens=100",
            "plowshare.llm.pricing.contract.tiers[0].rates-per-million.input=2.50",
            "plowshare.llm.pools[0].model-families.production-chat=family"
        })).run(context -> {
            assertTrue(context.getStartupFailure() == null, () -> String.valueOf(context.getStartupFailure()));
            PricingCatalog catalog = PricingCatalog.from(context.getBean(LlmProperties.class));
            RateCard selected = catalog.select("local", "production-chat", NOW).orElseThrow();
            assertEquals(new BigDecimal("1.25"), selected.rates().input());
            assertEquals(new BigDecimal("0.25"), selected.rates().cacheRead());
            assertEquals(100L, selected.tiers().getFirst().fromInputTokens());
            assertEquals(new BigDecimal("2.50"), selected.tiers().getFirst().rates().input());
        });
    }

    @Test
    void negative_configured_rates_stop_server_startup() {
        runner().withPropertyValues(values(base(), new String[] {
            "plowshare.llm.pricing.contract.revision=v1",
            "plowshare.llm.pricing.contract.billing-route=local",
            "plowshare.llm.pricing.contract.model=production-chat",
            "plowshare.llm.pricing.contract.currency=USD",
            "plowshare.llm.pricing.contract.rates-per-million.input=-1"
        })).run(context -> assertNotNull(context.getStartupFailure()));
    }

    private static LlmProperties properties() {
        var pool = new PoolProperties();
        pool.setName("pool");
        pool.setBillingRoute("local");
        pool.setModels(List.of("production-chat"));
        var properties = new LlmProperties();
        properties.setPools(List.of(pool));
        return properties;
    }

    private static PriceProperties price(String route, String model, String input) {
        var price = new PriceProperties();
        price.setRevision("v1");
        price.setBillingRoute(route);
        price.setModel(model);
        price.setCurrency("USD");
        price.setRatesPerMillion(Map.of("input", new BigDecimal(input)));
        return price;
    }

    private static RateCard card(String revision, String route, Instant from, Instant until) {
        return new RateCard(revision, route, "model", RateCard.Mode.TOKEN, "USD", from, until,
                new RateCard.Rates(BigDecimal.ONE, BigDecimal.ONE, null, null), List.of(), null, "operator");
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(LlmConfig.class).withBean(ObjectMapper.class).withBean(NoOpTokenLedger.class);
    }

    private static String[] base() {
        return new String[] {
            "plowshare.llm.embedding-model=production-chat",
            "plowshare.llm.embedding-dim=768",
            "plowshare.llm.embedding-max-input-tokens=1536",
            "plowshare.llm.default-context-length=64000",
            "plowshare.llm.pools[0].name=pool",
            "plowshare.llm.pools[0].billing-route=local",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
            "plowshare.llm.pools[0].models[0]=production-chat"
        };
    }

    private static String[] values(String[] base, String[] extra) {
        return java.util.stream.Stream.concat(java.util.Arrays.stream(base), java.util.Arrays.stream(extra))
                .toArray(String[]::new);
    }
}
