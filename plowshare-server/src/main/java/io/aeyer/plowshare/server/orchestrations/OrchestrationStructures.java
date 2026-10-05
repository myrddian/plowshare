package io.aeyer.plowshare.server.orchestrations;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.Orchestration.Structure;

/** JSON conversion at the message persistence boundary; malformed durable state is never hidden. */
public final class OrchestrationStructures {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .build();

  private OrchestrationStructures() {}

  public static Structure decode(String stored) {
    if (stored == null) return null;
    if (stored.length() > 2097152)
      throw new IllegalStateException("orchestration structure exceeds its bound");
    try {
      return JSON.readValue(stored, Structure.class);
    } catch (java.io.IOException | IllegalArgumentException invalid) {
      throw new IllegalStateException("invalid orchestration structure", invalid);
    }
  }

  public static String encode(Structure structure) {
    if (structure == null) return null;
    try {
      return JSON.writeValueAsString(structure);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("cannot encode orchestration structure", invalid);
    }
  }
}
