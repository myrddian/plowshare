package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.api.CitationsResponse;
import io.aeyer.plowshare.server.api.DocumentDetailResponse;
import io.aeyer.plowshare.server.api.RetrieveResponse;
import io.aeyer.plowshare.server.embedding.QueryEmbeddings.Origin;
import io.aeyer.plowshare.server.information.InformationContext;
import io.aeyer.plowshare.server.information.InformationReadSelection;
import java.util.Objects;
import java.util.UUID;

/** Provider IO completes before the final live authorization and batch retention fence. */
public final class SelectedDocumentReading implements ScopedDocumentReading {
  private final SelectedDocumentRepository repository;
  private final SelectedPassages passages;

  public SelectedDocumentReading(SelectedDocumentRepository repository, SelectedPassages passages) {
    this.repository = Objects.requireNonNull(repository);
    this.passages = Objects.requireNonNull(passages);
  }

  public RetrieveResponse retrieve(
      InformationReadSelection selection,
      InformationContext queryHome,
      Origin origin,
      String question,
      UUID document,
      int limit) {
    repository.require(selection, document);
    var checked =
        new Origin(
            origin.invocation(),
            () -> {
              origin.validity().run();
              repository.require(selection, document);
            },
            origin.budget(),
            origin.globalRead());
    var hits = passages.retrieve(selection, queryHome, checked, question, document, limit);
    origin.validity().run();
    repository.retain(
        selection, hits.stream().map(hit -> hit.chunk().documentId()).distinct().toList());
    return RetrieveResponse.of(question, document, limit, hits);
  }

  public DocumentDetailResponse outline(InformationReadSelection selection, UUID document) {
    return repository.outline(selection, document);
  }

  public CitationsResponse citations(
      InformationReadSelection selection, UUID document, String conversation, int limit) {
    return repository.citations(selection, document, conversation, limit);
  }
}
