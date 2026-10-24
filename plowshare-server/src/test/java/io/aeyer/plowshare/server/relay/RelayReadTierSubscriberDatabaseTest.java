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
 * Decision 0038 against real PostgreSQL: a read-tier account may subscribe and consume, and every
 * other authority stays where it was.
 *
 * <p>Real PostgreSQL rather than mocks, deliberately, because every assertion here is about
 * something only the database decides: the V187 table and its membership-change trigger, the {@code
 * relay_topics.generation} comparison, the {@code relay_consumer_leases} row that says whose
 * consumer state it is, and the roles that {@code JdbcProjectMembers} reads. Roles in particular
 * are real rows, not a stubbed {@code mayUse}: a fixture whose membership cannot disagree with
 * itself would pass an implementation that had stopped consulting membership at all.
 *
 * <p>Each refusal is checked together with the state it must not have changed. A refusal that threw
 * after writing is not a refusal, and an assertion on the exception alone cannot tell the
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
  private static final Duration WINDOW = Duration.ofHours(12);
  private static final Instant T0 = Instant.parse("2026-10-24T09:00:00Z");

  private static DriverManagerDataSource source;
  private static JdbcTemplate jdbc;
  private static UnitOfWork work;

  private long projectId;
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
    AuthorityFixtures.truncate(jdbc, "TRUNCATE relay_topics,projects,admins CASCADE");
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
    relay = new DurableRelay(new JdbcRelayRepository(jdbc, work), () -> now);
    consumers =
        new JdbcRelayConsumerRepository(jdbc, work, new JdbcRelayDeliveryRepository(jdbc, work));
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

  private Relay.SubscriptionKey key() {
    return new Relay.SubscriptionKey(topic(), "notices");
  }

  private RelayConsumption.Request request() {
    return new RelayConsumption.Request(PROJECT, "release.observed", "notices");
  }

  /** A manager registers and publishes; nothing a read-tier account does could have done this. */
  private void published(String text) {
    relay.registerTopic(topic(), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    relay.publish(
        topic(),
        new Relay.Draft(
            UUID.randomUUID().toString(), MANAGER, now, null, null, new RelayPayload.Text(text)));
  }

  private Long seenThrough(String subscriber) {
    return jdbc.queryForObject(
        "SELECT seen_through FROM relay_subscriptions WHERE scope_key=? AND topic=? AND subscriber=?",
        Long.class,
        "project:" + projectId,
        "release.observed",
        subscriber);
  }

  private int authorityRows() {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_subscription_authority WHERE scope_key=?",
            Integer.class,
            "project:" + projectId));
  }

  // ------------------------------------------------------------------------------------- pin 1

  @Test
  void a_viewer_subscribes_consumes_and_advances_its_own_offset() {
    published("released");
    var subscription = consumption.subscribe(VIEWER, request(), Relay.Start.OLDEST_RETAINED);
    assertEquals(0, subscription.seenThrough());
    assertEquals(1, authorityRows());

    var read = consumption.consume(VIEWER, request(), 10);
    assertEquals(1, read.publications().size());
    assertEquals(
        new RelayPayload.Text("released"), read.publications().getFirst().event().payload());
    // Reading does not advance: the offset is still the baseline until the viewer advances it.
    assertEquals(0L, seenThrough("notices"));

    assertEquals(1, consumption.advance(VIEWER, request(), 1).seenThrough());
    assertEquals(1L, seenThrough("notices"));
    assertTrue(consumption.consume(VIEWER, request(), 10).publications().isEmpty());

    // And the lease a read-tier consumer takes is its own row.
    var lease = consumption.lease(VIEWER, request(), Duration.ofMinutes(2));
    assertEquals(VIEWER, lease.account());
    consumption.release(VIEWER, request(), lease);
  }

  // ------------------------------------------------------------------------------------- pin 2

  @Test
  void an_account_with_no_role_in_the_project_can_do_none_of_those() {
    published("released");
    // The subscription and a valid authority record exist -- established by the viewer. The
    // stranger's refusal must come from membership, not from there being nothing to consume.
    consumption.subscribe(VIEWER, request(), Relay.Start.OLDEST_RETAINED);

    assertThrows(
        CallerFault.class,
        () -> consumption.subscribe(STRANGER, request(), Relay.Start.OLDEST_RETAINED));
    assertThrows(CallerFault.class, () -> consumption.consume(STRANGER, request(), 10));
    assertThrows(CallerFault.class, () -> consumption.advance(STRANGER, request(), 1));
    assertThrows(
        CallerFault.class, () -> consumption.lease(STRANGER, request(), Duration.ofMinutes(2)));

    assertEquals(1, authorityRows());
    assertEquals(0L, seenThrough("notices"));
    assertEquals(
        0,
        (int)
            Objects.requireNonNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM relay_consumer_leases WHERE account=?",
                    Integer.class,
                    STRANGER)));
  }

  // ------------------------------------------------------------------------------------- pin 3

  @Test
  void an_account_cannot_write_another_subscribers_lease_or_offset_even_when_it_may_read() {
    published("released");
    // Both accounts may read the topic, and both have established authority on the same
    // subscription. The only thing separating them is whose consumer state the row is.
    consumption.subscribe(CONTRIBUTOR, request(), Relay.Start.OLDEST_RETAINED);
    consumption.subscribe(VIEWER, request(), Relay.Start.OLDEST_RETAINED);
    var held = consumption.lease(CONTRIBUTOR, request(), Duration.ofMinutes(2));
    assertEquals(CONTRIBUTOR, held.account());

    assertThrows(
        CallerFault.class, () -> consumption.lease(VIEWER, request(), Duration.ofMinutes(2)));
    assertThrows(CallerFault.class, () -> consumption.advance(VIEWER, request(), 1));
    // A release the viewer did not take cannot end the contributor's lease either.
    assertThrows(CallerFault.class, () -> consumption.release(VIEWER, request(), held));

    var row =
        jdbc.queryForMap(
            "SELECT worker,account,epoch FROM relay_consumer_leases WHERE scope_key=? AND topic=? AND subscriber=?",
            "project:" + projectId,
            "release.observed",
            "notices");
    assertEquals(CONTRIBUTOR, row.get("account"));
    assertEquals(CONTRIBUTOR, row.get("worker"));
    assertEquals(held.epoch(), ((Number) row.get("epoch")).longValue());
    assertEquals(0L, seenThrough("notices"));

    // Reading is not a consumer-state write, so the viewer still sees the content it may read.
    assertEquals(1, consumption.consume(VIEWER, request(), 10).publications().size());

    // Once the contributor's lease lapses, the row is free and reassignment is allowed. That is
    // crash recovery, not writing someone else's live row, and the distinction must survive.
    consumption.release(CONTRIBUTOR, request(), held);
    assertEquals(VIEWER, consumption.lease(VIEWER, request(), Duration.ofMinutes(2)).account());
  }

  // ------------------------------------------------------------------------------------- pin 5

  @Test
  void a_subscription_whose_generation_no_longer_matches_the_topics_is_not_valid() {
    published("released");
    consumption.subscribe(VIEWER, request(), Relay.Start.OLDEST_RETAINED);
    assertEquals(
        RelaySubscriptionAuthority.Validity.VALID, authority.validate(key(), VIEWER, WINDOW, now));

    authority.invalidateTopic(topic());

    assertEquals(
        RelaySubscriptionAuthority.Validity.GENERATION_CHANGED,
        authority.validate(key(), VIEWER, WINDOW, now));
    assertThrows(CallerFault.class, () -> consumption.consume(VIEWER, request(), 10));
    assertThrows(CallerFault.class, () -> consumption.advance(VIEWER, request(), 1));
    assertThrows(
        CallerFault.class, () -> consumption.lease(VIEWER, request(), Duration.ofMinutes(2)));
    assertEquals(0L, seenThrough("notices"));

    // Re-establishing binds to the new incarnation; the stored cursor is untouched.
    consumption.subscribe(VIEWER, request(), Relay.Start.OLDEST_RETAINED);
    assertEquals(
        RelaySubscriptionAuthority.Validity.VALID, authority.validate(key(), VIEWER, WINDOW, now));
    assertEquals(0L, seenThrough("notices"));
  }

  @Test
  void a_membership_change_withdraws_authority_without_waiting_for_the_window() {
    published("released");
    consumption.subscribe(VIEWER, request(), Relay.Start.OLDEST_RETAINED);
    assertEquals(1, authorityRows());

    // Well inside the window: if this took effect only by expiry the record would still be valid.
    jdbc.update("DELETE FROM project_members WHERE project_id=? AND handle=?", projectId, VIEWER);

    assertEquals(0, authorityRows());
    assertEquals(
        RelaySubscriptionAuthority.Validity.NOT_ESTABLISHED,
        authority.validate(key(), VIEWER, WINDOW, now));
    assertThrows(CallerFault.class, () -> consumption.consume(VIEWER, request(), 10));
  }

  @Test
  void a_scope_wide_withdrawal_removes_only_that_accounts_records() {
    published("released");
    consumption.subscribe(VIEWER, request(), Relay.Start.OLDEST_RETAINED);
    consumption.subscribe(CONTRIBUTOR, request(), Relay.Start.OLDEST_RETAINED);
    assertEquals(2, authorityRows());

    assertEquals(1, authority.invalidateAccount(new Relay.ProjectScope(projectId), VIEWER));

    assertEquals(
        RelaySubscriptionAuthority.Validity.NOT_ESTABLISHED,
        authority.validate(key(), VIEWER, WINDOW, now));
    assertEquals(
        RelaySubscriptionAuthority.Validity.VALID,
        authority.validate(key(), CONTRIBUTOR, WINDOW, now));
  }

  // ------------------------------------------------------------------------------------- pin 6

  @Test
  void a_subscription_past_the_configured_maximum_age_is_not_valid_and_the_age_is_configured() {
    published("released");
    consumption.subscribe(VIEWER, request(), Relay.Start.OLDEST_RETAINED);

    now = T0.plus(WINDOW).minusSeconds(1);
    assertEquals(
        RelaySubscriptionAuthority.Validity.VALID, authority.validate(key(), VIEWER, WINDOW, now));
    assertEquals(1, consumption.consume(VIEWER, request(), 10).publications().size());

    now = T0.plus(WINDOW);
    assertEquals(
        RelaySubscriptionAuthority.Validity.EXPIRED,
        authority.validate(key(), VIEWER, WINDOW, now));
    assertThrows(CallerFault.class, () -> consumption.consume(VIEWER, request(), 10));
    assertThrows(CallerFault.class, () -> consumption.advance(VIEWER, request(), 1));
    assertEquals(0L, seenThrough("notices"));

    // The bound is the configured one, not a constant: the same record, same clock, and only the
    // configured window different, is valid under a longer window and expired under a shorter one.
    assertEquals(
        1, consumption(Duration.ofDays(2)).consume(VIEWER, request(), 10).publications().size());
    now = T0.plusSeconds(600);
    assertThrows(
        CallerFault.class, () -> consumption(Duration.ofMinutes(5)).consume(VIEWER, request(), 10));
    assertEquals(
        1,
        consumption(Duration.ofMinutes(30)).consume(VIEWER, request(), 10).publications().size());
  }

  @Test
  void establishing_authority_requires_an_existing_topic_and_subscription() {
    // No topic: nothing to capture a generation from, and no authority record may appear.
    assertThrows(IllegalStateException.class, () -> authority.establish(key(), VIEWER, now));
    assertEquals(0, authorityRows());

    // A topic but no subscription row: the foreign key refuses an orphan record.
    relay.registerTopic(topic(), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    assertThrows(RuntimeException.class, () -> authority.establish(key(), VIEWER, now));
    assertEquals(0, authorityRows());
  }

  @Test
  void a_read_tier_subscriber_cannot_bring_a_topic_into_being() {
    assertThrows(
        CallerFault.class,
        () -> consumption.subscribe(VIEWER, request(), Relay.Start.OLDEST_RETAINED));
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
    consumption.subscribe(VIEWER, request(), Relay.Start.OLDEST_RETAINED);
    consumption.consume(VIEWER, request(), 10);
    consumption.advance(VIEWER, request(), 1);

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
  void topic_read_authority_and_consumer_state_ownership_are_independent_conjuncts() {
    published("released");
    consumption.subscribe(CONTRIBUTOR, request(), Relay.Start.OLDEST_RETAINED);
    var held = consumption.lease(CONTRIBUTOR, request(), Duration.ofMinutes(2));

    // Ownership alone does not admit: the stranger owns nothing and may read nothing.
    assertFalse(consumers.writableBy(key(), STRANGER));
    // Read authority alone does not admit either: the viewer may read, and still may not write
    // the contributor's live row. Each conjunct is checked, and neither implies the other.
    assertTrue(new JdbcProjectMembers(jdbc).mayUse(PROJECT, VIEWER));
    assertFalse(consumers.writableBy(key(), VIEWER));
    assertTrue(consumers.writableBy(key(), CONTRIBUTOR));

    consumers.release(held);
    assertTrue(consumers.writableBy(key(), VIEWER), "a lapsed lease is reassignable");
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
