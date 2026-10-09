package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;

/** Authenticated topic ingress/egress. Implementations recheck deployment grants on every call. */
public interface RelayPorts {
  RelayPort.Published publish(String account, RelayPort.Publish request);

  default RelayPort.Batch consume(String account, RelayPort.Consume request) {
    return consume(account, request, RelayReadBudget.SEGMENTED);
  }

  RelayPort.Batch consume(String account, RelayPort.Consume request, RelayReadBudget budget);

  RelayPort.Acknowledged acknowledge(String account, RelayPort.Ack request);
}
