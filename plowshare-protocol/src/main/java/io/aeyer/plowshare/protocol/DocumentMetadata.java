package io.aeyer.plowshare.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Exact metadata profile and transport-independent byte limits for authored information. */
public final class DocumentMetadata {
  private DocumentMetadata() {}

  public static final String PROFILE = "plowshare-document-metadata/1";
  public static final int SCHEMA_BYTES = 65536;
  public static final int METADATA_BYTES = 16384;
  public static final int PRESENTATION_BYTES = 8192;

  public static SchemaValue.ObjectValue empty() {
    return new SchemaValue.ObjectValue(Map.of());
  }

  /** Strict root and byte checks precede domain admission or validation. */
  public static SchemaValue.ObjectValue instance(SchemaValue value) {
    if (!(value instanceof SchemaValue.ObjectValue object))
      throw new IllegalArgumentException("Document metadata must be an object");
    if (SchemaCodec.compactJson(object).getBytes(StandardCharsets.UTF_8).length > METADATA_BYTES)
      throw new IllegalArgumentException("Document metadata exceeds 16 KiB");
    portableKeys(object);
    return object;
  }

  public static void definition(SchemaDefinition schema) {
    Objects.requireNonNull(schema);
    SchemaValidation.declaration(schema);
    if (SchemaCodec.compactJson(schema).getBytes(StandardCharsets.UTF_8).length > SCHEMA_BYTES)
      throw new IllegalArgumentException("Document schema exceeds 64 KiB");
    portableKeys(schema);
  }

  // The public SDK object codecs reserve these names. Enforce the same document profile at the
  // server boundary rather than admitting a binding that a supporting client cannot decode.
  private static void portableKey(String name) {
    if (java.util.Set.of("__proto__", "prototype", "constructor").contains(name))
      throw new IllegalArgumentException("Document profile reserves this object member name");
  }

  private static void portableKeys(SchemaValue value) {
    if (value instanceof SchemaValue.ObjectValue object)
      object
          .values()
          .forEach(
              (name, child) -> {
                portableKey(name);
                portableKeys(child);
              });
    else if (value instanceof SchemaValue.Array array)
      array.values().forEach(DocumentMetadata::portableKeys);
  }

  private static void portableKeys(SchemaDefinition schema) {
    if (schema == null) return;
    for (var entries : java.util.Arrays.asList(schema.properties(), schema.definitions()))
      if (entries != null)
        entries.forEach(
            (name, child) -> {
              portableKey(name);
              portableKeys(child);
            });
    if (schema.required() != null) schema.required().forEach(DocumentMetadata::portableKey);
    for (var children :
        java.util.Arrays.asList(
            schema.prefixItems(), schema.allOf(), schema.anyOf(), schema.oneOf()))
      if (children != null) children.forEach(DocumentMetadata::portableKeys);
    for (var child :
        java.util.Arrays.asList(
            schema.items(),
            schema.contains(),
            schema.not(),
            schema.when(),
            schema.then(),
            schema.otherwise())) portableKeys(child);
    if (schema.additionalProperties() instanceof SchemaDefinition.Constrained nested)
      portableKeys(nested.schema());
  }

  /**
   * Presentation is a closed, declarative object; no renderer code or remote resource is allowed.
   */
  public static SchemaValue.ObjectValue presentation(SchemaDefinition schema, SchemaValue value) {
    if (!(value instanceof SchemaValue.ObjectValue object))
      throw new IllegalArgumentException("Document presentation must be an object");
    SchemaValue.validate(object);
    if (SchemaCodec.compactJson(object).getBytes(StandardCharsets.UTF_8).length
        > PRESENTATION_BYTES)
      throw new IllegalArgumentException("Document presentation exceeds 8 KiB");
    for (var entry : object.values().entrySet()) {
      if (entry.getKey().equals("fieldOrder")
          && entry.getValue() instanceof SchemaValue.Array order) {
        var seen = new java.util.HashSet<String>();
        for (SchemaValue field : order.values()) {
          if (!(field instanceof SchemaValue.Text text)
              || !declared(schema, text.value())
              || !seen.add(text.value()))
            throw new IllegalArgumentException("Invalid presentation field order");
        }
      } else if (entry.getKey().equals("descriptions")
          && entry.getValue() instanceof SchemaValue.ObjectValue descriptions) {
        for (var description : descriptions.values().entrySet())
          if (!declared(schema, description.getKey())
              || !(description.getValue() instanceof SchemaValue.Text))
            throw new IllegalArgumentException("Invalid presentation description");
      } else throw new IllegalArgumentException("Unsupported presentation hint");
    }
    return object;
  }

  private static boolean declared(SchemaDefinition schema, String path) {
    if (!path.startsWith("/") || path.equals("/")) return false;
    var current = schema;
    for (String segment : path.substring(1).split("/", -1)) {
      if (segment.matches(".*~(?![01]).*")) return false;
      String name = segment.replace("~1", "/").replace("~0", "~");
      if (current.properties() == null || !current.properties().containsKey(name)) return false;
      current = current.properties().get(name);
    }
    return true;
  }

  public static SchemaValidation.Result validate(
      SchemaDefinition schema, SchemaValue.ObjectValue metadata) {
    definition(schema);
    return SchemaValidation.inspect(schema, instance(metadata));
  }
}
