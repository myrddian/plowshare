package io.aeyer.plowshare.server.providers;

import static io.aeyer.plowshare.server.providers.ProviderFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProviderOAuthControllerTest {
  @Test
  void public_landing_is_constant_has_a_matching_csp_and_cannot_consume_any_grant()
      throws Exception {
    var service = mock(ProviderConnections.class);
    var controller = new ProviderOAuthController(service);
    var mvc = MockMvcBuilders.standaloneSetup(controller).build();
    var response =
        mvc.perform(
                get(ConfiguredProviderEndpoints.CALLBACK_PATH)
                    .param("code", "private-code")
                    .param("state", "private-state")
                    .param("iss", "https://foreign.example"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andReturn()
            .getResponse();
    String html = response.getContentAsString(StandardCharsets.UTF_8);
    var fixture = java.nio.file.Path.of("build", "provider-credentials", "callback.json");
    java.nio.file.Files.createDirectories(fixture.getParent());
    java.nio.file.Files.writeString(
        fixture,
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "html",
                    html,
                    "csp",
                    Objects.requireNonNull(response.getHeader("Content-Security-Policy")))));
    assertFalse(html.contains("private-code"));
    assertFalse(html.contains("private-state"));
    assertFalse(html.contains("foreign.example"));
    String script = html.substring(html.indexOf("<script>") + 8, html.indexOf("</script>"));
    String hash =
        Base64.getEncoder()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(script.getBytes(StandardCharsets.UTF_8)));
    assertTrue(
        Objects.requireNonNull(response.getHeader("Content-Security-Policy"))
            .contains("'sha256-" + hash + "'"));
    assertTrue(script.contains("credentials: 'same-origin'"));
    assertTrue(script.contains("history.replaceState"));
    assertFalse(AuthFilter.gates(ConfiguredProviderEndpoints.CALLBACK_PATH));
    assertTrue(AuthFilter.gates(ProviderOAuthController.COMPLETE_PATH));
    verifyNoInteractions(service);
  }

  @Test
  void completion_rejects_missing_authentication_unknown_fields_and_coercion_before_service() {
    var service = mock(ProviderConnections.class);
    var controller = new ProviderOAuthController(service);
    var request = new MockHttpServletRequest();
    var body =
        Map.<String, Object>of("state", "s", "issuer", OAUTH.issuer(), "code", "private-code");
    assertThrows(CallerFault.class, () -> controller.complete(request, body));
    request.setAttribute(AuthFilter.HANDLE_ATTRIBUTE, ACTOR);
    var unknown = new HashMap<>(body);
    unknown.put("subject", "other");
    assertThrows(CallerFault.class, () -> controller.complete(request, unknown));
    var coerced = new HashMap<>(body);
    coerced.put("code", 123);
    assertThrows(CallerFault.class, () -> controller.complete(request, coerced));
    verifyNoInteractions(service);
    controller.complete(request, body);
    verify(service).callback(ACTOR, "s", OAUTH.issuer(), "private-code");
  }
}
