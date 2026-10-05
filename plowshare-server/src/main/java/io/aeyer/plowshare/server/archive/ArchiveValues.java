package io.aeyer.plowshare.server.archive;

/** Persisted archive identifiers and narrative fields, before SQL or row delivery. */
final class ArchiveValues {
  private ArchiveValues() {}

  static String identity(String value, String field) {
    if (value == null
        || value.isBlank()
        || value.length() > 1024
        || !value.equals(value.strip())
        || value
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
      throw new IllegalArgumentException("invalid archive " + field);
    return value;
  }

  static String text(String value, String field, int maximum) {
    if (value == null || value.length() > maximum || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("invalid archive " + field);
    return value;
  }
}
