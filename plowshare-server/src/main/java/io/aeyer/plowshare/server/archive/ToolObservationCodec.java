package io.aeyer.plowshare.server.archive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.ToolObservation;

/** Bounded typed persistence encoding. No generic JSON escapes this boundary. */
final class ToolObservationCodec {
  private static final ObjectMapper MAPPER =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .build();

  private ToolObservationCodec() {}

  static String write(ToolObservation value) {
    if (value == null) return null;
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException invalid) {
      throw new IllegalArgumentException("Invalid tool observation", invalid);
    }
  }

  static ToolObservation read(String value) {
    if (value == null) return null;
    if (value.length() > 131072) throw new InvalidToolObservationException(null);
    try {
      return MAPPER.readValue(value, ToolObservation.class);
    } catch (JsonProcessingException invalid) {
      throw new InvalidToolObservationException(invalid);
    }
  }
}
