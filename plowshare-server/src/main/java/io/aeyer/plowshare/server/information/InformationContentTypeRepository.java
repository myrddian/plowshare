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
  InformationContent.Versions list(String namespace, boolean drafts, int limit, int offset);

  Optional<InformationContent.Version> version(String namespace, UUID version, boolean lock);

  /** The caller checks revision access before this exact attached-contract lookup. */
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
