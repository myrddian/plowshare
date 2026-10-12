package io.aeyer.plowshare.server.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.ProviderCredentials.OAuth;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import okhttp3.*;

/**
 * Explicit registration, bounded metadata and one token POST. Redirects, transport retries, dynamic
 * endpoint adoption and automatic authorization continuations are disabled.
 */
public final class HttpProviderOAuth implements ProviderOAuth {
  private final OkHttpClient client;
  private final ObjectMapper mapper;
  private final Clock clock;

  public HttpProviderOAuth(OkHttpClient client, ObjectMapper mapper, Clock clock) {
    this.client =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .readTimeout(java.time.Duration.ofSeconds(10))
            .callTimeout(java.time.Duration.ofSeconds(15))
            .build();
    this.mapper = mapper;
    this.clock = clock;
  }

  private JsonNode send(Request request) {
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null)
        throw new CallerFault(
            "OAuth endpoint refused the request; inspect credential status before any new authorization");
      var body = response.body();
      if (body.contentLength() > 65536)
        throw new CallerFault("OAuth response exceeds the supported profile");
      try (var stream = body.byteStream()) {
        byte[] bytes = stream.readNBytes(65537);
        if (bytes.length > 65536)
          throw new CallerFault("OAuth response exceeds the supported profile");
        JsonNode json = mapper.readTree(bytes);
        if (json == null || !json.isObject())
          throw new CallerFault("OAuth endpoint returned an invalid object");
        return json;
      }
    } catch (IOException failed) {
      // Transport exceptions can include request URLs. Do not attach upstream bodies or causes.
      throw new CallerFault(
          "OAuth delivery is uncertain; inspect credential status and authorize again");
    }
  }

  private JsonNode get(String url) {
    return send(new Request.Builder().url(url).header("Accept", "application/json").get().build());
  }

  private static String text(JsonNode value, String field, int maximum) {
    JsonNode node = value.get(field);
    if (node == null
        || !node.isTextual()
        || node.textValue().isEmpty()
        || node.textValue().length() > maximum
        || node.textValue().chars().anyMatch(c -> c < 32 || c > 126))
      throw new CallerFault("OAuth response has an invalid " + field);
    return node.textValue();
  }

  private static Set<String> strings(JsonNode value, String field, int maximum) {
    JsonNode node = value.get(field);
    if (node == null || !node.isArray() || node.size() > maximum)
      throw new CallerFault("OAuth metadata has an invalid " + field);
    var result = new HashSet<String>();
    for (JsonNode entry : node) {
      if (!entry.isTextual()
          || entry.textValue().isEmpty()
          || entry.textValue().length() > 2048
          || entry.textValue().chars().anyMatch(c -> c < 32 || c > 126)
          || !result.add(entry.textValue()))
        throw new CallerFault("OAuth metadata has an invalid " + field);
    }
    return Set.copyOf(result);
  }

  @Override
  public void discover(OAuth c, boolean pkce) {
    var resource = get(c.resourceMetadataUrl());
    if (!c.resource().equals(text(resource, "resource", 2048))
        || !strings(resource, "authorization_servers", 16).contains(c.issuer()))
      throw new CallerFault(
          "Protected resource metadata does not match the configured resource and issuer");
    var issuer = get(c.issuerMetadataUrl());
    if (!c.issuer().equals(text(issuer, "issuer", 2048))
        || !c.tokenEndpoint().equals(text(issuer, "token_endpoint", 2048)))
      throw new CallerFault(
          "OAuth metadata does not match the configured issuer and token endpoint");
    var grants = strings(issuer, "grant_types_supported", 16);
    var authentication = strings(issuer, "token_endpoint_auth_methods_supported", 16);
    if (!strings(issuer, "scopes_supported", 64).containsAll(c.scopes()))
      throw new CallerFault("OAuth metadata does not support the configured scopes");
    if (pkce) {
      if (!c.authorizationEndpoint().equals(text(issuer, "authorization_endpoint", 2048))
          || !strings(issuer, "code_challenge_methods_supported", 8).contains("S256")
          || !strings(issuer, "response_types_supported", 16).contains("code")
          || !grants.contains("authorization_code")
          || !authentication.contains("none")
          || !issuer.path("authorization_response_iss_parameter_supported").isBoolean()
          || !issuer.path("authorization_response_iss_parameter_supported").booleanValue())
        throw new CallerFault(
            "OAuth issuer does not support this PKCE/public-client/issuer-response profile");
    } else if (!grants.contains("client_credentials")
        || !authentication.contains("client_secret_post"))
      throw new CallerFault(
          "OAuth issuer does not support the configured machine authentication profile");
  }

  @Override
  public String authorizationUrl(OAuth c, String state, String verifier) {
    String challenge;
    try {
      challenge =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  MessageDigest.getInstance("SHA-256")
                      .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
    var url =
        Objects.requireNonNull(HttpUrl.parse(c.authorizationEndpoint()))
            .newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", c.clientId())
            .addQueryParameter("redirect_uri", c.redirectUri())
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("resource", c.resource());
    if (!c.scopes().isEmpty()) url.addQueryParameter("scope", String.join(" ", c.scopes()));
    return url.build().toString();
  }

  private Tokens token(OAuth c, FormBody.Builder form) {
    form.add("client_id", c.clientId()).add("resource", c.resource());
    if (!c.scopes().isEmpty()) form.add("scope", String.join(" ", c.scopes()));
    var answer =
        send(
            new Request.Builder()
                .url(c.tokenEndpoint())
                .header("Accept", "application/json")
                .post(form.build())
                .build());
    String access = text(answer, "access_token", 8192);
    if (!"Bearer".equalsIgnoreCase(text(answer, "token_type", 32)))
      throw new CallerFault("Unsupported OAuth token type");
    JsonNode lifetime = answer.get("expires_in");
    if (lifetime == null
        || !lifetime.isIntegralNumber()
        || !lifetime.canConvertToLong()
        || lifetime.longValue() < 1
        || lifetime.longValue() > 31536000)
      throw new CallerFault("OAuth provider must return a bounded token lifetime");
    String refresh = answer.has("refresh_token") ? text(answer, "refresh_token", 8192) : null;
    if (answer.has("scope")) {
      String scope = text(answer, "scope", 8192);
      var granted = Arrays.asList(scope.split(" ", -1));
      if (granted.size() != new HashSet<>(granted).size()
          || !new HashSet<>(granted).equals(new HashSet<>(c.scopes())))
        throw new CallerFault(
            "OAuth provider returned a different scope grant; configure and authorize explicitly");
    }
    if (answer.has("resource") && !c.resource().equals(text(answer, "resource", 2048)))
      throw new CallerFault("OAuth provider returned a different resource grant");
    try {
      return new Tokens(
          access,
          refresh,
          OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
              .plusSeconds(lifetime.longValue()));
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("OAuth provider returned invalid credential material");
    }
  }

  @Override
  public Tokens exchange(OAuth c, String code, String verifier) {
    return token(
        c,
        new FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", c.redirectUri())
            .add("code_verifier", verifier));
  }

  @Override
  public Tokens refresh(OAuth c, String refresh) {
    return token(
        c, new FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh));
  }

  @Override
  public Tokens machine(OAuth c, String clientSecret) {
    return token(
        c,
        new FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_secret", clientSecret));
  }
}
