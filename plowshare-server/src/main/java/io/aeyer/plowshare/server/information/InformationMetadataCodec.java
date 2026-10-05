package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;

/**
 * Model metadata conversion: validate every suggestion before selecting source-backed attribution.
 */
public final class InformationMetadataCodec {
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .build();

  static {
    for (var shape :
        java.util.List.of(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean))
      JSON.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
          .setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
  }

  private InformationMetadataCodec() {}

  private record Candidate(String name, String evidence, Boolean certain) {
    Candidate {
      if (name == null
          || name.isBlank()
          || name.length() > 256
          || name.codePoints().anyMatch(Character::isISOControl)
          || name.indexOf('\u2028') >= 0
          || name.indexOf('\u2029') >= 0
          || evidence == null
          || evidence.isBlank()
          || evidence.length() > 512
          || evidence.indexOf('\0') >= 0
          || certain == null)
        throw new IllegalArgumentException("Invalid bibliographic candidate fields");
      name = name.strip();
      evidence = evidence.strip();
    }

    boolean verified(String retained) {
      return certain && evidence.contains(name) && retained != null && retained.contains(evidence);
    }
  }

  private record Suggestion(
      List<String> autoTag,
      Map<String, List<String>> tagGroups,
      Candidate documentAuthor,
      Candidate documentOrganisation) {
    Suggestion {
      autoTag = InformationFacets.tags(autoTag);
      if (tagGroups != null) tagGroups = InformationTagGroups.from(tagGroups, autoTag);
    }
  }

  public static InformationMetadata read(String wire, String retained) {
    return converted(tree(wire), retained);
  }

  /** Legacy paid checkpoints containing only tag lists remain readable without a new model call. */
  static InformationMetadata convert(Object value, String retained) {
    return converted(JSON.valueToTree(value), retained);
  }

  private static InformationMetadata converted(JsonNode value, String retained) {
    if (value != null && value.isArray())
      return new InformationMetadata(
          InformationFacets.tags(JSON.convertValue(value, Object.class)), null, null, null);
    if (value == null || !value.isObject())
      throw new IllegalStateException(
          "Information metadata must be an object or historical tag list");
    try {
      if (!value.path("autoTag").isArray())
        throw new IllegalArgumentException("autoTag array required");
      Suggestion suggestion = JSON.treeToValue(value, Suggestion.class);
      Candidate selected = suggestion.documentAuthor();
      String source = "person";
      if (selected == null || !selected.verified(retained)) {
        selected = suggestion.documentOrganisation();
        source = "organisation";
      }
      if (selected == null || !selected.verified(retained))
        return new InformationMetadata(
            suggestion.autoTag(), null, null, null, suggestion.tagGroups());
      return new InformationMetadata(
          suggestion.autoTag(),
          selected.name(),
          source,
          selected.evidence(),
          suggestion.tagGroups());
    } catch (java.io.IOException | IllegalArgumentException invalid) {
      for (Throwable cause = invalid; cause != null; cause = cause.getCause())
        if (cause instanceof io.aeyer.plowshare.server.faults.CallerFault refused) throw refused;
      throw new IllegalStateException(
          "Information tagger returned invalid metadata fields", invalid);
    }
  }

  public static Map<String, List<String>> groups(String wire, List<String> tags) {
    return InformationTagGroups.from(JSON.convertValue(tree(wire), Object.class), tags);
  }

  private static JsonNode tree(String wire) {
    if (wire == null || wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
      throw new IllegalStateException("Information metadata exceeds 64 KiB or is absent");
    try {
      return JSON.readTree(wire);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("Information metadata is not valid JSON", invalid);
    }
  }
}
