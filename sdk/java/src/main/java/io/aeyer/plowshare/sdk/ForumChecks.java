package io.aeyer.plowshare.sdk;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Boundary constraints for the forum, swarm and message facades; authority stays on the server. */
final class ForumChecks {
  private ForumChecks() {}

  static String id(String value) {
    return id(value, 256);
  }

  static String id(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || !value.equals(value.strip())
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
      throw new IllegalArgumentException("Invalid bounded identity");
    return value;
  }

  static String optional(String value) {
    return value == null ? null : id(value);
  }

  static String uuid(String value) {
    if (!UUID.fromString(id(value)).toString().equals(value))
      throw new IllegalArgumentException("Expected canonical UUID");
    return value;
  }

  static String text(String value, int max, boolean nonblank) {
    ContractChecks.narrative(value, "text", max, nonblank);
    if (value != null && value.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff))
      throw new IllegalArgumentException("Text contains an unpaired surrogate");
    return value;
  }

  static String name(String value) {
    id(value, 64);
    if (!value.matches("[a-z][a-z0-9]*(?:[-_][a-z0-9]+)*"))
      throw new IllegalArgumentException("Invalid swarm name");
    return value;
  }

  static void count(long value) {
    if (value < 0) throw new IllegalArgumentException("Negative count");
  }

  static void count(Long value) {
    if (value != null) count(value.longValue());
  }

  static void positive(int value, int min) {
    if (value < min) throw new IllegalArgumentException("Invalid positive count");
  }

  static void budget(Integer value) {
    if (value != null) positive(value, 2);
  }

  static void page(Integer offset, Integer limit) {
    if (offset != null) count(offset);
    if (limit != null && (limit < 1 || limit > 200))
      throw new IllegalArgumentException("Limit must be 1..200");
  }

  static <T> List<T> list(List<T> values, int max) {
    Objects.requireNonNull(values);
    ContractChecks.collection(values, "values", max);
    return List.copyOf(values);
  }

  static List<String> ids(List<String> values, int max) {
    var copy = list(values, max);
    copy.forEach(ForumChecks::id);
    return copy;
  }
}
