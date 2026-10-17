package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Global is a destination, never an authenticated actor or an authority bypass. */
@Component
public final class LiveAccountKnowledgePermissions implements AccountKnowledgePermissions {
  private final IdentityRepository identities;
  private final ScopedGrantRepository grants;
  private final ScopedAuthority authority;

  public LiveAccountKnowledgePermissions(
      IdentityRepository identities, ScopedGrantRepository grants, ScopedAuthority authority) {
    this.identities = Objects.requireNonNull(identities);
    this.grants = Objects.requireNonNull(grants);
    this.authority = Objects.requireNonNull(authority);
  }

  public boolean allows(String actor, AccountId account, AuthorityAction action) {
    var identity = identities.account(actor);
    var scope = identities.knowledge(account);
    var subject = GrantRecipient.account(identity.id());
    // Public readers need no content-write grant. Resource audiences remain an additional SQL gate.
    if (action == AuthorityAction.READ) return authority.allows(subject, scope, action);
    if (identity.kind() != IdentityRepository.AccountKind.USER) return false;
    return grants.live(subject).stream()
        .anyMatch(
            grant ->
                grant.role() == StandardRole.ADMIN
                    && grant.scope().equals(scope.id())
                    && grant.coverage() == ScopedGrantRepository.Coverage.SELF
                    && authority.allowsUsing(subject, scope, action, grant.id()));
  }
}
