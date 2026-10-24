package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.DurableRelay;
import io.aeyer.plowshare.server.relay.JdbcRelayLogRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.ScopedRelayInspection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL, for the things that need it: that a fired event reaches {@code firings} through
 * a committed publication, that the derived topic name is accepted by the columns that constrain
 * it, and that the scope's own authorization admits exactly the accounts the scope choice was
 * justified by.
 *
 * <p>Everything about the consumer's own decisions — publisher verification, cursor movement,
 * decode failures — is pinned without a database in {@link RelayEventPublicationTest}, because
 * Postgres would not make any of it truer.
 */
@Tag("full-db")
@Testcontainers
class RelayEventPublicationDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant AT = Instant.parse("2026-10-25T09:00:00Z");

  private static JdbcTemplate jdbc;
  private static UnitOfWork work;

  private TriggerStore triggers;
  private FiringStore firings;
  private Relay relay;
  private Intake intake;
  private EventPublication publication;

  /** The name under test, derived rather than spelled: see {@link EventTopics}. */
  private static final String TOPIC = EventTopics.fired(Relay.SystemScope.SERVER);

  private static final Relay.TopicKey KEY = new Relay.TopicKey(Relay.SystemScope.SERVER, TOPIC);

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    var template = new TransactionTemplate(new DataSourceTransactionManager(source));
    work =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> action) {
            return Objects.requireNonNull(template.execute(status -> action.get()));
          }
        };
  }

  @BeforeEach
  void fresh() {
    io.aeyer.plowshare.server.testing.AuthorityFixtures.truncate(
        jdbc,
        "TRUNCATE relay_topics, relay_source_events, firings, triggers, projects, admins CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES('operator','fixture',true)");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES('viewer','fixture',false)");
    triggers = new JdbcTriggerStore(jdbc);
    firings = new JdbcFiringStore(jdbc);
    relay = new DurableRelay(new JdbcRelayRepository(jdbc, work), () -> AT);
    intake = new Intake(triggers, firings, mock(Dispatcher.class), () -> AT);
    publication =
        new RelayEventPublication(
            relay,
            intake,
            work,
            () -> {
              if (TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException("job dispatch must not hold a transaction");
            },
            () -> AT);
  }

  private TriggerRecord trigger(String name, String project) {
    return triggers.define(
        new TriggerRecord(
            name, "daily", project, null, "fixture-bot", "task", 1, 1, 1, false, "operator"));
  }

  private List<Relay.Publication> published() {
    var read = relay.read(new Relay.SubscriptionKey(KEY, "inspection.fixture"), 100);
    return read.publications();
  }

  // ---------------------------------------------------------------------------------------------
  // Pin 1: the firing arrives through a publication, not beside one
  // ---------------------------------------------------------------------------------------------

  @Test
  void a_fired_event_reaches_firings_through_a_publication_on_the_derived_topic() {
    trigger("t", null);
    // A subscription registered before the event, so the fixture reader sees the publication the
    // event produces rather than starting after it.
    relay.registerTopic(KEY, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    relay.subscribe(
        new Relay.SubscriptionKey(KEY, "inspection.fixture"), Relay.Start.OLDEST_RETAINED);

    var created = publication.fire("daily", new EventPayload.Text("x=1"), "operator");

    // Both halves, deliberately: a test that asserted only the firing would still pass if the
    // publication were skipped, which is the whole of what this change does.
    assertEquals(1, created.size());
    assertEquals(
        1,
        (int)
            Objects.requireNonNull(
                jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class)));
    assertEquals(
        1,
        (int)
            Objects.requireNonNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM relay_publications WHERE scope_key='system' AND topic=?",
                    Integer.class,
                    TOPIC)));
    var publications = published();
    assertEquals(1, publications.size());
    assertEquals(TOPIC, publications.getFirst().topic().name());
  }

  @Test
  void the_topic_row_the_publication_created_carries_the_derived_name_and_the_general_message() {
    publication.fire("daily", new EventPayload.Empty(), "operator");
    var row =
        jdbc.queryForMap(
            "SELECT name, payload_kind, scope_key, project_id FROM relay_topics WHERE name=?",
            TOPIC);
    assertEquals(TOPIC, row.get("name"));
    // No payload family of its own: the general message, which is what adds no enum value, no codec
    // case and no CHECK allowlist entry.
    assertEquals("TEXT", row.get("payload_kind"));
    assertEquals("system", row.get("scope_key"));
    assertNull(row.get("project_id"));
  }

  // ---------------------------------------------------------------------------------------------
  // Pin 2: the content round-trips
  // ---------------------------------------------------------------------------------------------

  @Test
  void the_firing_and_the_publication_both_carry_exactly_what_was_fired() {
    trigger("t", null);
    relay.registerTopic(KEY, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    relay.subscribe(
        new Relay.SubscriptionKey(KEY, "inspection.fixture"), Relay.Start.OLDEST_RETAINED);

    var data = new EventPayload.Text("x=1 and some prose, ごはん 🍚");
    var created = publication.fire("daily", data, "operator");

    // What the subscriber stored.
    var firing = firings.find(created.getFirst().id()).orElseThrow();
    assertEquals("daily", firing.event());
    assertEquals(data, firing.data());
    // And what the broker stored, decoded by the codec that wrote it.
    var carried = published().getFirst().event().payload();
    assertInstanceOf(RelayPayload.Text.class, carried);
    assertEquals(
        new FiredEventCodec.Notice("daily", data),
        FiredEventCodec.read(((RelayPayload.Text) carried).text()));
  }

  /** Every optional field and boundary the family's validation admits, through the real column. */
  @Test
  void the_optional_and_boundary_shaped_payloads_the_family_admits_all_survive_the_round_trip() {
    trigger("t", null);
    relay.registerTopic(KEY, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    relay.subscribe(
        new Relay.SubscriptionKey(KEY, "inspection.fixture"), Relay.Start.OLDEST_RETAINED);

    record Case(String event, EventPayload data) {}
    var cases =
        List.of(
            // No data at all, which is the other half of what event.fire accepts.
            new Case("empty-data", new EventPayload.Empty()),
            // The shortest and longest text the family admits.
            new Case("shortest-text", new EventPayload.Text("x")),
            new Case("longest-text", new EventPayload.Text("x".repeat(1048576))),
            // Multi-byte content, because the broker's allowance is in bytes.
            new Case("multibyte", new EventPayload.Text("ごはん 🍚 café")),
            // The longest event name the family admits.
            new Case("e".repeat(1024), new EventPayload.Text("x=1")));

    for (var one : cases) {
      var created = publication.fire(one.event(), one.data(), "operator");
      assertEquals(1, created.size(), one.event());
      var firing = firings.find(created.getFirst().id()).orElseThrow();
      assertEquals(one.event(), firing.event(), one.event());
      assertEquals(one.data(), firing.data(), one.event());
    }
    // And each one is still readable out of the log, not merely accepted on the way in.
    var decoded =
        published().stream()
            .map(p -> FiredEventCodec.read(((RelayPayload.Text) p.event().payload()).text()))
            .toList();
    assertEquals(
        cases.stream().map(one -> new FiredEventCodec.Notice(one.event(), one.data())).toList(),
        decoded);
  }

  // ---------------------------------------------------------------------------------------------
  // Pin 5: the derived name is accepted by the real schema
  // ---------------------------------------------------------------------------------------------

  /**
   * Asserted by storing the name through a real topic column rather than by restating the rule as a
   * regex in Java. {@code relay_source_events.topic} carries the length and pattern CHECK every
   * topic column applies; {@code relay_topics.name} is exercised by every other test in this file,
   * since publishing creates the row.
   */
  @Test
  void the_derived_name_is_accepted_by_a_real_topic_column() {
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "INSERT INTO relay_source_events(project_id,topic,family,subject,state,occurred_at)"
                    + " VALUES(NULL,?,'LIFECYCLE','subject','state',?)",
                TOPIC,
                java.sql.Timestamp.from(AT)),
        "the derived topic name must satisfy the schema's own length and pattern rules");
    assertEquals(
        1,
        (int)
            Objects.requireNonNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM relay_source_events WHERE topic=?",
                    Integer.class,
                    TOPIC)));
  }

  /**
   * And it clears every length bound a topic name actually faces, read from the live catalogue
   * rather than from a number written down here.
   *
   * <p>Written this way because the number in the documentation is wrong. V117, V119 and V124 each
   * bounded a topic name at 160 characters, and <b>V141 widened all three to 1024 in one
   * migration</b> — so the claim repeated by V190's header and by {@code InformationTopics}, that
   * {@code relay_source_events.topic} is a 160-character bound and "the tightest bound a topic name
   * faces", was already superseded when it was written. A test asserting 160 would therefore have
   * pinned a fiction, and one asserting 1024 would pin whatever is true today. Asking {@code
   * pg_constraint} instead means this test keeps telling the truth across a migration that moves a
   * bound in either direction, and fails if one is ever tightened past what the derivation needs.
   */
  @Test
  void the_derived_name_clears_every_topic_name_length_bound_the_live_schema_declares() {
    var bounds =
        jdbc.queryForList(
            "SELECT t.relname || '.' || c.conname AS where_it_is,"
                + " pg_get_constraintdef(c.oid) AS definition"
                + " FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid"
                + " WHERE c.contype = 'c' AND t.relname LIKE 'relay%'"
                + " AND pg_get_constraintdef(c.oid) ~ 'length\\(\\(?(topic|name|publish_to)\\)?\\)'");
    assertFalse(bounds.isEmpty(), "the scan must find the topic-name constraints it checks");
    var pattern = java.util.regex.Pattern.compile("length\\(\\(?\\w+\\)?\\) <= (\\d+)");
    int tightest = Integer.MAX_VALUE;
    for (var bound : bounds) {
      var found = pattern.matcher(String.valueOf(bound.get("definition")));
      while (found.find()) tightest = Math.min(tightest, Integer.parseInt(found.group(1)));
    }
    assertNotEquals(Integer.MAX_VALUE, tightest, "no length bound was parsed out of " + bounds);
    final int bound = tightest;
    assertTrue(
        TOPIC.length() <= bound,
        "the derived name is "
            + TOPIC.length()
            + " characters against a tightest bound of "
            + bound);
    // The bound is enforced and not merely declared, so a name that overran it would have failed.
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO relay_source_events(project_id,topic,family,subject,state,occurred_at)"
                    + " VALUES(NULL,?,'LIFECYCLE','subject','state',?)",
                "e" + "x".repeat(bound),
                java.sql.Timestamp.from(AT)));
  }

  // ---------------------------------------------------------------------------------------------
  // The authorization basis the scope choice rests on
  // ---------------------------------------------------------------------------------------------

  /**
   * The whole justification for publishing on the server scope is that the topic's read audience
   * equals the operation's authority: {@code ScopedRelayInspection.require} gates system-scope
   * inspection on {@code isServerAdmin}, and {@code ProjectAuthorization} admits {@code event.fire}
   * only to a server administrator. If that ever stops holding, the scope choice becomes wrong, and
   * this is the test that must say so.
   */
  @Test
  void only_a_server_administrator_can_read_the_topic_the_events_are_published_to() {
    publication.fire("daily", new EventPayload.Text("x=1"), "operator");
    var inspection =
        new ScopedRelayInspection(
            new JdbcProjectMembers(jdbc),
            mock(ProjectWorkspaces.class),
            new JdbcRelayLogRepository(jdbc, work));
    var query = new RelayLog.Query(null, true, TOPIC, null, 10);

    // The population that may fire the event can read its log.
    var page = inspection.log("operator", query);
    assertNotNull(page);

    // And every other state an account can be in cannot. Enumerated rather than asserting the one
    // refusal the implementation happens to reach first.
    for (String account : new String[] {"viewer", "nobody"})
      assertThrows(
          CallerFault.class,
          () -> inspection.log(account, query),
          "admitted to the system scope: " + account);

    // A disabled administrator is not one, which is the state a withdrawn account leaves behind.
    jdbc.update("UPDATE admins SET enabled=false WHERE handle='operator'");
    assertThrows(CallerFault.class, () -> inspection.log("operator", query));
  }

  // ---------------------------------------------------------------------------------------------
  // Pin 6 and the fan-out: what Intake guaranteed still holds
  // ---------------------------------------------------------------------------------------------

  /**
   * One raised event still reaches every unpaused trigger on the installation, across projects and
   * defining accounts. This is the fan-out {@code JdbcTriggerStore.listening} has always performed
   * — {@code WHERE event = ? AND NOT paused}, with no project or account filter — and it is the
   * behaviour most easily lost by inserting a publication step in front of it.
   */
  @Test
  void one_publication_still_fans_out_to_every_listening_trigger_across_projects() {
    jdbc.update("INSERT INTO projects(name,workspace) VALUES('alpha','fixture')");
    jdbc.update("INSERT INTO projects(name,workspace) VALUES('beta','fixture')");
    trigger("in-alpha", "alpha");
    trigger("in-beta", "beta");
    trigger("homeless", null);
    var paused =
        triggers.define(
            new TriggerRecord(
                "asleep", "daily", null, null, "fixture-bot", "task", 1, 1, 1, false, "operator"));
    triggers.pause(paused.name(), true, "operator");
    // A trigger listening for something else, so the selection is by event and not by everything.
    triggers.define(
        new TriggerRecord(
            "other", "weekly", null, null, "fixture-bot", "task", 1, 1, 1, false, "operator"));

    var created = publication.fire("daily", new EventPayload.Text("x=1"), "operator");

    assertEquals(
        List.of("homeless", "in-alpha", "in-beta"),
        created.stream().map(FiringRecord::trigger).sorted().toList());
    assertTrue(created.stream().allMatch(f -> "queued".equals(f.status())));
    // One publication for the whole fan-out, not one per trigger: the firings are the subscriber's
    // copies of a single event.
    assertEquals(1, publicationCount());
  }

  /**
   * An event nothing listens for is still accounted for, as one {@code unmatched} firing attached
   * to no trigger. {@code Intake}'s empty-listening branch is the only thing that writes such a
   * row, and it is also the clearest evidence that a fired event has no project: this firing
   * belongs to no trigger and so to no project by any route.
   */
  @Test
  void an_event_no_trigger_listens_for_still_records_its_unmatched_firing() {
    var created = publication.fire("nobody-listens", new EventPayload.Text("x=1"), "operator");
    assertEquals(1, created.size());
    assertEquals("unmatched", created.getFirst().status());
    assertNull(created.getFirst().trigger());
    assertNull(created.getFirst().target());
    assertEquals(1, publicationCount());
  }

  /**
   * The scheduled family's queue policy stays the scheduled family's. A fired event takes no queue
   * lock and is not superseded at record time — {@code Dispatcher.dispatch} applies the trigger's
   * cap when the firing is handed over — so a publication step that started applying the scheduled
   * policy would change what a fired event does.
   */
  @Test
  void a_fired_event_records_no_supersession_and_takes_no_scheduled_queue_lock() {
    trigger("t", null);
    var inspected = new java.util.ArrayList<String>();
    var recording =
        new JdbcFiringStore(jdbc) {
          @Override
          public void lockScheduledQueues(List<String> names) {
            inspected.add("lock");
            super.lockScheduledQueues(names);
          }

          @Override
          public int supersedeBeyond(String trigger, int cap, String newest) {
            inspected.add("supersede");
            return super.supersedeBeyond(trigger, cap, newest);
          }
        };
    var publisher =
        new RelayEventPublication(
            relay,
            new Intake(triggers, recording, mock(Dispatcher.class), () -> AT),
            work,
            () -> {},
            () -> AT);
    publisher.fire("daily", new EventPayload.Empty(), "operator");
    assertEquals(List.of(), inspected);
    // Two in a row both remain queued: nothing superseded the first at record time.
    publisher.fire("daily", new EventPayload.Empty(), "operator");
    assertEquals(2, firings.list("t", "queued", 0, 10).size());
    assertEquals(0, firings.list("t", "superseded", 0, 10).size());
  }

  // ---------------------------------------------------------------------------------------------
  // What replays on first registration
  // ---------------------------------------------------------------------------------------------

  /**
   * Nothing. The subscription is registered with {@code Start.LATEST}, so a topic that already
   * carries publications — one written before this subscriber existed — does not hand them to the
   * firing inbox the first time it registers. The schedules migration made the same choice for the
   * same reason: a firing already in the inbox must not arrive a second time.
   */
  @Test
  void first_registration_replays_nothing_that_was_published_before_it() {
    trigger("t", null);
    relay.registerTopic(KEY, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    // An earlier publication by this subsystem's own namespace, so it is not skipped for being
    // foreign: if anything replayed, this is what would arrive.
    relay.publish(
        KEY,
        new Relay.Draft(
            "event:" + java.util.UUID.randomUUID(),
            "event:" + io.aeyer.plowshare.protocol.RelayPort.hash("operator"),
            AT,
            null,
            null,
            new RelayPayload.Text(
                FiredEventCodec.write(
                    new FiredEventCodec.Notice("legacy", new EventPayload.Text("old"))))));
    assertEquals(
        0,
        (int)
            Objects.requireNonNull(
                jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class)));

    var created = publication.fire("daily", new EventPayload.Text("new"), "operator");

    assertEquals(1, created.size());
    assertEquals("daily", created.getFirst().event());
    assertEquals(
        List.of("daily"),
        firings.list(null, null, 0, 10).stream().map(FiringRecord::event).toList(),
        "the publication that preceded the subscription must not be replayed into the inbox");
  }

  private int publicationCount() {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE scope_key='system' AND topic=?",
            Integer.class,
            TOPIC));
  }
}
