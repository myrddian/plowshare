package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.InformationNavigation.*;
import io.aeyer.plowshare.protocol.InformationNavigation.Readable;
import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.archive.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real SQL access closure, migration, atomic obligations and optimistic control fences. */
@Tag("full-db")
@Testcontainers
class DocumentNavigationPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  JdbcTemplate jdbc;
  UnitOfWork work;
  JdbcInformationNavigationRepository repository;
  final InformationContext alice =
      new InformationContext("alice", InformationContext.Selection.project("operations"))
          .withView(true);
  final InformationContext viewer =
      new InformationContext("viewer", InformationContext.Selection.project("operations"))
          .withView(true);

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
    io.aeyer.plowshare.server.testing.AuthorityFixtures.truncate(jdbc, "TRUNCATE admins CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES('alice','fixture',false),('bob','fixture',false),('viewer','fixture',false),('admin','fixture',true)");
    jdbc.update("INSERT INTO projects(name) VALUES('operations'),('restricted')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'alice','MANAGER' FROM projects");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'viewer','VIEWER' FROM projects WHERE name='operations'");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'bob','CONTRIBUTOR' FROM projects WHERE name='restricted'");
    repository =
        new JdbcInformationNavigationRepository(
            jdbc, new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc));
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
  }

  long project(String name) {
    return Objects.requireNonNull(
        jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, name));
  }

  UUID resource(String owner, String project, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind,tags) VALUES(?,?,?,?,?,'source','[\"billing\"]'::jsonb)",
        id,
        project == null ? "account:" + owner : "project:" + project,
        name,
        owner,
        project == null ? null : project(project));
    revision(id, 1, owner, project, name);
    return id;
  }

  UUID revision(UUID resource, int ordinal, String owner, String project, String name) {
    UUID revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,text_hash,byte_size,extracted_text) VALUES(?,?,?,?,'text/plain','fixture','fixture',10,'body')",
        revision,
        resource,
        ordinal,
        "Private " + name);
    jdbc.update(
        "INSERT INTO documents(id,source_name,title,content_hash,text_hash,byte_size,ingested_at,ingested_by,information_namespace) VALUES(?,?,?,'fixture','fixture',10,now(),?,?)",
        revision,
        name + ordinal,
        "Private " + name,
        owner,
        resource.toString());
    jdbc.update(
        "INSERT INTO information_document_policies(document_id,owner_handle,visibility,project_id,assigned_at) VALUES(?,?,?,?,now())",
        revision,
        owner,
        project == null ? "personal" : "project",
        project == null ? null : project(project));
    return revision;
  }

  UUID head(UUID resource) {
    return jdbc.queryForObject("SELECT information_navigation_head(?)", UUID.class, resource);
  }

  PolicySettings permissive() {
    return new PolicySettings(Policy.NONE, Policy.NONE, true, "Approved reference");
  }

  void set(PolicyTarget target, long expected, PolicySettings settings) {
    work.inTransaction(
        () -> {
          repository.lock();
          repository.setPolicy("admin", target, expected, settings);
          return true;
        });
  }

  Relationship link(UUID a, UUID b) {
    return work.inTransaction(
        () -> {
          repository.lock();
          return repository.create(
              alice, UUID.randomUUID(), a, new Target(b, null), "runbook", head(a));
        });
  }

  boolean readable(UUID resource, String account, String project) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT information_readable(?,?,'project',?,true)",
            Boolean.class,
            head(resource),
            account,
            project));
  }

  @Test
  void lockedReferenceDoesNotExposeTitleOrPropagateAndPinnedRevisionMustBelongToTarget() {
    UUID a = resource("alice", "operations", "service"), b = resource("bob", null, "secret");
    set(new PolicyTarget("project", "operations", null), 0, permissive());
    work.inTransaction(
        () -> {
          repository.lock();
          return repository.setPolicy(
              "bob",
              new PolicyTarget("document", null, b),
              0,
              new PolicySettings(
                  new Policy(true, false, false), Policy.NONE, true, "Approved locked label"));
        });
    Relationship edge = link(a, b);
    assertTrue(readable(a, "viewer", "operations"));
    assertInstanceOf(Unavailable.class, repository.select(viewer, new Target(b, null)));
    var connections = repository.relationships(viewer, a, "outgoing", null, 50, 0).connections();
    assertEquals(1, connections.size());
    var locked = assertInstanceOf(Locked.class, connections.getFirst().selection());
    assertEquals("Approved locked label", locked.label());
    assertEquals(b, locked.resource());
    assertThrows(
        RuntimeException.class,
        () ->
            work.inTransaction(
                () ->
                    repository.create(
                        alice, UUID.randomUUID(), a, new Target(b, head(a)), "invalid", head(a))));
    assertEquals(edge.id(), connections.getFirst().relationship().id());
  }

  @Test
  void propagationIsTransitiveBacklinksDoNotReverseItAndUnlinkRetainsObligations() {
    UUID a = resource("alice", "operations", "service"),
        b = resource("bob", "restricted", "secret"),
        c = resource("alice", "operations", "index");
    Relationship edge = link(a, b);
    link(c, a);
    assertFalse(readable(a, "viewer", "operations"));
    assertFalse(readable(c, "viewer", "operations"));
    assertTrue(readable(b, "bob", "restricted"));
    assertTrue(readable(a, "alice", "operations"));
    var removed =
        work.inTransaction(
            () -> {
              repository.lock();
              return repository.remove(alice, edge.id(), edge.version());
            });
    assertTrue(removed.retainedObligations());
    assertFalse(readable(a, "viewer", "operations"));
    assertTrue(repository.relationships(alice, a, "outgoing", null, 50, 0).connections().isEmpty());
  }

  @Test
  void tighteningCatchesAllExistingEdgesAndWideningRollsBack() {
    set(new PolicyTarget("project", "operations", null), 0, permissive());
    UUID a = resource("alice", "operations", "service"),
        b = resource("bob", "restricted", "secret");
    var target = new PolicyTarget("document", null, b);
    set(target, 0, permissive());
    link(a, b);
    assertTrue(readable(a, "viewer", "operations"));
    set(target, 1, new PolicySettings(new Policy(false, true, true), Policy.NONE, true, "Hidden"));
    assertFalse(readable(a, "viewer", "operations"));
    assertThrows(RuntimeException.class, () -> set(target, 2, permissive()));
    assertEquals(2, repository.policy("admin", target).version());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_reference_obligations", Integer.class));
  }

  @Test
  void portableRelationshipsDoNotUseReadableHistoryToRevealHiddenCurrentLinks() {
    UUID a = resource("alice", "operations", "service");
    UUID b = resource("alice", "operations", "runbook");
    set(new PolicyTarget("project", "operations", null), 0, permissive());
    set(
        new PolicyTarget("document", null, b),
        0,
        new PolicySettings(new Policy(false, true, false), Policy.NONE, true, "Restricted"));
    UUID source = head(a), oldTarget = head(b);
    link(a, b);
    work.inTransaction(
        () -> {
          repository.lock();
          return repository.create(
              alice, UUID.randomUUID(), a, new Target(b, oldTarget), "pinned", source);
        });
    var interchange = new JdbcInformationInterchangeRepository(jdbc);
    var selected = List.of(source, oldTarget);
    assertEquals(2, interchange.relationships(viewer, selected).size());

    UUID latest = revision(b, 2, "alice", "operations", "runbook");
    jdbc.update("UPDATE information_revisions SET availability='withdrawn' WHERE id=?", latest);
    assertTrue(readable(a, "viewer", "operations"));
    assertInstanceOf(Readable.class, repository.select(viewer, new Target(b, oldTarget)));
    assertInstanceOf(Unavailable.class, repository.select(viewer, new Target(b, null)));
    var ordinary = repository.relationships(viewer, a, "outgoing", null, 50, 0).connections();
    assertEquals(1, ordinary.size());
    assertEquals("pinned", ordinary.getFirst().relationship().relation());
    var exported = interchange.relationships(viewer, selected);
    assertEquals(1, exported.size());
    assertEquals("pinned", exported.getFirst().relation());
    assertEquals(oldTarget, exported.getFirst().pinnedRevision());

    // An old readable source must not expose its unavailable current resource's graph either.
    UUID latestSource = revision(a, 2, "alice", "operations", "service");
    jdbc.update(
        "UPDATE information_revisions SET availability='withdrawn' WHERE id=?", latestSource);
    assertInstanceOf(Readable.class, repository.select(viewer, new Target(a, source)));
    assertTrue(interchange.relationships(viewer, selected).isEmpty());
  }

  @Test
  void latestHeadNeverFallsBackAndDocumentCountsDeduplicateTags() {
    UUID a = resource("alice", "operations", "runbook");
    UUID old = head(a);
    UUID latest = revision(a, 2, "alice", "operations", "runbook");
    assertEquals(
        latest,
        assertInstanceOf(Readable.class, repository.select(viewer, new Target(a, null)))
            .document()
            .revision());
    assertEquals(
        old,
        assertInstanceOf(Readable.class, repository.select(viewer, new Target(a, old)))
            .document()
            .revision());
    assertEquals(1, repository.documents(viewer, null, null, null, 50, 0).documents().size());
    assertEquals(1, repository.facets(viewer).total());
    assertEquals(1, repository.facets(viewer).facets().get("tags").getFirst().count());
    jdbc.update("UPDATE information_revisions SET availability='withdrawn' WHERE id=?", latest);
    assertInstanceOf(Unavailable.class, repository.select(viewer, new Target(a, null)));
    assertTrue(repository.documents(viewer, null, null, null, 50, 0).documents().isEmpty());
    assertInstanceOf(Readable.class, repository.select(viewer, new Target(a, old)));
  }

  @Test
  void ownerFallbackCannotBypassPolicyOnPendingRevisionsAndManagerDemotionIsLive() {
    UUID a = resource("alice", "operations", "service"),
        b = resource("bob", "restricted", "secret");
    link(a, b);
    jdbc.update("DELETE FROM documents WHERE id=?", head(a));
    jdbc.update(
        "DELETE FROM project_members WHERE handle='alice' AND project_id=?", project("restricted"));
    var catalogue = new JdbcInformationCatalogueRepository(jdbc, java.time.Clock.systemUTC());
    assertFalse(catalogue.readable(alice, head(a)));
    jdbc.update("UPDATE project_members SET role='CONTRIBUTOR' WHERE handle='alice'");
    assertThrows(
        RuntimeException.class,
        () ->
            work.inTransaction(
                () ->
                    repository.setPolicy(
                        "alice",
                        new PolicyTarget("project", "operations", null),
                        0,
                        permissive())));
  }

  @Test
  void savedViewsAreAccountOwnedVersionedAndSurviveRepositoryRecreation() {
    UUID id = UUID.randomUUID();
    var path =
        new Path(
            List.of(
                new ScopeStep(io.aeyer.plowshare.protocol.InformationRequest.Scope.personal())));
    work.inTransaction(() -> repository.save("alice", id, 0, "Billing", path));
    assertEquals(
        path,
        new JdbcInformationNavigationRepository(
                jdbc, new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc))
            .view("alice", id)
            .path());
    assertThrows(RuntimeException.class, () -> repository.view("viewer", id));
    assertThrows(
        RuntimeException.class,
        () -> work.inTransaction(() -> repository.save("alice", id, 0, "Stale", path)));
    work.inTransaction(() -> repository.delete("alice", id, 1));
    assertTrue(repository.views("alice").views().isEmpty());
  }

  @Test
  void nonPropagatingIntermediaryCannotLaunderAnInheritedRestriction() {
    set(new PolicyTarget("project", "operations", null), 0, permissive());
    UUID a = resource("alice", "operations", "service"),
        b = resource("bob", "restricted", "secret"),
        c = resource("alice", "operations", "index");
    var target = new PolicyTarget("document", null, b);
    set(target, 0, permissive());
    link(c, a);
    link(a, b);
    assertTrue(readable(c, "viewer", "operations"));
    set(target, 1, new PolicySettings(new Policy(false, true, true), Policy.NONE, true, "Hidden"));
    assertFalse(readable(a, "viewer", "operations"));
    assertFalse(readable(c, "viewer", "operations"));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_reference_obligations", Integer.class));
  }

  @Test
  void policyWritersSerializeExpectedVersionsAndReceiptsSurviveRestart() throws Exception {
    var target = new PolicyTarget("project", "operations", null);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var go = new java.util.concurrent.CountDownLatch(1);
      var attempts = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 2; i++)
        attempts.add(
            pool.submit(
                () -> {
                  go.await();
                  try {
                    set(target, 0, permissive());
                    return true;
                  } catch (io.aeyer.plowshare.server.faults.CallerFault expected) {
                    return false;
                  }
                }));
      go.countDown();
      int successes = 0;
      for (var attempt : attempts)
        if (attempt.get(10, java.util.concurrent.TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes);
      assertEquals(1, repository.policy("admin", target).version());
    }
    UUID request = UUID.randomUUID(), id = UUID.randomUUID();
    var result =
        new InformationNavigationRepository.Receipt(null, null, null, new Changed(id, 1, false));
    work.inTransaction(
        () -> {
          repository.lock();
          repository.record(
              "alice",
              request,
              "fixture",
              result,
              "views.delete",
              id.toString(),
              "Fixture",
              Optional.empty());
          return true;
        });
    assertEquals(
        result,
        new JdbcInformationNavigationRepository(
                jdbc, new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc))
            .receipt("alice", request)
            .orElseThrow()
            .result());
  }

  @Test
  void mandatoryConstraintsServiceCeilingsAndHiddenCountsAreEnforced() {
    UUID a = resource("alice", "operations", "service"),
        b = resource("bob", "restricted", "secret");
    set(
        new PolicyTarget("project", "operations", null),
        0,
        new PolicySettings(Policy.NONE, new Policy(true, true, true), true, "Hidden"));
    set(new PolicyTarget("document", null, a), 0, permissive());
    assertTrue(
        repository.policy("admin", new PolicyTarget("document", null, a)).effective().ownerOnly());
    assertTrue(repository.documents(viewer, null, null, null, 50, 0).documents().isEmpty());
    assertEquals(0, repository.facets(viewer).total());
    assertFalse(repository.canManage("viewer", new PolicyTarget("document", null, b)));
    assertFalse(
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT information_navigation_own_readable(?,?,'operations')",
                Boolean.class,
                head(b),
                "@service/fixture")));
  }

  @Test
  void actualInputRestrictionsApplyEvenWhenOrdinaryLinksDoNotPropagate() {
    set(new PolicyTarget("project", "operations", null), 0, permissive());
    UUID a = resource("alice", "operations", "report"), b = resource("bob", "restricted", "secret");
    set(new PolicyTarget("document", null, b), 0, permissive());
    link(a, b);
    assertTrue(readable(a, "viewer", "operations"));
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) VALUES(?,?)",
        head(a),
        head(b));
    assertFalse(readable(a, "viewer", "operations"));
  }

  @Test
  void incompleteClosureFailsClosedAndAbsentSourcesMatchHiddenSources() {
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) SELECT ('22222222-2222-2222-2222-'||lpad(i::text,12,'0'))::uuid,'project:operations','node-'||i,'alice',?,'source' FROM generate_series(1,1025) i",
        project("operations"));
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size) SELECT id,id,1,source_name,'text/plain','fixture',1 FROM information_resources WHERE namespace='project:operations'");
    UUID root = UUID.fromString("22222222-2222-2222-2222-000000000001");
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) SELECT ?,id FROM information_revisions WHERE resource_id IN (SELECT id FROM information_resources WHERE namespace='project:operations') AND id<>?",
        root,
        root);
    assertTrue(
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT information_legacy_readable(?,'alice','project','operations',true)",
                Boolean.class,
                root)));
    assertTrue(
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT information_readable(?,'alice','project','operations',true)",
                Boolean.class,
                root)));
    UUID referencing = resource("alice", "operations", "bounded-reference");
    link(referencing, root);
    assertFalse(readable(referencing, "alice", "operations"));
    UUID hidden = resource("bob", "restricted", "hidden");
    String known =
        assertThrows(
                io.aeyer.plowshare.server.faults.NotFoundFault.class,
                () ->
                    work.inTransaction(
                        () ->
                            repository.create(
                                alice,
                                UUID.randomUUID(),
                                hidden,
                                new Target(root, null),
                                "related",
                                head(hidden))))
            .getMessage();
    String absent =
        assertThrows(
                io.aeyer.plowshare.server.faults.NotFoundFault.class,
                () ->
                    work.inTransaction(
                        () ->
                            repository.create(
                                alice,
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                new Target(root, null),
                                "related",
                                root)))
            .getMessage();
    assertEquals(known, absent);
  }

  @Test
  void relatedDocumentSetsUseTargetScopeAndRetainServiceProjectCeilings() {
    set(new PolicyTarget("project", "operations", null), 0, permissive());
    UUID a = resource("alice", "operations", "service"),
        b = resource("bob", "restricted", "secret");
    set(new PolicyTarget("document", null, b), 0, permissive());
    link(a, b);
    assertEquals(
        List.of(b),
        repository.documents(alice, a, "runbook", "outgoing", 50, 0).documents().stream()
            .map(Document::resource)
            .toList());
    assertEquals(1, repository.facets(alice, a, "runbook", "outgoing").total());
    assertTrue(repository.documents(viewer, a, "runbook", "outgoing", 50, 0).documents().isEmpty());
    assertInstanceOf(
        Locked.class,
        repository
            .relationships(viewer, a, "outgoing", null, 50, 0)
            .connections()
            .getFirst()
            .selection());
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,account_kind,must_change_password,server_admin) VALUES('@service/fixture','fixture','SERVICE',false,false)");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'@service/fixture','CONTRIBUTOR' FROM projects");
    var machine =
        new InformationContext(
                "@service/fixture",
                new InformationContext.Selection(
                    InformationContext.Scope.PROJECT, "operations", false))
            .withView(true);
    assertFalse(
        repository
            .documents(machine, null, null, null, 50, 0)
            .documents()
            .getFirst()
            .scope()
            .includeShared());
    assertTrue(
        repository.documents(machine, a, "runbook", "outgoing", 50, 0).documents().isEmpty());
    assertTrue(
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT information_navigation_own_readable(?,?,'restricted')",
                Boolean.class,
                head(b),
                "@service/fixture")));
    assertFalse(
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT information_navigation_own_readable(?,?,'operations')",
                Boolean.class,
                head(b),
                "@service/fixture")));
  }

  @Test
  void requestIdsAreAccountOwnedAndDoNotBecomeRelationshipIds() {
    UUID a = resource("alice", null, "alice-document"),
        b = resource("bob", null, "bob-document"),
        request = UUID.randomUUID();
    var navigation =
        new DocumentNavigation(
            repository,
            new InformationAccess(new EffectiveProjectPermissions(new JdbcProjectMembers(jdbc))),
            work,
            true);
    var selection = io.aeyer.plowshare.protocol.InformationRequest.Selection.personal();
    var first =
        new io.aeyer.plowshare.protocol.InformationRequest.RelationshipCreate(
            selection, request, a, new Target(a, null), "related", head(a));
    var second =
        new io.aeyer.plowshare.protocol.InformationRequest.RelationshipCreate(
            selection, request, b, new Target(b, null), "related", head(b));
    var one =
        assertInstanceOf(
                io.aeyer.plowshare.protocol.InformationResponse.NavigationRelationship.class,
                navigation.execute("alice", first))
            .value();
    var two =
        assertInstanceOf(
                io.aeyer.plowshare.protocol.InformationResponse.NavigationRelationship.class,
                navigation.execute("bob", second))
            .value();
    assertNotEquals(request, one.id());
    assertNotEquals(one.id(), two.id());
    assertEquals(
        one,
        assertInstanceOf(
                io.aeyer.plowshare.protocol.InformationResponse.NavigationRelationship.class,
                navigation.execute("alice", first))
            .value());
    assertEquals(
        2, jdbc.queryForObject("SELECT count(*) FROM information_relationships", Integer.class));
  }

  @Test
  void referenceVisibilityCannotReleaseQuarantinedTargets() {
    set(new PolicyTarget("project", "operations", null), 0, permissive());
    UUID a = resource("alice", "operations", "source"),
        b = resource("bob", "restricted", "quarantined");
    set(new PolicyTarget("document", null, b), 0, permissive());
    jdbc.update(
        "UPDATE information_document_policies SET visibility='quarantined',owner_handle=NULL,project_id=NULL,assigned_at=NULL WHERE document_id=?",
        head(b));
    assertThrows(io.aeyer.plowshare.server.faults.NotFoundFault.class, () -> link(a, b));
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM information_relationships", Integer.class));
    assertTrue(repository.relationships(alice, a, "outgoing", null, 50, 0).connections().isEmpty());
  }

  @Test
  void cyclesTerminateAndSharedTagsDoNotCreateDependencies() {
    UUID a = resource("alice", "operations", "a"),
        b = resource("alice", "operations", "b"),
        unrelated = resource("bob", "restricted", "c");
    link(a, b);
    link(b, a);
    assertTrue(readable(a, "viewer", "operations"));
    assertFalse(readable(unrelated, "viewer", "operations"));
    assertEquals(2, repository.documents(viewer, null, null, null, 50, 0).documents().size());
  }
}
