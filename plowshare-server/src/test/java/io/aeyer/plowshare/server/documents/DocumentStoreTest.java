package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The corpus against a real Postgres, and above all <b>the paragraph identity rule</b>.
 *
 * <p>Spec decision 1 puts that rule in code rather than in a primary key, so this class is where it
 * is actually pinned down: every assertion below about an id surviving, or not surviving, a
 * re-ingest is a statement the schema does not make and cannot.
 *
 * <p>Testcontainers and not H2, for {@code MemoryStoreTest}'s reason: the schema uses {@code
 * vector}, an HNSW index and a {@code DEFERRABLE} unique constraint, none of which an in-memory
 * stand-in has.
 *
 * <p>No embedding client anywhere in this class. The store writes chunks unembedded and vectors are
 * attached afterwards, which is exactly what lets a test of the corpus never go near a model.
 */
@Tag("full-db")
@Testcontainers
class DocumentStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;

  private DocumentStore store;

  @BeforeAll
  static void migrate() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    // A REAL transaction and not UnitOfWork.NONE, which the archive's tests
    // can use and this one cannot: `paragraphs_one_per_position` is
    // DEFERRABLE INITIALLY DEFERRED, and a deferred constraint in autocommit
    // is an immediate one — every statement is its own transaction, so the
    // check runs at the end of each. A re-ingest that moves ordinals would
    // then be refused for a collision that does not exist at commit.
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
  }

  private static final Instant AT = Instant.parse("2026-09-04T09:00:00Z");

  private DocumentStore.Written ingest(String sourceName, String text) {
    Extracted extracted =
        TextExtraction.extract(sourceName, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return store.write(
        sourceName,
        extracted,
        text.length(),
        "test",
        AT,
        Derivation.derive(extracted, ShippedChunking.SHIPPED));
  }

  /** Every paragraph of a document, ordinal to id. */
  private Map<Integer, UUID> paragraphIds(UUID documentId) {
    return jdbc.query(
        "SELECT ordinal, id FROM paragraphs WHERE document_id = ? ORDER BY ordinal",
        rs -> {
          Map<Integer, UUID> found = new java.util.LinkedHashMap<>();
          while (rs.next()) {
            found.put(rs.getInt("ordinal"), (UUID) rs.getObject("id"));
          }
          return found;
        },
        documentId);
  }

  private List<String> paragraphTexts(UUID documentId) {
    return jdbc.queryForList(
        "SELECT text FROM paragraphs WHERE document_id = ? ORDER BY ordinal",
        String.class,
        documentId);
  }

  // --- a first ingest -------------------------------------------------------

  @Test
  void a_document_arrives_with_its_paragraphs_and_chunks() {
    DocumentStore.Written written = ingest("notes.md", "First one.\n\nSecond one.");

    assertEquals(2, written.added());
    assertEquals(0, written.kept());
    assertEquals(0, written.removed());
    assertEquals(List.of("First one.", "Second one."), paragraphTexts(written.documentId()));
    assertEquals(2, chunkCount(written.documentId()));
  }

  @Test
  void a_document_is_found_by_the_name_it_was_filed_under() {
    DocumentStore.Written written = ingest("notes.md", "Something.");

    DocumentStore.StoredDocument found = store.find("notes.md").orElseThrow();
    assertEquals(written.documentId(), found.id());
    assertEquals("notes", found.title());
    assertEquals("test", found.ingestedBy());
    assertTrue(store.find("nothing.md").isEmpty());
  }

  /**
   * Two names are two documents, whatever the bytes say. Identity is the name, and the content hash
   * is a fact about the document rather than its identity — see V18.
   */
  @Test
  void the_same_text_under_two_names_is_two_documents() {
    DocumentStore.Written one = ingest("a.md", "Identical.");
    DocumentStore.Written two = ingest("b.md", "Identical.");

    assertNotEquals(one.documentId(), two.documentId());
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM documents", Integer.class));
  }

  // --- the identity rule ----------------------------------------------------

  /**
   * The property the whole schema decision exists for.
   *
   * <p>Anchor's re-ingest is cascade-delete-then-insert, so every paragraph and chunk uuid moves.
   * Here, unchanged text keeps its id, and a citation pointing at it goes on meaning the same text.
   */
  @Test
  void re_ingesting_unchanged_text_keeps_every_paragraph_id() {
    DocumentStore.Written first = ingest("notes.md", "Alpha.\n\nBeta.\n\nGamma.");
    Map<Integer, UUID> before = paragraphIds(first.documentId());

    DocumentStore.Written again = ingest("notes.md", "Alpha.\n\nBeta.\n\nGamma.");

    assertEquals(first.documentId(), again.documentId());
    assertEquals(3, again.kept());
    assertEquals(0, again.added());
    assertEquals(0, again.removed());
    assertEquals(before, paragraphIds(again.documentId()));
  }

  /**
   * Changed text gets a new id, and its neighbours do not.
   *
   * <p>The second half is the one that matters: an edit to one paragraph must not invalidate every
   * citation into the document.
   */
  @Test
  void an_edited_paragraph_gets_a_new_id_and_leaves_its_neighbours_alone() {
    DocumentStore.Written first = ingest("notes.md", "Alpha.\n\nBeta.\n\nGamma.");
    Map<Integer, UUID> before = paragraphIds(first.documentId());

    DocumentStore.Written again = ingest("notes.md", "Alpha.\n\nBeta, revised.\n\nGamma.");
    Map<Integer, UUID> after = paragraphIds(again.documentId());

    assertEquals(before.get(1), after.get(1));
    assertNotEquals(before.get(2), after.get(2));
    assertEquals(before.get(3), after.get(3));
    assertEquals(2, again.kept());
    assertEquals(1, again.added());
    assertEquals(1, again.removed());
  }

  /**
   * Position is a mutable column and not identity.
   *
   * <p>Inserting a paragraph at the top moves every ordinal below it and must move no id. This is
   * also the case the {@code DEFERRABLE} unique constraint exists for: every ordering of those
   * updates passes through a state where two rows share an ordinal.
   */
  @Test
  void inserting_a_paragraph_moves_ordinals_and_no_ids() {
    DocumentStore.Written first = ingest("notes.md", "Alpha.\n\nBeta.");
    Map<Integer, UUID> before = paragraphIds(first.documentId());

    DocumentStore.Written again = ingest("notes.md", "Preface.\n\nAlpha.\n\nBeta.");
    Map<Integer, UUID> after = paragraphIds(again.documentId());

    assertEquals(before.get(1), after.get(2), "Alpha moved from ordinal 1 to 2 and changed id");
    assertEquals(before.get(2), after.get(3), "Beta moved from ordinal 2 to 3 and changed id");
    assertFalse(before.containsValue(after.get(1)));
    assertEquals(List.of("Preface.", "Alpha.", "Beta."), paragraphTexts(again.documentId()));
  }

  @Test
  void a_removed_paragraph_and_its_chunks_are_gone() {
    DocumentStore.Written first = ingest("notes.md", "Alpha.\n\nBeta.\n\nGamma.");
    assertEquals(3, chunkCount(first.documentId()));

    DocumentStore.Written again = ingest("notes.md", "Alpha.\n\nGamma.");

    assertEquals(1, again.removed());
    assertEquals(List.of("Alpha.", "Gamma."), paragraphTexts(again.documentId()));
    assertEquals(2, chunkCount(again.documentId()));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM paragraphs", Integer.class));
  }

  /**
   * The occurrence term, doing the job it exists for.
   *
   * <p>Three identical paragraphs are three rows with one hash. On re-ingest each has to find its
   * own row rather than all three matching the first.
   */
  @Test
  void repeated_paragraphs_each_keep_their_own_id() {
    DocumentStore.Written first = ingest("notes.md", "Notes\n\nBody.\n\nNotes\n\nNotes");
    Map<Integer, UUID> before = paragraphIds(first.documentId());
    assertEquals(4, before.size());

    DocumentStore.Written again = ingest("notes.md", "Notes\n\nBody.\n\nNotes\n\nNotes");

    assertEquals(4, again.kept());
    assertEquals(before, paragraphIds(again.documentId()));
  }

  /**
   * Dropping one of three identical paragraphs leaves two rows, and the remaining ones are the
   * first two occurrences.
   */
  @Test
  void dropping_one_of_several_identical_paragraphs_retires_the_last_occurrence() {
    DocumentStore.Written first = ingest("notes.md", "Notes\n\nNotes\n\nNotes");
    Map<Integer, UUID> before = paragraphIds(first.documentId());

    DocumentStore.Written again = ingest("notes.md", "Notes\n\nNotes");

    assertEquals(2, again.kept());
    assertEquals(1, again.removed());
    Map<Integer, UUID> after = paragraphIds(again.documentId());
    assertEquals(before.get(1), after.get(1));
    assertEquals(before.get(2), after.get(2));
  }

  // --- chunks and vectors ---------------------------------------------------

  /**
   * A chunk is written with no vector, and the vector is attached afterwards.
   *
   * <p>V1 states the rule for memories — "a memory is written before it is embedded, and an
   * embedding endpoint being down must lose the vector, never the write" — and it is the same rule
   * here, with one thing added: a chunk that arrives unembedded can be embedded later from its own
   * row, because the text is right there.
   */
  @Test
  void chunks_are_written_unembedded_and_a_vector_is_attached_afterwards() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.\n\nBeta.");

    List<DocumentStore.UnembeddedChunk> waiting = store.unembedded(written.documentId());
    assertEquals(2, waiting.size());
    assertEquals(2, store.countUnembedded(written.documentId()));

    store.attach(waiting.get(0).id(), vector(0.5f));

    assertEquals(1, store.countUnembedded(written.documentId()));
    assertEquals(1, store.unembedded(written.documentId()).size());
  }

  /**
   * The re-ingest saving that falls out of the identity rule.
   *
   * <p>A paragraph that kept its id kept its text, so its chunks are what the same chunker would
   * produce from the same text — and their vectors are still correct. Re-deriving them would pay
   * the embedding cost of a whole document to arrive back where it started.
   */
  @Test
  void an_unchanged_paragraph_keeps_its_chunks_and_their_vectors() {
    DocumentStore.Written first = ingest("notes.md", "Alpha.\n\nBeta.");
    for (DocumentStore.UnembeddedChunk chunk : store.unembedded(first.documentId())) {
      store.attach(chunk.id(), vector(0.25f));
    }
    assertEquals(0, store.countUnembedded(first.documentId()));
    Map<UUID, String> chunksBefore = chunksByText(first.documentId());

    DocumentStore.Written again = ingest("notes.md", "Alpha.\n\nBeta, revised.");

    // Alpha's chunk is untouched — same row, still embedded. Beta's is a
    // new row awaiting a vector.
    assertEquals(1, store.countUnembedded(again.documentId()));
    Map<UUID, String> chunksAfter = chunksByText(again.documentId());
    UUID alphaChunk =
        chunksBefore.entrySet().stream()
            .filter(e -> e.getValue().equals("Alpha."))
            .findFirst()
            .orElseThrow()
            .getKey();
    assertEquals("Alpha.", chunksAfter.get(alphaChunk));
  }

  @Test
  void a_chunk_records_whether_the_chunker_cut_it() {
    ingest("prose.md", "Alpha.");
    assertEquals(
        List.of(false), jdbc.queryForList("SELECT split_mid_sentence FROM chunks", Boolean.class));

    ingest("table.md", "x".repeat(7000));
    assertTrue(
        jdbc.queryForObject("SELECT count(*) FROM chunks WHERE split_mid_sentence", Integer.class)
            > 1);
  }

  @Test
  void the_stored_size_is_the_size_the_chunker_measured() {
    DocumentStore.Written written = ingest("notes.md", "Alpha and beta and gamma.");

    assertEquals(
        List.of(25),
        jdbc.queryForList(
            "SELECT byte_size FROM chunks c JOIN paragraphs p ON p.id = c.paragraph_id"
                + " WHERE p.document_id = ?",
            Integer.class,
            written.documentId()));
  }

  // --- the read side --------------------------------------------------------

  /**
   * The hit is a chunk and the citation is a paragraph.
   *
   * <p>Nearest first, and every row carries the paragraph it came from and the document that holds
   * it — because a chunk is an artefact of the chunker and nothing should ever cite one. V18 says
   * so at the {@code chunks} table and this is the read that depends on it.
   */
  @Test
  void the_nearest_chunks_come_back_first_carrying_the_paragraph_they_cite() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.\n\nBeta.\n\nGamma.");
    embed("Alpha.", axis(0));
    embed("Beta.", blend(1f, 1f));
    embed("Gamma.", axis(1));

    List<DocumentStore.Hit> hits = store.searchByVector(axis(0), 10);

    assertEquals(
        List.of("Alpha.", "Beta.", "Gamma."),
        hits.stream().map(DocumentStore.Hit::chunkText).toList());
    DocumentStore.Hit nearest = hits.get(0);
    assertEquals(written.documentId(), nearest.documentId());
    assertEquals("notes.md", nearest.sourceName());
    assertEquals("notes", nearest.title());
    assertEquals("Alpha.", nearest.paragraphText());
    assertEquals(1, nearest.paragraphOrdinal());
    assertEquals(paragraphIds(written.documentId()).get(1), nearest.paragraphId());
    // 1 - cosine distance, so an identical direction is 1 and an orthogonal
    // one is 0. Anchor's `1 - (embedding <=> ?)` exactly.
    assertEquals(1.0, nearest.similarity(), 1e-6);
    assertEquals(0.0, hits.get(2).similarity(), 1e-6);
  }

  /**
   * <b>A hit carries what its document argues, and it does so from both halves.</b>
   *
   * <p>V22 stored {@code documents.summary} and nothing read it back out on the retrieval path:
   * both queries joined {@code documents} for its name and title and selected no summary, so the
   * top of the summariser cascade — the one output V22 calls the sentence that "says what a
   * document in the corpus IS" — was reachable from no search, no tool and therefore no agent. This
   * is the join column that closes that, and {@code DocumentTools} is what renders it.
   *
   * <p><b>Both halves in one test, and that is the point of it rather than thoroughness.</b> {@code
   * SEARCH_SQL} and {@code LEXICAL_SQL} are two strings that must produce the same {@link
   * DocumentStore.Hit}: the lexical half selects the cosine distance it does not rank by precisely
   * so that no surface has to learn a second kind of hit. A column added to one and not the other
   * would give a model the document's argument for a passage found by meaning and withhold it for
   * the same passage found by its words.
   *
   * <p>Null when nothing has summarised the document, which is the ordinary state of a corpus whose
   * cascade has not run or did not reach the top — asserted here so that the renderer's silence has
   * something under it.
   */
  @Test
  void a_hit_carries_what_its_document_argues_whichever_half_found_it() {
    DocumentStore.Written written = ingest("notes.md", "Retry budget here.");
    embed("Retry budget here.", axis(0));

    assertNull(
        store.searchByVector(axis(0), 10).get(0).documentSummary(),
        "a document nothing has summarised reports an argument anyway");
    assertNull(store.searchByText("retry budget", axis(0), 10).get(0).documentSummary());

    store.attachDocumentSummary(
        written.documentId(), "Argues retry budgets are per run and rejects per call.");

    assertEquals(
        "Argues retry budgets are per run and rejects per call.",
        store.searchByVector(axis(0), 10).get(0).documentSummary());
    assertEquals(
        "Argues retry budgets are per run and rejects per call.",
        store.searchByText("retry budget", axis(0), 10).get(0).documentSummary(),
        "the word half of the search hands over a hit the meaning half would have"
            + " carried an argument on");
  }

  /**
   * A chunk with no vector is not a hit, however the question is phrased.
   *
   * <p>{@code MemoryStore.searchByVector}'s {@code embedding IS NOT NULL} filter, and the reason it
   * cannot be left to the index: {@code chunks_by_vector} holds no NULL rows, so the filter is
   * redundant while the index is used — and on a plan that falls back to a scan, {@code NULL <=> v}
   * is NULL and sorts <em>last</em> rather than nowhere, so an under-full answer would be padded
   * with chunks nothing compared.
   */
  @Test
  void a_chunk_with_no_vector_is_never_a_hit() {
    ingest("notes.md", "Alpha.\n\nBeta.");
    embed("Alpha.", axis(0));

    List<DocumentStore.Hit> hits = store.searchByVector(axis(0), 10);

    assertEquals(List.of("Alpha."), hits.stream().map(DocumentStore.Hit::chunkText).toList());
  }

  @Test
  void the_limit_is_the_most_that_comes_back() {
    ingest("notes.md", "Alpha.\n\nBeta.\n\nGamma.");
    embed("Alpha.", axis(0));
    embed("Beta.", blend(1f, 1f));
    embed("Gamma.", axis(1));

    assertEquals(2, store.searchByVector(axis(0), 2).size());
  }

  /** A limit of none asks the database nothing, as {@code MemoryStore.searchByVector} does. */
  @Test
  void a_limit_of_none_is_an_empty_answer_and_no_query() {
    ingest("notes.md", "Alpha.");
    embed("Alpha.", axis(0));

    assertTrue(store.searchByVector(axis(0), 0).isEmpty());
  }

  /**
   * <b>The index V18 built is the index this read uses.</b>
   *
   * <p>The whole argument for {@code chunks_by_vector} is that documents must not inherit {@code
   * MemoryStore}'s exact scan, and nothing about the SQL makes that visible from the outside — a
   * query that quietly fell back to a sequential scan would return the same rows and pass every
   * other test here.
   *
   * <p>{@code enable_seqscan = off} is what makes the assertion about the <em>query</em> rather
   * than about the row count: on a three-row table the planner will scan whatever the SQL says, so
   * this asks the narrower and more durable question — can this query be served by that index at
   * all. A second {@code ORDER BY} key is the way to lose it, which is why {@code searchByVector}
   * orders by distance alone and breaks ties in Java.
   */
  @Test
  void the_search_is_served_by_the_hnsw_index() {
    ingest("notes.md", "Alpha.\n\nBeta.");
    embed("Alpha.", axis(0));
    embed("Beta.", axis(1));

    String plan = explainSearch();

    assertTrue(
        plan.contains("chunks_by_vector"),
        "the vector search is not using the HNSW index V18 built for it:\n" + plan);
  }

  /**
   * What the corpus can and cannot be asked, in one answer.
   *
   * <p>{@code MemoryStore.countUnsearchable}'s counterpart at corpus scale, and it exists so an
   * empty search result is never a lie: a document stored while the embedding endpoint was down
   * holds every word of its text and answers no question at all.
   */
  @Test
  void coverage_counts_what_a_search_can_and_cannot_reach() {
    assertEquals(new DocumentStore.Coverage(0, 0), store.coverage());

    ingest("notes.md", "Alpha.\n\nBeta.\n\nGamma.");
    assertEquals(new DocumentStore.Coverage(0, 3), store.coverage());

    embed("Alpha.", axis(0));
    assertEquals(new DocumentStore.Coverage(1, 2), store.coverage());
  }

  /**
   * <b>A citation goes stale; it never quietly means something else.</b>
   *
   * <p>This is what spec decision 1 was bought for. The paragraph id a search hands out is the
   * surrogate key, so a re-ingest that leaves the text alone hands out the same id for the same
   * words — and one that edits the text hands out a <em>new</em> id, leaving whoever held the old
   * one with a citation that resolves to nothing rather than to different prose.
   */
  @Test
  void an_edited_paragraph_takes_a_new_citation_rather_than_repointing_the_old_one() {
    ingest("notes.md", "Alpha.\n\nBeta.");
    embed("Alpha.", axis(0));
    embed("Beta.", axis(1));
    UUID citedAlpha = store.searchByVector(axis(0), 1).get(0).paragraphId();
    UUID citedBeta = store.searchByVector(axis(1), 1).get(0).paragraphId();

    ingest("notes.md", "Alpha.\n\nBeta, revised.");
    embed("Beta, revised.", axis(1));

    // Untouched text, same citation.
    assertEquals(citedAlpha, store.searchByVector(axis(0), 1).get(0).paragraphId());
    // Edited text, and the old citation is nowhere in the corpus rather than
    // pointing at prose it never described.
    DocumentStore.Hit beta = store.searchByVector(axis(1), 1).get(0);
    assertEquals("Beta, revised.", beta.paragraphText());
    assertNotEquals(citedBeta, beta.paragraphId());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM paragraphs WHERE id = ?", Integer.class, citedBeta));
  }

  /**
   * <b>And the way to lose it, pinned as a decision rather than left in a comment.</b>
   *
   * <p>{@code MemoryStore.searchByVector} breaks a distance tie with {@code , m.id}, so that two
   * equidistant rows do not come back in physical order. Copying that here costs the index:
   * measured against this container, the same query with {@code , c.id} appended plans as a {@code
   * Sort} over a merge join that reaches {@code chunks} through {@code chunks_one_per_ordinal}, and
   * {@code chunks_by_vector} does not appear in the plan at all. Nothing errors and the same rows
   * come back, which is exactly why this is a test rather than a sentence — the regression it
   * catches is invisible in every other assertion in this class.
   *
   * <p>So the ordering is deliberately done in two places: distance in the database, where the
   * index is, and the tie in {@link DocumentStore#searchByVector}, over the handful of rows that
   * came back.
   */
  @Test
  void a_second_sort_key_would_cost_the_index_which_is_why_ties_break_in_java() {
    ingest("notes.md", "Alpha.\n\nBeta.");
    embed("Alpha.", axis(0));
    embed("Beta.", axis(1));

    String plan =
        explain(DocumentStore.SEARCH_SQL.replace(" ORDER BY distance", " ORDER BY distance, c.id"));

    assertFalse(
        plan.contains("chunks_by_vector"),
        "a second ORDER BY key no longer costs the HNSW index, so searchByVector could"
            + " break its ties in SQL after all:\n"
            + plan);
  }

  // --- the lexical half ------------------------------------------------------

  /**
   * <b>Every chunk is indexed for its words, by the database, from its own text.</b>
   *
   * <p>V21's column is {@code GENERATED ALWAYS AS ... STORED}, so this asserts three separate
   * things at once and each of them is a decision. That the column is populated at all without
   * {@link DocumentStore} ever naming it; that it is stemmed — {@code refilled} is stored as {@code
   * refil}, which is why a question asking about refilling reaches a chunk that says refilled; and
   * that the English configuration's stopwords are gone, which is what makes {@code the} not a
   * search term.
   */
  @Test
  void every_chunk_is_indexed_for_its_words_without_the_store_writing_one() {
    ingest("notes.md", "The retry budget is refilled on every successful call.");

    String indexed = jdbc.queryForObject("SELECT text_search::text FROM chunks", String.class);

    assertTrue(indexed.contains("'refil'"), indexed);
    assertTrue(indexed.contains("'budget'"), indexed);
    assertFalse(indexed.contains("'the'"), indexed);
  }

  /**
   * <b>And nothing can write it, which is the whole reason it is generated.</b>
   *
   * <p>The alternative shapes — a plain column the application fills, or a trigger — both admit a
   * chunk whose text says one thing and whose lexical index says another, and that divergence has
   * no symptom: the row is perfect, the search misses it, and nothing goes red. It is the same
   * failure the embedding coupling is written about, one column over, and here it can be made
   * structurally impossible rather than merely avoided.
   */
  @Test
  void nothing_can_write_the_lexical_index_out_of_step_with_the_text() {
    ingest("notes.md", "Alpha.");

    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE chunks SET text_search = to_tsvector('english', 'something else')"));
  }

  /**
   * <b>The half that matches on words, and the case it exists for.</b>
   *
   * <p>Every chunk here is embedded, and the question's vector points at {@code axis(0)} — which is
   * Alpha's direction and nowhere near Gamma's. A vector search would rank the third chunk last of
   * three. The lexical half returns it alone, because it is the only chunk holding the word that
   * was asked for, and that is the whole trade: an embedding is a summary of what a passage is
   * <em>about</em>, and a rare token barely moves it.
   */
  @Test
  void the_lexical_half_matches_on_words_where_the_vector_half_matches_on_direction() {
    ingest("notes.md", "Alpha.\n\nBeta.\n\nThe ef_search ceiling is forty.");
    embed("Alpha.", axis(0));
    embed("Beta.", axis(1));
    embed("The ef_search ceiling is forty.", axis(2));

    List<DocumentStore.Hit> hits = store.searchByText("ef_search", axis(0), 10);

    assertEquals(
        List.of("The ef_search ceiling is forty."),
        hits.stream().map(DocumentStore.Hit::chunkText).toList());
  }

  /**
   * <b>And it reaches exactly the corpus the vector half reaches — no more.</b>
   *
   * <p>{@code text_search} is generated for every chunk, so nothing in the schema stops this query
   * returning one that has no vector. The {@code embedding IS NOT NULL} predicate is what stops it,
   * and the reason is not the index: it is that {@link DocumentStore#coverage} reports one number
   * for what a search could reach, and a corpus whose reachable half depended on which words a
   * question happened to use could not be described by one.
   */
  @Test
  void a_chunk_with_no_vector_is_never_a_lexical_hit_either() {
    ingest("notes.md", "Alpha alone.\n\nAlpha also.");
    embed("Alpha alone.", axis(0));

    List<DocumentStore.Hit> hits = store.searchByText("alpha", axis(0), 10);

    assertEquals(List.of("Alpha alone."), hits.stream().map(DocumentStore.Hit::chunkText).toList());
  }

  /**
   * <b>A lexical hit carries the cosine distance the vector half would have given it</b>, which is
   * what lets one fused list be rendered.
   *
   * <p>The query vector is passed into a query that does not rank by it. It selects nothing and
   * orders nothing; it is there so that a hit found on words and a hit found on meaning are the
   * same record, with the same number beside them, and no surface has to learn a second kind of
   * hit.
   */
  @Test
  void a_lexical_hit_carries_the_distance_the_vector_half_would_have_given_it() {
    ingest("notes.md", "Alpha.");
    embed("Alpha.", axis(1));

    double lexical = store.searchByText("alpha", axis(0), 10).get(0).distance();
    double vector = store.searchByVector(axis(0), 10).get(0).distance();

    assertEquals(vector, lexical, 1e-9);
  }

  /**
   * <b>Every content word of the question has to be there.</b>
   *
   * <p>{@code websearch_to_tsquery} conjoins unquoted terms, and that is the decision rather than
   * an accident of the function chosen. Postgres keeps no corpus-wide term statistics, so {@code
   * ts_rank_cd} has no inverse document frequency and cannot tell a rare word from a common one;
   * under a disjunction the top of this list would be whichever chunks repeat the question's most
   * ordinary words. A conjunction makes membership the signal and leaves the ranking little to do —
   * which is also why the fusion reads only this side's rank order and never its score.
   */
  @Test
  void every_content_word_of_the_question_must_be_present() {
    ingest("notes.md", "The retry budget is refilled.\n\nBudgets are unrelated here.");
    embed("The retry budget is refilled.", axis(0));
    embed("Budgets are unrelated here.", axis(1));

    assertEquals(
        List.of("The retry budget is refilled."),
        store.searchByText("how is the retry budget refilled", axis(0), 10).stream()
            .map(DocumentStore.Hit::chunkText)
            .toList());
  }

  /**
   * A question with no word in it that survives stemming and stopping matches nothing, rather than
   * everything.
   */
  @Test
  void a_question_of_nothing_but_stopwords_matches_nothing() {
    ingest("notes.md", "Alpha.");
    embed("Alpha.", axis(0));

    assertTrue(store.searchByText("the and of", axis(0), 10).isEmpty());
  }

  /**
   * <b>{@code ts_rank_cd(...) > 0} is this side's {@code embedding IS NOT NULL}</b>, and here is
   * the input that shows why it is not redundant.
   *
   * <p>{@code websearch_to_tsquery} reads a leading dash as a negation, so {@code -alpha} is the
   * tsquery {@code !'alpha'} — which every chunk that does not say Alpha <em>matches</em>, and
   * which {@code ts_rank_cd} scores 0 for all of them because there is no cover to measure. Without
   * the rank predicate this question returns most of the corpus, ranked 0, in whatever order the
   * scan reached it — and under fusion those chunks would arrive carrying real-looking ranks and
   * contributing real-looking scores. With it, the lexical half returns nothing and the fusion
   * falls back to the vector half, which is the honest answer to a question that named no word.
   */
  @Test
  void a_question_that_names_no_word_to_match_returns_nothing_rather_than_everything() {
    ingest("notes.md", "Alpha.\n\nBeta.\n\nGamma.");
    embed("Alpha.", axis(0));
    embed("Beta.", axis(1));
    embed("Gamma.", axis(2));

    assertTrue(store.searchByText("-alpha", axis(0), 10).isEmpty());
  }

  /**
   * <b>Ties are broken in SQL here, where the vector half cannot break them at all.</b>
   *
   * <p>Two chunks that match the same words to the same density score identically, and a {@code
   * LIMIT} over an unbroken tie takes an arbitrary subset — so what would vary between two runs of
   * one question is the candidate <em>set</em> and not merely its order. The vector half pays for
   * its tie-break in Java because a second sort key costs it the HNSW index; this one does not, and
   * {@link #a_second_sort_key_costs_the_gin_index_nothing} is why.
   *
   * <p><b>Ordered by {@link #AS_POSTGRES_ORDERS} and not by {@code Comparator.naturalOrder()},
   * which is what this test used to say and what made it fail about half the time.</b> See that
   * field.
   */
  @Test
  void two_equally_ranked_chunks_come_back_in_a_fixed_order() {
    ingest("notes.md", "Retry budget here.\n\nRetry budget there.");
    embed("Retry budget here.", axis(0));
    embed("Retry budget there.", axis(1));

    List<DocumentStore.Hit> hits = store.searchByText("retry budget", axis(0), 10);

    assertEquals(2, hits.size());
    assertEquals(
        hits.stream().map(DocumentStore.Hit::chunkId).sorted(AS_POSTGRES_ORDERS).toList(),
        hits.stream().map(DocumentStore.Hit::chunkId).toList());
  }

  /**
   * Two uuids in the order <b>Postgres</b> puts them in, which is not the order {@link
   * UUID#compareTo} puts them in.
   *
   * <p><b>Measured, after this file's tie-break assertion failed at roughly one run in two.</b>
   * {@code UUID.compareTo} compares the two {@code long} halves as <em>signed</em> values, so a
   * uuid whose first hex digit is 8-f sorts <em>before</em> one beginning 0-7; Postgres compares a
   * {@code uuid} as sixteen unsigned bytes, so it sorts after. The two disagree whenever exactly
   * one of a pair has its high bit set, which is about half of random pairs — observed as expected
   * {@code [f3634e1c-…, 5fc22858-…]} against a returned {@code [5fc22858-…, f3634e1c-…]}.
   *
   * <p><b>The production SQL is right and was not touched.</b> {@code ORDER BY lexical_rank DESC,
   * c.id} is deliberate: a {@code LIMIT} over an unbroken tie varies the candidate <em>set</em> and
   * not merely its order. What was wrong was the expectation this test compared it against, and
   * changing {@code LEXICAL_SQL} to agree with a signed comparison would have been making the
   * database wrong to make an assertion right.
   */
  private static final Comparator<UUID> AS_POSTGRES_ORDERS =
      (left, right) -> {
        int high =
            Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0
            ? high
            : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
      };

  /** A limit of none asks the database nothing, as the vector half does. */
  @Test
  void a_lexical_limit_of_none_is_an_empty_answer_and_no_query() {
    ingest("notes.md", "Alpha.");
    embed("Alpha.", axis(0));

    assertTrue(store.searchByText("alpha", axis(0), 0).isEmpty());
  }

  /** <b>The index V21 built is the index this read uses.</b> */
  @Test
  void the_lexical_search_is_served_by_the_gin_index() {
    ingest("notes.md", "Alpha.\n\nBeta.");
    embed("Alpha.", axis(0));
    embed("Beta.", axis(1));

    String plan = explainLexical(DocumentStore.LEXICAL_SQL);

    assertTrue(
        plan.contains("chunks_by_text"),
        "the lexical search is not using the GIN index V21 built for it:\n" + plan);
  }

  /**
   * <b>And the hazard that bites the vector half does not bite this one, which is why the two order
   * themselves differently.</b>
   *
   * <p>An HNSW index supplies the ORDER, so asking for a different order loses it silently. A GIN
   * index supplies the FILTER — a {@code Bitmap Index Scan}, and then a {@code Sort} over whatever
   * survived it — so the sort key is not the index's business and a second one changes nothing
   * about it. Both halves of that are asserted here rather than described, because the conclusion
   * drawn from it is that {@code LEXICAL_SQL} may do in SQL the thing {@link
   * DocumentStore#SEARCH_SQL} is forbidden to do.
   */
  @Test
  void a_second_sort_key_costs_the_gin_index_nothing() {
    ingest("notes.md", "Alpha.\n\nBeta.");
    embed("Alpha.", axis(0));
    embed("Beta.", axis(1));

    String plan = explainLexical(DocumentStore.LEXICAL_SQL);
    String withoutTheTieBreak =
        explainLexical(
            DocumentStore.LEXICAL_SQL.replace(
                " ORDER BY lexical_rank DESC, c.id", " ORDER BY lexical_rank DESC"));

    assertTrue(plan.contains("Bitmap Index Scan on chunks_by_text"), plan);
    assertTrue(plan.contains("Sort"), plan);
    assertTrue(withoutTheTieBreak.contains("chunks_by_text"), withoutTheTieBreak);
  }

  /**
   * <b>And the predicate that is not redundant, pinned the way the vector half's second sort key
   * is.</b>
   *
   * <p>Drop {@code text_search @@ q} and there is nothing left for a GIN index to serve: the plan
   * is a sequential scan of the whole table, ranked by a function that answers 0 for every chunk
   * the question does not match. Nothing errors and rows come back, which is why this is a test.
   */
  @Test
  void without_the_match_predicate_the_read_loses_the_index_altogether() {
    ingest("notes.md", "Alpha.\n\nBeta.");
    embed("Alpha.", axis(0));
    embed("Beta.", axis(1));

    String plan = explainLexical(DocumentStore.LEXICAL_SQL.replace(" AND c.text_search @@ q", ""));

    assertFalse(
        plan.contains("chunks_by_text"),
        "the match predicate is no longer what earns the GIN index, so LEXICAL_SQL"
            + " could drop it after all:\n"
            + plan);
  }

  /**
   * <b>The corpus and the questions asked of it are stemmed by one configuration.</b>
   *
   * <p>V21 welds {@code english} into a generated column, because a generated column may not read a
   * session setting; {@code LEXICAL_SQL} has to spell the same word, and nothing about a mismatch
   * fails — the query parses, the operator runs, and matching quietly stops. It is the vector
   * half's model coupling in a place with no width to check, so the check is this.
   */
  @Test
  void the_migration_and_the_query_stem_by_the_same_configuration() throws Exception {
    String migration =
        new String(
            getClass()
                .getResourceAsStream("/db/migration/V21__chunk_text_search.sql")
                .readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);

    assertTrue(
        migration.contains("to_tsvector('" + DocumentStore.TEXT_SEARCH_CONFIGURATION + "', text)"),
        "V21 does not generate chunks.text_search with the configuration"
            + " DocumentStore.LEXICAL_SQL asks its questions in ('"
            + DocumentStore.TEXT_SEARCH_CONFIGURATION
            + "')");
    assertTrue(
        DocumentStore.LEXICAL_SQL.contains(
            "websearch_to_tsquery('" + DocumentStore.TEXT_SEARCH_CONFIGURATION + "'"),
        DocumentStore.LEXICAL_SQL);
  }

  // --- the read the ask path makes -----------------------------------------

  /**
   * A paper whose headings are real, so the attribution below is a title the document wrote rather
   * than one the parser invented.
   *
   * <p>The same text {@code HierarchyIngestTest} uses, and for the same reason: {@code
   * SectionDetector}'s {@code NUMBERED_HEADING} branch is what turns these three lines into three
   * named sections, and a fixture that drifted from that would test attribution over a hierarchy
   * nothing produces.
   */
  private static final String PAPER =
      """
            1. Introduction
            The conjecture has stood for forty years and we refute it here.

            2. Method
            We search the space of graphs with a learned policy.

            3. Results
            The counterexample has eleven vertices and is reproduced below.
            """;

  /**
   * <b>The scoped read answers from one document, and the corpus-wide read is not what it is made
   * of.</b>
   *
   * <p>This is the whole of §2.2. Anchor's critic holds a proposed answer against what <em>this
   * document</em> argues, and there is no corpus-wide version of that question — so the port adds a
   * second query rather than a {@code document:} filter on {@link DocumentStore#searchByVector},
   * whose {@link DocumentStore#coverage} contract says in as many words that a search whose reach
   * depended on its input could not be described by one number.
   *
   * <p>The other document's chunk is deliberately the <em>nearest</em> one in the corpus: a scope
   * that was applied after the ranking rather than inside it would still return three rows here and
   * would have thrown away the best of them.
   */
  @Test
  void the_scoped_search_answers_from_one_document_and_no_other() {
    DocumentStore.Written mine = ingest("mine.md", "Alpha.\n\nBeta.\n\nGamma.");
    ingest("theirs.md", "Delta.");
    embed("Alpha.", blend(1f, 1f));
    embed("Beta.", blend(1f, 3f));
    embed("Gamma.", axis(1));
    embed("Delta.", axis(0));

    List<DocumentStore.Passage> found = store.searchWithinDocument(mine.documentId(), axis(0), 10);

    assertEquals(
        List.of("Alpha.", "Beta.", "Gamma."),
        found.stream().map(DocumentStore.Passage::chunkText).toList());
    assertEquals(paragraphIds(mine.documentId()).get(1), found.get(0).paragraphId());
    assertEquals(Math.sqrt(0.5), found.get(0).similarity(), 1e-6);
  }

  /**
   * <b>A passage says which section of the document it came from, and it says it through {@link
   * StructuralRef}.</b>
   *
   * <p>Anchor's {@code ChunkSearchHit} carries {@code sectionTitle} and {@code sectionSynthetic} —
   * the two stored columns, raw — and its own chunk block then re-derives the rule from them, which
   * §3.2 records as the one place Anchor bypasses its own gate. The port hands out the gated type
   * instead, so nothing downstream can reach a title without saying how it degrades.
   *
   * <p>The section's id is on the passage as well, and it is not decoration: field 6 of the ask
   * prompt is the summaries of the sections owning the top chunks, and the alternative to an id is
   * matching {@link DocumentStore#hierarchy} back by title string — which is exactly the fragility
   * §5.4 records as the reason Anchor's grounding is weaker than Plowshare's.
   */
  @Test
  void a_passage_names_the_section_its_paragraph_is_in() {
    DocumentStore.Written written = ingest("paper.md", PAPER);
    embed("We search the space of graphs with a learned policy.", axis(0));

    DocumentStore.Passage found =
        store.searchWithinDocument(written.documentId(), axis(0), 10).get(0);

    DocumentStore.Attribution.InSection where =
        assertInstanceOf(DocumentStore.Attribution.InSection.class, found.attribution());
    assertEquals(new StructuralRef.Named("2. Method"), where.title());
    assertEquals(
        jdbc.queryForObject(
            "SELECT id FROM sections WHERE document_id = ? AND ordinal = 2",
            UUID.class,
            written.documentId()),
        where.sectionId());
    assertEquals("2. Method", where.title().render(StructuralRef.WhenSynthetic.PLACEHOLDER));
  }

  /**
   * <b>The sentinel is in the row and never in the answer.</b>
   *
   * <p>A document that declares no heading gets one synthetic section over all of it, and {@code
   * sections.title} really does hold {@link SyntheticTitles#SECTION} — asserted here, because a
   * test that only checked the rendered side would pass just as well over a store that had stopped
   * writing the sentinel and started writing a plausible name, which is the failure V26 says the
   * sentinel exists to make visible. Named through the constant and never spelled out, which {@code
   * StructuralRefTest.the_sentinel_strings_are_spelled_out_in_one_file_only} enforces over javadoc
   * as readily as over code — and did, on the first draft of this comment.
   *
   * <p>What comes back is {@link StructuralRef.Synthetic}, so the three degradations are the call
   * site's choice and none of them is the sentinel.
   */
  @Test
  void a_synthetic_section_never_hands_its_sentinel_to_a_reader() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.\n\nBeta.");
    embed("Alpha.", axis(0));

    assertEquals(
        SyntheticTitles.SECTION,
        jdbc.queryForObject(
            "SELECT title FROM sections WHERE document_id = ?",
            String.class,
            written.documentId()));

    DocumentStore.Passage found =
        store.searchWithinDocument(written.documentId(), axis(0), 10).get(0);

    DocumentStore.Attribution.InSection where =
        assertInstanceOf(DocumentStore.Attribution.InSection.class, found.attribution());
    assertEquals(new StructuralRef.Synthetic(), where.title());
    assertNull(where.title().render(StructuralRef.WhenSynthetic.OMIT));
    assertEquals("", where.title().render(StructuralRef.WhenSynthetic.BLANK));
    assertEquals(
        StructuralRef.UNNAMED, where.title().render(StructuralRef.WhenSynthetic.PLACEHOLDER));
  }

  /**
   * <b>A paragraph in no section is still evidence, and the passage says so rather than the search
   * dropping it.</b>
   *
   * <p>{@code paragraphs.section_id} is nullable — V18 froze {@code document_id NOT NULL} and V26
   * could only add beside it — so a paragraph derived before the hierarchy existed hangs off its
   * document and off nothing else. Anchor cannot reach this state and its query cannot express it:
   * Anchor's paragraphs reach their document only through {@code section -> chapter}, so the port's
   * version of that join would be an inner one and would drop this row <em>silently</em>, which is
   * the exact failure stage 5 named one tier up and refused to write around.
   *
   * <p>So the row is kept and the hole is on the passage. The policy question — whether a document
   * in this state may be asked at all — is already answered, loudly, by {@link
   * DocumentStore#summarisedParagraphsInNoSection} and the {@code STUCK} the cascade returns from
   * it; a second answer here, in a read, would be the same question asked in a different currency.
   */
  @Test
  void a_paragraph_in_no_section_is_a_passage_that_says_it_has_none() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.\n\nBeta.");
    embed("Alpha.", axis(0));
    embed("Beta.", axis(1));
    jdbc.update(
        "UPDATE paragraphs SET section_id = NULL WHERE document_id = ? AND ordinal = 1",
        written.documentId());

    List<DocumentStore.Passage> found =
        store.searchWithinDocument(written.documentId(), axis(0), 10);

    assertEquals(
        List.of("Alpha.", "Beta."), found.stream().map(DocumentStore.Passage::chunkText).toList());
    assertEquals(new DocumentStore.Attribution.InNoSection(), found.get(0).attribution());
    assertInstanceOf(DocumentStore.Attribution.InSection.class, found.get(1).attribution());
  }

  /**
   * <b>Anchor's join shape is the one that loses it</b>, which is why the port filters on {@code
   * p.document_id} directly.
   *
   * <p>Not a style preference and not a plan measurement: V18's {@code paragraphs.document_id NOT
   * NULL} makes the filter reachable in one hop, and taking Anchor's three-hop route instead would
   * make the {@code sections} join load bearing for <em>membership</em> when it is only needed for
   * the title. This runs both shapes over the same rows and shows the difference is a row, not a
   * plan.
   */
  @Test
  void anchors_own_join_would_have_dropped_that_paragraph_without_a_word() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.\n\nBeta.");
    embed("Alpha.", axis(0));
    embed("Beta.", axis(1));
    jdbc.update(
        "UPDATE paragraphs SET section_id = NULL WHERE document_id = ? AND ordinal = 1",
        written.documentId());

    // Anchor's `paragraphs -> sections -> chapters`, filtered on the chapter's
    // document, which is the only route its schema offers.
    List<String> throughTheHierarchy =
        jdbc.queryForList(
            "SELECT c.text FROM chunks c"
                + " JOIN paragraphs p ON p.id = c.paragraph_id"
                + " JOIN sections s ON s.id = p.section_id"
                + " JOIN chapters ch ON ch.id = s.chapter_id"
                + " WHERE ch.document_id = ? AND c.embedding IS NOT NULL"
                + " ORDER BY c.embedding <=> CAST(? AS vector)",
            String.class,
            written.documentId(),
            "[1" + ",0".repeat(767) + "]");

    assertEquals(List.of("Beta."), throughTheHierarchy);
    assertEquals(
        List.of("Alpha.", "Beta."),
        store.searchWithinDocument(written.documentId(), axis(0), 10).stream()
            .map(DocumentStore.Passage::chunkText)
            .toList());
  }

  /**
   * {@link DocumentStore#searchByVector}'s filter, one scope down and for its reason exactly: on
   * any plan that falls back to a scan, {@code NULL <=> v} sorts last rather than nowhere, so an
   * under-full answer would be padded with chunks nothing compared.
   */
  @Test
  void a_chunk_with_no_vector_is_never_a_passage() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.\n\nBeta.");
    embed("Alpha.", axis(0));

    assertEquals(
        List.of("Alpha."),
        store.searchWithinDocument(written.documentId(), axis(0), 10).stream()
            .map(DocumentStore.Passage::chunkText)
            .toList());
  }

  /**
   * <b>Fifty is the ceiling whatever the caller asks for</b>, which is the half of Anchor's {@code
   * Math.max(1, Math.min(50, limit))} that does work.
   *
   * <p>This list becomes a prompt. A caller that asked for a thousand chunks of one document would
   * be asking for the document back, one tier below the summaries the deliberation is built to read
   * instead — so the cap is the shape of the ask rather than a defensive habit.
   */
  @Test
  void a_scoped_search_asks_for_no_more_than_fifty() {
    StringBuilder many = new StringBuilder();
    for (int i = 1; i <= 60; i++) {
      many.append("Claim number ").append(i).append(" is recorded here.\n\n");
    }
    DocumentStore.Written written = ingest("long.md", many.toString());
    embedEveryChunk(written.documentId(), axis(0));
    assertEquals(60, chunkCount(written.documentId()));

    assertEquals(
        DocumentStore.MOST_PASSAGES,
        store.searchWithinDocument(written.documentId(), axis(0), 1000).size());
  }

  /**
   * A limit of none asks the database nothing, as both corpus-wide reads do.
   *
   * <p><b>The one place this method does not copy Anchor.</b> Anchor's lower clamp is {@code
   * Math.max(1, limit)}, so a caller asking for no chunks is answered with one — the store
   * answering a question it was not asked. Both of this class's other reads refuse instead, on the
   * grounds that a caller that asked for none must not pay a round trip to be told so, and one
   * method in three behaving differently would be the surprise.
   */
  @Test
  void a_scoped_limit_of_none_is_an_empty_answer_and_no_query() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.");
    embed("Alpha.", axis(0));

    assertTrue(store.searchWithinDocument(written.documentId(), axis(0), 0).isEmpty());
    assertTrue(store.searchWithinDocument(written.documentId(), axis(0), -1).isEmpty());
  }

  /**
   * <b>The scoped read is an exact search over one document, and the HNSW index is not what serves
   * it — which is right rather than a regression.</b>
   *
   * <p>The corpus-wide read's whole argument is that {@code chunks_by_vector} must appear in its
   * plan. Measured here, this one's does not: the planner reaches the document's paragraphs by
   * {@code paragraphs_matched_by}, gathers their chunks and sorts them, and it costs less than half
   * what the index plan costs.
   *
   * <p><b>The reason is not the fixture's size, and it is the reason this test asserts the absence
   * rather than being deleted.</b> The index plan for this query — reachable, and pinned one test
   * down — is {@code Index Scan using chunks_by_vector} over the <em>whole corpus</em> with the
   * document as a join filter applied afterwards. That is ANN post-filtering: pgvector walks the
   * corpus graph in distance order and discards every chunk belonging to some other document. With
   * {@code hnsw.iterative_scan} off, which is pgvector 0.8's default and this container's setting,
   * the walk stops when its candidate list is exhausted, so a post-filtered scan can return
   * <em>fewer rows than the limit</em> — an under-full evidence set, with no error, on the path
   * whose entire job is to put a document's best passages in front of a critic.
   *
   * <p>An exact sort has no such failure and is bounded by what a document holds: V18 sizes the
   * corpus at twenty thousand chunks for a hundred documents, so the set being sorted is hundreds
   * of rows, not the corpus. <b>{@code embedding IS NOT NULL} is therefore not the hypothetical
   * guard it is on the corpus-wide read</b> — the sort plan is the plan, and {@code NULL <=> v}
   * sorts last rather than nowhere.
   */
  @Test
  void the_scoped_read_sorts_one_document_rather_than_walking_the_corpus_graph() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.\n\nBeta.");
    embed("Alpha.", axis(0));
    embed("Beta.", axis(1));

    String plan = explainScoped(DocumentStore.SCOPED_SEARCH_SQL, written.documentId());

    assertTrue(
        plan.contains("paragraphs_matched_by"),
        "the document filter is no longer index-served, so the scoped read reaches its"
            + " document by scanning:\n"
            + plan);
    assertFalse(
        plan.contains("chunks_by_vector"),
        "the scoped read is now planned as a post-filtered HNSW walk of the whole"
            + " corpus, which can return fewer passages than it was asked for:\n"
            + plan);
  }

  /**
   * <b>The index stays reachable even though it is not chosen, and that is what the tie-break in
   * Java is protecting.</b>
   *
   * <p>Without this, the test above would be satisfied by SQL that could never use the index at
   * all, and {@code ORDER BY distance} alone would be cargo-culted from {@link
   * DocumentStore#SEARCH_SQL} rather than meant. With the sort taken off the table the planner does
   * reach for {@code chunks_by_vector} — so the option is open, and a corpus of one document, where
   * the filter selects everything, is exactly where the planner would want it.
   *
   * <p>Appending {@code , c.id} closes it: the second key is an ordering the HNSW index cannot
   * produce, so the plan falls back to a sort <em>even with sorting disabled</em>. Measured on this
   * container rather than assumed from the corpus-wide read, because the joins give this query plan
   * choices that one does not have.
   */
  @Test
  void a_second_sort_key_would_close_the_index_off_which_is_why_ties_break_in_java() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.\n\nBeta.");
    embed("Alpha.", axis(0));
    embed("Beta.", axis(1));

    assertTrue(
        explainScoped(
                DocumentStore.SCOPED_SEARCH_SQL, written.documentId(), "SET enable_sort = off")
            .contains("chunks_by_vector"),
        "the scoped read can no longer be served by the HNSW index under any plan");
    assertFalse(
        explainScoped(
                DocumentStore.SCOPED_SEARCH_SQL.replace(
                    " ORDER BY distance", " ORDER BY distance, c.id"),
                written.documentId(),
                "SET enable_sort = off")
            .contains("chunks_by_vector"),
        "a second ORDER BY key no longer closes the index off, so the scoped read could"
            + " break its ties in SQL after all");
  }

  /**
   * Two equidistant chunks come back in the same order on every call, which the SQL deliberately
   * does not ask the database for.
   */
  @Test
  void equidistant_passages_come_back_in_a_fixed_order() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.\n\nBeta.");
    embed("Alpha.", axis(1));
    embed("Beta.", axis(1));

    List<DocumentStore.Passage> found =
        store.searchWithinDocument(written.documentId(), axis(1), 10);

    assertEquals(
        found.stream()
            .map(DocumentStore.Passage::chunkId)
            .sorted(Comparator.comparing(UUID::toString))
            .toList(),
        found.stream().map(DocumentStore.Passage::chunkId).toList());
  }

  private void embedEveryChunk(UUID documentId, float[] embedding) {
    for (UUID chunk :
        jdbc.queryForList(
            "SELECT c.id FROM chunks c JOIN paragraphs p ON p.id = c.paragraph_id"
                + " WHERE p.document_id = ?",
            UUID.class,
            documentId)) {
      store.attach(chunk, embedding);
    }
  }

  /**
   * The plan for one scoped query, with a sequential scan taken off the table.
   *
   * <p>Separate from {@link #explain} for {@link #explainLexical}'s reason: {@link
   * DocumentStore#SCOPED_SEARCH_SQL} takes a document id in the middle, and it has to be a real one
   * — a document that holds no rows would make the planner's choice a statement about an empty
   * table.
   *
   * <p>{@code alsoOff} takes further node types off the table, which is how a test asks what plans
   * this query <em>has</em> rather than only which one the planner likes best.
   */
  private String explainScoped(String sql, UUID documentId, String... alsoOff) {
    return String.join(
        "\n",
        jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<List<String>>)
                connection -> {
                  for (String setting :
                      Stream.concat(Stream.of("SET enable_seqscan = off"), Stream.of(alsoOff))
                          .toList()) {
                    try (var off = connection.createStatement()) {
                      off.execute(setting);
                    }
                  }
                  try (var explain = connection.prepareStatement("EXPLAIN " + sql)) {
                    explain.setString(1, "[" + "0,".repeat(767) + "1]");
                    explain.setObject(2, documentId);
                    explain.setInt(3, 5);
                    List<String> lines = new java.util.ArrayList<>();
                    try (var rows = explain.executeQuery()) {
                      while (rows.next()) {
                        lines.add(rows.getString(1));
                      }
                    }
                    return lines;
                  }
                }));
  }

  // --- what the schema refuses ---------------------------------------------

  /**
   * Two documents cannot share a name. The store never writes one, so this asserts the constraint
   * rather than the store — which is the half that survives a rewrite of the store.
   */
  @Test
  void the_schema_refuses_two_documents_under_one_name() {
    ingest("notes.md", "Alpha.");

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO documents"
                    + " (id, source_name, title, content_hash, text_hash, byte_size,"
                    + " ingested_at, ingested_by)"
                    + " VALUES (?, 'notes.md', 't', 'h', 'h', 1, now(), 'test')",
                UUID.randomUUID()));
  }

  /** Deleting a document takes its paragraphs and their chunks with it. */
  @Test
  void deleting_a_document_cascades_the_whole_derivation() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.\n\nBeta.");

    jdbc.update("DELETE FROM documents WHERE id = ?", written.documentId());

    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM paragraphs", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chunks", Integer.class));
  }

  private int chunkCount(UUID documentId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM chunks c JOIN paragraphs p ON p.id = c.paragraph_id"
            + " WHERE p.document_id = ?",
        Integer.class,
        documentId);
  }

  private Map<UUID, String> chunksByText(UUID documentId) {
    return jdbc
        .queryForList(
            "SELECT c.id, c.text FROM chunks c JOIN paragraphs p"
                + " ON p.id = c.paragraph_id WHERE p.document_id = ?",
            documentId)
        .stream()
        .collect(Collectors.toMap(row -> (UUID) row.get("id"), row -> (String) row.get("text")));
  }

  private static float[] vector(float value) {
    float[] embedding = new float[768];
    java.util.Arrays.fill(embedding, value);
    return embedding;
  }

  /**
   * Attach a vector to the one chunk whose text is this.
   *
   * <p>By text and not by ordinal, so a test reads as the sentence it is making: this paragraph is
   * near that question.
   */
  private void embed(String chunkText, float[] embedding) {
    store.attach(
        jdbc.queryForObject("SELECT id FROM chunks WHERE text = ?", UUID.class, chunkText),
        embedding);
  }

  /**
   * A vector pointing along one axis.
   *
   * <p>Never the zero vector, which has no direction: pgvector's {@code <=>} over one is undefined
   * and comes back NaN, which would order by nothing and say nothing about the ordering.
   */
  private static float[] axis(int i) {
    float[] embedding = new float[768];
    embedding[i] = 1f;
    return embedding;
  }

  /** A vector between the first two axes, for a hit that should land in the middle. */
  private static float[] blend(float first, float second) {
    float[] embedding = new float[768];
    embedding[0] = first;
    embedding[1] = second;
    return embedding;
  }

  /**
   * The plan for the store's own search SQL, with a sequential scan taken off the table.
   *
   * <p>Reads {@link DocumentStore#SEARCH_SQL} rather than a second copy of it: a test that
   * explained a query it wrote itself would go on passing after the store's query stopped being
   * index-servable, which is the whole failure it is here to catch.
   */
  private String explainSearch() {
    return explain(DocumentStore.SEARCH_SQL);
  }

  /**
   * Postgres's plan for one query, with {@code enable_seqscan} off.
   *
   * <p>Off, because on a three-row table the planner will scan whatever the SQL says and the answer
   * would be about the fixture rather than about the query. What is asked here is the narrower and
   * far more durable question: <em>can</em> this query be served by {@code chunks_by_vector} at
   * all.
   */
  /**
   * The plan for one lexical query, with a sequential scan taken off the table.
   *
   * <p>Separate from {@link #explain} only because {@link DocumentStore#LEXICAL_SQL} takes a third
   * parameter — the question — which has to be a real one: an empty tsquery would make the
   * planner's choice a statement about a question nobody asks.
   */
  private String explainLexical(String sql) {
    return String.join(
        "\n",
        jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<List<String>>)
                connection -> {
                  try (var off = connection.createStatement()) {
                    off.execute("SET enable_seqscan = off");
                  }
                  try (var explain = connection.prepareStatement("EXPLAIN " + sql)) {
                    explain.setString(1, "[" + "0,".repeat(767) + "1]");
                    explain.setString(2, "alpha");
                    explain.setInt(3, 5);
                    List<String> lines = new java.util.ArrayList<>();
                    try (var rows = explain.executeQuery()) {
                      while (rows.next()) {
                        lines.add(rows.getString(1));
                      }
                    }
                    return lines;
                  }
                }));
  }

  private String explain(String sql) {
    return String.join(
        "\n",
        jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<List<String>>)
                connection -> {
                  try (var off = connection.createStatement()) {
                    off.execute("SET enable_seqscan = off");
                  }
                  try (var explain = connection.prepareStatement("EXPLAIN " + sql)) {
                    explain.setString(1, "[" + "0,".repeat(767) + "1]");
                    explain.setInt(2, 5);
                    List<String> lines = new java.util.ArrayList<>();
                    try (var rows = explain.executeQuery()) {
                      while (rows.next()) {
                        lines.add(rows.getString(1));
                      }
                    }
                    return lines;
                  }
                }));
  }

  // --- the summary half, which is what the cascade writes back -------------

  /**
   * A paragraph is stored with no summary, and one is attached afterwards.
   *
   * <p>The same shape as the vector one column over, and for the same reason: the text is committed
   * before any model is called, so a summariser that never ran leaves a document whose paragraphs
   * are all there and none of them labelled.
   */
  @Test
  void a_paragraph_is_written_unsummarised_and_the_summary_is_attached_later() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.\n\nBeta.");

    List<DocumentStore.UnsummarisedParagraph> waiting = store.unsummarised(written.documentId());
    assertEquals(
        List.of("Alpha.", "Beta."),
        waiting.stream().map(DocumentStore.UnsummarisedParagraph::text).toList());

    store.attachSummary(waiting.get(0).id(), "Claims alpha.");
    assertEquals(
        List.of("Beta."),
        store.unsummarised(written.documentId()).stream()
            .map(DocumentStore.UnsummarisedParagraph::text)
            .toList());
    assertEquals(List.of("Claims alpha."), store.paragraphSummaries(written.documentId()));
  }

  /**
   * <b>An unchanged paragraph keeps its summary, and that is the whole reason the cascade is
   * affordable twice.</b>
   *
   * <p>The identity rule says an unchanged paragraph keeps its id; this says what that buys. A
   * re-ingest of a document with one edited paragraph is one model call and not two hundred,
   * because {@code write} touches a kept row's ordinal and nothing else.
   */
  @Test
  void a_re_ingest_only_owes_summaries_for_the_paragraphs_that_changed() {
    DocumentStore.Written first = ingest("notes.md", "Alpha.\n\nBeta.\n\nGamma.");
    for (DocumentStore.UnsummarisedParagraph waiting : store.unsummarised(first.documentId())) {
      store.attachSummary(waiting.id(), "Claims " + waiting.text());
    }
    assertTrue(store.unsummarised(first.documentId()).isEmpty());

    DocumentStore.Written again = ingest("notes.md", "Alpha.\n\nBeta, revised.\n\nGamma.");

    assertEquals(
        List.of("Beta, revised."),
        store.unsummarised(again.documentId()).stream()
            .map(DocumentStore.UnsummarisedParagraph::text)
            .toList());
  }

  /**
   * In document order, because the level above reads them as a sequence and a reversal is an
   * argument told backwards.
   */
  @Test
  void paragraph_summaries_come_back_in_the_order_the_document_makes_them() {
    DocumentStore.Written written = ingest("notes.md", "One.\n\nTwo.\n\nThree.");
    for (DocumentStore.UnsummarisedParagraph waiting : store.unsummarised(written.documentId())) {
      store.attachSummary(waiting.id(), "Claims " + waiting.text());
    }

    assertEquals(
        List.of("Claims One.", "Claims Two.", "Claims Three."),
        store.paragraphSummaries(written.documentId()));
  }

  /**
   * A paragraph with no summary is left out rather than represented by a blank, because a blank
   * line in the middle of an ordered run of claims is a claim the document does not make.
   */
  @Test
  void a_paragraph_nothing_summarised_is_absent_from_the_run_and_not_blank() {
    DocumentStore.Written written = ingest("notes.md", "One.\n\nTwo.\n\nThree.");
    List<DocumentStore.UnsummarisedParagraph> waiting = store.unsummarised(written.documentId());
    store.attachSummary(waiting.get(0).id(), "Claims one.");
    store.attachSummary(waiting.get(2).id(), "Claims three.");

    assertEquals(
        List.of("Claims one.", "Claims three."), store.paragraphSummaries(written.documentId()));
  }

  @Test
  void a_document_summary_is_attached_and_read_back() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.");
    assertTrue(store.find("notes.md").orElseThrow().summary() == null);

    store.attachDocumentSummary(written.documentId(), "Argues alpha throughout.");

    assertEquals("Argues alpha throughout.", store.find("notes.md").orElseThrow().summary());
  }

  /**
   * <b>A document whose derivation changed loses its summary, in the same transaction that changed
   * it.</b>
   *
   * <p>A document summary is derived from every paragraph summary under it, so a document that
   * gained or lost a paragraph is one the stored sentence is no longer about. Leaving it would
   * leave a confident, readable, wrong summary — the failure V18's chunk column calls a silent
   * truncation, one table up.
   */
  @Test
  void a_derivation_that_changed_drops_the_document_summary() {
    DocumentStore.Written first = ingest("notes.md", "Alpha.\n\nBeta.");
    store.attachDocumentSummary(first.documentId(), "Argues alpha and beta.");

    ingest("notes.md", "Alpha.\n\nBeta.\n\nGamma.");

    assertEquals(null, store.find("notes.md").orElseThrow().summary());
  }

  /**
   * And a re-ingest that changed nothing keeps it, which is what makes an unchanged re-ingest cost
   * no model call at all.
   */
  @Test
  void a_re_ingest_that_changed_nothing_keeps_the_document_summary() {
    DocumentStore.Written first = ingest("notes.md", "Alpha.\n\nBeta.");
    store.attachDocumentSummary(first.documentId(), "Argues alpha and beta.");

    ingest("notes.md", "Alpha.\n\nBeta.");

    assertEquals("Argues alpha and beta.", store.find("notes.md").orElseThrow().summary());
  }

  /**
   * Blank is not a summary. The schema refuses it for the reason V18 refuses a blank paragraph: an
   * empty string is what a model that answered nothing arrives as, and a row holding one says a
   * summary was written.
   */
  @Test
  void a_blank_summary_is_refused_by_the_schema() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.");
    UUID paragraph = paragraphIds(written.documentId()).get(1);

    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE paragraphs SET summary = '' WHERE id = ?", paragraph));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE documents SET summary = '' WHERE id = ?", written.documentId()));
  }

  // --- what the per-document ask reads -------------------------------------

  /**
   * <b>A document by its id, and the word it uses for its own top-level parts.</b>
   *
   * <p>{@code documents.top_level_label} landed in V26 and nothing read it. The per-document ask is
   * what it was written for: Anchor substitutes it into all three deliberation prompts because
   * <i>"the model was correctly citing our internal labels but those labels contradicted the
   * document's own self-references"</i>, and that read as fabrication.
   */
  @Test
  void a_document_is_found_by_its_id_and_says_what_it_calls_its_own_parts() {
    DocumentStore.Written written =
        ingest(
            "paper.md", "1. Introduction\n\nThe first thing.\n\n2. Results\n\nThe second thing.");

    DocumentStore.StoredDocument found = store.find(written.documentId()).orElseThrow();

    assertEquals(written.documentId(), found.id());
    assertEquals("paper.md", found.sourceName());
    assertEquals(Vocabulary.SECTION, found.vocabulary());
  }

  @Test
  void a_document_id_naming_nothing_is_an_empty_answer_and_not_a_failure() {
    assertTrue(store.find(UUID.randomUUID()).isEmpty());
  }

  /**
   * <b>The document's own bibliography, which is not {@code citations}.</b> V25 records what an
   * answer took from the corpus; these are the entries the paper itself prints, and the ask hands
   * them to the proposer and the critic so a model can tell an author of this document from a third
   * party it cites.
   */
  @Test
  void a_documents_own_references_come_back_in_the_order_it_numbers_them() {
    DocumentStore.Written written =
        ingest(
            "paper.md",
            """
                1. Introduction

                The first thing.

                References

                [1] Wagner, A. Something.
                [2] Nguyen, T. Something else.
                """);

    assertEquals(
        List.of(
            new DocumentStore.StoredReference(1, "Wagner, A. Something."),
            new DocumentStore.StoredReference(2, "Nguyen, T. Something else.")),
        store.references(written.documentId()));
  }

  @Test
  void a_document_that_prints_no_bibliography_has_no_references() {
    DocumentStore.Written written = ingest("notes.md", "Alpha.");

    assertEquals(List.of(), store.references(written.documentId()));
  }

  /**
   * <b>A paragraph's text, read back for the one document that named it.</b>
   *
   * <p>This is the read the grounding check runs on, and the document is a parameter rather than a
   * convenience: the ask is per-document by construction, so a quote attributed to a paragraph of
   * some <em>other</em> paper is a failed attribution and not a citation. {@code
   * CitationStore.record} would not catch it — it validates against the corpus, which that
   * paragraph is genuinely in.
   */
  @Test
  void a_paragraph_of_another_document_is_not_a_paragraph_of_this_one() {
    DocumentStore.Written mine = ingest("mine.md", "The bound is tight.");
    DocumentStore.Written theirs = ingest("theirs.md", "The bound is loose.");
    UUID hers = paragraphIds(theirs.documentId()).get(1);

    assertEquals("The bound is loose.", store.paragraphTextIn(theirs.documentId(), hers));
    assertNull(store.paragraphTextIn(mine.documentId(), hers));
    assertNull(store.paragraphTextIn(mine.documentId(), UUID.randomUUID()));
  }
}
