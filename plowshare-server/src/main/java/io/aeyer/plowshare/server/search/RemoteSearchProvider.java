package io.aeyer.plowshare.server.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.search.SearchAnswer;
import io.aeyer.plowshare.protocol.search.SearchAsk;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * {@link SearchProvider} over one out-of-process provider, dialled at whatever {@link
 * Registration#baseUrl} an operator registered it under.
 *
 * <h2>One class, not one per provider</h2>
 *
 * <p>Every field a call needs to behave differently per provider already lives on the {@link
 * Registration} row that {@link #search} is handed: {@code baseUrl} says where to dial, and {@code
 * facts.domainExclusion} says whether the ignore list may travel there. Nothing else about "which
 * provider this is" is knowable to this class and nothing else is needed — dispatch never learns,
 * and does not need to learn, whether the row it picked names a process on {@code localhost} or
 * across the internet. A subclass per provider would exist only to close over a {@code baseUrl}
 * that a method parameter already carries, duplicating this class once per registration for no
 * behaviour a field could not hold instead.
 *
 * <h2>{@link #search} never throws</h2>
 *
 * <p>A refused connection, a timeout, a non-2xx status and a body {@link ObjectMapper} cannot parse
 * are the same fact from this class's side of the wire: the provider did not produce an answer. All
 * four are folded into {@link SearchAnswer#failed} rather than allowed to propagate, because the
 * ladder that calls this port — not built in this task — is the thing that decides what a failure
 * means: retry it, demote the provider a rung, or surface it to the caller. That decision needs a
 * value to inspect. An exception thrown from here would resolve it by accident, as whatever happens
 * to unwind the ladder's own call stack, and every rung after the one that threw would never run.
 * So the {@code catch} below is deliberately as wide as {@link Exception}: narrowing it to {@link
 * java.io.IOException} would still let a Jackson mapping failure that surfaces as {@link
 * RuntimeException} (an unexpected token, a numeric overflow) end this method's promise by
 * accident.
 *
 * <h2>The ignore list is scrubbed before it leaves this process</h2>
 *
 * <p>{@link SearchAsk#ignoredDomains} rides along only when {@code at.facts().domainExclusion()}
 * says the provider understands the field — otherwise it is replaced with an empty list before the
 * request body is built. A provider that has not declared {@code domainExclusion} is not going to
 * honour the list (spec §7 already says an ignoring provider is not misbehaving), so sending it
 * anyway buys nothing while it hands that provider's operator a list of domains this operator
 * suppresses — read straight off the wire, for a filter that was never going to be applied.
 * Withholding it here, once, is cheaper than trusting every provider that exists today or ever will
 * to discard a field it did not ask for.
 */
public class RemoteSearchProvider implements SearchProvider {

  private static final String SEARCH_PATH = "/v1/search";
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

  private final OkHttpClient http;
  private final ObjectMapper json;
  private final Duration timeout;

  /**
   * @param http shared with {@code SearchRegistrar} through {@link SearchConfig#searchHttpClient()}
   *     rather than built by this class — see that bean's javadoc for why the search package
   *     settled on one client serving both, after briefly diverging. This class is still handed the
   *     client as a constructor argument rather than reading the bean itself, because its own tests
   *     must point it at a {@code okhttp3.mockwebserver.MockWebServer} — a test-only dependency,
   *     named here as {@code @code} rather than {@code @link} because doclint has no classpath to
   *     resolve it from a {@code main}-source comment — and because a single shared instance serves
   *     every search rather than one probe.
   * @param timeout the budget for one call, applied per request through {@link Call#timeout()}
   *     rather than baked into {@code http} at construction. {@code http} is shared and may be
   *     reused for calls this class knows nothing about, so this class must not reach into it with
   *     {@code newBuilder().callTimeout(...)} — that would build and discard a whole new client,
   *     with its own connection pool wrapper, on every single search. {@link Call#timeout()} sets a
   *     deadline on the one call this method is making and touches nothing else {@code http} is
   *     shared with.
   */
  public RemoteSearchProvider(OkHttpClient http, ObjectMapper json, Duration timeout) {
    this.http = http;
    this.json = json;
    this.timeout = timeout;
  }

  @Override
  public SearchAnswer search(Registration at, SearchAsk ask) {
    long startedAt = System.nanoTime();
    try {
      // Inside the try, not above it. This method's contract is that
      // nothing propagates out of it -- the ladder is entitled to a
      // SearchAnswer for every dial, and an escaping exception here
      // would strand every rung after this one. at.facts() comes off a
      // row this class did not build, so a null facts() is a
      // NullPointerException on a line whose contract says there are
      // none. Contained in practice today (ProviderStore always maps a
      // full row); one line to make that a property of this method
      // rather than of its current caller.
      SearchAsk outbound =
          at.facts().domainExclusion()
              ? ask
              : new SearchAsk(ask.requestId(), ask.query(), ask.max(), List.of());
      RequestBody body = RequestBody.create(json.writeValueAsString(outbound), JSON);
      Request request = new Request.Builder().url(at.baseUrl() + SEARCH_PATH).post(body).build();
      Call call = http.newCall(request);
      call.timeout().timeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
      try (Response response = call.execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          return failed(ask, at, "HTTP " + response.code(), startedAt);
        }
        return json.readValue(response.body().string(), SearchAnswer.class);
      }
    } catch (Exception e) {
      String detail =
          e.getMessage() == null || e.getMessage().isBlank()
              ? e.getClass().getSimpleName()
              : e.getMessage();
      return failed(ask, at, detail, startedAt);
    }
  }

  private static SearchAnswer failed(
      SearchAsk ask, Registration at, String message, long startedAt) {
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
    return SearchAnswer.failed(ask.requestId(), at.providerKey(), message, elapsedMs);
  }
}
