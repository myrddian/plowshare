package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import io.aeyer.plowshare.server.relay.DurableRelay;
import io.aeyer.plowshare.server.relay.JdbcRelayConsumerRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayDeliveryRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.JdbcRelaySubscriptionAuthority;
import io.aeyer.plowshare.server.relay.ReadTierRelayConsumption;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayConsumption;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * Information events on Relay (decision 0037) against real PostgreSQL.
 *
 * <p>Real PostgreSQL and real rows, because every assertion here is about something only the
 * database decides: which {@code authority_scopes} row a resource's home trigger derives, the V185
 * alias between an account's knowledge scope and its personal project, the roles {@code
 * JdbcProjectMembers} reads, Relay's own topic lock and position allocation, and the conditional
 * INSERTs that fence consumer-group ownership. A stubbed membership or a stubbed home scope would
 * answer the only questions worth asking with whatever it was told to answer.
 *
 * <h2>The invariant these pins exist for</h2>
 *
 * <p><b>No account may receive an information event for a scope it may not read.</b> Delivery
 * follows subscription, so the pin is that a subscription cannot exist, or cannot deliver, for an
 * account that fails the scope's read authority — and that is asserted in <i>every</i> state the
 * subject can be in: never a member, a member of some other project, and a member whose role was
 * withdrawn after a subscription had already been established. A refusal asserted in one state only
 * is what let an earlier pin on this branch pass against exploitable code.
 *
 * <p>Set-equality with the removed per-admin fan-out is deliberately <b>not</b> asserted. Frozen
 * emit-time recipients and subscription-time delivery are different semantics by design, and
 * demanding both would re-impose the design being removed.
 */
