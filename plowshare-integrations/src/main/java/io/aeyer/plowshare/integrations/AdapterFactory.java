package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.function.Function;

/** Explicitly installed modules provide adapter factories through ServiceLoader. */
public interface AdapterFactory {
  String name();

  IntegrationAdapter create(JsonNode configuration, Function<String, String> environment)
      throws IOException;
}
