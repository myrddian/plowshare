package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.archive.*;
import java.time.Clock;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Migrates real V157 revision flags without manufacturing universal service grants. */
@Tag("full-db")
@Testcontainers
class RootDocumentPolicyMigrationTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  @Test
  void legacy_shared_mixed_and_conflicting_privacy_are_reconciled_without_audience_union() {
    var source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    Flyway.configure().dataSource(source).target("157").load().migrate();
    var jdbc = new JdbcTemplate(source);
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password) VALUES('manager','fixture',false),('outside','fixture',false),('other','fixture',false)");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,account_kind) VALUES('app','fixture',false,'SERVICE')");
    jdbc.update("INSERT INTO projects(name) VALUES('one')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'manager','MANAGER' FROM projects WHERE name='one'");
    UUID consistent = root(jdbc, "consistent"),
        mixed = root(jdbc, "mixed"),
        conflict = root(jdbc, "conflict"),
        unknown = UUID.randomUUID();
    UUID shared = revision(jdbc, consistent, 1, "shared", "manager"),
        old = revision(jdbc, mixed, 1, "shared", "manager");
    revision(jdbc, mixed, 2, "project", "manager");
    revision(jdbc, conflict, 1, "personal", "other");
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,kind) VALUES(?,'legacy:unknown','unmapped','source')",
        unknown);
    Flyway.configure().dataSource(source).load().migrate();
    assertEquals("HOME", audience(jdbc, consistent));
    assertEquals("HOME", audience(jdbc, mixed));
    assertEquals("QUARANTINED", audience(jdbc, conflict));
    assertEquals("QUARANTINED", audience(jdbc, unknown));
    assertTrue(
        jdbc.queryForObject(
            "SELECT needs_review FROM information_resource_policies WHERE resource_id=?",
            Boolean.class,
            mixed));
    var records =
        new JdbcInformationCatalogueRepository(
            jdbc, Clock.systemUTC(), new RecordedInformationEvents());
    var human =
        new InformationContext("outside", InformationContext.Selection.globalRead()).withView(true);
    var app =
        new InformationContext("app", InformationContext.Selection.globalRead()).withView(true);
    assertFalse(records.readable(human, shared));
    assertFalse(records.readable(app, shared));
    assertFalse(records.readable(human, old));
    var work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    work.inTransaction(
        () -> {
          records.share(
              new InformationContext(
                      "manager", InformationAccess.projectSelection("manager", "one"))
                  .withView(true),
              shared,
              true);
          return null;
        });
    assertTrue(records.readable(app, shared));
    assertEquals(
        "shared",
        jdbc.queryForObject(
            "SELECT visibility FROM information_document_policies WHERE document_id=?",
            String.class,
            shared));
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_resource_policy_history WHERE resource_id=?",
            Integer.class,
            consistent));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_grants g JOIN authority_roles r ON r.id=g.role_id WHERE r.name='GLOBAL_READ'",
            Integer.class));
    // A new compatibility projection cannot grant an audience by inserting another Shared flag.
    UUID later = root(jdbc, "later");
    UUID laterRevision = revision(jdbc, later, 1, "shared", "manager");
    assertEquals("HOME", audience(jdbc, later));
    assertFalse(records.readable(human, laterRevision));
    UUID raw = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO documents(id,source_name,title,content_hash,text_hash,byte_size,ingested_at,ingested_by) VALUES(?,?,'Raw','raw','raw',1,now(),'manager')",
        raw,
        raw.toString());
    jdbc.update(
        "INSERT INTO information_document_policies(document_id,owner_handle,visibility,assigned_at) VALUES(?,'manager','shared',now())",
        raw);
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_readable(?,'outside','global_read',NULL,false)",
            Boolean.class,
            raw));
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_readable(?,'app','global_read',NULL,false)", Boolean.class, raw));
    // Idempotent startup leaves the reviewed root and immutable evidence intact.
    Flyway.configure().dataSource(source).load().migrate();
    assertEquals("GLOBAL_READ", audience(jdbc, consistent));
    assertTrue(records.readable(app, shared));
  }

  private UUID root(JdbcTemplate jdbc, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) SELECT ?,'project:one',?,'manager',id,'source' FROM projects WHERE name='one'",
        id,
        name);
    return id;
  }

  private UUID revision(
      JdbcTemplate jdbc, UUID resource, int ordinal, String visibility, String owner) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size) VALUES(?,?,?,'title','text/plain','fixture',1)",
        id,
        resource,
        ordinal);
    jdbc.update(
        "INSERT INTO documents(id,source_name,title,content_hash,text_hash,byte_size,ingested_at,ingested_by,information_namespace) VALUES(?,?,'title',?,'fixture',1,now(),'fixture','project:one')",
        id,
        id.toString(),
        id.toString());
    jdbc.update(
        "INSERT INTO information_document_policies(document_id,owner_handle,visibility,project_id,assigned_at) SELECT ?,?,?,CASE WHEN ?='project' THEN id ELSE NULL END,now() FROM projects WHERE name='one'",
        id,
        owner,
        visibility,
        visibility);
    return id;
  }

  private String audience(JdbcTemplate jdbc, UUID root) {
    return jdbc.queryForObject(
        "SELECT audience FROM information_resource_policies WHERE resource_id=?",
        String.class,
        root);
  }
}
