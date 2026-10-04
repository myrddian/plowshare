package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.documents.CitationStore.Cited;
import io.aeyer.plowshare.server.documents.CitationStore.Standing;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code citations} against a real corpus: what a citation resolves to, and what it resolves to
 * once the thing it named has moved.
 *
 * <h2>The property V18 and TODO 1.3 both called unexercised</h2>
 *
 * <p>Both say the surrogate key exists "precisely so a citation survives a re-ingest" and both
 * admit the property is not exercised. {@link
 * #a_citation_survives_a_re_ingest_that_left_its_paragraph_alone} is the first test in this
 * repository where an actual citation is the thing surviving — {@code DocumentStoreTest} could only
 * compare id maps, because there was nothing that held one. {@code IngestServiceTest} proves the
 * other half, that the ids come out of the whole pipeline unchanged and not merely out of {@code
 * DocumentStore.write}.
 */
@Tag("full-db")
@Testcontainers
class CitationStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;

  private DocumentStore documents;
  private CitationStore citations;

  private static final Instant AT = Instant.parse("2026-09-04T09:00:00Z");
  private static final Instant LATER = Instant.parse("2026-09-04T10:00:00Z");

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
  void freshCorpus() {
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, citations, documents CASCADE");
    jdbc.execute("DELETE FROM entries");
    jdbc.execute("DELETE FROM turns");
    jdbc.execute("DELETE FROM conversations");
    documents = new DocumentStore(jdbc, transactions);
    citations = new CitationStore(jdbc);
  }

  // --- what a citation is ------------------------------------------------------

  /** A citation carries the words, the coordinates and who said it. */
  @Test
  void a_citation_resolves_to_the_words_it_named() {
    ingest("retries.md", "Alpha is unrelated.\n\nRetries are budgeted per run.");
    UUID paragraph = paragraphAt(2);

    assertTrue(citations.record(paragraph, null, null, "librarian", AT));

    Cited only = citations.recent(10).get(0);
    assertEquals(Standing.RESOLVES, only.standing());
    assertEquals(paragraph, only.paragraphId());
    assertEquals("Retries are budgeted per run.", only.paragraphText());
    assertEquals("retries.md", only.sourceName());
    assertEquals(2, only.paragraphOrdinal());
    assertEquals("librarian", only.agent());
    assertEquals(AT, only.citedAt());
  }

  /**
   * <b>The premise this whole slice rests on, exercised at last.</b>
   *
   * <p>A citation is made, the document is edited elsewhere and ingested again, and the citation
   * still resolves to the same words. Note that the paragraph cited is not the one that changed: an
   * edit anywhere in a document re-derives the whole of it, so this fails the moment the identity
   * rule stops matching unchanged text to its existing row.
   */
  @Test
  void a_citation_survives_a_re_ingest_that_left_its_paragraph_alone() {
    ingest("retries.md", "Alpha is unrelated.\n\nRetries are budgeted per run.");
    UUID cited = paragraphAt(2);
    citations.record(cited, null, null, "librarian", AT);

    ingest("retries.md", "Alpha is quite unrelated.\n\nRetries are budgeted per run.");

    Cited after = citations.recent(10).get(0);
    assertEquals(Standing.RESOLVES, after.standing());
    assertEquals(cited, after.paragraphId());
    assertEquals("Retries are budgeted per run.", after.paragraphText());
  }

  /**
   * And an edit to the cited paragraph leaves a citation that <b>says so</b>.
   *
   * <p>V18: "a dangling citation is a staleness signal and never a silent repoint at different
   * text". The row is still here, it still names the document and the position, and its text is
   * gone because the words are — which is a different answer from both "this citation never
   * existed" and "here are some other words".
   */
  @Test
  void a_citation_to_an_edited_paragraph_is_kept_and_says_the_paragraph_is_gone() {
    ingest("retries.md", "Alpha is unrelated.\n\nRetries are budgeted per run.");
    UUID cited = paragraphAt(2);
    citations.record(cited, null, null, "librarian", AT);

    ingest("retries.md", "Alpha is unrelated.\n\nRetries are budgeted per turn.");

    Cited after = citations.recent(10).get(0);
    assertEquals(Standing.PARAGRAPH_GONE, after.standing());
    assertNull(after.paragraphId(), "ON DELETE SET NULL did not fire");
    assertNull(after.paragraphText());
    // What is left is what a reader needs: which paper, and where in it.
    assertEquals("retries.md", after.sourceName());
    assertEquals(2, after.paragraphOrdinal());
    assertEquals("retries", after.title(), "the document is still here, titled");
    assertNotEquals(cited, paragraphAt(2));
  }

  /**
   * <b>And the document going away is a third answer and not the second.</b>
   *
   * <p><b>Nothing on this server deletes a document</b> — there is no {@code DELETE /v1/documents},
   * no {@code DELETE FROM documents} in {@code main}, and the retention sweep does not reach the
   * corpus. The row is deleted here directly, because what is being tested is the promise this
   * schema makes to the slice that adds that endpoint: {@code paragraphs.document_id} is already
   * {@code ON DELETE CASCADE}, so without {@code citations .paragraph_id}'s own key a delete would
   * take every citation into that document with it, silently.
   */
  @Test
  void a_citation_to_a_deleted_document_is_kept_and_says_the_document_is_gone() {
    UUID document = ingest("retries.md", "Retries are budgeted per run.");
    citations.record(paragraphAt(1), null, null, "librarian", AT);

    jdbc.update("DELETE FROM documents WHERE id = ?", document);

    Cited after = citations.recent(10).get(0);
    assertEquals(Standing.DOCUMENT_GONE, after.standing());
    assertNull(after.paragraphId());
    assertNull(after.documentId());
    assertNull(after.title());
    assertEquals("retries.md", after.sourceName(), "the name the answer cited is gone too");
    assertEquals(1, count(), "a citation cascaded away with its document");
  }

  /**
   * <b>The insert is the validation.</b>
   *
   * <p>A uuid that names no paragraph writes nothing and says so. That is the whole guard: a
   * stored-result handle an agent quoted, a memory id, or a uuid a model invented all land here and
   * all leave the table alone.
   */
  @Test
  void a_paragraph_id_the_corpus_does_not_hold_writes_nothing() {
    ingest("retries.md", "Retries are budgeted per run.");

    assertFalse(citations.record(UUID.randomUUID(), null, null, "librarian", AT));
    assertEquals(0, count());
  }

  // --- the three reads ----------------------------------------------------------

  @Test
  void what_one_conversation_cited_comes_back_in_the_order_it_said_so() {
    ingest("retries.md", "Alpha.\n\nBeta.\n\nGamma.");
    String conversation = conversation("cnv_0000000001aaa");
    citations.record(paragraphAt(3), conversation, 2, "librarian", LATER);
    citations.record(paragraphAt(1), conversation, 1, "librarian", AT);
    citations.record(paragraphAt(2), null, null, "librarian", AT);

    List<Cited> made = citations.madeIn(conversation, 10);

    assertEquals(List.of("Alpha.", "Gamma."), made.stream().map(Cited::paragraphText).toList());
    assertEquals(List.of(1, 2), made.stream().map(Cited::turnOrdinal).toList());
  }

  /**
   * The backlink counts a citation whose paragraph was edited away.
   *
   * <p>"Has anybody used this paper" and "is this citation still good" are different questions, and
   * {@code citations_of} answers the first — which is why {@link CitationStore#of} keys on the
   * document and not the paragraph.
   */
  @Test
  void what_has_cited_a_document_counts_a_citation_whose_paragraph_was_edited_away() {
    UUID document = ingest("retries.md", "Alpha.\n\nBeta.");
    citations.record(paragraphAt(1), null, null, "librarian", AT);
    citations.record(paragraphAt(2), null, null, "librarian", AT);

    ingest("retries.md", "Alpha.\n\nBeta, revised.");

    List<Cited> against = citations.of(document, 10);
    assertEquals(2, against.size());
    assertEquals(
        List.of(Standing.RESOLVES, Standing.PARAGRAPH_GONE),
        against.stream().map(Cited::standing).sorted().toList());
  }

  /** A run started on its own behalf cites like any other. */
  @Test
  void a_citation_from_a_run_in_no_conversation_is_a_citation() {
    ingest("retries.md", "Retries are budgeted per run.");

    assertTrue(citations.record(paragraphAt(1), null, null, "librarian", AT));

    Cited only = citations.recent(10).get(0);
    assertNull(only.conversationId());
    assertNull(only.turnOrdinal());
    assertEquals(Standing.RESOLVES, only.standing());
  }

  /** The listing's cap is the store's and not the caller's. */
  @Test
  void no_listing_returns_more_than_the_store_will_hand_out() {
    ingest("many.md", paragraphs(CitationStore.MOST_LISTED + 5));
    for (int ordinal = 1; ordinal <= CitationStore.MOST_LISTED + 5; ordinal++) {
      citations.record(paragraphAt(ordinal), null, null, "librarian", AT);
    }

    assertEquals(CitationStore.MOST_LISTED, citations.recent(1000).size());
    assertTrue(citations.recent(0).isEmpty());
  }

  /**
   * <b>A citation is not a listing of what the agent was shown.</b>
   *
   * <p>Said as a test because the shape most likely to be reached for later — "record every hit of
   * every search" — is one this table would accept without complaint and that would make every row
   * in it false. Ten passages in front of a model and one claim taken from one of them is one
   * citation.
   */
  @Test
  void one_answer_naming_one_paragraph_twice_is_one_citation() {
    ingest("retries.md", "Retries are budgeted per run.");
    UUID paragraph = paragraphAt(1);

    new Citations(citations, java.time.Clock.fixed(AT, java.time.ZoneOffset.UTC))
        .whatTheAnswerCited(
            librarian(), null, null, "Per run (" + paragraph + "). See also " + paragraph + ".");

    assertEquals(1, count());
  }

  @Test
  void model_citation_scope_is_filtered_before_limit_and_excludes_standalone_runs() {
    UUID document = ingest("paper.md", "A source paragraph.");
    UUID paragraph = paragraphAt(1);
    String own = conversation("cnv_own"),
        foreign = conversation("cnv_foreign"),
        global = conversation("cnv_global");
    jdbc.update(
        "INSERT INTO projects (name) VALUES ('ledger'), ('elsewhere') ON CONFLICT (name) DO NOTHING");
    jdbc.update(
        "UPDATE conversations SET project_id = (SELECT id FROM projects WHERE name = ?) WHERE id = ?",
        "ledger",
        own);
    jdbc.update(
        "UPDATE conversations SET project_id = (SELECT id FROM projects WHERE name = ?) WHERE id = ?",
        "elsewhere",
        foreign);
    citations.record(paragraph, own, 1, "reader", AT);
    citations.record(paragraph, foreign, 1, "reader", LATER);
    citations.record(paragraph, global, 1, "reader", LATER);
    citations.record(paragraph, null, null, "reader", LATER);

    var home = io.aeyer.plowshare.protocol.Home.of("ledger");
    assertEquals(
        List.of(own), citations.inHome(home, null, 1).stream().map(Cited::conversationId).toList());
    assertEquals(
        List.of(own),
        citations.inHome(home, document, 20).stream().map(Cited::conversationId).toList());
    assertTrue(citations.inHome(home, UUID.randomUUID(), 20).isEmpty());
    assertTrue(
        citations.inHome(io.aeyer.plowshare.protocol.Home.of("never-defined"), null, 20).isEmpty());
    assertEquals(
        List.of(global),
        citations.inHome(io.aeyer.plowshare.protocol.Home.global(), null, 20).stream()
            .map(Cited::conversationId)
            .toList());

    ingest("paper.md", "Different source text.");
    Cited stale = citations.inHome(home, document, 20).get(0);
    assertEquals(Standing.PARAGRAPH_GONE, stale.standing());
    assertNull(stale.paragraphText());
    assertEquals(own, stale.conversationId());
  }

  // --- fixtures -----------------------------------------------------------------

  private static io.aeyer.plowshare.server.agents.AgentDefinition librarian() {
    return new io.aeyer.plowshare.server.agents.AgentDefinition(
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

  private static String paragraphs(int count) {
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= count; i++) {
      text.append("Paragraph ").append(i).append(" claims something.\n\n");
    }
    return text.toString();
  }

  private UUID paragraphAt(int ordinal) {
    return jdbc.queryForObject("SELECT id FROM paragraphs WHERE ordinal = ?", UUID.class, ordinal);
  }

  /** A conversation row for the foreign key to land on. */
  private String conversation(String id) {
    // No `agent`: conversations_a_person_s_conversation_names_no_agent
    // refuses one on a `turn`, which is what a person's conversation is.
    jdbc.update(
        "INSERT INTO conversations (id, created_at, budget_total, budget_spent,"
            + " origin, lifecycle) VALUES (?, ?, 12, 0, 'turn', 'active')",
        id,
        AT.atOffset(java.time.ZoneOffset.UTC));
    return id;
  }

  private int count() {
    return jdbc.queryForObject("SELECT count(*) FROM citations", Integer.class);
  }
}
