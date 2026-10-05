package io.aeyer.plowshare.server.auth;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Database-only readiness probe. Startup readiness remains the controller's responsibility. */
@Repository
public class JdbcDatabaseReadiness implements DatabaseReadiness {
  private final JdbcTemplate jdbc;

  public JdbcDatabaseReadiness(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean available() {
    try {
      return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
    } catch (DataAccessException unavailable) {
      return false;
    }
  }
}
