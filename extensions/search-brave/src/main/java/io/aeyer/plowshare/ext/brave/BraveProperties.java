package io.aeyer.plowshare.ext.brave;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The facts an operator states about this process's own connection to Brave's API, under {@code
 * plowshare.ext.brave.*}.
 *
 * <p><b>There is deliberately no {@code apiKey} field here.</b> Brave's vendor credential lives in
 * this process's own environment — {@code SEARCH_BRAVE_API_KEY}, read directly in {@link
 * BraveApplication} — and never as a value bound through this record or written into this module's
 * {@code application.properties}. That is not an implementation detail; it is the point of the
 * whole out-of-process arrangement (spec §3): Plowshare holds a {@code baseUrl} it registered this
 * process under, and the credential that actually pays Brave never has to enter Plowshare's
 * configuration, its process memory, or a token this repository's operator tooling has to rotate. A
 * {@code plowshare.ext.brave.api-key} property here would recreate exactly the bearer-token problem
 * the secrets work already lives with — see {@code secrets-outside-the-fence} — one layer closer to
 * a third party's bill.
 *
 * @param baseUrl Brave's own API origin. Configurable, rather than a hard-coded {@code
 *     https://api.search.brave.com}, only so a test or a proxy can point this process elsewhere
 *     without a code change — see {@code BraveControllerTest}.
 * @param timeoutMs the budget for one call, applied per request through {@link
 *     okhttp3.Call#timeout()}, on {@code RemoteSearchProvider}'s own reasoning.
 * @param maxResults the ceiling this operator's Brave plan is willing to pay for per answer. {@link
 *     BraveController#capabilities()} reports this same value as {@link
 *     io.aeyer.plowshare.protocol.search.ProviderFacts#maxResults()}, on {@code
 *     SearxngProperties}'s identical argument for keeping the two in one place.
 */
@ConfigurationProperties(prefix = "plowshare.ext.brave")
public record BraveProperties(String baseUrl, long timeoutMs, int maxResults) {}
