package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.protocol.ActingContext;
import java.util.UUID;

/** Temporary, bounded consent admissions owned by an authenticated physical connection. */
public interface ActingContexts {
  record Connection(String id, String session, String actor) {
    public Connection {
      if (id == null
          || id.isBlank()
          || id.length() > 512
          || session == null
          || session.isBlank()
          || session.length() > 512)
        throw new IllegalArgumentException("Acting needs an authenticated physical connection");
      actor = ActingValues.serviceActor(actor);
    }
  }

  ActingContext.Opened open(Connection connection, ActingContext.Open request);

  /**
   * Resolve selection without mutating connection identity. Later permission checks remain
   * required.
   */
  RequestAuthority.Delegated resolve(Connection connection, UUID context);

  void close(Connection connection, UUID context);

  /** Connection teardown removes all its contexts; a reconnect never inherits them. */
  void disconnected(String connection);
}
