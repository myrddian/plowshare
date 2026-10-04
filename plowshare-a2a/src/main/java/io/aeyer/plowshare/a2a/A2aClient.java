package io.aeyer.plowshare.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import okhttp3.*;

/**
 * A2A 1.0 JSON-RPC sender. Explicit peer URLs and credentials belong to the adapter. Remote
 * messages/artifacts remain opaque JSON; no local filesystem is advertised.
 */
public final class A2aClient implements AutoCloseable {
  public static final class InvalidMessage extends IOException {
    InvalidMessage(String message) {
      super(message);
    }
  }

  public record Observation(
      String state, String task, String context, Map<String, Object> result, String error) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP =
      new com.fasterxml.jackson.core.type.TypeReference<>() {};
  private static final Set<String> STATES =
      Set.of(
          "SUBMITTED",
          "WORKING",
          "INPUT_REQUIRED",
          "AUTH_REQUIRED",
          "COMPLETED",
          "FAILED",
          "CANCELED",
          "REJECTED");
  private final OkHttpClient http;
  private final HttpUrl endpoint;
  private final HttpUrl cardUrl;
  private final String bearer;
  private Map<String, Object> cachedCard;
  private long cardExpires;
  private String cardEtag;
  private String cardModified;
  private String cardCacheControl = "";

  public A2aClient(String endpoint, String bearer, Duration timeout) {
    this(endpoint, null, bearer, timeout);
  }

  public A2aClient(String endpoint, String agentCard, String bearer, Duration timeout) {
    HttpUrl url = HttpUrl.parse(endpoint);
    if (url == null
        || !url.username().isEmpty()
        || !url.password().isEmpty()
        || url.fragment() != null
        || url.query() != null)
      throw new IllegalArgumentException(
          "A2A endpoint must be an HTTP(S) URL without credentials, query or fragment");
    if (timeout.isZero() || timeout.isNegative())
      throw new IllegalArgumentException("positive A2A timeout required");
    this.endpoint = url;
    this.bearer = bearer;
    this.cardUrl =
        agentCard == null
            ? url.newBuilder().encodedPath("/.well-known/agent-card.json").build()
            : HttpUrl.parse(agentCard);
    if (cardUrl == null
        || !cardUrl.username().isEmpty()
        || !cardUrl.password().isEmpty()
        || cardUrl.fragment() != null
        || cardUrl.query() != null)
      throw new IllegalArgumentException(
          "Agent Card URL must be HTTP(S) without credentials, query or fragment");
    this.http =
        new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .callTimeout(timeout)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor(
                chain -> {
                  var sent = chain.request().tag(java.util.concurrent.atomic.AtomicBoolean.class);
                  if (sent != null && !sent.compareAndSet(false, true))
                    throw new IOException("A2A request replay is disabled");
                  return chain.proceed(chain.request());
                })
            .build();
  }

