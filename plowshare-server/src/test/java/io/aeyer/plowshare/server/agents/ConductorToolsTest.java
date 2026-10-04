package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.hooks.Approving;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.Tier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code orchestration_ask}, {@code orchestration_check} and {@code orchestration_finish} as a
 * model reads them: each calls a fake {@link ConductorActions} first and trips a real {@link
 * TurnEnd} only once that call has succeeded.
 */
class ConductorToolsTest {

  private static final Home home = Home.global();
  private static final String ORCHESTRATION = "orc_1";

  private final FakeActions actions = new FakeActions();
  private final FakePort port = new FakePort();

  @Test
  void ask_records_then_ends_the_turn_awaiting() {
    FakeActions actions = new FakeActions();
    TurnEnd end = new TurnEnd();
    AgentTool ask = tool(ConductorTools.ASK_NAME, actions, end);

    String result = ask.run("{\"question\": \"Which database?\"}", home);

    assertTrue(result.toLowerCase().contains("asked"), result);
    assertEquals(List.of(new FakeActions.AskCall(ORCHESTRATION, "Which database?")), actions.asks);
    assertTrue(end.requested().isPresent());
    assertEquals(Outcome.Ending.AWAITING, end.requested().get().ending());
    assertEquals("Which database?", end.requested().get().text());
  }

  @Test
  void a_refused_ask_does_not_end_the_turn() {
    FakeActions actions = new FakeActions();
    actions.askRefusal = Optional.of("this orchestration is not running");
    TurnEnd end = new TurnEnd();
    AgentTool ask = tool(ConductorTools.ASK_NAME, actions, end);

    String result = ask.run("{\"question\": \"Which database?\"}", home);

    assertEquals("this orchestration is not running", result);
    assertTrue(end.requested().isEmpty());
  }

  @Test
  void an_ask_with_questions_records_its_structure_and_ends_the_turn_on_its_text() {
    FakeActions actions = new FakeActions();
    TurnEnd end = new TurnEnd();
    AgentTool ask = tool(ConductorTools.ASK_NAME, actions, end);

    String result =
        ask.run(
            """
                {"question": "Two things first.", "questions": [
                  {"header": "Store", "question": "Which database?", "options": [
                    {"label": "Postgres", "description": "The one we run."},
                    {"label": "SQLite", "description": "A file."}]}]}""",
            home);

    assertTrue(result.toLowerCase().contains("asked"), result);
    assertTrue(actions.asks.isEmpty(), "the plain ask is not called");
    FakeActions.StructuredAskCall call = actions.structuredAsks.get(0);
    assertEquals(
        """
                Two things first.
                1. [Store] Which database?
                   - Postgres — The one we run.
                   - SQLite — A file.
                   (choose one, or answer in words)""",
        call.question());
    assertEquals(call.question(), end.requested().orElseThrow().text());
    StructuredQuestions.Asked asked = StructuredQuestions.parse(call.structure());
    assertEquals("Two things first.", asked.lead());
    assertEquals("Store", asked.questions().get(0).header());
  }

  @Test
  void an_ask_with_a_broken_question_is_refused_and_asks_nothing() {
    FakeActions actions = new FakeActions();
    TurnEnd end = new TurnEnd();
    AgentTool ask = tool(ConductorTools.ASK_NAME, actions, end);

    String result =
        ask.run(
            """
                {"question": "Which?", "questions": [
                  {"header": "Store", "question": "Which database?", "options": [
                    {"label": "Postgres", "description": "p"}]}]}""",
            home);

    assertEquals("question 1 needs 2 to 4 options; it had 1. Nothing was asked.", result);
    assertTrue(actions.structuredAsks.isEmpty());
    assertTrue(actions.asks.isEmpty());
    assertTrue(end.requested().isEmpty());
  }

  @Test
  void the_ask_schema_offers_questions_and_still_requires_only_the_question() {
    AgentTool ask = tool(ConductorTools.ASK_NAME, new FakeActions(), new TurnEnd());

    Map<String, Object> parameters = ask.schema().parameters();
    assertEquals(List.of("question"), parameters.get("required"));
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
    assertEquals(List.of("question", "questions"), List.copyOf(properties.keySet()));
  }

  @Test
  void finish_records_then_ends_the_turn_answered() {
    FakeActions actions = new FakeActions();
    TurnEnd end = new TurnEnd();
    AgentTool finish = tool(ConductorTools.FINISH_NAME, actions, end);

    String result = finish.run("{\"result\": \"all stages done\"}", home);

    assertTrue(result.toLowerCase().contains("finished"), result);
    assertEquals(
        List.of(new FakeActions.FinishCall(ORCHESTRATION, "all stages done")), actions.finishes);
    assertTrue(end.requested().isPresent());
    assertEquals(Outcome.Ending.ANSWERED, end.requested().get().ending());
    assertEquals("all stages done", end.requested().get().text());
  }

