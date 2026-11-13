package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayCausation;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persistence of exact run sources, outbound native ownership and retained return receipts. */
public interface MessageExportRepository extends NativeExecutionSources {
  record Binding(
      UUID sourceEvent,
      long projectId,
      MessageExportProperties.Policy policy,
      UUID returnGeneration,
      NativeMessageExports.Export nativeRequest,
      RelayCausation causation) {
    public Binding {
      Objects.requireNonNull(sourceEvent);
      Objects.requireNonNull(policy);
      Objects.requireNonNull(returnGeneration);
      Objects.requireNonNull(nativeRequest);
      Objects.requireNonNull(causation);
      if (projectId < 1
          || causation.depth() < 1
          || !policy.project().equals(nativeRequest.project()))
        throw new IllegalArgumentException("Export binding differs from its native project");
    }
  }

  void bind(Binding binding);

  Optional<Binding> binding(UUID sourceEvent, boolean lock);

  /** Serializes canonical return UUID reuse before native mailbox/request locks. */
  void lockReturn(UUID requestId);

  Optional<MessageExports.Receipt> receipt(MessageExports.Command command);

  void save(
      MessageExports.Command command, MessageExports.Receipt receipt, RelayCausation causation);
}
