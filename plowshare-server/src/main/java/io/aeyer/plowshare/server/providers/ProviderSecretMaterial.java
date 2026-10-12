package io.aeyer.plowshare.server.providers;

import io.aeyer.plowshare.protocol.ProviderCredentials;

/**
 * Stored only as authenticated ciphertext. Raw JSON conversion belongs to its persistence codec.
 */
public record ProviderSecretMaterial(
    String access, String refresh, String clientSecret, String apiKey, String verifier) {
  public ProviderSecretMaterial {
    for (String value : new String[] {access, refresh, clientSecret, apiKey, verifier})
      if (value != null) ProviderCredentials.secret(value);
    if (verifier != null
        && (access != null
            || refresh != null
            || clientSecret != null
            || apiKey != null
            || !verifier.matches("[A-Za-z0-9_-]{43}")))
      throw new IllegalArgumentException("Invalid PKCE material");
    if (apiKey != null
        && (access != null || refresh != null || clientSecret != null || verifier != null))
      throw new IllegalArgumentException("Invalid API key material");
    if (refresh != null && access == null)
      throw new IllegalArgumentException("Refresh material requires an access grant");
    if (access == null
        && refresh == null
        && clientSecret == null
        && apiKey == null
        && verifier == null) throw new IllegalArgumentException("Credential material is empty");
  }

  @Override
  public String toString() {
    return "ProviderSecretMaterial[<redacted>]";
  }
}
