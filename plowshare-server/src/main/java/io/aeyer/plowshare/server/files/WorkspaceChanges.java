package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.Home;
import java.util.Objects;

/** Session-bound filesystem hints, never proof of content changes or permission to index. */
public interface WorkspaceChanges {
  record Change(Home home, String owner, String session) {
    public Change {
      Objects.requireNonNull(home);
      WorkspaceValues.identity(owner, "change owner");
      WorkspaceValues.identity(session, "change session");
    }
  }

  /** Whether the client may send filesystem hints on an acknowledged file-provider socket. */
  boolean enabled();

  /** Bounded/coalesced admission only; the socket callback must not perform database work. */
  void changed(Change change);
}
