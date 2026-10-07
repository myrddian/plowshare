package io.aeyer.plowshare.protocol;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Public project-topic ingress and leased egress. An acknowledgement is not a filter verdict. */
public final class RelayPort {
  private RelayPort() {}

  /** An authenticated account gets a bounded, separate publisher namespace. */
  public static String publisher(String account) {
    return "sdk:" + hash(identity(account));
  }

  public static String hash(String text) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 unavailable", unavailable);
    }
  }

  /**
   * Stable request identity is retained with the publication. Parent references preserve ancestry.
   */
  public record Publish(
      String requestId,
      String project,
      String topic,
      String text,
      Instant occurredAt,
      String correlationId,
      String parentTopic,
      String parentEventId) {
    public Publish {
      uuid(requestId);
      identity(project);
      name(topic);
      RelayPort.text(text);
      Objects.requireNonNull(occurredAt);
      if (occurredAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
          || occurredAt.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) throw invalid();
      if (correlationId != null) identity(correlationId);
      if ((parentTopic == null) != (parentEventId == null)) throw invalid();
      if (parentTopic != null) {
        name(parentTopic);
        identity(parentEventId);
      }
    }
  }

  public record Published(
      String requestId, String project, String topic, String position, Instant publishedAt) {
    public Published {
      uuid(requestId);
      identity(project);
      name(topic);
      decimal(position);
      if (position.equals("0")) throw invalid();
      Objects.requireNonNull(publishedAt);
    }
  }

  public enum Start {
    OLDEST_RETAINED,
    LATEST
  }

  public enum Status {
    DATA,
    EMPTY,
    GAP,
    BUSY
  }

  /** Same group shares a cursor; a different consumer cannot take an unexpired batch. */
  public record Consume(
      String project,
      String topic,
      String group,
      String consumerId,
      Start start,
      Integer limit,
      Integer waitMs) {
    public Consume {
      identity(project);
      name(topic);
      RelayPort.group(group);
      uuid(consumerId);
      Objects.requireNonNull(start);
      if (limit != null && (limit < 1 || limit > 100)) throw invalid();
      if (waitMs != null && (waitMs < 0 || waitMs > 30000)) throw invalid();
    }
  }

  /** Tokens fence a whole delivered batch. Empty/busy reads carry no acknowledgement authority. */
  public record Batch(
      String project,
      String topic,
      String group,
      String consumerId,
      Status status,
      String batchId,
      String fence,
      String through,
      Instant expiresAt,
      String expiredThrough,
      List<RelayLog.Event> events) {
    public Batch {
      identity(project);
      name(topic);
      RelayPort.group(group);
      uuid(consumerId);
      Objects.requireNonNull(status);
      decimal(through);
      events = List.copyOf(events);
      if (events.size() > 100) throw invalid();
      boolean leased = status == Status.DATA || status == Status.GAP;
      if (leased) {
        uuid(batchId);
        decimal(fence);
        if (fence.equals("0")) throw invalid();
        Objects.requireNonNull(expiresAt);
      } else if (batchId != null || fence != null || expiresAt != null) throw invalid();
      if ((status == Status.GAP) != (expiredThrough != null)) throw invalid();
      if (expiredThrough != null) decimal(expiredThrough);
      if ((status == Status.DATA) != !events.isEmpty()) throw invalid();
      long previous = 0;
      for (var event : events) {
        long position = decimal(event.position());
        if (position <= previous || position > decimal(through)) throw invalid();
        previous = position;
      }
    }
  }

  /** A gap requires its inspected exact boundary; data acknowledgements cover the issued batch. */
  public record Ack(
      String project,
      String topic,
      String group,
      String consumerId,
      String batchId,
      String fence,
      String expiredThrough) {
    public Ack {
      identity(project);
      name(topic);
      RelayPort.group(group);
      uuid(consumerId);
      uuid(batchId);
      decimal(fence);
      if (fence.equals("0")) throw invalid();
      if (expiredThrough != null) decimal(expiredThrough);
    }
  }

  public record Acknowledged(
      String project, String topic, String group, String batchId, String through, boolean gap) {
    public Acknowledged {
      identity(project);
      name(topic);
      RelayPort.group(group);
      uuid(batchId);
      decimal(through);
    }
  }

  public static String name(String value) {
    if (value == null
        || value.length() > 160
        || !value.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")) throw invalid();
    return value;
  }

  public static String group(String value) {
    name(value);
    if (value.length() > 140) throw invalid();
    return value;
  }

  public static String identity(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 256
        || !value.equals(value.strip())
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
      throw invalid();
    return value;
  }

  public static String text(String value) {
    if (value == null || value.isBlank() || value.length() > 65536 || value.indexOf('\0') >= 0)
      throw invalid();
    return value;
  }

  public static void uuid(String value) {
    if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid();
  }

  public static long decimal(String value) {
    if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
    return Long.parseLong(value);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid Relay port value");
  }
}
