package io.aeyer.plowshare.a2a.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Discovery boundary for the supported peer-card shape. Every nested field is typed and checked;
 * unknown extension shapes are refused rather than carried into services. Cards remain untrusted
 * descriptions and confer no routing or execution authority.
 */
public final class AgentCardCodec {
  private static final ObjectMapper JSON =
      A2aJson.mapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private AgentCardCodec() {}

  public static AgentCard read(String wire) throws IOException {
    if (wire == null || wire.getBytes(StandardCharsets.UTF_8).length > 64 * 1024)
      throw new IOException("Agent Card is absent or exceeds 64 KiB");
    var value = JSON.readTree(wire);
    if (value == null || !value.isObject()) throw new IOException("Agent Card must be an object");
    return A2aJson.decode(JSON, value, AgentCard.class);
  }
}
