package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import io.aeyer.plowshare.server.relay.RelayCausationCodec;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Requires PostgreSQL atomic reservation, immutable source mapping and job-causation trigger
 * checks.
 */
@Tag("full-db")
@Testcontainers
class NativeTurnRelaySourcesDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private long project;
  private String conversation;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void fresh() {
    project =
        jdbc.queryForObject(
            "INSERT INTO projects(name,workspace) VALUES(?,'fixture') RETURNING id",
            Long.class,
            "causal-" + UUID.randomUUID());
    conversation = "cnv_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.update(
        "INSERT INTO conversations(id,project_id,created_at,budget_total,budget_spent,owner_handle,origin,relay_causation) VALUES(?,?,clock_timestamp(),100,0,'alice','turn',?::jsonb)",
        conversation,
        project,
        RelayCausationCodec.write(RelayCausation.root("initial-conversation")));
  }

  @Test
  void concurrent_reservations_keep_distinct_roots_in_one_conversation() throws Exception {
    var turns = new TurnStore(jdbc);
    var one = new RelayCausation("first-root", "first-source", 2);
    var two = new RelayCausation("second-root", "second-source", 4);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Callable<Integer> first = () -> turns.reserve(conversation, one);
      Callable<Integer> second = () -> turns.reserve(conversation, two);
      var results = executor.invokeAll(List.of(first, second));
      int firstOrdinal = results.getFirst().get();
      int secondOrdinal = results.getLast().get();
      assertFalse(firstOrdinal == secondOrdinal);
      var firstOwner = owner("first", firstOrdinal);
      var secondOwner = owner("second", secondOrdinal);
      retain(firstOwner);
      retain(secondOwner);
      var sources = new JdbcTurnRelaySources(jdbc);
      assertEquals(one, sources.ancestry(project, firstOwner).orElseThrow());
      assertEquals(two, sources.ancestry(project, secondOwner).orElseThrow());
      assertTrue(sources.ancestry(project + 1, firstOwner).isEmpty());
      var wrong =
          firstOwner.withExecution(
              firstOwner.conversations(),
              UsageLineage.root("wrong-run"),
              UsageLineage.NONE,
              "first",
              (long) firstOrdinal,
              1L);
      assertTrue(sources.ancestry(project, wrong).isEmpty());
    }
    assertEquals(
        "initial-conversation",
        new ObjectMapper()
            .readTree(
                jdbc.queryForObject(
                    "SELECT relay_causation::text FROM conversations WHERE id=?",
                    String.class,
                    conversation))
            .get("rootId")
            .textValue());
  }

  @Test
  void failed_source_insert_rolls_back_ordinal_and_sources_cannot_be_rewritten() {
    var turns = new TurnStore(jdbc);
    var cause = new RelayCausation("root", "parent", 1);
    turns.reserve(conversation, cause);
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE turn_relay_sources SET relay_causation=?::jsonb WHERE conversation_id=?",
                RelayCausationCodec.write(RelayCausation.root("replacement")),
                conversation));
    // A test-only insert trigger emulates a storage failure in the second half of the single CTE.
    jdbc.execute(
        "CREATE FUNCTION refuse_fixture_source() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
    jdbc.execute(
        "CREATE TRIGGER refuse_fixture_source BEFORE INSERT ON turn_relay_sources FOR EACH ROW EXECUTE FUNCTION refuse_fixture_source()");
    try {
      assertThrows(DataAccessException.class, () -> turns.reserve(conversation, cause));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT turn_sequence FROM conversations WHERE id=?", Integer.class, conversation));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM turn_relay_sources WHERE conversation_id=?",
              Integer.class,
              conversation));
    } finally {
      jdbc.execute("DROP TRIGGER refuse_fixture_source ON turn_relay_sources");
      jdbc.execute("DROP FUNCTION refuse_fixture_source()");
    }
  }

  @Test
  void jobs_require_retained_native_source_before_diverging_from_conversation_root() {
    var jobs = new JdbcJobLog(jdbc);
    var cause = new RelayCausation("native-root", "request", 2);
    String name =
        jdbc.queryForObject("SELECT name FROM projects WHERE id=?", String.class, project);
    assertThrows(
        DataAccessException.class,
        () ->
            jobs.started(
                "job_Refused", "first", Home.of(name), Instant.now(), conversation, cause));
    new TurnStore(jdbc).reserve(conversation, cause);
    jobs.started("job_Accepted", "first", Home.of(name), Instant.now(), conversation, cause);
    assertEquals(
        RelayCausationCodec.write(cause),
        RelayCausationCodec.write(
            RelayCausationCodec.read(
                jdbc.queryForObject(
                    "SELECT relay_causation::text FROM jobs WHERE id='job_Accepted'",
                    String.class))));
  }

  @Test
  void message_history_without_exact_turn_source_never_borrows_another_root() throws Exception {
    // MESSAGE has no conversation-owned allowance; use its owning API so schema invariants hold.
    String name =
        jdbc.queryForObject("SELECT name FROM projects WHERE id=?", String.class, project);
    var record =
        new ConversationStore(jdbc)
            .log(Origin.MESSAGE, Home.of(name), "worker", null, null, "alice");
    var messageOwner =
        UsageAttribution.project(
                "alice", Long.toString(project), UsageAttribution.Operation.AGENT_CHAT)
            .withExecution(
                UsageLineage.root(record.id()),
                UsageLineage.root("message-run"),
                UsageLineage.NONE,
                "worker",
                1L,
                1L);
    retain(messageOwner);
    jdbc.update(
        "UPDATE conversations SET relay_causation=?::jsonb WHERE id=?",
        RelayCausationCodec.write(RelayCausation.root("other-turn")),
        record.id());
    assertTrue(new JdbcTurnRelaySources(jdbc).ancestry(project, messageOwner).isEmpty());
  }

  @Test
  void a_causal_delegation_is_created_with_its_executing_parents_source() {
    var cause = new RelayCausation("current-root", "current-message", 5);
    String name =
        jdbc.queryForObject("SELECT name FROM projects WHERE id=?", String.class, project);
    var child =
        new ConversationStore(jdbc)
            .logCausalDelegate(Home.of(name), "child", conversation, "alice", "call", cause);
    assertEquals(
        cause,
        RelayCausationCodec.read(
            jdbc.queryForObject(
                "SELECT relay_causation::text FROM conversations WHERE id=?",
                String.class,
                child.id())));
    assertEquals(
        "initial-conversation",
        RelayCausationCodec.read(
                jdbc.queryForObject(
                    "SELECT relay_causation::text FROM conversations WHERE id=?",
                    String.class,
                    conversation))
            .rootId());
  }

  private UsageAttribution owner(String definition, int ordinal) {
    return UsageAttribution.project(
            "alice", Long.toString(project), UsageAttribution.Operation.AGENT_CHAT)
        .withExecution(
            UsageLineage.root(conversation),
            UsageLineage.root("run-" + ordinal),
            UsageLineage.NONE,
            definition,
            (long) ordinal,
            1L);
  }

  private void retain(UsageAttribution owner) throws Exception {
    jdbc.update(
        "INSERT INTO inference_run_ownership(conversation_id,turn_ordinal,attribution) VALUES(?,?,?::jsonb)",
        owner.conversations().id(),
        owner.turnOrdinal(),
        new ObjectMapper().writeValueAsString(owner));
  }
}
