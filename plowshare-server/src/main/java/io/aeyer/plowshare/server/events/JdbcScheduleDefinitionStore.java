package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.InformationJson;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** One transaction projects an authenticated file into the existing durable scheduling runtime. */
public final class JdbcScheduleDefinitionStore implements ScheduleDefinitionStore {
  private final JdbcTemplate jdbc;
  private final UnitOfWork work;
  private final ScheduleStore schedules;
  private final TriggerStore triggers;
  private final FiringStore firings;
  private final ProjectionCache<Optional<FileSnapshot>> fileCache;
  private final ProjectionCache<List<ScheduledWork.File>> filesCache;

  public JdbcScheduleDefinitionStore(
      JdbcTemplate jdbc,
      UnitOfWork work,
      ScheduleStore schedules,
      TriggerStore triggers,
      FiringStore firings) {
    this(
        jdbc,
        work,
        schedules,
        triggers,
        firings,
        ProjectionCache.direct(),
        ProjectionCache.direct());
  }

  public JdbcScheduleDefinitionStore(
      JdbcTemplate jdbc,
      UnitOfWork work,
      ScheduleStore schedules,
      TriggerStore triggers,
      FiringStore firings,
      ProjectionCache<Optional<FileSnapshot>> fileCache,
      ProjectionCache<List<ScheduledWork.File>> filesCache) {
    this.jdbc = jdbc;
    this.work = work;
    this.schedules = schedules;
    this.triggers = triggers;
    this.firings = firings;
    this.fileCache = java.util.Objects.requireNonNull(fileCache);
    this.filesCache = java.util.Objects.requireNonNull(filesCache);
  }

  private static final String SOURCE_COLUMNS =
      "s.id,s.account,s.project_id,p.name AS project,s.source";
  private static final String SOURCE_QUERY =
      "SELECT "
          + SOURCE_COLUMNS
          + " FROM schedule_sources s LEFT JOIN projects p ON p.id=s.project_id";
  private static final String FILE_COLUMNS =
      "name,internal_name,definition,paused_override,status,error";

  private static Source source(java.sql.ResultSet row, int index) throws java.sql.SQLException {
    return new Source(
        row.getLong("id"),
        row.getString("account"),
        row.getObject("project_id", Long.class),
        row.getString("project"),
        row.getString("source"));
  }

  public Source register(String account, Long projectId, String source) {
    if (account == null
        || account.isBlank()
        || account.length() > 256
        || !List.of("server", "workspace").contains(source)
        || projectId != null && projectId < 1)
      throw new IllegalArgumentException("Invalid schedule source registration");
    return work.inTransaction(
        () -> {
          jdbc.update(
              "INSERT INTO schedule_sources(account,project_id,source) VALUES (?,?,?) ON CONFLICT DO NOTHING",
              account,
              projectId,
              source);
          var found =
              jdbc
                  .query(
                      SOURCE_QUERY + " WHERE s.project_id IS NOT DISTINCT FROM ? AND s.source=?",
                      JdbcScheduleDefinitionStore::source,
                      projectId,
                      source)
                  .stream()
                  .findFirst()
                  .orElseThrow();
          if (!found.account().equals(account))
            throw new CallerFault("This schedule folder is registered to another account");
          return found;
        });
  }

  public List<Source> sources() {
    return jdbc.query(SOURCE_QUERY + " ORDER BY s.id", JdbcScheduleDefinitionStore::source);
  }

  public List<ScheduledWork.File> files(Source source) {
    if (!filesCache.enabled()) return directFiles(source);
    var currentSource = readSource(source);
    if (currentSource.isEmpty()) return List.of();
    var current = currentSource.orElseThrow();
    long maximum = projectionBound(filesCache.maxWeight(), current);
    return filesCache.read(
        readKey(current, "files"),
        maximum > 0,
        () -> currentFiles(current, null, maximum).stream().map(FileSnapshot::effective).toList(),
        () -> currentFiles(current, null, null).stream().map(FileSnapshot::effective).toList(),
        JdbcScheduleDefinitionStore::filesWeight);
  }

  private List<ScheduledWork.File> directFiles(Source source) {
    return jdbc
        .query(
            "SELECT " + FILE_COLUMNS + " FROM schedule_files WHERE source_id=? ORDER BY name",
            (row, index) -> snapshot(source, row),
            source.id())
        .stream()
        .map(FileSnapshot::effective)
        .toList();
  }

