package io.aeyer.plowshare.server.approvals;

import java.util.List;

/** Authenticated account-owned policy controls, independent of any Desktop or terminal process. */
public interface ApprovalPolicies {
  record Capabilities(
      List<ApprovalPolicy.Kind> operations, boolean humanHistory, boolean additionalFileAccess) {
    public Capabilities {
      operations = List.copyOf(operations);
    }
  }

  /** Explicit coverage prevents clients from advertising a mode for an unintegrated operation. */
  Capabilities capabilities();

  ApprovalPolicy read(String owner);

  /** Saves against the supplied revision. An uncertain response must be reconciled by read. */
  ApprovalPolicy save(String owner, ApprovalPolicy expected);
}
