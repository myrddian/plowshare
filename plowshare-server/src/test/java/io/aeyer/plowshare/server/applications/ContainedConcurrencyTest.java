package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.protocol.ApplicationDeployment.Deploy;
import io.aeyer.plowshare.protocol.ApplicationDeployment.File;
import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.events.ScheduleDefinitionStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.ConfiguredFileStores;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Real PostgreSQL, and every assertion here is something only the engine decides.
 *
 * <p><strong>Database-specific justification, per assertion.</strong>
 *
 * <ul>
 *   <li><em>Row locking under real concurrency.</em> Creation takes the container's project row
 *       lock and a parent revision change takes the same row's lock, and the claim is that they
 *       therefore cannot interleave between activation's live-child count and creation's insert.
 *       That claim is about two concurrent transactions blocking on one PostgreSQL row lock. A mock
 *       cannot block, so a mocked test of this asserts only that the calls are made — which is what
 *       {@code ContainedRecoveryTest.creationTakesTheContainerLockBeforeTheChildRowLands} asserts,
 *       and it is deliberately the weaker claim.
 *   <li><em>Cascade behaviour.</em> Destruction is one {@code DELETE} on {@code projects} and the
 *       two-hop cascade ({@code authority_scopes.project_id}, then {@code contained_principals} and
 *       {@code contained_applications} from the scope) is what removes everything else. The
 *       assertion <em>is</em> the foreign keys.
 *   <li><em>Transaction rollback and {@code ON DELETE RESTRICT}.</em> A parent-first delete must be
 *       refused by {@code authority_scopes.container} and must leave the descendant's rows intact.
 *       Only the engine enforces that, and only a real transaction rolls it back.
 * </ul>
 *
 * <p>Everything about destruction that does not need the engine — authority, ordering, allowance
 * zeroing, the collection record, source removal and the sweep — is mocked in {@code
 * ContainedDestructionTest} and {@code ContainedRecoveryTest}.
 */
@Tag("full-db")
@Testcontainers
class ContainedConcurrencyTest {
  @Container
  static final PostgreSQLContainer<?> DB =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  @TempDir Path directory;

  DriverManagerDataSource source;
  JdbcTemplate jdbc;
  UnitOfWork work;
  ConfiguredFileStores files;
  ProjectStore projects;
  JdbcContainedApplicationRepository records;
  JdbcContainedPrincipalRepository principals;
  ContainmentFence fence;
  SourceApplicationDeployments deployments;

  String parent;
  long parentProject;
  ScopeId parentScope;

  @BeforeEach
  void migrate() throws Exception {
    source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    var schema = "cascade_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    records = new JdbcContainedApplicationRepository(jdbc);
    principals = new JdbcContainedPrincipalRepository(jdbc);
    fence = new SourceContainmentFence(records);
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES ('deployer','fixture',TRUE)");
    parent = "app-" + UUID.randomUUID();
    Path store = Files.createDirectories(directory.resolve("sources"));
    Path config = directory.resolve("filestore.js");
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    Files.writeString(
        config,
        "export default "
            + json.writeValueAsString(
                Map.of(
                    "version",
                    1,
                    "defaultStore",
                    "applications",
                    "fileStores",
                    Map.of(
                        "applications",
                        Map.of(
                            "root",
                            store.toString(),
                            "access",
                            Map.of(
                                "accounts",
                                List.of(Map.of("handle", "deployer", "role", "MANAGER")))))))
            + ";");
    files = new ConfiguredFileStores(config.toString());
    projects =
        new ProjectStore(
            jdbc,
            directory.resolve("server.yml"),
            directory.resolve("sampling"),
            null,
            null,
            null,
            files);
    deployments =
        new SourceApplicationDeployments(
            new JdbcApplicationDeploymentStore(
                jdbc, work, projects, mock(ScheduleDefinitionStore.class), null, null, fence),
            new JdbcProjectMembers(jdbc),
            projects,
            files,
            (project, root) -> {});
  }

  @AfterEach
  void close() {
    files.close();
  }

  Deploy request(UUID expected, String text) {
    return new Deploy(
        parent,
        UUID.randomUUID(),
        expected,
        new FileStoreReference("applications", parent),
        List.of(),
        List.of(
            new File(
                "plowshare.json",
                "{\"version\":1,\"name\":\""
                    + parent
                    + "\",\"nesting\":true,\"access\":{\"accounts\":[{\"handle\":\"deployer\",\"role\":\"MANAGER\"}]}}"),
            new File("README.md", text)));
  }

