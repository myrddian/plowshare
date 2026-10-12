package io.aeyer.plowshare.server.providers;

import static io.aeyer.plowshare.server.providers.ProviderFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.*;

class OwnedProviderConnectionsTest {
  final Memory repository = new Memory();
  final ProviderCredentialAccess access = mock(ProviderCredentialAccess.class);
  final ProviderOAuth oauth = mock(ProviderOAuth.class);
  final Time clock = new Time();
  final com.fasterxml.jackson.databind.ObjectMapper json =
      JsonMapper.builder().findAndAddModules().build();
  final CredentialCipher cipher = new AesCredentialCipher("first", Map.of("first", KEY));
  OwnedProviderConnections service;

  @BeforeEach
  void setup() {
    when(access.subject(anyString(), any(Target.class)))
        .thenAnswer(
            a -> {
              Target target = a.getArgument(1);
              return target.subject() == null ? a.getArgument(0) : target.subject();
            });
    service =
        new OwnedProviderConnections(
            repository,
            access,
            policy(),
            cipher,
            new JsonProviderSecrets(json),
            oauth,
            UnitOfWork.NONE,
            clock);
    var encoder = new HttpProviderOAuth(new okhttp3.OkHttpClient(), json, clock);
    when(oauth.authorizationUrl(any(), anyString(), anyString()))
        .thenAnswer(
            a -> encoder.authorizationUrl(a.getArgument(0), a.getArgument(1), a.getArgument(2)));
    when(oauth.exchange(any(), anyString(), anyString()))
        .thenReturn(tokens("access-first", "refresh-first"));
  }

