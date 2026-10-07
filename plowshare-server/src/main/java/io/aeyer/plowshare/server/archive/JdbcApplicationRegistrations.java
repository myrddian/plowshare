package io.aeyer.plowshare.server.archive;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores only application boundary adoption; account grants remain in deployed source. */
@Repository
public final class JdbcApplicationRegistrations implements ApplicationRegistrations {
  private final JdbcTemplate jdbc;

  public JdbcApplicationRegistrations(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean required(String project) {
    return ArchiveUnavailableException.translating(
        "read application boundary",
        () ->
            jdbc
                .queryForList(
                    "SELECT application_boundary FROM projects WHERE name = ?",
                    Boolean.class,
                    project)
                .stream()
                .anyMatch(Boolean.TRUE::equals));
  }

  public void require(String project) {
    ArchiveUnavailableException.translating(
        "register application boundary",
        () -> {
          if (jdbc.update("UPDATE projects SET application_boundary = TRUE WHERE name = ?", project)
              != 1)
            throw new ArchiveRefusedException("Application project is no longer registered");
          return Boolean.TRUE;
        });
  }
}