  /** The parent Application, deployed through the untouched administrator-gated path. */
  UUID deployParent() {
    var receipt = deployments.deploy("deployer", request(null, "one"));
    parentProject = projects.id(parent);
    parentScope =
        new ScopeId(
            jdbc.queryForObject(
                "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parentProject));
    return receipt.release().revision();
  }

  ContainedApplicationRepository.Retained child(ScopeId container) {
    String name = "contained:" + UUID.randomUUID();
    return records.create(
        new ContainedApplicationRepository.Creation(
            container,
            UUID.randomUUID(),
            "a".repeat(64),
            name,
            Path.of("/srv/containment").resolve(name.substring("contained:".length())),
            UUID.randomUUID(),
            new AllowanceId(UUID.randomUUID()),
            5));
  }

  /**
   * Two transactions, one row lock. The creating transaction takes the container's project row lock
   * and holds it while a parent revision change asks for the same row; the revision change then
   * reads a live child and refuses. The point is the <em>window</em>: without the shared lock the
   * revision change would have counted zero children before the insert landed and both would have
   * committed, leaving a child running under configuration that no longer exists.
   */
  @Test
  void concurrentCreateAndActivationCannotBothCommit() throws Exception {
    UUID first = deployParent();
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Future<UUID> creating =
          pool.submit(
              () ->
                  work.inTransaction(
                      () -> {
                        records.lockContainer(parentScope);
                        locked.countDown();
                        try {
                          assertTrue(release.await(20, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                          throw new IllegalStateException(interrupted);
                        }
                        return child(parentScope).id();
                      }));
      assertTrue(locked.await(20, TimeUnit.SECONDS));
      Future<Object> activating =
          pool.submit(
              () -> {
                try {
                  return deployments.deploy("deployer", request(first, "two"));
                } catch (RuntimeException refused) {
                  return refused;
                }
              });
      // The revision change is now blocked on the container's project row, not racing it.
      assertThrows(TimeoutException.class, () -> activating.get(2, TimeUnit.SECONDS));
      release.countDown();
      UUID created = creating.get(20, TimeUnit.SECONDS);
      Object answer = activating.get(20, TimeUnit.SECONDS);
      assertInstanceOf(CallerFault.class, answer);
      assertEquals(
          "This Application holds a live contained Application; destroy it before changing the"
              + " revision that authorized it",
          ((CallerFault) answer).getMessage());
      // Exactly one committed: the child exists, and the revision did not move.
      assertTrue(records.find(created).isPresent());
      assertEquals(first, deployments.status("deployer", parent).activeRevision());
      assertEquals(1, records.liveChildrenOfProject(parent));
      // And once the child is gone the same revision change is admitted, so the fence is a fence
      // and not a permanent refusal.
      records.destroy(created);
      assertEquals(0, records.liveChildrenOfProject(parent));
      var moved = deployments.deploy("deployer", request(first, "three"));
      assertEquals(
          moved.release().revision(), deployments.status("deployer", parent).activeRevision());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * One {@code DELETE} on {@code projects} per record, and the cascade removes the scope, the
   * principal and the containment record with it. Nothing has a second code path, which is why the
   * assertion is the foreign keys rather than a sequence of statements.
   *
   * <p>It also asserts the ordering the schema forces and the rollback that enforces it: a
   * parent-first delete is refused by {@code authority_scopes.container}'s {@code ON DELETE
   * RESTRICT} and leaves every row of both levels intact.
   *
   * <p>And it asserts the boundary ADR 0005 owns: an ordinary Application's project, authority
   * scope and {@code application_releases} rows are untouched by any of this. Collection is for
   * contained stages, and the release history is not this capability's to write or to remove.
   */
  @Test
  void cascadeRemovesScopesPrincipalsAndRowsInOneTransaction() {
    UUID revision = deployParent();
    var inner = child(parentScope);
    var deeper = child(inner.scope());
    principals.issue(
        new AuthorityScope.Contained(inner.scope(), inner.projectId(), inner.container()));
    principals.issue(
        new AuthorityScope.Contained(deeper.scope(), deeper.projectId(), deeper.container()));
    records.collected(
        new ContainedApplicationRepository.Collection(
            deeper.id(),
            deeper.scope(),
            deeper.container(),
            deeper.project(),
            deeper.revision(),
            Path.of("/srv/containment").resolve(deeper.project().substring("contained:".length())),
            ContainedApplicationRepository.Cause.DESTROYED,
            false));

    // Deepest first is what the subtree read answers, and it is what the schema requires.
    assertEquals(
        List.of(deeper.id(), inner.id()),
        records.subtree(inner.scope()).stream()
            .map(ContainedApplicationRepository.Retained::id)
            .toList());

    // Parent first is refused, and the refusal leaves both levels whole.
    assertThrows(Exception.class, () -> records.destroy(inner.id()));
    assertEquals(2L, rows("contained_applications"));
    assertEquals(2L, rows("contained_principals"));

    records.destroy(deeper.id());
    assertEquals(1L, rows("contained_applications"));
    assertEquals(1L, rows("contained_principals"));
    records.destroy(inner.id());
    assertEquals(0L, rows("contained_applications"));
    assertEquals(0L, rows("contained_principals"));
    // Two hops: no contained project and no contained scope survive either.
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM projects WHERE name LIKE 'contained:%'", Long.class));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_scopes WHERE kind='CONTAINED'", Long.class));
    // The collection record survives the cascade it records, which is why it holds no foreign key.
    assertEquals(1L, rows("contained_collections"));
    assertEquals(
        List.of(deeper.id()),
        records.uncollectedSources().stream()
            .map(ContainedApplicationRepository.Collection::child)
            .toList());
    records.sourceCollected(deeper.id());
    assertEquals(List.of(), records.uncollectedSources());
    assertTrue(records.collection(deeper.id()).orElseThrow().sourceRemoved());

    // ADR 0005's retained release history is untouched, and so is the ordinary project it belongs
    // to and the authority scope over it.
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM application_releases WHERE project_id=?",
            Long.class,
            parentProject));
    assertEquals(revision, deployments.status("deployer", parent).activeRevision());
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_scopes WHERE project_id=?", Long.class, parentProject));
  }

