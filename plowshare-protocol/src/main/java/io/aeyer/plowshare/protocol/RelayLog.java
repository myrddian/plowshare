package io.aeyer.plowshare.protocol;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Read-only Relay inspection. Decimal strings preserve 64-bit broker positions in JavaScript. */
public final class RelayLog {
  private RelayLog() {}

  public record TopicsQuery(String project, boolean system, Integer limit) {
    public TopicsQuery {
      scope(project, system);
      bound(limit);
    }
  }

  public record Query(String project, boolean system, String topic, String after, Integer limit) {
    public Query {
      scope(project, system);
      name(topic);
      decimal(after == null ? "0" : after);
      bound(limit);
    }
  }

  public record Process(String project, Integer limit) {
    public Process {
      scope(project, false);
      if (limit != null && (limit < 1 || limit > 32))
        throw new IllegalArgumentException("Relay processing limit must be 1..32");
    }
  }

  public record Gap(String topic, String subscriber, String expiredThrough) {
    public Gap {
      name(topic);
      identity(subscriber);
      decimal(expiredThrough);
    }
  }

  public record Processed(String project, int admitted, int dispatched, List<Gap> gaps) {
    public Processed {
      scope(project, false);
      if (admitted < 0 || admitted > 32 || dispatched < 0 || dispatched > 32)
        throw new IllegalArgumentException("Invalid Relay processing count");
      gaps = List.copyOf(gaps);
    }
  }

  public record Scope(String project, boolean system) {
    public Scope {
      scope(project, system);
    }
  }

  public record Topic(
      String name,
      String kind,
      String retentionSeconds,
      String maxRecords,
      String through,
      String expiredThrough,
      String generation) {
    public Topic(
        String name,
        String kind,
        String retentionSeconds,
        String maxRecords,
        String through,
        String expiredThrough) {
      this(name, kind, retentionSeconds, maxRecords, through, expiredThrough, null);
    }

    public Topic {
      if (generation != null && !UUID.fromString(generation).toString().equals(generation))
        throw new IllegalArgumentException("Invalid Relay generation");
      RelayLog.name(name);
      RelayLog.kind(kind);
      positive(retentionSeconds);
      if (maxRecords != null) positive(maxRecords);
      decimal(through);
      decimal(expiredThrough);
      if (Long.parseLong(expiredThrough) > Long.parseLong(through))
        throw new IllegalArgumentException("Invalid Relay expiry boundary");
    }
  }

  public record Topics(Scope scope, List<Topic> topics) {
    public Topics {
      Objects.requireNonNull(scope);
      topics = List.copyOf(topics);
      if (topics.size() > 100) throw new IllegalArgumentException("Too many Relay topics");
    }
  }

  public record Lifecycle(
      String source, String subject, String state, String context, String related) {
    public Lifecycle {
      name(source);
      identity(subject, 1024);
      identity(state);
      if (context != null) identity(context, 1024);
      if (related != null) identity(related, 1024);
    }
  }

  public record Wake(String firing, String target, String type) {
    public Wake {
      identity(firing, 1024);
      identity(target, 1024);
      if (!firing.startsWith("fir_")
          || !target.startsWith("conversation:")
          || target.length() <= 13
          || !Set.of("MESSAGE", "BOARD").contains(type))
        throw new IllegalArgumentException("Invalid native wake reference");
    }
  }

  public record Payload(
      String kind,
      String text,
      String schedule,
      String emits,
      Instant fireAt,
      Lifecycle lifecycle,
      Wake wake) {
    public Payload(String kind, String text, String schedule, String emits, Instant fireAt) {
      this(kind, text, schedule, emits, fireAt, null, null);
    }

    public Payload {
      RelayLog.kind(kind);
      if (kind.equals("TEXT")) RelayPort.text(text);
      else if (text != null) throw new IllegalArgumentException("Invalid Relay text payload");
      if (kind.equals("LIFECYCLE") != (lifecycle != null)
          || kind.equals("WAKE_REQUESTED") != (wake != null))
        throw new IllegalArgumentException("Invalid Relay structured payload");
      if (kind.equals("SCHEDULE_DUE")) {
        identity(schedule, 1024);
        identity(emits, 1024);
        Objects.requireNonNull(fireAt);
      } else if (schedule != null || emits != null || fireAt != null)
        throw new IllegalArgumentException("Invalid Relay schedule payload");
    }
  }

