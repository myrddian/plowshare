package io.aeyer.plowshare.server.personal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns account/project queries and the lock preventing concurrent filesystem initialization. */
@Repository
public class JdbcPersonalSpaceRepository implements PersonalSpaceRepository {
  private final JdbcTemplate jdbc;

  public JdbcPersonalSpaceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<String> owner(long id) {
    return jdbc
        .queryForList(
            "SELECT personal_owner FROM projects WHERE id = ? AND personal_owner IS NOT NULL",
            String.class,
            id)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Long> id(String handle) {
    if (handle == null) return Optional.empty();
    return jdbc
        .queryForList("SELECT id FROM projects WHERE personal_owner = ?", Long.class, handle)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Long> projectId(String project) {
    return jdbc
        .queryForList(
            "SELECT id FROM projects WHERE name = ? AND personal_owner IS NOT NULL",
            Long.class,
            project)
        .stream()
        .findFirst();
  }

  @Override
  public List<String> accounts() {
    return jdbc.queryForList(
        "SELECT handle FROM admins WHERE NOT bootstrap AND account_kind='USER' ORDER BY handle",
        String.class);
  }

  @Override
  public boolean serviceAccount(String handle) {
    return !jdbc.queryForList(
            "SELECT 1 FROM admins WHERE handle=? AND account_kind<>'USER'", Integer.class, handle)
        .isEmpty();
  }

  @Override
  public Reserved reserve(String handle) {
    Objects.requireNonNull(handle);
    if (serviceAccount(handle))
      throw new IllegalArgumentException("Service accounts have no Personal space");
    jdbc.update(
        "INSERT INTO projects(name, personal_owner) VALUES (?, ?) ON CONFLICT (personal_owner) DO NOTHING",
        PersonalSpaces.name(handle),
        handle);
    return jdbc.queryForObject(
        "SELECT id,union_since IS NOT NULL AS initialized FROM projects WHERE personal_owner = ? FOR UPDATE",
        (row, n) -> new Reserved(row.getLong(1), row.getBoolean(2)),
        handle);
  }

  @Override
  public void initialized(long project, String handle) {
    if (jdbc.update(
            "UPDATE projects SET workspace = ?, union_since = COALESCE(union_since, now()) WHERE id = ? AND personal_owner=?",
            "/personal",
            project,
            handle)
        != 1) throw new IllegalStateException("Personal identity changed during initialization");
    jdbc.update(
        "INSERT INTO project_members(project_id, handle,role) VALUES (?, ?, 'MANAGER') ON CONFLICT DO NOTHING",
        project,
        handle);
  }
}
