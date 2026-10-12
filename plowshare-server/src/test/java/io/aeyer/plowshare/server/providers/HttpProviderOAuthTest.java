package io.aeyer.plowshare.server.providers;

import static io.aeyer.plowshare.server.providers.ProviderFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.junit.jupiter.api.Test;

class HttpProviderOAuthTest {
  final com.fasterxml.jackson.databind.ObjectMapper mapper =
      JsonMapper.builder().findAndAddModules().build();

  static String resource() {
    return "{\"resource\":\"https://provider.example/mcp\",\"authorization_servers\":[\"https://issuer.example\"]}";
  }

  static String issuer() {
    return "{\"issuer\":\"https://issuer.example\",\"token_endpoint\":\"https://issuer.example/token\",\"authorization_endpoint\":\"https://issuer.example/authorize\",\"scopes_supported\":[\"tools\"],\"grant_types_supported\":[\"authorization_code\",\"refresh_token\",\"client_credentials\"],\"token_endpoint_auth_methods_supported\":[\"none\",\"client_secret_post\"],\"code_challenge_methods_supported\":[\"S256\"],\"response_types_supported\":[\"code\"],\"authorization_response_iss_parameter_supported\":true}";
  }

  HttpProviderOAuth fixture(List<String> replies, List<Request> requests, int status) {
    var index = new AtomicInteger();
    var client =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  requests.add(chain.request());
                  return new Response.Builder()
                      .request(chain.request())
                      .protocol(Protocol.HTTP_1_1)
                      .code(status)
                      .message("fixture")
                      .body(
                          ResponseBody.create(
                              replies.get(index.getAndIncrement()),
                              MediaType.get("application/json")))
                      .build();
                })
            .build();
    return new HttpProviderOAuth(client, mapper, new Time());
  }

  @Test
  void configured_metadata_is_confirmed_without_adopting_foreign_endpoints() {
    var requests = new ArrayList<Request>();
    fixture(List.of(resource(), issuer()), requests, 200).discover(OAUTH, true);
    assertEquals(
        List.of(OAUTH.resourceMetadataUrl(), OAUTH.issuerMetadataUrl()),
        requests.stream().map(r -> r.url().toString()).toList());
    var foreign = issuer().replace("https://issuer.example/token", "https://foreign.example/token");
    assertThrows(
        CallerFault.class,
        () -> fixture(List.of(resource(), foreign), new ArrayList<>(), 200).discover(OAUTH, true));
    assertThrows(
        CallerFault.class,
        () ->
            fixture(List.of(resource(), issuer().replace("S256", "plain")), new ArrayList<>(), 200)
                .discover(OAUTH, true));
    assertThrows(
        CallerFault.class,
        () ->
            fixture(
                    List.of(resource(), issuer().replace("supported\":true", "supported\":false")),
                    new ArrayList<>(),
                    200)
                .discover(OAUTH, true));
  }

  @Test
  void pkce_and_resource_parameters_are_encoded_without_forwarding_plowshare_auth() {
    var requests = new ArrayList<Request>();
    var oauth =
        fixture(
            List.of(
                "{\"access_token\":\"fixture-access\",\"refresh_token\":\"fixture-refresh\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"tools\"}"),
            requests,
            200);
    var tokens = oauth.exchange(OAUTH, "code-with+reserved&characters", "v".repeat(43));
    assertEquals("fixture-access", tokens.access());
    assertNull(requests.getFirst().header("Authorization"));
    var body = (FormBody) requests.getFirst().body();
    assertNotNull(body);
    var fields = new HashMap<String, String>();
    for (int i = 0; i < body.size(); i++) fields.put(body.name(i), body.value(i));
    assertEquals(OAUTH.resource(), fields.get("resource"));
    assertEquals(OAUTH.redirectUri(), fields.get("redirect_uri"));
    assertEquals("code-with+reserved&characters", fields.get("code"));
    assertEquals("v".repeat(43), fields.get("code_verifier"));
    assertFalse(tokens.toString().contains("fixture-access"));
  }

  @Test
  void invalid_tokens_redirects_oversize_and_transport_loss_are_refused_with_one_send() {
    for (String response :
        List.of(
            "{\"access_token\":\"private-material\",\"token_type\":\"MAC\",\"expires_in\":3600}",
            "{\"access_token\":\"private-material\",\"token_type\":\"Bearer\",\"expires_in\":0}",
            "{\"access_token\":\"private-material\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"resource\":\"https://foreign.example/mcp\"}",
            "x".repeat(65537))) {
      var requests = new ArrayList<Request>();
      var oauth = fixture(List.of(response), requests, 200);
      var failure = assertThrows(CallerFault.class, () -> oauth.refresh(OAUTH, "private-refresh"));
      assertEquals(1, requests.size());
      assertFalse(failure.getMessage().contains("private-material"));
      assertNull(failure.getCause());
    }
    var requests = new ArrayList<Request>();
    assertThrows(
        CallerFault.class,
        () ->
            fixture(List.of("private-material"), requests, 302).refresh(OAUTH, "private-refresh"));
    assertEquals(1, requests.size());
    var sends = new AtomicInteger();
    var client =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  sends.incrementAndGet();
                  throw new IOException("private-refresh and endpoint");
                })
            .build();
    var failure =
        assertThrows(
            CallerFault.class,
            () ->
                new HttpProviderOAuth(client, mapper, new Time())
                    .refresh(OAUTH, "private-refresh"));
    assertEquals(1, sends.get());
    assertNull(failure.getCause());
    assertFalse(failure.getMessage().contains("private-refresh"));
  }
}
