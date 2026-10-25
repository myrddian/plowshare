package io.aeyer.plowshare.server.information;

import static java.nio.charset.StandardCharsets.UTF_8;
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
import io.aeyer.plowshare.server.relay.JdbcRelayPayloadStore;
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

  /** The literal the topic name used to be, kept only so its absence can be asserted. */
  private static final String WITHDRAWN = "information.changed";

  /** The derivation's own namespace and the expression V190 reconstructs it with in SQL. */
  private static final String DERIVATION =
      "'information.events.' || encode(sha256(('plowshare.information.events/1:' || ?)::bytea),"
          + " 'hex')";

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
  private JdbcRelaySubscriptionAuthority authority;
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
    // relay_payloads is not reachable by a cascade from relay_topics, so it is named.
    AuthorityFixtures.truncate(
        jdbc, "TRUNCATE relay_topics,relay_payloads,information_resources,projects,admins CASCADE");
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
    var consumers =
        new JdbcRelayConsumerRepository(
            jdbc, work, new JdbcRelayDeliveryRepository(jdbc, work), broker);
    var projects = new Workspaces();
    authority = new JdbcRelaySubscriptionAuthority(jdbc);
    consumption =
        new ReadTierRelayConsumption(
            new JdbcProjectMembers(jdbc), projects, relay, consumers, authority, WINDOW, () -> now);
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

  /**
   * Publishes with no enclosing transaction, so {@code UnitOfWork.afterCommit} runs the wake hint
   * inline instead of on a virtual thread. The hint pins are about <i>who</i> is hinted, and a
   * negative assertion against an asynchronous fan-out cannot distinguish "never sent" from "not
   * sent yet". The transactional path is what every other pin here exercises.
   */
  private void publishAndHint(UUID revision, String action) {
    events.publish(new InformationEvents.Occurrence(revision, 1, AUTHOR, "derive", action, ""));
  }

  private InformationContext reader(String account, String project) {
    return new InformationContext(account, InformationContext.Selection.project(project))
        .withView(true);
  }

  private String scope(long project) {
    return "project:" + project;
  }

  /**
   * The topic name a project's information events live under.
   *
   * <p>Derived here rather than spelled out, because these pins are about rows and audiences and
   * would be unreadable with an 83-character literal repeated per project. That makes the queries
   * agree with the derivation by construction, so the two assertions the derivation itself needs
   * are made explicitly and separately: {@link
   * #the_stored_topic_name_is_the_derived_one_and_the_literal_is_nowhere} asserts what PostgreSQL
   * holds, and {@link #the_topic_name_postgresql_derives_is_the_one_java_derives} asserts the
   * migration's SQL expression and this method produce the same string.
   */
  private static String topic(long project) {
    return InformationTopics.of(new Relay.ProjectScope(project));
  }

  private Integer publications(long project) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM relay_publications WHERE scope_key=? AND topic=?",
        Integer.class,
        scope(project),
        topic(project));
  }

  /** Every topic row, whatever its name: "no topic was brought into being" admits no exceptions. */
  private Integer topics() {
    return jdbc.queryForObject("SELECT count(*) FROM relay_topics", Integer.class);
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
        topic(project));
  }

  private Long committed(long project, String account) {
    return jdbc.queryForObject(
        "SELECT seen_through FROM relay_subscriptions WHERE scope_key=? AND topic=? AND subscriber=?",
        Long.class,
        scope(project),
        topic(project),
        "reader:" + account + ":information.events");
  }

  private int authorityRows() {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_subscription_authority WHERE topic=?",
            Integer.class,
            topic(projectId)));
  }

  // ---- pins -----------------------------------------------------------------------------------

  /**
   * What reached the broker, asserted on what PostgreSQL holds rather than on what was requested.
   *
   * <p>A test that only checked the argument the publisher passed would pass just as happily if the
   * broker stored something else, so every assertion here reads the stored rows back. The literal
   * the name used to be is asserted absent from the three tables keyed on it, in every scope, which
   * is the part that would catch a derivation applied on one path and not the other.
   */
  @Test
  void the_stored_topic_name_is_the_derived_one_and_the_literal_is_nowhere() {
    publish(projectHomed(), "derive", "ready", "");
    publish(personalHomed(), "intake", "retained", "");
    events.read(reader(VIEWER, PROJECT), 0, 50);

    assertEquals(
        List.of(topic(personalId), topic(projectId)).stream().sorted().toList(),
        jdbc.queryForList("SELECT name FROM relay_topics ORDER BY name", String.class),
        "the stored topic names are the derived ones");
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_topics WHERE name=?", Integer.class, WITHDRAWN),
        "no topic may exist under the withdrawn literal");
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE topic=?", Integer.class, WITHDRAWN),
        "nothing may be published under the withdrawn literal");
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_subscriptions WHERE topic=?", Integer.class, WITHDRAWN),
        "and no subscription may be established against it");
    // The two scopes are two topics. One name shared across scopes would merge two audiences.
    assertNotEquals(topic(projectId), topic(personalId));
  }

  /**
   * The derivation in Java and the one in {@code V190__information_topic_derived_name.sql} are the
   * same function.
   *
   * <p>This is the pin the migration's comment points at. The two derivations are written in
   * different languages, run at different times, and a disagreement would not fail anything else:
   * the migration would rename live rows to a name no publisher or consumer ever asks for, so every
   * subscriber's cursor would be stranded under a name nothing reads. The expression below is the
   * migration's own, applied to the scope keys V120 stores.
   */
  @Test
  void the_topic_name_postgresql_derives_is_the_one_java_derives() {
    // The expression below is the migration's, established by reading it out of the shipped file
    // rather than by having been copied from it once. Without this step the pin would compare Java
    // against a transcription, and a change to the migration alone would not fail anything.
    String migration;
    try (var shipped =
        RelayInformationEventsDatabaseTest.class
            .getClassLoader()
            .getResourceAsStream("db/migration/V190__information_topic_derived_name.sql")) {
      migration = new String(Objects.requireNonNull(shipped).readAllBytes(), UTF_8);
    } catch (java.io.IOException unreadable) {
      throw new AssertionError(unreadable);
    }
    assertTrue(
        migration.replaceAll("\\s+", " ").contains(DERIVATION.replace("?", "scope_key")),
        "the expression this pin evaluates must be the one V190 applies to relay_topics.scope_key");

    assertEquals(
        topic(projectId),
        jdbc.queryForObject("SELECT " + DERIVATION, String.class, scope(projectId)));
    assertEquals(
        topic(personalId),
        jdbc.queryForObject("SELECT " + DERIVATION, String.class, scope(personalId)));
    assertEquals(
        InformationTopics.of(Relay.SystemScope.SERVER),
        jdbc.queryForObject("SELECT " + DERIVATION, String.class, "system"),
        "the server scope is derivable too, so the migration can rename a row holding one");
    assertNotEquals(
        topic(projectId),
        jdbc.queryForObject("SELECT " + DERIVATION, String.class, scope(personalId)),
        "and the SQL separates scopes as well, rather than agreeing on one constant");
  }

  /**
   * The derived name satisfies the schema's own name rule, asserted by storing it.
   *
   * <p>Not by a regular expression in Java, which would only restate the migration's. {@code
   * relay_topics.name} is checked by publishing — its CHECK is the 1024-character form V141 left —
   * and {@code relay_source_events.topic} is checked directly here because its CHECK is the
   * stricter 160-character one, which is the bound the name has to stay inside.
   */
  @Test
  void the_derived_name_is_accepted_by_the_real_schema_including_its_strictest_bound() {
    publish(projectHomed(), "derive", "ready", "");
    assertEquals(1, publications(projectId), "relay_topics accepted the name");

    assertDoesNotThrow(
        () ->
            jdbc.update(
                "INSERT INTO relay_source_events(project_id,topic,family,subject,state,occurred_at)"
                    + " VALUES(?,?,'LIFECYCLE','fixture','fixture',now())",
                projectId,
                topic(projectId)),
        "the strictest relay topic-name CHECK in the schema must accept the derived name");
    // And the bound it was accepted against is real: one character more is refused.
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO relay_source_events(project_id,topic,family,subject,state,occurred_at)"
                    + " VALUES(?,?,'LIFECYCLE','fixture','fixture',now())",
                projectId,
                topic(projectId) + ".UPPER"));
  }

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

  /**
   * The read path that regressed, pinned against real rows.
   *
   * <p>{@code relay.log} serves a project's topics to any project member and reports a general
   * message's content verbatim, so it is a second read path over the same publications -- and one
   * gated on membership rather than on resource ownership. While detail travelled in the payload it
   * was therefore readable there by every member, including the non-owner that {@code
   * information.events} withholds it from. Detail is not in the publication any more, and this
   * asserts it on the log view itself rather than on the notice, because the log view is where the
   * leak appeared.
   */
  @Test
  void the_topics_log_discloses_no_owner_only_detail_to_a_member_who_is_not_the_owner() {
    UUID revision = projectHomed();
    publish(revision, "derive", "ready", "private processing details");

    var inspection =
        new io.aeyer.plowshare.server.relay.ScopedRelayInspection(
            new JdbcProjectMembers(jdbc),
            new Workspaces(),
            new io.aeyer.plowshare.server.relay.JdbcRelayLogRepository(jdbc, work));
    var page =
        inspection.log(
            VIEWER,
            new io.aeyer.plowshare.protocol.RelayLog.Query(
                PROJECT, false, topic(projectId), null, 50));

    assertEquals(1, page.events().size(), "the publication is visible to a project member");
    var payload = page.events().getFirst().payload();
    assertEquals("TEXT", payload.kind(), "an information notice is the general message");
    assertFalse(
        payload.text().contains("private processing details"),
        "owner-only detail must not be readable through the topic's log");
    assertFalse(payload.text().contains("detail"), "the publication does not name the field");
    // Nor does the publisher name the account behind the change to everyone who can inspect it.
    assertFalse(page.events().getFirst().publisher().contains(AUTHOR));
    assertTrue(page.events().getFirst().publisher().startsWith("information:"));
    // The owner's own read path still answers, from the history rather than from the publication.
    assertEquals(
        "private processing details",
        events.read(reader(AUTHOR, PROJECT), 0, 50).events().getFirst().detail());
  }

  /**
   * Deletion now deletes. A publication is immutable and retained for the topic's policy window, so
   * a detail published into one outlived the removal of the revision it described; the history
   * cascades with the revision instead, and the event's own existence is all that survives.
   */
  @Test
  void deleting_a_revision_takes_its_detail_with_it_rather_than_leaving_it_in_the_topic() {
    UUID revision = projectHomed();
    publish(revision, "derive", "ready", "private processing details");
    assertEquals(
        "private processing details",
        events.read(reader(AUTHOR, PROJECT), 0, 50).events().getFirst().detail());

    jdbc.update("DELETE FROM information_revisions WHERE id=?", revision);

    assertEquals(0, history(revision), "the history cascades with the revision");
    assertEquals(1, publications(projectId), "the publication is immutable and remains");
    var page = events.read(reader(AUTHOR, PROJECT), 0, 50);
    assertEquals(1, page.events().size(), "the event still occupied a position");
    assertEquals("", page.events().getFirst().detail(), "and its detail is no longer answerable");
  }

  /** A subscription row for this account exists on the topic, whatever its authority now says. */
  private boolean subscribed(String account) {
    return Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM relay_subscriptions WHERE scope_key=? AND topic=? AND reader_account=?",
                Integer.class,
                scope(projectId),
                topic(projectId),
                account))
        > 0;
  }

  @Test
  void a_wake_hint_reaches_a_currently_authorized_reader() {
    publish(projectHomed(), "intake", "retained", "");
    events.read(reader(VIEWER, PROJECT), 0, 50);
    hinted.clear();

    publishAndHint(projectHomed(), "ready");
    assertEquals(
        List.of(VIEWER),
        List.copyOf(hinted),
        "the capability has to survive the authority gate, not be removed by it");
  }

  @Test
  void an_account_that_never_subscribed_is_never_hinted() {
    publishAndHint(projectHomed(), "ready");
    assertEquals(
        List.of(),
        List.copyOf(hinted),
        "a hint names a revision, so an account with no established authority must not get one");
    assertEquals(
        List.of(), consumption.audience(new Relay.ProjectScope(projectId), topic(projectId)));
  }

  /**
   * A hint is a disclosure: it names a revision, its generation and a stream position. So it is
   * governed by the same invariant delivery is, and withheld by <b>every</b> route that invalidates
   * authority — not only the one revocation happens to take.
   *
   * <p>Each route is asserted together with the fact that makes it non-trivial: the {@code
   * relay_subscriptions} row survives in all three, so nothing here passes because the subscription
   * vanished. The audience query is checked alongside the push, because an empty push list could
   * also mean the fan-out never ran.
   */
  @Test
  void an_account_whose_authority_lapsed_is_not_hinted_by_any_invalidation_route() {
    var scope = new Relay.ProjectScope(projectId);

    // Route 1: membership withdrawn. The V187 trigger drops the authority record in the same
    // change; the subscription row is left exactly where it was.
    publish(projectHomed(), "intake", "retained", "");
    events.read(reader(VIEWER, PROJECT), 0, 50);
    assertEquals(List.of(VIEWER), consumption.audience(scope, topic(projectId)));
    jdbc.update("DELETE FROM project_members WHERE project_id=? AND handle=?", projectId, VIEWER);
    assertTrue(subscribed(VIEWER), "the subscription row must outlive its authority record");
    assertEquals(List.of(), consumption.audience(scope, topic(projectId)));
    hinted.clear();
    publishAndHint(projectHomed(), "ready");
    assertEquals(List.of(), List.copyOf(hinted), "a revoked account must stop being told");

    // Route 2: the topic's incarnation moved. The authority record survives and is stale.
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES(?,?,'VIEWER')",
        projectId,
        VIEWER);
    events.read(reader(VIEWER, PROJECT), 0, 50);
    assertEquals(List.of(VIEWER), consumption.audience(scope, topic(projectId)));
    authority.invalidateTopic(new Relay.TopicKey(projectId, topic(projectId)));
    assertTrue(subscribed(VIEWER));
    assertEquals(1, authorityRows(), "the record must still be there, and still be refused");
    assertEquals(List.of(), consumption.audience(scope, topic(projectId)));
    hinted.clear();
    publishAndHint(projectHomed(), "ready");
    assertEquals(
        List.of(), List.copyOf(hinted), "a reset topic must stop hinting to stale records");

    // Route 3: aged past the configured window. Nothing was revoked and nothing was reset; the
    // fallback bound alone must withhold the hint. A join against the authority table that
    // forgot the age would pass both routes above and fail here.
    events.read(reader(VIEWER, PROJECT), 0, 50);
    assertEquals(List.of(VIEWER), consumption.audience(scope, topic(projectId)));
    now = now.plus(WINDOW).plusSeconds(1);
    assertTrue(subscribed(VIEWER));
    assertEquals(1, authorityRows());
    assertEquals(List.of(), consumption.audience(scope, topic(projectId)));
    hinted.clear();
    publishAndHint(projectHomed(), "ready");
    assertEquals(
        List.of(), List.copyOf(hinted), "an aged-out subscription must stop hinting as well");
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
                  new RelayConsumption.Request(PROJECT, topic(projectId), "information.events"),
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
                new RelayConsumption.Request(PROJECT, topic(projectId), "information.events"),
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
