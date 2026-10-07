package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationRoutingTest {
  @Test
  void root_routing_needs_explicit_work_access_in_both_applications(@TempDir Path root)
      throws Exception {
    var projects = mock(ProjectStore.class);
    var stored = mock(ProjectMembers.class);
    var registrations = mock(ApplicationRegistrations.class);
    var members =
        new ApplicationProjectMembers(
            stored, new WorkspaceApplicationPolicy(projects, registrations));
    var routing = new ProjectMessageRouting(projects, members);
    for (String name : List.of("source", "destination")) {
      Path workspace = Files.createDirectory(root.resolve(name));
      when(projects.find(name))
          .thenReturn(
              Optional.of(
                  new ProjectRecord(name, workspace, List.of(), List.of(), "DISJOINT", List.of())));
      when(projects.id(name)).thenReturn(name.equals("source") ? 1L : 2L);
      when(stored.role(name, "reader")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
      // A legacy identity must not shadow the root Application's routing.
      Files.createDirectory(workspace.resolve(".plowshare"));
      Files.writeString(workspace.resolve(".plowshare/project"), name);
    }
    when(projects.effectiveExclusions(any(ProjectRecord.class))).thenReturn(List.of());
    Files.writeString(
        root.resolve("source/plowshare.json"),
        "{\"version\":1,\"name\":\"source\",\"access\":{\"accounts\":[{\"handle\":\"reader\",\"role\":\"CONTRIBUTOR\"}]},\"routing\":{\"sendTo\":[\"destination\"]}}");
    Files.writeString(
        root.resolve("destination/plowshare.json"),
        "{\"version\":1,\"name\":\"destination\",\"routing\":{\"acceptFrom\":[\"source\"]}}");
    assertThrows(Board.Refused.class, () -> routing.require("reader", "source", "destination"));
    Files.writeString(
        root.resolve("destination/plowshare.json"),
        "{\"version\":1,\"name\":\"destination\",\"access\":{\"accounts\":[{\"handle\":\"reader\",\"role\":\"CONTRIBUTOR\"}]},\"routing\":{\"acceptFrom\":[\"source\"]}}");
    assertDoesNotThrow(() -> routing.require("reader", "source", "destination"));
    Files.writeString(root.resolve("source/plowshare.json"), "{\"version\":1,\"name\":\"source\"}");
    assertThrows(CallerFault.class, () -> routing.require("reader", "source", "destination"));
  }
}
