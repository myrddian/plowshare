package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Upgrades retained application input selections without rewriting attribution or grants. */
@Tag("full-db")
@Testcontainers
class RetainedLogReadAuthorityMigrationTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  @Test
  void existingApplicationReaderUsesTheSameAclBeforeAndAfterUpgrade() {
    var source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    Flyway.configure().dataSource(source).target("234").load().migrate();
    var jdbc = new JdbcTemplate(source);
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin,must_change_password) VALUES('reader','fixture',true,false),('creator','fixture',false,false)");
    jdbc.update("INSERT INTO projects(name) VALUES('application')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','VIEWER' FROM projects WHERE name='application'");
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) SELECT ?,'project:application','fixture.txt','creator',id,'source' FROM projects WHERE name='application'",
        resource);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size) VALUES(?,?,1,'Fixture','text/plain','fixture',1)",
        revision,
        resource);
    jdbc.update(
        "INSERT INTO conversations(id,created_at,origin,budget_total,budget_spent,owner_handle,project_id) SELECT 'service-log',now(),'turn',100,0,'creator',id FROM projects WHERE name='application'");
    jdbc.update(
        "INSERT INTO information_job_inputs VALUES('service-log','creator',?,'project','application',true)",
        revision);
    assertTrue(
        jdbc.queryForObject(
            "SELECT information_readable(?,'reader','project','application',true)",
            Boolean.class,
            revision));
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_log_readable('service-log','reader')", Boolean.class));

    Flyway.configure().dataSource(source).load().migrate();
    assertTrue(
        jdbc.queryForObject(
            "SELECT information_log_readable('service-log','reader')", Boolean.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_unreadable_logs('reader') WHERE log='service-log'",
            Integer.class));
    assertEquals(
        "creator",
        jdbc.queryForObject(
            "SELECT owner_handle FROM information_job_inputs WHERE job_id='service-log'",
            String.class));
    assertEquals(
        "VIEWER",
        jdbc.queryForObject("SELECT authority_project_role('application','reader')", String.class));

    jdbc.update("DELETE FROM project_members WHERE handle='reader'");
    assertTrue(
        jdbc.queryForObject(
            "SELECT server_admin FROM admins WHERE handle='reader'", Boolean.class));
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_log_readable('service-log','reader')", Boolean.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_unreadable_logs('reader') WHERE log='service-log'",
            Integer.class));
  }
}
