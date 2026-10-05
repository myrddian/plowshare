package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
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

  public JdbcScheduleDefinitionStore(
      JdbcTemplate jdbc,
      UnitOfWork work,
      ScheduleStore schedules,
      TriggerStore triggers,
      FiringStore firings) {
    this.jdbc = jdbc;
    this.work = work;
    this.schedules = schedules;
    this.triggers = triggers;
    this.firings = firings;
  }

  private static final String SOURCE_QUERY =
      "SELECT s.id,s.account,s.project_id,p.name AS project,s.source FROM schedule_sources s LEFT JOIN projects p ON p.id=s.project_id";

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
    return jdbc.query(
        "SELECT name,internal_name,definition,status,error FROM schedule_files WHERE source_id=? ORDER BY name",
        (r, i) ->
            new ScheduledWork.File(
                r.getString("name"),
                source.project(),
                source.source(),
                path(source, r.getString("name")),
                r.getString("internal_name"),
                r.getString("definition") == null
                    ? null
                    : ScheduleDefinitionCodec.read(r.getString("definition")),
                r.getString("status"),
                r.getString("error")),
        source.id());
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

  public boolean requiresDefinition(String internalName, String account) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM schedules WHERE name=? AND defined_by=? AND file_managed)",
            Boolean.class,
            internalName,
            account));
  }

  public Optional<ScheduledWork.File> managed(String name, String account) {
    return sourceOf(name, account)
        .flatMap(s -> files(s).stream().filter(f -> f.internalName().equals(name)).findFirst());
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
          var old = files(source).stream().filter(f -> f.name().equals(name)).findFirst();
          if (old.isEmpty()
              && (schedules.find(internal).isPresent() || triggers.find(internal).isPresent()))
            throw new CallerFault("The internal schedule name is already in use");
          if (old.isPresent()
              && definition.equals(old.get().definition())
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
                  .isPresent()) return old.get();
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
          schedules.pause(internal, definition.paused(), source.account());
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
                  definition.paused(),
                  source.account()));
          if (definition.paused()) firings.refuseWaiting(internal, "schedule file paused");
          jdbc.update(
              "INSERT INTO schedule_files(source_id,name,internal_name,definition,status) VALUES (?,?,?,?::jsonb,'active') ON CONFLICT(source_id,name) DO UPDATE SET definition=EXCLUDED.definition,status='active',error=NULL",
              source.id(),
              name,
              internal,
              ScheduleDefinitionCodec.write(definition));
          return files(source).stream()
              .filter(f -> f.name().equals(name))
              .findFirst()
              .orElseThrow();
        });
  }

  public void reject(Source source, String name, String error) {
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

  private void lock(Source source) {
    if (jdbc.queryForList(
                "SELECT id FROM schedule_sources WHERE id=? AND account=? FOR UPDATE",
                source.id(),
                source.account())
            .size()
        != 1) throw new CallerFault("The schedule folder registration is unavailable");
  }

  private static String path(Source source, String name) {
    return (source.source().equals("workspace") ? ".plowshare/" : "")
        + "schedules/"
        + name
        + ".json";
  }
}
