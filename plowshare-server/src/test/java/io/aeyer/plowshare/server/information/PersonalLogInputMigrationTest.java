package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises retained selector repair across an actual Flyway upgrade and live disclosure gates. */
@Tag("full-db")
@Testcontainers
class PersonalLogInputMigrationTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  @Test
  void repairs_only_own_account_inputs_selected_through_the_owners_personal_project() {
    var source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    Flyway.configure().dataSource(source).target("180").load().migrate();
    var jdbc = new JdbcTemplate(source);
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password) VALUES('alice','fixture',false),('bob','fixture',false)");
    String personal = PersonalSpaces.name("alice"), foreign = PersonalSpaces.name("bob");
    jdbc.update(
        "INSERT INTO projects(name,personal_owner) VALUES(?,'alice'),(?,'bob')", personal, foreign);
    jdbc.update("INSERT INTO projects(name) VALUES('ordinary')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'alice','MANAGER' FROM projects WHERE name IN (?,'ordinary')",
        personal);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'bob','MANAGER' FROM projects WHERE name=?",
        foreign);

    UUID account = revision(jdbc, "alice", null),
        workspace = revision(jdbc, "alice", personal),
        otherAccount = revision(jdbc, "bob", null),
        deleted = revision(jdbc, "alice", null),
        derived = revision(jdbc, "alice", null);
    jdbc.update("UPDATE information_revisions SET availability='deleted' WHERE id=?", deleted);
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) VALUES(?,?)",
        derived,
        otherAccount);
    input(jdbc, "repaired", account, personal, true);
    input(jdbc, "repaired", workspace, personal, true);
    input(jdbc, "foreign-document", otherAccount, personal, true);
    input(jdbc, "ordinary-project", account, "ordinary", true);
    input(jdbc, "foreign-personal", account, foreign, true);
    input(jdbc, "deleted", deleted, personal, false);
    input(jdbc, "derived", derived, personal, false);
    input(jdbc, "quarantined", account, personal, false);
    jdbc.update(
        "INSERT INTO information_quarantined_payloads VALUES('quarantined','fixture: incomplete ledger')");
    jdbc.update(
        "INSERT INTO information_job_inputs VALUES('already-personal','alice',?,'personal',NULL,false)",
        account);

    assertFalse(readable(jdbc, "repaired", "alice"));
    // The exact same source and dependency checks succeed in its owner's account selection.
    assertTrue(
        jdbc.queryForObject(
            "SELECT information_readable(?,'alice','personal',NULL,true)", Boolean.class, account));
    Flyway.configure().dataSource(source).load().migrate();

    assertTrue(readable(jdbc, "repaired", "alice"));
    assertFalse(readable(jdbc, "repaired", "bob"));
    assertSelection(jdbc, "repaired", account, "personal", null, true);
    assertSelection(jdbc, "repaired", workspace, "project", personal, true);
    assertSelection(jdbc, "ordinary-project", account, "project", "ordinary", true);
    assertSelection(jdbc, "foreign-personal", account, "project", foreign, true);
    assertSelection(jdbc, "foreign-document", otherAccount, "project", personal, true);
    assertSelection(jdbc, "already-personal", account, "personal", null, false);
    assertSelection(jdbc, "deleted", deleted, "personal", null, false);
    for (String log :
        new String[] {
          "ordinary-project",
          "foreign-personal",
          "foreign-document",
          "deleted",
          "derived",
          "quarantined"
        }) assertFalse(readable(jdbc, log, "alice"), log);
    assertTrue(readable(jdbc, "already-personal", "alice"));

    // Normalizing an address is not an authority lease: later source withdrawal still blocks it.
    jdbc.update("UPDATE information_revisions SET availability='deleted' WHERE id=?", account);
    assertFalse(readable(jdbc, "repaired", "alice"));
    Flyway.configure().dataSource(source).load().migrate();
    assertSelection(jdbc, "repaired", account, "personal", null, true);
    assertEquals(
        9, jdbc.queryForObject("SELECT count(*) FROM information_job_inputs", Integer.class));
  }

  private UUID revision(JdbcTemplate jdbc, String owner, String project) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) VALUES(?,?,?,?,(SELECT id FROM projects WHERE name=?),'source')",
        resource,
        project == null ? "account:" + owner : "project:" + project,
        resource.toString(),
        owner,
        project);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size) VALUES(?,?,1,'Fixture','text/plain','fixture',1)",
        revision,
        resource);
    return revision;
  }

  private void input(JdbcTemplate jdbc, String log, UUID revision, String project, boolean global) {
    jdbc.update(
        "INSERT INTO information_job_inputs VALUES(?,'alice',?,'project',?,?)",
        log,
        revision,
        project,
        global);
  }

  private boolean readable(JdbcTemplate jdbc, String log, String actor) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT information_log_readable(?,?)", Boolean.class, log, actor));
  }

  private void assertSelection(
      JdbcTemplate jdbc, String log, UUID revision, String scope, String project, boolean global) {
    assertTrue(
        jdbc.queryForObject(
            "SELECT owner_handle='alice' AND scope=? AND project_name IS NOT DISTINCT FROM ?::text AND include_shared=? FROM information_job_inputs WHERE job_id=? AND revision_id=?",
            Boolean.class,
            scope,
            project,
            global,
            log,
            revision));
  }
}
