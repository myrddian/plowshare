package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Real PostgreSQL is required because the assertions are the V197 migration applying over the
 * shipped schema, the {@code ON DELETE CASCADE} from {@code authority_scopes} collecting the
 * principal, the repository's insert-select guard against a stored CONTAINED row, and the table's
 * actual column set. A mock cannot evaluate a foreign-key cascade or inspect a schema.
 */
@Tag("full-db")
@Testcontainers
class ContainedPrincipalSchemaTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private JdbcTemplate jdbc;
  private JdbcContainedPrincipalRepository repository;
  private UUID parentScope;
  private long childProject;
  private UUID childScope;

  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "principal_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);
    repository = new JdbcContainedPrincipalRepository(jdbc);
    jdbc.update("INSERT INTO projects(name) VALUES ('parent')");
    var parentProject =
        jdbc.queryForObject("SELECT id FROM projects WHERE name='parent'", Long.class);
    parentScope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parentProject);
    jdbc.update("INSERT INTO projects(name) VALUES (?)", "contained:" + UUID.randomUUID());
    childProject =
        jdbc.queryForObject("SELECT id FROM projects WHERE name LIKE 'contained:%'", Long.class);
    childScope = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES (?,'CONTAINED',?,?)",
        childScope,
        childProject,
        parentScope);
  }

  private AuthorityScope.Contained scope() {
    return new AuthorityScope.Contained(
        new ScopeId(childScope), childProject, new ScopeId(parentScope));
  }

  @Test
  void theTableHoldsNoCredentialMaterial() {
    var columns =
        jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema=current_schema() AND table_name='contained_principals'"
                + " ORDER BY column_name",
            String.class);
    assertEquals(List.of("created_at", "id", "scope_id"), columns);
    var key = repository.issue(scope()).id().toString();
    // Not resolvable in any table an authentication path reads.
    for (var lookup :
        List.of(
            "SELECT count(*) FROM admins WHERE handle=?",
            "SELECT count(*) FROM authority_accounts WHERE id::text=? OR legacy_handle=?",
            "SELECT count(*) FROM service_tokens WHERE id::text=? OR principal_handle=?")) {
      var args = lookup.contains("OR") ? new Object[] {key, key} : new Object[] {key};
      assertEquals(0L, jdbc.queryForObject(lookup, Long.class, args), lookup);
    }
  }

  @Test
  void issueIsIdempotentAndDeletingTheScopeCascadesToThePrincipal() {
    var first = repository.issue(scope());
    assertEquals(first, repository.issue(scope()));
    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM contained_principals", Long.class));
    jdbc.update("DELETE FROM authority_scopes WHERE id=?", childScope);
    assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM contained_principals", Long.class));
  }

  @Test
  void deletingTheContainedProjectCollectsThePrincipalWithoutASecondPath() {
    repository.issue(scope());
    jdbc.update("DELETE FROM projects WHERE id=?", childProject);
    assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM contained_principals", Long.class));
  }

  @Test
  void revokeRemovesThePrincipalButLeavesTheScope() {
    repository.issue(scope());
    repository.revoke(new ScopeId(childScope));
    assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM contained_principals", Long.class));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_scopes WHERE id=?", Long.class, childScope));
  }

  @Test
  void issueRefusesAScopeThatIsNotStoredAsDescribed() {
    var wrongContainer =
        new AuthorityScope.Contained(
            new ScopeId(childScope), childProject, new ScopeId(UUID.randomUUID()));
    assertThrows(CallerFault.class, () -> repository.issue(wrongContainer));
    var wrongProject =
        new AuthorityScope.Contained(
            new ScopeId(childScope), childProject + 999, new ScopeId(parentScope));
    assertThrows(CallerFault.class, () -> repository.issue(wrongProject));
    // An ordinary PROJECT scope is not contained and gets no principal.
    var ordinary =
        new AuthorityScope.Contained(
            new ScopeId(parentScope), childProject, new ScopeId(UUID.randomUUID()));
    assertThrows(CallerFault.class, () -> repository.issue(ordinary));
    assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM contained_principals", Long.class));
  }
}
