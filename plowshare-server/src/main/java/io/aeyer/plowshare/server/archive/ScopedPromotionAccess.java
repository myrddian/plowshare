package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Checks the two existing sides of a release without creating new promotion outcomes. */
@Component
public final class ScopedPromotionAccess implements PromotionAccess {
  private final PromotionSourceRepository sources;
  private final ProjectMembers members;
  private final IdentityRepository identities;
  private final ScopedAuthority authority;
  private final ScopedGrantRepository grants;

  public ScopedPromotionAccess(
      PromotionSourceRepository sources,
      ProjectMembers members,
      IdentityRepository identities,
      ScopedAuthority authority,
      ScopedGrantRepository grants) {
    this.sources = Objects.requireNonNull(sources);
    this.members = Objects.requireNonNull(members);
    this.identities = Objects.requireNonNull(identities);
    this.authority = Objects.requireNonNull(authority);
    this.grants = Objects.requireNonNull(grants);
  }

  @Override
  public void require(String account, String proposal) {
    if (account == null || account.isBlank()) throw denied();
    var actor = identities.account(account);
    if (actor.kind() != IdentityRepository.AccountKind.USER) throw denied();
    var source = sources.source(proposal).orElseThrow(ScopedPromotionAccess::denied);
    if (!source.publishable()
        || !identities.project(source.project()).id().equals(source.home())
        || members
            .role(source.project(), account)
            .filter(role -> role == ProjectRole.MANAGER)
            .isEmpty()) throw denied();
    var global = identities.knowledge(BuiltInIdentities.GLOBAL);
    var principal = GrantRecipient.account(actor.id());
    // Require the live human ADMIN binding itself. A MANAGER action bundle on a different
    // account-knowledge scope (or two unrelated grants) cannot authorize Global publication.
    boolean destination =
        grants.live(principal).stream()
            .anyMatch(
                grant ->
                    grant.role() == StandardRole.ADMIN
                        && grant.scope().equals(global.id())
                        && grant.coverage() == ScopedGrantRepository.Coverage.SELF
                        && authority.allowsUsing(
                            principal, global, AuthorityAction.WRITE, grant.id())
                        && authority.allowsUsing(
                            principal, global, AuthorityAction.MANAGE_SCOPE, grant.id()));
    if (!destination) throw denied();
  }

  private static CallerFault denied() {
    return new CallerFault(
        "Promotion requires source MANAGER release and human Global ADMIN destination authority");
  }
}
