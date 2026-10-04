package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.ProjectCaps;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.orchestrations.CapsSource;
import io.aeyer.plowshare.server.orchestrations.Orchestrations;
import io.aeyer.plowshare.server.ws.CapsFrames.CapsView;
import io.aeyer.plowshare.server.ws.CapsFrames.SettingView;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** {@code orchestration.caps} — spec 2026-09-29 §2: the caps as read now, applied, and said. */
class CapsFramesTest {

  private static final ProjectCaps STEPS_40 =
      new ProjectCaps(
          new ProjectCaps.Setting(40, ProjectCaps.PROJECT_FILE),
          ProjectCaps.Setting.UNSET,
          ProjectCaps.Setting.UNSET,
          null);

  private Orchestrations orchestrations;
  private CapsFrames frames;

  @BeforeEach
  void wire() {
    orchestrations = mock(Orchestrations.class);
    when(orchestrations.applyCaps(eq("story"), eq("enzo"), any())).thenReturn(1);
    CapsSource source = project -> STEPS_40;
    frames = new CapsFrames(providerOf(source), providerOf(orchestrations));
  }

  @SuppressWarnings("unchecked")
  private <T> ObjectProvider<T> providerOf(T bean) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(bean);
    when(provider.getIfAvailable(any(Supplier.class))).thenReturn(bean);
    return provider;
  }

  @Test
  void the_caps_are_answered_with_their_sources_and_how_many_runs_they_reached() {
    Outcome outcome = frames.caps(Map.of("project", "story"), new Asking("tab-1", "enzo"));

    CapsView view = (CapsView) outcome.payload();
    assertEquals(new SettingView(40, ".plowshare/environment.yml"), view.steps());
    assertEquals(new SettingView(null, "definition"), view.budget());
    assertEquals(new SettingView(null, "definition"), view.autoContinue());
    assertEquals(new SettingView(null, "definition"), view.time());
    assertEquals(new SettingView(5, "default"), view.failedChecks());
    assertEquals(1, view.applied());
    assertEquals("story", view.project());
    verify(orchestrations).applyCaps("story", "enzo", STEPS_40);
  }

  @Test
  void why_a_file_was_not_read_is_said() {
    ProjectCaps unreadable =
        new ProjectCaps(
            ProjectCaps.Setting.UNSET,
            ProjectCaps.Setting.UNSET,
            ProjectCaps.Setting.UNSET,
            "line 2: not a number");
    CapsSource source = project -> unreadable;
    CapsFrames saying = new CapsFrames(providerOf(source), providerOf(orchestrations));

    CapsView view =
        (CapsView) saying.caps(Map.of("project", "story"), new Asking("tab-1", "enzo")).payload();

    assertEquals("line 2: not a number", view.said());
  }

  @Test
  void a_server_without_the_engine_reads_the_caps_and_applies_them_to_nothing() {
    CapsSource source = project -> STEPS_40;
    CapsFrames bare = new CapsFrames(providerOf(source), this.<Orchestrations>providerOf(null));

    CapsView view =
        (CapsView) bare.caps(Map.of("project", "story"), new Asking("tab-1", "enzo")).payload();

    assertEquals(0, view.applied());
    assertEquals(40, view.steps().value());
  }

  @Test
  void an_empty_project_is_refused_naming_the_frame() {
    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () -> frames.caps(Map.of("project", " "), new Asking("tab-1", "enzo")));

    assertTrue(refused.getMessage().contains("orchestration.caps"), refused.getMessage());
    verify(orchestrations, never()).applyCaps(any(), any(), any());
  }

  @Test
  void a_socket_with_no_account_is_refused() {
    assertThrows(
        CallerFault.class, () -> frames.caps(Map.of("project", "story"), new Asking("tab-1")));
  }

  @Test
  void the_area_routes_orchestration_caps() {
    assertEquals(java.util.Set.of(FrameTypes.ORCHESTRATION_CAPS), frames.frames().keySet());
    assertEquals("orchestration.caps", FrameTypes.ORCHESTRATION_CAPS);
  }
}
