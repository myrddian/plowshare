package io.aeyer.plowshare.server.relay.tools;

import static io.aeyer.plowshare.server.relay.tools.HostedScopeFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.HostedToolScopes.*;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real row mapping, rollback, CAS and lock contention cannot be established by behavior fakes. */
@Tag("full-db")
@Testcontainers
class HostedScopePersistenceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  static UnitOfWork transactions;
  JdbcHostedToolScopeRepository store;
  HostedScopeFixture f;
  OwnedHostedToolScopes service;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    transactions =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> work) {
            return Objects.requireNonNull(tx.execute(s -> work.get()));
          }
        };
  }

  @BeforeEach
  void setup() {
    io.aeyer.plowshare.server.testing.AuthorityFixtures.truncate(
        jdbc,
        "TRUNCATE hosted_tool_scope_mutations,hosted_tool_scope_workers,hosted_tool_scopes,provider_credentials,provider_connections,projects CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES (?, 'fixture',FALSE),(?,'fixture',FALSE) ON CONFLICT(handle) DO NOTHING",
        OWNER,
        EXECUTOR);
    jdbc.update(
        "INSERT INTO projects(name,workspace) VALUES (?,'fixture'),('configuration','fixture')",
        PROJECT);
    f = new HostedScopeFixture();
    long id =
        Objects.requireNonNull(
            jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, PROJECT));
    when(f.projects.id(PROJECT)).thenReturn(id);
    jdbc.update(
        "INSERT INTO provider_connections(id,project_id,revision,enabled,definition) SELECT ?,id,?,TRUE,?::jsonb FROM projects WHERE name='configuration'",
        CONNECTION,
        f.grant.get().connection().revision(),
        "{\"name\":\"Fixture\",\"endpoint\":\"https://provider.example/mcp\",\"authentication\":\"API_KEY\",\"apiKeyHeader\":\"X-API-Key\",\"oauth\":null}");
    store =
        new JdbcHostedToolScopeRepository(
            jdbc, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    service = f.service(store, f.server, transactions);
  }

  Binding save() {
    return service
        .save(OWNER, new Save(PROJECT, ID, UUID.randomUUID(), null, definition()))
        .binding();
  }

  @Test
  void retained_configuration_receipt_and_fence_survive_repository_restart() {
    UUID request = UUID.randomUUID();
    var receipt = service.save(OWNER, new Save(PROJECT, ID, request, null, definition()));
    var r =
        service.register(
            EXECUTOR, "worker-session", "socket", new Register(ID, UUID.randomUUID(), 1));
    var restarted =
        new JdbcHostedToolScopeRepository(
            jdbc, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    assertEquals(receipt, restarted.receipt(OWNER, PROJECT, request).orElseThrow());
    assertEquals(r, restarted.lease(ID).orElseThrow().registration());
    assertEquals(0, restarted.lease(ID).orElseThrow().catalogueAfter());
    assertThrows(
        CallerFault.class,
        () -> service.save(OWNER, new Save(PROJECT, ID, request, null, definition())));
    assertTrue(restarted.receipt("bob", PROJECT, request).isEmpty());
  }

  @Test
  void receipt_failure_rolls_back_assignment_and_generation() {
    var failed = spy(store);
    doThrow(new IllegalStateException("Injected receipt failure"))
        .when(failed)
        .receipt(anyString(), any());
    var broken = f.service(failed, f.server, transactions);
    assertThrows(
        IllegalStateException.class,
        () -> broken.save(OWNER, new Save(PROJECT, ID, UUID.randomUUID(), null, definition())));
    assertTrue(store.find(ID, false).isEmpty());
    assertEquals(
        0L, jdbc.queryForObject("SELECT count(*) FROM hosted_tool_scope_mutations", Long.class));
    var b = save();
    assertThrows(
        IllegalStateException.class,
        () -> broken.change(OWNER, new Change(PROJECT, ID, UUID.randomUUID(), 1, Action.DISABLE)));
    assertEquals(b, store.find(ID, false).orElseThrow());
    assertThrows(
        CallerFault.class,
        () -> service.change(OWNER, new Change(PROJECT, ID, UUID.randomUUID(), 2, Action.DISABLE)));
  }

  @Test
  void concurrent_executor_registration_has_one_winner() throws Exception {
    save();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var ready = new CountDownLatch(2);
      var start = new CountDownLatch(1);
      Callable<Boolean> register =
          () -> {
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS));
            try {
              service.register(
                  EXECUTOR, "worker-session", "socket", new Register(ID, UUID.randomUUID(), 1));
              return true;
            } catch (CallerFault refused) {
              return false;
            }
          };
      var a = pool.submit(register);
      var b = pool.submit(register);
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
      assertEquals(
          1L, jdbc.queryForObject("SELECT count(*) FROM hosted_tool_scope_workers", Long.class));
    }
  }

  @Test
  void project_deletion_withdraws_worker_and_retains_unadoptable_uuid() {
    save();
    service.register(EXECUTOR, "worker-session", "socket", new Register(ID, UUID.randomUUID(), 1));
    jdbc.update("DELETE FROM projects WHERE name=?", PROJECT);
    assertTrue(store.find(ID, false).isEmpty());
    assertEquals(
        0L, jdbc.queryForObject("SELECT count(*) FROM hosted_tool_scope_workers", Long.class));
    assertTrue(
        jdbc.queryForObject(
            "SELECT deleted AND NOT enabled AND project_id IS NULL FROM hosted_tool_scopes WHERE id=?",
            Boolean.class,
            ID));
    jdbc.update("INSERT INTO projects(name,workspace) VALUES (?,'fixture')", PROJECT);
    when(f.projects.id(PROJECT))
        .thenReturn(
            jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, PROJECT));
    assertThrows(CallerFault.class, this::save);
  }

  @Test
  void hosted_selection_and_request_publication_are_atomic_and_duplicate_does_not_repin() {
    var b = save();
    var r =
        service.register(
            EXECUTOR, "worker-session", "socket", new Register(ID, UUID.randomUUID(), 1));
    f.catalogue(b, f.clock.instant());
    long project = f.projects.id(PROJECT);
    var broker =
        new JdbcRelayRepository(
            jdbc,
            transactions,
            new JdbcRelayPayloadStore(
                jdbc,
                transactions,
                java.time.Duration.ofHours(1),
                java.time.Duration.ofHours(24),
                java.time.Duration.ofDays(30)));
    var relay = new DurableRelay(broker, () -> f.clock.instant());
    var spyRelay = spy(relay);
    doAnswer(
            c -> {
              c.callRealMethod();
              throw new IllegalStateException("Injected after publication");
            })
        .when(spyRelay)
        .publish(any(), any());
    var tool =
        io.aeyer.plowshare.server.applications.ApplicationServerSettings.catalogue(CATALOGUE)
            .tools()
            .getFirst()
            .bind(
                PROJECT,
                new io.aeyer.plowshare.server.applications.ApplicationServerSettings.Provider(
                    b.route().provider(), EXECUTOR, "network_", 30));
    UUID id = UUID.randomUUID();
    var request =
        new RelayToolCodec.Request(
            RelayToolCodec.VERSION,
            id.toString(),
            PROJECT,
            tool.provider(),
            tool.name(),
            OWNER,
            "run",
            "call",
            f.clock.instant().plusSeconds(30).toString(),
            Map.of());
    var intent =
        new RelayToolRepository.Intent(
            id,
            project,
            RelayPort.hash(id.toString()),
            tool,
            request,
            f.clock.instant(),
            new RelayCausation("root", "run", 1),
            "coordinator",
            null);
    var broken = new JdbcRelayToolRepository(jdbc, transactions, spyRelay, service);
    assertThrows(IllegalStateException.class, () -> broken.submit(intent));
    assertTrue(broken.find(project, OWNER, id).isEmpty());
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE scope_key=?",
            Long.class,
            "project:" + project));
    var ledger = new JdbcRelayToolRepository(jdbc, transactions, relay, service);
    var retained = ledger.submit(intent);
    assertEquals(r.registration(), retained.intent().hosted().registration());
    assertEquals(
        f.grant.get().credential().generation(), retained.intent().hosted().credentialGeneration());
    assertEquals(
        retained,
        new JdbcRelayToolRepository(jdbc, transactions, relay, service)
            .find(project, OWNER, id)
            .orElseThrow());
    service.change(OWNER, new Change(PROJECT, ID, UUID.randomUUID(), 1, Action.DISABLE));
    assertEquals(retained, ledger.submit(intent));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE scope_key=? AND topic=?",
            Long.class,
            "project:" + project,
            tool.requests()));
    // Hosted result publication settles the same owning record atomically, including retired
    // routes.
    when(f.ledger.find(project, OWNER, id, true))
        .thenAnswer(c -> ledger.find(project, OWNER, id, true));
    when(f.relay.registerTopic(any(), any(), any()))
        .thenAnswer(c -> relay.registerTopic(c.getArgument(0), c.getArgument(1), c.getArgument(2)));
    doAnswer(c -> relay.publish(c.getArgument(0), c.getArgument(1)))
        .when(f.relay)
        .publish(any(), any());
    var result =
        new RelayToolCodec.Result(
            RelayToolCodec.VERSION,
            id.toString(),
            PROJECT,
            tool.provider(),
            tool.name(),
            RelayToolCodec.State.COMPLETED,
            "journalled fixture");
    var body =
        new Publish(
            ID,
            r.registration(),
            new RelayPort.Publish(
                RelayToolCodec.resultRequestId(id).toString(),
                PROJECT,
                tool.results(),
                RelayToolCodec.write(result),
                f.clock.instant(),
                id.toString(),
                tool.requests(),
                id.toString()));
    when(f.ledger.finish(any(), any()))
        .thenThrow(new IllegalStateException("Injected completion failure"));
    assertThrows(
        IllegalStateException.class,
        () -> service.publish(EXECUTOR, "reconnected", "socket2", body));
    assertNull(ledger.find(project, OWNER, id).orElseThrow().result());
    assertTrue(
        broker
            .retained(
                new Relay.TopicKey(project, tool.results()),
                RelayToolCodec.resultRequestId(id).toString())
            .isEmpty());
    doAnswer(c -> ledger.finish(c.getArgument(0), c.getArgument(1)))
        .when(f.ledger)
        .finish(any(), any());
    service.publish(EXECUTOR, "reconnected", "socket2", body);
    assertEquals(result, ledger.find(project, OWNER, id).orElseThrow().result());
    assertTrue(
        broker
            .retained(
                new Relay.TopicKey(project, tool.results()),
                RelayToolCodec.resultRequestId(id).toString())
            .isPresent());
  }
}