@Tag("full-db")
@Testcontainers
class RelayInformationEventsDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final String PROJECT = "atlas";
  private static final String AUTHOR = "author";
  private static final String VIEWER = "viewer";
  private static final String STRANGER = "stranger";
  private static final String ELSEWHERE = "elsewhere";
  private static final String TOPIC = "information.changed";
  private static final Duration WINDOW = Duration.ofHours(12);
  private static final Instant T0 = Instant.parse("2026-10-24T09:00:00Z");

  private static DriverManagerDataSource source;
  private static JdbcTemplate jdbc;
  private static UnitOfWork work;
  private static TransactionTemplate template;

  private long projectId;
  private long personalId;
  private JdbcRelayRepository broker;
  private Relay relay;
  private RelayConsumption consumption;
  private InformationEventRecords records;
  private InformationEvents events;
  private final CopyOnWriteArrayList<String> hinted = new CopyOnWriteArrayList<>();
  private Instant now = T0;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    var manager = new DataSourceTransactionManager(source);
    template = new TransactionTemplate(manager);
    work = new ArchiveConfig().unitOfWork(manager);
  }

  @BeforeEach
  void fresh() {
    AuthorityFixtures.truncate(
        jdbc, "TRUNCATE relay_topics,information_resources,projects,admins CASCADE");
    jdbc.update(
        """
        INSERT INTO admins(handle,password_hash,server_admin)
        VALUES('author','fixture',false),('viewer','fixture',false),
              ('stranger','fixture',false),('elsewhere','fixture',false)
        """);
    AuthorityFixtures.personalIdentity(jdbc, AUTHOR);
    personalId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT id FROM projects WHERE name=?", Long.class, PersonalSpaces.name(AUTHOR)));
    projectId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES(?,'fixture') RETURNING id",
                Long.class,
                PROJECT));
    long other =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('other','fixture') RETURNING id",
                Long.class));
    // STRANGER is deliberately absent, and ELSEWHERE holds a role on a different project: "no read
    // access" must be a missing row and "a role somewhere" must not be a role here.
    for (var member : Map.of(AUTHOR, "CONTRIBUTOR", VIEWER, "VIEWER").entrySet())
      jdbc.update(
          "INSERT INTO project_members(project_id,handle,role) VALUES(?,?,?)",
          projectId,
          member.getKey(),
          member.getValue());
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES(?,?,'MANAGER')",
        other,
        ELSEWHERE);

    now = T0;
    hinted.clear();
    broker = new JdbcRelayRepository(jdbc, work);
    relay = new DurableRelay(broker, () -> now);
    var consumers =
        new JdbcRelayConsumerRepository(
            jdbc, work, new JdbcRelayDeliveryRepository(jdbc, work), broker);
    var projects = new Workspaces();
    consumption =
        new ReadTierRelayConsumption(
            new JdbcProjectMembers(jdbc),
            projects,
            relay,
            consumers,
            new JdbcRelaySubscriptionAuthority(jdbc),
            WINDOW,
            () -> now);
    records = new JdbcInformationEventRecords(jdbc);
    events =
        new RelayInformationEvents(
            records,
            relay,
            consumption,
            projects,
            work,
            (handle, body) -> hinted.add(handle),
            () -> now);
  }

  /** Real project rows; the personal-owner rule is load-bearing and must not be stubbed. */
  private final class Workspaces implements ProjectWorkspaces {
    @Override
    public Optional<ProjectRecord> find(String name) {
      throw new UnsupportedOperationException("this fixture needs identity, not workspaces");
    }

    @Override
    public Long id(String name) {
      return jdbc
          .query("SELECT id FROM projects WHERE name=?", (row, index) -> row.getLong(1), name)
          .stream()
          .findFirst()
          .orElse(null);
    }

    @Override
    public Optional<String> personalOwner(String name) {
      return jdbc
          .query(
              "SELECT personal_owner FROM projects WHERE name=?",
              (row, index) -> row.getString(1),
              name)
          .stream()
          .filter(Objects::nonNull)
          .findFirst();
    }

    @Override
    public List<Path> effectiveExclusions(ProjectRecord project) {
      throw new UnsupportedOperationException("this fixture needs identity, not workspaces");
    }
  }

  // ---- fixtures -------------------------------------------------------------------------------

  /**
   * One retained revision whose home scope the V157 trigger derives from the namespace it is given,
   * which is how the server itself decides a resource's home.
   */
  private UUID revision(String namespace, Long project, String owner) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind)"
            + " VALUES(?,?,?,?,?,'source')",
        resource,
        namespace,
        resource.toString(),
        owner,
        project);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size)"
            + " VALUES(?,?,1,'fixture','text/plain','fixture',1)",
        revision,
        resource);
    return revision;
  }

  private UUID projectHomed() {
    return revision("project:" + PROJECT, projectId, AUTHOR);
  }

  private UUID personalHomed() {
    return revision("project:" + PersonalSpaces.name(AUTHOR), personalId, AUTHOR);
  }

  private UUID accountKnowledgeHomed() {
    return revision("account:" + AUTHOR, null, AUTHOR);
  }

  private UUID globalKnowledgeHomed() {
    String global =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT a.legacy_handle FROM authority_accounts a JOIN authority_scopes s ON s.account_id=a.id"
                    + " WHERE s.builtin='GLOBAL_KNOWLEDGE'",
                String.class));
    return revision("account:" + global, null, global);
  }

  private UUID restrictedHomed() {
    long client =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('client:acme','fixture') RETURNING id",
                Long.class));
    return revision("project:client:acme", client, AUTHOR);
  }

  private void publish(UUID revision, String stage, String action, String detail) {
    template.executeWithoutResult(
        status ->
            events.publish(
                new InformationEvents.Occurrence(revision, 1, AUTHOR, stage, action, detail)));
  }

  private InformationContext reader(String account, String project) {
    return new InformationContext(account, InformationContext.Selection.project(project))
        .withView(true);
  }

  private String scope(long project) {
    return "project:" + project;
  }

  private Integer publications(long project) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM relay_publications WHERE scope_key=? AND topic=?",
        Integer.class,
        scope(project),
        TOPIC);
  }

  private Integer topics() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM relay_topics WHERE name=?", Integer.class, TOPIC);
  }

  private Integer history(UUID revision) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM information_revision_events WHERE revision_id=?",
        Integer.class,
        revision);
  }

  private List<String> groups(long project) {
    return jdbc.queryForList(
        "SELECT subscriber FROM relay_subscriptions WHERE scope_key=? AND topic=? ORDER BY subscriber",
        String.class,
        scope(project),
        TOPIC);
  }

  private Long committed(long project, String account) {
    return jdbc.queryForObject(
        "SELECT seen_through FROM relay_subscriptions WHERE scope_key=? AND topic=? AND subscriber=?",
        Long.class,
        scope(project),
        TOPIC,
        "reader:" + account + ":information.events");
  }

  private int authorityRows() {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_subscription_authority WHERE topic=?",
            Integer.class,
            TOPIC));
  }

  // ---- pins -----------------------------------------------------------------------------------

  @Test
  void a_project_homed_event_publishes_to_that_projects_topic_and_reaches_a_reader() {
    UUID revision = projectHomed();
    publish(revision, "derive", "ready", "private processing details");

    assertEquals(1, publications(projectId));
    assertEquals(0, publications(personalId));
    assertEquals(1, history(revision));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT relay_position FROM information_revision_events WHERE revision_id=?",
            Long.class,
            revision),
        "a revision's history records Relay's position, not a sequence of its own");

    var page = events.read(reader(VIEWER, PROJECT), 0, 50);
    assertEquals(1, page.events().size());
    assertEquals(revision, page.events().getFirst().revisionId());
    assertEquals(1, page.events().getFirst().sequence());
    assertEquals("ready", page.events().getFirst().action());
    assertEquals(
        "", page.events().getFirst().detail(), "detail belongs to the home resource's owner");
    assertEquals(
        "private processing details",
        events.read(reader(AUTHOR, PROJECT), 0, 50).events().getFirst().detail());
  }

  @Test
  void a_wake_hint_goes_to_the_topics_subscribers_and_to_nobody_else() throws Exception {
    UUID first = projectHomed();
    publish(first, "intake", "retained", "");
    events.read(reader(VIEWER, PROJECT), 0, 50);
    hinted.clear();

    publish(projectHomed(), "derive", "ready", "");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (hinted.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
    assertEquals(
        List.of(VIEWER),
        List.copyOf(hinted),
        "recipients come from the topic's subscriptions, not from a recipient table");
  }

  @Test
  void an_account_with_no_read_access_cannot_subscribe_and_nothing_is_delivered_to_it() {
    UUID revision = projectHomed();
    publish(revision, "derive", "ready", "");

    // Every state the subject can be in, not just the one the implementation happens to refuse.
    for (String outsider : List.of(STRANGER, ELSEWHERE)) {
      assertThrows(CallerFault.class, () -> events.read(reader(outsider, PROJECT), 0, 50));
      assertThrows(
          CallerFault.class,
          () ->
              consumption.subscribe(
                  outsider,
                  new RelayConsumption.Request(PROJECT, TOPIC, "information.events"),
                  Relay.Start.OLDEST_RETAINED));
    }
    assertEquals(
        List.of(), groups(projectId), "a refused reader must leave no consumer group behind");
    assertEquals(0, authorityRows(), "and no record that its authority was ever established");
  }

  @Test
  void a_withdrawn_role_stops_delivering_through_a_subscription_that_already_existed() {
    UUID revision = projectHomed();
    publish(revision, "derive", "ready", "");
    assertEquals(1, events.read(reader(VIEWER, PROJECT), 0, 50).events().size());
    assertEquals(1, authorityRows());

    jdbc.update("DELETE FROM project_members WHERE project_id=? AND handle=?", projectId, VIEWER);

    assertThrows(CallerFault.class, () -> events.read(reader(VIEWER, PROJECT), 0, 50));
    assertEquals(
        0,
        authorityRows(),
        "a membership change withdraws that account's established authority in the same change");
    // Re-establishing is refused too: the subscription row surviving is not an authority.
    assertThrows(
        CallerFault.class,
        () ->
            consumption.subscribe(
                VIEWER,
                new RelayConsumption.Request(PROJECT, TOPIC, "information.events"),
                Relay.Start.OLDEST_RETAINED));
  }

  @Test
  void account_knowledge_publishes_to_the_owners_personal_project_and_only_the_owner_reads_it() {
    UUID revision = accountKnowledgeHomed();
    publish(revision, "derive", "ready", "");

    assertEquals(1, publications(personalId), "the V185 alias: the owner's personal project");
    assertEquals(0, publications(projectId));

    String personal = PersonalSpaces.name(AUTHOR);
    assertEquals(1, events.read(reader(AUTHOR, personal), 0, 50).events().size());
    for (String outsider : List.of(VIEWER, STRANGER, ELSEWHERE))
      assertThrows(CallerFault.class, () -> events.read(reader(outsider, personal), 0, 50));
    assertEquals(
        List.of("reader:" + AUTHOR + ":information.events"),
        groups(personalId),
        "a personal project's topic admits its exact owner and nobody else");
  }

  @Test
  void a_personal_homed_event_publishes_to_that_personal_projects_topic() {
    UUID revision = personalHomed();
    publish(revision, "intake", "retained", "");
    assertEquals(1, publications(personalId));
    assertEquals(0, publications(projectId));
    assertEquals(
        revision,
        events
            .read(reader(AUTHOR, PersonalSpaces.name(AUTHOR)), 0, 50)
            .events()
            .getFirst()
            .revisionId());
  }

  @Test
  void a_home_scope_with_no_relay_audience_fails_loudly_and_leaves_no_partial_state() {
    for (UUID revision : List.of(globalKnowledgeHomed(), restrictedHomed())) {
      var refused =
          assertThrows(
              InformationEventRecords.Unaddressable.class,
              () -> publish(revision, "derive", "ready", "details"));
      assertTrue(refused.getMessage().contains("0039"), refused.getMessage());
      assertEquals(0, topics(), "no topic may be brought into being for an unaddressable home");
      assertEquals(0, history(revision), "and no history row may survive the refusal");
    }
    assertEquals(
        0,
        Objects.requireNonNull(
            jdbc.queryForObject("SELECT count(*) FROM relay_publications", Integer.class)),
        "nothing may be published to any audience when the right one does not exist");
  }

  @Test
  void two_accounts_get_independent_consumer_groups_and_independent_positions() {
    publish(projectHomed(), "intake", "retained", "");
    publish(projectHomed(), "derive", "ready", "");
    publish(projectHomed(), "metadata", "tags", "");

    assertEquals(3, events.read(reader(AUTHOR, PROJECT), 0, 50).events().size());
    assertEquals(3, events.read(reader(VIEWER, PROJECT), 0, 50).events().size());
    assertEquals(
        List.of(
            "reader:" + AUTHOR + ":information.events", "reader:" + VIEWER + ":information.events"),
        groups(projectId));

    // One account acknowledges two of the three. The other's position must not move with it.
    var acknowledged = events.read(reader(AUTHOR, PROJECT), 2, 50);
    assertEquals(2L, committed(projectId, AUTHOR));
    assertEquals(0L, committed(projectId, VIEWER));
    assertEquals(
        List.of(3L),
        acknowledged.events().stream().map(event -> event.sequence()).toList(),
        "an acknowledged position is the account's own progress, not a shared cursor");
    assertEquals(
        3,
        events.read(reader(VIEWER, PROJECT), 0, 50).events().size(),
        "the other account still has the whole backlog");
  }

  @Test
  void a_publication_waits_only_for_its_own_revision_and_never_for_the_installation()
      throws Exception {
    UUID held = projectHomed();
    UUID independent = personalHomed();
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var worker =
          threads.submit(
              () ->
                  template.execute(
                      status -> {
                        jdbc.queryForObject(
                            "SELECT id FROM information_revisions WHERE id=? FOR UPDATE",
                            UUID.class,
                            held);
                        locked.countDown();
                        assertDoesNotThrow(() -> release.await(20, TimeUnit.SECONDS));
                        return null;
                      }));
      assertTrue(locked.await(10, TimeUnit.SECONDS));
      try {
        // The removed fence was one advisory key for the whole installation, so this publication
        // would have waited behind the held revision even though it shares nothing with it.
        assertTimeoutPreemptively(
            Duration.ofSeconds(10), () -> publish(independent, "intake", "retained", ""));
        assertEquals(1, publications(personalId));
      } finally {
        release.countDown();
      }
      worker.get(20, TimeUnit.SECONDS);
    }
    // And the revision row lock the fence was confused with is still taken: publishing for the
    // held revision waits for that worker rather than racing its allowance row.
    publish(held, "derive", "ready", "");
    assertEquals(1, publications(projectId));
  }
}
