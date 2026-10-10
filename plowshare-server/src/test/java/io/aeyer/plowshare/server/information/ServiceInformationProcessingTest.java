package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

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
    jdbc.execute("TRUNCATE projects,admins CASCADE");
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
        new ApplicationProjectMembers(new JdbcProjectMembers(jdbc), ignored -> boundary.get());
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    repository = new JdbcInformationProcessingRepository(jdbc, Clock.systemUTC(), members);
  }

  UUID retained(String owner, Long home) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) VALUES (?,'fixture',?,?,?,'source')",
        resource,
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
            jdbc, work, new InformationAccess(members), Clock.systemUTC());
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
  void withdrawnAuthorityRefusesRenewalCheckpointAndExpiredLeaseReclaim(String change) {
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
    assertFalse(repository.renew(lease));
    assertThrows(
        StaleLease.class,
        () ->
            work.inTransaction(
                () -> {
                  repository.requireLease(lease);
                  repository.extracted(
                      revision,
                      new Extracted(
                          "Late", "fixture", "Late write", List.of(), Extracted.NOT_CONVERTED));
                  return null;
                }));
    assertNull(repository.readRevision(revision).extractedText());
    jdbc.update(
        "UPDATE information_steps SET lease_until=now()-interval '1 second' WHERE revision_id=? AND stage='extract'",
        revision);
    assertTrue(work.inTransaction(() -> repository.candidate(revision, false).isEmpty()));
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
  void deniedApplicationDoesNotStarveOtherProjectsOrPersonalWorkAndCanResume() {
    UUID denied = retained(PRINCIPAL, project);
    UUID allowed = retained("reader", null);
    boundary.set(
        new ApplicationPolicy.Boundary(
            ApplicationPolicy.Kind.APPLICATION, Map.of("reader", ProjectRole.CONTRIBUTOR)));
    assertEquals(
        allowed,
        work.inTransaction(
            () -> repository.candidate(null, false).orElseThrow().lease().revision()));
    assertEquals("pending", state(denied, "extract"));
    boundary.set(
        new ApplicationPolicy.Boundary(
            ApplicationPolicy.Kind.APPLICATION, Map.of("worker", ProjectRole.CONTRIBUTOR)));
    assertEquals(denied, claim(denied).revision());
  }

  @Test
  void deniedHumanProjectDoesNotHideTheSameAccountsPersonalWork() {
    UUID denied = retained("reader", project), personal = retained("reader", null);
    boundary.set(
        new ApplicationPolicy.Boundary(
            ApplicationPolicy.Kind.APPLICATION, Map.of("worker", ProjectRole.CONTRIBUTOR)));
    assertEquals(
        personal,
        work.inTransaction(
            () -> repository.candidate(null, false).orElseThrow().lease().revision()));
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
  void serviceCannotProcessPersonalOrUnscopedProjectSources() {
    UUID personal = retained(PRINCIPAL, null);
    Long other = jdbc.queryForObject("SELECT id FROM projects WHERE name='other'", Long.class);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,'worker','CONTRIBUTOR')",
        other);
    UUID unscoped = retained(PRINCIPAL, other);
    assertTrue(work.inTransaction(() -> repository.candidate(personal, false).isEmpty()));
    assertTrue(work.inTransaction(() -> repository.candidate(unscoped, false).isEmpty()));
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
  void withdrawnMetadataPageDoesNotStarveAnAuthorizedSource(String stage) {
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
    assertEquals(List.of(new InformationProcessingRepository.Queued(allowed, 1, "reader")), queued);
  }
}
