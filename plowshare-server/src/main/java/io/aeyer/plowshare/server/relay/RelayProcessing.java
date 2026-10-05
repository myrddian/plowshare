package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayLog;

/**
 * Explicit bounded processing under an authenticated account. Gaps require separate
 * acknowledgement.
 */
public interface RelayProcessing {
  RelayLog.Processed process(String account, RelayLog.Process query);
}
