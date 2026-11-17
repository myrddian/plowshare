package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.IntegrationContracts;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Binding;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ConfiguredAction;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.protocol.IntegrationPayload.ActionContext;
import io.aeyer.plowshare.protocol.IntegrationPayload.Parameter;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One bounded native action attempt, never a retry. The SDK provider must durably retain the
 * original invocation intent before entering this boundary. Implementations check the guard after
 * queueing and before possible transmission. They return UNKNOWN after any possible send without a
 * valid acknowledgement; cancellation does not prove the external effect stopped.
 */
public interface ActionExecutor {
  int MAX_TARGETS = 20;

  @FunctionalInterface
  interface Guard {
    /** Current local admission check; revocation cannot undo an already admitted effect. */
    void require() throws IOException;
  }

  record Request(String invocationId, ConfiguredAction action, Map<String, Parameter> parameters) {
    public Request {
      if (!UUID.fromString(invocationId).toString().equals(invocationId))
        throw new IllegalArgumentException("Canonical action invocation UUID required");
      Objects.requireNonNull(action);
      parameters = Map.copyOf(parameters);
      action.profile().validate(parameters);
      if (action.profile().targets().isEmpty() || action.profile().targets().size() > MAX_TARGETS)
        throw new IllegalArgumentException(
            "Executable profiles require 1..20 fixed entity targets");
    }
  }

  enum Delivery {
    NOT_SUBMITTED,
    ACKNOWLEDGED,
    REFUSED,
    UNKNOWN
  }

  enum Effect {
    NOT_STARTED,
    NOT_OBSERVED,
    UNKNOWN
  }

  /** Native identity is meaningful only together with its connection epoch. */
  record NativeRequest(String epoch, int id) {
    public NativeRequest {
      IntegrationContracts.identity(epoch, 256);
      if (id < 1) throw new IllegalArgumentException("Positive native request identity required");
    }
  }

  record Receipt(
      String invocationId,
      Binding binding,
      String action,
      String profileFingerprint,
      List<String> targets,
      Delivery delivery,
      Effect effect,
      Optional<NativeRequest> nativeRequest,
      Optional<ActionContext> context,
      Instant recordedAt) {
    public Receipt {
      if (!UUID.fromString(invocationId).toString().equals(invocationId))
        throw new IllegalArgumentException("Canonical action invocation UUID required");
      Objects.requireNonNull(binding);
      IntegrationContracts.identity(action, 256);
      if (profileFingerprint == null || !profileFingerprint.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Exact profile fingerprint required");
      targets = List.copyOf(targets);
      if (targets.isEmpty()
          || targets.size() > MAX_TARGETS
          || targets.stream().distinct().count() != targets.size())
        throw new IllegalArgumentException("Fixed receipt targets required");
      targets.forEach(HomeAssistantResources::haName);
      Objects.requireNonNull(delivery);
      Objects.requireNonNull(effect);
      Objects.requireNonNull(nativeRequest);
      Objects.requireNonNull(context);
      Objects.requireNonNull(recordedAt);
      if (delivery == Delivery.NOT_SUBMITTED
              && (effect != Effect.NOT_STARTED || context.isPresent())
          || delivery == Delivery.ACKNOWLEDGED
              && (effect != Effect.NOT_OBSERVED || nativeRequest.isEmpty())
          || delivery == Delivery.UNKNOWN && effect != Effect.UNKNOWN
          || delivery == Delivery.REFUSED && (effect != Effect.UNKNOWN || nativeRequest.isEmpty()))
        throw new IllegalArgumentException("Inconsistent action receipt");
    }
  }

  Receipt execute(Request request, Guard guard, OperationBudget budget) throws IOException;
}
