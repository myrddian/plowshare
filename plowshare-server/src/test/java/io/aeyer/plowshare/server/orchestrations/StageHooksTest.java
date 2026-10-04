package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.RunHooks;
import io.aeyer.plowshare.server.agents.TodoTools;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.StageDone;
import io.aeyer.plowshare.server.hooks.StageShown;
import io.aeyer.plowshare.server.hooks.StageStart;
import io.aeyer.plowshare.server.hooks.Tier;
import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoNotices;
import io.aeyer.plowshare.server.todos.TodoOp;
import io.aeyer.plowshare.server.todos.TodoRefused;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Spec 2026-09-28-hooks-reach-the-log §3: stage.pre / stage.post in front of the board. */
class StageHooksTest {

  private static final Home HOME = Home.of("story");
  private static final String CONV = "cnv_conductor";

  private OrchestrationRecord run =
      new OrchestrationRecord(
          "orc_1",
          "code_implementation",
          io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier.PROJECT,
          "sha256:x",
          "src",
          "test",
          List.of(
              new StageRules.Stage("design", List.of()),
              new StageRules.Stage("code", List.of(), true),
              new StageRules.Stage("review", List.of("code"))),
          2,
          0,
          "story",
          CONV,
          null,
          "interlocutor",
          "enzo",
          null,
          null,
          0,
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

  private final List<TodoItem> items =
      new ArrayList<>(
          List.of(
              item("design", TodoStatus.DONE, "designed"),
              item("code", TodoStatus.IN_PROGRESS, "built it"),
              item("review", TodoStatus.PENDING, null)));

  private static TodoItem item(String stage, TodoStatus status, String summary) {
    return new TodoItem(
        "td_" + stage,
        CONV,
        null,
        0,
        "the " + stage + " stage",
        status,
        summary,
        true,
        stage,
        Instant.EPOCH);
  }

  private final TodoLists todos =
      new TodoLists() {
        @Override
        public List<TodoItem> list(String conversation) {
          return items;
        }

        @Override
        public List<TodoItem> apply(String c, List<TodoOp> o, String s) {
          throw new AssertionError("StageHooks never applies");
        }

        @Override
        public Optional<Notice> noticeFor(String conversation) {
          throw new AssertionError();
        }

        @Override
        public void noticed(String conversation, TodoNotices.Seen seen) {
          throw new AssertionError();
        }

        @Override
        public void forget(String conversation) {
          throw new AssertionError();
        }
      };

  /** Records what each gate was asked, and answers as told. */
  private static final class Asked implements RunHooks {
    final List<String> lines = new ArrayList<>();
    final List<HookRecord> parked = new ArrayList<>();
    Gate pre = Gate.NOTHING;
    Gate post = Gate.NOTHING;

    @Override
    public Gate stagePre(HookContext.Orchestration orchestration, StageStart start) {
      lines.add("pre " + orchestration + " " + start);
      return pre;
    }

    @Override
    public Gate stagePost(HookContext.Orchestration orchestration, StageDone done) {
      lines.add("post " + orchestration + " " + done);
      return post;
    }

    @Override
    public void record(List<HookRecord> records) {
      parked.addAll(records);
    }
  }

  private final Asked hooks = new Asked();
  private List<String> check = List.of("pytest", "-q");

  private StageHooks stageHooks() {
    return new StageHooks(
        conversation -> Optional.of(run),
        todos,
        id ->
            Optional.of(
                new OrchestrationChecks.Check(
                    "orc_1",
                    check,
                    "local",
                    "/repo",
                    OrchestrationChecks.OPEN,
                    null,
                    Instant.EPOCH)),
        hooks);
  }

  private static TodoOp.Update to(String stage, TodoStatus status, String summary) {
    return new TodoOp.Update("td_" + stage, status, null, summary);
  }

  private static HookRecord said(String decision) {
    return new HookRecord(
        "fixture", "f.js", Tier.PROJECT, Stage.STAGE_POST, null, decision, null, null, null, 0);
  }

  @Test
  void a_checked_stage_s_done_move_shows_its_stored_summary_and_its_passed_check() {
    hooks.post = new Gate(null, List.of("reviewed code"), List.of(said(HookRecord.NOTE)));
    String summary = "built it — check passed: `pytest -q` (exit 0, 1.5s)";

    TodoTools.Checked passed =
        stageHooks().checked(CONV, List.of(to("code", TodoStatus.DONE, summary)), HOME);

    assertEquals(
        List.of(
            "post "
                + new HookContext.Orchestration("orc_1", "code_implementation", "code")
                + " "
                + new StageDone(
                    new StageShown("code", "the code stage", 1, 3),
                    summary,
                    List.of("pytest", "-q"))),
        hooks.lines);
    assertEquals(List.of("reviewed code"), passed.notes());
    assertEquals(List.of(said(HookRecord.NOTE)), hooks.parked, "its records are parked");
  }

  @Test
  void an_unchecked_stage_shows_no_check_and_a_start_after_a_done_fires_in_batch_order() {
    items.set(1, item("code", TodoStatus.DONE, "built it"));
    items.set(2, item("review", TodoStatus.PENDING, null));

    stageHooks()
        .checked(
            CONV,
            List.of(
                to("review", TodoStatus.IN_PROGRESS, null),
                to("review", TodoStatus.DONE, "looked")),
            HOME);

    assertEquals(2, hooks.lines.size());
    assertTrue(hooks.lines.get(0).startsWith("pre "), hooks.lines.get(0));
    assertTrue(hooks.lines.get(0).endsWith("returning=false, returnsLeft=2]"), hooks.lines.get(0));
    assertTrue(hooks.lines.get(1).contains("summary=looked, check=null"), hooks.lines.get(1));
  }

  /** Spec §3: a return is shown as one, with the returns the run has left once it stands. */
  @Test
  void a_return_shows_stage_pre_that_it_is_one_and_how_many_returns_are_left() {
    items.set(1, item("code", TodoStatus.DONE, "built it"));
    items.set(2, item("review", TodoStatus.IN_PROGRESS, null));

    stageHooks().checked(CONV, List.of(to("code", TodoStatus.IN_PROGRESS, null)), HOME);

    assertEquals(
        List.of(
            "pre "
                + new HookContext.Orchestration("orc_1", "code_implementation", "code")
                + " "
                + new StageStart(new StageShown("code", "the code stage", 1, 3), true, 1)),
        hooks.lines,
        "max 2, none used before, this one used: one left");
  }

  @Test
  void a_denial_refuses_the_whole_write_naming_the_stage_and_the_hook() {
    hooks.post = new Gate("'freeze': not on Friday", List.of(), List.of(said(HookRecord.DENY)));

    TodoRefused refused =
        assertThrows(
            TodoRefused.class,
            () ->
                stageHooks().checked(CONV, List.of(to("code", TodoStatus.DONE, "built it")), HOME));

    assertEquals(
        "todo_write refused operation 1: stage 'code' was refused by a hook on"
            + " stage.post: 'freeze': not on Friday. Nothing was changed.",
        refused.getMessage());
    assertEquals(List.of(said(HookRecord.DENY)), hooks.parked, "a denial is recorded too");
  }

  @Test
  void no_hook_is_asked_about_a_batch_the_board_refuses_or_a_run_that_ended() {
    stageHooks().checked(CONV, List.of(to("design", TodoStatus.IN_PROGRESS, null)), HOME);
    run =
        new OrchestrationRecord(
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
            OrchestrationState.FINISHED,
            run.pendingCap(),
            run.result(),
            run.failure(),
            run.restarts(),
            run.nudges(),
            run.endedInProse(),
            run.resultDeliveredAt(),
            run.createdAt(),
            run.endedAt());
    stageHooks().checked(CONV, List.of(to("code", TodoStatus.DONE, "built it")), HOME);

    assertEquals(
        List.of(),
        hooks.lines,
        "code does not list design in may-return-to, and a finished run moves nothing");
  }
}
