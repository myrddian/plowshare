package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.allowances.AllowanceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns admission/support atomicity; immutable rows never manufacture permission on restart. */
@Repository
public class JdbcOperationRecords implements OperationRecords {
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
    return jdbc.query(
        "SELECT a.*,s.action AS support_action,s.grant_id FROM operation_admissions a"
            + " LEFT JOIN operation_admission_support s ON s.operation=a.id"
            + " WHERE a.id=? AND a.sealed ORDER BY s.action,s.grant_id",
        (ResultSetExtractor<Optional<OperationAuthority.Admission>>)
            rows -> {
              if (!rows.next()) return Optional.empty();
              var header = AdmissionHeader.read(rows);
              return Optional.of(header.admission(supports(rows)));
            },
        id);
  }

  @Override
  public Optional<OperationAuthority.Conversation> conversation(String id) {
    return jdbc.query(
        "SELECT c.conversation,c.agent,c.parent_conversation,c.allowance_id,c.capabilities,"
            + "a.*,s.action AS support_action,s.grant_id FROM operation_conversations c"
            + " LEFT JOIN operation_admissions a ON a.id=c.operation AND a.sealed"
            + " LEFT JOIN operation_admission_support s ON s.operation=a.id"
            + " WHERE c.conversation=? ORDER BY s.action,s.grant_id",
        (ResultSetExtractor<Optional<OperationAuthority.Conversation>>)
            rows -> {
              if (!rows.next()) return Optional.empty();
              if (rows.getObject("id", UUID.class) == null)
                throw new IllegalStateException("Missing conversation admission");
              var header = AdmissionHeader.read(rows);
              String conversation = rows.getString("conversation"),
                  agent = rows.getString("agent"),
                  parent = rows.getString("parent_conversation"),
                  capabilities = rows.getString("capabilities");
              var budget = migratedAllowance(rows.getObject("allowance_id", UUID.class), id);
              return Optional.of(
                  new OperationAuthority.Conversation(
                      conversation,
                      agent,
                      header.admission(supports(rows)),
                      parent,
                      budget,
                      capabilities));
            },
        id);
  }

  /**
   * One statement for the whole scope, grouped in the mapper rather than one read per conversation.
   *
   * <p>The support join multiplies rows, so the result set is ordered by conversation first and the
   * extractor closes a conversation when that column changes — the same shape {@link
   * #conversation(String)} extracts for one id, made to repeat. A per-conversation second query
   * would be the N+1 the query rules forbid, and the admission it would fetch is already on these
   * rows.
   *
   * <p>{@code ORDER BY c.conversation} is the oldest-first order the contract promises: a
   * conversation id is {@code cnv_} plus a time-ordered identifier, so lexical order is creation
   * order. A row with no sealed admission is skipped rather than failing the read: this is a
   * listing, and one unusable row must not refuse the rest.
   */
  @Override
  public List<OperationAuthority.Conversation> conversationsIn(AuthorityIds.ScopeId scope) {
    Objects.requireNonNull(scope, "scope");
    return jdbc.query(
        "SELECT c.conversation,c.agent,c.parent_conversation,c.allowance_id,c.capabilities,"
            + "a.*,s.action AS support_action,s.grant_id FROM operation_conversations c"
            + " JOIN operation_admissions a ON a.id=c.operation AND a.sealed"
            + " LEFT JOIN operation_admission_support s ON s.operation=a.id"
            + " WHERE a.scope=? ORDER BY c.conversation,s.action,s.grant_id",
        (ResultSetExtractor<List<OperationAuthority.Conversation>>)
            rows -> {
              var found = new ArrayList<OperationAuthority.Conversation>();
              boolean more = rows.next();
              while (more) {
                String conversation = rows.getString("conversation"),
                    agent = rows.getString("agent"),
                    parent = rows.getString("parent_conversation"),
                    capabilities = rows.getString("capabilities");
                var header = AdmissionHeader.read(rows);
                var budget =
                    migratedAllowance(rows.getObject("allowance_id", UUID.class), conversation);
                // Consumes this conversation's contiguous support rows. The cursor is left on the
                // next conversation's first row, which is what the returned flag reports; the
                // ResultSet is forward-only, so position is never read back from it.
                var support = new ArrayList<OperationAuthority.Support>();
                more = false;
                do {
                  if (!conversation.equals(rows.getString("conversation"))) {
                    more = true;
                    break;
                  }
                  String action = rows.getString("support_action");
                  if (action != null)
                    support.add(
                        new OperationAuthority.Support(
                            AuthorityAction.valueOf(action),
                            rows.getObject("grant_id", UUID.class)));
                } while (rows.next());
                found.add(
                    new OperationAuthority.Conversation(
                        conversation,
                        agent,
                        header.admission(List.copyOf(support)),
                        parent,
                        budget,
                        capabilities));
              }
              return List.copyOf(found);
            },
        scope.value());
  }

  private static List<OperationAuthority.Support> supports(ResultSet rows) throws SQLException {
    var support = new ArrayList<OperationAuthority.Support>();
    do {
      String action = rows.getString("support_action");
      if (action != null)
        support.add(
            new OperationAuthority.Support(
                AuthorityAction.valueOf(action), rows.getObject("grant_id", UUID.class)));
    } while (rows.next());
    return List.copyOf(support);
  }

  private record AdmissionHeader(
      UUID id,
      String executor,
      UUID account,
      UUID scope,
      String project,
      UUID source,
      boolean globalRead) {
    static AdmissionHeader read(ResultSet row) throws SQLException {
      return new AdmissionHeader(
          row.getObject("id", UUID.class),
          row.getString("executor_handle"),
          row.getObject("executor", UUID.class),
          row.getObject("scope", UUID.class),
          row.getString("project_name"),
          row.getObject("source_account", UUID.class),
          row.getBoolean("global_read"));
    }

    OperationAuthority.Admission admission(List<OperationAuthority.Support> support) {
      return new OperationAuthority.Admission(
          id,
          executor,
          new AuthorityIds.AccountId(account),
          new AuthorityIds.ScopeId(scope),
          io.aeyer.plowshare.protocol.Home.of(project),
          GrantRecipient.account(new AuthorityIds.AccountId(source)),
          support,
          globalRead);
    }
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
          var inserted =
              jdbc.query(
                  "INSERT INTO operation_conversations(conversation,agent,operation,parent_conversation,allowance_id,capabilities) VALUES(?,?,?,?,?,?) ON CONFLICT(conversation) DO NOTHING RETURNING conversation,agent,operation,parent_conversation,allowance_id,capabilities",
                  JdbcOperationRecords::binding,
                  bound.id(),
                  bound.agent(),
                  bound.admission().id(),
                  bound.parent(),
                  bound.budget().value(),
                  bound.capabilities());
          // Idempotent conflict reconciliation needs only the immutable binding tuple. Admission
          // support is unrelated to this comparison and remains live evidence at its own owner.
          var stored =
              inserted.isEmpty()
                  ? jdbc.query(
                          "SELECT conversation,agent,operation,parent_conversation,allowance_id,capabilities FROM operation_conversations WHERE conversation=?",
                          JdbcOperationRecords::binding,
                          bound.id())
                      .getFirst()
                  : inserted.getFirst();
          // Supporting rows have a stable semantic set, independent of JDBC enumeration order.
          if (!stored.id().equals(bound.id())
              || !stored.agent().equals(bound.agent())
              || !stored.operation().equals(bound.admission().id())
              || !Objects.equals(stored.parent(), bound.parent())
              || !Objects.equals(stored.allowance(), bound.budget().value())
              || !stored.capabilities().equals(bound.capabilities()))
            throw new IllegalStateException("Conversation authority cannot be rebound");
        });
  }

  private record Binding(
      String id,
      String agent,
      UUID operation,
      String parent,
      UUID allowance,
      String capabilities) {}

  private static Binding binding(ResultSet row, int index) throws SQLException {
    return new Binding(
        row.getString("conversation"),
        row.getString("agent"),
        row.getObject("operation", UUID.class),
        row.getString("parent_conversation"),
        row.getObject("allowance_id", UUID.class),
        row.getString("capabilities"));
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
