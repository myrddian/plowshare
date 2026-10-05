package io.aeyer.plowshare.sdk;

import java.util.Set;

/** Contract invariants shared by SDK value constructors; deserialization is not validation. */
final class ContractChecks {
  private ContractChecks() {}

  /** Narrative text can contain newlines; renderers still escape it for their output context. */
  static String text(String value, String field) {
    if (value == null || value.isBlank() || value.length() > 32768 || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException(field + " must be bounded nonblank text");
    return value;
  }

  static String identity(String value, String field) {
    if (value == null
        || value.isBlank()
        || value.length() > 1024
        || value
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
      throw new IllegalArgumentException(field + " must be bounded nonblank text");
    return value.strip();
  }

  static String optionalIdentity(String value, String field) {
    return value == null ? null : identity(value, field);
  }

  static String narrative(String value, String field, int maximum, boolean required) {
    if (value == null && !required) return null;
    if (value == null
        || required && value.isBlank()
        || value.length() > maximum
        || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException(field + " must be bounded text");
    return value;
  }

  static String pathText(String value, String field) {
    if (value == null) return null;
    if (value.isBlank()
        || value.length() > 8192
        || value
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
      throw new IllegalArgumentException(field + " must be a bounded path");
    return value;
  }

  static String timestamp(String value, String field) {
    if (value == null) return null;
    try {
      java.time.OffsetDateTime.parse(value);
    } catch (java.time.format.DateTimeParseException invalid) {
      throw new IllegalArgumentException("invalid " + field, invalid);
    }
    return value;
  }

  static void collection(java.util.List<?> values, String field, int maximum) {
    if (values != null
        && (values.size() > maximum || values.stream().anyMatch(java.util.Objects::isNull)))
      throw new IllegalArgumentException(field + " must be a bounded list without null entries");
  }

  static void nonnegative(long value, String field) {
    if (value < 0) throw new IllegalArgumentException(field + " must be nonnegative");
  }

  static void nonnegative(Number value, String field) {
    if (value != null && value.doubleValue() < 0)
      throw new IllegalArgumentException(field + " must be nonnegative");
  }

  static void finite(double value, String field) {
    if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
  }

  static String oneOf(String value, String field, Set<String> choices) {
    if (!choices.contains(value == null ? "" : value))
      throw new IllegalArgumentException("Unknown " + field);
    return value;
  }
}
