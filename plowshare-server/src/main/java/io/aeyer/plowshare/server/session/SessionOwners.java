package io.aeyer.plowshare.server.session;

import java.util.Optional;

/** Authenticated ownership of the current session; absence never means an anonymous grant. */
public interface SessionOwners {
  Optional<String> accountOf(String session);
}
