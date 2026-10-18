package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.protocol.frames.*;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.fetch.PageFetcher;
import io.aeyer.plowshare.server.ws.*;
import java.time.Clock;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Synthetic pre-substrate upgrade; never reads or adopts a deployment's database. */
@Tag("full-db")
@Testcontainers
class AuthorityCutoverRehearsalTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  JdbcTemplate jdbc;
  DriverManagerDataSource source;

  /** Identity and revision evidence remain distinct from retained compatibility custody. */
  record Retained(UUID root, List<UUID> revisions) {}

  @Test
  void legacyUpgradeAndReopenedReadersPreserveEvidenceWithoutActivatingGlobalIntake() {
    source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    Flyway.configure().dataSource(source).target("151").load().migrate();
    jdbc = new JdbcTemplate(source);
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin) VALUES('manager','fixture',false,false),('creator','fixture',false,false),('reader','fixture',false,false)");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin,account_kind) VALUES('app','fixture',false,false,'SERVICE')");
    jdbc.update("INSERT INTO projects(name) VALUES('global'),('personal:orphan')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'manager','MANAGER' FROM projects WHERE name='global'");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'creator','CONTRIBUTOR' FROM projects WHERE name='global'");

    var shared = root("project:global", "shared", List.of("shared", "shared"));
    var mixed = root("project:global", "mixed", List.of("shared", "project"));
    var privateRoot = root("project:global", "private", List.of("personal"));
    var personal = root("account:creator", "personal", List.of("personal"));
    var unknownManual = root("legacy", "manual", List.of("shared"));
    var request = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_requests VALUES('creator',?,'retained-request',?)",
        request,
        shared.revisions().getFirst());
    jdbc.update(
        "INSERT INTO information_events(revision_id,generation,actor_handle,stage,action) VALUES(?,1,'creator','intake','retained')",
        shared.revisions().getFirst());
    jdbc.update(
        "INSERT INTO memories(id,summary,scope,body,state,formed_at,formed_by,formed_where,invalidated_at,invalidated_by,invalidated_why) VALUES('global-memory','retained','scope','retained body','invalidated',now(),'old-worker','conversation:old-log',now(),'old-worker','retained reason')");
    jdbc.update(
        "INSERT INTO conversations(id,created_at,budget_total,budget_spent,owner_handle,origin) VALUES('old-log',now(),8,3,'creator','turn')");
    jdbc.update(
        "INSERT INTO jobs(id,agent,started_at,ended_at,ending,steps,model_calls,conversation_id) VALUES('old-job','memory_digester',now(),now(),'ANSWERED',2,3,'old-log')");
    seedAccounting();
    var historical = history();

    Flyway.configure().dataSource(source).load().migrate();
    assertEquals(historical, history());
    var identities = new JdbcIdentityRepository(jdbc);
    identities.verifyBuiltIns();
    var project = assertInstanceOf(AuthorityScope.Project.class, identities.project("global"));
    assertNotEquals(BuiltInIdentities.GLOBAL_KNOWLEDGE, project.id());
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> identities.project("personal:orphan"));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_accounts WHERE builtin IS NOT NULL", Integer.class));
    assertEquals("LEGACY_SHARED", audience(shared));
    assertEquals("HOME", audience(mixed));
    assertEquals("HOME", audience(privateRoot));
    assertEquals("HOME", audience(personal));
    assertEquals("QUARANTINED", audience(unknownManual));
    assertNull(
        jdbc.queryForObject(
            "SELECT home_scope_id FROM information_resources WHERE id=?",
            UUID.class,
            unknownManual.root()));
    assertTrue(
        jdbc.queryForObject(
            "SELECT needs_review FROM information_resource_policies WHERE resource_id=?",
            Boolean.class,
            mixed.root()));
    assertEquals(
        identities.account("creator").id().value(),
        jdbc.queryForObject(
            "SELECT created_by FROM information_resources WHERE id=?", UUID.class, shared.root()));
    assertNull(
        jdbc.queryForObject(
            "SELECT created_by FROM information_resources WHERE id=?",
            UUID.class,
            unknownManual.root()));
    assertEquals(
        BuiltInIdentities.GLOBAL_KNOWLEDGE.value(),
        jdbc.queryForObject(
            "SELECT home_scope_id FROM memories WHERE id='global-memory'", UUID.class));
    var memory = new MemoryStore(jdbc).load("global-memory").orElseThrow();
    assertEquals(Home.global(), memory.home());
    assertEquals("conversation:old-log", memory.formed().where());
    assertTrue(new MemoryStore(jdbc).loadAll(Home.global()).isEmpty());
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM operation_admissions", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM system_operations", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_grant_records WHERE effective_role='GLOBAL_READ'",
            Integer.class));

    // Reopen the owning repositories/router; no old in-memory selection survives this boundary.
    var members = new JdbcProjectMembers(jdbc);
    var access = new InformationAccess(new EffectiveProjectPermissions(members));
    var work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    var records = new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC());
    var catalogue = InformationFixtures.catalogue(jdbc, work, access, Clock.systemUTC());
    var router = router(catalogue, access, members);
    assertEquals(Set.copyOf(shared.revisions()), listed(router, "reader"));
    assertTrue(listed(router, "app").isEmpty());
    // A contributor's creation evidence never supplies audience control.
    assertNotEquals(
        Code.OK, audienceFrame(router, "creator", "share", shared, UUID.randomUUID()).code());
    var publish = UUID.randomUUID();
    assertEquals(Code.OK, audienceFrame(router, "manager", "share", shared, publish).code());
    assertEquals(Set.copyOf(shared.revisions()), listed(router, "app"));
    assertEquals("GLOBAL_READ", audience(shared));
    assertEquals(
        project.id().value(),
        jdbc.queryForObject(
            "SELECT home_scope_id FROM information_resources WHERE id=?",
            UUID.class,
            shared.root()));
    assertEquals(2, policyVersions(shared));
    // Known receipt is recovered without making a second audience mutation.
    assertEquals(Code.OK, audienceFrame(router, "manager", "share", shared, publish).code());
    assertEquals(2, policyVersions(shared));
    members.remove("global", "creator");
    assertEquals(Set.copyOf(shared.revisions()), listed(router, "app"));
    assertEquals(
        Code.OK, audienceFrame(router, "manager", "unshare", shared, UUID.randomUUID()).code());
    assertTrue(listed(router, "reader").isEmpty());
    assertTrue(listed(router, "app").isEmpty());
    assertEquals(3, policyVersions(shared));

    // Unsupported Global intake remains a refusal even for a human deployment administrator.
    jdbc.update("UPDATE admins SET server_admin=true WHERE handle='manager'");
    var rejected =
        request(
            router,
            "manager",
            "information.upload",
            Map.of(
                "scope",
                Map.of("kind", "global"),
                "requestId",
                UUID.randomUUID().toString(),
                "name",
                "manual",
                "text",
                "body"));
    assertEquals(Code.BAD_REQUEST, rejected.code());
    assertEquals(
        5, jdbc.queryForObject("SELECT count(*) FROM information_resources", Integer.class));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_resources SET created_by=? WHERE id=?",
                BuiltInIdentities.SYSTEM.value(),
                shared.root()));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_resource_policy_history SET audience='GLOBAL_READ' WHERE resource_id=?",
                mixed.root()));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_resources SET home_scope_id=? WHERE id=?",
                UUID.randomUUID(),
                mixed.root()));
    Flyway.configure().dataSource(source).load().migrate();
    assertEquals(historical, history());
    assertEquals(project, new JdbcIdentityRepository(jdbc).project("global"));
    assertEquals("HOME", audience(shared));
    assertEquals("QUARANTINED", audience(unknownManual));
    assertFalse(
        records.readable(
            new InformationContext("app", InformationContext.Selection.shared()).withView(true),
            shared.revisions().getFirst()));
  }

  private Retained root(String namespace, String name, List<String> flags) {
    UUID root = UUID.randomUUID();
    Long project =
        namespace.startsWith("project:")
            ? jdbc.queryForObject("SELECT id FROM projects WHERE name='global'", Long.class)
            : null;
    String owner =
        namespace.equals("account:creator")
            ? "creator"
            : namespace.equals("legacy") ? null : "manager";
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) VALUES(?,?,?,?,?,'source')",
        root,
        namespace,
        name,
        owner,
        project);
    var revisions = new ArrayList<UUID>();
    for (int i = 0; i < flags.size(); i++) {
      UUID revision = UUID.randomUUID();
      revisions.add(revision);
      jdbc.update(
          "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,source_bytes) VALUES(?,?,?,'retained','text/plain',?,4,?)",
          revision,
          root,
          i + 1,
          revision.toString(),
          new byte[] {1, 2, 3, 4});
      jdbc.update(
          "INSERT INTO documents(id,source_name,title,content_hash,text_hash,byte_size,ingested_at,ingested_by,information_namespace) VALUES(?,?,'retained',?,'fixture',4,now(),'creator',?)",
          revision,
          revision.toString(),
          revision.toString(),
          namespace);
      jdbc.update(
          "INSERT INTO information_document_policies(document_id,owner_handle,visibility,project_id,assigned_at) VALUES(?,?,?,?,now())",
          revision,
          owner == null ? "creator" : owner,
          flags.get(i),
          flags.get(i).equals("project") ? project : null);
    }
    return new Retained(root, List.copyOf(revisions));
  }

  private String audience(Retained root) {
    return jdbc.queryForObject(
        "SELECT audience FROM information_resource_policies WHERE resource_id=?",
        String.class,
        root.root());
  }

  private int policyVersions(Retained root) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM information_resource_policy_history WHERE resource_id=?",
        Integer.class,
        root.root());
  }

  private FrameRouter router(
      InformationCatalogue catalogue, InformationAccess access, ProjectMembers members) {
    var frames =
        new InformationFrames(
            catalogue,
            access,
            mock(DocumentStore.class),
            mock(RetrievalService.class),
            mock(Deliberation.class),
            mock(JobStore.class),
            new DocumentsProperties(),
            mock(PageFetcher.class),
            mock(InformationContentTypes.class),
            mock(InformationContentSettings.class),
            mock(InformationNavigationService.class),
            mock(InformationInterchangeService.class));
    var router = new FrameRouter(frames.frames());
    router.useAuthorization(
        new ProjectAuthorization(
            new JdbcResourceScopeRepository(jdbc), members, mock(AdminStore.class)));
    return router;
  }

  private Outcome request(
      FrameRouter router, String actor, String operation, Map<String, Object> payload) {
    return router.route(
        new Envelope(UUID.randomUUID().toString(), operation, Envelope.CURRENT_VERSION, payload),
        new Asking("rehearsal", actor));
  }

  private Outcome audienceFrame(
      FrameRouter router, String actor, String verb, Retained root, UUID request) {
    return request(
        router,
        actor,
        "information." + verb,
        Map.of(
            "scope",
            Map.of("kind", "project", "project", "global"),
            "revision",
            root.revisions().getFirst().toString(),
            "requestId",
            request.toString()));
  }

  private Set<UUID> listed(FrameRouter router, String actor) {
    var result =
        request(
            router,
            actor,
            "information.list",
            Map.of(
                "scope",
                Map.of("kind", "shared"),
                "view",
                io.aeyer.plowshare.protocol.InformationContent.VIEW));
    assertEquals(Code.OK, result.code(), result.said());
    var revisions = assertInstanceOf(List.class, result.payload());
    var ids = new HashSet<UUID>();
    for (Object item : revisions) ids.add(assertInstanceOf(Information.Revision.class, item).id());
    return ids;
  }

  /** Compare only pre-existing fields; additive identity columns are checked separately. */
  private List<String> history() {
    return List.of(
        jdbc.queryForObject(
            "SELECT jsonb_agg(to_jsonb(j) ORDER BY id)::text FROM jobs j", String.class),
        jdbc.queryForObject(
            "SELECT jsonb_agg(to_jsonb(r) ORDER BY request_id)::text FROM information_requests r",
            String.class),
        jdbc.queryForObject(
            "SELECT jsonb_agg(to_jsonb(c)-'budget_id' ORDER BY id)::text FROM conversations c",
            String.class),
        jdbc.queryForObject(
            "SELECT jsonb_agg(to_jsonb(a) ORDER BY attempt_id)::text FROM inference_attempts a",
            String.class),
        jdbc.queryForObject(
            "SELECT jsonb_agg(to_jsonb(c) ORDER BY call_id)::text FROM inference_calls c",
            String.class),
        jdbc.queryForObject(
            "SELECT jsonb_agg(jsonb_build_array(id,source_bytes,content_hash) ORDER BY id)::text FROM information_revisions",
            String.class),
        jdbc.queryForObject(
            "SELECT jsonb_agg(jsonb_build_array(id,formed_at,formed_by,formed_where,state,body) ORDER BY id)::text FROM memories",
            String.class));
  }

  private void seedAccounting() {
    UUID journal = UUID.randomUUID(), instance = UUID.randomUUID(), call = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO inference_accounting_journals(journal_id,tracking_started_at) VALUES(?,now())",
        journal);
    jdbc.update(
        "INSERT INTO inference_accounting_instances(instance_id,journal_id,started_at) VALUES(?,?,now())",
        instance,
        journal);
    jdbc.update(
        "INSERT INTO inference_calls(call_id,instance_id,attribution,scope,ancestor_conversation_ids,ancestor_run_ids,ancestor_orchestration_ids,operation,attribution_status,specifier,pool,wire_model,billing_route,lane,created_at,last_event_at,ended_at,lifecycle,last_call_sequence) VALUES(?,?,'{}','GLOBAL','{}','{}','{}','legacy','LEGACY_UNATTRIBUTED','fixture','fixture','fixture','fixture','CHAT',now(),now(),now(),'SUCCEEDED',1)",
        call,
        instance);
    jdbc.update(
        "INSERT INTO inference_attempts(attempt_id,call_id,attempt_number,started_at,ended_at,outcome,cost_kind,cost_amount,cost_currency) VALUES(?,?,1,now(),now(),'SUCCEEDED','KNOWN',0.012345,'USD')",
        UUID.randomUUID(),
        call);
  }
}
