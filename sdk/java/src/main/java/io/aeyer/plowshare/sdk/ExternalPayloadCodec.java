package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.ExternalMessage;
import io.aeyer.plowshare.protocol.ExternalResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Strict conversion of registered external content before it enters SDK or adapter contracts. */
public final class ExternalPayloadCodec {
  private static final ObjectMapper JSON =
      SdkJson.mapper()
          .registerModule(ExternalTimeCodec.module())
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  private ExternalPayloadCodec() {}

  public static ExternalMessage message(String wire) throws IOException {
    return read(wire, ExternalMessage.class, 256 * 1024);
  }

  public static ExternalResult result(String wire) throws IOException {
    return read(wire, ExternalResult.class, 1024 * 1024);
  }

  private static <T> T read(String wire, Class<T> type, int maximum) throws IOException {
    if (wire == null || wire.getBytes(StandardCharsets.UTF_8).length > maximum)
      throw new IOException("external payload is absent or exceeds its bound");
    var node = JSON.readTree(wire);
    if (node == null || !node.isObject())
      throw new IOException("external payload requires an object");
    // Polymorphic families are closed by Jackson's registered subtype contracts and their
    // constructors.
    try {
      return JSON.treeToValue(node, type);
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Invalid external " + type.getSimpleName(), invalid);
    }
  }
}
