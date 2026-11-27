package io.aeyer.plowshare.server.goals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.access.ApplicationGrantLineage;
import io.aeyer.plowshare.server.access.AuthorityAction;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.GrantRecipient;
import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.access.IdentityRepository.Account;
import io.aeyer.plowshare.server.access.IdentityRepository.AccountKind;
import io.aeyer.plowshare.server.access.JdbcIdentityRepository;
import io.aeyer.plowshare.server.access.JdbcOperationRecords;
import io.aeyer.plowshare.server.access.JdbcResourceScopeRepository;
import io.aeyer.plowshare.server.access.JdbcScopedGrantRepository;
import io.aeyer.plowshare.server.access.LiveScopedAuthority;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.access.OperationAuthority.Support;
import io.aeyer.plowshare.server.access.PinnedOperationAuthority;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.ConversationDefinitions;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.DefinitionSource;
import io.aeyer.plowshare.server.agents.GoalTestActivation;
import io.aeyer.plowshare.server.agents.GoalTurn.Decision;
import io.aeyer.plowshare.server.agents.GoalTurn.DecisionType;
import io.aeyer.plowshare.server.agents.JdbcSkillExecutionsRepository;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.OperationExecution;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Pace;
import io.aeyer.plowshare.server.agents.RunAdmission;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.SendMessageTool;
import io.aeyer.plowshare.server.agents.SkillDefinition;
import io.aeyer.plowshare.server.agents.SkillExecutions;
import io.aeyer.plowshare.server.agents.Transcript;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.Turn.Refused;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.approvals.ApprovalPolicy;
import io.aeyer.plowshare.server.approvals.FileApproval;
import io.aeyer.plowshare.server.approvals.JdbcSuspendedRunCalls;
import io.aeyer.plowshare.server.approvals.JdbcToolCallTraces;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.RunApprovalStore;
import io.aeyer.plowshare.server.approvals.SuspendedRunCalls;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.events.CronSchedule;
import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.FiringRecord;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.Intake;
import io.aeyer.plowshare.server.events.JdbcFiringStore;
import io.aeyer.plowshare.server.events.JdbcInboxStore;
import io.aeyer.plowshare.server.events.JdbcScheduleDefinitionStore;
import io.aeyer.plowshare.server.events.JdbcScheduleStore;
import io.aeyer.plowshare.server.events.JdbcTriggerStore;
import io.aeyer.plowshare.server.events.RelayScheduledPublication;
import io.aeyer.plowshare.server.events.ScheduleDefinitionStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.goals.ConversationGoals.Action;
import io.aeyer.plowshare.server.goals.ConversationGoals.Control;
import io.aeyer.plowshare.server.goals.ConversationGoals.Reason;
import io.aeyer.plowshare.server.goals.ConversationGoals.State;
import io.aeyer.plowshare.server.goals.GoalAgents.Answer;
import io.aeyer.plowshare.server.goals.GoalAgents.Batch;
import io.aeyer.plowshare.server.goals.GoalAgents.Listen;
import io.aeyer.plowshare.server.goals.GoalAgents.Start;
import io.aeyer.plowshare.server.goals.GoalAgents.Unlisten;
import io.aeyer.plowshare.server.goals.GoalConversationAccess.Disclosure;
import io.aeyer.plowshare.server.goals.GoalConversationAccess.Visibility;
import io.aeyer.plowshare.server.goals.GoalExecution.Prepared;
import io.aeyer.plowshare.server.goals.GoalRepository.Binding;
import io.aeyer.plowshare.server.goals.GoalWaitStore.Origin;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.information.InformationLogVisibility;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.messaging.JdbcMessagingRepository;
import io.aeyer.plowshare.server.messaging.MessageInboxes;
import io.aeyer.plowshare.server.messaging.MessageIngress;
import io.aeyer.plowshare.server.messaging.MessageService.GoalAnswerReady;
import io.aeyer.plowshare.server.messaging.MessageService.InstanceView;
import io.aeyer.plowshare.server.messaging.MessageService.Voice;
import io.aeyer.plowshare.server.messaging.RelayMessaging;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrations;
import io.aeyer.plowshare.server.pool.ScopedSqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import io.aeyer.plowshare.server.relay.DurableRelay;
import io.aeyer.plowshare.server.relay.GoalRelaySources;
import io.aeyer.plowshare.server.relay.GoalRelaySources.Search;
import io.aeyer.plowshare.server.relay.GoalRelaySources.Selection;
import io.aeyer.plowshare.server.relay.JdbcRelayPayloadStore;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayRetirementRepository;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayConfiguration;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayTopicOwnership;
import io.aeyer.plowshare.server.relay.RelayTopicOwnership.Owner;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("full-db")
@Testcontainers
class DedicatedGoalAgentsDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork work;
  private static final SqlWorkBudgetProperties LIMITS =
      new SqlWorkBudgetProperties(Duration.ofSeconds(2), Duration.ofMillis(500), 16, 4, 16, 2);
  private static final ScopedSqlWorkBudget BUDGET = new ScopedSqlWorkBudget(LIMITS, null);
  private static final GoalProperties GOALS =
      new GoalProperties(true, 3, 10, 1, 5, 2, 8, Duration.ofMillis(1));

  @BeforeAll
  static void migrate() {
    var raw =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(raw).load().migrate();
    var source = BUDGET.decorate(raw);
    jdbc = new JdbcTemplate(source);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    work =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> action) {
            return transaction.execute(status -> action.get());
          }
        };
  }

  @AfterAll
  static void closeBudget() {
    BUDGET.close();
  }

  @Test
  void dedicated_listener_restarts_from_the_durable_cursor_and_wakes_its_exact_inbox()
      throws Exception {
    String owner = "goal-owner";
    String project = "goal-project";
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES(?,'fixture')", owner);
    long projectId =
        jdbc.queryForObject(
            "INSERT INTO projects(name) VALUES(?) RETURNING id", Long.class, project);
    var account =
        new AccountId(
            jdbc.queryForObject(
                "SELECT id FROM authority_accounts WHERE legacy_handle=?", UUID.class, owner));
    var scope =
        new ScopeId(
            jdbc.queryForObject(
                "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, projectId));
    UUID admissionId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO operation_admissions(id,executor,executor_handle,scope,project_name,source_account)
        VALUES(?,?,?,?,?,?)
        """,
        admissionId,
        account.value(),
        owner,
        scope.value(),
        project,
        account.value());
    var authority =
        new Admission(
            admissionId,
            owner,
            account,
            scope,
            Home.of(project),
            GrantRecipient.account(account),
            List.of(new Support(AuthorityAction.READ, UUID.randomUUID())));
    var identities = mock(IdentityRepository.class);
    when(identities.account(owner))
        .thenReturn(new Account(account, owner, AccountKind.USER, false));
    when(identities.scope(scope)).thenReturn(new AuthorityScope.Project(scope, projectId));
    var members = new JdbcProjectMembers(jdbc);
    members.add(project, owner);
    assertTrue(members.mayUse(project, owner));
    var scopes = mock(ProjectWorkspaces.class);
    when(scopes.personalOwner(project)).thenReturn(Optional.empty());
    var broker =
        new DurableRelay(
            new JdbcRelayRepository(
                jdbc,
                work,
                new JdbcRelayPayloadStore(
                    jdbc, work, Duration.ofHours(1), Duration.ofHours(24), Duration.ofDays(30))),
            Instant::now);
    var config = new RelayConfiguration();
    var selection =
        config.goalRelaySources(
            mock(OperationAuthority.class),
            identities,
            members,
            scopes,
            config.relayTopicRegistry(jdbc, BUDGET, LIMITS));

    var repo = new JdbcGoalRepository(jdbc, work, BUDGET, LIMITS);
    var inbox = new JdbcGoalAgentStore(jdbc, work, BUDGET, LIMITS);
    var logs = new ConversationStore(jdbc);
    var allowances = new JdbcAllowanceRepository(jdbc);
    var definition =
        new AgentDefinition(
            "worker",
            "Worker",
            "model",
            List.of(),
            List.of(),
            List.of(),
            4,
            8,
            "Audit the project.");
    var messages =
        new RelayMessaging(
            new JdbcMessagingRepository(jdbc, allowances),
            allowances,
            logs,
            mock(FiringStore.class),
            mock(MessageInboxes.class),
            work,
            mock(Voice.class),
            (name, target) -> definition,
            ignored -> {},
            Instant::now);

    var runtime = mock(GoalExecution.class);
    var retained = new AtomicReference<Prepared>();
    when(runtime.prepareDedicated(eq(owner), any()))
        .thenAnswer(
            call -> {
              InstanceView instance = call.getArgument(1);
              var prepared =
                  new Prepared(
                      logs.find(instance.conversation()).orElseThrow(),
                      definition,
                      authority,
                      allowances.bind(Budget.of(8)));
              retained.set(prepared);
              return prepared;
            });
    when(runtime.restore(any(Binding.class))).thenAnswer(call -> retained.get());
    when(runtime.idle(anyString())).thenReturn(true);
    var opening = new AtomicReference<String>();
    var invocation = new AtomicReference<RunAdmission>();
    var currentTurn = new AtomicReference<Prepared>();
    var beforeClaim = new AtomicReference<Runnable>(() -> {});
    when(runtime.start(any(), any(), anyString(), any()))
        .thenAnswer(
            call -> {
              beforeClaim.getAndSet(() -> {}).run();
              RunAdmission run = call.getArgument(3);
              String job = "job_" + UUID.randomUUID().toString().replace("-", "");
              run.started().accept(job);
              opening.set(call.getArgument(2));
              invocation.set(run);
              currentTurn.set(call.getArgument(1));
              return Optional.of(job);
            });

    var agent =
        new DedicatedGoalAgents(
            inbox,
            repo,
            runtime,
            messages,
            identities,
            selection,
            broker,
            GOALS,
            new JdbcRelayRetirementRepository(jdbc, work),
            Instant::now);
    messages.useGoalInbox(agent);
    messages.useGoalScheduleTransactions(BUDGET.transactions(work, LIMITS.queryCeiling()));
    var start = new Start(project, "worker", "Watch the authorized feed", null, UUID.randomUUID());
    var launched = agent.start(owner, start);
    assertEquals(launched, agent.start(owner, start));
    assertThrows(
        CallerFault.class,
        () ->
            agent.start(
                owner, new Start(project, "worker", "Changed objective", null, start.requestId())));
    assertEquals("persistent", messages.owned(launched.instance(), owner).lifetime());
    assertThrows(
        NotFoundFault.class,
        () ->
            agent.sources(
                "another",
                launched.goal(),
                launched.conversation(),
                new Search(null, null, null, 10)));

    String keeper =
        scheduledWakes(
            owner,
            projectId,
            project,
            repo,
            runtime,
            messages,
            inbox,
            identities,
            selection,
            broker,
            agent,
            launched,
            retained.get(),
            opening,
            invocation,
            beforeClaim);

    var key = new Relay.TopicKey(projectId, "data.release");
    var hiddenKey = new Relay.TopicKey(projectId, "internal.mailbox");
    var topic = broker.registerTopic(key, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    var hidden =
        broker.registerOwnedTopic(
            hiddenKey,
            RelayPayload.Kind.TEXT,
            Relay.Policy.systemDefault(),
            RelayTopicOwnership.internal(Owner.MESSAGING));
    var discovered =
        agent.sources(
            owner, launched.goal(), launched.conversation(), new Search(null, null, null, 10));
    assertEquals(
        List.of(new GoalAgents.Source(key.name(), topic.generation(), RelayPayload.Kind.TEXT)),
        discovered.sources());
    var rejected =
        new Listen(
            launched.goal(),
            launched.conversation(),
            new Selection(hiddenKey.name(), hidden.generation()));
    assertThrows(CallerFault.class, () -> agent.listen(owner, rejected));

    var request =
        new Listen(
            launched.goal(),
            launched.conversation(),
            new Selection(key.name(), topic.generation()));
    var baseListener =
        GoalListenerTools.bind(
                agent, repo.find(launched.goal()).orElseThrow().binding(), retained.get())
            .stream()
            .filter(tool -> tool.schema().name().equals("message_listen"))
            .findFirst()
            .orElseThrow();
    var unchanged =
        baseListener.run(
            """
        {"topic":"%s","generation":"%s"}
        """
                .formatted(key.name(), topic.generation()),
            Home.of(project));
    var listener = agent.listen(owner, request);
    assertTrue(unchanged.contains(listener.binding().toString()));
    assertEquals(listener, agent.listen(owner, request));
    var event =
        broker.publish(
            key,
            new Relay.Draft(
                "release",
                owner,
                Instant.now(),
                null,
                null,
                new RelayPayload.Text("Release notes are ready.")));
    agent.intake();
    var pending = inbox.next(launched.goal()).orElseThrow();
    assertEquals(event.position(), pending.batch().events().getFirst().position());
    assertEquals(listener.binding(), pending.listener().id());

    var sender = messages.openGoalInbox(owner, project, "worker");
    messages.send(
        new RunExtras.Context(
            definition, sender.conversation(), null, Transcript.NONE, Home.of(project), owner),
        new SendMessageTool.Request(
            launched.instance(),
            "Check the amended criteria.",
            null,
            false,
            false,
            "persistent",
            null,
            null),
        Home.of(project));
    String addressed = inbox.next(launched.goal()).orElseThrow().message();
    assertTrue(messages.route(addressed).isPresent());

    // Recreate the controller, with no in-memory poll or pending state retained.
    var restarted =
        new DedicatedGoalAgents(
            inbox,
            repo,
            runtime,
            messages,
            identities,
            selection,
            broker,
            GOALS,
            new JdbcRelayRetirementRepository(jdbc, work),
            Instant::now);
    var recovered = new GoalService(repo, runtime, GOALS, restarted);
    recovered.dispatch();
    assertTrue(opening.get().contains("Check the amended criteria."));
    assertFalse(opening.get().contains("Release notes are ready."));
    String addressedJob = repo.find(launched.goal()).orElseThrow().progress().job();
    finish(invocation.get(), addressedJob, 4);
    recovered.dispatch();
    assertTrue(messages.route(addressed).orElseThrow().handled());
    assertTrue(opening.get().contains("Release notes are ready."));
    String activated = repo.find(launched.goal()).orElseThrow().progress().job();
    finish(invocation.get(), activated, 5);
    assertTrue(inbox.next(launched.goal()).isEmpty());
    assertEquals(State.IDLE, repo.find(launched.goal()).orElseThrow().progress().state());
    recovered.dispatch();
    assertEquals(State.IDLE, repo.find(launched.goal()).orElseThrow().progress().state());

    broker.publish(
        key,
        new Relay.Draft(
            "queued-release",
            owner,
            Instant.now(),
            null,
            null,
            new RelayPayload.Text("This queued body must be fenced.")));
    restarted.intake();
    var queued = inbox.next(launched.goal()).orElseThrow();
    members.remove(project, owner);
    assertFalse(members.mayUse(project, owner));
    assertThrows(
        CallerFault.class, () -> restarted.requireSource(retained.get(), queued.listener()));
    assertThrows(CallerFault.class, () -> restarted.listen(owner, request));
    assertThrows(
        CallerFault.class,
        () ->
            restarted.sources(
                owner, launched.goal(), launched.conversation(), new Search(null, null, null, 10)));
    members.add(project, owner);
    assertEquals(
        1,
        jdbc.update(
            "DELETE FROM relay_topics WHERE project_id=? AND name=?", projectId, key.name()));
    var replacement =
        broker.registerTopic(key, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    assertNotEquals(topic.generation(), replacement.generation());
    assertEquals(
        List.of(
            new GoalAgents.Source(key.name(), replacement.generation(), RelayPayload.Kind.TEXT)),
        restarted
            .sources(
                owner, launched.goal(), launched.conversation(), new Search(null, null, null, 10))
            .sources());
    assertThrows(
        CallerFault.class, () -> restarted.requireSource(retained.get(), queued.listener()));
    assertThrows(CallerFault.class, () -> restarted.listen(owner, request));
    recovered.dispatch();
    var paused = repo.find(launched.goal()).orElseThrow();
    assertEquals(State.PAUSED, paused.progress().state());
    assertNull(paused.cycle());
    var removal =
        new Unlisten(
            launched.goal(),
            launched.conversation(),
            listener.binding(),
            paused.version(),
            UUID.randomUUID());
    var removed = restarted.unlisten(owner, removal);
    assertEquals(paused.version() + 1, removed.version());
    assertEquals(removed, restarted.unlisten(owner, removal));
    assertEquals(State.PAUSED, repo.find(launched.goal()).orElseThrow().progress().state());
    var scheduleStore = new JdbcScheduleStore(jdbc);
    var files =
        new JdbcScheduleDefinitionStore(
            jdbc,
            BUDGET.transactions(work, LIMITS.queryCeiling()),
            scheduleStore,
            new JdbcTriggerStore(jdbc),
            new JdbcFiringStore(jdbc));
    var source = files.register(owner, projectId, "server");
    removeWhileGoalIsLocked(files, source, keeper, repo, launched.goal());
    assertEquals(State.COMPLETE, repo.find(launched.goal()).orElseThrow().progress().state());

    standingBatches(
        owner,
        project,
        key,
        replacement,
        repo,
        inbox,
        identities,
        selection,
        broker,
        runtime,
        messages,
        definition,
        opening,
        invocation,
        currentTurn,
        beforeClaim);
  }

  private static void removeWhileGoalIsLocked(
      JdbcScheduleDefinitionStore files,
      ScheduleDefinitionStore.Source source,
      String name,
      JdbcGoalRepository repo,
      UUID goal)
      throws Exception {
    // pg_stat_activity retains a snapshot for each transaction. Observe the waiting writer
    // through an independent autocommit connection while the test transaction owns the Goal.
    var stats =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    try (var workers = Executors.newSingleThreadExecutor()) {
      var removal =
          BUDGET
              .transactions(work, LIMITS.queryCeiling())
              .inTransaction(
                  () -> {
                    repo.lock(goal).orElseThrow();
                    var pending = workers.submit(() -> files.remove(source, name));
                    boolean waiting = false;
                    for (int attempt = 0;
                        attempt < 70 && !waiting && !pending.isDone();
                        attempt++) {
                      waiting =
                          Boolean.TRUE.equals(
                              stats.queryForObject(
                                  """
              SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                WHERE pid<>pg_backend_pid() AND cardinality(pg_blocking_pids(pid))>0
                  AND wait_event_type='Lock' AND query LIKE '%distinct_targets%')
              """,
                                  Boolean.class));
                      if (!waiting) {
                        try {
                          Thread.sleep(10);
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(
                              "Interrupted while waiting for the schedule writer", interrupted);
                        }
                      }
                    }
                    assertTrue(waiting, "the schedule writer must wait for the exact Goal row");
                    assertEquals(
                        List.of("scheduled-" + source.id() + "-" + name),
                        jdbc.queryForList(
                            "SELECT name FROM schedules WHERE name=? FOR UPDATE NOWAIT",
                            String.class,
                            "scheduled-" + source.id() + "-" + name));
                    return pending;
                  });
      removal.get(5, TimeUnit.SECONDS);
    }
  }

  private void standingBatches(
      String owner,
      String project,
      Relay.TopicKey key,
      Relay.Topic topic,
      JdbcGoalRepository repo,
      GoalAgentStore inbox,
      IdentityRepository identities,
      GoalRelaySources selection,
      Relay broker,
      GoalExecution runtime,
      RelayMessaging messages,
      AgentDefinition definition,
      AtomicReference<String> opening,
      AtomicReference<RunAdmission> invocation,
      AtomicReference<Prepared> currentTurn,
      AtomicReference<Runnable> beforeClaim) {
    var clock = new AtomicReference<>(Instant.parse("2026-11-21T00:00:00Z"));
    var properties = new GoalProperties(true, 3, 10, 1, 5, 2, 2, Duration.ofMinutes(1));
    Supplier<DedicatedGoalAgents> restart =
        () ->
            new DedicatedGoalAgents(
                inbox,
                repo,
                runtime,
                messages,
                identities,
                selection,
                broker,
                properties,
                new JdbcRelayRetirementRepository(jdbc, work),
                clock::get);
    var agent = restart.get();
    messages.useGoalInbox(agent);
    var goal =
        agent.start(
            owner,
            new Start(project, definition.name(), "Review each batch.", null, UUID.randomUUID()));
    var batchTool =
        GoalListenerTools.bind(
                agent,
                repo.find(goal.goal()).orElseThrow().binding(),
                runtime.restore(repo.find(goal.goal()).orElseThrow().binding()))
            .stream()
            .filter(tool -> tool.schema().name().equals("message_listen"))
            .findFirst()
            .orElseThrow();
    String choice =
        "{\"topic\":\"" + key.name() + "\",\"generation\":\"" + topic.generation() + "\"";
    assertTrue(batchTool.run(choice + ",\"batchCount\":2}", Home.of(project)).contains("together"));
    assertTrue(
        batchTool
            .run(choice + ",\"batchCount\":null,\"maxDelaySeconds\":null}", Home.of(project))
            .contains("valid Goal wake arguments"));
    assertTrue(
        batchTool
            .run(choice + ",\"batchCount\":2,\"maxDelaySeconds\":30}", Home.of(project))
            .contains("\"binding\""));
    var request =
        new Listen(
            goal.goal(),
            goal.conversation(),
            new Selection(key.name(), topic.generation()),
            new Batch(2, 30));
    var binding = agent.listen(owner, request);
    var direct = messages.openGoalInbox(owner, project, definition.name());

    publish(broker, key, owner, "batch-first");
    agent.intake();
    assertTrue(inbox.next(goal.goal()).isEmpty(), "first member should remain open");
    var restarted = restart.get();
    messages.useGoalInbox(restarted);
    publish(broker, key, owner, "batch-second");
    restarted.intake();
    var collected = inbox.next(goal.goal()).orElseThrow();
    UUID firstBatch = collected.batch().id();
    assertEquals(
        List.of("batch-first", "batch-second"),
        collected.batch().events().stream().map(GoalAgentStore.Event::eventId).toList());
    restarted.intake();
    assertEquals(firstBatch, inbox.next(goal.goal()).orElseThrow().batch().id());

    // While the launch is READY, both a selected empty frontier and queued automation yield to
    // direct steering that commits before the claim locks the Goal.
    var scheduler = new GoalService(repo, runtime, properties, restarted);
    beforeClaim.set(
        () ->
            sendSteering(
                messages,
                owner,
                project,
                definition,
                direct.conversation(),
                goal.instance(),
                "Steering that wins the empty-frontier race."));
    scheduler.dispatch();
    assertEquals(State.READY, repo.find(goal.goal()).orElseThrow().progress().state());
    scheduler.dispatch();
    assertTrue(opening.get().contains("wins the empty-frontier race"));
    assertFalse(opening.get().contains("batch-first"));
    finish(invocation.get(), repo.find(goal.goal()).orElseThrow().progress().job(), 1);

    scheduler.dispatch();
    assertTrue(opening.get().contains("batch-first"));
    assertTrue(opening.get().contains("batch-second"));
    var sources = GoalTestActivation.provenance(invocation.get().goal()).sources();
    assertEquals("relay-batch:" + firstBatch, sources.getFirst().id());
    assertEquals(3, sources.size());
    var ongoing = repo.find(goal.goal()).orElseThrow();
    var workWindow = currentTurn.get().budget();
    assertTrue(workWindow.tryClaim().isPresent());
    assertTrue(workWindow.tryClaim().isPresent());
    assertTrue(workWindow.tryClaim().isEmpty());
    publish(broker, key, owner, "late-member");
    restarted.intake();
    assertTrue(inbox.next(goal.goal()).isEmpty(), "running membership is frozen");

    GoalTestActivation.settle(
        invocation.get().goal(),
        ongoing.progress().job(),
        2,
        new Outcome(Outcome.Ending.ANSWERED, "More work remains", 1, 2, ""),
        null,
        () -> {});
    scheduler.dispatch();
    var exhausted = repo.find(goal.goal()).orElseThrow();
    assertEquals(Reason.MODEL_ALLOWANCE, exhausted.progress().reason());
    assertEquals(ongoing.cycle(), exhausted.cycle());
    int total = runtime.restore(exhausted.binding()).budget().limit();
    assertThrows(
        Refused.class,
        () ->
            scheduler.control(
                owner,
                Action.RESUME,
                new Control(
                    goal.conversation(),
                    UUID.randomUUID(),
                    goal.goal(),
                    exhausted.version(),
                    null)));
    var requestId = UUID.randomUUID();
    var extend =
        new Control(goal.conversation(), requestId, goal.goal(), exhausted.version(), null, total);
    var resumed = scheduler.control(owner, Action.RESUME, extend);
    assertEquals(resumed, scheduler.control(owner, Action.RESUME, extend));
    var extended = repo.find(goal.goal()).orElseThrow();
    assertEquals(ongoing.cycle().id(), extended.cycle().id());
    assertEquals(ongoing.cycle().startSpent(), extended.cycle().startSpent());
    assertEquals(2, runtime.restore(extended.binding()).budget().spent());
    assertTrue(extended.cycle().maxCalls() > exhausted.cycle().maxCalls());

    clock.updateAndGet(at -> at.plusSeconds(30));
    var afterDeadline = restart.get();
    messages.useGoalInbox(afterDeadline);
    afterDeadline.intake();
    var later = inbox.next(goal.goal()).orElseThrow();
    assertNotEquals(firstBatch, later.batch().id());
    assertEquals(
        List.of("late-member"),
        later.batch().events().stream().map(GoalAgentStore.Event::eventId).toList());
    var recovered = new GoalService(repo, runtime, properties, afterDeadline);
    recovered.dispatch();
    assertFalse(
        opening.get().contains("late-member"), "ongoing cycles leave automatic inputs queued");
    finish(invocation.get(), repo.find(goal.goal()).orElseThrow().progress().job(), 3);
    recovered.dispatch();
    assertEquals(State.IDLE, repo.find(goal.goal()).orElseThrow().progress().state());
    var parked = repo.find(goal.goal()).orElseThrow();
    var paused =
        recovered.control(
            owner,
            Action.PAUSE,
            new Control(
                goal.conversation(), UUID.randomUUID(), goal.goal(), parked.version(), null));
    assertNull(repo.find(goal.goal()).orElseThrow().cycle());
    var idle =
        recovered.control(
            owner,
            Action.RESUME,
            new Control(
                goal.conversation(), UUID.randomUUID(), goal.goal(), paused.version(), null));
    assertEquals(State.IDLE, idle.state());

    // The final unlisten is safe during a claimed batch. It preserves active ownership and
    // accepted direct steering, and restart never treats the latter as handled successfully.
    clock.updateAndGet(at -> at.plusSeconds(30));
    recovered.dispatch();
    var claimed = repo.find(goal.goal()).orElseThrow();
    assertEquals(State.RUNNING, claimed.progress().state());
    assertTrue(opening.get().contains("late-member"));
    sendSteering(
        messages,
        owner,
        project,
        definition,
        direct.conversation(),
        goal.instance(),
        "This is queued during the paid turn.");
    String queued = inbox.next(goal.goal()).orElseThrow().message();
    var remove =
        new Unlisten(
            goal.goal(),
            goal.conversation(),
            binding.binding(),
            claimed.version(),
            UUID.randomUUID());
    var removal = afterDeadline.unlisten(owner, remove);
    assertEquals(removal, afterDeadline.unlisten(owner, remove));
    var disarmed = repo.find(goal.goal()).orElseThrow();
    assertEquals(claimed.progress().job(), disarmed.progress().job());
    assertFalse(inbox.standing(goal.goal()));
    var cancelled =
        recovered.control(
            owner,
            Action.CANCEL,
            new Control(
                goal.conversation(), UUID.randomUUID(), goal.goal(), disarmed.version(), null));
    assertEquals(State.STOPPING, cancelled.state());
    var restartedScheduler = new GoalService(repo, runtime, properties, restart.get());
    restartedScheduler.dispatch();
    var ended = repo.find(goal.goal()).orElseThrow();
    assertEquals(State.CANCELLED, ended.progress().state());
    assertEquals(Reason.UNCONFIRMED_TURN, ended.progress().reason());
    assertEquals(
        "UNCERTAIN",
        jdbc.queryForObject(
            "SELECT disposition FROM goal_agent_inputs WHERE claimed_job=?",
            String.class,
            claimed.progress().job()));
    assertTrue(messages.route(queued).orElseThrow().handled());
    assertEquals("UNAVAILABLE", messages.route(queued).orElseThrow().ending());
    assertTrue(
        sendSteering(
                messages,
                owner,
                project,
                definition,
                direct.conversation(),
                goal.instance(),
                "Forbidden after cancel")
            .contains("That Goal Agent has ended"));
    assertEquals(topic.generation(), broker.topic(key).generation());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_subscriptions WHERE subscriber=?",
            Integer.class,
            "goal." + binding.binding()));

    var gapTopic = new Relay.TopicKey(key.projectId(), "data.retention-gap");
    var gap =
        broker.registerTopic(
            gapTopic, RelayPayload.Kind.TEXT, new Relay.Policy(Duration.ofDays(1), 1L));
    var gapAgent = restart.get();
    var gapGoal =
        gapAgent.start(
            owner,
            new Start(project, definition.name(), "Check retention.", null, UUID.randomUUID()));
    gapAgent.listen(
        owner,
        new Listen(
            gapGoal.goal(),
            gapGoal.conversation(),
            new Selection(gapTopic.name(), gap.generation())));
    publish(broker, gapTopic, owner, "evicted");
    publish(broker, gapTopic, owner, "still-retained");
    var relayRepo =
        new JdbcRelayRepository(
            jdbc,
            work,
            new JdbcRelayPayloadStore(
                jdbc, work, Duration.ofHours(1), Duration.ofHours(24), Duration.ofDays(30)));
    assertEquals(1, relayRepo.prune(gapTopic, Instant.now(), 10));
    gapAgent.intake();
    var fenced = repo.find(gapGoal.goal()).orElseThrow();
    assertEquals(State.PAUSED, fenced.progress().state());
    assertTrue(fenced.progress().detail().contains("RETENTION_GAP (1–1)"));
    assertTrue(inbox.next(gapGoal.goal()).isEmpty());
  }

  private static void publish(Relay broker, Relay.TopicKey key, String owner, String event) {
    broker.publish(
        key,
        new Relay.Draft(event, owner, Instant.now(), null, null, new RelayPayload.Text(event)));
  }

  private static String sendSteering(
      RelayMessaging messages,
      String owner,
      String project,
      AgentDefinition definition,
      String sender,
      String recipient,
      String text) {
    var home = Home.of(project);
    return messages.send(
        new RunExtras.Context(definition, sender, null, Transcript.NONE, home, owner),
        new SendMessageTool.Request(recipient, text, null, false, false, "persistent", null, null),
        home);
  }

  @Test
  void upgrade_fences_unidentified_legacy_inputs_and_preserves_paused_spending() {
    // An isolated database preserves the V297 schema while the other cases use the latest schema.
    jdbc.execute("CREATE DATABASE goal_cycle_upgrade");
    var legacySource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/goal_cycle_upgrade"),
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    Flyway.configure().dataSource(legacySource).target("297").load().migrate();
    var legacy = new JdbcTemplate(legacySource);
    String owner = "legacy-goal-owner";
    String project = "legacy-goals";
    legacy.update("INSERT INTO admins(handle,password_hash) VALUES(?,'fixture')", owner);
    long projectId =
        legacy.queryForObject(
            "INSERT INTO projects(name) VALUES(?) RETURNING id", Long.class, project);
    var topic = new Relay.TopicKey(projectId, "upgrade.events");
    var broker =
        new DurableRelay(
            new JdbcRelayRepository(
                legacy,
                UnitOfWork.NONE,
                new JdbcRelayPayloadStore(
                    legacy,
                    UnitOfWork.NONE,
                    Duration.ofHours(1),
                    Duration.ofHours(24),
                    Duration.ofDays(30))),
            Instant::now);
    var generation =
        broker
            .registerTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault())
            .generation();
    var running =
        legacyGoal(legacy, owner, project, projectId, topic, generation, State.RUNNING, null);
    var blocked =
        legacyGoal(
            legacy,
            owner,
            project,
            projectId,
            topic,
            generation,
            State.BLOCKED,
            Reason.UNCONFIRMED_TURN);
    var paused =
        legacyGoal(
            legacy,
            owner,
            project,
            projectId,
            topic,
            generation,
            State.PAUSED,
            Reason.OPERATOR_PAUSED);
    var missing =
        legacyGoal(legacy, owner, project, projectId, topic, generation, State.IDLE, null);
    for (var binding : List.of(running, blocked, paused)) {
      broker.subscribe(new Relay.SubscriptionKey(topic, "goal." + binding), Relay.Start.LATEST);
    }
    Flyway.configure().dataSource(legacySource).load().migrate();

    for (var binding : List.of(running, blocked)) {
      assertEquals(
          "UNCERTAIN",
          legacy.queryForObject(
              "SELECT disposition FROM goal_agent_inputs WHERE listener=?", String.class, binding));
      assertEquals(
          "DISARMED",
          legacy.queryForObject(
              "SELECT state FROM goal_agent_relay_batches WHERE listener=?",
              String.class,
              binding));
    }
    assertEquals(
        "READY",
        legacy.queryForObject(
            "SELECT state FROM goal_agent_relay_batches WHERE listener=?", String.class, paused));
    assertNotNull(
        legacy.queryForObject(
            "SELECT cursor_generation FROM goal_agent_listeners WHERE id=?", UUID.class, paused));
    assertEquals(
        2,
        legacy.queryForObject(
            """
        SELECT a.spent FROM execution_allowances a JOIN conversation_goals g ON g.allowance=a.id
        JOIN goal_agent_listeners l ON l.goal=g.id WHERE l.id=? AND g.cycle IS NOT NULL
        """,
            Integer.class,
            paused));
    assertEquals(
        "SOURCE_CHANGED",
        legacy.queryForObject(
            "SELECT failure FROM goal_agent_listeners WHERE id=?", String.class, missing));
    assertEquals(
        "PAUSED",
        legacy.queryForObject(
            "SELECT g.state FROM conversation_goals g JOIN goal_agent_listeners l ON l.goal=g.id WHERE l.id=?",
            String.class,
            missing));
    assertEquals(
        "SOURCE_UNAVAILABLE",
        legacy.queryForObject(
            "SELECT disposition FROM goal_agent_inputs WHERE listener=?", String.class, missing));
  }

  private static UUID legacyGoal(
      JdbcTemplate jdbc,
      String owner,
      String project,
      long projectId,
      Relay.TopicKey topic,
      UUID generation,
      State state,
      Reason reason) {
    var conversation =
        new ConversationStore(jdbc).open(Home.of(project), Budget.of(8), null, owner);
    UUID admission = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO operation_admissions(id,executor,executor_handle,scope,project_name,source_account)
        SELECT ?,a.id,?,s.id,?,a.id FROM authority_accounts a, authority_scopes s
        WHERE a.legacy_handle=? AND s.project_id=?
        """,
        admission,
        owner,
        project,
        owner,
        projectId);
    UUID goal = UUID.randomUUID();
    String instance = "ins_" + goal.toString().replace("-", "");
    jdbc.update(
        """
        INSERT INTO message_instances(id,account,project,agent,conversation,lifetime)
        VALUES(?,?,?,'worker',?,'persistent')
        """,
        instance,
        owner,
        project,
        conversation.id());
    var budget = new JdbcAllowanceRepository(jdbc).bind(Budget.of(8));
    budget.tryClaim().orElseThrow();
    budget.tryClaim().orElseThrow();
    String job = state == State.RUNNING ? "job_" + goal.toString().replace("-", "") : null;
    jdbc.update(
        """
        INSERT INTO conversation_goals(
          id,conversation,admission,executor,agent,fingerprint,allowance,objective,activation_limit,
          activations,state,job,last_job,reason,instance)
        VALUES(?,?,?,?,'worker',?,?,'Review retained events.',3,1,?,?,?,?,?)
        """,
        goal,
        conversation.id(),
        admission,
        owner,
        GoalText.digest("worker"),
        budget.identity(),
        state.name(),
        job,
        job,
        reason == null ? null : reason.name(),
        instance);
    var binding = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO goal_agent_listeners(id,goal,project_id,topic,generation) VALUES(?,?,?,?,?)",
        binding,
        goal,
        projectId,
        topic.name(),
        generation);
    jdbc.update(
        """
        INSERT INTO goal_agent_inputs(goal,listener,position,event_id,body)
        VALUES(?,?,1,'legacy-event','Review retained events.')
        """,
        goal,
        binding);
    return binding;
  }

  @Test
  void handoffPersistsTheExactParentAdmissionAndRefusesWiderOrWithdrawnGrants() throws Exception {
    String account = "handoff-owner";
    var home = Home.of("handoff-project");
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES(?,'fixture')", account);
    jdbc.update("INSERT INTO projects(name) VALUES(?)", home.project());
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,?,'CONTRIBUTOR' FROM projects WHERE name=?",
        account,
        home.project());
    var members = new JdbcProjectMembers(jdbc);
    var identities = new JdbcIdentityRepository(jdbc);
    var grants = new JdbcScopedGrantRepository(jdbc);
    ApplicationPolicy applications =
        project -> new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of());
    var records = new JdbcOperationRecords(jdbc);
    var authority =
        new PinnedOperationAuthority(
            identities,
            grants,
            new LiveScopedAuthority(
                grants,
                applications,
                new ApplicationGrantLineage(grants, applications),
                identities),
            members,
            records,
            new JdbcResourceScopeRepository(jdbc));
    var logs = new ConversationStore(jdbc);
    var allowances = new JdbcAllowanceRepository(jdbc);
    var worker = definition("worker", List.of("result_list"));
    var wider = definition("wider", List.of("file_read"));
    var allowed =
        new AgentDefinition(
                "allowed",
                "Caller",
                "model",
                List.of("result_list"),
                List.of(worker.name(), wider.name()),
                List.of(),
                4,
                8,
                "Review the project.")
            .withSkills(List.of("goal"));
    var sibling = definition("sibling", List.of("result_list")).withSkills(List.of("goal"));
    var parent = logs.open(home, Budget.of(8), null, account);
    var other = logs.open(home, Budget.of(8), null, account);
    var parentAdmission =
        authority.admit(account, authority.scope(home), OperationExecution.actions(allowed));
    var otherAdmission =
        authority.admit(account, authority.scope(home), OperationExecution.actions(sibling));

    var lookup = new DefinitionResolver.Caller(7L, null, account);
    var definitions = mock(ConversationDefinitions.class);
    when(definitions.callerForConversation(anyString(), eq(null))).thenReturn(lookup);
    when(definitions.readAgent(allowed.name(), lookup)).thenReturn(allowed);
    when(definitions.readAgent(sibling.name(), lookup)).thenReturn(sibling);
    when(definitions.readAgent(worker.name(), lookup)).thenReturn(worker);
    when(definitions.readAgent(wider.name(), lookup)).thenReturn(wider);
    when(definitions.requireAgent(worker.name(), lookup)).thenReturn(worker);
    var messaging =
        new RelayMessaging(
            new JdbcMessagingRepository(jdbc, allowances),
            allowances,
            logs,
            mock(FiringStore.class),
            mock(MessageInboxes.class),
            work,
            mock(Voice.class),
            (name, project) -> worker,
            ignored -> {},
            Instant::now);
    var inbox = new JdbcGoalAgentStore(jdbc, work, BUDGET, LIMITS);
    var repo = new JdbcGoalRepository(jdbc, work, BUDGET, LIMITS);
    var runtime =
        new GoalRuntime(
            logs,
            definitions,
            authority,
            records,
            identities,
            mock(Turn.class),
            mock(JobStore.class),
            mock(SuspendedRunCalls.class),
            allowances,
            messaging);
    var service =
        new DedicatedGoalAgents(
            inbox,
            repo,
            runtime,
            messaging,
            identities,
            mock(GoalRelaySources.class),
            mock(Relay.class),
            GOALS,
            new JdbcRelayRetirementRepository(jdbc, work),
            Instant::now);

    messaging.useGoalInbox(service);
    messaging.useGoalScheduleTransactions(BUDGET.transactions(work, LIMITS.queryCeiling()));

    var calling = new GoalHandoffs.Caller(parent.id(), allowed, parentAdmission);
    var ungranted = new GoalHandoffs.Caller(other.id(), sibling, otherAdmission);
    var request =
        new Start(
            home.project(), worker.name(), "Review the release notes.", null, UUID.randomUUID());
    SkillDefinition skill;
    try (var resource = getClass().getResourceAsStream("/skills/goal/SKILL.md")) {
      skill =
          SkillDefinition.parse(
              new DefinitionSource.Definition(
                  "goal", "fixture", new String(resource.readAllBytes(), StandardCharsets.UTF_8)),
              OrchestrationDefinition.Tier.SHIPPED);
    }
    var skillRuns = new JdbcSkillExecutionsRepository(jdbc);
    var original =
        new SkillExecutions.Handoff(parentAdmission.id(), OperationExecution.fingerprint(allowed));
    assertTrue(
        skillRuns.claimHandoff(
            account, request.requestId(), "handoff", parent.id(), allowed.name(), skill, original));
    skillRuns.running(account, request.requestId(), parent.id());
    skillRuns.closed(parent.id(), new Outcome(Outcome.Ending.AWAITING, "Which notes?", 0, 0, ""));
    assertEquals(
        original, new JdbcSkillExecutionsRepository(jdbc).active(parent.id()).getFirst().handoff());
    assertEquals(
        List.of(new GoalHandoffs.Candidate(worker.name(), worker.description())),
        service.candidates(calling));
    assertTrue(service.candidates(ungranted).isEmpty());
    assertThrows(CallerFault.class, () -> service.start(ungranted, request));
    assertThrows(
        CallerFault.class,
        () ->
            service.start(
                calling,
                new Start(
                    home.project(), wider.name(), request.objective(), null, request.requestId())));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM message_instances WHERE project=?",
            Integer.class,
            home.project()));

    var receipt = service.start(calling, request);
    assertEquals(receipt, service.start(calling, request));
    assertThrows(
        CallerFault.class,
        () ->
            service.start(
                calling,
                new Start(
                    home.project(), worker.name(), "Different task", null, request.requestId())));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM message_instances WHERE project=?",
            Integer.class,
            home.project()));
    var bound = repo.find(receipt.goal()).orElseThrow().binding();
    assertEquals(allowed.name(), bound.callingAgent());
    assertEquals(parent.id(), bound.callingConversation());
    var restarted = new JdbcOperationRecords(jdbc);
    assertEquals(Optional.of(parentAdmission.id()), restarted.parent(bound.admission()));
    var stored = restarted.find(bound.admission()).orElseThrow();
    assertEquals(Set.copyOf(parentAdmission.support()), Set.copyOf(stored.support()));
    assertNotEquals(parentAdmission.id(), runtime.restore(bound).authority().id());

    var siblingWithGrant =
        new AgentDefinition(
                sibling.name(),
                "Sibling",
                "model",
                List.of("result_list"),
                List.of(worker.name()),
                List.of(),
                4,
                8,
                "Review the project.")
            .withSkills(List.of("goal"));
    when(definitions.readAgent(sibling.name(), lookup)).thenReturn(siblingWithGrant);
    var siblingReceipt =
        service.start(
            new GoalHandoffs.Caller(other.id(), siblingWithGrant, otherAdmission),
            new Start(
                home.project(),
                worker.name(),
                "Review the next release.",
                null,
                UUID.randomUUID()));
    var siblingBound = repo.find(siblingReceipt.goal()).orElseThrow().binding();
    var schedules = new JdbcScheduleStore(jdbc);
    var triggers = new JdbcTriggerStore(jdbc);
    var firings = new JdbcFiringStore(jdbc);
    var files = new JdbcScheduleDefinitionStore(jdbc, work, schedules, triggers, firings);
    long project =
        jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, home.project());
    var source = files.register(account, project, "server");
    Instant at = Instant.parse("2026-10-05T01:00:00Z");
    var firstSchedule =
        service.proposeSchedule(
            bound, runtime.restore(bound), "0 0 * * * *", "UTC", "Check this release.");
    var siblingSchedule =
        service.proposeSchedule(
            siblingBound,
            runtime.restore(siblingBound),
            "0 0 * * * *",
            "UTC",
            "Check the next release.");
    var firstFile =
        files.apply(source, firstSchedule.name(), firstSchedule.definition(), at.minusSeconds(1));
    var siblingFile =
        files.apply(
            source, siblingSchedule.name(), siblingSchedule.definition(), at.minusSeconds(1));
    var intake = new Intake(triggers, firings, mock(Dispatcher.class), () -> at);
    var broker =
        new DurableRelay(
            new JdbcRelayRepository(
                jdbc,
                work,
                new JdbcRelayPayloadStore(
                    jdbc, work, Duration.ofHours(1), Duration.ofHours(24), Duration.ofDays(30))),
            () -> at);
    var publication = new RelayScheduledPublication(schedules, broker, intake, work, () -> {});
    var firstTick = recordTick(schedules, firings, publication, firstFile.internalName(), at);
    var siblingTick = recordTick(schedules, firings, publication, siblingFile.internalName(), at);
    when(definitions.readAgent(allowed.name(), lookup))
        .thenReturn(definition("allowed", List.of("result_list")).withSkills(List.of("goal")));
    assertThrows(CallerFault.class, () -> runtime.restore(bound));
    assertThrows(
        CallerFault.class,
        () ->
            messaging.receiveGoalSchedule(
                account, home.project(), firstTick.goalSchedule(), firstSchedule.definition()));
    assertTrue(inbox.next(receipt.goal()).isEmpty());
    var accepted =
        messaging.receiveGoalSchedule(
            account, home.project(), siblingTick.goalSchedule(), siblingSchedule.definition());
    assertEquals(accepted.message(), inbox.next(siblingReceipt.goal()).orElseThrow().message());
    files.remove(source, firstSchedule.name());
    files.remove(source, siblingSchedule.name());
    when(definitions.readAgent(allowed.name(), lookup)).thenReturn(allowed);
    members.remove(home.project(), account);
    assertThrows(CallerFault.class, () -> runtime.restore(bound));
  }

  private String scheduledWakes(
      String owner,
      long projectId,
      String project,
      JdbcGoalRepository repo,
      GoalExecution runtime,
      RelayMessaging messages,
      GoalAgentStore inbox,
      IdentityRepository identities,
      GoalRelaySources selection,
      Relay broker,
      DedicatedGoalAgents agent,
      GoalAgents.Started launched,
      Prepared prepared,
      AtomicReference<String> opening,
      AtomicReference<RunAdmission> invocation,
      AtomicReference<Runnable> beforeClaim)
      throws Exception {
    var mapper = new ObjectMapper();
    var goal = repo.find(launched.goal()).orElseThrow();
    var tool =
        GoalListenerTools.bind(agent, goal.binding(), prepared).stream()
            .filter(candidate -> candidate.schema().name().equals("goal_schedule"))
            .findFirst()
            .orElseThrow();
    var draft =
        mapper.readTree(
            tool.run(
                "{\"cron\":\"0 0 * * * *\",\"zone\":\"UTC\",\"input\":\"Inspect the release feed.\"}",
                Home.of("untrusted-project")));
    assertEquals("schedule.save", draft.get("operation").textValue());
    var request = mapper.treeToValue(draft.get("request"), ScheduledWork.Save.class);
    assertEquals(project, request.project());
    assertEquals(launched.instance(), request.definition().target().to());
    assertEquals(goal.binding().agent(), request.definition().action().agent());
    assertFalse(request.overwrite());
    assertTrue(
        tool.run("{\"cron\":\"broken\",\"zone\":\"UTC\",\"input\":\"test\"}", Home.of(project))
            .contains("refused"));
    assertTrue(
        tool.run(
                "{\"cron\":\"0 0 * * * *\",\"zone\":\"UTC\",\"input\":\"test\",\"to\":\"ins_other\"}",
                Home.of(project))
            .contains("valid Goal wake arguments"));

    var schedules = new JdbcScheduleStore(jdbc);
    var triggers = new JdbcTriggerStore(jdbc);
    var firings = new JdbcFiringStore(jdbc);
    var files = new JdbcScheduleDefinitionStore(jdbc, work, schedules, triggers, firings);
    var source = files.register(owner, projectId, "server");
    Instant at = Instant.parse("2026-10-05T01:00:00Z");
    var workDefinition = request.definition();
    var file = files.apply(source, request.name(), workDefinition, at.minusSeconds(1));
    String schedule = file.internalName();
    var selected = schedules.due(at).getFirst();
    assertFalse(selected.fileGeneration() == null);
    assertEquals(file, files.apply(source, request.name(), workDefinition, at));
    assertEquals(selected, schedules.due(at).getFirst());
    assertTrue(inbox.standing(launched.goal()));

    var scheduler = new GoalService(repo, runtime, GOALS, agent);
    scheduler.dispatch();
    finish(invocation.get(), repo.find(launched.goal()).orElseThrow().progress().job(), 1);
    assertEquals(State.IDLE, repo.find(launched.goal()).orElseThrow().progress().state());

    var intake = new Intake(triggers, firings, mock(Dispatcher.class), () -> at);
    var publication = new RelayScheduledPublication(schedules, broker, intake, work, () -> {});
    var first = recordTick(schedules, firings, publication, schedule, at);
    assertEquals(selected.fileGeneration(), first.scheduleGeneration());
    assertThrows(
        CallerFault.class,
        () -> messages.receiveScheduled(owner, project, schedule, first.id(), workDefinition));
    var task = messages.receiveGoalSchedule(owner, project, first.goalSchedule(), workDefinition);
    assertEquals(
        task, messages.receiveGoalSchedule(owner, project, first.goalSchedule(), workDefinition));
    firings.finish(first.id(), at);
    assertEquals(task.message(), inbox.next(launched.goal()).orElseThrow().message());
    assertEquals(first.goalSchedule(), inbox.next(launched.goal()).orElseThrow().schedule());

    var restarted =
        new DedicatedGoalAgents(
            inbox,
            repo,
            runtime,
            messages,
            identities,
            selection,
            broker,
            GOALS,
            new JdbcRelayRetirementRepository(jdbc, work),
            Instant::now);
    messages.useGoalInbox(restarted);
    var recovered = new GoalService(repo, runtime, GOALS, restarted);
    var sender = messages.openGoalInbox(owner, project, goal.binding().agent());
    beforeClaim.set(
        () ->
            messages.send(
                new RunExtras.Context(
                    prepared.agent(),
                    sender.conversation(),
                    null,
                    Transcript.NONE,
                    Home.of(project),
                    owner),
                new SendMessageTool.Request(
                    launched.instance(),
                    "Steering after schedule selection.",
                    null,
                    false,
                    false,
                    "persistent",
                    null,
                    null),
                Home.of(project)));
    recovered.dispatch();
    assertEquals(State.IDLE, repo.find(launched.goal()).orElseThrow().progress().state());
    assertTrue(inbox.next(launched.goal()).orElseThrow().schedule() == null);
    recovered.dispatch();
    assertTrue(opening.get().contains("Steering after schedule selection."));
    finish(invocation.get(), repo.find(launched.goal()).orElseThrow().progress().job(), 2);
    recovered.dispatch();
    assertTrue(opening.get().contains("Scheduled Goal wake " + schedule));
    assertTrue(opening.get().contains("Inspect the release feed."));
    var provenance = GoalTestActivation.provenance(invocation.get().goal());
    assertEquals("schedule-firing:" + first.id(), provenance.sources().getFirst().id());
    assertEquals(first.scheduleGeneration().toString(), provenance.sources().getFirst().revision());
    assertEquals(opening.get().codePointCount(0, opening.get().length()), provenance.end());
    assertTrue(
        opening
            .get()
            .substring(opening.get().offsetByCodePoints(0, provenance.start()))
            .startsWith("Scheduled Goal wake " + schedule));
    finish(invocation.get(), repo.find(launched.goal()).orElseThrow().progress().job(), 3);
    assertEquals(State.IDLE, repo.find(launched.goal()).orElseThrow().progress().state());
    assertTrue(inbox.next(launched.goal()).isEmpty());

    var next = recordTick(schedules, firings, publication, schedule, at.plusSeconds(3600));
    var stale = messages.receiveGoalSchedule(owner, project, next.goalSchedule(), workDefinition);
    firings.finish(next.id(), at.plusSeconds(3600));
    var unstaged = recordTick(schedules, firings, publication, schedule, at.plusSeconds(7200));
    assertEquals(first.scheduleGeneration(), unstaged.scheduleGeneration());
    var edit =
        new ScheduledWork(
            1,
            workDefinition.cron(),
            workDefinition.zone(),
            false,
            new ScheduledWork.Action(
                "agent", goal.binding().agent(), null, "New instructions.", null),
            workDefinition.target(),
            workDefinition.limits());
    files.apply(source, request.name(), edit, at.plusSeconds(7200));
    assertThrows(
        CallerFault.class,
        () -> messages.receiveGoalSchedule(owner, project, unstaged.goalSchedule(), edit));
    var prior = invocation.get();
    recovered.dispatch();
    assertEquals(prior, invocation.get());
    assertTrue(inbox.next(launched.goal()).isEmpty());
    recovered.dispatch();
    assertTrue(messages.route(stale.message()).orElseThrow().handled());
    var changed = schedules.due(at.plusSeconds(10800)).getFirst();
    assertNotEquals(first.scheduleGeneration(), changed.fileGeneration());

    files.pause(source, request.name(), edit, true, at.plusSeconds(7200));
    assertTrue(inbox.standing(launched.goal()));
    assertTrue(schedules.due(at.plusSeconds(10800)).isEmpty());
    var paused = files.managed(schedule, owner).orElseThrow().definition();
    files.pause(source, request.name(), paused, false, at.plusSeconds(7200));
    var resumed = schedules.due(at.plusSeconds(10800)).getFirst();
    assertNotEquals(changed.fileGeneration(), resumed.fileGeneration());
    firings.finish(unstaged.id(), at.plusSeconds(7200));
    String keeper = "keep-" + launched.goal();
    var pausedKeeper =
        new ScheduledWork(
            1, edit.cron(), edit.zone(), true, edit.action(), edit.target(), edit.limits());
    files.apply(source, keeper, pausedKeeper, at.plusSeconds(7200));
    files.remove(source, request.name());
    assertTrue(inbox.standing(launched.goal()));
    files.apply(source, request.name(), edit, at.plusSeconds(7200));
    assertNotEquals(
        resumed.fileGeneration(), schedules.due(at.plusSeconds(10800)).getFirst().fileGeneration());

    schedules.define(
        schedule, CronSchedule.parse("0 15 * * * *", "UTC"), schedule, owner, at.plusSeconds(7200));
    var drifted = recordTick(schedules, firings, publication, schedule, at.plusSeconds(8100));
    assertTrue(drifted.goalSchedule() == null);
    assertThrows(
        CallerFault.class,
        () -> messages.receiveScheduled(owner, project, schedule, drifted.id(), edit));
    assertThrows(
        CallerFault.class,
        () ->
            messages.receive(
                owner,
                new MessageIngress.Request(
                    project,
                    "system:goal-schedule",
                    "client",
                    goal.binding().agent(),
                    UUID.randomUUID(),
                    null,
                    "Untrusted schedule text.",
                    null,
                    null)));
    files.remove(source, request.name());
    return keeper;
  }

  private static FiringRecord recordTick(
      JdbcScheduleStore schedules,
      JdbcFiringStore firings,
      RelayScheduledPublication publication,
      String schedule,
      Instant at) {
    var due =
        schedules.due(at).stream()
            .filter(it -> it.name().equals(schedule))
            .findFirst()
            .orElseThrow();
    assertEquals(at, due.nextFireAt());
    assertTrue(publication.publish(due, at.plusSeconds(3600), at));
    var firing = firings.oldestWaiting("trigger:" + schedule).orElseThrow();
    assertTrue(firings.claimStart(firing.id(), at));
    return firing;
  }

  @Test
  void early_goal_consent_restarts_and_claims_the_original_call_at_exhausted_allowance() {
    var fixture = waitFixture("goal-wait-early");
    fixture.scheduler().dispatch();
    var original = fixture.current();
    var originalTurn = fixture.active().get();
    assertTrue(fixture.runtime().restore(original.binding()).budget().tryClaim().isPresent());
    var approval = fixture.approval();
    assertTrue(
        fixture.approvals().allow(approval.id(), RunApproval.ONCE, List.of(), fixture.owner()));
    fixture.parkApproval(approval);
    assertEquals(State.WAITING, fixture.current().progress().state());
    var pending = fixture.waitStore().open(fixture.started().goal()).orElseThrow();
    assertEquals(approval.id(), pending.dependency().reference());
    assertEquals(original.progress().job(), pending.origin().job());
    assertEquals(original.cycle().id(), pending.origin().cycle());

    var restarted = fixture.scheduler();
    restarted.dispatch();
    assertEquals(originalTurn, fixture.active().get(), "unreleased consent must not spend a job");
    fixture.calls().release(approval.id());
    assertEquals(
        State.PAUSED, restarted.control(fixture.owner(), Action.PAUSE, fixture.control()).state());
    restarted.dispatch();
    assertEquals(originalTurn, fixture.active().get());
    assertEquals(
        pending.id(), fixture.waitStore().open(fixture.started().goal()).orElseThrow().id());
    assertEquals(
        State.WAITING,
        restarted.control(fixture.owner(), Action.RESUME, fixture.control()).state());
    restarted.dispatch();

    var resumed = fixture.current();
    assertEquals(State.RUNNING, resumed.progress().state());
    assertEquals(original.cycle(), resumed.cycle());
    assertEquals(original.progress().activations(), resumed.progress().activations());
    assertNotEquals(original.progress().job(), resumed.progress().job());
    assertEquals(approval.id(), fixture.active().get().goal().approval().id());
    assertEquals(0, fixture.prepared().get().budget().remaining());
    assertEquals(
        GoalWaitStore.State.CLAIMED,
        fixture.waitStore().open(fixture.started().goal()).orElseThrow().state());

    GoalTestActivation.dependencySettled(fixture.active().get().goal());
    GoalTestActivation.settle(
        fixture.active().get().goal(),
        resumed.progress().job(),
        2,
        new Outcome(Outcome.Ending.CALL_BUDGET, "No model calls remain.", 0, 0, ""),
        null,
        () -> {});
    assertTrue(fixture.waitStore().open(fixture.started().goal()).isEmpty());
    assertEquals(Reason.MODEL_ALLOWANCE, fixture.current().progress().reason());
  }

  @Test
  void cancel_retires_an_already_allowed_wait_and_recovery_never_claims_it() {
    var fixture = waitFixture("goal-wait-cancel");
    var scheduler = fixture.scheduler();
    scheduler.dispatch();
    var original = fixture.active().get();
    var approval = fixture.approval();
    fixture.parkApproval(approval);
    assertTrue(
        fixture.approvals().allow(approval.id(), RunApproval.ONCE, List.of(), fixture.owner()));
    fixture.calls().release(approval.id());
    assertEquals(
        State.CANCELLED,
        scheduler.control(fixture.owner(), Action.CANCEL, fixture.control()).state());
    fixture.scheduler().dispatch();
    assertEquals(original, fixture.active().get());
    assertEquals("waiting", fixture.calls().find(approval.id()).orElseThrow().state());
    assertFalse(fixture.calls().claim(approval.id()));
    assertTrue(
        fixture.calls().recoverable().stream()
            .noneMatch(it -> it.approval().equals(approval.id())));
    assertTrue(fixture.waitStore().open(fixture.started().goal()).isEmpty());
  }

  @Test
  void a_missing_orchestration_receipt_after_an_uncertain_start_blocks_without_resending() {
    var fixture = waitFixture("goal-wait-orchestration");
    fixture.scheduler().dispatch();
    var original = fixture.current();
    var run = fixture.active().get();
    var retainedOrigin =
        new Origin(
            fixture.started().goal(),
            original.cycle().id(),
            original.progress().job(),
            original.progress().job(),
            null,
            1,
            1,
            "start-work");
    var expectedHash = "sha256:" + "1".repeat(64);
    when(fixture.orchestrations().granted(any(), eq("review")))
        .thenReturn(Optional.of(new GoalOrchestrations.Definition("review", expectedHash)));
    when(fixture.orchestrations().start(any(), any(), any()))
        .thenThrow(new IllegalStateException("lost owner receipt"));
    fixture
        .waits()
        .startOrchestration(
            retainedOrigin,
            fixture.runtime().restore(original.binding()),
            fixture.started().conversation(),
            "review",
            "Review release notes.");
    fixture.settleAwaiting();
    assertEquals(State.WAITING, fixture.current().progress().state());
    fixture.scheduler().dispatch();
    assertEquals(State.BLOCKED, fixture.current().progress().state());
    assertEquals(Reason.UNCONFIRMED_TURN, fixture.current().progress().reason());
    assertEquals(run, fixture.active().get());
    fixture.scheduler().dispatch();
    verify(fixture.orchestrations()).start(any(), any(), any());
  }

  @Test
  void a_terminal_orchestration_receipt_is_rechecked_at_claim_and_projected_once() {
    var fixture = waitFixture("goal-wait-early-result");
    var scheduler = fixture.scheduler();
    scheduler.dispatch();
    var original = fixture.current();
    var run = fixture.active().get();
    var origin =
        new Origin(
            fixture.started().goal(),
            original.cycle().id(),
            original.progress().job(),
            original.progress().job(),
            null,
            1,
            1,
            "start-work");
    var hash = "sha256:" + "2".repeat(64);
    when(fixture.orchestrations().granted(any(), eq("review")))
        .thenReturn(Optional.of(new GoalOrchestrations.Definition("review", hash)));
    when(fixture.orchestrations().start(any(), any(), any()))
        .thenAnswer(
            call -> {
              call.<Runnable>getArgument(2).run();
              return "orc_ready";
            });
    fixture
        .waits()
        .startOrchestration(
            origin,
            fixture.runtime().restore(original.binding()),
            fixture.started().conversation(),
            "review",
            "Review the release notes.");
    var retained = fixture.waitStore().open(fixture.started().goal()).orElseThrow();
    var ended = Instant.parse("2026-11-24T00:00:00Z");
    var result =
        new GoalOrchestrations.Run(
            "orc_ready", "cnv_readable", "finished", ended, "Release notes reviewed.");
    when(fixture
            .orchestrations()
            .inspect(any(), eq(retained.dependency().request()), eq("review"), eq(hash)))
        .thenReturn(Optional.of(result));
    when(fixture
            .orchestrations()
            .stillTerminal(
                any(), eq(retained.dependency().request()), eq("review"), eq(hash), eq(result)))
        .thenReturn(false, true);
    fixture.settleAwaiting();

    var restarted = fixture.scheduler();
    restarted.dispatch();
    assertEquals(run, fixture.active().get(), "a stale terminal attempt rolls back the Goal claim");
    assertEquals(State.WAITING, fixture.current().progress().state());
    restarted.dispatch();
    var resumed = fixture.current();
    assertEquals(State.RUNNING, resumed.progress().state());
    assertEquals(original.cycle(), resumed.cycle());
    assertEquals(original.progress().activations(), resumed.progress().activations());
    var provenance = GoalTestActivation.provenance(fixture.active().get().goal());
    assertEquals("orchestration:" + result.id(), provenance.sources().getFirst().id());
    assertEquals(ended.toString(), provenance.sources().getFirst().revision());
    assertEquals(
        ended, fixture.waitStore().open(fixture.started().goal()).orElseThrow().ownerEndedAt());
    GoalTestActivation.dependencySettled(fixture.active().get().goal());
    finish(fixture.active().get(), resumed.progress().job(), 2);
    restarted.dispatch();
    assertEquals(State.COMPLETE, fixture.current().progress().state());
    assertTrue(fixture.waitStore().open(fixture.started().goal()).isEmpty());
  }

  @Test
  void a_pruned_schedule_input_does_not_erase_its_retained_wait_fence() {
    var fixture = waitFixture("goal-wait-stale-schedule");
    fixture.scheduler().dispatch();
    var original = fixture.current();
    var job = original.progress().job();
    var schedule =
        new FiringRecord.GoalSchedule(
            "schedule_removed",
            "firing_removed",
            UUID.randomUUID(),
            Instant.parse("2026-11-24T00:00:00Z"));
    var origin =
        new Origin(
            fixture.started().goal(),
            original.cycle().id(),
            job,
            job,
            schedule,
            1,
            1,
            "start-work");
    String hash = "sha256:" + "3".repeat(64);
    when(fixture.orchestrations().granted(any(), eq("review")))
        .thenReturn(Optional.of(new GoalOrchestrations.Definition("review", hash)));
    when(fixture.orchestrations().start(any(), any(), any())).thenReturn("orc_early");
    fixture
        .waits()
        .startOrchestration(
            origin,
            fixture.runtime().restore(original.binding()),
            fixture.started().conversation(),
            "review",
            "Review once the schedule fires.");
    fixture.settleAwaiting();
    assertEquals(
        schedule,
        fixture.waitStore().open(fixture.started().goal()).orElseThrow().origin().schedule());

    var resumed = fixture.scheduler();
    resumed.dispatch();
    assertEquals(State.PAUSED, fixture.current().progress().state());
    assertEquals(Reason.AUTHORITY_OR_SOURCE_CHANGED, fixture.current().progress().reason());
    verify(fixture.orchestrations(), never()).inspect(any(), any(), anyString(), anyString());
    resumed.dispatch();
    verify(fixture.orchestrations()).cancel(any(), any(), eq("review"), eq(hash));
  }

  @Test
  void an_exact_human_answer_survives_pause_and_restart_with_independent_delivery_receipts() {
    var fixture = waitFixture("goal-return-human", true);
    var goal = fixture.started().goal();
    var conversation = fixture.started().conversation();
    var account = fixture.owner();
    var scheduler = fixture.scheduler();
    scheduler.dispatch();
    var original = fixture.active().get();
    String questionText = GoalTestActivation.ask(original.goal(), "Which retained release?");
    assertTrue(fixture.humanInbox().list(account, true, 0, 10).isEmpty());
    GoalTestActivation.settle(
        original.goal(),
        fixture.current().progress().job(),
        1,
        new Outcome(Outcome.Ending.AWAITING, questionText, 1, 1, "", Pace.NONE, true),
        null,
        () -> {});
    var parked = fixture.current();
    var question = fixture.waitStore().open(goal).orElseThrow().dependency().request();
    assertEquals(State.WAITING, parked.progress().state());
    assertEquals(
        GoalReturnStore.Delivery.PENDING,
        fixture.returnStore().intent(question).orElseThrow().delivery());
    assertThrows(
        NotFoundFault.class,
        () ->
            fixture
                .dedicated()
                .answer(
                    account,
                    new Answer(goal, conversation, question, UUID.randomUUID(), "Too early")));

    var restarted = fixture.scheduler();
    restarted.dispatch();
    restarted.dispatch();
    assertEquals(original, fixture.active().get());
    var items = fixture.humanInbox().list(account, true, 0, 10);
    assertEquals(1, items.size());
    assertEquals("goal.question", items.getFirst().kind());
    assertEquals(conversation, items.getFirst().conversation());
    assertFalse(items.getFirst().answer().contains(questionText));
    assertEquals(
        items.getFirst().id(), fixture.returnStore().intent(question).orElseThrow().receipt());
    assertThrows(
        NotFoundFault.class,
        () ->
            fixture
                .dedicated()
                .answer(
                    account,
                    new Answer(goal, conversation, UUID.randomUUID(), UUID.randomUUID(), "first")));

    assertEquals(State.PAUSED, restarted.control(account, Action.PAUSE, fixture.control()).state());
    var request = new Answer(goal, conversation, question, UUID.randomUUID(), "Release 17.");
    var receipt = fixture.dedicated().answer(account, request);
    assertEquals(receipt, fixture.dedicated().answer(account, request));
    assertThrows(
        CallerFault.class,
        () ->
            fixture
                .dedicated()
                .answer(
                    account,
                    new Answer(goal, conversation, question, request.requestId(), "Release 18.")));
    assertThrows(
        CallerFault.class,
        () ->
            fixture
                .dedicated()
                .answer(
                    account,
                    new Answer(goal, conversation, question, UUID.randomUUID(), "Release 17.")));
    restarted.dispatch();
    assertEquals(original, fixture.active().get());
    assertEquals(
        State.WAITING, restarted.control(account, Action.RESUME, fixture.control()).state());
    restarted.dispatch();
    var active = fixture.current();
    assertEquals(parked.cycle(), active.cycle());
    assertEquals(parked.progress().activations(), active.progress().activations());
    var dependency = fixture.waitStore().open(goal).orElseThrow();
    assertEquals(request.requestId().toString(), dependency.dependency().reference());
    var provenance = GoalTestActivation.provenance(fixture.active().get().goal());
    assertEquals("goal-question:" + question, provenance.sources().getLast().id());
    assertEquals(request.requestId().toString(), provenance.sources().getLast().revision());
    GoalTestActivation.dependencySettled(fixture.active().get().goal());
    finish(fixture.active().get(), active.progress().job(), 2);
    assertEquals(State.COMPLETE, fixture.current().progress().state());
    assertEquals(receipt, fixture.dedicated().answer(account, request));
    restarted.dispatch();
    restarted.dispatch();
    var settled = fixture.humanInbox().list(account, true, 0, 10);
    assertEquals(1, settled.size());
    assertEquals("goal.result", settled.getFirst().kind());
    assertFalse(settled.getFirst().answer().contains("Verified"));
  }

  @Test
  void discovery_pages_exact_owner_rows_and_keeps_immutable_receipts_and_completed_cycles() {
    var store = new JdbcGoalDiscoveryStore(jdbc, BUDGET, LIMITS, InformationLogVisibility.direct());
    var fixture = waitFixture("goal-discovery", ReturnFixture.NONE, store);
    String project = fixture.owner() + "-project";
    var original = fixture.started();
    var statusRequest = new GoalAgents.StatusRequest(project, original.goal());
    var receiptRequest = new GoalAgents.ReceiptRequest(project, original.requestId());
    String sibling = "goal-discovery-sibling";
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES(?,'fixture')", sibling);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,?,'CONTRIBUTOR'),(?,?,'VIEWER')",
        fixture.project(),
        fixture.owner(),
        fixture.project(),
        sibling);
    assertTrue(store.list(sibling, new GoalAgents.Listing(project, null, 1)).goals().isEmpty());
    assertTrue(store.status(sibling, statusRequest).isEmpty());
    assertTrue(store.receipt(sibling, receiptRequest).isEmpty());
    assertEquals(Visibility.UNAVAILABLE, store.visibility(sibling, original.conversation()));
    assertThrows(NotFoundFault.class, () -> store.requireLog(sibling, original.conversation()));

    var scheduler = fixture.scheduler();
    scheduler.dispatch();
    String completedJob = fixture.current().progress().job();
    UUID topicGeneration = UUID.randomUUID();
    UUID cursorGeneration = UUID.randomUUID();
    UUID listener = UUID.randomUUID();
    UUID batch = UUID.randomUUID();
    String topic = "data.discovery";
    String tag = "project:" + fixture.project();
    jdbc.update(
        """
        INSERT INTO relay_topics(scope_key,project_id,name,payload_kind,retention_seconds,
          generation,topic_owner) VALUES(?,?,?,'TEXT',100,?,'GENERAL_PURPOSE')
        """,
        tag,
        fixture.project(),
        topic,
        topicGeneration);
    jdbc.update(
        """
        INSERT INTO relay_subscriptions(scope_key,topic,subscriber,seen_through,seen_at,generation)
        VALUES(?,?,?,0,now(),?)
        """,
        tag,
        topic,
        "goal." + listener,
        cursorGeneration);
    jdbc.update(
        """
        INSERT INTO goal_agent_listeners(id,goal,project_id,topic,generation,cursor_generation)
        VALUES(?,?,?,?,?,?)
        """,
        listener,
        original.goal(),
        fixture.project(),
        topic,
        topicGeneration,
        cursorGeneration);
    long sequence =
        jdbc.queryForObject(
            "SELECT nextval(pg_get_serial_sequence('goal_agent_inputs','sequence'))", Long.class);
    jdbc.update(
        """
        INSERT INTO goal_agent_relay_batches(id,listener,first_sequence,first_at,deadline,member_count)
        VALUES(?,?,?,now(),now()+interval '12 hours',1)
        """,
        batch,
        listener,
        sequence);
    finish(fixture.active().get(), completedJob, 1);
    var idle = fixture.dedicated().status(fixture.owner(), statusRequest);
    assertEquals(State.IDLE, idle.state());
    assertEquals(GoalAgents.Lifecycle.STANDING, idle.lifecycle());
    assertEquals(
        new GoalAgents.Result(completedJob, 1, "Verified", List.of("turn")), idle.latestResult());
    assertEquals(topic, idle.relayListeners().getFirst().topic());
    assertEquals(batch, idle.relayListeners().getFirst().pendingBatch().id());
    assertEquals(original, fixture.dedicated().receipt(fixture.owner(), receiptRequest));

    long source =
        jdbc.queryForObject(
            "INSERT INTO schedule_sources(account,project_id,source) VALUES(?,?,'server') RETURNING id",
            Long.class,
            fixture.owner(),
            fixture.project());
    jdbc.update(
        """
        INSERT INTO schedule_files(source_id,name,internal_name,definition,status)
        VALUES(?,'wake','file_goal_discovery',jsonb_build_object(
          'cron','0 0 * * * *','zone','+01','paused',false,
          'action',jsonb_build_object('kind','agent','agent',?::text),
          'target',jsonb_build_object('kind','message','to',?::text)
        ),'active')
        """,
        source,
        fixture.current().binding().agent(),
        original.instance());
    var nextFire = Instant.parse("2030-01-01T01:00:00Z");
    jdbc.update(
        """
        INSERT INTO schedules(name,cron,zone,emits,next_fire_at,defined_by,file_managed)
        VALUES('file_goal_discovery','0 0 * * * *','+01:00','file_goal_discovery',?, ?, TRUE)
        """,
        Timestamp.from(nextFire),
        fixture.owner());
    var scheduled = fixture.dedicated().status(fixture.owner(), statusRequest);
    assertEquals(nextFire, scheduled.nextScheduleAt());
    assertEquals(nextFire, scheduled.schedules().getFirst().nextFireAt());
    jdbc.update("UPDATE schedules SET zone='UTC' WHERE name='file_goal_discovery'");
    assertNull(fixture.dedicated().status(fixture.owner(), statusRequest).nextScheduleAt());
    jdbc.update("UPDATE schedules SET zone='+01:00' WHERE name='file_goal_discovery'");
    jdbc.update(
        "UPDATE project_members SET role='VIEWER' WHERE project_id=? AND handle=?",
        fixture.project(),
        fixture.owner());
    var noEmitter = fixture.dedicated().status(fixture.owner(), statusRequest);
    assertNull(noEmitter.nextScheduleAt());
    assertEquals(1, noEmitter.schedules().size());
    jdbc.update(
        "UPDATE project_members SET role='CONTRIBUTOR' WHERE project_id=? AND handle=?",
        fixture.project(),
        fixture.owner());

    // A new cycle can clear transient Goal progress without erasing its prior committed result.
    jdbc.update("UPDATE conversation_goals SET detail=NULL WHERE id=?", original.goal());
    assertEquals(
        idle.latestResult(),
        store.status(fixture.owner(), statusRequest).orElseThrow().latestResult());
    var next =
        fixture
            .dedicated()
            .start(
                fixture.owner(),
                new Start(
                    project,
                    fixture.current().binding().agent(),
                    "A newer goal.",
                    null,
                    UUID.randomUUID()));
    try {
      var page =
          fixture.dedicated().list(fixture.owner(), new GoalAgents.Listing(project, null, 1));
      assertEquals(next.goal(), page.next());
      assertEquals(next.goal(), page.goals().getFirst().goal());
      var older =
          fixture
              .dedicated()
              .list(fixture.owner(), new GoalAgents.Listing(project, page.next(), 1));
      assertEquals(original.goal(), older.goals().getFirst().goal());
      assertNull(older.next());
    } finally {
      // The class shares the scheduler's scan. End this untouched fixture before another test
      // can observe it with that test's own mock runtime.
      scheduler.control(
          fixture.owner(),
          Action.CANCEL,
          new Control(next.conversation(), UUID.randomUUID(), next.goal(), next.version(), null));
    }

    var control = fixture.control();
    var pausing = scheduler.control(fixture.owner(), Action.PAUSE, control);
    assertFalse(pausing.objective().isEmpty());
    store.requireDisclosure(fixture.owner(), project, List.of(Disclosure.forStatus(idle)));
    jdbc.update(
        "INSERT INTO information_quarantined_payloads(payload_id,reason) VALUES(?,'fixture')",
        original.conversation());
    assertThrows(
        NotFoundFault.class,
        () ->
            store.requireDisclosure(fixture.owner(), project, List.of(Disclosure.forStatus(idle))));
    var restricted = fixture.dedicated().status(fixture.owner(), statusRequest);
    assertFalse(restricted.readable());
    assertEquals(original.instance(), restricted.instance());
    assertEquals(pausing.version(), restricted.version());
    assertNull(restricted.agent());
    assertNull(restricted.objective());
    assertNull(restricted.latestResult());
    assertTrue(restricted.relayListeners().isEmpty());
    var replay = scheduler.control(fixture.owner(), Action.PAUSE, control);
    assertEquals(pausing.version(), replay.version());
    assertEquals("", replay.agent());
    assertEquals("", replay.objective());
    assertNull(replay.detail());
    assertEquals(
        replay, scheduler.get(fixture.owner(), original.conversation(), control.requestId()));
    assertEquals(Visibility.RESTRICTED, store.visibility(fixture.owner(), original.conversation()));
    assertEquals(original, fixture.dedicated().receipt(fixture.owner(), receiptRequest));
    var cancelled = scheduler.control(fixture.owner(), Action.CANCEL, fixture.control());
    assertEquals(State.CANCELLED, cancelled.state());
    assertEquals("", cancelled.objective());
  }

  @Test
  void completing_a_standing_cycle_does_not_enqueue_a_routine_notice() {
    var fixture = waitFixture("goal-return-standing", true);
    var scheduler = fixture.scheduler();
    scheduler.dispatch();
    jdbc.update(
        "INSERT INTO goal_agent_listeners(id,goal,project_id,topic,generation) VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        fixture.started().goal(),
        fixture.project(),
        "data.fixture",
        UUID.randomUUID());
    finish(fixture.active().get(), fixture.current().progress().job(), 1);
    assertEquals(State.IDLE, fixture.current().progress().state());
    assertTrue(
        fixture.returnStore().pendingAfter(new UUID(0, 0), 100).stream()
            .noneMatch(intent -> intent.route().goal().equals(fixture.started().goal())));
    assertTrue(fixture.humanInbox().list(fixture.owner(), false, 0, 10).isEmpty());
  }

  @Test
  void resuming_before_delivery_retires_the_old_blocker_notice() {
    var fixture = waitFixture("goal-return-stale-blocker", true);
    var scheduler = fixture.scheduler();
    scheduler.dispatch();
    var decision = new Decision(DecisionType.BLOCK, "The target name is unavailable.", List.of());
    GoalTestActivation.settle(
        fixture.active().get().goal(),
        fixture.current().progress().job(),
        1,
        new Outcome(Outcome.Ending.ANSWERED, decision.explanation(), 1, 1, "", Pace.NONE, true),
        decision,
        () -> {});
    assertEquals(State.BLOCKED, fixture.current().progress().state());
    var blocker =
        fixture.returnStore().pendingAfter(new UUID(0, 0), 100).stream()
            .filter(intent -> intent.route().goal().equals(fixture.started().goal()))
            .findFirst()
            .orElseThrow();
    var resumed =
        new Control(
            fixture.started().conversation(),
            UUID.randomUUID(),
            fixture.started().goal(),
            fixture.current().version(),
            2);
    scheduler.control(fixture.owner(), Action.RESUME, resumed);
    scheduler.dispatch();
    assertEquals(
        GoalReturnStore.Delivery.WITHHELD,
        fixture.returnStore().intent(blocker.id()).orElseThrow().delivery());
    assertTrue(fixture.humanInbox().list(fixture.owner(), false, 0, 10).isEmpty());
  }

  @Test
  void withheld_result_delivery_preserves_the_committed_goal_completion() {
    var fixture = waitFixture("goal-return-withheld", true);
    var scheduler = fixture.scheduler();
    scheduler.dispatch();
    finish(fixture.active().get(), fixture.current().progress().job(), 1);
    var pending =
        fixture.returnStore().pendingAfter(new UUID(0, 0), 100).stream()
            .filter(intent -> intent.route().goal().equals(fixture.started().goal()))
            .findFirst()
            .orElseThrow();
    assertEquals(State.COMPLETE, fixture.current().progress().state());
    doThrow(new NotFoundFault("The log is unavailable"))
        .when(fixture.information())
        .requireLog(fixture.started().conversation(), fixture.owner());
    fixture.scheduler().dispatch();
    var result = fixture.returnStore().intent(pending.id()).orElseThrow();
    assertEquals(GoalReturnStore.Delivery.WITHHELD, result.delivery());
    assertEquals(State.COMPLETE, fixture.current().progress().state());
    assertEquals("Verified", fixture.current().progress().detail());
    assertTrue(fixture.humanInbox().list(fixture.owner(), false, 0, 10).isEmpty());
  }

  @Test
  void native_final_answer_commits_with_exact_goal_wait_and_resumes_once() throws Exception {
    var fixture = waitFixture("goal-return-native", ReturnFixture.BOT);
    var goal = fixture.started().goal();
    var home = fixture.bot().admission().home();
    var route = fixture.returnStore().route(goal).orElseThrow().bot();
    var scheduler = fixture.scheduler();
    scheduler.dispatch();
    var original = fixture.active().get();
    var questionText = GoalTestActivation.ask(original.goal(), "Which release?");
    GoalTestActivation.settle(
        original.goal(),
        fixture.current().progress().job(),
        1,
        new Outcome(Outcome.Ending.AWAITING, questionText, 1, 1, "", Pace.NONE, true),
        null,
        () -> {});
    var wait = fixture.waitStore().open(goal).orElseThrow();
    var question = wait.dependency().request();
    var restarted = fixture.scheduler();
    restarted.dispatch();
    var delivered = fixture.returnStore().intent(question).orElseThrow();
    assertEquals(GoalReturnStore.Delivery.DELIVERED, delivered.delivery());
    var context =
        new RunExtras.Context(
            fixture.bot().definition(),
            fixture.bot().conversation(),
            "session",
            mock(Transcript.class),
            home,
            fixture.owner());
    var json =
        fixture
            .messages()
            .send(
                context,
                new SendMessageTool.Request(
                    null, "Release 17.", delivered.receipt(), false, true, "persistent", null),
                home);
    var answer = new ObjectMapper().readTree(json).path("message").asText();
    assertFalse(answer.isBlank(), json);
    assertEquals(answer, fixture.returnStore().intent(question).orElseThrow().answerMessage());
    assertTrue(
        fixture.messages().goalAnswer(route, delivered.receipt()) instanceof GoalAnswerReady);
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM goal_agent_inputs WHERE goal=?", Integer.class, goal));
    restarted.dispatch();
    var resumed = fixture.current();
    assertEquals(wait.origin().cycle(), resumed.cycle().id());
    assertEquals(answer, fixture.waitStore().open(goal).orElseThrow().dependency().reference());
    verify(fixture.information())
        .inherit(fixture.bot().conversation(), fixture.started().conversation(), fixture.owner());
    GoalTestActivation.dependencySettled(fixture.active().get().goal());
    finish(fixture.active().get(), resumed.progress().job(), 2);
    restarted.dispatch();
    var finalMessage = fixture.messages().finalReply(route.message()).orElseThrow();
    assertEquals("Verified", fixture.messages().delivery(finalMessage, fixture.owner()).body());
    assertTrue(fixture.humanInbox().list(fixture.owner(), false, 0, 10).isEmpty());
    restarted.dispatch();
    assertEquals(Optional.of(finalMessage), fixture.messages().finalReply(route.message()));
  }

  private enum ReturnFixture {
    NONE,
    HUMAN,
    BOT
  }

  private WaitFixture waitFixture(String owner) {
    return waitFixture(owner, ReturnFixture.NONE);
  }

  private WaitFixture waitFixture(String owner, boolean withReturns) {
    return waitFixture(owner, withReturns ? ReturnFixture.HUMAN : ReturnFixture.NONE);
  }

  private WaitFixture waitFixture(String owner, ReturnFixture audience) {
    return waitFixture(owner, audience, null);
  }

  private WaitFixture waitFixture(
      String owner, ReturnFixture audience, GoalDiscoveryStore discovery) {
    String project = owner + "-project";
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES(?,'fixture')", owner);
    long projectId =
        jdbc.queryForObject(
            "INSERT INTO projects(name) VALUES(?) RETURNING id", Long.class, project);
    var account =
        new AccountId(
            jdbc.queryForObject(
                "SELECT id FROM authority_accounts WHERE legacy_handle=?", UUID.class, owner));
    var scope =
        new ScopeId(
            jdbc.queryForObject(
                "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, projectId));
    UUID admission = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO operation_admissions(id,executor,executor_handle,scope,project_name,source_account)
        VALUES(?,?,?,?,?,?)
        """,
        admission,
        account.value(),
        owner,
        scope.value(),
        project,
        account.value());
    var authority =
        new Admission(
            admission,
            owner,
            account,
            scope,
            Home.of(project),
            GrantRecipient.account(account),
            List.of(new Support(AuthorityAction.READ, UUID.randomUUID())));
    var identities = mock(IdentityRepository.class);
    when(identities.account(owner))
        .thenReturn(new Account(account, owner, AccountKind.USER, false));
    var repo = new JdbcGoalRepository(jdbc, work, BUDGET, LIMITS);
    var inbox = new JdbcGoalAgentStore(jdbc, work, BUDGET, LIMITS);
    var logs = new ConversationStore(jdbc);
    var allowances = new JdbcAllowanceRepository(jdbc);
    var definition = definition("wait_worker", List.of("file_read"));
    var callerDefinition =
        new AgentDefinition(
            "wait_caller",
            "Caller",
            "model",
            Sampling.Intent.BALANCED,
            Sampling.NONE,
            List.of(SendMessageTool.NAME),
            List.of(),
            List.of(),
            4,
            8,
            "Handle the request.",
            true,
            false,
            false,
            true);
    var messages =
        new RelayMessaging(
            new JdbcMessagingRepository(jdbc, allowances),
            allowances,
            logs,
            mock(FiringStore.class),
            mock(MessageInboxes.class),
            work,
            mock(Voice.class),
            (name, target) -> name.equals(callerDefinition.name()) ? callerDefinition : definition,
            ignored -> {},
            Instant::now);
    messages.useGoalScheduleTransactions(BUDGET.transactions(work, LIMITS.queryCeiling()));
    var properties = new GoalProperties(true, 1, 4, 1, 8, 2, 1, Duration.ofMillis(1));
    var runtime = mock(GoalExecution.class);
    var retained = new AtomicReference<Prepared>();
    Function<InstanceView, Prepared> prepare =
        instance -> {
          var prepared =
              new Prepared(
                  logs.find(instance.conversation()).orElseThrow(),
                  definition,
                  authority,
                  allowances.bind(Budget.of(1)));
          retained.set(prepared);
          return prepared;
        };
    when(runtime.prepareDedicated(eq(owner), any()))
        .thenAnswer(call -> prepare.apply(call.getArgument(1)));
    when(runtime.prepareHandoff(any(), any(), eq(definition)))
        .thenAnswer(call -> prepare.apply(call.getArgument(1)));
    when(runtime.handoffTarget(any(), eq(definition.name()))).thenReturn(definition);
    when(runtime.restore(any())).thenAnswer(call -> retained.get());
    when(runtime.conversation(anyString()))
        .thenAnswer(call -> logs.find(call.getArgument(0)).orElseThrow());
    when(runtime.statusBudget(any(), any())).thenAnswer(call -> retained.get().budget());
    when(runtime.idle(anyString())).thenReturn(true);
    var active = new AtomicReference<RunAdmission>();
    var prepared = new AtomicReference<Prepared>();
    when(runtime.start(any(), any(), anyString(), any()))
        .thenAnswer(
            call -> {
              String job = "job_" + UUID.randomUUID().toString().replace("-", "");
              var run = call.<RunAdmission>getArgument(3);
              run.started().accept(job);
              active.set(run);
              prepared.set(call.getArgument(1));
              return Optional.of(job);
            });
    var waitStore = new JdbcGoalWaitStore(jdbc, BUDGET, LIMITS);
    var returnStore = new JdbcGoalReturnStore(jdbc, BUDGET, LIMITS);
    var humanInbox = new Inbox(new JdbcInboxStore(jdbc), AccountPushes.NONE, Instant::now);
    var information = mock(InformationJobs.class);
    var returns =
        audience != ReturnFixture.NONE
            ? new GoalReturns(
                returnStore, repo, inbox, waitStore, runtime, humanInbox, messages, information)
            : null;
    var dedicated =
        new DedicatedGoalAgents(
            inbox,
            repo,
            runtime,
            messages,
            identities,
            mock(GoalRelaySources.class),
            mock(Relay.class),
            properties,
            new JdbcRelayRetirementRepository(jdbc, work),
            Instant::now,
            returns,
            discovery);
    messages.useGoalInbox(dedicated);
    GoalHandoffs.Caller bot = null;
    if (audience == ReturnFixture.BOT) {
      var caller =
          messages.open(
              owner, project, callerDefinition.name(), false, UUID.randomUUID().toString());
      var source =
          messages.open(owner, project, definition.name(), false, UUID.randomUUID().toString());
      var context =
          new RunExtras.Context(
              definition,
              source.conversation(),
              "session",
              mock(Transcript.class),
              Home.of(project),
              owner);
      var opening =
          messages.send(
              context,
              new SendMessageTool.Request(
                  caller.id(), "Hand off the review.", null, false, false, "persistent", null),
              Home.of(project));
      String message =
          jdbc.queryForObject(
              "SELECT message FROM message_routes WHERE recipient=?", String.class, caller.id());
      assertTrue(opening.contains(message), opening);
      bot =
          new GoalHandoffs.Caller(
              caller.conversation(), callerDefinition, authority, false, message);
    }
    var request =
        new Start(
            project,
            definition.name(),
            "Review the released artifact.",
            null,
            UUID.randomUUID(),
            bot != null);
    var goal = bot == null ? dedicated.start(owner, request) : dedicated.start(bot, request);
    var approvals = new RunApprovalStore(jdbc, Instant::now);
    var calls =
        new JdbcSuspendedRunCalls(
            jdbc,
            BUDGET,
            LIMITS.queryCeiling(),
            new JdbcToolCallTraces(jdbc, BUDGET, LIMITS.queryCeiling()));
    var orchestrations = mock(GoalOrchestrations.class);
    var waits =
        new GoalWaits(
            waitStore,
            repo,
            inbox,
            calls,
            approval -> approvals.deny(approval, RunApproval.RUN_ENDED),
            orchestrations,
            information,
            returns);
    return new WaitFixture(
        owner,
        projectId,
        goal,
        repo,
        runtime,
        dedicated,
        properties,
        approvals,
        calls,
        waitStore,
        waits,
        orchestrations,
        active,
        prepared,
        returnStore,
        humanInbox,
        information,
        messages,
        bot);
  }

  private record WaitFixture(
      String owner,
      long project,
      GoalAgents.Started started,
      GoalRepository repository,
      GoalExecution runtime,
      DedicatedGoalAgents dedicated,
      GoalProperties properties,
      RunApprovalStore approvals,
      SuspendedRunCalls calls,
      GoalWaitStore waitStore,
      GoalWaits waits,
      GoalOrchestrations orchestrations,
      AtomicReference<RunAdmission> active,
      AtomicReference<Prepared> prepared,
      GoalReturnStore returnStore,
      Inbox humanInbox,
      InformationJobs information,
      RelayMessaging messages,
      GoalHandoffs.Caller bot) {
    GoalService scheduler() {
      return new GoalService(repository, runtime, properties, dedicated, waits);
    }

    GoalRepository.Goal current() {
      return repository.find(started.goal()).orElseThrow();
    }

    Control control() {
      return new Control(
          started.conversation(), UUID.randomUUID(), started.goal(), current().version(), null);
    }

    RunApproval approval() {
      return approvals.askFile(
          project,
          started.conversation(),
          started.conversation(),
          owner,
          current().binding().agent(),
          "local",
          "project",
          "Review the original file read.",
          new FileApproval(
              ApprovalPolicy.Kind.FILE_READ,
              "project",
              List.of("/project/notes.md"),
              "0".repeat(64),
              null));
    }

    void parkApproval(RunApproval approval) {
      var request = new ToolCall("read-notes", "file_read", "{\"path\":\"notes.md\"}");
      String job = current().progress().job();
      int answer =
          new EntryStore(jdbc, work)
              .append(started.conversation(), 1, LoggedEntry.answer("", List.of(request)), job)
              .ordinal();
      GoalTestActivation.retainApproval(
          active.get().goal(),
          approval.id(),
          1,
          answer,
          request.id(),
          () ->
              calls.retainAt(
                  approval.id(), started.conversation(), null, "local", owner, request, answer));
      settleAwaiting();
    }

    void settleAwaiting() {
      GoalTestActivation.settle(
          active.get().goal(),
          current().progress().job(),
          1,
          new Outcome(
              Outcome.Ending.AWAITING,
              "Waiting for the retained dependency.",
              1,
              1,
              "",
              Pace.NONE,
              true),
          null,
          () -> {});
    }
  }

  private static AgentDefinition definition(String name, List<String> tools) {
    return new AgentDefinition(
        name, name, "model", tools, List.of(), List.of(), 4, 8, "Review the project.", true, true);
  }

  private static void finish(RunAdmission invocation, String job, int ordinal) {
    var outcome = new Outcome(Outcome.Ending.ANSWERED, "Verified", 1, 1, "", Pace.NONE, true);
    var decision = new Decision(DecisionType.COMPLETE, "Verified", List.of("turn"));
    GoalTestActivation.settle(invocation.goal(), job, ordinal, outcome, decision, () -> {});
  }
}
