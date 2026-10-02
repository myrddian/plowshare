package io.aeyer.plowshare.ext.searxng;

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
import org.springframework.stereotype.Component;

/**
 * The one class in this module that knows SearXNG's own JSON shape.
 *
 * <h2>Exclusion has no dedicated parameter either</h2>
 *
 * <p>SearXNG's {@code /search?format=json} route takes a query string and
 * nothing that names a domain to withhold. This class turns {@code
 * ignoredDomains} into a {@code -site:<domain>} token appended to the outgoing
 * query text instead — the same minus-site convention the underlying engines
 * SearXNG federates to already understand, so the exclusion travels through
 * the one channel SearXNG actually forwards rather than a Plowshare-invented
 * parameter no engine on the other end would honour. A dedicated exclusion
 * parameter was rejected for exactly that reason: SearXNG's JSON API does not
 * expose one, so declaring one here would be Plowshare inventing a contract
 * this class could not actually keep.
 *
 * <h2>{@link #search} throws rather than answers</h2>
 *
 * <p>This class is not the one that decides what a failure means to a
 * caller — {@link SearxngController#search} is, and it is the class that owns
 * a {@link io.aeyer.plowshare.protocol.search.SearchAnswer#failed}. Keeping
 * that decision out of here means this class can stay a plain translator
 * between {@link io.aeyer.plowshare.protocol.search.SearchAsk} and one
 * upstream's wire shape, on {@code RemoteSearchProvider}'s own division
 * between "what happened on the wire" and "what that means downstream".
 */
@Component
class SearxngClient {

    private static final int MAX_ERROR_BODY_BYTES = 1_024;

    private final OkHttpClient http;
    private final ObjectMapper json;
    private final SearxngProperties properties;

    SearxngClient(OkHttpClient http, ObjectMapper json, SearxngProperties properties) {
        this.http = http;
        this.json = json;
        this.properties = properties;
    }

    /**
     * @throws IOException on a refused connection, a timeout, a non-2xx
     *     status or a body this class cannot parse as SearXNG's own JSON
     *     shape — one exception type for every way an answer failed to come
     *     back, exactly as {@link okhttp3.Call#execute()} already throws
     *     {@link IOException} for the transport half of that list.
     */
    List<Hit> search(String query, int max, List<String> ignoredDomains) throws IOException {
        HttpUrl base = HttpUrl.parse(properties.baseUrl());
        if (base == null) {
            throw new IOException("not a valid base URL: " + properties.baseUrl());
        }
        HttpUrl url = base.newBuilder()
                .addPathSegment("search")
                .addQueryParameter("q", withExclusions(query, ignoredDomains))
                .addQueryParameter("format", "json")
                .build();
        Request request = new Request.Builder().url(url).get().build();
        Call call = http.newCall(request);
        call.timeout().timeout(properties.timeoutMs(), TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException(failure(response, "SearXNG"));
            }
            JsonNode root = json.readTree(response.body().string());
            return toHits(root, Math.min(max, properties.maxResults()));
        }
    }

    /** Keep the upstream's actionable error text, bounded and on one line. */
    private static String failure(Response response, String upstream) throws IOException {
        String message = upstream + " returned HTTP " + response.code();
        if (response.body() == null) {
            return message + " with no response body";
        }
        byte[] bytes = response.body().byteStream().readNBytes(MAX_ERROR_BODY_BYTES + 1);
        int shown = Math.min(bytes.length, MAX_ERROR_BODY_BYTES);
        String detail = new String(bytes, 0, shown, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").trim();
        if (detail.isEmpty()) {
            return message;
        }
        return message + ": " + detail + (bytes.length > MAX_ERROR_BODY_BYTES ? "…" : "");
    }

    private static String withExclusions(String query, List<String> ignoredDomains) {
        if (ignoredDomains.isEmpty()) {
            return query;
        }
        StringBuilder outbound = new StringBuilder(query);
        for (String domain : ignoredDomains) {
            outbound.append(" -site:").append(domain);
        }
        return outbound.toString();
    }

    private static List<Hit> toHits(JsonNode root, int max) {
        List<Hit> hits = new ArrayList<>();
        for (JsonNode result : root.path("results")) {
            if (hits.size() >= max) {
                break;
            }
            hits.add(new Hit(
                    result.path("url").asText(""),
                    result.path("title").asText(""),
                    result.path("content").asText("")));
        }
        return hits;
    }
}
