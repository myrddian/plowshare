package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
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
 * V257 against PostgreSQL: the READABILITY triggers name the item each change affected and still
 * move the domain counter; fan-out above the bound, unaddressable rows and TRUNCATE fall back to
 * the domain item; counters are transactional (read-your-writes) and roll back with a savepoint;
 * contained_principals moves AUTHORITY; and keyed notifications reach a second, independent
 * listener and cache ("process") that invalidates only the matching entries.
 */
@Tag("full-db")
@Testcontainers
class ItemProjectionVersionsDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final ProjectionVersions.Domain READABILITY =
      ProjectionVersions.Domain.READABILITY;
  private static JdbcTemplate jdbc;
  private static TransactionTemplate transactions;

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(source()).load().migrate();
    jdbc = new JdbcTemplate(source());
    transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('reader','x')");
  }

  private static DataSource source() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private static void log(String id, String parent) {
    if (parent == null)
      jdbc.update(
          "INSERT INTO conversations(id,created_at,origin,budget_total,budget_spent,owner_handle)"
              + " VALUES(?,now(),'turn',100,0,'reader')",
          id);
    else
      jdbc.update(
          "INSERT INTO conversations(id,created_at,origin,agent,parent_id,lifecycle,owner_handle)"
              + " VALUES(?,now(),'delegation','agent',?,NULL,'reader')",
          id,
          parent);
  }

  private static long counter(String item) {
    return jdbc.queryForObject(
        "SELECT coalesce(max(counter),0) FROM snapshot_cache_item_versions"
            + " WHERE domain='readability' AND item=?",
        Long.class,
        item);
  }

  private static long domainCounter() {
    return jdbc.queryForObject("SELECT last_value FROM snapshot_cache_readability", Long.class);
  }

  private static ProjectionVersions.Item tree(String root) {
    return new ProjectionVersions.Item(READABILITY, "conversation:" + root);
  }

  @Test
  void aTreeWriteMovesOnlyItsRootAndTheDomainCounterAsBefore() {
    String a = unique("root-a");
    String b = unique("root-b");
    log(a, null);
    log(b, null);
    long treeA = counter("conversation:" + a);
    long treeB = counter("conversation:" + b);
    long domain = domainCounter();
    long all = counter("*");

    String child = unique("child");
    log(child, a);
    long childCounter = counter("conversation:" + child);
    log(unique("grandchild"), child);

    assertNotEquals(treeA, counter("conversation:" + a), "The tree's root is addressed");
    assertEquals(treeB, counter("conversation:" + b), "Another tree is untouched");
    // V258: a new edge also names its child, the old root if the child was one. Later writes
    // below the child are addressed by the tree's root alone.
    assertTrue(childCounter > 0, "A new edge names its child endpoint");
    assertEquals(
        childCounter, counter("conversation:" + child), "Descendants are addressed by their root");
    assertEquals(all, counter("*"), "No fallback for an addressable change");
    assertTrue(domainCounter() > domain, "Domain-scoped projections still see the change");

    // A column update the READABILITY trigger ignores moves nothing.
    long before = counter("conversation:" + a);
    jdbc.update("UPDATE conversations SET title='renamed' WHERE id=?", child);
    assertEquals(before, counter("conversation:" + a));
  }

  @Test
  void edgesAndInformationRowsAreAddressedUnderEveryAffectedItem() {
    String a = unique("caller");
    String b = unique("conductor-root");
    String conductor = unique("conductor");
    log(a, null);
    log(b, null);
    log(conductor, b);
    long treeA = counter("conversation:" + a);
    long treeB = counter("conversation:" + b);
    jdbc.update(
        "INSERT INTO orchestrations(id,definition_name,tier,definition_hash,definition_source,"
            + "definition_origin,stages,max_returns,conductor_conversation,caller_agent,state,depth,"
            + "created_at,caller_conversation) VALUES(?,'fixture','SHIPPED','hash','source','origin',"
            + "'[]',1,?,'agent','running',0,now(),?)",
        unique("run"),
        conductor,
        a);
    assertNotEquals(treeA, counter("conversation:" + a), "The caller's tree joins");
    assertNotEquals(treeB, counter("conversation:" + b), "The conductor's old tree is addressed");

    UUID resource = UUID.randomUUID();
    UUID revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,kind) VALUES(?,'fixture',?,'source')",
        resource,
        unique("source") + ".md");
    assertTrue(counter("resource:" + resource) > 0);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,"
            + "byte_size) VALUES(?,?,1,'A','text/plain','hash',1)",
        revision,
        resource);
    long revisionCounter = counter("revision:" + revision);
    assertTrue(revisionCounter > 0);
    jdbc.update("UPDATE information_revisions SET availability='withdrawn' WHERE id=?", revision);
    assertNotEquals(revisionCounter, counter("revision:" + revision));

    long resourceCounter = counter("resource:" + resource);
    long all = counter("*");
    jdbc.update(
        "INSERT INTO information_navigation_policies(policy_key,version,settings,actor)"
            + " VALUES(?,1,'{}','reader')",
        "document:" + resource);
    assertNotEquals(resourceCounter, counter("resource:" + resource));
    assertEquals(all, counter("*"));
    jdbc.update(
        "INSERT INTO information_navigation_policies(policy_key,version,settings,actor)"
            + " VALUES(?,1,'{}','reader') ON CONFLICT DO NOTHING",
        "project:" + Math.abs(UUID.randomUUID().getMostSignificantBits()));
    assertNotEquals(all, counter("*"), "A project policy has no item address: fallback");
  }

  @Test
  void fanOutAboveTheBoundAndTruncateFallBackToTheDomainItem() {
    long all = counter("*");
    var roots = new ArrayList<String>();
    for (int index = 0; index < 65; index++) roots.add(unique("bulk"));
    transactions.executeWithoutResult(
        status -> {
          for (String root : roots) log(root, null);
        });
    assertNotEquals(all, counter("*"), "65 items exceed the 64-item bound");
    for (String root : roots) assertEquals(0, counter("conversation:" + root));

    long bounded = counter("*");
    var fewer = List.of(unique("few"), unique("few"));
    transactions.executeWithoutResult(
        status -> {
          for (String root : fewer) log(root, null);
        });
    assertEquals(bounded, counter("*"));
    for (String root : fewer) assertTrue(counter("conversation:" + root) > 0);

    jdbc.execute("TRUNCATE information_quarantined_payloads");
    assertNotEquals(bounded, counter("*"));
  }

  @Test
  void itemCountersAreTransactionalAndRollBackWithASavepoint() {
    String root = unique("savepoint");
    log(root, null);
    long before = counter("conversation:" + root);
    transactions.executeWithoutResult(
        status -> {
          Object savepoint = status.createSavepoint();
          log(unique("discarded"), root);
          status.rollbackToSavepoint(savepoint);
        });
    assertEquals(before, counter("conversation:" + root));
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM snapshot_cache_item_flushes", Integer.class),
        "No flush marker outlives its transaction");

    var versions = new JdbcProjectionVersions(jdbc, source(), 128);
    String other = unique("other");
    log(other, null);
    var mine = versions.captureItems(Set.of(tree(root))).orElseThrow();
    var theirs = versions.captureItems(Set.of(tree(other))).orElseThrow();
    assertTrue(mine.counters().containsKey(ProjectionVersions.Item.all(READABILITY)));
    assertTrue(versions.currentItems(mine));
    log(unique("child"), root);
    assertFalse(versions.currentItems(mine), "A committed write is seen at once");
    assertTrue(versions.currentItems(theirs));
  }

  @Test
  void containedPrincipalsMoveTheAuthorityDomain() {
    String outer = unique("outer");
    String contained = "contained:" + UUID.randomUUID();
    jdbc.update("INSERT INTO projects(name) VALUES(?),(?)", outer, contained);
    UUID scope = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES(?,'CONTAINED',"
            + "(SELECT id FROM projects WHERE name=?),"
            + "(SELECT id FROM authority_scopes WHERE project_id=(SELECT id FROM projects WHERE name=?)))",
        scope,
        contained,
        outer);
    var versions = new JdbcProjectionVersions(jdbc, source(), 128);
    var before = versions.capture(Set.of(ProjectionVersions.Domain.AUTHORITY)).orElseThrow();
    jdbc.update("INSERT INTO contained_principals(scope_id) VALUES(?)", scope);
    assertFalse(versions.current(before), "Creating a principal moves AUTHORITY");
    var created = versions.capture(Set.of(ProjectionVersions.Domain.AUTHORITY)).orElseThrow();
    jdbc.update("DELETE FROM contained_principals WHERE scope_id=?", scope);
    assertFalse(versions.current(created), "Removing a principal moves AUTHORITY");
  }

  /**
   * Two independent processes: each has its own listener session, version repository and caches. A
   * write committed through a third connection reaches process two as a keyed hint, which drops
   * only that tree's entry before any read runs. Its domain-scoped projection still clears.
   */
  @Test
  void aKeyedNotificationInvalidatesTheMatchingItemOnASecondProcess() throws Exception {
    String a = unique("process-a");
    String b = unique("process-b");
    log(a, null);
    log(b, null);
    try (var one = new Process();
        var two = new Process()) {
      for (var process : List.of(one, two)) {
        process.read(a);
        process.read(b);
        process.readDomain();
        assertEquals(2, process.items.statistics().entries());
        assertEquals(1, process.domain.statistics().entries());
      }

      log(unique("step"), a);

      for (var process : List.of(one, two)) {
        await(() -> process.items.statistics().entries() == 1, "Tree A's entry is dropped");
        await(() -> process.domain.statistics().entries() == 0, "The domain projection clears");
        int loadsB = process.loads.get();
        process.read(b);
        assertEquals(loadsB, process.loads.get(), "Tree B is still a hit");
        process.read(a);
        assertEquals(loadsB + 1, process.loads.get(), "Tree A reloads");
      }

      // Fan-out above the bound reaches the second process as the fallback item.
      transactions.executeWithoutResult(
          status -> {
            for (int index = 0; index < 65; index++) log(unique("burst"), null);
          });
      await(() -> two.items.statistics().entries() == 0, "The fallback clears the projection");
    }
  }

  private static void await(BooleanSupplier condition, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < deadline, message);
      Thread.sleep(20);
    }
  }

  /** One server process's listener, versions and two caches, on connections of its own. */
  private static final class Process implements AutoCloseable {
    final JdbcProjectionVersions versions = new JdbcProjectionVersions(jdbc, source(), 128);
    final ListeningProjectionChangeSignals signals =
        new ListeningProjectionChangeSignals(
            new PostgresProjectionNotificationSource(source(), Duration.ofSeconds(2)),
            versions,
            4,
            Duration.ofMillis(200),
            Duration.ofMillis(100),
            Duration.ofMillis(200),
            Duration.ofSeconds(2));
    final BoundedSnapshotCache<VersionedProjectionCache.Versioned<Long>> items =
        new BoundedSnapshotCache<>(
            new SnapshotCache.Namespace(versions.databaseIdentity().toString(), "log-trees"),
            limits());
    final BoundedSnapshotCache<VersionedProjectionCache.Versioned<Long>> domain =
        new BoundedSnapshotCache<>(
            new SnapshotCache.Namespace(versions.databaseIdentity().toString(), "domain"),
            limits());
    final VersionedProjectionCache<Long> itemCache;
    final VersionedProjectionCache<Long> domainCache;
    final AtomicInteger loads = new AtomicInteger();

    Process() throws Exception {
      itemCache =
          VersionedProjectionCache.itemScoped(
              items,
              versions,
              signals,
              Set.of(READABILITY),
              1_000_000,
              null,
              Duration.ofSeconds(2));
      domainCache =
          new VersionedProjectionCache<>(
              domain,
              versions,
              signals,
              Set.of(READABILITY),
              1_000_000,
              null,
              Duration.ofSeconds(2));
      signals.start();
      ItemProjectionVersionsDatabaseTest.await(signals::healthy, "Listener must become ready");
    }

    private static SnapshotCache.Limits limits() {
      return new SnapshotCache.Limits(
          8, 4_000_000, 2, 4_000_000, 64, 64, 64, 256, Duration.ofSeconds(10));
    }

    long read(String root) {
      return itemCache.read(
          new SnapshotCache.Key("log-tree", root),
          Set.of(tree(root)),
          true,
          () -> {
            loads.incrementAndGet();
            return jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE id=? OR parent_id=?",
                Long.class,
                root,
                root);
          },
          () -> fail("Cacheable reads stay cached"),
          value -> 64);
    }

    long readDomain() {
      return domainCache.read(
          new SnapshotCache.Key("all-logs", "count"),
          true,
          () -> jdbc.queryForObject("SELECT count(*) FROM conversations", Long.class),
          value -> 64);
    }

    public void close() {
      try {
        itemCache.close();
        domainCache.close();
      } finally {
        signals.close();
      }
    }
  }
}
