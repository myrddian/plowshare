package io.aeyer.plowshare.server.providers;

import static io.aeyer.plowshare.server.providers.ProviderFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class CurrentProviderCredentialGrantsTest {
  final Memory store = new Memory();
  final ProviderCredentialAccess access = mock(ProviderCredentialAccess.class);
  final Time clock = new Time();
  final CredentialCipher cipher = new AesCredentialCipher("fixture", Map.of("fixture", KEY));
  final ProviderSecretCodec codec =
      new JsonProviderSecrets(new com.fasterxml.jackson.databind.ObjectMapper());
  final CurrentProviderCredentialGrants service =
      new CurrentProviderCredentialGrants(store, access, policy(), cipher, codec, clock);

  @BeforeEach
  void setup() {
    when(access.subject(anyString(), any())).thenReturn(ACTOR);
  }

  void grant(ProviderSecretMaterial secret, OffsetDateTime expiry) {
    var c = store.connection;
    var metadata =
        new Credential(
            ID, PROJECT, ACTOR, UUID.randomUUID(), c.revision(), State.READY, expiry, "fixture");
    var seed =
        new ProviderCredentialRepository.Stored(
            metadata,
            new CredentialCipher.Sealed("fixture", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAAA"),
            null,
            null,
            null,
            null);
    store.grants.put(
        ACTOR,
        new ProviderCredentialRepository.Stored(
            metadata, cipher.seal(seed.context(), codec.encode(secret)), null, null, null, null));
  }

  @Test
  void encrypted_api_key_is_released_only_as_its_allowed_header() {
    grant(new ProviderSecretMaterial(null, null, null, "fixture-api-key", null), null);
    var ref = service.require(ACTOR, new Target(PROJECT, ID, null));
    var h = service.header(ref);
    assertEquals("X-API-Key", h.name());
    assertEquals("fixture-api-key", h.value());
    assertFalse(h.toString().contains(h.value()));
    doThrow(new CallerFault("Revoked owner")).when(access).subject(anyString(), any());
    assertThrows(CallerFault.class, () -> service.require(ACTOR, new Target(PROJECT, ID, null)));
  }

  @Test
  void oauth_releases_access_but_never_refresh_client_secret_or_verifier() {
    store.connection = new Connection(ID, PROJECT, UUID.randomUUID(), true, false, oauth());
    grant(
        new ProviderSecretMaterial(
            "fixture-access", "fixture-refresh", "fixture-client", null, null),
        OffsetDateTime.ofInstant(clock.instant().plusSeconds(60), ZoneOffset.UTC));
    var ref = service.require(ACTOR, new Target(PROJECT, ID, null));
    assertEquals(
        new ProviderCredentialGrants.Header("Authorization", "Bearer fixture-access"),
        service.header(ref));
    clock.advance(61);
    assertThrows(CallerFault.class, () -> service.header(ref));
    assertThrows(CallerFault.class, () -> service.inspect(ACTOR, new Target(PROJECT, ID, null)));
  }

  @Test
  void rotation_and_machine_secret_without_access_cannot_release_a_header() {
    store.connection =
        new Connection(
            ID,
            PROJECT,
            UUID.randomUUID(),
            true,
            false,
            new Definition(
                "Fixture",
                "https://provider.example/mcp",
                Authentication.CLIENT_CREDENTIALS,
                null,
                OAUTH));
    grant(new ProviderSecretMaterial(null, null, "fixture-client", null, null), null);
    assertThrows(CallerFault.class, () -> service.require(ACTOR, new Target(PROJECT, ID, null)));
    grant(
        new ProviderSecretMaterial("fixture-access", null, "fixture-client", null, null),
        OffsetDateTime.ofInstant(clock.instant().plusSeconds(60), ZoneOffset.UTC));
    var ref = service.require(ACTOR, new Target(PROJECT, ID, null));
    grant(
        new ProviderSecretMaterial("fixture-next", null, "fixture-client", null, null),
        OffsetDateTime.ofInstant(clock.instant().plusSeconds(60), ZoneOffset.UTC));
    assertThrows(CallerFault.class, () -> service.header(ref));
  }
}
