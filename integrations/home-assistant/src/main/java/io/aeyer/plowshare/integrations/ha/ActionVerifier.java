package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.ha.ActionExecutor.Receipt;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadBatch;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Revision;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Separate observation step. Matching a fresh state predicate never proves action causation. */
public interface ActionVerifier {
  record Request(Receipt receipt, Revision revision, Map<Reference, String> expectedStates) {
    public Request {
      Objects.requireNonNull(receipt);
      Objects.requireNonNull(revision);
      expectedStates = Map.copyOf(expectedStates);
      if (!receipt.binding().equals(revision.binding())
          || expectedStates.isEmpty()
          || expectedStates.size() > 20)
        throw new IllegalArgumentException("Explicit bounded verification selection required");
      expectedStates.forEach(
          (reference, state) -> {
            if (!reference.binding().equals(receipt.binding())
                || state == null
                || state.length() > 4096
                || state.indexOf('\0') >= 0)
              throw new IllegalArgumentException("Invalid verification predicate");
          });
    }
  }

  enum Predicate {
    MATCHED,
    NOT_MATCHED,
    UNAVAILABLE
  }

  /** The original receipt/context is correlation evidence only, never proof of physical cause. */
  record Verification(Receipt receipt, ReadBatch observation, Predicate predicate) {
    public Verification {
      Objects.requireNonNull(receipt);
      Objects.requireNonNull(observation);
      Objects.requireNonNull(predicate);
    }
  }

  Verification verify(Request request, OperationBudget budget) throws IOException;
}
