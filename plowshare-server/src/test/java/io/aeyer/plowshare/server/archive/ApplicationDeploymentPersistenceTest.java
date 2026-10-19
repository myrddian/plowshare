package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ApplicationDeployment.*;
import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.acting.*;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.applications.SourceApplicationDeployments;
import io.aeyer.plowshare.server.events.ScheduleDefinitionStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.ConfiguredFileStores;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** SQL constraints, placement/receipt atomicity, row locks and restart reads require PostgreSQL. */
@Tag("full-db")
@Testcontainers
class ApplicationDeploymentPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  static JdbcTemplate jdbc;
  @TempDir Path directory;
  ConfiguredFileStores files;
  ProjectStore projects;
  UnitOfWork work;
  ScheduleDefinitionStore schedules;
  ApplicationDeploymentStore store;
  SourceApplicationDeployments deployments;
  String name;

  private ActingAuthorization actingPolicy(ActingRepository repository) {
    var identities = new JdbcIdentityRepository(jdbc);
    var grants = new JdbcScopedGrantRepository(jdbc);
    var applications =
        new io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy(
            projects, new JdbcApplicationRegistrations(jdbc));
    var lineage = new ApplicationGrantLineage(grants, applications);
    return new LiveActingAuthorization(
        repository,
        new JdbcProjectMembers(jdbc),
        java.time.Clock.systemUTC(),
        identities,
        new LiveScopedAuthority(grants, applications, lineage, identities));
  }

  @BeforeAll
  static void migrate() {
    source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    jdbc = new JdbcTemplate(source);
    Flyway.configure().dataSource(source).load().migrate();
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES ('deployer','fixture',TRUE)");
  }

  @BeforeEach
  void setup() throws Exception {
    name = "app-" + UUID.randomUUID();
    Path root = Files.createDirectory(directory.resolve("sources"));
    Path config = directory.resolve("filestore.js");
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    Files.writeString(
        config,
        "export default "
            + json.writeValueAsString(
                Map.of(
                    "version",
                    1,
                    "defaultStore",
                    "applications",
                    "fileStores",
                    Map.of(
                        "applications",
                        Map.of(
                            "root",
                            root.toString(),
                            "access",
                            Map.of(
                                "accounts",
                                List.of(Map.of("handle", "deployer", "role", "MANAGER")))))))
            + ";");
    files = new ConfiguredFileStores(config.toString());
    projects =
        new ProjectStore(
            jdbc,
            directory.resolve("server.yml"),
            directory.resolve("sampling"),
            null,
            null,
            null,
            files);
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    schedules = mock(ScheduleDefinitionStore.class);
    restart();
  }

  @AfterEach
  void close() {
    files.close();
  }

  void restart() {
    store = new JdbcApplicationDeploymentStore(jdbc, work, projects, schedules);
    deployments =
        new SourceApplicationDeployments(
            store, new JdbcProjectMembers(jdbc), projects, files, (project, root) -> {});
  }

  Deploy request(UUID id, UUID expected, String text) {
    return new Deploy(
        name,
        id,
        expected,
        new FileStoreReference("applications", name),
        List.of(),
        List.of(
            new File(
                "plowshare.json",
                "{\"version\":1,\"name\":\""
                    + name
                    + "\",\"access\":{\"accounts\":[{\"handle\":\"deployer\",\"role\":\"MANAGER\"}]}}"),
            new File("README.md", text)));
  }

  @Test
  void deploy_update_rollback_and_restart_keep_identity_and_conversations() {
    var initial = deployments.deploy("deployer", request(UUID.randomUUID(), null, "one"));
    Long id = projects.id(name);
    var conversations = new ConversationStore(jdbc);
    var conversation = conversations.open(Home.of(name), Budget.of(20));
    var updated =
        deployments.deploy(
            "deployer", request(UUID.randomUUID(), initial.release().revision(), "two"));
    assertEquals(id, projects.id(name));
    assertEquals(
        id,
        jdbc.queryForObject(
            "SELECT project_id FROM conversations WHERE id = ?", Long.class, conversation.id()));
    restart();
    assertEquals(
        updated.release().revision(), deployments.status("deployer", name).activeRevision());
    assertEquals(initial, deployments.receipt("deployer", name, initial.requestId()));
    var rolled =
        deployments.activate(
            "deployer",
            new Activate(
                name,
                UUID.randomUUID(),
                updated.release().revision(),
                initial.release().revision()));
    assertEquals(initial.release(), rolled.release());
    assertEquals(
        initial.release().revision(), deployments.status("deployer", name).activeRevision());
    assertTrue(
        projects
            .find(name)
            .orElseThrow()
            .workspace()
            .endsWith(initial.release().revision().toString()));
    verify(schedules, times(1)).register("deployer", id, "server");
  }

  @Test
  void source_updates_do_not_recreate_removed_membership() {
    var first = deployments.deploy("deployer", request(UUID.randomUUID(), null, "one"));
    Long id = projects.id(name);
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM project_members WHERE project_id = ?", Integer.class, id));
    jdbc.update("DELETE FROM project_members WHERE project_id = ?", id);
    var second =
        deployments.deploy(
            "deployer", request(UUID.randomUUID(), first.release().revision(), "two"));
    assertEquals(
        second.release().revision(), deployments.status("deployer", name).activeRevision());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM project_members WHERE project_id = ?", Integer.class, id));
  }

  @Test
  void identical_receipt_survives_source_removal_but_uuid_cannot_change_payload() throws Exception {
    var request = request(UUID.randomUUID(), null, "one");
    var committed = deployments.deploy("deployer", request);
    Files.delete(projects.find(name).orElseThrow().workspace().resolve("plowshare.json"));
    restart();
    assertEquals(committed, deployments.deploy("deployer", request));
    assertThrows(
        CallerFault.class,
        () -> deployments.deploy("deployer", request(request.requestId(), null, "changed")));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM application_releases WHERE project_id = ?",
            Integer.class,
            projects.id(name)));
  }

  @Test
  void concurrent_updates_compare_the_reviewed_head_under_the_project_lock() throws Exception {
    var initial = deployments.deploy("deployer", request(UUID.randomUUID(), null, "one"));
    var left = request(UUID.randomUUID(), initial.release().revision(), "left");
    var right = request(UUID.randomUUID(), initial.release().revision(), "right");
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (var request : List.of(left, right))
        results.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  try {
                    deployments.deploy("deployer", request);
                    return true;
                  } catch (CallerFault refused) {
                    return false;
                  }
                }));
      int succeeded = 0;
      for (var result : results) if (result.get(20, TimeUnit.SECONDS)) succeeded++;
      assertEquals(1, succeeded);
    }
    assertEquals(2, deployments.status("deployer", name).releases().size());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM application_deployment_receipts WHERE project_id = ?",
            Integer.class,
            projects.id(name)));
  }

  @Test
  void failed_schedule_enrollment_rolls_back_project_placement_and_receipt() {
    doThrow(new CallerFault("Enrollment failed"))
        .when(schedules)
        .register(eq("deployer"), anyLong(), eq("server"));
    UUID id = UUID.randomUUID();
    assertThrows(CallerFault.class, () -> deployments.deploy("deployer", request(id, null, "one")));
    assertTrue(projects.find(name).isEmpty());
    assertNull(deployments.status("deployer", name).activeRevision());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM application_deployment_receipts WHERE request_id = ?",
            Integer.class,
            id));
  }

  private io.aeyer.plowshare.server.applications.ApplicationIdentities ownedLifecycle() {
    return ownedLifecycle(java.time.Clock.systemUTC());
  }

  private io.aeyer.plowshare.server.applications.ApplicationIdentities ownedLifecycle(
      java.time.Clock clock) {
    var identities =
        new io.aeyer.plowshare.server.applications.OwnedApplicationIdentities(
            new io.aeyer.plowshare.server.applications.JdbcApplicationIdentityRepository(jdbc),
            new io.aeyer.plowshare.server.auth.JdbcServiceAccountRepository(jdbc),
            new io.aeyer.plowshare.server.auth.AdminStore(jdbc),
            new JdbcProjectMembers(jdbc),
            projects,
            work,
            event -> {},
            clock);
    store =
        new JdbcApplicationDeploymentStore(
            jdbc, work, projects, schedules, identities, actingRepository());
    deployments =
        new SourceApplicationDeployments(
            store,
            new JdbcProjectMembers(jdbc),
            projects,
            files,
            (project, root) -> {},
            identities);
    return identities;
  }

  private Deploy ownedRequest(UUID requestId, UUID expected, String handle) {
    var original = request(requestId, expected, "native source");
    String manifest = original.files().getFirst().text();
    manifest =
        manifest.substring(0, manifest.length() - 1)
            + ",\"service\":{\"handle\":\""
            + handle
            + "\",\"role\":\"CONTRIBUTOR\"}}";
    return new Deploy(
        name,
        requestId,
        expected,
        original.destination(),
        original.writableAreas(),
        List.of(new File("plowshare.json", manifest), original.files().get(1)));
  }

  private JdbcActingRepository actingRepository() {
    return new JdbcActingRepository(
        jdbc,
        new io.aeyer.plowshare.server.applications.JdbcApplicationIdentityRepository(jdbc),
        () -> true);
  }

  private Deploy actingRequest(UUID expected, String handle, String ceiling) {
    var original = ownedRequest(UUID.randomUUID(), expected, handle);
    String manifest = original.files().getFirst().text();
    manifest =
        manifest.substring(0, manifest.length() - 1)
            + ",\"acting\":[{\"grant\":\"users\",\"ceiling\":\""
            + ceiling
            + "\",\"personal\":true,\"proofs\":[\"acting-token\"]}]}";
    return new Deploy(
        name,
        original.requestId(),
        expected,
        original.destination(),
        original.writableAreas(),
        List.of(new File("plowshare.json", manifest), original.files().get(1)));
  }

  private io.aeyer.plowshare.server.applications.ApplicationIdentities actingFixture() {
    var identities = ownedLifecycle();
    String handle = "acting-" + UUID.randomUUID();
    deployments.deploy("deployer", actingRequest(null, handle, "CONTRIBUTOR"));
    identities.issue(
        "deployer",
        new io.aeyer.plowshare.protocol.ApplicationIdentity.Issue(
            name, UUID.randomUUID(), 30, false));
    actingTokens()
        .eligibility(
            new RequestAuthority.Direct("deployer"),
            new ActingTokens.Eligibility(
                name, "users", Set.of("deployer"), Set.of(), UUID.randomUUID()));
    return identities;
  }

  private ActingTokens actingTokens() {
    return new OwnedActingTokens(
        actingRepository(), new JdbcProjectMembers(jdbc), work, java.time.Clock.systemUTC());
  }

  private ActingTokens.Issue actingIssue(UUID requestId) {
    return new ActingTokens.Issue(
        name,
        "users",
        new ActingScope(ProjectRole.CONTRIBUTOR, Set.of(name), false),
        7,
        Optional.of("external fixture"),
        requestId);
  }

  @Test
  void actingPersonalTurnReceiptsRoundTripAndSurviveForgottenConversation() {
    var identities = actingFixture();
    String personalName = io.aeyer.plowshare.server.personal.PersonalSpaces.name("deployer");
    jdbc.update("INSERT INTO projects(name) VALUES (?) ON CONFLICT DO NOTHING", personalName);
    Long personalId =
        jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, personalName);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,'deployer','MANAGER') ON CONFLICT DO NOTHING",
        personalId);
    var token =
        actingTokens()
            .issue(
                new RequestAuthority.Direct("deployer"),
                new ActingTokens.Issue(
                    name,
                    "users",
                    new ActingScope(ProjectRole.CONTRIBUTOR, Set.of(), true),
                    7,
                    Optional.empty(),
                    UUID.randomUUID()))
            .token();
    var authority =
        new RequestAuthority.Delegated(
            token.actor(),
            token.subject(),
            new RequestAuthority.Delegation(name, "users", token.id(), token.scope()));
    var consent = actingRepository();
    var repository =
        new JdbcActingWorkRepository(
            jdbc, new ConversationStore(jdbc), consent, new DataSourceTransactionManager(source));
    var policy = actingPolicy(consent);
    var definition =
        new io.aeyer.plowshare.server.agents.AgentDefinition(
            "chat", "chat", "fast", List.of(), List.of(), List.of(), 4, 8, "Answer", true, false);
    var count = new java.util.concurrent.atomic.AtomicInteger();
    ActingTurns executor =
        (conversation, agent, task, admission) -> {
          count.incrementAndGet();
          admission.check().run();
          admission.started().accept("job_" + UUID.randomUUID().toString().replace("-", ""));
        };
    var service =
        new PersonalActingWork(
            repository,
            policy,
            (subject, agent) -> definition,
            executor,
            work,
            10,
            mock(io.aeyer.plowshare.server.access.ResourceScopeRepository.class));
    var request =
        new ActingWork.Start(
            UUID.randomUUID(), "chat", "Private task must not reach receipt", null);
    var receipt = service.start(authority, request);
    assertEquals(ActingWork.State.STARTED, receipt.state());
    assertEquals(authority, repository.binding(receipt.conversation()).orElseThrow().authority());
    assertEquals(
        "deployer", new ConversationStore(jdbc).ownerOf(receipt.conversation()).orElseThrow());
    var restarted =
        new JdbcActingWorkRepository(
            jdbc, new ConversationStore(jdbc), consent, new DataSourceTransactionManager(source));
    assertEquals(
        receipt, restarted.receipt(authority.actor(), request.requestId()).orElseThrow().receipt());
    assertEquals(receipt, service.start(authority, request));
    assertEquals(1, count.get());
    identities.issue(
        "deployer",
        new io.aeyer.plowshare.protocol.ApplicationIdentity.Issue(
            name, UUID.randomUUID(), 30, true));
    service.requireJob(authority, receipt.job(), ActingPermission.READ);
    assertEquals(
        authority.actor(),
        restarted.job(receipt.job()).orElseThrow().binding().authority().actor());

    assertThrows(
        IllegalStateException.class,
        () -> repository.started(authority.actor(), request.requestId(), "job_ANOTHER"));
    repository.finished(authority.actor(), request.requestId());
    repository.uncertain(authority.actor(), request.requestId());
    assertEquals(
        ActingWork.State.FINISHED, service.receipt(authority, request.requestId()).state());

    actingTokens().revoke(new RequestAuthority.Direct("deployer"), token.id(), UUID.randomUUID());
    assertThrows(
        CallerFault.class,
        () -> service.requireJob(authority, receipt.job(), ActingPermission.READ));
    // A physical history deletion must not erase idempotency or restore a used request UUID.
    jdbc.update("DELETE FROM conversations WHERE id=?", receipt.conversation());
    assertTrue(restarted.receipt(authority.actor(), request.requestId()).isPresent());
    assertFalse(restarted.owns(restarted.binding(receipt.conversation()).orElseThrow()));
    assertThrows(CallerFault.class, () -> service.start(authority, request));
    assertEquals(1, count.get());
  }

  @Test
  void actingPersonalConcurrentAdmissionAndRollbackUseOneReceipt() throws Exception {
    actingFixture();
    String personalName = io.aeyer.plowshare.server.personal.PersonalSpaces.name("deployer");
    jdbc.update("INSERT INTO projects(name) VALUES (?) ON CONFLICT DO NOTHING", personalName);
    Long personalId =
        jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, personalName);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,'deployer','MANAGER') ON CONFLICT DO NOTHING",
        personalId);
    var token =
        actingTokens()
            .issue(
                new RequestAuthority.Direct("deployer"),
                new ActingTokens.Issue(
                    name,
                    "users",
                    new ActingScope(ProjectRole.CONTRIBUTOR, Set.of(), true),
                    7,
                    Optional.empty(),
                    UUID.randomUUID()))
            .token();
    var authority =
        new RequestAuthority.Delegated(
            token.actor(),
            token.subject(),
            new RequestAuthority.Delegation(name, "users", token.id(), token.scope()));
    var repository =
        new JdbcActingWorkRepository(
            jdbc,
            new ConversationStore(jdbc),
            actingRepository(),
            new DataSourceTransactionManager(source));
    var policy = actingPolicy(actingRepository());
    var definition =
        new io.aeyer.plowshare.server.agents.AgentDefinition(
            "chat", "chat", "fast", List.of(), List.of(), List.of(), 4, 8, "Answer", true, false);
    var submissions = new java.util.concurrent.atomic.AtomicInteger();
    var service =
        new PersonalActingWork(
            repository,
            policy,
            (subject, agent) -> definition,
            (conversation, agent, task, admission) -> {
              submissions.incrementAndGet();
              admission.started().accept("job_" + UUID.randomUUID().toString().replace("-", ""));
            },
            work,
            10,
            mock(io.aeyer.plowshare.server.access.ResourceScopeRepository.class));
    var request = new ActingWork.Start(UUID.randomUUID(), "chat", "Hello", null);
    CountDownLatch ready = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          threads.submit(
              () -> {
                ready.await();
                return service.start(authority, request);
              });
      var second =
          threads.submit(
              () -> {
                ready.await();
                return service.start(authority, request);
              });
      ready.countDown();
      assertEquals(
          first.get(10, TimeUnit.SECONDS).conversation(),
          second.get(10, TimeUnit.SECONDS).conversation());
    }
    assertEquals(1, submissions.get());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM acting_work_receipts WHERE actor=? AND request_id=?",
            Integer.class,
            authority.actor(),
            request.requestId()));
    UUID rolledBack = UUID.randomUUID();
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  service.start(
                      authority, new ActingWork.Start(rolledBack, "chat", "Rollback", null));
                  throw new IllegalStateException("rollback");
                }));
    assertTrue(repository.receipt(authority.actor(), rolledBack).isEmpty());
    assertEquals(1, submissions.get(), "outer transaction rollback must not enqueue work");
  }

  @Test
  void actingProofExchangeAuditContainsOnlyMetadata() {
    actingFixture();
    var caller = new RequestAuthority.Direct("deployer");
    var credential = actingTokens().issue(caller, actingIssue(UUID.randomUUID()));
    var repository = actingRepository();
    assertEquals(
        credential.token(),
        repository
            .proof(io.aeyer.plowshare.server.auth.Tokens.hash(credential.credential()))
            .orElseThrow());
    var policy = actingPolicy(repository);
    var contexts = new BoundActingContexts(repository, policy, java.time.Clock.systemUTC());
    var connection =
        new ActingContexts.Connection(
            "fixture-connection", "fixture-session", credential.token().actor());
    var request =
        new io.aeyer.plowshare.protocol.ActingContext.Open(
            name,
            "users",
            new io.aeyer.plowshare.protocol.ActingContext.Proof(
                "acting-token", credential.credential()));
    var opened = contexts.open(connection, request);
    assertEquals("deployer", opened.subject());
    assertThrows(
        CallerFault.class,
        () ->
            contexts.open(
                connection,
                new io.aeyer.plowshare.protocol.ActingContext.Open(
                    name,
                    "users",
                    new io.aeyer.plowshare.protocol.ActingContext.Proof(
                        "acting-token", "psa_" + "0".repeat(48)))));
    contexts.close(connection, opened.context());
    assertEquals(
        List.of("OPEN", "REFUSED", "CLOSE"),
        jdbc.queryForList(
            "SELECT action FROM acting_context_audit WHERE application=? ORDER BY id",
            String.class,
            name));
    for (String row :
        jdbc.queryForList(
            "SELECT row_to_json(a)::text FROM acting_context_audit a WHERE application=?",
            String.class,
            name)) {
      assertFalse(row.contains(credential.credential()));
      assertFalse(
          row.contains(io.aeyer.plowshare.server.auth.Tokens.hash(credential.credential())));
    }
  }

  @Test
  void actingDeclarationsFollowActivationNarrowingAndRollback() {
    var identities = actingFixture();
    var repository = actingRepository();
    var caller = new RequestAuthority.Direct("deployer");
    var issued = actingTokens().issue(caller, actingIssue(UUID.randomUUID()));
    var authority =
        new RequestAuthority.Delegated(
            issued.token().actor(),
            "deployer",
            new RequestAuthority.Delegation(
                name, "users", issued.token().id(), issued.token().scope()));
    var policy = actingPolicy(repository);
    assertEquals(
        ProjectRole.CONTRIBUTOR,
        policy.require(authority, ActingPermission.WORK, new ActingTarget.Project(name)));
    var initial = deployments.status("deployer", name).activeRevision();
    String handle = identities.status("deployer", name).identity().service().handle();
    var lower = deployments.deploy("deployer", actingRequest(initial, handle, "VIEWER"));
    assertEquals(ProjectRole.VIEWER, repository.grant(name, "users").orElseThrow().ceiling());
    assertThrows(
        CallerFault.class,
        () -> policy.require(authority, ActingPermission.WORK, new ActingTarget.Project(name)));
    var removed =
        deployments.deploy(
            "deployer", ownedRequest(UUID.randomUUID(), lower.release().revision(), handle));
    assertTrue(repository.grant(name, "users").isEmpty());
    assertThrows(
        CallerFault.class,
        () -> policy.require(authority, ActingPermission.READ, new ActingTarget.Project(name)));
    deployments.activate(
        "deployer",
        new Activate(
            name, UUID.randomUUID(), removed.release().revision(), lower.release().revision()));
    assertEquals(ProjectRole.VIEWER, repository.grant(name, "users").orElseThrow().ceiling());
    assertEquals(
        ProjectRole.VIEWER,
        policy.require(authority, ActingPermission.READ, new ActingTarget.Project(name)));
    assertThrows(
        CallerFault.class,
        () -> policy.require(authority, ActingPermission.WORK, new ActingTarget.Project(name)));
  }

  @Test
  void actingIssueReceiptRestartAndRevocationNeverRetainSecret() {
    var identities = actingFixture();
    var caller = new RequestAuthority.Direct("deployer");
    var issue = actingIssue(UUID.randomUUID());
    var credential = actingTokens().issue(caller, issue);
    var repository = actingRepository();
    assertEquals(credential.token(), repository.token(credential.token().id()).orElseThrow());
    assertEquals(List.of(credential.token()), repository.tokens("deployer"));
    assertTrue(repository.tokens("foreign").isEmpty());
    assertEquals(
        credential.token(),
        repository
            .proof(io.aeyer.plowshare.server.auth.Tokens.hash(credential.credential()))
            .orElseThrow());
    var rotated =
        identities.issue(
            "deployer",
            new io.aeyer.plowshare.protocol.ApplicationIdentity.Issue(
                name, UUID.randomUUID(), 30, true));
    assertEquals(credential.token().actor(), "@service/" + rotated.receipt().tokenId());
    var authority =
        new RequestAuthority.Delegated(
            credential.token().actor(),
            "deployer",
            new RequestAuthority.Delegation(
                name, "users", credential.token().id(), credential.token().scope()));
    var policy = actingPolicy(repository);
    assertEquals(
        ProjectRole.CONTRIBUTOR,
        policy.require(authority, ActingPermission.WORK, new ActingTarget.Project(name)));
    assertEquals(
        credential.receipt(), actingTokens().receipt(caller, issue.requestId()).orElseThrow());
    assertEquals(
        io.aeyer.plowshare.server.auth.Tokens.hash(credential.credential()),
        jdbc.queryForObject(
            "SELECT digest FROM acting_tokens WHERE id=?", String.class, credential.token().id()));
    String stored =
        jdbc.queryForObject(
            "SELECT row_to_json(t)::text FROM acting_tokens t WHERE id=?",
            String.class,
            credential.token().id());
    assertFalse(stored.contains(credential.credential()));
    assertTrue(
        new io.aeyer.plowshare.server.auth.JdbcServiceCredentialsRepository(
                jdbc, java.time.Clock.systemUTC())
            .authentication(credential.credential())
            .isEmpty());
    assertThrows(CallerFault.class, () -> actingTokens().issue(caller, issue));
    UUID revoke = UUID.randomUUID();
    var receipt = actingTokens().revoke(caller, credential.token().id(), revoke);
    assertEquals(receipt, actingTokens().revoke(caller, credential.token().id(), revoke));
    assertTrue(repository.token(credential.token().id()).orElseThrow().revoked());
    assertThrows(
        CallerFault.class,
        () -> policy.require(authority, ActingPermission.READ, new ActingTarget.Project(name)));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM acting_audit WHERE request_id=?", Integer.class, revoke));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE acting_tokens SET digest='plaintext' WHERE id=?", credential.token().id()));
  }

  @Test
  void actingEligibilityReplacementAndMutationFailuresRollBack() {
    actingFixture();
    var repository = actingRepository();
    var caller = new RequestAuthority.Direct("deployer");
    String membershipProject = "eligibility-" + UUID.randomUUID();
    jdbc.update("INSERT INTO projects(name) VALUES (?)", membershipProject);
    Long membershipId =
        jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, membershipProject);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,'deployer','VIEWER')",
        membershipId);
    actingTokens()
        .eligibility(
            caller,
            new ActingTokens.Eligibility(
                name, "users", Set.of(), Set.of(membershipProject), UUID.randomUUID()));
    assertTrue(repository.eligible(name, "users", "deployer"));
    jdbc.update("DELETE FROM project_members WHERE project_id=?", membershipId);
    assertFalse(repository.eligible(name, "users", "deployer"));
    UUID failed = UUID.randomUUID();
    assertThrows(
        CallerFault.class,
        () ->
            actingTokens()
                .eligibility(
                    caller,
                    new ActingTokens.Eligibility(
                        name, "users", Set.of("deployer"), Set.of("missing-project"), failed)));
    assertTrue(actingTokens().receipt(caller, failed).isEmpty());
    assertFalse(repository.eligible(name, "users", "deployer"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM acting_eligible_projects WHERE application=?",
            Integer.class,
            name));
    actingTokens()
        .eligibility(
            caller,
            new ActingTokens.Eligibility(
                name, "users", Set.of("deployer"), Set.of(), UUID.randomUUID()));
    var before = actingTokens().tokens(caller).size();
    var failureRepository = spy(repository);
    doThrow(new CallerFault("fixture receipt failure")).when(failureRepository).complete(any());
    var service =
        new OwnedActingTokens(
            failureRepository, new JdbcProjectMembers(jdbc), work, java.time.Clock.systemUTC());
    var issue = actingIssue(UUID.randomUUID());
    assertThrows(CallerFault.class, () -> service.issue(caller, issue));
    assertEquals(before, actingTokens().tokens(caller).size());
    assertTrue(repository.receipt(issue.requestId()).isEmpty());
  }

  @Test
  void actingProjectConsentCannotFollowARecreatedProjectName() {
    var identities = actingFixture();
    var binding = identities.status("deployer", name).identity();
    String scopeProject = "scope-" + UUID.randomUUID();
    jdbc.update("INSERT INTO projects(name) VALUES (?)", scopeProject);
    Long oldId =
        jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, scopeProject);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,?,'CONTRIBUTOR')",
        oldId,
        binding.service().handle());
    jdbc.update(
        "INSERT INTO service_token_scopes(token_id,project_id,role) VALUES (?,?,'CONTRIBUTOR')",
        binding.tokenId(),
        oldId);
    var issue =
        new ActingTokens.Issue(
            name,
            "users",
            new ActingScope(ProjectRole.CONTRIBUTOR, Set.of(scopeProject), false),
            7,
            Optional.empty(),
            UUID.randomUUID());
    var credential = actingTokens().issue(new RequestAuthority.Direct("deployer"), issue);
    var authority =
        new RequestAuthority.Delegated(
            binding.principal(),
            "deployer",
            new RequestAuthority.Delegation(name, "users", credential.token().id(), issue.scope()));
    var policy = actingPolicy(actingRepository());
    assertEquals(
        ProjectRole.CONTRIBUTOR,
        policy.require(authority, ActingPermission.WORK, new ActingTarget.Project(scopeProject)));
    jdbc.update("DELETE FROM service_token_scopes WHERE project_id=?", oldId);
    jdbc.update("DELETE FROM project_members WHERE project_id=?", oldId);
    jdbc.update("DELETE FROM projects WHERE id=?", oldId);
    jdbc.update("INSERT INTO projects(name) VALUES (?)", scopeProject);
    Long newId =
        jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, scopeProject);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,?,'CONTRIBUTOR')",
        newId,
        binding.service().handle());
    jdbc.update(
        "INSERT INTO service_token_scopes(token_id,project_id,role) VALUES (?,?,'CONTRIBUTOR')",
        binding.tokenId(),
        newId);
    assertNotEquals(oldId, newId);
    assertTrue(
        actingRepository()
            .token(credential.token().id())
            .orElseThrow()
            .scope()
            .projects()
            .isEmpty());
    assertThrows(
        CallerFault.class,
        () ->
            policy.require(
                authority, ActingPermission.WORK, new ActingTarget.Project(scopeProject)));
  }

  @Test
  void actingConcurrentIssueAndRevokeRetainOneOutcomePerRequest() throws Exception {
    actingFixture();
    var caller = new RequestAuthority.Direct("deployer");
    var issue = actingIssue(UUID.randomUUID());
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var attempts = new ArrayList<Future<Boolean>>();
      for (int n = 0; n < 2; n++)
        attempts.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  try {
                    actingTokens().issue(caller, issue);
                    return true;
                  } catch (CallerFault refused) {
                    return false;
                  }
                }));
      int successes = 0;
      for (var attempt : attempts) if (attempt.get(20, TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes);
      var receipt = actingTokens().receipt(caller, issue.requestId()).orElseThrow();
      UUID token = receipt.token().orElseThrow();
      UUID revoke = UUID.randomUUID();
      var revokes = new ArrayList<Future<ActingTokens.Receipt>>();
      for (int n = 0; n < 2; n++)
        revokes.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  return actingTokens().revoke(caller, token, revoke);
                }));
      assertEquals(
          revokes.get(0).get(20, TimeUnit.SECONDS), revokes.get(1).get(20, TimeUnit.SECONDS));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM acting_tokens WHERE application=?", Integer.class, name));
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT count(*) FROM acting_audit WHERE token_id=?", Integer.class, token));
    }
  }

  @Test
  void actingGrantActivationFailureRollsBackSourceHeadAndAudit() {
    var identities = ownedLifecycle();
    String handle = "acting-" + UUID.randomUUID();
    var repository = spy(actingRepository());
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new CallerFault("fixture activation failure");
            })
        .when(repository)
        .activate(anyString(), any(), anyList(), anyString());
    store =
        new JdbcApplicationDeploymentStore(jdbc, work, projects, schedules, identities, repository);
    deployments =
        new SourceApplicationDeployments(
            store,
            new JdbcProjectMembers(jdbc),
            projects,
            files,
            (project, root) -> {},
            identities);
    assertThrows(
        CallerFault.class,
        () -> deployments.deploy("deployer", actingRequest(null, handle, "CONTRIBUTOR")));
    assertTrue(projects.find(name).isEmpty());
    assertNull(deployments.status("deployer", name).activeRevision());
    assertTrue(actingRepository().grant(name, "users").isEmpty());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM acting_grants WHERE application=?", Integer.class, name));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM acting_audit WHERE application=?", Integer.class, name));
  }

  @Test
  void ownedDeploymentReservesDormantCredentialAndSchedulesUseTheStablePrincipal() {
    // Keep sub-microsecond digits nonzero so the regression cannot pass on a coarse host clock.
    var identities =
        ownedLifecycle(
            java.time.Clock.fixed(
                java.time.Instant.now()
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                    .plusNanos(676594204),
                java.time.ZoneOffset.UTC));
    String handle = "worker-" + UUID.randomUUID();
    var request = ownedRequest(UUID.randomUUID(), null, handle);
    var deployed = deployments.deploy("deployer", request);
    var reserved = identities.status("deployer", name).identity();
    assertEquals(io.aeyer.plowshare.protocol.ApplicationIdentity.State.ACTIVE, reserved.state());
    assertFalse(reserved.credentialIssued());
    assertFalse(
        jdbc.queryForObject("SELECT account_active(?)", Boolean.class, reserved.principal()));
    verify(schedules).register(reserved.principal(), projects.id(name), "server");
    assertEquals(deployed, deployments.deploy("deployer", request));
    var credentials =
        new io.aeyer.plowshare.server.auth.JdbcServiceCredentialsRepository(
            jdbc, java.time.Clock.systemUTC());
    UUID issueId = UUID.randomUUID();
    var issued =
        identities.issue(
            "deployer",
            new io.aeyer.plowshare.protocol.ApplicationIdentity.Issue(name, issueId, 30, false));
    assertEquals(reserved.tokenId(), issued.receipt().tokenId());
    assertEquals(676594000, issued.receipt().expiresAt().getNano());
    assertTrue(credentials.authentication(issued.credential()).isPresent());
    assertEquals(issued.receipt(), identities.receipt("deployer", name, issueId));
    assertThrows(
        CallerFault.class,
        () ->
            identities.issue(
                "deployer",
                new io.aeyer.plowshare.protocol.ApplicationIdentity.Issue(
                    name, issueId, 30, false)));
    var rotated =
        identities.issue(
            "deployer",
            new io.aeyer.plowshare.protocol.ApplicationIdentity.Issue(
                name, UUID.randomUUID(), 30, true));
    assertEquals(reserved.tokenId(), rotated.receipt().tokenId());
    assertEquals(676594000, rotated.receipt().expiresAt().getNano());
    assertEquals(
        rotated.receipt(), identities.receipt("deployer", name, rotated.receipt().requestId()));
    assertEquals(
        rotated.receipt().expiresAt(), identities.status("deployer", name).identity().expiresAt());
    assertTrue(credentials.authentication(issued.credential()).isEmpty());
    assertTrue(credentials.authentication(rotated.credential()).isPresent());
    var services =
        new io.aeyer.plowshare.server.auth.ServiceAccounts(
            new io.aeyer.plowshare.server.auth.JdbcServiceAccountRepository(jdbc),
            work,
            new io.aeyer.plowshare.server.auth.AdminStore(jdbc),
            new JdbcProjectMembers(jdbc),
            event -> {});
    assertThrows(
        CallerFault.class, () -> services.rotate("deployer", handle, reserved.tokenId(), 30));
    services.revoke("deployer", handle, reserved.tokenId());
    assertTrue(credentials.authentication(rotated.credential()).isEmpty());
  }

  @Test
  void failedFirstActivationKeepsDormantReservationAndRollsBackSourceMembershipAndHead() {
    var identities = ownedLifecycle();
    String handle = "worker-" + UUID.randomUUID();
    doThrow(new CallerFault("fixture enrollment failure"))
        .when(schedules)
        .register(anyString(), anyLong(), eq("server"));
    assertThrows(
        CallerFault.class,
        () -> deployments.deploy("deployer", ownedRequest(UUID.randomUUID(), null, handle)));
    var reserved = identities.status("deployer", name).identity();
    assertEquals(io.aeyer.plowshare.protocol.ApplicationIdentity.State.RESERVED, reserved.state());
    assertFalse(
        jdbc.queryForObject("SELECT account_active(?)", Boolean.class, reserved.principal()));
    assertTrue(projects.find(name).isEmpty());
    assertNull(deployments.status("deployer", name).activeRevision());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM service_token_scopes WHERE token_id=?",
            Integer.class,
            reserved.tokenId()));
    assertThrows(
        CallerFault.class,
        () ->
            identities.issue(
                "deployer",
                new io.aeyer.plowshare.protocol.ApplicationIdentity.Issue(
                    name, UUID.randomUUID(), 30, false)));
    reset(schedules);
    deployments.deploy("deployer", ownedRequest(UUID.randomUUID(), null, handle));
    assertEquals(reserved.tokenId(), identities.status("deployer", name).identity().tokenId());
  }

  @Test
  void nativeZeroBudgetSurvivesAConversationRoundTripAndCannotRecordSpending() {
    deployments.deploy("deployer", request(UUID.randomUUID(), null, "native"));
    var conversations = new ConversationStore(jdbc);
    var record = conversations.open(Home.of(name), Budget.noCalls());
    assertEquals(0, new ConversationStore(jdbc).find(record.id()).orElseThrow().budget().limit());
    assertThrows(ArchiveException.class, () -> conversations.increaseBudget(record.id(), 1, 0));
    assertThrows(ArchiveException.class, () -> conversations.turnEnded(record.id(), Budget.of(1)));
    assertThrows(ArchiveException.class, () -> conversations.turnEnded(record.id(), Budget.none()));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE conversations SET budget_spent=1 WHERE id=?", record.id()));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE conversations SET budget_total=-1 WHERE id=?", record.id()));
  }

  @Test
  void concurrentReservationsRetainOneIdentityAndNeverAdoptAnExistingHandle() throws Exception {
    var identities = ownedLifecycle();
    String handle = "worker-" + UUID.randomUUID();
    var declaration =
        new io.aeyer.plowshare.protocol.ApplicationIdentity.Declaration(handle, "CONTRIBUTOR");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> identities.reserve("deployer", name, declaration));
      var second = executor.submit(() -> identities.reserve("deployer", name, declaration));
      assertEquals(
          first.get(20, TimeUnit.SECONDS).tokenId(), second.get(20, TimeUnit.SECONDS).tokenId());
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM service_tokens WHERE owner_handle=?", Integer.class, handle));
    assertThrows(
        CallerFault.class, () -> identities.reserve("deployer", name + "-foreign", declaration));
    assertThrows(
        CallerFault.class,
        () ->
            identities.reserve(
                "deployer",
                name + "-human",
                new io.aeyer.plowshare.protocol.ApplicationIdentity.Declaration(
                    "deployer", "VIEWER")));
  }

  @Test
  void concurrentOwnedActivationCommitsOnlyOneHeadAndUsesTheRetainedReservation() throws Exception {
    var identities = ownedLifecycle();
    String handle = "worker-" + UUID.randomUUID();
    var declaration =
        new io.aeyer.plowshare.protocol.ApplicationIdentity.Declaration(handle, "CONTRIBUTOR");
    var reserved = identities.reserve("deployer", name, declaration);
    java.util.concurrent.Callable<Boolean> deploy =
        () -> {
          try {
            deployments.deploy("deployer", ownedRequest(UUID.randomUUID(), null, handle));
            return true;
          } catch (CallerFault stale) {
            return false;
          }
        };
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(deploy);
      var second = executor.submit(deploy);
      assertNotEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
    }
    assertEquals(reserved.tokenId(), identities.status("deployer", name).identity().tokenId());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM application_deployment_receipts WHERE project_id=?",
            Integer.class,
            projects.id(name)));
    verify(schedules, times(1)).register(reserved.principal(), projects.id(name), "server");
  }

  @Test
  void concurrentIssuanceStoresOneReceiptAndUpdatesItsCurrentGeneration() throws Exception {
    var identities = ownedLifecycle();
    String handle = "worker-" + UUID.randomUUID();
    deployments.deploy("deployer", ownedRequest(UUID.randomUUID(), null, handle));
    UUID id = UUID.randomUUID();
    var request = new io.aeyer.plowshare.protocol.ApplicationIdentity.Issue(name, id, 30, false);
    java.util.concurrent.Callable<Boolean> issue =
        () -> {
          try {
            identities.issue("deployer", request);
            return true;
          } catch (CallerFault retained) {
            return false;
          }
        };
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(issue);
      var second = executor.submit(issue);
      assertNotEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
    }
    assertEquals(id, identities.status("deployer", name).identity().credentialRequestId());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM application_token_receipts WHERE request_id=?",
            Integer.class,
            id));
    var rotated =
        identities.issue(
            "deployer",
            new io.aeyer.plowshare.protocol.ApplicationIdentity.Issue(
                name, UUID.randomUUID(), 30, true));
    assertEquals(
        rotated.receipt().requestId(),
        identities.status("deployer", name).identity().credentialRequestId());
  }

  @Test
  void invalidSourceNeverReservesAuthorityAndRemovedSourceCannotDropIdentity() {
    var identities = ownedLifecycle();
    String handle = "worker-" + UUID.randomUUID();
    deployments =
        new SourceApplicationDeployments(
            store,
            new JdbcProjectMembers(jdbc),
            projects,
            files,
            (project, root) -> {
              throw new CallerFault("invalid package");
            },
            identities);
    assertThrows(
        CallerFault.class,
        () -> deployments.deploy("deployer", ownedRequest(UUID.randomUUID(), null, handle)));
    assertNull(identities.status("deployer", name).identity());
    identities = ownedLifecycle();
    var deployed = deployments.deploy("deployer", ownedRequest(UUID.randomUUID(), null, handle));
    var retained = identities.status("deployer", name).identity();
    assertThrows(
        CallerFault.class,
        () ->
            deployments.deploy(
                "deployer", request(UUID.randomUUID(), deployed.release().revision(), "manual")));
    assertEquals(
        deployed.release().revision(), deployments.status("deployer", name).activeRevision());
    assertEquals(retained, identities.status("deployer", name).identity());
  }
}
