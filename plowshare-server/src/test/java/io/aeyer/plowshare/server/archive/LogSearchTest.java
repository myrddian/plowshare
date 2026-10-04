package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Speaker;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Searching the conversation log by its words.
 *
 * <h2>What this file holds that {@code EntryStoreTest} does not</h2>
 *
 * <p>{@code EntryStoreTest} is about what the table accepts and what a fold does to it. This is
 * about one read and the column {@code V23} built for it, and it is separate for {@code
 * DocumentStoreTest}'s reason one package over: the lexical half of a search has a set of claims of
 * its own — which rows it can reach, which it cannot and says so about, what a question that names
 * no word comes back with, and which index serves the plan — and none of them is a claim about
 * appending an entry.
 *
 * <p><b>Every assertion here that looks like it is about Postgres is about Postgres.</b> The
 * generated column, the {@code english} configuration, the rank-above-zero predicate and the GIN
 * plan are all facts of the database rather than of this repository's Java, and they are measured
 * against {@code pgvector/pgvector:pg16} exactly as {@code V21}'s were.
 */
@Tag("full-db")
@Testcontainers
class LogSearchTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Home PAYMENTS = Home.of("payments");

  private static JdbcTemplate jdbc;

  private ConversationStore conversations;
  private EntryStore entries;
  private String conversation;

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
    conversation = conversations.open(Home.global(), Budget.of(20)).id();
  }

  // --- the column V23 built ------------------------------------------------------

  /**
   * <b>Every entry is indexed for its words without any writer saying so.</b>
   *
   * <p>V21's claim about {@code chunks.text_search}, asked again of the log: the column is {@code
   * GENERATED ALWAYS AS ... STORED}, so it is computed by the database inside the statement that
   * writes the text, from that text.
   */
  @Test
  void every_entry_is_indexed_for_its_words_without_the_store_writing_one() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));

    assertEquals(
        1,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM entries WHERE text_search @@"
                    + " websearch_to_tsquery('english', 'refilling')",
                Integer.class),
        "the stored lexemes do not answer a stemmed question, so either the column is"
            + " not there or it was not computed from the content");
  }

  /**
   * <b>And nothing can write it out of step with the text it indexes.</b>
   *
   * <p>The whole reason the column is generated rather than filled by {@code append}: a lexical
   * index that disagrees with its own text has no symptom. Postgres refuses the write outright, so
   * the divergence is unreachable rather than merely unlikely.
   */
  @Test
  void nothing_can_write_the_lexical_index_out_of_step_with_the_text() {
    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.update(
                "INSERT INTO entries (conversation_id, ordinal, kind, role, content,"
                    + " turn_ordinal, text_search)"
                    + " VALUES (?, 1, 'utterance', 'user', 'alpha', 1,"
                    + " to_tsvector('english', 'beta'))",
                conversation));
  }

  /**
   * <b>An ejected payload has no lexemes, and that is what makes it unsearchable rather than merely
   * absent.</b>
   *
   * <p>{@code V19} dropped {@code NOT NULL} from {@code content} so a retention sweep can eject a
   * payload and keep the row. {@code to_tsvector} of NULL is NULL, so the row leaves the index the
   * moment the bytes go — which is correct and is exactly why {@code EntryStore.search} has to
   * report the count separately. A row that is present and unfindable with nothing saying why is
   * the confident-empty answer this project keeps deleting.
   */
  @Test
  void an_ejected_payload_has_no_lexemes_at_all() {
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", "the retry budget refilled"));
    assertTrue(
        entries.ejectPayload(
            conversation, 1, Instant.parse("2026-09-04T10:00:00Z"), "/exports/one.jsonl"));

    assertEquals(
        1,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM entries WHERE text_search IS NULL", Integer.class),
        "an ejected payload still carries lexemes, so the index disagrees with a row"
            + " whose text is gone");
  }

  /**
   * <b>The migration and the query stem by the same configuration.</b>
   *
   * <p>{@code DocumentStoreTest}'s check, asked again of the log's half. {@code V23} welds {@code
   * english} into a generated column because a generated column may not read {@code
   * default_text_search_config}; {@code EntryStore.SEARCH_SQL} has to spell the same word, and
   * nothing about a mismatch fails — the query parses, the operator runs, and matching quietly
   * stops.
   */
  @Test
  void the_migration_and_the_query_stem_by_the_same_configuration() throws Exception {
    String migration =
        new String(
            getClass()
                .getResourceAsStream("/db/migration/V23__entry_text_search.sql")
                .readAllBytes(),
            StandardCharsets.UTF_8);

    assertTrue(
        migration.contains("to_tsvector('" + EntryStore.TEXT_SEARCH_CONFIGURATION + "', content)"),
        "V23 does not generate entries.text_search with the configuration"
            + " EntryStore.SEARCH_SQL asks its questions in ('"
            + EntryStore.TEXT_SEARCH_CONFIGURATION
            + "')");
    assertTrue(
        EntryStore.SEARCH_SQL.contains(
            "websearch_to_tsquery('" + EntryStore.TEXT_SEARCH_CONFIGURATION + "'"),
        EntryStore.SEARCH_SQL);
    assertTrue(
        EntryStore.REACH_SQL.contains(
            "websearch_to_tsquery('" + EntryStore.TEXT_SEARCH_CONFIGURATION + "'"),
        EntryStore.REACH_SQL);
  }

  // --- which rows a search can reach ---------------------------------------------

  /**
   * <b>What was said is searchable, whether or not a model can still see it.</b>
   *
   * <p>The four kinds that carry a role are the conversation; a fold does not unsay any of them.
   * Dropping superseded rows would make a search miss precisely the part of a long conversation
   * nothing else can reach, which is most of what a search over a log is for.
   */
  @Test
  void a_folded_entry_is_still_findable() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));
    int summary = entries.append(conversation, 1, LoggedEntry.summary("a fold")).ordinal();
    entries.supersede(conversation, 0, 1, summary);

    List<LogSearch.Hit> hits = entries.search(Home.global(), "budget", 0, 20).hits();

    assertEquals(1, hits.size(), "a folded entry dropped out of the search");
    assertEquals(
        summary, hits.get(0).supersededBy(), "the hit does not say which summary folded it away");
  }

  /**
   * <b>The kinds that never reach a model are never hits.</b>
   *
   * <p>A {@code diagnostic} is the harness talking about the conversation, an {@code
   * attempt_failed} is a call that never reached a model, a {@code runtime_note} is a nudge the
   * harness composed, and a {@code plan} has no writer at all. Searching them would answer a
   * different question from searching the conversation, and the answer would be dominated by the
   * harness's own fixed vocabulary.
   */
  @Test
  void the_kinds_no_model_ever_sees_are_never_hits() {
    entries.append(conversation, 1, LoggedEntry.diagnostic("a budget was refilled by a fold"));
    entries.append(conversation, 1, LoggedEntry.runtimeNote("this budget call repeats"));
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));

    List<LogSearch.Hit> hits = entries.search(Home.global(), "budget", 0, 20).hits();

    assertEquals(
        List.of(EntryKind.UTTERANCE),
        hits.stream().map(LogSearch.Hit::kind).toList(),
        "a search reached a kind no model has ever been shown");
  }

  /**
   * <b>And what it could not reach is counted rather than left out.</b>
   *
   * <p>{@code DocumentStore.coverage}'s rule at log scale: an empty answer is a conclusion somebody
   * acts on, so it may only ever mean the log was searched and held nothing. The three counts
   * partition the tier exactly — every entry is searched, or ejected, or of a kind that is recorded
   * and never said — so a reader can check the arithmetic rather than trust it.
   */
  @Test
  void what_a_search_could_not_reach_is_counted_and_the_counts_partition_the_log() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", "a hundred thousand lines"));
    entries.append(conversation, 1, LoggedEntry.diagnostic("compaction triggered"));
    entries.append(
        conversation,
        1,
        LoggedEntry.attemptFailed(Outcome.Ending.UNAVAILABLE, "the endpoint died"));
    entries.ejectPayload(conversation, 2, Instant.parse("2026-09-04T10:00:00Z"), "/exports/a");

    LogSearch.Reach reach = entries.search(Home.global(), "budget", 0, 20).reach();

    assertEquals(1, reach.searched());
    assertEquals(1, reach.ejected());
    assertEquals(2, reach.recordedOnly());
    assertEquals(
        4,
        reach.searched() + reach.ejected() + reach.recordedOnly(),
        "the three counts do not add up to the log, so one of them is counting a row"
            + " another one has already counted");
  }

  /**
   * A search says how many entries matched and not merely how many it is returning, so a page that
   * ended and a page that was cut are two facts.
   */
  @Test
  void a_search_says_how_many_matched_and_not_only_how_many_it_returns() {
    for (int i = 1; i <= 5; i++) {
      entries.append(
          conversation, i, LoggedEntry.utterance("budget number " + i, Speaker.person(null)));
    }

    LogSearch found = entries.search(Home.global(), "budget", 0, 2);

    assertEquals(2, found.hits().size());
    assertEquals(5, found.total());
  }

  // --- what the question means ---------------------------------------------------

  /**
   * <b>A question that names no word to match returns nothing rather than everything.</b>
   *
   * <p>V21's second transferable finding, asked of the log. {@code websearch_to_tsquery} reads a
   * leading dash as negation, so {@code -alpha} parses to {@code !'alpha'} — which most rows
   * satisfy, at rank 0. Without {@code ts_rank_cd(...) > 0} those rows arrive carrying real-looking
   * ranks.
   */
  @Test
  void a_question_that_names_no_word_to_match_returns_nothing_rather_than_everything() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));
    entries.append(
        conversation, 1, LoggedEntry.utterance("sockets and pools", Speaker.person(null)));

    assertEquals(
        List.of(),
        entries.search(Home.global(), "-alpha", 0, 20).hits(),
        "a negation matched rows at rank 0 and they came back as hits");
  }

  /**
   * A question of nothing but stopwords parses to nothing and matches nothing, which is the answer
   * and not a failure.
   */
  @Test
  void a_question_of_nothing_but_stopwords_matches_nothing() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));

    assertEquals(List.of(), entries.search(Home.global(), "the and of", 0, 20).hits());
  }

  /**
   * Every content word has to be present: {@code websearch_to_tsquery} builds a conjunction, which
   * is what answers this database's having no corpus-wide term statistics to weigh a common word
   * down with.
   */
  @Test
  void every_content_word_of_the_question_must_be_present() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));

    assertEquals(1, entries.search(Home.global(), "retry budget", 0, 20).hits().size());
    assertEquals(List.of(), entries.search(Home.global(), "retry budget sockets", 0, 20).hits());
  }

  /**
   * <b>Two equally ranked entries come back in a fixed order, and it is conversation order.</b>
   *
   * <p>A {@code LIMIT} over an unbroken tie takes an arbitrary subset of the tied rows, so without
   * a tie-break the candidate <em>set</em> and not merely its order would vary between two runs of
   * one question. A GIN index supplies the filter and not the order, so the tie-break costs the
   * plan nothing — see {@link #a_second_sort_key_costs_the_gin_index_nothing}.
   */
  @Test
  void equally_ranked_entries_come_back_in_conversation_order() {
    entries.append(conversation, 2, LoggedEntry.utterance("budget", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.utterance("budget", Speaker.person(null)));

    List<Integer> turns = new ArrayList<>();
    for (LogSearch.Hit hit : entries.search(Home.global(), "budget", 0, 20).hits()) {
      turns.add(hit.turnOrdinal());
    }
    assertEquals(
        List.of(1, 2),
        turns,
        "equally ranked hits did not come back in the order the conversation held them");
  }

  // --- the tier ------------------------------------------------------------------

  /** A search stays in one tier, which is the boundary every other read of this archive keeps. */
  @Test
  void a_search_stays_in_its_tier() {
    String elsewhere = conversations.open(PAYMENTS, Budget.of(20)).id();
    entries.append(
        elsewhere, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));

    assertEquals(List.of(), entries.search(Home.global(), "budget", 0, 20).hits());
    assertEquals(1, entries.search(PAYMENTS, "budget", 0, 20).hits().size());
  }

  /**
   * A project nothing has ever been said in is an ordinary question with an empty answer, and never
   * the global tier's rows under another name.
   */
  @Test
  void a_project_nothing_was_said_in_answers_empty_and_not_the_global_tier() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));

    LogSearch found = entries.search(Home.of("ledger"), "budget", 0, 20);

    assertEquals(List.of(), found.hits());
    assertEquals(0, found.reach().searched());
  }

  // --- what a hit carries --------------------------------------------------------

  /**
   * <b>A hit shows the words around the match and not the opening of the entry.</b>
   *
   * <p>The difference is the whole point of a search: a {@code file_read} result is a hundred
   * thousand characters and the match is as likely to be at character ninety thousand as at the
   * first. An excerpt from the front would be a hit that never shows what was hit.
   */
  @Test
  void a_hit_shows_the_words_around_the_match() {
    entries.append(
        conversation,
        1,
        LoggedEntry.toolResult(
            "c1",
            "padding ".repeat(2000) + "the retry budget refilled " + "padding ".repeat(2000)));

    LogSearch.Hit hit = entries.search(Home.global(), "budget", 0, 20).hits().get(0);

    assertTrue(
        hit.snippet().contains("budget"),
        "the snippet does not contain the word that was searched for: " + hit.snippet());
    assertTrue(
        hit.snippet().length() <= EntryStore.MOST_CHARACTERS_PER_SNIPPET,
        "a snippet of " + hit.snippet().length() + " characters is past the bound");
    assertTrue(
        hit.length() > EntryStore.MOST_CHARACTERS_PER_SNIPPET,
        "the entry's real length did not travel beside the snippet");
  }

  /** A hit carries the address the whole result is redeemable at, for the one kind that has one. */
  @Test
  void a_hit_carries_the_handle_its_result_is_redeemable_at() {
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", "the retry budget refilled"));

    LogSearch.Hit hit = entries.search(Home.global(), "budget", 0, 20).hits().get(0);

    assertEquals(EntryKind.TOOL_RESULT, hit.kind());
    assertTrue(hit.handle() != null, "a tool result hit carries no handle to redeem it at");
    assertEquals(conversation, hit.conversationId());
  }

  // --- bounds --------------------------------------------------------------------

  /**
   * A page of no entries is not a page, and a negative offset is not a place to start. Both are the
   * caller's bug, as they are for {@code pageOfLog}.
   */
  @Test
  void a_search_refuses_a_window_that_is_not_a_page() {
    assertThrows(
        IllegalArgumentException.class, () -> entries.search(Home.global(), "budget", 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> entries.search(Home.global(), "budget", -1, 20));
  }

  /** The bound is the most that comes back, and the total still says how many there were. */
  @Test
  void the_bound_is_the_most_that_comes_back() {
    for (int i = 1; i <= 30; i++) {
      entries.append(
          conversation, i, LoggedEntry.utterance("budget number " + i, Speaker.person(null)));
    }

    assertEquals(20, entries.search(Home.global(), "budget", 0, 20).hits().size());
    assertEquals(10, entries.search(Home.global(), "budget", 20, 20).hits().size());
  }

  // --- the plan ------------------------------------------------------------------

  /**
   * <b>The index V23 built is the index this read reaches for.</b>
   *
   * <p><b>What this asserts and what it does not.</b> {@code enable_seqscan} is off, so the
   * question is which index <em>can</em> serve the read and not which the planner picks on a given
   * day — {@code DocumentStoreTest}'s plan checks take the same position and it is worth stating,
   * because the answer to the second question genuinely varies here: measured against 200 000
   * entries in 501 conversations, a search of the whole tier uses this index, and a search narrowed
   * to one conversation is served by the primary key alone, because 400 rows is sixteen heap blocks
   * and there is nothing for a GIN scan to improve on.
   */
  @Test
  void the_search_is_served_by_the_gin_index() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));

    String plan = explain(EntryStore.SEARCH_SQL);

    assertTrue(
        plan.contains("entries_by_text"),
        "the search is not using the GIN index V23 built for it:\n" + plan);
  }

  /**
   * <b>A second sort key costs this index nothing, which is why the tie is broken in SQL.</b>
   *
   * <p>V21's finding, re-measured on this table. A GIN index supplies the filter — a bitmap scan —
   * and the ranking is a {@code Sort} over whatever survives it, so the sort key is not the index's
   * business. An HNSW index supplies the order instead, which is why {@code
   * DocumentStore.SEARCH_SQL} may not do what this query does.
   */
  @Test
  void a_second_sort_key_costs_the_gin_index_nothing() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));

    String plan = explain(EntryStore.SEARCH_SQL);
    String withoutTheTieBreak =
        explain(
            EntryStore.SEARCH_SQL.replace(
                " ORDER BY rank DESC, e.conversation_id, e.turn_ordinal, e.ordinal",
                " ORDER BY rank DESC"));

    assertTrue(plan.contains("Bitmap Index Scan on entries_by_text"), plan);
    assertTrue(plan.contains("Sort"), plan);
    assertTrue(withoutTheTieBreak.contains("entries_by_text"), withoutTheTieBreak);
  }

  /**
   * <b>And the match predicate is what earns the index in the first place.</b>
   *
   * <p>Drop {@code text_search @@ q} and there is nothing left for a GIN index to serve: the read
   * is over the whole tier, ranked by a function that answers 0 for every entry the question does
   * not match. Nothing errors and rows come back, which is why this is a test.
   */
  @Test
  void without_the_match_predicate_the_read_loses_the_index_altogether() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));

    String plan = explain(EntryStore.SEARCH_SQL.replace("AND e.text_search @@ q\n", ""));

    assertFalse(
        plan.contains("entries_by_text"),
        "the match predicate is no longer what earns the GIN index, so SEARCH_SQL"
            + " could drop it after all:\n"
            + plan);
  }

  /**
   * The plan for one statement, with sequential scans taken away so that the question is which
   * index can serve the read.
   *
   * <p>{@code DocumentStoreTest.explainLexical}'s shape and its parameters, in {@code SEARCH_SQL}'s
   * order: the snippet's character bound, the question, the tier, then the window.
   */
  private String explain(String sql) {
    return String.join(
        "\n",
        jdbc.execute(
            (ConnectionCallback<List<String>>)
                connection -> {
                  try (var off = connection.createStatement()) {
                    off.execute("SET enable_seqscan = off");
                  }
                  try (var explain = connection.prepareStatement("EXPLAIN " + sql)) {
                    explain.setInt(1, EntryStore.MOST_CHARACTERS_PER_SNIPPET);
                    explain.setString(2, "budget");
                    explain.setObject(3, null, java.sql.Types.BIGINT);
                    explain.setInt(4, 0);
                    explain.setInt(5, 20);
                    List<String> lines = new ArrayList<>();
                    try (var rows = explain.executeQuery()) {
                      while (rows.next()) {
                        lines.add(rows.getString(1));
                      }
                    }
                    return lines;
                  }
                }));
  }
}
