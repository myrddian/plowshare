package io.aeyer.plowshare.server.auth;

import java.util.Optional;

/**
 * Digest-backed account sessions. Refresh locks the chain and rotates one grant; reuse revokes the
 * chain, and logout shares that lock so refresh cannot resurrect a revoked session. Issuance checks
 * the current account credential version. Ephemeral bootstrap tokens remain owned by TokenStore.
 */
public interface DurableSessions {
  TokenStore.Pair issue(String handle, boolean restricted);

  TokenStore.Pair issue(String handle, boolean restricted, String expectedHash);

  Optional<TokenStore.Authentication> authentication(String presented);

  boolean valid(String presented);

  Optional<String> handle(String presented);

  boolean restricted(String presented);

  Optional<TokenStore.Pair> refresh(String presented);

  Optional<Long> version(String handle);

  void revoke(String presented);
}
