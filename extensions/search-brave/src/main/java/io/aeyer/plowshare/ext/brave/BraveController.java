package io.aeyer.plowshare.ext.brave;

import io.aeyer.plowshare.protocol.search.CostClass;
import io.aeyer.plowshare.protocol.search.Hit;
import io.aeyer.plowshare.protocol.search.NetworkTier;
import io.aeyer.plowshare.protocol.search.ProviderFacts;
import io.aeyer.plowshare.protocol.search.ProviderHealth;
import io.aeyer.plowshare.protocol.search.SearchAnswer;
import io.aeyer.plowshare.protocol.search.SearchAsk;
import io.aeyer.plowshare.protocol.search.Verb;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Brave provider's whole wire surface, on {@code SearxngController}'s shape: {@code GET
 * /health}, {@code GET /v1/search/capabilities} and {@code POST /v1/search}, plowshare-protocol's
 * search contract.
 *
 * <h2>What is different from {@code SearxngController}, and why</h2>
 *
 * <ul>
 *   <li>{@link CostClass#METERED}, not {@link CostClass#FREE} — a call to Brave's API is billed
 *       against the operator's plan; a call to a self-hosted SearXNG is not.
 *   <li>{@link NetworkTier#PUBLIC_INTERNET}, not {@code INTERNAL_NETWORK} — Brave is a vendor's
 *       public API, reachable from anywhere, where SearXNG is presumed to live on the operator's
 *       own network.
 *   <li>{@code domainExclusion} is {@code false} — deliberately, and it is not something to "fix"
 *       by inventing an exclusion mechanism Brave's API does not have. See {@link BraveClient}'s
 *       javadoc for what was rejected and why. This {@code false} is exactly what makes Task 5's
 *       conditional-hint test on the Plowshare side meaningful: with both providers declaring
 *       support, nothing would ever exercise the branch that withholds the ignore list from one
 *       that has not.
 * </ul>
 *
 * <p>Everything else — the absent poller on {@code /health}, and why {@code /v1/search} binds a
 * validation-free {@link RawAsk} rather than {@link SearchAsk} directly — is argued in {@code
 * SearxngController}'s class javadoc and applies here unchanged.
 */
@RestController
class BraveController {

  static final String VERSION = "1.0.0";
  private static final String PROVIDER_KEY = "brave";
  private static final int MAX_LOGGED_FAILURE_CHARS = 2_000;
  private static final Logger log = LoggerFactory.getLogger(BraveController.class);

  /**
   * Brave's own documented ceiling on {@code q}, in characters — not a number this class invented.
   * Reported through {@link ProviderFacts#maxQueryLength()} so a caller can trim a query before
   * sending it rather than discovering the limit from a rejected request.
   */
  private static final int MAX_QUERY_LENGTH = 400;

  private final BraveClient client;
  private final BraveProperties properties;

  BraveController(BraveClient client, BraveProperties properties) {
    this.client = client;
    this.properties = properties;
  }

  @GetMapping("/health")
  ProviderHealth health() {
    return ProviderHealth.up(VERSION);
  }

  @GetMapping("/v1/search/capabilities")
  ProviderFacts capabilities() {
    return new ProviderFacts(
        PROVIDER_KEY,
        "Brave Search",
        VERSION,
        "Brave's public Web Search API, billed against the operator's own plan.",
        Set.of(Verb.SEARCH),
        CostClass.METERED,
        NetworkTier.PUBLIC_INTERNET,
        properties.maxResults(),
        MAX_QUERY_LENGTH,
        false);
  }

  /**
   * What {@code POST /v1/search} actually receives — see the class javadoc for why it is not {@link
   * SearchAsk}.
   */
  record RawAsk(String requestId, String query, int max, List<String> ignoredDomains) {}

  @PostMapping("/v1/search")
  SearchAnswer receive(@RequestBody RawAsk raw) {
    SearchAsk ask;
    try {
      ask = new SearchAsk(raw.requestId(), raw.query(), raw.max(), raw.ignoredDomains());
    } catch (IllegalArgumentException e) {
      String requestId = raw.requestId() == null ? "" : raw.requestId();
      String detail = failureDetail(e);
      logFailure(requestId, detail, 0L);
      return SearchAnswer.failed(requestId, PROVIDER_KEY, detail, 0L);
    }
    return search(ask);
  }

  /**
   * The {@code catch} here is deliberately as wide as {@link Exception}, on {@code
   * SearxngController}'s own equivalent method: a refused connection, a timeout, a non-2xx status
   * and a Jackson mapping failure are the same fact from this method's side, and this method must
   * never throw one of them out to its caller.
   */
  SearchAnswer search(SearchAsk ask) {
    long startedAt = System.nanoTime();
    try {
      List<Hit> hits = client.search(ask.query(), ask.max());
      return SearchAnswer.success(ask.requestId(), PROVIDER_KEY, hits, elapsedMs(startedAt));
    } catch (Exception e) {
      String detail = failureDetail(e);
      long elapsedMs = elapsedMs(startedAt);
      logFailure(ask.requestId(), detail, elapsedMs);
      return SearchAnswer.failed(ask.requestId(), PROVIDER_KEY, detail, elapsedMs);
    }
  }

  /**
   * Provider logs never include the query or credential. The request id is enough to join this
   * process's warning to Plowshare's warning for the failed plugin call, while the bounded one-line
   * detail remains useful in a service log and cannot turn an upstream response into arbitrary log
   * volume.
   */
  private static void logFailure(String requestId, String detail, long elapsedMs) {
    log.warn(
        "Search provider failed providerKey={} requestId={} elapsedMs={} detail={}",
        PROVIDER_KEY,
        oneLine(requestId),
        elapsedMs,
        oneLine(detail));
  }

  private static String failureDetail(Exception e) {
    return e.getMessage() == null || e.getMessage().isBlank()
        ? e.getClass().getSimpleName()
        : e.getMessage();
  }

  private static String oneLine(String value) {
    String flattened = value == null ? "" : value.replaceAll("\\s+", " ").trim();
    return flattened.length() <= MAX_LOGGED_FAILURE_CHARS
        ? flattened
        : flattened.substring(0, MAX_LOGGED_FAILURE_CHARS) + "…";
  }

  private static long elapsedMs(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000L;
  }
}
