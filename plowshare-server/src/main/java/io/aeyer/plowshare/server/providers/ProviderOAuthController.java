package io.aeyer.plowshare.server.providers;

import io.aeyer.plowshare.protocol.ProviderCredentials.Credential;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.faults.CallerFault;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** OAuth's static browser landing page restores a same-site context for Strict login cookies. */
@RestController
public final class ProviderOAuthController {
  public static final String COMPLETE_PATH = "/v1/provider-credentials/oauth/complete";
  private static final String SCRIPT =
      """
      const message = document.getElementById('message');
      const query = new URLSearchParams(location.search);
      history.replaceState(null, '', location.pathname);
      const fields = ['state', 'iss', 'code'];
      if ([...query.keys()].length !== 3 || fields.some(key => query.getAll(key).length !== 1)) {
        message.textContent = 'Authorization was not completed. Inspect credential status and begin a new authorization.';
      } else {
        fetch('COMPLETE_ROUTE', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ state: query.get('state'), issuer: query.get('iss'), code: query.get('code') })
        }).then(response => {
          message.textContent = response.ok
            ? 'Authorization completed. Return to Plowshare and inspect credential status.'
            : 'Authorization completion was not confirmed. Return to Plowshare and inspect credential status. If authorization is still needed, sign in as the initiating account and begin a new authorization.';
        }).catch(() => {
          message.textContent = 'Authorization delivery is uncertain. Return to Plowshare and inspect credential status before another authorization.';
        });
      }
      """
          .replace("COMPLETE_ROUTE", "../../v1/provider-credentials/oauth/complete");
  private final ProviderConnections connections;

  public ProviderOAuthController(ProviderConnections connections) {
    this.connections = connections;
  }

  /** Public, constant HTML only. It reads no account, credential, configuration or OAuth state. */
  @GetMapping(
      value = ConfiguredProviderEndpoints.CALLBACK_PATH,
      produces = "text/html;charset=UTF-8")
  public ResponseEntity<String> landing() {
    String digest;
    try {
      digest =
          Base64.getEncoder()
              .encodeToString(
                  MessageDigest.getInstance("SHA-256")
                      .digest(SCRIPT.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        .header(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'sha256-"
                + digest
                + "'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
        .body(
            "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Provider authorization</title><p id=\"message\">Completing authorization…</p><script>"
                + SCRIPT
                + "</script></html>");
  }

  /**
   * Authenticated completion is the HTTP exception; ordinary management remains WebSocket based.
   */
  @PostMapping(value = COMPLETE_PATH, consumes = "application/json")
  public ResponseEntity<Credential> complete(
      HttpServletRequest request, @RequestBody Map<String, Object> payload) {
    if (!(request.getAttribute(AuthFilter.HANDLE_ATTRIBUTE) instanceof String actor))
      throw new CallerFault("Sign in to the account that began OAuth authorization");
    if (!payload.keySet().equals(Set.of("state", "issuer", "code"))
        || !(payload.get("state") instanceof String state)
        || !(payload.get("issuer") instanceof String issuer)
        || !(payload.get("code") instanceof String code))
      throw new CallerFault("OAuth completion requires exactly one state, issuer and code");
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        .body(connections.callback(actor, state, issuer, code));
  }
}
