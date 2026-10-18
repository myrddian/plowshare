package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.EventsProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

/** Discovery and lease-fenced acknowledgements for human notifications, independent of handlers. */
public final class JdbcRelayApprovalInboxRepository implements RelayApprovalInboxRepository {
  private static final String MISSING =
      """
      a.state='asked' AND (NOT EXISTS (SELECT 1 FROM user_inbox i
        WHERE i.kind='approval' AND i.about='approval:'||a.id
          AND i.handle=COALESCE(a.handle,(SELECT c.owner_handle FROM conversations c
            WHERE c.id=a.conversation)))
        OR EXISTS (SELECT 1 FROM user_inbox i
          WHERE i.kind='approval' AND i.about='approval:'||a.id
            AND i.handle=COALESCE(a.handle,(SELECT c.owner_handle FROM conversations c
              WHERE c.id=a.conversation))
            AND i.read_at IS NULL AND i.settled_at IS NULL
            AND (i.question_alert_attempted_at IS NULL
              OR i.question_alert_attempted_at<=?)))
      """;
  private static final String STALE =
      """
      a.state<>'asked' AND EXISTS(SELECT 1 FROM user_inbox i
        WHERE i.kind='approval' AND i.about='approval:'||a.id AND i.settled_at IS NULL)
      """;
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final Relay relay;
  private final RelayConsumerRepository consumers;
  private final Duration alertRetry;
  private final Supplier<Instant> clock;

  public JdbcRelayApprovalInboxRepository(
      JdbcTemplate jdbc, UnitOfWork transactions, Relay relay, RelayConsumerRepository consumers) {
    this(jdbc, transactions, relay, consumers, EventsProperties.DEFAULT_QUESTION_ALERT_RETRY);
  }

  public JdbcRelayApprovalInboxRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      Relay relay,
      RelayConsumerRepository consumers,
      Duration alertRetry) {
    this(jdbc, transactions, relay, consumers, alertRetry, Instant::now);
  }

  public JdbcRelayApprovalInboxRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      Relay relay,
      RelayConsumerRepository consumers,
      Duration alertRetry,
      Supplier<Instant> clock) {
    if (alertRetry == null || alertRetry.isNegative() || alertRetry.isZero())
      throw new IllegalArgumentException("Positive question alert interval required");
    this.alertRetry = alertRetry;
    this.clock = Objects.requireNonNull(clock);
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    this.relay = Objects.requireNonNull(relay);
    this.consumers = Objects.requireNonNull(consumers);
  }

  @Override
  public List<Relay.TopicKey> ready(String after, int limit) {
    RelayValues.limit(limit, 256);
    if (after != null && (after.length() > 128 || after.indexOf('\0') >= 0))
      throw new IllegalArgumentException("Invalid approval notification cursor");
    return jdbc.query(
        """
        SELECT t.scope_key,t.name FROM relay_topics t
        LEFT JOIN relay_subscriptions s ON s.scope_key=t.scope_key AND s.topic=t.name
          AND s.subscriber=?
        WHERE t.name IN ('approval.requested','approval.resolved') AND t.scope_key LIKE 'project:%'
          AND (CAST(? AS text) IS NULL OR t.scope_key||'/'||t.name>?)
          AND (s.subscriber IS NULL OR s.seen_through<t.last_position OR EXISTS
            (SELECT 1 FROM run_approvals a WHERE 'project:'||a.project_id::text=t.scope_key AND
        """
            + " ((t.name='approval.requested' AND "
            + MISSING
            + ") OR (t.name='approval.resolved' AND "
            + STALE
            + ")))) ORDER BY t.scope_key,t.name LIMIT ?",
        (row, index) ->
            new Relay.TopicKey(
                RelayScopeCodec.read(row.getString("scope_key")), row.getString("name")),
        SUBSCRIBER,
        after,
        after,
        Timestamp.from(clock.get().minus(alertRetry)),
        limit);
  }

  @Override
  public List<String> missing(long projectId, String after, int limit) {
    if (projectId < 1) throw new IllegalArgumentException("Project required");
    RelayValues.limit(limit, 1000);
    return jdbc.queryForList(
        "SELECT a.id FROM run_approvals a WHERE a.project_id=? AND "
            + MISSING
            + " AND (CAST(? AS text) IS NULL OR a.id>?) ORDER BY a.id LIMIT ?",
        String.class,
        projectId,
        Timestamp.from(clock.get().minus(alertRetry)),
        after,
        after,
        limit);
  }

  @Override
  public List<String> unsettledNotices(long projectId, String after, int limit) {
    if (projectId < 1) throw new IllegalArgumentException("Project required");
    RelayValues.limit(limit, 1000);
    return jdbc.queryForList(
        "SELECT a.id FROM run_approvals a WHERE a.project_id=? AND "
            + STALE
            + " AND (CAST(? AS text) IS NULL OR a.id>?) ORDER BY a.id LIMIT ?",
        String.class,
        projectId,
        after,
        after,
        limit);
  }

  private long project(RelayConsumerRepository.Lease lease) {
    if (!SUBSCRIBER.equals(lease.subscription().subscriber())
        || !List.of("approval.requested", "approval.resolved")
            .contains(lease.subscription().topic().name())
        || !(lease.subscription().topic().scope() instanceof Relay.ProjectScope scope))
      throw new IllegalArgumentException("Not an approval Inbox subscription");
    return scope.projectId();
  }

  @Override
  public void advance(RelayConsumerRepository.Lease lease, long position) {
    project(lease);
    transactions.inTransaction(
        () -> {
          consumers.requireOwned(lease);
          relay.advanceSeen(lease.subscription(), position);
          consumers.requireOwned(lease);
          return true;
        });
  }

  @Override
  public void recoverGap(RelayConsumerRepository.Lease lease, long boundary) {
    long project = project(lease);
    transactions.inTransaction(
        () -> {
          consumers.requireOwned(lease);
          jdbc.update(
              """
          INSERT INTO relay_approval_gap_recoveries(scope_key,topic,subscriber,expired_through,pending_approvals)
          SELECT ?,?,?,?,count(*) FROM run_approvals WHERE project_id=? AND state='asked'
          ON CONFLICT DO NOTHING
          """,
              RelayScopeCodec.write(lease.subscription().topic()),
              lease.subscription().topic().name(),
              SUBSCRIBER,
              boundary,
              project);
          relay.acknowledgeGap(lease.subscription(), boundary);
          consumers.requireOwned(lease);
          return true;
        });
  }

  @Override
  public int prune(Duration retention, int limit) {
    if (retention == null || retention.isNegative() || retention.isZero())
      throw new IllegalArgumentException("Positive audit retention required");
    RelayValues.limit(limit, 1000);
    return jdbc.update(
        """
        DELETE FROM relay_approval_gap_recoveries WHERE (scope_key,topic,subscriber,expired_through) IN
          (SELECT scope_key,topic,subscriber,expired_through FROM relay_approval_gap_recoveries
           WHERE recovered_at<clock_timestamp()-(? * interval '1 second')
           ORDER BY recovered_at LIMIT ?)
        """,
        retention.toSeconds(),
        limit);
  }
}
