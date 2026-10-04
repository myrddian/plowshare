package io.aeyer.plowshare.ext.searxng;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The three facts an operator states about the SearXNG instance this process dials, under {@code
 * plowshare.ext.searxng.*} — nothing more.
 *
 * <p>There is no field here for a vendor credential, because SearXNG is a self-hosted metasearch
 * engine and has none: {@code baseUrl} is itself the whole of what this process needs to reach it.
 * That is the opposite of {@code BraveProperties}, which for the identical reason declines to
 * declare one either — see that class's javadoc for the case where a credential does exist and is
 * kept out of both places anyway.
 *
 * @param baseUrl where the operator's SearXNG instance answers, with no trailing slash assumed one
 *     way or the other — {@link SearxngClient} builds its request URL through {@link
 *     okhttp3.HttpUrl}, which tolerates either.
 * @param timeoutMs the budget for one call to that instance, applied per request through {@link
 *     okhttp3.Call#timeout()} rather than baked into a shared client at construction, on {@code
 *     RemoteSearchProvider}'s own reasoning.
 * @param maxResults the ceiling this operator's deployment imposes on a single answer, independent
 *     of whatever {@code max} a given {@link io.aeyer.plowshare.protocol.search.SearchAsk}
 *     requests. {@link SearxngController#capabilities()} reports this same value as {@link
 *     io.aeyer.plowshare.protocol.search.ProviderFacts#maxResults()}, so the number Plowshare is
 *     told to expect and the number this process actually enforces cannot drift apart into two
 *     configuration surfaces that have to be kept in step by hand.
 */
@ConfigurationProperties(prefix = "plowshare.ext.searxng")
public record SearxngProperties(String baseUrl, long timeoutMs, int maxResults) {}
