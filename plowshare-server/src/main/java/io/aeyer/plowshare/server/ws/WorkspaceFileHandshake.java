package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.session.WorkspaceRecovery;
import java.util.List;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/** Negotiates a private recovery proof in a header, never a URL. Authentication is separate. */
final class WorkspaceFileHandshake extends DefaultHandshakeHandler {
  @Override
  protected String selectProtocol(List<String> requested, WebSocketHandler handler) {
    for (String protocol : requested) {
      if (protocol.startsWith(WorkspaceRecovery.FILE_PROTOCOL_PREFIX)
          && protocol
              .substring(WorkspaceRecovery.FILE_PROTOCOL_PREFIX.length())
              .matches("[a-f0-9]{64}")) {
        return protocol;
      }
    }
    return null;
  }
}
