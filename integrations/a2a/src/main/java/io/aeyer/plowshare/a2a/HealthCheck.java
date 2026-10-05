package io.aeyer.plowshare.a2a;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Listener/card readiness at an explicitly configured probe URL; execution is checked separately.
 */
public final class HealthCheck {
  private HealthCheck() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 0) throw new IllegalArgumentException("HealthCheck takes no arguments");
    URI uri = probe(System.getenv("PLOWSHARE_A2A_HEALTH_URL"));
    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
    var response =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(request, HttpResponse.BodyHandlers.discarding());
    if (response.statusCode() != 200) System.exit(1);
  }

  /** Validate before opening a connection; never infer the listener host or deployment port. */
  static URI probe(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 4096
        || !value.equals(value.strip())
        || value.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("explicit PLOWSHARE_A2A_HEALTH_URL required");
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("invalid PLOWSHARE_A2A_HEALTH_URL");
    }
    if (!java.util.Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || uri.getPort() == 0
        || uri.getPort() > 65535
        || !"/.well-known/agent-card.json".equals(uri.getRawPath()))
      throw new IllegalArgumentException(
          "health URL must be an HTTP(S) Agent Card URL without credentials, query or fragment");
    return uri;
  }
}