  public Optional<FileSnapshot> snapshot(Source source, String name) {
    new FileIdentity(source.id(), name);
    if (!fileCache.enabled()) return directSnapshot(source, name);
    var currentSource = readSource(source);
    if (currentSource.isEmpty()) return Optional.empty();
    var current = currentSource.orElseThrow();
    long maximum = projectionBound(fileCache.maxWeight(), current);
    return fileCache.read(
        readKey(current, "file:" + name),
        maximum > 0,
        () -> {
          var loaded = currentFiles(current, name, maximum);
          if (loaded.isEmpty()) throw new ProjectionCache.Bypass();
          return loaded.stream().findFirst();
        },
        () -> currentFiles(current, name, null).stream().findFirst(),
        loaded -> loaded.map(value -> fileWeight(value.effective())).orElse(64L));
  }

  private Optional<FileSnapshot> directSnapshot(Source source, String name) {
    return jdbc
        .query(
            "SELECT " + FILE_COLUMNS + " FROM schedule_files WHERE source_id=? AND name=?",
            (row, index) -> snapshot(source, row),
            source.id(),
            name)
        .stream()
        .findFirst();
  }

  private static final int MAX_CACHED_FILES = 256;
  private static final String FILE_BYTES =
      "128::bigint+COALESCE(octet_length(f.definition::text),0)+octet_length(f.name)"
          + "+octet_length(f.internal_name)+octet_length(f.status)+COALESCE(octet_length(f.error),0)";
  private static final String SOURCE_FROM =
      " FROM schedule_sources s LEFT JOIN projects p ON p.id=s.project_id";
  private static final String CURRENT_SOURCE =
      "s.id=? AND s.account=? AND s.source=? AND s.project_id IS NOT DISTINCT FROM ?";

  /**
   * Refresh only the source address and ownership. Warm reads must not scan or serialize file
   * definitions merely to confirm a cache hit; durable versions fence their complete membership.
   * Count/byte guards run only in the bounded cold loader below.
   */
  private Optional<Source> readSource(Source source) {
    return jdbc
        .query(
            SOURCE_QUERY + " WHERE " + CURRENT_SOURCE,
            JdbcScheduleDefinitionStore::source,
            sourceArguments(source).toArray())
        .stream()
        .findFirst();
  }

  /**
   * Configured readers use current source fields, not an earlier caller-held source label. The
   * bounded left join returns only a scalar sentinel when any definition/collection grew: no
   * oversized definition reaches the row mapper. Fallback retains the full original result.
   */
  private List<FileSnapshot> currentFiles(Source source, String name, Long maximumBytes) {
    var args = sourceArguments(source);
    if (name != null) args.add(name);
    String selected =
        "SELECT "
            + SOURCE_COLUMNS
            + ",f.name,f.internal_name,f.definition,f.paused_override,f.status,f.error"
            + (maximumBytes == null ? "" : "," + FILE_BYTES + " AS file_bytes")
            + SOURCE_FROM
            + " JOIN schedule_files f ON f.source_id=s.id WHERE "
            + CURRENT_SOURCE
            + (name == null ? "" : " AND f.name=?");
    if (maximumBytes == null)
      return jdbc.query(
          selected + " ORDER BY f.name",
          (row, index) -> snapshot(source(row, index), row),
          args.toArray());
    args.add(MAX_CACHED_FILES);
    args.add(maximumBytes);
    return jdbc
        .query(
            "WITH retained AS MATERIALIZED ("
                + selected
                + "), bounds AS (SELECT count(*) AS total_files,"
                + "COALESCE(sum(file_bytes),0) AS projection_bytes FROM retained) SELECT bounds.total_files,"
                + "bounds.projection_bytes,retained.* FROM bounds LEFT JOIN retained ON bounds.total_files<=?"
                + " AND bounds.projection_bytes<=? ORDER BY retained.name",
            (row, index) -> {
              if (row.getLong("total_files") > MAX_CACHED_FILES
                  || row.getLong("projection_bytes") > maximumBytes)
                throw new ProjectionCache.Bypass();
              if (row.getLong("total_files") == 0) return Optional.<FileSnapshot>empty();
              return Optional.of(snapshot(source(row, index), row));
            },
            args.toArray())
        .stream()
        .flatMap(Optional::stream)
        .toList();
  }

  private static java.util.ArrayList<Object> sourceArguments(Source source) {
    return new java.util.ArrayList<>(
        java.util.Arrays.asList(
            source.id(), source.account(), source.source(), source.projectId()));
  }

  private static SnapshotCache.Key readKey(Source source, String projection) {
    return new SnapshotCache.Key(
        source.id() + ":" + projection, ProjectionCache.fingerprint(InformationJson.json(source)));
  }

