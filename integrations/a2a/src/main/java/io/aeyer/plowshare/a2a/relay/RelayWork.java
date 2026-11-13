package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.protocol.ExternalMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Adapter-owned values inside Relay TEXT; none of these adds a core operation or carrier kind. */
public final class RelayWork {
  private RelayWork() {}

  public enum Action {
    SEND,
    READ,
    CANCEL
  }

  public enum Phase {
    INTENT,
    OBSERVED,
    REPLY_INTENT,
    CONFIRMED
  }

  /** A source request UUID owns one immutable intent across delivery and restart. */
  public record Request(
      String schema,
      UUID requestId,
      String peer,
      Action action,
      ExternalMessage message,
      String task,
      String context) {
    public Request {
      if (!"plowshare-a2a-dispatch/1".equals(schema))
        throw new IllegalArgumentException("Unsupported dispatch contract");
      Objects.requireNonNull(requestId);
      alias(peer);
      Objects.requireNonNull(action);
      if (action == Action.SEND) {
        Objects.requireNonNull(message);
        if (task != null
            || context != null
            || message.messageId() != null
            || message.role() != null)
          throw new IllegalArgumentException("Send identity and role belong to the adapter");
      } else {
        if (message != null) throw new IllegalArgumentException("Only SEND carries a message");
        identity(task);
        identity(context);
      }
    }
  }

  public record Report(
      String schema,
      UUID requestId,
      String sourceEvent,
      String producer,
      String peer,
      RemotePeer.Observation observation) {
    public Report {
      if (!"plowshare-a2a-observation/1".equals(schema))
        throw new IllegalArgumentException("Unsupported observation contract");
      Objects.requireNonNull(requestId);
      identity(sourceEvent);
      identity(producer);
      alias(peer);
      Objects.requireNonNull(observation);
    }
  }

  /** Intent has no result; later phases preserve exactly the same observed evidence. */
  public record Entry(
      UUID requestId,
      String hash,
      String sourceEvent,
      String producer,
      String peer,
      Action action,
      String task,
      String context,
      Phase phase,
      RemotePeer.Observation observation,
      Instant occurredAt) {
    public Entry {
      Objects.requireNonNull(requestId);
      if (hash == null || !hash.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid retained request hash");
      identity(sourceEvent);
      identity(producer);
      alias(peer);
      Objects.requireNonNull(action);
      Objects.requireNonNull(phase);
      Objects.requireNonNull(occurredAt);
      if (task != null) identity(task);
      if (context != null) identity(context);
      if (action != Action.SEND && (task == null || context == null))
        throw new IllegalArgumentException("Missing retained task identity");
      if ((phase == Phase.INTENT) != (observation == null))
        throw new IllegalArgumentException("Invalid retained dispatch phase");
    }

    public Entry observed(RemotePeer.Observation result) {
      if (phase != Phase.INTENT) throw new IllegalStateException("Dispatch already observed");
      return new Entry(
          requestId,
          hash,
          sourceEvent,
          producer,
          peer,
          action,
          task,
          context,
          Phase.OBSERVED,
          result,
          occurredAt);
    }

    public Entry advance(Phase next) {
      if (next.ordinal() != phase.ordinal() + 1 || phase == Phase.INTENT)
        throw new IllegalStateException("Invalid dispatch transition");
      return new Entry(
          requestId,
          hash,
          sourceEvent,
          producer,
          peer,
          action,
          task,
          context,
          next,
          observation,
          occurredAt);
    }

    public Report report() {
      return new Report(
          "plowshare-a2a-observation/1", requestId, sourceEvent, producer, peer, observation);
    }

    public String replyId() {
      return UUID.nameUUIDFromBytes(
              ("a2a-observation/1:" + requestId).getBytes(StandardCharsets.UTF_8))
          .toString();
    }
  }

  record Snapshot(String schema, String owner, String generation, Map<String, Entry> entries) {
    Snapshot {
      if (!"plowshare-a2a-journal/1".equals(schema)
          || owner == null
          || !owner.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid journal ownership");
      if (generation != null && !UUID.fromString(generation).toString().equals(generation))
        throw new IllegalArgumentException("Invalid group incarnation");
      entries = Map.copyOf(entries);
      if (entries.size() > 1024) throw new IllegalArgumentException("Journal capacity exceeded");
      entries.forEach(
          (key, value) -> {
            if (!key.equals(value.requestId().toString()))
              throw new IllegalArgumentException("Foreign retained request");
          });
    }
  }

  static String identity(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 1024
        || !value.equals(value.strip())
        || value
            .codePoints()
            .anyMatch(
                c ->
                    Character.isISOControl(c)
                        || c == 0x2028
                        || c == 0x2029
                        || c >= 0xd800 && c <= 0xdfff))
      throw new IllegalArgumentException("Invalid adapter identity");
    return value;
  }

  static String alias(String value) {
    identity(value);
    if (!value.matches("[A-Za-z0-9_.-]{1,64}"))
      throw new IllegalArgumentException("Invalid peer alias");
    return value;
  }

  static String text(String value, int max) {
    if (value == null
        || value.length() > max
        || value.indexOf('\0') >= 0
        || value.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff))
      throw new IllegalArgumentException("Invalid adapter text");
    return value;
  }
}
