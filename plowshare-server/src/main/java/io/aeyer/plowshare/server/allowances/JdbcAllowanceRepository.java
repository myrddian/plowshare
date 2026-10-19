package io.aeyer.plowshare.server.allowances;

import io.aeyer.plowshare.server.agents.Budget;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Canonical persistence shared by conversation, board and information repositories. */
@Repository
public class JdbcAllowanceRepository implements AllowanceRepository {
  private final JdbcTemplate jdbc;

  public JdbcAllowanceRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  public void retain(AllowanceSnapshot selected) {
    jdbc.update(
        "INSERT INTO execution_allowances(id,call_limit,spent) VALUES(?,?,?) ON CONFLICT(id) DO NOTHING",
        selected.id().value(),
        selected.ceiling(),
        selected.spent());
  }

  public Optional<AllowanceSnapshot> find(AllowanceId id) {
    return jdbc
        .query(
            "SELECT call_limit,spent FROM execution_allowances WHERE id=?",
            (r, n) -> new AllowanceSnapshot(id, r.getObject(1, Integer.class), r.getInt(2)),
            id.value())
        .stream()
        .findFirst();
  }

  public Optional<Budget.Claim> claim(AllowanceId id, int reserve, Integer expectedSpent) {
    if (reserve < 0) throw new IllegalArgumentException("Negative reserve");
    if (expectedSpent != null && expectedSpent < 0)
      throw new IllegalArgumentException("Negative observed spending");
    return jdbc
        .query(
            "SELECT ordinal,call_limit FROM execution_allowance_claim(?,?,?)",
            (r, n) -> new Budget.Claim(id.value(), r.getInt(1), r.getObject(2, Integer.class)),
            id.value(),
            reserve,
            expectedSpent)
        .stream()
        .findFirst();
  }

  public void accept(Budget.Claim claim) {
    jdbc.queryForObject(
        "SELECT execution_allowance_accept(?,?,?) IS NULL",
        Boolean.class,
        claim.budget(),
        claim.ordinal(),
        claim.limit());
  }

  public void ceiling(AllowanceId id, Integer total) {
    if (total != null && total < 0) throw new IllegalArgumentException("Negative ceiling");
    // A metadata-only revision's zero is unallocated work, not a disabled/native service policy.
    // Its existing explicit processing control may allocate it after resource authorization.
    if (jdbc.update(
            "UPDATE execution_allowances a SET call_limit=? WHERE id=? AND (call_limit IS DISTINCT FROM 0 OR EXISTS(SELECT 1 FROM information_revisions r WHERE r.processing_budget_id=a.id AND r.processing_policy='explicit_only')) AND (?::integer IS NULL OR spent<=?)",
            total,
            id.value(),
            total,
            total)
        != 1)
      throw new IllegalStateException(
          "Allowance cannot be changed below usage or activated from disabled");
  }

  public void increase(AllowanceId id, int total, int observedSpent) {
    if (observedSpent < 0 || total <= observedSpent)
      throw new IllegalArgumentException("Increase must leave calls available");
    if (jdbc.update(
            "UPDATE execution_allowances SET call_limit=? WHERE id=? AND call_limit>0 AND call_limit<=? AND spent=?",
            total,
            id.value(),
            observedSpent,
            observedSpent)
        != 1) throw new IllegalStateException("Allowance moved before automatic increase");
  }

  public void add(AllowanceId id, int calls) {
    if (calls < 1) throw new IllegalArgumentException("Positive allowance chunk required");
    if (jdbc.update(
            "UPDATE execution_allowances SET call_limit=CASE WHEN call_limit IS NULL THEN NULL ELSE call_limit+? END WHERE id=?",
            calls,
            id.value())
        != 1) throw new IllegalStateException("Missing message allowance");
  }
}
