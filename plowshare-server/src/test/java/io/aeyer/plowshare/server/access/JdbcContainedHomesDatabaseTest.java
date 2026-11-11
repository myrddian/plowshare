package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link JdbcContainedHomes} against the real {@code contained_own_home}.
 *
 * <p><strong>Why this needs a database.</strong> Every property under test belongs to a SQL
 * function and to the rows it joins: that ownership is the principal's single {@code scope_id} row
 * and not an ancestor's or a sibling's, that the {@code contained_knowledge_actor} gate makes a
 * non-UUID handle and an ordinary account own nothing, and that a NULL result is read as "owns
 * nothing" rather than as an error. A mock of the function would restate the implementation's
 * assumptions instead of checking them, which is the specific failure the gate test above it avoids
 * by not trying.
 *
 * <p>The sibling case is the one that matters most. Two children under one container means "a child
 * does not own its sibling's home" is a measurement rather than a shape, and it is the assertion
 * that fails if the function is ever loosened to match on the container instead of the scope.
 */
@Tag("full-db")
@Testcontainers
class JdbcContainedHomesDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private JdbcTemplate jdbc;
  private ContainedHomes homes;
  private UUID parentScope;

  /** One contained Application's durable identity, as this fixture inserts it. */
  private record Child(String project, UUID scope, String principal) {}

  private Child first;
  private Child second;

  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "contained_homes_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);
    homes = new JdbcContainedHomes(jdbc);

    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password) VALUES ('alice','fixture',FALSE)");
    jdbc.update("INSERT INTO projects(name) VALUES ('parent')");
    long parent = jdbc.queryForObject("SELECT id FROM projects WHERE name='parent'", Long.class);
    parentScope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parent);
    first = child();
    second = child();
  }

  private Child child() {
    String project = "contained:" + UUID.randomUUID();
    jdbc.update("INSERT INTO projects(name) VALUES (?)", project);
    long id = jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, project);
    UUID scope = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES (?,'CONTAINED',?,?)",
        scope,
        id,
        parentScope);
    jdbc.update("INSERT INTO contained_principals(scope_id) VALUES (?)", scope);
    return new Child(
        project,
        scope,
        jdbc.queryForObject(
                "SELECT id FROM contained_principals WHERE scope_id=?", UUID.class, scope)
            .toString());
  }

  @Test
  void aChildOwnsItsOwnHome() {
    assertTrue(homes.ownsHome(first.principal(), first.project()));
    assertTrue(homes.ownsHome(second.principal(), second.project()));
  }

  @Test
  void aChildDoesNotOwnItsSiblingsHome() {
    assertFalse(homes.ownsHome(first.principal(), second.project()));
    assertFalse(homes.ownsHome(second.principal(), first.project()));
  }

  @Test
  void aChildDoesNotOwnItsContainersProject() {
    assertFalse(homes.ownsHome(first.principal(), "parent"));
  }

  /** The {@code contained_knowledge_actor} gate: an ordinary account owns no contained home. */
  @Test
  void anOrdinaryAccountOwnsNothing() {
    assertFalse(homes.ownsHome("alice", first.project()));
    assertFalse(homes.ownsHome("alice", "parent"));
  }

  /**
   * A UUID that is not a live contained principal owns nothing, and a non-UUID string reaches the
   * function's guarded cast without raising — the case that would turn a refusal into a 500.
   */
  @Test
  void anUnknownPrincipalAndAMalformedOneBothOwnNothing() {
    assertFalse(homes.ownsHome(UUID.randomUUID().toString(), first.project()));
    assertFalse(homes.ownsHome("1-1-1-1-1", first.project()));
    assertFalse(homes.ownsHome("not-a-uuid", first.project()));
  }

  /** Blank and null arguments own nothing and never reach the database. */
  @Test
  void blankArgumentsOwnNothing() {
    assertFalse(homes.ownsHome(null, first.project()));
    assertFalse(homes.ownsHome(first.principal(), null));
    assertFalse(homes.ownsHome("  ", first.project()));
    assertFalse(homes.ownsHome(first.principal(), " "));
  }

  /** A project that does not exist owns nothing rather than raising. */
  @Test
  void anAbsentProjectOwnsNothing() {
    assertFalse(homes.ownsHome(first.principal(), "contained:" + UUID.randomUUID()));
  }
}
