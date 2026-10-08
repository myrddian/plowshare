package io.aeyer.plowshare.server.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.ApplicationDeployment.*;
import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Project row locks serialize activation, including the first deployment and source adoption. */
public final class JdbcApplicationDeploymentStore implements ApplicationDeploymentStore {
  private final JdbcTemplate jdbc;
  private final UnitOfWork work;
  private final ServerProjects projects;
  private final io.aeyer.plowshare.server.events.ScheduleDefinitionStore schedules;

  public JdbcApplicationDeploymentStore(
      JdbcTemplate jdbc,
      UnitOfWork work,
      ServerProjects projects,
      io.aeyer.plowshare.server.events.ScheduleDefinitionStore schedules) {
    this.jdbc = jdbc;
    this.work = work;
    this.projects = projects;
    this.schedules = schedules;
  }

  @Override
  public Optional<Receipt> receipt(
      String account, String project, UUID requestId, String fingerprint) {
    Objects.requireNonNull(requestId);
    var found =
        jdbc.query(
            "SELECT r.account, r.fingerprint, p.name, s.revision, s.digest, s.file_count "
                + "FROM application_deployment_receipts r JOIN projects p ON p.id = r.project_id "
                + "JOIN application_releases s ON s.revision = r.revision WHERE r.request_id = ?",
            (row, n) -> {
              if (!account.equals(row.getString("account"))
                  || !project.equals(row.getString("name"))
                  || fingerprint != null && !fingerprint.equals(row.getString("fingerprint")))
                throw new CallerFault(
                    "Deployment request UUID already belongs to a different submission");
              return new Receipt(
                  requestId,
                  project,
                  new Release(
                      row.getObject("revision", UUID.class),
                      row.getString("digest"),
                      row.getInt("file_count")));
            },
            requestId);
    return found.stream().findFirst();
  }

  @Override
  public Retained release(String project, UUID revision) {
    return jdbc
        .query(
            "SELECT s.* FROM application_releases s JOIN projects p ON p.id = s.project_id "
                + "WHERE p.name = ? AND s.revision = ?",
            (row, n) ->
                new Retained(
                    new Release(
                        row.getObject("revision", UUID.class),
                        row.getString("digest"),
                        row.getInt("file_count")),
                    destination(row.getString("destination")),
                    ApplicationPlacementCodec.decode(row.getString("placement"))),
            project,
            revision)
        .stream()
        .findFirst()
        .orElseThrow(() -> new CallerFault("Choose a retained Application revision"));
  }

  @Override
  public Receipt commit(String account, Mutation mutation) {
    if (!mutation.fingerprint().matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid deployment fingerprint");
    return work.inTransaction(
        () -> {
          Long id = ProjectIds.toWrite(jdbc, Home.of(mutation.project()));
          jdbc.queryForObject("SELECT id FROM projects WHERE id = ? FOR UPDATE", Long.class, id);
          var prior =
              receipt(account, mutation.project(), mutation.requestId(), mutation.fingerprint());
          if (prior.isPresent()) return prior.get();
          List<UUID> heads =
              jdbc.queryForList(
                  "SELECT revision FROM application_deployment_heads WHERE project_id = ?",
                  UUID.class,
                  id);
          UUID active = heads.isEmpty() ? null : heads.getFirst();
          if (!Objects.equals(active, mutation.expectedRevision()))
            throw new CallerFault(
                "Application revision changed; read deployment status before submitting a new request");
          var release = mutation.release();
          if (active != null) {
            var previous = release(mutation.project(), active);
            if (!previous.destination().equals(release.destination())
                || !previous
                    .placement()
                    .writableAreas()
                    .equals(release.placement().writableAreas()))
              throw new CallerFault(
                  "Deployment updates preserve destination and admitted writable areas");
          }
          // The owning repository updates only source placement. The enclosing transaction retains
          // membership, memories and in-flight records, and rolls back every database effect on
          // failure.
          projects.activateDeployment(
              mutation.project(),
              release.placement(),
              active == null ? null : release(mutation.project(), active).placement(),
              mutation.verifiedRoot(),
              account);
          if (mutation.install()) {
            jdbc.update(
                "INSERT INTO application_releases (revision, project_id, digest, file_count, destination, placement) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)",
                release.release().revision(),
                id,
                release.release().digest(),
                release.release().fileCount(),
                encode(release.destination()),
                ApplicationPlacementCodec.encode(release.placement()));
          } else {
            if (!release.equals(release(mutation.project(), release.release().revision())))
              throw new CallerFault("Retained Application revision changed");
          }
          jdbc.update(
              "INSERT INTO application_deployment_heads (project_id, revision) VALUES (?, ?) ON CONFLICT (project_id) DO UPDATE SET revision = EXCLUDED.revision",
              id,
              release.release().revision());
          jdbc.update(
              "INSERT INTO application_deployment_receipts (request_id, account, project_id, fingerprint, revision) VALUES (?, ?, ?, ?, ?)",
              mutation.requestId(),
              account,
              id,
              mutation.fingerprint(),
              release.release().revision());
          if (active == null) schedules.register(account, id, "server");
          return new Receipt(mutation.requestId(), mutation.project(), release.release());
        });
  }

  @Override
  public Status status(String project) {
    // One SQL snapshot pairs the head with its catalog. The active release is always included,
    // even when more than one hundred historical revisions exist.
    record Row(UUID active, Release release) {}
    var rows =
        jdbc.query(
            "SELECT h.revision AS active, s.revision, s.digest, s.file_count FROM projects p LEFT JOIN application_deployment_heads h ON h.project_id = p.id LEFT JOIN application_releases s ON s.project_id = p.id WHERE p.name = ? ORDER BY (s.revision = h.revision) DESC NULLS LAST, s.created_at DESC, s.revision LIMIT 100",
            (row, n) ->
                new Row(
                    row.getObject("active", UUID.class),
                    row.getObject("revision", UUID.class) == null
                        ? null
                        : new Release(
                            row.getObject("revision", UUID.class),
                            row.getString("digest"),
                            row.getInt("file_count"))),
            project);
    return new Status(
        project,
        rows.isEmpty() ? null : rows.getFirst().active(),
        rows.stream().map(Row::release).filter(Objects::nonNull).toList());
  }

  private static String encode(FileStoreReference value) {
    try {
      return new ObjectMapper().writeValueAsString(value);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("Cannot encode destination", invalid);
    }
  }

  private static FileStoreReference destination(String text) {
    try {
      var node = new ObjectMapper().readTree(text);
      if (!node.isObject()
          || node.size() != 2
          || !node.path("store").isTextual()
          || !node.path("path").isTextual())
        throw new IllegalArgumentException("Invalid destination");
      return new FileStoreReference(node.get("store").textValue(), node.get("path").textValue());
    } catch (java.io.IOException | IllegalArgumentException invalid) {
      throw new IllegalStateException("Invalid stored deployment destination", invalid);
    }
  }
}
