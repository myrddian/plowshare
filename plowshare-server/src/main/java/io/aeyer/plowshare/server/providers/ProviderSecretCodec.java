package io.aeyer.plowshare.server.providers;

/** Typed, closed codec for encrypted material, independent of the management API. */
public interface ProviderSecretCodec {
  String encode(ProviderSecretMaterial material);

  ProviderSecretMaterial decode(String material);
}
