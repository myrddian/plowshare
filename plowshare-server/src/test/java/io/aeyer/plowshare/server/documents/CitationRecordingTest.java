package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Citing;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.documents.CitationStore.Cited;
import io.aeyer.plowshare.server.documents.CitationStore.Standing;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
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

/**
 * A turn ends and what its answer cited is written down — through the seam, the runtime and the
 * log, rather than by calling the store.
 *
 * <p>{@code CitationStoreTest} is about what a citation resolves to; this is about how one comes to
 * exist. Every test here drives a real run over a scripted endpoint and then closes the turn the
 * way {@code Turn} does, so the thing under test is {@code Compaction.TurnTranscript.closed}
 * calling {@link Citing} and not a fixture standing in for it.
 */
@Testcontainers
class CitationRecordingTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  /** Far above any history here, so no fold ever starts. */
  private static final int ROOMY = 1_000_000;

  private static final Instant AT = Instant.parse("2026-09-04T09:00:00Z");

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;

  private DocumentStore documents;
  private CitationStore citations;
  private ConversationStore conversations;
  private EntryStore entries;
  private TurnStore turns;
  private Compaction compaction;
  private ScriptedChat transport;

  @BeforeAll
  static void migrate() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    TransactionTemplate template =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transactions =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> work) {
            return template.execute(status -> work.get());
          }
        };
  }

  @BeforeEach
  void freshServer() {
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, citations, documents CASCADE");
    jdbc.execute("DELETE FROM entries");
    jdbc.execute("DELETE FROM turns");
    jdbc.execute("DELETE FROM conversations");
    documents = new DocumentStore(jdbc, transactions);
    citations = new CitationStore(jdbc);
    conversations = new ConversationStore(jdbc, () -> AT, null);
    entries = new EntryStore(jdbc);
    turns = new TurnStore(jdbc);
    transport = new ScriptedChat();
  }

  @AfterEach
  void stopFolding() {
    if (compaction != null) {
      compaction.close();
    }
  }

  // --- the seam ------------------------------------------------------------------

  /**
   * <b>The slice in one assertion: a librarian answers, and the corpus knows it was used.</b>
   *
   * <p>Nothing new was put in front of the model to make this happen. The answer is prose with a
   * paragraph id in it, exactly the shape {@code librarian.md} has already been instructing since
   * 1.2, and every byte of this run's prompt is what it would have been before this table existed.
   */
  @Test
  void what_an_answer_named_is_written_down_when_the_turn_closes() {
    ingest("retries.md", "Alpha is unrelated.\n\nRetries are budgeted per run.");
    UUID paragraph = paragraphAt(2);
    String conversation = conversation();

    answer(
        librarian(), conversation, "Retries are budgeted per run (paragraph " + paragraph + ").");

    List<Cited> made = citations.madeIn(conversation, 10);
    assertEquals(1, made.size());
    Cited only = made.get(0);
    assertEquals(paragraph, only.paragraphId());
    assertEquals("Retries are budgeted per run.", only.paragraphText());
    assertEquals("librarian", only.agent());
    assertEquals(1, only.turnOrdinal());
    assertEquals(Standing.RESOLVES, only.standing());
  }

  /**
   * <b>The guardrail is the loader's, and here is the agent it refuses.</b>
   *
   * <p>The same answer, word for word, from an agent whose {@code tools:} does not hold {@code
   * document_search}. It records nothing — not because the uuid is bad, but because an agent that
   * was never granted the corpus cannot have been handed a paragraph id by it, so a uuid in its
   * answer is a uuid about something else.
   */
  @Test
  void an_agent_that_was_never_granted_the_corpus_cites_nothing() {
    ingest("retries.md", "Retries are budgeted per run.");
    UUID paragraph = paragraphAt(1);
    String conversation = conversation();

    answer(
        withoutTheCorpus(),
        conversation,
        "Retries are budgeted per run (paragraph " + paragraph + ").");

    assertEquals(0, count());
  }

  /**
   * An answer that named nothing records nothing, which is the ordinary case for every agent that
   * never touches the corpus.
   */
  @Test
  void an_answer_with_no_paragraph_in_it_records_nothing() {
    ingest("retries.md", "Retries are budgeted per run.");
    String conversation = conversation();

    answer(librarian(), conversation, "The corpus does not answer that.");

    assertEquals(0, count());
  }

  /**
   * <b>The corpus is the filter and nothing else has to be.</b>
   *
   * <p>A stored-result handle is a uuid too, and an agent may quote one when it says what it read
   * back. It joins to no paragraph, so it writes no row — which is why {@link Citations} needs no
   * list of uuids to ignore.
   */
  @Test
  void a_uuid_that_is_not_a_paragraph_is_not_a_citation() {
    ingest("retries.md", "Retries are budgeted per run.");
    String conversation = conversation();

    answer(
        librarian(),
        conversation,
        "I read back stored result " + UUID.randomUUID() + " and it says nothing.");

    assertEquals(0, count());
  }

  /**
   * A run that did not answer cites nothing.
   *
   * <p>Every ending but {@code ANSWERED} puts the runtime's own sentence into {@code Outcome.text},
   * and a citation read out of a harness sentence would be one the agent never made. Driven by a
   * cap of nought turns, which is the cheapest ending to reach.
   */
  @Test
  void a_run_that_did_not_answer_cites_nothing() {
    ingest("retries.md", "Retries are budgeted per run.");
    String conversation = conversation();
    UUID paragraph = paragraphAt(1);

    Compaction.TurnTranscript transcript = transcript(conversation, librarian());
    Outcome stopped =
        new Outcome(
            Outcome.Ending.TURN_CAP,
            "This run stopped at its cap, paragraph " + paragraph + ".",
            0,
            0,
            "");
    transcript.closed("what does it say", stopped);

    assertFalse(stopped.answered());
    assertEquals(0, count());
  }

  // --- what retention does to a citation -------------------------------------------

  /**
   * <b>Ejection does not reach a citation, and that is a property of where the citation points.</b>
   *
   * <p>The brief for this slice asked what a citation resolves to after a tool result is ejected.
   * The answer is <b>exactly what it resolved to before</b>, and the reason is structural rather
   * than lucky:
   *
   * <ul>
   *   <li>the citation names a row in {@code paragraphs}, which retention never touches — the sweep
   *       works over {@code conversations} and {@code entries} and prunes {@code jobs}, and the
   *       corpus is not in any of the three;
   *   <li>the answer the citation was read out of is an {@code answer} row, and V19's {@code
   *       entries_only_a_tool_result_is_ejected} is what guarantees it is never ejected;
   *   <li>what ejection does take away is the search result — the <em>evidence</em> the agent was
   *       shown — and {@code result_read} already answers for that in its own words.
   * </ul>
   *
   * <p>This is also the argument, seen from the other end, for why {@code CitationStore.record}
   * does not validate a citation against what the log says the run was shown: that half is the
   * ejectable half, and gating on it would drop citations exactly in the conversations that have
   * run longest. The ejection here is performed with the sweep's own statement.
   */
  @Test
  void a_citation_still_resolves_after_the_result_it_came_from_is_ejected() {
    ingest("retries.md", "Retries are budgeted per run.");
    UUID paragraph = paragraphAt(1);
    String conversation = conversation();
    entries.append(
        conversation,
        1,
        io.aeyer.plowshare.server.agents.LoggedEntry.toolResult(
            "call_1", "cite paragraph " + paragraph));
    answer(librarian(), conversation, "Per run (paragraph " + paragraph + ").");

    int ejected =
        jdbc.update(
            "UPDATE entries SET ejected_chars = length(content), content = NULL,"
                + " ejected_at = ?, export = ? WHERE conversation_id = ?"
                + " AND kind = 'tool_result' AND ejected_at IS NULL",
            AT.atOffset(ZoneOffset.UTC),
            "exports/somewhere.txt",
            conversation);
    assertEquals(1, ejected, "nothing was ejected, so this proves nothing");

    Cited after = citations.madeIn(conversation, 10).get(0);
    assertEquals(Standing.RESOLVES, after.standing());
    assertEquals("Retries are budgeted per run.", after.paragraphText());
    assertNotNull(after.conversationId());
  }

  // --- fixtures ---------------------------------------------------------------------

  /** Run one turn to an answer and close it, as {@code Turn} does. */
  private void answer(AgentDefinition definition, String conversationId, String said) {
    transport.then(said);
    Compaction.TurnTranscript transcript = transcript(conversationId, definition);
    Outcome outcome =
        new JobRuntime(transport.dispatcher(), List.of())
            .run(
                definition,
                "what does the corpus say",
                Home.global(),
                Budget.of(4),
                () -> false,
                null,
                io.aeyer.plowshare.server.agents.JobWatch.UNWATCHED,
                transcript,
                TurnCap.none());
    assertTrue(outcome.answered(), outcome.text());
    transcript.closed("what does the corpus say", outcome);
  }

  private Compaction.TurnTranscript transcript(String conversationId, AgentDefinition definition) {
    if (compaction == null) {
      compaction =
          new Compaction(
              transport.dispatcher(),
              CitationRecordingTest::folder,
              turns,
              new CompactionStore(jdbc),
              entries,
              ROOMY,
              conversations,
              null,
              new Citations(citations, Clock.fixed(AT, ZoneOffset.UTC)));
    }
    return compaction.transcriptFor(conversationId, definition, Speaker.person(null));
  }

  private String conversation() {
    return conversations.open(Home.global(), Budget.of(12)).id();
  }

  private static AgentDefinition librarian() {
    return new AgentDefinition(
        "librarian",
        "answers from the corpus",
        "reasoning",
        List.of("document_search"),
        List.of(),
        List.of(),
        20,
        12,
        "You are a librarian.");
  }

  private static AgentDefinition withoutTheCorpus() {
    return new AgentDefinition(
        "interlocutor",
        "talks",
        "reasoning",
        List.of(),
        List.of(),
        List.of(),
        20,
        12,
        "You are an interlocutor.");
  }

  private UUID ingest(String sourceName, String text) {
    Extracted extracted = TextExtraction.extract(sourceName, text.getBytes(StandardCharsets.UTF_8));
    return documents
        .write(
            sourceName,
            extracted,
            text.length(),
            "test",
            AT,
            Derivation.derive(extracted, ShippedChunking.SHIPPED))
        .documentId();
  }

  private UUID paragraphAt(int ordinal) {
    return jdbc.queryForObject("SELECT id FROM paragraphs WHERE ordinal = ?", UUID.class, ordinal);
  }

  private int count() {
    return jdbc.queryForObject("SELECT count(*) FROM citations", Integer.class);
  }

  /**
   * The agent a fold runs as, which nothing in this class asks for.
   *
   * <p>Named rather than left out all the same. A fold that could not say what it runs as would run
   * as the agent being folded, which is the defect {@code Compaction.FOLDER} exists to end --
   * {@code implementation rationale} §6.1 -- and a fixture that quietly had no folder would be the
   * one place that could not tell the two apart.
   */
  private static AgentDefinition folder() {
    return new AgentDefinition(
        Compaction.FOLDER,
        "a fixture folder",
        "fast",
        List.of(),
        List.of(),
        List.of(),
        1,
        1,
        "You summarise a span of a recorded conversation.");
  }
}
