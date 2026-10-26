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
  private io.aeyer.plowshare.server.reads.ReadAdmission admission =
      io.aeyer.plowshare.server.reads.ReadAdmission.direct();

  public JdbcEmbeddingWorkRepository useAdmission(
      io.aeyer.plowshare.server.reads.ReadAdmission admission) {
    this.admission = Objects.requireNonNull(admission);
    return this;
  }

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
    return switch (store) {
      case MEMORIES -> "s.state IN ('active','cold') AND information_memory_safe(s.id)";
      case DIGESTS -> "information_digest_safe(s.id)";
      case DOCUMENTS -> "s.summary IS NOT NULL";
      default -> "true";
    };
  }

  // Global maintenance is automatic work. Explicit owner repairs use source()/publish() only
  // inside the information worker's selected-stage lease and its live authority fences.
  private static String automaticInformation(Store store) {
    return switch (store) {
      case CHUNKS ->
          " AND NOT EXISTS(SELECT 1 FROM information_revisions information_revision WHERE information_revision.id=parent.document_id AND information_revision.processing_policy='explicit_only')";
      case DOCUMENTS ->
          " AND NOT EXISTS(SELECT 1 FROM information_revisions information_revision WHERE information_revision.id=s.id AND information_revision.processing_policy='explicit_only')";
      default -> "";
    };
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
          long version =
              Objects.requireNonNull(
                  jdbc.queryForObject(
                      "UPDATE embedding_slots SET target_space_id=?,target_mode=?,target_distance=? WHERE slot=? RETURNING version",
                      Long.class,
                      space.id(),
                      policy.mode().configurationName(),
                      policy.distance().name(),
                      slot.stored()));
          // The slot row is still exclusively locked. The supplied immutable descriptor/policy
          // are exactly what was written; V115 supplies the actual incremented slot version.
          return new EmbeddingProfile(slot, space, policy, version);
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

  private static String queueIdentity(Store store, String queue) {
    return store == Store.PASSAGES
        ? queue
            + ".source_type=s.source_type AND "
            + queue
            + ".source_id=s.source_id AND "
            + queue
            + ".position=s.position"
        : "s.id="
            + (store == Store.CHUNKS || store == Store.DOCUMENTS
                ? "CAST(" + queue + ".source_id AS uuid)"
                : queue + ".source_id");
  }

  private String missing(EmbeddingProfile target, Store store) {
    return eligible(store)
        + automaticInformation(store)
        + " AND NOT ("
        + ready(target, store)
        + ") AND NOT EXISTS(SELECT 1 FROM "
        + table(store)
        + "_embedding_failures f WHERE "
        + link(store, "f")
        + " AND f.slot=? AND f.space_id=? AND f.source_revision=s.embedding_source_revision AND f.retry_at>CURRENT_TIMESTAMP)";
  }

  private record ScanPage(int count, String type, String id, Integer position) {}

  private record ScanCursor(String type, String id, Integer position) {}

  @Override
  public boolean discoveryDue() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM embedding_repair_work WHERE available_at<=CURRENT_TIMESTAMP)"
                + " OR EXISTS(SELECT 1 FROM embedding_repair_scans WHERE next_at<=CURRENT_TIMESTAMP)",
            Boolean.class));
  }

  @Override
  public boolean activationCandidate(EmbeddingProfile profile) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT NOT EXISTS(SELECT 1 FROM embedding_repair_scans WHERE slot=? AND space_id=? AND NOT enumerated)"
                + " AND NOT EXISTS(SELECT 1 FROM embedding_repair_work WHERE slot=? AND space_id=?)",
            Boolean.class,
            profile.slot().stored(),
            profile.space().id(),
            profile.slot().stored(),
            profile.space().id()));
  }

  /**
   * Recovery walks at most 1,000 primary keys per call, once per profile/store across replicas.
   * Source triggers enqueue late commits atomically; this cursor is never a commit watermark. Once
   * caught up, reconciliation sleeps five minutes. It also catches eligibility-only edits without
   * turning every idle worker tick into an all-corpus anti-join.
   */
  private void reconcile(EmbeddingProfile target, Store store) {
    String name = table(store);
    var scans =
        jdbc.query(
            "SELECT source_type,source_id,position FROM embedding_repair_scans WHERE slot=? AND space_id=? AND store=? AND next_at<=CURRENT_TIMESTAMP FOR UPDATE SKIP LOCKED",
            (row, n) ->
                new ScanCursor(
                    row.getString("source_type"),
                    row.getString("source_id"),
                    (Integer) row.getObject("position")),
            target.slot().stored(),
            target.space().id(),
            name);
    if (scans.isEmpty()) return;
    var cursor = scans.getFirst();
    String id = cursor.id();
    // Keep the initial and continuation statements distinct. A nullable OR cursor prevents
    // generic prepared plans from seeking directly to the primary-key boundary and turns a full
    // reconciliation into repeated prefix scans as the corpus grows.
    String selection =
        id == null
            ? "true"
            : store == Store.PASSAGES
                ? "(s.source_type,s.source_id,s.position)>(?,?,?)"
                : "s.id>CAST(? AS "
                    + (store == Store.CHUNKS || store == Store.DOCUMENTS ? "uuid" : "text")
                    + ")";
    String order = store == Store.PASSAGES ? "s.source_type,s.source_id,s.position" : "s.id";
    String columns = store == Store.PASSAGES ? "s.source_type,s.source_id,s.position" : "s.id";
    String pageJoin =
        store == Store.PASSAGES
            ? "page.source_type=s.source_type AND page.source_id=s.source_id AND page.position=s.position"
            : "page.id=s.id";
    var args = new ArrayList<Object>();
    if (id != null) {
      if (store == Store.PASSAGES) args.addAll(Arrays.asList(cursor.type(), id, cursor.position()));
      else args.add(id);
    }
    args.addAll(List.of(target.slot().stored(), target.space().id(), name));
    args.addAll(
        List.of(
            target.slot().stored(),
            target.space().id(),
            target.slot().stored(),
            target.space().id()));
    String last =
        store == Store.PASSAGES ? "source_type DESC,source_id DESC,position DESC" : "id DESC";
    ScanPage page =
        jdbc.queryForObject(
            "WITH page AS MATERIALIZED (SELECT "
                + columns
                + " FROM "
                + name
                + " s WHERE "
                + selection
                + " ORDER BY "
                + order
                + " LIMIT 1000),"
                + " queued AS (INSERT INTO embedding_repair_work(slot,space_id,store,source_type,source_id,position,source_revision) SELECT ?,?,?,"
                + (store == Store.PASSAGES
                    ? "s.source_type,s.source_id,s.position"
                    : "'',s.id::text,0")
                + ",s.embedding_source_revision FROM page JOIN "
                + name
                + " s ON "
                + pageJoin
                + joins(store)
                + " WHERE "
                + missing(target, store)
                + " ON CONFLICT DO NOTHING RETURNING 1) SELECT (SELECT count(*) FROM page) AS scanned,"
                + (store == Store.PASSAGES
                    ? "(SELECT source_type FROM page ORDER BY " + last + " LIMIT 1)"
                    : "NULL::text")
                + " AS source_type,"
                + "(SELECT "
                + (store == Store.PASSAGES ? "source_id" : "id::text")
                + " FROM page ORDER BY "
                + last
                + " LIMIT 1) AS source_id,"
                + (store == Store.PASSAGES
                    ? "(SELECT position FROM page ORDER BY " + last + " LIMIT 1)"
                    : "NULL::integer")
                + " AS position",
            (row, n) ->
                new ScanPage(
                    row.getInt("scanned"),
                    row.getString("source_type"),
                    row.getString("source_id"),
                    (Integer) row.getObject("position")),
            args.toArray());
    Objects.requireNonNull(page);
    boolean done = page.count() < 1000;
    jdbc.update(
        "UPDATE embedding_repair_scans SET source_type=?,source_id=?,position=?,enumerated=enumerated OR ?,next_at=CURRENT_TIMESTAMP + CASE WHEN ? THEN INTERVAL '5 minutes' ELSE INTERVAL '0 seconds' END WHERE slot=? AND space_id=? AND store=?",
        done ? null : page.type(),
        done ? null : page.id(),
        done ? null : page.position(),
        done,
        done,
        target.slot().stored(),
        target.space().id(),
        name);
  }

  @Override
  public List<Source> pending(EmbeddingProfile target, Store store, int limit) {
    return pending(target, store, limit, false);
  }

  @Override
  public List<Source> claimPending(EmbeddingProfile target, Store store, int limit) {
    return admission.read(
        "embedding-repair",
        io.aeyer.plowshare.server.reads.ReadAdmission.Work.MAINTENANCE,
        () -> pending(target, store, limit, true));
  }

  private List<Source> pending(EmbeddingProfile target, Store store, int limit, boolean claim) {
    Objects.requireNonNull(target);
    Objects.requireNonNull(store);
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("embedding repair batch must be 1..100");
    return transactions.inTransaction(
        () -> {
          // Scope compilation to short discovery SQL. Provider calls and activation/index DDL
          // run outside this transaction. Global planner configuration stays unchanged.
          return discovery(
              () -> {
                // Match source DDL/TRUNCATE order before taking cursor or queue row locks.
                // ACCESS SHARE permits normal source writers and holds no provider connection.
                jdbc.execute("LOCK TABLE " + table(store) + " IN ACCESS SHARE MODE");
                reconcile(target, store);
                String name = table(store);
                var keys =
                    jdbc.query(
                        "SELECT source_type,source_id,position FROM embedding_repair_work WHERE slot=? AND space_id=? AND store=? AND available_at<=CURRENT_TIMESTAMP ORDER BY available_at,source_type,source_id,position LIMIT ?"
                            + " FOR UPDATE SKIP LOCKED",
                        (row, n) ->
                            new Key(
                                store,
                                row.getString("source_id"),
                                store == Store.PASSAGES ? row.getString("source_type") : null,
                                row.getInt("position")),
                        target.slot().stored(),
                        target.space().id(),
                        name,
                        limit);
                if (keys.isEmpty()) return List.of();
                // Hydrate only the bounded reserved keys, in one statement. Casting the key
                // to the source's UUID type preserves primary-key seeks; casting the indexed
                // source column to text would reintroduce a corpus scan for every batch.
                var arguments = queueKeyArguments(keys);
                arguments.addAll(List.of(target.slot().stored(), target.space().id(), name));
                arguments.addAll(
                    List.of(
                        target.slot().stored(),
                        target.space().id(),
                        target.slot().stored(),
                        target.space().id()));
                var found =
                    jdbc.query(
                        "WITH picked(source_type,source_id,position) AS MATERIALIZED (VALUES "
                            + queueKeyValues(keys)
                            + ") SELECT "
                            + sourceColumns(store)
                            + " FROM picked JOIN "
                            + name
                            + " s ON "
                            + queueIdentity(store, "picked")
                            + joins(store)
                            + " JOIN embedding_repair_work queued ON "
                            + queueIdentity(store, "queued")
                            + " AND queued.source_revision=s.embedding_source_revision WHERE queued.slot=? AND queued.space_id=? AND queued.store=? AND "
                            + missing(target, store),
                        sourceMapper(store),
                        arguments.toArray());
                var matched =
                    found.stream().map(Source::key).collect(java.util.stream.Collectors.toSet());
                updateQueue(
                    target,
                    store,
                    keys.stream().filter(key -> !matched.contains(key)).toList(),
                    true);
                if (claim)
                  updateQueue(target, store, found.stream().map(Source::key).toList(), false);
                return List.copyOf(found);
              });
        });
  }

  private static String queueKeyValues(List<Key> keys) {
    return String.join(",", Collections.nCopies(keys.size(), "(?::text,?::text,?::integer)"));
  }

  private static ArrayList<Object> queueKeyArguments(List<Key> keys) {
    var args = new ArrayList<Object>();
    for (var key : keys)
      args.addAll(
          List.of(key.sourceType() == null ? "" : key.sourceType(), key.id(), key.position()));
    return args;
  }

  /**
   * Rows remain locked until the discovery TX ends; stale sources cannot overwrite a new enqueue.
   */
  private void updateQueue(EmbeddingProfile target, Store store, List<Key> keys, boolean discard) {
    if (keys.isEmpty()) return;
    var args = queueKeyArguments(keys);
    args.addAll(List.of(target.slot().stored(), target.space().id(), table(store)));
    jdbc.update(
        "WITH picked(source_type,source_id,position) AS (VALUES "
            + queueKeyValues(keys)
            + ") "
            + (discard
                ? "DELETE FROM embedding_repair_work queued USING picked"
                : "UPDATE embedding_repair_work queued SET available_at=CURRENT_TIMESTAMP + INTERVAL '5 minutes' FROM picked")
            + " WHERE queued.slot=? AND queued.space_id=? AND queued.store=? AND queued.source_type=picked.source_type AND queued.source_id=picked.source_id AND queued.position=picked.position",
        args.toArray());
  }

  private <T> T discovery(Supplier<T> action) {
    String[] settings =
        jdbc.queryForObject(
            "SELECT current_setting('jit'),current_setting('statement_timeout')",
            (row, n) -> new String[] {row.getString(1), row.getString(2)});
    jdbc.execute("SET LOCAL jit=off");
    jdbc.execute("SET LOCAL statement_timeout='2s'");
    // Restore on success even inside an existing caller transaction. On failure the owning unit
    // rolls back the transaction (including LOCAL settings); do not mask an aborted-TX error with
    // a second statement. Neither a pooled lease nor an enclosing activation inherits this policy.
    T result = action.get();
    jdbc.queryForObject(
        "SELECT set_config('jit',?,true),set_config('statement_timeout',?,true)",
        (row, n) -> row.getString(1),
        Objects.requireNonNull(settings)[0],
        settings[1]);
    return result;
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
  public boolean publish(EmbeddingProfile target, Source expected, float[] vector, Runnable fence) {
    return transactions.inTransaction(
        () -> {
          // Activation locks slot before source tables. The consumer fence must use that same
          // order when it freezes memory/privacy state for publication.
          lock(target.slot(), false);
          fence.run();
          return publish(target, expected, vector);
        });
  }

  @Override
  public boolean publishLegacy(Source expected, float[] vector, Runnable fence) {
    if (expected.key().store() != Store.MEMORIES && expected.key().store() != Store.DIGESTS)
      throw new IllegalArgumentException("Unsupported legacy repair store");
    if (vector == null || vector.length != 768)
      throw new IllegalArgumentException("Invalid legacy embedding width");
    double magnitude = 0;
    for (float value : vector) {
      if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite embedding");
      magnitude += (double) value * value;
    }
    if (magnitude == 0) throw new IllegalArgumentException("Zero embedding");
    return transactions.inTransaction(
        () -> {
          fence.run();
          if (!source(expected.key()).filter(expected::equals).isPresent()) return false;
          int written =
              jdbc.update(
                  "UPDATE "
                      + table(expected.key().store())
                      + " s SET embedding=CAST(? AS vector) WHERE "
                      + identity(expected.key())
                      + " AND s.embedding_source_revision=? AND "
                      + eligible(expected.key().store()),
                  Arrays.toString(vector),
                  expected.key().id(),
                  expected.revision());
          fence.run();
          return written == 1;
        });
  }

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

  @Override
  public boolean beginRepair(EmbeddingProfile target, Source expected) {
    return transactions.inTransaction(
        () -> {
          lock(target.slot(), false);
          if (!target.equals(target(target.slot()).orElse(null))
              && !target.equals(active(target.slot()).orElse(null))) return false;
          if (!source(expected.key()).filter(expected::equals).isPresent()) return false;
          var store = expected.key().store();
          String failures = table(store) + "_embedding_failures";
          return jdbc.update(
                  "INSERT INTO "
                      + failures
                      + "("
                      + keys(store)
                      + ",slot,space_id,source_revision,category,retry_at) VALUES("
                      + "?,".repeat(keyArguments(expected.key()).size())
                      + "?,?,?,'RepairInFlight','infinity'::timestamptz) "
                      + "ON CONFLICT("
                      + keys(store)
                      + ",slot,space_id) DO UPDATE SET "
                      + "source_revision=excluded.source_revision,category=excluded.category,retry_at=excluded.retry_at "
                      + "WHERE "
                      + failures
                      + ".source_revision<>excluded.source_revision OR ("
                      + failures
                      + ".category<>'RepairInFlight' AND "
                      + failures
                      + ".retry_at<=CURRENT_TIMESTAMP)",
                  append(
                      keyArguments(expected.key()),
                      target.slot().stored(),
                      target.space().id(),
                      expected.revision()))
              == 1;
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
