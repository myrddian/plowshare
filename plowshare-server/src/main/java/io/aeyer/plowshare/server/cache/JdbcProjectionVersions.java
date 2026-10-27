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
 * transaction completes. Freshness and the captured snapshot are per statement, not necessarily per
 * new physical lease. The version pool is separately bounded and must account for this occupancy.
 */
public final class JdbcProjectionVersions implements ProjectionVersions {
  private record Counters(UUID database, Map<Domain, Long> values) {}

  private record Captured(
      Counters counters, List<String> pending, String futureFrom, Instant start, Instant until) {}

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

  private static String expiryWindow(Set<Domain> domains) {
    if (!domains.contains(Domain.OBSERVATIONS) && !domains.contains(Domain.QUEUES))
      return EXPIRY_WINDOW;
    return EXPIRY_WINDOW
        .replace(
            "(SELECT expires_at FROM service_tokens WHERE expires_at<=moment.at ORDER BY expires_at DESC LIMIT 1)",
            "(SELECT expires_at FROM service_tokens WHERE expires_at<=moment.at ORDER BY expires_at DESC LIMIT 1),"
                + "(SELECT lease_until FROM information_steps WHERE state='running' AND lease_until IS NOT NULL AND lease_until<=moment.at ORDER BY lease_until DESC LIMIT 1)")
        .replace(
            "(SELECT expires_at FROM service_tokens WHERE expires_at>moment.at ORDER BY expires_at LIMIT 1)",
            "(SELECT expires_at FROM service_tokens WHERE expires_at>moment.at ORDER BY expires_at LIMIT 1),"
                + "(SELECT lease_until FROM information_steps WHERE state='running' AND lease_until IS NOT NULL AND lease_until>moment.at ORDER BY lease_until LIMIT 1)");
  }

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
    // Snapshot xmax is essential: an assigned tail writer may be absent from snapshot xip.
    // Relevant committed journal rows at/above xmax remain evidence even when counters stay flat.
    // The snapshot and counters need no completed reader probe, XID allocation or second checkout.
    Captured captured =
        Objects.requireNonNull(
            authoritative.queryForObject(
                "WITH moment AS MATERIALIZED (SELECT clock_timestamp() AS at,pg_current_snapshot() AS snapshot),"
                    + " version AS MATERIALIZED (SELECT * FROM snapshot_cache_capture_counters()) SELECT version.*,"
                    + " ARRAY(SELECT xid::text FROM pg_snapshot_xip(moment.snapshot) xid LIMIT ?) AS pending,"
                    + " pg_snapshot_xmax(moment.snapshot)::text AS future_from,"
                    + expiryWindow(selected)
                    + " FROM moment,version",
                (row, index) -> {
                  var values = new EnumMap<Domain, Long>(Domain.class);
                  for (Domain domain : selected)
                    values.put(
                        domain, row.getLong(domain.name().toLowerCase(java.util.Locale.ROOT)));
                  java.sql.Array array = row.getArray("pending");
                  try {
                    return new Captured(
                        new Counters(row.getObject("database_id", UUID.class), Map.copyOf(values)),
                        List.of((String[]) array.getArray()),
                        row.getString("future_from"),
                        instant(row.getObject("window_start", OffsetDateTime.class)),
                        instant(row.getObject("valid_until", OffsetDateTime.class)));
                  } finally {
                    array.free();
                  }
                },
                maxPending + 2));
    if (!captured.counters().database().equals(eligibility.database())) return Optional.empty();
    // Protect an outer READ COMMITTED transaction's now(), including expiry during capture and
    // backwards clock movement. Sharing cannot attach a new authority window to historical SQL.
    if (captured.start() != null && eligibility.transactionTime().isBefore(captured.start())
        || captured.until() != null && !eligibility.transactionTime().isBefore(captured.until()))
      return Optional.empty();
    var pending =
        captured.pending().stream()
            .filter(id -> !id.equals(eligibility.callerTransaction()))
            .toList();
    if (pending.size() > maxPending) return Optional.empty();
    return Optional.of(
        new Token(
            captured.counters().database(),
            captured.counters().values(),
            pending,
            captured.futureFrom(),
            captured.start(),
            captured.until()));
  }

  @Override
  public boolean current(Token token) {
    Objects.requireNonNull(token);
    if (token.pendingTransactions().size() > maxPending) return false;
    var sql =
        new StringBuilder("WITH version AS MATERIALIZED (")
            .append(counterSql(token.counters().keySet()))
            .append("), moment AS MATERIALIZED (SELECT clock_timestamp() AS at FROM version) ")
            .append("SELECT database_id=? AND writer_floor IS NOT NULL");
    var args = new ArrayList<Object>();
    args.add(token.database());
    for (Domain domain : Domain.values()) {
      if (!token.counters().containsKey(domain)) continue;
      sql.append(" AND ").append(domain.name().toLowerCase(java.util.Locale.ROOT)).append("=?");
      args.add(token.counters().get(domain));
    }
    sql.append(" AND (?::timestamptz IS NULL OR moment.at>=?::timestamptz)")
        .append(" AND (?::timestamptz IS NULL OR moment.at<?::timestamptz)")
        .append(" AND ?::xid8>writer_floor")
        .append(" AND NOT EXISTS(SELECT 1 FROM unnest(?::text[]) pending(xid)")
        .append(
            " WHERE pending.xid::xid8<=writer_floor OR pg_xact_status(pending.xid::xid8) IS NULL)")
        .append(" AND NOT EXISTS(SELECT 1 FROM snapshot_cache_writers writer")
        .append(" WHERE writer.domain=ANY(?::text[]) AND writer.xid>=?::xid8)")
        .append(" AND NOT EXISTS(SELECT 1 FROM snapshot_cache_writers writer")
        .append(
            " WHERE writer.domain=ANY(?::text[]) AND writer.xid=ANY(?::xid8[])) FROM version,moment");
    args.add(offset(token.windowStart()));
    args.add(offset(token.windowStart()));
    args.add(offset(token.validUntil()));
    args.add(offset(token.validUntil()));
    args.add(token.futureTransactionsFrom());
    String pending = "{" + String.join(",", token.pendingTransactions()) + "}";
    String domains =
        "{"
            + token.counters().keySet().stream()
                .map(domain -> domain.name().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(","))
            + "}";
    args.add(pending);
    args.add(domains);
    args.add(token.futureTransactionsFrom());
    args.add(domains);
    args.add(pending);
    // Writer evidence and its retention floor use this statement's single MVCC snapshot. An
    // unrelated/read-only commit cannot retire a value. Relevant commits after this snapshot
    // linearize after the read and are detected next time; missing/pruned evidence fails closed.
    // Counters remain conservative allocation hints, including during rollback.
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

  private static String counterSql(Set<Domain> domains) {
    var sql =
        new StringBuilder(
            "SELECT database_id, (SELECT through FROM snapshot_cache_writer_retention WHERE singleton) AS writer_floor");
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
