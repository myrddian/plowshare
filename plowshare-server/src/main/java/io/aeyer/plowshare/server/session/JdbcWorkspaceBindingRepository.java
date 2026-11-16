package io.aeyer.plowshare.server.session;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The conflict predicate makes identity verification and first registration one atomic write. */
public final class JdbcWorkspaceBindingRepository implements WorkspaceBindingRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Duration budget;

  public JdbcWorkspaceBindingRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this(jdbc, transactions, Duration.ofSeconds(2));
  }

  public JdbcWorkspaceBindingRepository(
      JdbcTemplate jdbc, TransactionTemplate transactions, Duration budget) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    if (budget.isNegative() || budget.isZero())
      throw new IllegalArgumentException("Positive SQL budget required");
    this.budget = budget;
    this.transactions.setTimeout(Math.toIntExact(Math.max(1, budget.toSeconds())));
  }

  @Override
  public String bind(
      String hash, String session, String account, String machine, String root, String project) {
    for (String value : List.of(hash, session, account, machine, root, project))
      if (value.isBlank() || value.length() > 4096 || value.indexOf('\0') >= 0)
        throw new IllegalArgumentException("Bounded workspace identity required");
    long deadline = System.nanoTime() + budget.toNanos();
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              long remaining = deadline - System.nanoTime();
              if (remaining <= 0)
                throw new IllegalStateException("Workspace binding SQL deadline expired");
              jdbc.queryForObject(
                  "SELECT set_config('statement_timeout', ?, true)",
                  String.class,
                  Math.max(1, Duration.ofNanos(remaining).toMillis()) + "ms");
              List<String> existing =
                  jdbc.query(
                      """
          INSERT INTO workspace_recovery_bindings(key_hash, session_id, account, machine, root, project)
          VALUES(?,?,?,?,?,?)
          ON CONFLICT(key_hash) DO UPDATE SET key_hash=EXCLUDED.key_hash
          WHERE workspace_recovery_bindings.account=EXCLUDED.account
            AND workspace_recovery_bindings.machine=EXCLUDED.machine
            AND workspace_recovery_bindings.root=EXCLUDED.root
            AND workspace_recovery_bindings.project=EXCLUDED.project
          RETURNING session_id
          """,
                      (row, number) -> row.getString(1),
                      hash,
                      session,
                      account,
                      machine,
                      root,
                      project);
              if (existing.size() != 1)
                throw new CallerFault(
                    "This workspace recovery key belongs to a different attachment");
              if (System.nanoTime() - deadline >= 0)
                throw new IllegalStateException("Workspace binding SQL deadline expired");
              return existing.getFirst();
            }));
  }
}
