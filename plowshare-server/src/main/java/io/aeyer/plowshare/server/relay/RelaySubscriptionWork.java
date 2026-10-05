package io.aeyer.plowshare.server.relay;

import java.util.List;

/** Independent bounded consumption shared by background workers and explicit processing. */
public interface RelaySubscriptionWork {
  /** Lists current active keys after source and policy authority checks; no broker effects. */
  List<Relay.SubscriptionKey> subscriptions(RelayProjectFiles.Access access);

  /**
   * Processes one subscription with a distributed lease. Routing and effects run outside database
   * transactions. A competing owner returns an idle result; a gap never advances the cursor.
   * Inactive subscriptions do no work; previously pinned branches remain retained.
   */
  Result process(
      RelayProjectFiles.Access access,
      Relay.SubscriptionKey key,
      String worker,
      int admissionLimit,
      int dispatchLimit);

  record Result(int admitted, int dispatched, Relay.Gap gap) {
    public Result {
      if (admitted < 0 || admitted > 32 || dispatched < 0 || dispatched > 32)
        throw new IllegalArgumentException("invalid subscription processing counts");
    }

    public boolean progressed() {
      return admitted > 0 || dispatched > 0;
    }
  }
}
