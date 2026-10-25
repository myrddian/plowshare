package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Decision 0038 against real PostgreSQL: a read-tier account may subscribe to and consume its own
 * consumer group, and every other authority stays where it was.
 *
 * <p>Real PostgreSQL rather than mocks, deliberately, because every assertion here is about
 * something only the database decides: the V187 table and column, the membership-change trigger,
 * the {@code relay_topics.generation} comparison, the conditional INSERTs that fence group and
 * lease ownership, and the roles {@code JdbcProjectMembers} reads. Roles in particular are real
 * rows, not a stubbed {@code mayUse}: a fixture whose membership cannot disagree with itself would
 * pass an implementation that had stopped consulting membership at all.
 *
 * <h2>The pins that matter most</h2>
 *
 * <p>{@code relay_subscriptions.subscriber} is a <b>consumer group</b> and {@code seen_through} is
 * that group's committed offset. Two unrelated consumers sharing one group name do not each receive
 * the stream — they divide it, and either may commit an offset past events the other never read. So
 * the question these pins ask is not "did a permission check run?" but "does this account have its
 * own group?": {@link #a_read_tier_group_is_the_readers_own_and_never_a_share_of_the_pipelines()}
 * and {@link #one_readers_group_cannot_be_joined_or_committed_by_another_reader()}.
 *
 * <p>An earlier version of this test asked the permission question instead, and only in the state
 * where another account held a <i>live</i> lease. It therefore passed against an implementation
 * that let a read-tier account commit the project pipeline's offset whenever that pipeline held no
 * lease or a lapsed one — the two states an idle pipeline is in essentially always. All three lease
 * states are now asserted, at the service boundary and at each fence separately, because a pin that
 * cannot reach a fence cannot tell whether the fence is there.
 *
 * <p>Each refusal is checked together with the state it must not have changed. A refusal that threw
 * after writing is not a refusal, and an assertion on the exception alone cannot see the
 * difference.
 */
@Tag("full-db")
@Testcontainers
class RelayReadTierSubscriberDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final String PROJECT = "atlas";
  private static final String MANAGER = "manager";
  private static final String CONTRIBUTOR = "contributor";
  private static final String VIEWER = "viewer";
  private static final String STRANGER = "stranger";

  /**
   * The project-declared consumer group the work-tier pipeline owns. Its offset is the pipeline's.
   */
  private static final String PIPELINE = "pipeline";

  /** A logical group name a reader asks for. The stored identity is derived from the account. */
  private static final String GROUP = "notices";

  private static final Duration WINDOW = Duration.ofHours(12);
  private static final Duration LEASE = Duration.ofMinutes(2);
  private static final Instant T0 = Instant.parse("2026-10-24T09:00:00Z");

  private static DriverManagerDataSource source;
  private static JdbcTemplate jdbc;
  private static UnitOfWork work;

  private long projectId;
  private JdbcRelayRepository broker;
  private Relay relay;
  private RelayConsumerRepository consumers;
  private RelaySubscriptionAuthority authority;
  private RelayConsumption consumption;
  private Instant now = T0;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    var template = new TransactionTemplate(new DataSourceTransactionManager(source));
    work =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(java.util.function.Supplier<T> action) {
            return Objects.requireNonNull(template.execute(status -> action.get()));
          }
        };
  }

  @BeforeEach
  void fresh() {
    AuthorityFixtures.truncate(
        jdbc, "TRUNCATE relay_topics,relay_payloads,projects,admins CASCADE");
    jdbc.update(
        """
        INSERT INTO admins(handle,password_hash,server_admin)
        VALUES('manager','fixture',false),('contributor','fixture',false),
              ('viewer','fixture',false),('stranger','fixture',false)
        """);
    projectId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES(?,'fixture') RETURNING id",
                Long.class,
                PROJECT));
    // STRANGER is deliberately absent: "no role" must be a missing row, not a role named none.
    for (var member :
        Map.of(MANAGER, "MANAGER", CONTRIBUTOR, "CONTRIBUTOR", VIEWER, "VIEWER").entrySet())
      jdbc.update(
          "INSERT INTO project_members(project_id,handle,role) VALUES(?,?,?)",
          projectId,
          member.getKey(),
          member.getValue());

    now = T0;
    broker =
        new JdbcRelayRepository(
            jdbc,
            work,
            new JdbcRelayPayloadStore(
                jdbc,
                work,
                java.time.Duration.ofHours(1),
                java.time.Duration.ofHours(24),
                java.time.Duration.ofDays(30)));
    relay = new DurableRelay(broker, () -> now);
    consumers =
        new JdbcRelayConsumerRepository(
            jdbc, work, new JdbcRelayDeliveryRepository(jdbc, work), broker);
    authority = new JdbcRelaySubscriptionAuthority(jdbc);
    consumption = consumption(WINDOW);
  }

  private RelayConsumption consumption(Duration window) {
    var projects = mock(ProjectWorkspaces.class);
    when(projects.id(PROJECT)).thenReturn(projectId);
    when(projects.personalOwner(PROJECT)).thenReturn(Optional.empty());
    // Membership is the real thing under test, so it is the real repository over real rows.
    return new ReadTierRelayConsumption(
        new JdbcProjectMembers(jdbc), projects, relay, consumers, authority, window, () -> now);
  }

  private Relay.TopicKey topic() {
    return new Relay.TopicKey(projectId, "release.observed");
  }

  /** A work-tier (project-declared) group identity: the bare name, exactly as declared. */
  private Relay.SubscriptionKey key(String group) {
    return new Relay.SubscriptionKey(topic(), group);
  }

  /** The stored identity of one account's read-tier group, as the server derives it. */
  private Relay.SubscriptionKey readerKey(String account, String group) {
    return Relay.SubscriptionKey.reader(topic(), account, group);
  }

  private String stored(String account, String group) {
    return readerKey(account, group).subscriber();
  }

  private RelayConsumption.Request request(String group) {
    return new RelayConsumption.Request(PROJECT, "release.observed", group);
  }

  /** A manager registers and publishes; nothing a read-tier account does could have done this. */
  private void published(String text) {
    relay.registerTopic(topic(), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    relay.publish(
        topic(),
        new Relay.Draft(
            UUID.randomUUID().toString(), MANAGER, now, null, null, new RelayPayload.Text(text)));
  }

  /** The work tier's own group: created through {@link Relay#subscribe}, so it has no reader. */
  private void pipelineGroup() {
    relay.subscribe(key(PIPELINE), Relay.Start.OLDEST_RETAINED);
    assertNull(reader(PIPELINE), "a project-declared group belongs to the work tier");
  }

  private String reader(String stored) {
    return jdbc.queryForObject(
        "SELECT reader_account FROM relay_subscriptions WHERE scope_key=? AND topic=? AND subscriber=?",
        String.class,
        "project:" + projectId,
        "release.observed",
        stored);
  }

  private Long committed(String stored) {
    return jdbc.queryForObject(
        "SELECT seen_through FROM relay_subscriptions WHERE scope_key=? AND topic=? AND subscriber=?",
        Long.class,
        "project:" + projectId,
        "release.observed",
        stored);
  }

  private int authorityRows() {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_subscription_authority WHERE scope_key=?",
            Integer.class,
            "project:" + projectId));
  }

  private int leaseRows(String stored) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_consumer_leases WHERE scope_key=? AND topic=? AND subscriber=?",
            Integer.class,
            "project:" + projectId,
            "release.observed",
            stored));
  }

  /** Expires a lease in place: the lapsed state without waiting for one to lapse. */
  private void lapse(String stored) {
    assertEquals(
        1,
        jdbc.update(
            """
            UPDATE relay_consumer_leases SET lease_until=clock_timestamp() - interval '1 minute'
            WHERE scope_key=? AND topic=? AND subscriber=?
            """,
            "project:" + projectId,
            "release.observed",
            stored));
  }

  // ------------------------------------------------------------------------------------- pin 1

  @Test
  void a_viewer_subscribes_consumes_and_commits_its_own_groups_offset() {
    published("released");
    var subscription = consumption.subscribe(VIEWER, request(GROUP), Relay.Start.OLDEST_RETAINED);
    assertEquals(0, subscription.seenThrough());
    assertEquals(1, authorityRows());
    assertEquals(
        VIEWER,
        reader(stored(VIEWER, GROUP)),
        "the group established by a reader is that reader's own");

    var read = consumption.consume(VIEWER, request(GROUP), 10);
    assertEquals(1, read.publications().size());
    assertEquals(
        new RelayPayload.Text("released"), read.publications().getFirst().event().payload());
    // Reading does not commit: the offset is still the baseline until the viewer advances it.
    assertEquals(0L, committed(stored(VIEWER, GROUP)));

    var lease = consumption.lease(VIEWER, request(GROUP), LEASE);
    assertEquals(VIEWER, lease.account());
    assertEquals(1, consumption.advance(VIEWER, request(GROUP), lease, 1).seenThrough());
    assertEquals(1L, committed(stored(VIEWER, GROUP)));
    assertTrue(consumption.consume(VIEWER, request(GROUP), 10).publications().isEmpty());

    // Extending its own lease is not contention, and the commit still works on the new epoch.
    var extended = consumption.lease(VIEWER, request(GROUP), LEASE);
    assertEquals(lease.epoch() + 1, extended.epoch());
    assertEquals(1, consumption.advance(VIEWER, request(GROUP), extended, 1).seenThrough());
    consumption.release(VIEWER, request(GROUP), extended);
  }

  // ------------------------------------------------------------------------------------- pin 2

  @Test
  void an_account_with_no_role_in_the_project_can_do_none_of_those() {
    published("released");
    // The group and a valid authority record exist -- established by the viewer. The stranger's
    // refusal must come from membership, not from there being nothing to consume.
    consumption.subscribe(VIEWER, request(GROUP), Relay.Start.OLDEST_RETAINED);
    var viewers = consumption.lease(VIEWER, request(GROUP), LEASE);

    assertThrows(
        CallerFault.class,
        () -> consumption.subscribe(STRANGER, request(GROUP), Relay.Start.OLDEST_RETAINED));
    assertThrows(CallerFault.class, () -> consumption.consume(STRANGER, request(GROUP), 10));
    assertThrows(
        CallerFault.class, () -> consumption.advance(STRANGER, request(GROUP), viewers, 1));
    assertThrows(CallerFault.class, () -> consumption.lease(STRANGER, request(GROUP), LEASE));

    assertEquals(1, authorityRows());
    assertEquals(VIEWER, reader(stored(VIEWER, GROUP)));
    assertEquals(0L, committed(stored(VIEWER, GROUP)));
    assertEquals(
        0,
        (int)
            Objects.requireNonNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM relay_consumer_leases WHERE account=?",
                    Integer.class,
                    STRANGER)));
  }

  // -------- pin 3: a read-tier reader gets its own consumer group, never a share of another's

  @Test
  void a_read_tier_group_is_the_readers_own_and_never_a_share_of_the_pipelines() {
    published("released");
    pipelineGroup();
    // The viewer has its own valid group, so nothing below is refused for lack of standing.
    consumption.subscribe(VIEWER, request(GROUP), Relay.Start.OLDEST_RETAINED);
    var mine = consumption.lease(VIEWER, request(GROUP), LEASE);
    // A lease value the viewer could construct for a group that is not its own. The record is
    // public, so the fence cannot be the token's shape; it has to be the row in the database.
    var forged =
        new RelayConsumerRepository.Lease(key(PIPELINE), VIEWER, VIEWER, 1, T0.plusSeconds(600));

    // State 1: no lease row at all. This is the state an idle pipeline is in almost always, and
    // the one an "is anyone holding it?" check wrongly reads as free to commit against.
    assertEquals(0, leaseRows(PIPELINE));
    refuseEveryRouteToThePipelinesGroup(mine, forged);

    // State 2: a lapsed lease. Reclaimable by its owner, and still not the viewer's to commit.
    assertTrue(
        consumers
            .acquire(key(PIPELINE), "request:" + UUID.randomUUID(), CONTRIBUTOR, LEASE)
            .isPresent());
    lapse(PIPELINE);
    refuseEveryRouteToThePipelinesGroup(mine, forged);

    // State 3: a live lease held by another account.
    assertTrue(
        consumers
            .acquire(key(PIPELINE), "request:" + UUID.randomUUID(), CONTRIBUTOR, LEASE)
            .isPresent());
    refuseEveryRouteToThePipelinesGroup(mine, forged);

    // The capability itself survives all of that: the viewer still owns and commits its own.
    assertEquals(1, consumption.advance(VIEWER, request(GROUP), mine, 1).seenThrough());
    assertEquals(1L, committed(stored(VIEWER, GROUP)));
  }

  /** Every route a read-tier account has to the pipeline's offset, and the state it must leave. */
  private void refuseEveryRouteToThePipelinesGroup(
      RelayConsumerRepository.Lease own, RelayConsumerRepository.Lease forged) {
    long before = committed(PIPELINE);
    String owner = reader(PIPELINE);
    int leases = leaseRows(PIPELINE);

    // Asking for the declared name does not join the declared group: it gets its own, under its
    // own stored identity. There is no shared offset, so there is nothing to adjudicate.
    consumption.subscribe(VIEWER, request(PIPELINE), Relay.Start.LATEST);
    assertEquals("reader:viewer:pipeline", stored(VIEWER, PIPELINE));
    assertEquals(VIEWER, reader(stored(VIEWER, PIPELINE)));
    // Leasing through the service leases the viewer's OWN group, never the declared one: the
    // request cannot address the declared group, and the repository refuses it in any lease state.
    assertEquals(
        stored(VIEWER, PIPELINE),
        consumption.lease(VIEWER, request(PIPELINE), LEASE).subscription().subscriber());
    assertTrue(consumers.acquireAsReader(key(PIPELINE), VIEWER, LEASE).isEmpty());
    // It cannot commit its offset: not with a lease it holds elsewhere, not with a forged token.
    assertThrows(CallerFault.class, () -> consumption.advance(VIEWER, request(PIPELINE), own, 1));
    assertThrows(
        CallerFault.class, () -> consumption.advance(VIEWER, request(PIPELINE), forged, 1));
    // And not at the repository fence either, which is what the service relies on.
    assertThrows(IllegalStateException.class, () -> consumers.advanceSeen(forged, 1));

    assertEquals(before, committed(PIPELINE), "the pipeline's committed offset must not move");
    assertEquals(owner, reader(PIPELINE), "the pipeline's group must not change hands");
    assertEquals(leases, leaseRows(PIPELINE), "no lease row may be created or taken over");
  }

  @Test
  void a_lapsed_lease_is_reclaimable_only_within_the_accounts_own_group() {
    published("released");
    pipelineGroup();
    consumption.subscribe(VIEWER, request(GROUP), Relay.Start.OLDEST_RETAINED);

    // The viewer's own lapsed lease: reclaiming it is recovery of its own crashed consumer.
    var first = consumption.lease(VIEWER, request(GROUP), LEASE);
    lapse(stored(VIEWER, GROUP));
    var reclaimed = consumption.lease(VIEWER, request(GROUP), LEASE);
    assertEquals(first.epoch() + 1, reclaimed.epoch());
    assertEquals(VIEWER, reclaimed.account());

    // A lapsed lease on a group that is not the account's is not reclaimable at all, so
    // reclamation can never become a route into another group's committed offset.
    assertTrue(
        consumers
            .acquire(key(PIPELINE), "request:" + UUID.randomUUID(), CONTRIBUTOR, LEASE)
            .isPresent());
    lapse(PIPELINE);
    assertTrue(consumers.acquireAsReader(key(PIPELINE), VIEWER, LEASE).isEmpty());
    assertEquals(
        CONTRIBUTOR,
        jdbc.queryForObject(
            "SELECT account FROM relay_consumer_leases WHERE scope_key=? AND topic=? AND subscriber=?",
            String.class,
            "project:" + projectId,
            "release.observed",
            PIPELINE));
  }

  @Test
  void the_same_logical_name_from_two_accounts_is_two_groups_with_independent_offsets() {
    published("released");
    consumption.subscribe(CONTRIBUTOR, request(GROUP), Relay.Start.OLDEST_RETAINED);
    consumption.subscribe(VIEWER, request(GROUP), Relay.Start.OLDEST_RETAINED);

    // Asserted on the STORED names: a prefix that is merely intended is a prefix not applied.
    assertEquals("reader:contributor:notices", stored(CONTRIBUTOR, GROUP));
    assertEquals("reader:viewer:notices", stored(VIEWER, GROUP));
    assertEquals(CONTRIBUTOR, reader(stored(CONTRIBUTOR, GROUP)));
    assertEquals(VIEWER, reader(stored(VIEWER, GROUP)));

    // Two groups, so there is no shared offset to corrupt: each commits only its own.
    var theirs = consumption.lease(CONTRIBUTOR, request(GROUP), LEASE);
    assertEquals(1, consumption.advance(CONTRIBUTOR, request(GROUP), theirs, 1).seenThrough());
    assertEquals(1L, committed(stored(CONTRIBUTOR, GROUP)));
    assertEquals(0L, committed(stored(VIEWER, GROUP)));
    // And the viewer still has the publication to read, because nobody consumed it on its behalf.
    assertEquals(1, consumption.consume(VIEWER, request(GROUP), 10).publications().size());
  }

  @Test
  void a_reader_cannot_address_another_readers_stored_group_even_at_the_repository() {
    published("released");
    consumption.subscribe(CONTRIBUTOR, request(GROUP), Relay.Start.OLDEST_RETAINED);
    var held = consumption.lease(CONTRIBUTOR, request(GROUP), LEASE);
    var theirs = readerKey(CONTRIBUTOR, GROUP);

    // The viewer may read the topic and holds a role. It still cannot reach this group: the
    // service gives it no way to name one, and the fences behind the service refuse it anyway.
    assertTrue(new JdbcProjectMembers(jdbc).mayUse(PROJECT, VIEWER));
    // Refused at the namespace, before the conflict clause behind it is even consulted.
    assertThrows(
        IllegalArgumentException.class,
        () -> relay.subscribeAsReader(theirs, Relay.Start.LATEST, VIEWER));
    assertTrue(consumers.acquireAsReader(theirs, VIEWER, LEASE).isEmpty());
    assertThrows(
        IllegalStateException.class,
        () ->
            consumers.advanceSeen(
                new RelayConsumerRepository.Lease(theirs, VIEWER, VIEWER, 1, T0.plusSeconds(600)),
                1));
    // A release the viewer did not take cannot end the contributor's lease either.
    assertThrows(CallerFault.class, () -> consumption.release(VIEWER, request(GROUP), held));

    assertEquals(CONTRIBUTOR, reader(stored(CONTRIBUTOR, GROUP)));
    assertEquals(0L, committed(stored(CONTRIBUTOR, GROUP)));
    var row =
        jdbc.queryForMap(
            "SELECT worker,account,epoch FROM relay_consumer_leases WHERE scope_key=? AND topic=? AND subscriber=?",
            "project:" + projectId,
            "release.observed",
            theirs.subscriber());
    assertEquals(CONTRIBUTOR, row.get("account"));
    assertEquals(held.epoch(), ((Number) row.get("epoch")).longValue());

    // Its owner is unaffected throughout.
    assertEquals(1, consumption.advance(CONTRIBUTOR, request(GROUP), held, 1).seenThrough());
  }

  @Test
  void a_reader_asking_for_a_project_declared_name_gets_its_own_group_not_the_pipelines() {
    published("released");
    pipelineGroup();
    var lease = consumers.acquire(key(PIPELINE), "request:one", CONTRIBUTOR, LEASE);
    assertEquals(1, consumers.advanceSeen(lease.orElseThrow(), 1).seenThrough());

    // The viewer asks for exactly the project-declared logical name.
    consumption.subscribe(VIEWER, request(PIPELINE), Relay.Start.OLDEST_RETAINED);

    // It gets its own group. The stored name is not the declared one, and the declared group's
    // committed offset is untouched, so the pipeline skips nothing.
    assertEquals("reader:viewer:pipeline", stored(VIEWER, PIPELINE));
    assertEquals(VIEWER, reader(stored(VIEWER, PIPELINE)));
    assertEquals(0L, committed(stored(VIEWER, PIPELINE)));
    assertEquals(1L, committed(PIPELINE));
    assertNull(reader(PIPELINE));
    // Two rows, not one adopted row.
    assertEquals(
        2,
        (int)
            Objects.requireNonNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM relay_subscriptions WHERE scope_key=? AND topic=?",
                    Integer.class,
                    "project:" + projectId,
                    "release.observed")));

    // And the viewer's own group still has the publication to read.
    assertEquals(1, consumption.consume(VIEWER, request(PIPELINE), 10).publications().size());
  }

  @Test
  void work_tier_registration_is_unaffected_by_a_prior_read_tier_group_of_the_same_logical_name() {
    published("released");
    // Reader first, then the project declares the name. The declaration must create its own row
    // rather than adopt the reader's: ON CONFLICT DO NOTHING followed by a read of the existing
    // row is exactly how a work-tier writer silently joins a reader's consumer group.
    consumption.subscribe(VIEWER, request(PIPELINE), Relay.Start.OLDEST_RETAINED);
    var readers = consumption.lease(VIEWER, request(PIPELINE), LEASE);
    assertEquals(1, consumption.advance(VIEWER, request(PIPELINE), readers, 1).seenThrough());

    var declared = relay.subscribe(key(PIPELINE), Relay.Start.OLDEST_RETAINED);
    assertEquals(0, declared.seenThrough(), "a fresh work-tier group starts at its own baseline");
    assertNull(reader(PIPELINE));
    assertEquals(0L, committed(PIPELINE));
    assertEquals(1L, committed(stored(VIEWER, PIPELINE)), "the reader's offset is its own");
  }

  @Test
  void the_two_namespaces_are_disjoint_in_the_schema_and_in_the_broker() {
    published("released");
    // The broker refuses to register a read-tier name through the work-tier path, so the adopting
    // conflict path cannot be reached by naming a reader's group.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            relay.subscribe(
                new Relay.SubscriptionKey(topic(), stored(VIEWER, GROUP)), Relay.Start.LATEST));

    // And the schema holds the same line independently of any Java caller.
    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.update(
                """
                INSERT INTO relay_subscriptions(scope_key,topic,subscriber,seen_through,seen_at)
                VALUES(?,?,?,0,clock_timestamp())
                """,
                "project:" + projectId,
                "release.observed",
                stored(VIEWER, GROUP)),
        "a reader name with no owner column must be rejected");
    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.update(
                """
                INSERT INTO relay_subscriptions(scope_key,topic,subscriber,seen_through,seen_at,reader_account)
                VALUES(?,?,?,0,clock_timestamp(),?)
                """,
                "project:" + projectId,
                "release.observed",
                stored(VIEWER, GROUP),
                CONTRIBUTOR),
        "an owner column disagreeing with the name must be rejected");
    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.update(
                """
                INSERT INTO relay_subscriptions(scope_key,topic,subscriber,seen_through,seen_at,reader_account)
                VALUES(?,?,?,0,clock_timestamp(),?)
                """,
                "project:" + projectId,
                "release.observed",
                PIPELINE,
                VIEWER),
        "a work-tier name with an owner column must be rejected");
  }

  @Test
  void the_work_tier_pipeline_keeps_its_own_group_and_lease_untouched() {
    published("released");
    pipelineGroup();
    // The work tier's own leased commit still works exactly as before: the read-tier fences are
    // additional routes into the table, not a replacement for the pipeline's.
    var lease = consumers.acquire(key(PIPELINE), "request:one", CONTRIBUTOR, LEASE);
    assertTrue(lease.isPresent());
    assertEquals(1, consumers.advanceSeen(lease.get(), 1).seenThrough());
    assertEquals(1L, committed(PIPELINE));
    assertNull(reader(PIPELINE));
  }

  // ------------------------------------------------------------------------------------- pin 5

  @Test
  void a_subscription_whose_generation_no_longer_matches_the_topics_is_not_valid() {
    published("released");
    consumption.subscribe(VIEWER, request(GROUP), Relay.Start.OLDEST_RETAINED);
    var lease = consumption.lease(VIEWER, request(GROUP), LEASE);
    assertEquals(
        RelaySubscriptionAuthority.Validity.VALID,
        authority.validate(readerKey(VIEWER, GROUP), VIEWER, WINDOW, now));

    authority.invalidateTopic(topic());

    assertEquals(
        RelaySubscriptionAuthority.Validity.GENERATION_CHANGED,
        authority.validate(readerKey(VIEWER, GROUP), VIEWER, WINDOW, now));
    assertThrows(CallerFault.class, () -> consumption.consume(VIEWER, request(GROUP), 10));
    assertThrows(CallerFault.class, () -> consumption.advance(VIEWER, request(GROUP), lease, 1));
    assertThrows(CallerFault.class, () -> consumption.lease(VIEWER, request(GROUP), LEASE));
    assertEquals(0L, committed(stored(VIEWER, GROUP)));

    // Re-establishing binds to the new incarnation; the committed offset is untouched.
    consumption.subscribe(VIEWER, request(GROUP), Relay.Start.OLDEST_RETAINED);
    assertEquals(
        RelaySubscriptionAuthority.Validity.VALID,
        authority.validate(readerKey(VIEWER, GROUP), VIEWER, WINDOW, now));
    assertEquals(0L, committed(stored(VIEWER, GROUP)));
  }

  @Test
  void a_membership_change_withdraws_authority_without_waiting_for_the_window() {
    published("released");
    consumption.subscribe(VIEWER, request(GROUP), Relay.Start.OLDEST_RETAINED);
    assertEquals(1, authorityRows());

    // Well inside the window: if this took effect only by expiry the record would still be valid.
    jdbc.update("DELETE FROM project_members WHERE project_id=? AND handle=?", projectId, VIEWER);

    assertEquals(0, authorityRows());
    assertEquals(
        RelaySubscriptionAuthority.Validity.NOT_ESTABLISHED,
        authority.validate(readerKey(VIEWER, GROUP), VIEWER, WINDOW, now));
    assertThrows(CallerFault.class, () -> consumption.consume(VIEWER, request(GROUP), 10));
  }

  @Test
  void a_scope_wide_withdrawal_removes_only_that_accounts_records() {
    published("released");
    consumption.subscribe(VIEWER, request(GROUP), Relay.Start.OLDEST_RETAINED);
    consumption.subscribe(CONTRIBUTOR, request("others"), Relay.Start.OLDEST_RETAINED);
    assertEquals(2, authorityRows());

    assertEquals(1, authority.invalidateAccount(new Relay.ProjectScope(projectId), VIEWER));

    assertEquals(
        RelaySubscriptionAuthority.Validity.NOT_ESTABLISHED,
        authority.validate(readerKey(VIEWER, GROUP), VIEWER, WINDOW, now));
    assertEquals(
        RelaySubscriptionAuthority.Validity.VALID,
        authority.validate(readerKey(CONTRIBUTOR, "others"), CONTRIBUTOR, WINDOW, now));
  }

  // ------------------------------------------------------------------------------------- pin 6

  @Test
  void a_subscription_past_the_configured_maximum_age_is_not_valid_and_the_age_is_configured() {
    published("released");
    consumption.subscribe(VIEWER, request(GROUP), Relay.Start.OLDEST_RETAINED);
    var lease = consumption.lease(VIEWER, request(GROUP), LEASE);

    now = T0.plus(WINDOW).minusSeconds(1);
    assertEquals(
        RelaySubscriptionAuthority.Validity.VALID,
        authority.validate(readerKey(VIEWER, GROUP), VIEWER, WINDOW, now));
    assertEquals(1, consumption.consume(VIEWER, request(GROUP), 10).publications().size());

    now = T0.plus(WINDOW);
    assertEquals(
        RelaySubscriptionAuthority.Validity.EXPIRED,
        authority.validate(readerKey(VIEWER, GROUP), VIEWER, WINDOW, now));
    assertThrows(CallerFault.class, () -> consumption.consume(VIEWER, request(GROUP), 10));
    assertThrows(CallerFault.class, () -> consumption.advance(VIEWER, request(GROUP), lease, 1));
    assertEquals(0L, committed(stored(VIEWER, GROUP)));

    // The bound is the configured one, not a constant: the same record, same clock, and only the
    // configured window different, is valid under a longer window and expired under a shorter one.
    assertEquals(
        1,
        consumption(Duration.ofDays(2)).consume(VIEWER, request(GROUP), 10).publications().size());
    now = T0.plusSeconds(600);
    assertThrows(
        CallerFault.class,
        () -> consumption(Duration.ofMinutes(5)).consume(VIEWER, request(GROUP), 10));
    assertEquals(
        1,
        consumption(Duration.ofMinutes(30))
            .consume(VIEWER, request(GROUP), 10)
            .publications()
            .size());
  }

  @Test
  void establishing_authority_requires_an_existing_topic_and_subscription() {
    // No topic: nothing to capture a generation from, and no authority record may appear.
    assertThrows(
        IllegalStateException.class,
        () -> authority.establish(readerKey(VIEWER, GROUP), VIEWER, now));
    assertEquals(0, authorityRows());

    // A topic but no subscription row: the foreign key refuses an orphan record.
    relay.registerTopic(topic(), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    assertThrows(
        RuntimeException.class, () -> authority.establish(readerKey(VIEWER, GROUP), VIEWER, now));
    assertEquals(0, authorityRows());
  }

  @Test
  void a_read_tier_subscriber_cannot_bring_a_topic_into_being() {
    assertThrows(
        CallerFault.class,
        () -> consumption.subscribe(VIEWER, request(GROUP), Relay.Start.OLDEST_RETAINED));
    assertEquals(
        0,
        (int)
            Objects.requireNonNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM relay_topics WHERE scope_key=?",
                    Integer.class,
                    "project:" + projectId)));
    assertEquals(0, authorityRows());
  }

  @Test
  void the_managed_topic_policy_is_untouched_by_a_read_tier_subscriber() {
    relay.configureTopic(
        topic(), RelayPayload.Kind.TEXT, new Relay.Policy(Duration.ofDays(9), 500L));
    relay.publish(
        topic(),
        new Relay.Draft(
            UUID.randomUUID().toString(),
            MANAGER,
            now,
            null,
            null,
            new RelayPayload.Text("released")));
    consumption.subscribe(VIEWER, request(GROUP), Relay.Start.OLDEST_RETAINED);
    consumption.consume(VIEWER, request(GROUP), 10);
    consumption.advance(
        VIEWER, request(GROUP), consumption.lease(VIEWER, request(GROUP), LEASE), 1);

    var stored =
        jdbc.queryForMap(
            "SELECT retention_seconds,max_records FROM relay_topics WHERE scope_key=? AND name=?",
            "project:" + projectId,
            "release.observed");
    assertEquals(
        Duration.ofDays(9).getSeconds(), ((Number) stored.get("retention_seconds")).longValue());
    assertEquals(500L, ((Number) stored.get("max_records")).longValue());
  }

  @Test
  void the_work_and_manage_tiers_still_read_real_roles() {
    var members = new JdbcProjectMembers(jdbc);
    // The tiers 0038 must not move, over the same rows the consumption tests use.
    assertTrue(members.mayUse(PROJECT, VIEWER));
    assertFalse(members.mayWork(PROJECT, VIEWER));
    assertFalse(members.mayManage(PROJECT, VIEWER));
    assertTrue(members.mayWork(PROJECT, CONTRIBUTOR));
    assertFalse(members.mayManage(PROJECT, CONTRIBUTOR));
    assertTrue(members.mayManage(PROJECT, MANAGER));
    for (String tier : List.of(VIEWER, CONTRIBUTOR, MANAGER))
      assertTrue(members.mayUse(PROJECT, tier));
    assertFalse(members.mayUse(PROJECT, STRANGER));
  }
}
