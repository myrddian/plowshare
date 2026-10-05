package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Persistence/prompt codec for complete log DTOs. No service inspects a JSON snapshot. */
public final class SkillContextLogs {
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .findAndAddModules()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
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

  private static final TypeReference<List<SkillContextLog.Entry>> ENTRIES =
      new TypeReference<>() {};

  private SkillContextLogs() {}

  public static SkillContextLog read(String source) {
    size(source);
    try {
      List<SkillContextLog.Entry> decoded = JSON.readValue(source, ENTRIES);
      if (decoded == null)
        throw new IllegalStateException("The parent log snapshot must be an array");
      return new SkillContextLog(decoded);
    } catch (java.io.IOException | IllegalArgumentException invalid) {
      throw new IllegalStateException("The parent log snapshot is unreadable", invalid);
    }
  }

  /**
   * Historical text for a model/persistence channel, never dispatched or interpreted as a command.
   */
  public static String write(SkillContextLog source) {
    try {
      String encoded = JSON.writeValueAsString(source.entries());
      size(encoded);
      return encoded;
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("The parent log snapshot cannot be written", invalid);
    }
  }

  private static void size(String source) {
    if (source == null
        || source.getBytes(StandardCharsets.UTF_8).length > ChannelDefinitions.MAX_SOURCE_BYTES)
      throw new IllegalStateException(
          "The complete skill context exceeds the source limit; nothing was truncated or substituted");
  }
}
