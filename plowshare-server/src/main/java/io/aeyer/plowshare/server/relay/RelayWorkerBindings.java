package io.aeyer.plowshare.server.relay;

import java.util.List;

/** Explicit current execution identities for local Relay worker supervision. */
@FunctionalInterface
public interface RelayWorkerBindings {
  /**
   * A complete immutable snapshot. Failure refuses enrollment; it must not preserve old authority.
   */
  List<RelayWorkerProperties.Project> current();
}
