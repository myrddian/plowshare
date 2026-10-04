package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit migration of quarantined policies. Not exposed as a model tool or a REST controller. */
public final class DocumentPolicyAssignments {
  private InformationWriteGates gates;

  public void useWriteGates(InformationWriteGates gates) {
    this.gates = gates;
  }

  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final InformationAccess access;
  private final Clock clock;
  private final String migrationAccount;

  public DocumentPolicyAssignments(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      InformationAccess access,
      Clock clock,
      String migrationAccount) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.access = Objects.requireNonNull(access, "access");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.migrationAccount = migrationAccount;
  }

  public java.util.Map<String, Object> inventory(String actor, int limit) {
    return inventory(actor, limit, 0);
  }

  public java.util.Map<String, Object> inventory(String actor, int limit, int offset) {
    requireOperator(actor);
    if (offset < 0) throw new CallerFault("migration offset must be nonnegative");
    if (limit < 1 || limit > 100)
      throw new CallerFault("migration inventory limit must be between 1 and 100");
    return java.util.Map.of(
        "documents",
        jdbc.queryForList(
            "SELECT d.id,d.source_name,d.title,p.visibility FROM documents d JOIN information_document_policies p ON p.document_id=d.id WHERE p.visibility='quarantined' ORDER BY d.id LIMIT ? OFFSET ?",
            limit,
            offset),
        "payloads",
        jdbc.queryForList(
            "SELECT payload_id,reason FROM information_quarantined_payloads ORDER BY payload_id LIMIT ? OFFSET ?",
            limit,
            offset));
  }

  public java.util.Map<String, Object> inspect(String actor, String payload, String reason) {
    return inspect(actor, payload, reason, 100, 0);
  }

  public java.util.Map<String, Object> inspect(
      String actor, String payload, String reason, int limit, int offset) {
    requireOperator(actor);
    if (limit < 1 || limit > 100 || offset < 0)
      throw new CallerFault("inspection needs limit 1..100 and nonnegative offset");
    if (reason == null || reason.isBlank())
      throw new CallerFault("migration inspection needs a reason");
    return transactions.inTransaction(
        () -> {
          if (jdbc.queryForList(
                  "SELECT payload_id FROM information_quarantined_payloads WHERE payload_id=?",
                  payload)
              .isEmpty()) throw new NotFoundFault("no quarantined payload available");
          audit(actor, payload, null, "inspect", java.util.List.of(), reason);
          return java.util.Map.of(
              "log",
              jdbc.queryForList(
                  "SELECT id,owner_handle,agent FROM conversations WHERE id=?", payload),
              "entries",
              jdbc.queryForList(
                  "SELECT ordinal,kind,content,tool_calls FROM entries WHERE conversation_id=? ORDER BY ordinal LIMIT ? OFFSET ?",
                  payload,
                  limit,
                  offset),
              "job",
              jdbc.queryForList("SELECT id,agent,ending FROM jobs WHERE id=?", payload));
        });
  }

  public void release(
      String actor,
      String payload,
      String owner,
      InformationContext.Selection selection,
      java.util.List<UUID> revisions,
      String reason) {
    release(actor, payload, owner, selection, revisions, reason, null, null);
  }

  public void release(
      String actor,
      String payload,
      String owner,
      InformationContext.Selection selection,
      java.util.List<UUID> revisions,
      String reason,
      UUID request,
      String session) {
    requireOperator(actor);
    if (gates == null) {
      releaseUnchecked(actor, payload, owner, selection, revisions, reason);
      return;
    }
    gates.execute(
        access.resolve(actor, null),
        request,
        "migration.release",
        java.util.Arrays.asList(payload, owner, selection, revisions, reason),
        java.util.List.of(),
        session,
        Boolean.class,
        () -> {
          releaseUnchecked(actor, payload, owner, selection, revisions, reason);
          return true;
        });
  }

  private void releaseUnchecked(
      String actor,
      String payload,
      String owner,
      InformationContext.Selection selection,
      java.util.List<UUID> revisions,
      String reason) {
    requireOperator(actor);
    if (reason == null || reason.isBlank() || revisions.isEmpty())
      throw new CallerFault("payload release needs reviewed input revisions and a reason");
    var context = access.resolve(owner, selection);
    transactions.inTransaction(
        () -> {
          if (jdbc.queryForList(
                  "SELECT payload_id FROM information_quarantined_payloads WHERE payload_id=? FOR UPDATE",
                  payload)
              .isEmpty()) throw new NotFoundFault("no quarantined payload available");
          for (UUID revision : revisions) {
            if (!jdbc.queryForObject(
                "SELECT information_readable(?,?,?,?,?)",
                Boolean.class,
                revision,
                owner,
                context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),
                context.selection().project(),
                context.selection().includeShared()))
              throw new CallerFault(
                  "adopt and authorise every reviewed source before releasing its payload");
          }
          var logs =
              jdbc.queryForList("SELECT owner_handle FROM conversations WHERE id=?", payload);
          if (!logs.isEmpty()
              && logs.getFirst().get("owner_handle") != null
              && !owner.equals(logs.getFirst().get("owner_handle")))
            throw new CallerFault("payload already belongs to a different account");
          jdbc.update(
              "UPDATE conversations SET owner_handle=? WHERE id=? AND owner_handle IS NULL",
              owner,
              payload);
          new InformationJobs(jdbc, access).bind(payload, context, revisions);
          jdbc.update("DELETE FROM information_quarantined_payloads WHERE payload_id=?", payload);
          audit(actor, payload, owner, "release", revisions, reason);
          return null;
        });
  }

  private void audit(
      String actor,
      String payload,
      String owner,
      String action,
      java.util.List<UUID> revisions,
      String reason) {
    String inputs;
    try {
      inputs = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(revisions);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException(invalid);
    }
    jdbc.update(
        "INSERT INTO information_payload_assignments VALUES(?,?,?,?,?,CAST(? AS jsonb),?,?)",
        UUID.randomUUID(),
        payload,
        actor,
        owner,
        action,
        inputs,
        reason,
        OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
  }

  private void requireOperator(String actor) {
    if (migrationAccount == null || migrationAccount.isBlank() || !migrationAccount.equals(actor))
      throw new CallerFault("information migration requires the explicitly configured operator");
    access.resolve(actor, null);
  }

  /** Ownership comes from this explicit operator decision, never documents.ingested_by. */
  public void assign(
      String actor,
      UUID document,
      String owner,
      InformationContext.Scope visibility,
      String project,
      String reason) {
    assign(actor, document, owner, visibility, project, reason, null, null);
  }

  public void assign(
      String actor,
      UUID document,
      String owner,
      InformationContext.Scope visibility,
      String project,
      String reason,
      UUID request,
      String session) {
    requireOperator(actor);
    if (gates == null) {
      assignUnchecked(actor, document, owner, visibility, project, reason);
      return;
    }
    gates.execute(
        access.resolve(actor, null),
        request,
        "migration.adopt",
        java.util.Arrays.asList(document, owner, visibility, project, reason),
        java.util.List.of(),
        session,
        Boolean.class,
        () -> {
          assignUnchecked(actor, document, owner, visibility, project, reason);
          return true;
        });
  }

  private void assignUnchecked(
      String actor,
      UUID document,
      String owner,
      InformationContext.Scope visibility,
      String project,
      String reason) {
    requireOperator(actor);
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(visibility, "visibility");
    InformationContext.Selection destination =
        new InformationContext.Selection(visibility, project, false);
    InformationContext context = access.resolve(owner, destination);
    if (reason == null || reason.isBlank()) {
      throw new CallerFault("information assignment needs a reason");
    }
    OffsetDateTime at = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    transactions.inTransaction(
        () -> {
          access.requireSelection(context);
          Long projectId =
              project == null
                  ? null
                  : jdbc.queryForObject(
                      "SELECT id FROM projects WHERE name = ?", Long.class, project);
          String policy = visibility.name().toLowerCase(java.util.Locale.ROOT);
          int changed =
              jdbc.update(
                  "UPDATE information_document_policies SET owner_handle = ?,"
                      + " visibility = ?, project_id = ?, assigned_at = ?"
                      + " WHERE document_id = ? AND visibility = 'quarantined'"
                      + " AND owner_handle IS NULL",
                  owner,
                  policy,
                  projectId,
                  at,
                  document);
          if (changed != 1) {
            throw new NotFoundFault("no quarantined document is available for assignment");
          }
          jdbc.update(
              "UPDATE information_resources SET owner_handle=?,project_id=?,namespace=? WHERE id=? AND namespace='legacy' AND owner_handle IS NULL",
              owner,
              projectId,
              project == null ? "account:" + owner : "project:" + project,
              document);
          jdbc.update(
              "INSERT INTO information_policy_assignments"
                  + " (id, document_id, actor_handle, owner_handle, visibility, project_name,"
                  + " reason, assigned_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
              UUID.randomUUID(),
              document,
              actor,
              owner,
              policy,
              project,
              reason,
              at);
          return null;
        });
  }
}
