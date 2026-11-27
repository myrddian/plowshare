package io.aeyer.plowshare.server.archive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.InputProvenanceCodec;
import io.aeyer.plowshare.server.archive.PassageIndex.Coverage;
import io.aeyer.plowshare.server.archive.PassageIndex.Match;
import io.aeyer.plowshare.server.archive.PassageIndex.Occurrence;
import io.aeyer.plowshare.server.archive.PassageIndex.PassageText;
import io.aeyer.plowshare.server.archive.PassageIndex.Ranking;
import io.aeyer.plowshare.server.archive.PassageIndex.Source;
import io.aeyer.plowshare.server.embedding.EmbeddingIndexPlan;
import io.aeyer.plowshare.server.embedding.EmbeddingProfile;
import io.aeyer.plowshare.server.embedding.EmbeddingQuery;
import io.aeyer.plowshare.server.embedding.EmbeddingSearchPolicy;
import io.aeyer.plowshare.server.embedding.EmbeddingSlot;
import io.aeyer.plowshare.server.information.InformationLogSql;
import io.aeyer.plowshare.server.information.InformationLogVisibility;
import io.aeyer.plowshare.server.reads.JdbcReadDeadline;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owns retrieval SQL and lock ordering. External embedding calls never run in this repository. */
public final class JdbcPassageRepository implements PassageRepository {
  private static final int ANN_READ_KEYS = 500;
  private static final ObjectReader OCCURRENCES_IN =
      new ObjectMapper().readerFor(new TypeReference<List<Occurrence>>() {});
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final Duration readBudget;
  private final InformationLogVisibility visibility;

  private record IndexSettings(String scan, String sort, String jit) {}

  private record SelectedPassage(String id, int position, String hash, long revision) {}

  private record CandidateWindow(
      List<PassageRanking.Candidate> passages, List<SelectedPassage> keys, boolean full) {}

  private record PassageKey(String id, int position) {}

  public JdbcPassageRepository(JdbcTemplate jdbc, UnitOfWork transactions) {
    this(jdbc, transactions, Duration.ofSeconds(2));
  }

  public JdbcPassageRepository(JdbcTemplate jdbc, UnitOfWork transactions, Duration readBudget) {
    this(jdbc, transactions, readBudget, InformationLogVisibility.direct());
  }

