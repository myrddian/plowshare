package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.messaging.JdbcMessagingRepository;
import io.aeyer.plowshare.server.messaging.MessageIngress;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.messaging.RelayMessaging;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Synthetic pre-retirement upgrade: actual Flyway rollback, table locks and native ID retention.
 */
@Tag("full-db")
@Testcontainers
class LegacyRelayCutoverDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  @TempDir Path definitions;

  @Test
  void unresolved_work_blocks_archival_while_settled_history_and_native_schedules_survive()
      throws Exception {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    migrate(source, "254");
    var fixture = new BoardFixture(source);
    var jdbc = fixture.jdbc;
    jdbc.update("UPDATE admins SET server_admin=true WHERE handle='enzo'");
    fixture.conversations.open(Home.of("payments"), Budget.of(10), TurnCap.of(4), "enzo");
    Files.writeString(
        definitions.resolve("reviewer.md"),
        "---\nname: reviewer\ndescription: fixture\nmodel: test\ntools: [send_message]\nmax-turns: 4\nmax-model-calls: 8\n---\nHelp.\n");
    var agents = AgentRegistry.of(definitions, Set.of("send_message"));
    var messages =
        new RelayMessaging(
            new JdbcMessagingRepository(jdbc),
            new JdbcAllowanceRepository(jdbc),
            fixture.conversations,
            fixture.firings,
            MessageInboxFixture.inboxes(fixture),
            fixture.work,
            mock(MessageService.Voice.class),
            (agent, project) -> agents.get(agent),
            fixture.drained::add,
            fixture.clock);
    var request =
        new MessageIngress.Request(
            "payments",
            "native-channel",
            "retained-client",
            "reviewer",
            UUID.randomUUID(),
            null,
            "Retained native body 😀",
            null,
            new MessageIngress.Source("relay", "original-event", "a".repeat(64)));
    var task = messages.receive("enzo", request);
    String legacySource =
        "{\"messageId\":\"historical-message\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"Original protocol evidence\"}]}";
    jdbc.update(
        "UPDATE message_external_tasks SET source=?::jsonb WHERE id=?", legacySource, task.id());
    var scheduled =
        new ScheduledWork(
            1,
            "0 0 9 * * *",
            "UTC",
            false,
            new ScheduledWork.Action("skill", "reviewer", "review", "Exact arguments", null),
            new ScheduledWork.Target("message", null, null, "reviewer", null),
            null);
    var first =
        messages.receiveScheduled(
            "enzo", "payments", "retained-schedule", "first-firing", scheduled);

    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('historian','fixture')");
    String conversation =
        fixture.conversations.open(Home.of("retired-scope"), Budget.of(10), null, "historian").id();
    long project =
        jdbc.queryForObject("SELECT id FROM projects WHERE name='retired-scope'", Long.class);
    for (String state : List.of("COMPLETED", "FAILED", "CANCELED", "REJECTED"))
      insertOutgoing(jdbc, UUID.randomUUID(), project, conversation, state);
    UUID guard = UUID.randomUUID();
    insertOutgoing(jdbc, guard, project, conversation, "QUEUED");
    jdbc.update(
        "INSERT INTO outgoing_peers(account,project_key,peer,advertised_at,agent_card) VALUES('historian',0,'global-peer','2026-01-01T00:00:00Z','{\"name\":\"Global\"}'::jsonb),('historian',?,'project-peer','2026-01-02T00:00:00Z','{\"name\":\"Project\"}'::jsonb),('historian',9223372036854775807,'unresolved-peer','2026-01-03T00:00:00Z',NULL)",
        project);
    Map<String, String> nativeBefore = nativeHistory(jdbc);
    for (String unresolved :
        List.of("QUEUED", "DISPATCHED", "WORKING", "INPUT_REQUIRED", "AUTH_REQUIRED", "UNKNOWN")) {
      jdbc.update("UPDATE outgoing_work SET state=? WHERE id=?", unresolved, guard);
      var failure = assertThrows(FlywayException.class, () -> migrate(source, "255"));
      assertTrue(causes(failure).contains("V255 outgoing retirement preflight refused"));
      assertFalse(causes(failure).contains("Retained outgoing secret"));
      assertEquals(
          "outgoing_work",
          jdbc.queryForObject("SELECT to_regclass('outgoing_work')::text", String.class));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM information_schema.columns WHERE table_name='outgoing_work' AND column_name='project_name_snapshot'",
              Integer.class));
      assertEquals(nativeBefore, nativeHistory(jdbc));
    }

    // A writer that wins before the migration lock must be inspected after it commits.
    // Checking state before waiting for this lock would incorrectly archive its UNKNOWN row.
    jdbc.update("UPDATE outgoing_work SET state='COMPLETED' WHERE id=?", guard);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var writer =
          executor.submit(
              () ->
                  new TransactionTemplate(new DataSourceTransactionManager(source))
                      .executeWithoutResult(
                          status -> {
                            jdbc.update(
                                "UPDATE outgoing_work SET state='UNKNOWN' WHERE id=?", guard);
                            entered.countDown();
                            await(release);
                          }));
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      var upgrade = executor.submit(() -> migrate(source, "255"));
      try {
        awaitMigrationLock(jdbc);
        assertThrows(TimeoutException.class, () -> upgrade.get(200, TimeUnit.MILLISECONDS));
      } finally {
        release.countDown();
      }
      writer.get(10, TimeUnit.SECONDS);
      var refused = assertThrows(ExecutionException.class, () -> upgrade.get(30, TimeUnit.SECONDS));
      assertTrue(causes(refused).contains("V255 outgoing retirement preflight refused"));
    }
    jdbc.update("UPDATE outgoing_work SET state='COMPLETED' WHERE id=?", guard);
    String outgoingBefore = rows(jdbc, "outgoing_work");
    String peersBefore = rows(jdbc, "outgoing_peers");
    migrate(source, "255");

    assertEquals(outgoingBefore, archivedRows(jdbc, "retired_outgoing_work"));
    assertEquals(peersBefore, archivedRows(jdbc, "retired_outgoing_peers"));
    assertEquals(
        5,
        jdbc.queryForObject(
            "SELECT count(*) FROM retired_outgoing_work WHERE project_name_snapshot='retired-scope'",
            Integer.class));
    assertTrue(
        jdbc.queryForObject(
            "SELECT project_key=0 AND project_name_snapshot IS NULL FROM retired_outgoing_peers WHERE peer='global-peer'",
            Boolean.class));
    assertTrue(
        jdbc.queryForObject(
            "SELECT project_key>0 AND project_name_snapshot IS NULL FROM retired_outgoing_peers WHERE peer='unresolved-peer'",
            Boolean.class));
    assertEquals(
        "retired-scope",
        jdbc.queryForObject(
            "SELECT project_name_snapshot FROM retired_outgoing_peers WHERE peer='project-peer'",
            String.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_constraint WHERE contype='f' AND conrelid IN ('retired_outgoing_work'::regclass,'retired_outgoing_peers'::regclass)",
            Integer.class));
    for (String table : List.of("retired_outgoing_work", "retired_outgoing_peers")) {
      assertThrows(
          DataAccessException.class,
          () -> jdbc.execute("INSERT INTO " + table + " SELECT * FROM " + table + " WHERE false"));
      assertThrows(
          DataAccessException.class,
          () -> jdbc.execute("UPDATE " + table + " SET account=account WHERE false"));
      assertThrows(
          DataAccessException.class, () -> jdbc.execute("DELETE FROM " + table + " WHERE false"));
      assertThrows(DataAccessException.class, () -> jdbc.execute("TRUNCATE " + table));
    }
    jdbc.update("DELETE FROM conversations WHERE id=?", conversation);
    jdbc.update("DELETE FROM projects WHERE id=?", project);
    jdbc.update("DELETE FROM admins WHERE handle='historian'");
    assertEquals(outgoingBefore, archivedRows(jdbc, "retired_outgoing_work"));
    assertEquals(peersBefore, archivedRows(jdbc, "retired_outgoing_peers"));
    assertEquals(nativeBefore, nativeHistory(jdbc));
    var retained = messages.receive("enzo", request);
    assertEquals(task.id(), retained.id());
    assertEquals(task.context(), retained.context());
    assertEquals(task.source(), retained.source());
    assertEquals(task.message(), retained.message());
    var same =
        messages.receiveScheduled(
            "enzo", "payments", "retained-schedule", "first-firing", scheduled);
    var next =
        messages.receiveScheduled(
            "enzo", "payments", "retained-schedule", "next-firing", scheduled);
    assertEquals(first.id(), same.id());
    assertEquals(first.requestId(), same.requestId());
    assertNotEquals(first.id(), next.id());
    assertEquals(first.context(), next.context());
  }

  private static void migrate(DriverManagerDataSource source, String target) {
    Flyway.configure().dataSource(source).target(target).load().migrate();
  }

  private static void insertOutgoing(
      JdbcTemplate jdbc, UUID id, long project, String conversation, String state) {
    jdbc.update(
        "INSERT INTO outgoing_work(id,account,request_id,peer,project_id,conversation,message,state,cancel_requested,worker,lease_until,remote_task,remote_context,result,error,revision,created_at) VALUES(?,'historian',?,'retained-peer',?,?,'{\"body\":\"Retained outgoing secret\"}'::jsonb,?,true,'retained-worker','2026-01-01T03:00:00Z','remote-task','remote-context','{\"evidence\":[1,2]}'::jsonb,'retained-error',17,'2026-01-01T00:00:00Z')",
        id,
        UUID.randomUUID(),
        project,
        conversation,
        state);
  }

  private static Map<String, String> nativeHistory(JdbcTemplate jdbc) {
    var snapshot = new LinkedHashMap<String, String>();
    for (String table :
        List.of(
            "message_external_contexts",
            "message_external_tasks",
            "message_records",
            "message_routes",
            "message_instances",
            "message_agent_inboxes",
            "execution_allowances")) snapshot.put(table, rows(jdbc, table));
    return Map.copyOf(snapshot);
  }

  // All identifiers come from the fixed fixture allowlists above, never external input.
  private static String rows(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject(
        "SELECT coalesce(jsonb_agg(retained ORDER BY retained::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t) AS retained FROM "
            + table
            + " t) records",
        String.class);
  }

  private static String archivedRows(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject(
        "SELECT coalesce(jsonb_agg(retained ORDER BY retained::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t)-'project_name_snapshot' AS retained FROM "
            + table
            + " t) records",
        String.class);
  }

  private static String causes(Throwable failure) {
    var messages = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      messages.append(cause.getMessage()).append('\n');
    return messages.toString();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS))
        throw new AssertionError("Migration barrier timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static void awaitMigrationLock(JdbcTemplate jdbc) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE relation='outgoing_work'::regclass AND mode='AccessExclusiveLock' AND NOT granted)",
        Boolean.class)) {
      assertTrue(
          System.nanoTime() < deadline, "Migration did not wait for its outgoing table fence");
      Thread.sleep(20);
    }
  }
}
