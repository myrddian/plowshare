package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.api.CitationsResponse;
import io.aeyer.plowshare.server.api.DocumentDetailResponse;
import io.aeyer.plowshare.server.information.InformationReadSelection;
import java.util.List;
import java.util.UUID;

/** Live source policy and durable input retention for explicit scoped document reads. */
public interface SelectedDocumentRepository {
  /** Preflight before inference, and a fresh fence before disclosure; no content is loaded. */
  void require(InformationReadSelection selection, UUID document);

  /** Batch-retain only disclosed exact revisions, failing if any became unreadable. */
  void retain(InformationReadSelection selection, List<UUID> documents);

  DocumentDetailResponse outline(InformationReadSelection selection, UUID document);

  /** Filter both source policy and conversation access before the bounded result limit. */
  CitationsResponse citations(
      InformationReadSelection selection, UUID document, String conversation, int limit);
}
