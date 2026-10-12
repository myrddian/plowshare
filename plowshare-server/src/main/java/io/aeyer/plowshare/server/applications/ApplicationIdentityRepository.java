package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.protocol.ApplicationIdentity.*;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns reservation uniqueness, project binding and one-time issuance receipts. Locks join the
 * caller transaction.
 */
public interface ApplicationIdentityRepository {
  record IssueRecord(String actor, Issue request, Receipt receipt) {}

  Optional<Binding> find(String project, boolean lock);

  void reserve(String project, Declaration service, UUID tokenId, String actor);

  /** Sets the exact project row once; refuses deletion/recreation or a different declaration. */
  void activate(Binding identity);

  Optional<IssueRecord> receipt(UUID requestId);

  void issued(String actor, Issue request, Receipt receipt, String digest);
}
