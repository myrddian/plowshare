package io.aeyer.plowshare.server.relay;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable start intent survives broker admission cleanup; absence never authorizes a replay. */
public final class JdbcRelayExecutions implements RelayExecutions {
  private final JdbcTemplate jdbc;
  private final java.util.function.Supplier<java.time.Instant> clock;

  public JdbcRelayExecutions(JdbcTemplate jdbc) {
    this(jdbc, java.time.Instant::now);
  }

  public JdbcRelayExecutions(
      JdbcTemplate jdbc, java.util.function.Supplier<java.time.Instant> clock) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.clock = Objects.requireNonNull(clock);
  }

  public boolean begin(RelayReceiver.Request request) {
    if (request.delivery().state() != RelayDeliveries.State.DISPATCHING)
      throw new IllegalArgumentException("Relay execution requires committed dispatch intent");
    return jdbc.update(
            """
        INSERT INTO relay_executions(request_id,account,project_id,handler_source,handler_hash)
        SELECT d.id,?,?,d.handler_source,d.handler_hash FROM relay_deliveries d
        WHERE d.id=? AND d.scope_key=? AND d.state='DISPATCHING' AND d.fence=? AND d.worker=? AND d.lease_until>?
        ON CONFLICT(request_id) DO NOTHING
        """,
            request.access().account(),
            request.access().projectId(),
            request.identity(),
            RelayScopeCodec.write(request.delivery().key().subscription().topic()),
            request.delivery().fence(),
            request.delivery().worker(),
            java.sql.Timestamp.from(RelayValues.time(clock.get())))
        == 1;
  }

  public void accepted(
      RelayReceiver.Request request, RelayDeliveries.Receipt receipt, String conversation) {
    Objects.requireNonNull(conversation, "conversation");
    if (!java.util.Set.of("job", "orchestration").contains(receipt.namespace()))
      throw new IllegalArgumentException("unsupported execution receipt");
    if (jdbc.update(
            """
        UPDATE relay_executions SET receipt_namespace=?,receipt_id=?,conversation_id=?,accepted_at=now()
        WHERE request_id=? AND account=? AND project_id=? AND receipt_id IS NULL
        """,
            receipt.namespace(),
            receipt.id(),
            conversation,
            request.identity(),
            request.access().account(),
            request.access().projectId())
        != 1) throw new IllegalStateException("Relay execution receipt was not recorded");
  }

  public int prune(java.time.Instant now, java.time.Duration retention, int limit) {
    RelayValues.limit(limit, 1000);
    new Relay.Policy(retention, null);
    return jdbc.update(
        """
        DELETE FROM relay_executions WHERE request_id IN (
          SELECT e.request_id FROM relay_executions e LEFT JOIN conversations c ON c.id=e.conversation_id
          WHERE e.accepted_at < ? AND (e.conversation_id IS NULL OR c.log_closed_at IS NOT NULL)
            AND NOT EXISTS(SELECT 1 FROM relay_deliveries d WHERE d.id=e.request_id)
          ORDER BY e.accepted_at,e.request_id LIMIT ? FOR UPDATE OF e SKIP LOCKED)
        """,
        java.sql.Timestamp.from(RelayValues.time(now).minus(retention)),
        limit);
  }

  public Optional<Accepted> find(String account, long projectId, UUID requestId) {
    RelayValues.identity(account, "account");
    if (projectId < 1) throw new IllegalArgumentException("project ID must be positive");
    Objects.requireNonNull(requestId);
    return jdbc
        .query(
            """
        SELECT receipt_namespace,receipt_id,conversation_id FROM relay_executions
        WHERE request_id=? AND account=? AND project_id=? AND receipt_id IS NOT NULL
        """,
            (row, index) ->
                new Accepted(
                    new RelayDeliveries.Receipt(row.getString(1), row.getString(2)),
                    row.getString(3)),
            requestId,
            account,
            projectId)
        .stream()
        .findFirst();
  }
}
