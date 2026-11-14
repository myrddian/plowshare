package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.access.AuthoritySnapshot;
import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.access.JdbcAuthoritySnapshotRepository;
import io.aeyer.plowshare.server.cache.JdbcProjectionVersions;
import io.aeyer.plowshare.server.cache.ListeningProjectionChangeSignals;
import io.aeyer.plowshare.server.cache.PostgresProjectionNotificationSource;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.ProjectionCacheProperties;
import io.aeyer.plowshare.server.cache.ProjectionCaches;
import io.aeyer.plowshare.server.cache.ProjectionChangeSignals;
import io.aeyer.plowshare.server.cache.ProjectionItemVersions;
import io.aeyer.plowshare.server.cache.ProjectionTelemetry;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Item invalidation of log-tree facts against PostgreSQL and the V257/V258 triggers, through the
 * production composition ({@link ProjectionCaches#createItemScoped}) and the production entry point
 * ({@link JdbcJobInformationRepository#logAllowed}): a new child conversation, job input or
 * quarantine in tree A reloads only tree A, tree B keeps serving hits, the single confirmation
 * catches a write before any notification, and a writing transaction is answered by SQL that sees
 * its own write.
 */
@Tag("full-db")
@Testcontainers
class LogReadabilityInvalidationDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private final Map<String, AtomicInteger> loads = new ConcurrentHashMap<>();
  private final AtomicInteger confirmations = new AtomicInteger();
  private final AtomicInteger predicates = new AtomicInteger();
  private UUID readable;
  private UUID foreign;

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(source()).load().migrate();
    jdbc = new JdbcTemplate(source());
  }

  private static DataSource source() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @BeforeEach
  void setup() {
    AuthorityFixtures.truncate(jdbc, "TRUNCATE admins,projects CASCADE");
    jdbc.update("TRUNCATE information_job_inputs,information_quarantined_payloads");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES('alice','fixture'),('bob','fixture')");
    jdbc.update("INSERT INTO projects(name) VALUES('one'),('two')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'alice','MANAGER' FROM projects WHERE name='one'");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'bob','MANAGER' FROM projects WHERE name='two'");
    readable = doc("one");
    foreign = doc("two");
  }

  private static UUID doc(String project) {
    UUID resource = UUID.randomUUID();
    UUID revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) VALUES(?,?,?,'alice',(SELECT id FROM projects WHERE name=?),'source')",
        resource,
        "project:" + project,
        resource + ".txt",
        project);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text,allowance_total) VALUES(?,?,1,'Fixture','text/plain','fixture',1,'body',10)",
        revision,
        resource);
    return revision;
  }

  private static String log(String parent) {
    String id = "log-" + UUID.randomUUID();
    if (parent == null)
      jdbc.update(
          "INSERT INTO conversations(id,created_at,origin,budget_total,budget_spent,owner_handle) VALUES(?,now(),'turn',100,0,'alice')",
          id);
    else
      jdbc.update(
          "INSERT INTO conversations(id,created_at,origin,agent,parent_id,lifecycle,owner_handle) VALUES(?,now(),'delegation','agent',?,NULL,'alice')",
          id,
          parent);
    return id;
  }

  private static void input(String job, UUID revision, String project) {
    jdbc.update(
        "INSERT INTO information_job_inputs(job_id,owner_handle,revision_id,scope,project_name,include_shared) VALUES(?,'alice',?,'project',?,false)",
        job,
        revision,
        project);
  }

  private int loads(String root) {
    return loads.getOrDefault(root, new AtomicInteger()).get();
  }

  /** The production tree repository, counting each physical tree load per root. */
  private LogTreeFactRepository counted() {
    var trees = new JdbcLogTreeFactRepository(jdbc);
    return new LogTreeFactRepository() {
      public Optional<List<String>> roots(String log) {
        return trees.roots(log);
      }

      public LogTreeFact tree(String root) {
        loads.computeIfAbsent(root, key -> new AtomicInteger()).incrementAndGet();
        return trees.tree(root);
      }
    };
  }

  /** The production item versions, counting confirmation statements. */
  private ProjectionItemVersions counted(JdbcProjectionVersions versions) {
    return new ProjectionItemVersions() {
      public Optional<ItemToken> captureItems(Set<ProjectionVersions.Item> items) {
        return versions.captureItems(items);
      }

      public boolean currentItems(ItemToken token) {
        return versions.currentItems(token);
      }

      public Set<ProjectionVersions.Item> staleItems(Collection<ItemToken> tokens) {
        confirmations.incrementAndGet();
        return versions.staleItems(tokens);
      }

      public boolean canShare() {
        return versions.canShare();
      }
    };
  }

  private static ProjectionCacheProperties properties() {
    return new ProjectionCacheProperties(
        256,
        33_554_432,
        8,
        67_108_864,
        256,
        64,
        8192,
        8_388_608,
        Duration.ofSeconds(2),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        128,
        4,
        1L << 40);
  }

  /** The production entry point over the cached check; counts the SQL predicate's runs. */
  private JdbcJobInformationRepository repository(
      ProjectionCaches caches, JdbcProjectionVersions versions) {
    var authority = new JdbcAuthoritySnapshotRepository(jdbc, 10_000);
    AuthoritySnapshots snapshots = () -> authority.load().map(AuthoritySnapshot::new);
    var check =
        new CachedLogReadability(
            caches.createItemScoped(
                "log-tree-facts-v1",
                Set.of(ProjectionVersions.Domain.READABILITY),
                ProjectionCaches.Freshness.VERSIONED),
            counted(),
            new KnowledgeFactSource(
                caches.createItemScoped(
                    "knowledge-facts-v1",
                    Set.of(ProjectionVersions.Domain.READABILITY),
                    ProjectionCaches.Freshness.VERSIONED),
                new JdbcKnowledgeFactRepository(jdbc),
                ReadAdmission.direct(),
                256),
            counted(versions),
            snapshots,
            ReadAdmission.direct(),
            1024);
    LogReadabilityCheck counting =
        (account, log) -> {
          var verdict = check.readable(account, log);
          if (verdict.isEmpty()) predicates.incrementAndGet();
          return verdict;
        };
    var visibility =
        new JdbcInformationLogVisibility(
            jdbc, ProjectionCache.direct(), versions, ReadAdmission.direct(), counting);
    return new JdbcJobInformationRepository(jdbc, visibility);
  }

  @Test
  void aWriteInOneTreeReloadsOnlyThatTreeAndTheNextCheckReflectsIt() throws Exception {
    var versions = new JdbcProjectionVersions(jdbc, source(), 128);
    try (var signals =
            new ListeningProjectionChangeSignals(
                new PostgresProjectionNotificationSource(source(), Duration.ofSeconds(2)),
                versions,
                4,
                Duration.ofMillis(200),
                Duration.ofMillis(100),
                Duration.ofMillis(200),
                Duration.ofSeconds(2));
        var caches =
            new ProjectionCaches(
                versions, versions, signals, properties(), null, ProjectionTelemetry.NONE)) {
      signals.start();
      await(signals::healthy, "Listener must become ready");
      var logs = repository(caches, versions);
      String a = log(null);
      String aChild = log(a);
      String b = log(null);
      String bChild = log(b);
      input(a, readable, "one");
      input(b, readable, "one");
      await(
          () -> {
            int before = predicates.get();
            boolean allowed = logs.logAllowed(aChild, "alice");
            return allowed && predicates.get() == before;
          },
          "Tree A becomes servable");
      assertTrue(logs.logAllowed(bChild, "alice"));
      int predicate = predicates.get();
      int confirmed = confirmations.get();
      assertTrue(logs.logAllowed(aChild, "alice"));
      assertTrue(logs.logAllowed(bChild, "alice"));
      assertFalse(logs.logAllowed(bChild, "bob"), "Bob cannot read project one's input");
      assertEquals(predicate, predicates.get(), "Served without the SQL predicate");
      assertEquals(confirmed + 3, confirmations.get(), "One confirmation statement per check");
      int treeA = loads(a);
      assertEquals(1, loads(b));

      // A new child conversation in tree A: tree A reloads, tree B stays a hit.
      String aGrandchild = log(aChild);
      await(() -> logs.logAllowed(aGrandchild, "alice"), "The new child is visible");
      assertTrue(loads(a) > treeA, "Tree A reloads");
      assertTrue(logs.logAllowed(bChild, "alice"));
      assertEquals(1, loads(b), "Tree B is untouched");

      // An unreadable retained input in tree A.
      input(aChild, foreign, "two");
      await(
          () -> {
            int before = predicates.get();
            boolean allowed = logs.logAllowed(aGrandchild, "alice");
            return !allowed && predicates.get() == before;
          },
          "The next served check reflects the input");
      assertTrue(logs.logAllowed(a, "alice"), "The input's ancestor stays readable");
      assertTrue(logs.logAllowed(bChild, "alice"));
      assertEquals(1, loads(b), "Tree B is untouched");

      // A quarantine in tree B: only now does tree B reload.
      jdbc.update(
          "INSERT INTO information_quarantined_payloads(payload_id,reason) VALUES(?,'fixture')", b);
      await(() -> !logs.logAllowed(bChild, "alice"), "The quarantine applies");
      assertEquals(2, loads(b), "Tree B reloads once");
      int afterQuarantine = loads(a);
      assertFalse(logs.logAllowed(aGrandchild, "alice"));
      assertEquals(afterQuarantine, loads(a), "Tree A stays a hit");
    }
  }

  @Test
  void theSingleConfirmationCatchesAWriteCommittedBeforeAnyNotification() {
    var versions = new JdbcProjectionVersions(jdbc, source(), 128);
    try (var signals = new SilentSignals();
        var caches =
            new ProjectionCaches(
                versions, versions, signals, properties(), null, ProjectionTelemetry.NONE)) {
      var logs = repository(caches, versions);
      String a = log(null);
      String aChild = log(a);
      String b = log(null);
      assertTrue(logs.logAllowed(aChild, "alice"));
      assertTrue(logs.logAllowed(b, "alice"));
      int predicate = predicates.get();

      input(a, foreign, "two");
      assertFalse(logs.logAllowed(aChild, "alice"), "Stale facts are never disclosed");
      assertEquals(predicate + 1, predicates.get(), "The stale check went to SQL");
      assertFalse(logs.logAllowed(aChild, "alice"));
      assertEquals(predicate + 1, predicates.get(), "The reloaded tree serves again");
      assertEquals(2, loads(a), "Only the stale tree reloaded");
      assertTrue(logs.logAllowed(b, "alice"));
      assertEquals(1, loads(b));
      assertEquals(predicate + 1, predicates.get());
    }
  }

  @Test
  void aCallerInAWritingTransactionIsAnsweredBySqlWithItsOwnWrite() {
    var versions = new JdbcProjectionVersions(jdbc, source(), 128);
    try (var signals = new SilentSignals();
        var caches =
            new ProjectionCaches(
                versions, versions, signals, properties(), null, ProjectionTelemetry.NONE)) {
      var logs = repository(caches, versions);
      String a = log(null);
      String aChild = log(a);
      assertTrue(logs.logAllowed(aChild, "alice"));
      var transactions =
          new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
      transactions.executeWithoutResult(
          status -> {
            jdbc.update(
                "INSERT INTO information_quarantined_payloads(payload_id,reason) VALUES(?,'fixture')",
                a);
            int predicate = predicates.get();
            assertFalse(logs.logAllowed(aChild, "alice"), "Own write is visible");
            assertEquals(predicate + 1, predicates.get(), "Answered by SQL");
            status.setRollbackOnly();
          });
      assertTrue(logs.logAllowed(aChild, "alice"));
    }
  }

  private static void await(BooleanSupplier condition, String message) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      try {
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(message, interrupted);
      }
    }
    throw new AssertionError(message);
  }

  /** A ready listener that delivers no hints: freshness rests on the confirmation alone. */
  private static final class SilentSignals implements ProjectionChangeSignals {
    private final List<Observer> observers = new CopyOnWriteArrayList<>();

    public Subscription subscribe(Set<ProjectionVersions.Domain> domains, Observer observer) {
      observers.add(observer);
      observer.stateChanged(new State(1, true));
      return () -> observers.remove(observer);
    }

    public void start() {}

    public boolean healthy() {
      return true;
    }

    public OptionalLong deliveredThrough() {
      return OptionalLong.empty();
    }

    public void close() {}
  }
}
