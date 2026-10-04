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
    JsonNode config = new ObjectMapper().readTree(Files.readString(Path.of(args[0])));
    String origin = required(config, "plowshare");
    String project = config.has("project") ? required(config, "project") : null;
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
        try (var receiver =
            config.has("receive")
                ? new Receiver(receiving(config.get("receive"), project), sdk)
                : null) {
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

  private static Receiver.Config receiving(JsonNode config, String project) {
    var clients = new LinkedHashMap<String, String>();
    if (!config.path("clients").isObject())
      throw new IllegalArgumentException("receiver clients object required");
    var entries = config.path("clients").fields();
    while (entries.hasNext()) {
      var entry = entries.next();
      String variable = required(entry.getValue(), "bearerEnv");
      String token = System.getenv(variable);
      if (token == null || token.isBlank())
        throw new IllegalArgumentException(
            "configured receiver client credential variable is missing");
      clients.put(entry.getKey(), token);
    }
    int port = config.has("port") ? config.path("port").asInt(-1) : 8093;
    if (port < 1 || port > 65535)
      throw new IllegalArgumentException("receiver port must be 1..65535");
    long wait = config.has("waitMs") ? config.path("waitMs").asLong(-1) : 30000;
    return new Receiver.Config(
        config.has("bind") ? required(config, "bind") : "127.0.0.1",
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
    return value.get(field).asText();
  }
}
