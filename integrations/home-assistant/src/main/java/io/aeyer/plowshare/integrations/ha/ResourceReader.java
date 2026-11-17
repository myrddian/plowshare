package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadBatch;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadRequest;
import java.io.IOException;

/**
 * Reads a bounded batch from one binding/credential/policy snapshot. Implementations authorize the
 * whole selection before issuing one batch operation, preserve request order, and return an
 * explicit missing/unavailable result per selected resource. They must not perform per-entity
 * network reads, silently refresh a stale revision, or reuse state across a connection epoch.
 */
public interface ResourceReader {
  ReadBatch read(ReadRequest request, OperationBudget budget) throws IOException;
}
