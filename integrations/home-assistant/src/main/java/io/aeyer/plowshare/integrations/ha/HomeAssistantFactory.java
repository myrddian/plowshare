package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.*;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Function;

/** Owns the installed HA configuration boundary; creation receives only validated settings. */
public final class HomeAssistantFactory implements AdapterFactory {
  public String name() {
    return "home-assistant";
  }

  public HomeAssistantSettings decode(String source) throws IOException {
    var tree = Json.parse(source);
    // Optional absent booleans are supported; explicit null is not an accepted flag.
    if (tree.path("actions").isObject())
      for (var action : tree.path("actions"))
        if (action.has("returnResponse") && !action.get("returnResponse").isBoolean())
          throw new IllegalArgumentException("returnResponse must be boolean");
    if (tree.path("actions").isObject())
      for (var action : tree.path("actions"))
        if (action.path("returnResponse").isBoolean()
            && action.get("returnResponse").booleanValue())
          throw new IllegalArgumentException(
              "returnResponse requires a registered service-response DTO; this binding supports action receipts only");
    return IntegrationCodec.decode(tree, HomeAssistantSettings.class);
  }

  public IntegrationAdapter create(
      AdapterConfiguration configuration, Function<String, String> environment) throws IOException {
    if (!(configuration instanceof HomeAssistantSettings settings))
      throw new IllegalArgumentException("HA settings required");
    String token = environment.apply(settings.tokenEnv());
    if (token == null || token.isBlank())
      throw new IllegalArgumentException("configured HA credential is missing");
    return new HomeAssistantAdapter(settings, token, Duration.ofSeconds(20));
  }
}
