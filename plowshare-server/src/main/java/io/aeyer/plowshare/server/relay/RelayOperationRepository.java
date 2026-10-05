package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayControl;
import java.time.Duration;
import java.util.Optional;

/** Atomic operator mutations, incarnation checks and retained audit receipts. No receiver calls. */
public interface RelayOperationRepository {
  /** Authenticated exact-request receipt lookup; conflicting request-ID reuse is refused. */
  Optional<RelayControl.Result> receipt(
      RelayProjectFiles.Access access, RelayControl.Request request);

  /**
   * Applies one SQL mutation and records actor/reason/outcome in the same transaction. Read-only
   * receiver inspection, if needed, completes before this call. Removal requires empty metadata,
   * with no admitted history or live consumer lease. Abandoned unknown effects remain retained.
   */
  RelayControl.Result apply(
      RelayProjectFiles.Access access,
      RelayControl.Request request,
      Optional<RelayDeliveries.Resolution> resolution);

  /** Bounded cleanup of completed audit receipts; generations still fence delayed requests. */
  int prune(Duration retention, int limit);
}
