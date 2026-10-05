package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns cursor locks, bounded event row mapping and the durable readability predicate. */
@Repository
public class JdbcInformationEventRepository implements InformationEventRepository {
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;

  public JdbcInformationEventRepository(JdbcTemplate jdbc, UnitOfWork transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  @Override
  public List<Event> claim(UUID token, Instant now) {
    Objects.requireNonNull(token);
    Objects.requireNonNull(now);
    return transactions.inTransaction(
        () -> {
          var cursors =
              jdbc.queryForList(
                  "SELECT cursor FROM information_event_dispatch WHERE singleton=true AND (lease_until IS NULL OR lease_until<?) FOR UPDATE SKIP LOCKED",
                  Long.class,
                  now.atOffset(ZoneOffset.UTC));
          if (cursors.isEmpty()) return List.of();
          var batch =
              jdbc.query(
                  "SELECT sequence,revision_id,generation,action FROM information_events WHERE sequence>? ORDER BY sequence LIMIT 100",
                  (row, n) ->
                      new Event(
                          row.getLong(1),
                          row.getObject(2, UUID.class),
                          row.getInt(3),
                          row.getString(4)),
                  cursors.getFirst());
          if (!batch.isEmpty()
              && jdbc.update(
                      "UPDATE information_event_dispatch SET token=?,lease_until=? WHERE singleton=true",
                      token,
                      now.plusSeconds(30).atOffset(ZoneOffset.UTC))
                  != 1) throw new IllegalStateException("information event cursor disappeared");
          return List.copyOf(batch);
        });
  }

  @Override
  public List<String> recipients(long sequence) {
    if (sequence < 1) throw new IllegalArgumentException("invalid event sequence");
    return jdbc.queryForList(
        "SELECT account FROM information_event_recipients WHERE sequence=?",
        String.class,
        sequence);
  }

  @Override
  public boolean readable(UUID revision, String account) {
    Objects.requireNonNull(revision);
    Objects.requireNonNull(account);
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT information_readable(?,?,'personal',NULL,true) OR EXISTS(SELECT 1 FROM projects p JOIN project_members m ON m.project_id=p.id WHERE m.handle=? AND information_readable(?,?,'project',p.name,true))",
            Boolean.class,
            revision,
            account,
            account,
            revision,
            account));
  }

  @Override
  public boolean complete(UUID token, long sequence) {
    Objects.requireNonNull(token);
    if (sequence < 1) throw new IllegalArgumentException("invalid event sequence");
    return jdbc.update(
            "UPDATE information_event_dispatch SET cursor=?,token=NULL,lease_until=NULL WHERE singleton=true AND token=?",
            sequence,
            token)
        == 1;
  }
}
