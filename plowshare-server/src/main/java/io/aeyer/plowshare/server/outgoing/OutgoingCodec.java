package io.aeyer.plowshare.server.outgoing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.aeyer.plowshare.protocol.AgentCard;
import io.aeyer.plowshare.protocol.Outgoing;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.util.Map;

/** Outbox transport/persistence conversion. This codec does not change other frame families. */
public final class OutgoingCodec {
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .findAndAddModules()
          .addModule(ExternalTimeCodec.module())
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .build();

  static {
    for (var shape :
        new CoercionInputShape[] {
          CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
  }

  private OutgoingCodec() {}

  public static AgentCard card(String value) throws IOException {
    if (value == null || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64 * 1024)
      throw new IOException("peer card is absent or exceeds 64 KiB");
    return JSON.readValue(value, AgentCard.class);
  }

  public static io.aeyer.plowshare.protocol.ExternalMessage message(String value)
      throws IOException {
    return read(value, io.aeyer.plowshare.protocol.ExternalMessage.class, 256 * 1024);
  }

  public static io.aeyer.plowshare.protocol.ExternalResult result(String value) throws IOException {
    return read(value, io.aeyer.plowshare.protocol.ExternalResult.class, 1024 * 1024);
  }

  private static <T> T read(String value, Class<T> type, int maximum) throws IOException {
    if (value == null || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maximum)
      throw new IOException("external payload is absent or exceeds its bound");
    T result = JSON.readValue(value, type);
    if (result == null) throw new IOException("external payload must be an object");
    return result;
  }

  /** Frame v1 tolerates unknown top-level fields; the nested card contract is closed. */
  public static <T> T request(Map<String, Object> payload, Class<T> type) {
    if (type.getEnclosingClass() != Outgoing.class || !type.isRecord())
      throw new IllegalArgumentException("not an outgoing request DTO");
    try {
      var node = JSON.valueToTree(payload == null ? Map.of() : payload);
      var accepted = JSON.createObjectNode();
      for (var field : type.getRecordComponents()) {
        if (node.has(field.getName())) accepted.set(field.getName(), node.get(field.getName()));
        else if (field.getType().isPrimitive())
          throw new IllegalArgumentException("missing " + field.getName());
      }
      return JSON.treeToValue(accepted, type);
    } catch (IOException | IllegalArgumentException invalid) {
      throw new CallerFault("invalid outgoing " + type.getSimpleName() + " contract");
    }
  }
}
