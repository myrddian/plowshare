package io.aeyer.plowshare.sdk;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import okhttp3.*;

/**
 * Native redirect-free authentication with bounded responses and no HTTP retry.
 * Interrupted/ambiguous refreshes remain fenced by the owning credential store.
 */
public final class HttpAuthentication implements UserAuthentication {
  private final String origin;
  private final Duration timeout;
  private final String accessCookie;
  private final String refreshCookie;

  public HttpAuthentication(String origin) {
    this(origin, Duration.ofSeconds(30), "ps_access", "ps_refresh");
  }

  public HttpAuthentication(
      String origin, Duration timeout, String accessCookie, String refreshCookie) {
    this.origin = AuthState.canonicalOrigin(origin);
    if (timeout == null || timeout.isZero() || timeout.isNegative())
      throw new IllegalArgumentException("Authentication timeout must be positive");
    if (accessCookie == null
        || refreshCookie == null
        || !accessCookie.matches("[A-Za-z0-9_]{1,64}")
        || !refreshCookie.matches("[A-Za-z0-9_]{1,64}")
        || accessCookie.equals(refreshCookie))
      throw new IllegalArgumentException("Invalid authentication cookie names");
    this.accessCookie = accessCookie;
    this.refreshCookie = refreshCookie;
    this.timeout = timeout;
  }

  @Override
  public String origin() {
    return origin;
  }

  private <T> T request(Request request, AsyncSupport.Decoder<Response, T> decoder)
      throws IOException {
    var http =
        new OkHttpClient.Builder()
            .callTimeout(timeout)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();
    try {
      return Plowshare.await(HttpAsync.call(http.newCall(request), decoder));
    } catch (IOException failure) {
      // HTTP/library exceptions may contain credential-bearing headers. Only a
      // fixed diagnostic crosses this boundary; interruption remains observable.
      throw new IOException("Authentication refused, invalid or unavailable");
    } finally {
      // This optional bootstrap owns its workers/pool. A short-lived native
      // consumer must not remain alive waiting for OkHttp's idle executor.
      http.dispatcher().executorService().shutdown();
      http.connectionPool().evictAll();
    }
  }

  private Request.Builder at(String path) {
    // Keep the explicit origin authority even when OkHttp represents a mapped
    // IPv6 address internally as IPv4. Credential keys and virtual hosts agree.
    return new Request.Builder()
        .url(origin + path)
        .header("Host", origin.substring(origin.indexOf("://") + 3));
  }

  @Override
  public SignIn login(String handle, String password) throws IOException {
    AuthState.account(handle);
    if (password == null || password.isEmpty() || password.length() > 4096)
      throw new IllegalArgumentException("A bounded nonempty password is required");
    record Login(String handle, String password) {
      @Override
      public String toString() {
        return "Login[redacted]";
      }
    }
    var json = SdkJson.mapper();
    var request =
        at("/v1/auth/login")
            .header("X-Plowshare-Token-Delivery", "body")
            .post(
                RequestBody.create(
                    json.writeValueAsBytes(new Login(handle, password)),
                    MediaType.get("application/json")))
            .build();
    return request(
        request,
        response -> {
          if (response.code() != 200 || response.body() == null)
            throw new IOException("Sign-in refused");
          byte[] bytes = response.body().byteStream().readNBytes(65537);
          if (bytes.length > 65536) throw new IOException("Authentication response too large");
          var raw = json.readTree(bytes);
          if (raw == null
              || !raw.isObject()
              || !raw.path("access").isTextual()
              || !raw.path("mustChangePassword").isBoolean())
            throw new IOException("Invalid sign-in response");
          boolean restricted = raw.get("mustChangePassword").booleanValue();
          var renewal = raw.get("refresh");
          if (renewal != null && !renewal.isNull() && !renewal.isTextual())
            throw new IOException("Invalid refresh token");
          String refresh = renewal == null || renewal.isNull() ? null : renewal.textValue();
          if (!restricted && refresh == null) throw new IOException("Missing refresh token");
          try {
            return new SignIn(
                new Tokens(raw.get("access").textValue(), refresh),
                restricted,
                "true".equals(response.header("X-Plowshare-Setup-Required")));
          } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid sign-in credentials");
          }
        });
  }

  @Override
  public Standing standing(Tokens tokens) throws IOException {
    return request(
        at("/v1/auth/session").header("Authorization", "Bearer " + tokens.access()).get().build(),
        response -> {
          if (response.code() == 401) return new Standing(false, false);
          if (response.code() != 204) throw new IOException("Session status unavailable");
          return new Standing(
              true, "true".equals(response.header("X-Plowshare-Must-Change-Password")));
        });
  }

  @Override
  public Tokens refresh(Tokens tokens) throws IOException {
    if (tokens.refresh() == null) throw new IOException("Session requires explicit login");
    return request(
        at("/v1/auth/refresh")
            .header("Cookie", refreshCookie + "=" + tokens.refresh())
            .post(RequestBody.create(new byte[0], null))
            .build(),
        response -> {
          if (response.code() != 204) throw new IOException("Refresh refused");
          var jar = new HashMap<String, String>();
          for (String header : response.headers("Set-Cookie")) {
            String[] parts = header.split(";", -1);
            String[] pair = parts[0].split("=", 2);
            if (pair.length != 2) continue;
            String name = pair[0].trim();
            if (!name.equals(accessCookie) && !name.equals(refreshCookie)) continue;
            for (int i = 1; i < parts.length; i++) {
              String attr = parts[i].split("=", 2)[0].trim();
              if (attr.equals(accessCookie) || attr.equals(refreshCookie))
                throw new IOException("Ambiguous refresh cookies");
            }
            if (jar.putIfAbsent(name, pair[1]) != null)
              throw new IOException("Duplicate refresh cookie");
          }
          try {
            return new Tokens(jar.get(accessCookie), AuthState.secret(jar.get(refreshCookie)));
          } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid refresh pair");
          }
        });
  }

  @Override
  public void logout(Tokens tokens) throws IOException {
    request(
        at("/v1/auth/logout")
            .header("Authorization", "Bearer " + tokens.access())
            .post(RequestBody.create(new byte[0], null))
            .build(),
        response -> {
          if (response.code() != 204) throw new IOException("Logout failed; retain credentials");
          return Boolean.TRUE;
        });
  }
}
