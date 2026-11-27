package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Direct reads of the current topic row so retired and replaced feeds cannot match a stale read.
 */
final class JdbcRelayTopicRegistry implements RelayTopicRegistry {
  private static final String SELECT =
      "SELECT scope_key,name,generation,payload_kind,topic_owner,goal_feed FROM relay_topics";
  private static final String ELIGIBLE = "(topic_owner='GENERAL_PURPOSE' OR goal_feed)";
  private final JdbcTemplate jdbc;
  private final SqlWorkBudget sql;
  private final Duration ceiling;

  JdbcRelayTopicRegistry(JdbcTemplate jdbc, SqlWorkBudget sql, SqlWorkBudgetProperties budgets) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.sql = Objects.requireNonNull(sql);
    this.ceiling = budgets.queryCeiling();
  }

  @Override
  public Optional<Source> find(Relay.TopicKey key) {
    Objects.requireNonNull(key);
    return sql.within(
        ceiling,
        () ->
            jdbc
                .query(
                    SELECT + " WHERE scope_key=? AND name=?",
                    JdbcRelayTopicRegistry::source,
                    RelayScopeCodec.write(key),
                    key.name())
                .stream()
                .findFirst());
  }

  @Override
  public Optional<Source> eligible(Relay.TopicKey key, UUID generation) {
    return eligible(key, generation, "");
  }

  @Override
  public Optional<Source> lockEligible(Relay.TopicKey key, UUID generation) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException("Relay source binding requires a write transaction");
    // Relay.subscribe takes this lock too. Take it up front rather than upgrade a shared
    // lock while another goal is concurrently binding the same feed.
    return eligible(key, generation, " FOR UPDATE");
  }

  private Optional<Source> eligible(Relay.TopicKey key, UUID generation, String locking) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(generation);
    return sql.within(
        ceiling,
        () ->
            jdbc
                .query(
                    SELECT
                        + " WHERE scope_key=? AND name=? AND generation=? AND "
                        + ELIGIBLE
                        + locking,
                    JdbcRelayTopicRegistry::source,
                    RelayScopeCodec.write(key),
                    key.name(),
                    generation)
                .stream()
                .findFirst());
  }

  @Override
  public List<Source> eligible(
      Relay.Scope scope, String prefix, RelayPayload.Kind kind, String after, int limit) {
    Objects.requireNonNull(scope);
    RelayValues.limit(limit, 100);
    if (after != null) RelayValues.topic(after);
    String namePrefix = prefix == null ? "%" : checkedPrefix(prefix);
    String family = kind == null ? null : kind.name();
    return sql.within(
        ceiling,
        () ->
            List.copyOf(
                jdbc.query(
                    SELECT
                        + " WHERE scope_key=? AND "
                        + ELIGIBLE
                        + " AND name>? AND name LIKE ? ESCAPE '!'"
                        + " AND (?::text IS NULL OR payload_kind=?) ORDER BY name LIMIT ?",
                    JdbcRelayTopicRegistry::source,
                    RelayScopeCodec.write(scope),
                    after == null ? "" : after,
                    namePrefix,
                    family,
                    family,
                    limit)));
  }

  private static String checkedPrefix(String prefix) {
    boolean delimiter = prefix.endsWith(".") || prefix.endsWith("_") || prefix.endsWith("-");
    RelayValues.topic(delimiter ? prefix.substring(0, prefix.length() - 1) : prefix);
    if (prefix.length() > RelayPort.MAX_TOPIC_LENGTH)
      throw new IllegalArgumentException("Relay topic prefix is too long");
    return prefix.replace("_", "!_") + "%";
  }

  private static Source source(ResultSet row, int index) throws SQLException {
    return new Source(
        new Relay.TopicKey(RelayScopeCodec.read(row.getString("scope_key")), row.getString("name")),
        row.getObject("generation", UUID.class),
        RelayPayload.Kind.valueOf(row.getString("payload_kind")),
        ownership(row));
  }

  static RelayTopicOwnership ownership(ResultSet row) throws SQLException {
    return new RelayTopicOwnership(
        RelayTopicOwnership.Owner.valueOf(row.getString("topic_owner")),
        row.getBoolean("goal_feed"));
  }
}
