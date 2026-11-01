package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.auth.AccountActivity;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.tools.HostedInvocationAuthority;
import io.aeyer.plowshare.server.relay.tools.RelayToolAuthority;
import io.aeyer.plowshare.server.relay.tools.RelayToolRepository;
import java.util.Objects;
import java.util.UUID;

/** Providers act for a retained invocation; the owner is derived, never impersonated. */
public final class InvocationArtifactAuthority implements ArtifactAuthority {
  private final RelayToolRepository invocations;
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final AccountActivity accounts;
  private final RelayToolAuthority tools;
  private final HostedInvocationAuthority hosted;

  public InvocationArtifactAuthority(
      RelayToolRepository invocations,
      ProjectMembers members,
      ProjectWorkspaces projects,
      AccountActivity accounts,
      RelayToolAuthority tools,
      HostedInvocationAuthority hosted) {
    this.invocations = invocations;
    this.members = members;
    this.projects = projects;
    this.accounts = accounts;
    this.tools = tools;
    this.hosted = hosted;
  }

  @Override
  public RelayToolRepository.Stored require(String actor, UUID invocation) {
    if (actor == null || !accounts.isEnabled(actor)) throw refused();
    var stored = invocations.find(invocation).orElseThrow(InvocationArtifactAuthority::refused);
    var intent = stored.intent();
    String project = intent.binding().project();
    String owner = intent.request().account();
    if (!actor.equals(intent.binding().account())
        || !accounts.isEnabled(owner)
        || !Objects.equals(projects.id(project), intent.projectId())
        || projects.personalOwner(project).isPresent()
        || !members.mayWork(project, owner)) throw refused();
    if (intent.hosted() != null) hosted.authorizeArtifact(actor, intent);
    else if (io.aeyer.plowshare.protocol.HostedToolScopes.hosted(intent.binding().provider())
        || !members.mayWork(project, actor)
        || !tools.permitsArtifact(intent.binding())) throw refused();
    return stored;
  }

  private static CallerFault refused() {
    return new CallerFault("artifact invocation is unavailable to this producer");
  }
}
