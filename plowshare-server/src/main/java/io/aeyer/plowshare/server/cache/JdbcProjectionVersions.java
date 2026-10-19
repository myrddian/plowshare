package io.aeyer.plowshare.server.cache;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * PostgreSQL-only version repository. Independent autocommit leases deliberately bypass Spring's
 * caller-bound connection: a historical caller snapshot cannot establish current shared freshness.
 * The configured authoritative source must be a real pool/source, never a transaction-aware proxy.
 * Caller SQL is used only to refuse dirty, historical or expiry-spanning shared admission.
 *
 * <p>Spring synchronization may retain the independent autocommit lease until the caller
 * transaction completes. Freshness and the completed probe barrier are per statement, not
 * necessarily per new physical lease. The version pool is separately bounded and must account for
 * this occupancy.
 */
public final class JdbcProjectionVersions implements ProjectionVersions {
  private record Counters(UUID database, Map<Domain, Long> values) {}

  private record Pending(List<String> transactions, Instant start, Instant until) {}

  private record Eligibility(
      boolean safe, String callerTransaction, Instant transactionTime, UUID database) {}

  private static final String EXPIRY_WINDOW =
      """
      greatest(
        (SELECT expires_at FROM authority_grants WHERE expires_at<=moment.at ORDER BY expires_at DESC LIMIT 1),
        (SELECT expires_at FROM service_tokens WHERE expires_at<=moment.at ORDER BY expires_at DESC LIMIT 1)
      ) AS window_start,
      least(
        (SELECT expires_at FROM authority_grants WHERE expires_at>moment.at ORDER BY expires_at LIMIT 1),
        (SELECT expires_at FROM service_tokens WHERE expires_at>moment.at ORDER BY expires_at LIMIT 1)
      ) AS valid_until
      """;

  private final JdbcTemplate caller;
  private final JdbcTemplate authoritative;
  private final int maxPending;

  public JdbcProjectionVersions(JdbcTemplate caller, DataSource authoritative, int maxPending) {
    this.caller = Objects.requireNonNull(caller);
    Objects.requireNonNull(authoritative);
    if (authoritative instanceof TransactionAwareDataSourceProxy)
      throw new IllegalArgumentException(
          "Authoritative versions require an independent data source");
    if (maxPending < 1 || maxPending > 4096)
      throw new IllegalArgumentException("Invalid pending transaction capacity");
    this.maxPending = maxPending;
    this.authoritative = new JdbcTemplate(new FreshConnections(authoritative));
  }

  @Override
  public UUID databaseIdentity() {
    return Objects.requireNonNull(
        authoritative.queryForObject(
            "SELECT database_id FROM snapshot_cache_identity WHERE singleton", UUID.class));
  }

  @Override
  public Optional<Token> capture(Set<Domain> domains) {
    Set<Domain> selected = checked(domains);
    Eligibility eligibility = eligibility();
    if (!eligibility.safe()) return Optional.empty();
    // Do not combine these statements. The first reads counters then allocates/commits a probe XID.
    // Snapshot xmax follows completed XIDs; without that barrier an active tail writer might be
    // absent from xip even though its allocation was observed. Autocommit completes the probe
    // statement before the first JdbcTemplate call returns; the second gets a fresh snapshot even
    // if Spring synchronization retains/reuses the independent lease until the outer transaction
    // ends.
    Counters counters = captureCounters(selected);
    // Dedicated pool configuration must identify the caller's actual database/schema. A separate
    // but healthy database cannot fence this caller's mutations, even with equal numeric counters.
    if (!counters.database().equals(eligibility.database())) return Optional.empty();
    Pending pending =
        Objects.requireNonNull(
            authoritative.queryForObject(
                "WITH moment AS MATERIALIZED (SELECT clock_timestamp() AS at) SELECT "
                    + "ARRAY(SELECT xid::text FROM pg_snapshot_xip(pg_current_snapshot()) xid LIMIT ?) AS pending,"
                    + EXPIRY_WINDOW
                    + " FROM moment",
                (row, index) -> {
                  java.sql.Array array = row.getArray("pending");
                  try {
                    return new Pending(
                        List.of((String[]) array.getArray()),
                        instant(row.getObject("window_start", OffsetDateTime.class)),
                        instant(row.getObject("valid_until", OffsetDateTime.class)));
                  } finally {
                    array.free();
                  }
                },
                maxPending + 2));
    // An expiry may cross after the caller guard but before the authoritative window query. A
    // warm hit must not attach the new visibility window to SQL evaluated using an older now().
    // Check both interval ends: wall clock may also move backwards across an expiry boundary.
    if (pending.start() != null && eligibility.transactionTime().isBefore(pending.start())
        || pending.until() != null && !eligibility.transactionTime().isBefore(pending.until()))
      return Optional.empty();
    // One extra slot permits the explicitly safe caller-XID exception; a second is the overflow
    // sentinel, so filtering the caller can never hide omitted active transactions.
    List<String> transactions =
        pending.transactions().stream()
            .filter(id -> !id.equals(eligibility.callerTransaction()))
            .toList();
    if (transactions.size() > maxPending) return Optional.empty();
    return Optional.of(
        new Token(
            counters.database(),
            counters.values(),
            transactions,
            pending.start(),
            pending.until()));
  }

