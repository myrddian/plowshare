package io.aeyer.plowshare.server.providers;

import static io.aeyer.plowshare.server.providers.ProviderFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.ws.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProviderBoundariesTest {
  @Test
  void ordinary_users_cannot_select_other_subjects_and_service_tokens_remain_independent() {
    var accounts = mock(AdminStore.class);
    var members = mock(ProjectMembers.class);
    when(accounts.isEnabled(anyString())).thenReturn(true);
    doThrow(new CallerFault("admin required")).when(accounts).requireServerAdmin(ACTOR);
    var access = new CurrentProviderCredentialAccess(accounts, members);
    assertEquals(ACTOR, access.subject(ACTOR, new Target(PROJECT, ID, null)));
    String principal = "@service/" + UUID.randomUUID();
    assertThrows(
        CallerFault.class, () -> access.subject(ACTOR, new Target(PROJECT, ID, principal)));
    assertEquals(principal, access.subject("admin", new Target(PROJECT, ID, principal)));
    verify(members).requireRole(PROJECT, principal, ProjectRole.CONTRIBUTOR);
    when(accounts.isEnabled(principal)).thenReturn(false);
    assertThrows(
        CallerFault.class, () -> access.subject("admin", new Target(PROJECT, ID, principal)));
    assertThrows(IllegalArgumentException.class, () -> new Target(PROJECT, ID, "other-user"));
    assertThrows(
        IllegalArgumentException.class, () -> new Target(PROJECT, ID, "service-owner-handle"));
  }

  @Test
  void owner_account_and_membership_are_rechecked_without_admin_bypass() {
    var accounts = mock(AdminStore.class);
    var members = mock(ProjectMembers.class);
    when(accounts.isEnabled(ACTOR)).thenReturn(true);
    var access = new CurrentProviderCredentialAccess(accounts, members);
    access.subject(ACTOR, new Target(PROJECT, ID, null));
    doThrow(new CallerFault("membership revoked"))
        .when(members)
        .requireRole(PROJECT, ACTOR, ProjectRole.CONTRIBUTOR);
    assertThrows(CallerFault.class, () -> access.subject(ACTOR, new Target(PROJECT, ID, null)));
    when(accounts.isEnabled(ACTOR)).thenReturn(false);
    assertThrows(CallerFault.class, () -> access.read(ACTOR, PROJECT));
  }

  @Test
  void configured_policy_rejects_unapproved_destinations_and_callback_resource_changes() {
    var policy = policy();
    policy.require(oauth());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            policy.require(
                new Definition(
                    "foreign",
                    "https://foreign.example/mcp",
                    Authentication.API_KEY,
                    "X-API-Key",
                    null)));
    for (String url :
        List.of(
            "http://provider.example/mcp",
            "https://user@provider.example/mcp",
            "https://provider.example:99999/mcp",
            "https://provider.example/mcp?x=1",
            "https://provider.example/mcp%2f",
            "https://provider.example/mcp#frag"))
      assertThrows(
          IllegalArgumentException.class,
          () -> new Definition("fixture", url, Authentication.API_KEY, "X-API-Key", null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConfiguredProviderEndpoints(
                Set.of("https://provider.example"), "https://plowshare.example/wrong"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AesCredentialCipher("absent", Map.of("first", KEY)));
  }

  @Test
  void persistence_codec_preserves_explicit_nulls_and_round_trips_maximum_escaped_material()
      throws Exception {
    var source =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .setSerializationInclusion(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    var codec = new JsonProviderSecrets(source);
    String secret = "\\\"".repeat(4096);
    var maximum = new ProviderSecretMaterial(secret, secret, secret, null, null);
    var cipher = new AesCredentialCipher("first", Map.of("first", KEY));
    String encoded = codec.encode(maximum);
    assertTrue(encoded.length() > 32768);
    assertTrue(source.readTree(encoded).has("apiKey"));
    assertEquals(
        maximum,
        codec.decode(cipher.open("fixture-context", cipher.seal("fixture-context", encoded))));
    for (String invalid :
        List.of(
            "{\"access\":123,\"refresh\":null,\"clientSecret\":null,\"apiKey\":null,\"verifier\":null}",
            "{\"access\":null,\"refresh\":null,\"clientSecret\":null,\"apiKey\":\"fixture\",\"verifier\":null,\"extra\":true}",
            "{\"apiKey\":\"fixture\"}"))
      assertThrows(IllegalStateException.class, () -> codec.decode(invalid));
  }

  @Test
  void
      unknown_nested_fields_coercion_missing_nullable_fields_and_noncanonical_ids_fail_before_service() {
    var service = mock(ProviderConnections.class);
    var frames = new ProviderCredentialFrames(service);
    var handler = frames.frames().get(FrameTypes.PROVIDER_CREDENTIAL_PUT);
    var valid = new HashMap<String, Object>();
    valid.put("project", PROJECT);
    valid.put("connection", ID.toString());
    valid.put("subject", null);
    valid.put("requestId", UUID.randomUUID().toString());
    valid.put("expectedGeneration", null);
    valid.put("secret", "private-key");
    for (var change :
        List.of(
            Map.of("connection", "1-1-1-1-1"),
            Map.of("owner", "other"),
            Map.of("secret", 123),
            Map.of("secret", "bad\nkey"),
            Map.of("subject", "other-user"))) {
      var invalid = new HashMap<>(valid);
      invalid.putAll(change);
      var error =
          assertThrows(
              CallerFault.class, () -> handler.handle(invalid, new Asking("session", ACTOR)));
      assertFalse(error.getMessage().contains("private-key"));
    }
    var missing = new HashMap<>(valid);
    missing.remove("subject");
    assertThrows(CallerFault.class, () -> handler.handle(missing, new Asking("session", ACTOR)));
    var save = frames.frames().get(FrameTypes.PROVIDER_CONNECTION_SAVE);
    var definition = new HashMap<String, Object>();
    definition.put("name", "fixture");
    definition.put("endpoint", "https://provider.example/mcp");
    definition.put("authentication", "API_KEY");
    definition.put("apiKeyHeader", "X-API-Key");
    definition.put("oauth", null);
    definition.put("hiddenSecret", "private-key");
    var request = new HashMap<String, Object>();
    request.put("project", PROJECT);
    request.put("connection", ID.toString());
    request.put("requestId", UUID.randomUUID().toString());
    request.put("expectedRevision", null);
    request.put("definition", definition);
    assertThrows(CallerFault.class, () -> save.handle(request, new Asking("session", ACTOR)));
    verifyNoInteractions(service);
    handler.handle(valid, new Asking("session", ACTOR));
    verify(service).put(eq(ACTOR), any(Put.class));
  }
}
