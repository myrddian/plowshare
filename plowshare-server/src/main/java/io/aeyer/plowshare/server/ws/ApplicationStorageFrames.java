package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.api.ProjectView;
import io.aeyer.plowshare.server.archive.ServerProjects;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Explicit adoption of host aliases; legacy workspace requests keep their original meaning. */
@Component
public final class ApplicationStorageFrames implements FrameArea {
  private final ServerProjects projects;
  private final ApplicationPolicy applications;

  public ApplicationStorageFrames(ServerProjects projects, ApplicationPolicy applications) {
    this.projects = projects;
    this.applications = applications;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(FrameTypes.APPLICATION_STORAGE_SET, this::set);
  }

  private Outcome set(Map<String, Object> payload, Asking asking) {
    if (!payload.keySet().equals(Set.of("project", "applicationRoot", "writableAreas")))
      throw new CallerFault("Specify project, applicationRoot and writableAreas");
    String name =
        Payloads.required(
            payload, "project", FrameTypes.APPLICATION_STORAGE_SET, "an existing Application");
    var placement = FileStoreRequests.placement(payload);
    if (applications.read(name).kind() != ApplicationPolicy.Kind.APPLICATION)
      throw new CallerFault("Adoption requires a valid deployed Application manifest");
    var placed =
        projects.place(name, placement, asking.requireHandle(FrameTypes.APPLICATION_STORAGE_SET));
    return Outcome.ok(
        ProjectView.of(placed, projects.effectiveExclusions(placed)).application(true));
  }
}
