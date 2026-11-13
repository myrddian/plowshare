package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.board.BoardBridge;
import io.aeyer.plowshare.server.board.BoardNativeRequests;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Retained forum requester proof; public forum stores do not inspect native messaging tables. */
public interface BoardNativeExportRepository {
  record Binding(
      UUID sourceEvent,
      long projectId,
      BoardMessageExportProperties.Policy policy,
      String definition,
      NativeMessageExports.Export nativeRequest,
      RelayCausation causation) {
    public Binding {
      Objects.requireNonNull(sourceEvent);
      Objects.requireNonNull(policy);
      Objects.requireNonNull(nativeRequest);
      Objects.requireNonNull(causation);
      definition = RelayPort.identity(definition);
      if (projectId < 1
          || causation.depth() < 1
          || !policy.project().equals(nativeRequest.project()))
        throw new IllegalArgumentException("Invalid native board export binding");
    }
  }

  record Outcome(UUID delivery, UUID returnId, Binding binding) {}

  enum OutcomeState {
    PENDING,
    PUBLISHED,
    UNAVAILABLE
  }

  record Evidence(
      UUID delivery,
      UUID sourceEvent,
      UUID returnId,
      OutcomeState state,
      String code,
      Optional<String> nativeMessage) {}

  Optional<Evidence> outcome(UUID delivery, String account);

  void bind(Binding binding);

  BoardNativeRequests.Refusal refuse(UUID delivery, BoardBridge.Agent actor);

  boolean refused(BoardBridge.Agent actor);

  boolean accepted(BoardBridge.Agent actor);

  Optional<Outcome> pendingOutcome();

  void published(Outcome outcome, String message);

  void unavailable(Outcome outcome);

  void defer(Outcome outcome, Instant until);

  Optional<Binding> binding(UUID sourceEvent);
}
