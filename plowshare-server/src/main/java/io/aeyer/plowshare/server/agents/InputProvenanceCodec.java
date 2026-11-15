package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Strict persistence codec. Unclassified legacy inputs remain unclassified, never guessed. */
public final class InputProvenanceCodec {
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private InputProvenanceCodec() {}

  public static String write(InputProvenance provenance) {
    if (provenance == null) return null;
    try {
      return JSON.writeValueAsString(provenance);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static InputProvenance read(String json) {
    if (json == null) return null;
    try {
      return JSON.readValue(json, InputProvenance.class);
    } catch (JsonProcessingException invalid) {
      throw new IllegalArgumentException("Invalid persisted input provenance", invalid);
    }
  }
}
