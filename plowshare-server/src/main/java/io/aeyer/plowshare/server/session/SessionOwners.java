package io.aeyer.plowshare.server.session;

import java.util.Optional;
import java.util.Set;

/** Authenticated ownership of the current session; absence never means an anonymous grant. */
public interface SessionOwners {
  SessionOwners NONE = session -> Optional.empty();

  Optional<String> accountOf(String session);

  /** Attachments whose private recovery proofs were verified on this transport. */
  default Set<String> logicalSessions(String session) {
    return session == null ? Set.of() : Set.of(canonical(session));
  }

  /** Logical attachment after verified recovery; unknown sessions retain their own identity. */
  default String canonical(String session) {
    return session;
  }
}
