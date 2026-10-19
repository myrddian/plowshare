package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationContent;
import io.aeyer.plowshare.protocol.SchemaDefinition;
import io.aeyer.plowshare.protocol.SchemaValue;
import java.util.Optional;
import java.util.UUID;

/**
 * Registry persistence. Mutating methods require the caller's transaction and defend scope fences.
 */
public interface InformationContentTypeRepository {
  /**
   * Immutable namespace/page projection after current caller selection and draft-management checks.
   * Sharing requires DOCUMENTS/AUTHORITY fences; bounded cold reads preserve complete direct
   * fallback.
   */
  InformationContent.Versions list(String namespace, boolean drafts, int limit, int offset);

  /** A lock=true read always locks fresh type then version rows in the caller transaction. */
  Optional<InformationContent.Version> version(String namespace, UUID version, boolean lock);

  /**
   * The caller checks current revision access on every operation, including a retained-value hit.
   * Exact revision/version identity and DOCUMENTS/AUTHORITY fences cover binding
   * deletion/redaction; this immutable DTO never grants access and never substitutes for mutation
   * locks.
   */
  Optional<InformationContent.Contract> attached(UUID revision, UUID version);

  InformationContent.Version create(
      String namespace,
      String actor,
      UUID type,
      Long expectedControlRevision,
      String slug,
      String label,
      String description,
      SchemaDefinition schema,
      SchemaValue.ObjectValue presentation);

  InformationContent.Version transition(
      String namespace, UUID version, long expected, boolean activate);
}
