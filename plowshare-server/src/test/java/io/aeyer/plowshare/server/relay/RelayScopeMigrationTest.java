package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
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

/** Verifies the populated V119-to-V120 upgrade, real FK constraints and cascading ownership. */
@Tag("full-db")
@Testcontainers
class RelayScopeMigrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  @Test
  void upgrade_preserves_input_positions_source_and_receipts_and_isolates_system_topics() {
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(data).target("119").load().migrate();
    var jdbc = new JdbcTemplate(data);
    long projectId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('relay-upgrade','fixture') RETURNING id",
                Long.class));
    var at = Instant.parse("2026-10-05T01:00:00Z");
    var time = Timestamp.from(at);
    var payload = new RelayPayload.Text("original retained input");
    String encoded = RelayPayloadCodec.write(payload);
    var routing =
        RelayDeliveries.SourcePin.of("notices/routes.js", "export const manifest = {};\r\n");
    var id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO relay_topics(project_id,name,payload_kind,retention_seconds,last_position) VALUES(?,'release.observed','TEXT',345600,1)",
        projectId);
    jdbc.update(
        "INSERT INTO relay_publications(project_id,topic,position,event_id,publisher,occurred_at,published_at,schema_version,payload) VALUES(?,'release.observed',1,'original','source',?,?,1,?::jsonb)",
        projectId,
        time,
        time,
        encoded);
    jdbc.update(
        "INSERT INTO relay_subscriptions(project_id,topic,subscriber,seen_through,seen_at) VALUES(?,'release.observed','notices',1,?)",
        projectId,
        time);
    jdbc.update(
        """
        INSERT INTO relay_admissions(project_id,topic,subscriber,publication_position,event_id,publisher,
          occurred_at,published_at,payload_kind,schema_version,payload,routing_path,routing_source,routing_hash,branch_count,admitted_at)
        VALUES(?,'release.observed','notices',1,'original','source',?,?,'TEXT',1,?::jsonb,?,?,?,1,?)
        """,
        projectId,
        time,
        time,
        encoded,
        routing.path(),
        routing.source(),
        routing.sha256(),
        time);
    jdbc.update(
        """
        INSERT INTO relay_deliveries(id,project_id,topic,subscriber,publication_position,branch_index,
          branch_name,receiver,state,updated_at,receipt_namespace,receipt_id)
        VALUES(?,?,'release.observed','notices',1,0,'original','fixture','ACCEPTED',?,'fixture','durable-receipt')
        """,
        id,
        projectId,
        time);

    // Upgrade the populated pre-scope schema through the current version before using current
    // repositories, which also require the later lease and generation columns.
    Flyway.configure().dataSource(data).load().migrate();
    var template = new TransactionTemplate(new DataSourceTransactionManager(data));
    var transactions =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> work) {
            return Objects.requireNonNull(template.execute(status -> work.get()));
          }
        };
    var broker = new JdbcRelayRepository(jdbc, transactions);
    var deliveries = new JdbcRelayDeliveryRepository(jdbc, transactions);
    var project = new Relay.TopicKey(projectId, "release.observed");
    var subscription = new Relay.SubscriptionKey(project, "notices");
    assertEquals(1, broker.topic(project).lastPosition());
    assertEquals(1, broker.read(subscription, 1).subscription().seenThrough());
    assertEquals(payload, broker.retained(project, "original").orElseThrow().event().payload());
    var admission =
        deliveries.admission(new RelayDeliveries.AdmissionKey(subscription, 1)).orElseThrow();
    assertEquals(routing, admission.decision().routing());
    assertEquals(id, admission.deliveries().getFirst().id());
    var delivered = deliveries.delivery(admission.deliveries().getFirst()).orElseThrow();
    assertEquals(RelayDeliveries.State.ACCEPTED, delivered.state());
    assertEquals(new RelayDeliveries.Receipt("fixture", "durable-receipt"), delivered.receipt());

    var system = new Relay.TopicKey(Relay.SystemScope.SERVER, project.name());
    broker.configureTopic(
        system, RelayPayload.Kind.TEXT, new Relay.Policy(Duration.ofSeconds(1), null));
    var occurrence =
        new Relay.Draft(
            "original", "system-source", at, null, null, new RelayPayload.Text("system input"));
    assertEquals(1, broker.append(system, occurrence, at).position());
    var systemSubscriber = new Relay.SubscriptionKey(system, "notices");
    broker.subscribe(systemSubscriber, Relay.Start.OLDEST_RETAINED, at);
    assertEquals(1, broker.unread(systemSubscriber).count());
    assertEquals(0, broker.unread(subscription).count());
    assertTrue(broker.topicsToPrune(at.plusSeconds(2), 100).contains(system));
    assertEquals(1, broker.prune(system, at.plusSeconds(2), 100));
    assertTrue(broker.read(systemSubscriber, 1).gap().isPresent());
    assertTrue(broker.retained(project, "original").isPresent());

    for (String invalid : java.util.List.of("project:0", "project:01", "unknown"))
      assertThrows(
          DataIntegrityViolationException.class,
          () ->
              jdbc.update("UPDATE relay_topics SET scope_key=? WHERE scope_key='system'", invalid));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE relay_topics SET project_id=? WHERE scope_key='system'", projectId));
    jdbc.update("DELETE FROM projects WHERE id=?", projectId);
    assertThrows(IllegalStateException.class, () -> broker.topic(project));
    assertTrue(deliveries.delivery(delivered.key()).isEmpty());
    assertEquals(system, broker.topic(system).key());
    assertEquals(1, broker.read(systemSubscriber, 1).gap().orElseThrow().throughInclusive());
  }
}
