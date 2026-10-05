package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayControl;

/**
 * Manager-authorized project controls; built-in subscriptions and system topics stay owner-managed.
 */
public interface RelayOperations {
  RelayControl.Result operate(String account, RelayControl.Request request);
}
