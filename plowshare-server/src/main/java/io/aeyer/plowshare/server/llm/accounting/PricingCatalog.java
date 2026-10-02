package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.PoolProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Exact route/model matching with no default vendor pricing or deployment-name inference. */
public final class PricingCatalog {

    private final Map<Key, List<RateCard>> versions;
    private final Map<String, Route> routes;

    public PricingCatalog(List<RateCard> cards) {
        this(cards, Map.of());
    }

    private PricingCatalog(List<RateCard> cards, Map<String, Route> routes) {
        var grouped = new HashMap<Key, List<RateCard>>();
        for (RateCard card : List.copyOf(cards)) {
            Key key = new Key(card.billingRoute(), card.model());
            List<RateCard> previous = grouped.computeIfAbsent(key, ignored -> new ArrayList<>());
            for (RateCard existing : previous) {
                if (overlaps(existing, card)) {
                    throw new IllegalArgumentException("conflicting price intervals for billing route '"
                            + key.billingRoute() + "' and model '" + key.model() + "'");
                }
            }
            previous.add(card);
        }
        var frozen = new HashMap<Key, List<RateCard>>();
        grouped.forEach((key, cardsForKey) -> frozen.put(key, List.copyOf(cardsForKey)));
        this.versions = Map.copyOf(frozen);
        this.routes = Map.copyOf(routes);
    }

    /** Validate binding before transports are created; copy every mutable configuration value. */
    public static PricingCatalog from(LlmProperties properties) {
        var routes = new HashMap<String, Route>();
        var served = new HashMap<String, List<String>>();
        List<PoolProperties> pools = properties.getPools();
        for (int index = 0; index < pools.size(); index++) {
            PoolProperties pool = pools.get(index);
            String key = "plowshare.llm.pools[" + index + "]";
            try {
                String route = pool.billingRoute();
                UsageLineage.requireId(route);
                Map<String, String> families = pool.getModelFamilies();
                for (var entry : families.entrySet()) {
                    if (!pool.getModels().contains(entry.getKey())) {
                        throw new IllegalArgumentException("model-families names an unserved model");
                    }
                    UsageLineage.requireId(entry.getValue());
                }
                Route snapshot = new Route(route, families);
                if (routes.put(pool.getName(), snapshot) != null) {
                    throw new IllegalArgumentException("pool names must be unique");
                }
                served.computeIfAbsent(route, ignored -> new ArrayList<>()).addAll(pool.getModels());
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new IllegalStateException(key + " has invalid billing-route or model-families: "
                        + invalid.getMessage(), invalid);
            }
        }
        var cards = new ArrayList<RateCard>();
        properties.getPricing().forEach((name, bound) -> {
            try {
                UsageLineage.requireId(name);
                RateCard card = bound.snapshot();
                if (!served.getOrDefault(card.billingRoute(), List.of()).contains(card.model())) {
                    throw new IllegalArgumentException("price must name a served billing route and exact wire model");
                }
                cards.add(card);
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new IllegalStateException("plowshare.llm.pricing." + name + " is invalid: "
                        + invalid.getMessage(), invalid);
            }
        });
        try {
            return new PricingCatalog(cards, routes);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("plowshare.llm.pricing: " + invalid.getMessage(), invalid);
        }
    }

    /** Capture this immutable card before queueing; retries retain it across configuration changes. */
    public Optional<RateCard> select(String billingRoute, String wireModel, Instant admission) {
        Objects.requireNonNull(admission, "admission");
        return versions.getOrDefault(new Key(billingRoute, wireModel), List.of()).stream()
                .filter(card -> card.activeAt(admission)).findFirst();
    }

    public Route routeFor(String pool) {
        Route route = routes.get(pool);
        if (route == null) {
            throw new IllegalArgumentException("no billing route declared for pool");
        }
        return route;
    }

    private static boolean overlaps(RateCard one, RateCard two) {
        return (one.validUntil() == null || two.validFrom() == null
                || one.validUntil().isAfter(two.validFrom()))
                && (two.validUntil() == null || one.validFrom() == null
                || two.validUntil().isAfter(one.validFrom()));
    }

    private record Key(String billingRoute, String model) { }

    /** Family drives capabilities; pricing still uses the wire model, never this alias. */
    public record Route(String billingRoute, Map<String, String> modelFamilies) {
        public Route {
            UsageLineage.requireId(billingRoute);
            modelFamilies = Map.copyOf(modelFamilies);
        }
    }
}
