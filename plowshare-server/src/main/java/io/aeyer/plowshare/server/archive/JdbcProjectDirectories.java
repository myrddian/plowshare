package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reuses archive project identity semantics, including the race-safe registration upsert. */
@Repository
public class JdbcProjectDirectories implements ProjectDirectories {
  private final JdbcTemplate jdbc;

  public JdbcProjectDirectories(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Long existing(Home home) {
    return ProjectIds.forDirectory(jdbc, Objects.requireNonNull(home));
  }

  @Override
  public Long register(Home home) {
    return ProjectIds.toWrite(jdbc, Objects.requireNonNull(home));
  }
}
