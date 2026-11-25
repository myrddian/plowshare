package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.events.AgentRunner;
import io.aeyer.plowshare.server.events.AgentRunner.ScheduledTask;
import io.aeyer.plowshare.server.events.EventPayload;
import io.aeyer.plowshare.server.events.FiringRecord;
import io.aeyer.plowshare.server.events.TriggerRecord;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentRunAwaitingTest {

  @Test
  void only_a_child_with_the_same_executor_home_and_scope_inherits_the_saved_schedule() {
    String owner = "sam";
    Home home = Home.of(PersonalSpaces.name(owner));
    var scope = new ScopeId(UUID.randomUUID());
    var parentAdmission = admission(owner, home, scope);
    var schedule = scheduledTask(owner, home);
    var registry = mock(AgentRegistry.class);
    var caller =
        new AgentDefinition(
            "bot",
            "bot",
            "fast",
            List.of("agent_run"),
            List.of("writer"),
            List.of(),
            5,
            5,
            "Draft");
    var writer =
        new AgentDefinition(
            "writer", "writer", "fast", List.of(), List.of(), List.of(), 5, 5, "Write");
    when(registry.find("writer")).thenReturn(Optional.of(writer));
    var sameHomeAndScope = admission(owner, home, scope);

    for (var childAdmission :
        List.of(
            sameHomeAndScope,
            admission("different-executor", home, scope),
            admission(owner, Home.of("different-home"), scope),
            admission(owner, home, new ScopeId(UUID.randomUUID())))) {
      JobRuntime runtime = mock(JobRuntime.class);
      Transcript parent = mock(Transcript.class);
      Transcript child = mock(Transcript.class);
      Budget budget = Budget.of(10);
      when(parent.scheduledTask()).thenReturn(schedule);
      when(parent.conversationId()).thenReturn("parent");
      when(parent.delegate(writer, home, null)).thenReturn(child);
      when(runtime.childOperation(parentAdmission, "parent", child, writer, home, budget))
          .thenReturn(childAdmission);
      when(runtime.activity()).thenReturn(RunActivity.NONE);
      when(runtime.runOperation(
              eq(writer),
              any(),
              eq(home),
              eq(budget),
              any(),
              eq("tab-1"),
              eq(JobWatch.UNWATCHED),
              eq(child),
              any(),
              eq(List.of()),
              eq(owner),
              eq(false),
              eq(childAdmission)))
          .thenReturn(new Outcome(Outcome.Ending.ANSWERED, "done", 1, 1, ""));
      var tool =
          new AgentRunTool(
                  registry,
                  runtime,
                  caller,
                  budget,
                  () -> false,
                  "tab-1",
                  parent,
                  List.of(),
                  ImageStore.NONE,
                  null,
                  owner)
              .underOperation(parentAdmission);

      tool.run("{\"agent\":\"writer\",\"task\":\"Use the model-generated handoff\"}", home);

      assertEquals(Outcome.Ending.ANSWERED, tool.returned());
      if (childAdmission == sameHomeAndScope) verify(child).prepareScheduledTask(same(schedule));
      else verify(child, never()).prepareScheduledTask(any());
    }
  }

  private static Admission admission(String owner, Home home, ScopeId scope) {
    var admission = mock(Admission.class);
    when(admission.executor()).thenReturn(owner);
    when(admission.home()).thenReturn(home);
    when(admission.scope()).thenReturn(scope);
    return admission;
  }

  private static ScheduledTask scheduledTask(String owner, Home home) {
    String instruction = "Update /personal/Out/brief.md.";
    var jobs = mock(EventRuns.class);
    var task = new AtomicReference<ScheduledTask>();
    when(jobs.submitTriggeredEvent(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              task.set(call.getArgument(9));
              return new JobStore.EventRun("job", "parent");
            });
    var trigger =
        new TriggerRecord(
            "daily",
            "daily",
            home.project(),
            null,
            "bot",
            instruction,
            null,
            null,
            1,
            false,
            owner);
    var firing = mock(FiringRecord.class);
    when(firing.data()).thenReturn(new EventPayload.Scheduled("daily", Instant.EPOCH));
    new AgentRunner(mock(Callers.class), jobs, mock(Turn.class))
        .start(trigger, firing, instruction, (id, outcome) -> {});
    return task.get();
  }

  @Test
  void a_child_waiting_for_approval_ends_its_parent_waiting_on_the_same_question() {
    AgentRegistry registry = mock(AgentRegistry.class);
    JobRuntime runtime = mock(JobRuntime.class);
    AgentDefinition caller = mock(AgentDefinition.class);
    AgentDefinition callee = mock(AgentDefinition.class);
    Transcript parent = mock(Transcript.class);
    Transcript child = mock(Transcript.class);
    Budget budget = Budget.of(10);
    TurnEnd end = new TurnEnd();
    Home home = Home.of("payments");
    Outcome awaiting =
        new Outcome(Outcome.Ending.AWAITING, "Approve running ./gradlew test [apr_1]", 1, 1, "");

    when(caller.name()).thenReturn("interlocutor");
    when(caller.calls()).thenReturn(List.of("coder"));
    when(callee.name()).thenReturn("coder");
    when(callee.delegable()).thenReturn(true);
    when(callee.description()).thenReturn("writes and tests code");
    when(callee.maxTurns()).thenReturn(10);
    when(registry.find("coder")).thenReturn(Optional.of(callee));
    when(parent.delegate(callee, home, null)).thenReturn(child);
    // A mock's activity() is null; the real runtime's never is (RunActivity.NONE until wired).
    ToldActivity activity = new ToldActivity();
    when(runtime.activity()).thenReturn(activity);
    when(runtime.run(
            same(callee),
            eq("build it"),
            eq(home),
            same(budget),
            any(),
            eq("tab-1"),
            same(JobWatch.UNWATCHED),
            same(child),
            any(TurnCap.class),
            eq(List.of())))
        .thenReturn(awaiting);

    AgentRunTool tool =
        new AgentRunTool(
            registry,
            runtime,
            caller,
            budget,
            () -> false,
            "tab-1",
            parent,
            List.of(),
            ImageStore.NONE,
            end);
    tool.run("{\"agent\":\"coder\",\"task\":\"build it\"}", home);

    assertEquals(Outcome.Ending.AWAITING, end.requested().orElseThrow().ending());
    assertEquals(awaiting.text(), end.requested().orElseThrow().text());
    verify(child).closed("build it", awaiting);
    // Gone out, and not come back: a waiting delegate's return is told when it is resumed and
    // ends (Orchestrations.delegateEnded), once. The call's own outcome is that it waits.
    assertEquals(List.of("delegated interlocutor coder build it"), activity.told());
    assertEquals(Outcome.Ending.AWAITING, tool.returned());
  }
}