  public record Event(
      String position,
      String eventId,
      String publisher,
      Instant occurredAt,
      Instant publishedAt,
      String correlationId,
      String causationId,
      Payload payload,
      RelayCausation causation) {
    public Event(
        String position,
        String eventId,
        String publisher,
        Instant occurredAt,
        Instant publishedAt,
        String correlationId,
        String causationId,
        Payload payload) {
      this(
          position,
          eventId,
          publisher,
          occurredAt,
          publishedAt,
          correlationId,
          causationId,
          payload,
          null);
    }

    public Event {
      positive(position);
      identity(eventId);
      identity(publisher);
      Objects.requireNonNull(occurredAt);
      Objects.requireNonNull(publishedAt);
      if (correlationId != null) identity(correlationId);
      if (causationId != null) identity(causationId);
      Objects.requireNonNull(payload);
    }
  }

  public record Subscriber(
      String name, String seenThrough, Instant seenAt, String gapThrough, String generation) {
    public Subscriber(String name, String seenThrough, Instant seenAt, String gapThrough) {
      this(name, seenThrough, seenAt, gapThrough, null);
    }

    public Subscriber {
      if (generation != null && !UUID.fromString(generation).toString().equals(generation))
        throw new IllegalArgumentException("Invalid Relay generation");
      identity(name);
      decimal(seenThrough);
      Objects.requireNonNull(seenAt);
      if (gapThrough != null) decimal(gapThrough);
    }
  }

  /** Execution IDs are shown only to their submitting owner; publication receipts are shared. */
  public record Branch(
      String id,
      String position,
      String subscriber,
      String name,
      String receiver,
      String state,
      String fence,
      Instant updatedAt,
      String failure,
      String receiptNamespace,
      String receiptId,
      String conversation,
      String conversationProject,
      String routingHash,
      String handlerHash) {
    public Branch {
      Objects.requireNonNull(id);
      if (!UUID.fromString(id).toString().equalsIgnoreCase(id))
        throw new IllegalArgumentException("Invalid Relay branch identity");
      positive(position);
      identity(subscriber);
      identity(name);
      identity(receiver);
      if (!Set.of(
              "READY",
              "CLAIMED",
              "DISPATCHING",
              "ACCEPTED",
              "FAILED",
              "UNCERTAIN",
              "ABANDONED",
              "ABANDONED_UNCERTAIN")
          .contains(state)) throw new IllegalArgumentException("Invalid Relay delivery state");
      decimal(fence);
      Objects.requireNonNull(updatedAt);
      if (failure != null) identity(failure);
      if ((receiptNamespace == null) != (receiptId == null))
        throw new IllegalArgumentException("Incomplete Relay receipt");
      if (receiptNamespace != null) {
        identity(receiptNamespace);
        identity(receiptId);
      }
      if (conversation != null) identity(conversation);
      if ((conversation == null) != (conversationProject == null))
        throw new IllegalArgumentException("Incomplete Relay conversation metadata");
      if (conversationProject != null) identity(conversationProject);
      hash(routingHash);
      if (handlerHash != null) hash(handlerHash);
    }
  }

  /** Availability loss recovered from an already durable owning inbox, never a replay receipt. */
  public record Recovery(
      String subscriber, String expiredThrough, String pendingInbox, Instant recoveredAt) {
    public Recovery {
      identity(subscriber);
      positive(expiredThrough);
      decimal(pendingInbox);
      Objects.requireNonNull(recoveredAt);
    }
  }

