package io.aeyer.plowshare.server.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.faults.CallerFault;

/** Strict file/socket boundary. Invalid bytes never enter scheduling logic. */
public final class ScheduleDefinitionCodec {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .findAndAddModules()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .build();

  static {
    for (var shape :
        new com.fasterxml.jackson.databind.cfg.CoercionInputShape[] {
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean
        })
      JSON.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
          .setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
  }

  private ScheduleDefinitionCodec() {}

  public static ScheduledWork read(String text) {
    if (text == null || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
      throw new CallerFault("Schedule files must be at most 64 KiB");
    return read(text, ScheduledWork.class);
  }

  public static <T> T read(String text, Class<T> type) {
    try {
      var parsed = JSON.readTree(text);
      var definition =
          type == ScheduledWork.Save.class
              ? parsed.get("definition")
              : type == ScheduledWork.class ? parsed : null;
      if (definition != null) {
        if (!definition.isObject()
            || !definition.has("paused")
            || !definition.get("paused").isBoolean())
          throw new IllegalArgumentException("paused must be present and boolean");
        var limits = definition.get("limits");
        if (limits != null && !limits.isNull() && !limits.has("queueCap"))
          throw new IllegalArgumentException("limits require queueCap");
      }
      return JSON.treeToValue(parsed, type);
    } catch (java.io.IOException | IllegalArgumentException invalid) {
      throw new CallerFault("Invalid schedule definition: " + invalid.getMessage());
    }
  }

  public static String write(Object value) {
    try {
      return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("Cannot encode schedule definition", invalid);
    }
  }
}
