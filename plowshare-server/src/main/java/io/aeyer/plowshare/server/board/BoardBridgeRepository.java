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
  record Binding(
      UUID requestId,
      String account,
      String message,
      String topic,
      long replyProject,
      String replyTopic,
      String replyTo,
      RelayCausation causation) {
    public Binding {
      Objects.requireNonNull(requestId);
      account = RelayPort.identity(account);
      message = BoardClosing.identifier(message, "bdm_");
      topic = BoardClosing.identifier(topic, "bdt_");
      if (replyProject < 1) throw new IllegalArgumentException("Reply requires a project");
      replyTopic = RelayPort.topic(replyTopic);
      replyTo = RelayPort.identity(replyTo);
    }
  }

  record Pending(
      UUID requestId,
      long replyProject,
      String replyTopic,
      String replyTo,
      String topic,
      String message,
      String body,
      String authorKind,
      String author,
      boolean terminal,
      Instant postedAt,
      RelayCausation causation) {
    public Pending {
      Objects.requireNonNull(requestId);
      if (replyProject < 1) throw new IllegalArgumentException("Reply requires a project");
      replyTopic = RelayPort.topic(replyTopic);
      replyTo = RelayPort.identity(replyTo);
      topic = BoardClosing.identifier(topic, "bdt_");
      message = BoardClosing.identifier(message, "bdm_");
      body = RelayPort.text(body);
      authorKind = RelayPort.identity(authorKind);
      author = RelayPort.identity(author);
      Objects.requireNonNull(postedAt);
    }
  }

  void bind(Binding binding);

  Optional<Pending> pending();

  /** Back off a rolled-back append, preserving its debt and per-request order. */
  void defer(Pending pending, Instant until);

  void published(Pending pending);
}
