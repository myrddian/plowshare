package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.protocol.ApplicationIdentity.State;
import io.aeyer.plowshare.server.access.AuthorityAction;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.GrantRecipient;
import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.access.ScopedAuthority;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.time.Clock;
import java.util.Objects;

/**
 * Acting-token policy. Every call rereads live authority; no decision or membership is cached. The
 * composition root supplies the persistent policy source. Request admission and runtime propagation
 * must explicitly use this capability before they can support acting authority.
 */
public final class LiveActingAuthorization implements ActingAuthorization {
  private final ActingPolicySource source;
  private final ProjectMembers members;
  private final Clock clock;
  private final IdentityRepository identities;
  private final ScopedAuthority actions;

  public LiveActingAuthorization(
      ActingPolicySource source,
      ProjectMembers members,
      Clock clock,
      IdentityRepository identities,
      ScopedAuthority actions) {
    this.source = Objects.requireNonNull(source);
    this.members = Objects.requireNonNull(members);
    this.clock = Objects.requireNonNull(clock);
    this.identities = Objects.requireNonNull(identities);
    this.actions = Objects.requireNonNull(actions);
  }

  @Override
  public ProjectRole require(
      RequestAuthority.Delegated authority, ActingPermission permission, ActingTarget target) {
    Objects.requireNonNull(authority);
    Objects.requireNonNull(permission);
    Objects.requireNonNull(target);
    ProjectRole required = required(permission);
    AuthorityAction action = action(permission);
    if (!source.enabled()) throw refused();
    var now = clock.instant();
    var delegation = authority.delegation();
    var application = source.application(delegation.application()).orElseThrow(this::refused);
    if (!application.project().equals(delegation.application())
        || !application.principal().equals(authority.actor())
        || application.state() != State.ACTIVE
        || !application.credentialIssued()
        || application.revokedAt() != null
        || !application.expiresAt().toInstant().isAfter(now)
        || !source.accountActive(authority.actor())
        || !source.subjectActive(authority.subject())) throw refused();

    var grant =
        source.grant(delegation.application(), delegation.grant()).orElseThrow(this::refused);
    if (!grant.application().equals(delegation.application())
        || !grant.name().equals(delegation.grant())
        || !grant.proofs().contains(ActingPolicySource.ProofKind.ACTING_TOKEN)
        || !source.eligible(delegation.application(), delegation.grant(), authority.subject())) {
      throw refused();
    }
    var token = source.token(delegation.proof()).orElseThrow(this::refused);
    if (!token.id().equals(delegation.proof())
        || !token.application().equals(delegation.application())
        || !token.actor().equals(authority.actor())
        || !token.grant().equals(delegation.grant())
        || !token.subject().equals(authority.subject())
        || token.revoked()
        || !token.expiresAt().isAfter(now)) throw refused();

    ProjectRole effective =
        narrow(grant.ceiling(), narrow(token.scope().ceiling(), delegation.scope().ceiling()));
    switch (target) {
      case ActingTarget.Project project -> {
        if (!token.scope().projects().contains(project.name())
            || !delegation.scope().projects().contains(project.name())) throw refused();
        effective = narrow(effective, role(project.name(), authority.subject()));
        effective = narrow(effective, role(project.name(), authority.actor()));
        var scope = identities.project(project.name());
        if (!(scope instanceof AuthorityScope.Project)) throw refused();
        requireAction(authority.subject(), scope, action);
        requireAction(authority.actor(), scope, action);
      }
      case ActingTarget.Personal personal -> {
        if (!personal.owner().equals(authority.subject())
            || !grant.personal()
            || !token.scope().personal()
            || !delegation.scope().personal()) throw refused();
        // Personal belongs only to the subject. Requiring actor membership here would either
        // prevent delegation or widen the Application into a human's private space.
        effective =
            narrow(effective, role(PersonalSpaces.name(personal.owner()), authority.subject()));
        var subject = identities.account(authority.subject());
        var scope = identities.project(PersonalSpaces.name(personal.owner()));
        if (!(scope instanceof AuthorityScope.Personal own) || !own.account().equals(subject.id()))
          throw refused();
        requireAction(authority.subject(), scope, action);
      }
    }
    if (!effective.allows(required)) throw refused();
    return effective;
  }

  private ProjectRole role(String project, String account) {
    return members.role(project, account).orElseThrow(this::refused);
  }

  private void requireAction(String principal, AuthorityScope scope, AuthorityAction action) {
    // Service-token authentication and its current role ceiling were checked above. The
    // supporting account grant is an additional intersection, never a Personal actor grant.
    String account = members.authorityAccount(principal).orElseThrow(this::refused);
    if (!actions.allows(GrantRecipient.account(identities.account(account).id()), scope, action))
      throw refused();
  }

  private static ProjectRole narrow(ProjectRole left, ProjectRole right) {
    return left.allows(right) ? right : left;
  }

  private ProjectRole required(ActingPermission permission) {
    return switch (permission) {
      case READ -> ProjectRole.VIEWER;
      case WORK, CONVERSATION_CREATE, RUN_MODEL -> ProjectRole.CONTRIBUTOR;
      default -> throw refused();
    };
  }

  private AuthorityAction action(ActingPermission permission) {
    return switch (permission) {
      case READ -> AuthorityAction.READ;
      case WORK -> AuthorityAction.WRITE;
      case CONVERSATION_CREATE -> AuthorityAction.CONVERSATION_CREATE;
      case RUN_MODEL -> AuthorityAction.RUN_MODEL;
      default -> throw refused();
    };
  }

  private CallerFault refused() {
    // Do not disclose whether another subject's token, eligibility or resource exists.
    return new CallerFault("This delegated request is outside its current acting authority");
  }
}
