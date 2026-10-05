package io.aeyer.plowshare.server.relay;

import static io.aeyer.plowshare.server.relay.RelayDispatchFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.board.BoardMessaging;
import io.aeyer.plowshare.server.orchestrations.*;
import io.aeyer.plowshare.server.session.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkRelayReceiverTest {
  private final WorkCallers callers = mock(WorkCallers.class);
  private final EventRuns jobs = mock(EventRuns.class);
  private final GrantedOrchestrations grants = mock(GrantedOrchestrations.class);
  private final OrchestrationStarts starts = mock(OrchestrationStarts.class);
  private final OrchestrationStore runs = mock(OrchestrationStore.class);
  private final RelayExecutions executions = mock(RelayExecutions.class);
  private final RelayProjectFiles files = mock(RelayProjectFiles.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final ProjectPresences presences = mock(ProjectPresences.class);
  private final SessionOwners sessions = mock(SessionOwners.class);
  private final BoardMessaging.Routing routing = mock(BoardMessaging.Routing.class);
  private AgentDefinition agent;

  @BeforeEach
  void setup() {
    agent =
        new AgentDefinition(
            "worker",
            "fixture",
            "fast",
            io.aeyer.plowshare.server.llm.dispatch.Sampling.Intent.DEFAULT,
            io.aeyer.plowshare.server.llm.dispatch.Sampling.NONE,
            List.of("probe"),
            List.of(),
            List.of(),
            10,
            5,
            "task",
            true,
            true,
            false,
            false,
            false,
            AgentDefinition.Fallback.NONE,
            List.of("review"),
            null,
            false,
            List.of());
    when(projects.id("project")).thenReturn(9L);
    when(projects.find("project"))
        .thenReturn(
            Optional.of(
                new io.aeyer.plowshare.server.archive.ProjectRecord(
                    "project", java.nio.file.Path.of("/fixture"), List.of(), List.of())));
    when(callers.requireAgent(eq("worker"), any())).thenReturn(agent);
    when(executions.begin(any())).thenReturn(true);
    when(jobs.submitEvent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new JobStore.EventRun("job_fixture", "cnv_fixture"));
  }

  private RelayReceiver receiver(String name) {
    return new WorkRelayReceiver(
        name,
        callers,
        jobs,
        grants,
        starts,
        runs,
        executions,
        files,
        projects,
        presences,
        sessions,
        routing);
  }

  private RelayReceiver.Request request(String name, String project, String source) {
    var original = delivery(RelayDeliveries.State.DISPATCHING, false);
    var branch =
        new RelayDeliveries.Branch(
            "run",
            name,
            source == null
                ? null
                : RelayDeliveries.SourcePin.of("notices/scripts/review.js", source),
            null,
            new RelayWork("worker", project, name.equals("orchestration.start") ? "review" : null));
    var changed =
        new RelayDeliveries.Delivery(
            original.key(),
            original.admission(),
            original.publication(),
            original.routing(),
            branch,
            original.state(),
            original.fence(),
            original.worker(),
            original.leaseUntil(),
            original.admittedAt(),
            original.updatedAt(),
            null,
            null);
    return new RelayReceiver.Request(ACCESS, changed);
  }

  @Test
  void agent_dispatch_records_intent_before_normal_event_submission_and_receipt_after() {
    var request = request("agent.run", null, null);
    assertInstanceOf(RelayReceiver.Settled.class, receiver("agent.run").dispatch(request));
    var order = inOrder(executions, jobs);
    order.verify(executions).begin(request);
    order
        .verify(jobs)
        .submitEvent(
            eq(agent),
            contains("release-event"),
            any(),
            isNull(),
            isNull(),
            any(),
            eq("operator"),
            eq(Speaker.event("relay " + request.identity())),
            any());
    order
        .verify(executions)
        .accepted(request, new RelayDeliveries.Receipt("job", "job_fixture"), "cnv_fixture");
    verify(routing).require("operator", "project", "project");
  }

  @Test
  void script_uses_only_the_named_agents_grants_and_exact_pinned_source() {
    String source =
        "// plowshare-script v1\nexport const manifest={};export function step(input){return {state:null,command:{finish:'done'}};}";
    var request = request("script.run", null, source);
    var receiver = receiver("script.run");
    assertTrue(receiver.handlesScript());
    receiver.require(request);
    receiver.dispatch(request);
    var captured = ArgumentCaptor.forClass(AgentDefinition.class);
    verify(jobs)
        .submitEvent(captured.capture(), any(), any(), any(), any(), any(), any(), any(), any());
    assertEquals(agent.withPrompt(source), captured.getValue());
    assertEquals(source, request.delivery().branch().handler().source());
  }

  @Test
  void an_existing_intent_without_a_receipt_never_submits_again() {
    var request = request("agent.run", null, null);
    when(executions.begin(request)).thenReturn(false);
    assertInstanceOf(RelayReceiver.Uncertain.class, receiver("agent.run").dispatch(request));
    assertTrue(receiver("agent.run").inspect(request).isEmpty());
    verifyNoInteractions(jobs, starts);
  }

  @Test
  void a_retained_receipt_is_returned_without_launching_new_work() {
    var request = request("agent.run", null, null);
    var receipt = new RelayDeliveries.Receipt("job", "job_retained");
    when(executions.find("operator", 9, request.identity()))
        .thenReturn(Optional.of(new RelayExecutions.Accepted(receipt, "cnv_retained")));
    assertEquals(
        new RelayReceiver.Settled(new RelayDeliveries.Accepted(receipt)),
        receiver("agent.run").dispatch(request));
    assertEquals(
        Optional.of(new RelayDeliveries.Accepted(receipt)), receiver("agent.run").inspect(request));
    verifyNoInteractions(jobs, starts);
    verify(executions, never()).begin(any());
  }

  @Test
  void cross_project_policy_and_command_grants_are_checked_before_intent() {
    var request = request("agent.run", "other", null);
    doThrow(new RelayReceiver.Refused("route-denied"))
        .when(routing)
        .require("operator", "project", "other");
    assertThrows(RelayReceiver.Refused.class, () -> receiver("agent.run").dispatch(request));
    verify(executions, never()).begin(any());
    verifyNoInteractions(jobs);
    var orchestration = request("orchestration.start", null, null);
    when(grants.granted(any(), any(), any(), any(), any())).thenReturn(Map.of());
    assertThrows(
        RelayReceiver.Refused.class, () -> receiver("orchestration.start").require(orchestration));
    verifyNoInteractions(starts);
  }

  @Test
  void
      orchestration_starts_use_the_delivery_uuid_and_the_granted_definition_without_a_caller_turn() {
    var request = request("orchestration.start", null, null);
    var definition = mock(OrchestrationDefinition.class);
    var run = mock(OrchestrationRecord.class);
    when(run.id()).thenReturn("orc_fixture");
    when(run.conductorConversation()).thenReturn("cnv_fixture");
    when(grants.granted(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("review", definition));
    when(starts.start(any(), eq(request.identity()), anyString())).thenReturn(run);
    receiver("orchestration.start").dispatch(request);
    var captured = ArgumentCaptor.forClass(Orchestrations.Start.class);
    verify(starts).start(captured.capture(), eq(request.identity()), contains("release-event"));
    assertSame(definition, captured.getValue().definition());
    assertEquals("operator", captured.getValue().callerHandle());
    assertEquals("worker", captured.getValue().callerAgent());
    assertNull(captured.getValue().callerConversation());
    assertNull(captured.getValue().parent());
    verifyNoInteractions(jobs);
    when(runs.startReceipt("operator", request.identity()))
        .thenReturn(
            Optional.of(
                new OrchestrationStore.StartReceipt(request.identity(), "orc_fixture", false)));
    assertEquals(
        Optional.of(
            new RelayDeliveries.Accepted(
                new RelayDeliveries.Receipt("orchestration", "orc_fixture"))),
        receiver("orchestration.start").inspect(request));
    verify(starts, times(1)).start(any(), any(), anyString());
  }

  @Test
  void offline_or_wrong_owner_remote_workspace_cannot_start_work() {
    when(projects.find("project")).thenReturn(Optional.empty());
    var request = request("agent.run", null, null);
    assertThrows(RelayReceiver.Refused.class, () -> receiver("agent.run").require(request));
    when(presences.serving("project"))
        .thenReturn(Optional.of(new Presence("remote-session", "machine", "/fixture", "project")));
    when(sessions.accountOf("remote-session")).thenReturn(Optional.of("different"));
    assertThrows(RelayReceiver.Refused.class, () -> receiver("agent.run").require(request));
    when(sessions.accountOf("remote-session")).thenReturn(Optional.of("operator"));
    receiver("agent.run").dispatch(request);
    verify(jobs)
        .submitEvent(any(), any(), any(), eq("remote-session"), any(), any(), any(), any(), any());
  }
}
