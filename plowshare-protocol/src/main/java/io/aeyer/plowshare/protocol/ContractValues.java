package io.aeyer.plowshare.protocol;

import java.util.List;
import java.util.Objects;

/** Field-specific checks shared by wire contracts; narrative content is preserved, not escaped. */
final class ContractValues {
  private ContractValues() {}

  static String identity(String value, String field, int maximum) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || value
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
      throw new IllegalArgumentException(field + " must be a bounded nonblank identity");
    return value.strip();
  }

  static String optionalIdentity(String value, String field, int maximum) {
    return value == null ? null : identity(value, field, maximum);
  }

  static String text(String value, String field, int maximum, boolean required) {
    if (value == null && !required) return null;
    if (value == null
        || required && value.isBlank()
        || value.length() > maximum
        || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException(field + " must be bounded text");
    return value;
  }

  static <T> List<T> list(List<T> values, String field, int maximum) {
    Objects.requireNonNull(values, field);
    if (values.size() > maximum)
      throw new IllegalArgumentException(field + " has too many entries");
    return List.copyOf(values);
  }
}