  public record Page(
      Scope scope,
      Topic topic,
      String after,
      String next,
      String gapThrough,
      List<Event> events,
      List<Subscriber> subscribers,
      List<Branch> branches,
      List<Recovery> recoveries) {
    public Page(
        Scope scope,
        Topic topic,
        String after,
        String next,
        String gapThrough,
        List<Event> events,
        List<Subscriber> subscribers,
        List<Branch> branches) {
      this(scope, topic, after, next, gapThrough, events, subscribers, branches, List.of());
    }

    public Page {
      Objects.requireNonNull(scope);
      Objects.requireNonNull(topic);
      decimal(after);
      decimal(next);
      long boundary = Long.parseLong(topic.expiredThrough());
      long previous = Math.max(Long.parseLong(after), boundary);
      long through = Long.parseLong(topic.through());
      if (Long.parseLong(after) > through
          || !Objects.equals(
              gapThrough, Long.parseLong(after) < boundary ? topic.expiredThrough() : null))
        throw new IllegalArgumentException("Invalid Relay page boundary");
      events = List.copyOf(events);
      subscribers = List.copyOf(subscribers);
      branches = List.copyOf(branches);
      // Older peers omit this optional inspection field; absence means no reported audit.
      recoveries = recoveries == null ? List.of() : List.copyOf(recoveries);
      if (recoveries.size() > 1000 || !scope.system() && !recoveries.isEmpty())
        throw new IllegalArgumentException("Invalid native recoveries");
      for (var recovery : recoveries)
        if (Long.parseLong(recovery.expiredThrough()) > boundary)
          throw new IllegalArgumentException("Invalid native recovery boundary");
      if (events.size() > 100 || subscribers.size() > 1000 || branches.size() > 100)
        throw new IllegalArgumentException("Oversized Relay page");
      for (var event : events) {
        long position = Long.parseLong(event.position());
        if (position <= previous
            || position > through
            || !event.payload().kind().equals(topic.kind()))
          throw new IllegalArgumentException("Invalid Relay event order or kind");
        previous = position;
      }
      if (Long.parseLong(next) != previous)
        throw new IllegalArgumentException("Invalid Relay next position");
      for (var subscriber : subscribers) {
        long seen = Long.parseLong(subscriber.seenThrough());
        if (seen > through
            || !Objects.equals(
                subscriber.gapThrough(), seen < boundary ? topic.expiredThrough() : null))
          throw new IllegalArgumentException("Invalid Relay subscriber position");
      }
      for (var branch : branches)
        if (Long.parseLong(branch.position()) > through)
          throw new IllegalArgumentException("Invalid Relay branch position");
    }
  }

  private static void scope(String project, boolean system) {
    if (system == (project != null)
        || project != null
            && (project.isBlank()
                || project.length() > 256
                || !project.equals(project.strip())
                || project
                    .codePoints()
                    .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029)))
      throw new IllegalArgumentException(
          "Relay inspection requires exactly one project or system scope");
  }

  private static void identity(String value) {
    identity(value, 256);
  }

  private static void identity(String value, int maximum) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || !value.equals(value.strip())
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
      throw new IllegalArgumentException("Invalid Relay identity");
  }

  private static void kind(String value) {
    if (value == null
        || !Set.of("EMPTY", "TEXT", "SCHEDULE_DUE", "LIFECYCLE", "WAKE_REQUESTED").contains(value))
      throw new IllegalArgumentException("Invalid Relay payload kind");
  }

  private static void hash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid Relay source hash");
  }

  private static void positive(String value) {
    decimal(value);
    if (value.equals("0")) throw new IllegalArgumentException("Expected positive Relay value");
  }

  private static void bound(Integer limit) {
    if (limit != null && (limit < 1 || limit > 100))
      throw new IllegalArgumentException("Relay page limit must be 1..100");
  }

  private static void name(String name) {
    if (name == null || name.length() > 160 || !name.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*"))
      throw new IllegalArgumentException("invalid Relay topic name");
  }

  private static void decimal(String value) {
    if (value == null || !value.matches("0|[1-9][0-9]{0,18}"))
      throw new IllegalArgumentException("invalid Relay position");
    try {
      Long.parseLong(value);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("invalid Relay position");
    }
  }
}