  private static long projectionBound(long maximumWeight, Source source) {
    long available = maximumWeight - (4096L + 4L * InformationJson.json(source).length());
    return available <= 0 ? 0 : available / 64;
  }

  private static long fileWeight(ScheduledWork.File file) {
    return 2048L
        + 4L
            * (file.name().length()
                + file.path().length()
                + file.internalName().length()
                + file.source().length()
                + (file.project() == null ? 0 : file.project().length())
                + (file.error() == null ? 0 : file.error().length())
                + (file.definition() == null
                    ? 0
                    : ScheduleDefinitionCodec.write(file.definition()).length()));
  }

  private static long filesWeight(List<ScheduledWork.File> files) {
    long weight = 4096;
    for (var file : files) weight += fileWeight(file);
    return weight;
  }

  private static FileSnapshot snapshot(Source source, java.sql.ResultSet row)
      throws java.sql.SQLException {
    String definition = row.getString("definition");
    return new FileSnapshot(
        source,
        row.getString("name"),
        row.getString("internal_name"),
        definition == null ? null : ScheduleDefinitionCodec.read(definition),
        row.getObject("paused_override", Boolean.class),
        row.getString("status"),
        row.getString("error"));
  }

  public Optional<Source> sourceOf(String name, String account) {
    return jdbc
        .query(
            SOURCE_QUERY
                + " JOIN schedule_files f ON f.source_id=s.id WHERE f.internal_name=? AND s.account=?",
            JdbcScheduleDefinitionStore::source,
            name,
            account)
        .stream()
        .findFirst();
  }

  public Optional<Source> sourceOf(String name) {
    return jdbc
        .query(
            SOURCE_QUERY + " JOIN schedule_files f ON f.source_id=s.id WHERE f.internal_name=?",
            JdbcScheduleDefinitionStore::source,
            name)
        .stream()
        .findFirst();
  }

