package io.aeyer.plowshare.server.access;

import java.util.UUID;

/** Internal authorized role-management seam; no transport or client surface is added. */
public interface ScopedRoleGrants {
  ScopedGrantRepository.Binding issue(ScopedGrantRepository.Issue request);

  ScopedGrantRepository.Binding revoke(
      UUID id, long version, GrantRecipient issuer, UUID authority);
}
