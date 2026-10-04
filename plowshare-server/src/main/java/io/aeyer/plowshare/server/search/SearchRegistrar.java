package io.aeyer.plowshare.server.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.search.ProviderFacts;
import io.aeyer.plowshare.protocol.search.Verb;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

/**
 * Turns a URL an operator typed into a row in {@code search_providers} — the one piece of code in
 * this server that dials a provider process before it is trusted with anything.
 *
 * <h2>Registration fetches the facts; it does not take them</h2>
 *
 * <p>{@code POST /v1/search/providers} carries only a {@code baseUrl}. The alternative — an
 * operator typing {@code maxResults}, {@code costClass} and the rest into the request body — was
 * rejected because every one of those fields is something the provider process already knows about
 * itself: it is a transcription, not an input. A transcription drifts the first time the provider
 * is upgraded to serve fifty results instead of twenty-five, because nothing forces the row typed
 * at registration to be typed again at the next deploy. Fetching {@code GET
 * /v1/search/capabilities} from the URL itself means the row can never say anything the provider
 * does not currently say about itself — re-registering after an upgrade is the only way the row
 * changes, and it is the same call an operator was going to make anyway.
 *
 * <h2>Refused at registration, not at the next search</h2>
 *
 * <p>Spec §4 requires a bad registration to fail at the moment an operator caused it. {@link
 * #register} therefore refuses, in this order:
 *
 * <ol>
 *   <li>a {@code baseUrl} that is missing or blank — {@code POST /v1/search/providers} with a body
 *       of {@code {}} deserialises to a {@code null} here, and it is checked first because
 *       everything below it needs a URL to name in its own message;
 *   <li>a scheme that is not {@code http} or {@code https}, before a socket is even opened;
 *   <li>a provider that does not answer the capabilities probe at all;
 *   <li>an answer with no {@code providerKey};
 *   <li>an answer that does not declare {@link Verb#SEARCH};
 *   <li>an answer missing any of {@code name}, {@code version}, {@code costClass} or {@code
 *       networkTier} — the four remaining fields {@link ProviderStore#upsert} cannot write without.
 * </ol>
 *
 * <p>Every one of those is a fact this method already has in hand, and {@code
 * V33__search_providers.sql}'s own constraints would catch only some of them. {@code
 * search_providers_a_base_url_is_http} and {@code search_providers_declares_at_least_one_verb}
 * exist as a second line of defence, not as the first, because a constraint violation surfaces as a
 * {@code DataIntegrityViolationException} with none of the context an operator needs to fix a typed
 * URL.
 *
 * <h2>Why the last three refusals are here at all, and what they used to be</h2>
 *
 * <p>Items 1 and 6 above were added after the fact, because without them this route answered
 * <b>500</b> to three ordinary caller mistakes, and it did it in exactly the shape {@code
 * SearchService} had already been fixed for. A body of {@code {}} left {@code baseUrl} null and
 * {@code HttpUrl.parse(null)} threw a {@link NullPointerException}, which {@code
 * ApiExceptionHandler} has no handler for and which its {@code Throwable} fallback reports as "this
 * is a fault in the server, not in the request" — false, and the one thing that handler's own
 * javadoc says a status must never claim. A capabilities body with no {@code costClass} or {@code
 * networkTier} reached {@link ProviderStore#upsert} and threw the same way on {@code
 * facts.costClass().name()}; one with no {@code name} or {@code version} reached a {@code NOT NULL}
 * column and came back a {@code DataIntegrityViolationException}, also a 500. For those four fields
 * the database was not a second line of defence, because there was no first one.
 *
 * <p>These are not all the operator's own mistake — a missing {@code costClass} is a mistake by
 * whoever wrote the provider process — but the operator is who is standing there, and 400 with a
 * sentence naming the field and the URL is what lets them tell the two apart. A 500 tells them to
 * look at this server, which is the wrong place.
 *
 * <h2>Refusals throw {@link CallerFault}, deliberately unlike a tool</h2>
 *
 * <p>This is an HTTP route an operator calls by hand, not a model-facing tool whose refusal has to
 * read as text a model can act on — the rule the rest of this server's refusals follow does not
 * apply here. {@link CallerFault} is what {@code ApiExceptionHandler} maps to 400, and every
 * message below names exactly what a human operator typed wrong.
 */
@Component
public class SearchRegistrar {

