package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.ProjectView;
import io.aeyer.plowshare.server.archive.ProjectStore;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Attach client files without provisioning, marking, mirroring or exporting the directory. */
@Component
public class ClientProjectFrames implements FrameArea {
  public record Attach(String name, String workspace, String machine) {}

  private final ProjectStore projects;

  public ClientProjectFrames(ProjectStore projects) {
    this.projects = projects;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(FrameTypes.PROJECT_ATTACH, this::attach);
  }

  private Outcome attach(Map<String, Object> payload, Asking asking) {
    Attach request = Payloads.as(payload, Attach.class, FrameTypes.PROJECT_ATTACH);
    var project =
        projects.attachClient(
            request.name(),
            request.workspace(),
            request.machine(),
            asking.sessionId(),
            asking.requireHandle(FrameTypes.PROJECT_ATTACH));
    return Outcome.ok(
        ProjectView.of(project, List.of(), request.machine(), List.of(asking.handle())));
  }
}
