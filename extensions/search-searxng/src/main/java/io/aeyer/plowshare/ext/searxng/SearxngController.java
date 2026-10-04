package io.aeyer.plowshare.ext.searxng;

import io.aeyer.plowshare.protocol.search.CostClass;
import io.aeyer.plowshare.protocol.search.Hit;
import io.aeyer.plowshare.protocol.search.NetworkTier;
import io.aeyer.plowshare.protocol.search.ProviderFacts;
import io.aeyer.plowshare.protocol.search.ProviderHealth;
import io.aeyer.plowshare.protocol.search.SearchAnswer;
import io.aeyer.plowshare.protocol.search.SearchAsk;
import io.aeyer.plowshare.protocol.search.Verb;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SearXNG provider's whole wire surface: {@code GET /health}, {@code GET
 * /v1/search/capabilities} and {@code POST /v1/search}, on {@code plowshare-protocol}'s search
 * contract.
 *
 * <h2>{@code /health} is served and polled by nothing yet</h2>
 *
 * <p>Nothing in Plowshare calls this route today — health in this slice comes from {@code
 * consecutive_failures}, written by {@code RemoteSearchProvider} off the outcome of a real
 * invocation, not from a separate poll. The route exists anyway, for an operator checking this
 * process by hand and for a scheduler a later slice may add, because adding it retroactively would
 * mean every SearXNG process already deployed needing a new build to gain it. That is a decision
 * recorded here rather than a gap: this endpoint answering {@link ProviderHealth#up} is not proof
 * that any caller ever reaches it.
 *
 * <h2>{@code /v1/search} does not bind {@link SearchAsk} directly</h2>
 *
 * <p>{@link SearchAsk}'s canonical constructor refuses a blank {@code query} or {@code requestId}
 * at construction (spec, Task 1). Binding the request body straight to that type would let a blank
 * query throw during Spring's own message conversion, before {@link #search(SearchAsk)} ever ran —
 * Spring turns that into a 400 with no {@link SearchAnswer} in the body at all. {@code
 * RemoteSearchProvider} treats any non-2xx as a generic "remote returned status &lt;code&gt;"
 * failure, discarding whatever message this process tried to give, so a 400 here is strictly worse
 * than a 200 carrying {@link io.aeyer.plowshare.protocol.search.AnswerStatus#FAILED}. {@link
 * #receive} therefore binds a validation-free {@link RawAsk}, attempts the real construction
 * itself, and turns a refusal into a {@link SearchAnswer#failed} with the constructor's own message
 * — the one path that actually reaches the caller.
 */
@RestController
class SearxngController {

  static final String VERSION = "1.0.0";
  private static final String PROVIDER_KEY = "searxng";
  private static final int MAX_QUERY_LENGTH = 512;
  private static final int MAX_LOGGED_FAILURE_CHARS = 2_000;
  private static final Logger log = LoggerFactory.getLogger(SearxngController.class);

  private final SearxngClient client;
  private final SearxngProperties properties;

  SearxngController(SearxngClient client, SearxngProperties properties) {
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
        "SearXNG",
        VERSION,
        "Self-hosted metasearch over the operator's own SearXNG instance.",
        Set.of(Verb.SEARCH),
        CostClass.FREE,
        NetworkTier.INTERNAL_NETWORK,
        properties.maxResults(),
        MAX_QUERY_LENGTH,
        true);
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
   * The {@code catch} here is deliberately as wide as {@link Exception}, exactly as {@code
   * RemoteSearchProvider} argues on its own equivalent: a refused connection, a timeout and a
   * Jackson mapping failure that surfaces as an unchecked exception are the same fact from this
   * method's side — the upstream did not produce an answer — and narrowing to {@link IOException}
   * would let any of the latter end this method's promise that it never throws.
   */
  SearchAnswer search(SearchAsk ask) {
    long startedAt = System.nanoTime();
    try {
      List<Hit> hits = client.search(ask.query(), ask.max(), ask.ignoredDomains());
      return SearchAnswer.success(ask.requestId(), PROVIDER_KEY, hits, elapsedMs(startedAt));
    } catch (Exception e) {
      String detail = failureDetail(e);
      long elapsedMs = elapsedMs(startedAt);
      logFailure(ask.requestId(), detail, elapsedMs);
      return SearchAnswer.failed(ask.requestId(), PROVIDER_KEY, detail, elapsedMs);
    }
  }

  /** See Brave's equivalent: query text stays out; request id joins both processes' warnings. */
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
