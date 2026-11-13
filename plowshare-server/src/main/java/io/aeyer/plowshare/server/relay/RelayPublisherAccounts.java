package io.aeyer.plowshare.server.relay;

import java.util.Optional;

/**
 * Port-owned authenticated publisher provenance; payload fields and worker identities are not
 * actors.
 */
public interface RelayPublisherAccounts {
  /**
   * Records the SDK publisher derived from an authenticated account; a repeated binding is a no-op.
   */
  void rememberPublisher(String account);

  /** Returns a recorded account only for its exact server-derived SDK publisher identity. */
  Optional<String> publisherAccount(String publisher);
}
