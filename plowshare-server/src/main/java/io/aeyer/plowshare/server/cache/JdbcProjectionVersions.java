package io.aeyer.plowshare.server.cache;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
 *
 * <p>Item versions ({@link ProjectionItemVersions}) read {@code snapshot_cache_item_versions}
 * through the same independent leases: one primary-key probe per item, with the caller's own
 * transaction refused by the same eligibility guard as domain capture.
 */
public final class JdbcProjectionVersions implements ProjectionVersions, ProjectionItemVersions {
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

  // The item list is bound as parallel arrays; (domain,item) is the primary key of the table, so
  // each requested item is one index probe. Absent rows read as 0 (never written).
  private static final String ITEM_COUNTERS =
      """
      SELECT requested.domain,requested.item,coalesce(version.counter,0) AS counter,
        identity.database_id
      FROM unnest(?::text[],?::text[]) AS requested(domain,item)
      CROSS JOIN snapshot_cache_identity identity
      LEFT JOIN snapshot_cache_item_versions version
        ON version.domain=requested.domain AND version.item=requested.item
      WHERE identity.singleton
      """;

  private static final String ITEMS_CURRENT =
      """
      SELECT identity.database_id=?::uuid AND NOT EXISTS(
        SELECT 1 FROM unnest(?::text[],?::text[],?::bigint[]) AS expected(domain,item,counter)
        LEFT JOIN snapshot_cache_item_versions version
          ON version.domain=expected.domain AND version.item=expected.item
        WHERE coalesce(version.counter,0)<>expected.counter)
      FROM snapshot_cache_identity identity WHERE identity.singleton
      """;

  @Override
  public Optional<ItemToken> captureItems(Set<Item> items) {
    if (items == null || items.isEmpty())
      throw new IllegalArgumentException("Explicit projection items required");
    var selected = new TreeSet<Item>(ITEM_ORDER);
    for (Item item : items) {
      selected.add(Objects.requireNonNull(item));
      selected.add(Item.all(item.domain()));
    }
    if (selected.size() > MAX_ITEMS)
      throw new IllegalArgumentException("Too many projection items for one entry");
    Eligibility eligibility = eligibility();
    if (!eligibility.safe()) return Optional.empty();
    var domains = selected.stream().map(item -> domainName(item.domain())).toArray(String[]::new);
    var addresses = selected.stream().map(Item::address).toArray(String[]::new);
    var counters = new HashMap<Item, Long>();
    var databases = new HashSet<UUID>();
    authoritative.query(
        ITEM_COUNTERS,
        statement -> {
          statement.setArray(1, statement.getConnection().createArrayOf("text", domains));
          statement.setArray(2, statement.getConnection().createArrayOf("text", addresses));
        },
        row -> {
          var domain = Domain.valueOf(row.getString("domain").toUpperCase(Locale.ROOT));
          counters.put(new Item(domain, row.getString("item")), row.getLong("counter"));
          databases.add(row.getObject("database_id", UUID.class));
        });
    // A row per requested item is the statement's contract; anything else is not evidence.
    if (!counters.keySet().equals(selected) || databases.size() != 1)
      throw new IllegalStateException("Projection item versions unavailable");
    UUID database = databases.iterator().next();
    if (!database.equals(eligibility.database())) return Optional.empty();
    return Optional.of(new ItemToken(database, counters));
  }

  @Override
  public boolean currentItems(ItemToken token) {
    Objects.requireNonNull(token);
    var entries = new ArrayList<>(token.counters().entrySet());
    var domains = entries.stream().map(entry -> domainName(entry.getKey().domain())).toArray();
    var addresses = entries.stream().map(entry -> entry.getKey().address()).toArray();
    var counters = entries.stream().map(Map.Entry::getValue).toArray();
    return Boolean.TRUE.equals(
        authoritative.query(
            ITEMS_CURRENT,
            statement -> {
              statement.setObject(1, token.database());
              statement.setArray(2, statement.getConnection().createArrayOf("text", domains));
              statement.setArray(3, statement.getConnection().createArrayOf("text", addresses));
              statement.setArray(4, statement.getConnection().createArrayOf("bigint", counters));
            },
            (ResultSet row) -> row.next() ? row.getBoolean(1) : null));
  }

  // Every expected item whose committed counter differs, or every item when the database identity
  // differs. Each requested item is one primary-key probe; absent rows read as 0. A missing
  // identity row yields one NULL row, which the caller reads as "everything is stale".
  private static final String ITEMS_STALE =
      """
      SELECT expected.domain,expected.item
      FROM snapshot_cache_identity identity
      CROSS JOIN unnest(?::text[],?::text[],?::bigint[]) AS expected(domain,item,counter)
      LEFT JOIN snapshot_cache_item_versions version
        ON version.domain=expected.domain AND version.item=expected.item
      WHERE identity.singleton
        AND (identity.database_id<>?::uuid OR coalesce(version.counter,0)<>expected.counter)
      UNION ALL SELECT NULL,NULL
      WHERE NOT EXISTS(SELECT 1 FROM snapshot_cache_identity WHERE singleton)
      """;

  @Override
  public Set<Item> staleItems(Collection<ItemToken> tokens) {
    if (tokens == null || tokens.isEmpty())
      throw new IllegalArgumentException("Explicit projection item tokens required");
    var expected = new HashMap<Item, Long>();
    var stale = new HashSet<Item>();
    var databases = new HashSet<UUID>();
    for (var token : tokens) {
      databases.add(token.database());
      token
          .counters()
          .forEach(
              (item, counter) -> {
                Long previous = expected.putIfAbsent(item, counter);
                // Two captures of one item disagree: at least one of them is already outdated.
                if (previous != null && !previous.equals(counter)) stale.add(item);
              });
    }
    if (expected.size() > MAX_CONFIRMED_ITEMS)
      throw new IllegalArgumentException("Too many projection items to confirm at once");
    if (databases.size() != 1) return Set.copyOf(expected.keySet());
    var entries = new ArrayList<>(expected.entrySet());
    var domains = entries.stream().map(entry -> domainName(entry.getKey().domain())).toArray();
    var addresses = entries.stream().map(entry -> entry.getKey().address()).toArray();
    var counters = entries.stream().map(Map.Entry::getValue).toArray();
    UUID database = databases.iterator().next();
    authoritative.query(
        ITEMS_STALE,
        statement -> {
          statement.setArray(1, statement.getConnection().createArrayOf("text", domains));
          statement.setArray(2, statement.getConnection().createArrayOf("text", addresses));
          statement.setArray(3, statement.getConnection().createArrayOf("bigint", counters));
          statement.setObject(4, database);
        },
        row -> {
          if (row.getString("domain") == null) {
            stale.addAll(expected.keySet());
            return;
          }
          var domain = Domain.valueOf(row.getString("domain").toUpperCase(Locale.ROOT));
          stale.add(new Item(domain, row.getString("item")));
        });
    return Set.copyOf(stale);
  }

  private static final Comparator<Item> ITEM_ORDER =
      Comparator.comparing(Item::domain).thenComparing(Item::address);

  private static String domainName(Domain domain) {
    // Item domains come exclusively from the closed enum and are bound as values, never SQL text.
    return domain.name().toLowerCase(Locale.ROOT);
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
