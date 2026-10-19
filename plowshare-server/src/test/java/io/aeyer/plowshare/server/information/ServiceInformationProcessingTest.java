package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.documents.Extracted;
import io.aeyer.plowshare.server.information.InformationLifecycle.Lease;
import io.aeyer.plowshare.server.information.InformationLifecycle.StaleLease;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Actual PostgreSQL queue eligibility and checkpoint fencing for retained machine-owned sources.
 */
@Tag("full-db")
@Testcontainers
class ServiceInformationProcessingTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  static final String PRINCIPAL = "@service/" + UUID.randomUUID();
  JdbcTemplate jdbc;
  UnitOfWork work;
  ProjectMembers members;
  JdbcInformationProcessingRepository repository;
  Long project;
  UUID token;
  AtomicReference<ApplicationPolicy.Boundary> boundary;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(source);
    io.aeyer.plowshare.server.testing.AuthorityFixtures.truncate(
        jdbc, "TRUNCATE projects,admins CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,account_kind,must_change_password,server_admin) VALUES ('worker','hash','SERVICE',false,false), (?,'hash','SERVICE_TOKEN',false,false),('reader','hash','USER',false,false)",
        PRINCIPAL);
    project =
        jdbc.queryForObject(
            "INSERT INTO projects(name) VALUES ('automation') RETURNING id", Long.class);
    jdbc.update("INSERT INTO projects(name) VALUES ('other')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,'worker','CONTRIBUTOR'),(?,'reader','CONTRIBUTOR')",
        project,
        project);
    token = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at) VALUES (?,'worker','fixture',?,'fixture-digest',now()+interval '1 day')",
        token,
        PRINCIPAL);
    jdbc.update(
        "INSERT INTO service_token_scopes(token_id,project_id,role) VALUES (?,?,'CONTRIBUTOR')",
        token,
        project);
    boundary =
        new AtomicReference<>(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION,
                Map.of("worker", ProjectRole.CONTRIBUTOR, "reader", ProjectRole.CONTRIBUTOR)));
    members =
        new ApplicationProjectMembers(
            new JdbcProjectMembers(jdbc),
            ignored -> boundary.get(),
            new io.aeyer.plowshare.server.access.ApplicationGrantLineage(
                new io.aeyer.plowshare.server.access.JdbcScopedGrantRepository(jdbc),
                ignored -> boundary.get()));
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    repository = new JdbcInformationProcessingRepository(jdbc, Clock.systemUTC(), members);
  }

  UUID retained(String owner, Long home) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    String namespace =
        home == null
            ? "account:" + owner
            : "project:"
                + jdbc.queryForObject("SELECT name FROM projects WHERE id=?", String.class, home);
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) VALUES (?,?,?,?,?,'source')",
        resource,
        namespace,
        resource + ".txt",
        owner,
        home);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,source_bytes,allowance_total) VALUES (?,?,1,'Evidence','text/plain','fixture',8,?,5)",
        revision,
        resource,
        "Evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    for (String stage : InformationCatalogue.STAGES)
      jdbc.update(
          "INSERT INTO information_steps(revision_id,generation,stage,state) VALUES (?,1,?,'pending')",
          revision,
          stage);
    return revision;
  }

  Lease claim(UUID revision) {
    return work.inTransaction(
        () -> {
          Lease lease = repository.candidate(revision, false).orElseThrow().lease();
          repository.start(lease, null);
          return lease;
        });
  }

  String state(UUID revision, String stage) {
    return jdbc.queryForObject(
        "SELECT state FROM information_steps WHERE revision_id=? AND stage=?",
        String.class,
        revision,
        stage);
  }

  @Test
  void serviceOwnedSourceCompletesExtractionWithoutTokenMembershipOrOwnershipRewrite() {
    UUID revision = retained(PRINCIPAL, project);
    var catalogue =
        InformationFixtures.catalogue(
            jdbc,
            work,
            new InformationAccess(new EffectiveProjectPermissions(members)),
            Clock.systemUTC());
    var lifecycle =
        new InformationLifecycle(
            repository,
            work,
            catalogue,
            (lease, cancelled, fence) ->
                work.inTransaction(
                    () -> {
                      assertFalse(cancelled.getAsBoolean());
                      fence.run();
                      var source = repository.readRevision(lease.revision());
                      repository.extracted(
                          lease.revision(),
                          new Extracted(
                              "Evidence",
                              "fixture",
                              new String(
                                  source.sourceBytes(), java.nio.charset.StandardCharsets.UTF_8),
                              List.of(),
                              Extracted.NOT_CONVERTED));
                      return null;
                    }),
            new InformationLifecycle.Gates() {});
    assertTrue(lifecycle.drainOne(revision));
    assertEquals("ready", state(revision, "extract"));
    assertEquals("Evidence", repository.readRevision(revision).extractedText());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT attempt FROM information_steps WHERE revision_id=? AND stage='extract'",
            Integer.class,
            revision));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM project_members WHERE handle=?", Integer.class, PRINCIPAL));
    assertEquals(
        PRINCIPAL,
        jdbc.queryForObject(
            "SELECT q.owner_handle FROM information_resources q JOIN information_revisions r ON r.resource_id=q.id WHERE r.id=?",
            String.class,
            revision));
    assertEquals(
        Set.of(PRINCIPAL),
        new HashSet<>(
            jdbc.queryForList(
                "SELECT DISTINCT actor_handle FROM information_events WHERE revision_id=?",
                String.class,
                revision)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "revoked",
        "expired",
        "ownerDisabled",
        "principalDisabled",
        "membershipRemoved",
        "scopeRemoved",
        "applicationWithdrawn"
      })
  void formerCreatorAuthorityDoesNotControlSystemRenewalCheckpointOrReclaim(String change) {
    UUID revision = retained(PRINCIPAL, project);
    Lease lease = claim(revision);
    assertTrue(repository.renew(lease));
    work.inTransaction(
        () -> {
          repository.requireLease(lease);
          return null;
        });
    switch (change) {
      case "revoked" -> jdbc.update("UPDATE service_tokens SET revoked_at=now() WHERE id=?", token);
      case "expired" ->
          jdbc.update(
              "UPDATE service_tokens SET expires_at=now()-interval '1 second' WHERE id=?", token);
      case "ownerDisabled" -> jdbc.update("UPDATE admins SET enabled=false WHERE handle='worker'");
      case "principalDisabled" ->
          jdbc.update("UPDATE admins SET enabled=false WHERE handle=?", PRINCIPAL);
      case "membershipRemoved" -> jdbc.update("DELETE FROM project_members WHERE handle='worker'");
      case "scopeRemoved" ->
          jdbc.update("DELETE FROM service_token_scopes WHERE token_id=?", token);
      case "applicationWithdrawn" ->
          boundary.set(
              new ApplicationPolicy.Boundary(
                  ApplicationPolicy.Kind.APPLICATION, Map.of("reader", ProjectRole.CONTRIBUTOR)));
      default -> fail("unknown withdrawal fixture");
    }
    assertTrue(repository.renew(lease));
    work.inTransaction(
        () -> {
          repository.requireLease(lease);
          repository.extracted(
              revision,
              new Extracted(
                  "Retained", "fixture", "Retained write", List.of(), Extracted.NOT_CONVERTED));
          return null;
        });
    assertEquals("Retained write", repository.readRevision(revision).extractedText());
    jdbc.update(
        "UPDATE information_steps SET lease_until=now()-interval '1 second' WHERE revision_id=? AND stage='extract'",
        revision);
    assertTrue(work.inTransaction(() -> repository.candidate(revision, false).isPresent()));
  }

  @Test
  void visibilityCeilingAllowsPreviouslyAdmittedWorkWithoutGrantingContributorAuthority() {
    UUID revision = retained(PRINCIPAL, project);
    jdbc.update("UPDATE service_token_scopes SET role='VIEWER' WHERE token_id=?", token);
    assertEquals(Optional.of(ProjectRole.VIEWER), members.role("automation", PRINCIPAL));
    assertFalse(members.mayWork("automation", PRINCIPAL));
    Lease lease = claim(revision);
    work.inTransaction(
        () -> {
          repository.requireLease(lease);
          return null;
        });
    assertTrue(repository.renew(lease));
  }

  @Test
  void applicationWithdrawalDoesNotChangePreviouslyAdmittedSystemWork() {
    UUID projectWork = retained(PRINCIPAL, project), personal = retained("reader", null);
    boundary.set(
        new ApplicationPolicy.Boundary(
            ApplicationPolicy.Kind.APPLICATION, Map.of("reader", ProjectRole.CONTRIBUTOR)));
    assertEquals(projectWork, claim(projectWork).revision());
    assertEquals(personal, claim(personal).revision());
  }

  @Test
  void formerHumanMembershipDoesNotChangeProjectOrPersonalSystemWork() {
    UUID projectWork = retained("reader", project), personal = retained("reader", null);
    jdbc.update("DELETE FROM project_members WHERE handle='reader'");
    assertEquals(projectWork, claim(projectWork).revision());
    assertEquals(personal, claim(personal).revision());
  }

  @Test
  void concurrentWorkerSkipsTheLockedStage() throws Exception {
    UUID first = retained(PRINCIPAL, project), second = retained(PRINCIPAL, project);
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var other =
          executor.submit(
              () ->
                  work.inTransaction(
                      () -> {
                        var candidate = repository.candidate(null, false).orElseThrow();
                        assertEquals(first, candidate.lease().revision());
                        locked.countDown();
                        try {
                          assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError(interrupted);
                        }
                        repository.start(candidate.lease(), null);
                        return candidate;
                      }));
      try {
        assertTrue(locked.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(
            second,
            work.inTransaction(
                () -> repository.candidate(null, false).orElseThrow().lease().revision()));
      } finally {
        release.countDown();
      }
      other.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  @Test
  void unknownPersonalHomeIsRefusedButCreatorTokenScopeDoesNotGateRegisteredSystemWork() {
    UUID personal = retained(PRINCIPAL, null);
    Long other = jdbc.queryForObject("SELECT id FROM projects WHERE name='other'", Long.class);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,'worker','CONTRIBUTOR')",
        other);
    UUID unscoped = retained(PRINCIPAL, other);
    assertTrue(work.inTransaction(() -> repository.candidate(personal, false).isEmpty()));
    assertTrue(work.inTransaction(() -> repository.candidate(unscoped, false).isPresent()));
  }

  @Test
  void newlyUnreadableInputsInvalidateServiceLease() {
    UUID input = retained(PRINCIPAL, project), derived = retained(PRINCIPAL, project);
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) VALUES (?,?)",
        derived,
        input);
    Lease lease = claim(derived);
    assertTrue(repository.renew(lease));
    work.inTransaction(
        () -> {
          repository.requireLease(lease);
          return null;
        });
    jdbc.update("UPDATE information_revisions SET availability='withdrawn' WHERE id=?", input);
    assertFalse(repository.renew(lease));
    assertThrows(
        StaleLease.class,
        () ->
            work.inTransaction(
                () -> {
                  repository.requireLease(lease);
                  return null;
                }));
  }

  @ParameterizedTest
  @ValueSource(strings = {"autoTag", "tagGroups"})
  void serviceSourcesEnterBothMetadataSweepsUnderCurrentApplicationGrants(String stage) {
    UUID revision = retained(PRINCIPAL, project);
    metadataReady(revision, stage);
    var queued =
        work.inTransaction(
            () ->
                stage.equals("autoTag") ? repository.sweepUntagged() : repository.sweepTagGroups());
    assertEquals(
        List.of(new InformationProcessingRepository.Queued(revision, 1, PRINCIPAL)), queued);
    assertEquals("pending", state(revision, stage));
    Lease lease = claim(revision);
    assertEquals(stage, lease.stage());
    work.inTransaction(
        () -> {
          repository.requireLease(lease);
          return null;
        });
  }

  @Test
  void systemEvidenceIsImmutableAndWithdrawalStillFencesOutput() {
    UUID revision = retained("reader", project);
    Lease lease = claim(revision);
    var scope = repository.home(revision);
    UUID root =
        jdbc.queryForObject(
            "SELECT id FROM authority_grants WHERE provenance='ROOT_POLICY'", UUID.class);
    var evidence =
        new io.aeyer.plowshare.server.access.SystemOperationRecords.Envelope(
            UUID.randomUUID(),
            io.aeyer.plowshare.server.access.SystemOperations.Purpose.DOCUMENT_PREPARATION,
            scope,
            root,
            "worker",
            "model",
            "a".repeat(64));
    var records = new io.aeyer.plowshare.server.access.JdbcSystemOperationRecords(jdbc);
    records.retain(evidence);
    new io.aeyer.plowshare.server.access.JdbcSystemOperationRecords(jdbc).require(evidence);
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("UPDATE system_operations SET model='other' WHERE id=?", evidence.id()));
    jdbc.update("UPDATE information_revisions SET availability='withdrawn' WHERE id=?", revision);
    assertFalse(repository.renew(lease));
    assertThrows(
        StaleLease.class,
        () ->
            work.inTransaction(
                () -> {
                  repository.requireLease(lease);
                  return null;
                }));
  }

  @Test
  void conversationClaimsPersistBeforeDispatchAndStaleRecoveredReadersCannotDoubleSpend() {
    var store = new ConversationStore(jdbc);
    var budget = io.aeyer.plowshare.server.agents.Budget.of(2);
    var row = store.open(io.aeyer.plowshare.protocol.Home.of("automation"), budget, null, "reader");
    var stale = store.find(row.id()).orElseThrow().budget();
    var first = budget.tryClaim().orElseThrow();
    assertEquals(row.id(), first.conversation());
    assertEquals(1, store.find(row.id()).orElseThrow().budget().spent());
    assertThrows(ArchiveException.class, stale::tryClaim);
    var recovered = store.find(row.id()).orElseThrow().budget();
    var last = recovered.tryClaim().orElseThrow();
    assertEquals(2, last.ordinal());
    assertTrue(store.find(row.id()).orElseThrow().budget().exhausted());
    var admission =
        new io.aeyer.plowshare.server.llm.dispatch.InferenceBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            last.budget(),
            last.ordinal(),
            last.limit(),
            last.conversation());
    var records = new io.aeyer.plowshare.server.llm.accounting.JdbcInferenceAdmissions(jdbc);
    records.retain(admission);
    records.require(admission);
    assertEquals(
        row.id(),
        jdbc.queryForObject(
            "SELECT source_conversation FROM operation_inference_budgets WHERE operation_id=?",
            String.class,
            admission.operation()));
  }

  @Test
  void aRevokedCreatorDoesNotPreventPrivateSystemPipelineLogCreation() {
    UUID revision = retained(PRINCIPAL, project);
    Lease lease = claim(revision);
    jdbc.update("UPDATE service_tokens SET revoked_at=now() WHERE id=?", token);
    jdbc.update("DELETE FROM project_members WHERE handle='worker'");
    var identities = new io.aeyer.plowshare.server.access.JdbcIdentityRepository(jdbc);
    var grants = new io.aeyer.plowshare.server.access.JdbcScopedGrantRepository(jdbc);
    ApplicationPolicy applications = ignored -> boundary.get();
    var authority =
        new io.aeyer.plowshare.server.access.LiveScopedAuthority(
            grants,
            applications,
            new io.aeyer.plowshare.server.access.ApplicationGrantLineage(grants, applications),
            identities);
    var system =
        new io.aeyer.plowshare.server.access.BoundSystemOperations(
            identities,
            grants,
            authority,
            new io.aeyer.plowshare.server.access.JdbcSystemOperationRecords(jdbc),
            new io.aeyer.plowshare.server.access.OperationInference(
                org.mockito.Mockito.mock(io.aeyer.plowshare.server.access.OperationAuthority.class),
                new io.aeyer.plowshare.server.llm.accounting.JdbcInferenceAdmissions(jdbc)));
    var ordinary =
        org.mockito.Mockito.mock(io.aeyer.plowshare.server.access.OperationAuthority.class);
    org.mockito.Mockito.when(
            ordinary.admit(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("creator has no execution grant"));
    var conversations = new ConversationStore(jdbc, ordinary);
    var access = new InformationAccess(new EffectiveProjectPermissions(members));
    var catalogue = InformationFixtures.catalogue(jdbc, work, access, Clock.systemUTC());
    var inputs = new InformationJobs(new JdbcJobInformationRepository(jdbc), access);
    var stages =
        new InformationStageHooks(
            new JdbcInformationStageRepository(jdbc),
            catalogue,
            conversations,
            io.aeyer.plowshare.server.agents.LogStages.NONE,
            inputs,
            io.aeyer.plowshare.server.hooks.Hooks.NONE,
            io.aeyer.plowshare.server.harness.Harness.NONE,
            () -> null,
            system,
            repository);
    assertFalse(work.inTransaction(() -> stages.before(lease)).isDenied());
    String log =
        jdbc.queryForObject(
            "SELECT processing_log FROM information_revisions WHERE id=?", String.class, revision);
    assertNotNull(log);
    assertEquals(PRINCIPAL, conversations.ownerOf(log).orElseThrow());
    assertFalse(inputs.logAllowed(log, PRINCIPAL));
    org.mockito.Mockito.verifyNoInteractions(ordinary);
  }

  @Test
  void manualControlsUseCommandAuthorityWithoutWorkerLeasesOrProcessingLogReplacement() {
    UUID revision = retained(PRINCIPAL, project);
    var access = new InformationAccess(new EffectiveProjectPermissions(members));
    var catalogue = InformationFixtures.catalogue(jdbc, work, access, Clock.systemUTC());
    catalogue.withGates(controlHooks(catalogue, access));
    var context =
        new InformationContext(
                PRINCIPAL,
                new InformationContext.Selection(
                    InformationContext.Scope.PROJECT, "automation", false))
            .withView(true);
    UUID request = UUID.randomUUID();
    catalogue.tags(context, revision, List.of("sdk", "manual"), request);
    assertEquals(List.of("manual", "sdk"), catalogue.status(context, revision).tags());
    assertNull(
        jdbc.queryForObject(
            "SELECT processing_log FROM information_revisions WHERE id=?", String.class, revision));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_steps WHERE revision_id=? AND state='running'",
            Integer.class,
            revision));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM system_operations WHERE purpose='DOCUMENT_PREPARATION'",
            Integer.class));
    assertEquals(
        "completed",
        jdbc.queryForObject(
            "SELECT state FROM information_commands WHERE account=? AND request_id=?",
            String.class,
            PRINCIPAL,
            request));
    catalogue.tags(context, revision, List.of("sdk", "manual"), request);
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM system_operations WHERE purpose='DOCUMENT_PREPARATION'",
            Integer.class));
    jdbc.update("UPDATE service_tokens SET revoked_at=now() WHERE id=?", token);
    assertThrows(
        RuntimeException.class,
        () -> catalogue.tags(context, revision, List.of("changed"), UUID.randomUUID()));
    assertEquals(
        "[\"manual\", \"sdk\"]",
        jdbc.queryForObject(
            "SELECT tags::text FROM information_resources WHERE id=(SELECT resource_id FROM information_revisions WHERE id=?)",
            String.class,
            revision));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "reserved",
        "applied",
        "blocked",
        "completed",
        "expired",
        "wrongToken",
        "wrongRevision",
        "wrongActor"
      })
  void manualControlFenceChecksTheExactActiveCommandAttempt(String change) {
    UUID revision = retained(PRINCIPAL, project);
    var context =
        new InformationContext(
            PRINCIPAL,
            new InformationContext.Selection(
                InformationContext.Scope.PROJECT, "automation", false));
    var commands = new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC());
    var command =
        work.inTransaction(
            () -> commands.reserve(context, revision, UUID.randomUUID(), "a".repeat(64)));
    if (List.of("applied", "blocked", "completed").contains(change))
      commands.commandState(
          context,
          command,
          InformationCatalogueRepository.CommandState.valueOf(change.toUpperCase(Locale.ROOT)),
          null);
    if (change.equals("expired"))
      jdbc.update(
          "UPDATE information_commands SET lease_until=now()-interval '1 second' WHERE account=? AND request_id=?",
          PRINCIPAL,
          command.request());
    var selected =
        change.equals("wrongToken")
            ? new InformationCatalogueRepository.Command(
                command.request(), UUID.randomUUID(), false, false)
            : command;
    var target = change.equals("wrongRevision") ? UUID.randomUUID() : revision;
    var actor =
        change.equals("wrongActor")
            ? new InformationContext("reader", context.selection())
            : context;
    Runnable check =
        () ->
            work.inTransaction(
                () -> {
                  commands.requireCommand(actor, target, selected);
                  return null;
                });
    if (List.of("reserved", "applied").contains(change)) assertDoesNotThrow(check::run);
    else assertThrows(RuntimeException.class, check::run);
  }

  private InformationStageHooks controlHooks(
      InformationCatalogue catalogue, InformationAccess access) {
    var identities = new io.aeyer.plowshare.server.access.JdbcIdentityRepository(jdbc);
    var grants = new io.aeyer.plowshare.server.access.JdbcScopedGrantRepository(jdbc);
    ApplicationPolicy applications = ignored -> boundary.get();
    var authority =
        new io.aeyer.plowshare.server.access.LiveScopedAuthority(
            grants,
            applications,
            new io.aeyer.plowshare.server.access.ApplicationGrantLineage(grants, applications),
            identities);
    var system =
        new io.aeyer.plowshare.server.access.BoundSystemOperations(
            identities,
            grants,
            authority,
            new io.aeyer.plowshare.server.access.JdbcSystemOperationRecords(jdbc),
            new io.aeyer.plowshare.server.access.OperationInference(
                org.mockito.Mockito.mock(io.aeyer.plowshare.server.access.OperationAuthority.class),
                new io.aeyer.plowshare.server.llm.accounting.JdbcInferenceAdmissions(jdbc)));
    return new InformationStageHooks(
        new JdbcInformationStageRepository(jdbc),
        catalogue,
        new ConversationStore(jdbc),
        io.aeyer.plowshare.server.agents.LogStages.NONE,
        new InformationJobs(new JdbcJobInformationRepository(jdbc), access),
        io.aeyer.plowshare.server.hooks.Hooks.NONE,
        io.aeyer.plowshare.server.harness.Harness.NONE,
        () -> null,
        system,
        repository);
  }

  void metadataReady(UUID revision, String stage) {
    jdbc.update("UPDATE information_revisions SET extracted_text='Evidence' WHERE id=?", revision);
    jdbc.update("UPDATE information_steps SET state='ready' WHERE revision_id=?", revision);
    jdbc.update(
        "UPDATE information_steps SET state='skipped' WHERE revision_id=? AND stage=?",
        revision,
        stage);
    if (stage.equals("tagGroups"))
      jdbc.update(
          "UPDATE information_resources SET tags='[\"evidence\"]'::jsonb WHERE id=(SELECT resource_id FROM information_revisions WHERE id=?)",
          revision);
  }

  @ParameterizedTest
  @ValueSource(strings = {"autoTag", "tagGroups"})
  void creatorWithdrawalDoesNotRemoveRetainedSystemMetadataFromTheQueue(String stage) {
    for (int index = 0; index < 100; index++) {
      UUID denied = retained(PRINCIPAL, project);
      metadataReady(denied, stage);
    }
    UUID allowed = retained("reader", project);
    metadataReady(allowed, stage);
    boundary.set(
        new ApplicationPolicy.Boundary(
            ApplicationPolicy.Kind.APPLICATION, Map.of("reader", ProjectRole.CONTRIBUTOR)));
    var queued =
        work.inTransaction(
            () ->
                stage.equals("autoTag") ? repository.sweepUntagged() : repository.sweepTagGroups());
    assertEquals(100, queued.size());
    var next =
        work.inTransaction(
            () ->
                stage.equals("autoTag") ? repository.sweepUntagged() : repository.sweepTagGroups());
    assertEquals(List.of(new InformationProcessingRepository.Queued(allowed, 1, "reader")), next);
  }
}
