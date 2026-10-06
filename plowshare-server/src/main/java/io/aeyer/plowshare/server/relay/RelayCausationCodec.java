package io.aeyer.plowshare.server.relay;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.RelayCausation;
import java.io.IOException;
import java.util.Set;

/** Validates stored causation completely before it crosses the persistence boundary. */
public final class RelayCausationCodec {
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private RelayCausationCodec() {}

  public static String write(RelayCausation value) {
    if (value == null) return null;
    try {
      return JSON.writeValueAsString(value);
    } catch (IOException invalid) {
      throw new IllegalStateException("Cannot encode Relay causation");
    }
  }

  /** Null denotes a pre-upgrade publication, never proof of independent work. */
  public static RelayCausation read(String source) {
    if (source == null) return null;
    if (source.length() > 4096)
      throw new IllegalArgumentException("Invalid persisted Relay causation");
    try {
      var value = JSON.readTree(source);
      var fields = Set.of("rootId", "parentId", "depth");
      if (value == null || !value.isObject() || value.size() != 3)
        throw new IllegalArgumentException();
      value
          .fieldNames()
          .forEachRemaining(
              name -> {
                if (!fields.contains(name)) throw new IllegalArgumentException();
              });
      var root = value.get("rootId");
      var parent = value.get("parentId");
      var depth = value.get("depth");
      if (!root.isTextual()
          || !(parent.isNull() || parent.isTextual())
          || !depth.isIntegralNumber()
          || !depth.canConvertToInt()) throw new IllegalArgumentException();
      return new RelayCausation(
          root.textValue(), parent.isNull() ? null : parent.textValue(), depth.intValue());
    } catch (IOException | IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Invalid persisted Relay causation");
    }
  }
}
