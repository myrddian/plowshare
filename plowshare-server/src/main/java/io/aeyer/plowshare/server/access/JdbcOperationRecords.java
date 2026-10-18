package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.allowances.AllowanceId;
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
              "INSERT INTO operation_admissions(id,executor,executor_handle,scope,project_name,source_account,global_read) VALUES(?,?,?,?,?,?,?)",
              admission.id(),
              admission.account().value(),
              admission.executor(),
              admission.scope().value(),
              admission.home().project(),
              admission.source().account().value(),
              admission.globalRead());
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
  public void retainChild(OperationAuthority.Admission admission, UUID parent) {
    transactions.executeWithoutResult(
        status -> {
          retain(admission);
          if (jdbc.update(
                  "INSERT INTO operation_admission_parents(child,parent) SELECT ?,id FROM operation_admissions WHERE id=? AND sealed",
                  admission.id(),
                  parent)
              != 1) throw new IllegalStateException("Retained parent required");
        });
  }

  @Override
  public Optional<UUID> parent(UUID id) {
    return jdbc
        .query(
            "SELECT parent FROM operation_admission_parents WHERE child=?",
            (rs, n) -> rs.getObject(1, UUID.class),
            id)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<OperationAuthority.Admission> find(UUID id) {
    return jdbc
        .query(
            "SELECT * FROM operation_admissions WHERE id=? AND sealed",
            (rs, n) ->
                new OperationAuthority.Admission(
                    rs.getObject("id", UUID.class),
                    rs.getString("executor_handle"),
                    new AuthorityIds.AccountId(rs.getObject("executor", UUID.class)),
                    new AuthorityIds.ScopeId(rs.getObject("scope", UUID.class)),
                    io.aeyer.plowshare.protocol.Home.of(rs.getString("project_name")),
                    GrantRecipient.account(
                        new AuthorityIds.AccountId(rs.getObject("source_account", UUID.class))),
                    jdbc.query(
                        "SELECT action,grant_id FROM operation_admission_support WHERE operation=? ORDER BY action,grant_id",
                        (support, index) ->
                            new OperationAuthority.Support(
                                AuthorityAction.valueOf(support.getString(1)),
                                support.getObject(2, UUID.class)),
                        id),
                    rs.getBoolean("global_read")),
            id)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<OperationAuthority.Conversation> conversation(String id) {
    return jdbc
        .query(
            "SELECT * FROM operation_conversations WHERE conversation=?",
            (rs, n) ->
                new OperationAuthority.Conversation(
                    rs.getString("conversation"),
                    rs.getString("agent"),
                    find(rs.getObject("operation", UUID.class))
                        .orElseThrow(
                            () -> new IllegalStateException("Missing conversation admission")),
                    rs.getString("parent_conversation"),
                    migratedAllowance(rs.getObject("allowance_id", UUID.class), id),
                    rs.getString("capabilities")),
            id)
        .stream()
        .findFirst();
  }

  private static AllowanceId migratedAllowance(UUID source, String conversation) {
    if (source == null)
      throw new IllegalStateException(
          "Operation "
              + conversation
              + " has no migrated allowance; reconcile retained evidence before resuming");
    return new AllowanceId(source);
  }

  @Override
  public List<AccessRequest.Resource> targets(UUID operation) {
    var targets =
        jdbc.query(
            "SELECT conversation,parent_conversation FROM operation_conversations WHERE operation=?",
            (rs, n) ->
                java.util.stream.Stream.of(rs.getString(1), rs.getString(2))
                    .filter(Objects::nonNull)
                    .distinct()
                    .map(
                        id ->
                            new AccessRequest.Resource(
                                ResourceScopeRepository.Kind.CONVERSATION, id))
                    .toList(),
            operation);
    return targets.stream().flatMap(List::stream).distinct().toList();
  }

  @Override
  public void bind(OperationAuthority.Conversation bound) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "INSERT INTO operation_conversations(conversation,agent,operation,parent_conversation,allowance_id,capabilities) VALUES(?,?,?,?,?,?) ON CONFLICT(conversation) DO NOTHING",
              bound.id(),
              bound.agent(),
              bound.admission().id(),
              bound.parent(),
              bound.budget().value(),
              bound.capabilities());
          var stored = conversation(bound.id()).orElseThrow();
          // Supporting rows have a stable semantic set, independent of JDBC enumeration order.
          if (!stored.id().equals(bound.id())
              || !stored.agent().equals(bound.agent())
              || !stored.admission().id().equals(bound.admission().id())
              || !Objects.equals(stored.parent(), bound.parent())
              || !stored.budget().equals(bound.budget())
              || !stored.capabilities().equals(bound.capabilities()))
            throw new IllegalStateException("Conversation authority cannot be rebound");
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
