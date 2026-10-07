package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("full-db")
@Testcontainers
class ApplicationRegistrationsTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() {
    var source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @Test
  void adoption_survives_repository_restart_and_project_rename_without_changing_legacy_rows() {
    jdbc.update("INSERT INTO projects(name) VALUES ('chatbot'), ('legacy')");
    var registrations = new JdbcApplicationRegistrations(jdbc);
    assertFalse(registrations.required("chatbot"));
    registrations.require("chatbot");
    registrations.require("chatbot");
    assertTrue(new JdbcApplicationRegistrations(jdbc).required("chatbot"));
    jdbc.update("UPDATE projects SET name='renamed' WHERE name='chatbot'");
    assertTrue(registrations.required("renamed"));
    assertFalse(registrations.required("legacy"));
    assertThrows(ArchiveRefusedException.class, () -> registrations.require("missing"));
    assertFalse(registrations.required("missing"));
  }

  @Test
  void token_authority_uses_the_active_service_owner_and_revocation_closes_it() {
    String principal = "@service/application-fixture";
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin,must_change_password,account_kind) VALUES ('integration','fixture',FALSE,FALSE,'SERVICE'), (?, 'fixture',FALSE,FALSE,'SERVICE_TOKEN')",
        principal);
    jdbc.update(
        "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at) VALUES (?, 'integration','application',?,'application-fixture',now()+interval '1 day')",
        java.util.UUID.randomUUID(),
        principal);
    var members = new JdbcProjectMembers(jdbc);
    assertEquals(java.util.Optional.of("integration"), members.authorityAccount(principal));
    jdbc.update("UPDATE service_tokens SET revoked_at=now() WHERE principal_handle=?", principal);
    assertTrue(members.authorityAccount(principal).isEmpty());
  }
}
