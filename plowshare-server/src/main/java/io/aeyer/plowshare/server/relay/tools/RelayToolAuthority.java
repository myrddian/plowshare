package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.relay.RelayPortProperties;

/** Live project authority used immediately before an external Relay operation. */
public interface RelayToolAuthority {
  boolean permits(
      String account,
      String project,
      String topic,
      RelayPortProperties.Direction direction,
      String group);

  void validateResult(String account, RelayPort.Publish request);
}
