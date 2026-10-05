package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cross-store embedding lifecycle. Locks slot before sources; activation then locks all five tables
 * in enum order to freeze the coverage check and publication. Remote calls never run here. Staging
 * is durable progress: restarts recompute only missing/current inputs. Multiple processes may
 * compute the same input, but source/target fences prevent stale publication.
 */
public class JdbcEmbeddingWorkRepository implements EmbeddingWorkRepository {
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final EmbeddingSpaceRepository spaces;

  public JdbcEmbeddingWorkRepository(
      JdbcTemplate jdbc, UnitOfWork transactions, EmbeddingSpaceRepository spaces) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    this.spaces = Objects.requireNonNull(spaces);
  }

  private static String table(Store store) {
    return switch (store) {
      case MEMORIES -> "memories";
      case DIGESTS -> "digests";
      case CHUNKS -> "chunks";
      case DOCUMENTS -> "documents";
      case PASSAGES -> "retrieval_passages";
    };
  }

  private static String keys(Store store) {
    return store == Store.PASSAGES ? "source_type,source_id,position" : "source_id";
  }

  private static String link(Store store, String other) {
    return store == Store.PASSAGES
        ? other
            + ".source_type=s.source_type AND "
            + other
            + ".source_id=s.source_id AND "
            + other
            + ".position=s.position"
        : other + ".source_id=s.id";
  }

  private static String identity(Key key) {
    return key.store() == Store.PASSAGES
        ? "s.source_type=? AND s.source_id=? AND s.position=?"
        : "s.id=?";
  }

  private static List<Object> keyArguments(Key key) {
    return key.store() == Store.PASSAGES
        ? List.of(key.sourceType(), key.id(), key.position())
        : List.of(
            key.store() == Store.CHUNKS || key.store() == Store.DOCUMENTS
                ? UUID.fromString(key.id())
                : key.id());
  }

  private static String eligible(Store store) {
    return store == Store.DOCUMENTS ? "s.summary IS NOT NULL" : "true";
  }

  private static String text(Store store) {
    return switch (store) {
      case MEMORIES -> "s.summary || E'\\n' || s.scope";
      case DIGESTS, DOCUMENTS -> "s.summary";
      case CHUNKS -> "s.text";
      case PASSAGES -> "s.passage";
    };
  }

  private static String joins(Store store) {
    return switch (store) {
      case CHUNKS ->
          " JOIN paragraphs parent ON parent.id=s.paragraph_id LEFT JOIN information_document_policies policy ON policy.document_id=parent.document_id";
      case DOCUMENTS ->
          " LEFT JOIN information_document_policies policy ON policy.document_id=s.id";
      case PASSAGES ->
          " JOIN retrieval_sources parent ON parent.source_type=s.source_type AND parent.source_id=s.source_id LEFT JOIN conversations conversation ON conversation.id=parent.conversation_id LEFT JOIN digests digest ON parent.source_type='digest' AND digest.id=parent.source_id";
      default -> "";
    };
  }

  private static String project(Store store) {
    return switch (store) {
      case CHUNKS, DOCUMENTS -> "policy.project_id";
      case PASSAGES -> "coalesce(conversation.project_id,digest.project_id)";
      default -> "s.project_id";
    };
  }

  private static String sourceColumns(Store store) {
    return (store == Store.PASSAGES
            ? "s.source_id,s.source_type,s.position"
            : "s.id AS source_id,NULL::text AS source_type,0 AS position")
        + ",s.embedding_source_revision,"
        + text(store)
        + " AS input,"
        + "(SELECT name FROM projects usage_project WHERE usage_project.id="
        + project(store)
        + ") AS project";
  }

  private static org.springframework.jdbc.core.RowMapper<Source> sourceMapper(Store store) {
    return (row, n) ->
        new Source(
            new Key(
                store,
                row.getString("source_id"),
                row.getString("source_type"),
                row.getInt("position")),
            row.getLong("embedding_source_revision"),
            row.getString("input"),
            row.getString("project"));
  }

  @Override
  public Optional<Source> source(Key key) {
    Objects.requireNonNull(key);
    return jdbc
        .query(
            "SELECT "
                + sourceColumns(key.store())
                + " FROM "
                + table(key.store())
                + " s"
                + joins(key.store())
                + " WHERE "
                + identity(key)
                + " AND "
                + eligible(key.store()),
            sourceMapper(key.store()),
            keyArguments(key).toArray())
        .stream()
        .findFirst();
  }

  private Optional<EmbeddingProfile> profile(EmbeddingSlot slot, String pointer) {
    return jdbc.query(
            "SELECT "
                + pointer
                + "_space_id AS space_id,"
                + pointer
                + "_mode AS mode,"
                + pointer
                + "_distance AS distance,version"
                + " FROM embedding_slots WHERE slot=?",
            (r, n) ->
                r.getString("space_id") == null
                    ? Optional.<EmbeddingProfile>empty()
                    : Optional.of(
                        new EmbeddingProfile(
                            slot,
                            spaces.find(r.getString("space_id")).orElseThrow(),
                            new EmbeddingSearchPolicy(
                                EmbeddingSearchPolicy.Mode.fromConfiguration(r.getString("mode")),
                                EmbeddingSearchPolicy.Distance.valueOf(r.getString("distance"))),
                            r.getLong("version"))),
            slot.stored())
        .getFirst();
  }

  @Override
  public void inputLimit(EmbeddingSpace space, int tokens) {
    if (tokens < 1) throw new IllegalArgumentException("embedding input limit must be positive");
    jdbc.update(
        "INSERT INTO embedding_input_limits VALUES(?,?) ON CONFLICT(space_id) DO UPDATE SET max_input_tokens=excluded.max_input_tokens",
        space.id(),
        tokens);
  }

  @Override
  public int inputLimit(EmbeddingSpace space) {
    return jdbc.queryForObject(
        "SELECT max_input_tokens FROM embedding_input_limits WHERE space_id=?",
        Integer.class,
        space.id());
  }

  @Override
  public Optional<EmbeddingProfile> active(EmbeddingSlot slot) {
    return profile(slot, "active");
  }

  @Override
  public Optional<EmbeddingProfile> target(EmbeddingSlot slot) {
    return profile(slot, "target");
  }

  private void lock(EmbeddingSlot slot, boolean exclusive) {
    jdbc.queryForObject(
        "SELECT version FROM embedding_slots WHERE slot=? FOR " + (exclusive ? "UPDATE" : "SHARE"),
        Long.class,
        slot.stored());
  }

  @Override
  public EmbeddingProfile configure(
      EmbeddingSlot slot, EmbeddingSpace space, EmbeddingSearchPolicy policy) {
    Objects.requireNonNull(slot);
    Objects.requireNonNull(space);
    Objects.requireNonNull(policy).validate(space.definition());
    return transactions.inTransaction(
        () -> {
          lock(slot, true);
          var previous = target(slot);
          if (previous.isPresent()
              && previous.get().space().equals(space)
              && previous.get().search().equals(policy)) return previous.get();
          jdbc.update(
              "UPDATE embedding_slots SET target_space_id=?,target_mode=?,target_distance=? WHERE slot=?",
              space.id(),
              policy.mode().configurationName(),
              policy.distance().name(),
              slot.stored());
          return target(slot).orElseThrow();
        });
  }

  private String ready(EmbeddingProfile target, Store store) {
    return "("
        + new EmbeddingIndexPlan(target).present("s")
        + ") OR EXISTS(SELECT 1 FROM "
        + table(store)
        + "_embedding_staging staged WHERE "
        + link(store, "staged")
        + " AND staged.slot=? AND staged.space_id=? AND staged.source_revision=s.embedding_source_revision)";
  }

  @Override
  public List<Source> pending(EmbeddingProfile target, Store store, int limit) {
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("embedding repair batch must be 1..100");
    return jdbc.query(
        "SELECT "
            + sourceColumns(store)
            + " FROM "
            + table(store)
            + " s"
            + joins(store)
            + " WHERE "
            + eligible(store)
            + " AND NOT ("
            + ready(target, store)
            + ")"
            + " AND NOT EXISTS(SELECT 1 FROM "
            + table(store)
            + "_embedding_failures f WHERE "
            + link(store, "f")
            + " AND f.slot=? AND f.space_id=? AND f.source_revision=s.embedding_source_revision AND f.retry_at>CURRENT_TIMESTAMP)"
            + " ORDER BY s.embedding_source_revision,"
            + (store == Store.PASSAGES ? "s.source_type,s.source_id,s.position" : "s.id")
            + " LIMIT ?",
        sourceMapper(store),
        target.slot().stored(),
        target.space().id(),
        target.slot().stored(),
        target.space().id(),
        limit);
  }

  @Override
  public boolean ready(EmbeddingProfile target, Source source) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM "
                + table(source.key().store())
                + " s WHERE "
                + identity(source.key())
                + " AND s.embedding_source_revision=? AND ("
                + ready(target, source.key().store())
                + "))",
            Boolean.class,
            append(
                keyArguments(source.key()),
                source.revision(),
                target.slot().stored(),
                target.space().id())));
  }

  @Override
  public boolean publish(EmbeddingProfile target, Source expected, float[] vector) {
    float[] checked = EmbeddingQuery.validate(target, vector);
    return transactions.inTransaction(
        () -> {
          lock(target.slot(), false);
          if (!target.equals(target(target.slot()).orElse(null))
              && !target.equals(active(target.slot()).orElse(null))) return false;
          Key key = expected.key();
          Store store = key.store();
          var arguments = new ArrayList<>(keyArguments(key));
          arguments.add(expected.revision());
          var exists =
              jdbc.queryForList(
                  "SELECT s.embedding_source_revision FROM "
                      + table(store)
                      + " s WHERE "
                      + identity(key)
                      + " AND s.embedding_source_revision=? AND "
                      + eligible(store)
                      + " FOR UPDATE",
                  arguments.toArray());
          if (exists.isEmpty()) return false;
          var values = new ArrayList<>(keyArguments(key));
          values.add(target.slot().stored());
          values.add(target.space().id());
          values.add(expected.revision());
          values.add(Arrays.toString(checked));
          int keyCount = keyArguments(key).size();
          jdbc.update(
              "INSERT INTO "
                  + table(store)
                  + "_embedding_staging("
                  + keys(store)
                  + ",slot,space_id,source_revision,embedding) VALUES("
                  + "?,".repeat(keyCount)
                  + "?,?,?,CAST(? AS vector)) ON CONFLICT DO NOTHING",
              values.toArray());
          // Once active, publish individual new/edit repairs immediately. During a rebuild the old
          // columns remain intact until the complete corpus and indexes can switch atomically.
          if (active(target.slot()).filter(p -> p.space().equals(target.space())).isPresent())
            copy(target, store, " AND " + identity(key), keyArguments(key).toArray());
          jdbc.update(
              "DELETE FROM "
                  + table(store)
                  + "_embedding_failures f USING "
                  + table(store)
                  + " s WHERE "
                  + link(store, "f")
                  + " AND "
                  + identity(key)
                  + " AND f.slot=? AND f.space_id=?",
              append(keyArguments(key), target.slot().stored(), target.space().id()));
          return true;
        });
  }

  private static Object[] append(List<Object> values, Object... rest) {
    var result = new ArrayList<>(values);
    result.addAll(Arrays.asList(rest));
    return result.toArray();
  }

  private void copy(EmbeddingProfile target, Store store, String scope, Object... extra) {
    String slot = target.slot().stored();
    jdbc.update(
        "UPDATE "
            + table(store)
            + " s SET "
            + slot
            + "_embedding=staged.embedding,"
            + slot
            + "_space_id=staged.space_id,"
            + slot
            + "_source_revision=staged.source_revision"
            + " FROM "
            + table(store)
            + "_embedding_staging staged WHERE "
            + link(store, "staged")
            + " AND staged.slot=? AND staged.space_id=? AND staged.source_revision=s.embedding_source_revision AND "
            + eligible(store)
            + scope,
        append(List.of(target.slot().stored(), target.space().id()), extra));
  }

  @Override
  public void failed(EmbeddingProfile target, Source expected, String category) {
    if (category == null || !category.matches("[A-Za-z][A-Za-z0-9]{0,79}"))
      throw new IllegalArgumentException("invalid failure category");
    transactions.inTransaction(
        () -> {
          lock(target.slot(), false);
          if (!target.equals(target(target.slot()).orElse(null))
              && !target.equals(active(target.slot()).orElse(null))) return false;
          Key key = expected.key();
          Store store = key.store();
          var match =
              jdbc.queryForList(
                  "SELECT s.embedding_source_revision FROM "
                      + table(store)
                      + " s WHERE "
                      + identity(key)
                      + " AND s.embedding_source_revision=? FOR SHARE",
                  append(keyArguments(key), expected.revision()));
          if (match.isEmpty()) return false;
          String failures = table(store) + "_embedding_failures";
          jdbc.update(
              "INSERT INTO "
                  + failures
                  + "("
                  + keys(store)
                  + ",slot,space_id,source_revision,category,retry_at) VALUES("
                  + "?,".repeat(keyArguments(key).size())
                  + "?,?,?,?,CURRENT_TIMESTAMP + INTERVAL '30 seconds')"
                  + " ON CONFLICT("
                  + keys(store)
                  + ",slot,space_id) DO UPDATE SET source_revision=excluded.source_revision,category=excluded.category,"
                  + " attempts=CASE WHEN "
                  + failures
                  + ".source_revision=excluded.source_revision THEN "
                  + failures
                  + ".attempts+1 ELSE 1 END,"
                  + " retry_at=CURRENT_TIMESTAMP + least(INTERVAL '1 hour',INTERVAL '30 seconds' * power(2,least("
                  + failures
                  + ".attempts,7)))",
              append(
                  keyArguments(key),
                  target.slot().stored(),
                  target.space().id(),
                  expected.revision(),
                  category));
          return true;
        });
  }

  private boolean indexReady(EmbeddingProfile profile, Store store) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relname=? AND n.nspname=current_schema() AND i.indisvalid AND i.indisready)",
            Boolean.class,
            new EmbeddingIndexPlan(profile).indexName(store)));
  }

  private void indexes(EmbeddingProfile profile) {
    var plan = new EmbeddingIndexPlan(profile);
    if (plan.exact()) return;
    for (Store store : Store.values()) {
      if (indexReady(profile, store)) continue;
      jdbc.execute("DROP INDEX IF EXISTS " + plan.indexName(store));
      Long rows =
          jdbc.queryForObject(
              "SELECT count(*) FROM "
                  + table(store)
                  + " WHERE "
                  + profile.slot().stored()
                  + "_space_id=?",
              Long.class,
              profile.space().id());
      int lists =
          (int)
              Math.min(
                  32768,
                  Math.max(1, rows == null ? 1 : rows < 1000000 ? rows / 1000 : Math.sqrt(rows)));
      jdbc.execute(plan.createIndex(store, table(store), lists));
    }
  }

  @Override
  public boolean activate(EmbeddingProfile target) {
    return transactions.inTransaction(
        () -> {
          lock(target.slot(), true);
          if (!target.equals(target(target.slot()).orElse(null))) return false;
          var active = active(target.slot());
          if (active.isPresent()
              && active.get().space().equals(target.space())
              && active.get().search().equals(target.search())) {
            indexes(target);
            return true;
          }
          // Source writes do not acquire slot locks. Table locks make the all-store coverage check
          // authoritative, including inserts/deletes, and keep publication/index training atomic.
          for (Store store : Store.values())
            jdbc.execute("LOCK TABLE " + table(store) + " IN SHARE ROW EXCLUSIVE MODE");
          for (Store store : Store.values()) {
            Long missing =
                jdbc.queryForObject(
                    "SELECT count(*) FROM "
                        + table(store)
                        + " s WHERE "
                        + eligible(store)
                        + " AND NOT ("
                        + ready(target, store)
                        + ")",
                    Long.class,
                    target.slot().stored(),
                    target.space().id());
            if (missing == null || missing != 0) return false;
          }
          for (Store store : Store.values()) copy(target, store, "");
          indexes(target);
          int changed =
              jdbc.update(
                  "UPDATE embedding_slots SET active_space_id=?,active_mode=?,active_distance=? WHERE slot=? AND version=?",
                  target.space().id(),
                  target.search().mode().configurationName(),
                  target.search().distance().name(),
                  target.slot().stored(),
                  target.version());
          if (changed != 1)
            throw new IllegalStateException("embedding activation lost its version fence");
          return true;
        });
  }

  @Override
  public <T> T read(EmbeddingProfile expected, Supplier<T> work) {
    Objects.requireNonNull(work);
    return transactions.inTransaction(
        () -> {
          lock(expected.slot(), false);
          if (!expected.equals(active(expected.slot()).orElse(null)))
            throw new EmbeddingException(
                "embedding configuration changed during this query; submit a new search");
          if (!new EmbeddingIndexPlan(expected).exact())
            for (Store store : Store.values())
              if (!indexReady(expected, store))
                throw new EmbeddingException(
                    "configured embedding index is unavailable; rebuild it before searching");
          return work.get();
        });
  }
}
