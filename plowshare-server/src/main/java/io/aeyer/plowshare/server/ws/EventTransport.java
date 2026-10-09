package io.aeyer.plowshare.server.ws;

import java.io.IOException;
import java.util.Optional;

/** Connection-owned framing boundary. Empty input never reaches operation dispatch. */
interface EventTransport extends AutoCloseable {
  Optional<String> receive(String frame) throws IOException;

  void send(String frame) throws IOException;

  @Override
  void close();
}
