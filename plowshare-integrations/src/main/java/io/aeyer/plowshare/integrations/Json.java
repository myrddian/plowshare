package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Bounded JSON at the external integration boundary. No server dependencies. */
public final class Json {
  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  public static final int MAX_MESSAGE = 256 * 1024;

  private Json() {}

  public static JsonNode parse(String text) throws IOException {
    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE)
      throw new IOException("integration JSON exceeds limit");
    JsonNode node = MAPPER.readTree(text);
    if (node == null) throw new IOException("integration JSON is absent");
    return node;
  }

  public static ObjectNode object() {
    return MAPPER.createObjectNode();
  }

  public static String text(JsonNode node, String field) {
    if (!node.path(field).isTextual() || node.path(field).asText().isBlank())
      throw new IllegalArgumentException("nonblank " + field + " required");
    return node.path(field).asText();
  }

  public static void fields(JsonNode node, String... allowed) {
    if (!node.isObject()) throw new IllegalArgumentException("object required");
    Set<String> names = Set.of(allowed);
    node.fieldNames()
        .forEachRemaining(
            name -> {
              if (!names.contains(name))
                throw new IllegalArgumentException("unknown configuration field: " + name);
            });
  }

  public static String hash(JsonNode node) {
    return hash(canonical(node));
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String canonical(JsonNode n) {
    return sorted(n).toString();
  }

  private static JsonNode sorted(JsonNode n) {
    if (n.isObject()) {
      ObjectNode object = object();
      TreeMap<String, JsonNode> fields = new TreeMap<>();
      n.fields().forEachRemaining(e -> fields.put(e.getKey(), sorted(e.getValue())));
      fields.forEach(object::set);
      return object;
    }
    if (n.isArray()) {
      var array = MAPPER.createArrayNode();
      n.forEach(v -> array.add(sorted(v)));
      return array;
    }
    return n;
  }

  public static UUID identity(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  public static Map<String, Object> map(JsonNode node) {
    if (!node.isObject()) throw new IllegalArgumentException("JSON object required");
    return MAPPER.convertValue(
        node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }
}
