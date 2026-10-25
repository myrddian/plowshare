package io.aeyer.plowshare.server.auth;

import java.util.Collection;
import java.util.Set;

/** Current account/session facts; a socket identity never creates its own authorization lease. */
public interface SessionAuthority {
  record Session(String handle, long version) {
    public Session {
      if (handle == null || handle.isBlank() || version < 0)
        throw new IllegalArgumentException("Current account session required");
    }
  }

  boolean sessionCurrent(String handle, long version);

  /** Recheck distinct identities in bounded batches, retaining exact versions in the result. */
  Set<Session> currentSessions(Collection<Session> sessions);
}
