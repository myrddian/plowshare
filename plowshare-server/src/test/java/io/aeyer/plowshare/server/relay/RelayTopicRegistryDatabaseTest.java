package io.aeyer.plowshare.server.relay;

import static io.aeyer.plowshare.server.relay.RelayPayload.Kind.BLOB;
import static io.aeyer.plowshare.server.relay.RelayPayload.Kind.LIFECYCLE;
import static io.aeyer.plowshare.server.relay.RelayPayload.Kind.SCHEDULE_DUE;
import static io.aeyer.plowshare.server.relay.RelayPayload.Kind.TEXT;
import static io.aeyer.plowshare.server.relay.RelayPayload.Kind.WAKE_REQUESTED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import io.aeyer.plowshare.server.relay.RelayTopicOwnership.Owner;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A populated Flyway upgrade and real PostgreSQL registration, ownership and discovery fences. */
@Tag("full-db")
@Testcontainers
class RelayTopicRegistryDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Relay.Policy POLICY = Relay.Policy.systemDefault();
  private static final Relay.Scope SYSTEM = Relay.SystemScope.SERVER;
  private static final Map<Relay.TopicKey, Legacy> legacy = new LinkedHashMap<>();
  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;
  private static Relay relay;
  private static RelayTopicRegistry registry;
  private static Relay.ProjectScope project;

  @BeforeAll
  static void upgradePopulatedRegistry() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).target("288").load().migrate();
    jdbc = new JdbcTemplate(source);
    project = project("relay-legacy-topics");

    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('relay-owner','fixture')");
    var inbox = key(project, "messaging.agent.bound");
    jdbc.update(
        "INSERT INTO message_agent_inboxes(account,project_id,project,agent,topic) VALUES(?,?,?,?,?)",
        "relay-owner",
        project.projectId(),
        "relay-legacy-topics",
        "worker",
        inbox.name());
    legacy(inbox, TEXT, Owner.MESSAGING);
    legacy(key(project, "messaging.agent.unbound"), TEXT, Owner.UNCLASSIFIED);
    legacy(key(project, "data.legacy"), TEXT, Owner.UNCLASSIFIED);
    legacy(key(project, "board.message.posted"), LIFECYCLE, Owner.BOARD);
    legacy(key(project, "job.ended"), LIFECYCLE, Owner.JOBS);
    legacy(key(project, "approval.requested"), LIFECYCLE, Owner.APPROVALS);
    legacy(key(project, "orchestration.child.completed"), LIFECYCLE, Owner.ORCHESTRATIONS);
    legacy(key(project, "mailbox.item.added"), LIFECYCLE, Owner.PERSONAL);
    legacy(key(project, "application.inbox.item.added"), LIFECYCLE, Owner.APPLICATIONS);
    legacy(key(SYSTEM, "message.instance.changed"), LIFECYCLE, Owner.MESSAGING);
    legacy(key(SYSTEM, "message.wake.requested"), WAKE_REQUESTED, Owner.RELAY);
    legacy(key(SYSTEM, "board.wake.requested"), WAKE_REQUESTED, Owner.RELAY);
    legacy(key(SYSTEM, "server.custom"), TEXT, Owner.UNCLASSIFIED);
    var eventTopic = key(SYSTEM, "native-event-registration");
    legacy(eventTopic, TEXT, Owner.EVENTS);
    jdbc.update(
        "INSERT INTO relay_subscriptions(scope_key,topic,subscriber,seen_through,seen_at)"
            + " VALUES('system',?,'builtin.events',0,now())",
        eventTopic.name());
    legacy(key(SYSTEM, schedule(SYSTEM)), SCHEDULE_DUE, Owner.EVENTS);
    legacy(key(project, schedule(project)), SCHEDULE_DUE, Owner.EVENTS);
    legacy(
        key(
            project,
            "information.events."
                + RelayPort.hash(
                    "plowshare.information.events/1:" + RelayScopeCodec.write(project))),
        TEXT,
        Owner.INFORMATION);
    legacy(key(project, "information.events.lookalike"), TEXT, Owner.UNCLASSIFIED);

    Flyway.configure().dataSource(source).load().migrate();
    var template = new TransactionTemplate(new DataSourceTransactionManager(source));
    transactions =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> action) {
            return Objects.requireNonNull(template.execute(status -> action.get()));
          }
        };
    var payloads =
        new JdbcRelayPayloadStore(
            jdbc, transactions, Duration.ofHours(1), Duration.ofHours(24), Duration.ofDays(30));
    relay = new DurableRelay(new JdbcRelayRepository(jdbc, transactions, payloads), Instant::now);
    registry =
        new JdbcRelayTopicRegistry(
            jdbc,
            new SqlWorkBudget() {
              @Override
              public <T> T within(Duration budget, Supplier<T> action) {
                return action.get();
              }
            },
            new SqlWorkBudgetProperties(
                Duration.ofSeconds(2), Duration.ofMillis(500), 16, 4, 16, 2));
  }

  @Test
  void backfills_exact_owners_without_moving_generations_or_exposing_legacy_feeds() {
    legacy.forEach(
        (key, expected) -> {
          var registered = registry.find(key).orElseThrow();
          assertEquals(expected.generation(), registered.generation(), key.name());
          assertEquals(expected.owner(), registered.ownership().owner(), key.name());
          assertTrue(registry.eligible(key, expected.generation()).isEmpty(), key.name());
        });
    assertEquals(List.of(), registry.eligible(project, null, null, null, 100));
    assertEquals(List.of(), registry.eligible(SYSTEM, null, null, null, 100));
  }

  @Test
  void filters_before_paging_and_checks_exact_generation_for_direct_selection() {
    var scope = project("relay-eligible-topics");
    var outside = project("relay-other-source-scope");
    var general = key(scope, "news_feed.general");
    var privateFeed = key(scope, "news_feed.a-private");
    var openFeed = key(scope, "news_feed.public");
    var blob = key(scope, "news_feed.blob");
    var lookalike = key(scope, "news-feed.general");
    var open = new RelayTopicOwnership(Owner.BOARD, true);
    var ordinary = relay.registerTopic(general, TEXT, POLICY);
    var hidden =
        relay.registerOwnedTopic(
            privateFeed, TEXT, POLICY, RelayTopicOwnership.internal(Owner.MESSAGING));
    var exposed = relay.registerOwnedTopic(openFeed, LIFECYCLE, POLICY, open);
    relay.registerTopic(blob, BLOB, POLICY);
    relay.registerTopic(lookalike, TEXT, POLICY);
    relay.registerTopic(key(outside, general.name()), TEXT, POLICY);

    assertEquals(
        Owner.GENERAL_PURPOSE,
        registry.eligible(general, ordinary.generation()).orElseThrow().ownership().owner());
    assertEquals(open, registry.eligible(openFeed, exposed.generation()).orElseThrow().ownership());
    assertTrue(registry.eligible(privateFeed, hidden.generation()).isEmpty());
    assertTrue(registry.eligible(key(outside, general.name()), ordinary.generation()).isEmpty());
    assertEquals(List.of(general), keys(registry.eligible(scope, "news_feed.", TEXT, null, 1)));
    assertEquals(
        List.of(blob, general), keys(registry.eligible(scope, "news_feed.", null, null, 2)));
    assertEquals(
        List.of(openFeed), keys(registry.eligible(scope, "news_feed.", null, general.name(), 1)));
    assertEquals(List.of(openFeed), keys(registry.eligible(scope, null, LIFECYCLE, null, 100)));
    assertThrows(
        IllegalArgumentException.class, () -> registry.eligible(scope, "news%", null, null, 1));
    assertThrows(
        IllegalArgumentException.class, () -> registry.eligible(scope, null, null, null, 101));
  }

  @Test
  void claims_are_atomic_and_allow_neither_conflicts_nor_exposure_of_historical_data() {
    var scope = project("relay-owning-claims");
    var key = key(scope, "library.feed");
    var owner = RelayTopicOwnership.internal(Owner.INFORMATION);
    var created = relay.registerOwnedTopic(key, TEXT, POLICY, owner);
    assertEquals(
        created,
        relay.registerOwnedTopic(key, TEXT, new Relay.Policy(Duration.ofDays(10), 5L), owner));
    assertEquals(created, relay.registerTopic(key, TEXT, POLICY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            relay.registerOwnedTopic(
                key, TEXT, POLICY, RelayTopicOwnership.internal(Owner.MESSAGING)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            relay.registerOwnedTopic(
                key, TEXT, POLICY, new RelayTopicOwnership(Owner.INFORMATION, true)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            relay.registerOwnedTopic(
                key(SYSTEM, "board.wake.requested"),
                WAKE_REQUESTED,
                POLICY,
                new RelayTopicOwnership(Owner.BOARD, true)));

    var old = key(scope, "historic.feed");
    var before = rawTopic(old, TEXT);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            relay.registerOwnedTopic(
                old, TEXT, POLICY, new RelayTopicOwnership(Owner.INFORMATION, true)));
    assertEquals(before, relay.registerOwnedTopic(old, TEXT, POLICY, owner).generation());
    assertTrue(registry.eligible(old, before).isEmpty());

    var rollback = key(scope, "rollback.feed");
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.inTransaction(
                () -> {
                  relay.registerOwnedTopic(rollback, TEXT, POLICY, owner);
                  throw new IllegalStateException("owning registration rolled back");
                }));
    assertTrue(registry.find(rollback).isEmpty());
  }

  @Test
  void racing_subsystems_choose_one_owner_and_recreating_the_name_gets_a_fresh_classification()
      throws Exception {
    var scope = project("relay-owner-race");
    var topic = key(scope, "updates");
    var start = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> claim(start, topic, Owner.BOARD));
      var second = executor.submit(() -> claim(start, topic, Owner.INFORMATION));
      assertEquals(
          1,
          List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)).stream()
              .filter(Boolean::booleanValue)
              .count());
    }
    var previous = registry.find(topic).orElseThrow();
    assertEquals(
        1,
        jdbc.update(
            "DELETE FROM relay_topics WHERE scope_key=? AND name=?",
            RelayScopeCodec.write(scope),
            topic.name()));
    assertTrue(registry.find(topic).isEmpty());
    var replacement =
        relay.registerOwnedTopic(topic, TEXT, POLICY, new RelayTopicOwnership(Owner.BOARD, true));
    assertNotEquals(previous.generation(), replacement.generation());
    assertTrue(registry.eligible(topic, previous.generation()).isEmpty());
    assertTrue(registry.eligible(topic, replacement.generation()).isPresent());
  }

  @Test
  void selected_generation_is_locked_until_the_binding_transaction_finishes() throws Exception {
    var scope = project("relay-binding-lock");
    var topic = key(scope, "updates");
    var registered = relay.registerTopic(topic, TEXT, POLICY);
    assertThrows(
        IllegalStateException.class, () -> registry.lockEligible(topic, registered.generation()));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      transactions.inTransaction(
          () -> {
            assertTrue(registry.lockEligible(topic, registered.generation()).isPresent());
            var rotated =
                executor.submit(
                    () -> {
                      var blocked =
                          assertThrows(
                              DataAccessException.class,
                              () ->
                                  transactions.inTransaction(
                                      () -> {
                                        jdbc.execute("SET LOCAL lock_timeout = '250ms'");
                                        return jdbc.update(
                                            "UPDATE relay_topics SET generation=gen_random_uuid() WHERE scope_key=? AND name=?",
                                            RelayScopeCodec.write(scope),
                                            topic.name());
                                      }));
                      var sql = blocked.getMostSpecificCause();
                      assertTrue(sql instanceof SQLException);
                      assertEquals("55P03", ((SQLException) sql).getSQLState());
                      return true;
                    });
            assertEquals(true, assertDoesNotThrow(() -> rotated.get(5, TimeUnit.SECONDS)));
            return relay.subscribe(
                new Relay.SubscriptionKey(topic, "goal.binding"), Relay.Start.LATEST);
          });
    }
    jdbc.update(
        "UPDATE relay_topics SET generation=gen_random_uuid() WHERE scope_key=? AND name=?",
        RelayScopeCodec.write(scope),
        topic.name());
    assertTrue(registry.eligible(topic, registered.generation()).isEmpty());
  }

  private static boolean claim(CyclicBarrier start, Relay.TopicKey topic, Owner owner)
      throws Exception {
    start.await(5, TimeUnit.SECONDS);
    try {
      relay.registerOwnedTopic(topic, TEXT, POLICY, RelayTopicOwnership.internal(owner));
      return true;
    } catch (IllegalArgumentException conflict) {
      assertEquals(
          "Relay topic already has a different ownership declaration", conflict.getMessage());
      return false;
    }
  }

  private static String schedule(Relay.Scope scope) {
    return "schedule.occurrence."
        + RelayPort.hash("plowshare.schedule.occurrence/1:" + RelayScopeCodec.write(scope));
  }

  private static Relay.ProjectScope project(String name) {
    return new Relay.ProjectScope(
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES(?,'fixture') RETURNING id",
                Long.class,
                name)));
  }

  private static Relay.TopicKey key(Relay.Scope scope, String name) {
    return new Relay.TopicKey(scope, name);
  }

  private static List<Relay.TopicKey> keys(List<RelayTopicRegistry.Source> sources) {
    return sources.stream().map(RelayTopicRegistry.Source::key).toList();
  }

  private static void legacy(Relay.TopicKey key, RelayPayload.Kind kind, Owner owner) {
    legacy.put(key, new Legacy(rawTopic(key, kind), owner));
  }

  private static UUID rawTopic(Relay.TopicKey key, RelayPayload.Kind kind) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "INSERT INTO relay_topics(scope_key,project_id,name,payload_kind,retention_seconds)"
                + " VALUES(?,?,?,?,?) RETURNING generation",
            UUID.class,
            RelayScopeCodec.write(key),
            RelayScopeCodec.project(key),
            key.name(),
            kind.name(),
            POLICY.retention().getSeconds()));
  }

  private record Legacy(UUID generation, Owner owner) {}
}
