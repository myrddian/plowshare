package io.aeyer.plowshare.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.a2a.relay.IngressMain;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A2A adapter process. Config contains peer endpoints and environment variable names, never
 * credential values. The Plowshare bearer comes from PLOWSHARE_TOKEN.
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    IngressMain.main(args);
  }

  public static Receiver.Config receiving(JsonNode config, String project) {
    return receiving(config, project, System::getenv);
  }

  public static Receiver.Config receiving(
      JsonNode config, String project, Function<String, String> environment) {
    if (!config.isObject()) throw new IllegalArgumentException("receive object required");
    var allowed = Set.of("bind", "port", "publicUrl", "agent", "clients", "waitMs");
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
        Map.copyOf(clients),
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
