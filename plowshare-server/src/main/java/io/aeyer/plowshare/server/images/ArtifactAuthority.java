package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.server.relay.tools.RelayToolRepository;
import java.util.UUID;

/** Current producer authority, derived solely from authenticated actor and retained invocation. */
public interface ArtifactAuthority {
  /**
   * Called within a short unit of work; locks hosted generation/credential fences in their existing
   * order.
   */
  RelayToolRepository.Stored require(String actor, UUID invocation);
}
