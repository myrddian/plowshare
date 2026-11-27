package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.RelayTopicRegistry.Source;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Discovery and binding intersect the retained execution scope and Relay's current read policy. */
final class AuthorizedGoalRelaySources implements GoalRelaySources {
  private final OperationAuthority authority;
  private final IdentityRepository identities;
  private final RelayProjectReadAccess relayAccess;
  private final RelayTopicRegistry registry;

  AuthorizedGoalRelaySources(
      OperationAuthority authority,
      IdentityRepository identities,
      RelayProjectReadAccess relayAccess,
      RelayTopicRegistry registry) {
    this.authority = Objects.requireNonNull(authority);
    this.identities = Objects.requireNonNull(identities);
    this.relayAccess = Objects.requireNonNull(relayAccess);
    this.registry = Objects.requireNonNull(registry);
  }

  @Override
  public List<Source> discover(Admission goal, Search search) {
    Objects.requireNonNull(search);
    var scope = readable(goal);
    var kind = search.kind() == null ? RelayPayload.Kind.TEXT : search.kind();
    requireSupported(kind);
    return registry.eligible(scope, search.prefix(), kind, search.after(), search.limit());
  }

  @Override
  public Source requireForBinding(Admission goal, Selection selection) {
    Objects.requireNonNull(selection);
    var key = new Relay.TopicKey(readable(goal), selection.topic());
    var source =
        registry
            .lockEligible(key, selection.generation())
            .orElseThrow(() -> new CallerFault("Relay source is no longer eligible for this goal"));
    // Taking the topic lock may wait. Check again at the write boundary before the binding can
    // use that row. The numeric scope is already pinned by the selected topic.
    requireSupported(source.kind());
    requireRead(goal);
    return source;
  }

  @Override
  public Source requireForDelivery(Admission goal, Relay.TopicKey key, UUID generation) {
    if (!readable(goal).equals(key.scope())) throw unavailable();
    var source =
        registry.eligible(key, generation).orElseThrow(AuthorizedGoalRelaySources::unavailable);
    requireSupported(source.kind());
    return source;
  }

  private static void requireSupported(RelayPayload.Kind kind) {
    if (kind != RelayPayload.Kind.TEXT && kind != RelayPayload.Kind.EMPTY)
      throw new CallerFault("Goal Agent Relay listeners accept TEXT or EMPTY sources");
  }

  private static CallerFault unavailable() {
    return new CallerFault("Relay source is no longer eligible for this goal");
  }

  private Relay.ProjectScope readable(Admission goal) {
    requireRead(goal);
    // Resolve the numeric project from the retained scope so a name reused by a new project
    // cannot retarget the lookup.
    if (!(identities.scope(goal.scope()) instanceof AuthorityScope.Project project))
      throw new CallerFault("Relay source is unavailable to this goal");
    return new Relay.ProjectScope(project.projectId());
  }

  private void requireRead(Admission goal) {
    Objects.requireNonNull(goal);
    authority.requireReadTargets(goal, goal.home(), List.of());
    if (!relayAccess.mayRead(goal.executor(), goal.home().project()))
      throw new CallerFault("Relay source is unavailable to this goal");
  }
}
