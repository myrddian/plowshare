package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.CompletionOutcome;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.Invocation;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The log itself: what it holds, what it refuses, and what a fold does to it.
 *
 * <h2>What this file is for that {@code ProjectionTest} is not</h2>
 *
 * <p>{@code ProjectionTest} asks what a model would be shown. This asks what the table will accept,
 * which is the half nothing in Java can hold: {@code entries_kind_is_known} and {@code
 * entries_role_matches_kind} are the schema's copies of rules whose other copies are in {@code
 * EntryKind}, and nothing holds the two together at compile time. {@link
 * #every_kind_this_server_can_write_is_an_entry_this_table_holds} is the seam that fails on the
 * build that adds a constant without a migration rather than on the first conversation to reach it
 * — {@code TurnStoreTest.every_ending_this_server_can_reach_is_a_turn_this_table_holds}, one table
 * over, and for the same reason.
 */
@Tag("full-db")
@Testcontainers
class EntryStoreTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Home PAYMENTS = Home.of("payments");

  /**
   * A fixed moment for the injected clock, so a stamp in an assertion is a number somebody chose.
   * Truncated to milliseconds because that is what a TIMESTAMPTZ column keeps of an Instant with
   * nanoseconds on it.
   */
  private static final Instant THEN = Instant.parse("2026-09-03T14:00:00Z");

  private static JdbcTemplate jdbc;

  private ConversationStore conversations;
  private EntryStore entries;
  private String conversation;

  @Test
  void restarted_reads_keep_distinct_jobs_on_one_continued_turn_and_unknown_legacy_entries() {
    var jobs = new JdbcJobLog(jdbc);
    jobs.started("job_initial", "worker", PAYMENTS, THEN, conversation, null);
    jobs.ended(
        "job_initial",
        io.aeyer.plowshare.server.agents.Outcome.Ending.ANSWERED,
        1,
        1,
        THEN.plusSeconds(1));
    assertEquals(
        conversation, new JdbcJobLog(jdbc).find("job_initial").orElseThrow().conversation());
    entries.append(
        conversation,
        1,
        LoggedEntry.utterance("incoming", Speaker.message("msg_fixture")),
        "job_initial");
    entries.append(conversation, 1, LoggedEntry.answer("paused", List.of()), "job_initial");
    entries.append(conversation, 1, LoggedEntry.runtimeNote("continued"), "job_continuation");
    entries.append(conversation, 2, LoggedEntry.utterance("legacy", Speaker.person(null)));
    var reopened = new EntryStore(jdbc);
    var rows = reopened.pageOfLog(conversation, 0, 10).listed();
    assertEquals("job_initial", rows.get(0).job());
    assertEquals(Speaker.message("msg_fixture"), rows.get(0).speaker());
    assertEquals("job_initial", rows.get(1).job());
    assertEquals("job_continuation", rows.get(2).job());
    assertEquals(rows.get(0).turnOrdinal(), rows.get(2).turnOrdinal());
    assertNull(rows.get(3).job());
    assertEquals(
        "job_initial", reopened.pageOfProjection(conversation, 0, 10).listed().getFirst().job());
  }

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void freshConversations() {
    // board_topics, board_messages and board_seats: V74 gave each a foreign
    // key into conversations. firings: a further hop out, through its V74
    // foreign key into board_topics. user_inbox: a hop past that, through
    // its pre-existing V40 foreign key into firings. Postgres refuses to
    // truncate conversations unless every one of these goes with it.
    // All dependent projections live in this disposable container; reset newer foreign-key
    // dependants too (including embedding staging and job conversation links).
    jdbc.execute(
        "TRUNCATE TABLE board_message_route_bindings, outgoing_work, message_external_tasks, message_external_contexts, board_message_routes, board_message_instances, board_post_receipts, skill_executions, command_invocations, orchestration_start_receipts, orchestration_script_steps, board_reads, board_notices, board_decisions, digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, conversations, turns, compactions, entries, citations, orchestrations, orchestration_messages, board_topics, board_messages, board_seats, firings, user_inbox CASCADE");
    conversations = new ConversationStore(jdbc);
    entries = new EntryStore(jdbc);
    conversation = conversations.open(PAYMENTS, Budget.of(20)).id();
  }

  /**
   * Every kind this server can write is a kind this table holds.
   *
   * <p>Enumerates {@link EntryKind#values()} and writes one of each, so a constant added in Java
   * without a migration fails here rather than at the moment a real conversation reaches it — which
   * is the latest and most expensive place to find out. The shapes differ per kind because the
   * per-kind constraints do; {@link #anEntryOf} is where that mapping lives.
   */
  @Test
  void every_kind_this_server_can_write_is_an_entry_this_table_holds() {
    for (EntryKind kind : EntryKind.values()) {
      EntryRecord written = entries.append(conversation, 1, anEntryOf(kind));
      assertEquals(
          kind,
          written.kind(),
          kind + " could not be written; V11's entries_kind_is_known does not name it");
    }
    assertEquals(EntryKind.values().length, entries.forConversation(conversation).size());
  }

  /**
   * A kind that projects stores the role it projects as, and one that does not stores none.
   *
   * <p>The projection rule, read back out of the table. {@code entries_role_matches_kind} derives
   * the column from the kind with a {@code CASE} that has no {@code ELSE}, so a kind it does not
   * name can only be stored with a NULL role — which is what a projection skips. This asserts the
   * two halves agree for every kind at once.
   */
  @Test
  void a_kind_that_projects_stores_its_role_and_one_that_does_not_stores_none() {
    for (EntryKind kind : EntryKind.values()) {
      entries.append(conversation, 1, anEntryOf(kind));
      String role =
          jdbc.queryForObject(
              "SELECT role FROM entries WHERE conversation_id = ? ORDER BY ordinal DESC"
                  + " LIMIT 1",
              String.class,
              conversation);
      assertEquals(
          kind.role().map(chatRole -> chatRole.wireName()).orElse(null),
          role,
          kind + " stored a role that does not match what it projects as");
    }
  }

  /**
   * A role a caller invented cannot be smuggled past the kind. Written straight at the table,
   * because {@link EntryStore#append} derives the role and gives a caller no way to name one —
   * which is the point, and this is what stops the column being loosened later.
   */
  @Test
  void a_kind_that_does_not_project_cannot_be_given_a_role() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO entries (conversation_id, ordinal, kind, role, content,"
                    + " turn_ordinal) VALUES (?, 1, 'plan', 'user', 'sneak', 1)",
                conversation));
  }

  /**
   * A diagnostic cannot be written with a role, so it cannot be made to reach a model.
   *
   * <p>The same claim {@link #a_kind_that_does_not_project_cannot_be_given_a_role} makes about
   * {@code plan}, asked again about the kind added for the harness to talk about a conversation in
   * — which is the one whose whole purpose is that the model never reads it. V12 adds {@code
   * diagnostic} to {@code entries_kind_is_known} and touches {@code entries_role_matches_kind}
   * deliberately not at all: that constraint's {@code CASE} has no {@code ELSE}, so a kind it does
   * not name yields NULL and the row is required to hold NULL. This is the assertion that the
   * second half really followed from the first rather than needing to be remembered.
   */
  @Test
  void a_diagnostic_cannot_be_given_a_role() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO entries (conversation_id, ordinal, kind, role, content,"
                    + " turn_ordinal)"
                    + " VALUES (?, 1, 'diagnostic', 'system', 'compaction triggered', 1)",
                conversation));
  }

  /**
   * Numbered from one, across the whole conversation and not within a turn. Assigned by the store:
   * {@link EntryStore#append} takes no ordinal.
   */
  @Test
  void entries_are_numbered_from_one_across_a_conversation() {
    assertEquals(
        1,
        entries
            .append(conversation, 1, LoggedEntry.utterance("first", Speaker.person(null)))
            .ordinal());
    assertEquals(
        2, entries.append(conversation, 1, LoggedEntry.answer("second", List.of())).ordinal());
    assertEquals(
        3,
        entries
            .append(conversation, 2, LoggedEntry.utterance("third", Speaker.person(null)))
            .ordinal());

    assertEquals(
        List.of(1, 2, 3),
        entries.forConversation(conversation).stream().map(EntryRecord::ordinal).toList());
  }

  /**
   * A turn and a fold write one conversation's log at once, and neither loses a row.
   *
   * <p>The shape production has: a fold runs on its own thread after a turn ends, and the person's
   * next turn appends its utterance while the fold appends its summary inside {@link
   * EntryStore#fold}'s transaction. Both used to compute the same {@code MAX(ordinal) + 1}; the
   * primary key refused one, and the turn's transcript swallows a failed append at debug — so the
   * utterance vanished from every later prompt ({@code ConversationEndToEndTest} lost the exchange
   * the person was still in, under load). Several writers and a folder here, through the production
   * {@link UnitOfWork} shape: every row lands, each with its own number and none skipped, and a
   * writer's failure fails this test.
   */
  @Test
  void writers_and_a_fold_racing_on_one_conversation_never_lose_a_row() throws Exception {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    TransactionTemplate template =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    UnitOfWork work =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> body) {
            return template.execute(status -> body.get());
          }
        };
    EntryStore shared = new EntryStore(new JdbcTemplate(source), work);
    int writers = 6;
    int each = 40;
    ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<?>> done = new ArrayList<>();
    for (int writer = 0; writer < writers; writer++) {
      done.add(
          pool.submit(
              () -> {
                go.await();
                for (int n = 0; n < each; n++) {
                  shared.append(conversation, 2, LoggedEntry.answer("said " + n, List.of()));
                }
                return null;
              }));
    }
    done.add(
        pool.submit(
            () -> {
              go.await();
              for (int n = 0; n < each; n++) {
                shared.fold(conversation, 0, 1, LoggedEntry.summary("folded " + n));
              }
              return null;
            }));
    go.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS));
    for (Future<?> writer : done) {
      writer.get();
    }

    assertEquals(
        IntStream.rangeClosed(1, (writers + 1) * each).boxed().toList(),
        entries.forConversation(conversation).stream().map(EntryRecord::ordinal).sorted().toList(),
        "every append and every fold's summary landed, numbered without a gap");
  }

  /**
   * A log comes back in the order the conversation held it, and not in the order the rows happened
   * to arrive.
   *
   * <p><b>Arrival order is about to stop being conversation order.</b> A fold is becoming
   * asynchronous, so a {@code summary} entry can be appended after entries from a later turn have
   * already been written — and under {@code ORDER BY ordinal} that summary would come back
   * <em>after</em> the turns it stands for, with two live summaries coming back newest-first. A
   * model reading that is reading a conversation nobody had.
   *
   * <p>{@code turn_ordinal} is what makes the order recoverable: it is the turn that produced an
   * entry, and for a summary it is the last turn it stands for — V11's own column comment. So a
   * summary sorts among the turns it covers whichever moment it was physically written, and {@code
   * ordinal} only ever breaks the tie within one turn.
   *
   * <p>The two summaries here land in the order an asynchronous fold makes ordinary and a
   * synchronous one never could: the one reaching further arrives first.
   */
  @Test
  void a_log_comes_back_in_conversation_order_and_not_in_the_order_rows_arrived() {
    entries.append(conversation, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));
    entries.append(conversation, 2, LoggedEntry.utterance("turn two", Speaker.person(null)));
    entries.append(conversation, 3, LoggedEntry.utterance("turn three", Speaker.person(null)));
    entries.append(conversation, 2, LoggedEntry.summary("through turn two"));
    entries.append(conversation, 1, LoggedEntry.summary("through turn one"));

    assertEquals(
        List.of("turn one", "through turn one", "turn two", "through turn two", "turn three"),
        entries.forConversation(conversation).stream().map(EntryRecord::content).toList(),
        "the log came back in arrival order, so the later-reaching summary landed"
            + " ahead of the earlier one and both landed after the turns they"
            + " stand for");
  }

  /** One conversation's log is not numbered from another's count. */
  @Test
  void two_conversations_are_numbered_separately() {
    String other = conversations.open(PAYMENTS, Budget.of(20)).id();
    entries.append(conversation, 1, LoggedEntry.utterance("mine", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.utterance("mine again", Speaker.person(null)));

    assertEquals(
        1,
        entries.append(other, 1, LoggedEntry.utterance("theirs", Speaker.person(null))).ordinal());
  }

  /**
   * Tool calls survive the round trip, in order and with their arguments untouched.
   *
   * <p>The arguments are the raw JSON string the model emitted and {@code ToolCall} keeps them
   * unparsed on purpose. A column that re-serialised them would give the model back something it
   * did not send, so the assertion is on the string and not on a parse of it.
   */
  @Test
  void tool_calls_survive_the_round_trip() {
    List<ToolCall> asked =
        List.of(
            new ToolCall("c1", "probe_read", "{\"path\":\"a b/c.txt\"}"),
            new ToolCall("c2", "probe_write", ""));

    entries.append(conversation, 1, LoggedEntry.answer("working", asked));

    assertEquals(asked, entries.forConversation(conversation).get(0).toolCalls());
  }

  /**
   * An answer that asked for nothing comes back with an empty list and not a null, and the column
   * holds NULL rather than an empty array — one representation of "asked for nothing" in the table.
   */
  @Test
  void an_answer_that_asked_for_nothing_stores_no_calls() {
    entries.append(conversation, 1, LoggedEntry.answer("just talking", List.of()));

    assertEquals(List.of(), entries.forConversation(conversation).get(0).toolCalls());
    assertNull(
        jdbc.queryForObject(
            "SELECT tool_calls FROM entries WHERE conversation_id = ?",
            String.class,
            conversation));
  }

  /**
   * A fold covers what came before it and never itself.
   *
   * <p>The summary carries the reach as its own turn ordinal — it stands for those turns — so
   * without the ordinal bound in {@link EntryStore#supersede} it would fold itself away the moment
   * it arrived, and the projection would show a seam-less history with the turns behind it missing.
   */
  @Test
  void a_fold_covers_what_came_before_it_and_not_itself() {
    entries.append(conversation, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.answer("answered one", List.of()));
    entries.append(conversation, 2, LoggedEntry.utterance("turn two", Speaker.person(null)));
    EntryRecord summary = entries.append(conversation, 1, LoggedEntry.summary("they talked"));

    assertEquals(2, entries.supersede(conversation, 0, 1, summary.ordinal()));

    // In conversation order the summary sits with the turn it stands for, so
    // it comes third and turn two's utterance comes last.
    List<EntryRecord> log = entries.forConversation(conversation);
    assertEquals(summary.ordinal(), log.get(0).supersededBy());
    assertEquals(summary.ordinal(), log.get(1).supersededBy());
    assertNull(log.get(2).supersededBy(), "the summary folded itself away");
    assertNull(log.get(3).supersededBy(), "turn two is past the reach and was folded anyway");
  }

  /**
   * A fold covers the span since the last fold, and the last fold's summary goes on standing.
   *
   * <p>A fold used to cover everything from turn one, the previous summary included, so each one
   * replaced the one before it and every span of turns was re-summarised through successive lossy
   * passes. It now covers {@code (since, through]}, and the previous summary — which carries {@code
   * since} as its own turn ordinal, because that is the last turn it stands for — falls outside the
   * range.
   *
   * <p><b>Both halves are asserted because they fail differently.</b> A lower bound that was off by
   * one either folds the previous summary away, which is the behaviour this replaces, or leaves the
   * span between the two reaches projecting in full beside a summary of it.
   */
  @Test
  void a_fold_covers_only_the_span_since_the_last_one() {
    entries.append(conversation, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.answer("answered one", List.of()));
    EntryRecord first = entries.append(conversation, 1, LoggedEntry.summary("one so far"));
    assertEquals(
        2,
        entries.supersede(conversation, 0, 1, first.ordinal()),
        "the first fold covers turns 1 to 1, which is what a reach of 0 means");

    entries.append(conversation, 2, LoggedEntry.utterance("turn two", Speaker.person(null)));
    entries.append(conversation, 2, LoggedEntry.answer("answered two", List.of()));
    EntryRecord second = entries.append(conversation, 2, LoggedEntry.summary("two so far"));

    assertEquals(
        2,
        entries.supersede(conversation, 1, 2, second.ordinal()),
        "the second fold should cover turn two's two entries and nothing else");

    List<EntryRecord> log = entries.forConversation(conversation);
    assertEquals(first.ordinal(), log.get(0).supersededBy());
    assertEquals(first.ordinal(), log.get(1).supersededBy());
    assertNull(
        log.get(2).supersededBy(),
        "the second fold covered the first fold's summary, so the model is shown one"
            + " summary of a summary instead of two summaries");
    assertEquals(
        second.ordinal(),
        log.get(3).supersededBy(),
        "turn two's utterance is past the second reach and still projects");
    assertEquals(second.ordinal(), log.get(4).supersededBy());
    assertNull(log.get(5).supersededBy(), "the second summary folded itself away");
  }

  /**
   * A fold taken again over ground an earlier one already covered leaves the earlier one's answer
   * standing.
   *
   * <p>Which summary first covered a row is a fact about when it happened, and a column that moved
   * would lose it. Skipping is all the projection needs, and an entry is either covered or it is
   * not.
   *
   * <p><b>Still reachable now that folds cover disjoint spans.</b> The two ranges overlap whenever
   * a fold's lower bound sits below ground a previous fold covered, which is exactly the state
   * {@code Compaction.foldTheLog} leaves behind when the log refused its summary entry: {@code
   * EntryStore.foldedThrough} answers with the older reach, and the next fold comes back over
   * everything since. Both reaches here are 0 for that reason — this is the widest a fold's range
   * ever gets, and the clause has to hold at the width, not at the ordinary case.
   */
  @Test
  void a_fold_over_ground_already_covered_leaves_the_first_folds_answer_standing() {
    entries.append(conversation, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));
    EntryRecord first = entries.append(conversation, 1, LoggedEntry.summary("one so far"));
    entries.supersede(conversation, 0, 1, first.ordinal());
    entries.append(conversation, 2, LoggedEntry.utterance("turn two", Speaker.person(null)));
    EntryRecord second = entries.append(conversation, 2, LoggedEntry.summary("two so far"));

    assertEquals(
        2,
        entries.supersede(conversation, 0, 2, second.ordinal()),
        "the second fold should newly cover turn two's utterance and the first summary");

    List<EntryRecord> log = entries.forConversation(conversation);
    assertEquals(
        first.ordinal(),
        log.get(0).supersededBy(),
        "turn one's utterance was repointed at the newer fold");
    assertEquals(second.ordinal(), log.get(1).supersededBy());
    assertEquals(second.ordinal(), log.get(2).supersededBy());
    assertNull(log.get(3).supersededBy());
  }

  /**
   * How far a log is folded is the reach of the summary that still stands, and a log no fold has
   * touched is folded through turn zero.
   *
   * <p>The lower bound every fold after the first is taken from. It is read from the log rather
   * than from {@code compactions} because the two can disagree — {@code Compaction.foldTheLog} logs
   * and swallows, so a refused summary entry leaves a {@code compactions} row claiming a reach the
   * log never got — and the log is what the projection answers from.
   */
  @Test
  void how_far_a_log_is_folded_is_the_reach_of_the_summary_that_still_stands() {
    assertEquals(
        0,
        entries.foldedThrough(conversation),
        "a conversation nothing has folded is folded through turn zero");

    entries.append(conversation, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));
    EntryRecord first = entries.append(conversation, 1, LoggedEntry.summary("one so far"));
    entries.supersede(conversation, 0, 1, first.ordinal());
    assertEquals(1, entries.foldedThrough(conversation));

    entries.append(conversation, 2, LoggedEntry.utterance("turn two", Speaker.person(null)));
    EntryRecord second = entries.append(conversation, 2, LoggedEntry.summary("two so far"));
    entries.supersede(conversation, 1, 2, second.ordinal());
    assertEquals(
        2,
        entries.foldedThrough(conversation),
        "both summaries stand and the further-reaching one is the reach");
  }

  /**
   * A summary that was itself folded away is not the reach any more.
   *
   * <p>The shape a log written before folds became incremental has: each summary superseded by the
   * next, exactly one of them standing. Read without {@code superseded_by IS NULL} this would
   * answer with the reach of a summary no model is shown, and the next fold would start above a
   * span nothing covers.
   */
  @Test
  void a_summary_that_was_folded_away_is_not_how_far_the_log_is_folded() {
    entries.append(conversation, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));
    EntryRecord first = entries.append(conversation, 1, LoggedEntry.summary("one so far"));
    entries.append(conversation, 2, LoggedEntry.utterance("turn two", Speaker.person(null)));
    EntryRecord second = entries.append(conversation, 2, LoggedEntry.summary("two so far"));
    entries.supersede(conversation, 0, 2, second.ordinal());

    assertNotNull(
        entries.forConversation(conversation).stream()
            .filter(entry -> entry.ordinal() == first.ordinal())
            .findFirst()
            .orElseThrow()
            .supersededBy(),
        "the fixture did not fold the first summary away, so this proves nothing");
    assertEquals(2, entries.foldedThrough(conversation));
  }

  // --- a fold inside a turn (spec 2026-09-30-fold-at-60-and-80 §2) -------------------------

  /**
   * A fold inside a turn covers the older steps of that turn and whatever earlier turns no fold
   * stands for yet, in one write, and never the turn's opening request, the steps it keeps, a
   * standing in-turn summary, or itself.
   */
  @Test
  void a_fold_inside_a_turn_covers_older_steps_and_earlier_turns_and_keeps_the_request() {
    entries.append(conversation, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.answer("answered one", List.of()));
    EntryRecord asked =
        entries.append(conversation, 2, LoggedEntry.utterance("turn two", Speaker.person(null)));
    EntryRecord notice = entries.append(conversation, 2, LoggedEntry.notice("an inbox item"));
    EntryRecord firstCall =
        entries.append(
            conversation, 2, LoggedEntry.answer("", List.of(new ToolCall("a", "file_read", "{}"))));
    entries.append(conversation, 2, LoggedEntry.toolResult("a", "first"));
    EntryRecord secondCall =
        entries.append(
            conversation, 2, LoggedEntry.answer("", List.of(new ToolCall("b", "file_read", "{}"))));
    EntryRecord lastRemoved =
        entries.append(conversation, 2, LoggedEntry.toolResult("b", "second"));
    EntryRecord kept =
        entries.append(
            conversation, 2, LoggedEntry.answer("", List.of(new ToolCall("c", "file_read", "{}"))));
    EntryRecord keptResult = entries.append(conversation, 2, LoggedEntry.toolResult("c", "third"));

    EntryRecord summary =
        entries.foldWithinATurn(
            conversation,
            0,
            2,
            firstCall.ordinal(),
            lastRemoved.ordinal(),
            LoggedEntry.turnSummary("read two files"));

    assertEquals(EntryKind.TURN_SUMMARY, summary.kind());
    assertEquals(2, summary.turnOrdinal(), "an in-turn summary belongs to its turn");
    Map<Integer, Integer> coveredBy = new HashMap<>();
    entries
        .forConversation(conversation)
        .forEach(entry -> coveredBy.put(entry.ordinal(), entry.supersededBy()));
    assertEquals(summary.ordinal(), coveredBy.get(1), "turn one is older than the turn");
    assertEquals(summary.ordinal(), coveredBy.get(2));
    assertNull(coveredBy.get(asked.ordinal()), "the opening request is kept word for word");
    assertNull(coveredBy.get(notice.ordinal()), "what the turn opened with is kept");
    assertEquals(summary.ordinal(), coveredBy.get(firstCall.ordinal()));
    assertEquals(summary.ordinal(), coveredBy.get(secondCall.ordinal()));
    assertEquals(summary.ordinal(), coveredBy.get(lastRemoved.ordinal()));
    assertNull(coveredBy.get(kept.ordinal()), "a kept step was folded");
    assertNull(coveredBy.get(keptResult.ordinal()));
    assertNull(coveredBy.get(summary.ordinal()), "the summary folded itself away");
    assertEquals(
        1,
        entries.foldedThrough(conversation),
        "a fold inside turn two stands for every turn before it");
  }

  /**
   * A second fold inside the same turn leaves the first one's summary standing: each span of steps
   * is summarised once, as each span of turns is between turns.
   */
  @Test
  void a_second_fold_inside_a_turn_leaves_the_first_in_turn_summary_standing() {
    entries.append(conversation, 1, LoggedEntry.utterance("the task", Speaker.person(null)));
    EntryRecord first =
        entries.append(
            conversation, 1, LoggedEntry.answer("", List.of(new ToolCall("a", "file_read", "{}"))));
    EntryRecord firstResult = entries.append(conversation, 1, LoggedEntry.toolResult("a", "one"));
    EntryRecord earlier =
        entries.foldWithinATurn(
            conversation,
            0,
            1,
            first.ordinal(),
            firstResult.ordinal(),
            LoggedEntry.turnSummary("one file"));
    EntryRecord second =
        entries.append(
            conversation, 1, LoggedEntry.answer("", List.of(new ToolCall("b", "file_read", "{}"))));
    EntryRecord secondResult = entries.append(conversation, 1, LoggedEntry.toolResult("b", "two"));

    EntryRecord later =
        entries.foldWithinATurn(
            conversation,
            0,
            1,
            first.ordinal(),
            secondResult.ordinal(),
            LoggedEntry.turnSummary("another file"));

    Map<Integer, Integer> coveredBy = new HashMap<>();
    entries
        .forConversation(conversation)
        .forEach(entry -> coveredBy.put(entry.ordinal(), entry.supersededBy()));
    assertEquals(
        earlier.ordinal(),
        coveredBy.get(first.ordinal()),
        "a row the first fold covered was repointed at the second");
    assertNull(coveredBy.get(earlier.ordinal()), "the first in-turn summary was folded away");
    assertEquals(later.ordinal(), coveredBy.get(second.ordinal()));
    assertEquals(later.ordinal(), coveredBy.get(secondResult.ordinal()));
    assertEquals(
        0,
        entries.foldedThrough(conversation),
        "a fold inside the first turn stands for no turn before it");
  }

  /**
   * A fold between turns after a fold inside one covers the in-turn summary with the rest of its
   * turn, and the reach is the between-turn summary's again.
   */
  @Test
  void a_fold_between_turns_covers_an_in_turn_summary_with_its_turn() {
    entries.append(conversation, 1, LoggedEntry.utterance("the task", Speaker.person(null)));
    EntryRecord call =
        entries.append(
            conversation, 1, LoggedEntry.answer("", List.of(new ToolCall("a", "file_read", "{}"))));
    EntryRecord result = entries.append(conversation, 1, LoggedEntry.toolResult("a", "one"));
    EntryRecord inTurn =
        entries.foldWithinATurn(
            conversation,
            0,
            1,
            call.ordinal(),
            result.ordinal(),
            LoggedEntry.turnSummary("one file"));
    entries.append(conversation, 1, LoggedEntry.answer("done", List.of()));
    entries.append(conversation, 2, LoggedEntry.utterance("and then", Speaker.person(null)));

    EntryRecord between =
        entries.fold(
            conversation,
            entries.foldedThrough(conversation),
            1,
            LoggedEntry.summary("the task was done"));

    assertEquals(
        between.ordinal(),
        entries.forConversation(conversation).stream()
            .filter(entry -> entry.ordinal() == inTurn.ordinal())
            .findFirst()
            .orElseThrow()
            .supersededBy());
    assertEquals(1, entries.foldedThrough(conversation));
  }

  /**
   * A fold that pointed at an entry nobody wrote would be a seam nothing stands behind. {@code
   * entries_are_superseded_by_an_entry} refuses it, and it is written straight at the table because
   * the store gives a caller no way to.
   */
  @Test
  void an_entry_cannot_be_superseded_by_one_that_does_not_exist() {
    entries.append(conversation, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE entries SET superseded_by = 99 WHERE conversation_id = ?", conversation));
  }

  /**
   * A conversation nobody has spoken into has an empty log, rather than being a missing
   * conversation. {@code TurnStore.forConversation}'s choice for its reason.
   */
  @Test
  void a_conversation_nobody_has_spoken_into_has_an_empty_log() {
    assertEquals(List.of(), entries.forConversation(conversation));
  }

  /**
   * An id nothing opened is not a log, and the key says so rather than the row landing somewhere
   * nobody can find it.
   */
  @Test
  void an_entry_in_a_conversation_nobody_opened_is_refused() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            entries.append("cnv_nothing", 1, LoggedEntry.utterance("hello", Speaker.person(null))));
  }

  /**
   * A blank utterance is what an omitted field arrives as, and a log holding one reads as a turn
   * somebody said nothing in. The store cannot be made to write one through its factories, so this
   * goes at the table.
   */
  @Test
  void an_utterance_that_says_nothing_is_refused() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO entries (conversation_id, ordinal, kind, role, content,"
                    + " turn_ordinal) VALUES (?, 1, 'utterance', 'user', '', 1)",
                conversation));
  }

  /**
   * A tool result with no call to answer is one a model cannot place; an id on anything else is a
   * correlation that kind never made. One constraint refuses both, so both are asked.
   */
  @Test
  void a_tool_result_is_exactly_what_carries_a_call_id() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO entries (conversation_id, ordinal, kind, role, content,"
                    + " turn_ordinal) VALUES (?, 1, 'tool_result', 'tool', 'read it', 1)",
                conversation));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO entries (conversation_id, ordinal, kind, role, content,"
                    + " tool_call_id, turn_ordinal)"
                    + " VALUES (?, 1, 'utterance', 'user', 'hello', 'c1', 1)",
                conversation));
  }

  // --- when it happened, and how long it took --------------------------------------

  /**
   * Every entry says when it was written, from a clock the caller chose.
   *
   * <p>The clock is injected for {@code ConversationStore}'s reason: the value is on {@link
   * EntryRecord}, so a test that asserts on it has to be able to choose it, and {@code DEFAULT
   * now()} would be a clock nothing here can pull on. Two entries a second apart prove the store
   * reads it per append rather than once.
   */
  @Test
  void an_entry_says_when_it_was_recorded() {
    EntryStore timed = new EntryStore(jdbc, ticking(THEN, Duration.ofSeconds(1)));

    EntryRecord first =
        timed.append(
            conversation, 1, LoggedEntry.utterance("what broke it?", Speaker.person(null)));
    EntryRecord second =
        timed.append(
            conversation,
            1,
            LoggedEntry.answer("the migration", List.of(new ToolCall("c1", "file_read", "{}"))));

    assertEquals(THEN, first.recordedAt());
    assertEquals(THEN.plusSeconds(1), second.recordedAt());
    assertEquals(
        THEN,
        timed.forConversation(conversation).get(0).recordedAt(),
        "the stamp did not survive the round trip");
  }

  /**
   * A duration is measured where the operation happens and carried here; it is never the gap
   * between two entries.
   *
   * <p>The gap between the utterance and the answer in this fixture is a whole second of the
   * injected clock, and the answer's own measurement is 6 700 milliseconds. <b>Those two numbers
   * disagree on purpose</b>: an entry is written when something completes, so the interval between
   * two of them holds everything that happened in between and is not any one operation's duration.
   * {@code V16__entry_timing.sql} argues it, and this is the shape that would let somebody subtract
   * and be wrong.
   */
  @Test
  void a_duration_is_measured_and_never_the_gap_between_two_entries() {
    EntryStore timed = new EntryStore(jdbc, ticking(THEN, Duration.ofSeconds(1)));

    timed.append(conversation, 1, LoggedEntry.utterance("what broke it?", Speaker.person(null)));
    EntryRecord answer =
        timed.append(
            conversation,
            1,
            LoggedEntry.answer("the migration", List.of(new ToolCall("c1", "file_read", "{}")))
                .took(Duration.ofMillis(6700)));

    assertEquals(6700, answer.tookMillis());
    assertEquals(
        Duration.ofSeconds(1),
        Duration.between(THEN, answer.recordedAt()),
        "the fixture no longer has a gap that disagrees with the measurement");
  }

  /**
   * An entry nobody measured says so, rather than saying zero. The absence is the honest answer for
   * an utterance — a person spoke, and this server did not do it.
   */
  @Test
  void an_entry_that_records_no_operation_carries_no_duration() {
    EntryRecord written =
        entries.append(
            conversation, 1, LoggedEntry.utterance("what broke it?", Speaker.person(null)));

    assertNull(written.tookMillis());
  }

  /**
   * An operation that took under a millisecond is a measurement of zero and not an absence.
   *
   * <p>The opposite of {@code turns_prompt_tokens_are_a_measurement} one table over, and the
   * contrast is the point: a prompt of no tokens cannot happen, and a tool that refuses its
   * arguments before doing anything really does take no measurable time.
   */
  @Test
  void an_operation_faster_than_the_clock_is_still_a_measurement() {
    EntryRecord written =
        entries.append(
            conversation, 1, LoggedEntry.toolResult("c1", "it says here").took(Duration.ZERO));

    assertEquals(0, written.tookMillis());
  }

  /**
   * A length of time is never negative. Nothing in Java can produce one, so this goes straight at
   * the table — V2's argument about a second writer.
   */
  @Test
  void the_table_refuses_a_duration_that_runs_backwards() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO entries (conversation_id, ordinal, kind, role, content,"
                    + " tool_call_id, turn_ordinal, took_ms)"
                    + " VALUES (?, 1, 'tool_result', 'tool', 'it says here', 'c1', 1, -1)",
                conversation));
  }

  /**
   * Only the two kinds that record an operation may carry a duration.
   *
   * <p>An utterance is a person speaking, so a number on it could only be the time spent waiting
   * for a human — not an operation, and not this system's to charge. {@code
   * entries_only_a_completed_operation_is_timed} is what refuses it, and the store's factories give
   * a caller no way to try.
   */
  @Test
  void the_table_refuses_a_duration_on_a_kind_that_records_no_operation() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO entries (conversation_id, ordinal, kind, role, content,"
                    + " turn_ordinal, took_ms)"
                    + " VALUES (?, 1, 'utterance', 'user', 'what broke it?', 1, 12)",
                conversation));
  }

  /**
   * A turn's span is read off its own entries, and a summary is not one of them.
   *
   * <p><b>The reason {@code turns} got no timestamp of its own</b>, and the one trap in deriving
   * the span: a {@code summary} carries <em>the reach</em> as its {@code turn_ordinal} — V11's
   * column comment says so — and it is appended long after that turn ended, on a fold's own thread.
   * A reader that took {@code MAX(recorded_at)} over a turn ordinal without excluding it would
   * report the turn as having run until the fold, which here is a minute later than it really
   * ended.
   */
  @Test
  void a_turns_span_is_its_own_entries_and_never_the_fold_that_covered_them() {
    EntryStore timed = new EntryStore(jdbc, ticking(THEN, Duration.ofMinutes(1)));

    timed.append(conversation, 1, LoggedEntry.utterance("what broke it?", Speaker.person(null)));
    timed.append(conversation, 1, LoggedEntry.answer("the migration did", List.of()));
    // The fold, two minutes on, standing for turn 1 and carrying its ordinal.
    timed.append(conversation, 1, LoggedEntry.summary("they talked about the deploy"));

    List<EntryRecord> ofTurnOne =
        timed.forConversation(conversation).stream()
            .filter(entry -> entry.turnOrdinal() == 1)
            .filter(entry -> entry.kind() != EntryKind.SUMMARY)
            .toList();
    assertEquals(THEN, ofTurnOne.get(0).recordedAt());
    assertEquals(
        THEN.plusSeconds(60),
        ofTurnOne.get(ofTurnOne.size() - 1).recordedAt(),
        "the turn's end is its last entry and not the fold that came after it");
  }

  /**
   * A clock that starts at {@code from} and moves on by {@code step} every time it is read.
   * Deterministic, so a duration in a test is a number somebody chose rather than however long the
   * suite took.
   */
  private static Supplier<Instant> ticking(Instant from, Duration step) {
    AtomicReference<Instant> hand = new AtomicReference<>(from);
    return () -> hand.getAndUpdate(at -> at.plus(step));
  }

  // --- the handle a reference is redeemed with ------------------------------------

  /**
   * A tool result is given a handle when it is written, and nothing else is.
   *
   * <h2>Why only a tool result</h2>
   *
   * <p>A handle is an <em>address a model may ask for content at</em>, and the only content a model
   * is ever shown a reference to instead of itself is a tool result — {@code
   * Compaction.whatWasSaidAndWhatCameBack} substitutes those and nothing else. Everything else that
   * projects is projected whole, so a handle on it would address a row the model can already read;
   * and the kinds that do <em>not</em> project — {@code attempt_failed}, {@code runtime_note},
   * {@code plan}, {@code diagnostic} — are deliberately invisible, so a handle on one of those
   * would be a second door into exactly what V11's projection rule exists to keep shut.
   *
   * <p>Asserted for every kind at once, so a ninth kind cannot quietly acquire one.
   */
  @Test
  void a_tool_result_is_exactly_what_carries_a_handle() {
    for (EntryKind kind : EntryKind.values()) {
      EntryRecord written = entries.append(conversation, 1, anEntryOf(kind));
      assertEquals(
          kind == EntryKind.TOOL_RESULT,
          written.handle() != null,
          kind + " carries a handle it should not, or lacks one it should have");
    }
  }

  /**
   * Two results written in the same conversation are addressed separately, which is the whole of
   * what a handle is for.
   */
  @Test
  void every_tool_result_gets_a_handle_of_its_own() {
    EntryRecord first = entries.append(conversation, 1, LoggedEntry.toolResult("c1", "one"));
    EntryRecord second = entries.append(conversation, 1, LoggedEntry.toolResult("c2", "two"));
    assertNotEquals(
        first.handle(),
        second.handle(),
        "two results share one address, so redeeming one answers with the other");
  }

  /**
   * A handle cannot be forged into the table, so nothing can mint a second row at an address that
   * is already taken.
   *
   * <p>The unique index is global rather than per conversation, which is the stronger claim and the
   * one a UUID already makes: an address means one row in this database, so the scoping check
   * {@code EntryStore.redeem} applies is an authorisation and never a disambiguation.
   */
  @Test
  void a_handle_addresses_one_row_in_the_whole_table() {
    EntryRecord written = entries.append(conversation, 1, LoggedEntry.toolResult("c1", "one"));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO entries (conversation_id, ordinal, kind, role, content,"
                    + " tool_call_id, turn_ordinal, handle)"
                    + " VALUES (?, 99, 'tool_result', 'tool', 'two', 'c2', 1, ?)",
                conversation,
                written.handle()));
  }

  /** What a handle is redeemed for: the content of the row it addresses. */
  @Test
  void a_handle_answers_with_the_content_of_the_result_it_addresses() {
    EntryRecord written =
        entries.append(conversation, 1, LoggedEntry.toolResult("c1", "the file says this"));
    assertEquals(
        "the file says this",
        entries.redeem(conversation, written.handle()).orElseThrow().content());
  }

  /**
   * A result a fold covered is still redeemable, and that is the property this whole mechanism
   * exists to have.
   *
   * <p>A summary is a view and the log is complete, so the detail behind a seam stays addressable:
   * a model reading a summary of turns 1 to 40 can still ask what a file said in turn 12. The read
   * that builds a prompt filters {@code superseded_by IS NULL} — {@code thatProjectFor} — and this
   * one must not.
   */
  @Test
  void a_result_a_fold_covered_is_still_redeemable() {
    EntryRecord result =
        entries.append(conversation, 1, LoggedEntry.toolResult("c1", "the file says this"));
    int summary =
        entries.append(conversation, 1, LoggedEntry.summary("they read a file")).ordinal();
    entries.supersede(conversation, 0, 1, summary);

    assertTrue(
        entries.thatProjectFor(conversation).stream()
            .noneMatch(entry -> entry.ordinal() == result.ordinal()),
        "this test says nothing unless the fold really covered the result");
    assertEquals(
        "the file says this",
        entries.redeem(conversation, result.handle()).orElseThrow().content(),
        "a fold made a result unreachable, so compaction is still destructive");
  }

  /**
   * A handle from another conversation is refused.
   *
   * <p>Unguessable is not the same as unauthorised. A UUID cannot be constructed by a model holding
   * another one, which is what makes the attack inexpressible rather than merely refused — and this
   * is the second half of the doubled enforcement this project applies everywhere, because a handle
   * that leaked by any route at all must still not read across a boundary.
   */
  @Test
  void a_handle_belonging_to_another_conversation_is_refused() {
    EntryRecord mine = entries.append(conversation, 1, LoggedEntry.toolResult("c1", "mine"));
    String other = conversations.open(PAYMENTS, Budget.of(20)).id();

    assertTrue(
        entries.redeem(other, mine.handle()).isEmpty(),
        "a handle read a result out of a conversation it does not belong to");
    assertTrue(
        entries.redeem(conversation, java.util.UUID.randomUUID()).isEmpty(),
        "a handle nothing was written under answered with something");
  }

  // --- what is still addressable once a fold has taken the reference away --------------

  /**
   * The stored results a fold covered, with the tool that ran, the size and the handle — the three
   * facts a reference line carries, for the lines a fold took away.
   *
   * <p>Newest first, because the seam a model has just read is the nearest one and what is behind
   * it is what it was reading a moment ago. The name comes off the {@code answer} that declared the
   * call, which is the join this read does in SQL so that no covered row's {@code content} has to
   * travel to report its length.
   */
  @Test
  void the_stored_results_a_fold_covered_are_listed_newest_first() {
    entries.append(
        conversation,
        1,
        LoggedEntry.answer("looking", List.of(new ToolCall("c1", "file_read", "{}"))));
    EntryRecord first =
        entries.append(conversation, 1, LoggedEntry.toolResult("c1", "twelve chars"));
    entries.append(
        conversation,
        2,
        LoggedEntry.answer("looking again", List.of(new ToolCall("c2", "file_grep", "{}"))));
    EntryRecord second = entries.append(conversation, 2, LoggedEntry.toolResult("c2", "four"));
    fold(2);

    StoredResults listed = entries.storedResultsBehindASeam(conversation, 0, 10);

    assertEquals(2, listed.total());
    assertEquals(
        List.of(
            new StoredResults.Result("file_grep", 4, null, second.handle()),
            new StoredResults.Result("file_read", 12, null, first.handle())),
        listed.listed());
  }

  /**
   * A result no fold has covered is not listed, and that is the whole scope.
   *
   * <p>Such a result is already in front of the model as its own reference line — {@code
   * Compaction.REFERENCE} — so listing it would spend a bounded page on addresses the model is
   * holding anyway.
   */
  @Test
  void a_stored_result_no_fold_has_covered_is_not_listed() {
    entries.append(
        conversation,
        1,
        LoggedEntry.answer("looking", List.of(new ToolCall("c1", "file_read", "{}"))));
    EntryRecord folded =
        entries.append(conversation, 1, LoggedEntry.toolResult("c1", "behind the seam"));
    fold(1);
    entries.append(
        conversation,
        2,
        LoggedEntry.answer("looking again", List.of(new ToolCall("c2", "file_grep", "{}"))));
    entries.append(conversation, 2, LoggedEntry.toolResult("c2", "still in the prompt"));

    StoredResults listed = entries.storedResultsBehindASeam(conversation, 0, 10);

    assertEquals(1, listed.total(), "a result still in the prompt was listed as well");
    assertEquals(folded.handle(), listed.listed().get(0).handle());
  }

  /**
   * A page is what was asked for and the total is what there is, and the two are separate numbers.
   *
   * <p>A short page is either the end of the list or a caller that has paged past it, and a model
   * corrects those differently — {@code file_read} splits the same pair into a window and {@code
   * Span.totalLines} for the same reason.
   */
  @Test
  void a_page_is_bounded_and_the_total_is_the_conversations_own_number() {
    for (int turn = 1; turn <= 3; turn++) {
      entries.append(
          conversation,
          turn,
          LoggedEntry.answer("looking", List.of(new ToolCall("c" + turn, "file_read", "{}"))));
      entries.append(conversation, turn, LoggedEntry.toolResult("c" + turn, "read " + turn));
    }
    fold(3);

    assertEquals(2, entries.storedResultsBehindASeam(conversation, 0, 2).listed().size());
    assertEquals(3, entries.storedResultsBehindASeam(conversation, 0, 2).total());
    assertEquals(1, entries.storedResultsBehindASeam(conversation, 2, 2).listed().size());
    assertEquals(3, entries.storedResultsBehindASeam(conversation, 2, 2).total());
    assertEquals(
        List.of(),
        entries.storedResultsBehindASeam(conversation, 9, 2).listed(),
        "a page past the end invented rows");
    assertEquals(
        3,
        entries.storedResultsBehindASeam(conversation, 9, 2).total(),
        "a page past the end forgot how many there are");
  }

  /**
   * One conversation's listing is one conversation's, named in the WHERE exactly as {@code
   * redeem}'s authorisation clause is.
   */
  @Test
  void a_listing_is_one_conversations_and_no_other() {
    entries.append(
        conversation,
        1,
        LoggedEntry.answer("looking", List.of(new ToolCall("c1", "file_read", "{}"))));
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", "mine"));
    fold(1);
    String other = conversations.open(PAYMENTS, Budget.of(20)).id();

    assertEquals(
        StoredResults.NONE,
        entries.storedResultsBehindASeam(other, 0, 10),
        "one conversation listed another's stored results");
  }

  /**
   * A result written before {@code V13__entry_handles.sql} is not listed.
   *
   * <p>There is nothing to redeem, so a line for it would cost the model context and be able to
   * return nothing — which is the same argument {@code Compaction.referenced} makes when it leaves
   * such a result inline instead of referencing it.
   */
  @Test
  void a_result_with_no_handle_is_not_listed() {
    entries.append(
        conversation,
        1,
        LoggedEntry.answer("looking", List.of(new ToolCall("c1", "file_read", "{}"))));
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", "written long ago"));
    jdbc.update("UPDATE entries SET handle = NULL WHERE conversation_id = ?", conversation);
    fold(1);

    assertEquals(StoredResults.NONE, entries.storedResultsBehindASeam(conversation, 0, 10));
  }

  /**
   * A result whose call nothing in the log declares is still listed, without a name.
   *
   * <p>No run this server writes produces it — the assistant message is recorded before the results
   * it asked for — but a listing that dropped the row would withhold the address as well as the
   * name, and the address is the thing that cannot be recovered any other way.
   */
  @Test
  void a_result_nothing_declared_is_listed_with_no_name_rather_than_dropped() {
    EntryRecord orphan =
        entries.append(conversation, 1, LoggedEntry.toolResult("c1", "nothing asked for this"));
    fold(1);

    StoredResults listed = entries.storedResultsBehindASeam(conversation, 0, 10);

    assertEquals(1, listed.total());
    assertNull(listed.listed().get(0).tool());
    assertEquals(orphan.handle(), listed.listed().get(0).handle());
  }

  /**
   * A page the caller sized wrongly is the caller's bug and not a model's: {@code result_list}
   * refuses a bad argument as prose before it reaches here.
   */
  @Test
  void a_page_that_could_not_hold_anything_is_the_callers_bug() {
    assertThrows(
        IllegalArgumentException.class, () -> entries.storedResultsBehindASeam(conversation, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> entries.storedResultsBehindASeam(conversation, -1, 10));
    assertThrows(ValidationException.class, () -> entries.storedResultsBehindASeam(" ", 0, 10));
  }

  // --- one page of the log, and one page of what a model is shown ----------------

  /**
   * A page of the log carries the total, and the total is about the conversation rather than about
   * the page.
   *
   * <p>{@code StoredResults}' argument, asked of the other listing: a page that came back short is
   * either the end of the list or a caller that paged past it, and those are corrected differently.
   * The count is asked separately for exactly the case a window function cannot answer — a page
   * past the end has no rows to carry it.
   */
  @Test
  void a_page_of_the_log_says_how_many_entries_there_are_and_not_how_many_it_returned() {
    for (int at = 1; at <= 5; at++) {
      entries.append(conversation, at, LoggedEntry.utterance("thing " + at, Speaker.person(null)));
    }

    EntryPage first = entries.pageOfLog(conversation, 0, 2);
    assertEquals(2, first.listed().size());
    assertEquals(5, first.total());
    assertEquals(1, first.listed().get(0).ordinal());

    EntryPage past = entries.pageOfLog(conversation, 90, 2);
    assertEquals(List.of(), past.listed());
    assertEquals(
        5,
        past.total(),
        "a page past the end is where the total matters most: it is what tells the"
            + " caller to page back rather than that the log is empty");
  }

  /**
   * A page of the log holds everything, and a page of the projection holds what a model is shown.
   *
   * <p>The two readings this store already has, asked one page at a time. A fold covers the entries
   * it stands for and a {@code diagnostic} carries no role, so both are in the log and neither is
   * in the projection — which is the whole of the distinction the two pages exist to expose.
   */
  @Test
  void a_page_of_the_projection_drops_what_a_fold_covered_and_what_carries_no_role() {
    entries.append(conversation, 1, LoggedEntry.utterance("what broke it?", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.answer("the migration", List.of()));
    entries.append(conversation, 1, LoggedEntry.diagnostic("compaction triggered"));
    fold(1);
    entries.append(conversation, 2, LoggedEntry.utterance("and now?", Speaker.person(null)));

    EntryPage log = entries.pageOfLog(conversation, 0, 50);
    assertEquals(5, log.total(), "the log holds the folded turn, the diagnostic and the seam");

    EntryPage shown = entries.pageOfProjection(conversation, 0, 50);
    assertEquals(2, shown.total(), "the summary and the turn after it, and nothing else");
    assertEquals(
        List.of(EntryKind.SUMMARY, EntryKind.UTTERANCE),
        shown.listed().stream().map(EntryPage.Row::kind).toList());
  }

  /**
   * A tool result longer than the excerpt comes back cut, and says how long it really is.
   *
   * <p>The bound this page has that {@link EntryStore#forConversation} does not: a trajectory is
   * bounded in rows <em>and</em> in text, because one {@code file_read} result is a hundred
   * thousand characters and a page of fifty of them is not a page. The length is the database's,
   * computed where the text already is, exactly as {@code storedResultsBehindASeam} computes a
   * stored result's size.
   */
  @Test
  void a_tool_result_longer_than_the_excerpt_is_cut_and_still_says_its_whole_length() {
    String long_ = "x".repeat(EntryStore.MOST_CHARACTERS_PER_ENTRY + 500);
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", long_));
    entries.append(conversation, 1, LoggedEntry.answer("short", List.of()));

    List<EntryPage.Row> rows = entries.pageOfLog(conversation, 0, 10).listed();
    assertEquals(EntryStore.MOST_CHARACTERS_PER_ENTRY, rows.get(0).excerpt().length());
    assertEquals(long_.length(), rows.get(0).length());
    assertTrue(rows.get(0).cut(), "an entry cut at the excerpt says so");

    assertEquals("short", rows.get(1).excerpt());
    assertEquals(5, rows.get(1).length());
    assertFalse(rows.get(1).cut(), "an entry that fitted was not cut");
  }

  @Test
  void long_transcript_text_is_complete_in_forward_tail_earlier_and_projection_pages() {
    String answer =
        "# Practical steps\n\n"
            + "Long explanation.\n".repeat(6_000)
            + "\n**A2A (Python)**\n\n```python\nprint('complete')\n```\nFinal line 🎉";
    String utterance = "Detailed request\n".repeat(700);
    String summary = "Conversation summary\n".repeat(600);
    entries.append(conversation, 1, LoggedEntry.utterance(utterance, Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.answer(answer, List.of()));
    entries.append(conversation, 1, LoggedEntry.summary(summary));
    List<String> texts = List.of(utterance, answer, summary);

    List<EntryPage> pages =
        List.of(
            entries.pageOfLog(conversation, 0, 10),
            entries.pageOfLogBefore(
                conversation, EntryStore.FROM_THE_END, EntryStore.EVERY_KIND, true, 0, 10),
            entries.pageOfLogBefore(conversation, 4, EntryStore.EVERY_KIND, true, 0, 10),
            entries.pageOfProjection(conversation, 0, 10));
    for (EntryPage page : pages) {
      assertEquals(3, page.listed().size());
      for (EntryPage.Row row : page.listed()) {
        String whole = texts.get(row.ordinal() - 1);
        assertEquals(whole, row.excerpt());
        assertEquals(whole.codePointCount(0, whole.length()), row.length());
        assertFalse(row.cut(), "durable transcript text must retain its ending");
      }
    }
  }

  /**
   * A page carries the rest of what a row says, so that a reader of one never has to go back to the
   * log for the fact that made it legible.
   *
   * <p>Timing above all — {@code recorded_at} and {@code took_ms} are what V16 added and what
   * nothing could read back — and the supersession, which is how a page of the log shows that a
   * fold happened at all.
   */
  @Test
  void a_page_carries_the_timing_the_handle_and_the_fold_that_covered_a_row() {
    EntryStore stamped = new EntryStore(jdbc, ticking(THEN, Duration.ofSeconds(30)));
    stamped.append(conversation, 1, LoggedEntry.utterance("go on", Speaker.person(null)));
    stamped.append(
        conversation,
        1,
        LoggedEntry.answer("reading", List.of(new ToolCall("c1", "file_read", "{}"))));
    stamped.append(conversation, 1, LoggedEntry.toolResult("c1", "it says here"));
    int summary = stamped.append(conversation, 1, LoggedEntry.summary("they read")).ordinal();
    stamped.supersede(conversation, 0, 1, summary);

    List<EntryPage.Row> rows = stamped.pageOfLog(conversation, 0, 10).listed();
    assertEquals(THEN, rows.get(0).recordedAt());
    assertEquals(summary, rows.get(0).supersededBy());
    assertEquals(
        List.of("file_read"), rows.get(1).toolCalls().stream().map(EntryPage.Asked::name).toList());
    assertEquals("c1", rows.get(2).toolCallId());
    assertNotNull(rows.get(2).handle(), "a tool result's handle is on the page");
    assertNull(rows.get(3).supersededBy(), "a fold does not cover its own summary");
  }

  /**
   * A call's arguments are bounded on a page too, and the row says how long they really were.
   *
   * <p><b>The second unbounded dimension, and the one that is easy to miss.</b> {@code
   * StoredResults.Result} leaves a stored result's arguments out entirely and says why: {@code
   * file_write} sends a whole file as an argument, so an argument-carrying listing is unbounded in
   * exactly the dimension the listing exists to bound. A page cannot leave them out — what a tool
   * was asked for is most of what a trajectory is — so it cuts them where it cuts the content, and
   * in the database, so the file never crosses.
   */
  @Test
  void the_arguments_of_a_call_are_cut_where_the_content_is_and_say_their_length() {
    String wholeFile = "y".repeat(EntryStore.MOST_CHARACTERS_PER_ENTRY + 200);
    String arguments = "{\"path\":\"/srv/x\",\"content\":\"" + wholeFile + "\"}";
    entries.append(
        conversation,
        1,
        LoggedEntry.answer(
            "writing",
            List.of(
                new ToolCall("c1", "file_write", arguments),
                new ToolCall("c2", "file_stat", "{}"))));

    List<EntryPage.Asked> asked =
        entries.pageOfLog(conversation, 0, 10).listed().get(0).toolCalls();
    assertEquals(
        List.of("c1", "c2"),
        asked.stream().map(EntryPage.Asked::id).toList(),
        "in the order the model asked, which jsonb_agg only keeps if it is told to");
    assertEquals("file_write", asked.get(0).name());
    assertEquals(EntryStore.MOST_CHARACTERS_PER_ENTRY, asked.get(0).arguments().length());
    assertEquals(arguments.length(), asked.get(0).length());
    assertTrue(asked.get(0).cut());
    assertEquals("{}", asked.get(1).arguments());
    assertFalse(asked.get(1).cut());
  }

  /**
   * The three V62 fields, read where a reader looks for them: a call's salient argument beside its
   * cut ones, the child a delegating call opened, and a result's outcome on its row — none of it on
   * the row the call itself came from.
   */
  @Test
  void a_page_carries_each_result_s_outcome_each_call_s_salient_and_the_child_it_opened() {
    String wholeFile = "y".repeat(EntryStore.MOST_CHARACTERS_PER_ENTRY + 200);
    entries.append(
        conversation,
        1,
        LoggedEntry.answer(
                "two things",
                List.of(
                    new ToolCall(
                        "c1",
                        "file_edit",
                        "{\"path\":\"/srv/x\",\"content\":\"" + wholeFile + "\"}"),
                    new ToolCall("c2", "agent_run", "{\"agent\":\"helper\"}")))
            .naming(Map.of("c1", "/srv/x", "c2", "helper")));
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", "edited").told("ok"));
    String child =
        conversations
            .log(Origin.DELEGATION, PAYMENTS, "helper", conversation, null, null, "c2")
            .id();

    List<EntryPage.Row> rows = entries.pageOfLog(conversation, 0, 10).listed();
    EntryPage.Asked edit = rows.get(0).toolCalls().get(0);
    EntryPage.Asked delegation = rows.get(0).toolCalls().get(1);

    assertTrue(edit.cut(), "the arguments are still cut");
    assertEquals("/srv/x", edit.salient(), "but the salient argument is whole");
    assertNull(edit.opened());
    assertEquals(new EntryPage.Opened(child, "helper"), delegation.opened());
    assertEquals("ok", rows.get(1).outcome());
    assertNull(rows.get(0).outcome());
  }

  @Test
  void a_tool_result_keeps_the_outcome_it_was_told_and_an_answer_its_salients() {
    entries.append(
        conversation,
        1,
        LoggedEntry.answer(
                "reading", List.of(new ToolCall("c1", "file_read", "{\"path\":\"/srv/a\"}")))
            .naming(Map.of("c1", "/srv/a")));
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", "no such file").told("refused"));

    assertEquals(
        "refused",
        jdbc.queryForObject(
            "SELECT outcome FROM entries WHERE conversation_id = ? AND kind = 'tool_result'",
            String.class,
            conversation));
    assertEquals(
        "{\"c1\": \"/srv/a\"}",
        jdbc.queryForObject(
            "SELECT salients::text FROM entries WHERE conversation_id = ? AND kind = 'answer'",
            String.class,
            conversation));
  }

  @Test
  void a_row_told_nothing_stores_nothing_rather_than_an_empty_word() {
    entries.append(conversation, 1, LoggedEntry.answer("done", List.of()));
    entries.append(conversation, 1, LoggedEntry.toolResult("c9", "late"));

    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE conversation_id = ?"
                + " AND (outcome IS NOT NULL OR salients IS NOT NULL)",
            Integer.class,
            conversation));
  }

  @Test
  void only_a_tool_result_takes_an_outcome_and_only_an_answer_takes_salients() {
    assertThrows(
        IllegalArgumentException.class, () -> LoggedEntry.answer("x", List.of()).told("ok"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LoggedEntry.toolResult("c1", "x").naming(Map.of("c1", "a")));
  }

  /**
   * An entry whose text reaches past U+FFFF comes back as a page rather than as a 500.
   *
   * <p><b>Two units met, and neither was wrong on its own.</b> {@code PAGE_OF} sends {@code
   * left(content, ?)} and {@code length(content)}, and PostgreSQL counts both in CODE POINTS.
   * {@link String#length()} counts UTF-16 CODE UNITS, which is two for every character above
   * U+FFFF. So a row carrying a single emoji reached {@link EntryPage.Row} claiming an excerpt
   * longer than the entry it came out of, and the guard that exists to catch a malformed page threw
   * on a well-formed one.
   *
   * <p>Observed as {@code GET /v1/conversations/&#123;id&#125;/trajectory} answering 500 with "an
   * excerpt of 3142 characters cannot come out of an entry said to be 3140 long" -- two
   * supplementary characters in 3140, and the content well under {@link
   * EntryStore#MOST_CHARACTERS_PER_ENTRY}, so nothing was cut and the excerpt WAS the entry. Any
   * conversation anybody had put an emoji in could not be read back.
   */
  @Test
  void a_page_counts_characters_the_way_the_database_counts_them() {
    String said = "shipped it \uD83C\uDF89 and celebrated \uD83E\uDD73";
    entries.append(conversation, 1, LoggedEntry.answer(said, List.of()));

    EntryPage.Row row = entries.pageOfLog(conversation, 0, 10).listed().get(0);

    assertEquals(said, row.excerpt(), "the whole of it, far under the cap");
    assertEquals(
        said.codePointCount(0, said.length()),
        row.length(),
        "the database counted code points, so the record has to mean the same thing");
    assertNotEquals(
        said.length(),
        row.length(),
        "and UTF-16 code units are what it must NOT mean, or this test proves nothing");
    assertFalse(
        row.cut(), "a true here would offer a reader the rest of a text that is already whole");
  }

  /**
   * The same two units, met on the other bounded field. {@code left(call ->> 'arguments', ?)} and
   * {@code length(call ->> 'arguments')} are code points for {@link EntryPage.Asked}'s guard
   * exactly as they are for {@link EntryPage.Row}'s, and a tool asked for something with an emoji
   * in it is not rarer than a person saying one.
   */
  @Test
  void the_arguments_of_a_call_count_characters_the_way_the_database_counts_them() {
    String arguments = "{\"note\":\"shipped \uD83C\uDF89\"}";
    entries.append(
        conversation,
        1,
        LoggedEntry.answer("writing", List.of(new ToolCall("c1", "note_write", arguments))));

    EntryPage.Asked asked =
        entries.pageOfLog(conversation, 0, 10).listed().get(0).toolCalls().get(0);

    assertEquals(arguments, asked.arguments(), "the whole of it, far under the cap");
    assertEquals(
        arguments.codePointCount(0, arguments.length()),
        asked.length(),
        "the database counted code points, so the record has to mean the same thing");
    assertFalse(asked.cut(), "nothing was cut");
  }

  /**
   * A page holding an ejected payload still comes back, and the row says which of the two blanks it
   * is.
   *
   * <p><b>This is the reading nothing in the retention design mentions and that would have failed
   * first.</b> A console renders a scrollback out of these pages, and an ejected row's {@code
   * left(content, ?)} is NULL — so a page over an ejected conversation is where a null-unaware
   * reader falls over, months after the sweep that made one. The row keeps its ordinal, its kind,
   * its handle and its size; what it does not keep is text, and {@code cut} is false because the
   * page did not cut anything.
   */
  @Test
  void a_page_over_an_ejected_payload_says_the_payload_went_rather_than_failing() {
    EntryRecord stored =
        entries.append(conversation, 1, LoggedEntry.toolResult("c1", "the whole of a file"));
    Instant went = Instant.parse("2026-09-04T09:00:00Z");
    assertTrue(entries.ejectPayload(conversation, stored.ordinal(), went, "exports/x.txt"));

    EntryPage.Row row = entries.pageOfLog(conversation, 0, 10).listed().get(0);

    assertNull(row.excerpt(), "a blank would read as a tool that returned nothing");
    assertEquals(went, row.ejectedAt());
    assertEquals(
        "the whole of a file".length(),
        row.length(),
        "the size is kept, so the page still says how large the result was");
    assertFalse(row.cut(), "nothing on this page cut it; the bytes are not here at all");
    assertEquals(stored.ordinal(), row.ordinal());
    assertEquals(stored.handle(), row.handle());
  }

  /**
   * The refusals a caller's bug earns, and the one a dropped identifier earns. Both pages take the
   * same two, on {@code storedResultsBehindASeam}'s reasoning: a bad bound is the caller's mistake
   * and an unnamable conversation must not read as an empty log.
   */
  @Test
  void a_page_refuses_a_bound_that_is_not_one_and_an_id_that_names_nothing() {
    assertThrows(IllegalArgumentException.class, () -> entries.pageOfLog(conversation, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> entries.pageOfLog(conversation, -1, 5));
    assertThrows(ValidationException.class, () -> entries.pageOfLog(" ", 0, 5));
    assertThrows(
        IllegalArgumentException.class, () -> entries.pageOfProjection(conversation, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> entries.pageOfProjection(conversation, -1, 5));
    assertThrows(ValidationException.class, () -> entries.pageOfProjection(" ", 0, 5));
  }

  /**
   * One fold over everything said so far, as {@code Compaction.foldTheLog} writes it: a summary
   * entry carrying the reach, and every entry it covers pointed at it.
   */
  private void fold(int through) {
    int summary =
        entries
            .append(conversation, through, LoggedEntry.summary("they read some files"))
            .ordinal();
    entries.supersede(conversation, 0, through, summary);
  }

  /**
   * A kind this build cannot name is a row it cannot read back, and the read says so loudly rather
   * than answering with something plausible.
   */
  @Test
  void a_kind_this_build_does_not_know_is_a_row_it_refuses_to_read() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO entries (conversation_id, ordinal, kind, content, turn_ordinal)"
                    + " VALUES (?, 1, 'daydream', 'what if', 1)",
                conversation));
    assertNotNull(EntryKind.of("utterance"));
    assertThrows(IllegalArgumentException.class, () -> EntryKind.of("daydream"));
  }

  // --- the read that fetches only what a model can be shown ----------------------------

  /**
   * An entry a fold covered is not one that projects.
   *
   * <p>{@link EntryStore#thatProjectFor} is the read behind every turn's history, and it is the
   * filter {@code Projection} applies moved to where the rows are. An entry a fold covers is one no
   * model will be shown again, by construction — that is what superseding means — so fetching it is
   * work whose only consumer is the {@code continue} that discards it, paid on every turn for the
   * rest of the conversation's life.
   *
   * <p><b>{@link EntryStore#forConversation} still answers with it</b>, and that half is asserted
   * here rather than left to the rest of this file: the two reads are two different questions, and
   * a fold is the case where their answers come apart.
   */
  @Test
  void an_entry_a_fold_covered_is_not_one_that_projects() {
    entries.append(conversation, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.answer("answered one", List.of()));
    EntryRecord summary = entries.append(conversation, 1, LoggedEntry.summary("they talked"));
    entries.supersede(conversation, 0, 1, summary.ordinal());
    entries.append(conversation, 2, LoggedEntry.utterance("turn two", Speaker.person(null)));

    assertEquals(
        List.of("they talked", "turn two"),
        entries.thatProjectFor(conversation).stream().map(EntryRecord::content).toList(),
        "an entry the fold covered was fetched, for the projection to discard again");
    assertEquals(
        4,
        entries.forConversation(conversation).size(),
        "the log stopped holding what a fold covered, which is not what a fold does");
  }

  /**
   * Every kind a model can be shown is one that projects, and no other kind is.
   *
   * <p>Asked over {@link EntryKind#values()} rather than over a list written out here, so a
   * constant added later is covered by whichever side of the split its {@link EntryKind#projects()}
   * answer puts it on — including the side that is the guarantee, since a kind nobody classified
   * has no role and is therefore invisible to both halves of the filter at once.
   *
   * <p><b>Why the two halves cannot disagree.</b> The SQL says {@code role IS NOT NULL} and Java
   * says {@code kind().projects()}, which is {@code role != null} on the enum; {@code
   * entries_role_matches_kind} derives the column from the kind with a {@code CASE} that has no
   * {@code ELSE}, so a kind it does not name can only be stored with a NULL role. {@link
   * #a_kind_that_projects_stores_its_role_and_one_that_does_not_stores_none} is the assertion that
   * the stored column really does follow the enum, which is the step that makes the two predicates
   * one sentence.
   *
   * <p><b>A {@code tool_result} is on the visible side of this.</b> Worth pinning, because it is
   * the kind most easily mistaken for working a model does not re-read: it carries the {@code tool}
   * role, it projects, and what keeps it out of a request is {@code
   * Compaction.whatWasSaidAndWhatCameBack} — a consumer narrowing how much of its own history it
   * reads, one layer up and for reasons of its own. A read that dropped it here would be answering
   * a question nobody asked it.
   */
  @Test
  void every_kind_a_model_can_be_shown_is_one_that_projects_and_no_other_is() {
    for (EntryKind kind : EntryKind.values()) {
      entries.append(conversation, 1, anEntryOf(kind));
    }

    assertEquals(
        Arrays.stream(EntryKind.values()).filter(EntryKind::projects).toList(),
        entries.thatProjectFor(conversation).stream().map(EntryRecord::kind).toList(),
        "the rows fetched are not the rows a model can be shown");
    assertTrue(
        EntryKind.TOOL_RESULT.projects(),
        "a tool result stopped projecting, and this test's claim about it is now"
            + " a claim about something else");
  }

  // --- the read that answers what a turn was actually shown ---------------------------

  /**
   * A fold that happened later had not happened yet, and the read as of an earlier turn says so.
   *
   * <p><b>This is the whole reason {@code thatProjectedAt} is not {@code thatProjectFor} with
   * {@code turn_ordinal <= ?} bolted onto it.</b> {@code superseded_by} is a fact about the row as
   * it stands today and not about the row as it stood at turn 12: it records every fold that has
   * ever run. Here the fold reaches turn 40, so replaying {@code superseded_by IS NULL} against
   * turn 12 would hide five rows turn 12 could plainly see — and this read serves a screen whose
   * entire job is auditing what a turn was shown, so a history that is confident and wrong is the
   * one failure it cannot have.
   *
   * <p>The summary is out for the ordinary reason and not a special one: it was written after turn
   * 12's utterance, so it carries a later {@code ordinal} and the bound leaves it out exactly as it
   * leaves out anything else nobody had written yet.
   */
  @Test
  void a_projection_as_of_a_turn_shows_what_a_later_fold_covered() {
    entries.append(conversation, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));
    entries.append(conversation, 2, LoggedEntry.utterance("turn two", Speaker.person(null)));
    entries.append(conversation, 3, LoggedEntry.utterance("turn three", Speaker.person(null)));
    entries.append(conversation, 4, LoggedEntry.utterance("turn four", Speaker.person(null)));
    entries.append(conversation, 12, LoggedEntry.utterance("turn twelve", Speaker.person(null)));
    entries.append(conversation, 40, LoggedEntry.utterance("turn forty", Speaker.person(null)));
    EntryRecord summary =
        entries.append(
            conversation,
            40,
            LoggedEntry.summary("turns 1 to 40, folded long after turn twelve spoke"));
    assertEquals(
        6,
        entries.supersede(conversation, 0, 40, summary.ordinal()),
        "the fixture did not fold the rows this test is about");

    assertEquals(
        List.of("turn one", "turn two", "turn three", "turn four", "turn twelve"),
        entries.thatProjectedAt(conversation, 12).stream().map(EntryRecord::content).toList(),
        "a fold nobody had written at turn 12 hid rows turn 12 was shown");
    assertEquals(
        List.of("turns 1 to 40, folded long after turn twelve spoke"),
        entries.thatProjectFor(conversation).stream().map(EntryRecord::content).toList(),
        "the read behind the next turn stopped agreeing that the fold stands now");
  }

  /**
   * A fold that had already happened is applied, and its summary stands where the turns it covers
   * used to be.
   *
   * <p><b>The direction a naive fix breaks.</b> A predicate written only to rescue the rows a later
   * fold covered — dropping {@code superseded_by} and keeping the turn bound alone — would show
   * turns 1 to 8 here in full <em>and</em> the summary standing for them, which is a history no
   * turn of this conversation ever read. The fold at turn 8 is part of what turn 12 was shown, and
   * what turn 12 was shown of turns 1 to 8 is the summary.
   */
  @Test
  void a_projection_as_of_a_turn_hides_a_fold_that_had_already_happened() {
    for (int turn = 1; turn <= 8; turn++) {
      entries.append(
          conversation, turn, LoggedEntry.utterance("turn " + turn, Speaker.person(null)));
    }
    EntryRecord summary =
        entries.append(
            conversation, 8, LoggedEntry.summary("turns 1 to 8, folded before turn nine spoke"));
    assertEquals(
        8,
        entries.supersede(conversation, 0, 8, summary.ordinal()),
        "the fixture did not fold the rows this test is about");
    for (int turn = 9; turn <= 14; turn++) {
      entries.append(
          conversation, turn, LoggedEntry.utterance("turn " + turn, Speaker.person(null)));
    }

    assertEquals(
        List.of(
            "turns 1 to 8, folded before turn nine spoke",
            "turn 9",
            "turn 10",
            "turn 11",
            "turn 12"),
        entries.thatProjectedAt(conversation, 12).stream().map(EntryRecord::content).toList(),
        "a fold turn 12 had already been given was undone, or turns after it leaked in");
  }

  /**
   * Asked as of the conversation's live edge, the two reads answer with the same rows.
   *
   * <p><b>What stops this becoming a second opinion about what a model sees.</b> Every fold a
   * conversation has had has happened by its newest turn, so there the two predicates describe one
   * set of rows, and a change to either that pulled them apart would make the screen and the prompt
   * disagree about the present — the disagreement nobody would think to look for.
   *
   * <p><b>The overlap is the turn that has spoken and not yet answered</b>, and the fixture is that
   * shape on purpose. That is exactly where the two questions coincide: everything written is
   * everything that was in front of the model. Once a turn answers, the two part company by that
   * answer and by design — {@link
   * #a_projection_as_of_a_turn_stops_at_the_utterance_and_not_at_the_answer} is the assertion that
   * they do.
   */
  @Test
  void a_projection_as_of_the_latest_turn_is_the_projection() {
    for (int turn = 1; turn <= 5; turn++) {
      entries.append(
          conversation, turn, LoggedEntry.utterance("turn " + turn, Speaker.person(null)));
      entries.append(conversation, turn, LoggedEntry.answer("answered " + turn, List.of()));
    }
    EntryRecord summary = entries.append(conversation, 3, LoggedEntry.summary("turns 1 to 3"));
    entries.supersede(conversation, 0, 3, summary.ordinal());
    entries.append(conversation, 6, LoggedEntry.utterance("turn six", Speaker.person(null)));

    assertEquals(
        entries.thatProjectFor(conversation),
        entries.thatProjectedAt(conversation, 6),
        "the two reads disagree at the turn where they are asking one question");
  }

  /**
   * The turn a fold reaches, and the turn whose ending wrote it, both predate that fold.
   *
   * <p><b>The boundary, in a fixture shaped exactly as {@code Compaction} writes one.</b> A
   * summary's {@code turn_ordinal} is its <em>reach</em> and not the moment it was written: {@code
   * Compaction} sets it to {@code spoken.getLast().ordinal() - 1} from inside the ending callback
   * of the turn <em>after</em> the span, so the summary standing for turns 1 to 3 is appended at
   * the close of turn 4 — after turn 4's own answer. This test pins all three turns around that
   * seam:
   *
   * <ul>
   *   <li>turn 3, the fold's reach, was shown the raw turns and its own utterance; the fold did not
   *       exist;
   *   <li>turn 4, whose ending wrote the fold, was shown the same, because a fold decided at the
   *       end of a turn was not there at the start of it;
   *   <li>turn 5 is the first turn the summary stands in for anything.
   * </ul>
   *
   * <p><b>Every other test here sits two or more turns from a seam</b>, where a predicate comparing
   * turn numbers and one comparing ordinals cannot be told apart. This is the case that tells them
   * apart, and it is the case that occurs on every fold a conversation ever takes: a comparison
   * against {@code fold.turn_ordinal} answers turn 3 with the single summary that folds away turn
   * 3's own utterance.
   */
  @Test
  void a_projection_as_of_a_turn_a_fold_reaches_predates_that_fold() {
    entries.append(conversation, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));
    entries.append(conversation, 2, LoggedEntry.utterance("turn two", Speaker.person(null)));
    entries.append(conversation, 3, LoggedEntry.utterance("turn three", Speaker.person(null)));
    entries.append(conversation, 4, LoggedEntry.utterance("turn four", Speaker.person(null)));
    entries.append(conversation, 4, LoggedEntry.answer("answered four", List.of()));
    // Appended at the close of turn 4 and reaching turn 3, which is the
    // arithmetic Compaction actually does: the turn being spoken is never
    // folded, so the reach is the one before it.
    EntryRecord summary = entries.append(conversation, 3, LoggedEntry.summary("turns 1 to 3"));
    assertEquals(
        3,
        entries.supersede(conversation, 0, 3, summary.ordinal()),
        "the fixture did not fold the rows this test is about");
    entries.append(conversation, 5, LoggedEntry.utterance("turn five", Speaker.person(null)));

    assertEquals(
        List.of("turn one", "turn two", "turn three"),
        entries.thatProjectedAt(conversation, 3).stream().map(EntryRecord::content).toList(),
        "turn 3 was given a summary of itself, written at the close of the turn after it");
    assertEquals(
        List.of("turn one", "turn two", "turn three", "turn four"),
        entries.thatProjectedAt(conversation, 4).stream().map(EntryRecord::content).toList(),
        "turn 4 was given the fold its own ending went on to write");
    assertEquals(
        List.of("turns 1 to 3", "turn four", "answered four", "turn five"),
        entries.thatProjectedAt(conversation, 5).stream().map(EntryRecord::content).toList(),
        "turn 5 is the first turn the summary stands in for anything, and did not get it");
  }

  /**
   * The list ends at the turn's own utterance, and not at what the turn came to.
   *
   * <p><b>A turn's answer was not in front of the model when the turn started</b> — it is what the
   * turn produced — and the same goes for every tool result it collected on the way. {@code
   * JobRuntime} records the utterance before its first model call and everything else after, so the
   * ordinal of that first entry is where the prompt stops.
   *
   * <p><b>A bound on {@code turn_ordinal} would not have this property</b>, which is why it is
   * asserted rather than assumed: every entry of turn 2 shares turn 2's number, so a turn-number
   * bound answers "what was turn 2 shown" with turn 2's own answer in the list. {@link
   * EntryStore#thatProjectFor} does return it, and is right to — that is the next prompt's
   * question.
   */
  @Test
  void a_projection_as_of_a_turn_stops_at_the_utterance_and_not_at_the_answer() {
    entries.append(conversation, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.answer("answered one", List.of()));
    entries.append(conversation, 2, LoggedEntry.utterance("turn two", Speaker.person(null)));
    entries.append(conversation, 2, LoggedEntry.answer("answered two", List.of()));

    assertEquals(
        List.of("turn one", "answered one", "turn two"),
        entries.thatProjectedAt(conversation, 2).stream().map(EntryRecord::content).toList(),
        "what turn 2 went on to answer was reported as what turn 2 was shown");
    assertEquals(
        List.of("turn one", "answered one", "turn two", "answered two"),
        entries.thatProjectFor(conversation).stream().map(EntryRecord::content).toList(),
        "the next prompt's read stopped carrying the last answer, which it needs");
  }

  /**
   * A kind no model is shown is not shown as of a turn either.
   *
   * <p>The half of the predicate that did not change, pinned so it cannot be lost while the other
   * half is being argued about. {@code role IS NOT NULL} is {@code EntryKind.projects()} asked of
   * the rows, and a {@code diagnostic} is the harness talking to itself at a moment rather than
   * anything a turn was shown.
   */
  @Test
  void a_projection_as_of_a_turn_still_leaves_out_the_kinds_no_model_is_shown() {
    entries.append(conversation, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.diagnostic("folded in 41 ms"));
    entries.append(conversation, 2, LoggedEntry.runtimeNote("the transport retried"));

    assertEquals(
        List.of(EntryKind.UTTERANCE),
        entries.thatProjectedAt(conversation, 2).stream().map(EntryRecord::kind).toList(),
        "a kind carrying no role reached the read as of a turn");
  }

  /**
   * A turn number no entry could belong to is the caller's bug rather than an empty answer.
   *
   * <p>{@code entries_belong_to_a_turn_numbered_from_one} is where the floor comes from, and
   * answering zero rows would say "that turn was shown nothing" about a turn that cannot exist. The
   * route above turns a person's bad number into prose long before it reaches here, exactly as
   * {@code pageOfLog}'s bounds are refused here and corrected one layer up.
   */
  @Test
  void a_projection_as_of_a_turn_that_could_not_exist_is_refused() {
    assertThrows(IllegalArgumentException.class, () -> entries.thatProjectedAt(conversation, 0));
  }

  /**
   * A turn the log holds nothing for is refused rather than answered with an empty history.
   *
   * <p><b>The one place this read could still say "shown nothing" about a turn that happened.</b>
   * The moment a turn's prompt was assembled is the ordinal of its first entry, so a turn with no
   * entries has no such moment — and a query computing that inline would answer the empty list,
   * which on this screen reads as a turn that was sent nothing rather than as a turn nobody can
   * account for. The caller reaches here having established the turn from {@code turns}, so the two
   * records contradicting each other is the archive's problem and is raised as one.
   */
  @Test
  void a_projection_as_of_a_turn_the_log_holds_nothing_for_is_refused() {
    entries.append(conversation, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));

    assertThrows(IllegalStateException.class, () -> entries.thatProjectedAt(conversation, 7));
  }

  /**
   * One entry of each kind, in the shape that kind's constraints allow.
   *
   * <p>Written as a switch rather than as one generic row, because the per-kind constraints are the
   * thing being exercised: a {@code tool_result} needs an id, an {@code utterance} needs text, and
   * a row that satisfied all of them at once would prove nothing about any of them.
   */
  private static LoggedEntry anEntryOf(EntryKind kind) {
    return switch (kind) {
      case UTTERANCE -> LoggedEntry.utterance("what broke the deploy?", Speaker.person(null));
      case ANSWER ->
          LoggedEntry.answer("the migration did", List.of(new ToolCall("c1", "probe_read", "{}")));
      case TOOL_RESULT -> LoggedEntry.toolResult("c1", "it says here");
      case SUMMARY -> LoggedEntry.summary("they talked about the deploy");
      case TURN_SUMMARY -> LoggedEntry.turnSummary("it read three files and found the key");
      case NOTICE -> LoggedEntry.notice("an inbox item arrived");
      case ATTEMPT_FAILED ->
          LoggedEntry.attemptFailed(
              io.aeyer.plowshare.server.agents.Outcome.Ending.SESSION_GONE, "it went away");
      case RUNTIME_NOTE -> LoggedEntry.runtimeNote("[Runtime note] you keep doing that");
      case PLAN -> new LoggedEntry(EntryKind.PLAN, "step one: read the file", null, List.of());
      case DIAGNOSTIC ->
          new LoggedEntry(
              EntryKind.DIAGNOSTIC, "compaction triggered at 12 000 tokens", null, List.of());
      case THINKING -> LoggedEntry.thinking("the ledger tool has it, so ask it first");
      case REFUSAL ->
          LoggedEntry.refusal(
              "I'm sorry, but I can't help with that.",
              aCall(
                  Invocation.Dispatch.PRIMARY,
                  CompletionOutcome.REFUSED,
                  "the answer opens with a refusal phrase"));
      case HOOK ->
          LoggedEntry.hook(
              new io.aeyer.plowshare.server.hooks.HookRecord(
                  "no-secrets-in-writes",
                  "10-secrets.ts",
                  io.aeyer.plowshare.server.hooks.Tier.PROJECT,
                  io.aeyer.plowshare.server.hooks.Stage.TOOL_PRE,
                  "file_write",
                  io.aeyer.plowshare.server.hooks.HookRecord.DENY,
                  "a private key",
                  null,
                  null,
                  2));
    };
  }

  /**
   * A model call as the runtime would attribute one, with every field set so a round trip that
   * dropped any of them shows.
   */
  private static Invocation aCall(
      Invocation.Dispatch dispatch, CompletionOutcome outcome, String fallbackReason) {
    return new Invocation(
        UUID.randomUUID(),
        "osint_researcher",
        dispatch,
        dispatch == Invocation.Dispatch.PRIMARY ? "big" : "low_refusal_osint",
        dispatch == Invocation.Dispatch.PRIMARY ? "vllm-primary" : "spark",
        dispatch == Invocation.Dispatch.PRIMARY ? "gpt-oss-120b" : "small-uncensored",
        outcome,
        "stop",
        new TokenUsage(812, 14, null, 40),
        THEN,
        fallbackReason,
        1_830L);
  }

  // --- refusals and model provenance (V39) ---------------------------------------------

  @Test
  void an_answer_says_which_model_call_wrote_it_and_the_projection_never_reads_it() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("who owns example.org?", Speaker.person(null)));
    Invocation call =
        aCall(
            Invocation.Dispatch.FALLBACK,
            CompletionOutcome.ANSWERED,
            "the answer opens with a refusal phrase");
    EntryRecord answer =
        entries.append(
            conversation,
            1,
            LoggedEntry.answer("The registrant is listed as ...", List.of())
                .took(Duration.ofMillis(900))
                .by(call));

    assertEquals(
        call,
        entries.invocationOf(conversation, answer.ordinal()).orElseThrow(),
        "every field of the call a fallback made has to come back as it was written,"
            + " so nothing can attribute its words to another model");
    assertTrue(entries.invocationOf(conversation, 1).isEmpty(), "an utterance is not a model call");
  }

  @Test
  void a_refusal_is_recorded_and_never_projects() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("who owns example.org?", Speaker.person(null)));
    EntryRecord refused =
        entries.append(
            conversation,
            1,
            LoggedEntry.refusal(
                "I'm sorry, but I can't help with that.",
                aCall(Invocation.Dispatch.PRIMARY, CompletionOutcome.REFUSED, "a phrase")));
    entries.append(
        conversation,
        1,
        LoggedEntry.answer("The registrant is ...", List.of())
            .by(aCall(Invocation.Dispatch.FALLBACK, CompletionOutcome.ANSWERED, "a phrase")));

    assertEquals(
        List.of(EntryKind.UTTERANCE, EntryKind.REFUSAL, EntryKind.ANSWER),
        entries.forConversation(conversation).stream().map(EntryRecord::kind).toList(),
        "the log holds the refusal, in the place it happened");
    assertEquals(
        List.of(EntryKind.UTTERANCE, EntryKind.ANSWER),
        entries.thatProjectFor(conversation).stream().map(EntryRecord::kind).toList(),
        "what a model can be shown holds the question and the answer, and not the" + " refusal");
    assertNull(
        refused.supersededBy(),
        "nothing was superseded: the refusal was never an" + " answer to hide");
  }

  @Test
  void a_refusal_that_names_no_call_is_refused_by_the_table() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO entries (conversation_id, ordinal, kind, content, turn_ordinal)"
                    + " VALUES (?, 1, 'refusal', 'I cannot', 1)",
                conversation));
  }

  @Test
  void a_fallback_call_that_does_not_say_why_is_refused_by_the_table() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO entries (conversation_id, ordinal, kind, role, content, turn_ordinal,"
                    + " invocation, produced_by, dispatch, model_specifier, completion,"
                    + " sent_at) VALUES (?, 1, 'answer', 'assistant', 'hi', 1, ?, 'a',"
                    + " 'fallback', 'low_refusal', 'answered', now())",
                conversation,
                UUID.randomUUID()));
  }

  @Test
  void whether_the_last_turn_was_answered_by_a_fallback_is_asked_of_earlier_turns_only() {
    entries.append(conversation, 1, LoggedEntry.utterance("one", Speaker.person(null)));
    entries.append(
        conversation,
        1,
        LoggedEntry.answer("from the fallback", List.of())
            .by(aCall(Invocation.Dispatch.FALLBACK, CompletionOutcome.ANSWERED, "a phrase")));
    entries.append(conversation, 2, LoggedEntry.utterance("two", Speaker.person(null)));

    assertTrue(entries.lastAnswerWasAFallback(conversation, 2));
    assertFalse(entries.lastAnswerWasAFallback(conversation, 1), "turn one follows nothing");

    entries.append(
        conversation,
        2,
        LoggedEntry.answer("from the agent", List.of())
            .by(aCall(Invocation.Dispatch.PRIMARY, CompletionOutcome.ANSWERED, null)));
    assertFalse(entries.lastAnswerWasAFallback(conversation, 3));
  }

  /**
   * The one thing this file asserts about a store method's own refusal rather than about a
   * constraint: a null or blank conversation id would otherwise come back as an empty log, and a
   * dropped identifier would read as a conversation nobody has spoken into.
   */
  @Test
  void a_log_cannot_be_read_for_an_unnamable_conversation() {
    assertThrows(ValidationException.class, () -> entries.forConversation(" "));
    assertTrue(entries.forConversation(conversation).isEmpty());
  }

  // --- who spoke: 2026-09-28-the-log-is-the-source §2 ----------------------------------

  @Test
  void an_utterance_keeps_who_spoke_it() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("what broke it?", Speaker.person("enzo")));
    entries.append(
        conversation,
        2,
        LoggedEntry.utterance("The orchestration finished.", Speaker.orchestration("orc_1")));

    List<EntryPage.Row> rows = entries.pageOfLog(conversation, 0, 10).listed();

    assertEquals(Speaker.person("enzo"), rows.get(0).speaker());
    assertEquals(Speaker.orchestration("orc_1"), rows.get(1).speaker());
  }

  @Test
  void an_utterance_written_before_speakers_were_recorded_reads_as_a_person_s() {
    jdbc.update(
        "INSERT INTO entries (conversation_id, ordinal, kind, role, content,"
            + " turn_ordinal) VALUES (?, 1, 'utterance', 'user', 'from before V57', 1)",
        conversation);
    entries.append(conversation, 1, LoggedEntry.answer("an answer", List.of()));

    List<EntryPage.Row> rows = entries.pageOfLog(conversation, 0, 10).listed();

    assertEquals(Speaker.person(null), rows.get(0).speaker());
    assertNull(rows.get(1).speaker(), "an answer is the model's, and says no speaker");
  }

  @Test
  void the_table_refuses_a_speaker_on_anything_but_an_utterance() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO entries (conversation_id, ordinal, kind, role, content,"
                    + " turn_ordinal, speaker, speaker_name)"
                    + " VALUES (?, 1, 'answer', 'assistant', 'x', 1, 'harness', 'harness')",
                conversation));
  }

  @Test
  void the_table_refuses_a_harness_utterance_that_names_no_source() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO entries (conversation_id, ordinal, kind, role, content,"
                    + " turn_ordinal, speaker) VALUES (?, 1, 'utterance', 'user', 'x', 1,"
                    + " 'harness')",
                conversation));
  }

  // --- how far the log reaches, and what came after an ordinal ------------------------

  @Test
  void the_log_reaches_as_far_as_its_highest_ordinal() {
    assertEquals(0, entries.through(conversation));
    entries.append(conversation, 1, LoggedEntry.utterance("one", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.answer("first", List.of()));

    assertEquals(2, entries.through(conversation));
  }

  @Test
  void a_page_after_an_ordinal_holds_only_what_came_after_it_and_says_how_far_the_log_reaches() {
    for (int at = 1; at <= 5; at++) {
      entries.append(conversation, at, LoggedEntry.utterance("thing " + at, Speaker.person(null)));
    }

    EntryPage since = entries.pageOfLogAfter(conversation, 3, EntryStore.EVERY_KIND, false, 0, 10);
    assertEquals(List.of(4, 5), since.listed().stream().map(EntryPage.Row::ordinal).toList());
    assertEquals(2, since.total(), "the total counts what the reading covers");
    assertEquals(5, since.through());

    EntryPage nothingNew =
        entries.pageOfLogAfter(conversation, 5, EntryStore.EVERY_KIND, false, 0, 10);
    assertEquals(List.of(), nothingNew.listed());
    assertEquals(5, nothingNew.through(), "an empty reading still says how far the log reaches");
    assertEquals(5, entries.pageOfLog(conversation, 0, 10).through());
  }

  @Test
  void a_fold_written_late_is_after_the_ordinal_it_was_written_at() {
    entries.append(conversation, 1, LoggedEntry.utterance("one", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.answer("first", List.of()));
    entries.append(conversation, 2, LoggedEntry.utterance("two", Speaker.person(null)));
    fold(1);

    EntryPage since = entries.pageOfLogAfter(conversation, 3, EntryStore.EVERY_KIND, false, 0, 10);

    assertEquals(
        List.of(EntryKind.SUMMARY),
        since.listed().stream().map(EntryPage.Row::kind).toList(),
        "the summary sorts back among turn 1, and is still after ordinal 3");
  }

  // --- the tail, read backwards, and only the kinds a reader draws --------------------

  /**
   * Five turns, each a person's utterance, a tool result and the answer: fifteen rows, of which the
   * utterances and answers are the ten a chat draws.
   */
  private void fiveTurnsWithToolsBetween() {
    for (int turn = 1; turn <= 5; turn++) {
      entries.append(
          conversation, turn, LoggedEntry.utterance("asked " + turn, Speaker.person(null)));
      entries.append(conversation, turn, LoggedEntry.toolResult("call_" + turn, "read"));
      entries.append(conversation, turn, LoggedEntry.answer("answered " + turn, List.of()));
    }
  }

  private static final Set<EntryKind> DRAWN =
      EnumSet.of(EntryKind.UTTERANCE, EntryKind.ANSWER, EntryKind.SUMMARY);

  @Test
  void the_tail_is_read_newest_first_and_says_whether_anything_lies_before_it() {
    fiveTurnsWithToolsBetween();

    EntryPage tail =
        entries.pageOfLogBefore(
            conversation, EntryStore.FROM_THE_END, EntryStore.EVERY_KIND, false, 0, 4);

    assertEquals(
        List.of(15, 14, 13, 12),
        tail.listed().stream().map(EntryPage.Row::ordinal).toList(),
        "newest first, and the newest is the log's last row");
    assertEquals(15, tail.total(), "everything before the end is the reading");
    assertEquals(15, tail.through());
    assertEquals(12, tail.oldest());
    assertEquals(Boolean.TRUE, tail.more());
  }

  @Test
  void a_read_before_an_ordinal_holds_only_what_came_before_it() {
    fiveTurnsWithToolsBetween();

    EntryPage earlier =
        entries.pageOfLogBefore(conversation, 4, EntryStore.EVERY_KIND, false, 0, 10);

    assertEquals(List.of(3, 2, 1), earlier.listed().stream().map(EntryPage.Row::ordinal).toList());
    assertEquals(3, earlier.total());
    assertEquals(15, earlier.through(), "through is the whole log's, whatever the page holds");
    assertEquals(1, earlier.oldest());
    assertEquals(Boolean.FALSE, earlier.more(), "the first entry is on this page");
  }

  @Test
  void a_backwards_read_of_some_kinds_returns_and_counts_only_those() {
    fiveTurnsWithToolsBetween();

    EntryPage tail =
        entries.pageOfLogBefore(conversation, EntryStore.FROM_THE_END, DRAWN, false, 0, 4);

    assertEquals(
        List.of(15, 13, 12, 10),
        tail.listed().stream().map(EntryPage.Row::ordinal).toList(),
        "the tool results between are passed over, not counted against the limit");
    assertEquals(10, tail.total(), "the total counts what this reading holds");
    assertEquals(15, tail.through());
    assertEquals(Boolean.TRUE, tail.more());

    EntryPage rest = entries.pageOfLogBefore(conversation, tail.oldest(), DRAWN, false, 0, 10);
    assertEquals(
        List.of(9, 7, 6, 4, 3, 1), rest.listed().stream().map(EntryPage.Row::ordinal).toList());
    assertEquals(Boolean.FALSE, rest.more());
  }

  @Test
  void more_is_false_when_only_other_kinds_lie_before_the_page() {
    entries.append(conversation, 1, LoggedEntry.diagnostic("warming up"));
    entries.append(conversation, 1, LoggedEntry.utterance("hi", Speaker.person(null)));

    EntryPage tail =
        entries.pageOfLogBefore(conversation, EntryStore.FROM_THE_END, DRAWN, false, 0, 1);

    assertEquals(2, tail.oldest());
    assertEquals(Boolean.FALSE, tail.more(), "a diagnostic is nothing this reader asked for");
  }

  @Test
  void a_fold_summary_is_read_backwards_like_any_other_row() {
    entries.append(conversation, 1, LoggedEntry.utterance("one", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.answer("first", List.of()));
    entries.append(conversation, 2, LoggedEntry.utterance("two", Speaker.person(null)));
    fold(1);

    EntryPage tail =
        entries.pageOfLogBefore(conversation, EntryStore.FROM_THE_END, DRAWN, false, 0, 10);

    assertEquals(
        List.of(EntryKind.SUMMARY, EntryKind.UTTERANCE, EntryKind.ANSWER, EntryKind.UTTERANCE),
        tail.listed().stream().map(EntryPage.Row::kind).toList(),
        "by ordinal: the summary was written last, so it is the newest");
  }

  @Test
  void a_backwards_read_of_an_empty_log_is_empty_and_has_nothing_before_it() {
    EntryPage tail =
        entries.pageOfLogBefore(conversation, EntryStore.FROM_THE_END, DRAWN, false, 0, 40);

    assertEquals(List.of(), tail.listed());
    assertEquals(0, tail.total());
    assertEquals(0, tail.through());
    assertNull(tail.oldest());
    assertEquals(Boolean.FALSE, tail.more());
  }

  @Test
  void a_forward_read_of_some_kinds_counts_only_those_and_says_nothing_of_what_is_before() {
    fiveTurnsWithToolsBetween();

    EntryPage since = entries.pageOfLogAfter(conversation, 9, DRAWN, false, 0, 10);

    assertEquals(
        List.of(10, 12, 13, 15), since.listed().stream().map(EntryPage.Row::ordinal).toList());
    assertEquals(4, since.total());
    assertEquals(15, since.through(), "through is the log's, not the filtered rows'");
    assertEquals(10, since.oldest());
    assertNull(since.more(), "a forward reading was not asked what lies before it");
  }

  /**
   * Three turns, each a person's question, an answer that asked for a tool, the tool's result and
   * the answer that ends it: of each four rows, a chat draws two.
   */
  private void threeTurnsThatAskedForTools() {
    for (int turn = 1; turn <= 3; turn++) {
      entries.append(
          conversation, turn, LoggedEntry.utterance("asked " + turn, Speaker.person(null)));
      entries.append(
          conversation,
          turn,
          LoggedEntry.answer("let me look", List.of(new ToolCall("c" + turn, "file_read", "{}"))));
      entries.append(conversation, turn, LoggedEntry.toolResult("c" + turn, "read"));
      entries.append(conversation, turn, LoggedEntry.answer("answered " + turn, List.of()));
    }
  }

  @Test
  void a_drawn_reading_skips_the_answers_that_asked_for_tools_and_fills_its_limit_without_them() {
    threeTurnsThatAskedForTools();

    EntryPage undrawn =
        entries.pageOfLogBefore(conversation, EntryStore.FROM_THE_END, DRAWN, false, 0, 4);
    assertEquals(
        List.of(12, 10, 9, 8),
        undrawn.listed().stream().map(EntryPage.Row::ordinal).toList(),
        "without it, an answer that asked for a tool takes a place on the page");

    EntryPage tail =
        entries.pageOfLogBefore(conversation, EntryStore.FROM_THE_END, DRAWN, true, 0, 4);

    assertEquals(
        List.of(12, 9, 8, 5),
        tail.listed().stream().map(EntryPage.Row::ordinal).toList(),
        "two whole turns: every row on the page is one a chat draws");
    assertEquals(6, tail.total(), "and the count is of those too");
    assertEquals(12, tail.through(), "through is still the whole log's");
    assertEquals(Boolean.TRUE, tail.more());
  }

  @Test
  void more_ignores_the_answers_a_drawn_reading_skips() {
    entries.append(
        conversation,
        1,
        LoggedEntry.answer("let me look", List.of(new ToolCall("c1", "file_read", "{}"))));
    entries.append(conversation, 1, LoggedEntry.utterance("asked", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.answer("answered", List.of()));

    EntryPage tail =
        entries.pageOfLogBefore(conversation, EntryStore.FROM_THE_END, DRAWN, true, 0, 2);

    assertEquals(List.of(3, 2), tail.listed().stream().map(EntryPage.Row::ordinal).toList());
    assertEquals(Boolean.FALSE, tail.more(), "only a skipped answer lies before the page");
    assertEquals(
        Boolean.TRUE,
        entries.pageOfLogBefore(conversation, EntryStore.FROM_THE_END, DRAWN, false, 0, 2).more(),
        "which an undrawn reading counts");
  }

  @Test
  void a_drawn_reading_after_an_ordinal_skips_them_too() {
    threeTurnsThatAskedForTools();

    EntryPage since = entries.pageOfLogAfter(conversation, 4, EntryStore.EVERY_KIND, true, 0, 10);

    assertEquals(
        List.of(5, 7, 8, 9, 11, 12),
        since.listed().stream().map(EntryPage.Row::ordinal).toList(),
        "every other kind is read; only the answers that asked for tools are left out");
    assertEquals(6, since.total());
    assertEquals(12, since.through());
  }

  /**
   * A turn can commit between the moment a reading reads how far the log reaches and the moment it
   * reads the page. The page is the log as it was at that reach: the row that landed after it is in
   * neither the page nor the total, so the two agree and {@code more} is reckoned from both.
   */
  @Test
  void a_row_landing_after_the_reach_was_read_is_in_neither_the_page_nor_its_count() {
    for (int turn = 1; turn <= 3; turn++) {
      entries.append(
          conversation, turn, LoggedEntry.utterance("asked " + turn, Speaker.person(null)));
    }
    int reach = entries.through(conversation);
    entries.append(conversation, 4, LoggedEntry.utterance("landed between", Speaker.person(null)));

    EntryPage tail =
        entries.pageOfLogBefore(conversation, EntryStore.FROM_THE_END, DRAWN, false, 0, 2, reach);
    assertEquals(List.of(3, 2), tail.listed().stream().map(EntryPage.Row::ordinal).toList());
    assertEquals(3, tail.total());
    assertEquals(3, tail.through());
    assertEquals(Boolean.TRUE, tail.more(), "ordinal 1 lies before it, and nothing else");

    EntryPage whole =
        entries.pageOfLogBefore(conversation, EntryStore.FROM_THE_END, DRAWN, false, 0, 10, reach);
    assertEquals(
        List.of(3, 2, 1),
        whole.listed().stream().map(EntryPage.Row::ordinal).toList(),
        "not the row after the reach: it would be one more row than the total");
    assertEquals(3, whole.total());
    assertEquals(Boolean.FALSE, whole.more());

    EntryPage since =
        entries.pageOfLogAfter(conversation, 1, EntryStore.EVERY_KIND, false, 0, 10, reach);
    assertEquals(List.of(2, 3), since.listed().stream().map(EntryPage.Row::ordinal).toList());
    assertEquals(2, since.total());
    assertEquals(3, since.through(), "the next reading starts after the reach, and finds 4");

    assertEquals(
        List.of(4),
        entries
            .pageOfLogAfter(conversation, since.through(), EntryStore.EVERY_KIND, false, 0, 10)
            .listed()
            .stream()
            .map(EntryPage.Row::ordinal)
            .toList());
  }

  @Test
  void a_backwards_read_refuses_an_ordinal_before_the_first_and_an_empty_set_of_kinds() {
    assertThrows(
        IllegalArgumentException.class,
        () -> entries.pageOfLogBefore(conversation, 0, DRAWN, false, 0, 10));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            entries.pageOfLogBefore(conversation, EntryStore.FROM_THE_END, Set.of(), false, 0, 10));
  }
}
