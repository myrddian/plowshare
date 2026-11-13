package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Requires PostgreSQL migration, unique-key concurrency, rollback and receipt row mapping. */
@Tag("full-db")
@Testcontainers
class JdbcRelayPublishingRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;
  private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
  private JdbcRelayRepository broker;
  private JdbcRelayPublishingRepository repository;
  private Relay relay;
  private RelayPublishingRepository.Intent intent;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).target("245").load().migrate();
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
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
    AuthorityFixtures.truncate(jdbc, "TRUNCATE projects,inference_run_ownership CASCADE");
    long project =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('publishing-fixture','fixture') RETURNING id",
                Long.class));
    broker =
        new JdbcRelayRepository(
            jdbc,
            transactions,
            new JdbcRelayPayloadStore(
                jdbc,
                transactions,
                Duration.ofHours(1),
                Duration.ofHours(24),
                Duration.ofDays(30)));
    relay = new DurableRelay(broker, () -> NOW);
    repository = new JdbcRelayPublishingRepository(jdbc, transactions, relay);
    var owner =
        UsageAttribution.project(
                "alice", Long.toString(project), UsageAttribution.Operation.AGENT_CHAT)
            .withExecution(
                UsageLineage.root("cnv_publication"),
                UsageLineage.root("run:cnv_publication:1"),
                UsageLineage.NONE,
                "writer",
                1L,
                1L);
    intent =
        new RelayPublishingRepository.Intent(
            UUID.randomUUID(),
            project,
            "publishing-fixture",
            owner,
            "call",
            new RelayPublishing.Request("reports.input", "  retained text\n"),
            RelayPort.hash("work"),
            NOW,
            new RelayCausation("incoming-root", "run:cnv_publication:1", 2));
  }

  @Test
  void concurrent_same_call_returns_one_atomic_receipt_and_preserves_publisher_and_ancestry()
      throws Exception {
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      Callable<RelayPublishing.Receipt> publish = () -> repository.publish(intent);
      var results = threads.invokeAll(List.of(publish, publish, publish));
      var receipt = results.getFirst().get();
      for (var result : results) assertEquals(receipt, result.get());
      assertEquals("1", receipt.position());
    }
    assertEquals(1L, count("relay_execution_publications"));
    assertEquals(1L, count("relay_publications"));
    var published =
        relay
            .retained(
                new Relay.TopicKey(intent.project(), intent.request().topic()),
                intent.id().toString())
            .orElseThrow();
    assertEquals(
        RelayExecutionPublisher.identity("alice", "writer"), published.event().publisher());
    assertEquals(intent.causation(), published.event().causation());
    assertEquals(intent.request().text(), ((RelayPayload.Text) published.event().payload()).text());
  }

  @Test
  void historicalSdkExecutionProvenanceRequiresAllRetainedReceiptCoordinates() {
    repository.publish(intent);
    var topic = new Relay.TopicKey(intent.project(), intent.request().topic());
    var retained = relay.retained(topic, intent.id().toString()).orElseThrow();
    var legacyDraft =
        new Relay.Draft(
            retained.event().eventId(),
            RelayPort.publisher("alice"),
            retained.event().occurredAt(),
            retained.event().correlationId(),
            retained.event().causationId(),
            retained.event().payload(),
            retained.event().causation());
    var legacy =
        new Relay.Publication(topic, retained.position(), retained.publishedAt(), legacyDraft);
    var ports = new JdbcRelayPortRepository(jdbc, transactions, relay);
    assertTrue(ports.executionPublication(legacy));
    assertFalse(
        ports.executionPublication(
            new Relay.Publication(
                new Relay.TopicKey(topic.projectId() + 1, topic.name()),
                legacy.position(),
                legacy.publishedAt(),
                legacyDraft)));
    assertFalse(
        ports.executionPublication(
            new Relay.Publication(
                new Relay.TopicKey(topic.projectId(), "another.topic"),
                legacy.position(),
                legacy.publishedAt(),
                legacyDraft)));
    assertFalse(
        ports.executionPublication(
            new Relay.Publication(
                topic, legacy.position() + 1, legacy.publishedAt(), legacyDraft)));
    assertFalse(
        ports.executionPublication(
            new Relay.Publication(
                topic, legacy.position(), legacy.publishedAt().plusSeconds(1), legacyDraft)));
  }

  @Test
  void broker_expiry_and_topic_removal_do_not_allow_an_old_call_to_publish_again() {
    var original = repository.publish(intent);
    var topic = new Relay.TopicKey(intent.project(), intent.request().topic());
    assertEquals(1, broker.prune(topic, NOW.plus(Duration.ofDays(5)), 100));
    assertEquals(original, repository.publish(intent));
    assertEquals(0L, count("relay_publications"));
    jdbc.update("DELETE FROM relay_topics WHERE name=?", intent.request().topic());
    assertEquals(original, repository.publish(intent));
    assertEquals(0L, count("relay_topics"));
    var changed =
        new RelayPublishingRepository.Intent(
            intent.id(),
            intent.project(),
            intent.projectName(),
            intent.owner(),
            intent.call(),
            new RelayPublishing.Request("other.topic", "changed"),
            RelayPort.hash("changed"),
            NOW,
            intent.causation());
    assertThrows(IllegalArgumentException.class, () -> repository.publish(changed));
    assertEquals(0L, count("relay_topics"));
  }

  @Test
  void enclosing_failure_rolls_back_receipt_topic_and_publication_together() {
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.inTransaction(
                () -> {
                  repository.publish(intent);
                  throw new IllegalStateException("fixture rollback after publication");
                }));
    assertEquals(0L, count("relay_execution_publications"));
    assertEquals(0L, count("relay_publications"));
    assertEquals(0L, count("relay_topics"));
    assertEquals("1", repository.publish(intent).position());
  }

  @Test
  void an_existing_policy_is_preserved_and_a_wrong_payload_family_rolls_back_intent() {
    var topic = new Relay.TopicKey(intent.project(), intent.request().topic());
    var policy = new Relay.Policy(Duration.ofHours(2), 7L);
    relay.configureTopic(topic, RelayPayload.Kind.TEXT, policy);
    repository.publish(intent);
    assertEquals(policy, relay.topic(topic).policy());
    var wrong = new Relay.TopicKey(intent.project(), "wrong.family");
    relay.registerTopic(wrong, RelayPayload.Kind.LIFECYCLE, Relay.Policy.systemDefault());
    var invalid =
        new RelayPublishingRepository.Intent(
            UUID.randomUUID(),
            intent.project(),
            intent.projectName(),
            intent.owner(),
            "other-call",
            new RelayPublishing.Request(wrong.name(), "text"),
            RelayPort.hash("wrong"),
            NOW,
            intent.causation());
    assertThrows(IllegalArgumentException.class, () -> repository.publish(invalid));
    assertEquals(1L, count("relay_execution_publications"));
  }

  @Test
  void retained_run_ownership_matches_project_account_run_turn_and_definition_without_model_calls()
      throws Exception {
    var owner = intent.owner();
    var root = new RelayCausation("parent-root", "parent-event", 1);
    jdbc.update(
        "INSERT INTO conversations(id,project_id,created_at,budget_total,budget_spent,owner_handle,origin,relay_causation) VALUES(?,?,clock_timestamp(),100,0,'alice','turn',?::jsonb)",
        owner.conversations().id(),
        intent.project(),
        RelayCausationCodec.write(root));
    jdbc.update(
        "INSERT INTO inference_run_ownership(conversation_id,turn_ordinal,attribution) VALUES(?,1,?::jsonb)",
        owner.conversations().id(),
        new ObjectMapper().writeValueAsString(owner));
    assertEquals(root, repository.ancestry(intent.project(), owner).orElseThrow());
    assertTrue(repository.ancestry(intent.project() + 1, owner).isEmpty());
    for (var foreign :
        List.of(
            owner.withExecution(
                owner.conversations(),
                UsageLineage.root("job-not-accounting-run"),
                owner.orchestrations(),
                owner.agentName(),
                1L,
                1L),
            owner.withExecution(
                owner.conversations(),
                owner.runs(),
                owner.orchestrations(),
                "other_definition",
                1L,
                1L),
            owner.withExecution(
                owner.conversations(),
                owner.runs(),
                owner.orchestrations(),
                owner.agentName(),
                2L,
                1L))) {
      assertTrue(repository.ancestry(intent.project(), foreign).isEmpty());
    }
    assertEquals(0L, count("inference_calls"));
  }

  @Test
  void a_lowered_text_allowance_refuses_new_work_but_preserves_original_receipts() {
    var original = repository.publish(intent);
    var restrictedBroker =
        new JdbcRelayRepository(
            jdbc,
            transactions,
            new JdbcRelayPayloadStore(
                jdbc, transactions, Duration.ofHours(1), Duration.ofHours(24), Duration.ofDays(30)),
            new RelayTextLimit(2));
    var restricted =
        new JdbcRelayPublishingRepository(
            jdbc, transactions, new DurableRelay(restrictedBroker, () -> NOW));
    assertEquals(original, restricted.publish(intent));
    var oversized =
        new RelayPublishingRepository.Intent(
            UUID.randomUUID(),
            intent.project(),
            intent.projectName(),
            intent.owner(),
            "next-call",
            new RelayPublishing.Request("other.topic", "éé"),
            RelayPort.hash("oversize"),
            NOW,
            intent.causation());
    assertThrows(IllegalArgumentException.class, () -> restricted.publish(oversized));
    assertEquals(1L, count("relay_execution_publications"));
    assertEquals(1L, count("relay_topics"));
    assertEquals(1L, count("relay_publications"));
  }

  private long count(String table) {
    // Test-owned closed identifiers; production exposes no generic SQL capability.
    if (!List.of(
            "relay_topics", "relay_publications", "relay_execution_publications", "inference_calls")
        .contains(table)) throw new IllegalArgumentException("Unknown fixture table");
    return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
  }
}
