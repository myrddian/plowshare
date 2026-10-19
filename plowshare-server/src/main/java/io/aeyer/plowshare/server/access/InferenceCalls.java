package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.llm.dispatch.CallAuthorization;
import java.util.UUID;

/** Converts a successful existing allowance claim into retained inference evidence. */
public interface InferenceCalls {
  CallAuthorization claimed(OperationAuthority.Admission operation, Budget.Claim claim);

  CallAuthorization claimed(UUID operation, Budget.Claim claim, CallAuthorization authority);
}
