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
    if (home.isGlobal()) return null;
    var existing =
        jdbc.queryForList(
            "SELECT id::text FROM projects WHERE name=? FOR KEY SHARE",
            String.class,
            home.project());
    if (!existing.isEmpty()) return existing.getFirst();
    var inserted =
        jdbc.queryForList(
            "INSERT INTO projects(name) VALUES (?) ON CONFLICT(name) DO NOTHING RETURNING id::text",
            String.class,
            home.project());
    if (!inserted.isEmpty()) return inserted.getFirst();
    // A competing registration may commit while the INSERT waits. Read its identity afresh;
    // never update an unchanged project solely to obtain its ID and invalidate unrelated data.
    return jdbc.queryForObject(
        "SELECT id::text FROM projects WHERE name=? FOR KEY SHARE", String.class, home.project());
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
