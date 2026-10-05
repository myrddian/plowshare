package io.aeyer.plowshare.server.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Durable machine credentials. Execution identity carries the ceiling through queued work and
 * routing.
 */
public final class JdbcServiceCredentialsRepository implements ServiceCredentials {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public JdbcServiceCredentialsRepository(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    this.clock = java.util.Objects.requireNonNull(clock, "clock");
  }

  public Optional<TokenStore.Authentication> authentication(String presented) {
    if (!ServiceCredentials.credential(presented)
        || presented.length() > 4096
        || presented.codePoints().anyMatch(Character::isISOControl)) return Optional.empty();
    return jdbc
        .query(
            "SELECT t.principal_handle,a.session_version FROM service_tokens t"
                + " JOIN admins a ON a.handle=t.principal_handle WHERE t.digest=? AND t.expires_at>?"
                + " AND account_active(t.principal_handle)",
            (rs, n) -> new TokenStore.Authentication(rs.getString(1), false, rs.getLong(2)),
            Tokens.hash(presented),
            Timestamp.from(clock.instant()))
        .stream()
        .findFirst();
  }
}
