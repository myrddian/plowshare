package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Explicit migration of quarantined policies. Not exposed as a model tool or a REST controller. */
public final class DocumentPolicyAssignments {
  private InformationWriteGates gates;

  public void useWriteGates(InformationWriteGates gates) {
    this.gates = gates;
  }

  private final DocumentPolicyRepository repository;
  private final InformationJobs inputs;
  private final UnitOfWork transactions;
  private final InformationAccess access;
  private final Clock clock;
  private final String migrationAccount;

  public DocumentPolicyAssignments(
      DocumentPolicyRepository repository,
      InformationJobs inputs,
      UnitOfWork transactions,
      InformationAccess access,
      Clock clock,
      String migrationAccount) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.inputs = Objects.requireNonNull(inputs, "inputs");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.access = Objects.requireNonNull(access, "access");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.migrationAccount = migrationAccount;
  }

  public io.aeyer.plowshare.protocol.InformationMigration.Inventory inventory(
      String actor, int limit) {
    return inventory(actor, limit, 0);
  }

  public io.aeyer.plowshare.protocol.InformationMigration.Inventory inventory(
      String actor, int limit, int offset) {
    requireOperator(actor);
    if (offset < 0) throw new CallerFault("migration offset must be nonnegative");
    if (limit < 1 || limit > 100)
      throw new CallerFault("migration inventory limit must be between 1 and 100");
    return repository.inventory(limit, offset);
  }

  public io.aeyer.plowshare.protocol.InformationMigration.Inspection inspect(
      String actor, String payload, String reason) {
    return inspect(actor, payload, reason, 100, 0);
  }

  public io.aeyer.plowshare.protocol.InformationMigration.Inspection inspect(
      String actor, String payload, String reason, int limit, int offset) {
    requireOperator(actor);
    if (limit < 1 || limit > 100 || offset < 0)
      throw new CallerFault("inspection needs limit 1..100 and nonnegative offset");
    if (reason == null || reason.isBlank())
      throw new CallerFault("migration inspection needs a reason");
    return transactions.inTransaction(
        () -> {
          var inspection = repository.inspect(payload, limit, offset);
          repository.audit(
              actor, payload, null, "inspect", java.util.List.of(), reason, clock.instant());
          return inspection;
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
        new InformationGateIdentity.Release(payload, owner, selection, revisions, reason),
        java.util.List.of(),
        session,
        InformationGateResult.Completed.class,
        () -> {
          releaseUnchecked(actor, payload, owner, selection, revisions, reason);
          return new InformationGateResult.Completed(true);
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
          repository.lockPayload(payload);
          for (UUID revision : revisions)
            repository.requireReadable(revision, owner, context.selection());
          repository.adoptPayload(payload, owner);
          inputs.bind(payload, context, revisions);
          repository.releasePayload(payload);
          repository.audit(actor, payload, owner, "release", revisions, reason, clock.instant());
          return null;
        });
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
        new InformationGateIdentity.Adopt(document, owner, visibility, project, reason),
        java.util.List.of(),
        session,
        InformationGateResult.Completed.class,
        () -> {
          assignUnchecked(actor, document, owner, visibility, project, reason);
          return new InformationGateResult.Completed(true);
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
    transactions.inTransaction(
        () -> {
          access.requireSelection(context);
          repository.assign(actor, document, owner, destination, reason, clock.instant());
          return null;
        });
  }
}
