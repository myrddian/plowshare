package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;

/**
 * Short atomic transactions own batch fencing and cursor acknowledgement. Never calls detectors.
 */
public interface RelayPortRepository {
  default RelayPort.Batch consume(Relay.TopicKey topic, String account, RelayPort.Consume request) {
    return consume(topic, account, request, RelayReadBudget.SEGMENTED);
  }

  RelayPort.Batch consume(
      Relay.TopicKey topic, String account, RelayPort.Consume request, RelayReadBudget budget);

  RelayPort.Acknowledged acknowledge(Relay.TopicKey topic, String account, RelayPort.Ack request);
}
