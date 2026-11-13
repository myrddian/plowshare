package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;

/** Authenticated public SDK operations only; implementations never replay an uncertain mutation. */
public interface RelayGateway {
  /**
   * A positive transport guarantee: no publication request was submitted. Never inferred from
   * absence.
   */
  final class PublicationNotSubmitted extends IOException {
    public PublicationNotSubmitted() {
      super("Reply publication was not submitted");
    }
  }

  RelayPort.Batch consume(RelayPort.Consume request) throws IOException;

  RelayPort.Published publish(RelayPort.Publish request) throws IOException;

  RelayPort.Acknowledged acknowledge(RelayPort.Ack request) throws IOException;

  RelayLog.Page log(RelayLog.Query request) throws IOException;
}
