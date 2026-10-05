package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.embedding.*;
import io.aeyer.plowshare.server.information.InformationAccess;
import io.aeyer.plowshare.server.information.InformationContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

/**
 * The corpus's rows, and <b>the rule that decides which of them a re-ingested paragraph is</b>.
 *
 * <h2>Where the identity rule lives, and why it is here</h2>
 *
 * <p>Spec decision 1: {@code paragraphs.id} is a surrogate with the content hash beside it, and the
 * matching rule lives in code. {@link #match} is that rule, and it is four lines. The argument for
 * putting it here rather than in a primary key is entirely about this repository: {@code
 * MigrationsAreImmutableTest} freezes a migration once it is on {@code master}, so a rule in the
 * DDL could never be revised without moving every foreign key that points at a paragraph — and the
 * rule is exactly the kind of thing that gets revised. A format where occurrence is unstable, a
 * need for near-match rather than exact: each of those is an edit to {@link #match} and none of
 * them is an edit to V18.
 *
 * <p>As it stands: <b>a paragraph matching an existing row by document + content hash + occurrence
 * keeps that row's id; anything else gets a new one.</b> Unchanged text keeps its id, changed text
 * gets a new one, and a citation left pointing at a paragraph that is gone is a staleness signal
 * rather than a silent repoint at different text — which is the failure the whole arrangement
 * exists to make impossible.
 *
 * <h2>Text first, vectors after — never in the same transaction</h2>
 *
 * <p>{@link #write} persists the document, its paragraphs and its chunks with {@code embedding}
 * left NULL, and returns. Vectors are attached afterwards by {@link #attach}, one batch at a time,
 * outside any transaction.
 *
 * <p>That is not a convenience. {@code UnitOfWork}'s javadoc records what happened the last time a
 * model call sat inside a transaction here: a {@code LLM_BASE_URL} missing its {@code http://} made
 * OkHttp throw on every write, and every already-committed memory was rolled back. The same shape
 * at corpus scale would discard a whole document's text because the embedding endpoint was down. V1
 * states the rule for memories — "a memory is written before it is embedded, and an embedding
 * endpoint being down must lose the vector, never the write" — and it holds here with one thing
 * added: a chunk that arrives unembedded can be embedded later <em>from its own row</em>, because
 * the text is right there. {@link #unembedded} is that door.
 *
 * <h2>What a re-ingest does not pay for</h2>
 *
 * <p>A paragraph that kept its id kept its text, so the chunks under it are what the same chunker
 * would derive from the same text and their vectors are still correct. They are left alone. Only
 * new and changed paragraphs are re-chunked and re-embedded, which falls straight out of the
 * identity rule and is most of what makes re-ingesting an edited document cheap.
 *
 * <p><b>The condition on that, stated because nothing enforces it.</b> It holds only while the
 * chunk rule is unchanged. Changing {@code plowshare.llm.embedding-max-input-tokens}, {@code
 * plowshare.documents.chunk-target-tokens} or the tokenizer both are counted with makes every
 * stored chunk the output of a rule that is no longer in force, and re-ingesting will not repair
 * them, because their paragraphs are unchanged. That is a re-chunk and a re-embed of the corpus,
 * which is what application.yml says beside both keys.
 */
public final class DocumentStore {
  private DualEmbeddings dualEmbeddings;

  public void useDualEmbeddings(DualEmbeddings embeddings) {
    dualEmbeddings = java.util.Objects.requireNonNull(embeddings);
  }

  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final InformationAccess access;
  private final InformationContext context;
  private Runnable mutationFence;
  private java.util.function.Consumer<UUID> readAudit;

  /**
   * @param transactions must be a real transaction and not {@code UnitOfWork.NONE}. {@code
   *     paragraphs_one_per_position} is {@code DEFERRABLE INITIALLY DEFERRED}, and in autocommit a
   *     deferred constraint is an immediate one — every statement is its own transaction, so a
   *     re-ingest that moves ordinals would be refused for a collision that does not exist at
   *     commit
   */
  public DocumentStore(JdbcTemplate jdbc, UnitOfWork transactions) {
    this(jdbc, transactions, null, null);
  }