  @Test
  void a_refused_finish_does_not_end_the_turn() {
    FakeActions actions = new FakeActions();
    actions.finishRefusal = Optional.of("stage 2 of 3 is not done");
    TurnEnd end = new TurnEnd();
    AgentTool finish = tool(ConductorTools.FINISH_NAME, actions, end);

    String result = finish.run("{\"result\": \"all stages done\"}", home);

    assertEquals("stage 2 of 3 is not done", result);
    assertTrue(end.requested().isEmpty());
  }

  @Test
  void a_blank_question_or_result_is_refused_before_anything_is_recorded() {
    FakeActions actions = new FakeActions();
    TurnEnd end = new TurnEnd();
    AgentTool ask = tool(ConductorTools.ASK_NAME, actions, end);
    AgentTool finish = tool(ConductorTools.FINISH_NAME, actions, end);

    String askResult = ask.run("{\"question\": \"   \"}", home);
    String finishResult = finish.run("{\"result\": \"\"}", home);

    assertTrue(actions.asks.isEmpty());
    assertTrue(actions.finishes.isEmpty());
    assertTrue(end.requested().isEmpty());
    assertTrue(askResult.toLowerCase().contains("question"), askResult);
    assertTrue(finishResult.toLowerCase().contains("result"), finishResult);
  }

  @Test
  void a_second_ending_in_one_batch_is_told_its_turn_is_already_ending() {
    FakeActions actions = new FakeActions();
    TurnEnd end = new TurnEnd();
    AgentTool finish = tool(ConductorTools.FINISH_NAME, actions, end);
    AgentTool ask = tool(ConductorTools.ASK_NAME, actions, end);

    String finishResult = finish.run("{\"result\": \"all stages done\"}", home);
    String askResult = ask.run("{\"question\": \"Which database?\"}", home);

    assertTrue(finishResult.toLowerCase().contains("finished"), finishResult);
    assertEquals(
        "Your question was recorded, but this turn is already ending for another"
            + " reason; it will be delivered when that is settled.",
        askResult);
    assertEquals(Outcome.Ending.ANSWERED, end.requested().get().ending());
  }

  @Test
  void a_second_ending_the_other_way_around_is_told_its_turn_is_already_ending() {
    FakeActions actions = new FakeActions();
    TurnEnd end = new TurnEnd();
    AgentTool ask = tool(ConductorTools.ASK_NAME, actions, end);
    AgentTool finish = tool(ConductorTools.FINISH_NAME, actions, end);

    String askResult = ask.run("{\"question\": \"Which database?\"}", home);
    String finishResult = finish.run("{\"result\": \"all stages done\"}", home);

    assertTrue(askResult.toLowerCase().contains("asked"), askResult);
    assertEquals(
        "Your result was recorded, but this turn is already ending for another" + " reason.",
        finishResult);
    assertEquals(Outcome.Ending.AWAITING, end.requested().get().ending());
  }

  @Test
  void the_tools_never_throw_for_bad_json() {
    FakeActions actions = new FakeActions();
    TurnEnd end = new TurnEnd();
    AgentTool ask = tool(ConductorTools.ASK_NAME, actions, end);
    AgentTool finish = tool(ConductorTools.FINISH_NAME, actions, end);

    assertTrue(ask.run("{not json", home).length() > 0);
    assertTrue(finish.run("{not json", home).length() > 0);
    assertTrue(actions.asks.isEmpty());
    assertTrue(actions.finishes.isEmpty());
    assertTrue(end.requested().isEmpty());
  }

  // --- orchestration_check --------------------------------------------------------------

  @Test
  void setting_the_check_asks_a_person_and_ends_the_turn_on_it() {
    actions.next = new ConductorActions.CheckSet.Asking("Approve running pytest -q …");
    TurnEnd end = new TurnEnd();
    AgentTool check =
        tool(ConductorTools.forRun(actions, "orc_1", end, port), ConductorTools.CHECK_NAME);

    String said = check.run("{\"command\": [\"pytest\", \"-q\"]}", Home.of("story"));

    assertEquals(List.of("pytest", "-q"), actions.checkArgv);
    assertEquals("ask", actions.checkMode);
    assertEquals(Outcome.Ending.AWAITING, end.requested().orElseThrow().ending());
    assertTrue(said.contains("your turn ends"), said);
  }

