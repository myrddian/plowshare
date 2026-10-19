package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.*;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Current-root policy, universal eligibility, migration reconciliation and transactional fences.
 */
@Tag("full-db")
@Testcontainers
class RootDocumentPolicyPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  JdbcTemplate jdbc;
  UnitOfWork work;
  JdbcInformationCatalogueRepository records;
  JdbcInformationNavigationRepository navigation;
  String service = "@service/" + UUID.randomUUID();
  UUID token = UUID.randomUUID();

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
    AuthorityFixtures.truncate(jdbc, "TRUNCATE admins,projects CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin) VALUES('manager','fixture',false,false),('contributor','fixture',false,false),('reader','fixture',false,false),('outside','fixture',false,false),('admin','fixture',false,true)");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin,account_kind) VALUES('app','fixture',false,false,'SERVICE'),(?,'fixture',false,false,'SERVICE_TOKEN')",
        service);
    jdbc.update("INSERT INTO projects(name) VALUES('one'),('two')");
    for (var member :
        Map.of(
                "manager",
                "MANAGER",
                "contributor",
                "CONTRIBUTOR",
                "reader",
                "VIEWER",
                "app",
                "CONTRIBUTOR")
            .entrySet())
      jdbc.update(
          "INSERT INTO project_members(project_id,handle,role) SELECT id,?,? FROM projects WHERE name='one'",
          member.getKey(),
          member.getValue());
    jdbc.update(
        "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at) VALUES(?,'app','fixture',?,'fixture',now()+interval '1 day')",
        token,
        service);
    jdbc.update(
        "INSERT INTO service_token_scopes(token_id,project_id,role) SELECT ?,id,'VIEWER' FROM projects WHERE name='one'",
        token);
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    records = new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC());
    navigation = new JdbcInformationNavigationRepository(jdbc, new JdbcProjectMembers(jdbc));
  }

  InformationContext project(String actor) {
    return new InformationContext(actor, InformationAccess.projectSelection(actor, "one"))
        .withView(true);
  }

  InformationContext general(String actor) {
    return new InformationContext(actor, InformationContext.Selection.globalRead()).withView(true);
  }

  InformationCatalogue.Admission admit(
      InformationContext context, String name, UUID previous, List<UUID> inputs) {
    byte[] bytes = ("# " + name + " " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    return work.inTransaction(
        () ->
            records.admit(
                context,
                UUID.randomUUID(),
                new InformationCatalogueRepository.AdmissionWrite(
                    name,
                    bytes,
                    "text/markdown",
                    null,
                    "source",
                    inputs,
                    null,
                    10,
                    0,
                    false,
                    new DocumentType("document", "markdown"),
                    InformationCatalogue.sha256(bytes),
                    null,
                    previous,
                    true)));
  }

  InformationCatalogue.Admission original() {
    return admit(project("manager"), "shared.md", null, List.of());
  }

  void share(InformationContext context, UUID revision, boolean general) {
    work.inTransaction(
        () -> {
          records.share(context, revision, general);
          return null;
        });
  }

  String audience(UUID resource) {
    return jdbc.queryForObject(
        "SELECT audience FROM information_resource_policies WHERE resource_id=?",
        String.class,
        resource);
  }

  long version(UUID resource) {
    return jdbc.queryForObject(
        "SELECT version FROM information_resource_policies WHERE resource_id=?",
        Long.class,
        resource);
  }

  @Test
  void retainedAndStandaloneSelectionsUseLiveSubstrateForAllPrincipalKinds() {
    var own = original();
    var global =
        admit(
            new InformationContext(
                "admin",
                InformationContext.Selection.account(
                    io.aeyer.plowshare.server.access.BuiltInIdentities.GLOBAL.value())),
            "global.md",
            null,
            List.of());
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'manager','MANAGER' FROM projects WHERE name='two'");
    var otherContext =
        new InformationContext("manager", InformationContext.Selection.project("two"));
    var publicOther = admit(otherContext, "public.md", null, List.of());
    var privateOther = admit(otherContext, "private.md", null, List.of());
    share(otherContext, publicOther.revision(), true);
    for (String actor : List.of("reader", "app", service)) {
      var grantStore = new io.aeyer.plowshare.server.access.JdbcScopedGrantRepository(jdbc);
      var actorId =
          new io.aeyer.plowshare.server.access.JdbcIdentityRepository(jdbc).account(actor).id();
      assertTrue(grantStore.globalReadEligible(actorId));
      var selected = project(actor);
      for (UUID revision : List.of(own.revision(), global.revision(), publicOther.revision()))
        assertTrue(records.readable(selected, revision), actor + " " + revision);
      assertFalse(records.readable(selected, privateOther.revision()));
      assertEquals(3, navigation.facets(selected).total());
      var homeOnly =
          new InformationContext(
              actor,
              new InformationContext.Selection(InformationContext.Scope.PROJECT, "one", false));
      assertEquals(1, navigation.facets(homeOnly).total());
      assertEquals(2, navigation.facets(general(actor)).total());
      var ceiling = new io.aeyer.plowshare.server.access.KnowledgeReadScope(Home.of("one"), false);
      var retained = selected.within(ceiling);
      assertTrue(records.readable(retained, own.revision()));
      assertFalse(records.readable(retained, global.revision()));
      assertEquals(1, navigation.facets(retained).total());
      assertInstanceOf(
          InformationNavigation.Unavailable.class,
          navigation.select(retained, new InformationNavigation.Target(global.resource(), null)));
      assertEquals(
          3,
          navigation
              .facets(
                  selected.within(
                      new io.aeyer.plowshare.server.access.KnowledgeReadScope(
                          Home.of("one"), true)))
              .total());
    }
    share(otherContext, publicOther.revision(), false);
    assertFalse(records.readable(project(service), publicOther.revision()));
    assertEquals(2, navigation.facets(project(service)).total());
  }

  @Test
  void rootlessCompatibilityRowsAndOldSelectorCannotAuthorizeReads() {
    UUID raw = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO documents(id,source_name,title,content_hash,text_hash,byte_size,ingested_at,ingested_by) VALUES(?,?,'Raw','raw','raw',1,now(),'manager')",
        raw,
        raw.toString());
    jdbc.update(
        "INSERT INTO information_document_policies(document_id,owner_handle,visibility,assigned_at) VALUES(?,'manager','shared',now())",
        raw);
    for (String actor : List.of("manager", "outside", "app", service))
      assertFalse(records.readable(general(actor), raw));
    var current = original();
    share(project("manager"), current.revision(), true);
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_readable(?,'outside','shared',NULL,false)",
            Boolean.class,
            current.revision()));
  }

  @Test
  void manager_grant_covers_every_active_principal_but_supplies_no_project_write() {
    var first = original();
    assertFalse(records.readable(general(service), first.revision()));
    share(project("manager"), first.revision(), true);
    for (String actor : List.of("reader", "outside", "app", service)) {
      assertTrue(records.readable(general(actor), first.revision()), actor);
      assertEquals(1, records.list(general(actor), 20, 0, null).size());
    }
    assertEquals("GLOBAL_READ", audience(first.resource()));
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> records.lockResource(project("outside"), first.revision(), false)));
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> records.lockResource(project("reader"), first.revision(), true)));
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> records.lockResource(project("contributor"), first.revision(), true)));
  }

  @Test
  void content_revisions_inherit_policy_and_revocation_covers_history_pins_and_counts() {
    var first = original();
    share(project("manager"), first.revision(), true);
    long granted = version(first.resource());
    var next = admit(project("contributor"), "shared.md", first.revision(), List.of());
    assertEquals(granted, version(first.resource()));
    for (UUID revision : List.of(first.revision(), next.revision()))
      assertTrue(records.readable(general(service), revision));
    assertInstanceOf(
        InformationNavigation.Readable.class,
        navigation.select(
            general(service),
            new InformationNavigation.Target(first.resource(), first.revision())));
    share(project("manager"), next.revision(), false);
    for (UUID revision : List.of(first.revision(), next.revision()))
      assertFalse(records.readable(general(service), revision));
    assertInstanceOf(
        InformationNavigation.Unavailable.class,
        navigation.select(
            general(service),
            new InformationNavigation.Target(first.resource(), first.revision())));
    assertTrue(
        navigation.documents(general(service), null, null, null, 20, 0).documents().isEmpty());
    assertEquals(0, navigation.facets(general(service)).total());
    assertEquals(granted + 1, version(first.resource()));
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_resource_policy_history WHERE resource_id=?",
            Integer.class,
            first.resource()));
  }

  @Test
  void publication_and_user_tags_do_not_grant_a_read_audience() {
    var first = original();
    work.inTransaction(
        () -> {
          records.tags(project("contributor"), first.revision(), List.of("published", "everyone"));
          return null;
        });
    assertEquals("HOME", audience(first.resource()));
    assertEquals(1, version(first.resource()));
    assertFalse(records.readable(general("outside"), first.revision()));
    assertTrue(records.readable(project("reader"), first.revision()));
  }

  @Test
  void private_inputs_and_retained_references_cannot_be_published_by_releasing_the_derived_root() {
    var secret =
        admit(
            new InformationContext("manager", InformationContext.Selection.personal()),
            "secret.md",
            null,
            List.of());
    var derived = original();
    jdbc.update(
        "INSERT INTO information_inputs VALUES(?,?)", derived.revision(), secret.revision());
    assertThrows(RuntimeException.class, () -> share(project("manager"), derived.revision(), true));
    assertEquals("HOME", audience(derived.resource()));
    var referencing = admit(project("manager"), "referencing.md", null, List.of());
    UUID edge = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_relationships(id,source,target,relation,actor) VALUES(?,?,?,'references','manager')",
        edge,
        referencing.resource(),
        secret.resource());
    jdbc.update("UPDATE information_relationships SET removed=true WHERE id=?", edge);
    assertThrows(
        RuntimeException.class, () -> share(project("manager"), referencing.revision(), true));
    assertFalse(records.readable(general("outside"), referencing.revision()));
  }

  @Test
  void
      public_dependencies_are_valid_for_service_readers_and_later_tightening_revokes_derived_history() {
    var dependency = original();
    share(project("manager"), dependency.revision(), true);
    var derived = admit(project("manager"), "derived.md", null, List.of(dependency.revision()));
    share(project("manager"), derived.revision(), true);
    assertTrue(records.readable(general(service), derived.revision()));
    share(project("manager"), dependency.revision(), false);
    assertFalse(records.readable(general(service), derived.revision()));
    assertTrue(records.list(general(service), 20, 0, null).isEmpty());
  }

  @Test
  void owner_only_is_an_explicit_privacy_principal_and_never_created_by() {
    var first = original();
    jdbc.update(
        "INSERT INTO information_navigation_policies(policy_key,version,settings,actor) VALUES(?,1,?::jsonb,'manager')",
        "document:" + first.resource(),
        "{\"defaults\":{\"ownerOnly\":false,\"referenceHidden\":true,\"propagate\":true},\"mandatory\":{\"ownerOnly\":true,\"referenceHidden\":false,\"propagate\":false},\"allowOverrides\":true,\"referenceLabel\":\"Restricted\"}");
    assertTrue(records.readable(project("manager"), first.revision()));
    assertFalse(records.readable(project("reader"), first.revision()));
    assertThrows(CallerFault.class, () -> share(project("manager"), first.revision(), true));
    assertEquals(
        "manager",
        jdbc.queryForObject(
            "SELECT a.legacy_handle FROM information_resource_policies p JOIN authority_accounts a ON a.id=p.privacy_principal WHERE p.resource_id=?",
            String.class,
            first.resource()));
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_privacy_allows(?,'outside')", Boolean.class, first.resource()));
  }

  @Test
  void revoked_expired_and_disabled_principals_lose_general_read_without_individual_exclusions() {
    var first = original();
    share(project("manager"), first.revision(), true);
    jdbc.update("UPDATE service_tokens SET revoked_at=now() WHERE id=?", token);
    assertFalse(records.readable(general(service), first.revision()));
    jdbc.update(
        "UPDATE service_tokens SET revoked_at=NULL,expires_at=now()-interval '1 second' WHERE id=?",
        token);
    assertFalse(records.readable(general(service), first.revision()));
    jdbc.update("UPDATE admins SET enabled=false WHERE handle='outside'");
    assertFalse(records.readable(general("outside"), first.revision()));
    assertTrue(records.readable(general("reader"), first.revision()));
  }

  @Test
  void general_read_events_redact_execution_details_and_invalidate_every_revision() {
    var first = original();
    var next = admit(project("contributor"), "shared.md", first.revision(), List.of());
    share(project("manager"), next.revision(), true);
    work.inTransaction(
        () -> {
          records.event(
              first.revision(), 1, "manager", "derive", "stage.pre", "private processing details");
          return null;
        });
    assertTrue(
        records.events(general(service), 0, 100).events().stream()
            .allMatch(event -> event.detail().isEmpty()));
    assertTrue(new JdbcInformationEventRepository(jdbc, work).readable(first.revision(), service));
    share(project("manager"), next.revision(), false);
    for (UUID revision : List.of(first.revision(), next.revision()))
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM information_events WHERE revision_id=? AND action='unshared'",
              Integer.class,
              revision));
    assertTrue(records.events(general(service), 0, 100).events().isEmpty());
  }

  @Test
  void legacy_writers_and_history_rewrites_are_refused_and_policy_failure_rolls_back() {
    var first = original();
    jdbc.execute(
        "CREATE FUNCTION fail_root_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture audit failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER fail_root_audit BEFORE INSERT ON information_resource_policy_history FOR EACH ROW EXECUTE FUNCTION fail_root_audit()");
    try {
      assertThrows(
          DataAccessException.class, () -> share(project("manager"), first.revision(), true));
    } finally {
      jdbc.execute("DROP TRIGGER fail_root_audit ON information_resource_policy_history");
      jdbc.execute("DROP FUNCTION fail_root_audit()");
    }
    assertEquals("HOME", audience(first.resource()));
    assertEquals(1, version(first.resource()));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_resource_policy_history SET audience='GLOBAL_READ' WHERE resource_id=?",
                first.resource()));
  }

  @Test
  void general_read_does_not_release_processing_logs_or_provenance_derived_memories() {
    var first = original();
    share(project("manager"), first.revision(), true);
    jdbc.update(
        "INSERT INTO conversations(id,project_id,created_at,budget_total,budget_spent,owner_handle,origin,agent) SELECT 'private-log',id,now(),5,0,'manager','submission','fixture' FROM projects WHERE name='one'");
    jdbc.update(
        "INSERT INTO information_job_inputs(job_id,owner_handle,revision_id,scope,project_name,include_shared) VALUES('private-log','manager',?,'project','one',true)",
        first.revision());
    assertTrue(
        jdbc.queryForObject(
            "SELECT information_log_readable('private-log','manager')", Boolean.class));
    for (String actor : List.of("outside", "app", service)) {
      assertTrue(records.readable(general(actor), first.revision()));
      assertFalse(
          jdbc.queryForObject(
              "SELECT information_log_readable('private-log',?)", Boolean.class, actor));
    }
    jdbc.update(
        "INSERT INTO memories(id,project_id,summary,scope,body,state,formed_at,formed_by,formed_where) SELECT 'memory',id,'summary','task','body','active',now(),'manager','private-log' FROM projects WHERE name='one'");
    jdbc.update(
        "INSERT INTO proposals(id,memory_id,action,reason,state,created_at) VALUES('proposal','memory','promote','reason','pending',now())");
    var sources = new JdbcPromotionSourceRepository(jdbc);
    assertTrue(sources.source("proposal").orElseThrow().publishable());
    jdbc.update(
        "INSERT INTO memory_provenance(memory_id,conversation_id,turn_ordinal) VALUES('memory','private-log',1)");
    assertFalse(sources.source("proposal").orElseThrow().publishable());
  }

  @Test
  void configured_legacy_assignment_adopts_the_root_without_inventing_creator_authority() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,kind) VALUES(?,'legacy','adopted','source')",
        id);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size) VALUES(?,?,1,'Adopted','text/plain','fixture',1)",
        id,
        id);
    jdbc.update(
        "INSERT INTO documents(id,source_name,title,content_hash,text_hash,byte_size,ingested_at,ingested_by) VALUES(?,'adopted','Adopted','fixture','fixture',1,now(),'attribution')",
        id);
    jdbc.update("INSERT INTO information_document_policies(document_id) VALUES(?)", id);
    assertEquals("QUARANTINED", audience(id));
    var access =
        new InformationAccess(
            new io.aeyer.plowshare.server.access.EffectiveProjectPermissions(
                new JdbcProjectMembers(jdbc)));
    var assignments =
        new DocumentPolicyAssignments(
            new JdbcDocumentPolicyRepository(jdbc),
            new InformationJobs(new JdbcJobInformationRepository(jdbc), access),
            work,
            access,
            Clock.systemUTC(),
            "outside");
    assignments.assign(
        "outside",
        id,
        "manager",
        InformationContext.Scope.PROJECT,
        "one",
        "reviewed legacy identity");
    assertEquals("HOME", audience(id));
    assertEquals(2, version(id));
    assertTrue(records.readable(project("reader"), id));
    assertFalse(records.readable(general("outside"), id));
    assertNull(
        jdbc.queryForObject(
            "SELECT created_by FROM information_resources WHERE id=?", UUID.class, id));
  }

  @Test
  void concurrent_audience_updates_serialize_and_snapshot_read_does_not_retain_a_grant()
      throws Exception {
    var first = original();
    share(project("manager"), first.revision(), true);
    var snapshot =
        new org.springframework.transaction.support.TransactionTemplate(
            new DataSourceTransactionManager(source));
    snapshot.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setReadOnly(true);
    var pool = Executors.newFixedThreadPool(2);
    try {
      snapshot.executeWithoutResult(
          status -> {
            assertTrue(records.readable(general(service), first.revision()));
            try {
              pool.submit(() -> share(project("manager"), first.revision(), false))
                  .get(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
            assertTrue(
                records.readable(general(service), first.revision()),
                "already-open database snapshot");
          });
      assertFalse(records.readable(general(service), first.revision()));
      var start = new CountDownLatch(1);
      var tasks = new ArrayList<Future<?>>();
      for (int i = 0; i < 2; i++)
        tasks.add(
            pool.submit(
                () -> {
                  start.await();
                  share(project("manager"), first.revision(), true);
                  return null;
                }));
      start.countDown();
      for (var task : tasks) task.get(10, TimeUnit.SECONDS);
      assertEquals(5, version(first.resource()));
      assertEquals(
          5,
          jdbc.queryForObject(
              "SELECT count(*) FROM information_resource_policy_history WHERE resource_id=?",
              Integer.class,
              first.resource()));
      assertTrue(records.readable(general(service), first.revision()));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void global_home_control_requires_human_global_admin_and_does_not_imply_execution() {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,kind) VALUES(?,'account:GLOBAL','manual','GLOBAL','source')",
        resource);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,source_bytes) VALUES(?,?,1,'Manual','text/plain','fixture',1,?)",
        revision,
        resource,
        new byte[] {1});
    assertTrue(records.readable(general(service), revision));
    assertTrue(
        navigation.canManage(
            "admin", new InformationNavigation.PolicyTarget("document", null, resource)));
    assertFalse(
        navigation.canManage(
            "manager", new InformationNavigation.PolicyTarget("document", null, resource)));
    assertFalse(
        navigation.canManage(
            service, new InformationNavigation.PolicyTarget("document", null, resource)));
    assertNotNull(work.inTransaction(() -> records.lockResource(general("admin"), revision, true)));
    assertThrows(
        NotFoundFault.class,
        () -> work.inTransaction(() -> records.lockResource(general("admin"), revision, false)));
  }
}
