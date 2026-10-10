package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Orchestration;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.CallerAccess;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptStore;
import io.aeyer.plowshare.server.todos.StageSeeding;
import io.aeyer.plowshare.server.todos.TodoLists;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Recovery behavior through repositories and a model-free conductor; no PostgreSQL. */
class OrchestrationResumptionTest {
  private final OrchestrationStore store = mock(OrchestrationStore.class);
  private final ConversationStore conversations = mock(ConversationStore.class);
  private final ConductorVoice voice = mock(ConductorVoice.class);
  private final CallerAccess access = mock(CallerAccess.class);
  private final OrchestrationRecovery recovery = mock(OrchestrationRecovery.class);
  private final ScriptStore scripts = mock(ScriptStore.class);
  private final OrchestrationRecorder recorder = mock(OrchestrationRecorder.class);
  private final List<OrchestrationRecord> events = new ArrayList<>();
  private final List<UUID> resumptions = new ArrayList<>();
  private final Orchestration.Resume ask = new Orchestration.Resume("orc_root", UUID.randomUUID());
  private Orchestrations engine;
  private OrchestrationRecord failed;
  private OrchestrationRecord running;
  private static final String SOURCE =
      """
      ---
      name: retryable
      description: Retryable task.
      model: reasoning
      max-turns: 10
      max-model-calls: 40
      stages:
        - {id: goal}
      ---
      Conduct the task.
      """;

  private OrchestrationRecord run(OrchestrationState state, String parent) {
    return new OrchestrationRecord(
        "orc_root",
        "retryable",
        OrchestrationDefinition.Tier.PROJECT,
        "sha256:test",
        SOURCE,
        "test",
        List.of(),
        3,
        1,
        "project",
        "cnv_conductor",
        "cnv_caller",
        "assistant",
        "owner",
        "session",
        parent,
        parent == null ? 0 : 1,
        null,
        state,
        null,
        null,
        state == OrchestrationState.FAILED ? "provider unavailable" : null,
        2,
        2,
        true,
        null,
        Instant.EPOCH,
        state == OrchestrationState.FAILED ? Instant.EPOCH : null);
  }

  @BeforeEach
  void setup() {
    engine =
        new Orchestrations(
            store,
            conversations,
            mock(StageSeeding.class),
            mock(TodoLists.class),
            UnitOfWork.NONE,
            voice,
            mock(DeliveryPort.class),
            Set.of(),
            agent -> agent,
            session -> true,
            Instant::now,
            events::add,
            conversation -> {},
            3,
            access,
            recovery,
            scripts);
    engine.useResumeListener(
        (run, requestId) -> {
          assertEquals(OrchestrationState.RUNNING, store.find(run.id()).orElseThrow().state());
          resumptions.add(requestId);
        });
    engine.useRecorder(recorder);
    failed = run(OrchestrationState.FAILED, null);
    running = run(OrchestrationState.RUNNING, null);
    when(store.find(ask.id())).thenReturn(Optional.of(failed));
    var conversation = mock(ConversationRecord.class);
    when(conversation.budget()).thenReturn(Budget.of(40));
    when(conversations.find("cnv_conductor")).thenReturn(Optional.of(conversation));
    when(recovery.claim(ask.id(), "owner", ask.requestId(), Instant.EPOCH))
        .thenAnswer(
            call -> {
              when(store.find(ask.id())).thenReturn(Optional.of(running));
              return true;
            });
  }

  @Test
  void resumes_existing_run_with_pinned_conductor_and_notifies_listener_after_claim() {
    assertEquals(running, engine.resume(ask, "owner"));
    verify(access).requireWork("project", "owner");
    verify(voice)
        .speak(
            eq("cnv_conductor"),
            argThat(agent -> agent.name().equals("retryable")),
            contains("provider unavailable"),
            eq("session"),
            isNull(),
            any());
    verify(recorder).runResumed(running, "owner", "provider unavailable");
    assertEquals(List.of(running), events);
    assertEquals(List.of(ask.requestId()), resumptions);
    verify(store, never()).insert(any());
  }

