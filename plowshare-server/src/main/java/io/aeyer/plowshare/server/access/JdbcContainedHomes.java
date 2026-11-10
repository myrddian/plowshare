package io.aeyer.plowshare.server.access;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link ContainedHomes} answered by {@code contained_own_home} (V229), which is the same predicate
 * the knowledge read and write arms resolve ownership with.
 *
 * <p>Keyed on that function rather than on anything this class decides for itself, so the service
 * gates and the SQL arms below them cannot disagree about which principal owns which home. The
 * function is itself gated on {@code contained_knowledge_actor}, so a non-UUID handle, an ordinary
 * account, a foreign project and a revoked principal all return NULL — and this reads NULL as "owns
 * nothing" rather than as an error.
 *
 * <p>A failed query propagates. Unavailable authority is not permission, and this is the only place
 * that distinction can be preserved for the second path.
 */
public final class JdbcContainedHomes implements ContainedHomes {
  private final JdbcTemplate jdbc;

  public JdbcContainedHomes(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public boolean ownsHome(String actingPrincipal, String project) {
    // Blank in, false out, before the database is touched: an ordinary caller has no contained
    // principal to offer and must not pay a query to be told so.
    if (actingPrincipal == null
        || actingPrincipal.isBlank()
        || project == null
        || project.isBlank()) return false;
    return jdbc.queryForObject(
            "SELECT contained_own_home(?,?)", UUID.class, actingPrincipal, project)
        != null;
  }
}
