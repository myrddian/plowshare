package io.aeyer.plowshare.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.sdk.OutgoingClient;
import io.aeyer.plowshare.sdk.Plowshare;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;

/**
 * A2A adapter process. Config contains peer endpoints and environment variable names, never
 * credential values. The Plowshare bearer comes from PLOWSHARE_TOKEN.
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Usage: plowshare-a2a CONFIG.json");
    JsonNode config =
        new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readTree(Files.readString(Path.of(args[0])));
    String origin = required(config, "plowshare");
    String project = config.has("project") ? required(config, "project") : null;
    Receiver.Config receiving =
        config.has("receive") ? receiving(config.get("receive"), project) : null;
    String token = System.getenv("PLOWSHARE_TOKEN");
    if (token == null || token.isBlank())
      token =
          io.aeyer.plowshare.sdk.AuthClient.login(
              origin,
              System.getenv("PLOWSHARE_HANDLE"),
              System.getenv("PLOWSHARE_PASSWORD"),
              Duration.ofSeconds(30));
    var peers = new LinkedHashMap<String, A2aClient>();
    try {
      if (config.has("peers") && !config.path("peers").isObject())
        throw new IllegalArgumentException("peers must be an object");
      var entries = config.path("peers").fields();
      while (entries.hasNext()) {
        var entry = entries.next();
        String env =
            entry.getValue().has("bearerEnv") ? required(entry.getValue(), "bearerEnv") : null;
        String bearer = env == null ? null : System.getenv(env);
        if (env != null && (bearer == null || bearer.isBlank()))
          throw new IllegalArgumentException("configured peer credential variable is missing");
        String card =
            entry.getValue().has("agentCard") ? required(entry.getValue(), "agentCard") : null;
        peers.put(
            entry.getKey(),
            new A2aClient(
                required(entry.getValue(), "endpoint"), card, bearer, Duration.ofSeconds(20)));
      }
      try (var sdk = Plowshare.connect(origin, token, Duration.ofSeconds(30), null)) {
        var adapter = peers.isEmpty() ? null : new Adapter(new OutgoingClient(sdk), project, peers);
        try (var receiver = receiving == null ? null : new Receiver(receiving, sdk)) {
          if (adapter == null && receiver == null)
            throw new IllegalArgumentException("configure outgoing peers, receive, or both");
          while (!Thread.currentThread().isInterrupted() && sdk.connected()) {
            if (adapter != null) adapter.tick();
            Thread.sleep(1000);
          }
        }
      }
    } finally {
      peers.values().forEach(A2aClient::close);
    }
  }

  static Receiver.Config receiving(JsonNode config, String project) {
    return receiving(config, project, System::getenv);
  }

  static Receiver.Config receiving(
      JsonNode config, String project, java.util.function.Function<String, String> environment) {
    if (!config.isObject()) throw new IllegalArgumentException("receive object required");
    var allowed = java.util.Set.of("bind", "port", "publicUrl", "agent", "clients", "waitMs");
    config
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!allowed.contains(field))
                throw new IllegalArgumentException("unknown receiver setting");
            });
    var clients = new LinkedHashMap<String, String>();
    if (!config.path("clients").isObject())
      throw new IllegalArgumentException("receiver clients object required");
    var entries = config.path("clients").fields();
    while (entries.hasNext()) {
      var entry = entries.next();
      if (!entry.getValue().isObject() || entry.getValue().size() != 1)
        throw new IllegalArgumentException("receiver client requires only bearerEnv");
      String variable = required(entry.getValue(), "bearerEnv");
      if (!variable.matches("[A-Z][A-Z0-9_]*"))
        throw new IllegalArgumentException("bearerEnv must name an environment variable");
      String token = environment.apply(variable);
      if (token == null || token.isBlank())
        throw new IllegalArgumentException(
            "configured receiver client credential variable is missing");
      clients.put(entry.getKey(), token);
    }
    if (!config.path("port").isIntegralNumber() || !config.path("port").canConvertToInt())
      throw new IllegalArgumentException("explicit integral receiver port required");
    int port = config.path("port").intValue();
    if (port < 1 || port > 65535)
      throw new IllegalArgumentException("receiver port must be 1..65535");
    if (config.has("waitMs")
        && (!config.path("waitMs").isIntegralNumber() || !config.path("waitMs").canConvertToLong()))
      throw new IllegalArgumentException("integral receiver waitMs required");
    long wait = config.has("waitMs") ? config.path("waitMs").longValue() : 30000;
    return new Receiver.Config(
        required(config, "bind"),
        port,
        required(config, "publicUrl"),
        project,
        required(config, "agent"),
        java.util.Map.copyOf(clients),
        wait);
  }

  private static String required(JsonNode value, String field) {
    if (!value.path(field).isTextual() || value.path(field).asText().isBlank())
      throw new IllegalArgumentException("nonblank " + field + " required");
    String text = value.get(field).asText();
    if (text.length() > 4096
        || !text.equals(text.strip())
        || text.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("invalid " + field);
    return text;
  }
}