  @Test
  void a_refused_check_says_why_and_ends_nothing() {
    actions.next = new ConductorActions.CheckSet.Refused("the check is `x`; it does not change");
    TurnEnd end = new TurnEnd();

    String said =
        tool(ConductorTools.forRun(actions, "orc_1", end, port), ConductorTools.CHECK_NAME)
            .run("{\"command\": [\"pytest\"]}", Home.of("story"));

    assertEquals("the check is `x`; it does not change", said);
    assertTrue(end.requested().isEmpty());
  }

  /** Final review F1: a hook that denies the command as a run call refuses it as the check. */
  @Test
  void a_check_a_run_hook_denies_is_refused_with_the_hook_s_reason_and_nothing_is_set() {
    port.verdict = new Commands.Verdict("no pytest on Fridays", null);
    TurnEnd end = new TurnEnd();

    String said =
        tool(ConductorTools.forRun(actions, "orc_1", end, port), ConductorTools.CHECK_NAME)
            .run("{\"command\": [\"pytest\"]}", Home.of("story"));

    assertTrue(said.contains("no pytest on Fridays"), said);
    assertEquals(null, actions.checkArgv, "nothing is set");
    assertTrue(end.requested().isEmpty());
  }

  /** Final review F1: a hook's ask puts the check to a person even when the side is open. */
  @Test
  void a_check_a_run_hook_asks_about_is_put_to_a_person_even_under_mode_open() {
    port.side =
        new EnvironmentFile.Side(
            EnvironmentFile.OPEN,
            EnvironmentFile.Side.DEFAULT.shells(),
            EnvironmentFile.Side.DEFAULT.inherit(),
            EnvironmentFile.Side.DEFAULT.env(),
            EnvironmentFile.Side.DEFAULT.timeout(),
            EnvironmentFile.Side.DEFAULT.outputBytes(),
            EnvironmentFile.Side.DEFAULT.isolation());
    port.verdict = new Commands.Verdict(null, "pytest touches the network");
    actions.next = new ConductorActions.CheckSet.Asking("Approve running pytest?");

    tool(ConductorTools.forRun(actions, "orc_1", new TurnEnd(), port), ConductorTools.CHECK_NAME)
        .run("{\"command\": [\"pytest\"]}", Home.of("story"));

    assertEquals(EnvironmentFile.ASK, actions.checkMode, "asked of a person, never recorded open");
    assertEquals(
        Boolean.TRUE,
        actions.checkHookAsked,
        "and said to be a hook's, which no consent given before and no judge answers");
  }

  /** V67: a check nobody was asked about says why — in the harness's words, never a model's. */
  @Test
  void a_check_set_without_asking_says_why_nobody_was_asked() {
    actions.next = new ConductorActions.CheckSet.Set("the command judge found it clearly safe");

    String said =
        tool(
                ConductorTools.forRun(actions, "orc_1", new TurnEnd(), port),
                ConductorTools.CHECK_NAME)
            .run("{\"command\": [\"pytest\"]}", Home.of("story"));

    assertTrue(said.endsWith(" Nobody was asked: the command judge found it clearly safe."), said);
    assertEquals(Boolean.FALSE, actions.checkHookAsked);
  }

  /** Spec 2026-09-28-hooks-reach-the-log §3: the check's question passes approval.pre first. */
  @Test
  void the_check_hands_approval_pre_where_the_command_would_run_and_parks_its_records() {
    List<String> seen = new ArrayList<>();
    List<HookRecord> parked = new ArrayList<>();
    HookRecord said =
        new HookRecord(
            "assess",
            "a.js",
            Tier.PROJECT,
            Stage.APPROVAL_PRE,
            null,
            HookRecord.NOTE,
            null,
            "reads only",
            null,
            1);
    RunHooks hooks =
        new RunHooks() {
          @Override
          public Gate approvalPre(
              HookContext.RunEnvironment environment,
              HookContext.Orchestration orchestration,
              Approving approving) {
            seen.add(
                environment.side()
                    + " "
                    + environment.mode()
                    + " "
                    + orchestration
                    + " "
                    + approving.argv()
                    + " "
                    + approving.cwd()
                    + " "
                    + approving.reason()
                    + " "
                    + approving.attended()
                    + " "
                    + approving.scopes());
            return new Gate(null, List.of("reads only"), List.of(said));
          }

          @Override
          public void record(List<HookRecord> records) {
            parked.addAll(records);
          }
        };
    HookContext.Orchestration about =
        new HookContext.Orchestration("orc_1", "code_implementation", null);

    tool(
            ConductorTools.forRun(actions, "orc_1", new TurnEnd(), port, hooks, about),
            ConductorTools.CHECK_NAME)
        .run("{\"command\": [\"pytest\", \"-q\"]}", Home.of("story"));
    Gate gate = actions.before.before("this run's check");

    assertEquals(List.of("reads only"), gate.notes());
    assertEquals(
        List.of(
            "local ask "
                + about
                + " [pytest, -q] /repo this run's check false"
                + " [once, conversation, project]"),
        seen);
    assertEquals(List.of(said), parked);
  }

