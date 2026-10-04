package io.aeyer.plowshare.ext.brave;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.search.Hit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The one class in this module that knows Brave's own JSON shape, and the one class that ever
 * attaches the vendor credential to a request.
 *
 * <h2>{@code ignoredDomains} is not a parameter this class takes</h2>
 *
 * <p>Brave's Web Search API has no field or query parameter for excluding a domain, so there is
 * nothing here for {@code ignoredDomains} to become — on the opposite side of {@code
 * SearxngClient}'s {@code -site:} translation, which exists only because SearXNG's federated
 * engines understand that token. Inventing an equivalent for Brave (folding the list into the
 * free-text {@code q} the same way) was rejected: Brave's own documentation makes no promise that
 * its ranking treats {@code -site:} as an exclusion the way a generic web search does, so sending
 * it would be Plowshare asserting a behaviour it cannot verify, for a filter {@code
 * ProviderFacts.domainExclusion} already tells the caller this provider does not support. That
 * {@code false} is declared once, in {@link BraveController#capabilities()}; this class honours it
 * structurally, by having no code path that could apply it.
 *
 * <h2>{@link #search} throws rather than answers</h2>
 *
 * <p>Same division as {@code SearxngClient}: this class is a plain translator between one query and
 * Brave's wire shape, and {@link BraveController#search} is the one place that turns a failure into
 * a {@link io.aeyer.plowshare.protocol.search.SearchAnswer#failed}.
 *
 * <p>Not a {@code @Component}: this class needs the vendor credential as a constructor argument,
 * which Spring cannot autowire from the environment on its own — {@link BraveApplication} builds
 * this bean explicitly, reading {@code SEARCH_BRAVE_API_KEY} itself, exactly so that a plain
 * component-scanned bean is never the thing standing between this key and the environment it came
 * from.
 */
class BraveClient {

  private static final String SEARCH_PATH = "res/v1/web/search";
  private static final int MAX_ERROR_BODY_BYTES = 1_024;

  private final OkHttpClient http;
  private final ObjectMapper json;
  private final BraveProperties properties;
  private final String apiKey;

  /**
   * @param apiKey Brave's vendor credential, read once at process startup from {@code
   *     SEARCH_BRAVE_API_KEY} — see {@link BraveApplication} and {@link BraveProperties}'s javadoc
   *     for why it arrives as a constructor argument built from the OS environment rather than as a
   *     {@link BraveProperties} field. A missing or wrong key is not specially handled here: Brave
   *     answers an unauthenticated request with a non-2xx status exactly like any other upstream
   *     failure, and {@link #search} folds it into the same {@link IOException} path as a timeout
   *     or a refused connection.
   */
  BraveClient(OkHttpClient http, ObjectMapper json, BraveProperties properties, String apiKey) {
    this.http = http;
    this.json = json;
    this.properties = properties;
    this.apiKey = apiKey;
  }

  List<Hit> search(String query, int max) throws IOException {
    HttpUrl base = HttpUrl.parse(properties.baseUrl());
    if (base == null) {
      throw new IOException("not a valid base URL: " + properties.baseUrl());
    }
    HttpUrl url =
        base.newBuilder()
            .addPathSegments(SEARCH_PATH)
            .addQueryParameter("q", query)
            .addQueryParameter("count", String.valueOf(Math.min(max, properties.maxResults())))
            .build();
    Request request =
        new Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", apiKey == null ? "" : apiKey)
            .get()
            .build();
    Call call = http.newCall(request);
    call.timeout().timeout(properties.timeoutMs(), TimeUnit.MILLISECONDS);
    try (Response response = call.execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        throw new IOException(failure(response, "Brave"));
      }
      JsonNode root = json.readTree(response.body().string());
      return toHits(root, Math.min(max, properties.maxResults()));
    }
  }

  /** Keep the vendor's actionable error text, bounded and on one line. */
  private static String failure(Response response, String upstream) throws IOException {
    String message = upstream + " returned HTTP " + response.code();
    if (response.body() == null) {
      return message + " with no response body";
    }
    byte[] bytes = response.body().byteStream().readNBytes(MAX_ERROR_BODY_BYTES + 1);
    int shown = Math.min(bytes.length, MAX_ERROR_BODY_BYTES);
    String detail =
        new String(bytes, 0, shown, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
    if (detail.isEmpty()) {
      return message;
    }
    return message + ": " + detail + (bytes.length > MAX_ERROR_BODY_BYTES ? "…" : "");
  }

  private static List<Hit> toHits(JsonNode root, int max) {
    List<Hit> hits = new ArrayList<>();
    for (JsonNode result : root.path("web").path("results")) {
      if (hits.size() >= max) {
        break;
      }
      hits.add(
          new Hit(
              result.path("url").asText(""),
              result.path("title").asText(""),
              result.path("description").asText("")));
    }
    return hits;
  }
}
