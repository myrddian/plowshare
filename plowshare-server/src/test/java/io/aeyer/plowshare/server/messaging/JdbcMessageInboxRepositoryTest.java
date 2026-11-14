package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.relay.DurableRelay;
import io.aeyer.plowshare.server.relay.JdbcRelayPayloadStore;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL tuple ordering, nullable cursor binding and partial discovery indexes. */
@Tag("full-db")
@Testcontainers
class JdbcMessageInboxRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  @Test
  void numericKeysetsCoverReadyDrainedAndRetiredInboxesWithMatchingIndexes() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    var jdbc = new JdbcTemplate(source);
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('alice','fixture')");
    jdbc.update(
        "INSERT INTO projects(id,name,workspace) OVERRIDING SYSTEM VALUE VALUES(22,'project-small','fixture'),(100,'project-large','fixture')");
    var work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    var relay =
        new DurableRelay(
            new JdbcRelayRepository(
                jdbc,
                work,
                new JdbcRelayPayloadStore(
                    jdbc, work, Duration.ofHours(1), Duration.ofHours(24), Duration.ofDays(30))),
            Instant::now);
    var repository = new JdbcMessageInboxRepository(jdbc);
    var smaller =
        work.inTransaction(
            () -> repository.retain("alice", "project-small", "worker", "messaging.agent.small"));
    var larger =
        work.inTransaction(
            () -> repository.retain("alice", "project-large", "worker", "messaging.agent.large"));
    for (var inbox : List.of(smaller, larger)) {
      relay.registerTopic(
          inbox.subscription().topic(), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
      relay.subscribe(inbox.subscription(), Relay.Start.OLDEST_RETAINED);
      relay.publish(
          inbox.subscription().topic(),
          new Relay.Draft(
              "event",
              "messaging:fixture",
              Instant.now(),
              null,
              null,
              new RelayPayload.Text("retained")));
    }
    var first = MessageInboxRepository.Cursor.after(smaller);
    assertEquals(List.of(smaller), repository.ready(null, 1));
    assertEquals(List.of(larger), repository.ready(first, 1));
    assertTrue(repository.ready(MessageInboxRepository.Cursor.after(larger), 1).isEmpty());
    for (var inbox : List.of(smaller, larger)) relay.advanceSeen(inbox.subscription(), 1);
    assertEquals(List.of(smaller), repository.drained(null, 1));
    assertEquals(List.of(larger), repository.drained(first, 1));
    for (var inbox : List.of(smaller, larger))
      assertTrue(work.inTransaction(() -> repository.retire(inbox)));
    assertEquals(List.of(smaller), repository.retired(null, 1));
    assertEquals(List.of(larger), repository.retired(first, 1));
    assertTrue(repository.ready(null, 32).isEmpty());
    assertTrue(repository.drained(null, 32).isEmpty());
    String activeIndex =
        jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname='message_agent_inboxes_active_cursor'",
            String.class);
    String retiredIndex =
        jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname='message_agent_inboxes_retired'",
            String.class);
    assertTrue(
        activeIndex.contains("(project_id, topic)") && activeIndex.contains("retired_at IS NULL"));
    assertTrue(
        retiredIndex.contains("(project_id, topic)")
            && retiredIndex.contains("retired_at IS NOT NULL"));
  }
}
