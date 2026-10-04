package io.aeyer.plowshare.server.events;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import org.junit.jupiter.api.Test;

class AgentRunnerTest {

  @Test
  void an_untargeted_event_pins_the_account_that_defined_its_trigger() {
    Callers callers = mock(Callers.class);
    JobStore jobs = mock(JobStore.class);
    AgentDefinition definition = mock(AgentDefinition.class);
    DefinitionResolver.Caller caller = mock(DefinitionResolver.Caller.class);
    when(callers.callerFor("payments", null, "enzo")).thenReturn(caller);
    when(callers.requireAgent("coder", caller)).thenReturn(definition);
    when(jobs.submitEvent(
            eq(definition),
            eq("go"),
            eq(Home.of("payments")),
            eq(40),
            any(TurnCap.class),
            eq("enzo"),
            eq(Speaker.event("daily")),
            any()))
        .thenReturn(new JobStore.EventRun("job_1", "cnv_event"));

    new AgentRunner(callers, jobs, mock(Turn.class))
        .start(
            new TriggerRecord(
                "daily", "daily", "payments", null, "coder", "go", 40, 3, 1, false, "enzo"),
            "go",
            (conversation, outcome) -> {});

    verify(jobs)
        .submitEvent(
            eq(definition),
            eq("go"),
            eq(Home.of("payments")),
            eq(40),
            any(TurnCap.class),
            eq("enzo"),
            eq(Speaker.event("daily")),
            any());
  }

  /**
   * A conversation-targeted run is a turn in that conversation, under the conversation's own
   * ceilings (spec §5: a trigger's limits apply to untargeted runs only). trigger.define refuses
   * limits there now; a row stored before that refusal must not smuggle a turn cap in.
   */
  @Test
  void a_conversation_targeted_run_takes_the_conversations_turn_cap_not_the_triggers() {
    Callers callers = mock(Callers.class);
    Turn turns = mock(Turn.class);
    AgentRunner runner = new AgentRunner(callers, mock(JobStore.class), turns);
    runner.start(
        new TriggerRecord(
            "aimed", "daily", null, "cnv_talk", "bard", "go", 40, 3, 1, false, "enzo"),
        "go",
        (conversation, outcome) -> {});
    verify(turns)
        .speak(
            eq("cnv_talk"),
            any(),
            anyString(),
            isNull(),
            isNull(),
            eq(Speaker.event("aimed")),
            any());
  }
}
