package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
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

/**
 * <b>The hierarchy through the whole pipeline</b> — an upload in, chapters, sections and paragraphs
 * hanging off them out.
 *
 * <p>{@code ChapterDetectorTest} and {@code SectionDetectorTest} pin the rules over strings; {@code
 * HierarchySchemaTest} pins what the schema will accept. Neither can catch the failure that has no
 * symptom, which is the two of them agreeing about a document nothing ever ingested. Here the
 * detectors, the derivation, the store and V26 meet, and the assertions are about rows.
 *
 * <p><b>The PDF is why this class exists at all.</b> Anchor's corpus is papers, {@code
 * ChapterDetector}'s second precedence branch reads the PDF outline, and until stage 1 this server
 * could not read a PDF and had no outline to give it. {@code
 * the_documents_own_outline_becomes_its_chapters} is the first time that branch runs on real bytes.
 */
@Testcontainers
class HierarchyIngestTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;

  private DocumentStore store;
  private IngestService ingest;

  private static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");
  private static final BooleanSupplier NEVER = () -> false;

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
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, documents CASCADE");
    store = new DocumentStore(jdbc, transactions);
    ingest =
        new IngestService(
            store,
            new AxisEmbeddings(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            ShippedChunking.SHIPPED,
            8);
  }

  // --- a paper with headings ----------------------------------------------------

  private static final String PAPER =
      """
            1. Introduction
            The conjecture has stood for forty years and we refute it here.

            2. Method
            We search the space of graphs with a learned policy.

            3. Results
            The counterexample has eleven vertices and is reproduced below.
            """;

  @Test
  void a_papers_numbered_headings_become_its_sections() {
    UUID document = ingest("paper.md", PAPER);

    assertEquals(1, count("SELECT count(*) FROM chapters WHERE document_id = ?", document));
    assertEquals(
        List.of("1. Introduction", "2. Method", "3. Results"),
        jdbc.queryForList(
            "SELECT title FROM sections WHERE document_id = ?" + " ORDER BY ordinal",
            String.class,
            document));
  }

  /**
   * The document declares no chapter, so it gets one — synthetic, over all of it, and never
   * rendered by that name.
   */
  @Test
  void a_paper_with_no_chapter_heading_gets_the_synthetic_one() {
    UUID document = ingest("paper.md", PAPER);

    assertTrue(
        jdbc.queryForObject(
            "SELECT is_synthetic FROM chapters WHERE document_id = ?", Boolean.class, document));
    assertEquals(
        SyntheticTitles.CHAPTER,
        jdbc.queryForObject(
            "SELECT title FROM chapters WHERE document_id = ?", String.class, document));
  }

  /**
   * <b>The point of the whole stage:</b> a paragraph is reachable through the hierarchy and not
   * only from its document.
   */
  @Test
  void every_paragraph_hangs_off_the_section_whose_heading_it_is_under() {
    UUID document = ingest("paper.md", PAPER);

    assertEquals(
        0,
        count(
            "SELECT count(*) FROM paragraphs WHERE document_id = ? AND section_id IS NULL",
            document));
    assertEquals(
        List.of("1. Introduction", "2. Method", "3. Results"),
        jdbc.queryForList(
            "SELECT s.title FROM paragraphs p JOIN sections s ON s.id = p.section_id"
                + " WHERE p.document_id = ? ORDER BY p.ordinal",
            String.class,
            document));
  }

  /**
   * A heading is not part of the paragraph under it, which is what splitting within a section's
   * range buys.
   */
  @Test
  void a_heading_is_not_swallowed_into_the_paragraph_beneath_it() {
    UUID document = ingest("paper.md", PAPER);

    for (String text :
        jdbc.queryForList(
            "SELECT text FROM paragraphs WHERE document_id = ?", String.class, document)) {
      assertFalse(text.startsWith("1. Introduction"), text);
      assertFalse(text.startsWith("2. Method"), text);
    }
  }

  @Test
  void the_document_records_what_it_calls_its_own_groupings() {
    UUID document = ingest("paper.md", PAPER);

    assertEquals(
        "SECTION",
        jdbc.queryForObject(
            "SELECT top_level_label FROM documents WHERE id = ?", String.class, document));
  }

  /**
   * A document that declares nothing at all is one synthetic chapter holding one synthetic section
   * holding everything — Anchor's shape for untagged prose, and the state most of this corpus is in
   * today.
   */
  @Test
  void prose_with_no_headings_is_one_synthetic_chapter_over_one_synthetic_section() {
    UUID document =
        ingest("notes.md", "Retries are budgeted per run.\n\nAlpha is unrelated to that.\n");

    assertEquals(1, count("SELECT count(*) FROM chapters WHERE document_id = ?", document));
    assertEquals(1, count("SELECT count(*) FROM sections WHERE document_id = ?", document));
    assertEquals(
        2,
        count(
            "SELECT count(*) FROM paragraphs WHERE document_id = ?" + " AND section_id IS NOT NULL",
            document));
  }

  // --- the bibliography ---------------------------------------------------------

  private static final String WITH_REFERENCES =
      PAPER
          + """

            References
            [1] Wagner, A. Constructions in combinatorics. 2021.
            [2] Erdos, P. On a problem of graph theory. 1965.
            """;

  /**
   * The exclusion and what pays it back, in one document. The references section is not a section,
   * its entries are not paragraphs and cost no model call in the cascade — and the list survives,
   * in the table that is for it.
   */
  @Test
  void a_references_section_is_dropped_from_the_hierarchy_and_kept_as_a_bibliography() {
    UUID document = ingest("paper.md", WITH_REFERENCES);

    assertEquals(3, count("SELECT count(*) FROM sections WHERE document_id = ?", document));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM paragraphs WHERE document_id = ?" + " AND text LIKE '%Wagner%'",
            document));
    assertEquals(
        List.of(
            "Wagner, A. Constructions in combinatorics. 2021.",
            "Erdos, P. On a problem of graph theory. 1965."),
        jdbc.queryForList(
            "SELECT raw FROM document_references WHERE document_id = ?" + " ORDER BY ref_num",
            String.class,
            document));
  }

  // --- the PDF outline, which is the branch that had no input until stage 1 ------

  /**
   * <b>End to end from real PDF bytes.</b> The outline is the author's own table of contents, read
   * out of the file by {@code PdfExtraction}, and it is the only chapter-detection branch here that
   * is not a guess about prose.
   */
  @Test
  void the_documents_own_outline_becomes_its_chapters() {
    byte[] pdf =
        Pdfs.outlined(
            List.of("Antichains", "Diameters"),
            List.of(
                Pdfs.Page.of(
                    Pdfs.Paragraph.of("Antichains"),
                    Pdfs.Paragraph.of(
                        "A family of sets no one of which contains",
                        "another is what we study first.")),
                Pdfs.Page.of(
                    Pdfs.Paragraph.of("Diameters"),
                    Pdfs.Paragraph.of(
                        "The diameter of a family is the largest",
                        "distance between two of its members."))));

    UUID document = ingest("paper.pdf", pdf);

    assertEquals(
        List.of("Antichains", "Diameters"),
        jdbc.queryForList(
            "SELECT title FROM chapters WHERE document_id = ?" + " ORDER BY ordinal",
            String.class,
            document));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM chapters WHERE document_id = ?" + " AND is_synthetic", document));
    assertTrue(
        count(
                "SELECT count(*) FROM paragraphs WHERE document_id = ?"
                    + " AND section_id IS NOT NULL",
                document)
            > 0);
  }

  // --- what a re-ingest does to the two halves ----------------------------------

  /**
   * <b>The property everything else rests on, now with a hierarchy in the way.</b> V26 hangs
   * paragraphs off sections and the writer drops every chapter before it re-derives them; if that
   * edge cascaded rather than detaching, this assertion is where it would show — every id in the
   * document would move on an ingest that changed nothing, and every V25 citation into it would go
   * stale.
   */
  @Test
  void a_re_ingest_rebuilds_the_hierarchy_and_keeps_every_paragraph_id() {
    UUID document = ingest("paper.md", PAPER);
    Map<Integer, UUID> before = paragraphIds(document);
    List<UUID> oldSections = sectionIds(document);

    ingest("paper.md", PAPER);

    assertEquals(before, paragraphIds(document));
    assertNotEquals(
        oldSections,
        sectionIds(document),
        "the sections were expected to be re-derived, not reused");
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM paragraphs WHERE document_id = ? AND section_id IS NULL",
            document));
  }

  /** And the hierarchy does not accumulate: one derivation is in the corpus, not one per ingest. */
  @Test
  void a_re_ingest_leaves_one_hierarchy_behind_and_not_two() {
    UUID document = ingest("paper.md", WITH_REFERENCES);
    ingest("paper.md", WITH_REFERENCES);

    assertEquals(1, count("SELECT count(*) FROM chapters WHERE document_id = ?", document));
    assertEquals(3, count("SELECT count(*) FROM sections WHERE document_id = ?", document));
    assertEquals(
        2, count("SELECT count(*) FROM document_references WHERE document_id = ?", document));
  }

  /**
   * An edited document re-derives around the change: the edited paragraph takes a new id, the rest
   * keep theirs, and all of them end up under a section again.
   */
  @Test
  void an_edit_moves_one_paragraph_and_leaves_the_hierarchy_whole() {
    UUID document = ingest("paper.md", PAPER);
    Map<Integer, UUID> before = paragraphIds(document);

    ingest("paper.md", PAPER.replace("eleven vertices", "twelve vertices"));
    Map<Integer, UUID> after = paragraphIds(document);

    assertEquals(before.get(1), after.get(1));
    assertNotEquals(before.get(3), after.get(3));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM paragraphs WHERE document_id = ? AND section_id IS NULL",
            document));
  }

  /**
   * <b>A section a paper added is a section, and the ordinals reshuffle at commit.</b> V26 made
   * both uniqueness constraints DEFERRABLE for exactly this, and nothing else in the suite passes a
   * document through it.
   */
  @Test
  void a_section_inserted_in_the_middle_renumbers_the_ones_after_it() {
    UUID document = ingest("paper.md", PAPER);

    ingest(
        "paper.md",
        PAPER.replace(
            "3. Results",
            """
                3. Ablations
                We remove the learned policy and the search stalls.

                4. Results"""));

    assertEquals(
        List.of("1. Introduction", "2. Method", "3. Ablations", "4. Results"),
        jdbc.queryForList(
            "SELECT title FROM sections WHERE document_id = ?" + " ORDER BY ordinal",
            String.class,
            document));
  }

  // --- the sentinel never leaves the database ------------------------------------

  /**
   * The last line of defence, asserted over the surface an ingest actually produces. {@code
   * Outcome.text} is read by a model — it is the answer a job hands back — and a sentinel title
   * reaching it is precisely the failure {@link StructuralRef} exists to prevent.
   */
  @Test
  void no_sentinel_reaches_the_outcome_a_model_reads() {
    Outcome outcome = run("notes.md", "Prose with no heading in it at all.\n");

    assertFalse(outcome.text().contains("SYNTHETIC"), outcome.text());
    assertFalse(outcome.detail().contains("SYNTHETIC"), outcome.detail());
  }

  // --- fixtures -----------------------------------------------------------------

  private UUID ingest(String name, String text) {
    run(name, text);
    return documentId(name);
  }

  private UUID ingest(String name, byte[] bytes) {
    ingest.ingest(name, TextExtraction.extract(name, bytes), bytes.length, "test", NEVER);
    return documentId(name);
  }

  private Outcome run(String name, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return ingest.ingest(name, TextExtraction.extract(name, bytes), bytes.length, "test", NEVER);
  }

  private UUID documentId(String sourceName) {
    return jdbc.queryForObject(
        "SELECT id FROM documents WHERE source_name = ?", UUID.class, sourceName);
  }

  private Map<Integer, UUID> paragraphIds(UUID document) {
    Map<Integer, UUID> found = new LinkedHashMap<>();
    jdbc.query(
        "SELECT ordinal, id FROM paragraphs WHERE document_id = ? ORDER BY ordinal",
        rs -> {
          found.put(rs.getInt("ordinal"), (UUID) rs.getObject("id"));
        },
        document);
    return found;
  }

  private List<UUID> sectionIds(UUID document) {
    return jdbc.queryForList(
        "SELECT id FROM sections WHERE document_id = ? ORDER BY ordinal", UUID.class, document);
  }

  private int count(String sql, Object... args) {
    Integer found = jdbc.queryForObject(sql, Integer.class, args);
    return found == null ? 0 : found;
  }

  /**
   * One axis per distinct string, so nothing here depends on a model and every vector is exactly
   * comparable. {@code IngestServiceTest} carries the same stub for the same reason.
   */
  private static final class AxisEmbeddings implements EmbeddingClient {

    @Override
    public float[] embed(String text) {
      return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
      List<float[]> vectors = new ArrayList<>();
      for (String text : texts) {
        float[] vector = new float[768];
        vector[Math.floorMod(text.hashCode(), 768)] = 1.0f;
        vectors.add(vector);
      }
      return vectors;
    }
  }
}
