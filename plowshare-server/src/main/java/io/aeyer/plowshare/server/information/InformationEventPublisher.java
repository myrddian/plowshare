package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.AccountPushes;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * At-least-once invalidation hints. Clients recover authoritative state through scoped cursor
 * reads.
 */
public final class InformationEventPublisher implements AutoCloseable {
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final AccountPushes pushes;
  private final Clock clock;
  private ScheduledExecutorService worker;

  public InformationEventPublisher(
      JdbcTemplate jdbc, UnitOfWork transactions, AccountPushes pushes, Clock clock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.pushes = pushes;
    this.clock = clock;
  }

  public boolean drainOne() {
    UUID token = UUID.randomUUID();
    var events =
        transactions.inTransaction(
            () -> {
              var rows =
                  jdbc.queryForList(
                      "SELECT cursor FROM information_event_dispatch WHERE singleton=true AND (lease_until IS NULL OR lease_until<?) FOR UPDATE SKIP LOCKED",
                      clock.instant().atOffset(ZoneOffset.UTC));
              if (rows.isEmpty()) return List.<Map<String, Object>>of();
              long after = ((Number) rows.getFirst().get("cursor")).longValue();
              var batch =
                  jdbc.queryForList(
                      "SELECT sequence,revision_id,generation,action FROM information_events WHERE sequence>? ORDER BY sequence LIMIT 100",
                      after);
              if (!batch.isEmpty())
                jdbc.update(
                    "UPDATE information_event_dispatch SET token=?,lease_until=? WHERE singleton=true",
                    token,
                    clock.instant().plusSeconds(30).atOffset(ZoneOffset.UTC));
              return batch;
            });
    if (events.isEmpty()) return false;
    for (var event : events) {
      var body =
          Map.of(
              "kind",
              "information.changed",
              "sequence",
              event.get("sequence"),
              "revision",
              event.get("revision_id"),
              "generation",
              event.get("generation"));
      for (String account :
          jdbc.queryForList(
              "SELECT account FROM information_event_recipients WHERE sequence=?",
              String.class,
              event.get("sequence"))) {
        boolean reduction =
            List.of("withdrawn", "excluded", "deleted", "unshared", "unlinked")
                .contains(event.get("action"));
        boolean permitted =
            jdbc.queryForObject(
                "SELECT information_readable(?,?,'personal',NULL,true) OR EXISTS(SELECT 1 FROM projects p JOIN project_members m ON m.project_id=p.id WHERE m.handle=? AND information_readable(?,?,'project',p.name,true))",
                Boolean.class,
                event.get("revision_id"),
                account,
                account,
                event.get("revision_id"),
                account);
        if (reduction || permitted) pushes.push(account, body);
      }
    }
    jdbc.update(
        "UPDATE information_event_dispatch SET cursor=?,token=NULL,lease_until=NULL WHERE singleton=true AND token=?",
        events.getLast().get("sequence"),
        token);
    return true;
  }

  public synchronized void start() {
    if (worker != null) return;
    worker =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("information-events").factory());
    worker.scheduleWithFixedDelay(
        () -> {
          try {
            drainOne();
          } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(InformationEventPublisher.class)
                .warn("information events could not be delivered", failure);
          }
        },
        0,
        1,
        TimeUnit.SECONDS);
  }

  @Override
  public synchronized void close() {
    if (worker != null) worker.shutdownNow();
  }
}
