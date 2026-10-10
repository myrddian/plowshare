package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.agents.ApplicationResources;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationRelayWorkerBindingsTest {
  @TempDir Path root;

  private ApplicationRelayWorkerBindings bindings(
      ProjectMembers members, RelayWorkerProperties boot, ApplicationResources resources) {
    var catalogue = mock(ProjectCatalogue.class);
    var project = mock(ProjectRecord.class);
    when(project.name()).thenReturn("application");
    when(catalogue.all()).thenReturn(List.of(project));
    when(catalogue.id("application")).thenReturn(9L);
    return new ApplicationRelayWorkerBindings(catalogue, resources, members, boot);
  }

  private void declare(String account) throws Exception {
    Files.createDirectories(root.resolve("server"));
    Files.writeString(
        root.resolve("server/relay-workers.json"),
        "{\"version\":1,\"account\":\"" + account + "\"}");
  }

  @Test
  void enrollment_is_explicit_live_and_requires_current_work_authority() throws Exception {
    var members = mock(ProjectMembers.class);
    when(members.mayWork("application", "service")).thenReturn(true);
    when(members.mayWork("application", "replacement")).thenReturn(true);
    var bindings = bindings(members, new RelayWorkerProperties(), id -> Optional.of(root));
    assertTrue(bindings.current().isEmpty());
    declare("service");
    assertEquals(
        List.of(new RelayWorkerProperties.Project("application", "service")), bindings.current());
    declare("replacement");
    assertEquals("replacement", bindings.current().getFirst().account());
    when(members.mayWork("application", "replacement")).thenReturn(false);
    assertTrue(bindings.current().isEmpty());
    when(members.mayWork("application", "replacement")).thenReturn(true);
    assertEquals(1, bindings.current().size());
    Files.delete(root.resolve("server/relay-workers.json"));
    assertTrue(bindings.current().isEmpty());
  }

  @Test
  void invalid_replacements_and_foreign_roots_cannot_retain_previous_enrollment() throws Exception {
    var members = mock(ProjectMembers.class);
    when(members.mayWork("application", "service")).thenReturn(true);
    var resources = mock(ApplicationResources.class);
    when(resources.root(9L)).thenReturn(Optional.of(root));
    var bindings = bindings(members, new RelayWorkerProperties(), resources);
    declare("service");
    assertEquals(1, bindings.current().size());
    Files.writeString(root.resolve("server/relay-workers.json"), "{}");
    assertTrue(bindings.current().isEmpty());
    declare("service");
    when(resources.root(9L)).thenThrow(new WorkspaceRefusedException("invalid manifest"));
    assertTrue(bindings.current().isEmpty());
  }

  @Test
  void operator_and_application_accounts_must_not_disagree() throws Exception {
    var members = mock(ProjectMembers.class);
    when(members.mayWork("application", "service")).thenReturn(true);
    var boot = new RelayWorkerProperties();
    boot.setProjects(List.of(new RelayWorkerProperties.Project("application", "operator")));
    var bindings = bindings(members, boot, id -> Optional.of(root));
    assertEquals("operator", bindings.current().getFirst().account());
    declare("service");
    assertTrue(bindings.current().isEmpty());
    declare("operator");
    when(members.mayWork("application", "operator")).thenReturn(true);
    assertEquals("operator", bindings.current().getFirst().account());
  }

  @Test
  void automatic_enrollment_is_bounded_even_when_many_applications_are_deployed() throws Exception {
    declare("service");
    var catalogue = mock(ProjectCatalogue.class);
    var records =
        java.util.stream.IntStream.range(0, 33)
            .mapToObj(
                i -> {
                  var record = mock(ProjectRecord.class);
                  when(record.name()).thenReturn("application" + i);
                  when(catalogue.id("application" + i)).thenReturn((long) i + 1);
                  return record;
                })
            .toList();
    when(catalogue.all()).thenReturn(records);
    var members = mock(ProjectMembers.class);
    when(members.mayWork(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("service")))
        .thenReturn(true);
    var bindings =
        new ApplicationRelayWorkerBindings(
            catalogue, id -> Optional.of(root), members, new RelayWorkerProperties());
    assertThrows(io.aeyer.plowshare.server.faults.CallerFault.class, bindings::current);
    when(catalogue.all()).thenReturn(records.subList(0, 32));
    assertEquals(32, bindings.current().size());
  }
}
