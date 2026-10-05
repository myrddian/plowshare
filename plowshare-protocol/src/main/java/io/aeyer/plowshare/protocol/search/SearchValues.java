package io.aeyer.plowshare.protocol.search;

import java.net.IDN;

/** Provider/search identifiers and advisory host names have no URL/path interpretation. */
final class SearchValues {
  private SearchValues() {}

  static String identity(String value, String field, int maximum) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || !value.equals(value.strip())
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
      throw new IllegalArgumentException(field + " must be bounded unpadded identity text");
    return value;
  }

  static void domain(String value) {
    identity(value, "ignored domain", 253);
    String ascii;
    try {
      ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("invalid ignored domain", invalid);
    }
    if (ascii.length() > 253
        || ascii.startsWith(".")
        || ascii.endsWith(".")
        || ascii.contains("..")) throw new IllegalArgumentException("invalid ignored domain");
  }
}
