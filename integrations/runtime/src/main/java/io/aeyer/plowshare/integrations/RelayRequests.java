package io.aeyer.plowshare.integrations;

import io.aeyer.plowshare.protocol.IntegrationPayload;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Consumer-owned identities; no body supplies execution authority or a return destination. */
public final class RelayRequests {
  private RelayRequests() {}

  public record Policy(
      String requests,
      UUID requestGeneration,
      String returns,
      UUID returnGeneration,
      String channel,
      String publisher,
      Set<String> producers,
      int maxDepth,
      int maxReturnBytes) {
    public Policy {
      requests = RelayPort.topic(requests);
      returns = RelayPort.topic(returns);
      Objects.requireNonNull(requestGeneration);
      Objects.requireNonNull(returnGeneration);
      channel = RelayPort.identity(channel);
      publisher = RelayPort.identity(publisher);
      producers = Set.copyOf(producers);
      producers.forEach(RelayPort::identity);
      if (requests.equals(returns)
          || !channel.matches("[a-z][a-z0-9_.-]{0,63}")
          || !publisher.matches("sdk:[a-f0-9]{64}")
          || producers.isEmpty()
          || producers.size() > 64
          || maxDepth < 1
          || maxDepth > 32
          || maxReturnBytes < 1024
          || maxReturnBytes > Json.MAX_MESSAGE)
        throw new IllegalArgumentException("Invalid integration Relay policy");
    }
  }

  public record Checkpoint(String fingerprint, String position) {
    public Checkpoint {
      IntegrationContracts.identity(fingerprint, 1024);
      RelayRequests.position(position);
    }
  }

  /** Immutable authenticated log evidence, captured before advancing the owning binding cursor. */
  public record Source(
      String project,
      String topic,
      UUID generation,
      UUID event,
      String position,
      String publisher,
      Instant occurredAt,
      String text,
      RelayCausation causation) {
    public Source {
      project = RelayPort.identity(project);
      topic = RelayPort.topic(topic);
      Objects.requireNonNull(generation);
      Objects.requireNonNull(event);
      RelayRequests.position(position);
      publisher = RelayPort.identity(publisher);
      Objects.requireNonNull(occurredAt);
      text = RelayPort.text(text);
      if (text.getBytes(StandardCharsets.UTF_8).length > Json.MAX_MESSAGE)
        throw new IllegalArgumentException("Integration request exceeds its text limit");
    }
  }

  public record Delivery(
      Source source,
      IntegrationPayload.Request request,
      RelayPort.Publish publication,
      String returnAfter) {
    public Delivery {
      Objects.requireNonNull(source);
      if ((publication == null) != (returnAfter == null))
        throw new IllegalArgumentException("Return publication requires its retained log boundary");
      if (returnAfter != null) RelayRequests.position(returnAfter);
      if (publication != null
          && (!publication.project().equals(source.project())
              || publication.parentTopic() != null
              || publication.parentEventId() != null
              || !Objects.equals(publication.correlationId(), source.event().toString())))
        throw new IllegalArgumentException("Integration result differs from its retained source");
    }
  }

  static void position(String value) {
    if (value == null || !value.matches("0|[1-9][0-9]{0,18}"))
      throw new IllegalArgumentException("Invalid integration log position");
    Long.parseLong(value);
  }
}
