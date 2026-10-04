package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;

class AuthClientTest {
  @Test
  void login_bootstraps_account_access_without_credentials_in_url() throws Exception {
    try (var server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setBody("{\"access\":\"fixture-access\",\"mustChangePassword\":false}"));
      assertEquals(
          "fixture-access",
          AuthClient.login(
              server.url("/").toString(), "operator", "fixture-password", Duration.ofSeconds(2)));
      var request = server.takeRequest();
      assertEquals("/v1/auth/login", request.getPath());
      assertEquals("body", request.getHeader("X-Plowshare-Token-Delivery"));
      assertNull(request.getHeader("Authorization"));
      assertTrue(request.getBody().readUtf8().contains("fixture-password"));
    }
  }

  @Test
  void first_password_change_refused_credentials_never_follow_redirects() throws Exception {
    try (var server = new MockWebServer();
        var other = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setBody("{\"access\":\"fixture-access\",\"mustChangePassword\":true}"));
      assertThrows(
          java.io.IOException.class,
          () ->
              AuthClient.login(
                  server.url("/").toString(),
                  "operator",
                  "fixture-password",
                  Duration.ofSeconds(2)));
      server.enqueue(
          new MockResponse().setResponseCode(302).setHeader("Location", other.url("/login")));
      var refused =
          assertThrows(
              java.io.IOException.class,
              () ->
                  AuthClient.login(
                      server.url("/").toString(),
                      "operator",
                      "fixture-password",
                      Duration.ofSeconds(2)));
      assertFalse(refused.getMessage().contains("fixture-password"));
      assertEquals(0, other.getRequestCount());
    }
  }
}
