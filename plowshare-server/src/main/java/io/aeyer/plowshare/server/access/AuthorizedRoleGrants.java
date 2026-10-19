package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Domain authorization plus repository atomic lineage checks; never trusts a claimed role. */
@Component
public final class AuthorizedRoleGrants implements ScopedRoleGrants {
  private final ScopedGrantRepository grants;
  private final ScopedAuthority authority;
  private final IdentityRepository identities;

  public AuthorizedRoleGrants(
      ScopedGrantRepository grants, ScopedAuthority authority, IdentityRepository identities) {
    this.grants = grants;
    this.authority = authority;
    this.identities = identities;
  }

  @Override
  public ScopedGrantRepository.Binding issue(ScopedGrantRepository.Issue request) {
    require(request.issuer(), request.scope(), request.authority());
    return grants.issue(request);
  }

  @Override
  public ScopedGrantRepository.Binding revoke(
      UUID id, long version, GrantRecipient issuer, UUID issuing) {
    var existing = grants.find(id).orElseThrow(() -> new CallerFault("No grant has that identity"));
    require(issuer, existing.scope(), issuing);
    return grants.revoke(id, version, issuer, issuing);
  }

  private void require(GrantRecipient issuer, AuthorityIds.ScopeId scope, UUID grant) {
    if (!authority.allowsUsing(
        issuer, identities.scope(scope), AuthorityAction.MANAGE_ACCESS, grant))
      throw new CallerFault("This principal cannot manage access to that scope");
  }
}
