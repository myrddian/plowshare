package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Invalidation;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryState;
import io.aeyer.plowshare.protocol.Provenance;
import io.aeyer.plowshare.server.embedding.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the archive: one row per memory, in every state.
 *
 * <p>Ported from Excalibur's {@code archive/store.py}, which keeps memories as Markdown files in
 * two directories — {@code memories/} for what the archive stands behind and {@code retired/} for
 * what it does not. There, <b>which directory a record was in was its state</b>, and a search
 * simply could not see the other directory: there was no rule left for a model to skip. That
 * mechanism is gone here, but the property it bought is not optional, so it is re-expressed as a
 * filter on {@code state} that every read on the search path carries. The failure it prevents was
 * measured on 2026-08-18: asked a question whose only match was an invalidated memory, the
 * librarian returned that known-false memory 5 runs out of 5.
 *
 * <p>Three read shapes, and the difference between them is the whole design:
 *
 * <ul>
 *   <li>{@link #load(String)} resolves <em>any</em> state by id. A tombstone stays readable — that
 *       is what stops an agent relearning a mistake.
 *   <li>{@link #loadAll(Home)} is the search path: {@code active} and {@code cold}, never a
 *       tombstone.
 *   <li>{@link #index(Home)} is {@code active} alone. {@code cold} is still true; it has only
 *       dropped out of the index for disuse.
 * </ul>
 *
 * <p>Plain {@link JdbcTemplate} and hand-written SQL, following Anchor's {@code
 * DocumentRepositoryImpl}. Nothing here deletes, in any state, and there is deliberately no method
 * that could.
 */
@Repository
public class MemoryStore {
  private DualEmbeddings dualEmbeddings;

  public void useDualEmbeddings(DualEmbeddings embeddings) {
    dualEmbeddings = java.util.Objects.requireNonNull(embeddings);
  }

  private boolean informationProtected;

  public void protectInformation() {
    informationProtected = true;
  }

  private String safe(boolean aliased) {
    return informationProtected
        ? "information_memory_safe(" + (aliased ? "m.id" : "id") + ") AND "
        : "";
  }

  /*
   * The state literals below are MemoryState.wireName() values. They are
   * spelled out in the SQL rather than built from the enum because they are
   * already a contract with rows on disk — wireName() exists precisely so a
   * Java rename cannot change them — and dynamic SQL assembled from an enum
   * would be a second, less obvious place the same four words are written.
   * The four-state coverage in MemoryStoreTest is what pins them together.
   */

  /**
   * The search path: what the archive stands behind. {@code cold} belongs here — it is unused, not
   * untrue, and filing it with the tombstones would make true knowledge permanently unreachable.
   */
  private static final String LIVE_STATES = "('active', 'cold')";

  /**
   * The tombstones. Nothing searches this; a caller asks for it on purpose, which is the only way a
   * tombstone should ever be found.
   */
  private static final String RETIRED_STATES = "('superseded', 'invalidated')";

  /*
   * `project_id IS NOT DISTINCT FROM ?` and not `project_id = ?`, because the
   * global tier is a NULL project and `NULL = NULL` is NULL, not true — a
   * plain equality would silently return zero global rows forever. The CAST is
   * not decoration: with a bare `?` the driver sends an untyped NULL and
   * Postgres refuses the statement with "could not determine data type of
   * parameter".
   *
   * The parameter is a project id since V14 and not a name, and it is never
   * built here: ProjectIds.toRead is the one place a Home becomes one, because
   * the third case -- a project nothing has written to yet -- has to become a
   * value that matches no row rather than the NULL that means global.
   *
   * Unqualified despite the join below, which is deliberate rather than
   * careless: `projects` has no column of this name, so there is nothing for it
   * to be ambiguous with, and the two columns that DO collide -- `id` and
   * `name` -- are qualified everywhere they appear.
   */
  private static final String HOME_MATCHES = "project_id IS NOT DISTINCT FROM CAST(? AS BIGINT)";

  /*
   * The join is what keeps a Home a name. A row holds an id; a Memory holds
   * the project's name, because that is what the protocol, the API and every
   * agent-facing tool speak. Resolving it in the query rather than in the
   * mapper is what lets `load(id)` -- which is told no home at all -- still
   * answer with one.
   *
   * LEFT, and the tier system depends on it: a global memory has a NULL
   * project_id, an inner join would drop every one of them, and the failure
   * would read as an archive that had simply forgotten its global tier.
   */
  private static final String FROM_MEMORIES =
      " FROM memories m LEFT JOIN projects p ON p.id = m.project_id";

  private static final String COLUMNS =
      """
            m.id, p.name AS project, m.summary, m.scope, m.body, m.state, m.pinned,
            m.uses, m.last_used, m.formed_at, m.formed_by, m.formed_where,
            m.supersedes, m.superseded_by, m.invalidated_at, m.invalidated_by,
            m.invalidated_why
            """;

  /*
   * `embedding` appears in neither list, and that is the point. It is not a
   * field of Memory, so naming it here would write NULL over it on every
   * save — and Archive.read counts a use and saves the record straight back,
   * so the archive would lose the embedding of every memory anyone actually
   * recalled and get less searchable the more it was used. Task 8 writes that
   * column through its own statement.
   */
  private static final String UPSERT =
      """
            INSERT INTO memories (
                id, project_id, summary, scope, body, state, pinned, uses, last_used,
                formed_at, formed_by, formed_where, supersedes, superseded_by,
                invalidated_at, invalidated_by, invalidated_why)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                project_id      = EXCLUDED.project_id,
                summary         = EXCLUDED.summary,
                scope           = EXCLUDED.scope,
                body            = EXCLUDED.body,
                state           = EXCLUDED.state,
                pinned          = EXCLUDED.pinned,
                uses            = EXCLUDED.uses,
                last_used       = EXCLUDED.last_used,
                formed_at       = EXCLUDED.formed_at,
                formed_by       = EXCLUDED.formed_by,
                formed_where    = EXCLUDED.formed_where,
                supersedes      = EXCLUDED.supersedes,
                superseded_by   = EXCLUDED.superseded_by,
                invalidated_at  = EXCLUDED.invalidated_at,
                invalidated_by  = EXCLUDED.invalidated_by,
                invalidated_why = EXCLUDED.invalidated_why
            """;

  private final JdbcTemplate jdbc;

  public MemoryStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Writes the record, whatever state it is in.
   *
   * <p>An upsert, because every lifecycle operation is "write this record back in a new state" and
   * because the id is the primary key: a memory retired and re-saved is one row that changed, never
   * a second row. In Excalibur the equivalent was a file move, and its whole test suite around
   * crashes exists to prove a record could never end up in both directories or in neither. Here
   * that is a single statement Postgres either commits or does not.
   *
   * <p><b>Two statements since V14, and the first is not this row.</b> {@code project_id} is a
   * foreign key now, so a memory formed in a project nothing has written to before has nowhere to
   * point until that project is registered. {@link ProjectIds#toWrite} does it, on this thread and
   * so in this caller's transaction: a save that rolls back takes the registration with it. Nothing
   * about it is visible from outside — a memory is still formed in a project no operator has
   * defined, exactly as V3 promised — and the sentence above about a single statement is now about
   * the upsert alone.
   */
  public void save(Memory memory) {
    Invalidation invalidation = memory.invalidation();
    ArchiveUnavailableException.translating(
        "save a memory",
        () ->
            jdbc.update(
                UPSERT,
                memory.id(),
                // Inside the supplier, because Java evaluates an argument where
                // it is written: a database that is down is reported as "save a
                // memory" and not as the registration step underneath it.
                ProjectIds.toWrite(jdbc, memory.home()),
                memory.summary(),
                memory.scope(),
                memory.body(),
                memory.state().wireName(),
                memory.pinned(),
                memory.uses(),
                utc(memory.lastUsed()),
                utc(memory.formed().at()),
                memory.formed().by(),
                memory.formed().where(),
                memory.supersedes(),
                memory.supersededBy(),
                invalidation == null ? null : utc(invalidation.at()),
                invalidation == null ? null : invalidation.by(),
                invalidation == null ? null : invalidation.reason()));
  }

  /**
   * The record with this id, in whatever state it is in — tombstones included.
   *
   * <p>{@link Optional} rather than Excalibur's {@code ArchiveError}: a caller asking for an id
   * that was never written is asking a question, not making a mistake, and the layer above has to
   * branch on the answer either way. An exception would make the ordinary "is this here?" path cost
   * a stack trace.
   */
  public Optional<Memory> load(String id) {
    List<Memory> found =
        ArchiveUnavailableException.translating(
            "read a memory by id",
            () ->
                jdbc.query(
                    "SELECT " + COLUMNS + FROM_MEMORIES + " WHERE " + safe(true) + "m.id = ?",
                    ROW_MAPPER,
                    id));
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  /**
   * Every record in one tier that the archive stands behind, sorted by id.
   *
   * <p>Live states only, which is what makes the search path blind to a tombstone without the
   * caller having to remember a rule. One tier at a time and never merged: project shadows global,
   * but shadowing is a rule the {@code Archive} applies over two reads, and a store that quietly
   * unioned the tiers would make it untestable.
   */
  public List<Memory> loadAll(Home home) {
    return ArchiveUnavailableException.translating(
        "list the live memories in a tier",
        () ->
            jdbc.query(
                "SELECT "
                    + COLUMNS
                    + FROM_MEMORIES
                    + " WHERE "
                    + safe(true)
                    + HOME_MATCHES
                    + " AND state IN "
                    + LIVE_STATES
                    + " ORDER BY m.id",
                ROW_MAPPER,
                ProjectIds.toRead(jdbc, home)));
  }

  /**
   * Write one memory's embedding, touching no other column.
   *
   * <p>Its own statement, and this is the reason {@code embedding} is missing from {@link #save}'s
   * two column lists. A {@code Memory} has no embedding component, so an upsert that named the
   * column would write NULL over it every time — and {@code Archive.read} counts a use and saves
   * the record straight back, so the archive would lose the vector of every memory anyone actually
   * recalled and get <em>less</em> searchable the more it was used. {@code
   * MemoryStoreTest.saving_a_memory_again_does_not_erase_its_embedding} pins that.
   *
   * <p>An UPDATE and not an upsert: the row is always written first, by {@link #save}. Embedding a
   * memory that does not exist would be a caller with the order wrong, and inserting a half-row
   * here to accommodate it would produce a record with no summary, no body and no provenance.
   *
   * <p>Bound as pgvector's text form with an explicit cast, following Anchor's {@code
   * DocumentRepositoryImpl}: the driver has no mapping for {@code vector}, and a {@code float[]}
   * parameter arrives as a Postgres array, which {@code vector} will not take.
   */
  public void saveEmbedding(String id, float[] embedding) {
    ArchiveUnavailableException.translating(
        "save a memory's embedding",
        () ->
            jdbc.update(
                "UPDATE memories SET embedding = CAST(? AS vector) WHERE id = ?",
                toVectorText(embedding),
                id));
  }

  /**
   * The nearest live memories in one tier to a query vector.
   *
   * <p>This is recall. In Excalibur the equivalent is an agent with a turn budget calling {@code
   * grep} until it decides it is done; here it is one statement, and the three filters on it are
   * the archive's rules rather than instructions a model may or may not follow:
   *
   * <ul>
   *   <li><b>Live states only.</b> {@code superseded} and {@code invalidated} records never enter
   *       the search path, which is what the {@code memories/} versus {@code retired/} split
   *       bought. Measured 2026-08-18: asked a question whose only match was an invalidated memory,
   *       the librarian returned that known-false memory 5 runs out of 5.
   *   <li><b>{@code cold} is included</b>, exactly as it is in {@link #loadAll}. A cold memory is
   *       unused, not untrue, and it has left the index alone — in Excalibur it stays in {@code
   *       memories/} precisely so that grep can still reach it. Filtering to {@code active} here
   *       would make demotion indistinguishable from deletion for every caller that only ever
   *       searches, and would take back the promise that makes an aggressive index threshold safe:
   *       that a wrong eviction costs a slower recall and never a lost memory.
   *   <li><b>An embedding is required.</b> A NULL embedding is a memory written while the endpoint
   *       was down. It is still {@code active} and still readable by id; it is simply not reachable
   *       by <em>vector</em> search, having no vector to compare. Without this clause it would
   *       still come back: {@code ORDER BY} sorts NULLs last but does not drop them, so any query
   *       whose limit exceeded the number of embedded rows would hand back arbitrary unembedded
   *       memories as matches.
   * </ul>
   *
   * <p>One tier at a time, never a union. Project shadows global, and that is a rule {@link
   * Archive} applies across two calls; a store that quietly merged the tiers would make it
   * untestable.
   *
   * @param embedding the query vector
   * @param home the tier to search
   * @param limit how many to return at most
   */
  public List<Memory> searchByVector(float[] embedding, Home home, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    return ArchiveUnavailableException.translating(
        "search a tier by vector",
        () ->
            jdbc.query(
                "SELECT "
                    + COLUMNS
                    + FROM_MEMORIES
                    + " WHERE "
                    + safe(true)
                    + HOME_MATCHES
                    + " AND state IN "
                    + LIVE_STATES
                    + " AND embedding IS NOT NULL"
                    // `<=>` is cosine distance: 0 identical, 2 opposite,
                    // so ascending is nearest-first. Id breaks the tie,
                    // because two equidistant rows returned in physical
                    // order would make every ordering assertion depend
                    // on the plan.
                    + " ORDER BY embedding <=> CAST(? AS vector), m.id"
                    + " LIMIT ?",
                ROW_MAPPER,
                ProjectIds.toRead(jdbc, home),
                toVectorText(embedding),
                limit));
  }

  /** Same tier/state/information fences as legacy recall, with explicit slot identity. */
  public List<Memory> searchByVector(EmbeddingQuery query, Home home, int limit) {
    if (limit <= 0) return List.of();
    var plan = new EmbeddingIndexPlan(query.profile());
    String sql =
        "SELECT "
            + COLUMNS
            + ","
            + plan.denseDistance("m." + plan.column())
            + " AS distance"
            + FROM_MEMORIES
            + " WHERE "
            + safe(true)
            + HOME_MATCHES
            + " AND state IN "
            + LIVE_STATES
            + " AND "
            + plan.present("m");
    List<Object> args = new java.util.ArrayList<>();
    args.add(query.vectorText());
    args.add(ProjectIds.toRead(jdbc, home));
    if (plan.exact()) {
      sql += " ORDER BY distance,m.id LIMIT ?";
      args.add(Math.min(2000, limit));
    } else {
      sql =
          "SELECT candidates.* FROM ("
              + sql
              + " ORDER BY "
              + plan.candidateDistance("m." + plan.column())
              + " LIMIT ?) candidates ORDER BY distance,id LIMIT ?";
      args.add(query.vectorText());
      args.add(plan.candidates(Math.min(2000, limit)));
      args.add(Math.min(2000, limit));
    }
    String ranked = sql;
    return ArchiveUnavailableException.translating(
        "search a tier in an embedding space",
        () -> jdbc.query(ranked, ROW_MAPPER, args.toArray()));
  }

  public int countUnsearchable(EmbeddingProfile profile, Home home) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM memories m WHERE "
                + safe(true)
                + HOME_MATCHES
                + " AND state IN "
                + LIVE_STATES
                + " AND NOT ("
                + new EmbeddingIndexPlan(profile).present("m")
                + ")",
            Integer.class,
            ProjectIds.toRead(jdbc, home));
    return count == null ? 0 : count;
  }

  private String searchable(boolean aliased) {
    String prefix = aliased ? "m." : "memories.";
    return dualEmbeddings == null
        ? prefix + "embedding IS NOT NULL"
        : "EXISTS(SELECT 1 FROM embedding_slots selected WHERE selected.slot='prose' AND selected.active_space_id="
            + prefix
            + "prose_space_id AND "
            + prefix
            + "prose_embedding IS NOT NULL AND "
            + prefix
            + "prose_source_revision="
            + prefix
            + "embedding_source_revision)";
  }

  /**
   * pgvector's text form, {@code [0.1,0.2,…]}.
   *
   * <p>Built here rather than taken from {@code PGvector}, so the write and the query encode a
   * vector the one way and a test can compare against a literal it wrote by hand.
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

  /**
   * The records in one tier the archive no longer stands behind: {@code superseded} and {@code
   * invalidated}.
   *
   * <p>Nothing on the search path calls this. It exists so a curator can ask for the tombstones
   * deliberately — the only way a tombstone should ever be found — and so that "the retired set is
   * still there" is a thing a test can state directly rather than infer from a handful of loads by
   * id.
   */
  public List<Memory> loadRetired(Home home) {
    return ArchiveUnavailableException.translating(
        "list the retired memories in a tier",
        () ->
            jdbc.query(
                "SELECT "
                    + COLUMNS
                    + FROM_MEMORIES
                    + " WHERE "
                    + safe(true)
                    + HOME_MATCHES
                    + " AND state IN "
                    + RETIRED_STATES
                    + " ORDER BY m.id",
                ROW_MAPPER,
                ProjectIds.toRead(jdbc, home)));
  }

  /**
   * The index for one tier: id, summary and scope for its {@code active} records.
   *
   * <p><b>Active alone, not the live set.</b> {@code cold} is excluded here and included by {@link
   * #loadAll(Home)}, and the gap between those two is the entire meaning of the state: a cold
   * memory is still true, it has merely stopped earning its place in the librarian's attention
   * budget. Excalibur's {@code toc.py} draws the line in the same place. An index that included
   * cold would make demotion — the one lever the archive has for keeping the index small — do
   * nothing at all.
   *
   * <p>Each line also carries whether the record has an embedding, so the one projection an agent
   * is always shown is the place "this memory cannot be recalled" becomes visible. Without it the
   * index and recall disagree in silence: the index says the memory is there, recall says nothing
   * is close to the question, and the only trace is a {@code log.warn} on the server.
   */
  public List<TocEntry> index(Home home) {
    return ArchiveUnavailableException.translating(
        "index a tier",
        () ->
            jdbc.query(
                "SELECT id, summary, scope, (NOT ("
                    + searchable(false)
                    + ")) AS unsearchable FROM memories"
                    + " WHERE "
                    + safe(false)
                    + HOME_MATCHES
                    + " AND state = 'active'"
                    + " ORDER BY id",
                (rs, rowNum) ->
                    new TocEntry(
                        rs.getString("id"),
                        rs.getString("summary"),
                        rs.getString("scope"),
                        rs.getBoolean("unsearchable")),
                ProjectIds.toRead(jdbc, home)));
  }

  /**
   * How many live records in one tier {@link #searchByVector} cannot reach, because they have no
   * embedding.
   *
   * <p>The counterpart to that method's {@code embedding IS NOT NULL} filter, and it exists so the
   * filter stops being invisible. A memory written while the embedding endpoint was down is stored,
   * {@code active} and indexed, and is silently absent from every recall — so an archive holding
   * exactly one memory could answer "nothing is close to that question" with a straight face, and
   * an agent told that reasonably tries another question, forever.
   *
   * <p>Counted over the <em>live</em> states, matching the search path exactly: a tombstone with no
   * embedding is not missing from an answer, it is excluded from it on purpose, and counting it
   * here would report a problem that is not one.
   */
  public int countUnsearchable(Home home) {
    Integer count =
        ArchiveUnavailableException.translating(
            "count the unsearchable memories in a tier",
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM memories"
                        + " WHERE "
                        + safe(false)
                        + HOME_MATCHES
                        + " AND state IN "
                        + LIVE_STATES
                        + " AND NOT ("
                        + searchable(false)
                        + ")",
                    Integer.class,
                    ProjectIds.toRead(jdbc, home)));
    return count == null ? 0 : count;
  }

  /**
   * The live records in one tier that have no embedding, sorted by id.
   *
   * <p>{@link #countUnsearchable} says how many; this says which, and it is what a repair pass
   * iterates. Full {@link Memory} records rather than index lines, because re-embedding needs the
   * summary and the scope — {@code Archive.embeddedText} is built from those two and from nothing
   * else.
   */
  public List<Memory> unsearchable(Home home) {
    return ArchiveUnavailableException.translating(
        "list the unsearchable memories in a tier",
        () ->
            jdbc.query(
                "SELECT "
                    + COLUMNS
                    + FROM_MEMORIES
                    + " WHERE "
                    + safe(true)
                    + HOME_MATCHES
                    + " AND state IN "
                    + LIVE_STATES
                    + " AND NOT ("
                    + searchable(true)
                    + ")"
                    + " ORDER BY m.id",
                ROW_MAPPER,
                ProjectIds.toRead(jdbc, home)));
  }

  /**
   * Row to record. One mapper, so there is exactly one place the column names live and one place a
   * schema change has to be answered.
   */
  private static final RowMapper<Memory> ROW_MAPPER =
      (rs, rowNum) ->
          new Memory(
              rs.getString("id"),
              rs.getString("summary"),
              rs.getString("scope"),
              new Provenance(
                  instant(rs, "formed_at"),
                  rs.getString("formed_by"),
                  rs.getString("formed_where")),
              MemoryState.fromWireName(rs.getString("state")),
              rs.getBoolean("pinned"),
              rs.getInt("uses"),
              instant(rs, "last_used"),
              rs.getString("body"),
              rs.getString("supersedes"),
              rs.getString("superseded_by"),
              invalidation(rs),
              // NULL project is the global tier, and Home.of would reject the
              // null rather than mean it. Spelling the branch out here is what
              // keeps "global" from ever becoming a string a project could take.
              rs.getString("project") == null ? Home.global() : Home.of(rs.getString("project")));

  /**
   * The tombstone, or {@code null} on a memory that is still true.
   *
   * <p>Keyed on {@code invalidated_at}: the three columns are written together or not at all, and
   * {@code invalidated_why} would be the wrong key — an invalidation whose reason was somehow lost
   * is exactly the case that must still surface as a tombstone rather than as a live memory.
   */
  private static Invalidation invalidation(ResultSet rs) throws SQLException {
    Instant at = instant(rs, "invalidated_at");
    if (at == null) {
      return null;
    }
    return new Invalidation(at, rs.getString("invalidated_by"), rs.getString("invalidated_why"));
  }

  /*
   * OffsetDateTime rather than java.sql.Timestamp on both sides. Timestamp
   * carries no zone and the driver reads it back through the JVM's default
   * calendar, so a server in a non-UTC zone round-trips a shifted instant —
   * and a decay score computed from a shifted formed_at is wrong by hours
   * with nothing to show for it. Note that TIMESTAMPTZ stores microseconds:
   * an Instant with nanosecond precision does not come back equal to itself.
   */
  private static OffsetDateTime utc(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
