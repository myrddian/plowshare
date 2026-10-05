package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;

/** Resolves durable project identities for filesystem layout without exposing SQL. */
public interface ProjectDirectories {
  /** Global returns null; an absent project fails rather than sharing an unknown directory. */
  Long existing(Home home);

  /** Global returns null; registers an ordinary first-write project atomically if absent. */
  Long register(Home home);
}
