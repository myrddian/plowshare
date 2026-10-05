package io.aeyer.plowshare.server.board;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.aeyer.plowshare.protocol.Incoming;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.util.Map;

/** Ingress conversion before grant checks or receipt creation; nested provenance is closed. */
public final class IncomingRequests {
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .build();

  static {
    for (var shape :
        new CoercionInputShape[] {
          CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
  }

  private IncomingRequests() {}

  /** Unknown top-level v1 frame fields are ignored; declared values are never coerced. */
  public static <T> T read(Map<String, Object> payload, Class<T> type) {
    if (type.getEnclosingClass() != Incoming.class || !type.isRecord())
      throw new IllegalArgumentException("not an incoming request contract");
    try {
      var node = JSON.valueToTree(payload == null ? Map.of() : payload);
      var accepted = JSON.createObjectNode();
      for (var field : type.getRecordComponents())
        if (node.has(field.getName())) accepted.set(field.getName(), node.get(field.getName()));
      return JSON.treeToValue(accepted, type);
    } catch (IOException | IllegalArgumentException invalid) {
      throw new CallerFault("invalid incoming " + type.getSimpleName() + " contract");
    }
  }
}
