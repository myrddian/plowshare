package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.board.SwarmCatalog;
import io.aeyer.plowshare.server.board.SwarmDefinitions;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Definition discovery is an authenticated project read, never a grant of participation rights. */
@Component
public final class SwarmTypeFrames implements FrameArea {
  private final SwarmCatalog catalog;
  private final ProjectMembers members;

  public SwarmTypeFrames(SwarmCatalog catalog, ProjectMembers members) {
    this.catalog = catalog;
    this.members = members;
  }

  public record Types(String project, List<SwarmDefinitions.SwarmDefinition> types) {
    public Types {
      types = List.copyOf(types);
    }
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(FrameTypes.SWARM_TYPES, this::types);
  }

  Outcome types(Map<String, Object> payload, Asking asking) {
    String account = asking.requireHandle(FrameTypes.SWARM_TYPES);
    if (!payload.keySet().equals(java.util.Set.of("project")))
      throw new CallerFault("swarm.types takes only project");
    String project =
        Payloads.required(payload, "project", FrameTypes.SWARM_TYPES, "the selected project");
    members.requireRole(project, account, io.aeyer.plowshare.server.archive.ProjectRole.VIEWER);
    return Outcome.ok(new Types(project, catalog.types(project)));
  }
}
