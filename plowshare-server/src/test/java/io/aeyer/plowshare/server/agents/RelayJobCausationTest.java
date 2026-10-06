package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.archive.JobLog;
import org.junit.jupiter.api.Test;

class RelayJobCausationTest {
  @Test
  void causal_submission_requires_a_durable_write_before_execution_or_registration() {
    var runtime = mock(JobRuntime.class);
    var rows = mock(JobLog.class);
    var agent = mock(AgentDefinition.class);
    when(agent.name()).thenReturn("worker");
    when(agent.maxModelCalls()).thenReturn(5);
    var cause = RelayCausation.root("source").next("source", 8);
    doThrow(new IllegalStateException("storage unavailable"))
        .when(rows)
        .started(anyString(), eq("worker"), any(), any(), isNull(), eq(cause));
    try (var store = new JobStore(runtime, JobEvents.NONE, null, rows)) {
      assertThrows(
          IllegalStateException.class,
          () ->
              store.submitEvent(
                  agent,
                  "event",
                  Home.global(),
                  null,
                  null,
                  TurnCap.of(1),
                  "operator",
                  Speaker.event("fixture"),
                  (id, outcome) -> {},
                  cause));
      assertTrue(store.jobs().isEmpty());
      verifyNoInteractions(runtime);
    }
    try (var store = new JobStore(runtime)) {
      assertThrows(
          IllegalStateException.class,
          () ->
              store.submitEvent(
                  agent,
                  "event",
                  Home.global(),
                  null,
                  null,
                  TurnCap.of(1),
                  "operator",
                  Speaker.event("fixture"),
                  (id, outcome) -> {},
                  cause));
      assertTrue(store.jobs().isEmpty());
      verifyNoInteractions(runtime);
    }
  }
}
