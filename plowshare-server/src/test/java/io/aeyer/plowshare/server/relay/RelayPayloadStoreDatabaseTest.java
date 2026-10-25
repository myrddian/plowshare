package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The payload store's PostgreSQL-only behaviour. Every assertion here needs an actual server: the
 * V192-V194 migration sequence running over populated tables, the digest the migration computes
 * from PostgreSQL's own JSONB rendering, the foreign key refusing a referenced delete, the
 * conditional {@code ON CONFLICT (digest) DO UPDATE} deduplicating concurrent inserts, row locking
 * between a sweep and an append, cascading project deletion, and the horizon query's interval
 * arithmetic. None of it is reachable through a mocked {@code JdbcTemplate}, which is what let a
 * column renamed by V120 survive in the horizon query until it was read by eye.
 *
 * <p>The image must be the pgvector build: the first migration creates the {@code vector}
 * extension, which stock PostgreSQL does not ship.
 */
@Tag("full-db")
@Testcontainers
class RelayPayloadStoreDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  /** Deliberately off an hour boundary: the next-examination floor is 02:00 on this day. */
  private static final Instant NOW = Instant.parse("2026-10-05T01:30:00Z");

  private static final Instant NEXT_HOUR = Instant.parse("2026-10-05T02:00:00Z");
  private static final Duration STAGING = Duration.ofHours(5);
  private static final Duration SETTLED = Duration.ofDays(30);

  /** Wide enough that a horizon inside this test's fixtures is never clamped by accident. */
  private static final Duration OPEN_CEILING = Duration.ofDays(3700);

  private static final Duration TIGHT_CEILING = Duration.ofHours(48);
  private static final Duration LEASE = Duration.ofMinutes(5);
  private static final String BACKFILL_DATABASE = "relay_payload_backfill";
  private static final String STAGED_DATABASE = "relay_payload_staged";

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;
  private static DriverManagerDataSource source;
  private static JdbcTemplate backfilled;
  private static DriverManagerDataSource backfilledSource;
  private static JdbcRelayPayloadStore backfilledPayloads;
  private static JdbcRelayRepository backfilledBroker;

  private JdbcRelayPayloadStore store;
  private JdbcRelayRepository broker;
  private JdbcRelayDeliveryRepository deliveries;
  private long project;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    transactions = unitOfWork(source);
  }

  private static UnitOfWork unitOfWork(javax.sql.DataSource data) {
    var template = new TransactionTemplate(new DataSourceTransactionManager(data));
    return new UnitOfWork() {
      @Override
      public <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(template.execute(status -> work.get()));
      }
    };
  }

  @BeforeEach
  void fresh() {
    // relay_payloads is not reachable by a cascade from relay_topics -- the references point the
    // other way -- so it has to be named here or stored bytes leak between tests, and a
    // content-addressed store resolves a later identical payload to the leaked row.
    jdbc.execute("TRUNCATE relay_topics,relay_payloads CASCADE");
    jdbc.update("DELETE FROM projects WHERE name='relay-payload-fixture'");
    project =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('relay-payload-fixture','fixture') RETURNING id",
                Long.class));
    store = payloadStore(jdbc, transactions, OPEN_CEILING);
    broker = new JdbcRelayRepository(jdbc, transactions, store);
    deliveries = new JdbcRelayDeliveryRepository(jdbc, transactions);
  }

  private static JdbcRelayPayloadStore payloadStore(
      JdbcTemplate template, UnitOfWork work, Duration ceiling) {
    return new JdbcRelayPayloadStore(template, work, STAGING, ceiling, SETTLED);
  }

  // ---------------------------------------------------------------------------------------------
  // A and E: the migration sequence over populated tables.
  // ---------------------------------------------------------------------------------------------

  /**
   * Builds a second database, migrates it to V119 where both tables still carry their own {@code
   * payload} column, populates it, and migrates it to head. That runs V192, V193's {@code INSERT
   * ... SELECT DISTINCT ON (digest)} and both of its {@code UPDATE}s, then all of V194 -- the lock,
   * the repeat backfill, {@code SET NOT NULL} and {@code DROP COLUMN} -- over rows that exist.
   */
  private static synchronized JdbcTemplate backfilled() {
    if (backfilled != null) return backfilled;
    jdbc.execute("DROP DATABASE IF EXISTS " + BACKFILL_DATABASE);
    jdbc.execute("CREATE DATABASE " + BACKFILL_DATABASE);
    backfilledSource =
        new DriverManagerDataSource(
            "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ":"
                + POSTGRES.getFirstMappedPort()
                + "/"
                + BACKFILL_DATABASE,
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    Flyway.configure().dataSource(backfilledSource).target("119").load().migrate();
    var template = new JdbcTemplate(backfilledSource);
    long owner =
        Objects.requireNonNull(
            template.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('relay-backfill','fixture') RETURNING id",
                Long.class));
    var at = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
    template.update(
        "INSERT INTO relay_topics(project_id,name,payload_kind,retention_seconds,last_position) VALUES(?,'release.observed','TEXT',345600,3)",
        owner);
    template.update(
        "INSERT INTO relay_subscriptions(project_id,topic,subscriber,seen_through,seen_at) VALUES(?,'release.observed','notices',0,?)",
        owner,
        at);
    // alpha twice and beta once: the publications alone hold two distinct renderings in three rows.
    String[] publications = {"alpha", "alpha", "beta"};
    for (int position = 1; position <= publications.length; position++)
      template.update(
          """
          INSERT INTO relay_publications(project_id,topic,position,event_id,publisher,occurred_at,
            published_at,schema_version,payload)
          VALUES(?,'release.observed',?,?,'source',?,?,1,?::jsonb)
          """,
          owner,
          position,
          "event-" + position,
          at,
          at,
          RelayPayloadCodec.write(new RelayPayload.Text(publications[position - 1])));
    // alpha again, from the other table, and gamma which only the admissions hold.
    String[] admissions = {"alpha", "gamma"};
    var routing = RelayDeliveries.SourcePin.of("notices/routes.js", "export const manifest = {};");
    for (int position = 1; position <= admissions.length; position++)
      template.update(
          """
          INSERT INTO relay_admissions(project_id,topic,subscriber,publication_position,event_id,publisher,
            occurred_at,published_at,payload_kind,schema_version,payload,routing_path,routing_source,
            routing_hash,branch_count,admitted_at)
          VALUES(?,'release.observed','notices',?,?,'source',?,?,'TEXT',1,?::jsonb,?,?,?,0,?)
          """,
          owner,
          position,
          "event-" + position,
          at,
          at,
          RelayPayloadCodec.write(new RelayPayload.Text(admissions[position - 1])),
          routing.path(),
          routing.source(),
          routing.sha256(),
          at);
    Flyway.configure().dataSource(backfilledSource).load().migrate();
    backfilled = template;
    return backfilled;
  }

  @Test
  void migrationsBackfillEveryExistingPayloadByDigest() {
    var template = backfilled();
    // Three distinct JSONB renderings across five rows: alpha (three rows), beta, gamma.
    assertEquals(3L, one(template, "SELECT count(*) FROM relay_payloads"));
    assertEquals(
        0L,
        one(
            template,
            "SELECT count(*) FROM relay_payloads WHERE digest <> encode(sha256(convert_to(payload::text,'UTF8')),'hex')"),
        "every backfilled digest must be SHA-256 over PostgreSQL's rendering of its own bytes");
    assertEquals(
        0L,
        one(
            template,
            "SELECT count(*) FROM relay_payloads WHERE byte_length <> octet_length(payload::text)"),
        "byte_length must be the octet length of the stored rendering");
    assertEquals(
        1L,
        one(
            template,
            "SELECT count(DISTINCT payload_digest) FROM relay_publications WHERE position IN (1,2)"),
        "two publications of identical content must share one payload row");
    assertEquals(
        2L,
        one(template, "SELECT count(DISTINCT payload_digest) FROM relay_publications"),
        "alpha and beta are two renderings across three publications");
    assertEquals(
        1L,
        one(
            template,
            """
            SELECT count(*) FROM relay_admissions a JOIN relay_publications p
              ON p.scope_key=a.scope_key AND p.topic=a.topic AND p.position=a.publication_position
            WHERE a.publication_position=1 AND a.payload_digest=p.payload_digest
            """),
        "the admission and the publication of identical content must reach the same row");
    assertEquals(
        3L,
        one(
            template,
            """
            SELECT count(*) FROM relay_payloads q WHERE EXISTS
              (SELECT 1 FROM relay_publications r WHERE r.payload_digest=q.digest)
              OR EXISTS (SELECT 1 FROM relay_admissions a WHERE a.payload_digest=q.digest)
            """),
        "the backfill leaves no unreferenced payload behind");

    // V194's structural half. The dropped column takes V135's CHECK with it, and the reference
    // becomes the row's only way to hold a payload.
    for (String table : List.of("relay_publications", "relay_admissions")) {
      assertEquals(
          0L,
          one(
              template,
              "SELECT count(*) FROM information_schema.columns WHERE table_name=? AND column_name='payload'",
              table),
          table + " must no longer carry its own payload column");
      assertEquals(
          "NO",
          template.queryForObject(
              "SELECT is_nullable FROM information_schema.columns WHERE table_name=? AND column_name='payload_digest'",
              String.class,
              table),
          table + ".payload_digest must be NOT NULL");
      assertEquals(
          0L,
          one(
              template,
              "SELECT count(*) FROM pg_constraint WHERE conname=?",
              table + "_payload_check"),
          "V135's payload CHECK goes with the dropped column");
    }
    assertEquals(
        1L,
        one(
            template,
            "SELECT count(*) FROM pg_constraint WHERE conname='relay_payloads_payload_check'"),
        "the same bounds are now enforced once, on the stored row");

    // The rows are still readable as what was inserted, through the joined reader.
    var topic = new Relay.TopicKey(backfilledProject(), "release.observed");
    assertEquals(
        new RelayPayload.Text("alpha"),
        backfilledBroker().retained(topic, "event-1").orElseThrow().event().payload());
    assertEquals(
        new RelayPayload.Text("beta"),
        backfilledBroker().retained(topic, "event-3").orElseThrow().event().payload());
  }

  /**
   * F: the two renderings. A backfilled digest hashes PostgreSQL's normalized {@code payload::text}
   * and a runtime digest hashes the codec's serialization, so identical content can land under two
   * digests. The contract is that the divergence costs a missed deduplication and nothing else:
   * both digests read back as the same payload, each names exactly its own bytes, and reclaiming
   * one never touches the other or the publication that references it.
   */
  @Test
  void aBackfilledAndARuntimeDigestOfTheSameContentBothResolve() {
    var template = backfilled();
    var payloads = backfilledStore();
    String codecRendering = RelayPayloadCodec.write(new RelayPayload.Text("alpha"));
    String backfilledDigest =
        Objects.requireNonNull(
            template.queryForObject(
                "SELECT payload_digest FROM relay_publications WHERE position=1", String.class));
    String storedRendering =
        Objects.requireNonNull(
            template.queryForObject(
                "SELECT payload::text FROM relay_payloads WHERE digest=?",
                String.class,
                backfilledDigest));
    String runtimeDigest = payloads.store(new RelayPayload.Text("alpha"), NOW);

    // The divergence is real and this is exactly where it comes from: the migration hashed what
    // PostgreSQL renders, the store hashes what the codec writes, and the two strings differ.
    assertNotEquals(
        codecRendering, storedRendering, "PostgreSQL's JSONB rendering is not the codec's");
    assertEquals(
        io.aeyer.plowshare.protocol.RelayPort.hash(storedRendering),
        backfilledDigest,
        "the migration's digest is SHA-256 over the stored rendering");
    assertEquals(
        io.aeyer.plowshare.protocol.RelayPort.hash(codecRendering),
        runtimeDigest,
        "the store's digest is SHA-256 over the codec's serialization");
    assertNotEquals(backfilledDigest, runtimeDigest);

    // The whole cost is a missed deduplication: two rows of equivalent JSON, both readable.
    var distinct = Set.of(backfilledDigest, runtimeDigest);
    assertEquals(
        (long) distinct.size(),
        one(template, "SELECT count(*) FROM relay_payloads WHERE payload=?::jsonb", codecRendering),
        "the worst case is one row per rendering of equivalent JSON, never a missing row");
    for (String digest : distinct)
      assertEquals(
          new RelayPayload.Text("alpha"),
          RelayPayloadCodec.read(
              RelayPayload.Kind.TEXT, 1, payloads.load(List.of(digest)).get(digest).json()),
          "both digests must resolve through a read");

    // Reclaiming the unreferenced runtime row leaves the referenced backfilled row and its
    // publication untouched: the divergence never produces a wrong delete.
    template.update(
        "UPDATE relay_payloads SET ejection_at=?", Timestamp.from(NOW.plus(Duration.ofDays(4000))));
    template.update(
        "UPDATE relay_payloads SET ejection_at=? WHERE digest=?",
        Timestamp.from(NOW.minusSeconds(1)),
        runtimeDigest);
    assertEquals(1, payloads.sweep(NOW, 10));
    assertEquals(
        0L, one(template, "SELECT count(*) FROM relay_payloads WHERE digest=?", runtimeDigest));
    assertEquals(
        1L, one(template, "SELECT count(*) FROM relay_payloads WHERE digest=?", backfilledDigest));
    assertEquals(
        new RelayPayload.Text("alpha"),
        backfilledBroker()
            .retained(new Relay.TopicKey(backfilledProject(), "release.observed"), "event-1")
            .orElseThrow()
            .event()
            .payload());
  }

  /**
   * V194's own backfill. A single forward migration never reaches it with work to do, because V193
   * sets every digest first; the only way a null survives is a server that predates the digest
   * appending during a rolling deploy. This stops at V193, writes exactly that row, and then
   * migrates to head so V194's {@code INSERT ... DISTINCT ON}, both {@code UPDATE}s, the {@code SET
   * NOT NULL} and the {@code DROP COLUMN} all run with rows in front of them.
   */
  @Test
  void v194BackfillsRowsLeftWithoutADigestByARollingDeploy() {
    jdbc.execute("DROP DATABASE IF EXISTS " + STAGED_DATABASE);
    jdbc.execute("CREATE DATABASE " + STAGED_DATABASE);
    var staged =
        new DriverManagerDataSource(
            "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ":"
                + POSTGRES.getFirstMappedPort()
                + "/"
                + STAGED_DATABASE,
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    Flyway.configure().dataSource(staged).target("193").load().migrate();
    var template = new JdbcTemplate(staged);
    long owner =
        Objects.requireNonNull(
            template.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('relay-staged','fixture') RETURNING id",
                Long.class));
    String scope = RelayScopeCodec.write(new Relay.TopicKey(owner, "release.observed"));
    var at = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
    template.update(
        "INSERT INTO relay_topics(scope_key,project_id,name,payload_kind,retention_seconds,last_position) VALUES(?,?,'release.observed','TEXT',345600,2)",
        scope,
        owner);
    template.update(
        "INSERT INTO relay_subscriptions(scope_key,topic,subscriber,seen_through,seen_at) VALUES(?,'release.observed','notices',0,?)",
        scope,
        at);
    // Two publications of identical content, so V194's DISTINCT ON has a duplicate to collapse,
    // and one admission, so both of its UPDATEs have a row. Every payload_digest is left null.
    for (int position = 1; position <= 2; position++)
      template.update(
          """
          INSERT INTO relay_publications(scope_key,topic,position,event_id,publisher,occurred_at,
            published_at,schema_version,payload,payload_digest)
          VALUES(?,'release.observed',?,?,'source',?,?,1,?::jsonb,NULL)
          """,
          scope,
          position,
          "staged-" + position,
          at,
          at,
          RelayPayloadCodec.write(new RelayPayload.Text("staged")));
    var routing = RelayDeliveries.SourcePin.of("notices/routes.js", "export const manifest = {};");
    template.update(
        """
        INSERT INTO relay_admissions(scope_key,topic,subscriber,publication_position,event_id,publisher,
          occurred_at,published_at,payload_kind,schema_version,payload,payload_digest,routing_path,
          routing_source,routing_hash,branch_count,admitted_at)
        VALUES(?,'release.observed','notices',?,'staged-1','source',?,?,'TEXT',1,?::jsonb,NULL,?,?,?,0,?)
        """,
        scope,
        1,
        at,
        at,
        RelayPayloadCodec.write(new RelayPayload.Text("staged")),
        routing.path(),
        routing.source(),
        routing.sha256(),
        at);
    assertEquals(
        2L, one(template, "SELECT count(*) FROM relay_publications WHERE payload_digest IS NULL"));
    assertEquals(0L, one(template, "SELECT count(*) FROM relay_payloads"));

    Flyway.configure().dataSource(staged).load().migrate();

    assertEquals(
        1L,
        one(template, "SELECT count(*) FROM relay_payloads"),
        "DISTINCT ON collapses the duplicate content into one stored row");
    assertEquals(
        0L,
        one(
            template,
            "SELECT count(*) FROM relay_payloads WHERE digest <> encode(sha256(convert_to(payload::text,'UTF8')),'hex') OR byte_length <> octet_length(payload::text)"));
    assertEquals(
        1L,
        one(template, "SELECT count(DISTINCT payload_digest) FROM relay_publications"),
        "both publications now name the single stored row");
    assertEquals(
        1L,
        one(
            template,
            """
            SELECT count(*) FROM relay_admissions a JOIN relay_payloads q ON q.digest=a.payload_digest
            """),
        "V194's admission UPDATE resolved its row too");
    for (String table : List.of("relay_publications", "relay_admissions"))
      assertEquals(
          0L,
          one(
              template,
              "SELECT count(*) FROM information_schema.columns WHERE table_name=? AND column_name='payload'",
              table));
    var reader =
        new JdbcRelayRepository(
            template, unitOfWork(staged), payloadStore(template, unitOfWork(staged), OPEN_CEILING));
    assertEquals(
        new RelayPayload.Text("staged"),
        reader
            .retained(new Relay.TopicKey(owner, "release.observed"), "staged-1")
            .orElseThrow()
            .event()
            .payload());
  }

  private static synchronized JdbcRelayPayloadStore backfilledStore() {
    var template = backfilled();
    if (backfilledPayloads == null)
      backfilledPayloads = payloadStore(template, unitOfWork(backfilledSource), OPEN_CEILING);
    return backfilledPayloads;
  }

  private static synchronized JdbcRelayRepository backfilledBroker() {
    var template = backfilled();
    if (backfilledBroker == null)
      backfilledBroker =
          new JdbcRelayRepository(template, unitOfWork(backfilledSource), backfilledStore());
    return backfilledBroker;
  }

  private static long backfilledProject() {
    return Objects.requireNonNull(
        backfilled()
            .queryForObject("SELECT id FROM projects WHERE name='relay-backfill'", Long.class));
  }

  // ---------------------------------------------------------------------------------------------
  // E: references, concurrency and reclamation.
  // ---------------------------------------------------------------------------------------------

  @Test
  void deletingAReferencedPayloadIsRefusedByTheForeignKey() {
    var topic = configure("payload.referenced", Duration.ofSeconds(1));
    broker.append(topic, draft("one", "alpha"), NOW);
    String digest = publicationDigest(topic, 1);
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("DELETE FROM relay_payloads WHERE digest=?", digest),
        "the foreign key must refuse a delete of referenced bytes");
    assertEquals(1L, one(jdbc, "SELECT count(*) FROM relay_payloads WHERE digest=?", digest));
    // The sweep's own guard keeps it from reaching that refusal at all.
    mature(digest);
    assertEquals(0, store.sweep(NOW, 10));
    assertEquals(1L, one(jdbc, "SELECT count(*) FROM relay_payloads WHERE digest=?", digest));
  }

  @Test
  void concurrentIdenticalAppendsStoreOnePayload() {
    var topic = configure("payload.concurrent", Duration.ofHours(1));
    var payload = new RelayPayload.Text("identical under contention");
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(8);
    try {
      var running = new ArrayList<Future<String>>();
      for (int index = 0; index < 4; index++)
        running.add(
            pool.submit(
                () -> {
                  await(start);
                  return store.store(payload, NOW);
                }));
      for (int index = 0; index < 4; index++) {
        String eventId = "race-" + index;
        running.add(
            pool.submit(
                () -> {
                  await(start);
                  return broker
                      .append(
                          topic, new Relay.Draft(eventId, "source", NOW, null, null, payload), NOW)
                      .event()
                      .eventId();
                }));
      }
      start.countDown();
      var results = new ArrayList<String>();
      for (var future : running) results.add(get(future));
      assertEquals(8, results.size());
      String digest = store.store(payload, NOW);
      assertEquals(
          1L,
          one(jdbc, "SELECT count(*) FROM relay_payloads WHERE digest=?", digest),
          "ON CONFLICT (digest) must leave exactly one row");
      assertEquals(
          1L,
          one(jdbc, "SELECT count(*) FROM relay_payloads"),
          "identical content under contention is stored once");
      assertEquals(
          4L,
          one(
              jdbc,
              "SELECT count(*) FROM relay_publications WHERE scope_key=? AND payload_digest=?",
              RelayScopeCodec.write(topic),
              digest),
          "all four publications reference the single stored row");
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Review Focus 2. Two orderings of the same race.
   *
   * <p>Order one is the ordinary case: the reference is already committed when the guard runs, so
   * the guard sees it and defers.
   *
   * <p>Order two is the window the guard cannot close, and it is reached with an admission rather
   * than an append. An admission takes its {@code payload_digest} from the publication and never
   * calls {@link RelayPayloadStore#store}, so unlike a repeat append it neither re-arms the staging
   * window nor holds the store's row lock -- which is what leaves the payload a live candidate
   * while the reference is in flight. The insert's own foreign-key check holds a {@code KEY SHARE}
   * lock on the payload row, so the sweep's delete waits on it and the reference is committed by
   * the time the lock is released; the {@code NO ACTION} check then raises. The sweep must absorb
   * that per candidate: report no removal, keep the row, and still defer it, because the aborted
   * transaction rolled back the deferral the survivor path would have written and a row left
   * matured is re-selected on every pass.
   */
  @Test
  void aSweepConcurrentWithAReferenceCommittedInItsWindowKeepsAndDefersThePayload() {
    var topic = configure("payload.sweep.race", Duration.ofSeconds(1));
    var subscription = new Relay.SubscriptionKey(topic, "notices");
    var payload = new RelayPayload.Text("contended bytes");
    var admittedAt = Instant.parse("2026-10-11T03:00:00Z");
    broker.append(topic, new Relay.Draft("first", "source", NOW, null, null, payload), NOW);
    broker.subscribe(subscription, Relay.Start.OLDEST_RETAINED, NOW);
    String digest = publicationDigest(topic, 1);

    // Order one: the reference commits first. The guard's NOT EXISTS sees it and defers.
    mature(digest);
    assertEquals(0, store.sweep(NOW, 10));
    assertEquals(1L, one(jdbc, "SELECT count(*) FROM relay_payloads WHERE digest=?", digest));
    assertTrue(ejection(digest).isAfter(NOW));

    // Order two: nothing references the digest when the guard runs, and an admission naming it
    // commits while the sweep's delete is blocked on the payload row.
    jdbc.update("DELETE FROM relay_publications WHERE scope_key=?", RelayScopeCodec.write(topic));
    mature(digest);
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var admitting =
          pool.submit(
              () ->
                  transactions.inTransaction(
                      () -> {
                        admitDirectly(topic, digest, admittedAt);
                        held.countDown();
                        await(release);
                        return Boolean.TRUE;
                      }));
      await(held);
      var sweeping = pool.submit(() -> store.sweep(NOW, 10));
      // Let the sweep reach its DELETE and block on the referencing insert's lock.
      Thread.sleep(500);
      release.countDown();
      assertEquals(Boolean.TRUE, get(admitting));
      assertEquals(
          0,
          get(sweeping),
          "the foreign-key refusal must be absorbed per candidate, not end the pass");
      assertEquals(
          1L,
          one(jdbc, "SELECT count(*) FROM relay_payloads WHERE digest=?", digest),
          "the payload must survive the race");
      // The deferral proves the survivor was rescheduled rather than merely skipped, and the exact
      // date proves it came from the horizon query: one admission, no deliveries.
      assertEquals(
          admittedAt.plus(SETTLED),
          ejection(digest),
          "a refused candidate must be deferred to its horizon, or it is re-selected every pass");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("test interrupted", interrupted);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * Inserts an admission naming a digest without going through {@link RelayDeliveries#admit}, which
   * needs the publication this race has already removed. The columns are the ones the repository
   * writes; only the publication lookup is replaced by the digest under test.
   */
  private void admitDirectly(Relay.TopicKey topic, String digest, Instant admittedAt) {
    var routing = RelayDeliveries.SourcePin.of("notices/routes.js", "export const manifest = {};");
    jdbc.update(
        """
        INSERT INTO relay_admissions(scope_key,topic,subscriber,publication_position,event_id,publisher,
          occurred_at,published_at,payload_kind,schema_version,payload_digest,routing_path,
          routing_source,routing_hash,branch_count,admitted_at)
        VALUES(?,?,'notices',1,'first','source',?,?,'TEXT',1,?,?,?,?,0,?)
        """,
        RelayScopeCodec.write(topic),
        topic.name(),
        Timestamp.from(NOW),
        Timestamp.from(NOW),
        digest,
        routing.path(),
        routing.source(),
        routing.sha256(),
        Timestamp.from(admittedAt));
  }

  /**
   * The other direction of the same race, and the one the conflict clause closes. A digest whose
   * earlier references were pruned sits matured; a repeat append of that same content stores it
   * again and then has to get through the topic lock, the locked retained read and position
   * allocation before its own publication row exists. Nothing in that window references the
   * payload, so unless the repeat store re-arms {@code ejection_at} and locks the row, a sweep in
   * the window reclaims it and the appender's insert fails its foreign key.
   *
   * <p>The window is held open by taking the topic lock from another transaction, which blocks the
   * append after its store has committed and before its insert. This test discriminates: with the
   * conflict clause reduced to {@code DO NOTHING} the sweep reclaims the row and both the sweep
   * count and the append fail.
   */
  @Test
  void aRepeatAppendOfAMaturedDigestRacingASweepKeepsItsPayload() {
    var topic = configure("payload.repeat.race", Duration.ofHours(1));
    var payload = new RelayPayload.Text("repeated bytes");
    broker.append(topic, new Relay.Draft("first", "source", NOW, null, null, payload), NOW);
    String digest = publicationDigest(topic, 1);
    // Retention has removed the only reference, and the row has matured.
    jdbc.update("DELETE FROM relay_publications WHERE scope_key=?", RelayScopeCodec.write(topic));
    jdbc.update(
        "UPDATE relay_topics SET last_position=0 WHERE scope_key=?", RelayScopeCodec.write(topic));
    mature(digest);
    assertFalse(ejection(digest).isAfter(NOW), "the fixture must start as a sweep candidate");

    var release = new CountDownLatch(1);
    var holding = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var lock =
          pool.submit(
              () ->
                  transactions.inTransaction(
                      () -> {
                        jdbc.queryForList(
                            "SELECT 1 FROM relay_topics WHERE scope_key=? AND name=? FOR UPDATE",
                            Integer.class,
                            RelayScopeCodec.write(topic),
                            topic.name());
                        holding.countDown();
                        await(release);
                        return Boolean.TRUE;
                      }));
      await(holding);
      var appending =
          pool.submit(
              () ->
                  broker
                      .append(
                          topic, new Relay.Draft("second", "source", NOW, null, null, payload), NOW)
                      .position());
      // The append is blocked on the topic lock, which it reaches only after its store committed.
      awaitBlocked();
      assertEquals(
          0,
          store.sweep(NOW, 10),
          "the repeat store must have re-armed the staging window, leaving no candidate");
      assertTrue(
          ejection(digest).isAfter(NOW), "the repeat store must have moved the ejection date out");
      release.countDown();
      assertEquals(Boolean.TRUE, get(lock));
      assertEquals(1L, get(appending), "the repeat append must not fail its foreign key");
      assertEquals(
          payload,
          broker.retained(topic, "second").orElseThrow().event().payload(),
          "the repeat publication must still read its bytes");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * The regression guard for the sweep's date predicate. {@code sweep} selects every candidate in
   * one query and then reclaims them one transaction at a time, so a digest selected early can wait
   * behind earlier candidates. A repeat {@code store} that re-arms it in that interval changes
   * nothing the reference checks look at, so a delete that named only the digest and the two {@code
   * NOT EXISTS} checks would reclaim it under the appender. The interval is made deterministic by a
   * unit of work that, as the first candidate's transaction opens, stores the second candidate's
   * payload again. With {@code p.ejection_at<=?} removed from the delete this test fails: the
   * second payload is reclaimed and the pass reports two removals.
   */
  @Test
  void aCandidateReArmedAfterSelectionSurvivesThePassThatSelectedIt() {
    var first = new RelayPayload.Text("first candidate");
    var second = new RelayPayload.Text("second candidate");
    String firstDigest = store.store(first, NOW);
    String secondDigest = store.store(second, NOW);
    jdbc.update(
        "UPDATE relay_payloads SET ejection_at=? WHERE digest=?",
        Timestamp.from(NOW.minusSeconds(20)),
        firstDigest);
    jdbc.update(
        "UPDATE relay_payloads SET ejection_at=? WHERE digest=?",
        Timestamp.from(NOW.minusSeconds(10)),
        secondDigest);
    var armed = new AtomicBoolean();
    UnitOfWork reArming =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> work) {
            if (armed.compareAndSet(false, true)) store.store(second, NOW);
            return transactions.inTransaction(work);
          }
        };
    var sweeping = payloadStore(jdbc, reArming, OPEN_CEILING);

    assertEquals(
        1, sweeping.sweep(NOW, 10), "only the first candidate is unreferenced and still matured");
    assertEquals(
        0L,
        one(jdbc, "SELECT count(*) FROM relay_payloads WHERE digest=?", firstDigest),
        "the untouched candidate is reclaimed");
    assertEquals(
        1L,
        one(jdbc, "SELECT count(*) FROM relay_payloads WHERE digest=?", secondDigest),
        "a candidate re-armed after selection must survive the pass that selected it");
    assertTrue(ejection(secondDigest).isAfter(NOW), "and it stays deferred");
  }

  /** Waits until some backend is waiting on a lock, which is the append reaching the topic lock. */
  private void awaitBlocked() {
    for (int attempt = 0; attempt < 100; attempt++) {
      if (one(jdbc, "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock'") > 0)
        return;
      try {
        Thread.sleep(100);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("test interrupted", interrupted);
      }
    }
    throw new IllegalStateException("the append never reached the topic lock");
  }

  @Test
  void retentionDeletesAPrefixWhileAdmissionsKeepTheirPayload() {
    var topic = configure("payload.retention", Duration.ofSeconds(1));
    var subscription = new Relay.SubscriptionKey(topic, "notices");
    var admittedAt = Instant.parse("2026-10-11T03:00:00Z");
    broker.append(topic, draft("kept", "alpha"), NOW);
    broker.append(topic, draft("dropped", "beta"), NOW);
    broker.subscribe(subscription, Relay.Start.OLDEST_RETAINED, NOW);
    String kept = publicationDigest(topic, 1);
    String dropped = publicationDigest(topic, 2);
    assertNotEquals(kept, dropped);
    deliveries.admit(
        new RelayDeliveries.AdmissionKey(subscription, 1), decision(List.of()), admittedAt);

    // Retention removes the publication prefix; the admission is retained independently of it.
    assertEquals(2, broker.prune(topic, NOW.plus(Duration.ofDays(1)), 100));
    assertEquals(
        0L,
        one(
            jdbc,
            "SELECT count(*) FROM relay_publications WHERE scope_key=?",
            RelayScopeCodec.write(topic)));
    matureAll();
    assertEquals(1, store.sweep(NOW, 10), "only the payload no admission holds is reclaimed");
    assertEquals(0L, one(jdbc, "SELECT count(*) FROM relay_payloads WHERE digest=?", dropped));
    assertEquals(1L, one(jdbc, "SELECT count(*) FROM relay_payloads WHERE digest=?", kept));

    // Once the settled admission is pruned, the last reference is gone and the bytes follow.
    assertEquals(1, deliveries.pruneSettled(admittedAt.plus(SETTLED).plusSeconds(1), SETTLED, 100));
    mature(kept);
    assertEquals(1, store.sweep(NOW, 10));
    assertEquals(0L, one(jdbc, "SELECT count(*) FROM relay_payloads"));
  }

  @Test
  void aCascadedProjectDeleteLeavesPayloadsReclaimable() {
    var tight = payloadStore(jdbc, transactions, TIGHT_CEILING);
    var repository = new JdbcRelayRepository(jdbc, transactions, tight);
    var topic = configure(repository, "payload.cascade", Duration.ofDays(3650));
    var subscription = new Relay.SubscriptionKey(topic, "notices");
    repository.append(topic, draft("cascaded", "alpha"), NOW);
    repository.subscribe(subscription, Relay.Start.OLDEST_RETAINED, NOW);
    deliveries.admit(new RelayDeliveries.AdmissionKey(subscription, 1), decision(List.of()), NOW);
    String digest = publicationDigest(topic, 1);

    // A ten-year retention puts the horizon far out; the ceiling is what actually bounds it.
    mature(digest);
    assertEquals(0, tight.sweep(NOW, 10));
    Instant deferred = ejection(digest);
    assertTrue(deferred.isAfter(NOW), "a surviving candidate is rescheduled after now");
    assertFalse(
        deferred.isAfter(NOW.plus(TIGHT_CEILING)),
        "the deferral ceiling bounds how long unobserved cascades can hide unreachable bytes");

    // Deleting the project cascades through topics, publications and admissions unobserved.
    jdbc.update("DELETE FROM projects WHERE id=?", project);
    assertEquals(
        0L,
        one(
            jdbc,
            "SELECT count(*) FROM relay_publications WHERE scope_key=?",
            RelayScopeCodec.write(topic)));
    assertEquals(
        0L,
        one(
            jdbc,
            "SELECT count(*) FROM relay_admissions WHERE scope_key=?",
            RelayScopeCodec.write(topic)));
    assertEquals(1L, one(jdbc, "SELECT count(*) FROM relay_payloads WHERE digest=?", digest));
    assertEquals(
        1,
        tight.sweep(deferred, 10),
        "the next examination within the ceiling reclaims the orphaned bytes");
    assertEquals(0L, one(jdbc, "SELECT count(*) FROM relay_payloads"));
  }

  // ---------------------------------------------------------------------------------------------
  // D: the horizon query. Each fixture puts its expected date exactly on an hour boundary well
  // after NOW, so the asserted value is the date the SQL computed and not a rounding artefact.
  // ---------------------------------------------------------------------------------------------

  @Test
  void aPublicationHorizonIsItsPublishedTimePlusTheTopicRetention() {
    var topic = configure("payload.horizon.publication", Duration.ofHours(1));
    var publishedAt = Instant.parse("2026-10-10T05:00:00Z");
    broker.append(topic, draft("published", "alpha"), publishedAt);
    assertEquals(
        Instant.parse("2026-10-10T06:00:00Z"),
        deferral(store, publicationDigest(topic, 1)),
        "published_at + retention_seconds * interval '1 second'");
  }

  @Test
  void aSettledAdmissionHorizonUsesTheLatestDeliveryUpdate() {
    var topic = configure("payload.horizon.settled", Duration.ofSeconds(1));
    var subscription = new Relay.SubscriptionKey(topic, "notices");
    var admittedAt = Instant.parse("2026-10-11T03:00:00Z");
    var acceptedAt = Instant.parse("2026-10-11T07:00:00Z");
    broker.append(topic, draft("settled", "alpha"), Instant.parse("2026-01-01T00:00:00Z"));
    broker.subscribe(subscription, Relay.Start.OLDEST_RETAINED, NOW);
    deliveries.admit(
        new RelayDeliveries.AdmissionKey(subscription, 1),
        decision(List.of(branch("only"))),
        admittedAt);
    var claimed = deliveries.claim(subscription, "worker", LEASE, acceptedAt).orElseThrow();
    var dispatching = deliveries.prepareDispatch(claimed.claim(), acceptedAt);
    var accepted =
        deliveries.accepted(
            dispatching.claim(), new RelayDeliveries.Receipt("fixture", "receipt"), acceptedAt);
    assertEquals(RelayDeliveries.State.ACCEPTED, accepted.state());
    // The publication's own contribution elapsed in January, so GREATEST reports the admission's.
    assertEquals(
        acceptedAt.plus(SETTLED),
        deferral(store, publicationDigest(topic, 1)),
        "GREATEST(admitted_at, max delivery updated_at) + settledRetention");
  }

  @Test
  void anAdmissionWithoutDeliveriesUsesItsAdmittedTime() {
    var topic = configure("payload.horizon.bare", Duration.ofSeconds(1));
    var subscription = new Relay.SubscriptionKey(topic, "notices");
    var admittedAt = Instant.parse("2026-10-12T04:00:00Z");
    broker.append(topic, draft("bare", "alpha"), Instant.parse("2026-01-01T00:00:00Z"));
    broker.subscribe(subscription, Relay.Start.OLDEST_RETAINED, NOW);
    deliveries.admit(
        new RelayDeliveries.AdmissionKey(subscription, 1), decision(List.of()), admittedAt);
    assertEquals(
        admittedAt.plus(SETTLED),
        deferral(store, publicationDigest(topic, 1)),
        "COALESCE over an admission with no delivery falls back to admitted_at");
  }

  /**
   * Three outcomes are distinguishable here. The unsettled admission is excluded from the settled
   * maximum (including it would give 2026-11-03), the settled admission's elapsed horizon loses to
   * the unsettled flag (its own date would floor to 02:00), and the flag defers by the staging
   * window instead: 01:30 + 5h, truncated down to the hour.
   */
  @Test
  void aNonTerminalDeliverySetsTheUnsettledFlagAndIsExcludedFromTheSettledMaximum() {
    var topic = configure("payload.horizon.unsettled", Duration.ofSeconds(1));
    var settledSubscriber = new Relay.SubscriptionKey(topic, "settled-notices");
    var unsettledSubscriber = new Relay.SubscriptionKey(topic, "unsettled-notices");
    broker.append(topic, draft("shared", "alpha"), Instant.parse("2026-01-01T00:00:00Z"));
    broker.subscribe(settledSubscriber, Relay.Start.OLDEST_RETAINED, NOW);
    broker.subscribe(unsettledSubscriber, Relay.Start.OLDEST_RETAINED, NOW);
    deliveries.admit(
        new RelayDeliveries.AdmissionKey(settledSubscriber, 1),
        decision(List.of()),
        Instant.parse("2026-09-01T00:00:00Z"));
    deliveries.admit(
        new RelayDeliveries.AdmissionKey(unsettledSubscriber, 1),
        decision(List.of(branch("waiting"))),
        Instant.parse("2026-10-04T00:00:00Z"));
    assertEquals(
        RelayDeliveries.State.READY,
        deliveries
            .delivery(
                deliveries
                    .admission(new RelayDeliveries.AdmissionKey(unsettledSubscriber, 1))
                    .orElseThrow()
                    .deliveries()
                    .getFirst())
            .orElseThrow()
            .state());
    assertEquals(
        Instant.parse("2026-10-05T06:00:00Z"),
        deferral(store, publicationDigest(topic, 1)),
        "an unbounded reference is re-examined after the staging window, not at a settled date");
  }

  @Test
  void aTenYearRetentionIsAValidIntervalAndIsClampedToTheCeiling() {
    var topic = configure("payload.horizon.decade", Duration.ofDays(3650));
    var publishedAt = Instant.parse("2026-10-05T00:00:00Z");
    broker.append(topic, draft("decade", "alpha"), publishedAt);
    String digest = publicationDigest(topic, 1);
    // 3650 days of retention_seconds multiplied by interval '1 second' is a valid timestamptz.
    assertEquals(publishedAt.plusSeconds(3650L * 86400L), deferral(store, digest));
    // The same horizon through a 48-hour ceiling stops at now + 48h, truncated down to the hour.
    var tight = payloadStore(jdbc, transactions, TIGHT_CEILING);
    assertEquals(NOW.plus(TIGHT_CEILING).truncatedTo(ChronoUnit.HOURS), deferral(tight, digest));
  }

  @Test
  void anElapsedRetentionStillReschedulesStrictlyAfterNow() {
    var topic = configure("payload.horizon.elapsed", Duration.ofSeconds(1));
    broker.append(topic, draft("elapsed", "alpha"), Instant.parse("2026-01-01T00:00:00Z"));
    Instant next = deferral(store, publicationDigest(topic, 1));
    assertEquals(
        NEXT_HOUR,
        next,
        "a horizon already in the past must not keep a referenced payload in the candidate set");
    assertTrue(next.isAfter(NOW));
  }

  // ---------------------------------------------------------------------------------------------
  // F: the page boundary.
  // ---------------------------------------------------------------------------------------------

  /**
   * The window input is byte-identical. {@code byte_length} is written as {@code
   * octet_length(payload::text)} by the store and by both migrations, and every bounded page now
   * sums the column where it summed that expression. The second half asserts the one thing the
   * substitution did change: the sum's type went from bigint to numeric, which must compare and
   * render identically for the integral values a byte count takes.
   */
  @Test
  void storedByteLengthIsTheSameNumberThePagesUsedToComputeInline() {
    var topic = configure("payload.bytes", Duration.ofHours(1));
    for (int index = 1; index <= 3; index++)
      broker.append(topic, draft("sized-" + index, "payload ".repeat(index * 40)), NOW);
    assertEquals(
        0L,
        one(
            jdbc,
            "SELECT count(*) FROM relay_payloads WHERE byte_length <> octet_length(payload::text)"),
        "byte_length must equal the expression the pages used to compute per row");
    assertEquals(
        3L,
        one(
            jdbc,
            """
            SELECT count(*) FROM relay_publications p
              JOIN relay_payloads q ON q.digest=p.payload_digest
            WHERE p.scope_key=?
            """,
            RelayScopeCodec.write(topic)),
        "the join below must actually see all three rows; a zero count would pass vacuously");
    assertEquals(
        0L,
        one(
            jdbc,
            """
            SELECT count(*) FROM (
              SELECT SUM(q.byte_length + 4096) OVER (ORDER BY p.position) AS stored,
                     SUM(octet_length(q.payload::text) + 4096) OVER (ORDER BY p.position) AS inline
              FROM relay_publications p JOIN relay_payloads q ON q.digest=p.payload_digest
              WHERE p.scope_key=?) pages
            WHERE stored <> inline
               OR to_jsonb(stored)::text <> to_jsonb(inline)::text
               OR pg_typeof(stored)::text <> 'numeric'
               OR pg_typeof(inline)::text <> 'bigint'
            """,
            RelayScopeCodec.write(topic)),
        "the numeric sum compares and renders as the bigint sum did, digit for digit");
  }

  /**
   * The actual prefix boundary, measured. {@code boundedProjection}'s bound is the only page budget
   * this test can straddle: the read page's budget is 319 MiB, which at the 50 MiB text ceiling
   * needs seven maximal payloads to cross, so it is not reachable as a fixture. The bound is
   * derived as {@code (maxWeight - 32768) / 64}, so choosing maxWeight makes it exact to the byte,
   * and a binary search recovers the projection's own size. Growing the payload by a known number
   * of bytes must move that size by exactly the same number: the boundary tracks the stored bytes
   * one for one, through {@code byte_length} and through the numeric {@code page_bytes} the sized
   * record carries.
   */
  @Test
  void theBoundedPageBoundaryMovesByExactlyTheStoredPayloadBytes() {
    var shorterTopic = configure("payload.page.short", Duration.ofHours(1));
    var longerTopic = configure("payload.page.long", Duration.ofHours(1));
    broker.append(shorterTopic, draft("sized", "a".repeat(1000)), NOW);
    broker.append(longerTopic, draft("sized", "a".repeat(1064)), NOW);
    long shorter = projectionBytes(shorterTopic);
    long longer = projectionBytes(longerTopic);
    assertEquals(
        64L,
        longer - shorter,
        "64 more bytes of stored payload must move the page boundary by exactly 64 bytes");
  }

  /** The smallest bound at which the events projection is not refused, which is its exact size. */
  private long projectionBytes(Relay.TopicKey topic) {
    long low = 1;
    long high = 65536;
    assertTrue(fits(topic, high), "the fixture must fit the search range");
    assertFalse(fits(topic, low), "the fixture must exceed the smallest bound");
    while (low + 1 < high) {
      long middle = (low + high) / 2;
      if (fits(topic, middle)) high = middle;
      else low = middle;
    }
    return high;
  }

  private boolean fits(Relay.TopicKey topic, long bound) {
    var logs =
        new JdbcRelayLogRepository(
            jdbc, transactions, ProjectionCache.direct(), weighted(32768 + 64 * bound));
    try {
      return logs.read(topic, 0, 16, "operator").events().size() == 1;
    } catch (ProjectionCache.Bypass refused) {
      return false;
    }
  }

  private static <T> ProjectionCache<T> weighted(long maxWeight) {
    return new ProjectionCache<>() {
      @Override
      public boolean enabled() {
        return true;
      }

      @Override
      public long maxWeight() {
        return maxWeight;
      }

      @Override
      public T read(
          SnapshotCache.Key key,
          boolean cacheable,
          Supplier<T> boundedLoader,
          Supplier<T> uncachedLoader,
          ToLongFunction<T> weight) {
        return boundedLoader.get();
      }
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures.
  // ---------------------------------------------------------------------------------------------

  private Relay.TopicKey configure(String name, Duration retention) {
    return configure(broker, name, retention);
  }

  private Relay.TopicKey configure(
      JdbcRelayRepository repository, String name, Duration retention) {
    var topic = new Relay.TopicKey(project, name);
    repository.configureTopic(topic, RelayPayload.Kind.TEXT, new Relay.Policy(retention, null));
    return topic;
  }

  private static Relay.Draft draft(String eventId, String text) {
    return new Relay.Draft(eventId, "release-source", NOW, null, null, new RelayPayload.Text(text));
  }

  private static RelayDeliveries.Decision decision(List<RelayDeliveries.Branch> branches) {
    return new RelayDeliveries.Decision(
        RelayDeliveries.SourcePin.of("notices/routes.js", "export const manifest = {};"), branches);
  }

  private static RelayDeliveries.Branch branch(String name) {
    return new RelayDeliveries.Branch(name, "agent-message", null);
  }

  private String publicationDigest(Relay.TopicKey topic, long position) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT payload_digest FROM relay_publications WHERE scope_key=? AND topic=? AND position=?",
            String.class,
            RelayScopeCodec.write(topic),
            topic.name(),
            position));
  }

  /** Makes every stored payload a sweep candidate. */
  private void matureAll() {
    jdbc.update("UPDATE relay_payloads SET ejection_at=?", Timestamp.from(NOW.minusSeconds(1)));
  }

  /** Makes exactly one digest a sweep candidate, so a pass examines only the row under test. */
  private void mature(String digest) {
    jdbc.update(
        "UPDATE relay_payloads SET ejection_at=?", Timestamp.from(NOW.plus(Duration.ofDays(4000))));
    jdbc.update(
        "UPDATE relay_payloads SET ejection_at=? WHERE digest=?",
        Timestamp.from(NOW.minusSeconds(1)),
        digest);
  }

  /** Sweeps one referenced candidate and returns the date the horizon rescheduled it to. */
  private Instant deferral(JdbcRelayPayloadStore sweeper, String digest) {
    mature(digest);
    assertEquals(0, sweeper.sweep(NOW, 10), "a referenced payload is never deleted");
    Instant next = ejection(digest);
    assertTrue(next.isAfter(NOW), "the next examination must be strictly after now");
    return next;
  }

  private Instant ejection(String digest) {
    return Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT ejection_at FROM relay_payloads WHERE digest=?", Timestamp.class, digest))
        .toInstant();
  }

  private static long one(JdbcTemplate template, String sql, Object... arguments) {
    return Objects.requireNonNull(template.queryForObject(sql, Long.class, arguments));
  }

  private static <T> T get(Future<T> future) {
    try {
      return future.get(30, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("test interrupted", interrupted);
    } catch (java.util.concurrent.ExecutionException failed) {
      if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
      if (failed.getCause() instanceof AssertionError assertion) throw assertion;
      throw new IllegalStateException("test task failed", failed.getCause());
    } catch (java.util.concurrent.TimeoutException expired) {
      throw new IllegalStateException("test task timed out", expired);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS))
        throw new IllegalStateException("test latch timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("test interrupted", interrupted);
    }
  }
}
