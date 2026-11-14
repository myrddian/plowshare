package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.server.access.AuthoritySnapshot;
import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.access.JdbcAuthoritySnapshotRepository;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
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
import java.time.Clock;
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
 * Item invalidation of the shared readability facts against PostgreSQL and the V257 triggers,
 * through the production composition ({@link ProjectionCaches#createItemScoped}): a policy write on
 * one resource invalidates that resource's fact alone and the next check reflects it, other facts
 * keep serving hits, and the single confirmation statement catches a write committed after the
 * facts were loaded even when no notification has arrived.
 */
@Tag("full-db")
@Testcontainers
class KnowledgeReadabilityInvalidationDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private final Map<String, AtomicInteger> loads = new ConcurrentHashMap<>();
  private final AtomicInteger confirmations = new AtomicInteger();
  private InformationContext alice;
  private JdbcInformationCatalogueRepository sql;

  private record Doc(UUID resource, UUID revision) {}

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
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES('alice','fixture'),('bob','fixture')");
    jdbc.update("INSERT INTO projects(name) VALUES('one')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'alice','MANAGER' FROM projects WHERE name='one'");
    alice = new InformationContext("alice", InformationContext.Selection.project("one"));
    sql =
        new JdbcInformationCatalogueRepository(
            jdbc, Clock.systemUTC(), mock(InformationEvents.class));
  }

  private Doc doc() {
    UUID resource = UUID.randomUUID();
    UUID revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) VALUES(?,'project:one',?,'alice',(SELECT id FROM projects WHERE name='one'),'source')",
        resource,
        resource + ".txt");
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text,allowance_total) VALUES(?,?,1,'Fixture','text/plain','fixture',1,'body',10)",
        revision,
        resource);
    return new Doc(resource, revision);
  }

  /** Alice can no longer read it: private to bob. Moves only {@code resource:<id>}. */
  private void restrict(Doc doc) {
    jdbc.update(
        "UPDATE information_resource_policies SET version=version+1,private_only=true,privacy_principal=(SELECT id FROM authority_accounts WHERE legacy_handle='bob'),reason='AUDIENCE',actor=(SELECT id FROM authority_accounts WHERE legacy_handle='alice') WHERE resource_id=?",
        doc.resource());
  }

  private int loads(String kind, UUID id) {
    return loads.getOrDefault(kind + ":" + id, new AtomicInteger()).get();
  }

  /** The production fact repository, counting each physical load per item. */
  private KnowledgeFactRepository counted() {
    var facts = new JdbcKnowledgeFactRepository(jdbc);
    return new KnowledgeFactRepository() {
      public KnowledgeFact revision(UUID id) {
        loads.computeIfAbsent("revision:" + id, key -> new AtomicInteger()).incrementAndGet();
        return facts.revision(id);
      }

      public KnowledgeFact resource(UUID id) {
        loads.computeIfAbsent("resource:" + id, key -> new AtomicInteger()).incrementAndGet();
        return facts.resource(id);
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
        24,
        4);
  }

  private CachedKnowledgeReadability check(
      ProjectionCaches caches, JdbcProjectionVersions versions) {
    var authority = new JdbcAuthoritySnapshotRepository(jdbc, 10_000);
    AuthoritySnapshots snapshots = () -> authority.load().map(AuthoritySnapshot::new);
    return new CachedKnowledgeReadability(
        caches.createItemScoped(
            "knowledge-facts-v1",
            Set.of(ProjectionVersions.Domain.READABILITY),
            ProjectionCaches.Freshness.VERSIONED),
        counted(),
        counted(versions),
        snapshots,
        ReadAdmission.direct(),
        256);
  }

  @Test
  void aPolicyWriteInvalidatesOnlyThatResourcesFactAndTheNextCheckReflectsIt() throws Exception {
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
      var check = check(caches, versions);
      var a = doc();
      var b = doc();
      await(() -> check.readable(alice, a.revision()).isPresent(), "Facts become servable");
      assertEquals(Optional.of(true), check.readable(alice, a.revision()));
      assertEquals(Optional.of(true), check.readable(alice, b.revision()));
      int confirmed = confirmations.get();
      assertEquals(Optional.of(true), check.readable(alice, b.revision()));
      assertEquals(confirmed + 1, confirmations.get(), "One confirmation statement per check");
      assertEquals(1, loads("revision", b.revision()));
      assertEquals(1, loads("resource", b.resource()));
      int revisionA = loads("revision", a.revision());

      restrict(a);
      // The keyed notification drops resource:A's fact; until it lands, confirmation catches it.
      await(
          () -> check.readable(alice, a.revision()).equals(Optional.of(false)),
          "The next served check reflects the write");
      assertFalse(sql.readable(alice, a.revision()), "SQL agrees");
      assertEquals(2, loads("resource", a.resource()), "Only the written resource reloads");
      assertEquals(revisionA, loads("revision", a.revision()), "Its revision fact stays");
      assertEquals(Optional.of(true), check.readable(alice, b.revision()));
      assertEquals(1, loads("revision", b.revision()), "An unrelated revision stays a hit");
      assertEquals(1, loads("resource", b.resource()), "An unrelated resource stays a hit");
    }
  }

  @Test
  void theSingleConfirmationCatchesAWriteCommittedAfterTheFactsWereLoaded() {
    var versions = new JdbcProjectionVersions(jdbc, source(), 128);
    // Ready, but never delivers an item hint: only the confirmation statement can see the write.
    try (var signals = new SilentSignals();
        var caches =
            new ProjectionCaches(
                versions, versions, signals, properties(), null, ProjectionTelemetry.NONE)) {
      var check = check(caches, versions);
      var a = doc();
      var b = doc();
      assertEquals(Optional.of(true), check.readable(alice, a.revision()));
      assertEquals(Optional.of(true), check.readable(alice, b.revision()));

      restrict(a);
      int before = confirmations.get();
      assertEquals(
          Optional.empty(),
          check.readable(alice, a.revision()),
          "Stale facts are never disclosed: the check goes to SQL");
      assertEquals(before + 1, confirmations.get());
      assertEquals(Optional.of(false), check.readable(alice, a.revision()));
      assertEquals(2, loads("resource", a.resource()), "Only the stale fact was dropped");
      assertEquals(1, loads("revision", a.revision()));
      assertEquals(Optional.of(true), check.readable(alice, b.revision()));
      assertEquals(1, loads("resource", b.resource()));
      assertEquals(1, loads("revision", b.revision()));
    }
  }

  @Test
  void aCallerInAWritingTransactionIsAnsweredBySqlWithItsOwnWrite() {
    var versions = new JdbcProjectionVersions(jdbc, source(), 128);
    try (var signals = new SilentSignals();
        var caches =
            new ProjectionCaches(
                versions, versions, signals, properties(), null, ProjectionTelemetry.NONE)) {
      var check = check(caches, versions);
      var a = doc();
      var repository =
          new JdbcInformationCatalogueRepository(
              jdbc,
              Clock.systemUTC(),
              new JdbcAllowanceRepository(jdbc),
              ProjectionCache.direct(),
              ProjectionCache.direct(),
              ProjectionCache.direct(),
              ProjectionCache.direct(),
              ProjectionCache.direct(),
              check,
              mock(InformationEvents.class));
      assertTrue(repository.readable(alice, a.revision()));
      var transactions =
          new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
      transactions.executeWithoutResult(
          status -> {
            restrict(a);
            assertEquals(Optional.empty(), check.readable(alice, a.revision()));
            assertFalse(repository.readable(alice, a.revision()), "Own write is visible");
            status.setRollbackOnly();
          });
      assertEquals(Optional.of(true), check.readable(alice, a.revision()));
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
