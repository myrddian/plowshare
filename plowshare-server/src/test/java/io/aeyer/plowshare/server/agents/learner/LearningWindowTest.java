package io.aeyer.plowshare.server.agents.learner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What the system decides to show the learner, which is the decision the whole design turns on.
 *
 * <h2>Why every one of these is a test and not a sentence in a prompt</h2>
 *
 * <p>Each assertion here is a piece of policy — what is worth mining, in what order, and how much
 * of it at a time. Had the agent chosen its own window, none of it would be assertable: the answers
 * would be a model's, reached differently on each run and unauditable afterwards. That is reason 1
 * of the four on {@link LearningWindow}, and this file is what it buys.
 */
@Tag("full-db")
@Testcontainers
class LearningWindowTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Home PAYMENTS = Home.of("payments");
  private static final Home LEDGER = Home.of("ledger");

  private static JdbcTemplate jdbc;

  private ConversationStore conversations;
  private EntryStore entries;

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
    jdbc.execute(
        "TRUNCATE TABLE board_message_route_bindings, outgoing_work, message_external_tasks, message_external_contexts, board_message_routes, board_message_instances, board_post_receipts, skill_executions, command_invocations, orchestration_start_receipts, orchestration_script_steps, board_reads, board_notices, board_decisions, digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, conversations, turns, compactions, entries, citations, orchestrations, orchestration_messages, board_topics, board_messages, board_seats, firings, user_inbox");
    conversations = new ConversationStore(jdbc);
    entries = new EntryStore(jdbc);
  }

  /** A server with nothing folded has no window, which is not the same as an empty one. */
  @Test
  void a_server_with_nothing_folded_has_no_window() {
    String live = conversations.open(PAYMENTS, Budget.of(20)).id();
    entries.append(
        live, 1, LoggedEntry.utterance("still being talked about", Speaker.person(null)));

    assertTrue(LearningWindow.next(entries, conversations).isEmpty());
  }

  /**
   * The window is one conversation's folded material, with its project and the state of its tree on
   * it.
   */
  @Test
  void a_window_is_the_folded_material_of_one_conversation() {
    String conversation = conversations.open(PAYMENTS, Budget.of(20)).id();
    entries.append(
        conversation, 1, LoggedEntry.utterance("we roll back on red", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.answer("understood", List.of()));
    fold(conversation, 1, 1);

    LearningWindow window = LearningWindow.next(entries, conversations).orElseThrow();

    assertEquals(conversation, window.conversationId());
    assertEquals(PAYMENTS, window.home());
    assertEquals(ConversationLifecycle.ACTIVE, window.standing());
    assertEquals(
        List.of("we roll back on red", "understood"),
        window.entries().stream().map(EntryRecord::content).toList());
    assertEquals(2, window.shown());
    assertEquals(2, window.awaiting());
    assertEquals(List.of(1, 2), window.ordinals());
  }

  /**
   * A tree marked for ejection goes to the front.
   *
   * <p>The one relationship between the two workflows, and it is an ordering rather than a gate:
   * nothing here holds up an ejection, and the sweep still does not read {@code learned_at}. What
   * the mark buys is that the material this server will not get another chance at is mined first.
   */
  @Test
  void a_tree_marked_for_ejection_goes_to_the_front() {
    String older = folded(PAYMENTS, "the older conversation");
    String marked = folded(LEDGER, "about to be ejected");
    conversations.moveTo(marked, ConversationLifecycle.TO_BE_EJECTED);

    LearningWindow window = LearningWindow.next(entries, conversations).orElseThrow();

    assertEquals(marked, window.conversationId());
    assertEquals(ConversationLifecycle.TO_BE_EJECTED, window.standing());
    assertEquals(LEDGER, window.home());
    // And the older one is still there, mined by the window after this.
    assertEquals(1, entries.countAwaitingTheLearner(older, ConversationLifecycle.ACTIVE));
  }

  /** An archived tree comes before a live one, and after a marked one. */
  @Test
  void an_archived_tree_comes_before_a_live_one_and_after_a_marked_one() {
    folded(PAYMENTS, "still being used");
    String archived = folded(PAYMENTS, "a person called this done");
    conversations.moveTo(archived, ConversationLifecycle.ARCHIVED);

    assertEquals(
        archived, LearningWindow.next(entries, conversations).orElseThrow().conversationId());

    String marked = folded(LEDGER, "about to be ejected");
    conversations.moveTo(marked, ConversationLifecycle.TO_BE_EJECTED);

    assertEquals(
        marked, LearningWindow.next(entries, conversations).orElseThrow().conversationId());
  }

  /**
   * A delegated child is ranked by the tree it belongs to and not by itself.
   *
   * <p>The lifecycle lives on the root and a child carries NULL, so "this conversation is urgent"
   * is only ever a fact about its tree. A child of a marked root is as urgent as the root: its
   * payloads go in the same sweep.
   */
  @Test
  void a_delegated_child_is_ranked_by_the_root_of_its_tree() {
    folded(PAYMENTS, "an ordinary live conversation");
    String root = conversations.open(LEDGER, Budget.of(20)).id();
    String child = conversations.log(Origin.DELEGATION, LEDGER, "code_reviewer", root, null).id();
    entries.append(child, 1, LoggedEntry.utterance("read this repository", Speaker.person(null)));
    fold(child, 1, 1);
    conversations.moveTo(root, ConversationLifecycle.TO_BE_EJECTED);

    LearningWindow window = LearningWindow.next(entries, conversations).orElseThrow();

    assertEquals(child, window.conversationId());
    assertEquals(ConversationLifecycle.TO_BE_EJECTED, window.standing());
  }

  /**
   * A run that never folded is mined the moment somebody archives it, which is what makes {@code
   * archived} and {@code to_be_ejected} do anything at all.
   *
   * <p><b>Ranking was never enough and this is why.</b> {@link LearningWindow#rank} orders a
   * shortlist; it cannot put a conversation on one. A run that never folds supersedes nothing, so
   * it was never a candidate, and the two states could only ever reorder conversations that had
   * already folded something. They were decorative for exactly the shape §10 cites them for.
   *
   * <p>And that shape is the common one. Tool results do not push a fold — {@code
   * Compaction.foldIfItWouldNotFit} measures what a turn sent and what it added, and a result is
   * neither — so "ask a question, read one file, answer, done" is a whole run with nothing
   * superseded in it, and it is the run carrying the largest payloads on the server.
   */
  @Test
  void a_run_that_never_folded_is_mined_once_somebody_archives_it() {
    String once = conversations.open(PAYMENTS, Budget.of(20)).id();
    entries.append(once, 1, LoggedEntry.utterance("what does this file do?", Speaker.person(null)));
    entries.append(once, 1, LoggedEntry.answer("it rolls back on red", List.of()));

    assertTrue(LearningWindow.next(entries, conversations).isEmpty());

    conversations.moveTo(once, ConversationLifecycle.ARCHIVED);

    LearningWindow window = LearningWindow.next(entries, conversations).orElseThrow();

    assertEquals(once, window.conversationId());
    assertEquals(ConversationLifecycle.ARCHIVED, window.standing());
    assertEquals(2, window.shown());
    assertEquals(2, window.awaiting());
  }

  /**
   * And the live rows of a tree somebody can still speak into stay out of every window, however
   * long they have been sitting there.
   *
   * <p><b>The one thing the widening must not do.</b> The ground for it is that {@code Turn.speak}
   * refuses a turn into a finished tree, so there is no later moment to mine one in; while the tree
   * is {@code ACTIVE} that premise holds and the next fold offers the same material again. What
   * enforces it is {@code ConversationLifecycle.acceptsWork} — the same method {@code Turn} asks —
   * consulted on the ROOT that {@link LearningWindow#next} resolves, so a delegated child cannot be
   * mined out from under a conversation that is still going.
   */
  @Test
  void the_live_rows_of_a_tree_somebody_can_speak_into_are_in_no_window() {
    String root = conversations.open(PAYMENTS, Budget.of(20)).id();
    String child = conversations.log(Origin.DELEGATION, PAYMENTS, "code_reviewer", root, null).id();
    entries.append(
        root, 1, LoggedEntry.utterance("review the payments module", Speaker.person(null)));
    entries.append(child, 1, LoggedEntry.utterance("read this repository", Speaker.person(null)));

    assertTrue(LearningWindow.next(entries, conversations).isEmpty());
  }

  /**
   * A finished tree is drained rather than re-read, and a branch of it is reached through its root.
   *
   * <p><b>What stops a pass over an archived tree repeating for ever.</b> {@code learned_at} is
   * asked of every row whatever the tree's state — it is not part of the half that widened — so a
   * live row handed over is marked and gone from the queue exactly as a folded one is. The tree
   * here holds two conversations, so it takes two windows and then none: the drain is visible
   * rather than asserted.
   */
  @Test
  void a_finished_tree_is_drained_one_window_at_a_time_and_then_offers_nothing() {
    String root = conversations.open(LEDGER, Budget.of(20)).id();
    String child = conversations.log(Origin.DELEGATION, LEDGER, "code_reviewer", root, null).id();
    entries.append(root, 1, LoggedEntry.utterance("review the ledger", Speaker.person(null)));
    entries.append(child, 1, LoggedEntry.utterance("read this repository", Speaker.person(null)));
    conversations.moveTo(root, ConversationLifecycle.ARCHIVED);

    LearningWindow first = LearningWindow.next(entries, conversations).orElseThrow();
    entries.markLearned(first.conversationId(), first.ordinals(), java.time.Instant.now());

    LearningWindow second = LearningWindow.next(entries, conversations).orElseThrow();
    entries.markLearned(second.conversationId(), second.ordinals(), java.time.Instant.now());

    assertEquals(
        java.util.Set.of(root, child),
        java.util.Set.of(first.conversationId(), second.conversationId()));
    assertTrue(LearningWindow.next(entries, conversations).isEmpty());
  }

  /** The window stops at the entry cap, and says how many are still waiting. */
  @Test
  void a_window_stops_at_the_entry_cap_and_says_what_it_did_not_show() {
    String conversation = conversations.open(PAYMENTS, Budget.of(20)).id();
    for (int i = 0; i < LearningWindow.MOST_ENTRIES + 5; i++) {
      entries.append(
          conversation, 1, LoggedEntry.utterance("something said", Speaker.person(null)));
    }
    fold(conversation, 1, 1);

    LearningWindow window = LearningWindow.next(entries, conversations).orElseThrow();

    assertEquals(LearningWindow.MOST_ENTRIES, window.shown());
    assertEquals(LearningWindow.MOST_ENTRIES + 5, window.awaiting());
  }

  /** And at the character cap, without splitting an entry to reach it. */
  @Test
  void a_window_stops_at_the_character_cap_without_splitting_an_entry() {
    String conversation = conversations.open(PAYMENTS, Budget.of(20)).id();
    String big = "x".repeat(LearningWindow.MOST_CHARACTERS / 2);
    entries.append(conversation, 1, LoggedEntry.utterance(big, Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.utterance(big, Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.utterance(big, Speaker.person(null)));
    fold(conversation, 1, 1);

    LearningWindow window = LearningWindow.next(entries, conversations).orElseThrow();

    assertEquals(2, window.shown());
    assertEquals(3, window.awaiting());
    window.entries().forEach(entry -> assertEquals(big, entry.content()));
  }

  /**
   * An entry larger than the whole window is taken alone.
   *
   * <p>The anti-deadlock rule. A window that refused it would leave it at the head of the queue for
   * ever with everything behind it, which is the one way this design could stop making progress.
   */
  @Test
  void an_entry_larger_than_the_whole_window_is_taken_alone() {
    String conversation = conversations.open(PAYMENTS, Budget.of(20)).id();
    entries.append(
        conversation,
        1,
        LoggedEntry.utterance(
            "x".repeat(LearningWindow.MOST_CHARACTERS + 1), Speaker.person(null)));
    entries.append(
        conversation, 1, LoggedEntry.utterance("and something small", Speaker.person(null)));
    fold(conversation, 1, 1);

    LearningWindow window = LearningWindow.next(entries, conversations).orElseThrow();

    assertEquals(1, window.shown());
    assertEquals(2, window.awaiting());
  }

  /** A window already given is not given again. */
  @Test
  void a_window_already_given_is_not_offered_again() {
    String conversation = folded(PAYMENTS, "we roll back on red");
    LearningWindow window = LearningWindow.next(entries, conversations).orElseThrow();
    entries.markLearned(conversation, window.ordinals(), java.time.Instant.now());

    assertTrue(LearningWindow.next(entries, conversations).isEmpty());
  }

  /** A conversation holding one folded utterance, and the id of it. */
  private String folded(Home home, String said) {
    String conversation = conversations.open(home, Budget.of(20)).id();
    entries.append(conversation, 1, LoggedEntry.utterance(said, Speaker.person(null)));
    fold(conversation, 1, 1);
    return conversation;
  }

  /** Folds a log exactly as {@code Compaction} does. */
  private void fold(String conversation, int turnOrdinal, int through) {
    EntryRecord summary =
        entries.append(conversation, turnOrdinal, LoggedEntry.summary("they talked"));
    entries.supersede(conversation, 0, through, summary.ordinal());
  }

  /**
   * Both bounds are real bounds, and the shortlist is one too.
   *
   * <p>Not a restatement of the constants — the two cap tests above are what assert the behaviour.
   * This asserts the relationship a reader would otherwise have to check by eye: a window bounded
   * in rows but not in characters is not bounded, and a shortlist that considered everything would
   * pay for the ranking rather than for the learning.
   */
  @Test
  void the_window_is_bounded_in_two_units_and_the_shortlist_in_a_third() {
    assertTrue(LearningWindow.MOST_ENTRIES > 0);
    assertTrue(LearningWindow.MOST_CHARACTERS > 0);
    assertTrue(LearningWindow.MOST_CONSIDERED > 0);
  }
}