  /**
   * V200's {@code scope} column is what makes a collection record a receipt, and the walk it
   * enables is SQL: the destroyed subtree is rebuilt by descending {@code container} to {@code
   * scope} over rows whose referents no longer exist. The partial unique index on {@code scope} and
   * the replaced shape constraint are engine behaviour, so the migration applying over V199 and
   * refusing a half-described row are assertions only PostgreSQL can make.
   */
  @Test
  void aCollectionRecordRebuildsTheDestroyedSubtreeAfterItsScopesAreGone() {
    deployParent();
    var inner = child(parentScope);
    var deeper = child(inner.scope());
    for (var held : List.of(deeper, inner))
      records.collected(
          new ContainedApplicationRepository.Collection(
              held.id(),
              held.scope(),
              held.container(),
              held.project(),
              held.revision(),
              Path.of("/srv/containment").resolve(held.project().substring("contained:".length())),
              ContainedApplicationRepository.Cause.DESTROYED,
              false));
    records.destroy(deeper.id());
    records.destroy(inner.id());
    // Both scopes are gone, and the history still answers the three questions a retry asks.
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_scopes WHERE kind='CONTAINED'", Long.class));
    var named = records.collection(inner.id()).orElseThrow();
    assertEquals(parentScope, named.container());
    assertEquals(inner.scope(), named.scope());
    assertEquals(
        List.of(deeper.id()),
        records.collectedUnder(named.scope()).stream()
            .map(ContainedApplicationRepository.Collection::child)
            .toList());
    assertEquals(List.of(), records.collectedUnder(deeper.scope()));
    // A SWEPT row names no scope and is therefore on no chain, which the shape constraint enforces
    // and the walk relies on.
    records.collected(
        new ContainedApplicationRepository.Collection(
            UUID.randomUUID(),
            null,
            null,
            "contained:" + UUID.randomUUID(),
            null,
            Path.of("/srv/containment/elsewhere"),
            ContainedApplicationRepository.Cause.SWEPT,
            false));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM contained_collections WHERE cause='SWEPT' AND scope IS NOT NULL",
            Long.class));
    // And the engine refuses a DESTROYED row with no scope, so the rebuild cannot meet one.
    assertThrows(
        Exception.class,
        () ->
            jdbc.update(
                "INSERT INTO contained_collections(child,container,project,revision,workspace,cause)"
                    + " VALUES(?,?,?,?,?, 'DESTROYED')",
                UUID.randomUUID(),
                parentScope.value(),
                "contained:" + UUID.randomUUID(),
                UUID.randomUUID(),
                "/srv/containment/x"));
  }

  private long rows(String table) {
    Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    return count == null ? 0 : count;
  }
}
