package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Binds an already claimed allowance to actual execution authority, never to accounting metadata.
 */
@Component
public final class OperationInference implements InferenceCalls {
  private final OperationAuthority operations;
  private final InferenceAdmissions records;

  public OperationInference(OperationAuthority operations, InferenceAdmissions records) {
    this.operations = Objects.requireNonNull(operations);
    this.records = Objects.requireNonNull(records);
  }

  public CallAuthorization claimed(OperationAuthority.Admission operation, Budget.Claim claim) {
    if (!operation.actions().contains(AuthorityAction.RUN_MODEL))
      throw new CallerFault("RUN_MODEL is required");
    return claimed(operation.id(), claim, () -> operations.require(operation));
  }

  /** Internal callers must supply the declared purpose's retained authority and validity check. */
  public CallAuthorization claimed(
      UUID operation, Budget.Claim claim, CallAuthorization authority) {
    Objects.requireNonNull(claim);
    Objects.requireNonNull(authority);
    authority.require();
    var binding =
        new InferenceBinding(
            UUID.randomUUID(),
            operation,
            claim.budget(),
            claim.ordinal(),
            claim.limit(),
            claim.conversation());
    records.retain(binding);
    return new CallAuthorization() {
      @Override
      public void require() {
        authority.require();
      }

      @Override
      public InferenceBinding binding() {
        return binding;
      }
    };
  }
}
