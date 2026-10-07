package io.aeyer.plowshare.server.relay;

import static io.aeyer.plowshare.server.relay.RelayDispatchFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayCausation;
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
  private final RelayForwardingHistory history = mock(RelayForwardingHistory.class);
  private AgentDefinition agent;

  @BeforeEach
  void setup() {
    when(history.causation(any(), eq(8)))
        .thenAnswer(
            call -> {
              Relay.Publication input = call.getArgument(0);
              if (input.event().causation() != null) return Optional.of(input.event().causation());
              if (input.event().payload() instanceof RelayPayload.Lifecycle)
                return Optional.empty();
              return Optional.of(RelayCausation.root(input.event().eventId()));
            });
    agent = definition("worker");
    when(projects.id("project")).thenReturn(9L);
    when(projects.find("project"))
        .thenReturn(
            Optional.of(
                new io.aeyer.plowshare.server.archive.ProjectRecord(
                    "project", java.nio.file.Path.of("/fixture"), List.of(), List.of())));
    when(callers.requireAgent(eq("worker"), any())).thenReturn(agent);
    when(callers.requireAgent(eq("reviewer"), any())).thenReturn(definition("reviewer"));
    when(executions.begin(any())).thenReturn(true);
    when(jobs.submitEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new JobStore.EventRun("job_fixture", "cnv_fixture"));
  }

  private static AgentDefinition definition(String name) {
    return new AgentDefinition(
        name,
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
        routing,
        history,
        8);
  }

  private RelayReceiver.Request request(String name, String project, String source) {
    return request(
        name,
        project,
        source,
        delivery(RelayDeliveries.State.DISPATCHING, false).publication(),
        RelayDeliveries.State.DISPATCHING,
        1);
  }

  private RelayReceiver.Request request(
      String name,
      String project,
      String source,
      Relay.Publication input,
      RelayDeliveries.State state,
      long fence) {
    var original = delivery(state, false);
    var branch =
        new RelayDeliveries.Branch(
            "run",
            name,
            source == null
                ? null
                : RelayDeliveries.SourcePin.of("notices/scripts/review.js", source),
            null,
            new RelayWork(
                input.event().payload() instanceof RelayPayload.Lifecycle change
                        && "reviewer".equals(change.context())
                    ? "reviewer"
                    : "worker",
                project,
                name.equals("orchestration.start") ? "review" : null));
    var subscription = new Relay.SubscriptionKey(input.topic(), SUB.subscriber());
    var changed =
        new RelayDeliveries.Delivery(
            new RelayDeliveries.DeliveryKey(subscription, original.key().id()),
            new RelayDeliveries.AdmissionKey(subscription, input.position()),
            input,
            original.routing(),
            branch,
            original.state(),
            fence,
            original.worker(),
            original.leaseUntil(),
            original.admittedAt(),
            original.updatedAt(),
            original.receipt(),
            original.failureCode());
    var access =
        input.topic().projectId() == 10
            ? new RelayProjectFiles.Access("operator", "other", 10)
            : ACCESS;
    return new RelayReceiver.Request(access, changed);
  }

  @Test
  void review_requests_and_sdk_reply_descendants_cannot_start_native_work() {
    var root = RelayReviewCausation.root("00000000-0000-4000-8000-000000000001");
    var definition = mock(OrchestrationDefinition.class);
    when(grants.granted(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("review", definition));
    for (var cause : List.of(root, root.next("review-request", 8))) {
      var input =
          new Relay.Publication(
              SUB.topic(),
              1,
              java.time.Instant.EPOCH,
              new Relay.Draft(
                  "review-event",
                  "reviewer",
                  java.time.Instant.EPOCH,
                  null,
                  cause.parentId(),
                  new RelayPayload.Text("held review"),
                  cause));
      for (var name : List.of("agent.run", "script.run", "orchestration.start")) {
        var request =
            request(
                name,
                null,
                name.equals("script.run")
                    ? "// plowshare-script v1\nexport const manifest={};export function step(input){return {state:null,command:{finish:'done'}};}"
                    : null,
                input,
                RelayDeliveries.State.DISPATCHING,
                1);
        assertEquals(
            "receiver.review-protocol.refused",
            assertThrows(RelayReceiver.Refused.class, () -> receiver(name).require(request))
                .code());
        assertEquals(
            "receiver.review-protocol.refused",
            assertThrows(RelayReceiver.Refused.class, () -> receiver(name).dispatch(request))
                .code());
      }
    }
    verify(executions, never()).begin(any());
    verifyNoInteractions(jobs, starts);
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
            eq(Speaker.relay(request.identity())),
            any(),
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
        .submitEvent(
            captured.capture(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
    assertEquals(Speaker.relay(request.identity()), captured.getValue().source());
    assertEquals("operator", captured.getValue().callerHandle());
    assertEquals("worker", captured.getValue().callerAgent());
    assertNull(captured.getValue().callerConversation());
    assertNull(captured.getValue().parent());
    verifyNoInteractions(jobs);
    when(runs.find("orc_fixture")).thenReturn(Optional.of(run));
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
  void repeated_inspection_repairs_the_owning_receipt_without_starting_work() {
    var request = request("orchestration.start", null, null);
    when(grants.granted(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("review", mock(OrchestrationDefinition.class)));
    var receipt = new RelayDeliveries.Receipt("orchestration", "orc_fixture");
    var run = mock(OrchestrationRecord.class);
    when(run.conductorConversation()).thenReturn("cnv_fixture");
    when(runs.find("orc_fixture")).thenReturn(Optional.of(run));
    when(runs.startReceipt("operator", request.identity()))
        .thenReturn(
            Optional.of(
                new OrchestrationStore.StartReceipt(request.identity(), "orc_fixture", false)));
    for (int attempt = 0; attempt < 3; attempt++) {
      assertEquals(
          Optional.of(new RelayDeliveries.Accepted(receipt)),
          receiver("orchestration.start").inspect(request));
    }
    verify(executions, times(3)).accepted(request, receipt, "cnv_fixture");
    verifyNoInteractions(starts, jobs);
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
        .submitEvent(
            any(), any(), any(), eq("remote-session"), any(), any(), any(), any(), any(), any());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"self", "two-agents", "cross-project", "mixed"})
  void lifecycle_feedback_and_mixed_chains_share_one_limit_across_receiver_restarts(String mode) {
    when(projects.id("other")).thenReturn(10L);
    var availableProject = projects.find("project");
    when(projects.find("other")).thenReturn(availableProject);
    var relay = mock(Relay.class);
    var forwarded = new Relay.TopicKey(9, "release.forwarded");
    when(relay.topic(forwarded))
        .thenReturn(
            new Relay.Topic(
                forwarded, RelayPayload.Kind.LIFECYCLE, Relay.Policy.systemDefault(), 0, 0));
    var publication = new java.util.concurrent.atomic.AtomicReference<Relay.Publication>();
    when(relay.publish(eq(forwarded), any()))
        .thenAnswer(
            call -> {
              var result =
                  new Relay.Publication(forwarded, 1, java.time.Instant.EPOCH, call.getArgument(1));
              publication.set(result);
              return result;
            });
    var submitted = new java.util.concurrent.atomic.AtomicReference<RelayCausation>();
    when(jobs.submitEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              submitted.set(call.getArgument(9));
              return new JobStore.EventRun("job_fixture", "cnv_fixture");
            });
    var input = lifecycle("independent", "worker", RelayCausation.root("independent"));
    int effects = 0, launched = 0;
    while (effects < 8) {
      var next =
          request(
              "agent.run",
              mode.equals("cross-project")
                  ? (input.topic().projectId() == 9 ? "other" : "project")
                  : null,
              null,
              input,
              RelayDeliveries.State.DISPATCHING,
              effects + 1);
      // Recreate the adapter every turn, and change the fence: no in-memory counter survives.
      receiver("agent.run").require(next);
      receiver("agent.run").dispatch(next);
      effects++;
      launched++;
      assertEquals(effects, submitted.get().depth());
      assertEquals("independent", submitted.get().rootId());
      assertEquals(input.event().eventId(), submitted.get().parentId());
      input =
          lifecycle(
              "ended-" + effects,
              mode.equals("two-agents") && launched % 2 == 1 ? "reviewer" : "worker",
              submitted.get());
      if (mode.equals("cross-project"))
        input =
            new Relay.Publication(
                new Relay.TopicKey(launched % 2 == 1 ? 10 : 9, SUB.topic().name()),
                input.position(),
                input.publishedAt(),
                input.event());
      if (mode.equals("mixed")) {
        var raw = delivery(RelayDeliveries.State.DISPATCHING, false);
        var forwardRequest =
            new RelayReceiver.Request(
                ACCESS,
                new RelayDeliveries.Delivery(
                    new RelayDeliveries.DeliveryKey(
                        new Relay.SubscriptionKey(input.topic(), SUB.subscriber()), raw.key().id()),
                    new RelayDeliveries.AdmissionKey(
                        new Relay.SubscriptionKey(input.topic(), SUB.subscriber()),
                        input.position()),
                    input,
                    raw.routing(),
                    raw.branch(),
                    raw.state(),
                    effects + 1,
                    raw.worker(),
                    raw.leaseUntil(),
                    raw.admittedAt(),
                    raw.updatedAt(),
                    null,
                    null));
        var adapter = new ForwardRelayReceiver(relay, history, 8);
        adapter.dispatch(forwardRequest);
        effects++;
        input = publication.get();
      }
    }
    var exhausted = request("agent.run", null, null, input, RelayDeliveries.State.DISPATCHING, 99);
    var refusal =
        assertThrows(RelayReceiver.Refused.class, () -> receiver("agent.run").dispatch(exhausted));
    assertEquals("receiver.causation-limit.refused", refusal.code());
    verify(jobs, times(launched))
        .submitEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    // Fresh independent jobs remain eligible after the exhausted chain.
    var independent =
        request(
            "agent.run",
            null,
            null,
            lifecycle("new-job", "worker", RelayCausation.root("new-job")),
            RelayDeliveries.State.DISPATCHING,
            100);
    receiver("agent.run").dispatch(independent);
    assertEquals(1, submitted.get().depth());
    assertEquals("new-job", submitted.get().rootId());
  }

  private static Relay.Publication lifecycle(String id, String agent, RelayCausation cause) {
    return new Relay.Publication(
        SUB.topic(),
        1,
        java.time.Instant.EPOCH,
        new Relay.Draft(
            id,
            "system.source",
            java.time.Instant.EPOCH,
            null,
            cause == null ? null : cause.parentId(),
            new RelayPayload.Lifecycle("job.ended", "job_fixture", "ANSWERED", agent, null),
            cause));
  }

  @Test
  void
      missing_or_unknown_ancestry_refuses_work_and_uncertain_receipts_are_read_only_beyond_the_limit() {
    for (var cause : java.util.Arrays.asList(null, RelayCausation.unknown("legacy"))) {
      var missing =
          request(
              "agent.run",
              null,
              null,
              lifecycle("old", "worker", cause),
              RelayDeliveries.State.DISPATCHING,
              1);
      assertEquals(
          "receiver.causation-unavailable",
          assertThrows(RelayReceiver.Refused.class, () -> receiver("agent.run").dispatch(missing))
              .code());
    }
    verifyNoInteractions(jobs, starts);
    var cause = new RelayCausation("root", "parent", 8);
    var uncertain =
        request(
            "agent.run",
            null,
            null,
            lifecycle("end", "worker", cause),
            RelayDeliveries.State.UNCERTAIN,
            20);
    when(executions.find("operator", 9, uncertain.identity()))
        .thenReturn(
            Optional.of(
                new RelayExecutions.Accepted(
                    new RelayDeliveries.Receipt("job", "job_retained"), "cnv_fixture")));
    clearInvocations(history);
    receiver("agent.run").require(uncertain);
    assertTrue(receiver("agent.run").inspect(uncertain).isPresent());
    assertThrows(IllegalArgumentException.class, () -> receiver("agent.run").dispatch(uncertain));
    verifyNoInteractions(history, jobs, starts);
  }
}
