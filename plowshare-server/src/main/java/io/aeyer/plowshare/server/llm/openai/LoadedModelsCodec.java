package io.aeyer.plowshare.server.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.server.llm.LoadedModels;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransportException;
import java.util.LinkedHashMap;

/** Vendor metadata conversion finishes before provider discovery/cache logic runs. */
final class LoadedModelsCodec {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private LoadedModelsCodec() {}

  static LoadedModels read(String wire) {
    try {
      return read(JSON.readTree(wire));
    } catch (java.io.IOException invalid) {
      throw new LlmTransportException("unreadable loaded model metadata", invalid);
    }
  }

  static LoadedModels read(JsonNode root) {
    if (root == null
        || !root.isObject()
        || !root.path("data").isArray()
        || root.path("data").size() > 10000)
      throw new LlmTransportException("unreadable loaded model metadata");
    var lengths = new LinkedHashMap<String, Integer>();
    var identities = new java.util.HashSet<String>();
    for (JsonNode model : root.path("data")) {
      if (!model.isObject() || !model.path("id").isTextual())
        throw new LlmTransportException("unreadable loaded model identity");
      String id = model.get("id").textValue();
      if (id.isBlank()
          || id.length() > 1024
          || !id.equals(id.strip())
          || id.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029)
          || !identities.add(id))
        throw new LlmTransportException("invalid or duplicate loaded model identity");
      JsonNode loaded = model.get("loaded_context_length");
      if (loaded == null || loaded.isNull()) continue;
      if (!loaded.isIntegralNumber() || !loaded.canConvertToInt())
        throw new LlmTransportException("loaded model context window must be an integer");
      if (loaded.intValue() > 0) lengths.put(id, loaded.intValue());
    }
    return new LoadedModels(lengths);
  }
}
