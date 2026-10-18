package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real admission/support rollback, immutability, exact-grant revocation and job CAS semantics. */
@Tag("full-db")
@Testcontainers
class OperationRecordsPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  JdbcTemplate jdbc;
  JdbcOperationRecords records;
  PinnedOperationAuthority policy;

  @BeforeAll
  static void migrate() {
    source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(source);
    AuthorityFixtures.truncate(jdbc, "TRUNCATE projects,admins CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin,must_change_password) VALUES('admin','fixture',true,false),('alice','fixture',false,false)");
    jdbc.update("INSERT INTO projects(name) VALUES('one')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'alice','CONTRIBUTOR' FROM projects WHERE name='one'");
    var identities = new JdbcIdentityRepository(jdbc);
    identities.verifyBuiltIns();
    var grants = new JdbcScopedGrantRepository(jdbc);
    ApplicationPolicy applications =
        project -> new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of());
    var authority =
        new LiveScopedAuthority(
            grants, applications, new ApplicationGrantLineage(grants, applications), identities);
    records = new JdbcOperationRecords(jdbc);
    policy =
        new PinnedOperationAuthority(
            identities,
            grants,
            authority,
            new JdbcProjectMembers(jdbc),
            records,
            new JdbcResourceScopeRepository(jdbc));
  }

  OperationAuthority.Admission admit() {
    return policy.admit(
        "alice",
        Home.of("one"),
        Set.of(AuthorityAction.READ, AuthorityAction.WRITE, AuthorityAction.RUN_MODEL));
  }

  @Test
  void captured_grant_and_scope_survive_restart_without_replacement_authority() {
    var admitted = admit();
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT count(*) FROM operation_admission_support WHERE operation=?",
            Integer.class,
            admitted.id()));
    policy.started(admitted, "job-original");
    assertEquals(
        "job-original",
        jdbc.queryForObject(
            "SELECT job_id FROM operation_admissions WHERE id=?", String.class, admitted.id()));
    var members = new JdbcProjectMembers(jdbc);
    members.remove("one", "alice");
    members.assign("one", "alice", ProjectRole.CONTRIBUTOR, "admin", true);
    assertThrows(CallerFault.class, () -> policy.require(admitted));
    assertEquals(
        admitted.scope().value(),
        jdbc.queryForObject(
            "SELECT scope FROM operation_admissions WHERE id=?", UUID.class, admitted.id()));
    assertDoesNotThrow(() -> admit());
  }

  @Test
  void retained_evidence_does_not_block_project_deletion_or_adopt_a_recreated_name() {
    var admitted = admit();
    jdbc.update("DELETE FROM projects WHERE name='one'");
    assertEquals(
        admitted.scope().value(),
        jdbc.queryForObject(
            "SELECT scope FROM operation_admissions WHERE id=?", UUID.class, admitted.id()));
    jdbc.update("INSERT INTO projects(name) VALUES('one')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'alice','CONTRIBUTOR' FROM projects WHERE name='one'");
    assertThrows(CallerFault.class, () -> policy.require(admitted));
    assertDoesNotThrow(() -> admit());
  }

  @Test
  void support_failure_rolls_back_and_history_cannot_be_widened() {
    var good = admit();
    var bad =
        new OperationAuthority.Admission(
            UUID.randomUUID(),
            good.executor(),
            good.account(),
            good.scope(),
            good.home(),
            good.source(),
            List.of(new OperationAuthority.Support(AuthorityAction.RUN_MODEL, UUID.randomUUID())));
    assertThrows(DataAccessException.class, () -> records.retain(bad));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM operation_admissions", Integer.class));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE operation_admissions SET project_name='two' WHERE id=?", good.id()));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM operation_admission_support WHERE operation=?", good.id()));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO operation_admission_support(operation,action,grant_id) VALUES(?,'CONVERSATION_CREATE',?)",
                good.id(),
                good.support().getFirst().grant()));
  }

  @Test
  void correlation_is_idempotent_for_the_same_job_and_concurrent_rebinding_has_one_winner()
      throws Exception {
    var admission = admit();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (String job : List.of("job-one", "job-two"))
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    records.started(admission.id(), job);
                    return true;
                  } catch (IllegalStateException denied) {
                    return false;
                  }
                }));
      start.countDown();
      int accepted = 0;
      for (var result : futures) if (result.get(10, TimeUnit.SECONDS)) accepted++;
      assertEquals(1, accepted);
    }
    String job =
        jdbc.queryForObject(
            "SELECT job_id FROM operation_admissions WHERE id=?", String.class, admission.id());
    assertDoesNotThrow(() -> new JdbcOperationRecords(jdbc).started(admission.id(), job));
    assertThrows(IllegalStateException.class, () -> records.started(admission.id(), "replacement"));
  }

  @Test
  void child_envelopes_survive_repository_restart_without_rebinding_or_replacing_grants() {
    var parent =
        policy.admit(
            "alice",
            Home.of("one"),
            Set.of(
                AuthorityAction.READ,
                AuthorityAction.WRITE,
                AuthorityAction.RUN_MODEL,
                AuthorityAction.CONVERSATION_CREATE));
    var conversations = new ConversationStore(jdbc);
    var source =
        conversations.log(
            Origin.SUBMISSION,
            Home.of("one"),
            "parent",
            null,
            io.aeyer.plowshare.server.agents.Budget.of(3),
            "alice");
    policy.bind(
        new OperationAuthority.Conversation(
            source.id(), "parent", parent, null, source.budget().snapshot().id(), "0".repeat(64)));
    var child =
        policy.derive(
            parent, Set.of(AuthorityAction.READ, AuthorityAction.WRITE, AuthorityAction.RUN_MODEL));
    var log =
        conversations.log(Origin.DELEGATION, Home.of("one"), "child", source.id(), null, "alice");
    var envelope =
        new OperationAuthority.Conversation(
            log.id(), "child", child, source.id(), source.budget().snapshot().id(), "1".repeat(64));
    policy.bind(envelope);
    var restored = new JdbcOperationRecords(jdbc);
    var read = restored.conversation(log.id()).orElseThrow();
    assertEquals(child.id(), read.admission().id());
    assertEquals(source.id(), read.parent());
    assertEquals(source.budget().snapshot().id(), read.budget());
    assertEquals(Optional.of(parent.id()), restored.parent(child.id()));
    restored.bind(envelope);
    assertThrows(
        RuntimeException.class,
        () ->
            restored.bind(
                new OperationAuthority.Conversation(
                    log.id(),
                    "child",
                    child,
                    source.id(),
                    io.aeyer.plowshare.server.agents.Budget.none().snapshot().id(),
                    "1".repeat(64))));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE operation_conversations SET budget_conversation='new-budget' WHERE conversation=?",
                log.id()));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM operation_admission_parents WHERE child=?", child.id()));
    var members = new JdbcProjectMembers(jdbc);
    members.remove("one", "alice");
    members.assign("one", "alice", ProjectRole.CONTRIBUTOR, "admin", true);
    assertThrows(CallerFault.class, () -> policy.require(read.admission()));
    assertDoesNotThrow(this::admit);
  }

  @Test
  void aRetainedContinuationCanDelegateWithItsOriginalConversationAndAllowance() {
    ConversationStore conversations = new ConversationStore(jdbc);
    ConversationRecord parent =
        conversations.log(
            Origin.SUBMISSION,
            Home.of("one"),
            "parent",
            null,
            io.aeyer.plowshare.server.agents.Budget.of(4),
            "alice");
    OperationAuthority.Admission original = admit();
    policy.bind(
        new OperationAuthority.Conversation(
            parent.id(),
            "parent",
            original,
            null,
            parent.budget().snapshot().id(),
            "0".repeat(64)));
    OperationAuthority.Admission continuation = policy.derive(original, original.actions());
    OperationAuthority.Admission child = policy.derive(continuation, Set.of(AuthorityAction.READ));
    ConversationRecord log =
        conversations.log(Origin.DELEGATION, parent.home(), "child", parent.id(), null, "alice");
    policy.bind(
        new OperationAuthority.Conversation(
            log.id(),
            "child",
            child,
            parent.id(),
            parent.budget().snapshot().id(),
            "1".repeat(64)));
    assertEquals(Optional.of(continuation.id()), records.parent(child.id()));
    assertEquals(
        parent.budget().identity(), conversations.allowanceFor(log.id()).orElseThrow().identity());
  }

  @Test
  void aDelegatedChildCanRetainTheAllowanceOfAnOrdinaryConversationWithoutAnAgentEnvelope() {
    ConversationStore conversations = new ConversationStore(jdbc);
    ConversationRecord parent =
        conversations.open(
            io.aeyer.plowshare.protocol.Home.of("one"),
            io.aeyer.plowshare.server.agents.Budget.of(4));
    assertNull(parent.agent());
    OperationAuthority.Admission child = policy.derive(admit(), Set.of(AuthorityAction.READ));
    ConversationRecord log =
        conversations.log(Origin.DELEGATION, parent.home(), "child", parent.id(), null, "alice");
    policy.bind(
        new OperationAuthority.Conversation(
            log.id(),
            "child",
            child,
            parent.id(),
            parent.budget().snapshot().id(),
            "0".repeat(64)));
    assertEquals(
        parent.budget().identity(), conversations.allowanceFor(log.id()).orElseThrow().identity());
  }

  @Test
  void invalid_parent_support_rolls_back_child_and_cannot_be_attached_after_job_start() {
    var parent = admit();
    var extra =
        new OperationAuthority.Admission(
            UUID.randomUUID(),
            parent.executor(),
            parent.account(),
            parent.scope(),
            parent.home(),
            parent.source(),
            List.of(
                new OperationAuthority.Support(
                    AuthorityAction.ADMINISTER, parent.support().getFirst().grant())));
    assertThrows(DataAccessException.class, () -> records.retainChild(extra, parent.id()));
    assertTrue(records.find(extra.id()).isEmpty());
    var child = policy.derive(parent, Set.of(AuthorityAction.READ));
    policy.started(child, "child-job");
    var another = admit();
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO operation_admission_parents(child,parent) VALUES(?,?)",
                parent.id(),
                child.id()));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO operation_admission_parents(child,parent) VALUES(?,?)",
                child.id(),
                another.id()));
  }

  private io.aeyer.plowshare.server.orchestrations.Orchestrations engine(
      io.aeyer.plowshare.server.orchestrations.ConductorVoice voice,
      io.aeyer.plowshare.server.todos.StageSeeding seeding) {
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    UnitOfWork work =
        new UnitOfWork() {
          public <T> T inTransaction(java.util.function.Supplier<T> body) {
            return transaction.execute(status -> body.get());
          }
        };
    return new io.aeyer.plowshare.server.orchestrations.Orchestrations(
        new io.aeyer.plowshare.server.orchestrations.OrchestrationStore(
            jdbc, java.time.Instant::now, work),
        new ConversationStore(jdbc),
        seeding,
        org.mockito.Mockito.mock(io.aeyer.plowshare.server.todos.TodoLists.class),
        work,
        voice,
        org.mockito.Mockito.mock(io.aeyer.plowshare.server.orchestrations.DeliveryPort.class),
        Set.of(),
        agent -> agent,
        session -> false,
        java.time.Instant::now,
        run -> {},
        id -> {},
        2,
        org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class),
        new io.aeyer.plowshare.server.orchestrations.JdbcOrchestrationRecovery(jdbc, work),
        new io.aeyer.plowshare.server.orchestrations.scripted.JdbcScriptStore(jdbc),
        policy,
        new io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository(jdbc));
  }

  private io.aeyer.plowshare.server.orchestrations.Orchestrations.Start start() {
    var definition =
        io.aeyer.plowshare.server.agents.OrchestrationRegistry.parsePinned(
            "simple",
            "test",
            """
        ---
        name: simple
        description: A bounded task.
        model: fast
        max-turns: 3
        max-model-calls: 4
        stages:
          - {id: goal}
        ---
        Do the task.
        """,
            Set.of(),
            io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier.PROJECT);
    return new io.aeyer.plowshare.server.orchestrations.Orchestrations.Start(
        definition, Home.of("one"), "Help", null, null, "caller", "alice", null, null, 0);
  }

  @Test
  void aBoardSourceDoesNotEnableIndependentOrchestrationWithoutItsWakeLifecycle() {
    ConversationStore conversations = new ConversationStore(jdbc);
    io.aeyer.plowshare.server.board.BoardStore board =
        new io.aeyer.plowshare.server.board.BoardStore(jdbc, java.time.Instant::now);
    io.aeyer.plowshare.server.board.BoardTopic topic =
        board.openRoot(
            new io.aeyer.plowshare.server.board.BoardStore.NewTopic(
                "one", "Review", "REVIEW", "alice", "person", "alice", null, 20, 2));
    ConversationRecord seat =
        conversations.log(Origin.BOARD, Home.of("one"), "worker", null, null, "alice");
    board.seatIfAbsent(topic.id(), "worker", seat.id());
    policy.bind(
        new OperationAuthority.Conversation(
            seat.id(), "worker", admit(), null, board.allowance(topic.id()).id(), "0".repeat(64)));
    io.aeyer.plowshare.server.orchestrations.Orchestrations.Start request =
        new io.aeyer.plowshare.server.orchestrations.Orchestrations.Start(
            start().definition(),
            Home.of("one"),
            "Help",
            null,
            seat.id(),
            "worker",
            "alice",
            null,
            null,
            0);
    io.aeyer.plowshare.server.orchestrations.ConductorVoice voice =
        org.mockito.Mockito.mock(io.aeyer.plowshare.server.orchestrations.ConductorVoice.class);
    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () ->
                engine(
                        voice,
                        org.mockito.Mockito.mock(
                            io.aeyer.plowshare.server.todos.StageSeeding.class))
                    .start(request, UUID.randomUUID(), "{}"));
    assertTrue(refused.getMessage().contains("wake's lease and lifecycle"));
    assertEquals(0, board.allowance(topic.id()).spent());
    org.mockito.Mockito.verifyNoInteractions(voice);
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class));
  }

  @Test
  void orchestration_receipt_and_envelope_commit_together_and_restart_does_not_repeat_dispatch() {
    var voice =
        org.mockito.Mockito.mock(io.aeyer.plowshare.server.orchestrations.ConductorVoice.class);
    var seeding = org.mockito.Mockito.mock(io.aeyer.plowshare.server.todos.StageSeeding.class);
    var key = UUID.randomUUID();
    var original = engine(voice, seeding).start(start(), key, "{\"task\":\"Help\"}");
    var envelope = records.conversation(original.conductorConversation()).orElseThrow();
    assertEquals("alice", envelope.admission().executor());
    assertEquals(
        new io.aeyer.plowshare.server.archive.ConversationStore(jdbc)
            .allowanceFor(original.conductorConversation())
            .orElseThrow()
            .snapshot()
            .id(),
        envelope.budget());
    var restored = engine(voice, seeding).start(start(), key, "{\"task\":\"Help\"}");
    assertEquals(original.id(), restored.id());
    org.mockito.Mockito.verify(voice, org.mockito.Mockito.times(1))
        .speak(
            org.mockito.ArgumentMatchers.eq(original.conductorConversation()),
                org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM operation_conversations", Integer.class));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM operation_admissions", Integer.class));
  }

  @Test
  void late_orchestration_start_failure_rolls_back_conversation_authority_and_receipt() {
    var voice =
        org.mockito.Mockito.mock(io.aeyer.plowshare.server.orchestrations.ConductorVoice.class);
    var seeding = org.mockito.Mockito.mock(io.aeyer.plowshare.server.todos.StageSeeding.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("stage seed failed"))
        .when(seeding)
        .seedStages(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList());
    assertThrows(
        IllegalStateException.class,
        () -> engine(voice, seeding).start(start(), UUID.randomUUID(), "{}"));
    for (var table :
        List.of(
            "conversations",
            "orchestrations",
            "operation_admissions",
            "operation_conversations",
            "orchestration_start_receipts"))
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class), table);
    org.mockito.Mockito.verifyNoInteractions(voice);
  }

  @Test
  void concurrent_original_start_key_retains_one_envelope_and_dispatches_once() throws Exception {
    var voice =
        org.mockito.Mockito.mock(io.aeyer.plowshare.server.orchestrations.ConductorVoice.class);
    var seeding = org.mockito.Mockito.mock(io.aeyer.plowshare.server.todos.StageSeeding.class);
    var engine = engine(voice, seeding);
    var key = UUID.randomUUID();
    var gate = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(2)) {
      var first =
          threads.submit(
              () -> {
                gate.await();
                return engine.start(start(), key, "{}");
              });
      var second =
          threads.submit(
              () -> {
                gate.await();
                return engine.start(start(), key, "{}");
              });
      gate.countDown();
      assertEquals(first.get(10, TimeUnit.SECONDS).id(), second.get(10, TimeUnit.SECONDS).id());
    }
    org.mockito.Mockito.verify(voice, org.mockito.Mockito.times(1))
        .speak(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM operation_conversations", Integer.class));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM operation_admissions", Integer.class));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM orchestration_start_receipts", Integer.class));
  }

  @Test
  void deleted_execution_input_keeps_history_but_blocks_new_effects() {
    var parent = admit();
    var conversations = new ConversationStore(jdbc);
    var log =
        conversations.log(
            Origin.SUBMISSION,
            Home.of("one"),
            "worker",
            null,
            io.aeyer.plowshare.server.agents.Budget.of(3),
            "alice");
    policy.bind(
        new OperationAuthority.Conversation(
            log.id(), "worker", parent, null, log.budget().snapshot().id(), "0".repeat(64)));
    assertDoesNotThrow(() -> policy.require(parent));
    jdbc.update("DELETE FROM conversations WHERE id=?", log.id());
    assertTrue(records.conversation(log.id()).isPresent());
    assertThrows(CallerFault.class, () -> policy.require(parent));
  }

  @Test
  void sharedConductorsRetainCallerAllowanceAcrossRepositoryReopenAndRefuseMissingBinding() {
    var conversations = new ConversationStore(jdbc);
    var parent =
        policy.admit(
            "alice",
            Home.of("one"),
            Set.of(
                AuthorityAction.READ,
                AuthorityAction.WRITE,
                AuthorityAction.RUN_MODEL,
                AuthorityAction.CONVERSATION_CREATE));
    var root =
        conversations.log(
            Origin.SUBMISSION,
            Home.of("one"),
            "parent",
            null,
            io.aeyer.plowshare.server.agents.Budget.of(3),
            "alice");
    policy.bind(
        new OperationAuthority.Conversation(
            root.id(), "parent", parent, null, root.budget().snapshot().id(), "0".repeat(64)));
    var transactions =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    var child =
        transactions.execute(
            status -> {
              var authority = policy.derive(parent, parent.actions());
              var log =
                  conversations.logSharedOrchestration(
                      Home.of("one"), "conductor", "alice", root.budget());
              policy.bind(
                  new OperationAuthority.Conversation(
                      log.id(),
                      "conductor",
                      authority,
                      root.id(),
                      root.budget().snapshot().id(),
                      "1".repeat(64)));
              return log;
            });
    assertNotNull(child);
    assertNull(child.budget());
    assertNull(
        child.parentId()); // Independent log lifecycle, explicit retained authority ancestry.
    var childAdmission = policy.conversation(child.id()).orElseThrow().admission();
    var delegate =
        transactions.execute(
            status -> {
              var authority = policy.derive(childAdmission, childAdmission.actions());
              var log =
                  conversations.log(
                      Origin.DELEGATION, Home.of("one"), "delegate", child.id(), null, "alice");
              policy.bind(
                  new OperationAuthority.Conversation(
                      log.id(),
                      "delegate",
                      authority,
                      child.id(),
                      root.budget().snapshot().id(),
                      "3".repeat(64)));
              return log;
            });
    assertEquals(
        root.budget().identity(),
        conversations.allowanceFor(delegate.id()).orElseThrow().identity());
    var voice =
        org.mockito.Mockito.mock(io.aeyer.plowshare.server.orchestrations.ConductorVoice.class);
    var seeding = org.mockito.Mockito.mock(io.aeyer.plowshare.server.todos.StageSeeding.class);
    var request =
        new io.aeyer.plowshare.server.orchestrations.Orchestrations.Start(
            start().definition(),
            Home.of("one"),
            "Help",
            null,
            delegate.id(),
            "delegate",
            "alice",
            null,
            null,
            0);
    var started = engine(voice, seeding).start(request, UUID.randomUUID(), "{}");
    var retained = records.conversation(started.conductorConversation()).orElseThrow();
    assertEquals(delegate.id(), retained.parent());
    assertEquals(root.budget().snapshot().id(), retained.budget());
    assertNull(conversations.find(started.conductorConversation()).orElseThrow().budget());
    var reopened = new ConversationStore(jdbc);
    var budget = reopened.allowanceFor(child.id()).orElseThrow();
    assertEquals(root.budget().identity(), budget.identity());
    budget.tryClaim().orElseThrow();
    assertEquals(1, reopened.allowanceFor(child.id()).orElseThrow().spent());
    reopened.turnEnded(child.id(), budget);
    assertNull(reopened.find(child.id()).orElseThrow().budget());
    assertThrows(
        org.springframework.transaction.TransactionSystemException.class,
        () ->
            transactions.execute(
                status ->
                    conversations.logSharedOrchestration(
                        Home.of("one"), "unbound", "alice", root.budget())));
    var other =
        conversations.log(
            Origin.SUBMISSION,
            Home.of("one"),
            "other",
            null,
            io.aeyer.plowshare.server.agents.Budget.of(100),
            "alice");
    assertThrows(
        DataAccessException.class,
        () ->
            transactions.execute(
                status -> {
                  var authority = policy.derive(parent, parent.actions());
                  var log =
                      conversations.logSharedOrchestration(
                          Home.of("one"), "wrong", "alice", root.budget());
                  records.bind(
                      new OperationAuthority.Conversation(
                          log.id(),
                          "wrong",
                          authority,
                          root.id(),
                          other.budget().snapshot().id(),
                          "2".repeat(64)));
                  return log;
                }));
    assertEquals(3, reopened.allowanceFor(child.id()).orElseThrow().limit());
    var parentView = reopened.allowanceFor(root.id()).orElseThrow();
    var childView = reopened.allowanceFor(child.id()).orElseThrow();
    assertEquals(parentView.identity(), childView.identity());
    assertEquals(2, parentView.tryClaim().orElseThrow().ordinal());
    assertEquals(3, childView.tryClaim().orElseThrow().ordinal());
    assertTrue(parentView.tryClaim().isEmpty());
    assertTrue(childView.tryClaim().isEmpty());
  }

  @Test
  void globalReadCeilingIsRetainedImmutableAndCannotBeAddedToAnOldParent() {
    var ordinary = admit();
    assertFalse(records.find(ordinary.id()).orElseThrow().globalRead());
    var enabled =
        new OperationAuthority.Admission(
            UUID.randomUUID(),
            ordinary.executor(),
            ordinary.account(),
            ordinary.scope(),
            ordinary.home(),
            ordinary.source(),
            ordinary.support(),
            true);
    records.retain(enabled);
    assertTrue(new JdbcOperationRecords(jdbc).find(enabled.id()).orElseThrow().globalRead());
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE operation_admissions SET global_read=true WHERE id=?", ordinary.id()));
    var widened =
        new OperationAuthority.Admission(
            UUID.randomUUID(),
            ordinary.executor(),
            ordinary.account(),
            ordinary.scope(),
            ordinary.home(),
            ordinary.source(),
            ordinary.support(),
            true);
    assertThrows(DataAccessException.class, () -> records.retainChild(widened, ordinary.id()));
    assertTrue(records.find(widened.id()).isEmpty());
  }

  @Test
  void newRunGlobalTierIsExplicitAndAccountQueryPinsTheGlobalKnowledgeScope() {
    var memories = new MemoryStore(jdbc);
    memories.protectInformation();
    var origin =
        new io.aeyer.plowshare.server.embedding.QueryEmbeddings.Origin("retained-call", () -> {});
    var old =
        new MemoryQueries.Request(
            "alice", Home.of("one"), MemoryQueries.Kind.RECALL, "query", 5, true, origin);
    assertEquals(List.of(Home.of("one")), memories.pin(old).tiers());
    var current =
        new MemoryQueries.Request(
            "alice",
            Home.of("one"),
            MemoryQueries.Kind.RECALL,
            "query",
            5,
            true,
            origin.withGlobalRead(true));
    assertEquals(List.of(Home.of("one"), Home.global()), memories.pin(current).tiers());
    var queries = new io.aeyer.plowshare.server.embedding.JdbcQueryEmbeddingRepository(jdbc);
    var global =
        new io.aeyer.plowshare.server.information.InformationContext(
            "alice",
            io.aeyer.plowshare.server.information.InformationContext.Selection.account(
                BuiltInIdentities.GLOBAL.value()));
    assertEquals(BuiltInIdentities.GLOBAL_KNOWLEDGE.value(), queries.pin(global).scope());
    jdbc.update("UPDATE admins SET enabled=false WHERE handle='alice'");
    assertThrows(CallerFault.class, () -> memories.pin(current));
    assertThrows(CallerFault.class, () -> queries.pin(global));
  }
}
