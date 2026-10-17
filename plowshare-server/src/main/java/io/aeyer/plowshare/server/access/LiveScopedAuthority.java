package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Reads live lineage on every decision; failures never turn into permission or a cached allow. */
@Component
public final class LiveScopedAuthority implements ScopedAuthority {
  private final ScopedGrantRepository grants;
  private final ApplicationPolicy applications;
  private final GrantLineage lineage;
  private final IdentityRepository identities;

  public LiveScopedAuthority(
      ScopedGrantRepository grants,
      ApplicationPolicy applications,
      GrantLineage lineage,
      IdentityRepository identities) {
    this.grants = Objects.requireNonNull(grants);
    this.applications = Objects.requireNonNull(applications);
    this.lineage = Objects.requireNonNull(lineage);
    this.identities = Objects.requireNonNull(identities);
  }

  @Override
  public boolean allows(GrantRecipient recipient, AuthorityScope target, AuthorityAction action) {
    Objects.requireNonNull(recipient);
    Objects.requireNonNull(target);
    Objects.requireNonNull(action);
    if (!target.equals(identities.scope(target.id()))) return false;
    return grants.live(recipient).stream()
        .anyMatch(
            binding ->
                binding.recipient().equals(recipient)
                    && binding.actions().contains(action)
                    && covers(binding, target, action)
                    && applicationAllows(binding, action)
                    && lineage.valid(binding.id()));
  }

  @Override
  public boolean allowsUsing(
      GrantRecipient recipient,
      AuthorityScope target,
      AuthorityAction action,
      java.util.UUID grant) {
    Objects.requireNonNull(recipient);
    Objects.requireNonNull(target);
    Objects.requireNonNull(action);
    Objects.requireNonNull(grant);
    if (!target.equals(identities.scope(target.id()))) return false;
    return grants.live(recipient).stream()
        .filter(binding -> binding.id().equals(grant))
        .anyMatch(
            binding ->
                binding.recipient().equals(recipient)
                    && binding.actions().contains(action)
                    && covers(binding, target, action)
                    && applicationAllows(binding, action)
                    && lineage.valid(binding.id()));
  }

  private boolean applicationAllows(ScopedGrantRepository.Binding binding, AuthorityAction action) {
    if (binding.project().isEmpty()
        || binding.role() == StandardRole.ADMIN
        || binding.role() == StandardRole.SYSTEM) return true;
    ProjectRole stored =
        switch (binding.role()) {
          case MANAGER -> ProjectRole.MANAGER;
          case CONTRIBUTOR -> ProjectRole.CONTRIBUTOR;
          case READER -> ProjectRole.VIEWER;
          default -> throw new IllegalStateException("Unsupported project role");
        };
    return applications
        .read(binding.project().orElseThrow())
        .limit(binding.recipientHandle(), java.util.Optional.of(stored))
        .filter(
            role ->
                switch (action) {
                  case READ -> true;
                  case WRITE, CONVERSATION_CREATE, RUN_MODEL -> role != ProjectRole.VIEWER;
                  case MANAGE_ACCESS, MANAGE_SCOPE, SET_AUDIENCE -> role == ProjectRole.MANAGER;
                  case ADMINISTER -> false;
                })
        .isPresent();
  }

  private static boolean covers(
      ScopedGrantRepository.Binding binding, AuthorityScope target, AuthorityAction action) {
    return switch (binding.coverage()) {
      case SELF -> binding.scope().equals(target.id());
      case ALL_SCOPES -> true; // Only immutable intrinsic root policy may hold this coverage.
      case ORDINARY_PROJECT_CONTROL ->
          (binding.scope().equals(target.id()) || target instanceof AuthorityScope.Project)
              && switch (action) {
                case ADMINISTER, MANAGE_ACCESS, MANAGE_SCOPE, SET_AUDIENCE -> true;
                case READ, WRITE, CONVERSATION_CREATE, RUN_MODEL -> false;
              };
    };
  }
}
