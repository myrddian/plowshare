package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Encoding at transport and persistence boundaries. */
public final class InformationJson {
  // This standalone codec is outside Spring's mapper configuration. Catalogue projections
  // include protocol records with Instant fields, including extraction readiness timestamps.
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  private InformationJson() {}

  public static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }
}
