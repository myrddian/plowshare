package io.aeyer.plowshare.server.access;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns admission/support atomicity; immutable rows never manufacture permission on restart. */
@Repository
public final class JdbcOperationRecords implements OperationRecords {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcOperationRecords(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
    transactions =
        new TransactionTemplate(
            new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
  }

  @Override
  public void retain(OperationAuthority.Admission admission) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "INSERT INTO operation_admissions(id,executor,executor_handle,scope,project_name,source_account) VALUES(?,?,?,?,?,?)",
              admission.id(),
              admission.account().value(),
              admission.executor(),
              admission.scope().value(),
              admission.home().project(),
              admission.source().account().value());
          for (var item : admission.support())
            jdbc.update(
                "INSERT INTO operation_admission_support(operation,action,grant_id) VALUES(?,?,?)",
                admission.id(),
                item.action().name(),
                item.grant());
          if (jdbc.update(
                  "UPDATE operation_admissions SET sealed=true WHERE id=? AND NOT sealed",
                  admission.id())
              != 1) throw new IllegalStateException("Operation admission seal failed");
        });
  }

  @Override
  public void started(UUID operation, String job) {
    if (job == null || job.isBlank() || job.length() > 1024)
      throw new IllegalArgumentException("Existing job identity required");
    if (jdbc.update(
            "UPDATE operation_admissions SET job_id=? WHERE id=? AND sealed AND (job_id IS NULL OR job_id=?)",
            job,
            operation,
            job)
        != 1) throw new IllegalStateException("Operation job correlation refused");
  }
}
