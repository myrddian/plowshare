package io.aeyer.plowshare.server.board;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** File boundary for project routing; services receive checked DTOs and never navigate JSON. */
final class RoutingConfigurationCodec {
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .build();

  static {
    for (var shape :
        new CoercionInputShape[] {
          CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
  }

  private RoutingConfigurationCodec() {}

  static RoutingConfiguration.Manifest manifest(String text) {
    try {
      var root = tree(text);
      if (root.has("routing")) {
        var routing = root.get("routing");
        if (!routing.isObject()) throw new IOException("routing must be an object");
        for (var field : java.util.List.of("acceptFrom", "sendTo", "routeFiles"))
          if (routing.has(field) && !routing.get(field).isArray())
            throw new IOException(field + " must be an array");
      }
      var policy = JSON.createObjectNode();
      // Other manifest settings have their own configuration codecs; this slice owns routing.
      for (var key : java.util.List.of("version", "name", "routing"))
        if (root.has(key)) policy.set(key, root.get(key));
      return JSON.treeToValue(policy, RoutingConfiguration.Manifest.class);
    } catch (IOException | IllegalArgumentException invalid) {
      throw refusal(invalid, "Invalid project routing manifest or policy.");
    }
  }

  static RoutingConfiguration.RouteFile routes(String text) {
    try {
      var tree = tree(text);
      for (var entry : tree.path("routes")) {
        for (var field : java.util.List.of("conversation", "retainConversation"))
          if (entry.has(field) && entry.get(field).isNull())
            throw new Board.Refused(field + " must not be null");
        if (entry.has("retainConversation") && !entry.get("retainConversation").isBoolean())
          throw new Board.Refused("retainConversation must be true or false.");
      }
      return JSON.treeToValue(tree, RoutingConfiguration.RouteFile.class);
    } catch (IOException | IllegalArgumentException invalid) {
      throw refusal(invalid, "Invalid named route file.");
    }
  }

  /** Preserve our fixed contract diagnostics, never Jackson messages quoting untrusted input. */
  private static Board.Refused refusal(Throwable invalid, String fallback) {
    for (Throwable cause = invalid; cause != null; cause = cause.getCause())
      if (cause instanceof Board.Refused refused) return refused;
    return new Board.Refused(fallback);
  }

  private static JsonNode tree(String text) throws IOException {
    if (text == null || text.getBytes(StandardCharsets.UTF_8).length > 65536)
      throw new IOException("routing file exceeds 64 KiB");
    var root = JSON.readTree(text);
    if (root == null || !root.isObject()) throw new IOException("routing file must be an object");
    return root;
  }
}
