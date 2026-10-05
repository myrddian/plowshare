package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.FiringStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Adapts the existing owning inbox, not a second queue. Seen positions acknowledge availability;
 * queued instructions and uncertain starts remain solely in FiringStore. Native start claims and
 * offset changes are fenced by the group lease, with no runtime calls under a database lock.
 */
public final class JdbcRelayNativeRepository implements RelayNativeRepository {
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final Relay relay;
  private final RelayConsumerRepository consumers;
  private final FiringStore firings;

  public JdbcRelayNativeRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      Relay relay,
      RelayConsumerRepository consumers,
      FiringStore firings) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    this.relay = Objects.requireNonNull(relay);
    this.consumers = Objects.requireNonNull(consumers);
    this.firings = Objects.requireNonNull(firings);
  }

  @Override
  public void bind(Relay.TopicKey topic, RelayPayload.WakeRequested wake) {
    requireTopic(topic);
    String expected =
        wake.type() == RelayPayload.WakeKind.MESSAGE
            ? "message.wake.requested"
            : "board.wake.requested";
    if (!topic.name().equals(expected))
      throw new IllegalArgumentException("Wake topic differs from type");
    transactions.inTransaction(
        () -> {
          var found =
              jdbc.query(
                  """
          SELECT t.account,p.id AS project_id FROM firings f JOIN board_topics t ON t.id=f.topic
          JOIN projects p ON p.name=t.project WHERE f.id=? AND f.target=? AND f.status='queued'
          AND jsonb_exists(f.data,'direct_message')=?
          """,
                  (row, index) ->
                      new Binding(
                          new Relay.SubscriptionKey(topic, group(wake.target())),
                          wake.target(),
                          row.getString("account"),
                          row.getLong("project_id")),
                  wake.firing(),
                  wake.target(),
                  wake.type() == RelayPayload.WakeKind.MESSAGE);
          // Deleted or already settled owning work needs no new consumer; the historical notice
          // remains.
          if (found.isEmpty()) return false;
          var binding = found.getFirst();
          relay.subscribe(binding.subscription(), Relay.Start.OLDEST_RETAINED);
          jdbc.update(
              """
          INSERT INTO relay_native_consumers(scope_key,topic,subscriber,target,account,project_id)
          VALUES ('system',?,?,?,?,?) ON CONFLICT DO NOTHING
          """,
              topic.name(),
              binding.subscription().subscriber(),
              binding.target(),
              binding.account(),
              binding.projectId());
          if (!binding.equals(binding(binding.subscription())))
            throw new IllegalStateException("Native consumer authority changed");
          return true;
        });
  }

  @Override
  public boolean routes(String target) {
    io.aeyer.plowshare.server.events.EventPayload.identity(target, "wake target");
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM relay_native_consumers WHERE target=?)
          OR EXISTS(SELECT 1 FROM firings WHERE target=? AND topic IS NOT NULL AND status='queued')
        """,
            Boolean.class,
            target,
            target));
  }

  @Override
  public List<Binding> ready(String after, int limit) {
    RelayValues.limit(limit, 256);
    if (after != null && (after.length() > 512 || after.indexOf('\0') >= 0))
      throw new IllegalArgumentException("Invalid native cursor");
    return jdbc.query(
        """
        SELECT n.* FROM relay_native_consumers n
        JOIN relay_subscriptions s USING(scope_key,topic,subscriber)
        JOIN relay_topics t ON t.scope_key=n.scope_key AND t.name=n.topic
        WHERE (CAST(? AS text) IS NULL OR n.scope_key||'/'||n.topic||'/'||n.subscriber>?)
          AND (s.seen_through<t.last_position OR EXISTS(
            SELECT 1 FROM firings f WHERE f.target=n.target AND f.status='queued'))
        ORDER BY n.scope_key,n.topic,n.subscriber LIMIT ?
        """,
        (row, index) ->
            new Binding(
                new Relay.SubscriptionKey(
                    new Relay.TopicKey(Relay.SystemScope.SERVER, row.getString("topic")),
                    row.getString("subscriber")),
                row.getString("target"),
                row.getString("account"),
                row.getLong("project_id")),
        after,
        after,
        limit);
  }

  @Override
  public void advance(RelayConsumerRepository.Lease lease, long position) {
    transactions.inTransaction(
        () -> {
          require(lease);
          relay.advanceSeen(lease.subscription(), position);
          consumers.requireOwned(lease);
          return true;
        });
  }

  @Override
  public void recoverGap(RelayConsumerRepository.Lease lease, long boundary) {
    transactions.inTransaction(
        () -> {
          var binding = require(lease);
          // Native inbox admission preceded publication. Expiration loses availability notices
          // only.
          // Record the checked pending inbox and exact boundary; ordinary subscribers never use
          // this.
          jdbc.update(
              """
          INSERT INTO relay_native_gap_recoveries(scope_key,topic,subscriber,expired_through,pending_inbox)
          SELECT 'system',?,?,?,count(*) FROM firings WHERE target=? AND status='queued'
          ON CONFLICT DO NOTHING
          """,
              lease.subscription().topic().name(),
              lease.subscription().subscriber(),
              boundary,
              binding.target());
          relay.acknowledgeGap(lease.subscription(), boundary);
          consumers.requireOwned(lease);

          return true;
        });
  }

  @Override
  public boolean claim(
      Binding expected, RelayConsumerRepository.Lease lease, String firing, Instant at) {
    Objects.requireNonNull(expected);
    Objects.requireNonNull(at);
    RelayValues.identity(firing, "firing");
    if (!expected.subscription().equals(lease.subscription()))
      throw new IllegalArgumentException("Native lease differs from binding");
    return transactions.inTransaction(
        () -> {
          var actual = require(lease);
          if (!expected.equals(actual)) throw new IllegalStateException("Native authority changed");
          // Different wake topics can share a target. Serialize those claims and recheck the lease
          // after waiting. The existing unique running-target index remains the final invariant.
          jdbc.queryForObject(
              "SELECT pg_advisory_xact_lock(hashtextextended('relay-native:'||?,0))",
              (row, index) -> Boolean.TRUE,
              actual.target());
          consumers.requireOwned(lease);
          var next = firings.oldestWaiting(actual.target());
          if (next.isEmpty() || !next.get().id().equals(firing) || firings.busy(actual.target()))
            return false;
          // Row mapping above validates the original typed firing before any claim is changed.
          boolean claimed =
              jdbc.update(
                      """
          UPDATE firings SET status='started',job_id='pending',started_at=?
          WHERE id=? AND target=? AND status='queued'
          AND NOT EXISTS(SELECT 1 FROM firings WHERE target=? AND status='started' AND finished_at IS NULL)
          """,
                      OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC),
                      firing,
                      actual.target(),
                      actual.target())
                  == 1;
          consumers.requireOwned(lease);
          return claimed;
        });
  }

  @Override
  public int prune(java.time.Duration retention, int limit) {
    new Relay.Policy(retention, null);
    RelayValues.limit(limit, 1000);
    return transactions.inTransaction(
        () -> {
          int deleted =
              jdbc.update(
                  """
          WITH old AS (SELECT scope_key,topic,subscriber,expired_through FROM relay_native_gap_recoveries
            WHERE recovered_at<clock_timestamp()-(? * interval '1 second')
            ORDER BY recovered_at LIMIT ?)
          DELETE FROM relay_native_gap_recoveries a USING old
          WHERE a.scope_key=old.scope_key AND a.topic=old.topic AND a.subscriber=old.subscriber
            AND a.expired_through=old.expired_through
          """,
                  retention.toSeconds(),
                  limit);
          // Persist offsets while the destination exists, just like ordinary groups. Conversation
          // deletion cannot race a new owning start; pending or uncertain inboxes still forbid
          // removal.
          deleted +=
              jdbc.update(
                  """
          WITH gone AS (
            SELECT s.scope_key,s.topic,s.subscriber FROM relay_subscriptions s
            LEFT JOIN relay_native_consumers n USING(scope_key,topic,subscriber)
            WHERE s.scope_key='system' AND s.topic IN ('message.wake.requested','board.wake.requested')
              AND s.subscriber LIKE 'builtin.wakes.%'
              AND (n.target IS NULL OR NOT EXISTS(SELECT 1 FROM conversations c WHERE c.id=substring(n.target FROM 14)))
              AND NOT EXISTS(SELECT 1 FROM relay_consumer_leases l WHERE l.scope_key=s.scope_key
                AND l.topic=s.topic AND l.subscriber=s.subscriber AND l.lease_until>clock_timestamp())
              AND (n.target IS NULL OR NOT EXISTS(SELECT 1 FROM firings f WHERE f.target=n.target
                AND (f.status='queued' OR (f.status='started' AND f.finished_at IS NULL))))
            ORDER BY s.scope_key,s.topic,s.subscriber LIMIT ?)
          DELETE FROM relay_subscriptions s USING gone WHERE s.scope_key=gone.scope_key
            AND s.topic=gone.topic AND s.subscriber=gone.subscriber
          """,
                  limit);
          return deleted;
        });
  }

  private Binding require(RelayConsumerRepository.Lease lease) {
    consumers.requireOwned(lease);
    var binding = binding(lease.subscription());
    if (!binding.account().equals(lease.account()))
      throw new IllegalStateException("Native consumer principal changed");
    return binding;
  }

  private Binding binding(Relay.SubscriptionKey key) {
    requireTopic(key.topic());
    return jdbc
        .query(
            """
        SELECT target,account,project_id FROM relay_native_consumers
        WHERE scope_key='system' AND topic=? AND subscriber=?
        """,
            (row, index) ->
                new Binding(
                    key,
                    row.getString("target"),
                    row.getString("account"),
                    row.getLong("project_id")),
            key.topic().name(),
            key.subscriber())
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Native consumer is unavailable"));
  }

  private static void requireTopic(Relay.TopicKey topic) {
    if (topic.scope() != Relay.SystemScope.SERVER
        || !List.of("message.wake.requested", "board.wake.requested").contains(topic.name()))
      throw new IllegalArgumentException("Native wakes require their private system topic");
  }

  private static String group(String target) {
    try {
      return "builtin.wakes."
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(target.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
