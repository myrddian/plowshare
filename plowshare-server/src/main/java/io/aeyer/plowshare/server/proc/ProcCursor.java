package io.aeyer.plowshare.server.proc;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Typed keyset positions for the PROC pages whose order is not their row id alone.
 *
 * <p>A cursor is only a position: parsing it never authorizes anything, and every page still
 * rechecks authority. Each operation accepts only the shape it produced. Parsing is strict and
 * happens before any SQL runs; a malformed value, or a cursor minted by an earlier server whose
 * pages were ordered by bare identifiers, is a {@link CallerFault} whose message names the
 * operation and never echoes the supplied text. Callers restart the listing without {@code after}.
 *
 * <ul>
 *   <li><b>Calls</b>: {@code <createdMicros>:<callUuid>}, the call's {@code created_at} in whole
 *       microseconds since the Unix epoch (PostgreSQL's timestamp precision) and its canonical
 *       lowercase call id.
 *   <li><b>Queues</b>: {@code <revisionCreatedMicros>:<rowId>}, the owning revision's creation time
 *       and the queue row id, which begins with that revision's canonical UUID.
 *   <li><b>Tools</b>: the receipt id itself, {@code <conversation>:<ordinal>:<toolId>}.
 *       Conversation ids are minted by {@code ConversationStore} as {@code cnv_} plus hexadecimal
 *       digits and contain no {@code ':'}, so the first separator ends the conversation. The
 *       ordinal is a decimal entry ordinal, and everything after the second separator is the
 *       provider's tool id, which may itself contain {@code ':'}.
 * </ul>
 */
final class ProcCursor {
  /**
   * Decimal microseconds. Every {@code long} value up to the maximum lies below PostgreSQL's
   * timestamp ceiling (294276 AD); values that overflow {@code long} or precede {@link #EARLIEST}
   * are rejected, so a parsed position always binds as a valid {@code timestamptz}.
   */
  private static final Pattern MICROS = Pattern.compile("-?[0-9]{1,19}");

  /** Well inside PostgreSQL's 4713 BC floor; no retained record predates it. */
  private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");

  private static final Pattern ORDINAL = Pattern.compile("[0-9]{1,10}");

  /** Queue row suffixes are fixed SQL literals plus a configured embedding slot name. */
  private static final Pattern QUEUE_SUFFIX = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

  private ProcCursor() {}

  /** A chronological position: the ordering time and the unique tie-breaking key. */
  record Timed(Instant at, String key) {
    Timed {
      Objects.requireNonNull(at);
      Objects.requireNonNull(key);
    }

    /** The bound value for a {@code timestamptz} comparison, exact to the microsecond. */
    OffsetDateTime time() {
      return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
  }

  /** A transcript tool receipt position, compared as a numeric-ordinal row value. */
  record Tool(String conversation, int ordinal, String tool) {}

  /** Encode a chronological position; {@code at} must carry no sub-microsecond component. */
  static String timed(Instant at, String key) {
    Objects.requireNonNull(at);
    Objects.requireNonNull(key);
    if (at.getNano() % 1_000 != 0)
      throw new IllegalArgumentException("PROC cursor times are microsecond precision");
    return ChronoUnit.MICROS.between(Instant.EPOCH, at) + ":" + key;
  }

  /** Parse a {@code proc.calls} cursor. */
  static Timed call(String cursor) {
    var position = position(cursor, "call");
    if (!canonicalUuid(position.key())) throw invalid("call");
    return position;
  }

  /** Parse a {@code proc.queues} cursor. */
  static Timed queue(String cursor) {
    var position = position(cursor, "queue");
    String key = position.key();
    if (key.length() < 38
        || !canonicalUuid(key.substring(0, 36))
        || key.charAt(36) != ':'
        || !QUEUE_SUFFIX.matcher(key.substring(37)).matches()) throw invalid("queue");
    return position;
  }

  /** Parse a {@code proc.tools} cursor, which is the last returned receipt id. */
  static Tool tool(String cursor) {
    Objects.requireNonNull(cursor);
    int first = cursor.indexOf(':');
    int second = first < 0 ? -1 : cursor.indexOf(':', first + 1);
    if (first < 1 || second < 0 || second == cursor.length() - 1) throw invalid("tool");
    String ordinal = cursor.substring(first + 1, second);
    if (!ORDINAL.matcher(ordinal).matches()) throw invalid("tool");
    long value = Long.parseLong(ordinal);
    if (value > Integer.MAX_VALUE) throw invalid("tool");
    return new Tool(cursor.substring(0, first), (int) value, cursor.substring(second + 1));
  }

  private static Timed position(String cursor, String operation) {
    Objects.requireNonNull(cursor);
    int split = cursor.indexOf(':');
    if (split < 1 || split == cursor.length() - 1) throw invalid(operation);
    String micros = cursor.substring(0, split);
    if (!MICROS.matcher(micros).matches()) throw invalid(operation);
    long value;
    try {
      value = Long.parseLong(micros);
    } catch (NumberFormatException overflow) {
      throw invalid(operation);
    }
    Instant at = Instant.EPOCH.plus(value, ChronoUnit.MICROS);
    if (at.isBefore(EARLIEST)) throw invalid(operation);
    return new Timed(at, cursor.substring(split + 1));
  }

  private static boolean canonicalUuid(String value) {
    if (value.length() != 36) return false;
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  private static CallerFault invalid(String operation) {
    return new CallerFault(
        "PROC "
            + operation
            + " cursor is malformed or from an older ordering; omit after to restart");
  }
}
