package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Topic-first locking matches publication, lease acquisition and operator removal. */
public final class JdbcRelayRetirementRepository implements RelayRetirementRepository {
  private final JdbcTemplate jdbc;
  private final UnitOfWork work;

  public JdbcRelayRetirementRepository(JdbcTemplate jdbc, UnitOfWork work) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.work = Objects.requireNonNull(work);
  }

  @Override
  public boolean retireOwned(Relay.SubscriptionKey owner) {
    Objects.requireNonNull(owner);
    return work.inTransaction(
        () -> {
          String scope = RelayScopeCodec.write(owner.topic());
          String topic = owner.topic().name();
          var locked =
              jdbc.query(
                  "SELECT name FROM relay_topics WHERE scope_key=? AND name=? FOR UPDATE",
                  (row, index) -> row.getString(1),
                  scope,
                  topic);
          if (locked.isEmpty()) return true;
          if (Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM relay_publications WHERE scope_key=? AND topic=?)",
                  Boolean.class,
                  scope,
                  topic))) return false;
          // An availability cursor is not evidence that a receiver's retained inputs or paid work
          // finished. Those keep their own retention/lifecycle; retirement cannot cascade them
          // away.
          jdbc.update(
              """
          DELETE FROM relay_subscriptions s WHERE s.scope_key=? AND s.topic=? AND s.subscriber=?
            AND s.seen_through>=(SELECT last_position FROM relay_topics WHERE scope_key=s.scope_key AND name=s.topic)
            AND NOT EXISTS(SELECT 1 FROM relay_admissions a WHERE a.scope_key=s.scope_key AND a.topic=s.topic AND a.subscriber=s.subscriber)
            AND NOT EXISTS(SELECT 1 FROM relay_native_consumers n WHERE n.scope_key=s.scope_key AND n.topic=s.topic AND n.subscriber=s.subscriber)
            AND NOT EXISTS(SELECT 1 FROM relay_approval_gap_recoveries a WHERE a.scope_key=s.scope_key AND a.topic=s.topic AND a.subscriber=s.subscriber)
            AND NOT EXISTS(SELECT 1 FROM relay_consumer_leases l WHERE l.scope_key=s.scope_key AND l.topic=s.topic AND l.subscriber=s.subscriber AND l.lease_until>clock_timestamp())
            AND NOT EXISTS(SELECT 1 FROM relay_sdk_batches b WHERE b.scope_key=s.scope_key AND b.topic=s.topic AND b.subscriber=s.subscriber AND NOT b.acknowledged AND b.lease_until>clock_timestamp())
          """,
              scope,
              topic,
              owner.subscriber());
          return jdbc.update(
                  """
          DELETE FROM relay_topics t WHERE t.scope_key=? AND t.name=?
            AND NOT EXISTS(SELECT 1 FROM relay_subscriptions s WHERE s.scope_key=t.scope_key AND s.topic=t.name)
            AND NOT EXISTS(SELECT 1 FROM relay_publications p WHERE p.scope_key=t.scope_key AND p.topic=t.name)
          """,
                  scope,
                  topic)
              == 1;
        });
  }
}
