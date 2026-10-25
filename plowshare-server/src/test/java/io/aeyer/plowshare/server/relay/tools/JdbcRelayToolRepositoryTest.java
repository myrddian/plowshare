package io.aeyer.plowshare.server.relay.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.DurableRelay;
import io.aeyer.plowshare.server.relay.JdbcRelayPayloadStore;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.Relay;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL assertions require real transaction rollback, migrations and retained row mapping. */
@Tag("full-db")
@Testcontainers
class JdbcRelayToolRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  static UnitOfWork transactions;
  static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
  JdbcRelayRepository broker;
  JdbcRelayToolRepository repository;
  RelayToolRepository.Intent intent;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).target("141").load().migrate();
    jdbc = new JdbcTemplate(source);
    Long project =
        jdbc.queryForObject(
            "INSERT INTO projects(name,workspace) VALUES('tool-upgrade-fixture','fixture') RETURNING id",
            Long.class);
    UUID retained = UUID.randomUUID();
    var oldBinding =
        new RelayToolDefinition(
            "tool-upgrade-fixture", "scanner", "provider", "old_tool", "Fixture", List.of(), 30);
    var oldRequest =
        new RelayToolCodec.Request(
            RelayToolCodec.VERSION,
            retained.toString(),
            oldBinding.project(),
            oldBinding.provider(),
            oldBinding.name(),
            "caller",
            "run",
            "call",
            NOW.plusSeconds(30).toString(),
            Map.of());
    String oldText = RelayToolCodec.write(oldBinding);
    jdbc.update(
        "INSERT INTO relay_tool_invocations(id,project_id,account,run_id,call_id,fingerprint,binding,request,occurred_at,causation) VALUES(?,?,'caller','run','call',?,?,?,?,'{\"rootId\":\"root\",\"parentId\":\"run\",\"depth\":1}'::jsonb)",
        retained,
        project,
        RelayPort.hash("legacy"),
        oldText,
        RelayToolCodec.write(oldRequest),
        java.time.OffsetDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC));
    Flyway.configure().dataSource(source).load().migrate();
    assertEquals(
        oldText,
        jdbc.queryForObject(
            "SELECT binding FROM relay_tool_invocations WHERE id=?", String.class, retained));
    var template = new TransactionTemplate(new DataSourceTransactionManager(source));
    transactions =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> work) {
            return Objects.requireNonNull(template.execute(status -> work.get()));
          }
        };
  }

  @BeforeEach
  void fresh() {
    io.aeyer.plowshare.server.testing.AuthorityFixtures.truncate(
        jdbc, "TRUNCATE projects,inference_run_ownership CASCADE");
    long project =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('tool-fixture','fixture') RETURNING id",
                Long.class));
    broker =
        new JdbcRelayRepository(
            jdbc,
            transactions,
            new JdbcRelayPayloadStore(
                jdbc,
                transactions,
                java.time.Duration.ofHours(1),
                java.time.Duration.ofHours(24),
                java.time.Duration.ofDays(30)));
    repository =
        new JdbcRelayToolRepository(jdbc, transactions, new DurableRelay(broker, () -> NOW));
    var binding =
        new RelayToolDefinition(
            "tool-fixture", "scanner", "provider", "network_scope", "Read scope", List.of(), 30);
    UUID id = UUID.randomUUID();
    var request =
        new RelayToolCodec.Request(
            RelayToolCodec.VERSION,
            id.toString(),
            binding.project(),
            binding.provider(),
            binding.name(),
            "caller",
            "run",
            "call",
            NOW.plusSeconds(30).toString(),
            Map.of());
    intent =
        new RelayToolRepository.Intent(
            id,
            project,
            RelayPort.hash("identity"),
            binding,
            request,
            NOW,
            new RelayCausation("root", "run", 1));
  }

  @Test
  void suppliedBindingLeaseKeepsCurrentGenerationAndProjectWithdrawalFences() {
    var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var scopes = new JdbcHostedToolScopeRepository(jdbc, json);
    String owner = "hosted-reuse-owner";
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES(?,'fixture') ON CONFLICT DO NOTHING",
        owner);
    long project =
        jdbc.queryForObject("SELECT id FROM projects WHERE name='tool-fixture'", Long.class);
    var connection = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO provider_connections(id,project_id,revision,enabled,definition) VALUES(?,?,?,true,'{}'::jsonb)",
        connection,
        project,
        UUID.randomUUID());
    var definition =
        new io.aeyer.plowshare.protocol.HostedToolScopes.Definition(
            "scans",
            "scanner",
            "network_",
            List.of("*"),
            List.of("coordinator"),
            30,
            "configuration",
            connection,
            null,
            HostedScopeFixture.EXECUTOR);
    var id = UUID.randomUUID();
    var route =
        new io.aeyer.plowshare.protocol.ToolScopes.Connection(
            "tool-fixture",
            definition.scope(),
            definition.provider(),
            io.aeyer.plowshare.protocol.HostedToolScopes.route(id, 1),
            definition.executor(),
            definition.prefix(),
            definition.grants(),
            definition.agents(),
            definition.leaseSeconds());
    var binding =
        new io.aeyer.plowshare.protocol.HostedToolScopes.Binding(
            id, "tool-fixture", owner, 1, true, false, false, definition, owner, route);
    scopes.save(binding, null);
    var registration =
        new io.aeyer.plowshare.protocol.HostedToolScopes.Registration(
            binding,
            UUID.randomUUID(),
            NOW.atOffset(java.time.ZoneOffset.UTC),
            NOW.plusSeconds(30).atOffset(java.time.ZoneOffset.UTC));
    scopes.lease(
        new HostedToolScopeRepository.Lease(
            registration, "session", "socket", UUID.randomUUID(), 7));
    var retained = scopes.leases(List.of(binding));
    assertEquals(1, retained.size());
    assertSame(binding, retained.getFirst().registration().binding());
    assertEquals(7, retained.getFirst().catalogueAfter());
    jdbc.update("UPDATE hosted_tool_scopes SET generation=2 WHERE id=?", id);
    assertTrue(
        scopes.lease(binding).isEmpty(),
        "A stale typed definition cannot adopt a newer generation");
    jdbc.update("DELETE FROM projects WHERE id=?", project);
    assertTrue(
        scopes.leases(List.of(binding)).isEmpty(),
        "Project withdrawal fences the retained snapshot");
  }

  @Test
  void large_schema_definitions_survive_storage_completion_and_recovery() {
    var fields =
        java.util.stream.IntStream.range(0, 40)
            .mapToObj(
                i ->
                    "\"field_"
                        + String.format("%02d", i)
                        + "\":{\"type\":\"string\",\"description\":\""
                        + "d".repeat(2048)
                        + "\"}")
            .toList();
    var schema =
        RelayToolCodec.inputSchema(
            "{\"type\":\"object\",\"properties\":{" + String.join(",", fields) + "}}");
    var binding =
        new RelayToolDefinition(
            "tool-fixture", "scanner", "provider", "large", "Fixture", List.of(), 30, schema);
    var request =
        new RelayToolCodec.Request(
            RelayToolCodec.NESTED_VERSION,
            intent.id().toString(),
            binding.project(),
            binding.provider(),
            binding.name(),
            "caller",
            "run",
            "call",
            NOW.plusSeconds(30).toString(),
            Map.of(),
            io.aeyer.plowshare.protocol.SchemaCodec.fingerprint(schema));
    var large =
        new RelayToolRepository.Intent(
            intent.id(),
            intent.projectId(),
            intent.fingerprint(),
            binding,
            request,
            NOW,
            intent.causation());
    assertTrue(RelayToolCodec.write(binding).length() > 65536);
    assertEquals(large, repository.submit(large).intent());
    assertEquals(large, repository.submit(large).intent());
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE scope_key=?",
            Long.class,
            "project:" + large.projectId()));
    var result =
        new RelayToolCodec.Result(
            RelayToolCodec.VERSION,
            large.id().toString(),
            binding.project(),
            binding.provider(),
            binding.name(),
            RelayToolCodec.State.COMPLETED,
            "done");
    repository.finish(large, result);
    var restarted =
        new JdbcRelayToolRepository(jdbc, transactions, new DurableRelay(broker, () -> NOW));
    var stored = restarted.find(large.projectId(), "caller", large.id()).orElseThrow();
    assertEquals(large, stored.intent());
    assertEquals(result, stored.result());
    assertTrue(restarted.find(large.projectId(), "other", large.id()).isEmpty());
    assertEquals(result, restarted.finish(large, result).result());
    var empty =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update("UPDATE relay_tool_invocations SET binding='' WHERE id=?", large.id()));
    assertTrue(
        empty.getMostSpecificCause().getMessage().contains("relay_tool_invocations_binding_check"));
  }

  @Test
  void publication_and_intent_rollback_together() {
    var relay = spy(new DurableRelay(broker, () -> NOW));
    doAnswer(
            call -> {
              call.callRealMethod();
              throw new IllegalStateException("Injected failure after broker insert");
            })
        .when(relay)
        .publish(any(), any());
    var failed = new JdbcRelayToolRepository(jdbc, transactions, relay);
    assertThrows(IllegalStateException.class, () -> failed.submit(intent));
    assertTrue(repository.find(intent.projectId(), "caller", intent.id()).isEmpty());
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE scope_key=?",
            Long.class,
            "project:" + intent.projectId()));
    assertEquals(intent, repository.submit(intent).intent());
  }

  @Test
  void duplicate_identity_survives_pruning_and_completion_survives_restart() {
    var topic = new Relay.TopicKey(intent.projectId(), intent.binding().requests());
    assertEquals(intent, repository.submit(intent).intent());
    assertEquals(1, broker.prune(topic, NOW.plus(Duration.ofDays(5)), 100));
    assertEquals(intent, repository.submit(intent).intent());
    assertTrue(broker.retained(topic, intent.id().toString()).isEmpty());
    var result =
        new RelayToolCodec.Result(
            RelayToolCodec.VERSION,
            intent.id().toString(),
            intent.binding().project(),
            intent.binding().provider(),
            intent.binding().name(),
            RelayToolCodec.State.COMPLETED,
            "Configured scope");
    assertEquals(result, repository.finish(intent, result).result());
    var restarted =
        new JdbcRelayToolRepository(jdbc, transactions, new DurableRelay(broker, () -> NOW));
    assertEquals(
        result, restarted.find(intent.projectId(), "caller", intent.id()).orElseThrow().result());
    assertTrue(restarted.find(intent.projectId(), "other", intent.id()).isEmpty());
    assertEquals(result, restarted.finish(intent, result).result());
    assertThrows(
        IllegalStateException.class,
        () ->
            restarted.finish(
                intent,
                new RelayToolCodec.Result(
                    RelayToolCodec.VERSION,
                    intent.id().toString(),
                    intent.binding().project(),
                    intent.binding().provider(),
                    intent.binding().name(),
                    RelayToolCodec.State.COMPLETED,
                    "Changed result")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            restarted.submit(
                new RelayToolRepository.Intent(
                    intent.id(),
                    intent.projectId(),
                    RelayPort.hash("different"),
                    intent.binding(),
                    intent.request(),
                    NOW,
                    intent.causation())));
  }

  @Test
  void ancestry_uses_accounting_run_ownership_instead_of_job_identity() throws Exception {
    String conversation = "cnv_tool_" + intent.id();
    String run = "run:" + conversation + ":1";
    var owner =
        io.aeyer.plowshare.server.llm.accounting.UsageAttribution.project(
                "caller",
                Long.toString(intent.projectId()),
                io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Operation.AGENT_CHAT)
            .withExecution(
                io.aeyer.plowshare.server.llm.accounting.UsageLineage.root(conversation),
                io.aeyer.plowshare.server.llm.accounting.UsageLineage.root(run),
                io.aeyer.plowshare.server.llm.accounting.UsageLineage.NONE,
                "coordinator",
                1L,
                1L);
    jdbc.update(
        "INSERT INTO conversations(id,project_id,created_at,budget_total,budget_spent,owner_handle,origin,relay_causation) VALUES(?,?,clock_timestamp(),100,0,'caller','turn',?::jsonb)",
        conversation,
        intent.projectId(),
        io.aeyer.plowshare.server.relay.RelayCausationCodec.write(intent.causation()));
    jdbc.update(
        "INSERT INTO inference_run_ownership(conversation_id,turn_ordinal,attribution) VALUES(?,1,?::jsonb)",
        conversation,
        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(owner));
    assertEquals(
        java.util.Optional.of(intent.causation()), repository.ancestry(intent.projectId(), owner));
    assertTrue(repository.ancestry(intent.projectId() + 1, owner).isEmpty());
    assertTrue(
        repository
            .ancestry(
                intent.projectId(),
                owner.withExecution(
                    owner.conversations(),
                    io.aeyer.plowshare.server.llm.accounting.UsageLineage.root("job-id"),
                    owner.orchestrations(),
                    owner.agentName(),
                    1L,
                    1L))
            .isEmpty());
    assertTrue(
        repository
            .ancestry(
                intent.projectId(),
                owner.withExecution(
                    owner.conversations(),
                    owner.runs(),
                    owner.orchestrations(),
                    owner.agentName(),
                    2L,
                    1L))
            .isEmpty());
  }

  @Test
  void concurrent_submission_commits_one_request_and_one_owning_intent() throws Exception {
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<RelayToolRepository.Stored> submit =
          () -> {
            if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS))
              throw new IllegalStateException("Fixture start timed out");
            return repository.submit(intent);
          };
      var first = workers.submit(submit);
      var second = workers.submit(submit);
      start.countDown();
      assertEquals(intent, first.get(10, java.util.concurrent.TimeUnit.SECONDS).intent());
      assertEquals(intent, second.get(10, java.util.concurrent.TimeUnit.SECONDS).intent());
      assertEquals(
          1L,
          jdbc.queryForObject(
              "SELECT count(*) FROM relay_publications WHERE scope_key=? AND topic=?",
              Long.class,
              "project:" + intent.projectId(),
              intent.binding().requests()));
      assertEquals(
          1L,
          jdbc.queryForObject(
              "SELECT count(*) FROM relay_tool_invocations WHERE id=?", Long.class, intent.id()));
    }
  }
}
