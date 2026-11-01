package io.aeyer.plowshare.server.proc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
  void countAndEmptyCursorPageShareOneAuthorizedTreeRead() {
    var trace = mock(io.aeyer.plowshare.server.pool.JdbcExecutionTrace.class);
    when(trace.begin(anyString(), anyString()))
        .thenReturn(mock(io.aeyer.plowshare.server.pool.JdbcExecutionTrace.Span.class));
    var traced = new io.aeyer.plowshare.server.pool.TracedDataSource(data, trace);
    var measured =
        new JdbcProcRepository(new JdbcTemplate(traced), new DataSourceTransactionManager(traced));

    var page = measured.conversations("alice", query("mine", null, "zzzz", 1), live());
    assertEquals(2, page.total(), "the total covers the selection before the cursor");
    assertTrue(page.conversations().isEmpty());
    assertNull(page.cursor());
    verify(trace, times(1)).begin(startsWith("WITH RECURSIVE"), eq("executeQuery"));
    verify(trace, times(1)).begin(startsWith("SELECT 1 FROM admins"), eq("executeQuery"));

    // Reuse is confined to a statement: a later request must observe lost membership.
    jdbc.update("DELETE FROM project_members WHERE handle='alice'");
    var revoked = measured.conversations("alice", query("mine", null, null, 1), live());
    assertEquals(0, revoked.total());
    assertTrue(revoked.conversations().isEmpty());
    assertNull(revoked.cursor());
    assertThrows(
        CallerFault.class,
        () -> measured.conversations("bob", query("server", null, null, 1), live()));
  }

  @Test
  void onlyTheAuthorizedPageIsEnrichedEvenWhenThePlannerSorts() throws Exception {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) SELECT ?,'proc-plan','source.md','alice',id,'source' FROM projects WHERE name='research'",
        resource);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,processing_log) VALUES (?,?,1,'Source','text/plain','fixture',1,?)",
        revision,
        resource,
        root);
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
    assertEquals(1, checkSharedQueueWork(plan, "CTE authorized"));
    assertSingleRevisionScan(plan);
    assertEquals(1, checkSharedQueueWork(plan, "CTE edges"));
    assertEquals(1, checkSharedQueueWork(plan, "CTE page_revisions"));
    assertTrue(checkJobScanLoops(plan) > 0, "the plan must exercise job enrichment");
    var first = repository.conversations("alice", query("mine", null, null, 1), live());
    assertEquals(82, first.total());
    assertEquals(1, first.conversations().size());
    assertNotNull(first.cursor());
    assertEquals("alice", first.conversations().getFirst().owner());
  }

  @Test
  void genericInventoryPlanSharesConversationFactsAndKeepsPageEstimatesBounded() throws Exception {
    jdbc.update(
        "INSERT INTO conversations(id,owner_handle,created_at,origin,budget_total,budget_spent) SELECT 'generic-'||n,'alice',now(),'turn',20,0 FROM generate_series(1,1000) n");
    jdbc.execute("ANALYZE conversations; ANALYZE projects; ANALYZE project_members");
    // Explicit PREPARE/EXECUTE exercises a generic server plan independently of the JDBC
    // driver's prepare threshold. Nullable selector parameters must not masquerade as a tiny
    // subtree when the validated request is the full inventory.
    var names = new java.util.LinkedHashMap<String, Integer>();
    var matcher =
        java.util.regex.Pattern.compile("(?<!:):([a-z]+)")
            .matcher(JdbcProcRepository.conversationPageSql(false));
    var sql = new StringBuilder();
    while (matcher.find()) {
      names.computeIfAbsent(matcher.group(1), ignored -> names.size() + 1);
      matcher.appendReplacement(
          sql, java.util.regex.Matcher.quoteReplacement("$" + names.get(matcher.group(1))));
    }
    matcher.appendTail(sql);
    var types =
        names.keySet().stream()
            .map(
                name ->
                    switch (name) {
                      case "fleet" -> "boolean";
                      case "limit" -> "integer";
                      default -> "text";
                    })
            .toList();
    var values =
        names.keySet().stream()
            .map(
                name ->
                    switch (name) {
                      case "account" -> "'alice'";
                      case "fleet" -> "false";
                      case "limit" -> "2";
                      case "live" -> "''";
                      default -> "NULL";
                    })
            .toList();
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new DataSourceTransactionManager(data));
    tx.setReadOnly(true);
    String explained =
        tx.execute(
            ignored -> {
              jdbc.execute("SET LOCAL jit=off; SET LOCAL plan_cache_mode=force_generic_plan");
              jdbc.execute(
                  "PREPARE proc_inventory_generic(" + String.join(",", types) + ") AS " + sql);
              return jdbc.queryForObject(
                  "EXPLAIN (ANALYZE,FORMAT JSON) EXECUTE proc_inventory_generic("
                      + String.join(",", values)
                      + ")",
                  String.class);
            });
    var plan = new com.fasterxml.jackson.databind.ObjectMapper().readTree(explained);
    assertEquals(1, checkSharedQueueWork(plan, "CTE authorized"));
    assertEquals(1, checkSharedQueueWork(plan, "CTE page_selection"));
    assertGenericInventoryWork(plan);
    var page = repository.conversations("alice", query("mine", null, null, 1), live());
    assertEquals(1002, page.total());
    assertEquals(1, page.conversations().size());
    assertNotNull(page.cursor());
    jdbc.update("DELETE FROM project_members WHERE handle='alice'");
    assertEquals(
        1000,
        repository.conversations("alice", query("mine", null, null, 1), live()).total(),
        "global custody remains; project conversations are revoked");
  }

  private void assertGenericInventoryWork(com.fasterxml.jackson.databind.JsonNode plan) {
    if ("conversations_pkey".equals(plan.path("Index Name").asText()))
      assertTrue(
          plan.path("Actual Loops").asLong() <= 2,
          "only the page may hydrate conversations by ID: " + plan);
    if ("projects".equals(plan.path("Relation Name").asText())
        && "Seq Scan".equals(plan.path("Node Type").asText()))
      assertTrue(
          plan.path("Actual Loops").asLong() <= 1,
          "inventory project facts must not rescan per conversation: " + plan);
    if ("CTE page_selection".equals(plan.path("Subplan Name").asText()))
      assertTrue(
          plan.path("Plan Rows").asLong() <= ProcQuery.MAX_LIMIT + 1,
          "the generic plan must know the validated page ceiling: " + plan);
    for (var child : plan) assertGenericInventoryWork(child);
  }

  private void assertSingleRevisionScan(com.fasterxml.jackson.databind.JsonNode plan) {
    if ("information_revisions".equals(plan.path("Relation Name").asText())
        && "Seq Scan".equals(plan.path("Node Type").asText()))
      assertTrue(
          plan.path("Actual Loops").asLong() <= 1,
          "page sources and processing must not scan the entire revision corpus per row: " + plan);
    for (var child : plan) assertSingleRevisionScan(child);
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
    var exhausted = repository.queues("alice", query("mine", null, "zzzz", 2));
    assertTrue(exhausted.queues().isEmpty());
    assertEquals(
        page.summaries(), exhausted.summaries(), "Totals must survive an empty cursor page");
    assertQueueWorkIsShared();
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
  void targetedInspectionDoesNotBuildUnrelatedConversationTrees() throws Exception {
    jdbc.update(
        "INSERT INTO conversations(id,owner_handle,created_at,origin,budget_total,budget_spent) SELECT 'unrelated-'||n,'alice',now(),'turn',20,0 FROM generate_series(1,1000) n");
    var args =
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("account", "alice")
            .addValue("fleet", false)
            .addValue("project", null)
            .addValue("conversation", child)
            .addValue("root", null)
            .addValue("causation", null)
            .addValue("after", null)
            .addValue("limit", 3)
            .addValue("live", List.of("fixture-no-live"));
    String explained =
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
            .queryForObject(
                "EXPLAIN (ANALYZE, FORMAT JSON) "
                    + JdbcProcRepository.conversationPageSql(false, true),
                args,
                String.class);
    var plan = new com.fasterxml.jackson.databind.ObjectMapper().readTree(explained);
    assertEquals(1, checkTargetedTree(plan));
    UUID instance = callInstance();
    addCall(instance, UUID.randomUUID(), child, null, "research", true);
    var callPlan =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
                    .queryForObject(
                        "EXPLAIN (ANALYZE,FORMAT JSON) "
                            + JdbcProcRepository.callsPageSql(false, true),
                        args,
                        String.class));
    assertEquals(1, checkTargetedTree(callPlan));
    assertEquals(1, repository.calls("alice", query("mine", child, null, 2)).calls().size());
    assertEquals(
        List.of(child),
        repository
            .conversations("alice", query("mine", child, null, 2), live())
            .conversations()
            .stream()
            .map(row -> row.id())
            .toList());
  }

  private int checkTargetedTree(com.fasterxml.jackson.databind.JsonNode plan) {
    int found = 0;
    if ("CTE tree".equals(plan.path("Subplan Name").asText())) {
      found++;
      assertEquals(
          2,
          plan.path("Actual Rows").asLong(),
          "Only the selected root and child belong in the walk");
    }
    for (var childPlan : plan) found += checkTargetedTree(childPlan);
    return found;
  }

  private void assertConversationCallWorkIsShared() throws Exception {
    var args =
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("account", "alice")
            .addValue("fleet", false)
            .addValue("project", null)
            .addValue("conversation", null)
            .addValue("root", null)
            .addValue("causation", null)
            .addValue("after", null)
            .addValue("limit", 51)
            .addValue("live", List.of(""));
    var explained =
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
            .queryForObject(
                "EXPLAIN (ANALYZE, FORMAT JSON) " + JdbcProcRepository.conversationPageSql(false),
                args,
                String.class);
    var plan = new com.fasterxml.jackson.databind.ObjectMapper().readTree(explained);
    assertEquals(1, checkSharedQueueWork(plan, "CTE page_calls"));
    checkCallScans(plan);
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname='inference_calls_direct_conversation' AND indexdef LIKE '%(conversation_id)%'",
            Integer.class));
  }

  private void checkCallScans(com.fasterxml.jackson.databind.JsonNode plan) {
    if ("inference_calls".equals(plan.path("Relation Name").asText())
        && "Seq Scan".equals(plan.path("Node Type").asText()))
      assertTrue(
          plan.path("Actual Loops").asLong() <= 1,
          "A page must not rescan all retained calls per conversation: " + plan);
    for (var childPlan : plan) checkCallScans(childPlan);
  }

  private void assertQueueWorkIsShared() {
    var args =
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("account", "alice")
            .addValue("fleet", false)
            .addValue("project", null)
            .addValue("conversation", null)
            .addValue("root", null)
            .addValue("causation", null)
            .addValue("after", null)
            .addValue("limit", 3);
    String explained =
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
            .queryForObject(
                "EXPLAIN (ANALYZE, FORMAT JSON) " + JdbcProcRepository.queueSql(false),
                args,
                String.class);
    try {
      var plan = new com.fasterxml.jackson.databind.ObjectMapper().readTree(explained);
      assertEquals(1, checkSharedQueueWork(plan, "CTE processing_authority"));
      assertEquals(1, checkSharedQueueWork(plan, "CTE counters"));
    } catch (java.io.IOException invalid) {
      throw new AssertionError("PostgreSQL must return a JSON plan", invalid);
    }
  }

  private int checkSharedQueueWork(com.fasterxml.jackson.databind.JsonNode plan, String subplan) {
    int found = 0;
    if (subplan.equals(plan.path("Subplan Name").asText())) {
      found++;
      assertEquals(1, plan.path("Actual Loops").asLong(), subplan + " must be built only once");
      if (subplan.equals("CTE processing_authority")) checkAuthorityScans(plan);
    }
    for (var child : plan) found += checkSharedQueueWork(child, subplan);
    return found;
  }

  private void checkAuthorityScans(com.fasterxml.jackson.databind.JsonNode plan) {
    if (plan.path("Relation Name").asText().equals("information_revisions")
        && plan.path("Node Type").asText().equals("Seq Scan"))
      assertTrue(
          plan.path("Actual Loops").asLong() <= 1,
          "Worker eligibility must not rescan every revision per candidate: " + plan);
    for (var child : plan) checkAuthorityScans(child);
  }

  @Test
  void modelCallPageBoundsRootChecksUnderLowWorkMemoryAndPagesDoNotOverlap() throws Exception {
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
        SELECT gen_random_uuid(),?,'{}',c.project_id::text,'PROJECT',c.id,c.id,ARRAY[]::text[],ARRAY[]::text[],ARRAY[]::text[],
          'fixture','DOCUMENT_SUMMARY','SYSTEM','fast','fixture-pool','fixture-model',
          'fixture-pool','CHAT',now(),now(),now(),'SUCCEEDED',0,1
        FROM conversations c CROSS JOIN generate_series(1,1000) n WHERE c.id IN (?,?)
        """,
        instance,
        child,
        foreign);
    var args =
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("account", "alice")
            .addValue("fleet", false)
            .addValue("project", null)
            .addValue("conversation", null)
            .addValue("root", null)
            .addValue("causation", null)
            .addValue("after", null)
            .addValue("limit", 51);
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new DataSourceTransactionManager(data));
    String explained =
        tx.execute(
            status -> {
              jdbc.execute("SET LOCAL work_mem='64kB'; SET LOCAL jit=off");
              return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
                  .queryForObject(
                      "EXPLAIN (ANALYZE,FORMAT JSON) " + JdbcProcRepository.callsPageSql(false),
                      args,
                      String.class);
            });
    var plan = new com.fasterxml.jackson.databind.ObjectMapper().readTree(explained);
    assertTrue(checkBoundedCallParents(plan) > 0, "calls must check their actual current roots");
    checkCallScans(plan);
    var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
    args.addValue("after", "zzzz");
    var exhaustedPlan =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(
                named.queryForObject(
                    "EXPLAIN (ANALYZE,FORMAT JSON) " + JdbcProcRepository.callsPageSql(false),
                    args,
                    String.class));
    assertEquals(
        0,
        parentExecutions(exhaustedPlan),
        "an exhausted cursor must not check off-cursor ancestry");
    args.addValue("after", null);
    var terminalPlan =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(
                named.queryForObject(
                    "EXPLAIN (ANALYZE,FORMAT JSON) " + JdbcProcRepository.callsPageSql(true),
                    args,
                    String.class));
    assertEquals(
        0,
        parentExecutions(terminalPlan),
        "an active-only page must not check terminal calls' ancestry");

    var first = repository.calls("alice", query("mine", null, null, 50));
    var next = repository.calls("alice", query("mine", null, first.cursor(), 50));
    assertEquals(50, first.calls().size());
    assertEquals(50, next.calls().size());
    var ids = new java.util.HashSet<String>();
    first
        .calls()
        .forEach(
            call -> {
              assertEquals(child, call.conversation());
              assertTrue(ids.add(call.id()));
            });
    next.calls()
        .forEach(
            call -> {
              assertEquals(child, call.conversation());
              assertTrue(ids.add(call.id()));
            });
    jdbc.update("DELETE FROM project_members WHERE handle='alice'");
    assertTrue(
        repository.calls("alice", query("mine", null, null, 50)).calls().isEmpty(),
        "warm selection never leases project authority");
    assertFalse(
        repository.calls("alice", query("server", null, null, 50)).calls().isEmpty(),
        "explicit administrator metadata scope remains available");
  }

  private long parentExecutions(com.fasterxml.jackson.databind.JsonNode plan) {
    long total =
        "CTE parents".equals(plan.path("Subplan Name").asText())
            ? plan.path("Actual Loops").asLong()
            : 0;
    for (var child : plan) total += parentExecutions(child);
    return total;
  }

  private int checkBoundedCallParents(com.fasterxml.jackson.databind.JsonNode plan) {
    assertNotEquals(
        "CTE tree", plan.path("Subplan Name").asText(), "calls must not build the full inventory");
    int found = 0;
    if ("CTE parents".equals(plan.path("Subplan Name").asText())) {
      found++;
      assertTrue(
          plan.path("Actual Loops").asLong() <= 51,
          "root checks stop at the requested page plus its cursor row: " + plan);
    }
    for (var child : plan) found += checkBoundedCallParents(child);
    return found;
  }

  @Test
  void pageRevisionSnapshotKeepsLatestRootSourceAndCurrentProcessingState() {
    UUID resource = UUID.randomUUID(), older = UUID.randomUUID(), latest = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) SELECT ?,'proc-page','source.md','alice',id,'source' FROM projects WHERE name='research'",
        resource);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,processing_log,created_at) VALUES (?,?,1,'Older','text/plain','old',1,?,now()-interval '1 day'),(?,?,2,'Latest','text/plain','new',1,?,now())",
        older,
        resource,
        root,
        latest,
        resource,
        root);
    jdbc.update(
        "INSERT INTO information_steps(revision_id,generation,stage,state) VALUES (?,1,'extract','pending'),(?,2,'extract','failed')",
        latest,
        latest);
    var page = repository.conversations("alice", query("mine", null, null, 100), live());
    assertEquals(2, page.total());
    for (var row : page.conversations()) {
      assertEquals("source.md", row.resource());
      assertEquals(latest.toString(), row.revision());
      assertEquals(row.id().equals(root) ? "QUEUED" : "IDLE", row.state());
    }
    // Exclusion/lifecycle changes are observed on the next read, without a shared allow cache.
    jdbc.update("UPDATE information_revisions SET excluded=true WHERE id=?", latest);
    page = repository.conversations("alice", query("mine", null, null, 100), live());
    for (var row : page.conversations()) {
      assertEquals(older.toString(), row.revision());
      assertEquals("IDLE", row.state());
    }
    jdbc.update("UPDATE conversations SET lifecycle='archived' WHERE id=?", root);
    assertEquals(
        0, repository.conversations("alice", query("mine", null, null, 100), live()).total());
  }

  @Test
  void boundedCallBranchesPreserveOwnerRootSelectorsOrphansAndRevocation() {
    UUID instance = callInstance();
    UUID owned = UUID.fromString("00000000-0000-0000-0000-000000000010");
    UUID orphan = UUID.fromString("00000000-0000-0000-0000-000000000020");
    UUID hidden = UUID.fromString("00000000-0000-0000-0000-000000000030");
    addCall(instance, owned, child, null, "research", true);
    addCall(instance, orphan, null, "alice", "research", false);
    // Accounting principal/root provenance cannot substitute for conversation custody.
    addCall(instance, hidden, foreign, "alice", "research", true);
    var first = repository.calls("alice", query("mine", null, null, 1));
    assertEquals(owned.toString(), first.calls().getFirst().id());
    assertNotNull(first.cursor());
    var next = repository.calls("alice", query("mine", null, first.cursor(), 1));
    assertEquals(orphan.toString(), next.calls().getFirst().id());
    assertNull(next.cursor());
    assertTrue(repository.calls("alice", query("mine", null, "zzzz", 100)).calls().isEmpty());
    assertEquals(1, repository.calls("alice", query("mine", root, null, 100)).calls().size());
    assertEquals(1, repository.calls("alice", query("mine", child, null, 100)).calls().size());
    assertTrue(repository.calls("alice", query("mine", foreign, null, 100)).calls().isEmpty());
    assertEquals(
        1,
        repository
            .calls("alice", new ProcQuery("mine", "research", null, root, null, true, null, 100))
            .calls()
            .size());
    assertTrue(
        repository
            .calls("alice", new ProcQuery("mine", "other", null, null, null, null, null, 100))
            .calls()
            .isEmpty());
    jdbc.update(
        "UPDATE conversations SET relay_causation=jsonb_build_object('rootId','fixture-cause','parentId',NULL,'depth',0) WHERE id=?",
        child);
    assertEquals(
        1,
        repository
            .calls(
                "alice", new ProcQuery("mine", null, null, null, "fixture-cause", null, null, 100))
            .calls()
            .size());
    jdbc.update("UPDATE conversations SET lifecycle='archived' WHERE id=?", root);
    assertEquals(
        List.of(orphan.toString()),
        repository.calls("alice", query("mine", null, null, 100)).calls().stream()
            .map(ProcViews.Call::id)
            .toList());
    jdbc.update("DELETE FROM project_members WHERE handle='alice'");
    assertTrue(repository.calls("alice", query("mine", null, null, 100)).calls().isEmpty());
    assertEquals(2, repository.calls("alice", query("server", null, null, 100)).calls().size());
    jdbc.update("UPDATE admins SET server_admin=false WHERE handle='alice'");
    assertThrows(
        CallerFault.class, () -> repository.calls("alice", query("server", null, null, 100)));
    jdbc.update("UPDATE admins SET enabled=false WHERE handle='alice'");
    assertThrows(
        CallerFault.class, () -> repository.calls("alice", query("mine", null, null, 100)));
  }

  @Test
  void callsNeverTrustRecordedRootsOrAdmitCycles() {
    UUID instance = callInstance();
    addCall(instance, UUID.randomUUID(), child, null, "research", true);
    String descendant =
        conversations
            .log(Origin.DELEGATION, Home.of("research"), "fixture", child, null, "alice", null)
            .id();
    jdbc.update("UPDATE conversations SET parent_id=? WHERE id=?", descendant, child);
    assertTrue(repository.calls("alice", query("server", null, null, 100)).calls().isEmpty());
    // Restore a rooted chain, then archive its actual root. The call's recorded root is foreign.
    jdbc.update("UPDATE conversations SET parent_id=? WHERE id=?", root, child);
    jdbc.update("UPDATE conversations SET lifecycle='archived' WHERE id=?", root);
    assertTrue(repository.calls("alice", query("server", null, null, 100)).calls().isEmpty());
  }

  private UUID callInstance() {
    UUID journal = UUID.randomUUID(), instance = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO inference_accounting_journals(journal_id,tracking_started_at) VALUES (?,now())",
        journal);
    jdbc.update(
        "INSERT INTO inference_accounting_instances(instance_id,journal_id,started_at) VALUES (?,?,now())",
        instance,
        journal);
    return instance;
  }

  private void addCall(
      UUID instance,
      UUID call,
      String conversation,
      String account,
      String project,
      boolean active) {
    jdbc.update(
        """
        INSERT INTO inference_calls(call_id,instance_id,attribution,project_id,scope,conversation_id,
          root_conversation_id,ancestor_conversation_ids,ancestor_run_ids,ancestor_orchestration_ids,
          account_handle,agent_name,operation,attribution_status,specifier,pool,wire_model,billing_route,lane,
          created_at,last_event_at,ended_at,lifecycle,attempt_count,last_call_sequence)
        SELECT ?,?,'{}',p.id::text,'PROJECT',?,?,ARRAY[]::text[],ARRAY[]::text[],ARRAY[]::text[],
          ?,'fixture','DOCUMENT_SUMMARY',?,'fast','fixture-pool','fixture-model','fixture-pool','CHAT',now(),now(),
          CASE WHEN ? THEN NULL ELSE now() END,CASE WHEN ? THEN 'RUNNING' ELSE 'SUCCEEDED' END,0,1
        FROM projects p WHERE name=?
        """,
        call,
        instance,
        conversation,
        foreign,
        account,
        account == null ? "SYSTEM" : "ATTRIBUTED",
        active,
        active,
        project);
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
  void attemptBatchKeepsItsIndependentCapAndOrderForEveryAuthorizedCall() throws Exception {
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
    var conversations = repository.conversations("alice", query("mine", root, null, 100), live());
    var childSummary =
        conversations.conversations().stream()
            .filter(row -> row.id().equals(child))
            .findFirst()
            .orElseThrow();
    assertEquals(2, childSummary.calls());
    assertEquals(202, childSummary.attempts());
    assertEquals(2, childSummary.repeatedCalls());
    assertEquals("SYSTEM", childSummary.principal());
    assertConversationCallWorkIsShared();
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
