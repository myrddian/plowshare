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

/** Real home/provenance constraints, admitted SQL effects and expected-head concurrency. */
@Tag("full-db")
@Testcontainers
class KnowledgeScopePersistenceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  JdbcTemplate jdbc;
  UnitOfWork work;
  JdbcInformationCatalogueRepository records;
  JdbcInformationContentRepository content;

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
        "INSERT INTO admins(handle,password_hash) VALUES('creator','fixture'),('writer','fixture'),('reader','fixture'),('outsider','fixture')");
    jdbc.update("INSERT INTO projects(name) VALUES('one'),('two')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'creator','MANAGER' FROM projects WHERE name='one'");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'writer','CONTRIBUTOR' FROM projects WHERE name='one'");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','VIEWER' FROM projects WHERE name='one'");
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    records =
        new JdbcInformationCatalogueRepository(
            jdbc, Clock.systemUTC(), new RecordedInformationEvents());
    content =
        new JdbcInformationContentRepository(jdbc, new JdbcInformationContentTypeRepository(jdbc));
  }

  InformationContext project(String actor) {
    return new InformationContext(actor, InformationAccess.projectSelection(actor, "one"))
        .withView(true);
  }

  InformationContext personal(String actor) {
    return new InformationContext(actor, InformationContext.Selection.personal()).withView(true);
  }

  InformationCatalogue.Admission admit(
      InformationContext context, String name, UUID expected, List<UUID> inputs) {
    byte[] bytes = ("body " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    return records.admit(
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
            expected,
            true));
  }

  InformationCatalogue.Admission original() {
    return work.inTransaction(() -> admit(project("creator"), "shared.md", null, List.of()));
  }

  UUID account(String actor) {
    return jdbc.queryForObject(
        "SELECT id FROM authority_accounts WHERE legacy_handle=?", UUID.class, actor);
  }

  String provenance(String table, UUID id) {
    if (!Set.of("information_resources", "information_revisions").contains(table))
      throw new IllegalArgumentException();
    return jdbc.queryForObject(
        "SELECT created_by::text FROM " + table + " WHERE id=?", String.class, id);
  }

  @Test
  void contributor_revises_and_retires_without_becoming_creator_or_manager() {
    var first = original();
    var next =
        work.inTransaction(
            () -> admit(project("writer"), "shared.md", first.revision(), List.of()));
    assertEquals(first.resource(), next.resource());
    assertEquals(
        account("creator").toString(), provenance("information_resources", first.resource()));
    assertEquals(
        account("creator").toString(), provenance("information_revisions", first.revision()));
    assertEquals(
        account("writer").toString(), provenance("information_revisions", next.revision()));
    assertEquals("creator", records.row(next.revision()).ownerHandle());
    assertEquals(
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=(SELECT id FROM projects WHERE name='one')",
            UUID.class),
        work.inTransaction(() -> records.lockResource(project("writer"), next.revision(), false))
            .home()
            .value());
    work.inTransaction(
        () -> {
          records.tags(project("writer"), next.revision(), List.of("reviewed"));
          records.availability(project("writer"), next.revision(), "withdrawn");
          return null;
        });
    assertEquals("withdrawn", records.row(next.revision()).availability());
    assertEquals(
        "CONTRIBUTOR", new JdbcProjectMembers(jdbc).role("one", "writer").orElseThrow().name());
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> {
                  records.share(project("writer"), next.revision(), false);
                  return null;
                }));
  }

  @Test
  void reader_unrelated_and_wrong_selection_cannot_write_even_with_a_known_revision() {
    var first = original();
    assertTrue(records.readable(project("reader"), first.revision()));
    assertTrue(records.readable(project("writer"), first.revision()));
    assertFalse(records.readable(project("outsider"), first.revision()));
    for (var context :
        List.of(
            project("reader"),
            project("outsider"),
            personal("creator"),
            new InformationContext("writer", InformationContext.Selection.project("two")),
            new InformationContext("creator", InformationContext.Selection.globalRead()))) {
      assertThrows(
          NotFoundFault.class,
          () -> work.inTransaction(() -> records.lockResource(context, first.revision(), false)));
    }
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> admit(project("reader"), "shared.md", first.revision(), List.of())));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_revisions WHERE resource_id=?",
            Integer.class,
            first.resource()));
  }

  @Test
  void original_creator_loses_write_on_demotion_and_cannot_change_audience() {
    var first = original();
    jdbc.update("UPDATE project_members SET role='CONTRIBUTOR' WHERE handle='creator'");
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> records.lockResource(project("creator"), first.revision(), true)));
    jdbc.update("UPDATE project_members SET role='VIEWER' WHERE handle='creator'");
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> records.lockResource(project("creator"), first.revision(), false)));
  }

  @Test
  void mandatory_owner_only_and_private_inputs_block_non_creator_mutations() {
    var first = original();
    jdbc.update(
        "INSERT INTO information_navigation_policies(policy_key,version,settings,actor) VALUES(?,1,?::jsonb,'creator')",
        "document:" + first.resource(),
        "{\"defaults\":{\"ownerOnly\":true,\"referenceHidden\":true,\"propagate\":true},\"mandatory\":{\"ownerOnly\":false,\"referenceHidden\":false,\"propagate\":false},\"allowOverrides\":true,\"referenceLabel\":\"Restricted\"}");
    assertFalse(records.readable(project("writer"), first.revision()));
    assertFalse(records.readable(project("reader"), first.revision()));
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> admit(project("writer"), "shared.md", first.revision(), List.of())));
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> {
                  records.availability(project("writer"), first.revision(), "deleted");
                  return null;
                }));
    var privateSource =
        work.inTransaction(() -> admit(personal("creator"), "private.md", null, List.of()));
    jdbc.update("DELETE FROM information_navigation_policies");
    jdbc.update(
        "INSERT INTO information_inputs VALUES(?,?)", first.revision(), privateSource.revision());
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> records.lockResource(project("writer"), first.revision(), false)));
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> {
                  content.requireWrite(project("writer"), first.revision());
                  return null;
                }));
  }

  @Test
  void manager_control_and_generation_authorization_remain_distinct_from_write() {
    var first = original();
    work.inTransaction(
        () -> {
          content.requireWrite(project("writer"), first.revision());
          content.authorise(
              project("writer"), first.revision(), UUID.randomUUID(), List.of("extract"));
          return null;
        });
    assertEquals(
        "writer",
        jdbc.queryForObject(
            "SELECT actor FROM information_stage_authorizations WHERE revision_id=?",
            String.class,
            first.revision()));
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> records.lockResource(project("writer"), first.revision(), true)));
    assertNotNull(
        work.inTransaction(() -> records.lockResource(project("creator"), first.revision(), true)));
  }

  @Test
  void home_and_creator_rewrites_are_rejected_and_rollback_leaves_no_new_revision() {
    var first = original();
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_resources SET created_by=? WHERE id=?",
                account("writer"),
                first.resource()));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_revisions SET created_by=? WHERE id=?",
                account("writer"),
                first.revision()));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_resources SET home_scope_id=(SELECT id FROM authority_scopes WHERE project_id=(SELECT id FROM projects WHERE name='two')) WHERE id=?",
                first.resource()));
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  admit(project("writer"), "shared.md", first.revision(), List.of());
                  throw new IllegalStateException("rollback");
                }));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_revisions WHERE resource_id=?",
            Integer.class,
            first.resource()));
  }

  @Test
  void concurrent_contributors_cannot_both_replace_the_expected_head() throws Exception {
    var first = original();
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++)
        results.add(
            executor.submit(
                () -> {
                  start.await();
                  try {
                    work.inTransaction(
                        () -> admit(project("writer"), "shared.md", first.revision(), List.of()));
                    return true;
                  } catch (CallerFault conflict) {
                    assertEquals("INFORMATION_REVISION_CONFLICT", conflict.getMessage());
                    return false;
                  }
                }));
      start.countDown();
      assertEquals(
          1,
          (results.get(0).get(15, TimeUnit.SECONDS) ? 1 : 0)
              + (results.get(1).get(15, TimeUnit.SECONDS) ? 1 : 0));
    }
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_revisions WHERE resource_id=?",
            Integer.class,
            first.resource()));
  }

  @Test
  void audience_changes_need_manager_and_old_owner_based_writers_fail_closed() {
    var first = original();
    jdbc.update(
        "INSERT INTO documents(id,source_name,title,content_hash,text_hash,byte_size,ingested_at,ingested_by,information_namespace) VALUES(?,'shared.md','Shared','fixture','fixture',8,now(),'creator',?)",
        first.revision(),
        first.revision().toString());
    jdbc.update(
        "INSERT INTO information_document_policies(document_id,owner_handle,visibility,project_id,assigned_at) SELECT ?,'creator','project',id,now() FROM projects WHERE name='one'",
        first.revision());
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_document_policies SET visibility='shared',project_id=NULL WHERE document_id=?",
                first.revision()));
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> {
                  records.share(project("writer"), first.revision(), true);
                  return null;
                }));
    work.inTransaction(
        () -> {
          records.share(project("creator"), first.revision(), true);
          return null;
        });
    assertEquals(
        "GLOBAL_READ",
        jdbc.queryForObject(
            "SELECT audience FROM information_resource_policies WHERE resource_id=?",
            String.class,
            first.resource()));
    assertEquals(
        "project",
        jdbc.queryForObject(
            "SELECT visibility FROM information_document_policies WHERE document_id=?",
            String.class,
            first.revision()));
    work.inTransaction(
        () -> {
          records.share(project("creator"), first.revision(), false);
          return null;
        });
    assertEquals(
        "project",
        jdbc.queryForObject(
            "SELECT visibility FROM information_document_policies WHERE document_id=?",
            String.class,
            first.revision()));
  }

  @Test
  void metadata_revision_records_the_actual_writer_without_changing_resource_provenance() {
    var first = original();
    var projections = new JdbcInformationProjectionRepository(jdbc, content);
    UUID request = UUID.randomUUID();
    work.inTransaction(
        () ->
            new JdbcInformationGateRepository(jdbc, Clock.systemUTC())
                .claim(
                    "writer",
                    request,
                    "d".repeat(64),
                    "content.revise",
                    InformationCatalogue.Admission.class));
    var prepared =
        work.inTransaction(
            () ->
                projections.prepare(project("writer"), request, first.revision(), null, Map.of()));
    var copied =
        work.inTransaction(() -> projections.copy(project("writer"), request, prepared, Map.of()));
    assertEquals(first.resource(), copied.resource());
    assertEquals(
        account("writer").toString(), provenance("information_revisions", copied.revision()));
    assertEquals(
        account("creator").toString(), provenance("information_resources", copied.resource()));
    assertEquals(
        "explicit_only",
        jdbc.queryForObject(
            "SELECT processing_policy FROM information_revisions WHERE id=?",
            String.class,
            copied.revision()));
  }

  @Test
  void scoped_service_token_is_bounded_by_owner_and_current_token_ceiling() {
    var first = original();
    String principal = "@service/" + UUID.randomUUID();
    UUID token = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,account_kind,must_change_password,server_admin) VALUES('worker','fixture','SERVICE',false,false),(?,'fixture','SERVICE_TOKEN',false,false)",
        principal);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'worker','CONTRIBUTOR' FROM projects WHERE name='one'");
    jdbc.update(
        "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at) VALUES(?,'worker','fixture',?,'fixture',now()+interval '1 day')",
        token,
        principal);
    jdbc.update(
        "INSERT INTO service_token_scopes(token_id,project_id,role) SELECT ?,id,'CONTRIBUTOR' FROM projects WHERE name='one'",
        token);
    assertNotNull(
        work.inTransaction(
            () -> records.lockResource(project(principal), first.revision(), false)));
    jdbc.update("UPDATE service_token_scopes SET role='VIEWER' WHERE token_id=?", token);
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> records.lockResource(project(principal), first.revision(), false)));
    jdbc.update("UPDATE service_token_scopes SET role='CONTRIBUTOR' WHERE token_id=?", token);
    jdbc.update("UPDATE project_members SET role='VIEWER' WHERE handle='worker'");
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> records.lockResource(project(principal), first.revision(), false)));
  }

  @Test
  void retained_reference_obligations_prevent_mutation_after_dependency_access_is_lost() {
    var first = original();
    var target = work.inTransaction(() -> admit(personal("creator"), "target.md", null, List.of()));
    UUID edge = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_relationships(id,source,target,relation,actor) VALUES(?,?,?,'references','creator')",
        edge,
        first.resource(),
        target.resource());
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> records.lockResource(project("writer"), first.revision(), false)));
    jdbc.update("UPDATE information_relationships SET removed=true WHERE id=?", edge);
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> records.lockResource(project("writer"), first.revision(), false)));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_reference_obligations WHERE relationship=?",
            Integer.class,
            edge));
  }

  @Test
  void revocation_waits_for_the_admitted_mutation_transaction() throws Exception {
    var first = original();
    var admitted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var mutation =
          executor.submit(
              () ->
                  work.inTransaction(
                      () -> {
                        records.lockResource(project("writer"), first.revision(), false);
                        admitted.countDown();
                        try {
                          if (!release.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("release timeout");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        records.tags(project("writer"), first.revision(), List.of("committed"));
                        return true;
                      }));
      assertTrue(admitted.await(10, TimeUnit.SECONDS));
      var revocation =
          executor.submit(() -> new JdbcProjectMembers(jdbc).remove("one", "writer", "creator"));
      try {
        assertThrows(TimeoutException.class, () -> revocation.get(200, TimeUnit.MILLISECONDS));
      } finally {
        release.countDown();
      }
      assertTrue(mutation.get(10, TimeUnit.SECONDS));
      revocation.get(10, TimeUnit.SECONDS);
    }
    assertThrows(
        NotFoundFault.class,
        () ->
            work.inTransaction(
                () -> records.lockResource(project("writer"), first.revision(), false)));
  }

  @Test
  void migration_recovers_only_actual_creation_evidence_and_preserves_unknown_history() {
    var upgrade =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    String schema = "knowledge_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(upgrade).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    upgrade.setConnectionProperties(properties);
    Flyway.configure()
        .dataSource(upgrade)
        .schemas(schema)
        .defaultSchema(schema)
        .target("156")
        .load()
        .migrate();
    var old = new JdbcTemplate(upgrade);
    old.update(
        "INSERT INTO admins(handle,password_hash) VALUES('custodian','fixture'),('actual','fixture')");
    old.update("INSERT INTO projects(name) VALUES('legacy-area')");
    UUID evidenced = UUID.randomUUID(), unknown = UUID.randomUUID(), conflicted = UUID.randomUUID();
    for (UUID id : List.of(evidenced, unknown, conflicted)) {
      old.update(
          "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) SELECT ?,'project:legacy-area',?,'custodian',id,'source' FROM projects WHERE name='legacy-area'",
          id,
          id.toString());
      old.update(
          "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,source_bytes) VALUES(?,?,1,'retained','text/plain','fixture',1,?)",
          id,
          id,
          new byte[] {1});
    }
    old.update(
        "INSERT INTO information_requests VALUES('actual',?,'fixture',?)",
        UUID.randomUUID(),
        evidenced);
    old.update(
        "INSERT INTO information_requests VALUES('actual',?,'fixture',?)",
        UUID.randomUUID(),
        conflicted);
    old.update(
        "INSERT INTO information_events(revision_id,generation,actor_handle,stage,action) VALUES(?,1,'custodian','intake','retained')",
        conflicted);
    old.update(
        "INSERT INTO memories(id,summary,scope,body,state,formed_at,formed_by,formed_where) VALUES('old-global','retained','scope','original','active',now(),'worker-agent','conversation:evidence')");
    UUID root =
        old.queryForObject(
            "SELECT id FROM authority_grants WHERE provenance='ROOT_POLICY'", UUID.class);
    Flyway.configure().dataSource(upgrade).schemas(schema).defaultSchema(schema).load().migrate();
    UUID actual =
        old.queryForObject(
            "SELECT id FROM authority_accounts WHERE legacy_handle='actual'", UUID.class);
    for (String table : List.of("information_resources", "information_revisions")) {
      assertEquals(
          actual,
          old.queryForObject(
              "SELECT created_by FROM " + table + " WHERE id=?", UUID.class, evidenced));
      for (UUID id : List.of(unknown, conflicted))
        assertNull(
            old.queryForObject("SELECT created_by FROM " + table + " WHERE id=?", UUID.class, id));
    }
    assertEquals(
        "custodian",
        old.queryForObject(
            "SELECT owner_handle FROM information_resources WHERE id=?", String.class, evidenced));
    assertEquals(
        "00000000-0000-0000-0000-000000000005",
        old.queryForObject(
            "SELECT home_scope_id::text FROM memories WHERE id='old-global'", String.class));
    assertEquals(
        "conversation:evidence",
        old.queryForObject(
            "SELECT formed_where FROM memories WHERE id='old-global'", String.class));
    assertEquals(
        root,
        old.queryForObject(
            "SELECT id FROM authority_grants WHERE provenance='ROOT_POLICY'", UUID.class));
    Flyway.configure().dataSource(upgrade).schemas(schema).defaultSchema(schema).load().migrate();
    assertEquals(
        actual,
        old.queryForObject(
            "SELECT created_by FROM information_resources WHERE id=?", UUID.class, evidenced));
  }

  @Test
  void project_rename_retains_the_home_and_existing_source_identity() {
    var first = original();
    UUID home =
        work.inTransaction(() -> records.lockResource(project("writer"), first.revision(), false))
            .home()
            .value();
    jdbc.update("UPDATE projects SET name='renamed' WHERE name='one'");
    var renamed =
        new InformationContext("writer", InformationContext.Selection.project("renamed"))
            .withView(true);
    assertEquals(
        home,
        work.inTransaction(() -> records.lockResource(renamed, first.revision(), false))
            .home()
            .value());
    assertEquals(
        first.revision(),
        content.head("project:renamed", "shared.md", false).orElseThrow().revision());
    var revised =
        work.inTransaction(() -> admit(renamed, "shared.md", first.revision(), List.of()));
    assertEquals(first.resource(), revised.resource());
    work.inTransaction(
        () -> {
          records.tags(renamed, revised.revision(), List.of("same-home"));
          return null;
        });
    assertEquals(
        "project:one",
        jdbc.queryForObject(
            "SELECT namespace FROM information_resources WHERE id=?",
            String.class,
            first.resource()));
  }
}
