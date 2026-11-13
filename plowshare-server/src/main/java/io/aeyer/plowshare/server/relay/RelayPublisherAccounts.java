package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;
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

  record Execution(String account, String definition) {
    public Execution {
      account = RelayPort.identity(account);
      definition = RelayPort.identity(definition);
    }
  }

  /** Exact ledger-backed execution identity, including historical SDK-envelope provenance. */
  Optional<Execution> executionIdentity(Relay.Publication publication);

  /**
   * Identifies retained execution publications, including legacy events written under SDK names.
   * Evidence is the exact project, event ID, topic and position in the immutable call ledger; a
   * payload assertion or SDK prefix cannot establish execution or person provenance.
   */
  boolean executionPublication(Relay.Publication publication);
}
