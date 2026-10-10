package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConductorDelegateScopeTest {
  @Test
  void continuations_recheck_inherited_scope_and_preserve_the_session() {
    var turn = mock(Turn.class);
    when(turn.homeOf("child")).thenReturn(Home.of("application"));
    var definitions = mock(AgentDelegates.class);
    var agent =
        new AgentDefinition(
            "helper",
            "fixture",
            "m",
            List.of(),
            List.of(),
            List.of(),
            2,
            4,
            "Application helper",
            false,
            true);
    when(definitions.find(Home.of("application"), "session", "service", "helper"))
        .thenReturn(Optional.of(agent));
    when(turn.speakToDelegate(
            eq("child"),
            eq("parent"),
            eq(agent),
            eq("continue"),
            eq("service"),
            eq("session"),
            any()))
        .thenReturn("job");
    var voice = OrchestrationsConfig.conductorVoice(turn, definitions);
    assertEquals(
        "job",
        voice.resumeDelegate(
            "child", "helper", "parent", "continue", "service", "session", outcome -> {}));
    when(definitions.find(Home.of("application"), "session", "service", "helper"))
        .thenThrow(new CallerFault("work revoked"));
    assertThrows(
        CallerFault.class,
        () ->
            voice.resumeDelegate(
                "child", "helper", "parent", "continue", "service", "session", outcome -> {}));
    verify(turn, times(1))
        .speakToDelegate(
            eq("child"),
            eq("parent"),
            eq(agent),
            eq("continue"),
            eq("service"),
            eq("session"),
            any());
    doReturn(Optional.empty())
        .when(definitions)
        .find(Home.of("application"), "session", "service", "helper");
    assertThrows(
        Turn.Refused.class,
        () ->
            voice.resumeDelegate(
                "child", "helper", "parent", "continue", "service", "session", outcome -> {}));
  }
}