  @Test
  void no_port_no_check_tool() {
    assertTrue(
        ConductorTools.forRun(actions, "orc_1", new TurnEnd(), null).stream()
            .noneMatch(t -> t.schema().name().equals(ConductorTools.CHECK_NAME)));
  }

  private static AgentTool tool(String name, ConductorActions actions, TurnEnd end) {
    return tool(ConductorTools.forRun(actions, ORCHESTRATION, end), name);
  }

  private static AgentTool tool(List<AgentTool> tools, String name) {
    for (AgentTool candidate : tools) {
      if (candidate.schema().name().equals(name)) {
        return candidate;
      }
    }
    throw new AssertionError("no tool named " + name);
  }

  /**
   * Records every call it saw, and answers whatever refusal — or {@link ConductorActions.CheckSet}
   * — a test set.
   */
  private static final class FakeActions implements ConductorActions {

    record AskCall(String orchestration, String question) {}

    record StructuredAskCall(String orchestration, String question, String structure) {}

    record FinishCall(String orchestration, String result) {}

    final List<AskCall> asks = new ArrayList<>();
    final List<StructuredAskCall> structuredAsks = new ArrayList<>();
    final List<FinishCall> finishes = new ArrayList<>();
    Optional<String> askRefusal = Optional.empty();
    Optional<String> finishRefusal = Optional.empty();
    CheckSet next = new CheckSet.Set();
    List<String> checkArgv;
    String checkSide;
    String checkCwd;
    String checkMode;
    Boolean checkHookAsked;
    BeforeAsking before;

    @Override
    public Optional<String> ask(String orchestration, String question) {
      asks.add(new AskCall(orchestration, question));
      return askRefusal;
    }

    @Override
    public Optional<String> ask(String orchestration, String question, String structure) {
      if (structure == null) {
        return ask(orchestration, question);
      }
      structuredAsks.add(new StructuredAskCall(orchestration, question, structure));
      return askRefusal;
    }

    @Override
    public Optional<String> finish(String orchestration, String result) {
      finishes.add(new FinishCall(orchestration, result));
      return finishRefusal;
    }

    @Override
    public CheckSet setCheck(
        String orchestration, List<String> argv, String side, String cwd, String mode) {
      checkArgv = argv;
      checkSide = side;
      checkCwd = cwd;
      checkMode = mode;
      return next;
    }

    @Override
    public CheckSet setCheck(
        String orchestration,
        List<String> argv,
        String side,
        String cwd,
        String mode,
        boolean hookAsked,
        BeforeAsking before) {
      checkHookAsked = hookAsked;
      this.before = before;
      return setCheck(orchestration, argv, side, cwd, mode);
    }
  }

  /**
   * A {@link Commands.Port} whose {@code place} always lands the same way: local, {@code /repo},
   * mode {@code ask}.
   */
  private static final class FakePort implements Commands.Port {

    private static final EnvironmentFile.Side ASK =
        new EnvironmentFile.Side(
            EnvironmentFile.ASK,
            EnvironmentFile.Side.DEFAULT.shells(),
            EnvironmentFile.Side.DEFAULT.inherit(),
            EnvironmentFile.Side.DEFAULT.env(),
            EnvironmentFile.Side.DEFAULT.timeout(),
            EnvironmentFile.Side.DEFAULT.outputBytes(),
            EnvironmentFile.Side.DEFAULT.isolation());

    /** What the side allows; {@code ask} unless a test says otherwise. */
    EnvironmentFile.Side side = ASK;

    /** What the run tool's hooks say about the command. */
    Commands.Verdict verdict = Commands.Verdict.ALLOWED;

    @Override
    public Commands.Placed place(Home home, Path cwd, List<String> argv) {
      return new Commands.Placed(argv, Path.of("/repo"), null, EnvironmentFile.LOCAL, side, null);
    }

    @Override
    public Commands.Verdict judge(Commands.Placed placed) {
      return verdict;
    }

    @Override
    public CommandRunner.Outcome run(Commands.Placed placed) {
      throw new UnsupportedOperationException("not needed by these tests");
    }
  }
}
