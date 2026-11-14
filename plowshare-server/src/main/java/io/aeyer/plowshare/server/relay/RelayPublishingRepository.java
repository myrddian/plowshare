package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.access.OperationFence;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns execution provenance and the receipt ledger independently of broker retention. */
public interface RelayPublishingRepository {
  /** Match retained accounting run/turn/account/project/definition before inheriting ancestry. */
  Optional<RelayCausation> ancestry(long project, UsageAttribution owner);

  /**
   * Atomically retains intent, appends once and saves its receipt; conflicts and failures roll
   * back.
   */
  default RelayPublishing.Receipt publish(Intent intent) {
    return publish(intent, OperationFence.none());
  }

  /**
   * As {@link #publish(Intent)}, running {@code fence} inside the transaction after the receipt row
   * insert has serialized same-call contenders and before any broker append or receipt read. A
   * fence failure rolls back the inserted receipt row with everything else.
   */
  RelayPublishing.Receipt publish(Intent intent, OperationFence fence);

  record Intent(
      UUID id,
      long project,
      String projectName,
      UsageAttribution owner,
      String call,
      RelayPublishing.Request request,
      String fingerprint,
      Instant occurredAt,
      RelayCausation causation) {
    public Intent {
      Objects.requireNonNull(id);
      projectName = RelayPort.identity(projectName);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(request);
      Objects.requireNonNull(occurredAt);
      Objects.requireNonNull(causation);
      RelayPort.identity(call);
      if (project < 1
          || owner.status() != UsageAttribution.Status.ATTRIBUTED
          || owner.scope() != UsageAttribution.Scope.PROJECT
          || !Long.toString(project).equals(owner.projectId())
          || owner.agentName() == null
          || owner.runs().id() == null
          || owner.conversations().id() == null
          || owner.turnOrdinal() == null
          || owner.turnOrdinal() < 1
          || owner.stepOrdinal() == null
          || owner.stepOrdinal() < 1
          || fingerprint == null
          || !fingerprint.matches("[0-9a-f]{64}")
          || causation.depth() < 1
          || !owner.runs().id().equals(causation.parentId()))
        throw new IllegalArgumentException("Invalid execution publication intent");
      RelayPort.identity(owner.accountHandle());
      RelayPort.identity(owner.agentName());
      RelayPort.identity(owner.runs().id());
      RelayPort.identity(owner.conversations().id());
    }
  }
}
