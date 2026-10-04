package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.util.UUID;
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
 * <b>V26's DDL, and above all the one invariant the shortcut edge creates.</b>
 *
 * <p>V18 froze {@code paragraphs.document_id NOT NULL} and pre-specified the extension this
 * migration is: "a later slice that has a use for sections adds a {@code sections} table and a
 * NULLABLE {@code section_id} on {@code paragraphs}." So a paragraph now reaches its document by
 * two paths — directly, and through {@code section -> chapter -> document} — and <b>the failure
 * mode the redundancy creates is the two disagreeing</b>. That is what this class is mostly about.
 *
 * <p>These are assertions about the schema and not about {@link DocumentStore}, which is the point:
 * everything here is true of a {@code psql} session as well as of the writer, and a writer that
 * stopped maintaining the invariant would fail here rather than quietly produce a corpus whose two
 * edges point at different documents.
 */
@Tag("full-db")
@Testcontainers
class HierarchySchemaTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static TransactionTemplate transactions;

  @BeforeAll
  static void migrate() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @BeforeEach
  void freshCorpus() {
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, documents CASCADE");
  }

  // --- the two edges have to agree ----------------------------------------------

  @Test
  void a_paragraph_may_sit_in_a_section_of_its_own_document() {
    UUID document = document("one.md");
    UUID section = section(chapter(document, 1), 1);
    UUID paragraph = paragraph(document, 1);

    jdbc.update("UPDATE paragraphs SET section_id = ? WHERE id = ?", section, paragraph);

    assertEquals(
        section,
        jdbc.queryForObject(
            "SELECT section_id FROM paragraphs WHERE id = ?", UUID.class, paragraph));
  }

  /**
   * <b>The invariant, stated as the schema refusing the only way to break it.</b> Both rows are
   * legal on their own; what is refused is the pairing.
   */
  @Test
  void a_paragraph_cannot_sit_in_a_section_of_another_document() {
    UUID mine = document("mine.md");
    UUID theirs = document("theirs.md");
    UUID theirSection = section(chapter(theirs, 1), 1);
    UUID myParagraph = paragraph(mine, 1);

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE paragraphs SET section_id = ? WHERE id = ?", theirSection, myParagraph));
  }

  /** The same rule one level up, which is what makes the one above transitive. */
  @Test
  void a_section_cannot_sit_in_a_chapter_of_another_document() {
    UUID mine = document("mine.md");
    UUID theirs = document("theirs.md");
    UUID theirChapter = chapter(theirs, 1);

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO sections (id, chapter_id, document_id, ordinal, title)"
                    + " VALUES (?, ?, ?, 1, 'Results')",
                UUID.randomUUID(),
                theirChapter,
                mine));
  }

  /**
   * V18's own extension clause: the null is legal and means <em>derived before the hierarchy
   * existed</em>.
   */
  @Test
  void a_paragraph_that_belongs_to_no_section_is_legal() {
    UUID document = document("one.md");
    UUID paragraph = paragraph(document, 1);

    assertNull(
        jdbc.queryForObject(
            "SELECT section_id FROM paragraphs WHERE id = ?", UUID.class, paragraph));
  }

  // --- what a delete does, which is where V25 §6 got burned ---------------------

  /**
   * <b>The test V25 wished it had.</b> Its own comment records that a CHECK over two keys nulled by
   * two referential actions <em>in unspecified order</em> made a delete fail outright — "measured,
   * not reasoned about". The same delete now fires more actions than it did then, so it is asserted
   * rather than assumed.
   */
  @Test
  void deleting_a_document_that_has_a_whole_hierarchy_succeeds() {
    UUID document = document("one.md");
    UUID section = section(chapter(document, 1), 1);
    UUID paragraph = paragraph(document, 1);
    jdbc.update("UPDATE paragraphs SET section_id = ? WHERE id = ?", section, paragraph);

    jdbc.update("DELETE FROM documents WHERE id = ?", document);

    assertEquals(0, count("SELECT count(*) FROM paragraphs"));
    assertEquals(0, count("SELECT count(*) FROM sections"));
    assertEquals(0, count("SELECT count(*) FROM chapters"));
  }

  /**
   * <b>The reason the edge is {@code ON DELETE SET NULL} and not {@code ON DELETE CASCADE}</b>,
   * which is the one place this migration departs from the design document's own DDL.
   *
   * <p>A re-ingest replaces the hierarchy and must not replace the paragraphs: V18's identity rule,
   * V25's citations and three tests in {@code IngestServiceTest} all rest on an unchanged paragraph
   * keeping its id. Under {@code CASCADE}, dropping the old chapters would take every paragraph in
   * the document with it and every citation into it would go stale on an ingest that changed
   * nothing.
   */
  @Test
  void deleting_a_chapter_detaches_its_paragraphs_and_does_not_delete_them() {
    UUID document = document("one.md");
    UUID chapter = chapter(document, 1);
    UUID section = section(chapter, 1);
    UUID paragraph = paragraph(document, 1);
    jdbc.update("UPDATE paragraphs SET section_id = ? WHERE id = ?", section, paragraph);

    jdbc.update("DELETE FROM chapters WHERE id = ?", chapter);

    assertEquals(1, count("SELECT count(*) FROM paragraphs WHERE id = '" + paragraph + "'"));
    assertNull(
        jdbc.queryForObject(
            "SELECT section_id FROM paragraphs WHERE id = ?", UUID.class, paragraph));
  }

  // --- the ordinary constraints -------------------------------------------------

  @Test
  void ordinals_are_counted_from_one() {
    UUID document = document("one.md");
    assertThrows(DataIntegrityViolationException.class, () -> chapter(document, 0));
  }

  @Test
  void a_chapter_the_document_named_must_carry_its_title() {
    UUID document = document("one.md");
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO chapters (id, document_id, ordinal, title, is_synthetic)"
                    + " VALUES (?, ?, 1, NULL, FALSE)",
                UUID.randomUUID(),
                document));
  }

  @Test
  void a_synthetic_chapter_needs_no_title() {
    UUID document = document("one.md");
    jdbc.update(
        "INSERT INTO chapters (id, document_id, ordinal, title, is_synthetic)"
            + " VALUES (?, ?, 1, NULL, TRUE)",
        UUID.randomUUID(),
        document);
    assertEquals(1, count("SELECT count(*) FROM chapters"));
  }

  @Test
  void a_summary_may_be_absent_and_may_not_be_blank() {
    UUID document = document("one.md");
    UUID chapter = chapter(document, 1);
    jdbc.update("UPDATE chapters SET summary = NULL WHERE id = ?", chapter);
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE chapters SET summary = '' WHERE id = ?", chapter));
  }

  /**
   * V18's reason for {@code paragraphs_one_per_position}, one table up: a re-ingest that reshuffles
   * ordinals passes through states where two rows share one, and an immediate constraint would
   * refuse a collision that does not exist at commit.
   */
  @Test
  void two_chapters_may_share_an_ordinal_inside_a_transaction_and_not_at_commit() {
    UUID document = document("one.md");
    transactions.execute(
        status -> {
          UUID first = chapter(document, 1);
          UUID second = chapter(document, 2);
          jdbc.update("UPDATE chapters SET ordinal = 1 WHERE id = ?", second);
          jdbc.update("UPDATE chapters SET ordinal = 2 WHERE id = ?", first);
          return null;
        });
    assertEquals(2, count("SELECT count(*) FROM chapters"));

    // And the exception type is itself the evidence of deferral: an
    // immediate constraint would have been the UPDATE's own
    // DataIntegrityViolationException, and this one arrives from the commit.
    assertThrows(
        org.springframework.transaction.TransactionSystemException.class,
        () ->
            transactions.execute(
                status -> {
                  jdbc.update("UPDATE chapters SET ordinal = 1 WHERE document_id = ?", document);
                  return null;
                }));
  }

  // --- what the document row gained ---------------------------------------------

  @Test
  void a_document_records_what_it_calls_its_top_level_groupings() {
    UUID document = document("one.md");
    jdbc.update("UPDATE documents SET top_level_label = 'SECTION' WHERE id = ?", document);
    assertEquals(
        "SECTION",
        jdbc.queryForObject(
            "SELECT top_level_label FROM documents WHERE id = ?", String.class, document));
  }

  @Test
  void a_document_cannot_call_its_groupings_something_the_prompts_cannot_say() {
    UUID document = document("one.md");
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update("UPDATE documents SET top_level_label = 'Volume' WHERE id = ?", document));
  }

  // --- the bibliography, which is not `citations` --------------------------------

  @Test
  void a_document_holds_its_bibliography_entries_numbered_from_one() {
    UUID document = document("one.md");
    jdbc.update(
        "INSERT INTO document_references (id, document_id, ref_num, raw)"
            + " VALUES (?, ?, 1, 'Wagner, A. Refuting conjectures. 2021.')",
        UUID.randomUUID(),
        document);
    assertEquals(1, count("SELECT count(*) FROM document_references"));

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO document_references (id, document_id, ref_num, raw)"
                    + " VALUES (?, ?, 0, 'x')",
                UUID.randomUUID(),
                document));
  }

  /**
   * The two tables that share a word and not a concept: V25's `citations` is what an answer took
   * from the corpus, and it is untouched by this one.
   */
  @Test
  void a_bibliography_entry_is_not_a_citation() {
    UUID document = document("one.md");
    jdbc.update(
        "INSERT INTO document_references (id, document_id, ref_num, raw)"
            + " VALUES (?, ?, 1, 'Wagner, A. 2021.')",
        UUID.randomUUID(),
        document);
    assertEquals(0, count("SELECT count(*) FROM citations"));
  }

  // --- fixtures -----------------------------------------------------------------

  private UUID document(String sourceName) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO documents (id, source_name, title, content_hash, text_hash,"
            + " byte_size, ingested_at, ingested_by) VALUES (?, ?, ?, 'h', 'h', 1, ?,"
            + " 'test')",
        id,
        sourceName,
        sourceName,
        OffsetDateTime.now());
    return id;
  }

  private UUID chapter(UUID document, int ordinal) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO chapters (id, document_id, ordinal, title, summary)"
            + " VALUES (?, ?, ?, 'Results', 'what it found')",
        id,
        document,
        ordinal);
    return id;
  }

  private UUID section(UUID chapter, int ordinal) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO sections (id, chapter_id, document_id, ordinal, title)"
            + " SELECT ?, ?, c.document_id, ?, 'Method' FROM chapters c WHERE c.id = ?",
        id,
        chapter,
        ordinal,
        chapter);
    return id;
  }

  private UUID paragraph(UUID document, int ordinal) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO paragraphs (id, document_id, text, content_hash, occurrence,"
            + " ordinal) VALUES (?, ?, 'words', ?, 1, ?)",
        id,
        document,
        "hash" + ordinal,
        ordinal);
    return id;
  }

  private int count(String sql) {
    Integer found = jdbc.queryForObject(sql, Integer.class);
    return found == null ? 0 : found;
  }
}
