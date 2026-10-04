package io.aeyer.plowshare.a2a;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Local listener/card readiness only; end-to-end execution is a separate smoke test. */
public final class HealthCheck {
  public static void main(String[] args) throws Exception {
    String port = System.getenv().getOrDefault("PLOWSHARE_A2A_PORT", "8093");
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/.well-known/agent-card.json"))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build();
    var response =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()
            .send(request, HttpResponse.BodyHandlers.discarding());
    if (response.statusCode() != 200) System.exit(1);
  }
}