  /**
   * How long one capabilities probe may take. Ten seconds and not one of the LLM transport's
   * several timeouts: a provider is a local or LAN process answering a request with no model behind
   * it, and a probe that has not answered in ten seconds is not slow, it is not going to answer.
   */
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

  private static final String CAPABILITIES_PATH = "/v1/search/capabilities";

  private final ProviderStore store;
  private final OkHttpClient http;
  private final ObjectMapper mapper;

  /**
   * Takes {@code http} as an argument rather than building its own — {@link
   * SearchConfig#searchHttpClient()} is the one place in this package that constructs an {@code
   * OkHttpClient}, shared with {@link RemoteSearchProvider}, because both classes dial arbitrary
   * registered provider processes with no per-call shape that would justify a second connection
   * pool for the same class of endpoint. See that bean's javadoc for the argument in full — it used
   * to read the other way, on {@code OpenAiTransport}'s precedent, and that precedent stopped
   * applying once a sibling in this same package started configuring a client for an identical kind
   * of call. {@link #PROBE_TIMEOUT} is still this class's own number: it is applied per call
   * through {@link Call#timeout()} in {@link #probe}, not baked into the shared client, exactly as
   * {@link RemoteSearchProvider} applies {@link SearchProperties#getTimeout()} the same way against
   * the same client.
   */
  public SearchRegistrar(ProviderStore store, OkHttpClient http, ObjectMapper mapper) {
    this.store = store;
    this.http = http;
    this.mapper = mapper;
  }

