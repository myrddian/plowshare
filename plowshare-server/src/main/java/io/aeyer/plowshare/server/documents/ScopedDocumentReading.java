package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.api.CitationsResponse;
import io.aeyer.plowshare.server.api.DocumentDetailResponse;
import io.aeyer.plowshare.server.api.RetrieveResponse;
import io.aeyer.plowshare.server.embedding.QueryEmbeddings.Origin;
import io.aeyer.plowshare.server.information.InformationContext;
import io.aeyer.plowshare.server.information.InformationReadSelection;
import java.util.UUID;

/** Read-only model capability; scoped locators never change the durable run or inference home. */
public interface ScopedDocumentReading {
  RetrieveResponse retrieve(
      InformationReadSelection selection,
      InformationContext queryHome,
      Origin origin,
      String question,
      UUID document,
      int limit);

  DocumentDetailResponse outline(InformationReadSelection selection, UUID document);

  CitationsResponse citations(
      InformationReadSelection selection, UUID document, String conversation, int limit);
}
