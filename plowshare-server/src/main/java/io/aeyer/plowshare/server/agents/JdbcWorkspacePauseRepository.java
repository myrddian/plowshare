package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Typed checkpoint JSON is confined to this persistence boundary. No effects are replayed here. */
public final class JdbcWorkspacePauseRepository implements WorkspacePauseRepository {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Duration budget;
  private final long leaseMillis;

  public JdbcWorkspacePauseRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this(jdbc, transactions, Duration.ofSeconds(2), Duration.ofSeconds(30));
  }

  public JdbcWorkspacePauseRepository(
      JdbcTemplate jdbc, TransactionTemplate transactions, Duration budget, Duration ownerLease) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    if (budget.isNegative() || budget.isZero() || ownerLease.isNegative() || ownerLease.isZero())
      throw new IllegalArgumentException("Positive SQL and lease budgets required");
    this.budget = budget;
    this.leaseMillis = ownerLease.toMillis();
    transactions.setTimeout(Math.toIntExact(Math.max(1, budget.toSeconds())));
  }

  private <T> T work(Supplier<T> work) {
    long deadline = System.nanoTime() + budget.toNanos();
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              long remaining = deadline - System.nanoTime();
              if (remaining <= 0)
                throw new IllegalStateException("Workspace checkpoint SQL deadline expired");
              jdbc.queryForObject(
                  "SELECT set_config('statement_timeout', ?, true)",
                  String.class,
                  Math.max(1, Duration.ofNanos(remaining).toMillis()) + "ms");
              T result = work.get();
              if (System.nanoTime() - deadline >= 0)
                throw new IllegalStateException("Workspace checkpoint SQL deadline expired");
              return result;
            }));
  }

  @Override
  public void suspended(Snapshot snapshot, String owner) {
    String encoded;
    try {
      encoded = JSON.writeValueAsString(snapshot);
    } catch (JsonProcessingException invalid) {
      throw new IllegalArgumentException("Invalid workspace checkpoint", invalid);
    }
    if (encoded.length() > 4_000_000)
      throw new IllegalArgumentException("Workspace checkpoint is too large");
    int changed =
        work(
            () ->
                jdbc.update(
                    """
        INSERT INTO workspace_pauses(job_id, root, snapshot, state, owner, lease_until) VALUES(?,?,?::jsonb,?,?,clock_timestamp()+(? * interval '1 millisecond'))
        ON CONFLICT(job_id) DO UPDATE SET root=EXCLUDED.root, snapshot=EXCLUDED.snapshot,
          generation=workspace_pauses.generation+1, state=EXCLUDED.state, owner=EXCLUDED.owner, lease_until=EXCLUDED.lease_until
          WHERE workspace_pauses.state <> 'cancelled' AND (workspace_pauses.owner=EXCLUDED.owner OR workspace_pauses.state='done')
        """,
                    snapshot.job(),
                    snapshot.root(),
                    encoded,
                    snapshot.blocked() ? "blocked" : "paused",
                    owner,
                    leaseMillis));
    if (changed != 1)
      throw new IllegalStateException("Workspace checkpoint belongs to another execution owner");
  }

  @Override
  public List<Retained> pending(String after, int limit) {
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("Recovery limit must be 1..100");
    return work(
        () ->
            jdbc.query(
                "SELECT snapshot::text, generation, state FROM workspace_pauses WHERE state IN ('paused', 'claimed', 'started') AND lease_until < clock_timestamp() AND job_id > ? ORDER BY job_id LIMIT ?",
                (row, number) -> {
                  try {
                    return new Retained(
                        JSON.readValue(row.getString(1), Snapshot.class),
                        row.getLong(2),
                        row.getString(3));
                  } catch (JsonProcessingException invalid) {
                    throw new IllegalStateException(
                        "Invalid retained workspace checkpoint", invalid);
                  }
                },
                after,
                limit));
  }

  @Override
  public Optional<Retained> find(String job) {
    return work(
            () ->
                jdbc.query(
                    "SELECT snapshot::text, generation, state FROM workspace_pauses WHERE job_id=? AND state <> 'done'",
                    (row, number) -> {
                      try {
                        return new Retained(
                            JSON.readValue(row.getString(1), Snapshot.class),
                            row.getLong(2),
                            row.getString(3));
                      } catch (JsonProcessingException invalid) {
                        throw new IllegalStateException(
                            "Invalid retained workspace checkpoint", invalid);
                      }
                    },
                    job))
        .stream()
        .findFirst();
  }

  @Override
  public boolean renew(String job, String owner) {
    return work(
            () ->
                jdbc.update(
                    "UPDATE workspace_pauses SET lease_until=clock_timestamp()+(? * interval '1 millisecond') WHERE job_id=? AND owner=? AND state IN ('paused','blocked')",
                    leaseMillis,
                    job,
                    owner))
        == 1;
  }

  @Override
  public boolean resumed(String job, String owner) {
    return work(
            () ->
                jdbc.update(
                    "UPDATE workspace_pauses SET state='done' WHERE job_id=? AND owner=? AND state='paused'",
                    job,
                    owner))
        == 1;
  }

  @Override
  public boolean awaiting(String root) {
    return Boolean.TRUE.equals(
        work(
            () ->
                jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM workspace_pauses WHERE root=? AND state NOT IN ('done','cancelled'))",
                    Boolean.class,
                    root)));
  }

  @Override
  public boolean claim(String job, long generation, String owner) {
    return work(
            () ->
                jdbc.update(
                    "UPDATE workspace_pauses SET state='claimed', owner=?, lease_until=clock_timestamp()+(? * interval '1 millisecond') WHERE job_id=? AND generation=? AND state='paused' AND lease_until < clock_timestamp()",
                    owner,
                    leaseMillis,
                    job,
                    generation))
        == 1;
  }

  @Override
  public void started(String job, long generation, String owner) {
    if (work(
            () ->
                jdbc.update(
                    "UPDATE workspace_pauses SET state='started' WHERE job_id=? AND generation=? AND state='claimed' AND owner=?",
                    job,
                    generation,
                    owner))
        != 1) throw new IllegalStateException("Workspace recovery claim changed");
  }

  @Override
  public void completed(String job, String owner) {
    work(
        () ->
            jdbc.update(
                "UPDATE workspace_pauses SET state='done' WHERE job_id=? AND owner=? AND state <> 'cancelled'",
                job,
                owner));
  }

  @Override
  public Optional<Snapshot> cancel(String job) {
    return work(
            () ->
                jdbc.query(
                    "UPDATE workspace_pauses SET state='cancelled' WHERE job_id=? AND state NOT IN ('done','cancelled') RETURNING snapshot::text",
                    (row, number) -> {
                      try {
                        return JSON.readValue(row.getString(1), Snapshot.class);
                      } catch (JsonProcessingException invalid) {
                        throw new IllegalStateException(
                            "Invalid retained workspace checkpoint", invalid);
                      }
                    },
                    job))
        .stream()
        .findFirst();
  }

  @Override
  public void blocked(String job, String reason) {
    if (reason == null || reason.length() > 4000)
      throw new IllegalArgumentException("Bounded pause reason required");
    work(
        () ->
            jdbc.update(
                "UPDATE workspace_pauses SET state='blocked', reason=?, snapshot=jsonb_set(jsonb_set(snapshot, '{reason}', to_jsonb(?::text)), '{blocked}', 'true'::jsonb) WHERE job_id=? AND state NOT IN ('done','cancelled')",
                reason,
                reason,
                job));
  }
}
