package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
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
    // Universal role eligibility is not an issuable grant. It can admit knowledge READ, but
    // cannot support allowsUsing, grant issuance, project membership or execution authority.
    if (action == AuthorityAction.READ
        && target.id().equals(BuiltInIdentities.GLOBAL_KNOWLEDGE)
        && recipient.agent().isEmpty()
        && grants.globalReadEligible(recipient.account())) return true;
    return grants.live(recipient).stream()
        .anyMatch(
            binding ->
                binding.recipient().equals(recipient)
                    && binding.actions().contains(action)
                    && covers(binding, target, action)
                    && applicationAllows(binding, action, applications::read)
                    && lineage.valid(binding.id()));
  }

  @Override
  public boolean allowsUsing(
      GrantRecipient recipient, AuthorityScope target, AuthorityAction action, UUID grant) {
    Objects.requireNonNull(recipient);
    Objects.requireNonNull(target);
    Objects.requireNonNull(action);
    Objects.requireNonNull(grant);
    // The scope check precedes the grant read so an unknown target costs no grant query.
    if (!target.equals(identities.scope(target.id()))) return false;
    return new LiveWalk(recipient, grants.live(recipient)).usable(target, action, grant);
  }

  @Override
  public boolean allowsUsing(
      GrantRecipient recipient,
      AuthorityScope target,
      AuthorityAction action,
      UUID grant,
      List<ScopedGrantRepository.Binding> live) {
    return walk(recipient, live).allows(target, action, grant);
  }

  @Override
  public Walk walk(GrantRecipient recipient, List<ScopedGrantRepository.Binding> live) {
    return new LiveWalk(Objects.requireNonNull(recipient), Objects.requireNonNull(live));
  }

  /**
   * Per-walk memo of lineage validity per grant and Application boundary per project. Both are pure
   * functions of the stored rows and the deployed manifest for the duration of one decision, so
   * repeating them per support item buys nothing. The maps are fields of this short-lived object
   * only; nothing is retained on the authority, and a failure is never memoised because {@code
   * computeIfAbsent} stores nothing when the loader throws.
   */
  private final class LiveWalk implements Walk {
    private final GrantRecipient recipient;
    private final List<ScopedGrantRepository.Binding> live;
    private final Map<UUID, Boolean> lineages = new HashMap<>();
    private final Map<String, ApplicationPolicy.Boundary> boundaries = new HashMap<>();

    LiveWalk(GrantRecipient recipient, List<ScopedGrantRepository.Binding> live) {
      this.recipient = recipient;
      this.live = live;
    }

    @Override
    public boolean allows(AuthorityScope target, AuthorityAction action, UUID grant) {
      Objects.requireNonNull(target);
      Objects.requireNonNull(action);
      Objects.requireNonNull(grant);
      if (!target.equals(identities.scope(target.id()))) return false;
      return usable(target, action, grant);
    }

    boolean usable(AuthorityScope target, AuthorityAction action, UUID grant) {
      return live.stream()
          .filter(binding -> binding.id().equals(grant))
          .anyMatch(
              binding ->
                  binding.recipient().equals(recipient)
                      && binding.actions().contains(action)
                      && covers(binding, target, action)
                      && applicationAllows(binding, action, this::boundary)
                      && lineages.computeIfAbsent(binding.id(), lineage::valid));
    }

    private ApplicationPolicy.Boundary boundary(String project) {
      return boundaries.computeIfAbsent(project, applications::read);
    }
  }

  private static boolean applicationAllows(
      ScopedGrantRepository.Binding binding,
      AuthorityAction action,
      Function<String, ApplicationPolicy.Boundary> boundaries) {
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
    return boundaries
        .apply(binding.project().orElseThrow())
        .limit(binding.recipientHandle(), Optional.of(stored))
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