  ProviderOAuth.Tokens tokens(String access, String refresh) {
    return new ProviderOAuth.Tokens(
        access,
        refresh,
        java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC).plusHours(1));
  }

  Mutation mutation(String actor) {
    return new Mutation(
        PROJECT,
        ID,
        null,
        UUID.randomUUID(),
        repository.grants.containsKey(actor)
            ? repository.grants.get(actor).metadata().generation()
            : null);
  }

  Credential put(String actor, String value) {
    var r = mutation(actor);
    return service
        .put(actor, new Put(PROJECT, ID, null, r.requestId(), r.expectedGeneration(), value))
        .credential();
  }

  Authorization begin() {
    repository.connection = new Connection(ID, PROJECT, UUID.randomUUID(), true, false, oauth());
    return service.authorize(ACTOR, mutation(ACTOR));
  }

  Credential authorized() {
    var r = begin();
    String state =
        Objects.requireNonNull(HttpUrl.parse(r.authorizationUrl())).queryParameter("state");
    return service.callback(ACTOR, state, OAUTH.issuer(), "fixture-code");
  }

  @Test
  void membership_revoked_during_connection_lock_wait_refuses_the_transition() {
    var waiting = spy(repository);
    doAnswer(
            invocation -> {
              doThrow(new CallerFault("Membership revoked"))
                  .when(access)
                  .subject(eq(ACTOR), any(Target.class));
              return invocation.callRealMethod();
            })
        .when(waiting)
        .connection(PROJECT, ID, true);
    var checked =
        new OwnedProviderConnections(
            waiting,
            access,
            policy(),
            cipher,
            new JsonProviderSecrets(json),
            oauth,
            UnitOfWork.NONE,
            clock);
    assertThrows(
        CallerFault.class,
        () ->
            checked.put(ACTOR, new Put(PROJECT, ID, null, UUID.randomUUID(), null, "fixture-key")));
    assertTrue(repository.grants.isEmpty());
    assertTrue(repository.receipts.isEmpty());
    verifyNoInteractions(oauth);
  }

  @Test
  void user_credentials_are_separate_and_all_management_output_is_redacted() throws Exception {
    var first = put(ACTOR, "private-upstream-key");
    put("other", "different-upstream-key");
    assertNotEquals(
        first.generation(),
        service.credential("other", new Target(PROJECT, ID, null)).generation());
    var record = repository.grants.get(ACTOR);
    assertFalse(json.writeValueAsString(record).contains("private-upstream-key"));
    assertFalse(
        json.writeValueAsString(repository.receipts.values()).contains("private-upstream-key"));
    assertFalse(record.toString().contains("private-upstream-key"));
    assertThrows(
        IllegalStateException.class,
        () -> cipher.open(record.context() + "foreign", record.material()));
    assertThrows(
        CallerFault.class,
        () ->
            service.receipt(
                "other",
                PROJECT,
                repository.receipts.values().stream()
                    .filter(r -> r.actor().equals(ACTOR))
                    .findFirst()
                    .orElseThrow()
                    .receipt()
                    .requestId()));
    assertTrue(
        new Put(PROJECT, ID, null, UUID.randomUUID(), null, "private-upstream-key")
            .toString()
            .contains("<redacted>"));
  }

  @Test
  void stale_revision_and_duplicate_uuid_cannot_replace_a_grant() {
    var r = new Put(PROJECT, ID, null, UUID.randomUUID(), null, "first-key");
    service.put(ACTOR, r);
    var retained = repository.grants.get(ACTOR);
    assertThrows(CallerFault.class, () -> service.put(ACTOR, r));
    assertThrows(
        CallerFault.class,
        () ->
            service.put(
                ACTOR, new Put(PROJECT, ID, null, UUID.randomUUID(), null, "different-key")));
    assertEquals(retained, repository.grants.get(ACTOR));
    assertThrows(
        CallerFault.class,
        () ->
            service.change(
                ACTOR,
                new Change(
                    PROJECT, ID, UUID.randomUUID(), UUID.randomUUID(), ConnectionAction.DISABLE)));
  }

  @Test
  void pkce_state_is_owner_bound_issuer_bound_and_consumed_before_exchange() {
    var started = begin();
    var url = Objects.requireNonNull(HttpUrl.parse(started.authorizationUrl()));
    assertEquals("S256", url.queryParameter("code_challenge_method"));
    assertEquals(OAUTH.resource(), url.queryParameter("resource"));
    String state = url.queryParameter("state");
    var pending = repository.grants.get(ACTOR);
    assertNotEquals(state, pending.stateHash());
    assertThrows(CallerFault.class, () -> service.callback("other", state, OAUTH.issuer(), "code"));
    assertThrows(
        CallerFault.class, () -> service.callback(ACTOR, state, "https://foreign.example", "code"));
    verify(oauth, never()).exchange(any(), anyString(), anyString());
    assertEquals(State.READY, service.callback(ACTOR, state, OAUTH.issuer(), "code").state());
    assertThrows(CallerFault.class, () -> service.callback(ACTOR, state, OAUTH.issuer(), "code"));
    verify(oauth, times(1))
        .exchange(eq(OAUTH), eq("code"), argThat(v -> v.matches("[A-Za-z0-9_-]{43}")));
  }

  @Test
  void rotation_loss_is_single_send_uncertain_and_cannot_be_refreshed_again() {
    authorized();
    when(oauth.refresh(any(), anyString())).thenThrow(new CallerFault("uncertain"));
    var r = mutation(ACTOR);
    assertThrows(CallerFault.class, () -> service.refresh(ACTOR, r));
    var uncertain = repository.grants.get(ACTOR);
    assertEquals(State.UNCERTAIN, uncertain.metadata().state());
    assertNull(uncertain.material());
    assertEquals(
        State.REFRESHING, service.receipt(ACTOR, PROJECT, r.requestId()).credential().state());
    assertThrows(CallerFault.class, () -> service.refresh(ACTOR, mutation(ACTOR)));
    verify(oauth, times(1)).refresh(eq(OAUTH), eq("refresh-first"));
  }

  @Test
  void concurrent_revoke_wins_over_a_late_successful_rotation() {
    authorized();
    when(oauth.refresh(any(), anyString()))
        .thenAnswer(
            a -> {
              service.revoke(ACTOR, mutation(ACTOR));
              return tokens("late-access", "late-refresh");
            });
    assertThrows(CallerFault.class, () -> service.refresh(ACTOR, mutation(ACTOR)));
    assertEquals(State.REVOKED, repository.grants.get(ACTOR).metadata().state());
    assertNull(repository.grants.get(ACTOR).material());
  }

  @Test
  void membership_loss_during_exchange_discards_the_successful_remote_grant() {
    authorized();
    when(oauth.refresh(any(), anyString()))
        .thenAnswer(
            a -> {
              doThrow(new CallerFault("membership revoked")).when(access).subject(eq(ACTOR), any());
              return tokens("late-access", "late-refresh");
            });
    assertThrows(CallerFault.class, () -> service.refresh(ACTOR, mutation(ACTOR)));
    assertEquals(State.UNCERTAIN, repository.grants.get(ACTOR).metadata().state());
  }

  @Test
  void expired_pending_authorization_reconciles_without_reusing_the_state() {
    var started = begin();
    clock.advance(601);
    assertEquals(State.UNCERTAIN, service.credential(ACTOR, new Target(PROJECT, ID, null)).state());
    String state =
        Objects.requireNonNull(HttpUrl.parse(started.authorizationUrl())).queryParameter("state");
    assertThrows(CallerFault.class, () -> service.callback(ACTOR, state, OAUTH.issuer(), "code"));
    verify(oauth, never()).exchange(any(), anyString(), anyString());
  }

  @Test
  void configuration_changes_revoke_existing_keys_and_pending_oauth() {
    put(ACTOR, "first-key");
    var revision = repository.connection.revision();
    service.change(
        ACTOR, new Change(PROJECT, ID, UUID.randomUUID(), revision, ConnectionAction.DISABLE));
    assertEquals(State.REVOKED, repository.grants.get(ACTOR).metadata().state());
    assertThrows(CallerFault.class, () -> put(ACTOR, "second-key"));
    assertThrows(
        CallerFault.class,
        () ->
            service.change(
                ACTOR,
                new Change(PROJECT, ID, UUID.randomUUID(), revision, ConnectionAction.ENABLE)));
  }

  @Test
  void encryption_key_rotation_reseals_the_same_generation() {
    var first = put(ACTOR, "private-key");
    byte[] newKey = new byte[32];
    Arrays.fill(newKey, (byte) 1);
    var rotated =
        new AesCredentialCipher(
            "second", Map.of("first", KEY, "second", Base64.getEncoder().encodeToString(newKey)));
    service =
        new OwnedProviderConnections(
            repository,
            access,
            policy(),
            rotated,
            new JsonProviderSecrets(json),
            oauth,
            UnitOfWork.NONE,
            clock);
    var receipt = service.rekey(ACTOR, mutation(ACTOR));
    assertEquals(first.generation(), receipt.credential().generation());
    assertEquals("second", receipt.credential().keyId());
    var record = repository.grants.get(ACTOR);
    assertTrue(rotated.open(record.context(), record.material()).contains("private-key"));
    assertThrows(
        IllegalStateException.class, () -> cipher.open(record.context(), record.material()));
  }

  @Test
  void oauth_access_token_passthrough_is_refused() {
    repository.connection = new Connection(ID, PROJECT, UUID.randomUUID(), true, false, oauth());
    assertThrows(CallerFault.class, () -> put(ACTOR, "user-supplied-access"));
    assertTrue(repository.grants.isEmpty());
  }
}
