package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.server.access.AccessRequest;
import io.aeyer.plowshare.server.access.ResourceScopeRepository;
import io.aeyer.plowshare.server.archive.TurnHistoryRepository;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.util.List;
import java.util.Objects;

/** Scope comes from the owning conversation, never a submitted project or a routing context. */
public final class AuthorizedActingHistory implements ActingHistory {
  private final ResourceScopeRepository resources;
  private final TurnHistoryRepository turns;
  private final ActingAuthorization authorization;

  public AuthorizedActingHistory(
      ResourceScopeRepository resources,
      TurnHistoryRepository turns,
      ActingAuthorization authorization) {
    this.resources = Objects.requireNonNull(resources);
    this.turns = Objects.requireNonNull(turns);
    this.authorization = Objects.requireNonNull(authorization);
  }

  @Override
  public List<TurnRecord> turns(RequestAuthority.Delegated authority, String conversation) {
    var resource =
        new AccessRequest.Resource(ResourceScopeRepository.Kind.CONVERSATION, conversation);
    if (!resource.id().equals(conversation))
      throw new CallerFault("Choose an exact conversation ID");
    String project = project(resource);
    check(authority, project);
    var answer = List.copyOf(turns.forConversation(conversation));
    // Refuse relocation or revocation during the read instead of returning data under stale scope.
    if (!project.equals(project(resource)))
      throw new CallerFault("Conversation scope changed during the read");
    check(authority, project);
    return answer;
  }

  private String project(AccessRequest.Resource resource) {
    var projects = resources.projects(resource);
    if (projects.size() != 1 || projects.getFirst() == null)
      throw new CallerFault("Choose a scoped conversation");
    return projects.getFirst();
  }

  private void check(RequestAuthority.Delegated authority, String project) {
    ActingTarget target;
    try {
      target =
          project.equals(PersonalSpaces.name(authority.subject()))
              ? new ActingTarget.Personal(authority.subject())
              : new ActingTarget.Project(project);
    } catch (IllegalArgumentException outsideScope) {
      throw new CallerFault("Conversation is outside current acting authority");
    }
    authorization.require(authority, ActingPermission.READ, target);
  }
}
