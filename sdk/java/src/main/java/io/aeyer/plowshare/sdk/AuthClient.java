package io.aeyer.plowshare.sdk;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import okhttp3.*;

/** Authentication is an HTTP bootstrap exception; all application operations use WS. */
public final class AuthClient {
  private AuthClient() {}

  private record LoginReply(String access, boolean mustChangePassword) {
    private LoginReply {
      access = ContractChecks.identity(access, "access token");
    }
  }

  public static String login(String origin, String handle, String password, Duration timeout)
      throws IOException {
    HttpUrl base = HttpUrl.parse(origin);
    if (base == null
        || !base.username().isEmpty()
        || !base.password().isEmpty()
        || base.query() != null
        || base.fragment() != null
        || !base.encodedPath().equals("/"))
      throw new IllegalArgumentException(
          "Plowshare login requires an HTTP(S) origin without credentials or a path");
    if (handle == null || handle.isBlank() || password == null || password.isBlank())
      throw new IllegalArgumentException("Plowshare account handle and password are required");
    var json = SdkJson.mapper();
    var http =
        new OkHttpClient.Builder()
            .callTimeout(timeout)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();
    try {
      var request =
          new Request.Builder()
              .url(base.resolve("/v1/auth/login"))
              .header("X-Plowshare-Token-Delivery", "body")
              .post(
                  RequestBody.create(
                      json.writeValueAsBytes(Map.of("handle", handle, "password", password)),
                      MediaType.get("application/json")))
              .build();
      try (var response = http.newCall(request).execute()) {
        if (response.code() != 200 || response.body() == null)
          throw new IOException(
              "Plowshare account login was refused (HTTP " + response.code() + ")");
        byte[] bytes = response.body().byteStream().readNBytes(16385);
        if (bytes.length > 16384)
          throw new IOException("Plowshare login response exceeded its supported size");
        var body = SdkJson.decode(json, json.readTree(bytes), LoginReply.class);
        if (body.mustChangePassword())
          throw new IOException(
              "Complete the account's first password change before starting an adapter");
        return body.access();
      }
    } finally {
      http.dispatcher().executorService().shutdown();
      http.connectionPool().evictAll();
    }
  }
}
