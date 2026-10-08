package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.board.SwarmCatalog;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmTypeFramesTest {
  @Test
  void discovery_requires_live_viewer_access_before_reading_files() throws Exception {
    var catalog = mock(SwarmCatalog.class);
    var members = mock(ProjectMembers.class);
    var frames = new SwarmTypeFrames(catalog, members);
    doThrow(new CallerFault("No live grant"))
        .when(members)
        .requireRole("app", "viewer", ProjectRole.VIEWER);
    assertThrows(
        CallerFault.class,
        () -> frames.types(Map.of("project", "app"), new Asking("session", "viewer")));
    verifyNoInteractions(catalog);
    doNothing().when(members).requireRole("app", "viewer", ProjectRole.VIEWER);
    when(catalog.types("app")).thenReturn(List.of());
    assertEquals(
        new SwarmTypeFrames.Types("app", List.of()),
        frames.types(Map.of("project", "app"), new Asking("session", "viewer")).payload());
    verify(members, times(2)).requireRole("app", "viewer", ProjectRole.VIEWER);
  }

  @Test
  void unknown_fields_and_anonymous_readers_cannot_reach_the_catalog() {
    var catalog = mock(SwarmCatalog.class);
    var members = mock(ProjectMembers.class);
    var frames = new SwarmTypeFrames(catalog, members);
    assertThrows(
        CallerFault.class,
        () ->
            frames.types(
                Map.of("project", "app", "account", "owner"), new Asking("session", "viewer")));
    assertThrows(
        CallerFault.class,
        () -> frames.types(Map.of("project", "app"), new Asking("session", null)));
    verifyNoInteractions(catalog, members);
  }
}
