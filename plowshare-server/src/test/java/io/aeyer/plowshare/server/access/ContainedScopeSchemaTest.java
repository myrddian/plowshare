package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Real PostgreSQL is required here and nowhere else in this change: the assertions are the Flyway
 * migration applying over the shipped schema and the per-kind CHECK constraints themselves
 * rejecting malformed containment rows. Neither a mocked repository nor an in-memory fake can
 * evaluate a SQL CHECK, and the constraint is the fence, so it is proved where it lives.
 */
@Tag("full-db")
@Testcontainers
class ContainedScopeSchemaTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private JdbcTemplate jdbc;
  private long parentProject;
  private long childProject;
  private UUID parentScope;

  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "contained_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);
    parentProject = project("parent");
    childProject = project("child");
    parentScope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parentProject);
    // Ordinary project creation already maps every project to one PROJECT scope, and project_id is
    // UNIQUE on authority_scopes, so a contained scope replaces that mapping rather than joining
    // it. Releasing it here keeps this test to the CHECK constraints; deciding how a child project
    // acquires a contained mapping instead of a project one belongs to the task that creates
    // children.
    assertEquals(1, jdbc.update("DELETE FROM authority_scopes WHERE project_id=?", childProject));
  }

  private long project(String name) {
    jdbc.update("INSERT INTO projects(name) VALUES (?)", name);
    return jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, name);
  }

  private int insert(UUID id, String kind, Long project, UUID container) {
    return jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES (?,?,?,?)",
        id,
        kind,
        project,
        container);
  }

  @Test
  void migrationAcceptsAContainedScopeWithAContainer() {
    var child = UUID.randomUUID();
    assertEquals(1, insert(child, "CONTAINED", childProject, parentScope));
    assertEquals(
        parentScope,
        jdbc.queryForObject(
            "SELECT container FROM authority_scopes WHERE id=?", UUID.class, child));
    // Containment nests: a contained scope is itself a legal container.
    var third = project("grandchild");
    assertEquals(1, jdbc.update("DELETE FROM authority_scopes WHERE project_id=?", third));
    assertEquals(1, insert(UUID.randomUUID(), "CONTAINED", third, child));
  }

  @Test
  void containedScopeWithoutAContainerViolatesTheCheck() {
    assertThrows(
        DataIntegrityViolationException.class,
        () -> insert(UUID.randomUUID(), "CONTAINED", childProject, null));
    // A contained scope is a project confinement, never an account identity.
    assertThrows(
        DataIntegrityViolationException.class,
        () -> insert(UUID.randomUUID(), "CONTAINED", null, parentScope));
  }

  @Test
  void nonContainedKindsRejectAContainer() {
    assertThrows(
        DataIntegrityViolationException.class,
        () -> insert(UUID.randomUUID(), "PROJECT", childProject, parentScope));
  }

  @Test
  void aScopeCannotContainItself() {
    var self = UUID.randomUUID();
    assertThrows(
        DataIntegrityViolationException.class, () -> insert(self, "CONTAINED", childProject, self));
  }

  @Test
  void aContainerCannotBeDroppedWhileItStillContainsAScope() {
    assertEquals(1, insert(UUID.randomUUID(), "CONTAINED", childProject, parentScope));
    // Refusal, not a silent cascade: descendants are destroyed by the capability that owns their
    // lifecycle, so the schema must not quietly collect them when a container disappears.
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("DELETE FROM authority_scopes WHERE id=?", parentScope));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("DELETE FROM projects WHERE id=?", parentProject));
  }

  @Test
  void existingKindsAndRowsAreUnchanged() {
    assertEquals(
        "PROJECT",
        jdbc.queryForObject(
            "SELECT kind FROM authority_scopes WHERE project_id=?", String.class, parentProject));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_scopes WHERE container IS NOT NULL", Long.class));
  }
}
