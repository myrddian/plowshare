package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.protocol.Home;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns scope registration and orchestration linkage used for paid-call attribution. */
@Repository
public class JdbcUsageScopeRepository implements UsageScopeRepository {
  private final JdbcTemplate jdbc;

  public JdbcUsageScopeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String project(Home home) {
    Objects.requireNonNull(home);
    return home.isGlobal()
        ? null
        : jdbc.queryForObject(
            "INSERT INTO projects(name) VALUES (?) ON CONFLICT(name) DO UPDATE SET name=EXCLUDED.name RETURNING id::text",
            String.class,
            home.project());
  }

  @Override
  public String conductorConversation(String orchestration) {
    Objects.requireNonNull(orchestration);
    return jdbc.queryForObject(
        "SELECT conductor_conversation FROM orchestrations WHERE id=?",
        String.class,
        orchestration);
  }
}
