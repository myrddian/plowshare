package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.embedding.QueryEmbeddings.Origin;
import io.aeyer.plowshare.server.information.InformationContext;
import io.aeyer.plowshare.server.information.InformationReadSelection;
import java.util.List;
import java.util.UUID;

/** Semantic passage reads retain the run's inference lifecycle while selecting sources by RBAC. */
public interface SelectedPassages {
  List<DocumentStore.Retrieved> retrieve(
      InformationReadSelection selection,
      InformationContext queryHome,
      Origin origin,
      String question,
      UUID document,
      int limit);
}
