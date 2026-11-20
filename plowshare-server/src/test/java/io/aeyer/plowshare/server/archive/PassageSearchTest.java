package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.information.InformationLogVisibility;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The stored-passage lexical search prototype, against real PostgreSQL.
 *
 * <p>Everything asserted here is PostgreSQL's behavior: the V277 trigger and splitter, the V278
 * triggers that keep postings and counts in step under concurrent writers, the foreign keys that
 * remove them with their entry or conversation, and the planner's use of the indexes. A mocked
 * repository can show none of it.
 *
 * <p>The comparisons with {@link EntryStore#search} are deliberately narrow. The two searches match
 * the same entries and give them the same rank to nine decimal places (not bit for bit: one adds a
 * score's parts from stored frequencies, the other from the entry's lexemes). Their pages can still
 * differ, because they group differently: the served search groups hits whose 60-word headline
 * reads the same, this one groups hits whose stored passage does. Two entries that differ outside
 * the headline are one group there and two here, and the page shifts. On 3,000 short random entries
 * a review found different pages for 54 of 600 questions. The tests below therefore compare with
 * the served search only on fixtures where no two entries share a headline without sharing a
 * passage, and compare the pruned routes with this prototype's own exhaustive route everywhere
 * else.
 */
@Tag("full-db")
@Testcontainers
class PassageSearchTest {

  /** The pgvector image because V1 creates the vector extension and the whole chain runs. */
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
    // The same closure LogSearchTest truncates; entry_search_passages follows entries by CASCADE.
    jdbc.execute(
        "TRUNCATE TABLE message_route_bindings, message_external_tasks, message_external_contexts, message_routes, message_instances, board_post_receipts, skill_executions, command_invocations, orchestration_start_receipts, orchestration_script_steps, board_reads, board_notices, board_decisions, digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, conversations, turns, compactions, entries, citations, orchestrations, orchestration_messages, board_topics, board_messages, board_seats, firings, user_inbox CASCADE");
    conversations = new ConversationStore(jdbc);
    entries = new EntryStore(jdbc);
    conversation = conversations.open(Home.global(), Budget.of(20)).id();
  }

  // --- the projection V277 built -------------------------------------------------

  /**
   * The offsets are the whole of what is stored, so they must rebuild the text exactly and no
   * passage may be unbounded, including across a run with no whitespace and multibyte characters.
   */
  @Test
  void passagesRebuildTheEntryExactlyAndStayBounded() {
    String text =
        "  leading space\n\n"
            + "ordinary prose with several words in every sentence. ".repeat(120)
            + "xé€𝄞".repeat(1500)
            + "\n\ttail words after the unbroken run";
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", text));

    assertEquals(
        text,
        jdbc.queryForObject(
            "SELECT string_agg(substr(e.content,p.start_at,p.span),'' ORDER BY p.position)"
                + " FROM entry_search_passages p JOIN entries e USING (conversation_id,ordinal)",
            String.class),
        "the stored offsets do not rebuild the entry, so an excerpt would quote the wrong text");
    assertTrue(
        jdbc.queryForObject("SELECT max(span) FROM entry_search_passages", Integer.class) <= 1900,
        "a passage exceeded its bound, so highlighting it is no longer bounded work");
    assertTrue(
        jdbc.queryForObject("SELECT count(*) FROM entry_search_passages", Integer.class) > 10);
  }

  /** The passage lexemes are stemmed by the configuration the question is parsed in. */
  @Test
  void passageLexemesAnswerAStemmedQuestion() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));

    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM entry_search_passages WHERE text_search @@"
                + " websearch_to_tsquery('"
                + EntryStore.TEXT_SEARCH_CONFIGURATION
                + "', 'refilling')",
            Integer.class));
  }

  /** Only what search reads is projected, and the projection follows ejection and deletion. */
  @Test
  void passagesFollowTheEntryThroughEjectionMetadataUpdatesAndDeletion() {
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", "the retry budget refilled"));
    entries.append(conversation, 1, LoggedEntry.diagnostic("compaction triggered"));
    entries.append(conversation, 1, LoggedEntry.toolResult("c2", "a second retained result"));
    assertEquals(2, passageCount(), "a recorded-only kind was projected, or a said one was not");
    assertEquals(2, entriesWithTerms(), "the postings do not cover the same entries");
    assertCountsAgreeWithARecount("after appends");

    jdbc.update("UPDATE entries SET learned_at = now() WHERE conversation_id = ?", conversation);
    assertEquals(2, passageCount(), "a metadata update rewrote or dropped passages");

    assertTrue(
        entries.ejectPayload(
            conversation, 1, Instant.parse("2026-09-04T10:00:00Z"), "/exports/one.jsonl"));
    assertEquals(1, passageCount(), "an ejected payload kept searchable passages");
    assertEquals(1, entriesWithTerms(), "an ejected payload kept postings");
    assertCountsAgreeWithARecount("after ejection");
    assertEquals(
        entries.search(Home.global(), "retained", 0, 10).reach(),
        entries.searchPassages(Home.global(), "retained", 0, 10, null).page().reach(),
        "the stored counts report a different reach than a read of the entries");

    jdbc.update("DELETE FROM entries WHERE conversation_id = ? AND ordinal = 3", conversation);
    assertEquals(0, passageCount(), "a passage outlived its entry");
    assertEquals(0, entriesWithTerms(), "a posting outlived its entry");
    assertCountsAgreeWithARecount("after deletion");
  }

  /**
   * Document frequency is read from stored per-conversation lexeme counts, so they must be the
   * number of that conversation's postings for the lexeme after every kind of change, with no row
   * left behind for a lexeme the conversation no longer holds.
   */
  @Test
  void lexemeCountsFollowThePostingsThroughEveryChange() {
    String other = conversations.open(Home.global(), Budget.of(9)).id();
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", "the retry budget refilled"));
    entries.append(conversation, 1, LoggedEntry.toolResult("c2", "a budget review was retained"));
    entries.append(conversation, 1, LoggedEntry.diagnostic("budget compaction triggered"));
    entries.append(
        other, 1, LoggedEntry.utterance("another budget entirely", Speaker.person(null)));
    assertCountsAgreeWithARecount("after appends");
    assertEquals(2, lexemeCount(conversation, "budget"), "two said entries hold the word");
    assertEquals(1, lexemeCount(other, "budget"), "counts ran together across conversations");
    assertEquals(1, lexemeCount(conversation, "retri"));

    jdbc.update(
        "UPDATE entries SET content = 'the quota refilled' WHERE conversation_id = ? AND ordinal = 1",
        conversation);
    assertCountsAgreeWithARecount("after a content update");
    assertEquals(1, lexemeCount(conversation, "budget"), "the rewritten entry kept its old word");
    assertEquals(0, lexemeCount(conversation, "retri"), "a count outlived its last posting");
    assertEquals(1, lexemeCount(conversation, "quota"));
    assertEquals(1, lexemeCount(conversation, "refil"));

    jdbc.update("UPDATE entries SET learned_at = now() WHERE conversation_id = ?", conversation);
    assertCountsAgreeWithARecount("after a metadata-only update");
    assertEquals(1, lexemeCount(conversation, "budget"), "a metadata update moved a count");

    assertTrue(
        entries.ejectPayload(
            conversation, 2, Instant.parse("2026-09-04T10:00:00Z"), "/exports/two.jsonl"));
    assertCountsAgreeWithARecount("after ejection");
    assertEquals(0, lexemeCount(conversation, "budget"), "an ejected payload is still counted");
    assertEquals(1, lexemeCount(other, "budget"), "ejection reached another conversation");

    jdbc.update("DELETE FROM entries WHERE conversation_id = ? AND ordinal = 1", conversation);
    assertCountsAgreeWithARecount("after deleting an entry");
    assertEquals(0, lexemeCount(conversation, "quota"));

    jdbc.update("DELETE FROM entries WHERE conversation_id = ?", other);
    jdbc.update("DELETE FROM conversations WHERE id = ?", other);
    assertCountsAgreeWithARecount("after deleting a conversation");
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM entry_search_term_counts k WHERE NOT EXISTS (SELECT 1 FROM"
                + " entry_search_conversations c JOIN conversations o ON o.id = c.conversation_id"
                + " WHERE c.conversation_no = k.conversation_no)",
            Integer.class),
        "a lexeme count outlived its conversation");

    entries.append(conversation, 1, LoggedEntry.toolResult("c3", "the budget came back"));
    assertEquals(1, lexemeCount(conversation, "budget"));
    // TRUNCATE fires no row trigger; the statement trigger must clear what the rows had counted.
    jdbc.execute("TRUNCATE TABLE entries CASCADE");
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM entry_search_term_counts", Integer.class),
        "a TRUNCATE of entries left lexeme counts behind");
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM entry_search_terms", Integer.class),
        "a TRUNCATE of entries left postings behind");
    assertCountsAgreeWithARecount("after TRUNCATE");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM entry_search_conversations WHERE conversation_id = ?",
            Integer.class,
            conversation),
        "a TRUNCATE of entries dropped the statistics row of a conversation that still exists");
  }

  /**
   * A role or provenance change alters what is searchable without touching the content, and a
   * conversation removed in the transaction that removed its entries takes counts that still exist
   * with it.
   */
  @Test
  void roleProvenanceAndSameTransactionRemovalKeepEveryProjectionInStep() {
    entries.append(
        conversation,
        1,
        LoggedEntry.utterance("copied context then the retry budget", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.diagnostic("compaction triggered"));
    assertEquals(1, lexemeCount(conversation, "context"));

    jdbc.update(
        "UPDATE entries SET input_provenance = '{\"start\":0,\"end\":14,\"sources\":[]}'::jsonb"
            + " WHERE conversation_id = ? AND ordinal = 1",
        conversation);
    assertCountsAgreeWithARecount("after a provenance update");
    assertEquals(0, lexemeCount(conversation, "context"), "supplied context is still counted");
    assertEquals(1, lexemeCount(conversation, "budget"));
    assertEquals(0, entries.searchPassages(Home.global(), "context", 0, 10, null).page().total());

    jdbc.update(
        "UPDATE entries SET input_provenance = NULL, role = NULL, speaker = NULL,"
            + " kind = (SELECT kind FROM entries WHERE conversation_id = ? AND ordinal = 2)"
            + " WHERE conversation_id = ? AND ordinal = 1",
        conversation,
        conversation);
    assertCountsAgreeWithARecount("after a role update");
    assertEquals(0, lexemeCount(conversation, "budget"), "an entry without a role is counted");
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT recorded_only FROM entry_search_conversations WHERE conversation_id = ?",
            Integer.class,
            conversation));

    String doomed = conversations.open(Home.global(), Budget.of(9)).id();
    entries.append(doomed, 1, LoggedEntry.utterance("a doomed budget", Speaker.person(null)));
    assertEquals(1, lexemeCount(doomed, "doom"));
    inOneTransaction(
        connection -> {
          try (var statement = connection.createStatement()) {
            statement.executeUpdate(
                "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
                    + " VALUES ('"
                    + doomed
                    + "', 2, 1, 'utterance', 'user', 'another doomed line')");
            statement.executeUpdate("DELETE FROM entries WHERE conversation_id = '" + doomed + "'");
            assertEquals(
                1,
                count(
                    connection,
                    "SELECT count(*) FROM entry_search_term_counts k JOIN"
                        + " entry_search_conversations c USING (conversation_no)"
                        + " WHERE c.conversation_id = '"
                        + doomed
                        + "' AND k.lexeme = 'doom'"),
                "the counts were not deferred, so this does not exercise the cascade");
            statement.executeUpdate("DELETE FROM conversations WHERE id = '" + doomed + "'");
          }
        });
    assertCountsAgreeWithARecount("after removing a conversation with its entries");
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM entry_search_term_counts k WHERE NOT EXISTS (SELECT 1 FROM"
                + " entry_search_conversations c WHERE c.conversation_no = k.conversation_no)",
            Integer.class),
        "a lexeme count outlived its conversation");
  }

  /**
   * Counts are deltas applied at commit, so several changes to one entry inside a transaction add
   * up, and a transaction that asks for its constraints at once still ends with the right numbers.
   */
  @Test
  void deferredCountsAddUpAcrossATransactionAndUnderImmediateConstraints() {
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", "the retry budget refilled"));
    for (boolean immediate : new boolean[] {false, true}) {
      int base = immediate ? 20 : 10;
      inOneTransaction(
          connection -> {
            try (var statement = connection.createStatement()) {
              if (immediate) statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
              String insert =
                  "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
                      + " VALUES ('"
                      + conversation
                      + "', %d, 1, 'utterance', 'user', '%s')";
              String where = " WHERE conversation_id = '" + conversation + "' AND ordinal = ";
              statement.executeUpdate(insert.formatted(base, "a budget for quota"));
              statement.executeUpdate(insert.formatted(base + 1, "a short lived budget line"));
              statement.executeUpdate(
                  "UPDATE entries SET content = 'a quota without the other word'" + where + base);
              statement.executeUpdate(
                  "UPDATE entries SET content = 'the quota again with a budget'" + where + base);
              statement.executeUpdate("DELETE FROM entries" + where + (base + 1));
            }
          });
      assertCountsAgreeWithARecount(immediate ? "with immediate constraints" : "when deferred");
      assertEquals(immediate ? 3 : 2, lexemeCount(conversation, "budget"));
      assertEquals(immediate ? 2 : 1, lexemeCount(conversation, "quota"));
      assertEquals(0, lexemeCount(conversation, "live"));
    }
  }

  /**
   * The lock order V278 exists to keep. A fold inserts a summary and then updates older entries; an
   * ejection updates one of those entries. While statistics were updated inside each entry write,
   * the fold held the conversation's statistics row and wanted the entry, the ejection held the
   * entry and wanted the statistics row, and one of them died with "deadlock detected".
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aFoldShapedWriterAndAConcurrentEjectionDoNotDeadlock() {
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", "the retry budget refilled"));
    try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
      inOneTransaction(
          fold -> {
            try (var statement = fold.createStatement()) {
              statement.executeUpdate(
                  "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
                      + " VALUES ('"
                      + conversation
                      + "', 2, 1, 'utterance', 'user', 'a summary of the budget')");
              Future<Boolean> ejection =
                  pool.submit(
                      () ->
                          entries.ejectPayload(
                              conversation,
                              1,
                              Instant.parse("2026-09-04T10:00:00Z"),
                              "/exports/one.jsonl"));
              try {
                // Long enough for an ejection that is going to block to be blocked.
                ejection.get(2, TimeUnit.SECONDS);
              } catch (TimeoutException blocked) {
                // The fold carries on, as it would; a deadlock then shows itself below.
              }
              statement.executeUpdate(
                  "UPDATE entries SET learned_at = now() WHERE conversation_id = '"
                      + conversation
                      + "' AND ordinal = 1");
              fold.commit();
              assertTrue(ejection.get(20, TimeUnit.SECONDS), "the ejection did not happen");
            } catch (InterruptedException | ExecutionException | TimeoutException failed) {
              throw new IllegalStateException(
                  "the fold and the ejection did not both finish", failed);
            }
          });
    }
    assertCountsAgreeWithARecount("after a fold beside an ejection");
    assertEquals(1, lexemeCount(conversation, "budget"));
  }

  /**
   * Two transactions that wrote to the same two conversations in opposite orders settle them in the
   * same order, lowest conversation number first, and so cannot wait for each other at commit.
   *
   * <p>The schedule is forced rather than hoped for. A third session holds the lower conversation's
   * statistics row as an update of its counts would (a stronger lock would stop the writers'
   * statements, which take a key-share lock on that row for their pending rows). The first writer
   * wrote low then high and its commit waits for that row. The second wrote high then low; settling
   * in statement order it would lock the higher row and then wait for the lower one too. Released,
   * the first writer would take the lower row and want the higher: a cycle, which PostgreSQL ends
   * by failing one commit with "deadlock detected" (this test, run against the settle step as it
   * was first written, fails that way). Settling in number order, the second writer holds nothing
   * while it waits, and both commit.
   */
  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void writersOfTheSameConversationsInOppositeOrdersDoNotDeadlockAtCommit() throws Exception {
    String other = conversations.open(Home.global(), Budget.of(9)).id();
    List<String> byNumber =
        jdbc.queryForList(
            "SELECT conversation_id FROM entry_search_conversations"
                + " WHERE conversation_id IN (?, ?) ORDER BY conversation_no",
            String.class,
            conversation,
            other);
    String low = byNumber.get(0);
    String high = byNumber.get(1);
    List<Future<?>> commits = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      inOneTransaction(
          holder -> {
            try (var statement = holder.createStatement()) {
              statement.execute(
                  "SELECT 1 FROM entry_search_conversations WHERE conversation_id = '"
                      + low
                      + "' FOR NO KEY UPDATE");
              commits.add(pool.submit(() -> writeToBoth(low, high, 1)));
              awaitCommitsWaitingForALock(1);
              commits.add(pool.submit(() -> writeToBoth(high, low, 2)));
              awaitCommitsWaitingForALock(2);
            }
          });
      for (Future<?> commit : commits) commit.get(30, TimeUnit.SECONDS);
    }
    assertCountsAgreeWithARecount("after two writers of the same two conversations");
    assertEquals(2, lexemeCount(low, "share"));
    assertEquals(2, lexemeCount(high, "share"));
  }

  /** One transaction that appends an entry to each conversation, in the order given. */
  private void writeToBoth(String first, String second, int ordinal) {
    inOneTransaction(
        connection -> {
          try (var statement = connection.createStatement()) {
            for (String id : List.of(first, second)) {
              statement.executeUpdate(
                  "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
                      + " VALUES ('"
                      + id
                      + "', "
                      + ordinal
                      + ", 1, 'utterance', 'user', 'shared words from writer"
                      + ordinal
                      + "')");
            }
          }
        });
  }

  /** Waits until that many sessions of this database are blocked on a lock, or fails. */
  private static void awaitCommitsWaitingForALock(int sessions) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      Integer waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                  + " AND wait_event_type = 'Lock'",
              Integer.class);
      if (waiting != null && waiting >= sessions) return;
      try {
        TimeUnit.MILLISECONDS.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for a blocked commit");
      }
    }
    throw new IllegalStateException(sessions + " commits were expected to be waiting for a lock");
  }

  /**
   * The two row triggers are fired by an UPDATE's SET list, not by a comparison of the old and new
   * text, so a metadata update never reads an entry's stored text. This proves the list misses no
   * change: every statement that names content, role, provenance or (for the postings) the key
   * fires them, a statement that names none of those fires neither, and nothing but a statement's
   * SET list can change those columns, because no BEFORE UPDATE trigger on {@code entries} assigns
   * one of them. The last is checked against the catalog, so that a trigger added later fails here
   * instead of silently leaving a projection behind.
   */
  @Test
  void onlyAStatementThatNamesWhatSearchReadsFiresTheRowTriggers() throws IOException {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));
    List<String> none = List.of();
    List<String> both = List.of("entry_search_passages", "entry_search_terms");

    assertEquals(none, rowTriggersFiredBy("UPDATE entries SET learned_at = now()"));
    assertEquals(
        none, rowTriggersFiredBy("UPDATE entries SET recorded_at = now(), took_ms = NULL"));
    assertEquals(both, rowTriggersFiredBy("UPDATE entries SET content = 'the quota refilled'"));
    assertEquals(both, rowTriggersFiredBy("UPDATE entries SET role = role"));
    assertEquals(
        both, rowTriggersFiredBy("UPDATE entries SET input_provenance = input_provenance"));
    assertEquals(
        List.of("entry_search_terms"),
        rowTriggersFiredBy("UPDATE entries SET ordinal = ordinal + 10"),
        "the passages follow a changed key by foreign key; the postings need their trigger");
    assertEquals(
        both,
        rowTriggersFiredBy(
            "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
                + " SELECT conversation_id, ordinal, 1, 'utterance', 'user', 'unused' FROM entries"
                + " ON CONFLICT (conversation_id, ordinal)"
                + " DO UPDATE SET content = 'a ceiling was raised'"));
    assertCountsAgreeWithARecount("after updates through every listed column");
    assertEquals(1, entries.searchPassages(Home.global(), "ceiling", 0, 10, null).page().total());
    assertEquals(0, entries.searchPassages(Home.global(), "quota", 0, 10, null).page().total());

    assertEquals(
        List.of("content", "input_provenance", "role"), triggerColumns("entry_search_passages"));
    assertEquals(
        List.of("content", "conversation_id", "input_provenance", "ordinal", "role"),
        triggerColumns("entry_search_terms"));
    // tgtype: 1 is FOR EACH ROW, 2 is BEFORE, 16 is UPDATE.
    List<String> assigning =
        jdbc.queryForList(
            "SELECT t.tgname FROM pg_trigger t JOIN pg_proc f ON f.oid = t.tgfoid"
                + " WHERE t.tgrelid = 'entries'::regclass AND NOT t.tgisinternal"
                + " AND t.tgtype & 19 = 19"
                + " AND f.prosrc ~* 'new\\.(conversation_id|ordinal|content|role|input_provenance)\\s*:='",
            String.class);
    assertEquals(
        none,
        assigning,
        "a BEFORE UPDATE trigger on entries assigns a column search reads without the statement"
            + " naming it; the UPDATE OF lists of V277 and V278 no longer see every change");
    assertEquals(
        List.of("trajectory_image_ejection"),
        jdbc.queryForList(
            "SELECT tgname FROM pg_trigger WHERE tgrelid = 'entries'::regclass"
                + " AND NOT tgisinternal AND tgtype & 19 = 19 ORDER BY tgname",
            String.class),
        "the BEFORE UPDATE triggers on entries changed; check that none assigns content, role,"
            + " input_provenance or the key, then update this list");
  }

  /** The search row triggers on {@code entries} that one statement fired, in name order. */
  private List<String> rowTriggersFiredBy(String statement) throws IOException {
    JsonNode explained =
        new ObjectMapper()
            .readTree(
                jdbc.queryForObject("EXPLAIN (ANALYZE, FORMAT JSON) " + statement, String.class))
            .get(0);
    Set<String> fired = new TreeSet<>();
    for (JsonNode trigger : explained.path("Triggers")) {
      String name = trigger.path("Trigger Name").asText();
      if (name.equals("entry_search_passages") || name.equals("entry_search_terms")) {
        fired.add(name);
      }
    }
    return List.copyOf(fired);
  }

  private List<String> triggerColumns(String trigger) {
    return jdbc.queryForList(
        "SELECT a.attname::text FROM pg_trigger t JOIN pg_attribute a"
            + " ON a.attrelid = t.tgrelid AND a.attnum = ANY (t.tgattr)"
            + " WHERE t.tgrelid = 'entries'::regclass AND t.tgname = ? ORDER BY a.attname",
        String.class,
        trigger);
  }

  /**
   * An entry whose key changes takes its passages, postings and counts with it, whether or not it
   * is searchable and whether or not the same update rewrites it.
   */
  @Test
  void anEntryThatMovesOrIsRenumberedTakesItsProjectionsAlong() {
    String other = conversations.open(Home.global(), Budget.of(9)).id();
    entries.append(conversation, 1, LoggedEntry.diagnostic("compaction triggered"));
    entries.append(conversation, 1, LoggedEntry.toolResult("c2", "the retry budget refilled"));
    entries.append(conversation, 1, LoggedEntry.toolResult("c3", "a quota was retained"));
    entries.append(other, 1, LoggedEntry.utterance("another budget", Speaker.person(null)));

    jdbc.update(
        "UPDATE entries SET conversation_id = ?, ordinal = 7 WHERE conversation_id = ? AND ordinal = 1",
        other,
        conversation);
    assertCountsAgreeWithARecount("after moving a recorded-only entry");

    jdbc.update(
        "UPDATE entries SET ordinal = 9 WHERE conversation_id = ? AND ordinal = 2", conversation);
    assertCountsAgreeWithARecount("after renumbering a searchable entry");
    assertEquals(
        9,
        entries
            .searchPassages(Home.global(), "retry", 0, 10, null)
            .page()
            .hits()
            .getFirst()
            .ordinal());

    jdbc.update(
        "UPDATE entries SET conversation_id = ?, ordinal = 8, content = 'a quota moved away'"
            + " WHERE conversation_id = ? AND ordinal = 3",
        other,
        conversation);
    assertCountsAgreeWithARecount("after moving a searchable entry with new content");
    assertEquals(0, lexemeCount(conversation, "quota"), "the old conversation kept the count");
    assertEquals(1, lexemeCount(other, "quota"));
    assertEquals(0, lexemeCount(other, "retain"), "the replaced text is still counted");
    var moved = entries.searchPassages(Home.global(), "quota", 0, 10, null).page();
    assertEquals(1, moved.total());
    assertEquals(other, moved.hits().getFirst().conversationId());
    assertTrue(moved.hits().getFirst().snippet().contains("[quota]"));
    assertEquals(3, passageCount(), "a passage was lost or left under the old key");
  }

  /**
   * A count that no longer describes the postings refuses the change that would build on it,
   * instead of being corrected silently or carried forward.
   */
  @Test
  void aCountThatDisagreesWithThePostingsRefusesTheNextRemoval() {
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", "the retry budget refilled"));
    entries.append(conversation, 1, LoggedEntry.toolResult("c2", "a budget review"));
    jdbc.update("DELETE FROM entry_search_term_counts WHERE lexeme = 'retri'");

    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "DELETE FROM entries WHERE conversation_id = ? AND ordinal = 1", conversation),
        "an entry was removed against a lexeme count that does not exist");
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE conversation_id = ?", Integer.class, conversation),
        "the refused removal was not rolled back");
    assertEquals(2, lexemeCount(conversation, "budget"), "the refused removal moved a count");

    jdbc.update(
        "UPDATE entry_search_conversations SET searched = 0 WHERE conversation_id = ?",
        conversation);
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "DELETE FROM entries WHERE conversation_id = ? AND ordinal = 2", conversation),
        "a statistic was allowed to go negative");
  }

  /**
   * An entry large enough to have more passages than a smallint can number must still be writable.
   * Reaching passage 32,768 honestly takes thirty million characters; renumbering a stored passage
   * and moving the entry runs the same trigger statement over the same value in milliseconds.
   */
  @Test
  void aPassagePositionBeyondSmallintDoesNotRefuseTheEntry() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the quokka crossed", Speaker.person(null)));
    jdbc.update("UPDATE entry_search_passages SET position = 40000");

    jdbc.update(
        "UPDATE entries SET ordinal = 5 WHERE conversation_id = ? AND ordinal = 1", conversation);

    assertEquals(
        40000,
        jdbc.queryForObject(
            "SELECT best_position FROM entry_search_terms WHERE lexeme = 'quokka'", Integer.class));
    var hit = entries.searchPassages(Home.global(), "quokka", 0, 10, null).page().hits().getFirst();
    assertEquals(5, hit.ordinal());
    assertTrue(hit.snippet().contains("[quokka]"), hit.snippet());
  }

  /**
   * Writers to one conversation are serialised by its statistics row, so concurrent appends,
   * rewrites and deletions of entries sharing words must neither deadlock nor lose a count.
   */
  @Test
  void concurrentWritersToOneConversationLoseNoCount() throws Exception {
    int writers = 6;
    int each = 40;
    List<Callable<Void>> work = new ArrayList<>();
    for (int w = 0; w < writers; w++) {
      int writer = w;
      work.add(
          () -> {
            for (int i = 0; i < each; i++) {
              int ordinal = 1 + writer * each + i;
              jdbc.update(
                  "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
                      + " VALUES (?, ?, 1, 'utterance', 'user', ?)",
                  conversation,
                  ordinal,
                  "shared marker from writer" + writer + " step" + i % 5);
              if (i % 4 == 1) {
                jdbc.update(
                    "UPDATE entries SET content = 'shared rewritten text' WHERE conversation_id = ?"
                        + " AND ordinal = ?",
                    conversation,
                    ordinal - 1);
              }
              if (i % 4 == 3) {
                jdbc.update(
                    "DELETE FROM entries WHERE conversation_id = ? AND ordinal = ?",
                    conversation,
                    ordinal - 1);
              }
            }
            return null;
          });
    }
    try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
      for (Future<Void> done : pool.invokeAll(work)) done.get();
    }

    int remaining = writers * each - writers * (each / 4);
    assertCountsAgreeWithARecount("after concurrent writers");
    assertEquals(remaining, lexemeCount(conversation, "share"), "a concurrent write lost a count");
    assertEquals(
        entries.search(Home.global(), "marker", 0, 5).hits().getFirst().rank(),
        entries.searchPassages(Home.global(), "marker", 0, 5, null).page().hits().getFirst().rank(),
        1e-9,
        "the stored document frequency is not the one a count of matches gives");
  }

  // --- what the prototype keeps --------------------------------------------------

  /**
   * Seven entries, each short enough to be one passage and to fit whole in a headline, so that both
   * searches group them alike. On this fixture the two agree on entries, order, counts, sources and
   * snippets, and on ranks to nine decimal places, across each kind of web-search question. It says
   * nothing about archives where a headline is shorter than the entry: see the class comment for
   * how pages differ there.
   */
  @Test
  void sevenOnePassageEntriesAgreeWithTheServedSearchToNineDecimals() {
    List<String> said =
        List.of(
            "the retry budget refilled after the outage",
            "retry policy changed to exponential backoff",
            "the outage began when the budget ran out",
            "exponential backoff with jitter and a retry budget",
            "unrelated notes about the release train",
            "the retry budget refilled after the outage",
            "a budget review found no outage this quarter");
    for (int i = 0; i < said.size(); i++) {
      String id = i % 2 == 0 ? conversation : conversations.open(Home.global(), Budget.of(9)).id();
      entries.append(id, 1, LoggedEntry.utterance(said.get(i), Speaker.person(null)));
    }

    for (String question :
        List.of(
            "budget",
            "retry budget",
            "\"retry budget\"",
            "budget -outage",
            "backoff OR release",
            "-budget",
            "the and of",
            "nosuchword")) {
      LogSearch current = entries.search(Home.global(), question, 0, 50);
      BoundedLogSearch passages = entries.searchPassages(Home.global(), question, 0, 50, null);
      assertFalse(passages.truncated(), question);
      assertEquals(current.total(), passages.page().total(), question);
      assertEquals(current.reach(), passages.page().reach(), question);
      assertEquals(comparable(current.hits()), comparable(passages.page().hits()), question);
    }
    assertEquals(
        comparable(entries.search(Home.global(), "budget", 2, 2).hits()),
        comparable(entries.searchPassages(Home.global(), "budget", 2, 2, null).page().hits()),
        "a later page begins somewhere else");
  }

  /** Copies collapse before paging and cite at most twenty sources, as they do today. */
  @Test
  void copiesGroupBeforePagingAndCiteAtMostTwentySources() {
    for (int i = 0; i < 30; i++) {
      String id = conversations.open(Home.global(), Budget.of(2)).id();
      entries.append(
          id,
          1,
          LoggedEntry.utterance("workflow launch capacity boilerplate", Speaker.person(null)));
    }
    String useful = conversations.open(Home.global(), Budget.of(2)).id();
    entries.append(
        useful, 1, LoggedEntry.utterance("rareincident explanation", Speaker.person(null)));

    var found = entries.searchPassages(Home.global(), "capacity OR rareincident", 0, 1, null);
    assertFalse(found.truncated(), "a corpus this small is searched whole");
    assertEquals(2, found.page().total());
    assertEquals(useful, found.page().hits().getFirst().conversationId());
    var copies =
        entries.searchPassages(Home.global(), "capacity", 0, 1, null).page().hits().getFirst();
    assertEquals(30, copies.sourceCount());
    assertEquals(20, copies.sources().size());
    assertEquals(
        20, copies.sources().stream().map(LogSearch.Source::conversationId).distinct().count());
    assertTrue(
        entries.searchPassages(Home.global(), "capacity", 1, 1, null).page().hits().isEmpty());
  }

  /** A shared long opening is one group even when what follows it differs. */
  @Test
  void aSharedOpeningGroupsLongEntriesWhoseRemainderDiffers() {
    String boilerplate =
        "workflow launch returns a run handle and retains the report revision. ".repeat(1000);
    for (String suffix : List.of("alpha", "bravo", "charlie")) {
      String id = conversations.open(Home.global(), Budget.of(2)).id();
      entries.append(
          id,
          1,
          LoggedEntry.utterance(boilerplate + "Distinct footer " + suffix, Speaker.person(null)));
    }

    var found = entries.searchPassages(Home.global(), "workflow launch", 0, 10, null).page();
    assertEquals(1, found.total());
    assertEquals(3, found.hits().getFirst().sourceCount());
    assertEquals(3, found.hits().getFirst().sources().size());
    assertEquals(
        entries.search(Home.global(), "workflow launch", 0, 10).hits().getFirst().rank(),
        found.hits().getFirst().rank(),
        1e-9);
  }

  // --- what the prototype changes ------------------------------------------------

  /** The excerpt of a long entry is the passage holding the match, however deep it is. */
  @Test
  void aMatchDeepInALongEntryIsShownFromItsOwnPassage() {
    String filler = "ordinary filler sentence about nothing in particular. ".repeat(2000);
    entries.append(
        conversation,
        1,
        LoggedEntry.toolResult("c1", filler + "the quokka crossed the road. " + filler));

    var hit = entries.searchPassages(Home.global(), "quokka", 0, 10, null).page().hits().getFirst();

    assertTrue(hit.snippet().contains("[quokka]"), hit.snippet());
    assertTrue(hit.snippet().length() <= EntryStore.MOST_CHARACTERS_PER_SNIPPET, hit.snippet());
    assertTrue(hit.length() > 200_000, "the hit lost the length of the entry it came from");
    assertEquals(
        entries.search(Home.global(), "quokka", 0, 10).hits().getFirst().sourceRevision(),
        hit.sourceRevision());
  }

  /**
   * Matching stays entry-level, so a phrase cut by a passage boundary is still found; its excerpt
   * shows one side of the cut.
   */
  @Test
  void aPhraseAcrossAPassageBoundaryStillMatches() {
    String text = "a".repeat(893) + " zebra " + "crossing ahead of the " + "b".repeat(900);
    entries.append(conversation, 1, LoggedEntry.utterance(text, Speaker.person(null)));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(DISTINCT position) FROM entry_search_passages WHERE text_search @@"
                + " to_tsquery('english','zebra | crossing')",
            Integer.class),
        "the fixture no longer splits the phrase, so this test proves nothing");

    var found = entries.searchPassages(Home.global(), "\"zebra crossing\"", 0, 10, null).page();

    assertEquals(1, found.total());
    String snippet = found.hits().getFirst().snippet();
    assertTrue(snippet.contains("[zebra]") || snippet.contains("[crossing]"), snippet);
  }

  /** A gap in the projection costs time, never a hit. */
  @Test
  void anEntryWithoutPassagesIsStillFoundWithAWholeEntryExcerpt() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));
    jdbc.update("DELETE FROM entry_search_passages");

    var found = entries.searchPassages(Home.global(), "budget", 0, 10, null).page();

    assertEquals(1, found.total());
    assertTrue(found.hits().getFirst().snippet().contains("[budget]"));
  }

  /**
   * The point of the best-first postings: a single-word search skips postings that cannot reach the
   * page, and the page is still exactly the one an exhaustive search returns.
   *
   * <p>Three thousand distinct entries hold the word between one and seven times in bodies of
   * different lengths, so frequency and length pull the score in different directions in different
   * directions. The current search scores all of them.
   *
   * <p>The served search is only the oracle here and gets a long budget: it headlines all three
   * thousand matches, which took more than its two-second default on a slow build host. The pruned
   * search keeps the default budget.
   */
  @Test
  void aPrunedSingleWordSearchReturnsExactlyTheExhaustivePage() {
    jdbc.update(
        "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
            + " SELECT ?, n, 1 + n % 5, 'utterance', 'user',"
            + " repeat('marker ', 1 + n % 7) || 'unique' || n || ' ' || repeat('filler' || n % 13 || ' ', (n * 37) % 211)"
            + " FROM generate_series(1, 3000) n",
        conversation);

    EntryStore oracle = new EntryStore(jdbc).useSearchBudget(Duration.ofSeconds(30));
    for (int[] window : new int[][] {{0, 10}, {10, 10}, {0, 50}}) {
      LogSearch exhaustive = oracle.search(Home.global(), "marker", window[0], window[1]);
      BoundedLogSearch pruned =
          entries.searchPassages(Home.global(), "marker", window[0], window[1], null);

      assertEquals(
          positionsAndRanks(exhaustive.hits()),
          positionsAndRanks(pruned.page().hits()),
          "skipping postings changed the page at offset " + window[0]);
      assertTrue(pruned.truncated(), "no posting was skipped, so this test proves nothing");
      assertTrue(
          pruned.page().total() <= 2 * (window[0] + window[1]),
          "the pruned search read far more than the page needs: " + pruned.page().total());
    }
  }

  /**
   * A multi-word search scores every match and groups only the best. More matches than that window
   * are reported, not silently dropped.
   */
  @Test
  void moreMatchesThanTheGroupingWindowAreReportedAsTruncated() {
    jdbc.update(
        "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
            + " SELECT ?, n, 1, 'utterance', 'user', 'common marker text'"
            + " FROM generate_series(1, ?) n",
        conversation,
        EntryStore.MOST_GROUPED_ENTRIES + 5);

    var found = entries.searchPassages(Home.global(), "common marker", 0, 10, null);

    assertTrue(found.truncated());
    assertEquals(1, found.page().total());
    assertEquals(EntryStore.MOST_GROUPED_ENTRIES, found.page().hits().getFirst().sourceCount());
    assertEquals(EntryStore.MOST_GROUPED_ENTRIES + 5, found.page().reach().searched());
  }

  /** A word every probed entry repeats gives no threshold, so every copy is read and counted. */
  @Test
  void copiesBeyondTheProbeAreStillCountedWhenThereIsNoThreshold() {
    jdbc.update(
        "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
            + " SELECT ?, n, 1, 'utterance', 'user', 'common marker text'"
            + " FROM generate_series(1, 1500) n",
        conversation);

    var found = entries.searchPassages(Home.global(), "marker", 0, 10, null);

    assertFalse(found.truncated());
    assertEquals(1, found.page().total());
    assertEquals(1500, found.page().hits().getFirst().sourceCount());
  }

  /**
   * Every route stays within its reads in each state the planner can be in, on a corpus where a
   * wrong index is unaffordable: sixty thousand entries in one conversation, a word in half of
   * them. The states are statistics taken at three entries (what a migration or an early autovacuum
   * leaves), the new tables believed empty, no statistics at all, fresh statistics, and a generic
   * plan.
   *
   * <p>Rows returned by a scan say nothing about index tuples stepped over inside an index
   * condition, which is how a probe for one posting once walked every posting of its word. So the
   * measure is buffers touched and time, as PostgreSQL reports them for the statement the
   * repository actually runs.
   *
   * <p>The "believed empty" state is applied to the tables this search owns. Applied to {@code
   * entries} as well, a key lookup of one entry can be answered through {@code entries_recorded},
   * which reads the conversation's every entry: that table's indexes are not this migration's to
   * change, and the served search has the same exposure.
   */
  @Test
  @Timeout(value = 900, unit = TimeUnit.SECONDS)
  void everyRouteStaysWithinItsReadsWhateverThePlannerKnows() throws IOException {
    List<String> owned =
        List.of(
            "entry_search_passages",
            "entry_search_terms",
            "entry_search_term_counts",
            "entry_search_conversations",
            "entry_search_pending");
    List<String> all = new ArrayList<>(owned);
    all.add("entries");
    record Asked(String question, int skip, int most) {}
    List<Asked> questions =
        List.of(
            new Asked("marker", 0, 10),
            new Asked("marker", 0, 200),
            new Asked("marker OR filler3", 0, 10),
            new Asked("marker OR filler3", 0, 200),
            new Asked("marker filler3", 0, 10),
            new Asked("marker filler3", 0, 200),
            new Asked("\"filler3 filler3\" marker", 0, 10),
            new Asked("filler3 -marker", 0, 10));
    Map<String, List<String>> pages = new LinkedHashMap<>();
    try {
      for (String table : all) {
        jdbc.execute("ALTER TABLE " + table + " SET (autovacuum_enabled = false)");
      }
      for (String said : List.of("first marker note", "second budget line", "third quota item")) {
        entries.append(conversation, 1, LoggedEntry.utterance(said, Speaker.person(null)));
      }
      for (String table : all) jdbc.execute("ANALYZE " + table);
      for (int from = 11; from < 60011; from += 5000) {
        jdbc.update(
            "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
                + " SELECT ?, n, 1, 'utterance', 'user',"
                + " CASE WHEN n % 2 = 0 THEN repeat('marker ', 1 + (n / 2) % 7) ELSE '' END"
                + " || 'unique' || n || ' ' || repeat('filler' || n % 13 || ' ', (n * 37) % 211)"
                + " FROM generate_series(?, ?) n",
            conversation, from, from + 4999);
      }
      assertEquals(
          30001,
          jdbc.queryForObject(
              "SELECT count(*) FROM entry_search_terms WHERE lexeme = 'marker'", Integer.class));

      for (String state :
          List.of(
              "stale statistics",
              "new tables believed empty",
              "no statistics",
              "analyzed",
              "generic plan")) {
        if (state.equals("new tables believed empty")) {
          // What a vacuum of an emptied table leaves until the next one: pages, and no rows.
          for (String table : owned) {
            jdbc.update("DELETE FROM pg_statistic WHERE starelid = ?::regclass", table);
            jdbc.update(
                "UPDATE pg_class SET reltuples = 0, relpages = 1 WHERE oid = ?::regclass", table);
          }
        }
        if (state.equals("no statistics")) {
          // What a bulk load into new tables leaves before autovacuum reaches them.
          for (String table : all) {
            jdbc.update("DELETE FROM pg_statistic WHERE starelid = ?::regclass", table);
            jdbc.update(
                "UPDATE pg_class SET reltuples = -1, relpages = 0 WHERE oid = ?::regclass", table);
          }
        }
        if (state.equals("analyzed")) {
          for (String table : all) jdbc.execute("ANALYZE " + table);
        }
        for (Asked asked : questions) {
          String what = asked.question() + " at 0+" + asked.most() + " with " + state;
          JsonNode root =
              state.equals("generic plan")
                  ? explained(asked.question(), null, asked.skip(), asked.most(), true, false)
                  : new ObjectMapper()
                      .readTree(
                          entries.explainPassages(
                              Home.global(), asked.question(), asked.skip(), asked.most(), null))
                      .get(0);
          long buffers = buffers(root.get("Plan"));
          assertTrue(
              buffers <= MOST_BUFFERS_FOR_ONE_SEARCH,
              what + " touched " + buffers + " buffers:\n" + root);
          assertTrue(
              root.path("Execution Time").asDouble() < 1500,
              what + " took " + root.path("Execution Time").asDouble() + " ms:\n" + root);
          // The search itself, under its real two-second budget.
          BoundedLogSearch found =
              entries.searchPassages(
                  Home.global(), asked.question(), asked.skip(), asked.most(), null);
          pages.put(what, page(found.page().hits()));
        }
      }
      for (Asked asked : questions) {
        List<String> exhaustive =
            page(
                entries
                    .searchPassagesExhaustively(
                        Home.global(), asked.question(), asked.skip(), asked.most(), null)
                    .page()
                    .hits());
        assertFalse(exhaustive.isEmpty(), asked.question());
        pages.forEach(
            (what, found) -> {
              if (what.startsWith(asked.question() + " at 0+" + asked.most() + " with "))
                assertEquals(exhaustive, found, what);
            });
      }
    } finally {
      for (String table : all) {
        jdbc.execute("ALTER TABLE " + table + " RESET (autovacuum_enabled)");
      }
    }
  }

  /**
   * The same routes when the reader may see 300,000 conversations and the planner knows it.
   *
   * <p>This is the state the single-conversation fixture above cannot reach. Visibility used to be
   * tested as {@code x IN (SELECT ... FROM visible)}, which PostgreSQL hashes only while it
   * estimates the set to fit in {@code work_mem}: about 262,000 conversation numbers. With more,
   * correctly estimated, each test scanned the whole set once per posting, and a single-word search
   * of this fixture ran for seconds and was cancelled. A project's reader is used because the
   * global reader's set was then estimated at one row and stayed hashed by accident. Visibility is
   * now a bit read from a value built once, so the measures are the ones above (buffers, time, the
   * exhaustive page) plus one of its own: the visible set is read once wherever it is used, never
   * once per row.
   *
   * <p>The entries sit in four conversations whose numbers are spread across the 300,000, and one
   * of those is then hidden from an information-scoped reader, so the bitmap is exercised at both
   * ends and with a hole in it.
   */
  @Test
  @Timeout(value = 900, unit = TimeUnit.SECONDS)
  void everyRouteStaysWithinItsReadsWhenVeryManyConversationsAreVisible() throws IOException {
    String first = conversations.open(PAYMENTS, Budget.of(20)).id();
    Long project =
        jdbc.queryForObject("SELECT project_id FROM conversations WHERE id = ?", Long.class, first);
    jdbc.update(
        "INSERT INTO conversations(id, created_at, origin, budget_total, budget_spent, project_id)"
            + " SELECT 'crowd' || n, now(), 'turn', 20, 0, ? FROM generate_series(1, 300000) n",
        project);
    String[] holders = {first, "crowd100000", "crowd200000", "crowd300000"};
    jdbc.update(
        "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
            + " SELECT (?::text[])[1 + n % 4], n, 1, 'utterance', 'user',"
            + " CASE WHEN n % 2 = 0 THEN repeat('marker ', 1 + (n / 2) % 7) ELSE '' END"
            + " || 'unique' || n || ' ' || repeat('filler' || n % 13 || ' ', (n * 37) % 211)"
            + " FROM generate_series(1, 20000) n",
        (Object) holders);
    for (String table :
        List.of(
            "conversations",
            "entries",
            "entry_search_conversations",
            "entry_search_passages",
            "entry_search_terms",
            "entry_search_term_counts")) jdbc.execute("ANALYZE " + table);
    assertEquals(
        300001,
        jdbc.queryForObject(
            "SELECT count(*) FROM entry_search_conversations k JOIN conversations c"
                + " ON c.id = k.conversation_id WHERE c.project_id = ?",
            Integer.class,
            project));

    List<String> questions =
        List.of(
            "marker",
            "marker OR filler3",
            "marker filler3",
            "\"filler3 filler3\" marker",
            "filler3 -marker");
    for (String question : questions) {
      List<String> exhaustive =
          page(entries.searchPassagesExhaustively(PAYMENTS, question, 0, 10, null).page().hits());
      assertFalse(exhaustive.isEmpty(), question);
      for (boolean generic : new boolean[] {false, true}) {
        String what = question + (generic ? " as a generic plan" : "");
        JsonNode root =
            generic
                ? explained(question, project, null, 0, 10, true, false)
                : new ObjectMapper()
                    .readTree(entries.explainPassages(PAYMENTS, question, 0, 10, null))
                    .get(0);
        long buffers = buffers(root.get("Plan"));
        assertTrue(
            buffers <= MOST_BUFFERS_FOR_ONE_SEARCH,
            what + " touched " + buffers + " buffers:\n" + root);
        assertTrue(
            mostLoopsOverStep(root.get("Plan"), "seen") <= 1,
            what + " read the visible set once per row:\n" + root);
      }
      // The search itself, under its real two-second budget.
      BoundedLogSearch found = entries.searchPassages(PAYMENTS, question, 0, 10, null);
      assertEquals(exhaustive, page(found.page().hits()), question);
      assertEquals(20000, found.page().reach().searched(), question);
    }

    String hidden = holders[2];
    var visibility =
        new InformationLogVisibility() {
          public <T> T read(String account, Function<Facts, T> reader) {
            return reader.apply(new Facts(Optional.of(List.of(hidden))));
          }

          public boolean readable(String account, String log, BooleanSupplier authoritative) {
            return !log.equals(hidden);
          }
        };
    var scoped = entries.useVisibility(visibility).forAccount("reader");
    for (String question : questions) {
      BoundedLogSearch exhaustive =
          scoped.searchPassagesExhaustively(PAYMENTS, question, 0, 10, null);
      BoundedLogSearch found = scoped.searchPassages(PAYMENTS, question, 0, 10, null);
      assertEquals(page(exhaustive.page().hits()), page(found.page().hits()), question);
      assertEquals(15000, found.page().reach().searched(), question);
      assertFalse(found.page().hits().isEmpty(), question);
      for (LogSearch.Hit hit : found.page().hits()) {
        assertNotEquals(hidden, hit.conversationId(), "a hidden entry is a hit for " + question);
        for (LogSearch.Source source : hit.sources()) {
          assertNotEquals(hidden, source.conversationId(), "a hidden entry is cited");
        }
      }
    }
  }

  /** The most times any one read of a named step of the statement was repeated. */
  private static long mostLoopsOverStep(JsonNode node, String step) {
    long most =
        "CTE Scan".equals(node.path("Node Type").asText())
                && step.equals(node.path("CTE Name").asText())
            ? node.path("Actual Loops").asLong()
            : 0;
    for (JsonNode child : node.path("Plans")) most = Math.max(most, mostLoopsOverStep(child, step));
    return most;
  }

  /**
   * Five times what the widest of these searches was measured to touch when planned well (78,000,
   * the same in every planner state), and far less than one wrong index costs: a probe that walks a
   * word's thirty thousand postings touches over a hundred pages, and a search makes thousands of
   * probes.
   */
  private static final long MOST_BUFFERS_FOR_ONE_SEARCH = 400_000;

  // --- multi-word pruning --------------------------------------------------------

  /**
   * Distinct entries in six conversations: {@code alpha} in half (one to four times), {@code beta}
   * in a third (one to three times), both adjacent in a sixth, {@code delta} in three fifths,
   * {@code gamma} in one in ninety-seven, and bodies of different lengths so frequency and length
   * disagree. At 24,000 entries the conjunctions of the common words are expected to match more
   * than the grouping window, so they are pruned; with {@code gamma} they are searched whole.
   */
  private void loadMixedCorpus(int count) {
    List<String> ids = openConversations(6);
    jdbc.update(
        "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
            + " SELECT (?::text[])[1 + n % 6], n, 1 + n % 5, 'utterance', 'user',"
            + " CASE WHEN n % 2 = 0 THEN repeat('alpha ', 1 + (n / 2) % 4) ELSE '' END"
            + " || CASE WHEN n % 3 = 0 THEN repeat('beta ', 1 + (n / 3) % 3) ELSE '' END"
            + " || CASE WHEN n % 5 < 3 THEN repeat('delta ', 1 + n % 2) ELSE '' END"
            + " || CASE WHEN n % 97 = 0 THEN 'gamma ' ELSE '' END"
            + " || 'unique' || n || ' ' || repeat('filler' || n % 13 || ' ', (n * 37) % 61)"
            + " FROM generate_series(1, ?) n",
        (Object) ids.toArray(String[]::new), count);
    for (String table :
        List.of(
            "entries", "entry_search_passages", "entry_search_terms", "entry_search_term_counts"))
      jdbc.execute("ANALYZE " + table);
  }

  /**
   * The pruned page is the exhaustive page for every shape of question: the same entries, ranks,
   * order and excerpts, on first pages, later pages and the widest window. Where nothing was
   * skipped the counts and cited sources agree as well.
   */
  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  void aPrunedMultiWordSearchReturnsExactlyTheExhaustivePage() {
    loadMixedCorpus(24000);
    int pruned = 0;
    for (String question :
        List.of(
            "alpha beta",
            "alpha OR beta",
            "\"alpha beta\"",
            "alpha -beta",
            "alpha gamma -beta",
            "alpha OR gamma -beta",
            "beta -beta",
            "beta -\"alpha beta\"",
            "alpha beta gamma",
            "alpha delta",
            "alpha beta delta",
            "alpha OR beta OR delta",
            "\"alpha beta\" delta",
            "alpha delta -beta",
            "alpha OR beta OR gamma",
            "gamma alpha",
            "gamma OR alpha",
            "alpha filler3",
            "alpha OR nosuchword",
            "alpha nosuchword",
            "alpha unique600",
            "-alpha beta",
            "the alpha")) {
      for (int[] window : new int[][] {{0, 10}, {10, 10}, {40, 20}, {0, 200}}) {
        String asked = question + " at " + window[0] + "+" + window[1];
        BoundedLogSearch exhaustive =
            entries.searchPassagesExhaustively(Home.global(), question, window[0], window[1], null);
        BoundedLogSearch found =
            entries.searchPassages(Home.global(), question, window[0], window[1], null);

        assertEquals(page(exhaustive.page().hits()), page(found.page().hits()), asked);
        assertEquals(exhaustive.page().reach(), found.page().reach(), asked);
        if (found.truncated()) {
          pruned++;
        } else {
          assertFalse(exhaustive.truncated(), asked);
          assertEquals(exhaustive.page().total(), found.page().total(), asked);
          assertEquals(
              comparable(exhaustive.page().hits()), comparable(found.page().hits()), asked);
        }
      }
    }
    assertTrue(pruned > 20, "pruning was engaged for only " + pruned + " searches");
    for (String question :
        List.of("alpha beta", "alpha beta delta", "alpha OR beta", "alpha delta -beta")) {
      assertTrue(
          entries.searchPassages(Home.global(), question, 0, 10, null).truncated(),
          "the comparison above no longer exercises pruning for: " + question);
    }
    // Where the served search completes, it agrees too: the oracle is not only this prototype.
    assertEquals(
        positionsAndRanks(entries.search(Home.global(), "gamma alpha", 0, 10).hits()),
        positionsAndRanks(
            entries.searchPassages(Home.global(), "gamma alpha", 0, 10, null).page().hits()));
  }

  /**
   * Entries that tie exactly at the threshold are all read: a tie is never broken by skipping. Four
   * thousand entries hold both words and score the same to the last bit; two thousand hold one. The
   * conjunction is pruned by intersecting the two words' postings and the alternative by each
   * word's share, and both must return the tied entries in the order an exhaustive search does, on
   * a first page, a later page and the widest page. The last window is deeper than pruning serves
   * and takes the exhaustive route.
   */
  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  void entriesTiedAtTheThresholdAreAllConsideredOnBothPrunedRoutes() throws IOException {
    jdbc.update(
        "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
            + " SELECT ?, n, 1 + n % 5, 'utterance', 'user',"
            + " CASE WHEN n % 3 = 0 THEN 'alpha item' ELSE 'alpha beta item' END || n"
            + " FROM generate_series(1, 6000) n",
        conversation);
    for (String table : List.of("entries", "entry_search_terms", "entry_search_term_counts"))
      jdbc.execute("ANALYZE " + table);

    for (String question : List.of("alpha beta", "alpha OR beta")) {
      for (int[] window : new int[][] {{0, 10}, {100, 20}, {0, 200}, {990, 10}}) {
        String asked = question + " at " + window[0] + "+" + window[1];
        BoundedLogSearch exhaustive =
            entries.searchPassagesExhaustively(Home.global(), question, window[0], window[1], null);
        BoundedLogSearch found =
            entries.searchPassages(Home.global(), question, window[0], window[1], null);
        assertEquals(page(exhaustive.page().hits()), page(found.page().hits()), asked);
        assertEquals(window[1], found.page().hits().size(), asked);
        assertEquals(
            window[0] + window[1] <= 500,
            prunedRouteRan(question, window[0], window[1]),
            "the route taken is not the one this test is about: " + asked);
      }
    }
  }

  /**
   * A far page is an empty page on every route, as it is for the served search, and not an
   * arithmetic failure in the limits derived from the window.
   */
  @Test
  void aWindowFarBeyondTheAnswerIsAnEmptyPage() {
    loadMixedCorpus(9000);
    for (String question : List.of("alpha", "alpha beta", "alpha OR beta", "gamma alpha")) {
      for (int skip : new int[] {300_000_000, Integer.MAX_VALUE}) {
        BoundedLogSearch found = entries.searchPassages(Home.global(), question, skip, 10, null);
        assertTrue(found.page().hits().isEmpty(), question + " at " + skip);
        assertEquals(9000, found.page().reach().searched(), question + " at " + skip);
      }
      assertTrue(entries.search(Home.global(), question, 300_000_000, 10).hits().isEmpty());
    }
  }

  /**
   * The statement that searches passages runs with JIT compilation off, in a transaction of its
   * own, and hands the connection back as it found it. With the compile threshold at zero every
   * ordinary statement on this database is compiled, which the control shows.
   */
  @Test
  void thePassageSearchIsNeverCompiledAndLeavesItsConnectionUnchanged() throws IOException {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));
    String database = jdbc.queryForObject("SELECT current_database()", String.class);
    String question =
        "retry budget refilled outage exponential backoff jitter policy release train quota"
            + " review quarter workflow launch capacity";
    try {
      jdbc.execute("ALTER DATABASE \"" + database + "\" SET jit = on");
      jdbc.execute("ALTER DATABASE \"" + database + "\" SET jit_above_cost = 0");
      assertTrue(
          explained(question, null, 0, 10, false, true).has("JIT"),
          "this server does not compile plans, so the test below proves nothing");

      JsonNode searched =
          new ObjectMapper()
              .readTree(entries.explainPassages(Home.global(), question, 0, 10, null))
              .get(0);
      assertFalse(searched.has("JIT"), "the passage search was compiled:\n" + searched);

      var one =
          new SingleConnectionDataSource(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
      try {
        JdbcTemplate same = new JdbcTemplate(one);
        assertEquals(
            1,
            new EntryStore(same)
                .searchPassages(Home.global(), "budget", 0, 10, null)
                .page()
                .total());
        assertEquals("on", same.queryForObject("SHOW jit", String.class));
        assertEquals("off", same.queryForObject("SHOW transaction_read_only", String.class));
        assertEquals(
            0,
            same.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE pid = pg_backend_pid()"
                    + " AND state LIKE 'idle in transaction%'",
                Integer.class));
      } finally {
        one.destroy();
      }
    } finally {
      jdbc.execute("ALTER DATABASE \"" + database + "\" RESET jit_above_cost");
      jdbc.execute("ALTER DATABASE \"" + database + "\" RESET jit");
    }
  }

  /**
   * A search made inside its caller's transaction runs in that transaction as it is: it reads what
   * the transaction has written, it sets nothing that would outlast the statement, and it therefore
   * runs without the JIT setting and is compiled when the server would compile it. A transaction on
   * another {@code DataSource} is not this store's: the search then takes its own short transaction
   * and is not compiled.
   */
  @Test
  void aSearchInsideItsCallersTransactionRunsThereWithoutTheJitSetting() throws IOException {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));
    String database = jdbc.queryForObject("SELECT current_database()", String.class);
    var elsewhere =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    try {
      jdbc.execute("ALTER DATABASE \"" + database + "\" SET jit = on");
      jdbc.execute("ALTER DATABASE \"" + database + "\" SET jit_above_cost = 0");
      assertTrue(
          explained("budget", null, 0, 10, false, true).has("JIT"),
          "this server does not compile plans, so the test below proves nothing");

      new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
          .executeWithoutResult(
              status -> {
                jdbc.update(
                    "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
                        + " VALUES (?, 50, 1, 'utterance', 'user', 'an uncommitted quokka')",
                    conversation);
                assertEquals(
                    1,
                    entries.searchPassages(Home.global(), "quokka", 0, 10, null).page().total(),
                    "the search did not run in the transaction that wrote the entry");
                assertTrue(
                    planOf("budget").has("JIT"),
                    "the search switched JIT off inside a transaction it does not own");
                assertEquals("on", jdbc.queryForObject("SHOW jit", String.class));
                assertEquals(
                    "off", jdbc.queryForObject("SHOW transaction_read_only", String.class));
                status.setRollbackOnly();
              });
      assertEquals(0, entries.searchPassages(Home.global(), "quokka", 0, 10, null).page().total());

      new TransactionTemplate(new DataSourceTransactionManager(elsewhere))
          .executeWithoutResult(
              status -> {
                assertEquals(1, new JdbcTemplate(elsewhere).queryForObject("SELECT 1", int.class));
                assertFalse(
                    planOf("budget").has("JIT"),
                    "a transaction on another DataSource was taken for this store's");
              });
    } finally {
      jdbc.execute("ALTER DATABASE \"" + database + "\" RESET jit_above_cost");
      jdbc.execute("ALTER DATABASE \"" + database + "\" RESET jit");
    }
  }

  /** What PostgreSQL reports for the repository's own run of one global search. */
  private JsonNode planOf(String question) {
    try {
      return new ObjectMapper()
          .readTree(entries.explainPassages(Home.global(), question, 0, 10, null))
          .get(0);
    } catch (IOException unreadable) {
      throw new IllegalStateException("PostgreSQL returned a plan that is not JSON", unreadable);
    }
  }

  /**
   * A passage search that cannot finish is cancelled within its budget and fails as a cancelled
   * query, the same typed failure the statement gave before it ran in a transaction; it is not an
   * empty success, and the next search works.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aBlockedPassageSearchIsCancelledWithinItsBudgetAsACancelledQuery() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));
    EntryStore budgeted = new EntryStore(jdbc).useSearchBudget(Duration.ofMillis(1500));
    inOneTransaction(
        blocker -> {
          try (var statement = blocker.createStatement()) {
            statement.execute("LOCK TABLE entry_search_terms IN ACCESS EXCLUSIVE MODE");
            long started = System.nanoTime();
            assertThrows(
                ArchiveQueryCancelledException.class,
                () -> budgeted.searchPassages(Home.global(), "budget", 0, 10, null));
            assertTrue(
                System.nanoTime() - started < TimeUnit.SECONDS.toNanos(4),
                "the search outlived its budget");
          }
        });
    assertEquals(1, budgeted.searchPassages(Home.global(), "budget", 0, 10, null).page().total());
  }

  /**
   * What a pruned multi-word search reads. Either word alone is in thousands of entries; the best
   * twenty answers are among the short, dense ones, and only those postings and entries are read.
   */
  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  void aMultiWordSearchReadsOnlyPostingsThatCanReachThePage() {
    loadMixedCorpus(30000);
    int postings =
        jdbc.queryForObject(
            "SELECT count(*) FROM entry_search_terms WHERE lexeme IN ('alpha','beta')",
            Integer.class);
    assertEquals(25000, postings);

    for (String question :
        List.of("alpha OR beta", "alpha beta", "gamma OR alpha", "alpha -beta", "\"alpha beta\"")) {
      for (boolean generic : new boolean[] {false, true}) {
        String asked = question + (generic ? " (generic plan)" : "");
        JsonNode plan = plan(question, null, 0, 20, generic);
        long read =
            rowsRead(
                plan,
                node ->
                    scans(node, "entry_search_terms")
                        && node.path("Index Name").asText().endsWith("best_first"));
        assertTrue(
            read <= postings / 4,
            asked + " read " + read + " of " + postings + " postings best-first:\n" + plan);
        long matched = rowsRead(plan, node -> scans(node, "entries"));
        assertTrue(
            matched <= postings / 4, asked + " read " + matched + " entries to match:\n" + plan);
        assertTrue(entries.searchPassages(Home.global(), question, 0, 20, null).truncated(), asked);
      }
    }
  }

  /**
   * A compound lexeme is where the prototype's rank is deliberately not the served one. PostgreSQL
   * stores {@code up-to-date} as one lexeme beside its parts. The served query counts a word's
   * document frequency by parsing the lexeme again, which splits it and finds nothing, so it weighs
   * the compound as if no entry held it. The prototype reads the count of the stored lexeme. Same
   * entries, same order here, lower and truer ranks.
   */
  @Test
  void aCompoundLexemeIsWeighedByItsRealDocumentFrequency() {
    for (String said :
        List.of(
            "keep the branch up-to-date with main",
            "the up-to-date build passed and stays up-to-date",
            "an unrelated note about the release",
            "another unrelated note about the date")) {
      entries.append(conversation, 1, LoggedEntry.utterance(said, Speaker.person(null)));
    }

    LogSearch served = entries.search(Home.global(), "up-to-date", 0, 10);
    LogSearch found = entries.searchPassages(Home.global(), "up-to-date", 0, 10, null).page();

    assertEquals(
        served.hits().stream().map(LogSearch.Hit::ordinal).toList(),
        found.hits().stream().map(LogSearch.Hit::ordinal).toList());
    assertEquals(2, found.total());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT sum(entries) FROM entry_search_term_counts WHERE lexeme LIKE 'up-to-%'",
            Integer.class),
        "the fixture has no compound lexeme");
    assertTrue(
        found.hits().getFirst().rank() < served.hits().getFirst().rank() - 0.1,
        "the compound is no longer weighed differently: served "
            + served.hits().getFirst().rank()
            + ", prototype "
            + found.hits().getFirst().rank());
    assertNotEquals(
        positionsAndRanks(served.hits()),
        positionsAndRanks(found.hits()),
        "this difference is documented; if it is gone, remove the documentation");
    assertEquals(
        positionsAndRanks(entries.search(Home.global(), "date", 0, 10).hits()),
        positionsAndRanks(entries.searchPassages(Home.global(), "date", 0, 10, null).page().hits()),
        "a plain word must still rank as the served search ranks it");
  }

  // --- scope and disclosure ------------------------------------------------------

  @Test
  void conversationSelectionNarrowsTheSearchAndDoesNotCrossHomes() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("orchard evidence", Speaker.person(null)));
    String other = conversations.open(PAYMENTS, Budget.of(2)).id();
    entries.append(other, 1, LoggedEntry.utterance("orchard private", Speaker.person(null)));

    assertEquals(1, entries.searchPassages(Home.global(), "orchard", 0, 10, null).page().total());
    assertEquals(
        1, entries.searchPassages(Home.global(), "orchard", 0, 10, conversation).page().total());
    assertEquals(0, entries.searchPassages(Home.global(), "orchard", 0, 10, other).page().total());
    assertEquals(
        0,
        entries.searchPassages(Home.global(), "orchard", 0, 10, "missing").page().reach().total());
    assertEquals(1, entries.searchPassages(PAYMENTS, "orchard", 0, 10, null).page().total());
  }

  /** A hidden conversation changes no statistic, count, group or reference. */
  @Test
  void hiddenConversationsContributeNeitherStatisticsNorGroupedReferences() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("needle public evidence", Speaker.person(null)));
    String hidden = conversations.open(Home.global(), Budget.of(2)).id();
    entries.append(
        hidden, 1, LoggedEntry.utterance("needle public evidence", Speaker.person(null)));
    var visibility =
        new InformationLogVisibility() {
          public <T> T read(String account, Function<Facts, T> reader) {
            return reader.apply(new Facts(Optional.of(List.of(hidden))));
          }

          public boolean readable(String account, String log, BooleanSupplier authoritative) {
            return !log.equals(hidden);
          }
        };
    var scoped = entries.useVisibility(visibility).forAccount("reader");

    var found = scoped.searchPassages(Home.global(), "needle", 0, 10, null).page();

    assertEquals(1, found.total());
    assertEquals(1, found.reach().searched());
    assertEquals(1, found.hits().getFirst().sourceCount(), "a hidden copy was counted");
    assertEquals(conversation, found.hits().getFirst().sources().getFirst().conversationId());
    assertEquals(
        scoped.search(Home.global(), "needle", 0, 10).hits().getFirst().rank(),
        found.hits().getFirst().rank(),
        1e-9,
        "the hidden conversation moved a corpus statistic");
    assertEquals(0, scoped.searchPassages(Home.global(), "needle", 0, 10, hidden).page().total());
  }

  /**
   * Visibility is one bit per conversation number, so it must be right for every arrangement of
   * numbers: hidden conversations below, between and above the visible ones (a number past the last
   * visible one is beyond the bitmap), another home's conversations interleaved, and numbers that
   * do not start at one. Each conversation holds one distinct entry with the same words, so the
   * conversations a search returns are exactly the conversations it considered visible.
   */
  @Test
  void visibilityHoldsForEveryConversationNumberAroundAndBeyondTheVisibleOnes() {
    List<String> global = new ArrayList<>();
    for (int i = 0; i < 45; i++) {
      boolean elsewhere = i % 5 == 4;
      String id = conversations.open(elsewhere ? PAYMENTS : Home.global(), Budget.of(2)).id();
      entries.append(
          id, 1, LoggedEntry.utterance("needle shared thread item" + i, Speaker.person(null)));
      if (!elsewhere) global.add(id);
    }
    List<String> hidden =
        List.of(global.get(0), global.get(9), global.get(10), global.get(22), global.getLast());
    Set<String> visible = new TreeSet<>(global);
    visible.removeAll(hidden);
    var visibility =
        new InformationLogVisibility() {
          public <T> T read(String account, Function<Facts, T> reader) {
            return reader.apply(new Facts(Optional.of(hidden)));
          }

          public boolean readable(String account, String log, BooleanSupplier authoritative) {
            return !hidden.contains(log);
          }
        };
    var scoped = entries.useVisibility(visibility).forAccount("reader");

    for (String question :
        List.of("needle", "needle thread", "needle OR thread", "\"needle shared\" -nosuchword")) {
      for (boolean prune : new boolean[] {true, false}) {
        String asked = question + (prune ? "" : " (exhaustive)");
        LogSearch found =
            (prune
                    ? scoped.searchPassages(Home.global(), question, 0, 200, null)
                    : scoped.searchPassagesExhaustively(Home.global(), question, 0, 200, null))
                .page();
        Set<String> returned = new TreeSet<>();
        for (LogSearch.Hit hit : found.hits()) {
          returned.add(hit.conversationId());
          for (LogSearch.Source source : hit.sources()) returned.add(source.conversationId());
        }
        assertEquals(visible, returned, asked);
        assertEquals(visible.size(), found.reach().searched(), asked);
      }
    }
    for (String id : hidden) {
      assertEquals(
          0, scoped.searchPassages(Home.global(), "needle", 0, 10, id).page().reach().total(), id);
    }
    assertEquals(9, scoped.searchPassages(PAYMENTS, "needle", 0, 200, null).page().hits().size());
  }

  /**
   * The pruned multi-word routes read postings by word, not by conversation, so visibility has to
   * be applied where postings are read and again where a candidate becomes a match. A hidden
   * conversation here holds three hundred entries that would outrank every visible one for both
   * words. They must not move the threshold, appear as hits or sources, or change what was
   * reachable; the page must be the one an exhaustive search of the visible entries returns.
   */
  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  void hiddenConversationsNeitherRaiseTheThresholdNorReachAPrunedPage() {
    jdbc.update(
        "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
            + " SELECT ?, n, 1 + n % 5, 'utterance', 'user',"
            + " CASE WHEN n % 2 = 0 THEN repeat('alpha ', 1 + (n / 2) % 4) ELSE '' END"
            + " || CASE WHEN n % 3 = 0 THEN repeat('beta ', 1 + (n / 3) % 3) ELSE '' END"
            + " || 'unique' || n || ' ' || repeat('filler' || n % 13 || ' ', 5 + (n * 37) % 61)"
            + " FROM generate_series(1, 15000) n",
        conversation);
    String hidden = conversations.open(Home.global(), Budget.of(2)).id();
    jdbc.update(
        "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
            + " SELECT ?, n, 1, 'utterance', 'user',"
            + " 'alpha alpha alpha alpha beta beta beta hidden' || n"
            + " FROM generate_series(1, 300) n",
        hidden);
    for (String table : List.of("entries", "entry_search_terms", "entry_search_term_counts"))
      jdbc.execute("ANALYZE " + table);
    var visibility =
        new InformationLogVisibility() {
          public <T> T read(String account, Function<Facts, T> reader) {
            return reader.apply(new Facts(Optional.of(List.of(hidden))));
          }

          public boolean readable(String account, String log, BooleanSupplier authoritative) {
            return !log.equals(hidden);
          }
        };
    var scoped = entries.useVisibility(visibility).forAccount("reader");

    for (String question : List.of("alpha beta", "alpha OR beta", "alpha -beta")) {
      BoundedLogSearch exhaustive =
          scoped.searchPassagesExhaustively(Home.global(), question, 0, 10, null);
      BoundedLogSearch found = scoped.searchPassages(Home.global(), question, 0, 10, null);

      assertTrue(found.truncated(), "pruning was not engaged for " + question);
      assertEquals(10, found.page().hits().size(), question);
      assertEquals(page(exhaustive.page().hits()), page(found.page().hits()), question);
      assertEquals(15000, found.page().reach().searched(), question);
      for (LogSearch.Hit hit : found.page().hits()) {
        assertEquals(conversation, hit.conversationId(), "a hidden entry is a hit for " + question);
        for (LogSearch.Source source : hit.sources()) {
          assertEquals(conversation, source.conversationId(), "a hidden entry is cited");
        }
      }
      assertEquals(
          0,
          scoped.searchPassages(Home.global(), question, 0, 10, hidden).page().total(),
          question);
    }
    // The same question asked by a reader who may see everything is answered from the hidden
    // conversation, so the fixture is what it claims to be.
    assertEquals(
        hidden,
        entries
            .searchPassages(Home.global(), "alpha beta", 0, 10, null)
            .page()
            .hits()
            .getFirst()
            .conversationId());
  }

  /**
   * Counting is per statement and per conversation, never per entry: a statement leaves one pending
   * row for each conversation it changed, an update that search does not read leaves none, and the
   * commit applies each conversation's total once and leaves nothing pending.
   */
  @Test
  void aStatementStagesOneChangePerConversationAndTheCommitSettlesIt() {
    String other = conversations.open(Home.global(), Budget.of(9)).id();
    inOneTransaction(
        connection -> {
          try (var load =
                  connection.prepareStatement(
                      "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content)"
                          + " SELECT (?::text[])[1 + n % 2], n, 1, 'utterance', 'user',"
                          + " 'budget line' || n % 50 || ' unique' || n"
                          + " FROM generate_series(1, 3000) n");
              var statement = connection.createStatement()) {
            load.setArray(1, connection.createArrayOf("text", new String[] {conversation, other}));
            load.executeUpdate();
            assertEquals(2, count(connection, "SELECT count(*) FROM entry_search_pending"));
            assertEquals(
                0,
                count(
                    connection,
                    "SELECT coalesce(sum(searched), 0) FROM entry_search_conversations"),
                "the statistics were applied before the commit");
            assertEquals(
                3000,
                count(
                    connection,
                    "SELECT count(DISTINCT (conversation_no, ordinal)) FROM entry_search_terms"),
                "the postings were not written with the entries");

            statement.executeUpdate("UPDATE entries SET learned_at = now()");
            assertEquals(2, count(connection, "SELECT count(*) FROM entry_search_pending"));

            statement.executeUpdate(
                "UPDATE entries SET content = content || ' extra' WHERE ordinal % 10 = 0");
            assertEquals(3, count(connection, "SELECT count(*) FROM entry_search_pending"));

            statement.executeUpdate("DELETE FROM entries WHERE ordinal % 7 = 0");
            assertEquals(5, count(connection, "SELECT count(*) FROM entry_search_pending"));
          }
        });

    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM entry_search_pending", Integer.class));
    assertCountsAgreeWithARecount("after a transaction of bulk statements");
    assertEquals(
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE conversation_id = ? AND content LIKE '% extra'",
            Integer.class, conversation),
        lexemeCount(conversation, "extra"));
    assertTrue(lexemeCount(conversation, "extra") > 200);
    assertEquals(0, lexemeCount(other, "extra"), "only even ordinals were rewritten");
  }

  /**
   * Two things no application path does, which must still leave the counts right: a conversation
   * deleted and created again under the same id in one transaction starts from nothing, and a
   * transaction that writes an entry may then truncate {@code entries} (and only {@code entries}:
   * see the next test).
   */
  @Test
  void aConversationReplacedOrATableTruncatedInTheWritingTransactionLeavesTheCountsRight() {
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", "the retry budget refilled"));
    entries.append(conversation, 1, LoggedEntry.toolResult("c2", "a budget review"));
    String insert =
        "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content) VALUES ('"
            + conversation
            + "', %d, 1, 'utterance', 'user', '%s')";
    inOneTransaction(
        connection -> {
          try (var statement = connection.createStatement()) {
            statement.execute(
                "CREATE TEMP TABLE replaced ON COMMIT DROP AS SELECT * FROM conversations"
                    + " WHERE id = '"
                    + conversation
                    + "'");
            statement.executeUpdate(insert.formatted(3, "a third budget line"));
            statement.executeUpdate(
                "DELETE FROM entries WHERE conversation_id = '" + conversation + "'");
            statement.executeUpdate("DELETE FROM conversations WHERE id = '" + conversation + "'");
            statement.executeUpdate("INSERT INTO conversations SELECT * FROM replaced");
            statement.executeUpdate(insert.formatted(1, "the quota of the new conversation"));
          }
        });
    assertCountsAgreeWithARecount("after replacing a conversation in one transaction");
    assertEquals(0, lexemeCount(conversation, "budget"), "the old conversation's count survived");
    assertEquals(1, lexemeCount(conversation, "quota"));
    assertEquals(1, entries.searchPassages(Home.global(), "quota", 0, 10, null).page().total());

    inOneTransaction(
        connection -> {
          try (var statement = connection.createStatement()) {
            statement.executeUpdate(insert.formatted(2, "written and then truncated"));
            statement.execute("TRUNCATE TABLE entries CASCADE");
          }
        });
    assertCountsAgreeWithARecount("after truncating in the writing transaction");
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM entry_search_pending", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM entry_search_terms", Integer.class));
    assertEquals(0, entries.searchPassages(Home.global(), "quota", 0, 10, null).page().total());
  }

  /**
   * What a transaction that has written entries may truncate. {@code TRUNCATE entries CASCADE} is
   * allowed (the test above). A TRUNCATE that reaches {@code conversations} cascades to the pending
   * rows that transaction's own writes queued a deferred event for, and PostgreSQL refuses it; the
   * transaction's work is rolled back and nothing is left half counted. Settling first, with {@code
   * SET CONSTRAINTS ALL IMMEDIATE}, makes the same TRUNCATE legal. A transaction that has written
   * no entries truncates both, as every test here does before it starts.
   */
  @Test
  void aTransactionThatWroteEntriesMayNotTruncateConversationsUntilItHasSettled() {
    entries.append(conversation, 1, LoggedEntry.toolResult("c1", "the retry budget refilled"));
    String insert =
        "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content) VALUES ('"
            + conversation
            + "', 2, 1, 'utterance', 'user', 'written before a truncate')";

    DataAccessException refused =
        assertThrows(
            DataAccessException.class,
            () ->
                inOneTransaction(
                    connection -> {
                      try (var statement = connection.createStatement()) {
                        statement.executeUpdate(insert);
                        statement.execute("TRUNCATE TABLE conversations, entries CASCADE");
                      }
                    }));
    assertTrue(
        refused
            .getMostSpecificCause()
            .getMessage()
            .contains(
                "cannot TRUNCATE \"entry_search_pending\" because it has pending trigger events"),
        refused.getMostSpecificCause().getMessage());
    assertEquals(
        1,
        jdbc.queryForObject("SELECT count(*) FROM entries", Integer.class),
        "the refused transaction left its entry, or took the earlier one");
    assertCountsAgreeWithARecount("after a refused truncate");
    assertEquals(1, lexemeCount(conversation, "budget"));

    inOneTransaction(
        connection -> {
          try (var statement = connection.createStatement()) {
            statement.executeUpdate(insert);
            statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
            statement.execute("TRUNCATE TABLE conversations, entries CASCADE");
          }
        });
    for (String table :
        List.of(
            "conversations",
            "entries",
            "entry_search_conversations",
            "entry_search_pending",
            "entry_search_terms",
            "entry_search_term_counts",
            "entry_search_passages")) {
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class), table);
    }
  }

  @Test
  void aWindowThatIsNotAPageIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> entries.searchPassages(Home.global(), "budget", -1, 10, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> entries.searchPassages(Home.global(), "budget", 0, 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> entries.searchPassages(Home.global(), "budget", 0, 201, null));
  }

  // --- the plan ------------------------------------------------------------------

  /**
   * The multi-word match, the document frequency and the best-first postings are all index reads.
   * As in {@code LogSearchTest}, {@code enable_seqscan} is off: this asks which index can serve the
   * read, not what the planner prefers for a tiny table.
   */
  @Test
  void matchesAndPostingsAreServedByTheirIndexes() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("the retry budget refilled", Speaker.person(null)));
    jdbc.update(
        "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content) "
            + "SELECT ?, n+1,1,'utterance','user','unrelated filler vocabulary'"
            + " FROM generate_series(1,1000) n",
        conversation);
    jdbc.execute("ANALYZE entries");
    jdbc.execute("ANALYZE conversations");
    jdbc.execute("ANALYZE entry_search_passages");
    jdbc.execute("ANALYZE entry_search_terms");
    jdbc.execute("ANALYZE entry_search_term_counts");

    String plan =
        String.join(
            "\n",
            jdbc.execute(
                (ConnectionCallback<List<String>>)
                    connection -> {
                      try (var off = connection.createStatement()) {
                        off.execute("SET enable_seqscan = off");
                      }
                      try (var explain =
                          connection.prepareStatement(
                              "EXPLAIN "
                                  + EntryStore.PASSAGE_SEARCH_SQL.replace("/*information*/", ""))) {
                        explain.setString(1, "budget");
                        explain.setString(2, "budget");
                        explain.setObject(3, null, Types.BIGINT);
                        explain.setObject(4, null, Types.VARCHAR);
                        explain.setInt(5, EntryStore.MOST_GROUPED_ENTRIES);
                        explain.setInt(6, 20);
                        explain.setBoolean(7, true);
                        explain.setInt(8, 0);
                        explain.setInt(9, 20);
                        List<String> lines = new ArrayList<>();
                        try (var rows = explain.executeQuery()) {
                          while (rows.next()) lines.add(rows.getString(1));
                        }
                        return lines;
                      } finally {
                        try (var on = connection.createStatement()) {
                          on.execute("RESET enable_seqscan");
                        }
                      }
                    }));

    assertTrue(plan.contains("entries_evidence_text_search"), plan);
    assertTrue(
        plan.contains("entry_search_terms_best_first"),
        "the postings are not read through the best-first index:\n" + plan);
    assertTrue(
        plan.contains("entry_search_term_counts_pkey"),
        "document frequency is not read from the stored lexeme counts:\n" + plan);
  }

  /** The stored per-conversation counts are a sum of current rows, whatever was done to them. */
  private void assertCountsAgreeWithARecount(String when) {
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM (SELECT conversation_id,"
                + " count(*) FILTER (WHERE role IS NOT NULL AND"
                + " coalesce(evidence_search_length,search_length) IS NOT NULL) AS searched,"
                + " coalesce(sum(coalesce(evidence_search_length,search_length))"
                + " FILTER (WHERE role IS NOT NULL),0) AS total_length,"
                + " count(*) FILTER (WHERE ejected_at IS NOT NULL) AS ejected,"
                + " count(*) FILTER (WHERE role IS NULL) AS recorded_only"
                + " FROM entries GROUP BY conversation_id) x"
                + " FULL JOIN entry_search_conversations k USING (conversation_id)"
                + " WHERE ROW(coalesce(x.searched,0),coalesce(x.total_length,0),"
                + "coalesce(x.ejected,0),coalesce(x.recorded_only,0)) IS DISTINCT FROM"
                + " ROW(k.searched::bigint,k.total_length::numeric,k.ejected::bigint,"
                + "k.recorded_only::bigint)",
            Integer.class),
        "the stored search counts disagree with the entries " + when);
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM (SELECT lexeme, conversation_no, count(*) AS entries"
                + " FROM entry_search_terms GROUP BY lexeme, conversation_no) x"
                + " FULL JOIN entry_search_term_counts k USING (lexeme, conversation_no)"
                + " WHERE x.entries IS DISTINCT FROM k.entries::bigint",
            Integer.class),
        "the stored lexeme counts disagree with the postings " + when);
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM (SELECT c.conversation_no, e.ordinal, t.lexeme COLLATE \"C\" AS lexeme,"
                + " greatest(cardinality(t.positions), 1) AS tf,"
                + " coalesce(e.evidence_search_length, e.search_length) AS doc_length"
                + " FROM entries e JOIN entry_search_conversations c USING (conversation_id)"
                + " CROSS JOIN LATERAL unnest(coalesce(e.evidence_text_search, e.text_search)) t"
                + " WHERE e.role IS NOT NULL AND e.content IS NOT NULL) x"
                + " FULL JOIN entry_search_terms p USING (conversation_no, ordinal, lexeme)"
                + " WHERE ROW(x.tf::integer, x.doc_length) IS DISTINCT FROM"
                + " ROW(p.tf::integer, p.doc_length)",
            Integer.class),
        "the postings disagree with the entries " + when);
  }

  /** The stored count of one conversation's entries holding a lexeme; 0 when there is no row. */
  private int lexemeCount(String conversationId, String lexeme) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(k.entries), 0) FROM entry_search_term_counts k"
            + " JOIN entry_search_conversations c USING (conversation_no)"
            + " WHERE c.conversation_id = ? AND k.lexeme = ?",
        Integer.class,
        conversationId,
        lexeme);
  }

  private List<String> openConversations(int count) {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) ids.add(conversations.open(Home.global(), Budget.of(2)).id());
    return ids;
  }

  /** Work on one connection that is committed as one transaction. */
  private interface SqlWork {
    void run(Connection connection) throws SQLException;
  }

  private void inOneTransaction(SqlWork work) {
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              connection.setAutoCommit(false);
              try {
                work.run(connection);
                connection.commit();
              } finally {
                connection.rollback();
                connection.setAutoCommit(true);
              }
              return null;
            });
  }

  private static int count(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getInt(1);
    }
  }

  /** The plan tree of one pruned search run outside the repository, with JIT off. */
  private JsonNode plan(
      String question, String conversationId, int skip, int most, boolean generic) {
    return explained(question, conversationId, skip, most, generic, false).get("Plan");
  }

  /**
   * What PostgreSQL reports for one pruned search run as a plain prepared statement, outside the
   * repository. Prepared and executed by name so that {@code generic} can force the plan a cached
   * statement would get, in which no parameter value is known to the planner. {@code compiled}
   * leaves JIT as the session finds it; otherwise it is off, as it is where the repository runs the
   * statement.
   */
  private JsonNode explained(
      String question,
      String conversationId,
      int skip,
      int most,
      boolean generic,
      boolean compiled) {
    return explained(question, null, conversationId, skip, most, generic, compiled);
  }

  /** The same for one project's reader; a null project is the global reader. */
  private JsonNode explained(
      String question,
      Long project,
      String conversationId,
      int skip,
      int most,
      boolean generic,
      boolean compiled) {
    StringBuilder sql = new StringBuilder();
    int parameter = 0;
    for (String piece :
        EntryStore.PASSAGE_SEARCH_SQL.replace("/*information*/", "").split("\\?", -1)) {
      if (parameter > 0) sql.append('$').append(parameter);
      sql.append(piece);
      parameter++;
    }
    String asked = "'" + question.replace("'", "''") + "'";
    String scope = conversationId == null ? "NULL" : "'" + conversationId + "'";
    return jdbc.execute(
        (ConnectionCallback<JsonNode>)
            connection -> {
              try (var statement = connection.createStatement()) {
                if (!compiled) statement.execute("SET jit = off");
                statement.execute(
                    "SET plan_cache_mode = "
                        + (generic ? "force_generic_plan" : "force_custom_plan"));
                statement.execute(
                    "PREPARE passage_plan(text,text,bigint,text,integer,bigint,boolean,integer,"
                        + "integer) AS "
                        + sql);
                try (var rows =
                    statement.executeQuery(
                        "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) EXECUTE passage_plan("
                            + asked
                            + ","
                            + asked
                            + ","
                            + (project == null ? "NULL" : project.toString())
                            + ","
                            + scope
                            + ","
                            + EntryStore.MOST_GROUPED_ENTRIES
                            + ","
                            + (skip + most)
                            + ",true,"
                            + skip
                            + ","
                            + most
                            + ")")) {
                  rows.next();
                  return new ObjectMapper().readTree(rows.getString(1)).get(0);
                }
              } catch (IOException unreadable) {
                throw new IllegalStateException(
                    "PostgreSQL returned a plan that is not JSON", unreadable);
              }
            });
  }

  /** Pages the statement touched, in or out of the cache, everything it ran included. */
  private static long buffers(JsonNode plan) {
    return plan.path("Shared Hit Blocks").asLong() + plan.path("Shared Read Blocks").asLong();
  }

  /**
   * Whether the repository's own run of this search took the pruned multi-word route: whether the
   * step that reads postings able to reach the threshold read any.
   */
  private boolean prunedRouteRan(String question, int skip, int most) throws IOException {
    JsonNode plan =
        new ObjectMapper()
            .readTree(entries.explainPassages(Home.global(), question, skip, most, null))
            .get(0)
            .get("Plan");
    return rowsRead(plan, node -> "CTE near".equals(node.path("Subplan Name").asText())) > 0;
  }

  private static boolean scans(JsonNode node, String relation) {
    return relation.equals(node.path("Relation Name").asText());
  }

  /**
   * Rows the chosen plan nodes fetched, kept or not. PostgreSQL reports rows per loop, rounded, so
   * a node looped 256 times can be undercounted by up to half a row per loop; the assertions that
   * use this compare against thousands.
   */
  private static long rowsRead(JsonNode node, Predicate<JsonNode> chosen) {
    long read = 0;
    if (chosen.test(node)) {
      read =
          node.path("Actual Loops").asLong(1)
              * (node.path("Actual Rows").asLong()
                  + node.path("Rows Removed by Filter").asLong()
                  + node.path("Rows Removed by Index Recheck").asLong());
    }
    for (JsonNode child : node.path("Plans")) read += rowsRead(child, chosen);
    return read;
  }

  /** The page as a reader sees it, without the counts that pruning is allowed to bound. */
  private static List<String> page(List<LogSearch.Hit> hits) {
    return hits.stream()
        .map(
            hit ->
                hit.conversationId()
                    + ":"
                    + hit.ordinal()
                    + " "
                    + Math.round(hit.rank() * 1e9)
                    + " "
                    + hit.sourceRevision()
                    + " "
                    + hit.snippet())
        .toList();
  }

  private int entriesWithTerms() {
    return jdbc.queryForObject(
        "SELECT count(DISTINCT (conversation_no, ordinal)) FROM entry_search_terms", Integer.class);
  }

  /** Entry, rank and position on the page; counts are bounded when postings are skipped. */
  private static List<String> positionsAndRanks(List<LogSearch.Hit> hits) {
    return hits.stream()
        .map(hit -> hit.conversationId() + ":" + hit.ordinal() + " " + Math.round(hit.rank() * 1e9))
        .toList();
  }

  private int passageCount() {
    return jdbc.queryForObject("SELECT count(*) FROM entry_search_passages", Integer.class);
  }

  /** What two searches must agree on, in order. Ranks are compared to nine decimal places. */
  private static List<String> comparable(List<LogSearch.Hit> hits) {
    return hits.stream()
        .map(
            hit ->
                hit.conversationId()
                    + ":"
                    + hit.ordinal()
                    + " x"
                    + hit.sourceCount()
                    + " "
                    + Math.round(hit.rank() * 1e9)
                    + " "
                    + hit.sourceRevision()
                    + " "
                    + hit.sources().stream()
                        .map(source -> source.conversationId() + ":" + source.ordinal())
                        .toList()
                    + " "
                    + hit.snippet())
        .toList();
  }
}