  public boolean requiresDefinition(String internalName, String account) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM schedules WHERE name=? AND defined_by=? AND file_managed)",
            Boolean.class,
            internalName,
            account));
  }

  public Optional<ScheduledWork.File> managed(String name, String account) {
    return jdbc
        .query(
            "SELECT "
                + SOURCE_COLUMNS
                + ",f.name,f.internal_name,f.definition,f.paused_override,f.status,f.error"
                + " FROM schedule_sources s LEFT JOIN projects p ON p.id=s.project_id"
                + " JOIN schedule_files f ON f.source_id=s.id WHERE f.internal_name=? AND s.account=?",
            (row, index) -> snapshot(source(row, index), row).effective(),
            name,
            account)
        .stream()
        .findFirst();
  }

  public ScheduledWork.File apply(
      Source source, String name, ScheduledWork definition, Instant now) {
    ScheduledWork.identity(name, "file name");
    if (name.startsWith(".")) throw new IllegalArgumentException("Invalid schedule file name");
    java.util.Objects.requireNonNull(definition);
    java.util.Objects.requireNonNull(now);
    return work.inTransaction(
        () -> {
          lock(source);
          String internal = "scheduled-" + source.id() + "-" + name;
          // Compare immutable source content, not its operational pause projection.
          var old = directSnapshot(source, name);
          if (old.isEmpty()
              && (schedules.find(internal).isPresent() || triggers.find(internal).isPresent()))
            throw new CallerFault("The internal schedule name is already in use");
          if (old.isPresent()
              && definition.equals(old.get().rawDefinition())
              && old.get().status().equals("active")
              && triggers
                  .find(internal)
                  .filter(
                      t ->
                          java.util.Objects.equals(
                              t.project(),
                              definition.target().kind().equals("conversation")
                                  ? null
                                  : definition.target().project() == null
                                      ? source.project()
                                      : definition.target().project()))
                  .isPresent()) return old.get().effective();
          boolean unchanged = old.filter(f -> definition.equals(f.rawDefinition())).isPresent();
          var projected = unchanged ? old.orElseThrow().effectiveDefinition() : definition;
          var timing = CronSchedule.parse(definition.cron(), definition.zone());
          if (old.filter(f -> f.status().equals("refused")).isPresent()
              || schedules
                  .find(internal)
                  .filter(
                      s -> s.cron().equals(definition.cron()) && s.zone().equals(definition.zone()))
                  .isEmpty()) schedules.define(internal, timing, internal, source.account(), now);
          jdbc.update(
              "UPDATE schedules SET file_managed=TRUE WHERE name=? AND defined_by=?",
              internal,
              source.account());
          schedules.pause(internal, projected.paused(), source.account());
          var target = definition.target();
          var limits = definition.limits();
          triggers.define(
              new TriggerRecord(
                  internal,
                  internal,
                  target.kind().equals("conversation")
                      ? null
                      : target.project() == null ? source.project() : target.project(),
                  target.conversation(),
                  definition.action().agent(),
                  definition.action().utterance(),
                  limits.maxModelCalls(),
                  limits.maxTurns(),
                  limits.queueCap(),
                  projected.paused(),
                  source.account()));
          if (projected.paused()) firings.refuseWaiting(internal, "schedule file paused");
          // RETURNING observes the persisted override decision, including equality of JSONB on
          // conflict. Publish only through the enclosing transaction's existing commit boundary.
          return jdbc.queryForObject(
              "INSERT INTO schedule_files(source_id,name,internal_name,definition,status) VALUES (?,?,?,?::jsonb,'active') ON CONFLICT(source_id,name) DO UPDATE SET definition=EXCLUDED.definition,paused_override=CASE WHEN schedule_files.definition=EXCLUDED.definition THEN schedule_files.paused_override ELSE NULL END,status='active',error=NULL RETURNING "
                  + FILE_COLUMNS,
              (row, index) -> snapshot(source, row).effective(),
              source.id(),
              name,
              internal,
              ScheduleDefinitionCodec.write(definition));
        });
  }

  public void reject(Source source, String name, String error) {
    FileSnapshot.validateError(error);
    work.inTransaction(
        () -> {
          lock(source);
          String internal = "scheduled-" + source.id() + "-" + name;
          if (schedules.find(internal).isPresent())
            schedules.pause(internal, true, source.account());
          if (triggers.find(internal).isPresent()) triggers.pause(internal, true, source.account());
          firings.refuseWaiting(internal, "schedule source unavailable or invalid");
          jdbc.update(
              "INSERT INTO schedule_files(source_id,name,internal_name,status,error) VALUES (?,?,?,'refused',?) ON CONFLICT(source_id,name) DO UPDATE SET status='refused',error=EXCLUDED.error",
              source.id(),
              name,
              internal,
              error);
          return null;
        });
  }

  public void remove(Source source, String name) {
    work.inTransaction(
        () -> {
          lock(source);
          String internal = "scheduled-" + source.id() + "-" + name;
          firings.refuseWaiting(internal, "schedule file removed");
          if (triggers.find(internal).isPresent()) triggers.forget(internal, source.account());
          if (schedules.find(internal).isPresent()) schedules.forget(internal, source.account());
          jdbc.update("DELETE FROM schedule_files WHERE source_id=? AND name=?", source.id(), name);
          return null;
        });
  }

  public void pause(
      Source source, String name, ScheduledWork expected, boolean paused, Instant now) {
    ScheduledWork.identity(name, "file name");
    java.util.Objects.requireNonNull(expected);
    java.util.Objects.requireNonNull(now);
    work.inTransaction(
        () -> {
          lock(source);
          var file =
              directSnapshot(source, name)
                  .orElseThrow(() -> new CallerFault("The schedule file was removed"))
                  .effective();
          if (!file.status().equals("active") || !expected.equals(file.definition()))
            throw new CallerFault(
                "Schedule changed; inspect its current state before controlling it");
          var schedule =
              schedules
                  .find(file.internalName())
                  .orElseThrow(() -> new CallerFault("The schedule runtime is unavailable"));
          if (triggers.find(file.internalName()).isEmpty())
            throw new CallerFault("The schedule trigger is unavailable");
          if (!paused && schedule.paused())
            schedules.define(
                file.internalName(),
                CronSchedule.parse(expected.cron(), expected.zone()),
                file.internalName(),
                source.account(),
                now);
          schedules.pause(file.internalName(), paused, source.account());
          triggers.pause(file.internalName(), paused, source.account());
          if (paused) firings.refuseWaiting(file.internalName(), "schedule paused by operator");
          jdbc.update(
              "UPDATE schedule_files SET paused_override=? WHERE source_id=? AND name=?",
              paused,
              source.id(),
              name);
          return null;
        });
  }

  private void lock(Source source) {
    if (jdbc.queryForList(
                "SELECT id FROM schedule_sources WHERE id=? AND account=? FOR UPDATE",
                Long.class,
                source.id(),
                source.account())
            .size()
        != 1) throw new CallerFault("The schedule folder registration is unavailable");
  }
}
