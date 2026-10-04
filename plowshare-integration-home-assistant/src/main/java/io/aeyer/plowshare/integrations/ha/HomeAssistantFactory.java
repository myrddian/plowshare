package io.aeyer.plowshare.integrations.ha;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.integrations.*;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Function;

public final class HomeAssistantFactory implements AdapterFactory {
  public String name() {
    return "home-assistant";
  }

  public IntegrationAdapter create(JsonNode configuration, Function<String, String> environment)
      throws IOException {
    String variable = Json.text(configuration, "tokenEnv");
    if (!variable.matches("[A-Z][A-Z0-9_]*"))
      throw new IllegalArgumentException("HA tokenEnv must name an environment variable");
    String token = environment.apply(variable);
    if (token == null || token.isBlank())
      throw new IllegalArgumentException("configured HA credential is missing");
    return new HomeAssistantAdapter(configuration, token, Duration.ofSeconds(20));
  }
}
