package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationRegistry;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.TurnEnd;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CallerOrchestrationsTest {

  private static final String SOURCE =
      """
            ---
            name: code_implementation
            description: Takes a change to reviewed code.
            model: reasoning
            max-turns: 10
            max-model-calls: 40
            stages:
              - {id: goal}
              - {id: code}
            ---
            Conduct the implementation.
            """;

  private OrchestrationResolver resolver;
  private Callers callers;
  private Orchestrations engine;
  private OrchestrationCancel cancel;
  private OrchestrationStore store;
  private TodoLists todos;
  private OrchestrationDefinition definition;
  private DefinitionResolver.Caller resolvedCaller;
  private CallerOrchestrations access;

  @BeforeEach
  void setUp() {
    resolver = mock(OrchestrationResolver.class);
    callers = mock(Callers.class);
    engine = mock(Orchestrations.class);
    cancel = mock(OrchestrationCancel.class);
    store = mock(OrchestrationStore.class);
    todos = mock(TodoLists.class);
    definition =
        OrchestrationRegistry.parsePinned(
            "code_implementation", "test", SOURCE, Set.of(), OrchestrationDefinition.Tier.PROJECT);
    resolvedCaller = new DefinitionResolver.Caller(7L, "ses_1");
    when(callers.callerFor("story", "ses_1")).thenReturn(resolvedCaller);
    when(callers.callerForConversation("cnv_person", "ses_1")).thenReturn(resolvedCaller);
    when(callers.callerForConversation("cnv_conductor", "ses_1")).thenReturn(resolvedCaller);
    when(resolver.forCaller(resolvedCaller)).thenReturn(Map.of(definition.name(), definition));
    access = new CallerOrchestrations(resolver, callers, engine, cancel, store, todos);
  }

  @Test
  void a_grant_offers_one_named_start_tool_and_the_three_lifecycle_tools() {
    RunExtras.Extras extras = access.forRun(context("enzo"));

    assertEquals(
        List.of(
            "orchestrate_code_implementation",
            "orchestration_answer",
            "orchestration_status",
            "orchestration_cancel"),
        extras.tools().stream().map(tool -> tool.schema().name()).toList());
    assertTrue(extras.tools().get(0).schema().description().contains("goal, code"));
  }

  @Test
  void a_temporarily_unavailable_grant_keeps_the_lifecycle_tools_for_existing_runs() {
    when(resolver.forCaller(resolvedCaller)).thenReturn(Map.of());

    RunExtras.Extras extras = access.forRun(context("enzo"));

    assertEquals(
        List.of("orchestration_answer", "orchestration_status", "orchestration_cancel"),
        extras.tools().stream().map(tool -> tool.schema().name()).toList());
  }

  @Test
  void a_bound_start_uses_the_atomic_receipt_and_checks_its_exact_definition() {
    java.util.UUID invocation = java.util.UUID.randomUUID();
    OrchestrationRecord started = mock(OrchestrationRecord.class);
    when(started.id()).thenReturn("orc_bound");
    when(engine.start(any(), eq(invocation), any())).thenReturn(started);
    var operation =
        (io.aeyer.plowshare.server.agents.BoundCommandTool)
            tool(context("enzo", "cnv_person"), "orchestrate_code_implementation");
    assertTrue(
        operation
            .runBound(invocation, definition.hash(), "original request", Home.of("story"))
            .contains("orc_bound"));
    var payload = ArgumentCaptor.forClass(String.class);
    verify(engine).start(any(), eq(invocation), payload.capture());
    assertTrue(payload.getValue().contains("original request"));
    assertTrue(payload.getValue().contains(definition.hash()));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () ->
            operation.runBound(invocation, "changed source", "original request", Home.of("story")));
    verify(engine, org.mockito.Mockito.times(1)).start(any(), eq(invocation), any());
  }

  @Test
  void start_pins_the_callers_run_context_and_returns_the_handle_and_stages() {
    OrchestrationRecord started = mock(OrchestrationRecord.class);
    when(started.id()).thenReturn("orc_123");
    when(engine.start(any())).thenReturn(started);
    AgentTool tool = tool(context("enzo", "cnv_person"), "orchestrate_code_implementation");

    String result =
        tool.run("{\"request\":\"build it\",\"context\":\"approved spec\"}", Home.of("story"));

    assertEquals(
        "{\"id\":\"orc_123\",\"stages\":[\"goal\",\"code\"],\"note\":\""
            + CallerOrchestrations.ROOT_CANNOT_WAIT
            + "\"}",
        result);
    ArgumentCaptor<Orchestrations.Start> capture =
        ArgumentCaptor.forClass(Orchestrations.Start.class);
    verify(engine).start(capture.capture());
    Orchestrations.Start start = capture.getValue();
    assertEquals("build it", start.request());
    assertEquals("approved spec", start.context());
    assertEquals("cnv_person", start.callerConversation());
    assertEquals("interlocutor", start.callerAgent());
    assertEquals("enzo", start.callerHandle());
    assertEquals("ses_1", start.callerSession());
    assertEquals(Home.of("story"), start.home());
  }

  @Test
  void a_conductor_starting_a_child_goes_through_the_nested_door() {
    OrchestrationRecord parent = run("enzo", OrchestrationState.RUNNING);
    when(store.byConductorConversation("cnv_conductor")).thenReturn(java.util.Optional.of(parent));
    OrchestrationRecord child = mock(OrchestrationRecord.class);
    when(child.id()).thenReturn("orc_child");
    when(engine.startNested("orc_own", definition, "do it", null, true))
        .thenReturn(new Orchestrations.Started(null, child, true));
    AgentTool tool = tool(context("enzo", "cnv_conductor"), "orchestrate_code_implementation");

    String result = tool.run("{\"request\":\"do it\"}", Home.of("story"));

    assertEquals("{\"id\":\"orc_child\",\"stages\":[\"goal\",\"code\"]}", result);
    verify(engine, never()).start(any());
  }

  @Test
  void a_wait_that_lost_because_the_child_already_ended_says_so() {
    OrchestrationRecord parent = run("enzo", OrchestrationState.RUNNING);
    when(store.byConductorConversation("cnv_conductor")).thenReturn(java.util.Optional.of(parent));
    OrchestrationRecord child = mock(OrchestrationRecord.class);
    when(child.id()).thenReturn("orc_child");
    when(engine.startNested("orc_own", definition, "do it", null, true))
        .thenReturn(new Orchestrations.Started(null, child, false));
    AgentTool tool = tool(context("enzo", "cnv_conductor"), "orchestrate_code_implementation");

    String result = tool.run("{\"request\":\"do it\"}", Home.of("story"));

    assertEquals(
        "{\"id\":\"orc_child\",\"stages\":[\"goal\",\"code\"],\"note\":\""
            + CallerOrchestrations.CHILD_ALREADY_ENDED_BEFORE_THE_WAIT
            + "\"}",
        result);
  }

  @Test
  void a_wait_false_child_that_already_ended_gets_no_note() {
    OrchestrationRecord parent = run("enzo", OrchestrationState.RUNNING);
    when(store.byConductorConversation("cnv_conductor")).thenReturn(java.util.Optional.of(parent));
    OrchestrationRecord child = mock(OrchestrationRecord.class);
    when(child.id()).thenReturn("orc_child");
    when(engine.startNested("orc_own", definition, "do it", null, false))
        .thenReturn(new Orchestrations.Started(null, child, false));
    AgentTool tool = tool(context("enzo", "cnv_conductor"), "orchestrate_code_implementation");

    String result = tool.run("{\"request\":\"do it\",\"wait\":false}", Home.of("story"));

    assertEquals("{\"id\":\"orc_child\",\"stages\":[\"goal\",\"code\"]}", result);
  }

  @Test
  void a_terminal_conductor_run_is_refused_rather_than_starting_an_unparented_root() {
    OrchestrationRecord endedParent = run("enzo", OrchestrationState.FINISHED);
    when(store.byConductorConversation("cnv_conductor"))
        .thenReturn(java.util.Optional.of(endedParent));
    when(engine.startNested("orc_own", definition, "do it", null, true))
        .thenReturn(
            new Orchestrations.Started(
                "this orchestration is finished, so it cannot start a child", null, false));
    AgentTool tool = tool(context("enzo", "cnv_conductor"), "orchestrate_code_implementation");

    // A root start here would be a run with no parent, a dead ORCHESTRATION caller
    // conversation and usually no handle: nothing could cascade to it, answer it or see it.
    String result = tool.run("{\"request\":\"do it\"}", Home.of("story"));

    assertEquals("this orchestration is finished, so it cannot start a child", result);
    verify(engine, never()).start(any());
  }

  @Test
  void a_null_conversation_never_looks_for_a_conductor_run() {
    OrchestrationRecord started = mock(OrchestrationRecord.class);
    when(started.id()).thenReturn("orc_123");
    when(engine.start(any())).thenReturn(started);
    AgentTool tool = tool(context("enzo", null), "orchestrate_code_implementation");

    String result = tool.run("{\"request\":\"do it\"}", Home.of("story"));

    assertEquals(
        "{\"id\":\"orc_123\",\"stages\":[\"goal\",\"code\"],\"note\":\""
            + CallerOrchestrations.ROOT_CANNOT_WAIT
            + "\"}",
        result);
    verify(store, never()).byConductorConversation(any());
    verify(engine, never()).startNested(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void a_nested_start_that_is_refused_answers_with_the_refusal() {
    OrchestrationRecord parent = run("enzo", OrchestrationState.RUNNING);
    when(store.byConductorConversation("cnv_conductor")).thenReturn(java.util.Optional.of(parent));
    when(engine.startNested("orc_own", definition, "do it", null, true))
        .thenReturn(
            new Orchestrations.Started(
                "this orchestration is already 2 deep, and nesting stops at 2. Do this"
                    + " work yourself, or with agent_run.",
                null,
                false));
    AgentTool tool = tool(context("enzo", "cnv_conductor"), "orchestrate_code_implementation");

    String result = tool.run("{\"request\":\"do it\"}", Home.of("story"));

    assertEquals(
        "this orchestration is already 2 deep, and nesting stops at 2. Do this work"
            + " yourself, or with agent_run.",
        result);
    verify(engine, never()).start(any());
  }

  @Test
  void a_plain_caller_still_starts_a_root_and_is_told_wait_does_not_apply() {
    OrchestrationRecord started = mock(OrchestrationRecord.class);
    when(started.id()).thenReturn("orc_123");
    when(engine.start(any())).thenReturn(started);
    AgentTool tool = tool(context("enzo", "cnv_person"), "orchestrate_code_implementation");

    String result = tool.run("{\"request\":\"build it\",\"wait\":false}", Home.of("story"));

    assertEquals(
        "{\"id\":\"orc_123\",\"stages\":[\"goal\",\"code\"],\"note\":\""
            + CallerOrchestrations.ROOT_CANNOT_WAIT
            + "\"}",
        result);
    verify(engine).start(any());
    verify(engine, never()).startNested(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void wait_defaults_to_true() {
    OrchestrationRecord parent = run("enzo", OrchestrationState.RUNNING);
    when(store.byConductorConversation("cnv_conductor")).thenReturn(java.util.Optional.of(parent));
    OrchestrationRecord child = mock(OrchestrationRecord.class);
    when(child.id()).thenReturn("orc_child");
    when(engine.startNested("orc_own", definition, "do it", null, true))
        .thenReturn(new Orchestrations.Started(null, child, true));
    AgentTool tool = tool(context("enzo", "cnv_conductor"), "orchestrate_code_implementation");

    tool.run("{\"request\":\"do it\"}", Home.of("story"));

    verify(engine).startNested("orc_own", definition, "do it", null, true);
  }

  @Test
  void lifecycle_tools_do_not_reveal_or_change_another_accounts_run() {
    OrchestrationRecord run = run("mallory", OrchestrationState.ASKING);
    when(store.find("orc_other")).thenReturn(java.util.Optional.of(run));

    String answer =
        tool(context("enzo"), "orchestration_answer")
            .run("{\"id\":\"orc_other\",\"answer\":\"yes\"}", Home.of("story"));
    String status =
        tool(context("enzo"), "orchestration_status")
            .run("{\"id\":\"orc_other\"}", Home.of("story"));
    String cancelled =
        tool(context("enzo"), "orchestration_cancel")
            .run("{\"id\":\"orc_other\"}", Home.of("story"));

    assertEquals("No orchestration with that id is owned by this account.", answer);
    assertEquals(answer, status);
    assertEquals(answer, cancelled);
    verify(engine, never()).answerAsModel(any(), any(), any());
    verify(cancel, never()).cancel(any(), any());
    verify(cancel, never()).cancelUnlessRunning(any(), any());
  }

  @Test
  void a_conductor_turn_with_no_handle_of_its_own_owns_its_tree_through_its_row() {
    // Turn.speakToConductor submits no caller handle and a background conductor turn has no
    // live session for JobRuntime to fall back to, so the account is only on the row.
    OrchestrationRecord own = run("enzo", OrchestrationState.WAITING);
    when(store.byConductorConversation("cnv_conductor")).thenReturn(java.util.Optional.of(own));
    when(store.find("orc_child"))
        .thenReturn(
            java.util.Optional.of(
                child("orc_child", "cnv_child", "enzo", OrchestrationState.ASKING, "orc_own")));
    when(store.find("orc_mallorys"))
        .thenReturn(
            java.util.Optional.of(
                row("orc_mallorys", "cnv_mallorys", "mallory", OrchestrationState.ASKING)));
    when(store.messages("orc_child")).thenReturn(List.of());
    when(todos.list("cnv_child")).thenReturn(List.of());
    when(store.children("orc_child")).thenReturn(List.of());
    when(engine.answerAsModel("orc_child", "Postgres", "interlocutor")).thenReturn(true);
    when(cancel.cancelOwnChild("orc_child", "interlocutor")).thenReturn(true);
    RunExtras.Context conductor = context(null, "cnv_conductor");

    assertTrue(
        tool(conductor, "orchestration_answer")
            .run("{\"id\":\"orc_child\",\"answer\":\"Postgres\"}", Home.of("story"))
            .startsWith("Answered"));
    assertTrue(
        tool(conductor, "orchestration_status")
            .run("{\"id\":\"orc_child\"}", Home.of("story"))
            .contains("\"id\":\"orc_child\""));
    assertTrue(
        tool(conductor, "orchestration_cancel")
            .run("{\"id\":\"orc_child\"}", Home.of("story"))
            .startsWith("Cancelled"));
    // Reading the account off the row is not a way past it: another account's run is still not
    // this tree's.
    assertTrue(
        tool(conductor, "orchestration_answer")
            .run("{\"id\":\"orc_mallorys\",\"answer\":\"yes\"}", Home.of("story"))
            .contains("not one this run started"));
    verify(engine).answerAsModel("orc_child", "Postgres", "interlocutor");
    verify(cancel).cancelOwnChild("orc_child", "interlocutor");
    verify(cancel, never()).cancel(any(), any());
  }

  /**
   * Spec 2026-09-28: a run that ended three turns without progress asks the person whether it goes
   * on, and a model — the bot that started it, or a parent conductor — is refused an answer, with
   * the question, so it can tell the person rather than decide for them.
   */
  @Test
  void a_model_s_answer_to_a_question_only_the_person_may_answer_is_refused() {
    OrchestrationRecord run = startedBy("cnv_person", OrchestrationState.ASKING);
    when(store.find("orc_run")).thenReturn(java.util.Optional.of(run));
    when(engine.personOnlyQuestion("orc_run"))
        .thenReturn(
            java.util.Optional.of(
                "`orc_run` (`code_implementation`) ended 3 turns in a row"
                    + " without making progress."));

    String answered =
        tool(context("enzo", "cnv_person"), "orchestration_answer")
            .run("{\"id\":\"orc_run\",\"answer\":\"go on\"}", Home.of("story"));

    assertEquals(
        "Only the person can answer this: `orc_run` (`code_implementation`) ended 3"
            + " turns in a row without making progress. Tell the person; do not decide it for"
            + " them. Nothing changed.",
        answered);
    verify(engine, never()).answerAsModel(any(), any(), any());
  }

  /**
   * The same refusal when the run went stuck between the model's read and its answer: the store
   * refuses inside its lock, and the question is read again for the sentence.
   */
  @Test
  void a_model_s_answer_that_loses_to_a_stuck_question_is_refused_the_same_way() {
    OrchestrationRecord run = startedBy("cnv_person", OrchestrationState.ASKING);
    when(store.find("orc_run")).thenReturn(java.util.Optional.of(run));
    when(engine.personOnlyQuestion("orc_run"))
        .thenReturn(java.util.Optional.empty())
        .thenReturn(java.util.Optional.of("it is stuck"));
    when(engine.answerAsModel("orc_run", "go on", "interlocutor")).thenReturn(false);

    String answered =
        tool(context("enzo", "cnv_person"), "orchestration_answer")
            .run("{\"id\":\"orc_run\",\"answer\":\"go on\"}", Home.of("story"));

    assertTrue(answered.startsWith("Only the person can answer this: it is stuck."), answered);
  }

  /**
   * Spec 2026-09-29 §2: a cap question reached the person too, and the person answered first; the
   * parent's late answer is told whose answer settled it, so it does not guess.
   */
  @Test
  void a_conductor_answering_its_child_s_cap_after_the_person_did_is_told_who_answered() {
    when(store.byConductorConversation("cnv_conductor"))
        .thenReturn(java.util.Optional.of(run("enzo", OrchestrationState.WAITING)));
    when(store.find("orc_child"))
        .thenReturn(
            java.util.Optional.of(
                child("orc_child", "cnv_child", "enzo", OrchestrationState.RUNNING, "orc_own")));
    when(engine.answerAsModel("orc_child", "no", "interlocutor")).thenReturn(false);
    when(engine.answeredAlready("orc_child"))
        .thenReturn(
            java.util.Optional.of(
                "Orchestration orc_child's question was already answered by enzo (`yes`); nothing"
                    + " changed."));

    String said =
        tool(context(null, "cnv_conductor"), "orchestration_answer")
            .run("{\"id\":\"orc_child\",\"answer\":\"no\"}", Home.of("story"));

    assertEquals(
        "Orchestration orc_child's question was already answered by enzo (`yes`);"
            + " nothing changed.",
        said);
  }

  /**
   * A model may cancel an asking run (rule 2), but not one asking the person about being stuck:
   * that decision is theirs whichever way it goes.
   */
  @Test
  void a_model_may_not_cancel_a_run_asking_the_person_about_being_stuck() {
    OrchestrationRecord run = stuck(startedBy("cnv_person", OrchestrationState.ASKING));
    when(store.find("orc_run")).thenReturn(java.util.Optional.of(run));

    String said =
        tool(context("enzo", "cnv_person"), "orchestration_cancel")
            .run("{\"id\":\"orc_run\"}", Home.of("story"));

    assertEquals(
        "Only the person can stop orc_run while it asks about being stuck:" + " /cancel orc_run.",
        said);
    verify(cancel, never()).cancelUnlessRunning(any(), any());
    verify(cancel, never()).cancel(any(), any());
  }

  @Test
  void a_conductor_may_not_cancel_its_child_asking_the_person_about_being_stuck() {
    when(store.byConductorConversation("cnv_conductor"))
        .thenReturn(java.util.Optional.of(run("enzo", OrchestrationState.WAITING)));
    when(store.find("orc_child"))
        .thenReturn(
            java.util.Optional.of(
                stuck(
                    child(
                        "orc_child", "cnv_child", "enzo", OrchestrationState.ASKING, "orc_own"))));

    String said =
        tool(context(null, "cnv_conductor"), "orchestration_cancel")
            .run("{\"id\":\"orc_child\"}", Home.of("story"));

    assertEquals(
        "Only the person can stop orc_child while it asks about being stuck:"
            + " /cancel orc_child.",
        said);
    verify(cancel, never()).cancelOwnChild(any(), any());
  }

  /**
   * A run that went stuck between the model's read and its cancel: the store's model stop refuses
   * it, and the re-read names why.
   */
  @Test
  void a_model_s_cancel_that_loses_to_a_stuck_question_is_refused_the_same_way() {
    OrchestrationRecord asking = startedBy("cnv_person", OrchestrationState.ASKING);
    when(store.find("orc_run"))
        .thenReturn(java.util.Optional.of(asking))
        .thenReturn(java.util.Optional.of(stuck(asking)));
    when(cancel.cancelUnlessRunning("orc_run", "interlocutor")).thenReturn(false);

    String said =
        tool(context("enzo", "cnv_person"), "orchestration_cancel")
            .run("{\"id\":\"orc_run\"}", Home.of("story"));

    assertEquals(
        "Only the person can stop orc_run while it asks about being stuck:" + " /cancel orc_run.",
        said);
  }

  /** A bot's cancel of a root with a phase asking about being stuck would cascade to it. */
  @Test
  void a_model_may_not_cancel_a_run_with_a_phase_asking_about_being_stuck() {
    OrchestrationRecord root = startedBy("cnv_person", OrchestrationState.WAITING);
    when(store.find("orc_run")).thenReturn(java.util.Optional.of(root));
    when(engine.stuckBelow("orc_run")).thenReturn(java.util.Optional.of("orc_phase"));

    String said =
        tool(context("enzo", "cnv_person"), "orchestration_cancel")
            .run("{\"id\":\"orc_run\"}", Home.of("story"));

    assertEquals(
        "Only the person can stop orc_run while orc_phase asks about being stuck:"
            + " /cancel orc_run.",
        said);
    verify(cancel, never()).cancelUnlessRunning(any(), any());
  }

  @Test
  void a_conductor_may_not_cancel_its_middle_child_over_a_stuck_grandchild() {
    when(store.byConductorConversation("cnv_conductor"))
        .thenReturn(java.util.Optional.of(run("enzo", OrchestrationState.WAITING)));
    when(store.find("orc_child"))
        .thenReturn(
            java.util.Optional.of(
                child("orc_child", "cnv_child", "enzo", OrchestrationState.WAITING, "orc_own")));
    when(engine.stuckBelow("orc_child")).thenReturn(java.util.Optional.of("orc_grandchild"));

    String said =
        tool(context(null, "cnv_conductor"), "orchestration_cancel")
            .run("{\"id\":\"orc_child\"}", Home.of("story"));

    assertEquals(
        "Only the person can stop orc_child while orc_grandchild asks about being"
            + " stuck: /cancel orc_child.",
        said);
    verify(cancel, never()).cancelOwnChild(any(), any());
  }

  /**
   * A parent conductor refused an answer to its phase's stuck question is told to wait, not to tell
   * a person it has no way to reach.
   */
  @Test
  void a_conductor_refused_an_answer_to_its_stuck_phase_is_told_to_wait() {
    when(store.byConductorConversation("cnv_conductor"))
        .thenReturn(java.util.Optional.of(run("enzo", OrchestrationState.WAITING)));
    when(store.find("orc_child"))
        .thenReturn(
            java.util.Optional.of(
                stuck(
                    child(
                        "orc_child", "cnv_child", "enzo", OrchestrationState.ASKING, "orc_own"))));
    when(engine.personOnlyQuestion("orc_child")).thenReturn(java.util.Optional.of("go on?"));

    String said =
        tool(context(null, "cnv_conductor"), "orchestration_answer")
            .run("{\"id\":\"orc_child\",\"answer\":\"yes\"}", Home.of("story"));

    assertTrue(said.contains("The person has been asked directly. Wait for the phase"), said);
    assertFalse(said.contains("Tell the person"), said);
    verify(engine, never()).answerAsModel(any(), any(), any());
  }

  /**
   * V65: nor one asking the person whether its acceptance commands stand, which a model may neither
   * answer nor settle the other way.
   */
  @Test
  void a_model_may_not_answer_or_cancel_a_run_asking_the_person_about_the_verifier() {
    OrchestrationRecord run =
        asking(startedBy("cnv_person", OrchestrationState.ASKING), Orchestrations.UNCOVERED);
    when(store.find("orc_run")).thenReturn(java.util.Optional.of(run));
    when(engine.personOnlyQuestion("orc_run"))
        .thenReturn(java.util.Optional.of("does spec.md stand?"));

    String cancelled =
        tool(context("enzo", "cnv_person"), "orchestration_cancel")
            .run("{\"id\":\"orc_run\"}", Home.of("story"));
    String answered =
        tool(context("enzo", "cnv_person"), "orchestration_answer")
            .run("{\"id\":\"orc_run\",\"answer\":\"accept\"}", Home.of("story"));

    assertEquals(
        "Only the person can stop orc_run while it asks the person whether its"
            + " acceptance commands stand: /cancel orc_run.",
        cancelled);
    assertTrue(
        answered.startsWith("Only the person can answer this: does spec.md stand?"), answered);
    verify(cancel, never()).cancelUnlessRunning(any(), any());
    verify(engine, never()).answerAsModel(any(), any(), any());
  }

  /** {@code run}, asking the person about being stuck. */
  private static OrchestrationRecord stuck(OrchestrationRecord run) {
    return asking(run, Orchestrations.STUCK);
  }

  /** {@code run}, asking the person the harness question {@code kind}. */
  private static OrchestrationRecord asking(OrchestrationRecord run, String kind) {
    return new OrchestrationRecord(
        run.id(),
        run.definitionName(),
        run.tier(),
        run.definitionHash(),
        run.definitionSource(),
        run.definitionOrigin(),
        run.stages(),
        run.maxReturns(),
        run.returnsUsed(),
        run.project(),
        run.conductorConversation(),
        run.callerConversation(),
        run.callerAgent(),
        run.callerHandle(),
        run.callerSession(),
        run.parent(),
        run.depth(),
        run.waitingFor(),
        OrchestrationState.ASKING,
        kind,
        null,
        null,
        0,
        3,
        true,
        null,
        run.createdAt(),
        null);
  }

  /**
   * A conductor reached every run on its account: it could cancel a sibling phase, another tree, or
   * a run the person started by hand, and answer any of their questions. The runs it started are
   * the only ones it has any business with, and the only ones it is told about.
   */
  @Test
  void a_conductor_reaches_only_the_runs_it_started_even_on_its_own_account() {
    when(store.byConductorConversation("cnv_conductor"))
        .thenReturn(java.util.Optional.of(run("enzo", OrchestrationState.RUNNING)));
    when(store.find("orc_sibling"))
        .thenReturn(
            java.util.Optional.of(
                child(
                    "orc_sibling",
                    "cnv_sibling",
                    "enzo",
                    OrchestrationState.ASKING,
                    "orc_someone_else")));
    when(store.find("orc_by_hand"))
        .thenReturn(
            java.util.Optional.of(
                row("orc_by_hand", "cnv_by_hand", "enzo", OrchestrationState.ASKING)));
    RunExtras.Context conductor = context(null, "cnv_conductor");

    for (String id : List.of("orc_sibling", "orc_by_hand")) {
      String cancelled =
          tool(conductor, "orchestration_cancel").run("{\"id\":\"" + id + "\"}", Home.of("story"));
      String answered =
          tool(conductor, "orchestration_answer")
              .run("{\"id\":\"" + id + "\",\"answer\":\"yes\"}", Home.of("story"));
      String read =
          tool(conductor, "orchestration_status").run("{\"id\":\"" + id + "\"}", Home.of("story"));
      assertTrue(cancelled.contains("not one this run started"), cancelled);
      assertEquals(cancelled, answered);
      assertEquals(cancelled, read);
    }
    verify(cancel, never()).cancel(any(), any());
    verify(cancel, never()).cancelOwnChild(any(), any());
    verify(cancel, never()).cancelUnlessRunning(any(), any());
    verify(engine, never()).answerAsModel(any(), any(), any());
  }

  @Test
  void answer_uses_the_engine_door_that_claims_the_question_and_cancel_uses_its_service() {
    when(store.find("orc_own"))
        .thenReturn(java.util.Optional.of(run("enzo", OrchestrationState.ASKING)));
    when(engine.answerAsModel("orc_own", "yes", "interlocutor")).thenReturn(true);
    when(cancel.cancelUnlessRunning("orc_own", "interlocutor")).thenReturn(true);

    assertTrue(
        tool(context("enzo"), "orchestration_answer")
            .run("{\"id\":\"orc_own\",\"answer\":\"yes\"}", Home.of("story"))
            .startsWith("Answered"));
    assertTrue(
        tool(context("enzo"), "orchestration_cancel")
            .run("{\"id\":\"orc_own\"}", Home.of("story"))
            .startsWith("Cancelled"));

    verify(engine).answerAsModel("orc_own", "yes", "interlocutor");
    verify(cancel).cancelUnlessRunning("orc_own", "interlocutor");
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Spec 2026-09-29-orchestration-studio §2.3: a model answers a question with options by its
   * labels, through the same door as words — the engine checks them, as the model.
   */
  @Test
  void choices_go_through_the_engine_door_that_checks_them_as_the_model() throws Exception {
    when(store.find("orc_own"))
        .thenReturn(java.util.Optional.of(run("enzo", OrchestrationState.ASKING)));
    when(engine.personOnlyQuestion("orc_own")).thenReturn(java.util.Optional.empty());
    when(engine.answerChosen(eq("orc_own"), any(), eq("thanks"), eq("interlocutor"), eq(false)))
        .thenReturn(new Orchestrations.Chosen.Answered("1. [Store] chose \"SQLite\""));

    String said =
        tool(context("enzo"), "orchestration_answer")
            .run(
                """
                {"id": "orc_own", "answer": "thanks",
                 "choices": [{"header": "Store", "chosen": ["SQLite"]}]}""",
                Home.of("story"));

    assertEquals("Answered orchestration orc_own. Its conductor will continue.", said);
    verify(engine)
        .answerChosen(
            eq("orc_own"),
            eq(
                io.aeyer.plowshare.server.agents.StructuredAnswers.decode(
                    JSON.readTree("[{\"header\":\"Store\",\"chosen\":[\"SQLite\"]}]"))),
            eq("thanks"),
            eq("interlocutor"),
            eq(false));
    verify(engine, never()).answerAsModel(any(), any(), any());
  }

  @Test
  void a_choice_that_does_not_fit_is_told_to_the_model_in_the_engine_s_sentence() {
    when(store.find("orc_own"))
        .thenReturn(java.util.Optional.of(run("enzo", OrchestrationState.ASKING)));
    when(engine.personOnlyQuestion("orc_own")).thenReturn(java.util.Optional.empty());
    when(engine.answerChosen(eq("orc_own"), any(), any(), any(), eq(false)))
        .thenReturn(
            new Orchestrations.Chosen.Refused(
                "'Store' has no option 'MySQL'; its options are" + " 'Postgres', 'SQLite'."));

    String said =
        tool(context("enzo"), "orchestration_answer")
            .run(
                """
                {"id": "orc_own", "choices": [{"header": "Store", "chosen": ["MySQL"]}]}""",
                Home.of("story"));

    assertEquals(
        "'Store' has no option 'MySQL'; its options are 'Postgres', 'SQLite'."
            + " Nothing was answered.",
        said);
  }

  @Test
  void a_person_only_question_is_refused_to_a_model_s_choices_too() {
    OrchestrationRecord run = startedBy("cnv_person", OrchestrationState.ASKING);
    when(store.find("orc_run")).thenReturn(java.util.Optional.of(run));
    when(engine.personOnlyQuestion("orc_run"))
        .thenReturn(java.util.Optional.of("Install `triage_bugs` into this project?"));

    String said =
        tool(context("enzo", "cnv_person"), "orchestration_answer")
            .run(
                """
                {"id": "orc_run", "choices": [{"header": "Install", "chosen": ["Install"]}]}""",
                Home.of("story"));

    assertTrue(said.startsWith("Only the person can answer this: Install `triage_bugs`"), said);
    verify(engine, never()).answerChosen(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void an_answer_with_neither_words_nor_choices_is_refused_before_the_engine() {
    when(store.find("orc_own"))
        .thenReturn(java.util.Optional.of(run("enzo", OrchestrationState.ASKING)));

    String said =
        tool(context("enzo"), "orchestration_answer")
            .run("{\"id\": \"orc_own\"}", Home.of("story"));

    assertEquals(
        "orchestration_answer needs 'answer', the answer in words, or 'choices' for a"
            + " question with options. Nothing was answered.",
        said);
    verify(engine, never()).answerAsModel(any(), any(), any());
    verify(engine, never()).answerChosen(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void a_model_may_not_cancel_a_running_run() {
    OrchestrationRecord running = startedBy("cnv_person", OrchestrationState.RUNNING);
    when(store.find(running.id())).thenReturn(java.util.Optional.of(running));

    String said =
        tool(context("enzo", "cnv_person"), "orchestration_cancel")
            .run("{\"id\":\"" + running.id() + "\"}", Home.of("story"));

    assertEquals(
        "`"
            + running.id()
            + "` is working. Only the person can stop a running run,"
            + " with `/cancel "
            + running.id()
            + "`.",
        said);
    verify(cancel, never()).cancel(any(), any());
    verify(cancel, never()).cancelOwnChild(any(), any());
    verify(cancel, never()).cancelUnlessRunning(any(), any());
  }

  /**
   * The read and the stop are two steps, and the person can answer an asking run between them: the
   * stop is the guarded one, and a run it finds running again gets rule 2's refusal, not an
   * "already ended" that would be false (final review, 2026-09-27 in-flight work).
   */
  @Test
  void a_run_the_person_answered_between_the_read_and_the_stop_is_not_cancelled() {
    OrchestrationRecord asked = startedBy("cnv_person", OrchestrationState.ASKING);
    OrchestrationRecord answered = startedBy("cnv_person", OrchestrationState.RUNNING);
    when(store.find(asked.id()))
        .thenReturn(java.util.Optional.of(asked))
        .thenReturn(java.util.Optional.of(answered));
    when(cancel.cancelUnlessRunning(asked.id(), "interlocutor")).thenReturn(false);

    String said =
        tool(context("enzo", "cnv_person"), "orchestration_cancel")
            .run("{\"id\":\"" + asked.id() + "\"}", Home.of("story"));

    assertEquals(
        "`"
            + asked.id()
            + "` is working. Only the person can stop a running run,"
            + " with `/cancel "
            + asked.id()
            + "`.",
        said);
    verify(cancel, never()).cancel(any(), any());
  }

  @Test
  void a_model_may_still_cancel_an_asking_or_waiting_run() {
    for (OrchestrationState state :
        List.of(OrchestrationState.ASKING, OrchestrationState.WAITING)) {
      OrchestrationRecord run = startedBy("cnv_person", state);
      when(store.find(run.id())).thenReturn(java.util.Optional.of(run));
      when(cancel.cancelUnlessRunning(run.id(), "interlocutor")).thenReturn(true);

      assertTrue(
          tool(context("enzo", "cnv_person"), "orchestration_cancel")
              .run("{\"id\":\"" + run.id() + "\"}", Home.of("story"))
              .startsWith("Cancelled"));
    }
  }

  @Test
  void status_returns_the_run_stage_list_and_message_history() throws Exception {
    OrchestrationRecord own = run("enzo", OrchestrationState.ASKING);
    when(store.find("orc_own")).thenReturn(java.util.Optional.of(own));
    when(todos.list("cnv_conductor"))
        .thenReturn(
            List.of(
                new TodoItem(
                    "todo_1",
                    "cnv_conductor",
                    null,
                    0,
                    "Implement",
                    TodoStatus.IN_PROGRESS,
                    null,
                    true,
                    "code",
                    Instant.EPOCH)));
    when(store.messages("orc_own"))
        .thenReturn(
            List.of(
                new OrchestrationMessage(
                    "orm_1",
                    "orc_own",
                    OrchestrationMessage.Kind.QUESTION,
                    "Which database?",
                    "conductor",
                    Instant.EPOCH,
                    null,
                    null)));

    String result =
        tool(context("enzo"), "orchestration_status").run("{\"id\":\"orc_own\"}", Home.of("story"));

    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result);
    assertEquals("asking", json.path("state").asText());
    assertEquals("code", json.path("todos").get(0).path("stage_id").asText());
    assertEquals("Which database?", json.path("messages").get(0).path("text").asText());
  }

  @Test
  void
      status_previews_large_results_and_history_and_the_same_authority_can_read_the_complete_result()
          throws Exception {
    String large = "x".repeat(8191) + "🌍" + "z".repeat(140000);
    var base = run("enzo", OrchestrationState.FINISHED);
    var own =
        new OrchestrationRecord(
            base.id(),
            base.definitionName(),
            base.tier(),
            base.definitionHash(),
            base.definitionSource(),
            base.definitionOrigin(),
            base.stages(),
            base.maxReturns(),
            base.returnsUsed(),
            base.project(),
            base.conductorConversation(),
            base.callerConversation(),
            base.callerAgent(),
            base.callerHandle(),
            base.callerSession(),
            base.parent(),
            base.depth(),
            base.waitingFor(),
            base.state(),
            null,
            large,
            null,
            0,
            0,
            false,
            null,
            Instant.EPOCH,
            Instant.EPOCH);
    when(store.find(own.id())).thenReturn(java.util.Optional.of(own));
    stubRender(own);
    when(store.messages(own.id()))
        .thenReturn(
            java.util.stream.IntStream.range(0, 100)
                .mapToObj(
                    i ->
                        new OrchestrationMessage(
                            "orm_" + i,
                            own.id(),
                            OrchestrationMessage.Kind.QUESTION,
                            large,
                            "conductor",
                            Instant.EPOCH.plusSeconds(i),
                            null,
                            null))
                .toList());
    AgentTool status = tool(context("enzo"), "orchestration_status");
    var json =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(status.run("{\"id\":\"orc_own\"}", Home.of("story")));
    assertTrue(json.toString().length() < 12000);
    assertEquals(large.length(), json.path("result_length").asInt());
    assertTrue(json.path("result_truncated").asBoolean());
    assertEquals(0, json.path("result_read").path("result_offset").asInt());
    assertEquals(100, json.path("message_count").asInt());
    assertEquals(92, json.path("messages_omitted").asInt());
    assertEquals("orm_99", json.path("messages").get(7).path("id").asText());
    StringBuilder recovered = new StringBuilder();
    int offset = 0;
    while (offset < large.length()) {
      var page =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(
                  status.run(
                      "{\"id\":\"orc_own\",\"result_offset\":" + offset + ",\"result_limit\":8192}",
                      Home.of("story")));
      assertEquals(offset, page.path("start").asInt());
      String text = page.path("text").asText();
      assertFalse(Character.isHighSurrogate(text.charAt(text.length() - 1)));
      recovered.append(text);
      offset = page.path("end").asInt();
      assertEquals(large.length(), page.path("total").asInt());
    }
    assertEquals(large, recovered.toString(), "no report text or Unicode may be lost while paging");
    assertEquals(
        "No orchestration with that id is owned by this account.",
        tool(context("someone_else"), "orchestration_status")
            .run("{\"id\":\"orc_own\",\"result_offset\":0}", Home.of("story")));
    assertTrue(
        status
            .run("{\"id\":\"orc_own\",\"result_offset\":-1}", Home.of("story"))
            .contains("nonnegative"));
    assertTrue(
        status
            .run("{\"id\":\"orc_own\",\"result_limit\":9000}", Home.of("story"))
            .contains("8192"));
  }

  @Test
  void the_status_tool_reports_the_children_and_what_it_waits_for() throws Exception {
    OrchestrationRecord own =
        new OrchestrationRecord(
            "orc_own",
            "code_implementation",
            OrchestrationDefinition.Tier.PROJECT,
            "sha256:x",
            SOURCE,
            "test",
            List.of(),
            3,
            0,
            "story",
            "cnv_conductor",
            null,
            "interlocutor",
            "enzo",
            "ses_1",
            "orc_parent",
            1,
            "orc_child_1",
            OrchestrationState.WAITING,
            null,
            null,
            null,
            0,
            0,
            false,
            null,
            Instant.EPOCH,
            null);
    OrchestrationRecord child =
        new OrchestrationRecord(
            "orc_child_1",
            "code_implementation",
            OrchestrationDefinition.Tier.PROJECT,
            "sha256:x",
            SOURCE,
            "test",
            List.of(),
            3,
            0,
            "story",
            "cnv_child",
            null,
            "interlocutor",
            "enzo",
            "ses_1",
            "orc_own",
            2,
            null,
            OrchestrationState.RUNNING,
            null,
            null,
            null,
            0,
            0,
            false,
            null,
            Instant.EPOCH,
            null);
    when(store.find("orc_own")).thenReturn(java.util.Optional.of(own));
    when(todos.list("cnv_conductor")).thenReturn(List.of());
    when(store.messages("orc_own")).thenReturn(List.of());
    when(store.children("orc_own")).thenReturn(List.of(child));

    String result =
        tool(context("enzo"), "orchestration_status").run("{\"id\":\"orc_own\"}", Home.of("story"));

    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result);
    assertEquals("orc_parent", json.path("parent").asText());
    assertEquals(1, json.path("depth").asInt());
    assertEquals("orc_child_1", json.path("waiting_for").asText());
    assertEquals(1, json.path("children").size());
    assertEquals("orc_child_1", json.path("children").get(0).path("id").asText());
    assertEquals("running", json.path("children").get(0).path("state").asText());
  }

  @Test
  void checking_on_a_run_you_started_while_it_works_ends_your_turn() {
    TurnEnd end = new TurnEnd();
    OrchestrationRecord mine = startedBy("cnv_person", OrchestrationState.RUNNING);
    when(store.find(mine.id())).thenReturn(java.util.Optional.of(mine));
    stubRender(mine);

    String said =
        tool(contextWith("enzo", "cnv_person", end), "orchestration_status")
            .run("{\"id\":\"" + mine.id() + "\"}", Home.of("story"));

    assertTrue(said.contains("your turn ends here"), said);
    TurnEnd.Requested requested = end.requested().orElseThrow();
    assertEquals(Outcome.Ending.ANSWERED, requested.ending());
    assertEquals(
        "`"
            + mine.id()
            + "` (`code_implementation`) is working; its result will be"
            + " delivered to you when it finishes.",
        requested.text());
  }

  /**
   * The measured bot's batch, with a gated command beside the poll: [orchestration_status on its
   * own running run, a run needing approval]. An attended bot's approval is shown to the person
   * only as its turn's AWAITING ending, so rule 1's ANSWERED — first in the batch — used to take
   * the ending and the approval was never seen. Rule 1's request is soft: the approval's, made
   * exactly as {@code RunTool.ask} makes it, replaces it (re-review, 2026-09-27 in-flight work).
   */
  @Test
  void an_approval_raised_after_a_rule_one_status_in_the_same_batch_takes_the_ending() {
    TurnEnd end = new TurnEnd();
    OrchestrationRecord mine = startedBy("cnv_person", OrchestrationState.RUNNING);
    when(store.find(mine.id())).thenReturn(java.util.Optional.of(mine));
    stubRender(mine);

    String said =
        tool(contextWith("enzo", "cnv_person", end), "orchestration_status")
            .run("{\"id\":\"" + mine.id() + "\"}", Home.of("story"));
    boolean approvalStands =
        end.request(
            Outcome.Ending.AWAITING,
            "Approve running pytest -q in /repo on the local side? [apr_1]");

    assertTrue(said.contains("your turn ends here"), said);
    assertTrue(approvalStands, "the approval's request is not the one that lost");
    TurnEnd.Requested requested = end.requested().orElseThrow();
    assertEquals(Outcome.Ending.AWAITING, requested.ending());
    assertEquals("Approve running pytest -q in /repo on the local side? [apr_1]", requested.text());
  }

  @Test
  void checking_on_a_finished_or_asking_run_or_one_you_did_not_start_ends_nothing() {
    for (OrchestrationRecord run :
        List.of(
            startedBy("cnv_person", OrchestrationState.FINISHED),
            startedBy("cnv_person", OrchestrationState.ASKING),
            startedBy("cnv_someone_else", OrchestrationState.RUNNING))) {
      TurnEnd end = new TurnEnd();
      when(store.find(run.id())).thenReturn(java.util.Optional.of(run));
      stubRender(run);
      tool(contextWith("enzo", "cnv_person", end), "orchestration_status")
          .run("{\"id\":\"" + run.id() + "\"}", Home.of("story"));
      assertTrue(end.requested().isEmpty(), run.state() + " / " + run.callerConversation());
    }
  }

  @Test
  void a_conductor_checking_on_the_child_it_waits_on_still_ends_its_turn() {
    // the case YieldingStatus covered, now through the shared rule: the child's caller
    // conversation is the conductor's conversation
    TurnEnd end = new TurnEnd();
    OrchestrationRecord own = run("enzo", OrchestrationState.WAITING);
    when(store.byConductorConversation("cnv_conductor")).thenReturn(java.util.Optional.of(own));
    OrchestrationRecord kid = startedBy("cnv_conductor", OrchestrationState.RUNNING, own.id());
    when(store.find(kid.id())).thenReturn(java.util.Optional.of(kid));
    stubRender(kid);

    String said =
        tool(contextWith(null, "cnv_conductor", end), "orchestration_status")
            .run("{\"id\":\"" + kid.id() + "\"}", Home.of("story"));

    assertTrue(said.contains("your turn ends here"), said);
    assertEquals(Outcome.Ending.ANSWERED, end.requested().orElseThrow().ending());
  }

  private RunExtras.Context context(String handle) {
    return context(handle, null);
  }

  private RunExtras.Context context(String handle, String conversation) {
    return contextWith(handle, conversation, null);
  }

  /**
   * As {@link #context(String, String)}, plus the {@link TurnEnd} a caller-side tool trips — the
   * shape a run actually gets from {@code JobRuntime} once it has one (spec 2026-09-27 §2), rather
   * than the {@code null} the other overloads still stand in for.
   */
  private RunExtras.Context contextWith(String handle, String conversation, TurnEnd end) {
    AgentDefinition caller =
        new AgentDefinition(
            "interlocutor",
            "d",
            "m",
            AgentDefinition.DEFAULT_INTENT,
            Sampling.NONE,
            List.of(),
            List.of(),
            List.of(),
            4,
            8,
            "Talk.",
            true,
            false,
            false,
            true,
            false,
            AgentDefinition.Fallback.NONE,
            List.of("code_implementation"));
    return new RunExtras.Context(
        caller, conversation, "ses_1", null, Home.of("story"), handle, null, end);
  }

  private AgentTool tool(RunExtras.Context context, String name) {
    return access.forRun(context).tools().stream()
        .filter(candidate -> candidate.schema().name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  /**
   * A run this caller conversation started: {@code enzo}'s handle, no parent unless one is given —
   * the shape {@code owned} needs to let a conductor context reach it as its own child.
   */
  private static OrchestrationRecord startedBy(
      String callerConversation, OrchestrationState state) {
    return startedBy(callerConversation, state, null);
  }

  private static OrchestrationRecord startedBy(
      String callerConversation, OrchestrationState state, String parent) {
    return new OrchestrationRecord(
        "orc_run",
        "code_implementation",
        OrchestrationDefinition.Tier.PROJECT,
        "sha256:x",
        SOURCE,
        "test",
        List.of(),
        3,
        0,
        "story",
        "cnv_run_conductor",
        callerConversation,
        "interlocutor",
        "enzo",
        "ses_1",
        parent,
        parent == null ? 0 : 1,
        null,
        state,
        null,
        null,
        null,
        0,
        0,
        false,
        null,
        Instant.EPOCH,
        null);
  }

  /**
   * Stubs whatever {@code render} reads for this run, the way {@code status_returns_the_run_}
   * {@code stage_list_and_message_history} does by hand.
   */
  private void stubRender(OrchestrationRecord run) {
    when(todos.list(run.conductorConversation())).thenReturn(List.of());
    when(store.messages(run.id())).thenReturn(List.of());
    when(store.children(run.id())).thenReturn(List.of());
  }

  private static OrchestrationRecord run(String handle, OrchestrationState state) {
    return row("orc_own", "cnv_conductor", handle, state);
  }

  private static OrchestrationRecord child(
      String id, String conversation, String handle, OrchestrationState state, String parent) {
    return new OrchestrationRecord(
        id,
        "code_implementation",
        OrchestrationDefinition.Tier.PROJECT,
        "sha256:x",
        SOURCE,
        "test",
        List.of(),
        3,
        0,
        "story",
        conversation,
        "cnv_conductor",
        "code_implementation",
        handle,
        "ses_1",
        parent,
        1,
        null,
        state,
        null,
        null,
        null,
        0,
        0,
        false,
        null,
        Instant.EPOCH,
        null);
  }

  private static OrchestrationRecord row(
      String id, String conversation, String handle, OrchestrationState state) {
    return new OrchestrationRecord(
        id,
        "code_implementation",
        OrchestrationDefinition.Tier.PROJECT,
        "sha256:x",
        SOURCE,
        "test",
        List.of(),
        3,
        0,
        "story",
        conversation,
        null,
        "interlocutor",
        handle,
        "ses_1",
        null,
        0,
        null,
        state,
        null,
        null,
        null,
        0,
        0,
        false,
        null,
        Instant.EPOCH,
        null);
  }
}