  public JdbcPassageRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      Duration readBudget,
      InformationLogVisibility visibility) {
    if (readBudget == null
        || readBudget.isZero()
        || readBudget.isNegative()
        || readBudget.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException(
          "Passage read budget must be positive and at most one minute");
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.readBudget = readBudget;
    this.visibility = Objects.requireNonNull(visibility);
  }

  private static void sourceType(String type) {
    if (!Set.of("entry", "digest").contains(type))
      throw new IllegalArgumentException("unknown retrieval source type");
  }

  private static String passageFence(String type, ReadScope scope) {
    return type.equals("entry") && !scope.includeInputs() ? " AND NOT p.supplied_context " : "";
  }

  private static void validVector(float[] vector) {
    if (vector == null || vector.length != 768)
      throw new IllegalArgumentException("invalid passage vector width");
    double norm = 0;
    for (float cell : vector) {
      if (!Float.isFinite(cell)) throw new IllegalArgumentException("nonfinite passage vector");
      norm += cell * cell;
    }
    if (norm == 0) throw new IllegalArgumentException("zero passage vector");
  }

  @Override
  /**
   * The next eight committed sources needing repair: not yet ready, or ready under another
   * embedding generation. The worker polls this every two seconds and the table holds a row per
   * content-bearing entry and digest, so both halves must be index probes; written as one {@code
   * status<>'ready' OR generation IS DISTINCT FROM ?} predicate it read every row on every poll to
   * return nothing in steady state. Unready rows come from {@code retrieval_pending}. Ready rows of
   * another generation are the {@code IS NULL}, {@code <} and {@code >} ranges of V239's {@code
   * retrieval_ready_generation}, which are empty once every source is current. The selected set and
   * its order are unchanged.
   *
   * <p>The ready half is gated by a probe of those three ranges. pgJDBC switches to a server-side
   * generic plan after five executions, and a generic plan cannot see that {@code generation<?}
   * matches nothing: it estimates a third of the table and instead walks {@code retrieval_pending}
   * in {@code retry_at} order, filtering out every current row. On deployed data that read 3,100
   * blocks per idle poll instead of 17. Each probe is an ordered {@code LIMIT 1} on the generation
   * index, the only plan that can produce its first row without sorting the table, so it stays an
   * index probe under any plan. The gate does not depend on the outer rows, so PostgreSQL evaluates
   * it once as a one-time filter and skips the ready half when nothing is stale. After a generation
   * change, when most rows are stale, the gate is true and the ready half runs as before.
   */
  public List<Source> pending(String generation) {
    return jdbc.query(
        """
            WITH due AS (
              (SELECT source_type,source_id,retry_at FROM retrieval_sources
                WHERE status IN ('pending','failed')
                  AND (retry_at<=CURRENT_TIMESTAMP OR (last_error LIKE 'RepairInFlight:%' AND last_error<>?))
                ORDER BY retry_at,source_type,source_id LIMIT 8)
              UNION ALL
              (SELECT source_type,source_id,retry_at FROM retrieval_sources
                WHERE status='ready' AND (generation IS NULL OR generation<? OR generation>?)
                  AND (retry_at<=CURRENT_TIMESTAMP OR (last_error LIKE 'RepairInFlight:%' AND last_error<>?))
                  AND (EXISTS(SELECT 1 FROM retrieval_sources WHERE status='ready' AND generation IS NULL)
                    OR (SELECT generation FROM retrieval_sources WHERE status='ready' AND generation<?
                        ORDER BY generation LIMIT 1) IS NOT NULL
                    OR (SELECT generation FROM retrieval_sources WHERE status='ready' AND generation>?
                        ORDER BY generation DESC LIMIT 1) IS NOT NULL)
                ORDER BY retry_at,source_type,source_id LIMIT 8)
              ORDER BY retry_at,source_type,source_id LIMIT 8
            )
            SELECT s.source_type,s.source_id,s.source_hash,CASE WHEN s.generation=? THEN s.next_position ELSE 0 END AS next_position,
                   CASE WHEN s.source_type='entry' THEN e.content ELSE d.summary END AS text,e.input_provenance
            FROM due JOIN retrieval_sources s USING (source_type,source_id)
            LEFT JOIN entries e ON s.source_type='entry' AND e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal
            LEFT JOIN digests d ON s.source_type='digest' AND d.id=s.source_id
            ORDER BY s.retry_at,s.source_type,s.source_id
            """,
        (r, n) ->
            new Source(
                r.getString(1),
                r.getString(2),
                r.getString(3),
                r.getString("text"),
                r.getInt("next_position"),
                InputProvenanceCodec.read(r.getString("input_provenance"))),
        "RepairInFlight:" + generation,
        generation,
        generation,
        "RepairInFlight:" + generation,
        generation,
        generation,
        generation);
  }

  @Override
  public Optional<float[]> digestVector(String id, String generation) {
    var existing =
        jdbc.queryForList(
            "SELECT embedding::text FROM digests WHERE id=? AND embedding_generation=? AND embedding IS NOT NULL",
            String.class,
            id,
            generation);
    if (existing.isEmpty()) return Optional.empty();
    var cells = existing.getFirst().substring(1, existing.getFirst().length() - 1).split(",");
    float[] vector = new float[cells.length];
    for (int i = 0; i < cells.length; i++) vector[i] = Float.parseFloat(cells[i]);
    validVector(vector);
    return Optional.of(vector);
  }

  @Override
  public void failed(Source expected, String error) {
    jdbc.update(
        "UPDATE retrieval_sources SET status='failed',attempts=attempts+1,last_error=?, retry_at=CURRENT_TIMESTAMP + LEAST(3600,30*power(2,LEAST(attempts,7))) * interval '1 second' WHERE source_type=? AND source_id=? AND source_hash=?",
        error,
        expected.type(),
        expected.id(),
        expected.hash());
  }

  @Override
  public Optional<LogOwner> logOwner(String source) {
    return jdbc
        .query(
            "SELECT e.conversation_id,e.turn_ordinal FROM retrieval_sources s JOIN entries e ON e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal WHERE s.source_type='entry' AND s.source_id=?",
            (row, n) -> new LogOwner(row.getString(1), row.getInt(2)),
            source)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Home> digestHome(String source) {
    return jdbc
        .query(
            "SELECT p.name FROM digests d LEFT JOIN projects p ON p.id=d.project_id WHERE d.id=?",
            (row, n) -> row.getString(1) == null ? Home.global() : Home.of(row.getString(1)),
            source)
        .stream()
        .findFirst();
  }

  private static String digestFence(String type, ReadScope scope) {
    if (!type.equals("digest")) return "";
    String safe = scope.constrained() ? " AND information_digest_safe(owner.id)" : "";
    if (!scope.knowledgeOnly()) return safe;
    // A Global knowledge reader may see memory-derived trees, never historical NULL-home logs.
    // Filter before ANN candidate limits, rank fusion and aggregate coverage.
    return safe
        + """
         AND NOT EXISTS(WITH RECURSIVE tree(id) AS (
           SELECT owner.id UNION SELECT e.child_id FROM digest_children e JOIN tree t ON e.parent_id=t.id
         ) SELECT 1 FROM tree t JOIN digest_spans d ON d.digest_id=t.id)
        """;
  }

  private <T> List<T> informationRows(
      ReadScope scope, String sql, RowMapper<T> mapper, Object... args) {
    if (scope.constrained() && sql.contains("/*information*/"))
      return visibility.read(
          scope.account(), facts -> informationRows(scope, sql, mapper, facts, args));
    return informationRows(
        scope, sql, mapper, InformationLogVisibility.Facts.authoritative(), args);
  }

  private <T> List<T> informationRows(
      ReadScope scope,
      String sql,
      RowMapper<T> mapper,
      InformationLogVisibility.Facts facts,
      Object... args) {
    String marker = "/*information*/";
    sql =
        sql.replace(
                "AND /*passage-selection*/ ",
                scope.includeInputs() ? "AND " : "AND NOT p.supplied_context AND ")
            .replace(
                "/*passage-selection*/", scope.includeInputs() ? "" : "AND NOT p.supplied_context");
    int at = sql.indexOf(marker);
    List<Object> bound = new ArrayList<>(Arrays.asList(args));
    if (scope.conversation() != null && at >= 0) {
      int before = (int) sql.substring(0, at).chars().filter(c -> c == '?').count();
      bound.add(before, scope.conversation());
      sql = sql.replace(marker, " AND s.conversation_id=? " + marker);
      at = sql.indexOf(marker);
    }
    String predicate = "";
    if (scope.constrained() && at >= 0) {
      int before = (int) sql.substring(0, at).chars().filter(c -> c == '?').count();
      var filter = InformationLogSql.visible("s.conversation_id", scope.account(), facts);
      bound.addAll(before, filter.arguments());
      predicate = " AND " + filter.sql();
    }
    String selected = sql.replace(marker, predicate);
    return ReadDeadline.within(
        readBudget,
        () -> {
          long deadline =
              JdbcReadDeadline.completionDeadline(ReadDeadline.deadlineNanos(readBudget));
          var setter = new ArgumentPreparedStatementSetter(bound.toArray());
          try {
            var rows =
                jdbc.query(
                    selected,
                    statement -> {
                      setter.setValues(statement);
                      JdbcReadDeadline.apply(statement, deadline);
                    },
                    mapper);
            if (System.nanoTime() - deadline >= 0) throw ReadFailedException.deadlineExceeded();
            return rows;
          } finally {
            setter.cleanupParameters();
          }
        });
  }

  public boolean publish(
      Source expected,
      List<String> texts,
      List<float[]> vectors,
      boolean complete,
      String generation) {
    return publishBatch(expected, texts, vectors, complete, generation, true);
  }

  @Override
  public boolean publishText(
      Source expected, List<String> texts, boolean complete, String generation) {
    return publishBatch(
        expected, texts, Collections.nCopies(texts.size(), null), complete, generation, false);
  }

  @Override
  public Optional<Home> sourceHome(Source source) {
    return jdbc
        .query(
            "SELECT p.name FROM retrieval_sources s "
                + "LEFT JOIN conversations c ON c.id=s.conversation_id "
                + "LEFT JOIN digests d ON s.source_type='digest' AND d.id=s.source_id "
                + "LEFT JOIN projects p ON p.id=coalesce(c.project_id,d.project_id) "
                + "WHERE s.source_type=? AND s.source_id=? AND s.source_hash=?",
            (r, n) -> r.getString(1) == null ? Home.global() : Home.of(r.getString(1)),
            source.type(),
            source.id(),
            source.hash())
        .stream()
        .findFirst();
  }

  @Override
  public void requireSource(Source source, String generation) {
    var valid =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM retrieval_sources s "
                + "LEFT JOIN entries e ON s.source_type='entry' AND e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal "
                + "LEFT JOIN digests d ON s.source_type='digest' AND d.id=s.source_id "
                + "WHERE s.source_type=? AND s.source_id=? AND s.source_hash=? "
                + "AND CASE WHEN s.source_type='entry' THEN e.content ELSE d.summary END=? "
                + "AND CASE WHEN s.generation=? THEN s.next_position ELSE 0 END=? "
                + "AND (s.generation IS DISTINCT FROM ? OR s.status<>'ready'))",
            Boolean.class,
            source.type(),
            source.id(),
            source.hash(),
            source.text(),
            generation,
            source.next(),
            generation);
    if (!Boolean.TRUE.equals(valid))
      throw new IllegalStateException("Passage source changed or was withdrawn");
  }

  @Override
  public boolean beginRepair(Source source, String generation) {
    return jdbc.update(
            "UPDATE retrieval_sources SET status='failed',last_error=?,retry_at='infinity'::timestamptz "
                + "WHERE source_type=? AND source_id=? AND source_hash=? "
                + "AND CASE WHEN generation=? THEN next_position ELSE 0 END=? "
                + "AND (generation IS DISTINCT FROM ? OR status<>'ready') "
                + "AND (retry_at<=CURRENT_TIMESTAMP OR (last_error LIKE 'RepairInFlight:%' AND last_error<>?))",
            "RepairInFlight:" + generation,
            source.type(),
            source.id(),
            source.hash(),
            generation,
            source.next(),
            generation,
            "RepairInFlight:" + generation)
        == 1;
  }

  @Override
  public boolean publish(
      Source source,
      List<String> texts,
      List<float[]> vectors,
      boolean complete,
      String generation,
      Runnable fence) {
    return transactions.inTransaction(
        () -> {
          fence.run();
          return publish(source, texts, vectors, complete, generation);
        });
  }

  @Override
  public boolean publishProjected(
      Source expected,
      List<PassageText> texts,
      List<float[]> vectors,
      boolean complete,
      String generation,
      Runnable fence) {
    return transactions.inTransaction(
        () -> {
          fence.run();
          return publishProjectedBatch(
              expected,
              texts,
              vectors,
              complete,
              generation,
              vectors.stream().anyMatch(Objects::nonNull));
        });
  }

  @Override
  public boolean publishProjectedText(
      Source expected, List<PassageText> texts, boolean complete, String generation) {
    return publishProjectedBatch(
        expected, texts, Collections.nCopies(texts.size(), null), complete, generation, false);
  }

  private boolean publishBatch(
      Source expected,
      List<String> texts,
      List<float[]> vectors,
      boolean complete,
      String generation,
      boolean legacy) {
    if (expected.inputProvenance() != null)
      throw new IllegalArgumentException("Classified inputs require projected passage publication");
    return publishProjectedBatch(
        expected,
        texts.stream().map(text -> new PassageText(text, false)).toList(),
        vectors,
        complete,
        generation,
        legacy);
  }

  private boolean publishProjectedBatch(
      Source expected,
      List<PassageText> texts,
      List<float[]> vectors,
      boolean complete,
      String generation,
      boolean legacy) {
    sourceType(expected.type());
    if (texts.size() != vectors.size() || texts.size() > 16 || expected.next() < 0)
      throw new IllegalArgumentException("invalid passage batch");
    if (legacy) vectors.forEach(JdbcPassageRepository::validVector);

    return transactions.inTransaction(
        () -> {
          // Same lock order as source writes: owner first, derived queue second.
          var owners =
              expected.type().equals("entry")
                  ? jdbc.queryForList(
                      "SELECT e.ordinal FROM entries e JOIN retrieval_sources s ON s.conversation_id=e.conversation_id AND s.ordinal=e.ordinal WHERE s.source_type='entry' AND s.source_id=? FOR UPDATE OF e",
                      expected.id())
                  : jdbc.queryForList(
                      "SELECT id FROM digests WHERE id=? FOR UPDATE", expected.id());
          if (owners.isEmpty()) return false;
          var current =
              jdbc.queryForList(
                  "SELECT source_hash,generation,next_position,status FROM retrieval_sources WHERE source_type=? AND source_id=? FOR UPDATE",
                  expected.type(),
                  expected.id());
          if (current.isEmpty() || !current.get(0).get("source_hash").equals(expected.hash()))
            return false;
          var row = current.get(0);
          if (generation.equals(row.get("generation"))
              && ("ready".equals(row.get("status"))
                  || ((Number) row.get("next_position")).intValue() != expected.next()))
            return false;
          if (!generation.equals(row.get("generation")) && expected.next() != 0) return false;
          if (expected.next() == 0)
            jdbc.update(
                "DELETE FROM retrieval_passages WHERE source_type=? AND source_id=?",
                expected.type(),
                expected.id());
          if (!texts.isEmpty()) {
            var arguments = new ArrayList<Object>();
            for (int i = 0; i < texts.size(); i++) {
              arguments.add(expected.type());
              arguments.add(expected.id());
              arguments.add(expected.next() + i);
              arguments.add(texts.get(i).text());
              arguments.add(texts.get(i).suppliedContext());
              arguments.add(vectors.get(i) == null ? null : Arrays.toString(vectors.get(i)));
            }
            // The existing sixteen-passage limit bounds this statement. Owner/queue locks and
            // all publication predicates remain held; no partial per-passage publication escapes.
            jdbc.update(
                "INSERT INTO retrieval_passages(source_type,source_id,position,passage,supplied_context,embedding) VALUES "
                    + String.join(
                        ",", Collections.nCopies(texts.size(), "(?,?,?,?,?,CAST(? AS vector))")),
                arguments.toArray());
          }
          jdbc.update(
              "UPDATE retrieval_sources SET status=?,generation=?,next_position=?,attempts=0,last_error=NULL,retry_at=CURRENT_TIMESTAMP WHERE source_type=? AND source_id=?",
              complete ? "ready" : "pending",
              generation,
              expected.next() + texts.size(),
              expected.type(),
              expected.id());
          if (legacy
              && complete
              && expected.type().equals("digest")
              && expected.next() == 0
              && texts.size() == 1
              && texts.get(0).text().equals(expected.text()))
            jdbc.update(
                "UPDATE digests SET embedding=CAST(? AS vector),embedding_generation=? WHERE id=?",
                Arrays.toString(vectors.get(0)),
                generation,
                expected.id());
          return true;
        });
  }

  public List<Match> rank(
      Home home, String type, float[] query, int most, String generation, ReadScope scope) {
    sourceType(type);
    if (most < 1 || most > 2000)
      throw new IllegalArgumentException("passage result limit must be 1..2000");

    String join =
        type.equals("entry")
            ? " JOIN entries e ON e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal JOIN conversations owner ON owner.id=e.conversation_id "
            : " JOIN digests owner ON owner.id=s.source_id ";
    String eligible =
        type.equals("entry")
            ? " AND e.content IS NOT NULL AND e.role IS NOT NULL AND e.ejected_at IS NULL "
            : " AND owner.stale_at IS NULL ";
    String best =
        "SELECT DISTINCT ON (s.source_id) s.source_id,p.position,p.passage,s.source_hash,1-(p.embedding <=> CAST(? AS vector)) AS similarity"
            + " FROM retrieval_passages p JOIN retrieval_sources s USING(source_type,source_id)"
            + join
            + " WHERE s.source_type=? AND s.status='ready' AND s.generation=?"
            + " AND owner.project_id IS NOT DISTINCT FROM CAST(? AS bigint)"
            + eligible
            + passageFence(type, scope)
            + digestFence(type, scope)
            + (type.equals("entry") ? " /*information*/" : "")
            + " AND 1-(p.embedding <=> CAST(? AS vector))>=0.35 ORDER BY s.source_id,similarity DESC,p.position";
    return informationRows(
        scope,
        groupMatches(best, type.equals("entry")),
        JdbcPassageRepository::match,
        Arrays.toString(query),
        type,
        generation,
        ProjectIds.toRead(jdbc, home),
        Arrays.toString(query),
        most);
  }

  @Override
  public List<Match> rank(
      Home home, String type, EmbeddingQuery query, int most, String generation, ReadScope scope) {
    return rankWindow(home, type, query, most, generation, scope).matches();
  }

  @Override
  public Ranking rankWindow(
      Home home, String type, EmbeddingQuery query, int most, String generation, ReadScope scope) {
    return ReadDeadline.within(
        readBudget, () -> rankWithinDeadline(home, type, query, most, generation, scope));
  }

  @Override
  public Ranking rankWindow(
      Home home,
      String type,
      EmbeddingQuery query,
      int most,
      String generation,
      ReadScope scope,
      RankingReadUnit units) {
    sourceType(type);
    if (most < 1 || most > 2000)
      throw new IllegalArgumentException("passage result limit must be 1..2000");
    if (TransactionSynchronizationManager.isActualTransactionActive())
      // A caller owns this physical transaction. Never renew its deadline or pretend nested
      // TransactionTemplate calls release its connection. Preserve the established monolithic path.
      return units.joined(() -> rankWindow(home, type, query, most, generation, scope));
    var plan = passagePlan(query.profile());
    // Only the deployed full-vector HNSW policy has validated stable strict-order prefixes.
    // Half/binary and relaxed IVFFlat policies retain their established single-window behavior.
    if (!type.equals("entry")
        || query.profile().search().mode() != EmbeddingSearchPolicy.Mode.HNSW_VECTOR)
      return units.independent(() -> rankWindow(home, type, query, most, generation, scope));
    int bound = plan.candidates(most);
    var retained = new LinkedHashMap<PassageKey, PassageRanking.Candidate>();
    var keys = new LinkedHashMap<PassageKey, SelectedPassage>();
    var versions = new LinkedHashMap<String, String>();
    boolean full = false;
    for (int offset = 0; offset < bound; offset += ANN_READ_KEYS) {
      int pageOffset = offset;
      int limit = Math.min(ANN_READ_KEYS, bound - offset);
      var window =
          units.independent(
              () -> indexedWindow(home, type, query, generation, scope, plan, limit, pageOffset));
      for (var key : window.keys()) {
        var previousHash = versions.putIfAbsent(key.id(), key.hash());
        if (previousHash != null && !previousHash.equals(key.hash()))
          throw ReadFailedException.invalidated();
        var identity = new PassageKey(key.id(), key.position());
        var previous = keys.putIfAbsent(identity, key);
        if (previous != null && previous.revision() != key.revision())
          throw ReadFailedException.invalidated();
      }
      for (var candidate : window.passages())
        retained.putIfAbsent(new PassageKey(candidate.id(), candidate.position()), candidate);
      full = window.full();
      if (!full) break;
    }
    // Previous SQL snapshots have ended. Revalidate all retained identities once under fresh
    // disclosure/profile/project fences, without rerunning ANN or refetching text/vectors. No
    // result escapes if any admitted source changed or disappeared between independent units.
    units.independent(
        () ->
            transactions.inTransaction(
                () -> {
                  validateWindow(home, type, generation, scope, plan, List.copyOf(keys.values()));
                  return true;
                }));
    ReadDeadline.check();
    return new Ranking(
        PassageRanking.group(
            List.copyOf(retained.values()),
            most,
            true,
            query.profile().search().distance() == EmbeddingSearchPolicy.Distance.COSINE),
        true,
        full);
  }

  private void validateWindow(
      Home home,
      String type,
      String generation,
      ReadScope scope,
      EmbeddingIndexPlan plan,
      List<SelectedPassage> keys) {
    if (keys.isEmpty()) return;
    var valid =
        informationRows(
            scope,
            "SELECT p.source_id,p.position FROM unnest(CAST(? AS text[]),CAST(? AS integer[]),CAST(? AS text[]),CAST(? AS bigint[]))"
                + " AS selected(source_id,position,source_hash,source_revision)"
                + " JOIN retrieval_sources s ON s.source_type=? AND s.source_id=selected.source_id"
                + " JOIN retrieval_passages p ON p.source_type=s.source_type AND p.source_id=s.source_id"
                + " JOIN entries e ON e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal"
                + " JOIN conversations owner ON owner.id=e.conversation_id"
                + " WHERE p.position=selected.position AND s.source_hash=selected.source_hash"
                + " AND p.embedding_source_revision=selected.source_revision"
                + " AND s.status='ready' AND s.generation=?"
                + " AND owner.project_id IS NOT DISTINCT FROM CAST(? AS bigint)"
                + " AND e.content IS NOT NULL AND e.role IS NOT NULL AND e.ejected_at IS NULL"
                + passageFence(type, scope)
                + " AND "
                + plan.present("p")
                + " /*information*/",
            (row, n) -> new PassageKey(row.getString("source_id"), row.getInt("position")),
            new SqlArrayValue("text", keys.stream().map(SelectedPassage::id).toArray()),
            new SqlArrayValue("integer", keys.stream().map(SelectedPassage::position).toArray()),
            new SqlArrayValue("text", keys.stream().map(SelectedPassage::hash).toArray()),
            new SqlArrayValue("bigint", keys.stream().map(SelectedPassage::revision).toArray()),
            type,
            generation,
            ProjectIds.toRead(jdbc, home));
    if (valid.size() != keys.size()) throw ReadFailedException.invalidated();
  }

  private static EmbeddingIndexPlan passagePlan(EmbeddingProfile profile) {
    if (profile.slot() != EmbeddingSlot.PROSE)
      throw new IllegalArgumentException("Passage queries require the prose embedding slot");
    return new EmbeddingIndexPlan(profile);
  }

  private Ranking rankWithinDeadline(
      Home home, String type, EmbeddingQuery query, int most, String generation, ReadScope scope) {
    ReadDeadline.check();
    sourceType(type);
    if (most < 1 || most > 2000)
      throw new IllegalArgumentException("passage result limit must be 1..2000");
    var plan = passagePlan(query.profile());
    if (!plan.exact()) return indexedRank(home, type, query, most, generation, scope, plan);
    String join =
        type.equals("entry")
            ? " JOIN entries e ON e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal JOIN conversations owner ON owner.id=e.conversation_id "
            : " JOIN digests owner ON owner.id=s.source_id ";
    String eligible =
        type.equals("entry")
            ? " AND e.content IS NOT NULL AND e.role IS NOT NULL AND e.ejected_at IS NULL "
            : " AND owner.stale_at IS NULL ";
    String column = "p." + plan.column();
    String inner =
        "SELECT s.source_id,p.position,p.passage,s.source_hash,"
            + plan.denseDistance(column)
            + " AS distance"
            + " FROM retrieval_passages p JOIN retrieval_sources s USING(source_type,source_id)"
            + join
            + " WHERE s.source_type=? AND s.status='ready' AND s.generation=? AND owner.project_id IS NOT DISTINCT FROM CAST(? AS bigint)"
            + eligible
            + passageFence(type, scope)
            + digestFence(type, scope)
            + (type.equals("entry") ? " /*information*/" : "")
            + " AND "
            + plan.present("p");
    var args = new ArrayList<Object>();
    args.add(query.vectorText());
    args.add(type);
    args.add(generation);
    args.add(ProjectIds.toRead(jdbc, home));
    String best =
        "SELECT source_id,position,passage,source_hash,1-distance AS similarity FROM (SELECT DISTINCT ON(source_id) * FROM ("
            + inner
            + ") candidates ORDER BY source_id,distance,position) best"
            + (query.profile().search().distance() == EmbeddingSearchPolicy.Distance.COSINE
                ? " WHERE distance<=0.65"
                : "");
    args.add(most);
    return new Ranking(
        informationRows(
            scope,
            groupMatches(best, type.equals("entry")),
            JdbcPassageRepository::match,
            args.toArray()),
        false,
        false);
  }

  /**
   * Only the nearest authorized passage keys enter the first window. A second batched read fetches
   * text and scores original vectors for that bounded window, then deduplicates in memory: neither
   * large text sorts nor source/excerpt grouping can turn the ANN scan back into a full-corpus
   * exact ranking.
   *
   * <p>Iterative scans continue past rejected/private/stale rows up to pgvector's configured scan
   * bounds and the shared statement deadline. They still cannot promise exhaustive recall. LOCAL
   * settings are restored on success even when joining a caller transaction; failure rolls back the
   * owning transaction without issuing more SQL into an aborted transaction.
   */
  private Ranking indexedRank(
      Home home,
      String type,
      EmbeddingQuery query,
      int most,
      String generation,
      ReadScope scope,
      EmbeddingIndexPlan plan) {
    var window =
        indexedWindow(home, type, query, generation, scope, plan, plan.candidates(most), -1);
    ReadDeadline.check();
    var matches =
        PassageRanking.group(
            window.passages(),
            most,
            type.equals("entry"),
            query.profile().search().distance() == EmbeddingSearchPolicy.Distance.COSINE);
    ReadDeadline.check();
    return new Ranking(matches, true, window.full());
  }

  private CandidateWindow indexedWindow(
      Home home,
      String type,
      EmbeddingQuery query,
      String generation,
      ReadScope scope,
      EmbeddingIndexPlan plan,
      int candidateLimit,
      int offset) {
    String join =
        type.equals("entry")
            ? " JOIN entries e ON e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal JOIN conversations owner ON owner.id=e.conversation_id "
            : " JOIN digests owner ON owner.id=s.source_id ";
    String eligible =
        type.equals("entry")
            ? " AND e.content IS NOT NULL AND e.role IS NOT NULL AND e.ejected_at IS NULL "
            : " AND owner.stale_at IS NULL ";
    String column = "p." + plan.column();
    String qualifications =
        " WHERE s.source_type=? AND s.status='ready' AND s.generation=?"
            + " AND owner.project_id IS NOT DISTINCT FROM CAST(? AS bigint)"
            + eligible
            + digestFence(type, scope)
            + (type.equals("entry") ? " /*information*/" : "");
    // ANN order can visit many other homes before filling this home's window. Joining every
    // visited vector to its source/conversation repeats tens of thousands of PK probes, even when
    // the final window has only 2000 keys. Resolve eligible entry IDs once in the same statement
    // snapshot; IS TRUE deliberately retains a hashed SubPlan inside the ordered index scan rather
    // than allowing PostgreSQL to flatten membership back into those per-vector joins. Retain the
    // source hashes with those IDs too: refetching them for the accepted keys adds random source
    // index/heap reads on a cold database. The separately materialized ANN window keeps its ordered
    // limit before the hash projection joins the already authorized rows. Full-precision ranking
    // below owns final ordering, so this projection cannot change candidate membership or ties.
    String sql =
        (type.equals("entry")
                ? "WITH authorized_sources AS MATERIALIZED (SELECT s.source_id,s.source_hash FROM retrieval_sources s"
                    + join
                    + qualifications
                    + "), candidate_keys AS MATERIALIZED (SELECT p.source_id,p.position,p.embedding_source_revision"
                    + " FROM retrieval_passages p"
                    + " WHERE p.source_type='entry' AND (p.source_id IN (SELECT source_id FROM authorized_sources)) IS TRUE"
                : "SELECT p.source_id,p.position,s.source_hash,p.embedding_source_revision"
                    + " FROM retrieval_passages p JOIN retrieval_sources s USING(source_type,source_id)"
                    + join
                    + qualifications)
            + passageFence(type, scope)
            + " AND "
            + plan.present("p")
            + " ORDER BY "
            + plan.candidateDistance(column)
            + " LIMIT ?"
            + (offset >= 0 ? " OFFSET ?" : "")
            + (type.equals("entry")
                ? ") SELECT k.source_id,k.position,authorized.source_hash,k.embedding_source_revision"
                    + " FROM candidate_keys k JOIN authorized_sources authorized USING(source_id)"
                : "");
    CandidateWindow window =
        transactions.inTransaction(
            () -> {
              boolean hnsw =
                  query.profile().search().mode().configurationName().startsWith("hnsw_");
              String setting = hnsw ? "hnsw.iterative_scan" : "ivfflat.iterative_scan";
              // Equality between the two revision columns is badly underestimated by PostgreSQL
              // (0.5% even when all published vectors are current). It can otherwise choose a
              // full vector scan/sort for this explicitly indexed policy. Discourage that sort
              // only in this short transaction; the outer, bounded rerank happens in memory.
              // High estimated ANN costs also trigger JIT compilation whose startup consumes a
              // short interactive budget; no compilation is needed for this bounded read.
              IndexSettings previous =
                  informationRows(
                          scope,
                          "WITH previous AS MATERIALIZED (SELECT coalesce(current_setting(?,true),'off') AS scan,"
                              + "current_setting('enable_sort') AS sorting,current_setting('jit') AS jit)"
                              + " SELECT scan,sorting,jit,set_config(?,?,true),set_config('enable_sort','off',true),set_config('jit','off',true) FROM previous",
                          (row, n) ->
                              new IndexSettings(
                                  row.getString("scan"),
                                  row.getString("sorting"),
                                  row.getString("jit")),
                          setting,
                          setting,
                          hnsw ? "strict_order" : "relaxed_order")
                      .getFirst();
              var args = new ArrayList<Object>();
              Collections.addAll(
                  args,
                  type,
                  generation,
                  ProjectIds.toRead(jdbc, home),
                  query.vectorText(),
                  candidateLimit);
              if (offset >= 0) args.add(offset);
              List<SelectedPassage> keys =
                  informationRows(
                      scope,
                      sql,
                      (row, n) ->
                          new SelectedPassage(
                              row.getString("source_id"),
                              row.getInt("position"),
                              row.getString("source_hash"),
                              row.getLong("embedding_source_revision")),
                      args.toArray());
              // This is a different projection of exactly the keys just admitted, not a rerun of
              // ANN selection or permission discovery. Parallel typed arrays keep one batch and
              // one stable SQL shape. The source revision prevents a delete/reinsert or edit
              // between stages from attaching new text/vectors to an old authorized identity.
              List<PassageRanking.Candidate> selected =
                  keys.isEmpty()
                      ? List.of()
                      : informationRows(
                          scope,
                          "SELECT selected.source_id,selected.position,p.passage,selected.source_hash,"
                              + plan.denseDistance(column)
                              + " AS distance FROM unnest(CAST(? AS text[]),CAST(? AS integer[]),CAST(? AS text[]),CAST(? AS bigint[]))"
                              + " AS selected(source_id,position,source_hash,source_revision)"
                              + " JOIN retrieval_passages p ON p.source_type=? AND p.source_id=selected.source_id AND p.position=selected.position"
                              + " WHERE p.embedding_source_revision=selected.source_revision AND "
                              + plan.present("p"),
                          (row, n) ->
                              new PassageRanking.Candidate(
                                  row.getString("source_id"),
                                  row.getInt("position"),
                                  row.getString("passage"),
                                  row.getString("source_hash"),
                                  row.getDouble("distance")),
                          query.vectorText(),
                          new SqlArrayValue(
                              "text", keys.stream().map(SelectedPassage::id).toArray()),
                          new SqlArrayValue(
                              "integer", keys.stream().map(SelectedPassage::position).toArray()),
                          new SqlArrayValue(
                              "text", keys.stream().map(SelectedPassage::hash).toArray()),
                          new SqlArrayValue(
                              "bigint", keys.stream().map(SelectedPassage::revision).toArray()),
                          type);
              informationRows(
                  scope,
                  "SELECT set_config(?,?,true),set_config('enable_sort',?,true),set_config('jit',?,true)",
                  (row, n) -> row.getString(1),
                  setting,
                  previous.scan(),
                  previous.sort(),
                  previous.jit());
              return new CandidateWindow(selected, keys, keys.size() == candidateLimit);
            });
    return window;
  }

  /**
   * Duplicate passages collapse before the source limit, with bounded authorized references. Number
   * occurrences once so aggregates retain only the representative and twenty references, rather
   * than constructing full arrays of every duplicate's text and metadata then slicing them.
   */
  private static String groupMatches(String best, boolean group) {
    if (!group)
      return "SELECT best.*,1 AS source_count,jsonb_build_array(jsonb_build_object('id',source_id,'revision',source_hash))::text AS sources FROM ("
          + best
          + ") best ORDER BY similarity DESC,source_id LIMIT ?";
    return "WITH best AS ("
        + best
        + "), ordered AS (SELECT best.*,"
        + "regexp_replace(btrim(passage),'\\s+',' ','g') AS passage_key,"
        + "row_number() OVER (PARTITION BY regexp_replace(btrim(passage),'\\s+',' ','g')"
        + " ORDER BY similarity DESC,source_id,position) AS occurrence FROM best), grouped AS (SELECT "
        + "max(source_id) FILTER(WHERE occurrence=1) AS source_id,"
        + "max(position) FILTER(WHERE occurrence=1) AS position,"
        + "max(passage) FILTER(WHERE occurrence=1) AS passage,"
        + "max(source_hash) FILTER(WHERE occurrence=1) AS source_hash,"
        + "max(similarity) AS similarity,count(*)::integer AS source_count,"
        + "(jsonb_agg(jsonb_build_object('id',source_id,'revision',source_hash)"
        + " ORDER BY occurrence) FILTER(WHERE occurrence<=20))::text AS sources"
        + " FROM ordered GROUP BY passage_key)"
        + " SELECT * FROM grouped ORDER BY similarity DESC,source_id LIMIT ?";
  }

  private static Match match(ResultSet rs, int row) throws SQLException {
    try {
      List<Occurrence> sources = OCCURRENCES_IN.readValue(rs.getString("sources"));
      return new Match(
          rs.getString("source_id"),
          rs.getInt("position"),
          rs.getString("passage"),
          rs.getDouble("similarity"),
          rs.getString("source_hash"),
          sources,
          rs.getInt("source_count"));
    } catch (JsonProcessingException invalid) {
      throw new SQLException("Invalid persisted passage references", invalid);
    }
  }

  @Override
  public Coverage coverage(
      Home home,
      String type,
      String generation,
      ReadScope scope,
      Optional<EmbeddingProfile> profile) {
    sourceType(type);
    String join =
        type.equals("entry")
            ? " JOIN entries e ON e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal JOIN conversations owner ON owner.id=e.conversation_id "
            : " JOIN digests owner ON owner.id=s.source_id ";
    String present = profile.map(p -> passagePlan(p).present("p")).orElse("false");
    String complete =
        "s.status='ready' AND s.generation=? AND EXISTS(SELECT 1 FROM retrieval_passages p WHERE p.source_type=s.source_type AND p.source_id=s.source_id /*passage-selection*/)"
            + " AND NOT EXISTS(SELECT 1 FROM retrieval_passages p WHERE p.source_type=s.source_type AND p.source_id=s.source_id AND /*passage-selection*/ NOT ("
            + present
            + "))"
            + (type.equals("digest") ? " AND owner.stale_at IS NULL" : "");
    return informationRows(
            scope,
            "SELECT count(*) AS eligible,count(*) FILTER(WHERE "
                + complete
                + ") AS indexed,"
                + " COALESCE(sum((SELECT count(*) FROM retrieval_passages p WHERE p.source_type=s.source_type AND p.source_id=s.source_id AND /*passage-selection*/ "
                + present
                + ")),0) AS passages,"
                + " count(*) FILTER(WHERE s.status='failed') AS failed,"
                + (type.equals("digest")
                    ? "count(*) FILTER(WHERE owner.stale_at IS NOT NULL)"
                    : "0")
                + " AS stale"
                + " FROM retrieval_sources s"
                + join
                + " WHERE s.source_type=? AND owner.project_id IS NOT DISTINCT FROM CAST(? AS bigint)"
                + digestFence(type, scope)
                + (type.equals("entry") ? " /*information*/" : ""),
            (r, n) ->
                new Coverage(
                    r.getInt("eligible"),
                    r.getInt("indexed"),
                    r.getInt("passages"),
                    Math.max(
                        0,
                        r.getInt("eligible")
                            - r.getInt("indexed")
                            - r.getInt("failed")
                            - r.getInt("stale")),
                    r.getInt("failed"),
                    r.getInt("stale")),
            generation,
            type,
            ProjectIds.toRead(jdbc, home))
        .getFirst();
  }

  public Coverage coverage(Home home, String type, String generation, ReadScope scope) {
    sourceType(type);

    String join =
        type.equals("entry")
            ? " JOIN entries e ON e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal JOIN conversations owner ON owner.id=e.conversation_id "
            : " JOIN digests owner ON owner.id=s.source_id ";
    String fresh = type.equals("digest") ? " AND owner.stale_at IS NULL" : "";
    String stale =
        type.equals("digest") ? "count(*) FILTER(WHERE owner.stale_at IS NOT NULL)" : "0";
    return informationRows(
            scope,
            "SELECT count(*) AS eligible,"
                + " count(*) FILTER(WHERE s.status='ready' AND s.generation=?"
                + fresh
                + ") AS indexed,"
                + " COALESCE(sum((SELECT count(*) FROM retrieval_passages p WHERE p.source_type=s.source_type AND p.source_id=s.source_id AND /*passage-selection*/ s.generation=?)),0) AS passages,"
                + " count(*) FILTER(WHERE s.status='failed'"
                + fresh
                + ") AS failed,"
                + stale
                + " AS stale FROM retrieval_sources s"
                + join
                + " WHERE s.source_type=? AND owner.project_id IS NOT DISTINCT FROM CAST(? AS bigint)"
                + digestFence(type, scope)
                + (type.equals("entry") ? " /*information*/" : ""),
            (r, n) ->
                new Coverage(
                    r.getInt("eligible"),
                    r.getInt("indexed"),
                    r.getInt("passages"),
                    r.getInt("eligible")
                        - r.getInt("indexed")
                        - r.getInt("failed")
                        - r.getInt("stale"),
                    r.getInt("failed"),
                    r.getInt("stale")),
            generation,
            generation,
            type,
            ProjectIds.toRead(jdbc, home))
        .getFirst();
  }

  public LogSearch.Hit hit(
      Home home, String id, String revision, double rank, String snippet, ReadScope scope) {
    return informationRows(
            scope,
            "SELECT e.conversation_id,e.ordinal,e.turn_ordinal,e.kind,e.superseded_by,e.handle,e.recorded_at,length(e.content) AS source_length FROM retrieval_sources s JOIN entries e ON e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal"
                + " JOIN conversations c ON c.id=e.conversation_id WHERE s.source_type='entry' AND s.source_id=? AND s.source_hash=?"
                + " AND e.content IS NOT NULL AND e.role IS NOT NULL AND c.project_id IS NOT DISTINCT FROM CAST(? AS bigint) /*information*/",
            (r, n) ->
                new LogSearch.Hit(
                    r.getString("conversation_id"),
                    r.getInt("ordinal"),
                    r.getInt("turn_ordinal"),
                    EntryKind.of(r.getString("kind")),
                    rank,
                    snippet.length() > EntryStore.MOST_CHARACTERS_PER_SNIPPET
                        ? snippet.substring(0, EntryStore.MOST_CHARACTERS_PER_SNIPPET)
                        : snippet,
                    r.getInt("source_length"),
                    (Integer) r.getObject("superseded_by"),
                    r.getObject("handle", UUID.class),
                    r.getTimestamp("recorded_at") == null
                        ? null
                        : r.getTimestamp("recorded_at").toInstant()),
            id,
            revision,
            ProjectIds.toRead(jdbc, home))
        .stream()
        .findFirst()
        .orElse(null);
  }

  public String revision(Home home, String id, ReadScope scope) {
    return informationRows(
            scope,
            "SELECT s.source_hash FROM retrieval_sources s JOIN conversations c ON c.id=s.conversation_id"
                + " WHERE s.source_type='entry' AND s.source_id=? AND c.project_id IS NOT DISTINCT FROM CAST(? AS bigint) /*information*/",
            (r, n) -> r.getString(1),
            id,
            ProjectIds.toRead(jdbc, home))
        .stream()
        .findFirst()
        .orElse(null);
  }
}
