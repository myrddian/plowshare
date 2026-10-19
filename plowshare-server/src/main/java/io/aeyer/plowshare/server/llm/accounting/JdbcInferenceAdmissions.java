package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns immutable call claims, single submission and optional accounting correlation. */
@Repository
public final class JdbcInferenceAdmissions implements InferenceAdmissions {
  private final JdbcTemplate jdbc;
  private final AllowanceRepository allowances;
  private final org.springframework.transaction.support.TransactionTemplate transactions;

  public JdbcInferenceAdmissions(JdbcTemplate jdbc) {
    this(jdbc, new JdbcAllowanceRepository(jdbc));
  }

  @org.springframework.beans.factory.annotation.Autowired
  public JdbcInferenceAdmissions(JdbcTemplate jdbc, AllowanceRepository allowances) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.allowances = Objects.requireNonNull(allowances);
    transactions =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())));
  }

  @Override
  public void retain(InferenceBinding b) {
    transactions.executeWithoutResult(
        status -> {
          allowances.accept(
              new io.aeyer.plowshare.server.agents.Budget.Claim(
                  b.budget(), b.ordinal(), b.limit()));
          jdbc.update(
              "INSERT INTO operation_inference_budgets(operation_id,budget_id,source_conversation) VALUES(?,?,?) ON CONFLICT(operation_id) DO NOTHING",
              b.operation(),
              b.budget(),
              b.conversation());
          if (!Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM operation_inference_budgets WHERE operation_id=? AND budget_id=?)",
                  Boolean.class,
                  b.operation(),
                  b.budget())))
            throw new IllegalStateException("Operation cannot replace its allowance source");
          jdbc.update(
              "INSERT INTO inference_admissions(id,operation_id,budget_id,ordinal,call_limit) VALUES(?,?,?,?,?)",
              b.id(),
              b.operation(),
              b.budget(),
              b.ordinal(),
              b.limit());
        });
  }

  @Override
  public void submitted(InferenceBinding b) {
    require(b);
    if (jdbc.update(
            "UPDATE inference_admissions SET submitted=true WHERE id=? AND NOT submitted", b.id())
        != 1)
      throw new IllegalStateException(
          "Inference claim already submitted; reconcile original attempt");
  }

  @Override
  public void require(InferenceBinding b) {
    Boolean present =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM inference_admissions a JOIN operation_inference_budgets b USING(operation_id,budget_id) WHERE a.id=? AND operation_id=? AND budget_id=? AND ordinal=? AND call_limit IS NOT DISTINCT FROM ?)",
            Boolean.class,
            b.id(),
            b.operation(),
            b.budget(),
            b.ordinal(),
            b.limit());
    if (!Boolean.TRUE.equals(present))
      throw new IllegalStateException("Missing retained inference claim");
  }

  @Override
  public void accounted(InferenceBinding b, UUID call) {
    if (jdbc.update(
            "UPDATE inference_admissions SET accounting_call=? WHERE id=? AND submitted AND (accounting_call IS NULL OR accounting_call=?)",
            call,
            b.id(),
            call)
        != 1) throw new IllegalStateException("Inference accounting correlation refused");
  }
}