  @Override
  public boolean current(Token token) {
    Objects.requireNonNull(token);
    if (token.pendingTransactions().size() > maxPending) return false;
    var sql =
        new StringBuilder("WITH version AS MATERIALIZED (")
            .append(counterSql(token.counters().keySet()))
            .append("), moment AS MATERIALIZED (SELECT clock_timestamp() AS at FROM version) ")
            .append("SELECT database_id=?");
    var args = new ArrayList<Object>();
    args.add(token.database());
    for (Domain domain : Domain.values()) {
      if (!token.counters().containsKey(domain)) continue;
      sql.append(" AND ").append(domain.name().toLowerCase(java.util.Locale.ROOT)).append("=?");
      args.add(token.counters().get(domain));
    }
    sql.append(" AND (?::timestamptz IS NULL OR moment.at>=?::timestamptz)")
        .append(" AND (?::timestamptz IS NULL OR moment.at<?::timestamptz)")
        .append(" AND NOT EXISTS(SELECT 1 FROM unnest(?::text[]) xid")
        .append(" WHERE pg_xact_status(xid::xid8) IS DISTINCT FROM 'in progress'")
        .append(" AND pg_xact_status(xid::xid8) IS DISTINCT FROM 'aborted') FROM version,moment");
    args.add(offset(token.windowStart()));
    args.add(offset(token.windowStart()));
    args.add(offset(token.validUntil()));
    args.add(offset(token.validUntil()));
    args.add("{" + String.join(",", token.pendingTransactions()) + "}");
    // Counters never decrease and transaction outcomes are terminal. If all predicates hold,
    // they held together during this check even if a later commit races its completion. Unlike
    // capture, validation does not need a newer MVCC snapshot after the allocation observation.
    return Boolean.TRUE.equals(
        authoritative.queryForObject(sql.toString(), Boolean.class, args.toArray()));
  }

  @Override
  public boolean canShare() {
    return eligibility().safe();
  }

  private Eligibility eligibility() {
    return Objects.requireNonNull(
        caller.queryForObject(
            """
            WITH moment AS MATERIALIZED (SELECT clock_timestamp() AS at)
            SELECT current_setting('transaction_isolation')='read committed'
              AND current_setting('plowshare.cache_dirty',true) IS DISTINCT FROM 'on'
              AND NOT EXISTS(SELECT 1 FROM authority_grants,moment
                WHERE expires_at>transaction_timestamp() AND expires_at<=moment.at)
              AND NOT EXISTS(SELECT 1 FROM service_tokens,moment
                WHERE expires_at>transaction_timestamp() AND expires_at<=moment.at) AS safe,
              pg_current_xact_id_if_assigned()::text AS caller_transaction,
              transaction_timestamp() AS transaction_time,
              (SELECT database_id FROM snapshot_cache_identity WHERE singleton) AS caller_database
            """,
            (row, index) ->
                new Eligibility(
                    row.getBoolean("safe"),
                    row.getString("caller_transaction"),
                    row.getObject("transaction_time", OffsetDateTime.class).toInstant(),
                    row.getObject("caller_database", UUID.class))));
  }

  private Counters captureCounters(Set<Domain> domains) {
    return Objects.requireNonNull(
        authoritative.queryForObject(
            "SELECT database_id,documents,search,schedules,relay,transcripts,authority FROM snapshot_cache_capture_counters()",
            (row, index) -> {
              var values = new EnumMap<Domain, Long>(Domain.class);
              for (Domain domain : domains)
                values.put(domain, row.getLong(domain.name().toLowerCase(java.util.Locale.ROOT)));
              return new Counters(row.getObject("database_id", UUID.class), Map.copyOf(values));
            }));
  }

  private static String counterSql(Set<Domain> domains) {
    var sql = new StringBuilder("SELECT database_id");
    for (Domain domain : Domain.values()) {
      if (!domains.contains(domain)) continue;
      // Identifiers come exclusively from the closed enum, never a caller-supplied string.
      String name = domain.name().toLowerCase(java.util.Locale.ROOT);
      sql.append(", (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_")
          .append(name)
          .append(") AS ")
          .append(name);
    }
    sql.append(" FROM snapshot_cache_identity WHERE singleton");
    return sql.toString();
  }

  private static Set<Domain> checked(Set<Domain> domains) {
    if (domains == null || domains.isEmpty() || domains.size() > Domain.values().length)
      throw new IllegalArgumentException("Explicit projection domains required");
    return Set.copyOf(domains);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime offset(Instant value) {
    return value == null ? null : value.atOffset(java.time.ZoneOffset.UTC);
  }

  /** Unique Spring resource identity plus explicitly fresh, independent read-only leases. */
  private static final class FreshConnections extends AbstractDataSource {
    private final DataSource source;

    FreshConnections(DataSource source) {
      this.source = source;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return configure(source.getConnection());
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
      return configure(source.getConnection(user, password));
    }

    private Connection configure(Connection connection) throws SQLException {
      try {
        connection.setAutoCommit(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setReadOnly(true);
        return connection;
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.close();
        } catch (SQLException closing) {
          failure.addSuppressed(closing);
        }
        throw failure;
      }
    }
  }
}
