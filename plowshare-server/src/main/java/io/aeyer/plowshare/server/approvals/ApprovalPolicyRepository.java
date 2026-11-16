package io.aeyer.plowshare.server.approvals;

/**
 * Durable account policy. Missing policy reads as revision-zero Manual. Saves compare the expected
 * revision and publish a complete replacement atomically. Failure never returns a successful save.
 * Callers authorize owner access; repository implementations validate owner and policy invariants.
 */
public interface ApprovalPolicyRepository {
  ApprovalPolicy read(String owner);

  ApprovalPolicy save(String owner, ApprovalPolicy expected);
}