  private DocumentStore(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      InformationAccess access,
      InformationContext context) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.access = access;
    this.context = context;
  }

  /**
   * Immutable read view. Every query rechecks current authorization before selecting candidates.
   */
  public DocumentStore scoped(InformationAccess policy, InformationContext caller) {
    policy.requireSelection(caller);
    DocumentStore reader = new DocumentStore(jdbc, transactions, policy, caller);
    reader.embeddingFingerprint = embeddingFingerprint;
    reader.dualEmbeddings = dualEmbeddings;
    reader.embeddingQuery = embeddingQuery;
    reader.embeddingProfile = embeddingProfile;
    reader.readAudit = readAudit;
    return reader;
  }

  private EmbeddingQuery embeddingQuery;
  private EmbeddingProfile embeddingProfile;

  /** A query view retains the exact slot/model while scoped authorization is rechecked normally. */
  DocumentStore queried(EmbeddingQuery query) {
    DocumentStore reader = audited(readAudit);
    reader.embeddingQuery = java.util.Objects.requireNonNull(query);
    reader.embeddingProfile = query.profile();
    return reader;
  }

  DocumentStore profiled(EmbeddingProfile profile) {
    DocumentStore reader = audited(readAudit);
    reader.embeddingProfile = java.util.Objects.requireNonNull(profile);
    return reader;
  }

  /** Both representations of a committed source must be current, including staged replacements. */
  private static String dualReady(String alias, String table, String key) {
    String prefix = alias.isEmpty() ? "" : alias + ".";
    var clauses = new java.util.ArrayList<String>();
    for (EmbeddingSlot slot : EmbeddingSlot.values()) {
      String name = slot.stored();
      clauses.add(
          "EXISTS(SELECT 1 FROM embedding_slots selected WHERE selected.slot='"
              + name
              + "' AND selected.target_space_id IS NOT NULL AND (("
              + prefix
              + name
              + "_embedding IS NOT NULL AND "
              + prefix
              + name
              + "_space_id=selected.target_space_id AND "
              + prefix
              + name
              + "_source_revision="
              + prefix
              + "embedding_source_revision) OR EXISTS(SELECT 1 FROM "
              + table
              + "_embedding_staging staged WHERE staged.source_id="
              + key
              + " AND staged.slot=selected.slot AND staged.space_id=selected.target_space_id AND staged.source_revision="
              + prefix
              + "embedding_source_revision)))");
    }
    return "(" + String.join(" AND ", clauses) + ")";
  }

  private ReadQuery embeddingSql(ReadQuery original) {
    if (dualEmbeddings == null) return original;
    String sql = original.sql();
    EmbeddingProfile profile = embeddingProfile;
    boolean coverage = sql.contains(" AS searchable") || sql.contains(" AS rankable");
    if (profile == null && coverage)
      profile =
          dualEmbeddings.active(
              context != null && context.corpus() == InformationContext.Corpus.CODE
                  ? EmbeddingSlot.CODE
                  : EmbeddingSlot.PROSE);
    if (profile == null) {
      // These are write/repair inventories, independent of the capability's query slot.
      sql =
          sql.replace("c.embedding IS NULL", "NOT " + dualReady("c", "chunks", "c.id"))
              .replace(
                  "summary_embedding IS NULL", "NOT " + dualReady("", "documents", "documents.id"));
      return new ReadQuery(sql, original.arguments());
    }
    var plan = new EmbeddingIndexPlan(profile);
    boolean summary = sql.contains("summary_embedding");
    String column = summary ? plan.column() : "c." + plan.column();
    String present = plan.present(summary ? "" : "c");
    String oldColumn = summary ? "summary_embedding" : "c.embedding";
    sql =
        sql.replace(oldColumn + " IS NOT NULL", "(" + present + ")")
            .replace(oldColumn + " IS NULL", "NOT (" + present + ")");
    if (!summary)
      sql =
          sql.replaceAll("(?<![A-Za-z0-9_.])embedding IS NOT NULL", "(" + present + ")")
              .replaceAll("(?<![A-Za-z0-9_.])embedding IS NULL", "NOT (" + present + ")");
    // Stance is a cosine comparison, regardless of the ranking metric selected for search.
    boolean ranking = sql.contains(" AS distance");
    sql =
        sql.replace(
            oldColumn + " <=> CAST(? AS vector)",
            ranking ? plan.denseDistance(column) : column + " <=> CAST(? AS vector)");
    if (embeddingQuery == null || plan.exact() || !sql.contains("ORDER BY distance"))
      return new ReadQuery(sql, original.arguments());
    // Only fixed repository ranking statements have this suffix. Lexical and stance reads keep
    // their existing ordering. All authorization predicates stay inside the candidate statement.
    var suffix =
        java.util.regex.Pattern.compile(
                "ORDER BY distance(?:, id)?\\s+LIMIT \\?$", java.util.regex.Pattern.DOTALL)
            .matcher(sql.strip());
    if (!suffix.find())
      throw new IllegalStateException("embedding ranking query has no controlled candidate seam");
    int limit = ((Number) original.arguments()[original.arguments().length - 1]).intValue();
    String candidates =
        sql.strip().substring(0, suffix.start())
            + "ORDER BY "
            + plan.candidateDistance(column)
            + " LIMIT ?";
    var args = new java.util.ArrayList<Object>(java.util.Arrays.asList(original.arguments()));
    args.removeLast();
    args.add(embeddingQuery.vectorText());
    args.add(plan.candidates(limit));
    args.add(limit);
    return new ReadQuery(
        "SELECT candidates.* FROM (" + candidates + ") candidates ORDER BY distance LIMIT ?",
        args.toArray());
  }

  private String embeddingFingerprint;

  public DocumentStore embeddingSpace(String model, int width) {
    DocumentStore reader = audited(readAudit);
    reader.embeddingFingerprint =
        io.aeyer.plowshare.server.information.InformationConfiguration.embedding(model, width);
    return reader;
  }

  private String compatible(String expression, String stage) {
    if (embeddingFingerprint == null || dualEmbeddings != null) return "true";
    // Fingerprint is an internally produced hexadecimal SHA-256, never caller SQL.
    return "NOT EXISTS(SELECT 1 FROM information_revisions space_revision WHERE space_revision.id="
        + expression
        + " AND NOT EXISTS(SELECT 1 FROM information_steps space_step WHERE space_step.revision_id=space_revision.id AND space_step.generation=space_revision.generation AND space_step.stage='"
        + stage
        + "' AND space_step.state='ready' AND space_step.fingerprint='"
        + embeddingFingerprint
        + "'))";
  }

  /** A processing lease is rechecked under a short transaction before every checkpoint. */
  public DocumentStore fenced(Runnable fence) {
    if (access != null) throw new IllegalStateException("a reader cannot become a writer");
    DocumentStore writer = new DocumentStore(jdbc, transactions);
    writer.mutationFence = Objects.requireNonNull(fence, "fence");
    writer.dualEmbeddings = dualEmbeddings;
    return writer;
  }

  public DocumentStore audited(java.util.function.Consumer<UUID> audit) {
    DocumentStore reader = new DocumentStore(jdbc, transactions, access, context);
    reader.readAudit = audit;
    reader.embeddingFingerprint = embeddingFingerprint;
    reader.dualEmbeddings = dualEmbeddings;
    reader.embeddingQuery = embeddingQuery;
    reader.embeddingProfile = embeddingProfile;
    return reader;
  }

  private int writeUpdate(String sql, Object... args) {
    if (access != null)
      throw new IllegalStateException("a scoped reader cannot mutate projections");
    return transactions.inTransaction(
        () -> {
          if (mutationFence != null) mutationFence.run();
          return jdbc.update(sql, args);
        });
  }

  private record ReadQuery(String sql, Object[] arguments) {}

  /**
   * Explicit markers keep predicates and their bindings before ranking/paging, without parsing SQL.
   */
  private ReadQuery guarded(String sql, String documentExpression, Object... arguments) {
    boolean discovery = sql.contains("/*information:discover*/");
    String vectorStage =
        sql.contains("/*information:summary-vector*/")
            ? "summary_embed"
            : sql.contains("/*information:passage-vector*/") ? "embed" : null;
    sql =
        sql.replace("/*information:summary-vector*/", "")
            .replace("/*information:passage-vector*/", "");
    sql = sql.replace("/*information:discover*/", "");
    String and = "/*information:and*/";
    String where = "/*information:where*/";
    boolean hasWhere = sql.contains(and);
    String marker = hasWhere ? and : where;
    int at = sql.indexOf(marker);
    if (at < 0 || sql.indexOf(marker, at + marker.length()) >= 0) {
      throw new IllegalStateException("corpus read must have exactly one policy seam");
    }
    if (access == null) {
      if (discovery || vectorStage != null) {
        String predicate =
            "EXISTS (SELECT 1 FROM documents corpus_row WHERE corpus_row.id="
                + documentExpression
                + " AND corpus_row.document_type='document')";
        return new ReadQuery(
            sql.replace(marker, (hasWhere ? " AND " : " WHERE ") + predicate), arguments);
      }
      return new ReadQuery(sql.replace(marker, ""), arguments);
    }
    io.aeyer.plowshare.server.information.InformationSql.Filter filter =
        io.aeyer.plowshare.server.information.InformationSql.read(
            access.admitted(context), "information_row");
    String predicate =
        "EXISTS (SELECT 1 FROM documents information_row WHERE"
            + " information_row.id = "
            + documentExpression
            + " AND "
            + filter.sql()
            + " AND information_row.document_type='"
            + context.corpus().documentType()
            + "'"
            + (vectorStage == null ? "" : " AND " + compatible("information_row.id", vectorStage))
            + (discovery
                ? " AND NOT EXISTS(SELECT 1 FROM information_revisions excluded WHERE excluded.id=information_row.id AND excluded.excluded) AND NOT EXISTS(SELECT 1 FROM information_reports report WHERE report.revision_id=information_row.id AND report.status<>'final')"
                : "")
            + ")";
    var facets =
        io.aeyer.plowshare.server.information.InformationFacetSql.facets(
            context.facets(), "facet_revision", "facet_resource");
    if (!context.facets().empty())
      predicate +=
          " AND EXISTS(SELECT 1 FROM information_revisions facet_revision JOIN information_resources facet_resource ON facet_resource.id=facet_revision.resource_id WHERE facet_revision.id="
              + documentExpression
              + " AND "
              + facets.sql()
              + ")";
    int before = (int) sql.substring(0, at).chars().filter(c -> c == '?').count();
    List<Object> bound = new ArrayList<>();
    bound.addAll(java.util.Arrays.asList(arguments).subList(0, before));
    bound.addAll(filter.arguments());
    if (!context.facets().empty()) bound.addAll(facets.arguments());
    bound.addAll(java.util.Arrays.asList(arguments).subList(before, arguments.length));
    return new ReadQuery(
        sql.replace(marker, (hasWhere ? " AND " : " WHERE ") + predicate), bound.toArray());
  }

  private <T> List<T> readRows(String expression, String sql, RowMapper<T> mapper, Object... args) {
    ReadQuery query = embeddingSql(guarded(sql, expression, args));
    List<T> rows = jdbc.query(query.sql(), mapper, query.arguments());
    if (readAudit != null)
      for (T row : rows) {
        UUID document =
            switch (row) {
              case StoredDocument d -> d.id();
              case Listed d -> d.document().id();
              case Hit d -> d.documentId();
              case Passage d -> null;
              case Retrieved d -> d.chunk().documentId();
              case Ranked d -> d.document().id();
              default -> null;
            };
        if (document != null) readAudit.accept(document);
      }
    return rows;
  }

  private <T> List<T> readList(String expression, String sql, Class<T> type, Object... args) {
    ReadQuery query = embeddingSql(guarded(sql, expression, args));
    return jdbc.queryForList(query.sql(), type, query.arguments());
  }

  private <T> T readValue(String expression, String sql, RowMapper<T> mapper, Object... args) {
    ReadQuery query = embeddingSql(guarded(sql, expression, args));
    return jdbc.queryForObject(query.sql(), mapper, query.arguments());
  }

  private <T> T readScalar(String expression, String sql, Class<T> type, Object... args) {
    ReadQuery query = embeddingSql(guarded(sql, expression, args));
    return jdbc.queryForObject(query.sql(), type, query.arguments());
  }

  private <T> T readExtract(
      String expression, String sql, ResultSetExtractor<T> mapper, Object... args) {
    ReadQuery query = embeddingSql(guarded(sql, expression, args));
    return jdbc.query(query.sql(), mapper, query.arguments());
  }

  private void readEach(String expression, String sql, RowCallbackHandler mapper, Object... args) {
    ReadQuery query = embeddingSql(guarded(sql, expression, args));
    jdbc.query(query.sql(), mapper, query.arguments());
  }

  /** The document filed under this name, if the corpus holds one. */
  public Optional<StoredDocument> find(String sourceName) {
    return readRows(
            "documents.id",
            SELECT_DOCUMENT
                + " WHERE source_name = ? /*information:discover*/ /*information:and*/ ORDER BY ingested_at DESC, id LIMIT 1",
            DOCUMENT,
            sourceName)
        .stream()
        .findFirst();
  }

  /**
   * The document with this id, if the corpus holds one.
   *
   * <p><b>The identity read the per-document ask needs, and the reason it is by id rather than by
   * name.</b> Every other caller in this class reaches a document by {@code source_name}, which is
   * V18's identity and what an upload is filed under. An ask is asked <em>of</em> a document that
   * has already been chosen, and what names it at that point is the id on the row — the same id a
   * citation carries and a hierarchy hangs off. A caller holding an id and having to look up a name
   * to look up the row would be this class handing back a fact in order to be asked for it.
   *
   * <p>Empty for an id naming nothing, which is a caller's ordinary mistake — a uuid typed wrong, a
   * document deleted since — and not an error here. The surface that took the id is where a refusal
   * belongs.
   */
  public Optional<StoredDocument> find(UUID documentId) {
    Objects.requireNonNull(documentId, "documentId");
    return readRows(
            "documents.id",
            SELECT_DOCUMENT + " WHERE id = ? /*information:and*/",
            DOCUMENT,
            documentId)
        .stream()
        .findFirst();
  }

  /**
   * The columns {@link #readDocument} reads, without a {@code SELECT} or a {@code FROM} around
   * them. Split out from {@link #SELECT_DOCUMENT} when {@link #rankBySummary} needed the same
   * columns with a computed one beside them: the alternative was a second copy of the list, which
   * is the copy that stops matching the mapper.
   */
  private static final String DOCUMENT_COLUMNS =
      "id, source_name, title, content_hash, text_hash, byte_size,"
          + " ingested_at, ingested_by, summary, top_level_label, document_type, document_subtype";

  private static final String SELECT_DOCUMENT = "SELECT " + DOCUMENT_COLUMNS + " FROM documents";

  /**
   * Write one derivation of one document, replacing whatever was there.
   *
   * <p>All of it in one transaction: a half-applied derivation is a document whose paragraphs are
   * partly the old text and partly the new, which no later ingest would repair because nothing
   * would record that it happened.
   *
   * @param sourceName what the corpus files this document as. The identity; see V18 for why it is
   *     not the content hash
   * @param extracted the title, the content hash of the uploaded bytes, and the text those two
   *     describe
   * @param byteSize how large the upload was
   * @param ingestedBy who asked for this ingest
   * @param at when
   * @param derived the derivation: the hierarchy, the paragraphs under it, and the document's own
   *     bibliography
   */
  public Written write(
      String sourceName,
      Extracted extracted,
      long byteSize,
      String ingestedBy,
      Instant at,
      DerivedDocument derived) {
    return writeProjection(null, sourceName, extracted, byteSize, ingestedBy, at, derived);
  }

  /** An immutable revision has its own physical projection; names are never global identities. */
  public Written writeRevision(
      UUID revision,
      String sourceName,
      Extracted extracted,
      long byteSize,
      String by,
      Instant at,
      DerivedDocument derived) {
    Objects.requireNonNull(revision, "revision");
    return writeProjection(revision, sourceName, extracted, byteSize, by, at, derived);
  }

  private Written writeProjection(
      UUID revision,
      String sourceName,
      Extracted extracted,
      long byteSize,
      String ingestedBy,
      Instant at,
      DerivedDocument derived) {
    if (access != null) throw new IllegalStateException("a scoped reader cannot write projections");
    Objects.requireNonNull(sourceName, "sourceName");
    Objects.requireNonNull(extracted, "extracted");
    Objects.requireNonNull(derived, "derived");
    List<DerivedParagraph> paragraphs = derived.paragraphs();
    return transactions.inTransaction(
        () -> {
          if (mutationFence != null) mutationFence.run();
          if (revision != null && find(revision).isPresent()) {
            if (!find(revision).orElseThrow().contentHash().equals(extracted.contentHash()))
              throw new IllegalStateException("revision bytes changed");
            return new Written(
                revision,
                paragraphs.size(),
                0,
                0,
                0,
                derived.chapters().size(),
                derived.chapters().stream().mapToInt(c -> c.sections().size()).sum());
          }
          UUID documentId =
              upsertDocument(
                  revision, sourceName, extracted, byteSize, ingestedBy, at, derived.vocabulary());

          // THE HIERARCHY GOES FIRST, and it goes wholesale.
          //
          // Dropping the chapters cascades to the sections and, through
          // `paragraphs_are_in_their_sections_document`, DETACHES the
          // paragraphs rather than deleting them — V26 argues that ON DELETE
          // SET NULL at length, and it is what lets the identity rule below
          // run over rows that are still here. For the length of this
          // transaction a document's paragraphs hold the null V18's extension
          // clause describes, and they are re-pointed before it commits.
          //
          // Wholesale, and not matched the way paragraphs are: nothing cites a
          // chapter, the detectors re-run over the whole text anyway, and a
          // summary written from paragraph summaries that have changed is
          // exactly the stale-and-confident answer `documents.summary` is
          // nulled to avoid one table up.
          writeUpdate("DELETE FROM chapters WHERE document_id = ?", documentId);
          writeUpdate("DELETE FROM document_references WHERE document_id = ?", documentId);

          Map<String, UUID> existing = existingParagraphs(documentId);

          // The rule. Everything below is bookkeeping over its answer.
          List<Matched> matched = new ArrayList<>(paragraphs.size());
          Set<UUID> keeping = new HashSet<>();
          int kept = 0;
          for (DerivedParagraph paragraph : paragraphs) {
            UUID id = match(existing, paragraph);
            if (id != null) {
              kept++;
              keeping.add(id);
              matched.add(new Matched(id, paragraph, false));
            } else {
              matched.add(new Matched(UUID.randomUUID(), paragraph, true));
            }
          }

          // Deletes first, so a removed paragraph's ordinal is free before
          // anything is moved onto it. The deferred constraint means this
          // ordering is a courtesy rather than a requirement, and courtesies
          // that keep a statement's failure legible are worth having.
          List<UUID> removed =
              existing.values().stream().filter(id -> !keeping.contains(id)).toList();
          for (UUID id : removed) {
            writeUpdate("DELETE FROM paragraphs WHERE id = ?", id);
          }

          int chunks = 0;
          Map<Integer, UUID> byOrdinal = new HashMap<>();
          for (Matched entry : matched) {
            byOrdinal.put(entry.paragraph().ordinal(), entry.id());
            if (entry.isNew()) {
              insertParagraph(documentId, entry);
              chunks += insertChunks(entry);
            } else {
              // Ordinal alone. The text and the hash are what matched, so
              // rewriting them would be writing the same bytes over
              // themselves; the occurrence is part of the key and cannot
              // have moved without producing a different id.
              writeUpdate(
                  "UPDATE paragraphs SET ordinal = ? WHERE id = ?",
                  entry.paragraph().ordinal(),
                  entry.id());
            }
          }
          if (matched.size() - kept > 0 || !removed.isEmpty()) {
            // IN THE SAME TRANSACTION THAT CHANGED IT. A document summary is
            // derived from every paragraph summary under it, so a document
            // that gained or lost a paragraph is one the stored sentence is
            // no longer about -- and a stale summary is the confident,
            // readable, wrong answer rather than a missing one. Kept
            // paragraphs keep their own summaries, because their text is
            // what matched; it is only the sentence ABOUT ALL OF THEM that
            // has stopped being true.
            writeUpdate("UPDATE documents SET summary = NULL WHERE id = ?", documentId);
          }

          int sections = insertHierarchy(documentId, derived, byOrdinal);
          insertReferences(documentId, derived.references());
          return new Written(
              documentId,
              kept,
              matched.size() - kept,
              removed.size(),
              chunks,
              derived.chapters().size(),
              sections);
        });
  }

  /**
   * The chapters and sections of one derivation, and the edge from each paragraph to the section it
   * is in.
   *
   * <p><b>One statement per section rather than one per paragraph</b>, which is a property of the
   * derivation rather than an optimisation: {@code DerivedDocument.paragraphs()} walks the tree in
   * order and the document's ordinals are assigned along that walk, so a section's paragraphs are a
   * contiguous run of them. The range is taken from the first and last paragraph the section
   * actually holds, so a section with none is skipped and cannot null anything.
   *
   * @return how many sections were written
   */
  private int insertHierarchy(
      UUID documentId, DerivedDocument derived, Map<Integer, UUID> byOrdinal) {
    int sections = 0;
    for (DerivedDocument.Chapter chapter : derived.chapters()) {
      UUID chapterId = UUID.randomUUID();
      writeUpdate(
          "INSERT INTO chapters (id, document_id, ordinal, title, is_synthetic)"
              + " VALUES (?, ?, ?, ?, ?)",
          chapterId,
          documentId,
          chapter.ordinal(),
          // THE ONE PLACE THE SENTINEL IS CHOSEN. Everything else
          // holds a StructuralRef and cannot reach a title without
          // saying how it degrades; this is the persistence mapper
          // Anchor's own javadoc excepts from that rule.
          chapter.title().storedTitle(SyntheticTitles.CHAPTER),
          chapter.title().isSynthetic());
      for (DerivedDocument.Section section : chapter.sections()) {
        UUID sectionId = UUID.randomUUID();
        writeUpdate(
            "INSERT INTO sections (id, chapter_id, document_id, ordinal, title,"
                + " is_synthetic) VALUES (?, ?, ?, ?, ?, ?)",
            sectionId,
            chapterId,
            documentId,
            section.ordinal(),
            section.title().storedTitle(SyntheticTitles.SECTION),
            section.title().isSynthetic());
        sections++;
        List<DerivedParagraph> held = section.paragraphs();
        if (held.isEmpty()) {
          continue;
        }
        writeUpdate(
            "UPDATE paragraphs SET section_id = ? WHERE document_id = ?"
                + " AND ordinal BETWEEN ? AND ?",
            sectionId,
            documentId,
            held.get(0).ordinal(),
            held.get(held.size() - 1).ordinal());
      }
    }
    return sections;
  }

  /**
   * The document's own bibliography. Rewritten whole on every ingest, like the hierarchy: it is
   * derived from the same text by the same pass.
   */
  private void insertReferences(UUID documentId, List<ReferencesExtractor.Reference> references) {
    for (ReferencesExtractor.Reference reference : references) {
      writeUpdate(
          "INSERT INTO document_references (id, document_id, ref_num, raw)"
              + " VALUES (?, ?, ?, ?)",
          UUID.randomUUID(),
          documentId,
          reference.refNum(),
          reference.raw());
    }
  }

  /**
   * <b>The identity rule, and the only place it is written.</b>
   *
   * <p>Document + content hash + occurrence. The document is implicit — {@code existing} was loaded
   * for one document — and the other two are the key.
   *
   * <p>The known limitation, recorded in V18 as well: inserting a duplicate above an existing one
   * shifts the later one's occurrence, so it fails to match and takes a new id. That is a bug that
   * can be fixed by changing this method, which is the entire benefit of the surrogate key.
   *
   * @return the existing row's id, or null for a paragraph the corpus has not seen in this document
   */
  private static UUID match(Map<String, UUID> existing, DerivedParagraph paragraph) {
    return existing.get(key(paragraph.contentHash(), paragraph.occurrence()));
  }

  private static String key(String contentHash, int occurrence) {
    return contentHash + ":" + occurrence;
  }

  private Map<String, UUID> existingParagraphs(UUID documentId) {
    Map<String, UUID> byKey = new HashMap<>();
    jdbc.query(
        "SELECT id, content_hash, occurrence FROM paragraphs WHERE document_id = ?",
        rs -> {
          byKey.put(
              key(rs.getString("content_hash"), rs.getInt("occurrence")),
              (UUID) rs.getObject("id"));
        },
        documentId);
    return byKey;
  }

  private UUID upsertDocument(
      UUID revision,
      String sourceName,
      Extracted extracted,
      long byteSize,
      String ingestedBy,
      Instant at,
      Vocabulary vocabulary) {
    Optional<StoredDocument> existing =
        revision == null
            ? jdbc
                .query(
                    SELECT_DOCUMENT + " WHERE source_name = ? AND information_namespace = 'legacy'",
                    DOCUMENT,
                    sourceName)
                .stream()
                .findFirst()
            : find(revision);
    if (revision != null && existing.isPresent()) {
      if (!existing.get().contentHash().equals(extracted.contentHash()))
        throw new IllegalStateException("an immutable revision cannot change its bytes");
      return revision;
    }
    OffsetDateTime when = at.atOffset(ZoneOffset.UTC);
    // The enum's own name, which is what V26's CHECK is written over, so a
    // fourth value is a migration and a prompt change together rather than a
    // string nothing refuses.
    String label = vocabulary.name();
    var type =
        io.aeyer.plowshare.protocol.DocumentType.classify(
            sourceName,
            PdfExtraction.CONVERTER.equals(extracted.converter()) ? "application/pdf" : null);
    if (revision != null) {
      var metadata =
          jdbc.queryForMap(
              "SELECT document_type,document_subtype FROM information_revisions WHERE id=?",
              revision);
      type =
          new io.aeyer.plowshare.protocol.DocumentType(
              (String) metadata.get("document_type"), (String) metadata.get("document_subtype"));
    }
    if (existing.isPresent()) {
      writeUpdate(
          "UPDATE documents SET title = ?, content_hash = ?, text_hash = ?,"
              + " byte_size = ?, ingested_at = ?, ingested_by = ?,"
              + " top_level_label = ?, document_type = ?, document_subtype = ? WHERE id = ?",
          extracted.title(),
          extracted.contentHash(),
          Derivation.sha256(extracted.text()),
          byteSize,
          when,
          ingestedBy,
          label,
          type.type(),
          type.subtype(),
          existing.get().id());
      return existing.get().id();
    }
    UUID id = revision == null ? UUID.randomUUID() : revision;
    writeUpdate(
        "INSERT INTO documents (id, source_name, title, content_hash, text_hash,"
            + " byte_size, ingested_at, ingested_by, top_level_label, information_namespace, document_type, document_subtype)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        sourceName,
        extracted.title(),
        extracted.contentHash(),
        Derivation.sha256(extracted.text()),
        byteSize,
        when,
        ingestedBy,
        label,
        revision == null ? "legacy" : "revision:" + revision,
        type.type(),
        type.subtype());
    return id;
  }

  /** The code derivation covers source contiguously; locations are bound to chunk identity. */
  public void locateCodePassages(UUID document, String source) {
    if (source.isBlank())
      return; // No passages; the immutable extraction still preserves every character.
    if (access != null) throw new IllegalStateException("a reader cannot locate code projections");
    transactions.inTransaction(
        () -> {
          if (mutationFence != null) mutationFence.run();
          var rows =
              jdbc.queryForList(
                  "SELECT c.id,c.text FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id"
                      + " WHERE p.document_id=? ORDER BY p.ordinal,c.ordinal",
                  document);
          int offset = 0;
          for (var row : rows) {
            String text = (String) row.get("text");
            if (!source.startsWith(text, offset))
              throw new IllegalStateException("code projection differs from retained source");
            jdbc.update(
                "INSERT INTO code_passages(chunk_id,document_id,start_offset,end_offset) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                row.get("id"),
                document,
                offset,
                offset + text.length());
            offset += text.length();
          }
          if (offset != source.length())
            throw new IllegalStateException("code projection did not cover the retained source");
          return null;
        });
  }

  /** Persist a complete checkpoint under the same write fence as code chunks. */
  public void writeCodeOutline(UUID document, CodeOutline outline) {
    if (access != null) throw new IllegalStateException("a reader cannot write code outlines");
    transactions.inTransaction(
        () -> {
          if (mutationFence != null) mutationFence.run();
          jdbc.update("DELETE FROM code_outlines WHERE document_id=?", document);
          jdbc.update(
              "INSERT INTO code_outlines(document_id,source_hash,language,parser_version,status,reason,symbol_count) VALUES(?,?,?,?,?,?,?)",
              document,
              outline.sourceHash(),
              outline.language(),
              outline.parser(),
              outline.status(),
              outline.reason(),
              outline.symbols().size());
          for (var symbol : outline.symbols())
            jdbc.update(
                "INSERT INTO code_symbols(document_id,ordinal,name,kind,qualified_name,parent_ordinal,signature,start_offset,end_offset,start_line,end_line) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                document,
                symbol.ordinal(),
                symbol.name(),
                symbol.kind(),
                symbol.qualifiedName(),
                symbol.parentOrdinal(),
                symbol.signature(),
                symbol.start(),
                symbol.end(),
                symbol.startLine(),
                symbol.endLine());
          return null;
        });
  }

  private void insertParagraph(UUID documentId, Matched entry) {
    DerivedParagraph paragraph = entry.paragraph();
    writeUpdate(
        "INSERT INTO paragraphs (id, document_id, text, content_hash, occurrence, ordinal)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        entry.id(),
        documentId,
        paragraph.text(),
        paragraph.contentHash(),
        paragraph.occurrence(),
        paragraph.ordinal());
  }

  private int insertChunks(Matched entry) {
    List<Chunker.Chunk> chunks = entry.paragraph().chunks();
    for (int i = 0; i < chunks.size(); i++) {
      Chunker.Chunk chunk = chunks.get(i);
      // embedding left NULL. See the class javadoc: the text is durable
      // before a model is called, and never inside the same transaction.
      writeUpdate(
          "INSERT INTO chunks (id, paragraph_id, ordinal, text, byte_size,"
              + " split_mid_sentence) VALUES (?, ?, ?, ?, ?, ?)",
          UUID.randomUUID(),
          entry.id(),
          i + 1,
          chunk.text(),
          chunk.bytes(),
          chunk.splitMidSentence());
    }
    return chunks.size();
  }

  /**
   * The chunks of one document that have no vector yet, oldest paragraph first.
   *
   * <p>Ordered by paragraph and then by chunk so a partially embedded document is embedded front to
   * back rather than in whatever order the plan returns — which matters only for how an interrupted
   * ingest looks, and looking arbitrary is how a reader concludes something is wrong.
   */
  public List<UnembeddedChunk> unembedded(UUID documentId) {
    return readRows(
        "p.document_id",
        "SELECT c.id, c.text FROM chunks c JOIN paragraphs p ON p.id = c.paragraph_id"
            + " WHERE p.document_id = ? AND c.embedding IS NULL"
            + " /*information:and*/ ORDER BY p.ordinal, c.ordinal",
        (rs, row) -> new UnembeddedChunk((UUID) rs.getObject("id"), rs.getString("text")),
        documentId);
  }

  /**
   * How many chunks of this document a search could not reach.
   *
   * <p>{@code MemoryStore.countUnsearchable}'s counterpart, and it exists for the same reason: the
   * {@code embedding IS NULL} filter is invisible from the outside, so an ingest that stored
   * everything and embedded nothing would look exactly like one that worked.
   */
  public int countUnembedded(UUID documentId) {
    Integer count =
        readScalar(
            "p.document_id",
            "SELECT count(*) FROM chunks c JOIN paragraphs p ON p.id = c.paragraph_id"
                + " WHERE p.document_id = ? AND c.embedding IS NULL /*information:and*/",
            Integer.class,
            documentId);
    return count == null ? 0 : count;
  }

  // --- the summary side ---------------------------------------------------------

  /**
   * The paragraphs of one document that hold no summary yet, in document order.
   *
   * <p><b>{@link #unembedded} exactly, one derived thing over.</b> The order matters for the same
   * small reason and for one larger one: a partially summarised document is summarised front to
   * back rather than in whatever order the plan returns, and — because this is also the query that
   * says what a stopped cascade still owes — a reader comparing two runs of it sees a prefix
   * shrinking rather than a set changing.
   *
   * <p>This is the resumption gate. A cascade that spent its whole allowance, was cancelled, or
   * died with the process leaves the paragraphs it did not reach holding null here, and the next
   * ingest of the same document finds exactly those — from their own rows, with nothing outside
   * this database needing to have survived.
   */
  public List<UnsummarisedParagraph> unsummarised(UUID documentId) {
    return readRows(
        "paragraphs.document_id",
        "SELECT id, ordinal, text FROM paragraphs"
            + " WHERE document_id = ? AND summary IS NULL /*information:and*/ ORDER BY ordinal",
        (rs, row) ->
            new UnsummarisedParagraph(
                (UUID) rs.getObject("id"), rs.getInt("ordinal"), rs.getString("text")),
        documentId);
  }

  /**
   * Every summary this document holds, in the order the document makes them.
   *
   * <p>What the level above reads. <b>A paragraph with no summary is left out rather than
   * represented by a blank</b>, which is the one decision in this method: a blank line in the
   * middle of an ordered run of claims is a claim the document does not make, and the level above
   * cannot tell it from a paragraph that genuinely asserts nothing. A caller that needs to know
   * some are missing compares this against {@link #unsummarised}, which is the question asked in
   * the words it is asked in.
   */
  public List<String> paragraphSummaries(UUID documentId) {
    return readList(
        "paragraphs.document_id",
        "SELECT summary FROM paragraphs"
            + " WHERE document_id = ? AND summary IS NOT NULL /*information:and*/ ORDER BY ordinal",
        String.class,
        documentId);
  }

  /**
   * Put a summary on a paragraph that had none.
   *
   * <p>{@link #attach}'s shape, and its reason: one row at a time, outside any transaction of this
   * class's, so that a cascade stopped after two hundred calls keeps two hundred summaries. A batch
   * write at the end would make the whole ~26 minutes all-or-nothing, which is the property {@code
   * IngestService} refused for embedding and refuses again here.
   */
  public void attachSummary(UUID paragraphId, String summary) {
    writeUpdate("UPDATE paragraphs SET summary = ? WHERE id = ?", summary, paragraphId);
  }

  /**
   * What this document claims, or null for one nothing has summarised yet.
   *
   * <p>By id and not by name, which is the difference from {@link #find}: the cascade holds a
   * document id and never the string a caller filed it under, and a lookup by name there would be
   * this class handing back a fact the caller would have to look up in order to ask for.
   */
  public String documentSummary(UUID documentId) {
    List<String> found =
        readList(
            "documents.id",
            "SELECT summary FROM documents WHERE id = ? /*information:and*/",
            String.class,
            documentId);
    return found.isEmpty() ? null : found.get(0);
  }

  /**
   * Put a summary on a document. Nulled again by {@link #write} the moment a re-ingest changes what
   * it was written from.
   */
  public void attachDocumentSummary(UUID documentId, String summary) {
    writeUpdate("UPDATE documents SET summary = ? WHERE id = ?", summary, documentId);
  }

  // --- the two middle tiers -------------------------------------------------------

  /**
   * The document's chapters and their sections, in the order the document makes them, with whatever
   * summaries they already hold.
   *
   * <p><b>{@link StructuralRef} and not a title and a flag</b>, which is the whole of what V26's
   * two columns are for: this is the persistence mapper Anchor's own javadoc excepts from its grep,
   * and past it nothing can reach a unit's name without saying how it degrades when the parser
   * invented one.
   *
   * <p>Read whole rather than a chapter at a time. The hierarchy of one document is tens of rows
   * against the hundreds of paragraphs under it, and the cascade walks all of it twice — once to
   * summarise the sections and once to summarise the chapters from what that wrote.
   */
  public List<StoredChapter> hierarchy(UUID documentId) {
    Map<UUID, List<StoredSection>> sections = new HashMap<>();
    readEach(
        "s.document_id",
        "SELECT s.id, s.chapter_id, s.title, s.is_synthetic, s.summary FROM sections s"
            + " JOIN chapters c ON c.id = s.chapter_id"
            + " WHERE s.document_id = ? /*information:and*/ ORDER BY c.ordinal, s.ordinal",
        rs -> {
          sections
              .computeIfAbsent((UUID) rs.getObject("chapter_id"), chapter -> new ArrayList<>())
              .add(
                  new StoredSection(
                      (UUID) rs.getObject("id"),
                      StructuralRef.of(rs.getString("title"), rs.getBoolean("is_synthetic")),
                      rs.getString("summary")));
        },
        documentId);
    return readRows(
        "chapters.document_id",
        "SELECT id, title, is_synthetic, summary FROM chapters"
            + " WHERE document_id = ? /*information:and*/ ORDER BY ordinal",
        (rs, row) -> {
          UUID id = (UUID) rs.getObject("id");
          return new StoredChapter(
              id,
              StructuralRef.of(rs.getString("title"), rs.getBoolean("is_synthetic")),
              rs.getString("summary"),
              sections.getOrDefault(id, List.of()));
        },
        documentId);
  }

  /**
   * The summaries of one section's paragraphs, in document order.
   *
   * <p>{@link #paragraphSummaries} one level down, and it leaves a paragraph holding no summary out
   * for that method's reason exactly: a blank in an ordered run of claims is a claim the document
   * does not make, and the tier above cannot tell it from a paragraph that asserts nothing.
   */
  public List<String> sectionParagraphSummaries(UUID sectionId) {
    return readList(
        "paragraphs.document_id",
        "SELECT summary FROM paragraphs"
            + " WHERE section_id = ? AND summary IS NOT NULL /*information:and*/ ORDER BY ordinal",
        String.class,
        sectionId);
  }

  /**
   * Put a summary on a section. {@link #attachSummary}'s shape and its reason: one row at a time,
   * so a cascade that stops keeps what it paid for.
   */
  public void attachSectionSummary(UUID sectionId, String summary) {
    writeUpdate("UPDATE sections SET summary = ? WHERE id = ?", summary, sectionId);
  }

  /** Put a summary on a chapter. */
  public void attachChapterSummary(UUID chapterId, String summary) {
    writeUpdate("UPDATE chapters SET summary = ? WHERE id = ?", summary, chapterId);
  }

  /**
   * How many chapters and sections of this document are still owed a summary — <b>the resumption
   * gate for the two middle tiers</b>.
   *
   * <p>{@link #unsummarised} is the paragraph tier's; this is the other two's, and it is asked in
   * the same words: a unit is owed a summary when it holds no summary and has something to write
   * one from. A section has something when one of its paragraphs is labelled; a chapter has
   * something when one of its sections does, which is the same condition one level down, so the two
   * questions are asked over the same {@code EXISTS} and neither depends on work this run has not
   * done yet.
   *
   * <p><b>Why this exists at all, which is a decision rather than a detail.</b> A re-ingest
   * replaces the hierarchy wholesale — the detectors run again over new text — while V18's identity
   * rule keeps an unchanged paragraph's id and therefore its summary. So a document can hold every
   * paragraph summary it will ever need, and a document summary written before the re-ingest, over
   * chapters and sections that hold nothing. Without this the cascade's "nothing changed, nothing
   * to pay for" gate would fire on exactly that document and leave the two tiers the per-document
   * ask reads empty for ever.
   *
   * <p>A section that holds no labelled paragraph, and a chapter with no such section, are not owed
   * anything and are not counted: they are the empty units a heading immediately followed by
   * another heading leaves behind, and the cascade skips them for the same reason.
   */
  public int unsummarisedUnits(UUID documentId) {
    Integer sections =
        readScalar(
            "s.document_id",
            "SELECT count(*) FROM sections s WHERE s.document_id = ? AND s.summary IS NULL"
                + " AND EXISTS (SELECT 1 FROM paragraphs p WHERE p.section_id = s.id"
                + " AND p.summary IS NOT NULL) /*information:and*/",
            Integer.class,
            documentId);
    Integer chapters =
        readScalar(
            "c.document_id",
            "SELECT count(*) FROM chapters c WHERE c.document_id = ? AND c.summary IS NULL"
                + " AND EXISTS (SELECT 1 FROM sections s JOIN paragraphs p"
                + " ON p.section_id = s.id WHERE s.chapter_id = c.id"
                + " AND p.summary IS NOT NULL) /*information:and*/",
            Integer.class,
            documentId);
    return (sections == null ? 0 : sections) + (chapters == null ? 0 : chapters);
  }

  /**
   * How many labelled paragraphs of this document hang off no section.
   *
   * <p><b>The hole the upper tiers cannot see.</b> {@code paragraphs.section_id} is nullable — V18
   * froze {@code document_id NOT NULL} and V26 could only add beside it — so a paragraph written
   * before the hierarchy existed, or one detached by a delete and never re-pointed, is summarised
   * at the bottom tier and then read by nothing above it. A document summary written over that is a
   * summary of a document with a paragraph missing, and every level above reads summaries rather
   * than text, so nothing downstream could tell. The cascade asks this before it starts up the
   * tiers and refuses rather than writing one.
   */
  public int summarisedParagraphsInNoSection(UUID documentId) {
    Integer orphans =
        readScalar(
            "paragraphs.document_id",
            "SELECT count(*) FROM paragraphs WHERE document_id = ? AND section_id IS NULL"
                + " AND summary IS NOT NULL /*information:and*/",
            Integer.class,
            documentId);
    return orphans == null ? 0 : orphans;
  }

  // --- what the per-document ask reads --------------------------------------------

  /**
   * The bibliography this document prints, in the order it numbers it.
   *
   * <p><b>Not {@code citations}, and the two must not be reconciled.</b> V25's table records what
   * an <em>answer</em> took from the corpus; a row here is a work the <em>document</em> cites.
   * Anchor feeds these to its proposer and its critic as {@code document_citations} for one
   * specific job, which is the job the ask needs them for: telling <em>an author of this
   * document</em> from <em>a third party this document cites</em>, so a question naming Wagner is
   * answered about the method if Wagner wrote the paper and about the cited work if Wagner is in
   * this list.
   *
   * <p><b>Written since V26 and read by nothing until now.</b> {@code ReferencesExtractor} re-walks
   * the raw text for the reference block precisely so this list survives the detectors dropping
   * that block from the hierarchy; this is what pays that back.
   *
   * <p>Empty for a document that prints no bibliography, which is most of a corpus and is not a
   * gap.
   */
  public List<StoredReference> references(UUID documentId) {
    Objects.requireNonNull(documentId, "documentId");
    return readRows(
        "document_references.document_id",
        "SELECT ref_num, raw FROM document_references WHERE document_id = ?"
            + " /*information:and*/ ORDER BY ref_num",
        (rs, row) -> new StoredReference(rs.getInt("ref_num"), rs.getString("raw")),
        documentId);
  }

  /**
   * The text of one paragraph <b>of one named document</b>, or null.
   *
   * <p><b>The document is a parameter and not a convenience, and that is the whole reason this is
   * not {@code paragraphText(UUID)}.</b> It is the read the ask's grounding check runs on: {@code
   * ask_synthesiser} returns a paragraph id and the words it says its claim rests on, and the words
   * are looked for in that paragraph. A per-document ask that accepted a paragraph of <em>some
   * other paper</em> would validate a quotation against a document nobody asked about — and {@link
   * CitationStore#record} could not catch it, because it validates against the corpus, which that
   * paragraph really is in. So the scope is applied here, in the same statement that fetches the
   * text.
   *
   * <p>Null for a paragraph this document does not hold, for an id naming nothing at all, and for a
   * paragraph edited away since the answer was written. All three are the same fact from where the
   * check stands: <em>the words cannot be looked for</em>, which is a failed attribution and is
   * said rather than dropped.
   */
  public String paragraphTextIn(UUID documentId, UUID paragraphId) {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(paragraphId, "paragraphId");
    List<String> found =
        readList(
            "paragraphs.document_id",
            "SELECT text FROM paragraphs WHERE id = ? AND document_id = ? /*information:and*/",
            String.class,
            paragraphId,
            documentId);
    return found.isEmpty() ? null : found.get(0);
  }

  /**
   * One entry of a document's own reference list.
   *
   * @param refNum the number the document itself prints, from 1. It is how the paper's prose points
   *     at the entry — "[16]" — so it is the entry's name rather than its position
   * @param raw the entry as the paper printed it. <b>Not this server's prose</b>: like {@code
   *     chunks.text} it is content the server did not write and cannot vouch for, and it reaches a
   *     prompt quoted for that reason
   */
  public record StoredReference(int refNum, String raw) {}

  // --- the read side ------------------------------------------------------------

  /**
   * <b>The query the HNSW index exists for, and the shape of it is load bearing in three
   * places.</b>
   *
   * <p>Package-private and read by {@code DocumentStoreTest.explainSearch}, which asks Postgres for
   * its plan. A test that explained a second copy of this string would go on passing after the real
   * one stopped being index-servable, which is the failure that matters here: every other assertion
   * about this method returns the same rows either way.
   *
   * <ul>
   *   <li><b>{@code ORDER BY} distance and nothing else.</b> {@code MemoryStore.searchByVector}
   *       appends {@code , m.id} to make two equidistant rows come back in a fixed order; it can
   *       afford to, because it has no index to lose. Here a second sort key asks for an ordering
   *       {@code chunks_by_vector} does not produce, and what happens then is a plan choice rather
   *       than an error — the whole read quietly becomes the sequential scan V18 wrote three
   *       paragraphs explaining that documents must not inherit. The tie-break is therefore done in
   *       Java, over the handful of rows that came back, in {@link #searchByVector}.
   *   <li><b>The distance is selected once and ordered by its output name.</b> Naming the vector
   *       twice would put the same literal on the wire twice for one question; {@code ORDER BY
   *       distance} resolves to the same expression and keeps the index. The similarity a caller
   *       reads is {@code 1 - distance}, computed in Java for the same reason.
   *   <li><b>{@code embedding IS NOT NULL} is not redundant.</b> The index holds no NULL rows, so
   *       while it is used the filter changes nothing. On any plan that falls back to a scan it
   *       changes everything: {@code NULL <=> v} is NULL, and NULL sorts <em>last</em> in an
   *       ascending order rather than nowhere — so a corpus with fewer embedded chunks than the
   *       limit would have its answer padded with chunks nothing compared, which is a hit that
   *       means nothing wearing the shape of one that does.
   * </ul>
   */
  static final String SEARCH_SQL =
      "SELECT c.id AS chunk_id, c.text AS chunk_text,"
          + " c.embedding <=> CAST(? AS vector) AS distance,"
          + " p.id AS paragraph_id, p.text AS paragraph_text,"
          + " p.ordinal AS paragraph_ordinal,"
          + " d.id AS document_id, d.source_name AS source_name, d.title AS title,"
          + " d.summary AS document_summary"
          + " FROM chunks c"
          + " JOIN paragraphs p ON p.id = c.paragraph_id"
          + " JOIN documents d ON d.id = p.document_id"
          + " WHERE c.embedding IS NOT NULL"
          + " /*information:and*/ ORDER BY distance"
          + " LIMIT ?";

  /**
   * The chunks nearest this vector, nearest first, each carrying the paragraph it came from.
   *
   * <p><b>A hit is a chunk and a citation is a paragraph</b>, which is why every row carries both.
   * V18's own comment on {@code chunks} says it: change the chunk rule and every chunk id moves
   * while the document has not, so nothing cites a chunk. The paragraph id beside it is the
   * surrogate key spec decision 1 bought — stable across a re-ingest that left the text alone, and
   * replaced rather than repointed by one that did not.
   *
   * <p><b>No {@code embedding IS NULL} repair is done here and none is reported here either.</b>
   * {@link #coverage} is the counterpart, and a caller that renders an empty answer without it is
   * making a claim about the corpus it has not checked. {@code MemoryStore.countUnsearchable}
   * carries the whole argument.
   *
   * <p><b>Package-private, and that is the enforcement rather than a courtesy.</b> A vector means
   * something only inside the space the model that produced it defines, and a question embedded by
   * a different model does not fail here — the distances compute, the rows sort, and the answer is
   * a ranking of nothing. Nothing outside {@code documents} can therefore reach this method at all:
   * {@link RetrievalService} is the package's only public read and it takes prose, so the vector
   * every search runs on is one the corpus's own {@link
   * io.aeyer.plowshare.server.llm.EmbeddingClient} produced. The alternative was a sentence in a
   * javadoc asking callers not to, which is the shape {@code DispatchingEmbeddingClient}'s own
   * javadoc argues against at length.
   *
   * @param embedding the query vector, from the corpus's own embedding client
   * @param limit how many hits at most. Non-positive is an empty answer and no query, matching
   *     {@code MemoryStore.searchByVector}: a caller that asked for none must not pay a round trip
   *     to be told so
   */
  List<Hit> searchByVector(float[] embedding, int limit) {
    Objects.requireNonNull(embedding, "embedding");
    if (limit <= 0) {
      return List.of();
    }
    List<Hit> hits =
        new ArrayList<>(
            readRows(
                "d.id",
                SEARCH_SQL + " /*information:discover*/ /*information:passage-vector*/",
                HIT,
                toVectorText(embedding),
                limit));
    // The total order the SQL deliberately does not ask the database for.
    // Over at most `limit` rows, so it costs nothing, and it means two
    // equidistant chunks come back in the same order on every call rather
    // than in whatever order the index walked them.
    hits.sort(
        Comparator.comparingDouble(Hit::distance).thenComparing(hit -> hit.chunkId().toString()));
    return List.copyOf(hits);
  }

  /**
   * <b>The one place the corpus's text search configuration is named on this side of the wire</b>,
   * and it has to be the one V21 generated {@code chunks.text_search} with.
   *
   * <p>This is the vector half's model coupling in a different currency. A question stemmed by a
   * configuration the corpus was not stemmed by does not fail: {@code websearch_to_tsquery} parses
   * it, the operator runs, and the matching quietly stops — {@code refilled} stemmed by {@code
   * english} is {@code refil} and left whole by {@code simple}, so one of the two spellings simply
   * never meets the other. There is no width to check the way {@link RetrievalService#search}
   * checks a vector's, because a tsquery has no shape that is wrong; so the check is {@code
   * DocumentStoreTest.the_migration_and_the_query_stem_by_the_same_configuration}, which reads V21
   * and this constant and compares them.
   *
   * <p>It could not have been a session setting on either side. A {@code GENERATED ALWAYS}
   * expression must be immutable and the one-argument {@code to_tsvector(text)} reads {@code
   * default_text_search_config}, so Postgres refuses it outright — which is the database insisting
   * on the honest arrangement, since an index whose meaning depended on a connection's settings
   * would stem one chunk one way and the next another.
   */
  static final String TEXT_SEARCH_CONFIGURATION = "english";

  /**
   * <b>The query the GIN index exists for, and it is not the vector query with a different ORDER
   * BY.</b>
   *
   * <p>Package-private and read by {@code DocumentStoreTest} the way {@link #SEARCH_SQL} is, and
   * for the same reason: a test that explained a second copy of this string would go on passing
   * after the real one stopped being index-servable.
   *
   * <ul>
   *   <li><b>{@code websearch_to_tsquery} and not {@code plainto_tsquery} or {@code
   *       to_tsquery}.</b> The last one throws on ordinary prose — a stray {@code &} or a bare
   *       quote is a syntax error — and this input is a question a model wrote. The first two
   *       differ in what they accept: {@code websearch_to_tsquery} takes quoted phrases and a
   *       leading dash as negation, never raises, and conjoins the rest, which is the behaviour a
   *       person already expects from a search box.
   *   <li><b>A conjunction is the decision, not a leftover.</b> Every content word of the question
   *       must be in the chunk. The alternative — rewrite the {@code &}s to {@code |}s, which is
   *       the usual trick — would make this side return something for every question, ranked by a
   *       function with <em>no inverse document frequency</em>: Postgres keeps no corpus-wide term
   *       statistics, so {@code ts_rank_cd} weighs the question's most ordinary word exactly as
   *       heavily as its rarest. The top of a disjunctive list is therefore the chunks that repeat
   *       common words, which is noise wearing the shape of a result. Conjoined, this side is high
   *       precision and low recall — it fires on identifiers, names and numbers and stays quiet
   *       otherwise — and the vector half is the recall. That division is what the fusion is over.
   *   <li><b>{@code embedding IS NOT NULL}, which is not about the index here.</b> On the vector
   *       side that predicate stops NULLs padding a fallback plan's answer. Nothing padding happens
   *       here — {@code text_search} is generated for every chunk and never NULL — and the
   *       predicate is kept for a different reason: {@link #coverage} reports one number for what a
   *       search could reach, and a corpus whose reachable half depended on which words a question
   *       used could not be described by one. So both halves reach exactly the embedded corpus, and
   *       hybrid retrieval changes how the corpus is ranked rather than what it covers. The lexemes
   *       for an unembedded chunk are sitting in the column regardless, so the day that trade is
   *       revisited it is this predicate and the sentences {@code DocumentTools} renders about
   *       coverage, and nothing else.
   *   <li><b>{@code ts_rank_cd(...) > 0} is this side's version of the not-redundant filter.</b>
   *       {@code ts_rank_cd} is a plain function: it answers 0 for a chunk the query did not really
   *       match rather than declining to answer. A leading dash makes {@code -alpha} the tsquery
   *       {@code !'alpha'}, which every chunk that does not say Alpha <em>satisfies</em> and every
   *       one of them scores 0 — so without this predicate a question that named no word to match
   *       comes back holding most of the corpus in scan order, and under fusion those chunks arrive
   *       carrying real-looking ranks. With it, this side returns nothing and the fusion is the
   *       vector half alone.
   *   <li><b>The tie IS broken in SQL, which is the opposite of {@link #SEARCH_SQL} and not an
   *       inconsistency.</b> An HNSW index supplies the ORDER, so asking it for a different one
   *       loses it silently; a GIN index supplies the FILTER and the ranking is a {@code Sort} over
   *       whatever survived, so a second sort key costs nothing — measured, and pinned by {@code
   *       DocumentStoreTest.a_second_sort_key_costs_the_gin_index_nothing}. It is worth doing
   *       rather than merely possible: two chunks matching the same words to the same density score
   *       identically, and a {@code LIMIT} over an unbroken tie takes an arbitrary subset — so what
   *       would vary between two runs of one question is the candidate <em>set</em> handed to the
   *       fusion, not just its order.
   *   <li><b>The rank is not selected for the caller.</b> It is named once and ordered by its
   *       output name, on {@link #SEARCH_SQL}'s precedent, and {@link Hit} has nowhere to put it
   *       because nothing may use it: the fusion consumes this list's rank ORDER and never its
   *       score, which is what makes an uncalibrated, IDF-free number safe to rank by.
   * </ul>
   */
  static final String LEXICAL_SQL =
      "SELECT c.id AS chunk_id, c.text AS chunk_text,"
          + " c.embedding <=> CAST(? AS vector) AS distance,"
          + " ts_rank_cd(c.text_search, q) AS lexical_rank,"
          + " p.id AS paragraph_id, p.text AS paragraph_text,"
          + " p.ordinal AS paragraph_ordinal,"
          + " d.id AS document_id, d.source_name AS source_name, d.title AS title,"
          + " d.summary AS document_summary"
          + " FROM websearch_to_tsquery('"
          + TEXT_SEARCH_CONFIGURATION
          + "', ?) AS q"
          + " CROSS JOIN chunks c"
          + " JOIN paragraphs p ON p.id = c.paragraph_id"
          + " JOIN documents d ON d.id = p.document_id"
          + " WHERE c.embedding IS NOT NULL"
          + " AND c.text_search @@ q"
          + " AND ts_rank_cd(c.text_search, q) > 0"
          + " /*information:and*/ ORDER BY lexical_rank DESC, c.id"
          + " LIMIT ?";

  /**
   * The chunks whose words the question named, best cover first, each carrying the paragraph it
   * came from.
   *
   * <p><b>Package-private for {@link #searchByVector}'s reason, turned around.</b> That method is
   * closed because a vector from the wrong model is a ranking of nothing; this one is closed
   * because a lexical list is only half an answer. Reached on its own it is a search that will find
   * nothing for most well-formed questions and everything for a handful of them, and the one public
   * read stays {@link RetrievalService#search}, which decides what to do with two halves.
   *
   * <p><b>Why a lexical search takes a vector.</b> It does not rank by it and does not filter on
   * it. It is selected so that a hit found on words and a hit found on meaning are the same {@link
   * Hit}, with the same cosine distance beside them — so the fused list has one number in it and no
   * surface has to learn a second kind of hit or render a blank where a similarity goes.
   *
   * <p>No Java re-sort, unlike {@link #searchByVector}: the SQL already returns a total order,
   * because it can.
   *
   * @param question the question in prose, parsed into a tsquery by Postgres. A question that
   *     parses to nothing — only stopwords, or only a negation — matches nothing, which is the
   *     answer and not a failure
   * @param embedding the query vector, from the corpus's own embedding client
   * @param limit how many at most. Non-positive is an empty answer and no query, matching {@link
   *     #searchByVector}
   */
  List<Hit> searchByText(String question, float[] embedding, int limit) {
    Objects.requireNonNull(question, "question");
    Objects.requireNonNull(embedding, "embedding");
    if (limit <= 0) {
      return List.of();
    }
    return List.copyOf(
        readRows(
            "d.id",
            LEXICAL_SQL + " /*information:discover*/",
            HIT,
            toVectorText(embedding),
            question,
            limit));
  }

  /**
   * How much of the corpus a search can reach, and how much of it it cannot.
   *
   * <p>{@code MemoryStore.countUnsearchable} at corpus scale, and it exists for that method's
   * reason turned up a notch: {@link #searchByVector}'s {@code embedding IS NOT NULL} filter is
   * invisible from the outside, so a corpus holding one document that was stored while the
   * embedding endpoint was down answers every question with nothing — with every word of the
   * document's text on disk. An empty answer is a conclusion somebody acts on, so it may only ever
   * mean the corpus was searched and held nothing close.
   *
   * <p><b>Both numbers in one statement</b>, so they describe one instant. Two counts would be two
   * questions with a write between them, and the arithmetic a reader does on them — "so the corpus
   * is this big" — would be over a total that was never true.
   *
   * <p><b>What it costs, said out loud.</b> This is a full pass over {@code chunks}: there is no
   * index on {@code embedding IS NULL} and V18 could not have added a useful one before there was a
   * read to shape it. At the corpus sizes V18 sizes itself against — twenty thousand rows for a
   * hundred documents — that is a few milliseconds and the honesty is worth it. The fix, when it
   * stops being, is a partial index in a later migration, which is additive and needs nothing here
   * to change.
   */
  public Coverage coverage() {
    return readExtract(
        "p.document_id",
        "SELECT count(*) FILTER (WHERE embedding IS NOT NULL AND "
            + compatible("p.document_id", "embed")
            + ") AS searchable,"
            + " count(*) FILTER (WHERE embedding IS NULL OR NOT ("
            + compatible("p.document_id", "embed")
            + ")) AS unsearchable"
            + " FROM chunks c JOIN paragraphs p ON p.id = c.paragraph_id /*information:discover*/ /*information:where*/",
        rs ->
            rs.next()
                ? new Coverage(rs.getInt("searchable"), rs.getInt("unsearchable"))
                : new Coverage(0, 0));
  }

  private static final RowMapper<Hit> HIT =
      (rs, row) ->
          new Hit(
              (UUID) rs.getObject("chunk_id"),
              rs.getString("chunk_text"),
              rs.getDouble("distance"),
              (UUID) rs.getObject("paragraph_id"),
              rs.getString("paragraph_text"),
              rs.getInt("paragraph_ordinal"),
              (UUID) rs.getObject("document_id"),
              rs.getString("source_name"),
              rs.getString("title"),
              rs.getString("document_summary"));

  /**
   * One chunk a search found, and the citation that outlives it.
   *
   * @param chunkId what matched. Deliberately not a citation: a chunk is an artefact of the
   *     chunker, and changing {@code chunk-target-tokens} moves every one of these while the
   *     document has not moved at all
   * @param paragraphId <b>the citation.</b> The surrogate key from V18, which survives a re-ingest
   *     that left this paragraph's text alone and is replaced by a new one when the text changed —
   *     so a citation kept across an edit either means the same words or means nothing, and never
   *     quietly means different words
   * @param paragraphOrdinal where the paragraph sits in the document today. Position and not
   *     identity, per V18: it moves when a paragraph is inserted above it and the id does not
   * @param distance cosine distance, 0 for an identical direction and 2 for an opposite one.
   *     Carried as well as {@link #similarity} because it is what the ordering is actually done on,
   *     and a reader comparing two hits should be able to see the number the database compared
   * @param documentSummary <b>what the document this passage came from argues as a whole, or null
   *     for one nothing has summarised.</b> V22's top level, and it is on the hit for a reason a
   *     join makes cheap and nothing else does: a chunk read out of its document reads as a claim
   *     whether or not the document ends up making it, and {@code document_summariser} is
   *     instructed to record exactly that reversal. Null is ordinary rather than transitional — the
   *     column starts null and {@link #write} nulls it again on every re-ingest — so every reader
   *     has to have an answer for a hit that carries none. <b>Not this server's prose:</b> a model
   *     wrote it while reading the same uploaded text, so it inherits that text's provenance and
   *     {@code DocumentTools} quotes it for the reason it quotes {@link #chunkText}
   */
  public record Hit(
      UUID chunkId,
      String chunkText,
      double distance,
      UUID paragraphId,
      String paragraphText,
      int paragraphOrdinal,
      UUID documentId,
      String sourceName,
      String title,
      String documentSummary) {

    /**
     * Nearness rather than distance: 1 for an identical direction, 0 for an unrelated one.
     *
     * <p>{@code 1 - distance}, which is what Anchor's {@code findChunksForRetrieve} computes in SQL
     * and calls {@code similarity}. Computed here instead so the query names the vector once — see
     * {@link DocumentStore#SEARCH_SQL} — and so the two numbers cannot disagree.
     */
    public double similarity() {
      return 1.0 - distance;
    }
  }

  /**
   * What a search can reach and what it cannot.
   *
   * @param searchable chunks with a vector. The corpus, as far as any question is concerned
   * @param unsearchable chunks whose text is stored and whose vector is not. <b>Zero means an empty
   *     answer is complete</b>; anything else means the corpus holds text no question can reach,
   *     and ingesting those documents again embeds them without deriving anything a second time
   */
  public record Coverage(int searchable, int unsearchable) {}

  // --- the read the ask path makes ----------------------------------------------

  /**
   * The most passages one ask may retrieve, whatever it asks for.
   *
   * <p>Anchor's {@code Math.min(50, limit)}, and this is the half of its clamp that does work. What
   * comes back becomes a prompt: a caller asking for a thousand chunks of one document is asking
   * for the document back, one tier below the summaries the deliberation exists to read instead.
   * Anchor retrieves 15.
   *
   * <p><b>Anchor's other half is not ported.</b> Its {@code Math.max(1, limit)} answers a caller
   * who asked for no chunks with one, which is the store answering a question it was not asked;
   * {@link #searchByVector} and {@link #searchByText} both refuse instead, and one read of three
   * behaving differently would be the surprise rather than the fidelity.
   */
  static final int MOST_PASSAGES = 50;

  /**
   * <b>The ask path's query: one document, pure vector, section attribution attached.</b>
   *
   * <p>Anchor's {@code findSimilarChunksInDocument}. Package-private and read by {@code
   * DocumentStoreTest} for {@link #SEARCH_SQL}'s reason — a test that explained a second copy of
   * this string would go on passing after the real one stopped being index-servable.
   *
   * <ul>
   *   <li><b>No lexical half and no fusion, and that is a fact about Anchor rather than a
   *       simplification.</b> Anchor's server tree contains no {@code tsvector}, {@code
   *       to_tsquery}, {@code ts_rank} or {@code BM25} and no reciprocal-rank fusion at all — its
   *       ask ranks by cosine distance and nothing else. The method therefore takes no question
   *       string: there is nowhere in this signature to put one, which is a stronger statement than
   *       a comment saying the words are not used.
   *   <li><b>The document filter is one hop and not three.</b> Anchor reaches a document from a
   *       chunk only through {@code paragraph -> section -> chapter}, because its paragraphs hang
   *       off sections; V18 froze {@code paragraphs.document_id NOT NULL}, so here the filter is
   *       direct. <b>Porting Anchor's route would change behaviour and not only the plan</b>:
   *       {@code paragraphs.section_id} is nullable, so an inner join through {@code sections}
   *       would make the hierarchy decide <em>membership</em> when it is only wanted for a title,
   *       and a paragraph in no section would vanish from the evidence without a word. That is the
   *       silent loss stage 5 refused one tier up, and it is the whole argument for the {@code LEFT
   *       JOIN} below.
   *   <li><b>{@code LEFT JOIN sections}, and no join to {@code chapters}.</b> The section supplies
   *       the two columns {@link StructuralRef} is made of and nothing else is read from the
   *       hierarchy here; a chapter join would be a third table for a column no caller asked for.
   *       Field 5 of the ask prompt is every chapter of the document, which {@link #hierarchy}
   *       already answers in two statements for the whole document rather than once per hit.
   *   <li><b>The distance is named once and ordered by its output name</b>, for {@link
   *       #SEARCH_SQL}'s reason: naming the vector twice would put the same literal on the wire
   *       twice for one question.
   *   <li><b>{@code ORDER BY} distance and nothing else — and here the index it protects is one the
   *       planner does not take.</b> Measured on this container: with the document filter in place
   *       Postgres reaches the paragraphs by {@code paragraphs_matched_by}, gathers their chunks
   *       and sorts, at less than half the cost of the index plan. <b>That preference is correct
   *       and the port keeps it.</b> The index plan is {@code chunks_by_vector} over the <em>whole
   *       corpus</em> with the document applied afterwards as a join filter, which is ANN
   *       post-filtering: with {@code hnsw.iterative_scan} off — pgvector 0.8's default — such a
   *       scan can return <em>fewer rows than the limit</em>, an under-full evidence set with no
   *       error. An exact sort over one document's chunks has no such failure and is bounded by
   *       what a document holds. The single sort key stays anyway, because it keeps the index
   *       <em>reachable</em> — a corpus of one document is where the planner would want it — and
   *       appending {@code , c.id} closes it off even with sorting disabled. Both halves are pinned
   *       in {@code DocumentStoreTest}.
   *   <li><b>{@code embedding IS NOT NULL}, and on this read it is not the hypothetical guard it is
   *       on the corpus-wide one.</b> There the predicate matters only on a plan that falls back to
   *       a scan; here the sort <em>is</em> the plan, so {@code NULL <=> v} being NULL — and NULL
   *       sorting last rather than nowhere — would pad an under-full answer with chunks nothing
   *       compared every time.
   * </ul>
   */
  static final String SCOPED_SEARCH_SQL =
      "SELECT c.id AS chunk_id, c.text AS chunk_text,"
          + " c.embedding <=> CAST(? AS vector) AS distance,"
          + " p.id AS paragraph_id,"
          + " s.id AS section_id, s.title AS section_title,"
          + " s.is_synthetic AS section_synthetic"
          + " FROM chunks c"
          + " JOIN paragraphs p ON p.id = c.paragraph_id"
          + " LEFT JOIN sections s ON s.id = p.section_id"
          + " WHERE p.document_id = ? AND c.embedding IS NOT NULL"
          + " /*information:and*/ ORDER BY distance"
          + " LIMIT ?";

  /**
   * The chunks of <b>one document</b> nearest this vector, nearest first, each saying where in that
   * document it sits.
   *
   * <p><b>Why this exists beside {@link #searchByVector} rather than inside it.</b> Anchor's ask is
   * per-document by construction — its critic holds a proposed answer against what <em>this
   * document</em> argues as a whole, and "the document as a whole" has no corpus-wide analogue. A
   * {@code document:} filter on the corpus-wide read would look like it answered that and would
   * not, and it would cost something concrete: {@link #coverage} reports one number for what a
   * search can reach, and a corpus whose reachable half depended on the question could not be
   * described by one. So the corpus-wide read, {@link RetrievalService#search} and the {@code
   * coverage} contract are untouched, and this is a second query rather than a parameter on the
   * first.
   *
   * <p><b>The counterpart to {@code coverage} at this scope already exists.</b> {@link
   * #countUnembedded} is the per-document version and it is public, so a caller rendering an empty
   * answer has the same duty here that {@code MemoryStore.countUnsearchable} argues for
   * corpus-wide: an ask over a document stored while the embedding endpoint was down would
   * otherwise find nothing in a document holding every word of its text.
   *
   * <p><b>Package-private, for {@link #searchByVector}'s reason.</b> A vector means something only
   * inside the space the model that produced it defines, and a question embedded by a different
   * model does not fail here — the distances compute, the rows sort, and the answer is a ranking of
   * nothing. There is deliberately no endpoint, no MCP tool and no CLI verb over this: it is the
   * ask path's query and the deliberation is its only caller, so nothing here is model-visible and
   * no capability is added.
   *
   * @param documentId the document to answer from. One that holds nothing — or does not exist — is
   *     an empty answer, which is what an ask over an unembedded document also looks like; {@link
   *     #countUnembedded} is how a caller tells those apart
   * @param embedding the query vector, from the corpus's own embedding client
   * @param limit how many at most, capped at {@link #MOST_PASSAGES}. Non-positive is an empty
   *     answer and no query, matching {@link #searchByVector}
   */
  List<Passage> searchWithinDocument(UUID documentId, float[] embedding, int limit) {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(embedding, "embedding");
    if (limit <= 0) {
      return List.of();
    }
    List<Passage> passages =
        new ArrayList<>(
            readRows(
                "p.document_id",
                SCOPED_SEARCH_SQL,
                PASSAGE,
                toVectorText(embedding),
                documentId,
                Math.min(MOST_PASSAGES, limit)));
    // The total order the SQL deliberately does not ask for — {@link
    // #searchByVector}'s tie-break, over at most fifty rows.
    passages.sort(
        Comparator.comparingDouble(Passage::distance)
            .thenComparing(passage -> passage.chunkId().toString()));
    return List.copyOf(passages);
  }

  private static final RowMapper<Passage> PASSAGE =
      (rs, row) -> {
        UUID sectionId = (UUID) rs.getObject("section_id");
        return new Passage(
            (UUID) rs.getObject("chunk_id"),
            rs.getString("chunk_text"),
            rs.getDouble("distance"),
            (UUID) rs.getObject("paragraph_id"),
            sectionId == null
                ? new Attribution.InNoSection()
                // The persistence mapper again, in the other direction:
                // this is where the two stored columns become the type
                // that cannot be rendered without a policy. Anchor's own
                // chunk block carries `sectionTitle` and
                // `sectionSynthetic` raw and re-derives the rule at the
                // prompt; §3.2's decision is that every render site goes
                // through the gate, so what leaves this method is
                // already gated.
                : new Attribution.InSection(
                    sectionId,
                    StructuralRef.of(
                        rs.getString("section_title"), rs.getBoolean("section_synthetic"))));
      };

  /**
   * One chunk of one document that the ask path retrieved.
   *
   * <p><b>Not a {@link Hit}, and the difference is the scope.</b> A hit is read out of the corpus,
   * so it carries the document it came from and what that document argues; a passage is read out of
   * a document the caller already named, so repeating those on every row would be fifteen copies of
   * one fact the deliberation fetches once. What a passage carries instead is the thing a hit has
   * no use for: where in the document it sits.
   *
   * <p><b>Anchor's {@code ChunkSearchHit} has one field this does not.</b> It selects {@code
   * paragraph_summary} and no prompt reads it — §1 verified that against the source, and Anchor's
   * own SPEC claims otherwise. Selecting a column nothing may use is the habit {@link #LEXICAL_SQL}
   * refuses for the lexical rank, and V26 refuses one table over for {@code documents.authors}. The
   * slice that finds a reader for it adds it.
   *
   * @param chunkId what matched. Deliberately not a citation, for {@link Hit#chunkId()}'s reason: a
   *     chunk is an artefact of the chunker
   * @param chunkText the evidence. <b>Not this server's prose</b> — uploaded text, quoted under the
   *     rules that govern any of it
   * @param paragraphId <b>the citation</b>, and the thing the port grounds an answer in. Anchor's
   *     grounding is arrays of verbatim title strings because Anchor has no stable ids; V18's
   *     surrogate key is why this port does not have to string-match its own evidence back
   * @param distance cosine distance, ordered on. {@link Hit#distance()}'s reasoning, unchanged
   * @param attribution where in the document this passage sits, <b>never null</b>
   */
  public record Passage(
      UUID chunkId, String chunkText, double distance, UUID paragraphId, Attribution attribution) {

    public Passage {
      Objects.requireNonNull(attribution, "attribution");
    }

    /**
     * Nearness rather than distance, {@link Hit#similarity()} exactly — which is the number
     * Anchor's {@code ChunkSearchHit} calls {@code similarity}.
     */
    public double similarity() {
      return 1.0 - distance;
    }
  }

  /**
   * Where in its document a passage sits — <b>including the case where the corpus does not
   * know</b>.
   *
   * <p>Sealed for {@link StructuralRef}'s reason, one level out. That type makes it impossible to
   * render a unit's name without saying how a parser-invented one degrades; this one makes it
   * impossible to render a passage's place without saying what happens when it has none, and a
   * render site that has not decided fails to compile rather than quietly printing nothing.
   *
   * <p><b>The third state is real and it is not "synthetic".</b> {@link StructuralRef.Synthetic}
   * means the parser invented this unit; {@link InNoSection} means there is no unit — V26's
   * nullable {@code section_id}, which V18's extension clause made legal and which means
   * <em>derived before the hierarchy existed</em> or <em>detached mid-re-ingest</em>. Folding one
   * into the other would be convenient and would be a lie, and it would put the state back out of
   * sight, which is the entire failure this shape exists to prevent.
   *
   * <p><b>What it does not do is decide whether such a document may be asked.</b> That question
   * already has a loud answer one tier up — {@link #summarisedParagraphsInNoSection}, and the
   * {@code STUCK} the cascade returns from it, which means a document in this state has no section,
   * chapter or document summary and so cannot fill the ask's prompts at all. A read that refused as
   * well would be the same question asked twice in two currencies, and the one that can answer it
   * completely is not this one.
   */
  public sealed interface Attribution {

    /**
     * The passage is in a section of the document.
     *
     * @param sectionId the section's row. <b>An id and not a title</b>: field 6 of the ask prompt
     *     is the summaries of the sections owning the top chunks, and the alternative is matching
     *     {@link #hierarchy} back by title string — the fragility that makes Anchor's grounding
     *     weaker than this port's
     * @param title gated. {@link StructuralRef.Synthetic} for a section the parser invented, whose
     *     stored title is a sentinel that must never reach a prompt or a reader
     */
    record InSection(UUID sectionId, StructuralRef title) implements Attribution {

      public InSection {
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(title, "title");
      }
    }

    /**
     * The passage's paragraph is in no section. Evidence the document holds, that the hierarchy
     * cannot place.
     */
    record InNoSection() implements Attribution {}
  }

  // --- the ancestor stack, and the three reads over it ---------------------------

  /**
   * The most chunks one retrieve answers with.
   *
   * <p><b>Anchor's hundred, and it is a different bound from {@link #MOST_PASSAGES}.</b> Fifty is
   * what one deliberation is fed and nobody asked for; a hundred is what a caller may ask for on a
   * read it is paying for itself, and Anchor clamps {@code findChunksForRetrieve} to exactly that.
   * The two numbers are two decisions and are deliberately not one constant.
   */
  static final int MOST_RETRIEVED = 100;

  /**
   * Everything above a chunk, in one row.
   *
   * <p>The whole of Anchor's {@code RetrieveSearchRow}, and Anchor's own javadoc says what it is
   * for: <em>"one row per chunk with no follow-up reads"</em>. A tier left out of this list is a
   * caller reading a passage with no idea what the paper around it argues, which is the failure the
   * hierarchy exists to prevent.
   *
   * <p><b>Two joins are LEFT and the reason is V26 rather than taste.</b> {@code
   * paragraphs.section_id} is nullable here, so an inner join to {@code sections} would make the
   * hierarchy decide <em>membership</em> when it is only wanted for a name, and a paragraph in no
   * section would vanish from its own corpus's retrieval with nothing said. Anchor's route is that
   * inner join; it cannot see the state because its paragraphs hang off sections, so porting the
   * SQL faithfully would port a silent loss into a schema that has the state. {@code document_id}
   * is read from the paragraph, one hop, which V18 froze {@code NOT NULL} for.
   */
  private static final String ANCESTOR_COLUMNS =
      " p.id AS paragraph_id, p.ordinal AS paragraph_ordinal,"
          + " p.summary AS paragraph_summary,"
          + " s.id AS section_id, s.title AS section_title,"
          + " s.is_synthetic AS section_synthetic, s.summary AS section_summary,"
          + " h.id AS chapter_id, h.title AS chapter_title,"
          + " h.is_synthetic AS chapter_synthetic, h.summary AS chapter_summary,"
          + " d.id AS document_id, d.source_name AS source_name,"
          + " d.title AS document_title, d.summary AS document_summary";

  /** The four joins {@link #ANCESTOR_COLUMNS} is read through. */
  private static final String ANCESTOR_JOINS =
      " FROM chunks c"
          + " JOIN paragraphs p ON p.id = c.paragraph_id"
          + " JOIN documents d ON d.id = p.document_id"
          + " LEFT JOIN sections s ON s.id = p.section_id"
          + " LEFT JOIN chapters h ON h.id = s.chapter_id";

  /**
   * <b>The corpus-wide retrieve.</b>
   *
   * <p>Package-private and read by {@code CorpusReadsTest} for {@link #SEARCH_SQL}'s reason: a test
   * that explained a second copy of this string would go on passing after the real one stopped
   * being index-servable.
   *
   * <p><b>Here the index plan is the right one, which is the opposite of {@link
   * #SCOPED_SEARCH_SQL}.</b> §2.2a measured the scoped read and found the exact sort cheaper and
   * safer — an ANN scan post-filtered by document can come back short of the limit under pgvector
   * 0.8's defaults. Nothing is post-filtered here: the only predicate is {@code embedding IS NOT
   * NULL}, which every indexed row satisfies, so {@code chunks_by_vector} answers the order
   * directly and the corpus's size stops mattering. That is why this is a second constant rather
   * than the scoped one with its filter dropped — <b>the two reads have different correct
   * plans</b>, and one string built at runtime would hide that behind an {@code if}.
   */
  static final String RETRIEVE_SQL =
      "SELECT c.id AS chunk_id, c.text AS chunk_text,"
          + " c.embedding <=> CAST(? AS vector) AS distance,"
          + ANCESTOR_COLUMNS
          + ANCESTOR_JOINS
          + " WHERE c.embedding IS NOT NULL"
          + " /*information:and*/ ORDER BY distance"
          + " LIMIT ?";

  /**
   * <b>The same retrieve, restricted to one document.</b>
   *
   * <p>{@link #RETRIEVE_SQL} with one predicate added, and the predicate changes the plan rather
   * than only the answer: with a document named the planner reaches the paragraphs by {@code
   * paragraphs_matched_by} and sorts, which §2.2a measured at less than half the index plan's cost
   * and which cannot return an under-full answer. Both plans are pinned in {@code CorpusReadsTest}.
   */
  static final String RETRIEVE_IN_DOCUMENT_SQL =
      "SELECT c.id AS chunk_id, c.text AS chunk_text,"
          + " c.embedding <=> CAST(? AS vector) AS distance,"
          + ANCESTOR_COLUMNS
          + ANCESTOR_JOINS
          + " WHERE c.embedding IS NOT NULL AND p.document_id = ?"
          + " /*information:and*/ ORDER BY distance"
          + " LIMIT ?";

  /** One chunk by id, with the same stack and no vector anywhere in it. */
  static final String CHUNK_SQL =
      "SELECT c.id AS chunk_id, c.text AS chunk_text,"
          + ANCESTOR_COLUMNS
          + ANCESTOR_JOINS
          + " WHERE c.id = ? /*information:and*/";

  /**
   * The chunks nearest this vector, nearest first, each carrying everything above it.
   *
   * <p><b>Package-private, for {@link #searchByVector}'s reason exactly.</b> A vector means
   * something only inside the space the model that produced it defines, and a question embedded by
   * a different model does not fail here — the distances compute, the rows sort, and the answer is
   * a ranking of nothing. {@link RetrievalService#retrieve} is the public door and it is where the
   * width is checked.
   *
   * @param documentId the document to read, or <b>{@code null} for the whole corpus</b>. Anchor's
   *     nullable parameter, ported as its signature says rather than as its own shell uses it —
   *     every bound command there sends an id, so corpus-wide retrieval is a capability Anchor has
   *     and never exercises
   * @param embedding the query vector, from the corpus's own embedding client
   * @param limit how many at most, capped at {@link #MOST_RETRIEVED}. Non-positive is an empty
   *     answer and no query, matching {@link #searchWithinDocument}
   */
  List<Retrieved> retrieve(UUID documentId, float[] embedding, int limit) {
    Objects.requireNonNull(embedding, "embedding");
    if (limit <= 0) {
      return List.of();
    }
    int most = Math.min(MOST_RETRIEVED, limit);
    String vector = toVectorText(embedding);
    List<Retrieved> rows =
        new ArrayList<>(
            documentId == null
                ? readRows(
                    "d.id",
                    RETRIEVE_SQL + " /*information:discover*/ /*information:passage-vector*/",
                    RETRIEVED,
                    vector,
                    most)
                : readRows(
                    "d.id",
                    RETRIEVE_IN_DOCUMENT_SQL + " /*information:passage-vector*/",
                    RETRIEVED,
                    vector,
                    documentId,
                    most));
    // The total order the SQL deliberately does not ask for --
    // {@link #searchByVector}'s tie-break, over at most a hundred rows.
    rows.sort(
        Comparator.comparingDouble(Retrieved::distance)
            .thenComparing(row -> row.chunk().chunkId().toString()));
    return List.copyOf(rows);
  }

  /**
   * One chunk and everything above it, by id.
   *
   * <p><b>Public where {@link #retrieve} is not</b>, and the difference is the vector: this read
   * takes an id the caller already holds, so there is no embedding space to be wrong about.
   *
   * @return empty for a chunk the corpus does not hold, which is also what a chunk whose document
   *     was re-ingested looks like — V18's identity rule preserves a <em>paragraph</em>'s id across
   *     a re-ingest and says in as many words that a chunk is an artefact of the chunker
   */
  public Optional<ChunkWithAncestors> chunk(UUID chunkId) {
    Objects.requireNonNull(chunkId, "chunkId");
    return readRows("d.id", CHUNK_SQL, ANCESTORS, chunkId).stream().findFirst();
  }

  private static final RowMapper<ChunkWithAncestors> ANCESTORS =
      (rs, row) -> {
        UUID sectionId = (UUID) rs.getObject("section_id");
        return new ChunkWithAncestors(
            (UUID) rs.getObject("chunk_id"),
            rs.getString("chunk_text"),
            (UUID) rs.getObject("paragraph_id"),
            rs.getInt("paragraph_ordinal"),
            rs.getString("paragraph_summary"),
            sectionId == null
                ? new Placement.InNoSection()
                // The persistence mapper in the other direction, twice
                // over: this is where four stored columns become two
                // types that cannot be rendered without a policy. Anchor
                // carries all four raw to its controllers and re-derives
                // the rule there with an inline ternary; §3.2's decision
                // is that every render site goes through the gate, so
                // what leaves this method is already gated.
                : new Placement.InSection(
                    sectionId,
                    StructuralRef.of(
                        rs.getString("section_title"), rs.getBoolean("section_synthetic")),
                    rs.getString("section_summary"),
                    (UUID) rs.getObject("chapter_id"),
                    StructuralRef.of(
                        rs.getString("chapter_title"), rs.getBoolean("chapter_synthetic")),
                    rs.getString("chapter_summary")),
            (UUID) rs.getObject("document_id"),
            rs.getString("source_name"),
            rs.getString("document_title"),
            rs.getString("document_summary"));
      };

  private static final RowMapper<Retrieved> RETRIEVED =
      (rs, row) -> new Retrieved(ANCESTORS.mapRow(rs, row), rs.getDouble("distance"));

  // --- the corpus as a list ------------------------------------------------------

  /** The most documents one page answers with. */
  public static final int MOST_LISTED = 200;

  /**
   * A page of the corpus, newest first, each row saying what it holds.
   *
   * <p><b>The counts are subqueries on the page's own rows rather than a second read per
   * document.</b> Anchor calls {@code countsFor} inside the loop that builds its response, which is
   * three statements per document and a hundred and fifty for a page of fifty; these run once each
   * for the rows this page actually returns.
   *
   * <p><b>A fourth count Anchor does not have: paragraphs.</b> Anchor reports chapters, sections
   * and chunks. Here the paragraph is the unit a citation names and the unit the identity rule
   * preserves, so a corpus listing that counted everything except it would omit the one number a
   * reader of {@code GET /v1/documents/citations} can compare against.
   */
  private static final String PAGE_SQL =
      "SELECT d.id, d.source_name, d.title, d.content_hash, d.text_hash, d.byte_size,"
          + " d.ingested_at, d.ingested_by, d.summary, d.top_level_label, d.document_type, d.document_subtype,"
          + " (SELECT count(*) FROM chapters h WHERE h.document_id = d.id)"
          + " AS chapter_count,"
          + " (SELECT count(*) FROM sections s WHERE s.document_id = d.id)"
          + " AS section_count,"
          + " (SELECT count(*) FROM paragraphs p WHERE p.document_id = d.id)"
          + " AS paragraph_count,"
          + " (SELECT count(*) FROM chunks c JOIN paragraphs p"
          + " ON p.id = c.paragraph_id WHERE p.document_id = d.id) AS chunk_count"
          + " FROM documents d";

  /**
   * How a naming narrows a listing.
   *
   * <p><b>Both names, where Anchor matches one.</b> Anchor filters on {@code title} because that is
   * the only name it has. Plowshare has two — {@code source_name} is the identity a re-ingest
   * matches on and is {@code UNIQUE}; {@code title} is what the document calls itself — and a
   * person naming a paper reaches for whichever they last saw. Matching one of them would make half
   * the names a person has been shown fail to resolve, and which half would depend on which screen
   * they read it off.
   *
   * <p>A substring and not a prefix, and folded to lower case on both sides, which is Anchor's rule
   * and the one a person expects of a search box. No index serves it; a corpus of papers is
   * hundreds of rows and a sequential scan over hundreds is not worth an index that a re-ingest has
   * to maintain.
   */
  private static final String NAMED_LIKE =
      " WHERE (LOWER(d.source_name) LIKE ? OR LOWER(d.title) LIKE ?)";

  private static final String NEWEST_FIRST =
      // `id` after the timestamp for the reason every ordered read here
      // has one: two documents ingested in the same millisecond would
      // otherwise page in an order the database is free to change between
      // the two requests that read them, and a caller walking `offset`
      // would see one row twice and another never.
      " ORDER BY d.ingested_at DESC, d.id LIMIT ? OFFSET ?";

  /**
   * One page of the corpus.
   *
   * @param naming a substring of the filed name or the title, or {@code null} for the whole corpus.
   *     Blank is the whole corpus too: a caller that sent an empty search box has narrowed nothing
   * @param limit how many at most, capped at {@link #MOST_LISTED}. Non-positive is an empty page
   *     and no query
   * @param offset how many to skip. Negative is none skipped
   */
  public List<Listed> page(String naming, int limit, int offset) {
    if (limit <= 0) {
      return List.of();
    }
    int most = Math.min(MOST_LISTED, limit);
    int skip = Math.max(0, offset);
    if (blank(naming)) {
      return readRows(
          "d.id",
          PAGE_SQL + " /*information:discover*/ /*information:where*/" + NEWEST_FIRST,
          LISTED,
          most,
          skip);
    }
    String like = like(naming);
    return readRows(
        "d.id",
        PAGE_SQL + NAMED_LIKE + " /*information:discover*/ /*information:and*/" + NEWEST_FIRST,
        LISTED,
        like,
        like,
        most,
        skip);
  }

  /**
   * How many documents a naming reaches.
   *
   * <p>Answered separately rather than as a window function beside the page, because the two
   * questions have different lives: a caller walking pages asks this once and the pages many times,
   * and a {@code count(*) OVER ()} would make every page pay for a total nobody re-read.
   */
  public int count(String naming) {
    Integer total =
        blank(naming)
            ? readScalar(
                "d.id",
                "SELECT count(*) FROM documents d /*information:discover*/ /*information:where*/",
                Integer.class)
            : readScalar(
                "d.id",
                "SELECT count(*) FROM documents d"
                    + NAMED_LIKE
                    + " /*information:discover*/ /*information:and*/",
                Integer.class,
                like(naming),
                like(naming));
    return total == null ? 0 : total;
  }

  private static boolean blank(String naming) {
    return naming == null || naming.isBlank();
  }

  private static String like(String naming) {
    // The caller's own characters, and `%` and `_` among them are theirs
    // too: escaping them would refuse a document actually filed as
    // "100%_coverage.md", and this is a listing filter rather than a
    // security boundary -- the string is bound, never spliced.
    return "%" + naming.strip().toLowerCase(Locale.ROOT) + "%";
  }

  private static final RowMapper<Listed> LISTED =
      (rs, row) ->
          new Listed(
              readDocument(rs, row),
              rs.getInt("chapter_count"),
              rs.getInt("section_count"),
              rs.getInt("paragraph_count"),
              rs.getInt("chunk_count"));

  // --- the document's own summary, as a vector ------------------------------------

  /**
   * The most documents one ranking answers with.
   *
   * <p>Anchor's clamp on the same read. It is generous against a table that holds one row per
   * document rather than one per passage — a corpus of two hundred papers fits in one answer —
   * which is why it is not {@link #MOST_RETRIEVED}: those hundred are a hundred passages out of
   * tens of thousands, and these two hundred may be the whole corpus.
   */
  public static final int MOST_RANKED = 200;

  /**
   * Put a vector on a document summary that had none. {@link #attach}'s shape and its reason: one
   * row at a time, so a backfill that stops keeps what it paid for.
   */
  public void attachSummaryEmbedding(UUID documentId, float[] embedding) {
    Objects.requireNonNull(embedding, "embedding");
    writeUpdate(
        "UPDATE documents SET summary_embedding = CAST(? AS vector) WHERE id = ?",
        toVectorText(embedding),
        documentId);
  }

  /**
   * The document summaries that have no vector yet — <b>the work a backfill has to do</b>.
   *
   * <p>{@link #unembedded} one table up, and the same shape for the same reason: a row committed
   * without the derived thing, and a boundary at which the derived thing can be attached later.
   *
   * <p><b>A document with no summary at all is not here.</b> There is no text for a vector to be
   * of, so counting it as work would say the embedding endpoint failed when what happened is that
   * the cascade never ran.
   */
  public List<UnembeddedSummary> summariesAwaitingAVector() {
    return summariesAwaitingAVector(false);
  }

  public List<UnembeddedSummary> summariesAwaitingAVector(boolean legacyOnly) {
    return readRows(
        "documents.id",
        "SELECT id, summary FROM documents"
            + " WHERE summary IS NOT NULL AND summary_embedding IS NULL"
            + (legacyOnly ? " AND information_namespace='legacy'" : "")
            + " /*information:and*/ ORDER BY ingested_at, id",
        (rs, row) -> new UnembeddedSummary((UUID) rs.getObject("id"), rs.getString("summary")));
  }

  /**
   * How much of the corpus a ranking can reach.
   *
   * <p>{@link #coverage} for {@link #rankBySummary}, and it exists for that method's reason
   * exactly: an empty ranking is a conclusion a caller acts on, so every way of producing one that
   * is not "nothing is close" has to be distinguishable from it. The two states here are
   * <em>summarised and embedded</em> and <em>summarised and not yet embedded</em>; a document
   * nothing has summarised is in neither, because it is not waiting on an embedding, it is waiting
   * on the cascade.
   */
  public Ranking ranking() {
    return readValue(
        "documents.id",
        "SELECT count(*) FILTER (WHERE summary_embedding IS NOT NULL AND "
            + compatible("documents.id", "summary_embed")
            + ") AS rankable,"
            + " count(*) FILTER (WHERE summary IS NOT NULL"
            + " AND (summary_embedding IS NULL OR NOT ("
            + compatible("documents.id", "summary_embed")
            + "))) AS unranked"
            + " FROM documents /*information:discover*/ /*information:where*/",
        (rs, row) -> new Ranking(rs.getInt("rankable"), rs.getInt("unranked")));
  }

  /**
   * <b>Documents ranked by how close their own summary is to this vector.</b>
   *
   * <p>V27's read, and the whole of what that column buys: one comparison per document, where
   * {@link #searchByVector} compares against every chunk in the corpus and then leaves a caller to
   * decide what a single passage says about the paper it came from.
   *
   * <p><b>No index, and the sort is the plan on purpose.</b> V27 declines an HNSW index on this
   * column and gives the argument at length; the short form is that V18's rule is row count rather
   * than "it is a vector column", and this table holds one row per document. The {@code IS NOT
   * NULL} guard is therefore load-bearing rather than hypothetical: the sort <em>is</em> the plan,
   * so {@code NULL <=> v} being NULL — and NULL sorting last rather than nowhere — would pad every
   * under-full ranking with documents nothing compared.
   *
   * <p><b>Package-private, for {@link #searchByVector}'s reason.</b> A vector means something only
   * inside the space the model that produced it defines. {@link RetrievalService#rank} is the
   * public door and is where the width is checked.
   *
   * @param limit how many at most, capped at {@link #MOST_RANKED}. Non-positive is an empty answer
   *     and no query
   */
  List<Ranked> rankBySummary(float[] embedding, int limit) {
    Objects.requireNonNull(embedding, "embedding");
    if (limit <= 0) {
      return List.of();
    }
    return readRows(
        "documents.id",
        "SELECT "
            + DOCUMENT_COLUMNS
            + ", summary_embedding <=> CAST(? AS vector) AS distance"
            + " FROM documents WHERE summary_embedding IS NOT NULL"
            // `id` after the distance for `NEWEST_FIRST`'s reason:
            // two documents at the same distance would otherwise
            // page in an order the database is free to change.
            + " /*information:discover*/ /*information:summary-vector*/ /*information:and*/ ORDER BY distance, id"
            + " LIMIT ?",
        (rs, row) -> new Ranked(readDocument(rs, row), rs.getDouble("distance")),
        toVectorText(embedding),
        Math.min(MOST_RANKED, limit));
  }

  /**
   * <b>One document scored against a question and against its negation.</b>
   *
   * <p>Anchor's {@code /validate/quick}, whose stance score is the difference between the two.
   * <b>The subtraction is {@link Stance}'s and the two numbers are both returned</b>, because
   * Anchor's own response returns both and is right to: a stance near zero means "argues both ways"
   * if the topical relevance is high and "is not about this at all" if it is not, and the
   * difference alone cannot tell those apart.
   *
   * <p><b>One statement where Anchor sends two.</b> Anchor calls {@code documentSummaryCosine}
   * twice, which is two round trips and two chances for the row to change between them. Both
   * cosines are of the same stored vector and the question is one question.
   *
   * @return empty for a document the corpus does not hold <b>and</b> for one whose summary has no
   *     vector. Deliberately not a zero score: zero is a real reading — it is what a document
   *     unrelated to both the question and its negation returns — so answering an unembedded
   *     document with one would be indistinguishable from a genuine result
   */
  Optional<Stance> stanceOf(UUID documentId, float[] query, float[] negated) {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(negated, "negated");
    return readRows(
            "documents.id",
            "SELECT 1 - (summary_embedding <=> CAST(? AS vector)) AS topical,"
                + " 1 - (summary_embedding <=> CAST(? AS vector)) AS negated"
                + " FROM documents"
                + " WHERE id = ? AND summary_embedding IS NOT NULL /*information:and*/",
            (rs, row) -> new Stance(rs.getDouble("topical"), rs.getDouble("negated")),
            toVectorText(query),
            toVectorText(negated),
            documentId)
        .stream()
        .findFirst();
  }

  /**
   * A document summary waiting for a vector: its id, and the text a backfill has to embed.
   *
   * <p>{@link UnembeddedChunk}'s shape, deliberately, and the same relationship one table up. The
   * difference is the unit: a chunk's vector is of a passage somebody wrote, and this one is of a
   * sentence a model wrote <em>about</em> what somebody wrote — which is why the two are found by
   * two queries and filled by two passes rather than by one that pretends they are the same work.
   */
  public record UnembeddedSummary(UUID documentId, String summary) {}

  /**
   * How much of the corpus a ranking can reach.
   *
   * <p>{@link Coverage} for documents rather than chunks, and a separate record because the two
   * count different things and a caller holding one must not be able to read it as the other.
   * Coverage is passages a question can reach; this is <b>papers</b>, and a corpus can be fully
   * searchable at passage level while nothing in it is rankable at all — the summary embeddings are
   * written by a different pass from the chunk ones.
   *
   * @param rankable documents whose summary has a vector
   * @param unranked documents that have a summary and no vector for it. <b>Not documents with no
   *     summary</b>: those are waiting on the cascade rather than on an embedding, and folding them
   *     in here would report a model that was never called as an endpoint that failed
   */
  public record Ranking(int rankable, int unranked) {}

  /**
   * One document a question reached, and how near its summary was.
   *
   * @param document the row itself, whole, rather than a second spelling of six of its fields —
   *     {@link Listed}'s reasoning
   * @param distance cosine distance, ordered on. {@link Hit#distance()}'s reasoning, unchanged
   */
  public record Ranked(StoredDocument document, double distance) {

    public Ranked {
      Objects.requireNonNull(document, "document");
    }

    /**
     * Nearness rather than distance, and the number Anchor's {@code DocumentSearchHit} calls {@code
     * score}.
     */
    public double similarity() {
      return 1.0 - distance;
    }
  }

  /**
   * <b>What one document's summary says about a question and about its negation.</b> Anchor's
   * {@code /validate/quick}, whose whole output is these two numbers and their difference.
   *
   * <p><b>Both are returned and the difference is derived, because the difference alone is not
   * readable.</b> A score near zero means "this paper argues both ways" when {@link #topical} is
   * high and "this paper is not about this at all" when it is not, and those are opposite
   * conclusions. Anchor's response carries both for the same reason.
   *
   * <p><b>This is a heuristic and the type does not pretend otherwise.</b> Anchor's own javadoc
   * says so twice — "vector-only stance approximation", "NOT a substitute for full /validate" — and
   * the mechanism is worth stating plainly: it embeds {@code "not " + query} and hopes the
   * embedding model puts a negation somewhere useful in cosine space. Nothing here checks that it
   * does. What the number is good for is Anchor's stated purpose, a cheap pre-filter over a corpus
   * too large to deliberate on; what it is not good for is telling a reader what a paper concludes.
   *
   * @param topical cosine of the question against the summary, in {@code [-1, 1]}. High means the
   *     paper is <em>about</em> the question, whatever it says about it
   * @param negated the same cosine for {@code "not " + question}
   */
  public record Stance(double topical, double negated) {

    /**
     * Positive leans toward the question's claim, negative against it, near zero is mixed or
     * off-topic — which is what {@link #topical} beside it is for.
     */
    public double score() {
      return topical - negated;
    }
  }

  /** Put a vector on a chunk that had none. */
  public void attach(UUID chunkId, float[] embedding) {
    writeUpdate(
        "UPDATE chunks SET embedding = CAST(? AS vector) WHERE id = ?",
        toVectorText(embedding),
        chunkId);
  }

  /**
   * pgvector's text form, {@code [0.1,0.2,…]}.
   *
   * <p>Spelled out again rather than shared with {@code MemoryStore}'s private copy of the same six
   * lines. Extracting it would mean editing a shipped class for no behaviour change, and the format
   * is a wire contract with rows already on disk rather than a helper — one that a test can compare
   * against a literal it wrote by hand, which is the property {@code MemoryStore} names as the
   * reason it is not taken from {@code PGvector} either.
   */
  private static String toVectorText(float[] embedding) {
    StringBuilder text = new StringBuilder(embedding.length * 8 + 2).append('[');
    for (int i = 0; i < embedding.length; i++) {
      if (i > 0) {
        text.append(',');
      }
      text.append(embedding[i]);
    }
    return text.append(']').toString();
  }

  private static final RowMapper<StoredDocument> DOCUMENT = DocumentStore::readDocument;

  private static StoredDocument readDocument(ResultSet rs, int row) throws SQLException {
    return new StoredDocument(
        (UUID) rs.getObject("id"),
        rs.getString("source_name"),
        rs.getString("title"),
        rs.getString("content_hash"),
        rs.getString("text_hash"),
        rs.getLong("byte_size"),
        rs.getObject("ingested_at", OffsetDateTime.class).toInstant(),
        rs.getString("ingested_by"),
        rs.getString("summary"),
        // The second persistence mapper in this class, and the same
        // shape as the first: two stored columns become a type nothing
        // downstream can misread. V26's CHECK is written over the enum's
        // own names, so an unreadable value here is a row that got past
        // the constraint and is worth the exception rather than a quiet
        // fallback to SECTION -- which is exactly what Anchor does, and
        // what would make a paper's prompts say "section" for ever if
        // somebody spelled the label wrong once.
        rs.getString("top_level_label") == null
            ? null
            : Vocabulary.valueOf(rs.getString("top_level_label")),
        rs.getString("document_type"),
        rs.getString("document_subtype"));
  }

  /**
   * A paragraph and the id it is going to be written under, and whether the corpus has seen it
   * before.
   */
  private record Matched(UUID id, DerivedParagraph paragraph, boolean isNew) {}

  /**
   * One document as the corpus holds it.
   *
   * @param summary what the document claims, or {@code null} for one nothing has summarised yet.
   *     Null is an ordinary state and not a gap: the text is committed before any model is called,
   *     and V22 drops this column whenever a re-ingest changes the derivation it was written from
   * @param vocabulary what this document calls its own top-level parts, or {@code null} for a row
   *     written before V26 — which is a document nothing has re-ingested since the detectors
   *     landed, and not a detection that came back empty. The per-document ask is the reader this
   *     column was added for, and it substitutes the enum's spellings into all three of its prompts
   *     so that a paper saying "Section" throughout is never told it has chapters
   */
  public record StoredDocument(
      UUID id,
      String sourceName,
      String title,
      String contentHash,
      String textHash,
      long byteSize,
      Instant ingestedAt,
      String ingestedBy,
      String summary,
      Vocabulary vocabulary,
      String documentType,
      String documentSubtype) {
    public StoredDocument(
        UUID id,
        String sourceName,
        String title,
        String contentHash,
        String textHash,
        long byteSize,
        Instant ingestedAt,
        String ingestedBy,
        String summary,
        Vocabulary vocabulary) {
      this(
          id,
          sourceName,
          title,
          contentHash,
          textHash,
          byteSize,
          ingestedAt,
          ingestedBy,
          summary,
          vocabulary,
          "document",
          "text");
    }
  }

  /**
   * What a write came to.
   *
   * @param kept paragraphs that matched an existing row and held their id
   * @param added paragraphs the corpus had not seen in this document
   * @param removed rows that no paragraph matched, deleted with their chunks
   * @param chunks how many chunks were written, which is the number of embedding calls this ingest
   *     still owes — a re-ingest of an unchanged document owes none
   * @param chapters how many chapters the hierarchy came to. At least one for any document that
   *     derived a paragraph, because a document that declares no structure gets the synthetic
   *     chapter rather than none
   * @param sections how many sections, across every chapter. Rewritten whole by every ingest,
   *     unlike the paragraphs above
   */
  public record Written(
      UUID documentId, int kept, int added, int removed, int chunks, int chapters, int sections) {}

  /** A chunk waiting for a vector. */
  public record UnembeddedChunk(UUID id, String text) {}

  /**
   * A paragraph waiting for a summary: its id, and the raw text that is the one thing the cascade's
   * bottom level reads.
   *
   * <p><b>{@link UnembeddedChunk}'s shape, deliberately.</b> The two are the same relationship — a
   * row committed without the derived thing, and a boundary at which the derived thing can be
   * attached later — so they read the same way and a reader who has understood one has understood
   * the other. The difference is the unit: a vector belongs to a chunk and a summary belongs to a
   * paragraph, because a chunk is an artefact of the chunker and a claim is not.
   */
  public record UnsummarisedParagraph(UUID id, int ordinal, String text) {}

  /**
   * One stored chapter, its sections, and what each of them already claims.
   *
   * <p><b>{@link DerivedDocument.Chapter} read back rather than written out</b>, and it is a
   * different record on purpose: a derived chapter is paragraphs of text that have no ids yet, and
   * a stored one is ids and summaries with no text at all. Two records that differ in every field
   * are two records.
   *
   * @param title the unit as {@link StructuralRef} — the document's own words, or the parser's
   *     invention with the sentinel already discarded
   * @param summary what the tier above reads, or null for a chapter the cascade has not reached
   */
  public record StoredChapter(
      UUID id, StructuralRef title, String summary, List<StoredSection> sections) {

    public StoredChapter {
      sections = List.copyOf(sections);
    }
  }

  /** One stored section: its id, its title, and what it claims so far. */
  public record StoredSection(UUID id, StructuralRef title, String summary) {}

  /**
   * One chunk with every tier above it, which is what Anchor's {@code /retrieve} and {@code
   * /chunks/&#123;id&#125;} both answer with.
   *
   * <p><b>Not a {@link Hit} and not a {@link Passage}, and the three are three scopes rather than
   * three moods.</b> A hit is read out of the corpus and carries the paragraph to cite; a passage
   * is read out of a document the caller already named and carries only where in it the passage
   * sits, so the deliberation fetches the hierarchy once instead of on every row. This carries
   * <em>everything</em>, because its caller named nothing and holds nothing — that is the whole of
   * Anchor's <em>"no follow-up reads"</em>, and paying for it on the ask path would be fifteen
   * copies of one fact.
   *
   * @param chunkText the evidence. <b>Not this server's prose</b> — uploaded text, quoted under the
   *     rules that govern any of it
   * @param paragraphId <b>the citation</b>, and the id that survives a re-ingest where {@link
   *     #chunkId} does not
   * @param paragraphOrdinal where in the document the paragraph sits, which is the number every
   *     other document surface here prints beside a citation
   * @param placement where in the document this chunk sits, <b>never null</b>, and including the
   *     case where the corpus does not know
   * @param sourceName what the corpus filed the document under. Anchor's row carries only the
   *     title; here the filed name is the identity and is what a person names a document by
   * @param documentSummary what the document argues, or null for one nothing has summarised yet
   */
  public record ChunkWithAncestors(
      UUID chunkId,
      String chunkText,
      UUID paragraphId,
      int paragraphOrdinal,
      String paragraphSummary,
      Placement placement,
      UUID documentId,
      String sourceName,
      String documentTitle,
      String documentSummary) {

    public ChunkWithAncestors {
      Objects.requireNonNull(placement, "placement");
    }
  }

  /** A {@link ChunkWithAncestors} a question reached, and how near it was. */
  public record Retrieved(ChunkWithAncestors chunk, double distance) {

    public Retrieved {
      Objects.requireNonNull(chunk, "chunk");
    }

    /**
     * Nearness rather than distance, {@link Hit#similarity()} exactly — which is the number
     * Anchor's {@code RetrieveHit} calls {@code score}.
     */
    public double similarity() {
      return 1.0 - distance;
    }
  }

  /**
   * Where in its document a chunk sits, with the summaries of both tiers — <b>including the case
   * where the corpus does not know</b>.
   *
   * <p>{@link Attribution} widened, and a second type rather than more fields on that one. They
   * answer different questions: {@code Attribution} says where a passage sits for a prompt that
   * already holds the whole hierarchy, so a section id and a name is all of it; this says what the
   * ancestor stack <em>is</em> for a caller holding nothing. Putting these five extra fields on
   * {@code Attribution} would load the ask path's hot row with columns the deliberation never
   * reads, and dropping them from this one would put back the follow-up read the whole shape exists
   * to remove.
   *
   * <p>Sealed for {@link StructuralRef}'s reason, one level out, and {@link InNoSection} is
   * deliberately not folded into {@link StructuralRef.Synthetic}: synthetic means the parser
   * invented the unit, in-no-section means there is no unit, and folding them would put the state
   * back out of sight.
   */
  public sealed interface Placement {

    /**
     * The chunk's paragraph is in a section, and a section is always in a chapter — V26's {@code
     * sections.chapter_id NOT NULL}, which is why there is no third variant for a section with no
     * chapter.
     *
     * @param sectionTitle gated. {@link StructuralRef.Synthetic} for a section the parser invented,
     *     whose stored title is a sentinel that must never reach a prompt, an API or a reader
     * @param chapterTitle gated, the same way and for the same reason
     */
    record InSection(
        UUID sectionId,
        StructuralRef sectionTitle,
        String sectionSummary,
        UUID chapterId,
        StructuralRef chapterTitle,
        String chapterSummary)
        implements Placement {

      public InSection {
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(sectionTitle, "sectionTitle");
        Objects.requireNonNull(chapterId, "chapterId");
        Objects.requireNonNull(chapterTitle, "chapterTitle");
      }
    }

    /**
     * The chunk's paragraph is in no section. Evidence the document holds, that the hierarchy
     * cannot place.
     */
    record InNoSection() implements Placement {}
  }

  /**
   * One document in a listing, and how much of it there is.
   *
   * @param document the row itself, whole, rather than a second spelling of six of its fields
   * @param paragraphs the count Anchor's listing does not carry. The unit a citation names, so it
   *     is the one number here a reader can hold against {@code GET /v1/documents/citations}
   */
  public record Listed(
      StoredDocument document, int chapters, int sections, int paragraphs, int chunks) {

    public Listed {
      Objects.requireNonNull(document, "document");
    }
  }
}
