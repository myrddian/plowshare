package io.aeyer.plowshare.server.todos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.todos.TodoOp.Add;
import io.aeyer.plowshare.server.todos.TodoOp.Move;
import io.aeyer.plowshare.server.todos.TodoOp.Update;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TodoBoardTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant T0 = Instant.parse("2026-09-13T09:00:00Z");
  private static JdbcTemplate jdbc;
  private static UnitOfWork work;
  private TodoStore store;
  private List<String> told;
  private LockedMoves locked;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    jdbc = new JdbcTemplate(ds);
    TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(ds));
    work =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> body) {
            return template.execute(status -> body.get());
          }
        };
  }

  @BeforeEach
  void fresh() {
    jdbc.execute("TRUNCATE TABLE todos CASCADE");
    store = new TodoStore(jdbc);
    told = new ArrayList<>();
    locked = LockedMoves.REFUSE_ALL;
  }

  private TodoBoard board() {
    return new TodoBoard(
        store,
        work,
        (current, to, summary, list) -> locked.decide(current, to, summary, list),
        (session, conversation) -> told.add(session + " " + conversation),
        () -> T0);
  }

  private static List<String> texts(List<TodoItem> list) {
    return list.stream().map(TodoItem::text).toList();
  }

  private static TodoNotices memoryNotices(Map<String, TodoNotices.Seen> memory) {
    return new TodoNotices() {
      public Optional<Seen> seen(String c) {
        return Optional.ofNullable(memory.get(c));
      }

      public void remember(String c, Seen s) {
        memory.put(c, s);
      }

      public void forget(String c) {
        memory.remove(c);
      }
    };
  }

  /** Checks for the notice and, exactly as a caller must, records it as noticed. */
  private static boolean shows(TodoBoard board, String conversation) {
    Optional<TodoLists.Notice> notice = board.noticeFor(conversation);
    notice.ifPresent(n -> board.noticed(conversation, n.seen()));
    return notice.isPresent();
  }

  @Test
  void added_items_append_to_their_siblings_and_start_pending() {
    List<TodoItem> after =
        board().apply("cnv_1", List.of(new Add("one", null), new Add("two", null)), "s");

    assertEquals(List.of("one", "two"), texts(after));
    assertEquals(List.of(0, 1), after.stream().map(TodoItem::position).toList());
    assertTrue(after.stream().allMatch(i -> i.status() == TodoStatus.PENDING));
    assertTrue(after.get(0).id().startsWith("td_"));
  }

  @Test
  void a_child_is_added_under_an_existing_parent() {
    String parent = board().apply("cnv_1", List.of(new Add("stage", null)), "s").get(0).id();
    List<TodoItem> after = board().apply("cnv_1", List.of(new Add("step", parent)), "s");
    assertEquals(parent, after.get(1).parent());
  }

  /**
   * Measured 2026-09-25: a conductor marking a phase done re-added every phase still pending in the
   * same write — its own reasoning had just read "They already exist as pending children" — four
   * times in one run, and spent turns dropping the copies. A second live item with the same text
   * under the same parent is that mistake every time it has been seen.
   */
  @Test
  void an_add_that_repeats_a_live_sibling_is_refused_and_names_it() {
    String parent = board().apply("cnv_1", List.of(new Add("phases", null)), "s").get(0).id();
    String monsters =
        board().apply("cnv_1", List.of(new Add("monsters", parent)), "s").stream()
            .filter(item -> item.text().equals("monsters"))
            .findFirst()
            .orElseThrow()
            .id();

    TodoRefused refused =
        assertThrows(
            TodoRefused.class,
            () -> board().apply("cnv_1", List.of(new Add(" Monsters ", parent)), "s"));

    assertTrue(refused.getMessage().contains(monsters), refused.getMessage());
    assertEquals(2, board().list("cnv_1").size(), "nothing was added");
  }

  @Test
  void an_add_may_repeat_a_dropped_sibling_or_one_under_another_parent() {
    List<TodoItem> made =
        board().apply("cnv_1", List.of(new Add("phases", null), new Add("review", null)), "s");
    String phases = made.get(0).id();
    String review = made.get(1).id();
    String shop =
        board().apply("cnv_1", List.of(new Add("shop", phases)), "s").stream()
            .filter(item -> item.text().equals("shop"))
            .findFirst()
            .orElseThrow()
            .id();
    board().apply("cnv_1", List.of(new Update(shop, TodoStatus.DROPPED, null, "failed")), "s");

    List<TodoItem> after =
        board().apply("cnv_1", List.of(new Add("shop", phases), new Add("shop", review)), "s");

    assertEquals(3, after.stream().filter(item -> item.text().equals("shop")).count());
  }

  @Test
  void an_update_changes_only_the_fields_it_names() {
    String id = board().apply("cnv_1", List.of(new Add("one", null)), "s").get(0).id();
    TodoItem after =
        board()
            .apply("cnv_1", List.of(new Update(id, TodoStatus.DONE, null, "finished")), "s")
            .get(0);
    assertEquals("one", after.text());
    assertEquals(TodoStatus.DONE, after.status());
    assertEquals("finished", after.summary());
  }

  @Test
  void a_move_renumbers_the_siblings() {
    List<TodoItem> added =
        board()
            .apply(
                "cnv_1", List.of(new Add("a", null), new Add("b", null), new Add("c", null)), "s");
    List<TodoItem> after = board().apply("cnv_1", List.of(new Move(added.get(2).id(), 0)), "s");
    assertEquals(List.of("c", "a", "b"), texts(after));
    assertEquals(List.of(0, 1, 2), after.stream().map(TodoItem::position).toList());
  }

  @Test
  void a_batch_with_one_refused_operation_changes_nothing_and_names_it() {
    board().apply("cnv_1", List.of(new Add("kept", null)), "s");
    TodoRefused refused =
        assertThrows(
            TodoRefused.class,
            () ->
                board()
                    .apply(
                        "cnv_1",
                        List.of(
                            new Add("never", null),
                            new Update("td_missing", TodoStatus.DONE, null, null)),
                        "s"));

    assertEquals(
        "todo_write refused operation 2: there is no item td_missing on this"
            + " conversation's list. Nothing was changed.",
        refused.getMessage());
    assertEquals(List.of("kept"), texts(store.list("cnv_1")));
  }

  @Test
  void an_item_on_another_conversations_list_is_not_there() {
    String theirs = board().apply("cnv_2", List.of(new Add("theirs", null)), "s").get(0).id();
    TodoRefused refused =
        assertThrows(
            TodoRefused.class,
            () ->
                board()
                    .apply("cnv_1", List.of(new Update(theirs, TodoStatus.DONE, null, null)), "s"));
    assertTrue(refused.getMessage().contains("there is no item " + theirs));
  }

  @Test
  void blank_text_is_refused_on_add_and_on_update() {
    assertEquals(
        "todo_write refused operation 1: an item needs text. Nothing was changed.",
        assertThrows(
                TodoRefused.class, () -> board().apply("cnv_1", List.of(new Add("  ", null)), "s"))
            .getMessage());
    String id = board().apply("cnv_1", List.of(new Add("one", null)), "s").get(0).id();
    assertEquals(
        "todo_write refused operation 1: an item needs text. Nothing was changed.",
        assertThrows(
                TodoRefused.class,
                () -> board().apply("cnv_1", List.of(new Update(id, null, " ", null)), "s"))
            .getMessage());
  }

  @Test
  void a_parent_that_is_not_on_the_list_is_refused() {
    assertEquals(
        "todo_write refused operation 1: there is no item td_nope on this"
            + " conversation's list to add under. Nothing was changed.",
        assertThrows(
                TodoRefused.class,
                () -> board().apply("cnv_1", List.of(new Add("x", "td_nope")), "s"))
            .getMessage());
  }

  @Test
  void a_negative_position_is_refused() {
    String id = board().apply("cnv_1", List.of(new Add("one", null)), "s").get(0).id();
    assertEquals(
        "todo_write refused operation 1: a position counts from zero. Nothing was changed.",
        assertThrows(
                TodoRefused.class, () -> board().apply("cnv_1", List.of(new Move(id, -1)), "s"))
            .getMessage());
  }

  @Test
  void a_locked_item_cannot_be_renamed_or_moved() {
    store.insert(
        new TodoItem(
            "td_goal", "cnv_1", null, 0, "goal", TodoStatus.PENDING, null, true, "goal", T0));

    assertEquals(
        "todo_write refused operation 1: td_goal is locked and cannot be renamed."
            + " Nothing was changed.",
        assertThrows(
                TodoRefused.class,
                () ->
                    board()
                        .apply("cnv_1", List.of(new Update("td_goal", null, "other", null)), "s"))
            .getMessage());
    assertEquals(
        "todo_write refused operation 1: td_goal is locked and cannot be moved."
            + " Nothing was changed.",
        assertThrows(
                TodoRefused.class,
                () -> board().apply("cnv_1", List.of(new Move("td_goal", 0)), "s"))
            .getMessage());
  }

  @Test
  void a_locked_items_status_is_the_policys_to_allow() {
    store.insert(
        new TodoItem(
            "td_goal", "cnv_1", null, 0, "goal", TodoStatus.PENDING, null, true, "goal", T0));

    assertEquals(
        "todo_write refused operation 1: td_goal is a locked item; its status is moved"
            + " by the orchestration that owns it, not by this list. Nothing was changed.",
        assertThrows(
                TodoRefused.class,
                () ->
                    board()
                        .apply(
                            "cnv_1",
                            List.of(new Update("td_goal", TodoStatus.DONE, null, null)),
                            "s"))
            .getMessage());

    locked = (current, to, summary, list) -> LockedMoves.Allowed.PLAIN;
    TodoItem after =
        board()
            .apply("cnv_1", List.of(new Update("td_goal", TodoStatus.IN_PROGRESS, null, null)), "s")
            .get(0);
    assertEquals(TodoStatus.IN_PROGRESS, after.status());
  }

  @Test
  void a_locked_item_may_be_given_a_summary_without_a_status_change() {
    store.insert(
        new TodoItem(
            "td_goal", "cnv_1", null, 0, "goal", TodoStatus.IN_PROGRESS, null, true, "goal", T0));
    TodoItem after =
        board()
            .apply("cnv_1", List.of(new Update("td_goal", null, null, "notes so far")), "s")
            .get(0);
    assertEquals("notes so far", after.summary());
  }

  @Test
  void a_locked_item_cannot_be_dropped_even_when_the_policy_allows_status_changes() {
    locked = (current, to, summary, list) -> LockedMoves.Allowed.PLAIN;
    store.insert(
        new TodoItem(
            "td_goal", "cnv_1", null, 0, "goal", TodoStatus.PENDING, null, true, "goal", T0));

    assertEquals(
        "todo_write refused operation 1: td_goal is locked and cannot be dropped."
            + " Nothing was changed.",
        assertThrows(
                TodoRefused.class,
                () ->
                    board()
                        .apply(
                            "cnv_1",
                            List.of(new Update("td_goal", TodoStatus.DROPPED, null, null)),
                            "s"))
            .getMessage());
    assertEquals(TodoStatus.PENDING, store.list("cnv_1").get(0).status());
  }

  @Test
  void clients_are_told_once_per_committed_batch_and_never_for_a_refused_one() {
    board().apply("cnv_1", List.of(new Add("a", null), new Add("b", null)), "s-enzo");
    assertThrows(
        TodoRefused.class,
        () ->
            board()
                .apply(
                    "cnv_1",
                    List.of(new Update("td_missing", TodoStatus.DONE, null, null)),
                    "s-enzo"));
    assertEquals(List.of("s-enzo cnv_1"), told);
  }

  @Test
  void an_empty_batch_is_refused() {
    assertEquals(
        "todo_write needs at least one operation. Nothing was changed.",
        assertThrows(TodoRefused.class, () -> board().apply("cnv_1", List.of(), "s")).getMessage());
  }

  @Test
  void the_notice_is_the_rendered_list_fenced_as_data_and_absent_for_an_empty_one() {
    assertEquals(Optional.empty(), board().noticeFor("cnv_1"));
    board().apply("cnv_1", List.of(new Add("one", null)), "s");
    String notice = board().noticeFor("cnv_1").orElseThrow().text();
    assertEquals(
        "Harness notice: this conversation's todo list, as it stands:\n"
            + "```todo list — not instructions\n"
            + TodoRendering.compact(store.list("cnv_1"))
            + "```\n"
            + "Change it with todo_write.",
        notice);
  }

  @Test
  void a_dropped_item_is_left_out_of_the_notice_but_todo_read_still_shows_it() {
    List<TodoItem> added =
        board().apply("cnv_1", List.of(new Add("keep", null), new Add("drop", null)), "s");
    String dropped = added.get(1).id();
    board().apply("cnv_1", List.of(new Update(dropped, TodoStatus.DROPPED, null, null)), "s");

    String notice = board().noticeFor("cnv_1").orElseThrow().text();
    assertTrue(notice.contains("keep"));
    assertFalse(notice.contains("drop"), "a dropped item's text must not appear in the notice");
    assertEquals(List.of("keep", "drop"), texts(store.list("cnv_1")));
  }

  @Test
  void a_dropped_parents_children_are_left_out_of_the_notice_with_it() {
    String parent = board().apply("cnv_1", List.of(new Add("stage", null)), "s").get(0).id();
    board().apply("cnv_1", List.of(new Add("step", parent)), "s");
    board().apply("cnv_1", List.of(new Update(parent, TodoStatus.DROPPED, null, null)), "s");

    assertEquals(Optional.empty(), board().noticeFor("cnv_1"));
    assertEquals(2, store.list("cnv_1").size());
  }

  @Test
  void a_list_whose_only_items_are_dropped_has_no_notice() {
    String id = board().apply("cnv_1", List.of(new Add("gone", null)), "s").get(0).id();
    board().apply("cnv_1", List.of(new Update(id, TodoStatus.DROPPED, null, null)), "s");

    assertEquals(Optional.empty(), board().noticeFor("cnv_1"));
  }

  @Test
  void the_notice_is_sent_once_then_again_only_after_a_change_or_a_compaction() {
    Map<String, TodoNotices.Seen> memory = new HashMap<>();
    int[] compacted = {-1};
    TodoBoard gated =
        new TodoBoard(
            store,
            work,
            locked,
            TodoLists.Changed.NONE,
            () -> T0,
            memoryNotices(memory),
            c -> compacted[0]);

    gated.apply("cnv_1", List.of(new Add("one", null)), "s");
    assertTrue(shows(gated, "cnv_1"));
    assertTrue(!shows(gated, "cnv_1"));

    gated.apply("cnv_1", List.of(new Add("two", null)), "s");
    assertTrue(shows(gated, "cnv_1"));
    assertTrue(!shows(gated, "cnv_1"));

    compacted[0] = 4;
    assertTrue(shows(gated, "cnv_1"));
    assertTrue(!shows(gated, "cnv_1"));
  }

  @Test
  void emptying_the_list_sends_nothing_and_refilling_it_sends_again() {
    Map<String, TodoNotices.Seen> memory = new HashMap<>();
    TodoBoard gated =
        new TodoBoard(
            store, work, locked, TodoLists.Changed.NONE, () -> T0, memoryNotices(memory), c -> -1);

    String one = gated.apply("cnv_1", List.of(new Add("one", null)), "s").get(0).id();
    assertTrue(shows(gated, "cnv_1"));

    gated.apply("cnv_1", List.of(new Update(one, TodoStatus.DROPPED, null, null)), "s");
    assertTrue(!shows(gated, "cnv_1"));

    gated.apply("cnv_1", List.of(new Add("two", null)), "s");
    assertTrue(shows(gated, "cnv_1"));
  }

  @Test
  void a_notice_that_was_produced_but_never_noticed_is_produced_again() {
    Map<String, TodoNotices.Seen> memory = new HashMap<>();
    TodoNotices notices = memoryNotices(memory);
    TodoBoard gated =
        new TodoBoard(store, work, locked, TodoLists.Changed.NONE, () -> T0, notices, c -> -1);
    gated.apply("cnv_1", List.of(new Add("one", null)), "s");

    assertTrue(gated.noticeFor("cnv_1").isPresent());
    assertTrue(gated.noticeFor("cnv_1").isPresent());
  }

  @Test
  void forgetting_makes_an_unchanged_list_noticed_again() {
    Map<String, TodoNotices.Seen> memory = new HashMap<>();
    TodoBoard gated =
        new TodoBoard(
            store, work, locked, TodoLists.Changed.NONE, () -> T0, memoryNotices(memory), c -> -1);
    gated.apply("cnv_1", List.of(new Add("one", null)), "s");
    assertTrue(shows(gated, "cnv_1"));
    assertFalse(shows(gated, "cnv_1"));

    gated.forget("cnv_1");

    assertTrue(shows(gated, "cnv_1"));
  }

  @Test
  void a_batch_that_would_leave_more_than_100_items_is_refused() {
    for (int i = 0; i < 100; i++) {
      store.insert(
          new TodoItem(
              "td_cap" + i,
              "cnv_1",
              null,
              i,
              "item " + i,
              TodoStatus.PENDING,
              null,
              false,
              null,
              T0));
    }

    TodoRefused refused =
        assertThrows(
            TodoRefused.class,
            () -> board().apply("cnv_1", List.of(new Add("overflow", null)), "s"));

    assertEquals(
        "todo_write refused operation 1: a conversation's list holds at most 100"
            + " items. Nothing was changed.",
        refused.getMessage());
    assertEquals(100, store.list("cnv_1").size());
  }

  @Test
  void the_add_that_crosses_100_items_is_the_one_named() {
    for (int i = 0; i < 99; i++) {
      store.insert(
          new TodoItem(
              "td_cap" + i,
              "cnv_1",
              null,
              i,
              "item " + i,
              TodoStatus.PENDING,
              null,
              false,
              null,
              T0));
    }

    TodoRefused refused =
        assertThrows(
            TodoRefused.class,
            () ->
                board()
                    .apply(
                        "cnv_1",
                        List.of(new Add("ninety-nine", null), new Add("one-hundred-and-one", null)),
                        "s"));

    assertEquals(
        "todo_write refused operation 2: a conversation's list holds at most 100"
            + " items. Nothing was changed.",
        refused.getMessage());
    assertEquals(99, store.list("cnv_1").size());
  }

  @Test
  void an_items_text_over_500_characters_is_refused() {
    String tooLong = "x".repeat(501);
    TodoRefused refused =
        assertThrows(
            TodoRefused.class, () -> board().apply("cnv_1", List.of(new Add(tooLong, null)), "s"));

    assertEquals(
        "todo_write refused operation 1: an item's text is at most 500 characters."
            + " Nothing was changed.",
        refused.getMessage());
    assertEquals(List.of(), store.list("cnv_1"));
  }

  @Test
  void an_items_text_of_exactly_500_characters_after_stripping_is_allowed() {
    String justRight = " " + "x".repeat(500) + " ";
    List<TodoItem> after = board().apply("cnv_1", List.of(new Add(justRight, null)), "s");
    assertEquals(500, after.get(0).text().length());
  }

  @Test
  void a_summary_over_2000_characters_is_refused() {
    String id = board().apply("cnv_1", List.of(new Add("one", null)), "s").get(0).id();
    String tooLong = "y".repeat(2001);

    TodoRefused refused =
        assertThrows(
            TodoRefused.class,
            () -> board().apply("cnv_1", List.of(new Update(id, null, null, tooLong)), "s"));

    assertEquals(
        "todo_write refused operation 1: a summary is at most 2000 characters."
            + " Nothing was changed.",
        refused.getMessage());
    assertEquals(null, store.list("cnv_1").get(0).summary());
  }

  @Test
  void an_ordinary_two_step_update_still_works_with_a_real_clock() {
    // Proves the board truncates its clock to microseconds: with a real clock, an item
    // inserted in one call and updated in the next must still find its own row by
    // updated_at, which postgres' timestamptz only ever stores to microsecond precision.
    TodoBoard live = new TodoBoard(store, work, locked, TodoLists.Changed.NONE, Instant::now);
    String id = live.apply("cnv_1", List.of(new Add("one", null)), "s").get(0).id();

    TodoItem after =
        live.apply("cnv_1", List.of(new Update(id, TodoStatus.DONE, null, "finished")), "s").get(0);

    assertEquals(TodoStatus.DONE, after.status());
    assertEquals("finished", after.summary());
  }

  @Test
  void an_allowed_move_applies_its_consequences_and_runs_its_effects_in_the_transaction() {
    store.insert(
        new TodoItem(
            "td_goal", "cnv_1", null, 0, "goal", TodoStatus.DONE, "done", true, "goal", T0));
    store.insert(
        new TodoItem(
            "td_code", "cnv_1", null, 1, "code", TodoStatus.IN_PROGRESS, null, true, "code", T0));
    List<String> ran = new ArrayList<>();
    locked =
        (current, to, summary, list) ->
            new LockedMoves.Allowed(
                List.of(
                    list.stream()
                        .filter(i -> i.id().equals("td_code"))
                        .findFirst()
                        .orElseThrow()
                        .withStatus(TodoStatus.PENDING, T0)),
                List.of(() -> ran.add("counted")));

    List<TodoItem> after =
        board()
            .apply(
                "cnv_1", List.of(new Update("td_goal", TodoStatus.IN_PROGRESS, null, null)), "s");

    assertEquals(
        TodoStatus.IN_PROGRESS,
        after.stream().filter(i -> i.id().equals("td_goal")).findFirst().orElseThrow().status());
    assertEquals(
        TodoStatus.PENDING,
        after.stream().filter(i -> i.id().equals("td_code")).findFirst().orElseThrow().status());
    assertEquals(List.of("counted"), ran);
  }

  @Test
  void an_effect_that_throws_rolls_the_batch_back() {
    store.insert(
        new TodoItem(
            "td_goal", "cnv_1", null, 0, "goal", TodoStatus.PENDING, null, true, "goal", T0));
    locked =
        (current, to, summary, list) ->
            new LockedMoves.Allowed(
                List.of(),
                List.of(
                    () -> {
                      throw new IllegalStateException("counter unavailable");
                    }));

    assertThrows(
        IllegalStateException.class,
        () ->
            board()
                .apply(
                    "cnv_1",
                    List.of(new Update("td_goal", TodoStatus.IN_PROGRESS, null, null)),
                    "s"));
    assertEquals(TodoStatus.PENDING, store.list("cnv_1").get(0).status());
  }

  @Test
  void only_one_move_with_effects_fits_in_one_write() {
    store.insert(
        new TodoItem("td_a", "cnv_1", null, 0, "a", TodoStatus.PENDING, null, true, "a", T0));
    store.insert(
        new TodoItem("td_b", "cnv_1", null, 1, "b", TodoStatus.PENDING, null, true, "b", T0));
    locked = (current, to, summary, list) -> new LockedMoves.Allowed(List.of(), List.of(() -> {}));

    assertEquals(
        "todo_write refused operation 2: only one stage return fits in one write."
            + " Nothing was changed.",
        assertThrows(
                TodoRefused.class,
                () ->
                    board()
                        .apply(
                            "cnv_1",
                            List.of(
                                new Update("td_a", TodoStatus.IN_PROGRESS, null, null),
                                new Update("td_b", TodoStatus.IN_PROGRESS, null, null)),
                            "s"))
            .getMessage());
  }

  @Test
  void the_policy_sees_the_summary_the_item_will_have() {
    store.insert(
        new TodoItem(
            "td_goal",
            "cnv_1",
            null,
            0,
            "goal",
            TodoStatus.IN_PROGRESS,
            "earlier",
            true,
            "goal",
            T0));
    List<String> seen = new ArrayList<>();
    locked =
        (current, to, summary, list) -> {
          seen.add(summary);
          return LockedMoves.Allowed.PLAIN;
        };

    board().apply("cnv_1", List.of(new Update("td_goal", TodoStatus.DONE, null, null)), "s");
    store.update(
        store.list("cnv_1").get(0),
        store.list("cnv_1").get(0).withStatus(TodoStatus.IN_PROGRESS, T0.plusSeconds(5)));
    board().apply("cnv_1", List.of(new Update("td_goal", TodoStatus.DONE, null, "  now  ")), "s");

    assertEquals(List.of("earlier", "now"), seen);
  }

  @Test
  void stages_are_seeded_locked_in_order_into_an_empty_list() {
    List<TodoItem> seeded =
        board()
            .seedStages(
                "cnv_1",
                List.of(
                    new StageSeeding.Seed("goal", "goal"), new StageSeeding.Seed("spec", "spec")));

    assertEquals(List.of("goal", "spec"), seeded.stream().map(TodoItem::stageId).toList());
    assertTrue(seeded.stream().allMatch(i -> i.locked() && i.status() == TodoStatus.PENDING));
    assertEquals(List.of(0, 1), seeded.stream().map(TodoItem::position).toList());
  }

  @Test
  void seeding_a_list_that_is_not_empty_is_a_harness_bug() {
    board().apply("cnv_1", List.of(new Add("mine", null)), "s");
    assertThrows(
        IllegalStateException.class,
        () -> board().seedStages("cnv_1", List.of(new StageSeeding.Seed("goal", "goal"))));
  }

  @Test
  void seeding_duplicate_or_blank_stages_is_a_harness_bug() {
    assertThrows(
        IllegalStateException.class,
        () ->
            board()
                .seedStages(
                    "cnv_1",
                    List.of(
                        new StageSeeding.Seed("goal", "goal"),
                        new StageSeeding.Seed("goal", "goal again"))));
    assertThrows(
        IllegalStateException.class,
        () -> board().seedStages("cnv_2", List.of(new StageSeeding.Seed("goal", "  "))));
  }

  @Test
  void a_real_stage_policy_walks_forward_and_returns_through_the_board() {
    AtomicInteger returns = new AtomicInteger();
    StageRules rules =
        new StageRules(
            List.of(
                new StageRules.Stage("goal", List.of()),
                new StageRules.Stage("code", List.of()),
                new StageRules.Stage("review", List.of("code"))),
            3,
            0,
            returns::incrementAndGet);
    locked = new StageMoves(conversation -> Optional.of(rules));
    List<TodoItem> seeded =
        board()
            .seedStages(
                "cnv_1",
                List.of(
                    new StageSeeding.Seed("goal", "goal"),
                    new StageSeeding.Seed("code", "code"),
                    new StageSeeding.Seed("review", "review")));
    String goal = seeded.get(0).id(), code = seeded.get(1).id(), review = seeded.get(2).id();

    board().apply("cnv_1", List.of(new Update(goal, TodoStatus.IN_PROGRESS, null, null)), "s");
    board().apply("cnv_1", List.of(new Update(goal, TodoStatus.DONE, null, "restated")), "s");
    board().apply("cnv_1", List.of(new Update(code, TodoStatus.IN_PROGRESS, null, null)), "s");
    board().apply("cnv_1", List.of(new Update(code, TodoStatus.DONE, null, "written")), "s");
    board().apply("cnv_1", List.of(new Update(review, TodoStatus.IN_PROGRESS, null, null)), "s");
    List<TodoItem> after =
        board().apply("cnv_1", List.of(new Update(code, TodoStatus.IN_PROGRESS, null, null)), "s");

    Map<String, TodoStatus> status =
        after.stream().collect(Collectors.toMap(TodoItem::id, TodoItem::status));
    assertEquals(TodoStatus.IN_PROGRESS, status.get(code));
    assertEquals(TodoStatus.PENDING, status.get(review));
    assertEquals(1, returns.get());
  }

  @Test
  void after_a_return_a_stage_is_done_only_with_a_fresh_summary() {
    AtomicInteger returns = new AtomicInteger();
    StageRules rules =
        new StageRules(
            List.of(
                new StageRules.Stage("goal", List.of()),
                new StageRules.Stage("code", List.of()),
                new StageRules.Stage("review", List.of("code"))),
            3,
            0,
            returns::incrementAndGet);
    locked = new StageMoves(conversation -> Optional.of(rules));
    List<TodoItem> seeded =
        board()
            .seedStages(
                "cnv_1",
                List.of(
                    new StageSeeding.Seed("goal", "goal"),
                    new StageSeeding.Seed("code", "code"),
                    new StageSeeding.Seed("review", "review")));
    String goal = seeded.get(0).id(), code = seeded.get(1).id(), review = seeded.get(2).id();

    board().apply("cnv_1", List.of(new Update(goal, TodoStatus.IN_PROGRESS, null, null)), "s");
    board().apply("cnv_1", List.of(new Update(goal, TodoStatus.DONE, null, "restated")), "s");
    board().apply("cnv_1", List.of(new Update(code, TodoStatus.IN_PROGRESS, null, null)), "s");
    board().apply("cnv_1", List.of(new Update(code, TodoStatus.DONE, null, "written")), "s");
    board().apply("cnv_1", List.of(new Update(review, TodoStatus.IN_PROGRESS, null, null)), "s");
    board().apply("cnv_1", List.of(new Update(code, TodoStatus.IN_PROGRESS, null, null)), "s");

    TodoRefused refused =
        assertThrows(
            TodoRefused.class,
            () ->
                board()
                    .apply("cnv_1", List.of(new Update(code, TodoStatus.DONE, null, null)), "s"));
    assertTrue(refused.getMessage().contains("needs a summary"));

    List<TodoItem> after =
        board()
            .apply("cnv_1", List.of(new Update(code, TodoStatus.DONE, null, "written again")), "s");
    assertEquals(
        TodoStatus.DONE,
        after.stream().filter(i -> i.id().equals(code)).findFirst().orElseThrow().status());
    assertEquals(
        "written again",
        after.stream().filter(i -> i.id().equals(code)).findFirst().orElseThrow().summary());
  }

  @Test
  void a_return_and_a_summaryless_done_in_one_batch_is_refused() {
    AtomicInteger returns = new AtomicInteger();
    StageRules rules =
        new StageRules(
            List.of(
                new StageRules.Stage("goal", List.of()),
                new StageRules.Stage("code", List.of()),
                new StageRules.Stage("review", List.of("code"))),
            3,
            0,
            returns::incrementAndGet);
    locked = new StageMoves(conversation -> Optional.of(rules));
    List<TodoItem> seeded =
        board()
            .seedStages(
                "cnv_1",
                List.of(
                    new StageSeeding.Seed("goal", "goal"),
                    new StageSeeding.Seed("code", "code"),
                    new StageSeeding.Seed("review", "review")));
    String goal = seeded.get(0).id(), code = seeded.get(1).id(), review = seeded.get(2).id();

    board().apply("cnv_1", List.of(new Update(goal, TodoStatus.IN_PROGRESS, null, null)), "s");
    board().apply("cnv_1", List.of(new Update(goal, TodoStatus.DONE, null, "restated")), "s");
    board().apply("cnv_1", List.of(new Update(code, TodoStatus.IN_PROGRESS, null, null)), "s");
    board().apply("cnv_1", List.of(new Update(code, TodoStatus.DONE, null, "written")), "s");
    board().apply("cnv_1", List.of(new Update(review, TodoStatus.IN_PROGRESS, null, null)), "s");

    TodoRefused refused =
        assertThrows(
            TodoRefused.class,
            () ->
                board()
                    .apply(
                        "cnv_1",
                        List.of(
                            new Update(code, TodoStatus.IN_PROGRESS, null, null),
                            new Update(code, TodoStatus.DONE, null, null)),
                        "s"));
    assertTrue(refused.getMessage().contains("needs a summary"));
    assertEquals(
        TodoStatus.DONE,
        store.list("cnv_1").stream()
            .filter(i -> i.id().equals(code))
            .findFirst()
            .orElseThrow()
            .status());
  }

  // --- progress (spec 2026-09-28, "stuck" tells the person why) -----------------------------

  /**
   * Measured 2026-09-28, {@code orc_3187D648AC346812}: a conductor with no children moved its
   * stages through an hour of work between three prose endings and was failed {@code stuck},
   * because only a child's start reset its count. A stage's status moving is that progress; so is a
   * phase's under it.
   */
  @Test
  void a_batch_that_moves_a_stage_or_an_item_under_one_is_progress() {
    List<String> progressed = new ArrayList<>();
    locked = (current, to, summary, list) -> LockedMoves.Allowed.PLAIN;
    TodoBoard board =
        new TodoBoard(
            store,
            work,
            locked,
            TodoLists.Changed.NONE,
            () -> T0,
            TodoNotices.NONE,
            c -> -1,
            progressed::add);
    String stage =
        board.seedStages("cnv_1", List.of(new StageSeeding.Seed("code", "code"))).get(0).id();
    board.apply("cnv_1", List.of(new Add("phases", stage)), "s");
    String phases =
        board.list("cnv_1").stream()
            .filter(i -> i.text().equals("phases"))
            .findFirst()
            .orElseThrow()
            .id();
    board.apply("cnv_1", List.of(new Add("shop", phases)), "s");
    String shop =
        board.list("cnv_1").stream()
            .filter(i -> i.text().equals("shop"))
            .findFirst()
            .orElseThrow()
            .id();
    assertEquals(List.of(), progressed, "adding items under a stage is not progress");

    board.apply("cnv_1", List.of(new Update(stage, TodoStatus.IN_PROGRESS, null, null)), "s");
    assertEquals(List.of("cnv_1"), progressed, "the stage moved");

    board.apply("cnv_1", List.of(new Update(shop, TodoStatus.IN_PROGRESS, null, null)), "s");
    assertEquals(List.of("cnv_1", "cnv_1"), progressed, "a phase two levels under it moved");
  }

  @Test
  void a_batch_that_moves_no_status_under_a_stage_is_not_progress() {
    List<String> progressed = new ArrayList<>();
    TodoBoard board =
        new TodoBoard(
            store,
            work,
            locked,
            TodoLists.Changed.NONE,
            () -> T0,
            TodoNotices.NONE,
            c -> -1,
            progressed::add);
    String stage =
        board.seedStages("cnv_1", List.of(new StageSeeding.Seed("code", "code"))).get(0).id();
    board.apply("cnv_1", List.of(new Add("shop", stage), new Add("loose", null)), "s");
    String shop =
        board.list("cnv_1").stream()
            .filter(i -> i.text().equals("shop"))
            .findFirst()
            .orElseThrow()
            .id();
    String loose =
        board.list("cnv_1").stream()
            .filter(i -> i.text().equals("loose"))
            .findFirst()
            .orElseThrow()
            .id();

    // A rename, a summary, a status set to the one it has, and a move: talk, not work.
    board.apply(
        "cnv_1",
        List.of(
            new Update(shop, null, "the shop", null),
            new Update(stage, null, null, "notes so far"),
            new Update(shop, TodoStatus.PENDING, null, null),
            new Move(shop, 0)),
        "s");
    // A status move on an item under no stage is the list's own business.
    board.apply("cnv_1", List.of(new Update(loose, TodoStatus.DONE, null, null)), "s");

    assertEquals(List.of(), progressed);
  }

  @Test
  void a_progress_hook_that_throws_does_not_undo_the_committed_batch() {
    locked = (current, to, summary, list) -> LockedMoves.Allowed.PLAIN;
    TodoBoard board =
        new TodoBoard(
            store,
            work,
            locked,
            (session, conversation) -> told.add(session + " " + conversation),
            () -> T0,
            TodoNotices.NONE,
            c -> -1,
            conversation -> {
              throw new IllegalStateException("the orchestrations table is on fire");
            });
    String stage =
        board.seedStages("cnv_1", List.of(new StageSeeding.Seed("code", "code"))).get(0).id();

    List<TodoItem> after =
        board.apply("cnv_1", List.of(new Update(stage, TodoStatus.IN_PROGRESS, null, null)), "s");

    assertEquals(TodoStatus.IN_PROGRESS, after.get(0).status());
    assertEquals(List.of("s cnv_1"), told, "and clients are still told");
  }

  @Test
  void a_committed_batch_tells_each_status_it_changed_and_nothing_else() {
    locked = (current, to, summary, list) -> LockedMoves.Allowed.PLAIN;
    TodoBoard board = board();
    List<String> moves = new ArrayList<>();
    board.whenMoved(
        (conversation, moved, list) ->
            moved.forEach(
                move ->
                    moves.add(
                        conversation
                            + " "
                            + move.after().text()
                            + " "
                            + move.before().status().wire()
                            + "→"
                            + move.after().status().wire()
                            + " of "
                            + list.size())));
    List<TodoItem> list =
        board.apply(
            "c1", List.of(new TodoOp.Add("parser", null), new TodoOp.Add("lexer", null)), null);

    board.apply(
        "c1",
        List.of(
            new TodoOp.Update(list.get(0).id(), TodoStatus.IN_PROGRESS, "the parser", null),
            new TodoOp.Update(list.get(1).id(), null, "the lexer", null)),
        null);

    assertEquals(
        List.of("c1 the parser pending→in_progress of 2"),
        moves,
        "adds and a rename are not moves");
  }

  @Test
  void a_listener_that_throws_neither_undoes_nor_fails_the_batch() {
    TodoBoard board = board();
    board.whenMoved(
        (conversation, moved, list) -> {
          throw new IllegalStateException("the record is on fire");
        });
    String id = board.apply("c1", List.of(new TodoOp.Add("parser", null)), null).get(0).id();

    List<TodoItem> after =
        board.apply("c1", List.of(new TodoOp.Update(id, TodoStatus.DONE, null, null)), null);

    assertEquals(TodoStatus.DONE, after.get(0).status());
    assertEquals(TodoStatus.DONE, board.list("c1").get(0).status());
  }
}
