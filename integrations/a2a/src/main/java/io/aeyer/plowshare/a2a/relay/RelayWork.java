package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.a2a.model.ExternalMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    CONFIRMED,
    ABANDONED
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
      Instant occurredAt,
      int replyAttempts,
      String resolution) {
    /** Retains construction compatibility with the initial journal format. */
    public Entry(
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
      this(
          requestId,
          hash,
          sourceEvent,
          producer,
          peer,
          action,
          task,
          context,
          phase,
          observation,
          occurredAt,
          0,
          null);
    }

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
      // The broker's PostgreSQL timestamp identity is microsecond-precise. Normalize retained
      // v1 entries as well as fresh intents so a real receipt can reconcile after an upgrade.
      occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
      if (task != null) identity(task);
      if (context != null) identity(context);
      if (action != Action.SEND && (task == null || context == null))
        throw new IllegalArgumentException("Missing retained task identity");
      if (replyAttempts < 0 || replyAttempts > 1000000)
        throw new IllegalArgumentException("Invalid retained publication attempt count");
      if (resolution != null) text(resolution, 4096);
      if (phase == Phase.ABANDONED && (resolution == null || resolution.isBlank()))
        throw new IllegalArgumentException("An abandoned reply needs operator evidence");
      if (phase != Phase.ABANDONED && resolution != null && !resolution.equals("NOT_SUBMITTED"))
        throw new IllegalArgumentException("Invalid publication resolution");
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
          occurredAt,
          replyAttempts,
          null);
    }

    public Entry advance(Phase next) {
      if (next == Phase.REPLY_INTENT && phase == Phase.OBSERVED) {
        return copy(next, replyAttempts + 1, null);
      }
      if (next == Phase.CONFIRMED && phase == Phase.REPLY_INTENT) {
        return copy(next, replyAttempts, null);
      }
      throw new IllegalStateException("Invalid dispatch transition");
    }

    /** Only a transport's positive NOT_SUBMITTED result permits another publication attempt. */
    public Entry notSubmitted() {
      if (phase != Phase.REPLY_INTENT) throw new IllegalStateException("No publication in flight");
      return copy(Phase.OBSERVED, replyAttempts, "NOT_SUBMITTED");
    }

    /**
     * Operator disposition of unknown delivery; this is never evidence of successful publication.
     */
    public Entry abandon(String evidence) {
      if (phase != Phase.REPLY_INTENT) throw new IllegalStateException("No uncertain publication");
      return copy(Phase.ABANDONED, replyAttempts, text(evidence, 4096));
    }

    private Entry copy(Phase next, int attempts, String reason) {
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
          occurredAt,
          attempts,
          reason);
    }

    public boolean terminal() {
      return phase == Phase.CONFIRMED || phase == Phase.ABANDONED;
    }

    public boolean rejection() {
      return phase == Phase.OBSERVED
          && observation != null
          && observation.state().equals("REJECTED")
          && observation.result() == null
          && observation.task() == null
          && observation.context() == null;
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

  /** Small durable operation receipt. Bodies are discarded only after terminal disposition. */
  public record Archived(
      UUID requestId,
      String hash,
      String sourceEvent,
      String producer,
      String peer,
      Phase phase,
      String remoteTask,
      String remoteContext,
      String resolution) {
    public Archived {
      Objects.requireNonNull(requestId);
      if (hash == null || !hash.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid archived hash");
      identity(sourceEvent);
      identity(producer);
      alias(peer);
      Objects.requireNonNull(phase);
      if (phase != Phase.CONFIRMED && phase != Phase.ABANDONED)
        throw new IllegalArgumentException("Unsettled operation cannot be archived");
      if (remoteTask != null) identity(remoteTask);
      if (remoteContext != null) identity(remoteContext);
      if (remoteTask != null && remoteContext == null)
        throw new IllegalArgumentException("Missing archived context");
      if (resolution != null) text(resolution, 4096);
      if (phase == Phase.ABANDONED && (resolution == null || resolution.isBlank()))
        throw new IllegalArgumentException("Missing archived operator evidence");
      if (phase == Phase.CONFIRMED && resolution != null)
        throw new IllegalArgumentException("Confirmed receipt cannot assert operator abandonment");
    }

    static Archived of(Entry entry) {
      if (!entry.terminal()) throw new IllegalArgumentException("Unsettled operation");
      var observed = entry.observation();
      return new Archived(
          entry.requestId(),
          entry.hash(),
          entry.sourceEvent(),
          entry.producer(),
          entry.peer(),
          entry.phase(),
          observed.result() == null ? null : observed.task(),
          observed.result() == null ? null : observed.context(),
          entry.resolution());
    }
  }

  /** Read-only ownership evidence retained independently of full remote result bodies. */
  record RemoteOwnership(String producer, String peer, String task, String context) {
    RemoteOwnership {
      identity(producer);
      alias(peer);
      if (task != null) identity(task);
      identity(context);
    }
  }

  record Snapshot(
      String schema,
      String owner,
      String identity,
      String previousOwner,
      Instant migratedAt,
      String generation,
      Map<String, Entry> entries) {
    Snapshot(String schema, String owner, String generation, Map<String, Entry> entries) {
      this(schema, owner, owner, null, null, generation, entries);
    }

    Snapshot {
      if (!Set.of("plowshare-a2a-journal/1", "plowshare-a2a-journal/2").contains(schema)
          || owner == null
          || !owner.matches("[a-f0-9]{64}")
          || identity == null
          || !identity.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid journal ownership");
      if ((previousOwner == null) != (migratedAt == null)
          || previousOwner != null && !previousOwner.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid configuration migration evidence");
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
