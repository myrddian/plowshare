package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationMigration.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Quarantine reads and explicit adoption. Mutations participate in the caller's transaction. */
public interface DocumentPolicyRepository {
  Inventory inventory(int limit, int offset);

  Inspection inspect(String payload, int limit, int offset);

  /** Locks the quarantine row until the transaction ends; a missing row is refused. */
  void lockPayload(String payload);

  void requireReadable(UUID revision, String owner, InformationContext.Selection selection);

  void adoptPayload(String payload, String owner);

  void releasePayload(String payload);

  void audit(
      String actor,
      String payload,
      String owner,
      String action,
      List<UUID> revisions,
      String reason,
      Instant at);

  /** Adopts a previously unowned document exactly once, with its audit row atomically. */
  void assign(
      String actor,
      UUID document,
      String owner,
      InformationContext.Selection destination,
      String reason,
      Instant at);
}