  /**
   * Fetch the public card before advertising/claiming, with bounded conditional caching. Bearer
   * credentials are sent to a card URL only on the configured RPC origin.
   */
  public synchronized Map<String, Object> agentCard() throws IOException {
    long now = System.currentTimeMillis();
    if (cachedCard != null && now < cardExpires) return cachedCard;
    var request =
        new Request.Builder()
            .url(cardUrl)
            .header("Accept", "application/json")
            .header("A2A-Version", "1.0")
            .tag(
                java.util.concurrent.atomic.AtomicBoolean.class,
                new java.util.concurrent.atomic.AtomicBoolean());
    if (cardEtag != null) request.header("If-None-Match", cardEtag);
    else if (cardModified != null) request.header("If-Modified-Since", cardModified);
    if (bearer != null
        && !bearer.isBlank()
        && endpoint.scheme().equals(cardUrl.scheme())
        && endpoint.host().equals(cardUrl.host())
        && endpoint.port() == cardUrl.port()) request.header("Authorization", "Bearer " + bearer);
    try (Response response = http.newCall(request.build()).execute()) {
      Map<String, Object> card;
      if (response.code() == 304 && cachedCard != null) card = cachedCard;
      else {
        if (!response.isSuccessful() || response.body() == null)
          throw new IOException("A2A Agent Card fetch failed; peer is not advertised");
        if (response.body().contentLength() > 64 * 1024)
          throw new IOException("A2A Agent Card exceeds 64 KiB");
        byte[] bytes;
        try (var input = response.body().byteStream()) {
          bytes = input.readNBytes(64 * 1024 + 1);
        }
        if (bytes.length > 64 * 1024) throw new IOException("A2A Agent Card exceeds 64 KiB");
        JsonNode value = JSON.readTree(bytes);
        AgentCards.validate(value, endpoint);
        card = Collections.unmodifiableMap(JSON.convertValue(value, MAP));
      }
      String cache =
          response
              .header("Cache-Control", response.code() == 304 ? cardCacheControl : "")
              .toLowerCase(Locale.ROOT);
      long seconds = 300;
      var age =
          java.util.regex.Pattern.compile("(?:^|[,\\s])max-age\\s*=\\s*\"?(\\d+)").matcher(cache);
      if (age.find()) {
        try {
          seconds = Math.min(300, Long.parseLong(age.group(1)));
        } catch (NumberFormatException invalid) {
          seconds = 0;
        }
      }
      try {
        seconds = Math.max(0, seconds - Math.max(0, Long.parseLong(response.header("Age", "0"))));
      } catch (NumberFormatException invalid) {
        seconds = 0;
      }
      if (cache.contains("no-cache") || cache.contains("no-store")) seconds = 0;
      cardExpires = System.currentTimeMillis() + seconds * 1000;
      cachedCard = cache.contains("no-store") ? null : card;
      cardCacheControl = cachedCard == null ? "" : cache;
      cardEtag =
          cachedCard == null
              ? null
              : response.header("ETag", response.code() == 304 ? cardEtag : null);
      cardModified =
          cachedCard == null
              ? null
              : response.header("Last-Modified", response.code() == 304 ? cardModified : null);
      return card;
    }
  }

  public Observation send(UUID identity, Map<String, Object> content) throws IOException {
    JsonNode message = JSON.valueToTree(content);
    if (!message.isObject() || !message.path("parts").isArray() || message.path("parts").isEmpty())
      throw new InvalidMessage("A2A message requires nonempty parts");
    for (JsonNode part : message.path("parts")) {
      int kinds =
          (part.has("text") ? 1 : 0)
              + (part.has("url") ? 1 : 0)
              + (part.has("raw") ? 1 : 0)
              + (part.has("data") ? 1 : 0);
      if (!part.isObject()
          || kinds != 1
          || part.has("text") && !part.get("text").isTextual()
          || part.has("url") && !part.get("url").isTextual()
          || part.has("raw") && !part.get("raw").isTextual())
        throw new InvalidMessage("invalid A2A 1.0 content part");
    }
    var outgoing = (com.fasterxml.jackson.databind.node.ObjectNode) message.deepCopy();
    // Adapter-controlled ids/role preserve the outbox identity across observation recovery.
    outgoing.put("messageId", identity.toString());
    outgoing.put("role", "ROLE_USER");
    String task = optional(outgoing, "taskId"), context = optional(outgoing, "contextId");
    return observe(
        rpc(
            "SendMessage",
            Map.of("message", outgoing, "configuration", Map.of("returnImmediately", true))),
        task,
        context);
  }

  public Observation status(String task, String context) throws IOException {
    return observeTask(rpc("GetTask", Map.of("id", task)), task, context);
  }

  public Observation cancel(String task, String context) throws IOException {
    return observeTask(rpc("CancelTask", Map.of("id", task)), task, context);
  }

