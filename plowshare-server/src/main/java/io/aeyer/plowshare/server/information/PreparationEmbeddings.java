package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.embedding.*;
import java.util.List;

/** Existing document allowance shared by preparation chat and each actual embedding request. */
public interface PreparationEmbeddings {
  Budget budget(InformationLifecycle.Lease lease);

  EmbeddingAuthorization forSources(
      InformationLifecycle.Lease lease, List<EmbeddingWorkRepository.Key> sources);
}
