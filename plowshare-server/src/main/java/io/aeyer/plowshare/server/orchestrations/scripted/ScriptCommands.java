package io.aeyer.plowshare.server.orchestrations.scripted;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Sandbox/persistence codec; rejects ambiguous commands before they reach the runtime. */
final class ScriptCommands {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private ScriptCommands() {}

  /** Direct DTO construction has the same structural invariant as sandbox/stored conversion. */
  static void arguments(String name, String arguments, boolean readinessObserver) {
    try {
      JsonNode object = JSON.readTree(arguments);
      if (object == null || !object.isObject())
        throw new IllegalArgumentException("script arguments must be one JSON object");
      canonical(object, 0);
      boolean observer =
          name.equals("information_read")
              && object.path("operation").isTextual()
              && object.path("operation").textValue().equals("await");
      if (observer != readinessObserver)
        throw new IllegalArgumentException("script readiness observer does not match its command");
    } catch (java.io.IOException | IllegalStateException invalid) {
      throw new IllegalArgumentException("invalid script tool arguments", invalid);
    }
  }

  static ScriptStore.Command read(JsonNode node) {
    try {
      return decode(node);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("invalid script command: " + invalid.getMessage(), invalid);
    }
  }

  private static ScriptStore.Command decode(JsonNode node) {
    if (node == null || !node.isObject())
      throw new IllegalStateException("script command must be an object");
    if (node.has("finish")) {
      if (node.size() != 1 || !node.get("finish").isTextual())
        throw new IllegalStateException("script finish must contain one string");
      return new ScriptStore.Finish(node.get("finish").textValue());
    }
    if (node.has("waitMs")) {
      if (node.size() != 1
          || !node.get("waitMs").isIntegralNumber()
          || !node.get("waitMs").canConvertToInt())
        throw new IllegalStateException("script wait must contain one integer waitMs");
      return new ScriptStore.Wait(node.get("waitMs").intValue());
    }
    node.fieldNames()
        .forEachRemaining(
            field -> {
              if (!Set.of("tool", "arguments").contains(field))
                throw new IllegalStateException("unsupported script command field: " + field);
            });
    if (!node.path("tool").isTextual() || !node.path("arguments").isObject())
      throw new IllegalStateException("script tool command requires a name and argument object");
    return new ScriptStore.Tool(
        node.get("tool").textValue(),
        canonical(node.get("arguments"), 0).toString(),
        node.get("tool").textValue().equals("information_read")
            && node.path("arguments").path("operation").isTextual()
            && node.path("arguments").path("operation").textValue().equals("await"));
  }

  /** JSONB changes object-key order. Canonical commands keep retry/hook receipts stable. */
  private static JsonNode canonical(JsonNode node, int depth) {
    if (depth > 64) throw new IllegalStateException("script arguments exceed their nesting bound");
    if (node.isObject()) {
      var sorted = new java.util.TreeMap<String, JsonNode>();
      node.fields()
          .forEachRemaining(
              field -> sorted.put(field.getKey(), canonical(field.getValue(), depth + 1)));
      var object = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
      sorted.forEach(object::set);
      return object;
    }
    if (node.isArray()) {
      var array = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
      node.forEach(value -> array.add(canonical(value, depth + 1)));
      return array;
    }
    return node;
  }

  /**
   * Older journals retain their original encoding; comparison preserves JSON field/value semantics.
   */
  static boolean sameArguments(String first, String second) {
    if (java.util.Objects.equals(first, second)) return true;
    if (first == null || second == null || first.length() > 8388608 || second.length() > 8388608)
      return false;
    try {
      var json =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .enable(
                  com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
      var left = json.readTree(first);
      var right = json.readTree(second);
      return left != null && left.isObject() && left.equals(right);
    } catch (java.io.IOException invalid) {
      return false;
    }
  }
}
