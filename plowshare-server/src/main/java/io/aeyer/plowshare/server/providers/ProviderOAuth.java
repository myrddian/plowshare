package io.aeyer.plowshare.server.providers;

import io.aeyer.plowshare.protocol.ProviderCredentials.OAuth;
import java.time.OffsetDateTime;

/** Bounded, redirect-free upstream OAuth; no implicit retry after token-endpoint uncertainty. */
public interface ProviderOAuth {
  record Tokens(String access, String refresh, OffsetDateTime expiresAt) {
    public Tokens {
      io.aeyer.plowshare.protocol.ProviderCredentials.secret(access);
      if (refresh != null) io.aeyer.plowshare.protocol.ProviderCredentials.secret(refresh);
      java.util.Objects.requireNonNull(expiresAt);
    }

    @Override
    public String toString() {
      return "Tokens[access=<redacted>, refresh=<redacted>]";
    }
  }

  /**
   * Validate resource/issuer metadata against every configured endpoint and S256/issuer support.
   */
  void discover(OAuth configuration, boolean pkce);

  String authorizationUrl(OAuth configuration, String state, String verifier);

  Tokens exchange(OAuth configuration, String code, String verifier);

  Tokens refresh(OAuth configuration, String refresh);

  Tokens machine(OAuth configuration, String clientSecret);
}
