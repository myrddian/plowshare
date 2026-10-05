package io.aeyer.plowshare.server.documents;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Field validation at the document model-output boundary, before evidence is evaluated. */
final class DocumentModelValues {
  static final int MAX_ENTRIES = 256;

  private DocumentModelValues() {}

  static String text(String value, String field) {
    if (value == null || value.isBlank() || value.length() > 32768 || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(
          field + " must be nonblank text of at most 32768 characters");
    }
    return value.strip();
  }

  static String text(JsonNode value, String field) {
    if (!value.isTextual()) throw new IllegalArgumentException(field + " must be text");
    return text(value.textValue(), field);
  }

  static void fields(JsonNode value, String... allowed) {
    if (!value.isObject()) throw new IllegalArgumentException("Expected an object");
    Set<String> names = Set.of(allowed);
    value
        .fieldNames()
        .forEachRemaining(
            name -> {
              if (!names.contains(name))
                throw new IllegalArgumentException("Unknown field: " + name);
            });
  }

  /** Omitted historical optional lists mean empty; an explicitly invalid list is refused. */
  static JsonNode array(JsonNode value) {
    if (!value.isMissingNode() && (!value.isArray() || value.size() > MAX_ENTRIES)) {
      throw new IllegalArgumentException(
          "Expected an array of at most " + MAX_ENTRIES + " entries");
    }
    return value;
  }

  static int number(JsonNode value) {
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
      throw new IllegalArgumentException("Challenge numbers must be positive integers");
    }
    return value.intValue();
  }

  static void distinct(List<Integer> numbers) {
    if (new HashSet<>(numbers).size() != numbers.size()) {
      throw new IllegalArgumentException("Challenge numbers must be distinct");
    }
  }
}
