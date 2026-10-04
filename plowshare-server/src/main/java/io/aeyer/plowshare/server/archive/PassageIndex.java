package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.documents.Chunker;
import io.aeyer.plowshare.server.documents.Chunking;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.accounting.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Committed-source queue, shared by retained entries and durable digest summaries. */
public final class PassageIndex implements UsageAware {
  private UsageOwners usageOwners = UsageOwners.NONE;

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = java.util.Objects.requireNonNull(source);
  }

  public record Coverage(
      int eligible, int indexed, int passages, int pending, int failed, int stale) {
    public Coverage(int eligible, int indexed, int passages, int pending, int failed) {
      this(eligible, indexed, passages, pending, failed, 0);
    }

    public boolean complete() {
      return eligible == indexed && stale == 0;
    }
  }

  record Source(String type, String id, String hash, String text, int next) {}

  public record Match(String id, int position, String text, double similarity, String revision) {}

  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final EmbeddingClient embeddings;
  private final Chunking bounds;
  private final String generation;
  private final int width;
  private boolean informationScoped;
  private String informationAccount;

  public PassageIndex(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      EmbeddingClient embeddings,
      Chunking bounds,
      String generation,
      int width) {
    if (width != 768)
      throw new IllegalArgumentException("Retrieval passages require the schema's 768 dimensions");
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.embeddings = embeddings;
    this.bounds = bounds;
    this.generation = generation;
    this.width = width;
  }

  public PassageIndex forAccount(String account) {
    PassageIndex copy = new PassageIndex(jdbc, transactions, embeddings, bounds, generation, width);
    copy.informationScoped = true;
    copy.informationAccount = account;
    return copy;
  }

  private <T> List<T> informationRows(
      String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
    String marker = "/*information*/";
    int at = sql.indexOf(marker);
    List<Object> bound = new ArrayList<>(Arrays.asList(args));
    if (informationScoped && at >= 0) {
      int before = (int) sql.substring(0, at).chars().filter(c -> c == '?').count();
      bound.add(before, informationAccount);
    }
    return jdbc.query(
        sql.replace(
            marker, informationScoped ? " AND information_log_readable(s.conversation_id,?)" : ""),
        mapper,
        bound.toArray());
  }

  public String generation() {
    return generation;
  }

  public float[] query(String text) {
    return query(
        text, usageOwners.in(Home.global(), null, UsageAttribution.Operation.EMBEDDING_QUERY));
  }

  public float[] query(String text, UsageAttribution owner) {
    return checked(
        EmbeddingClient.owned(
            embeddings,
            text,
            owner.forOperation(UsageAttribution.Operation.EMBEDDING_QUERY, owner.agentName())));
  }

  private float[] checked(float[] vector) {
    if (vector == null || vector.length != width)
      throw new EmbeddingException("Incompatible retrieval embedding width");
    double norm = 0;
    for (float v : vector) {
      if (!Float.isFinite(v)) throw new EmbeddingException("Non-finite embedding");
      norm += v * v;
    }
    if (norm == 0) throw new EmbeddingException("Zero retrieval embedding");
    return vector;
  }

  /** At most eight sources per pass; successes survive restart, failures back off. */
  public int repair() {
    List<Source> pending =
        jdbc.query(
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
    int written = 0;
    for (Source source : pending) {
      if (source.text() == null || source.text().isBlank()) continue;
      try {
        var chunks = passages(source.text());
        // Reuse existing digest vectors only when their embedding generation is known.
        if (source.type().equals("digest")
            && source.next() == 0
            && source.text().length() <= EntryStore.MOST_CHARACTERS_PER_SNIPPET
            && bounds.tokenizer().count(source.text()).tokens() <= bounds.maxTokens()) {
          var existing =
              jdbc.queryForList(
                  "SELECT embedding::text FROM digests WHERE id=? AND embedding_generation=? AND embedding IS NOT NULL",
                  String.class,
                  source.id(),
                  generation);
          if (!existing.isEmpty()) {
            var cells = existing.get(0).substring(1, existing.get(0).length() - 1).split(",");
            float[] vector = new float[cells.length];
            for (int i = 0; i < cells.length; i++) vector[i] = Float.parseFloat(cells[i]);
            if (publish(source, List.of(source.text()), List.of(checked(vector)))) written++;
            continue;
          }
        }
        int start = source.next(), end = Math.min(start + 16, chunks.size());
        var texts = chunks.subList(start, end);
        UsageAttribution owner = repairOwner(source);
        var batch = EmbeddingClient.owned(embeddings, texts, owner);
        if (batch.size() != texts.size())
          throw new EmbeddingException("Embedding batch count mismatch");
        List<float[]> vectors = new ArrayList<>();
        for (var v : batch) vectors.add(checked(v));
        if (publish(source, texts, vectors, end == chunks.size())) written++;

      } catch (RuntimeException failure) {
        // A source changed/ejected during dispatch is not resurrected, even as a failure.
        jdbc.update(
            "UPDATE retrieval_sources SET status='failed',attempts=attempts+1,last_error=?,"
                + " retry_at=CURRENT_TIMESTAMP + LEAST(3600,30*power(2,LEAST(attempts,7))) * interval '1 second'"
                + " WHERE source_type=? AND source_id=? AND source_hash=?",
            failure.getClass().getSimpleName(),
            source.type(),
            source.id(),
            source.hash());
      }
    }
    return written;
  }

  private UsageAttribution repairOwner(Source source) {
    if (source.type().equals("entry")) {
      var rows =
          jdbc.query(
              "SELECT e.conversation_id,e.turn_ordinal FROM retrieval_sources s JOIN entries e "
                  + "ON e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal WHERE s.source_type='entry' AND s.source_id=?",
              (r, n) ->
                  usageOwners.conversation(
                      r.getString(1), r.getInt(2), UsageAttribution.Operation.EMBEDDING_REPAIR),
              source.id());
      if (!rows.isEmpty()) return rows.getFirst();
    } else {
      var homes =
          jdbc.query(
              "SELECT p.name FROM digests d LEFT JOIN projects p ON p.id=d.project_id WHERE d.id=?",
              (r, n) -> r.getString(1) == null ? Home.global() : Home.of(r.getString(1)),
              source.id());
      if (!homes.isEmpty())
        return usageOwners.in(homes.getFirst(), null, UsageAttribution.Operation.EMBEDDING_REPAIR);
    }
    throw new IllegalStateException("Retrieval source has no accounting owner");
  }

  /** Keep the entire matched passage visible inside the existing snippet contract. */
  private List<String> passages(String text) {
    List<String> result = new ArrayList<>();
    for (var chunk : Chunker.chunk(text, bounds)) {
      String held = chunk.text();
      for (int start = 0; start < held.length(); ) {
        int end = Math.min(start + EntryStore.MOST_CHARACTERS_PER_SNIPPET, held.length());
        if (end < held.length() && Character.isHighSurrogate(held.charAt(end - 1))) end--;
        String piece = held.substring(start, end);
        for (var bounded : Chunker.chunk(piece, bounds)) result.add(bounded.text());
        start = end;
      }
    }
    return List.copyOf(result);
  }

  boolean publish(Source expected, List<String> texts, List<float[]> vectors) {
    return publish(expected, texts, vectors, true);
  }

  boolean publish(Source expected, List<String> texts, List<float[]> vectors, boolean complete) {
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
                "INSERT INTO retrieval_passages VALUES(?,?,?,?,CAST(? AS vector))",
                expected.type(),
                expected.id(),
                expected.next() + i,
                texts.get(i),
                Arrays.toString(vectors.get(i)));
          jdbc.update(
              "UPDATE retrieval_sources SET status=?,generation=?,next_position=?,attempts=0,last_error=NULL,retry_at=CURRENT_TIMESTAMP WHERE source_type=? AND source_id=?",
              complete ? "ready" : "pending",
              generation,
              expected.next() + texts.size(),
              expected.type(),
              expected.id());
          if (complete
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

  /**
   * Query-time home joins remain authoritative after a home move. Exact top-k, no ANN post-filter.
   */
  public List<Match> rank(Home home, String type, float[] query, int most) {
    String join =
        type.equals("entry")
            ? " JOIN entries e ON e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal JOIN conversations owner ON owner.id=e.conversation_id "
            : " JOIN digests owner ON owner.id=s.source_id ";
    String eligible =
        type.equals("entry")
            ? " AND e.content IS NOT NULL AND e.role IS NOT NULL AND e.ejected_at IS NULL "
            : " AND owner.stale_at IS NULL ";
    return informationRows(
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

  public Coverage coverage(Home home, String type) {
    String join =
        type.equals("entry")
            ? " JOIN entries e ON e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal JOIN conversations owner ON owner.id=e.conversation_id "
            : " JOIN digests owner ON owner.id=s.source_id ";
    String fresh = type.equals("digest") ? " AND owner.stale_at IS NULL" : "";
    String stale =
        type.equals("digest") ? "count(*) FILTER(WHERE owner.stale_at IS NOT NULL)" : "0";
    return informationRows(
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

  /** Read the current source metadata, never trusting cached passage content after retention. */
  public LogSearch.Hit hit(Home home, String id, String revision, double rank, String snippet) {
    return informationRows(
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

  public String revision(Home home, String id) {
    return informationRows(
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
