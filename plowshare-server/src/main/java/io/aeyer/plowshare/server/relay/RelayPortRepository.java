package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;

/**
 * Short atomic transactions own batch fencing and cursor acknowledgement. Never calls detectors.
 */
public interface RelayPortRepository {
  RelayPort.Batch consume(Relay.TopicKey topic, String account, RelayPort.Consume request);

  RelayPort.Acknowledged acknowledge(Relay.TopicKey topic, String account, RelayPort.Ack request);
}
