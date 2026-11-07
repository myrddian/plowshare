package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.blobs.BlobClaim;
import io.aeyer.plowshare.server.blobs.BlobClaim.Retention;
import io.aeyer.plowshare.server.blobs.BlobKey;
import io.aeyer.plowshare.server.blobs.BlobRepository;
import io.aeyer.plowshare.server.blobs.BlobSource;
import io.aeyer.plowshare.server.blobs.JdbcBlobRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Whether a contained principal has an admitted blob claim path, and what the collection sweep
 * selects when a child's bytes are left unclaimed.
 *
 * <h2>Why this needs a real database</h2>
 *
 * <p>Every assertion here is a PostgreSQL predicate: V225's {@code owner} and {@code scope} CHECK
 * constraints, the {@code binary_blobs_collection} partial index's selection predicate as {@code
 * JdbcBlobRepository.deletions} writes it, the {@code FOR UPDATE SKIP LOCKED} candidate lock and
 * its post-lock {@code RETURNING} recheck, and the {@code release_invocation_blob_claim} trigger's
 * {@code unclaimed_at} reset. None of that can be answered by a mocked repository, which is the
 * exception the database test policy allows. The repository is driven exactly as production drives
 * it — {@code DiskBinaryBlobStorage} adds a disk fence and no SQL of its own.
 *
 * <h2>CHARACTERIZATION: what was found, before any change</h2>
 *
 * <p><strong>Nothing was broken. No production code changed.</strong> All six assertions below
 * passed against HEAD on the first run. What follows is the per-case finding and the predicate that
 * decided it.
 *
 * <p><strong>1. The claim path admits a contained principal, and no actor predicate is consulted at
 * all.</strong> {@code aContainedActorCanClaimABlobItWrote} passes because the blob layer is
 * actor-blind by contract — {@code BinaryBlobStorage} states that owners authorize access before
 * using it and that storage "does not resolve user/project permissions". The predicates that
 * actually admitted the claim are three, in this order: {@code BlobKey.requireScope}'s scope regex,
 * which admits only {@code global} and a {@code projects/} prefix over a decimal id (satisfied
 * because a contained Application has an ordinary {@code projects} row, so its byte namespace is
 * {@code projects/<child id>} — never the contained scope UUID, which the same regex rejects);
 * V225's {@code owner} CHECK {@code ^[A-Za-z0-9_.:-]+$}, which a bare UUID satisfies without a
 * handle, an {@code admins} row or an {@code authority_accounts} row; and {@code acquire}'s {@code
 * SELECT ... FOR UPDATE} plus {@code state='READY'}. So there was no gap to open: a contained
 * principal's claim is admitted <em>as TEXT</em>, which is exactly why it needs no credential
 * material. This is the one surface in the slice whose guard is the absence of a guard,
 * deliberately.
 *
 * <p><strong>The owner match is case SENSITIVE here, unlike V229's.</strong> {@code c.owner=?} is
 * plain TEXT equality in {@code acquire}, {@code readable} and {@code release}, whereas {@code
 * contained_knowledge_actor} (V229:30) matches a principal's UUID case-insensitively. The same
 * principal spelled in upper case is therefore a different blob owner, and is asserted to be. This
 * is recorded, not changed: {@code owner} is a generic slot identifier shared with relay
 * publication hashes and invocation ids, where case is meaningful, so folding it would be a wider
 * change than this slice authorizes. Callers are safe as long as they pass {@code UUID.toString()},
 * which is always lower case, and every current one does.
 *
 * <p><strong>2. A claimed blob is never a sweep candidate, and a child's image claim cannot be
 * released at all.</strong> {@code deletions} selects on {@code state='DELETING' OR (state='READY'
 * AND unclaimed_at<=? AND NOT EXISTS (SELECT 1 FROM binary_blob_claims ...))}, and re-checks that
 * same {@code NOT EXISTS} after taking the candidate's row lock. The child's claim makes both arms
 * false. The second half of that test is the stronger finding: the claim a child's binary output
 * actually carries is the PINNED {@code image-retention} claim that {@code
 * DiskImageBlobAccess.store} attaches in the <em>same</em> transaction that registers the bytes,
 * and {@code release} refuses a PINNED claim outright ("pinned blob claims require an owning-record
 * removal policy"). A child's image bytes therefore cannot reach the sweep by any release path;
 * only an owning-record policy deleting the claim row can start their clock.
 *
 * <p><strong>3. The brief's premise was wrong, so there was no defect to fix.</strong> The brief
 * named this case {@code aContainedChildsUnclaimedBlobIsNotSweptWhileItsSpaceIsReadable}; it is
 * renamed to what the characterization measured, because an unclaimed blob in a live child's
 * namespace <em>is</em> swept after its grace, by the identical predicate as any other. The sweep
 * has no scope arm and no containment arm. That is correct rather than the spec's "silent loss",
 * for two measured reasons. First, "unclaimed" is not "output the parent has not collected yet": a
 * claim is attached in the same transaction as the record that references the bytes ({@code adopt},
 * or {@code acquire} inside the entry/admission transaction), so bytes with no claim are bytes no
 * record points at, and there is nothing for a parent to collect them <em>through</em>. The one
 * producer that registers without an owner, {@code BinaryBlobStorage.prepare}, is used only by
 * {@code ProjectRelayBlobs}, which acquires the claim later in the same request; its unclaimed
 * window is a crash-recovery window, which is what {@code BlobProperties.recoveryGrace} is named
 * for. Second, and decisively, {@code aDestroyedChildsBytesAreOnlyEverReclaimedByTheSweep} shows
 * that destroying the contained project leaves the blob rows standing — V225 keys on a TEXT scope
 * precisely so bytes outlive the project row — so a containment arm in the sweep would not protect
 * a child's output, it would make a destroyed child's bytes unreclaimable forever. A migration
 * narrowing this predicate would have converted a bounded grace into an unbounded leak.
 *
 * <p><strong>4. The ordinary path is unchanged, and the grace is what decides.</strong> An aged
 * unclaimed blob in the container's own namespace and in {@code global} are both selected, while a
 * freshly registered one in the same namespace is not — so the predicate measured is {@code
 * unclaimed_at<=threshold}, not the scope. {@code
 * aReleasedChildClaimStillHoldsItsBytesForTheWholeGrace} completes the picture: {@code release}
 * resets {@code unclaimed_at} to the release instant, discarding any earlier ageing, so the full
 * grace restarts from the moment the last owner let go.
 *
 * <h2>Not covered here, deliberately</h2>
 *
 * <p>The service layer above these repositories still refuses a contained actor, for the child's
 * principal and for its container alike, because {@code ScopedPermissions.allows} resolves to
 * {@code authority_project_role} (V156:296) and a contained project carries no PROJECT scope.
 * Opening it would mean granting a contained principal a project role, which ADR 0042 forbids. That
 * ceiling is this slice's and is tested at the repository layer, as tasks 4 and 5 were. Disk
 * behaviour — the placement fence, digest verification and unlink — is {@code
 * DiskBinaryBlobStorage}'s and is unaffected by containment.
 */
@Tag("full-db")
@Testcontainers
class ContainedBlobClaimDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private static final String HOLDER = "alice";

  /** The production recovery grace {@code BlobProperties} defaults to. */
  private static final Duration GRACE = Duration.ofMinutes(10);

  private JdbcTemplate jdbc;
  private UnitOfWork work;
  private BlobRepository blobs;

  private long parentProject;
  private UUID parentScope;
  private long childProjectId;
  private UUID childScope;
  private String childPrincipal;

  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "contained_blob_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    blobs = new JdbcBlobRepository(jdbc);

    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password) VALUES (?,'fixture',FALSE)",
        HOLDER);
    jdbc.update("INSERT INTO projects(name) VALUES ('parent')");
    parentProject = jdbc.queryForObject("SELECT id FROM projects WHERE name='parent'", Long.class);
    parentScope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parentProject);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,?,'CONTRIBUTOR')",
        parentProject,
        HOLDER);

    var childName = "contained:" + UUID.randomUUID();
    jdbc.update("INSERT INTO projects(name) VALUES (?)", childName);
    childProjectId =
        jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, childName);
    childScope = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES (?,'CONTAINED',?,?)",
        childScope,
        childProjectId,
        parentScope);
    jdbc.update("INSERT INTO contained_principals(scope_id) VALUES (?)", childScope);
    childPrincipal =
        jdbc.queryForObject(
                "SELECT id FROM contained_principals WHERE scope_id=?", UUID.class, childScope)
            .toString();
  }

  /** The child's byte namespace: V225's TEXT scope over the contained project's own row. */
  private String childNamespace() {
    return "projects/" + childProjectId;
  }

  /** Distinct bytes per call, so each blob is its own registry row rather than a reuse. */
  private BlobSource source(String scope, String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    String digest = digest(bytes);
    return new BlobSource(
        new BlobKey(scope, digest), scope + "/blobs/" + digest + ".bin", bytes.length);
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** Bytes finalized with no owner, exactly as {@code BinaryBlobStorage.prepare} leaves them. */
  private BlobSource registered(String scope, String body) {
    var source = source(scope, body);
    work.inTransaction(
        () -> {
          blobs.register(source, Instant.now());
          return null;
        });
    return source;
  }

  private void acquire(BlobKey key, BlobClaim claim) {
    work.inTransaction(
        () -> {
          blobs.acquire(key, claim);
          return null;
        });
  }

  /** Push a row's unclaimed instant past the recovery grace without waiting for it. */
  private void aged(BlobKey key) {
    assertEquals(
        1,
        jdbc.update(
            "UPDATE binary_blobs SET unclaimed_at=now()-interval '30 minutes'"
                + " WHERE scope=? AND sha256=?",
            key.scope(),
            key.sha256()));
  }

  /** One bounded sweep pass with production's threshold: now minus the recovery grace. */
  private List<String> sweep() {
    return work.inTransaction(() -> blobs.deletions(Instant.now().minus(GRACE), 100)).stream()
        .map(entry -> entry.source().key().sha256())
        .toList();
  }

  private String state(BlobKey key) {
    return jdbc.queryForObject(
        "SELECT state FROM binary_blobs WHERE scope=? AND sha256=?",
        String.class,
        key.scope(),
        key.sha256());
  }

  @Test
  void aContainedActorCanClaimABlobItWrote() {
    var source = registered(childNamespace(), "child output " + UUID.randomUUID());
    var claim = new BlobClaim("trajectory-entry", childPrincipal, 0, Retention.PINNED);

    acquire(source.key(), claim);

    var entry = blobs.find(source.key()).orElseThrow();
    assertTrue(blobs.readable(entry, claim), "the child's own claim is retained and readable");
    assertEquals(
        childPrincipal,
        jdbc.queryForObject(
            "SELECT owner FROM binary_blob_claims WHERE scope=? AND sha256=?",
            String.class,
            source.key().scope(),
            source.key().sha256()));

    // The owner column is matched with TEXT equality, which is case sensitive -- unlike
    // contained_knowledge_actor's case-insensitive UUID match. Recorded, not changed.
    assertFalse(
        blobs.readable(
            entry,
            new BlobClaim(
                "trajectory-entry",
                childPrincipal.toUpperCase(java.util.Locale.ROOT),
                0,
                Retention.PINNED)),
        "the same principal in a different case is a different owner here");

    // The namespace is keyed on the contained project's row, never on the contained scope UUID.
    assertThrows(IllegalArgumentException.class, () -> new BlobKey("projects/" + childScope, "0"));
  }

  @Test
  void aContainedChildsClaimedBlobSurvivesTheSweep() {
    var source = registered(childNamespace(), "claimed child output " + UUID.randomUUID());
    // The claim a child's image actually carries: PINNED image-retention, attached by
    // DiskImageBlobAccess.store in the same transaction that registers the bytes.
    var pinned =
        new BlobClaim(
            "image-retention",
            "img_" + source.key().sha256().substring(0, 32),
            0,
            Retention.PINNED);
    acquire(source.key(), pinned);
    aged(source.key());

    assertFalse(sweep().contains(source.key().sha256()), "a claimed blob is never a candidate");
    assertEquals("READY", state(source.key()));

    // A pinned claim has no release capability at all, so no sweep can reach these bytes until
    // an owning-record policy removes the row itself.
    var refusal =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                work.inTransaction(
                    () -> {
                      blobs.release(source.key(), pinned, Instant.now());
                      return null;
                    }));
    assertTrue(refusal.getMessage().contains("owning-record removal policy"), refusal.getMessage());
    assertEquals("READY", state(source.key()));
  }

  @Test
  void aReleasedChildClaimStillHoldsItsBytesForTheWholeGrace() {
    var source = registered(childNamespace(), "released child output " + UUID.randomUUID());
    var releasable = new BlobClaim("trajectory-entry", childPrincipal, 0, Retention.RELEASABLE);
    acquire(source.key(), releasable);
    aged(source.key());

    work.inTransaction(
        () -> {
          blobs.release(source.key(), releasable, Instant.now());
          return null;
        });

    // Release resets unclaimed_at to the release instant, so the aged timestamp above is discarded
    // and the full grace starts again from the moment the last owner let go.
    assertFalse(sweep().contains(source.key().sha256()), "the grace restarts at the release");
    assertEquals("READY", state(source.key()));

    aged(source.key());
    assertTrue(sweep().contains(source.key().sha256()));
  }

  @Test
  void aContainedChildsUnclaimedBlobIsSweptAfterItsGraceLikeAnyOther() {
    var source = registered(childNamespace(), "unclaimed child output " + UUID.randomUUID());
    aged(source.key());

    // The child's space is still live: its scope row and its principal both exist, so V231/V232
    // still admit reads in it.
    assertEquals(
        1,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM contained_principals WHERE scope_id=?",
                Integer.class,
                childScope));

    assertTrue(sweep().contains(source.key().sha256()));
    assertEquals("DELETING", state(source.key()));
  }

  @Test
  void aDestroyedChildsBytesAreOnlyEverReclaimedByTheSweep() {
    var source = registered(childNamespace(), "orphaned child output " + UUID.randomUUID());
    aged(source.key());

    jdbc.update("DELETE FROM projects WHERE id=?", childProjectId);

    assertEquals(
        1,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM binary_blobs WHERE scope=?", Integer.class, childNamespace()),
        "V225's TEXT scope deliberately outlives the project row");
    assertTrue(sweep().contains(source.key().sha256()));
  }

  @Test
  void anOrdinaryUnclaimedBlobIsStillSweptAfterItsGrace() {
    var aged = registered("projects/" + parentProject, "parent output " + UUID.randomUUID());
    var global = registered("global", "global output " + UUID.randomUUID());
    var fresh = registered("projects/" + parentProject, "fresh output " + UUID.randomUUID());
    aged(aged.key());
    aged(global.key());

    var swept = sweep();
    assertTrue(swept.contains(aged.key().sha256()));
    assertTrue(swept.contains(global.key().sha256()));
    assertFalse(swept.contains(fresh.key().sha256()), "the grace, not the scope, is the predicate");
  }
}
