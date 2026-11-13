package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable disclosure bindings and publication outbox, not a second delivery log. Bind participates
 * in the forum mutation transaction. Pending locks a binding and reads its oldest board sequence;
 * publish and remove must commit together. Different workers cannot reorder one request's answers.
 */
public interface BoardBridgeRepository {
  sealed interface ReplyDestination permits TopicDestination, NativeDestination {}

  /** Null generation is retained only for historical unverified pending requests. */
  record TopicDestination(long project, String topic, UUID generation) implements ReplyDestination {
    public TopicDestination {
      if (project < 1) throw new IllegalArgumentException("Reply requires a project");
      topic = RelayPort.topic(topic);
    }
  }

  /** Opaque retained native requester proof; no instance or conversation arrives from a body. */
  record NativeDestination(UUID sourceEvent) implements ReplyDestination {
    public NativeDestination {
      Objects.requireNonNull(sourceEvent);
    }
  }

  record Binding(
      UUID requestId,
      String account,
      String message,
      String topic,
      ReplyDestination destination,
      String replyTo,
      RelayCausation causation) {
    public Binding(
        UUID requestId,
        String account,
        String message,
        String topic,
        long project,
        String replyTopic,
        UUID generation,
        String replyTo,
        RelayCausation causation) {
      this(
          requestId,
          account,
          message,
          topic,
          new TopicDestination(project, replyTopic, generation),
          replyTo,
          causation);
    }

    public Binding {
      Objects.requireNonNull(requestId);
      account = RelayPort.identity(account);
      message = BoardClosing.identifier(message, "bdm_");
      topic = BoardClosing.identifier(topic, "bdt_");
      Objects.requireNonNull(destination);
      replyTo = RelayPort.identity(replyTo);
      if (destination instanceof TopicDestination target && target.generation() == null)
        throw new IllegalArgumentException("New reply bindings require a topic incarnation");
      if (destination instanceof NativeDestination && causation == null)
        throw new IllegalArgumentException("Native board replies require retained ancestry");
    }
  }

  record Pending(
      UUID requestId,
      ReplyDestination destination,
      String replyTo,
      String topic,
      String message,
      String body,
      String authorKind,
      String author,
      boolean terminal,
      Instant postedAt,
      RelayCausation causation) {
    public Pending(
        UUID requestId,
        long project,
        String replyTopic,
        UUID generation,
        String replyTo,
        String topic,
        String message,
        String body,
        String authorKind,
        String author,
        boolean terminal,
        Instant postedAt,
        RelayCausation causation) {
      this(
          requestId,
          new TopicDestination(project, replyTopic, generation),
          replyTo,
          topic,
          message,
          body,
          authorKind,
          author,
          terminal,
          postedAt,
          causation);
    }

    public Pending {
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(destination);
      replyTo = RelayPort.identity(replyTo);
      topic = BoardClosing.identifier(topic, "bdt_");
      message = BoardClosing.identifier(message, "bdm_");
      body = RelayPort.text(body);
      authorKind = RelayPort.identity(authorKind);
      author = RelayPort.identity(author);
      Objects.requireNonNull(postedAt);
    }
  }

  enum Destination {
    AVAILABLE,
    MISSING,
    REPLACED,
    UNVERIFIED,
    INVALID_KIND,
    NATIVE_UNAVAILABLE
  }

  /**
   * A terminal failure is retained with the request and every answer that could not be delivered.
   */
  record Failure(UUID requestId, Destination reason, long owedMessages, Instant failedAt) {
    public Failure {
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(reason);
      if (reason == Destination.AVAILABLE || owedMessages < 1)
        throw new IllegalArgumentException("A reply failure requires undelivered evidence");
      Objects.requireNonNull(failedAt);
    }
  }

  void bind(Binding binding);

  Optional<Pending> pending();

  /** Atomically archives all owed answers and settles this binding; it is never retargeted. */
  void undeliverable(Pending pending, Destination reason);

  /** Scoped read-only terminal evidence; absence means no retained failed binding, not delivery. */
  Optional<Failure> failure(UUID requestId, String account);

  /** Back off a rolled-back append, preserving its debt and per-request order. */
  void defer(Pending pending, Instant until);

  void published(Pending pending);
}