  @Test
  void recorded_request_never_dispatches_again_even_after_a_second_failure() {
    when(recovery.received(ask.id(), "owner", ask.requestId())).thenReturn(true);
    assertEquals(failed, engine.resume(ask, "owner"));
    verify(recovery, never()).claim(any(), any(), any(), any());
    verifyNoInteractions(voice, scripts, recorder);
    assertTrue(events.isEmpty());
    assertTrue(resumptions.isEmpty());
  }

  @Test
  void competing_claim_loser_does_not_dispatch_or_emit() {
    when(recovery.claim(any(), any(), any(), any())).thenReturn(false);
    engine.resume(ask, "owner");
    verify(voice, never()).speak(any(), any(), any(), any(), any(), any());
    assertTrue(events.isEmpty());
    assertTrue(resumptions.isEmpty());
  }

  @Test
  void refuses_foreign_accounts_revoked_authority_nonfailed_runs_and_nested_runs() {
    assertThrows(CallerFault.class, () -> engine.resume(ask, "other"));
    doThrow(new CallerFault("revoked")).when(access).requireWork("project", "owner");
    assertThrows(CallerFault.class, () -> engine.resume(ask, "owner"));
    reset(access);
    for (var state :
        List.of(
            OrchestrationState.RUNNING,
            OrchestrationState.CANCELLED,
            OrchestrationState.FINISHED,
            OrchestrationState.CAPPED)) {
      when(store.find(ask.id())).thenReturn(Optional.of(run(state, null)));
      assertThrows(CallerFault.class, () -> engine.resume(ask, "owner"));
    }
    when(store.find(ask.id()))
        .thenReturn(Optional.of(run(OrchestrationState.FAILED, "orc_parent")));
    assertThrows(CallerFault.class, () -> engine.resume(ask, "owner"));
    verify(recovery, never()).claim(any(), any(), any(), any());
  }

  @Test
  void refuses_inflight_or_uncertain_work_before_changing_state() {
    when(voice.isSpeaking("cnv_conductor")).thenReturn(true);
    assertThrows(CallerFault.class, () -> engine.resume(ask, "owner"));
    when(voice.isSpeaking("cnv_conductor")).thenReturn(false);
    doThrow(new CallerFault("uncertain command")).when(scripts).requireRecoverable("cnv_conductor");
    assertThrows(CallerFault.class, () -> engine.resume(ask, "owner"));
    verify(recovery, never()).claim(any(), any(), any(), any());
  }

  @Test
  void refuses_spent_budget_without_resetting_accounting() {
    var conversation = mock(ConversationRecord.class);
    var budget = Budget.of(1);
    budget.trySpend();
    when(conversation.budget()).thenReturn(budget);
    when(conversations.find("cnv_conductor")).thenReturn(Optional.of(conversation));
    assertThrows(CallerFault.class, () -> engine.resume(ask, "owner"));
    assertEquals(1, budget.spent());
    verify(recovery, never()).claim(any(), any(), any(), any());
  }

  @Test
  void known_transient_delegate_continues_its_conversation_then_supplies_one_receipt() {
    when(scripts.failedDelegate("cnv_conductor"))
        .thenReturn(Optional.of(new ScriptStore.FailedDelegate("cnv_child", "worker", 4)));
    var callback = new java.util.concurrent.atomic.AtomicReference<Consumer<Outcome>>();
    doAnswer(
            call -> {
              callback.set(call.getArgument(6));
              return "job_child";
            })
        .when(voice)
        .resumeDelegate(
            eq("cnv_child"),
            eq("worker"),
            eq("cnv_conductor"),
            anyString(),
            eq("owner"),
            eq("session"),
            any());
    engine.resume(ask, "owner");
    verify(voice, never()).speak(any(), any(), any(), any(), any(), any());
    callback.get().accept(new Outcome(Outcome.Ending.ANSWERED, "recovered result", 1, 1, ""));
    verify(scripts).executed("cnv_conductor", 4, "recovered result");
    verify(voice).speak(eq("cnv_conductor"), any(), anyString(), eq("session"), isNull(), any());
  }
}
