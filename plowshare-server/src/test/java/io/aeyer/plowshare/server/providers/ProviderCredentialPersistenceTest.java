package io.aeyer.plowshare.server.providers;

import static io.aeyer.plowshare.server.providers.ProviderFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Actual migration/row mapping, conditional writes, atomic receipt rollback and refresh locks. */
@Tag("full-db")
@Testcontainers
class ProviderCredentialPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  static UnitOfWork work;
  final com.fasterxml.jackson.databind.ObjectMapper json =
      JsonMapper.builder().findAndAddModules().build();
  final Time clock = new Time();
  final ProviderCredentialAccess access = mock(ProviderCredentialAccess.class);
  final ProviderOAuth oauth = mock(ProviderOAuth.class);
  final CredentialCipher cipher =
      new AesCredentialCipher("fixture-key", Map.of("fixture-key", KEY));
  ProviderCredentialRepository repository;
  OwnedProviderConnections service;

  @BeforeAll
  static void migrate() {
    var source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    jdbc = new JdbcTemplate(source);
    Flyway.configure().dataSource(source).load().migrate();
    var template = new TransactionTemplate(new DataSourceTransactionManager(source));
    work =
        new UnitOfWork() {
          public <T> T inTransaction(java.util.function.Supplier<T> action) {
            return template.execute(s -> action.get());
          }
        };
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES ('owner','fixture',TRUE),('other','fixture',FALSE)");
    jdbc.update("INSERT INTO projects(name) VALUES (?)", PROJECT);
  }

  @BeforeEach
  void setup() {
    jdbc.update("DELETE FROM provider_credential_receipts");
    jdbc.update("DELETE FROM provider_credentials");
    jdbc.update("DELETE FROM provider_connections");
    jdbc.update("INSERT INTO projects(name) VALUES (?) ON CONFLICT(name) DO NOTHING", PROJECT);
    repository = new JdbcProviderCredentialRepository(jdbc, json);
    when(access.subject(anyString(), any(Target.class)))
        .thenAnswer(
            a -> {
              Target t = a.getArgument(1);
              return t.subject() == null ? a.getArgument(0) : t.subject();
            });
    service =
        new OwnedProviderConnections(
            repository,
            access,
            policy(),
            cipher,
            new JsonProviderSecrets(json),
            oauth,
            work,
            clock);
    service.save(ACTOR, new Save(PROJECT, ID, UUID.randomUUID(), null, api()));
  }

  Mutation mutation(UUID generation) {
    return new Mutation(PROJECT, ID, null, UUID.randomUUID(), generation);
  }

  @Test
  void maximum_escaped_oauth_material_satisfies_ciphertext_constraint_and_survives_restart() {
    var c = service.status(ACTOR, PROJECT, ID);
    var metadata =
        new Credential(
            ID, PROJECT, ACTOR, UUID.randomUUID(), c.revision(), State.READY, null, "fixture-key");
    String secret = "\\\"".repeat(4096);
    var material = new ProviderSecretMaterial(secret, secret, secret, null, null);
    var codec = new JsonProviderSecrets(json);
    String context =
        "provider-credential/1/"
            + ID
            + "/"
            + ACTOR
            + "/"
            + c.revision()
            + "/"
            + metadata.generation();
    var sealed = cipher.seal(context, codec.encode(material));
    assertTrue(sealed.ciphertext().length() > 65536);
    var stored = new ProviderCredentialRepository.Stored(metadata, sealed, null, null, null, null);
    work.inTransaction(
        () -> {
          repository.connection(PROJECT, ID, true);
          repository.store(stored, null);
          return true;
        });
    var restarted = new JdbcProviderCredentialRepository(jdbc, json);
    var retained = restarted.credential(ID, ACTOR, false).orElseThrow();
    assertEquals(material, codec.decode(cipher.open(retained.context(), retained.material())));
  }

  @Test
  void ciphertext_key_metadata_receipts_and_subject_isolation_survive_repository_restart()
      throws Exception {
    UUID request = UUID.randomUUID();
    var first = service.put(ACTOR, new Put(PROJECT, ID, null, request, null, "private-key"));
    service.put("other", new Put(PROJECT, ID, null, UUID.randomUUID(), null, "different-key"));
    var restarted = new JdbcProviderCredentialRepository(jdbc, json);
    var stored = restarted.credential(ID, ACTOR, false).orElseThrow();
    assertEquals(first.credential(), stored.metadata());
    assertEquals("fixture-key", stored.material().keyId());
    assertTrue(cipher.open(stored.context(), stored.material()).contains("private-key"));
    assertEquals(first, restarted.receipt(request).orElseThrow().receipt());
    assertNotEquals(
        stored.metadata().generation(),
        restarted.credential(ID, "other", false).orElseThrow().metadata().generation());
    String persisted =
        jdbc.queryForObject(
            "SELECT ciphertext FROM provider_credentials WHERE connection_id=? AND subject=?",
            String.class,
            ID,
            ACTOR);
    assertFalse(persisted.contains("private-key"));
    assertFalse(
        jdbc.queryForObject(
                "SELECT receipt::text FROM provider_credential_receipts WHERE request_id=?",
                String.class,
                request)
            .contains("private-key"));
    assertTrue(restarted.connection("foreign", ID, false).isEmpty());
  }

  @Test
  void receipt_conflict_rolls_back_the_preceding_grant_and_stale_generation_is_refused() {
    UUID request = UUID.randomUUID();
    service.put(ACTOR, new Put(PROJECT, ID, null, request, null, "first-key"));
    var before = repository.credential(ID, ACTOR, false).orElseThrow();
    var m = before.metadata();
    var changed =
        new ProviderCredentialRepository.Stored(
            new Credential(
                ID,
                PROJECT,
                ACTOR,
                UUID.randomUUID(),
                m.connectionRevision(),
                State.REVOKED,
                null,
                null),
            null,
            null,
            null,
            null,
            null);
    assertThrows(
        CallerFault.class,
        () ->
            work.inTransaction(
                () -> {
                  repository.connection(PROJECT, ID, true).orElseThrow();
                  repository.store(changed, m.generation());
                  repository.commit(
                      ACTOR,
                      new Receipt(
                          request,
                          "provider.credential.revoke",
                          repository.connection(PROJECT, ID, false).orElseThrow(),
                          changed.metadata()));
                  return true;
                }));
    assertEquals(before, repository.credential(ID, ACTOR, false).orElseThrow());
    assertThrows(
        CallerFault.class,
        () ->
            work.inTransaction(
                () -> {
                  repository.connection(PROJECT, ID, true);
                  repository.store(changed, UUID.randomUUID());
                  return true;
                }));
    assertEquals(before, repository.credential(ID, ACTOR, false).orElseThrow());
  }

  @Test
  void concurrent_refresh_is_serialized_without_holding_a_transaction_over_network_io()
      throws Exception {
    var c = service.status(ACTOR, PROJECT, ID);
    service.save(ACTOR, new Save(PROJECT, ID, UUID.randomUUID(), c.revision(), oauth()));
    var material = new ProviderSecretMaterial("access-first", "refresh-first", null, null, null);
    var metadata =
        new Credential(
            ID,
            PROJECT,
            ACTOR,
            UUID.randomUUID(),
            service.status(ACTOR, PROJECT, ID).revision(),
            State.READY,
            OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).plusHours(1),
            "fixture-key");
    String context =
        "provider-credential/1/"
            + ID
            + "/"
            + ACTOR
            + "/"
            + metadata.connectionRevision()
            + "/"
            + metadata.generation();
    var stored =
        new ProviderCredentialRepository.Stored(
            metadata,
            cipher.seal(context, new JsonProviderSecrets(json).encode(material)),
            null,
            null,
            null,
            null);
    work.inTransaction(
        () -> {
          repository.connection(PROJECT, ID, true);
          repository.store(stored, null);
          return true;
        });
    var sent = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(oauth.refresh(any(), anyString()))
        .thenAnswer(
            a -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              sent.countDown();
              assertTrue(release.await(10, TimeUnit.SECONDS));
              return new ProviderOAuth.Tokens(
                  "access-next",
                  "refresh-next",
                  OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).plusHours(1));
            });
    try (var workers = Executors.newFixedThreadPool(2)) {
      Future<Receipt> first =
          workers.submit(() -> service.refresh(ACTOR, mutation(metadata.generation())));
      assertTrue(sent.await(10, TimeUnit.SECONDS));
      Future<Boolean> other =
          workers.submit(
              () -> {
                // This lock would block if the first refresh held its connection transaction across
                // I/O.
                work.inTransaction(() -> repository.connection(PROJECT, ID, true).orElseThrow());
                assertThrows(
                    CallerFault.class,
                    () -> service.refresh(ACTOR, mutation(metadata.generation())));
                return true;
              });
      try {
        assertTrue(other.get(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
      assertEquals(State.READY, first.get(10, TimeUnit.SECONDS).credential().state());
    }
    verify(oauth, times(1)).refresh(any(), eq("refresh-first"));
    var completed = repository.credential(ID, ACTOR, false).orElseThrow();
    assertNotEquals(metadata.generation(), completed.metadata().generation());
    assertNull(completed.attempt());
  }

  @Test
  void connection_disable_atomically_discards_grants_and_prevents_id_adoption() {
    service.put(ACTOR, new Put(PROJECT, ID, null, UUID.randomUUID(), null, "first-key"));
    var connection = service.status(ACTOR, PROJECT, ID);
    service.change(
        ACTOR,
        new Change(PROJECT, ID, UUID.randomUUID(), connection.revision(), ConnectionAction.DELETE));
    var grant = repository.credential(ID, ACTOR, false).orElseThrow();
    assertEquals(State.REVOKED, grant.metadata().state());
    assertNull(grant.material());
    assertEquals(
        service.status(ACTOR, PROJECT, ID).revision(), grant.metadata().connectionRevision());
    assertThrows(
        CallerFault.class,
        () ->
            service.save(
                ACTOR,
                new Save(
                    PROJECT,
                    ID,
                    UUID.randomUUID(),
                    service.status(ACTOR, PROJECT, ID).revision(),
                    api())));
  }

  @Test
  void project_deletion_revokes_material_and_recreation_cannot_adopt_old_connection_ids() {
    service.put(ACTOR, new Put(PROJECT, ID, null, UUID.randomUUID(), null, "private-key"));
    jdbc.update("DELETE FROM projects WHERE name=?", PROJECT);
    assertEquals(
        "REVOKED",
        jdbc.queryForObject(
            "SELECT state FROM provider_credentials WHERE connection_id=? AND subject=?",
            String.class,
            ID,
            ACTOR));
    assertNull(
        jdbc.queryForObject(
            "SELECT ciphertext FROM provider_credentials WHERE connection_id=? AND subject=?",
            String.class,
            ID,
            ACTOR));
    assertTrue(
        jdbc.queryForObject(
            "SELECT deleted AND NOT enabled AND project_id IS NULL FROM provider_connections WHERE id=?",
            Boolean.class,
            ID));
    jdbc.update("INSERT INTO projects(name) VALUES (?)", PROJECT);
    assertThrows(CallerFault.class, () -> service.status(ACTOR, PROJECT, ID));
    assertThrows(
        CallerFault.class,
        () -> service.save(ACTOR, new Save(PROJECT, ID, UUID.randomUUID(), null, api())));
    assertTrue(service.list(ACTOR, PROJECT).isEmpty());
  }
}
