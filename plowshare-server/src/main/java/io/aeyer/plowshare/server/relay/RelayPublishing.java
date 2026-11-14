package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.access.OperationFence;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Granted TEXT publication from an authenticated execution, never a new independent root. */
public interface RelayPublishing {
  /**
   * Publishes once for the harness call identity. A repeated identity returns its original receipt
   * even after broker expiry; changed work is refused. Infrastructure failures propagate without
   * automatic replay. Project, session and attribution come from the harness, not tool arguments.
   */
  default Receipt publish(String project, Execution execution, Request request) {
    return publish(project, execution, request, OperationFence.none());
  }

  /**
   * Publishes as {@link #publish(String, Execution, Request)}, and runs {@code fence} inside the
   * publication transaction before anything is appended. A fence that throws rolls the whole
   * publication back, including its receipt; the refusal propagates unchanged.
   */
  Receipt publish(String project, Execution execution, Request request, OperationFence fence);

  /**
   * Distinguishes independently authored agent and orchestration definitions with the same name.
   */
  enum DefinitionKind {
    AGENT,
    ORCHESTRATION
  }

  /** Harness-owned coordinates, including the definition registry that supplied the tool grant. */
  record Execution(UsageAttribution owner, String session, String call, DefinitionKind kind) {
    public Execution {
      Objects.requireNonNull(owner);
      Objects.requireNonNull(kind);
      RelayPort.identity(call);
    }
  }

  /** Current tool grant in the correct caller-scoped definition registry. */
  @FunctionalInterface
  interface Definitions {
    boolean permits(long project, Execution execution);
  }

  /** The entire model/script input contract; text is preserved verbatim. */
  record Request(String topic, String text) {
    public Request {
      RelayPort.topic(topic);
      RelayPort.text(text);
    }
  }

  /** A durable broker acceptance receipt, not evidence of downstream execution. */
  record Receipt(UUID eventId, String topic, String position, Instant publishedAt) {
    public Receipt {
      Objects.requireNonNull(eventId);
      RelayPort.topic(topic);
      if (RelayPort.decimal(position) < 1) throw new IllegalArgumentException("Invalid position");
      Objects.requireNonNull(publishedAt);
    }
  }
}
