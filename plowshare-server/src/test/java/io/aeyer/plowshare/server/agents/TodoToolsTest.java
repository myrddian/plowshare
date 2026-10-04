package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoOp;
import io.aeyer.plowshare.server.todos.TodoRefused;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TodoToolsTest {

  private static final Instant T0 = Instant.parse("2026-09-13T09:00:00Z");

  /** Records what it was asked; answers a fixed list. */
  private static final class Fake implements TodoLists {
    final List<List<TodoOp>> applied = new ArrayList<>();
    final List<String> sessions = new ArrayList<>();
    RuntimeException refuse;

    @Override
    public List<TodoItem> list(String conversation) {
      return List.of(
          new TodoItem(
              "td_1", conversation, null, 0, "one", TodoStatus.PENDING, null, false, null, T0));
    }

    @Override
    public List<TodoItem> apply(String conversation, List<TodoOp> ops, String sessionId) {
      if (refuse != null) {
        throw refuse;
      }
      applied.add(ops);
      sessions.add(sessionId);
      return list(conversation);
    }

    @Override
    public Optional<Notice> noticeFor(String conversation) {
      return Optional.empty();
    }

    @Override
    public void noticed(
        String conversation, io.aeyer.plowshare.server.todos.TodoNotices.Seen seen) {
      // Nothing to do.
    }

    @Override
    public void forget(String conversation) {
      // Nothing to do.
    }
  }

  /**
   * A {@link Transcript} that hands over a fixed history and remembers the conversation it is in.
   * Copied from {@code JobRuntimeTest.Recorded} rather than built as an anonymous class: {@link
   * Transcript#promptMeasured(int)} is abstract, so an anonymous class overriding only {@code
   * before()} and {@code conversationId()} does not compile.
   */
  private static final class Recorded implements Transcript {

    private final List<ChatMessage> before;
    private final String conversationId;

    Recorded(List<ChatMessage> before, String conversationId) {
      this.before = List.copyOf(before);
      this.conversationId = conversationId;
    }

    @Override
    public List<ChatMessage> before() {
      return before;
    }

    @Override
    public String conversationId() {
      return conversationId;
    }

    @Override
    public void promptMeasured(int promptTokens) {
      // Not exercised here.
    }
  }

  private static Transcript in(String conversation) {
    return new Recorded(List.of(), conversation);
  }

  @Test
  void read_renders_the_conversations_list() {
    String out = new TodoTools.Read(new Fake(), in("cnv_1")).run("", Home.global());
    assertEquals("[ ] td_1 one\n", out);
  }

  @Test
  void write_parses_every_operation_kind_and_passes_the_session() {
    Fake fake = new Fake();
    String out =
        new TodoTools.Write(fake, in("cnv_1"), "s-enzo")
            .run(
                """
                {"ops":[
                  {"op":"add","text":"two","parent":"td_1"},
                  {"op":"update","id":"td_1","status":"in_progress","summary":"started"},
                  {"op":"move","id":"td_1","position":0}
                ]}""",
                Home.global());

    assertEquals(
        List.of(
            List.of(
                new TodoOp.Add("two", "td_1"),
                new TodoOp.Update("td_1", TodoStatus.IN_PROGRESS, null, "started"),
                new TodoOp.Move("td_1", 0))),
        fake.applied);
    assertEquals(List.of("s-enzo"), fake.sessions);
    assertTrue(out.startsWith("Done. The list is now:\n"));
  }

  @Test
  void a_before_step_sees_the_ops_first_and_can_refuse_them() {
    List<List<TodoOp>> seen = new ArrayList<>();
    TodoTools.Write write =
        new TodoTools.Write(
            new Fake(),
            in("cnv_1"),
            "s",
            (conversation, ops, home) -> {
              seen.add(ops);
              throw new TodoRefused(
                  "todo_write refused operation 1: not yet. Nothing was changed.");
            });

    String said = write.run("{\"ops\": [{\"op\": \"add\", \"text\": \"x\"}]}", Home.of("story"));

    assertEquals(1, seen.size());
    assertEquals("todo_write refused operation 1: not yet. Nothing was changed.", said);
  }

  @Test
  void a_refusal_is_the_models_to_read() {
    Fake fake = new Fake();
    fake.refuse = new TodoRefused("todo_write refused operation 1: nope. Nothing was changed.");
    String out =
        new TodoTools.Write(fake, in("cnv_1"), "s")
            .run("{\"ops\":[{\"op\":\"add\",\"text\":\"x\"}]}", Home.global());
    assertEquals("todo_write refused operation 1: nope. Nothing was changed.", out);
  }

  @Test
  void a_fractional_position_is_refused_like_a_missing_one() {
    Fake fake = new Fake();
    String out =
        new TodoTools.Write(fake, in("cnv_1"), "s")
            .run("{\"ops\":[{\"op\":\"move\",\"id\":\"td_1\",\"position\":2.7}]}", Home.global());

    assertEquals(
        "todo_write refused operation 1: a move needs 'position', a whole number"
            + " from zero. Nothing was changed.",
        out);
    assertEquals(List.of(), fake.applied);
  }

  @Test
  void an_unknown_operation_or_status_is_refused_by_name() {
    Fake fake = new Fake();
    assertEquals(
        "todo_write refused operation 1: 'op' must be add, update or move, not 'delete'."
            + " Nothing was changed.",
        new TodoTools.Write(fake, in("cnv_1"), "s")
            .run("{\"ops\":[{\"op\":\"delete\",\"id\":\"td_1\"}]}", Home.global()));
    assertEquals(
        "todo_write refused operation 1: 'status' must be pending, in_progress, done or"
            + " dropped, not 'finished'. Nothing was changed.",
        new TodoTools.Write(fake, in("cnv_1"), "s")
            .run(
                "{\"ops\":[{\"op\":\"update\",\"id\":\"td_1\",\"status\":\"finished\"}]}",
                Home.global()));
    assertEquals(List.of(), fake.applied);
  }

  @Test
  void ops_that_are_not_a_list_are_refused() {
    String out =
        new TodoTools.Write(new Fake(), in("cnv_1"), "s").run("{\"ops\":\"add\"}", Home.global());
    assertEquals(
        "todo_write needs 'ops': a list of operations, like {\"ops\":[{\"op\":\"add\","
            + "\"text\":\"write the spec\"}]}. Nothing was changed.",
        out);
  }

  @Test
  void unreadable_json_is_refused_not_thrown() {
    String out = new TodoTools.Write(new Fake(), in("cnv_1"), "s").run("{not json", Home.global());
    assertTrue(out.startsWith("todo_write could not read its arguments"));
  }

  @Test
  void a_null_argument_is_the_runtimes_bug_and_throws() {
    TodoTools.Read read = new TodoTools.Read(new Fake(), in("cnv_1"));
    assertThrows(NullPointerException.class, () -> read.run(null, Home.global()));
    assertThrows(NullPointerException.class, () -> read.run("", null));

    TodoTools.Write write = new TodoTools.Write(new Fake(), in("cnv_1"), "s");
    assertThrows(NullPointerException.class, () -> write.run(null, Home.global()));
    assertThrows(NullPointerException.class, () -> write.run("{}", null));
  }

  @Test
  void a_run_in_no_conversation_has_nowhere_to_keep_a_list() {
    assertEquals(
        "There is no todo list: this run is in no conversation, so there is nowhere to"
            + " keep one.",
        new TodoTools.Read(new Fake(), Transcript.NONE).run("", Home.global()));
    assertEquals(
        "There is no todo list: this run is in no conversation, so there is nowhere to"
            + " keep one.",
        new TodoTools.Write(new Fake(), Transcript.NONE, "s")
            .run("{\"ops\":[{\"op\":\"add\",\"text\":\"x\"}]}", Home.global()));
  }

  /**
   * Spec 2026-09-28-hooks-reach-the-log §3: a stage hook's note is appended to the tool's result.
   */
  @Test
  void notes_from_the_steps_in_front_of_the_board_follow_a_committed_result_in_step_order() {
    TodoTools.BeforeApply first =
        new TodoTools.BeforeApply() {
          @Override
          public List<TodoOp> check(String conversation, List<TodoOp> ops, Home home) {
            return checked(conversation, ops, home).ops();
          }

          @Override
          public TodoTools.Checked checked(String conversation, List<TodoOp> ops, Home home) {
            return new TodoTools.Checked(ops, List.of("note a"));
          }
        };
    TodoTools.BeforeApply plain = (conversation, ops, home) -> ops;
    TodoTools.BeforeApply second =
        new TodoTools.BeforeApply() {
          @Override
          public List<TodoOp> check(String conversation, List<TodoOp> ops, Home home) {
            return checked(conversation, ops, home).ops();
          }

          @Override
          public TodoTools.Checked checked(String conversation, List<TodoOp> ops, Home home) {
            return new TodoTools.Checked(ops, List.of("note b"));
          }
        };
    Fake fake = new Fake();

    String out =
        new TodoTools.Write(
                fake, in("cnv_1"), "s", TodoTools.BeforeApply.of(List.of(first, plain, second)))
            .run("{\"ops\":[{\"op\":\"add\",\"text\":\"x\"}]}", Home.global());

    assertTrue(out.startsWith("Done. The list is now:\n"), out);
    assertTrue(out.endsWith("\n\nnote a\n\nnote b"), out);
    assertEquals(1, fake.applied.size());
  }

  @Test
  void a_refused_batch_carries_no_note() {
    Fake fake = new Fake();
    fake.refuse = new TodoRefused("todo_write refused operation 1: nope. Nothing was changed.");
    TodoTools.BeforeApply noting =
        new TodoTools.BeforeApply() {
          @Override
          public List<TodoOp> check(String conversation, List<TodoOp> ops, Home home) {
            return ops;
          }

          @Override
          public TodoTools.Checked checked(String conversation, List<TodoOp> ops, Home home) {
            return new TodoTools.Checked(ops, List.of("never shown"));
          }
        };

    String out =
        new TodoTools.Write(fake, in("cnv_1"), "s", noting)
            .run("{\"ops\":[{\"op\":\"add\",\"text\":\"x\"}]}", Home.global());

    assertEquals("todo_write refused operation 1: nope. Nothing was changed.", out);
  }
}
