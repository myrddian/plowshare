package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Real PostgreSQL is required here and nowhere else in this change: the assertions are the Flyway
 * migration applying over the shipped schema, the per-kind CHECK constraints themselves rejecting
 * malformed containment rows, and the automatic project-to-scope mapping declining to touch a
 * contained project. Neither a mocked repository nor an in-memory fake can evaluate a SQL CHECK or
 * fire a trigger, and those are the fence, so they are proved where they live.
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
  private long ordinaryProject;
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
    ordinaryProject = project("ordinary");
    childProject = contained();
    parentScope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parentProject);
  }

  private long project(String name) {
    jdbc.update("INSERT INTO projects(name) VALUES (?)", name);
    return jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, name);
  }

  /** A child project's name carries only the fixed classification prefix; ancestry is a column. */
  private long contained() {
    return project("contained:" + UUID.randomUUID());
  }

  private int insert(UUID id, String kind, Long project, UUID container) {
    return jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES (?,?,?,?)",
        id,
        kind,
        project,
        container);
  }

  private long scopes(long project) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM authority_scopes WHERE project_id=?", Long.class, project);
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
    assertEquals(1, insert(UUID.randomUUID(), "CONTAINED", contained(), child));
  }

  @Test
  void containedScopeWithoutAContainerViolatesTheCheck() {
    assertThrows(
        DataIntegrityViolationException.class,
        () -> insert(UUID.randomUUID(), "CONTAINED", childProject, null));
    // A contained scope is a project confinement, never an account identity. With no project key
    // there is no contained project to find, so the shape trigger refuses before the CHECK is
    // reached; either refusal is the fence, and this asserts the one that actually fires.
    var refusal =
        assertThrows(
            DataAccessException.class,
            () -> insert(UUID.randomUUID(), "CONTAINED", null, parentScope));
    assertTrue(refusal.getMessage().contains("requires a contained project"), refusal.getMessage());
  }

  @Test
  void nonContainedKindsRejectAContainer() {
    assertEquals(
        1, jdbc.update("DELETE FROM authority_scopes WHERE project_id=?", ordinaryProject));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> insert(UUID.randomUUID(), "PROJECT", ordinaryProject, parentScope));
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

  /**
   * Ordinary project creation maps every project to one scope through an AFTER INSERT OR UPDATE
   * trigger. A contained project must be left alone, or it acquires an addressable project scope
   * that nobody named.
   */
  @Test
  void aContainedProjectIsNotMappedToAScopeAutomatically() {
    assertEquals(0L, scopes(childProject));
    assertEquals(1L, scopes(parentProject));
    assertEquals(1L, scopes(ordinaryProject));
  }

  /**
   * The case that would have been silent: the mapping is an upsert with {@code DO UPDATE SET
   * kind=EXCLUDED.kind} on a UNIQUE {@code project_id}, driven by a trigger that also fires on
   * UPDATE, so an unrelated later edit of the project row could retroactively convert a contained
   * scope back into an ordinary addressable one.
   */
  @Test
  void aContainedScopeSurvivesAnUpdateOfItsProjectRow() {
    var child = UUID.randomUUID();
    assertEquals(1, insert(child, "CONTAINED", childProject, parentScope));
    assertEquals(
        1, jdbc.update("UPDATE projects SET personal_owner=NULL WHERE id=?", childProject));
    assertEquals(
        1,
        jdbc.update(
            "UPDATE projects SET name='contained:'||gen_random_uuid() WHERE id=?", childProject));
    assertEquals(1L, scopes(childProject));
    assertEquals(
        "CONTAINED",
        jdbc.queryForObject("SELECT kind FROM authority_scopes WHERE id=?", String.class, child));
    assertEquals(
        parentScope,
        jdbc.queryForObject(
            "SELECT container FROM authority_scopes WHERE id=?", UUID.class, child));
  }

  @Test
  void aContainedScopeRequiresAContainedProject() {
    assertEquals(
        1, jdbc.update("DELETE FROM authority_scopes WHERE project_id=?", ordinaryProject));
    var refusal =
        assertThrows(
            DataAccessException.class,
            () -> insert(UUID.randomUUID(), "CONTAINED", ordinaryProject, parentScope));
    assertTrue(refusal.getMessage().contains("requires a contained project"), refusal.getMessage());
    // The mirror image: a contained project cannot be given an ordinary project scope either.
    assertThrows(
        DataAccessException.class, () -> insert(UUID.randomUUID(), "PROJECT", childProject, null));
  }

  @Test
  void aContainedScopeRequiresAnExistingContainer() {
    var refusal =
        assertThrows(
            DataAccessException.class,
            () -> insert(UUID.randomUUID(), "CONTAINED", childProject, UUID.randomUUID()));
    assertTrue(
        refusal.getMessage().contains("requires an existing container"), refusal.getMessage());
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
