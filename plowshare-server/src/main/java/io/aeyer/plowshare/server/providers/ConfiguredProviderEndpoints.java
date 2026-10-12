package io.aeyer.plowshare.server.providers;

import io.aeyer.plowshare.protocol.ProviderCredentials;
import io.aeyer.plowshare.protocol.ProviderCredentials.Definition;
import java.net.URI;
import java.util.Set;

/**
 * Exact HTTPS origins and callback; remote discovery may confirm configuration but never replace
 * it.
 */
public final class ConfiguredProviderEndpoints implements ProviderEndpointPolicy {
  public static final String CALLBACK_PATH = "/provider-credentials/oauth/callback";
  private final Set<String> origins;
  private final String callback;

  public ConfiguredProviderEndpoints(Set<String> origins, String callback) {
    this(origins, callback, "");
  }

  public ConfiguredProviderEndpoints(Set<String> origins, String callback, String contextPath) {
    if (origins.isEmpty() || origins.size() > 128)
      throw new IllegalArgumentException("Configure bounded provider allowed-origins");
    this.origins =
        origins.stream()
            .map(
                value -> {
                  URI uri = ProviderCredentials.https(value);
                  if (!uri.getRawPath().isEmpty())
                    throw new IllegalArgumentException(
                        "Provider allowed-origins must have no path");
                  return origin(uri);
                })
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    URI redirect = ProviderCredentials.https(callback);
    if (contextPath == null
        || !contextPath.matches("(?:/[A-Za-z0-9._-]+)*")
        || !(contextPath + CALLBACK_PATH).equals(redirect.getRawPath()))
      throw new IllegalArgumentException(
          "Provider callback must name the credential callback route");
    this.callback = callback;
  }

  private static String origin(URI uri) {
    return uri.getScheme() + "://" + uri.getRawAuthority();
  }

  private void allowed(String value) {
    if (!origins.contains(origin(ProviderCredentials.https(value))))
      throw new IllegalArgumentException(
          "Provider destination is outside configured allowed-origins");
  }

  @Override
  public void require(Definition definition) {
    allowed(definition.endpoint());
    if (definition.oauth() == null) return;
    var oauth = definition.oauth();
    for (String url :
        java.util.List.of(
            oauth.issuer(),
            oauth.resource(),
            oauth.resourceMetadataUrl(),
            oauth.issuerMetadataUrl(),
            oauth.authorizationEndpoint(),
            oauth.tokenEndpoint())) allowed(url);
    if (!callback.equals(oauth.redirectUri()))
      throw new IllegalArgumentException("Provider callback does not match configured redirect");
    if (!definition.endpoint().equals(oauth.resource()))
      throw new IllegalArgumentException(
          "OAuth resource must exactly match the configured provider endpoint");
  }
}
