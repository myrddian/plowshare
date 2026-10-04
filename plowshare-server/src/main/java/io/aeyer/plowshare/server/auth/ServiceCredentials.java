package io.aeyer.plowshare.server.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Durable machine credentials. Execution identity carries the ceiling through queued work and
 * routing.
 */
public final class ServiceCredentials {
  public static final String PREFIX = "pss_";
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public ServiceCredentials(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public static boolean principal(String account) {
    return account != null && account.startsWith("@service/");
  }

  public static boolean credential(String presented) {
    return presented != null && presented.startsWith(PREFIX);
  }

  public Optional<TokenStore.Authentication> authentication(String presented) {
    if (!credential(presented)) return Optional.empty();
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
