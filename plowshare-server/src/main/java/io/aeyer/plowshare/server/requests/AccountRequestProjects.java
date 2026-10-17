package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.auth.ServiceAccountRepository;
import io.aeyer.plowshare.server.auth.ServiceCredentials;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Uses existing credential metadata, never a project's position in a token's scope list. */
@Component
public final class AccountRequestProjects implements RequestProjects {
  private final IdentityRepository identities;
  private final ServiceAccountRepository tokens;
  private final ProjectMembers members;
  private final Clock clock;
  private final io.aeyer.plowshare.server.applications.ApplicationIdentityRepository applications;

  @Autowired
  public AccountRequestProjects(
      IdentityRepository identities,
      ServiceAccountRepository tokens,
      ProjectMembers members,
      io.aeyer.plowshare.server.applications.ApplicationIdentityRepository applications) {
    this(identities, tokens, members, applications, Clock.systemUTC());
  }

  public AccountRequestProjects(
      IdentityRepository identities,
      ServiceAccountRepository tokens,
      ProjectMembers members,
      io.aeyer.plowshare.server.applications.ApplicationIdentityRepository applications,
      Clock clock) {
    this.identities = Objects.requireNonNull(identities);
    this.tokens = Objects.requireNonNull(tokens);
    this.members = Objects.requireNonNull(members);
    this.clock = Objects.requireNonNull(clock);
    this.applications = Objects.requireNonNull(applications);
  }

  @Override
  public Optional<String> implicit(String account) {
    if (account == null || account.isBlank()) throw refused();
    var identity = identities.account(account);
    if (identity.kind() == IdentityRepository.AccountKind.USER) return Optional.empty();
    if (identity.kind() != IdentityRepository.AccountKind.SERVICE_TOKEN
        || !ServiceCredentials.principal(account)) throw refused();
    UUID id;
    try {
      String text = account.substring("@service/".length());
      id = UUID.fromString(text);
      if (!id.toString().equals(text)) throw refused();
    } catch (IllegalArgumentException invalid) {
      throw refused();
    }
    String owner = members.authorityAccount(account).orElseThrow(AccountRequestProjects::refused);
    var token = tokens.token(owner, id, false);
    if (!tokens.applicationOwned(id)
        || !token.id().equals(id)
        || !token.principal().equals(account)
        || token.revokedAt() != null
        || !token.expiresAt().toInstant().isAfter(clock.instant())
        || token.scopes().size() != 1) throw refused();
    String project = token.scopes().getFirst().project();
    var application =
        applications.find(project, false).orElseThrow(AccountRequestProjects::refused);
    if (!application.project().equals(project)
        || !application.tokenId().equals(id)
        || !application.principal().equals(account)
        || application.state() != io.aeyer.plowshare.protocol.ApplicationIdentity.State.ACTIVE
        || !application.credentialIssued()
        || application.revokedAt() != null
        || !application.expiresAt().toInstant().isAfter(clock.instant())) throw refused();
    if (!(identities.project(project) instanceof AuthorityScope.Project)
        || members.role(project, account).isEmpty()) throw refused();
    return Optional.of(project);
  }

  private static CallerFault refused() {
    return new CallerFault(
        "Supply an explicit project; no unambiguous active Application context is available");
  }
}
