package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.relay.Relay;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Messaging-owned immutable ingress receipts, pinned return bindings and bounded reply history. */
public interface MessageRelayRepository {
  record Family(
      String namespace,
      String account,
      String project,
      long projectId,
      String publisher,
      String sourceTopic,
      String subscriber,
      UUID sourceGeneration,
      String channel,
      String executionDefinition,
      String agent,
      String replyTopic,
      UUID replyGeneration) {
    public Family {
      for (String value :
          List.of(namespace, account, project, publisher, subscriber, channel, agent))
        RelayPort.identity(value);
      RelayPort.topic(sourceTopic);
      RelayPort.topic(replyTopic);
      if (executionDefinition != null) RelayPort.identity(executionDefinition);
      if (projectId < 1) throw new IllegalArgumentException("Invalid native source project");
      Objects.requireNonNull(sourceGeneration);
      Objects.requireNonNull(replyGeneration);
    }

    public Relay.TopicKey replies() {
      return new Relay.TopicKey(projectId, replyTopic);
    }
  }

  record Intent(
      UUID delivery,
      Family family,
      String client,
      UUID requestId,
      String sourceEvent,
      String receiver,
      String fingerprint,
      RelayCausation causation,
      Instant occurredAt) {
    public Intent {
      Objects.requireNonNull(delivery);
      Objects.requireNonNull(family);
      client = MessageIngress.client(client);
      Objects.requireNonNull(requestId);
      sourceEvent = RelayPort.identity(sourceEvent);
      if (!List.of("message.receive", "message.cancel", "message.inspect").contains(receiver)
          || fingerprint == null
          || !fingerprint.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid native operation identity");
      Objects.requireNonNull(occurredAt);
    }
  }

  record Operation(
      String identity,
      Family family,
      MessageRelayPayload.Snapshot snapshot,
      Instant occurredAt,
      RelayCausation causation) {
    public Operation {
      identity = RelayPort.identity(identity);
      Objects.requireNonNull(family);
      Objects.requireNonNull(snapshot);
      Objects.requireNonNull(occurredAt);
    }
  }

  record Binding(
      Family family,
      UUID task,
      String client,
      UUID requestId,
      String sourceEvent,
      String cursor,
      long revision,
      RelayCausation causation) {
    public Binding {
      Objects.requireNonNull(family);
      Objects.requireNonNull(task);
      client = MessageIngress.client(client);
      Objects.requireNonNull(requestId);
      sourceEvent = RelayPort.identity(sourceEvent);
      if (revision < 1 || !MessageRelayPayload.Cursor.read(cursor).task().equals(task))
        throw new IllegalArgumentException("Invalid native notification progress");
    }
  }

  record Chunk(
      long sequence,
      String message,
      String body,
      int length,
      boolean finalReply,
      boolean generated,
      String ending,
      Instant postedAt) {
    public Chunk {
      message = MessagePayload.identifier(message, "bdm_");
      Objects.requireNonNull(body);
      int points = body.codePointCount(0, body.length());
      if (sequence < 1
          || length < 1
          || points < 1
          || points > length
          || points > MessageRelayPayload.CHUNK_CODEPOINTS)
        throw new IllegalArgumentException("Invalid retained native reply slice");
      if (ending != null) Outcome.Ending.valueOf(ending);
      Objects.requireNonNull(postedAt);
    }
  }

  final class Conflict extends RuntimeException {
    public Conflict() {
      super("This native request identity names different retained work");
    }
  }

  /**
   * Family pins are immutable; edited targets/return generations require a new explicit channel.
   */
  void retainFamily(Family family);

  /**
   * Explicit operator-approved exact scope transfer; identifiers and source evidence are retained.
   */
  void migrate(Family family, String client);

  Optional<MessageRelayPayload.Snapshot> prior(Intent intent);

  Optional<MessageRelayPayload.Snapshot> receipt(UUID delivery);

  Optional<MessageRelayPayload.Snapshot> receipt(
      UUID delivery, long project, String publisher, String topic, String event);

  void retain(Intent intent, MessageRelayPayload.Snapshot snapshot);

  void bind(Intent intent, UUID task, String cursor, long revision);

  Optional<UUID> task(Family family, String client, UUID task, UUID originalRequest);

  long head(String message);

  /**
   * At most two rows and bounded codepoint slices. Offset applies only to the selected sequence.
   */
  List<Chunk> chunks(String message, long through, long sequence, int offset);

  /** Worker transaction takes the task revision row before selecting its current binding. */
  Optional<Binding> nextTask();

  void update(Binding binding, MessageRelayPayload.Snapshot snapshot);

  void deferTask(Binding binding);

  Optional<Operation> pending();

  enum TaskFence {
    LOCKED,
    NOT_REQUIRED,
    UNAVAILABLE
  }

  /**
   * Locks a positive notice's scoped task before broker append. This preserves native task→topic
   * ordering and fences terminal binding cleanup against revision-trigger insertion.
   */
  TaskFence lockTask(Operation operation);

  void published(Operation operation);

  void unavailable(Operation operation, String reason);

  void defer(Operation operation);
}
