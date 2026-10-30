package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Real PostgreSQL is required and nothing here could be asserted otherwise: the assertions are the
 * V198 migration applying over the shipped schema, the {@code UNIQUE (container, request_id)} that
 * makes a retried create read its receipt rather than make a second child, the agreement trigger
 * that keeps the denormalised container from drifting from {@code authority_scopes}, the recursive
 * subtree count that the breadth bound reads, and the {@code ON DELETE CASCADE} from {@code
 * authority_scopes} that collects a record with its scope. A mocked repository cannot evaluate a
 * unique index, fire a trigger, run a recursive CTE or cascade a foreign key.
 *
 * <p>Every behavioural assertion about creation and refusal lives in {@code
 * ContainedApplicationCreationTest} and {@code ContainedManifestRefusalTest} against mocks, per the
 * database test policy. This is the smallest set that needs the real engine.
 */
@Tag("full-db")
@Testcontainers
class ContainedApplicationSchemaTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private JdbcTemplate jdbc;
  private JdbcContainedApplicationRepository repository;
  private ScopeId container;

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
    repository = new JdbcContainedApplicationRepository(jdbc);
    jdbc.update("INSERT INTO projects(name) VALUES ('parent')");
    Long project = jdbc.queryForObject("SELECT id FROM projects WHERE name='parent'", Long.class);
    container =
        new ScopeId(
            jdbc.queryForObject(
                "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, project));
  }

  private ContainedApplicationRepository.Creation creation(UUID requestId, String digest) {
    String name = "contained:" + UUID.randomUUID();
    return new ContainedApplicationRepository.Creation(
        container,
        requestId,
        digest.repeat(64).substring(0, 64),
        name,
        Path.of("/srv/plowshare/containment").resolve(name.substring("contained:".length())),
        UUID.randomUUID(),
        new AllowanceId(UUID.randomUUID()),
        5);
  }

  @Test
  void aCreatedChildCarriesItsProjectScopeAndRecordTogether() {
    var retained = repository.create(creation(UUID.randomUUID(), "a"));
    assertTrue(retained.project().startsWith("contained:"));
    assertEquals(container, retained.container());
    assertTrue(retained.live());
    // The project acquired no addressable PROJECT scope: V195 excludes the reserved prefix from
    // authority_map_project, so the only scope over this project is the explicit CONTAINED one.
    assertEquals(
        "CONTAINED",
        jdbc.queryForObject(
            "SELECT kind FROM authority_scopes WHERE project_id=?",
            String.class,
            retained.projectId()));
    assertEquals(1, repository.liveDescendants(container));
  }

  @Test
  void theSameRequestUuidCannotCreateASecondChild() {
    UUID requestId = UUID.randomUUID();
    var first = repository.create(creation(requestId, "b"));
    assertThrows(CallerFault.class, () -> repository.create(creation(requestId, "c")));
    assertEquals(first.id(), repository.receipt(container, requestId).orElseThrow().id());
    assertEquals(1, repository.liveDescendants(container));
  }

  @Test
  void aRecordCannotDisagreeWithItsAuthorityScope() {
    var retained = repository.create(creation(UUID.randomUUID(), "d"));
    jdbc.update("INSERT INTO projects(name) VALUES ('other')");
    Long other = jdbc.queryForObject("SELECT id FROM projects WHERE name='other'", Long.class);
    var stranger =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, other);
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE contained_applications SET container=? WHERE id=?",
                stranger,
                retained.id()));
  }

  @Test
  void theBreadthCountIsTheWholeSubtreeNotJustTheImmediateChildren() {
    var child = repository.create(creation(UUID.randomUUID(), "e"));
    String name = "contained:" + UUID.randomUUID();
    jdbc.update("INSERT INTO projects(name) VALUES (?)", name);
    Long project = jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, name);
    UUID grandchild = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES(?,'CONTAINED',?,?)",
        grandchild,
        project,
        child.scope().value());
    jdbc.update(
        "INSERT INTO contained_applications"
            + "(scope_id,container,project_id,request_id,fingerprint,revision,workspace,"
            + "allowance,ceiling) VALUES(?,?,?,?,?,?,?,?,?)",
        grandchild,
        child.scope().value(),
        project,
        UUID.randomUUID(),
        "f".repeat(64),
        UUID.randomUUID(),
        "/srv/plowshare/containment/" + name.substring("contained:".length()),
        UUID.randomUUID(),
        1);
    assertEquals(2, repository.liveDescendants(container));
    assertEquals(1, repository.liveDescendants(child.scope()));
  }

  @Test
  void removingTheScopeCollectsTheRecord() {
    var retained = repository.create(creation(UUID.randomUUID(), "a"));
    jdbc.update("DELETE FROM authority_scopes WHERE id=?", retained.scope().value());
    assertTrue(repository.find(retained.id()).isEmpty());
    assertEquals(0, repository.liveDescendants(container));
  }

  @Test
  void aChildCeilingIsExplicitAndNonNegative() {
    var retained = repository.create(creation(UUID.randomUUID(), "a"));
    repository.ceiling(retained.id(), 0);
    assertEquals(0, repository.find(retained.id()).orElseThrow().ceiling());
    assertThrows(CallerFault.class, () -> repository.ceiling(retained.id(), null));
    assertThrows(CallerFault.class, () -> repository.ceiling(retained.id(), -1));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE contained_applications SET ceiling=NULL WHERE id=?", retained.id()));
  }
}
