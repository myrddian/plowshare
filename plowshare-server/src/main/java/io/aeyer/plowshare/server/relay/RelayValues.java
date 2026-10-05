package io.aeyer.plowshare.server.relay;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Shared validation for immutable values and repository preconditions. */
final class RelayValues {
  private RelayValues() {}

  static String name(String value, String field) {
    if (value == null
        || value.length() > 160
        || !value.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*"))
      throw new IllegalArgumentException(field + " must be a bounded lower-case name");
    return value;
  }

  static String identity(String value, String field) {
    if (value == null
        || value.isBlank()
        || value.length() > 256
        || !value.equals(value.strip())
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
      throw new IllegalArgumentException(field + " must be a bounded identity");
    return value;
  }

  static Instant time(Instant value) {
    Objects.requireNonNull(value, "time");
    // JDBC/PostgreSQL stores microseconds. Normalize before idempotency comparisons on retries.
    if (value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
        || value.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z")))
      throw new IllegalArgumentException("time is outside the supported database range");
    return value.truncatedTo(ChronoUnit.MICROS);
  }

  static void limit(int value, int maximum) {
    if (value < 1 || value > maximum)
      throw new IllegalArgumentException("limit must be between 1 and " + maximum);
  }
}
