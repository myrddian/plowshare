package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.PassageIndex.*;
import io.aeyer.plowshare.server.embedding.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns retrieval SQL and lock ordering. External embedding calls never run in this repository. */
public final class JdbcPassageRepository implements PassageRepository {
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;

  public JdbcPassageRepository(JdbcTemplate jdbc, UnitOfWork transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  private static void sourceType(String type) {
    if (!Set.of("entry", "digest").contains(type))
      throw new IllegalArgumentException("unknown retrieval source type");
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
  public List<Source> pending(String generation) {
    return jdbc.query(
        """
            SELECT s.source_type,s.source_id,s.source_hash,CASE WHEN s.generation=? THEN s.next_position ELSE 0 END AS next_position,
                   CASE WHEN s.source_type='entry' THEN e.content ELSE d.summary END AS text
            FROM retrieval_sources s
            LEFT JOIN entries e ON s.source_type='entry' AND e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal
            LEFT JOIN digests d ON s.source_type='digest' AND d.id=s.source_id
            WHERE (s.status<>'ready' OR s.generation IS DISTINCT FROM ?) AND s.retry_at<=CURRENT_TIMESTAMP
            ORDER BY s.retry_at,s.source_type,s.source_id LIMIT 8
            """,
        (r, n) ->
            new Source(
                r.getString(1),
                r.getString(2),
                r.getString(3),
                r.getString("text"),
                r.getInt("next_position")),
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

  private <T> List<T> informationRows(
      ReadScope scope,
      String sql,
      org.springframework.jdbc.core.RowMapper<T> mapper,
      Object... args) {
    String marker = "/*information*/";
    int at = sql.indexOf(marker);
    List<Object> bound = new ArrayList<>(Arrays.asList(args));
    if (scope.constrained() && at >= 0) {
      int before = (int) sql.substring(0, at).chars().filter(c -> c == '?').count();
      bound.add(before, scope.account());
    }
    return jdbc.query(
        sql.replace(
            marker,
            scope.constrained() ? " AND information_log_readable(s.conversation_id,?)" : ""),
        mapper,
        bound.toArray());
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
        expected,
        texts,
        java.util.Collections.nCopies(texts.size(), null),
        complete,
        generation,
        false);
  }

  private boolean publishBatch(
      Source expected,
      List<String> texts,
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
          for (int i = 0; i < texts.size(); i++)
            jdbc.update(
                "INSERT INTO retrieval_passages(source_type,source_id,position,passage,embedding) VALUES(?,?,?,?,CAST(? AS vector))",
                expected.type(),
                expected.id(),
                expected.next() + i,
                texts.get(i),
                vectors.get(i) == null ? null : Arrays.toString(vectors.get(i)));
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
              && texts.get(0).equals(expected.text()))
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

    String join =
        type.equals("entry")
            ? " JOIN entries e ON e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal JOIN conversations owner ON owner.id=e.conversation_id "
            : " JOIN digests owner ON owner.id=s.source_id ";
    String eligible =
        type.equals("entry")
            ? " AND e.content IS NOT NULL AND e.role IS NOT NULL AND e.ejected_at IS NULL "
            : " AND owner.stale_at IS NULL ";
    return informationRows(
        scope,
        "SELECT source_id,position,passage,source_hash,similarity FROM (SELECT DISTINCT ON (s.source_id) s.source_id,p.position,p.passage,s.source_hash,1-(p.embedding <=> CAST(? AS vector)) AS similarity"
            + " FROM retrieval_passages p JOIN retrieval_sources s USING(source_type,source_id)"
            + join
            + " WHERE s.source_type=? AND s.status='ready' AND s.generation=?"
            + " AND owner.project_id IS NOT DISTINCT FROM CAST(? AS bigint)"
            + eligible
            + (type.equals("entry") ? " /*information*/" : "")
            + " AND 1-(p.embedding <=> CAST(? AS vector))>=0.35 ORDER BY s.source_id,similarity DESC,p.position) best ORDER BY similarity DESC,source_id LIMIT ?",
        (r, n) ->
            new Match(
                r.getString(1),
                r.getInt(2),
                r.getString(3),
                r.getDouble("similarity"),
                r.getString("source_hash")),
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
    sourceType(type);
    if (most < 1 || most > 2000)
      throw new IllegalArgumentException("passage result limit must be 1..2000");
    var plan = new EmbeddingIndexPlan(query.profile());
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
            + (type.equals("entry") ? " /*information*/" : "")
            + " AND "
            + plan.present("p");
    var args = new java.util.ArrayList<Object>();
    args.add(query.vectorText());
    args.add(type);
    args.add(generation);
    args.add(ProjectIds.toRead(jdbc, home));
    if (plan.exact()) inner += " ORDER BY distance";
    else {
      inner += " ORDER BY " + plan.candidateDistance(column);
      args.add(query.vectorText());
    }
    // More passages than sources are considered before deduplication. This is an explicit
    // candidate bound; repeated passages never contribute additional fusion votes.
    if (!plan.exact()) {
      inner += " LIMIT ?";
      args.add(plan.candidates(most));
    }
    String sql =
        "SELECT source_id,position,passage,source_hash,distance FROM (SELECT DISTINCT ON(source_id) * FROM ("
            + inner
            + ") candidates ORDER BY source_id,distance,position) best"
            + (query.profile().search().distance() == EmbeddingSearchPolicy.Distance.COSINE
                ? " WHERE distance<=0.65"
                : "")
            + " ORDER BY distance,source_id LIMIT ?";
    args.add(most);
    return informationRows(
        scope,
        sql,
        (r, n) ->
            new Match(
                r.getString("source_id"),
                r.getInt("position"),
                r.getString("passage"),
                1 - r.getDouble("distance"),
                r.getString("source_hash")),
        args.toArray());
  }

  @Override
  public Coverage coverage(
      Home home,
      String type,
      String generation,
      ReadScope scope,
      java.util.Optional<EmbeddingProfile> profile) {
    sourceType(type);
    String join =
        type.equals("entry")
            ? " JOIN entries e ON e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal JOIN conversations owner ON owner.id=e.conversation_id "
            : " JOIN digests owner ON owner.id=s.source_id ";
    String present = profile.map(p -> new EmbeddingIndexPlan(p).present("p")).orElse("false");
    String complete =
        "s.status='ready' AND s.generation=? AND EXISTS(SELECT 1 FROM retrieval_passages p WHERE p.source_type=s.source_type AND p.source_id=s.source_id)"
            + " AND NOT EXISTS(SELECT 1 FROM retrieval_passages p WHERE p.source_type=s.source_type AND p.source_id=s.source_id AND NOT ("
            + present
            + "))"
            + (type.equals("digest") ? " AND owner.stale_at IS NULL" : "");
    return informationRows(
            scope,
            "SELECT count(*) AS eligible,count(*) FILTER(WHERE "
                + complete
                + ") AS indexed,"
                + " COALESCE(sum((SELECT count(*) FROM retrieval_passages p WHERE p.source_type=s.source_type AND p.source_id=s.source_id AND "
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
                + " COALESCE(sum((SELECT count(*) FROM retrieval_passages p WHERE p.source_type=s.source_type AND p.source_id=s.source_id AND s.generation=?)),0) AS passages,"
                + " count(*) FILTER(WHERE s.status='failed'"
                + fresh
                + ") AS failed,"
                + stale
                + " AS stale FROM retrieval_sources s"
                + join
                + " WHERE s.source_type=? AND owner.project_id IS NOT DISTINCT FROM CAST(? AS bigint)"
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
                    io.aeyer.plowshare.server.agents.EntryKind.of(r.getString("kind")),
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
