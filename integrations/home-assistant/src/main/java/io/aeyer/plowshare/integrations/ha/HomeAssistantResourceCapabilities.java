package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.Description;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.Query;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ReadResult;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.Resolution;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import java.io.IOException;

/**
 * Policy-filtered read capabilities of one configured HA instance. Every operation shares its
 * caller's finite budget. Discovery grants no authority and never executes a device action. The
 * identity owner remains open until all borrowing SDK provider work physically settles.
 */
public interface HomeAssistantResourceCapabilities extends AutoCloseable {
  /** A known schema/epoch mismatch; the caller must resolve again rather than reuse old fields. */
  final class SchemaChangedException extends IOException {
    public SchemaChangedException() {
      super("Capability schema changed; resolve the current catalogue before reading");
    }
  }

  /** Resolves all permitted resource/field pairs before paging; unavailable sources remain ties. */
  Resolution resolve(Query query, OperationBudget budget) throws IOException;

  /**
   * Describes one exact resource/schema page without returning values. Unknown and denied
   * references share the same unavailable result; known revision mismatch is typed separately.
   */
  Description describe(
      String resourceId, String revision, int offset, int limit, OperationBudget budget)
      throws IOException;

  /**
   * Fresh native batch with typed per-field evidence; values are returned only for a valid whole
   * selection.
   */
  ReadResult read(FieldReadRequest request, OperationBudget budget) throws IOException;

  /** Stops new collection and releases identity ownership only after borrowing work settles. */
  @Override
  void close() throws IOException;
}
