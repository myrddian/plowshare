package io.aeyer.plowshare.server.proc;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL is required for recursive archive scope, aggregate checkpoints and row mapping.
 */
@Tag("full-db")
@Testcontainers
class JdbcProcRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource data;
  JdbcTemplate jdbc;
  JdbcProcRepository repository;
  ConversationStore conversations;
  String root, child, foreign;

  @BeforeAll
  static void migrate() {
    data = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    Flyway.configure().dataSource(data).load().migrate();
  }

  @BeforeEach
  void prepare() {
    jdbc = new JdbcTemplate(data);
    AuthorityFixtures.truncate(
        jdbc,
        "TRUNCATE conversations,projects,admins,inference_run_ownership,inference_accounting_events,inference_attempts,inference_calls,inference_pricing_versions,inference_accounting_instances,inference_accounting_journals CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES ('alice','fixture',true),('bob','fixture',false)");
    conversations = new ConversationStore(jdbc);
    root = conversations.open(Home.of("research"), Budget.of(20), TurnCap.none(), "alice").id();
    child =
        conversations
            .log(
                Origin.DELEGATION,
                Home.of("research"),
                "information_tagger",
                root,
                null,
                "alice",
                "call-parent")
            .id();
    foreign = conversations.open(Home.of("other"), Budget.of(20), TurnCap.none(), "bob").id();
    jdbc.update(
        "INSERT INTO project_members(project_id,handle) SELECT id,CASE name WHEN 'research' THEN 'alice' ELSE 'bob' END FROM projects");
    repository = new JdbcProcRepository(jdbc, new DataSourceTransactionManager(data));
  }

  private ProcQuery query(String visibility, String conversation, String after, int limit) {
    return new ProcQuery(visibility, null, conversation, null, null, null, after, limit);
  }

  private ProcRepository.Live live(String... ids) {
    return new ProcRepository.Live(Instant.now(), List.of(ids));
  }

  @Test
  void allOriginsRemainVisibleAfterCompletionAndArchiveAppliesToWholeTree() {
    jdbc.update("UPDATE conversations SET last_turn_at=now() WHERE id=?", child);
    var page = repository.conversations("alice", query("mine", null, null, 100), live());
    assertEquals(2, page.total());
    assertTrue(
        page.conversations().stream()
            .anyMatch(c -> c.id().equals(child) && c.state().equals("COMPLETED")));
    assertEquals(
        3, repository.conversations("alice", query("server", null, null, 100), live()).total());
    assertThrows(
        CallerFault.class,
        () -> repository.conversations("bob", query("server", null, null, 100), live()));
    jdbc.update("UPDATE conversations SET lifecycle='archived' WHERE id=?", root);
    assertEquals(
        0, repository.conversations("alice", query("mine", null, null, 100), live()).total());
    assertEquals(
        1, repository.conversations("alice", query("server", null, null, 100), live()).total());
  }

  @Test
  void paginationRechecksAccessAndMissingRuntimeNeverMeansRunning() {
    jdbc.update(
        "INSERT INTO jobs(id,agent,project_id,started_at,conversation_id) SELECT 'job_fixture','worker',project_id,now(),id FROM conversations WHERE id=?",
        child);
    assertEquals(
        "UNKNOWN",
        repository
            .conversations("alice", query("mine", child, null, 100), live())
            .conversations()
            .getFirst()
            .state());
    assertEquals(
        "RUNNING",
        repository
            .conversations("alice", query("mine", child, null, 100), live(child))
            .conversations()
            .getFirst()
            .state());
    var active =
        repository.conversations(
            "alice", new ProcQuery("mine", null, null, null, null, true, null, 1), live());
    assertEquals(1, active.total());
    assertEquals(child, active.conversations().getFirst().id());
    assertNull(active.cursor());
    var first = repository.conversations("alice", query("mine", null, null, 1), live());
    assertNotNull(first.cursor());
    var second = repository.conversations("alice", query("mine", null, first.cursor(), 1), live());
    assertNotEquals(first.conversations().getFirst().id(), second.conversations().getFirst().id());
    jdbc.update("DELETE FROM project_members WHERE handle='alice'");
    assertEquals(
        0,
        repository.conversations("alice", query("mine", null, first.cursor(), 1), live()).total());
  }

  @Test
  void onlyTheAuthorizedPageIsEnrichedEvenWhenThePlannerSorts() throws Exception {
    for (int i = 0; i < 80; i++) {
      conversations.open(Home.of("research"), Budget.of(20), TurnCap.none(), "alice");
    }
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            new DataSourceTransactionManager(data));
    String explained =
        transaction.execute(
            ignored -> {
              // A valid alternative plan must not enrich the entire selection before its sort.
              // Counting actual scan loops avoids a machine-dependent wall-clock assertion.
              jdbc.execute("SET LOCAL enable_indexscan=off");
              jdbc.execute("SET LOCAL enable_bitmapscan=off");
              var args =
                  new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                      .addValue("account", "alice")
                      .addValue("fleet", false)
                      .addValue("project", null)
                      .addValue("conversation", null)
                      .addValue("root", null)
                      .addValue("causation", null)
                      .addValue("after", null)
                      .addValue("limit", 2)
                      .addValue("live", List.of(""));
              return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
                  .queryForObject(
                      "EXPLAIN (ANALYZE, FORMAT JSON) "
                          + JdbcProcRepository.conversationPageSql(false),
                      args,
                      String.class);
            });
    var plan = new com.fasterxml.jackson.databind.ObjectMapper().readTree(explained);
    assertTrue(checkJobScanLoops(plan) > 0, "the plan must exercise job enrichment");
    var first = repository.conversations("alice", query("mine", null, null, 1), live());
    assertEquals(82, first.total());
    assertEquals(1, first.conversations().size());
    assertNotNull(first.cursor());
    assertEquals("alice", first.conversations().getFirst().owner());
  }

  private int checkJobScanLoops(com.fasterxml.jackson.databind.JsonNode plan) {
    int found = 0;
    if (plan.isObject() && plan.path("Relation Name").asText().equals("jobs")) {
      found++;
      assertTrue(
          plan.path("Actual Loops").asLong() <= 2,
          "job enrichment must execute only for the requested page plus its cursor row: " + plan);
    }
    for (var child : plan) found += checkJobScanLoops(child);
    return found;
  }

  @Test
  void queuesReadWholePersistedBatchAndExcludeOldGenerations() {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) SELECT ?,'proc-fixture','notes.md','alice',id,'source' FROM projects WHERE name='research'",
        resource);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,processing_log) VALUES (?,?,1,'Notes','text/plain','fixture',1,?)",
        revision,
        resource,
        root);
    jdbc.update(
        "INSERT INTO information_steps(revision_id,generation,stage,state) VALUES (?,1,'extract','ready'),(?,1,'derive','pending'),(?,1,'embed','blocked'),(?,2,'extract','failed')",
        revision,
        revision,
        revision,
        revision);
    var page = repository.queues("alice", query("mine", root, null, 100));
    var queue = page.queues().getFirst();
    assertEquals(3, queue.total());
    assertEquals(1, queue.completed());
    assertEquals(1, queue.pending());
    assertEquals(1, queue.blocked());
    assertEquals(0, queue.failed());
    assertNull(queue.oldestPendingAt(), "source creation time is not an enqueue timestamp");
    assertEquals(3, page.summaries().getFirst().total());
    assertTrue(repository.queues("bob", query("mine", null, null, 100)).queues().isEmpty());
    assertTrue(repository.tools("alice", query("mine", root, null, 100)).isEmpty());
    assertTrue(repository.calls("alice", query("mine", root, null, 100)).calls().isEmpty());
    jdbc.update("UPDATE conversations SET lifecycle='archived' WHERE id=?", root);
    assertTrue(repository.queues("alice", query("server", null, null, 100)).queues().isEmpty());
  }

  @Test
  void sweepEligibilityCountsBeyondItsNextBatchAndSummaryDoesNotShrinkToPageSize() {
    jdbc.update(
        """
        INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind)
        SELECT gen_random_uuid(),'project:research','batch-'||n||'.md','alice',p.id,'source'
        FROM projects p CROSS JOIN generate_series(1,105) n WHERE p.name='research'
        """);
    jdbc.update(
        """
        INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text)
        SELECT gen_random_uuid(),id,1,'Batch','text/plain','fixture',1,'Retained extraction'
        FROM information_resources
        """);
    jdbc.update(
        """
        INSERT INTO information_steps(revision_id,generation,stage,state)
        SELECT r.id,1,s.stage,CASE s.stage WHEN 'autoTag' THEN 'skipped' ELSE 'ready' END
        FROM information_revisions r CROSS JOIN (VALUES ('extract'),('derive'),('autoTag')) s(stage)
        """);
    var page = repository.queues("alice", query("mine", null, null, 2));
    assertEquals(2, page.queues().size());
    assertNotNull(page.cursor());
    assertEquals(
        105,
        page.summaries().stream()
            .filter(q -> q.stage().equals("autoTag:eligible-not-enqueued"))
            .findFirst()
            .orElseThrow()
            .pending());
    var processing =
        new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
            jdbc,
            java.time.Clock.systemUTC(),
            new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc));
    var work =
        new org.springframework.transaction.support.TransactionTemplate(
            new DataSourceTransactionManager(data));
    assertEquals(100, work.execute(ignored -> processing.sweepUntagged()).size());
    var after = repository.queues("alice", query("mine", null, null, 2));
    assertEquals(
        5,
        after.summaries().stream()
            .filter(q -> q.stage().equals("autoTag:eligible-not-enqueued"))
            .findFirst()
            .orElseThrow()
            .pending());
    assertEquals(
        100,
        after.summaries().stream()
            .filter(q -> q.stage().equals("processing"))
            .findFirst()
            .orElseThrow()
            .pending());
  }

  @Test
  void systemCallsAndToolReceiptsAreAttributableWithoutLeakingContent() throws Exception {
    UUID journal = UUID.randomUUID(), instance = UUID.randomUUID(), call = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO inference_accounting_journals(journal_id,tracking_started_at) VALUES (?,now())",
        journal);
    jdbc.update(
        "INSERT INTO inference_accounting_instances(instance_id,journal_id,started_at) VALUES (?,?,now())",
        instance,
        journal);
    jdbc.update(
        """
        INSERT INTO inference_calls(call_id,instance_id,attribution,project_id,scope,conversation_id,
          root_conversation_id,ancestor_conversation_ids,ancestor_run_ids,ancestor_orchestration_ids,
          agent_name,operation,attribution_status,specifier,pool,wire_model,billing_route,lane,
          created_at,last_event_at,ended_at,lifecycle,attempt_count,last_call_sequence)
        SELECT ?,?,'{}',project_id::text,'PROJECT',id,?,ARRAY[?]::text[],ARRAY[]::text[],ARRAY[]::text[],
          'information_tagger','DOCUMENT_SUMMARY','SYSTEM','fast','fixture-pool','fixture-model',
          'fixture-pool','CHAT',now(),now(),now(),'SUCCEEDED',2,1 FROM conversations WHERE id=?
        """,
        call,
        instance,
        root,
        root,
        child);
    jdbc.update(
        """
        INSERT INTO inference_attempts(attempt_id,call_id,attempt_number,started_at,ended_at,outcome,input_tokens,output_tokens)
        VALUES (?,?,1,now(),now(),'FAILED',NULL,NULL),(?,?,2,now(),now(),'SUCCEEDED',42,7)
        """,
        UUID.randomUUID(),
        call,
        UUID.randomUUID(),
        call);
    var page = repository.calls("alice", query("mine", root, null, 100));
    assertEquals(1, page.calls().size());
    var read = page.calls().getFirst();
    assertEquals("alice", read.owner());
    assertEquals("SYSTEM", read.principal());
    assertEquals("fixture-pool", read.pool());
    assertEquals(2, read.observations().size());
    assertNull(read.observations().getFirst().inputTokens());
    assertEquals("42", read.observations().getLast().inputTokens());
    assertTrue(repository.calls("bob", query("mine", root, null, 100)).calls().isEmpty());
    assertThrows(
        CallerFault.class, () -> repository.calls("bob", query("server", null, null, 100)));
    jdbc.update(
        """
        INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content,tool_calls)
        VALUES (?,1,1,'answer','assistant','private-model-text',
          '[{"id":"tool-1","name":"web_search","arguments":"private-arguments"}]'::jsonb)
        """,
        child);
    jdbc.update(
        "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content,tool_call_id) VALUES (?,2,1,'tool_result','tool','private-tool-output','tool-1')",
        child);
    var tools = repository.tools("alice", query("mine", root, null, 100));
    assertEquals("RESULT_RECORDED", tools.getFirst().state());
    assertEquals("web_search", tools.getFirst().name());
    var json =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules()
            .setSerializationInclusion(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    assertFalse(json.writeValueAsString(tools).contains("private-"));
    assertTrue(json.writeValueAsString(page).contains("\"inputTokens\":null"));
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) SELECT ?,'project:research','proc-call-fixture.md','alice',id,'source' FROM projects WHERE name='research'",
        resource);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,processing_log) VALUES (?,?,1,'Fixture','text/plain','fixture',1,?)",
        revision,
        resource,
        root);
    jdbc.update(
        "INSERT INTO information_steps(revision_id,generation,stage,state) VALUES (?,1,'extract','ready'),(?,1,'derive','ready'),(?,1,'autoTag','ready'),(?,1,'embed','pending')",
        revision,
        revision,
        revision,
        revision);
    jdbc.update("UPDATE conversations SET last_turn_at=now() WHERE id=?", child);
    var audit = new ProcAudit(repository, () -> live());
    var selected = query("mine", root, null, 100);
    var fixture =
        new Acceptance(
            audit.list("alice", selected),
            audit.queues("alice", selected),
            audit.calls("alice", selected),
            audit.tools("alice", selected),
            audit.inspect("alice", selected));
    assertNull(
        fixture.conversations().conversations().stream()
            .filter(c -> c.id().equals(child))
            .findFirst()
            .orElseThrow()
            .causationDepth(),
        "unrecorded Relay depth is unknown, not a negative counter");
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("build/proc-acceptance.json"),
        io.aeyer.plowshare.server.ws.FrameJson.answering().writeValueAsString(fixture));
  }

  @Test
  void attemptBatchKeepsItsIndependentCapAndOrderForEveryAuthorizedCall() {
    UUID journal = UUID.randomUUID(), instance = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO inference_accounting_journals(journal_id,tracking_started_at) VALUES (?,now())",
        journal);
    jdbc.update(
        "INSERT INTO inference_accounting_instances(instance_id,journal_id,started_at) VALUES (?,?,now())",
        instance,
        journal);
    jdbc.update(
        """
        INSERT INTO inference_calls(call_id,instance_id,attribution,project_id,scope,conversation_id,
          root_conversation_id,ancestor_conversation_ids,ancestor_run_ids,ancestor_orchestration_ids,
          agent_name,operation,attribution_status,specifier,pool,wire_model,billing_route,lane,
          created_at,last_event_at,ended_at,lifecycle,attempt_count,last_call_sequence)
        SELECT gen_random_uuid(),?,'{}',project_id::text,'PROJECT',id,?,ARRAY[?]::text[],ARRAY[]::text[],ARRAY[]::text[],
          'information_tagger','DOCUMENT_SUMMARY','SYSTEM','fast','fixture-pool','fixture-model',
          'fixture-pool','CHAT',now(),now(),now(),'FAILED',101,1
        FROM conversations CROSS JOIN generate_series(1,2) cohort WHERE id=?
        """,
        instance,
        root,
        root,
        child);
    jdbc.update(
        """
        INSERT INTO inference_attempts(attempt_id,call_id,attempt_number,started_at,ended_at,outcome)
        SELECT gen_random_uuid(),call_id,attempt,now(),now(),'FAILED'
        FROM inference_calls CROSS JOIN generate_series(1,101) attempt
        """);
    var page = repository.calls("alice", query("mine", root, null, 100));
    assertEquals(2, page.calls().size());
    for (var call : page.calls()) {
      assertEquals(101, call.attempts());
      assertEquals(100, call.observations().size());
      assertEquals(1, call.observations().getFirst().number());
      assertEquals(100, call.observations().getLast().number());
    }
    assertTrue(repository.calls("bob", query("mine", root, null, 100)).calls().isEmpty());
  }

  /** Synthetic PostgreSQL evidence consumed by SDK and browser acceptance, without source text. */
  record Acceptance(
      ProcViews.Conversations conversations,
      ProcViews.Queues queues,
      ProcViews.Calls calls,
      ProcViews.Tools tools,
      ProcViews.Inspection inspection) {}
}
