package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import io.aeyer.plowshare.protocol.AgentCard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Discovery boundary for the supported peer-card shape. Every nested field is typed and checked;
 * unknown extension shapes are refused rather than carried into services. Cards remain untrusted
 * descriptions and confer no routing or execution authority.
 */
public final class AgentCardCodec {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      SdkJson.mapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private AgentCardCodec() {}

  public static AgentCard read(String wire) throws IOException {
    if (wire == null || wire.getBytes(StandardCharsets.UTF_8).length > 64 * 1024)
      throw new IOException("Agent Card is absent or exceeds 64 KiB");
    var value = JSON.readTree(wire);
    if (value == null || !value.isObject()) throw new IOException("Agent Card must be an object");
    return SdkJson.decode(JSON, value, AgentCard.class);
  }
}
