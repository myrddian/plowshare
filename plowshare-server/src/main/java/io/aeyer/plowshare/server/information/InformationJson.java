package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Encoding at transport and persistence boundaries. */
public final class InformationJson {
  private static final ObjectMapper JSON = new ObjectMapper();

  private InformationJson() {}

  public static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }
}
