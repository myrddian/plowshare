package io.aeyer.plowshare.server.relay;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Shared validation for immutable values and repository preconditions. */
final class RelayValues {
  private RelayValues() {}

  static String topic(String value) {
    return io.aeyer.plowshare.protocol.RelayPort.topic(value);
  }

  static String name(String value, String field) {
    if (value == null
        || value.length() > 160
        || !value.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*"))
      throw new IllegalArgumentException(field + " must be a bounded lower-case name");
    return value;
  }

  /**
   * The read-tier consumer-group prefix. A work-tier name cannot contain a colon, so the two
   * namespaces are disjoint; see V187 for the matching schema constraint.
   */
  private static final String READER = "reader:";

  /** Matches {@code RelayPort.identity}, which reports stored names back in log inspection. */
  private static final int MAX_SUBSCRIBER = 256;

  /**
   * Builds the stored consumer-group name for a read-tier subscription from the authenticated
   * account. The server derives it; a caller supplies only the logical group name, so it cannot
   * express a project-declared name and cannot land in another account's group.
   */
  static String readerSubscriber(String account, String group) {
    identity(account, "subscription reader");
    if (account.indexOf(':') >= 0)
      throw new IllegalArgumentException("subscription reader must not contain a colon");
    String stored = READER + account + ':' + name(group, "subscriber");
    // The stored name is reported back through RelayPort.identity in log inspection, which bounds
    // identities at 256. Exceeding it would make a topic's log page unreadable for everyone on
    // that topic rather than inconveniencing the one caller, so the budget is spent here, with an
    // actionable error, instead of being discovered there.
    if (stored.length() > MAX_SUBSCRIBER)
      throw new IllegalArgumentException(
          "read-tier subscriber name is too long for this account; shorten the group name");
    return stored;
  }

  /** Whether this stored name belongs to the read-tier namespace rather than the work tier. */
  static boolean reader(String subscriber) {
    return subscriber != null && subscriber.startsWith(READER);
  }

  /**
   * Validates a stored consumer-group name in either namespace. Work-tier names keep their original
   * rule; read-tier names must carry a non-empty owner and a valid logical name.
   */
  static String subscriber(String value) {
    if (!reader(value)) return name(value, "subscriber");
    if (value.length() > MAX_SUBSCRIBER)
      throw new IllegalArgumentException("subscriber name is too long");
    int split = value.indexOf(':', READER.length());
    if (split < 0 || split == READER.length())
      throw new IllegalArgumentException("read-tier subscriber must name its owning account");
    String account = value.substring(READER.length(), split);
    if (account.indexOf(':') >= 0)
      throw new IllegalArgumentException("invalid read-tier subscriber");
    identity(account, "subscription reader");
    name(value.substring(split + 1), "subscriber");
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
