package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;

/** Authenticated public SDK operations only; implementations never replay an uncertain mutation. */
public interface RelayGateway {
  RelayPort.Batch consume(RelayPort.Consume request) throws IOException;

  RelayPort.Published publish(RelayPort.Publish request) throws IOException;

  RelayPort.Acknowledged acknowledge(RelayPort.Ack request) throws IOException;

  RelayLog.Page log(RelayLog.Query request) throws IOException;
}