  private JsonNode rpc(String method, Map<String, ?> params) throws IOException {
    String id = UUID.randomUUID().toString();
    var body =
        JSON.writeValueAsString(
            Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    var request =
        new Request.Builder()
            .url(endpoint)
            .header("A2A-Version", "1.0")
            .tag(
                java.util.concurrent.atomic.AtomicBoolean.class,
                new java.util.concurrent.atomic.AtomicBoolean())
            .post(RequestBody.create(body, MediaType.get("application/json")));
    if (JSON.valueToTree(params).path("message").path("metadata").has("plowshareCommand"))
      request.header("A2A-Extensions", Receiver.COMMAND_EXTENSION);
    if (bearer != null && !bearer.isBlank()) request.header("Authorization", "Bearer " + bearer);
    try (Response response = http.newCall(request.build()).execute()) {
      if (!response.isSuccessful())
        throw new IOException(
            "A2A peer answered HTTP " + response.code() + "; remote outcome is unconfirmed");
      if (response.body() == null || response.body().contentLength() > 1024 * 1024)
        throw new IOException("A2A response is absent or exceeds 1 MiB");
      try (var input = response.body().byteStream()) {
        byte[] bytes = input.readNBytes(1024 * 1024 + 1);
        if (bytes.length > 1024 * 1024) throw new IOException("A2A response exceeds 1 MiB");
        JsonNode frame = JSON.readTree(bytes);
        if (frame == null
            || !frame.isObject()
            || !frame.path("jsonrpc").asText().equals("2.0")
            || !frame.path("id").isTextual()
            || !frame.path("id").asText().equals(id)
            || frame.has("result") == frame.has("error"))
          throw new IOException("unreadable or foreign A2A response; outcome is unconfirmed");
        if (frame.has("error"))
          throw new IOException("A2A peer reported a protocol error; outcome is unconfirmed");
        return frame.get("result");
      }
    }
  }

  private static Observation observe(JsonNode result, String task, String context)
      throws IOException {
    if (!result.isObject() || result.has("task") == result.has("message"))
      throw new IOException("A2A SendMessage returned neither one task nor one message");
    if (result.has("task")) return observeTask(result.get("task"), task, context);
    JsonNode message = result.get("message");
    if (!message.path("messageId").isTextual()
        || !message.path("role").asText().equals("ROLE_AGENT")
        || message.path("messageId").asText().isBlank()
        || !message.path("parts").isArray()
        || message.path("parts").isEmpty())
      throw new IOException("unreadable A2A response message");
    if (context != null && !context.equals(optional(message, "contextId")))
      throw new IOException("foreign A2A message context");
    return new Observation(
        "COMPLETED", null, optional(message, "contextId"), JSON.convertValue(result, MAP), null);
  }

  private static Observation observeTask(JsonNode task, String expected, String context)
      throws IOException {
    if (!task.isObject()
        || optional(task, "contextId") == null
        || !task.path("id").isTextual()
        || task.path("id").asText().isBlank()
        || expected != null && !expected.equals(task.path("id").asText())
        || context != null && !context.equals(optional(task, "contextId")))
      throw new IOException("unreadable or foreign A2A task");
    String state = task.path("status").path("state").asText();
    if (!state.startsWith("TASK_STATE_") || !STATES.contains(state.substring(11)))
      throw new IOException("unknown A2A task state");
    state = state.substring(11);
    return new Observation(
        state.equals("SUBMITTED") ? "WORKING" : state,
        task.get("id").asText(),
        optional(task, "contextId"),
        JSON.convertValue(Map.of("task", task), MAP),
        null);
  }

  private static String optional(JsonNode value, String key) throws IOException {
    if (!value.has(key)) return null;
    if (!value.get(key).isTextual() || value.get(key).asText().isBlank())
      throw new IOException("invalid A2A " + key);
    return value.get(key).asText();
  }

  @Override
  public void close() {
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
  }
}