  /**
   * Register a provider at {@code baseUrl}, or move an already-registered one to it.
   *
   * @param baseUrl where the provider process is. A trailing slash is stripped before the row is
   *     written, matching {@code search_providers_a_base_url_has_no_trailing_slash} — stripped here
   *     rather than left to the constraint so that a caller reading back the {@link Registration}
   *     this method returns already sees the stored form, not a value the database would have
   *     refused
   * @return the row {@link ProviderStore#upsert} just wrote, read back through {@link
   *     ProviderStore#find} so the answer carries whatever the database actually holds rather than
   *     what this call assembled
   * @throws CallerFault in the order the class javadoc lists
   */
  public Registration register(String baseUrl) {
    // First, because HttpUrl.parse(null) throws rather than answering
    // null, and because every refusal below quotes the URL back.
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new CallerFault(
          "no baseUrl was given, so there is nothing to"
              + " register. POST /v1/search/providers takes one field, baseUrl, naming"
              + " where the provider process answers");
    }
    // HttpUrl.parse answers null for anything that is not an absolute
    // http or https URL — a file: path, a bare host with no scheme, a
    // typo — so there is no second scheme check to write here.
    if (HttpUrl.parse(baseUrl) == null) {
      throw new CallerFault(
          "'"
              + baseUrl
              + "' is not an http or https URL, so"
              + " this server will not dial it. A provider is reached over plain HTTP,"
              + " never a file path or another scheme");
    }
    String stripped = strip(baseUrl);
    ProviderFacts facts = probe(stripped);
    if (facts.providerKey() == null || facts.providerKey().isBlank()) {
      throw new CallerFault(
          "the provider at '"
              + stripped
              + "' answered its"
              + " capabilities probe with no providerKey, so there is no key to register"
              + " it under. Its "
              + CAPABILITIES_PATH
              + " response must name one");
    }
    if (!facts.verbs().contains(Verb.SEARCH)) {
      throw new CallerFault(
          "the provider at '"
              + stripped
              + "' does not declare"
              + " "
              + Verb.SEARCH
              + " among its verbs ("
              + facts.verbs()
              + "), so this"
              + " server has nothing to route a search to it for");
    }
    // The four fields ProviderStore.upsert cannot write without. Checked
    // after providerKey and the verbs because those two decide whether
    // this is a search provider at all; these decide whether the row can
    // be written. See the class javadoc for what each of them used to do
    // instead of being refused here.
    named(facts.name(), "name", stripped);
    named(facts.version(), "version", stripped);
    declared(facts.costClass(), "costClass", stripped);
    declared(facts.networkTier(), "networkTier", stripped);
    store.upsert(facts.providerKey(), stripped, facts);
    return store
        .find(facts.providerKey())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "just upserted '"
                        + facts.providerKey()
                        + "' and could not read it back — the"
                        + " store and this method have disagreed about what upsert does"));
  }

  /**
   * Deregister a provider, or refuse a key nothing is registered under.
   *
   * <h2>The refusal is here and was the controller's</h2>
   *
   * <p>{@link ProviderStore#remove} answering {@code false} is not an error down there — see its
   * javadoc — and this used to add nothing to that, leaving {@code SearchProviderController} to
   * turn {@code false} into a 404. That worked while HTTP was the only surface. It stopped working
   * when a frame reached the same capability: a handler calling the store would have had to restate
   * the sentence, and two surfaces saying "no provider is registered under X" in two places is a
   * sentence that drifts the first time either is edited. So the decision that an unknown key
   * <em>is</em> a refusal lives here, once, and both surfaces answer 404 because {@code Faults}
   * maps {@link NotFoundFault} — not because anyone remembered to.
   *
   * <p><b>{@link NotFoundFault} and not {@code api.NotFoundException}</b>: that type may not leave
   * {@code api/}, and this package is well below it. The two mean the same thing and answer the
   * same {@code Code}; what differs is who may raise them.
   *
   * @param providerKey the key the listing answers with
   * @throws NotFoundFault if nothing is registered under {@code providerKey}
   */
  public void deregister(String providerKey) {
    if (!store.remove(providerKey)) {
      throw new NotFoundFault("no provider is registered under '" + providerKey + "'");
    }
  }

  /**
   * {@code GET {baseUrl}/v1/search/capabilities}, deserialised to {@link ProviderFacts}.
   *
   * <p>Every failure mode below — no socket, a refusal, a body that is not the shape {@link
   * ProviderFacts} expects — collapses to one message naming the URL and "did not answer", because
   * an operator fixing a typed URL does not need OkHttp's or Jackson's own wording to act on this:
   * they need to know the address they typed did not work.
   */
  private ProviderFacts probe(String baseUrl) {
    Request request = new Request.Builder().url(baseUrl + CAPABILITIES_PATH).build();
    Call call = http.newCall(request);
    call.timeout().timeout(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    try (Response response = call.execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        throw refusal(baseUrl, "HTTP " + response.code());
      }
      return mapper.readValue(response.body().string(), ProviderFacts.class);
    } catch (IOException unreachable) {
      throw refusal(baseUrl, unreachable.getMessage());
    }
  }

  /**
   * Refuses a capabilities field that has to be a non-blank string — {@code name} and {@code
   * version}, both {@code NOT NULL} columns in {@code V33__search_providers.sql}.
   *
   * <p>Blank as well as null: a {@code NOT NULL} column accepts {@code ""} quite happily, so a
   * provider answering {@code "name": ""} would write a nameless row that every listing shows as an
   * empty cell. Refusing it here is the only place that can be caught, and it costs one condition.
   */
  private static void named(String value, String field, String baseUrl) {
    if (value == null || value.isBlank()) {
      throw new CallerFault(
          "the provider at '"
              + baseUrl
              + "' answered its"
              + " capabilities probe with no "
              + field
              + ". A registered row must carry"
              + " one, so its "
              + CAPABILITIES_PATH
              + " response has to name it");
    }
  }

  /**
   * Refuses a capabilities enum that the row cannot be written without — {@code costClass} and
   * {@code networkTier}.
   *
   * <p>Separate from {@link #named} rather than folded into one {@code Object} check, because the
   * two failures read differently to whoever has to fix them: a missing string is a field left out,
   * while a null enum is a fact the provider never stated at all. The message says "did not
   * declare" for that reason.
   *
   * <p>Only an <em>absent</em> or explicitly null field reaches here. A value that is present but
   * is not one of the constants never gets this far: Jackson throws while deserialising, inside
   * {@link #probe}, and that collapses into the "did not answer" refusal along with every other
   * malformed body — see {@link #probe}'s own comment for why every parse failure shares one
   * message.
   */
  private static void declared(Enum<?> value, String field, String baseUrl) {
    if (value == null) {
      throw new CallerFault(
          "the provider at '"
              + baseUrl
              + "' did not declare a"
              + " "
              + field
              + " in its capabilities. Its "
              + CAPABILITIES_PATH
              + " response must state one of the values plowshare-protocol publishes for"
              + " that field; without it there is no row to write");
    }
  }

  private static CallerFault refusal(String baseUrl, String detail) {
    return new CallerFault(
        "the provider at '"
            + baseUrl
            + "' did not answer "
            + CAPABILITIES_PATH
            + " ("
            + detail
            + "). Registration probes it before writing"
            + " a row, so this fails now rather than at the next search");
  }

  private static String strip(String baseUrl) {
    return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }
}
