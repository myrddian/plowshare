package io.aeyer.plowshare.server.events;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
            isNull(),
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
            isNull(),
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

  @Test
  void managedCommandsPreserveArgumentsAndUseTheLiveDefinitionSession() {
    var callers = mock(Callers.class);
    var jobs = mock(JobStore.class);
    var definitions = mock(ScheduleDefinitions.class);
    var authority = mock(ScheduleDefinitions.Authority.class);
    var source = new ScheduleDefinitionStore.Source(1, "owner", 7L, "project", "workspace");
    var work =
        new io.aeyer.plowshare.protocol.ScheduledWork(
            1,
            "0 0 9 * * *",
            "UTC",
            false,
            new io.aeyer.plowshare.protocol.ScheduledWork.Action(
                "skill", "worker", "review", "exact\narguments", "NEW"),
            new io.aeyer.plowshare.protocol.ScheduledWork.Target("mailbox", null, null, null, null),
            null);
    var file =
        new io.aeyer.plowshare.protocol.ScheduledWork.File(
            "daily",
            "project",
            "workspace",
            ".plowshare/schedules/daily.json",
            "daily",
            work,
            "active",
            null);
    when(definitions.managed("daily", "owner")).thenReturn(java.util.Optional.of(file));
    when(definitions.sourceOf("daily", "owner")).thenReturn(java.util.Optional.of(source));
    when(definitions.executionSession(source)).thenReturn("live-session");
    var caller = mock(DefinitionResolver.Caller.class);
    var agent = mock(AgentDefinition.class);
    when(callers.callerFor("project", "live-session", "owner")).thenReturn(caller);
    when(callers.requireAgent("worker", caller)).thenReturn(agent);
    when(jobs.submitEvent(
            eq(agent),
            eq("/skill:review --mode=NEW exact\narguments"),
            eq(Home.of("project")),
            eq("live-session"),
            isNull(),
            any(),
            eq("owner"),
            any(),
            any()))
        .thenReturn(new JobStore.EventRun("job", "conversation"));
    var runner = new AgentRunner(callers, jobs, mock(Turn.class));
    runner.useSchedules(() -> definitions, () -> authority, () -> null);
    runner.start(
        new TriggerRecord(
            "daily",
            "daily",
            "project",
            null,
            "worker",
            work.action().utterance(),
            null,
            null,
            1,
            false,
            "owner"),
        mock(FiringRecord.class),
        "event metadata must not become command input",
        (c, o) -> {});
    verify(authority).validate(source, work);
    verify(jobs)
        .submitEvent(
            eq(agent),
            eq("/skill:review --mode=NEW exact\narguments"),
            eq(Home.of("project")),
            eq("live-session"),
            isNull(),
            any(),
            eq("owner"),
            any(),
            any());
  }

  @Test
  void messageAdmissionReturnsAMessageReceiptWithoutInventingAJob() {
    var definitions = mock(ScheduleDefinitions.class);
    var messages = mock(io.aeyer.plowshare.server.board.BoardMessaging.class);
    var jobs = mock(JobStore.class);
    var source = new ScheduleDefinitionStore.Source(1, "owner", 7L, "project", "server");
    var work =
        new io.aeyer.plowshare.protocol.ScheduledWork(
            1,
            "0 0 9 * * *",
            "UTC",
            false,
            new io.aeyer.plowshare.protocol.ScheduledWork.Action(
                "agent", "worker", null, "Review", null),
            new io.aeyer.plowshare.protocol.ScheduledWork.Target(
                "message", null, null, "worker", null),
            null);
    var file =
        new io.aeyer.plowshare.protocol.ScheduledWork.File(
            "daily", "project", "server", "schedules/daily.json", "daily", work, "active", null);
    when(definitions.managed("daily", "owner")).thenReturn(java.util.Optional.of(file));
    when(definitions.sourceOf("daily", "owner")).thenReturn(java.util.Optional.of(source));
    var task = mock(io.aeyer.plowshare.protocol.Incoming.Task.class);
    when(task.message()).thenReturn("bdm_delivery");
    when(messages.receiveScheduled("owner", "project", "daily", "firing", work)).thenReturn(task);
    var firing = mock(FiringRecord.class);
    when(firing.id()).thenReturn("firing");
    var runner = new AgentRunner(mock(Callers.class), jobs, mock(Turn.class));
    runner.useSchedules(() -> definitions, () -> (s, d) -> {}, () -> messages);
    var receipt =
        runner.start(
            new TriggerRecord(
                "daily", "daily", "project", null, "worker", "Review", null, null, 1, false,
                "owner"),
            firing,
            "Review",
            (c, o) ->
                org.junit.jupiter.api.Assertions.assertTrue(o.text().contains("bdm_delivery")));
    org.junit.jupiter.api.Assertions.assertEquals(
        new Dispatcher.Started.Message("bdm_delivery"), receipt);
    verifyNoInteractions(jobs);
  }

  @Test
  void anOrphanedFileScheduleCannotFallThroughToLegacyExecution() {
    var definitions = mock(ScheduleDefinitions.class);
    when(definitions.managed("daily", "owner")).thenReturn(java.util.Optional.empty());
    when(definitions.requiresDefinition("daily", "owner")).thenReturn(true);
    var callers = mock(Callers.class);
    var jobs = mock(JobStore.class);
    var runner = new AgentRunner(callers, jobs, mock(Turn.class));
    runner.useSchedules(() -> definitions, () -> (s, d) -> {}, () -> null);
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () ->
            runner.start(
                new TriggerRecord(
                    "daily", "daily", null, null, "worker", "Review", null, null, 1, false,
                    "owner"),
                mock(FiringRecord.class),
                "Review",
                (c, o) -> {}));
    verifyNoInteractions(callers, jobs);
  }
}
