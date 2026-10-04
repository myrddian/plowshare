package io.aeyer.plowshare.server.search;

import io.aeyer.plowshare.protocol.search.ProviderFacts;
import java.time.Instant;

/**
 * One registered provider: what it is, where it is, and what has happened to it.
 *
 * <p>{@code facts} is exactly what the provider's own {@code /v1/search/capabilities} answered —
 * see {@link ProviderFacts}'s own javadoc for why it carries no rung, no timeout and no enabled
 * flag. This record adds the three things a capabilities response cannot: where the operator
 * pointed it ({@code baseUrl}), and what has happened to it since ({@code lastHealthAt}, {@code
 * lastHealthStatus}, {@code consecutiveFailures}).
 *
 * @param providerKey the key this row is registered under, and the key its own {@code facts}
 *     carries — the two are never allowed to disagree, because there is nowhere in {@code
 *     search_providers} for a second key to live
 * @param baseUrl where the adapter sends a request, with no trailing slash — {@code
 *     search_providers_a_base_url_has_no_trailing_slash} is what guarantees this rather than a
 *     runtime strip
 * @param facts what the provider states about itself, as fetched at registration
 * @param lastHealthAt when a search through this provider last completed, or {@code null} for a row
 *     that has been registered but never searched
 * @param lastHealthStatus {@link io.aeyer.plowshare.protocol.search.AnswerStatus}'s name, as the
 *     last outcome recorded it, or {@code null} to match {@code lastHealthAt}
 * @param consecutiveFailures how many terminal searches through this provider have failed since the
 *     last success or the last registration, whichever is more recent
 */
public record Registration(
    String providerKey,
    String baseUrl,
    ProviderFacts facts,
    Instant lastHealthAt,
    String lastHealthStatus,
    int consecutiveFailures) {}
