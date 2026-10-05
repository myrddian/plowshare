package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.orchestrations.OrchestrationMessage.Kind;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore.NewOrchestration;
import io.aeyer.plowshare.server.todos.StageRules;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
 * {@code orchestrations} and {@code orchestration_messages}, V48 — driven against a real Postgres
 * so that every compare-and-set is proved against the database's own row lock and not against a
 * mock that cannot race.
 */
@Tag("full-db")
@Testcontainers
class OrchestrationStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant T0 = Instant.parse("2026-09-15T09:00:00Z");

  private static final List<StageRules.Stage> STAGES =
      List.of(
          new StageRules.Stage("goal", List.of()), new StageRules.Stage("review", List.of("goal")));

  private static final ObjectMapper JSON = new ObjectMapper();

  private static JdbcTemplate jdbc;
  private static UnitOfWork unitOfWork;

  private final AtomicReference<Instant> clock = new AtomicReference<>(T0);

  private OrchestrationStore store;
  private ConversationStore conversations;
  private EntryStore entries;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    jdbc = new JdbcTemplate(ds);
    // ONE shared DataSource under both the JdbcTemplate and the transaction manager, as
    // TodoBoardTest builds its UnitOfWork: a transaction opened on a different DataSource
    // would not be the transaction this store's statements run inside.
    TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(ds));
    unitOfWork =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> body) {
            return template.execute(status -> body.get());
          }
        };
  }

  @BeforeEach
  void fresh() {
    jdbc.execute(
        "TRUNCATE TABLE orchestration_messages, orchestrations, user_inbox, entries,"
            + " turns, conversations, admins, orchestration_concerns CASCADE");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
    clock.set(T0);
    store = new OrchestrationStore(jdbc, clock::get, unitOfWork);
    conversations = new ConversationStore(jdbc, clock::get, null);
    entries = new EntryStore(jdbc, clock::get);
  }

  @Test
  void resume_receipt_preserves_progress_and_never_replays_after_another_failure() {
    var run = store.insert(newOrchestration(conductorConversation(), 2));
    store.restarted(run.id());
    store.nudged(run.id());
    store.stop(run.id(), OrchestrationState.FAILED, "temporary outage");
    var recovery = new JdbcOrchestrationRecovery(jdbc, unitOfWork);
    var key = java.util.UUID.randomUUID();
    assertTrue(recovery.claim(run.id(), "enzo", key, T0));
    var resumed = store.find(run.id()).orElseThrow();
    assertEquals(OrchestrationState.RUNNING, resumed.state());
    assertEquals(run.definitionSource(), resumed.definitionSource());
    assertEquals(run.conductorConversation(), resumed.conductorConversation());
    assertEquals(run.returnsUsed(), resumed.returnsUsed());
    assertNull(resumed.failure());
    assertNull(resumed.endedAt());
    assertEquals(0, resumed.nudges());
    assertFalse(store.resultDelivered(run.id(), T0));
    clock.set(T0.plusSeconds(1));
    assertFalse(new JdbcOrchestrationRecovery(jdbc, unitOfWork).claim(run.id(), "enzo", key, T0));
    store.stop(run.id(), OrchestrationState.FAILED, "second outage");
    assertFalse(recovery.claim(run.id(), "enzo", key, T0));
    assertEquals(OrchestrationState.FAILED, store.find(run.id()).orElseThrow().state());
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> recovery.claim(run.id(), "enzo", java.util.UUID.randomUUID(), T0));
    assertFalse(store.resultDelivered(run.id(), T0));
    assertTrue(
        recovery.claim(
            run.id(),
            "enzo",
            java.util.UUID.randomUUID(),
            store.find(run.id()).orElseThrow().endedAt()));
    var other = store.insert(newOrchestration(conductorConversation(), 2));
    store.stop(other.id(), OrchestrationState.FAILED, "outage");
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () ->
            recovery.claim(
                other.id(), "enzo", key, store.find(other.id()).orElseThrow().endedAt()));
  }

  @Test
  void competing_resume_keys_claim_the_failure_once_and_refuse_foreign_owner() throws Exception {
    var run = store.insert(newOrchestration(conductorConversation(), 2));
    store.stop(run.id(), OrchestrationState.FAILED, "temporary outage");
    var recovery = new JdbcOrchestrationRecovery(jdbc, unitOfWork);
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () ->
            recovery.claim(
                run.id(),
                "other",
                java.util.UUID.randomUUID(),
                store.find(run.id()).orElseThrow().endedAt()));
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  n ->
                      pool.submit(
                          () -> {
                            start.await();
                            try {
                              return recovery.claim(
                                  run.id(), "enzo", java.util.UUID.randomUUID(), T0);
                            } catch (io.aeyer.plowshare.server.faults.CallerFault refused) {
                              return false;
                            }
                          }))
              .toList();
      start.countDown();
      int claimed = 0;
      for (var task : tasks) if (task.get(10, java.util.concurrent.TimeUnit.SECONDS)) claimed++;
      assertEquals(1, claimed);
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM orchestration_resume_receipts WHERE run_id=?",
            Integer.class,
            run.id()));
  }

  @Test
  void lost_start_acknowledgment_recovers_one_durable_run_and_refuses_changed_payload() {
    var key = java.util.UUID.randomUUID();
    var effects = new java.util.concurrent.atomic.AtomicInteger();
    Supplier<OrchestrationRecord> insert =
        () -> {
          effects.incrementAndGet();
          return store.insert(newOrchestration(conductorConversation(), 2));
        };
    var first =
        unitOfWork.inTransaction(
            () ->
                store.receiveStart(
                    "enzo", key, "{\"definition\":\"custom\",\"request\":\"research\"}", insert));
    // Discard the acknowledgment and recover through a new store (no process-local state).
    var reconnected = new OrchestrationStore(jdbc, clock::get, unitOfWork);
    assertEquals(first.id(), reconnected.startReceipt("enzo", key).orElseThrow().id());
    var repeated =
        unitOfWork.inTransaction(
            () ->
                reconnected.receiveStart(
                    "enzo", key, "{\"request\":\"research\",\"definition\":\"custom\"}", insert));
    assertFalse(repeated.created());
    assertEquals(first.id(), repeated.id());
    assertEquals(1, effects.get());
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> unitOfWork.inTransaction(() -> reconnected.receiveStart("enzo", key, "{}", insert)));
    assertTrue(reconnected.startReceipt("other", key).isEmpty());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM orchestrations", Integer.class));
  }

  @Test
  void receipt_and_run_roll_back_together_and_concurrent_starts_insert_once() throws Exception {
    var key = java.util.UUID.randomUUID();
    assertThrows(
        IllegalStateException.class,
        () ->
            unitOfWork.inTransaction(
                () -> {
                  store.receiveStart(
                      "enzo",
                      key,
                      "{}",
                      () -> store.insert(newOrchestration(conductorConversation(), 2)));
                  throw new IllegalStateException("simulate failure before commit");
                }));
    assertTrue(store.startReceipt("enzo", key).isEmpty());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM orchestrations", Integer.class));
    var effects = new java.util.concurrent.atomic.AtomicInteger();
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var ready = new java.util.concurrent.CountDownLatch(2);
      java.util.concurrent.Callable<OrchestrationStore.StartReceipt> submit =
          () -> {
            ready.countDown();
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return unitOfWork.inTransaction(
                () ->
                    store.receiveStart(
                        "enzo",
                        key,
                        "{}",
                        () -> {
                          effects.incrementAndGet();
                          return store.insert(newOrchestration(conductorConversation(), 2));
                        }));
          };
      var a = workers.submit(submit);
      var b = workers.submit(submit);
      assertEquals(
          a.get(10, java.util.concurrent.TimeUnit.SECONDS).id(),
          b.get(10, java.util.concurrent.TimeUnit.SECONDS).id());
      assertEquals(1, effects.get());
    }
  }

  /** A fresh conductor conversation, the way the harness opens one for a run — spec §5. */
  private String conductorConversation() {
    return conversations
        .log(Origin.ORCHESTRATION, Home.of("story"), "code_implementation", null, Budget.of(40))
        .id();
  }

  private NewOrchestration newOrchestration(String conductorConversation, int maxReturns) {
    return newOrchestration(
        conductorConversation, "code_implementation", "story", null, "enzo", maxReturns);
  }

  /**
   * {@link #newOrchestration(String, int)}'s own shape, with the fields a caller-lookup test needs
   * to vary broken out. A root run: no parent, depth 0.
   */
  private NewOrchestration newOrchestration(
      String conductorConversation,
      String definitionName,
      String project,
      String callerConversation,
      String callerHandle,
      int maxReturns) {
    return newOrchestration(
        conductorConversation,
        definitionName,
        project,
        callerConversation,
        callerHandle,
        maxReturns,
        null,
        0);
  }

  /**
   * {@link #newOrchestration(String, String, String, String, String, int)}'s own shape, with {@code
   * parent} and {@code depth} broken out for a nested run — {@code parent} must already be a real
   * row, since V52 makes it a foreign key.
   */
  private NewOrchestration newOrchestration(
      String conductorConversation,
      String definitionName,
      String project,
      String callerConversation,
      String callerHandle,
      int maxReturns,
      String parent,
      int depth) {
    return new NewOrchestration(
        definitionName,
        Tier.PROJECT,
        "sha256:9f86d081884c7d659a2feaa0c55ad015",
        "---\nname: " + definitionName + "\n---\nbody",
        "project",
        STAGES,
        maxReturns,
        project,
        conductorConversation,
        callerConversation,
        definitionName,
        callerHandle,
        "s-laptop",
        parent,
        depth);
  }

  /**
   * {@link #newOrchestration(String, int)}'s own shape, but with {@code stages} broken out instead
   * of fixed to {@link #STAGES} — a test proving that a checked stage round-trips needs to insert a
   * run with stages of its own choosing, not the module's shared fixture.
   */
  private OrchestrationRecord insertWith(List<StageRules.Stage> stages) {
    String conductor = conductorConversation();
    return store.insert(
        new NewOrchestration(
            "code_implementation",
            Tier.PROJECT,
            "sha256:9f86d081884c7d659a2feaa0c55ad015",
            "---\nname: code_implementation\n---\nbody",
            "project",
            stages,
            2,
            "story",
            conductor,
            null,
            "code_implementation",
            "enzo",
            "s-laptop",
            null,
            0));
  }

  // --- starting a run ---------------------------------------------------------------------

  @Test
  void a_run_starts_running_with_its_stages_pinned() {
    String conductor = conductorConversation();
    NewOrchestration n = newOrchestration(conductor, 2);

    OrchestrationRecord written = store.insert(n);
    OrchestrationRecord found = store.find(written.id()).orElseThrow();

    assertEquals(written.id(), found.id());
    assertTrue(found.id().startsWith(OrchestrationStore.PREFIX));
    assertEquals("code_implementation", found.definitionName());
    assertEquals(Tier.PROJECT, found.tier());
    assertEquals("sha256:9f86d081884c7d659a2feaa0c55ad015", found.definitionHash());
    assertEquals(STAGES, found.stages());
    assertEquals(2, found.maxReturns());
    assertEquals(0, found.returnsUsed());
    assertEquals("story", found.project());
    assertEquals(conductor, found.conductorConversation());
    assertNull(found.callerConversation());
    assertEquals("code_implementation", found.callerAgent());
    assertEquals("enzo", found.callerHandle());
    assertEquals(OrchestrationState.RUNNING, found.state());
    assertNull(found.result());
    assertNull(found.failure());
    assertEquals(0, found.restarts());
    assertEquals(0, found.nudges());
    assertNull(found.resultDeliveredAt());
    assertEquals(T0, found.createdAt());
    assertNull(found.endedAt());
  }

  @Test
  void a_checked_stage_survives_the_row() {
    List<StageRules.Stage> stages =
        List.of(
            new StageRules.Stage("test_design", List.of()),
            new StageRules.Stage("code", List.of(), true));
    OrchestrationRecord run = insertWith(stages);

    assertEquals(
        List.of(false, true),
        store.find(run.id()).orElseThrow().stages().stream()
            .map(StageRules.Stage::checked)
            .toList());
  }

  @Test
  void an_acceptance_key_survives_the_row_and_absent_reads_null() {
    List<StageRules.Stage> stages =
        List.of(
            new StageRules.Stage("spec", List.of(), false, "written"),
            new StageRules.Stage("acceptance", List.of(), false, "required"));
    OrchestrationRecord run = insertWith(stages);

    assertEquals(
        List.of("written", "required"),
        store.find(run.id()).orElseThrow().stages().stream()
            .map(StageRules.Stage::acceptance)
            .toList());
    assertNull(store.find(insertWith(STAGES).id()).orElseThrow().stages().get(0).acceptance());
  }

  @Test
  void a_stage_that_holds_phases_survives_the_row_and_absent_reads_false() {
    List<StageRules.Stage> stages =
        List.of(
            new StageRules.Stage("plan", List.of()),
            new StageRules.Stage("phases", List.of(), false, null, true));
    OrchestrationRecord run = insertWith(stages);

    assertEquals(
        List.of(false, true),
        store.find(run.id()).orElseThrow().stages().stream()
            .map(StageRules.Stage::holdsPhases)
            .toList());
  }

  @Test
  void the_pinned_source_origin_and_session_round_trip() {
    String conductor = conductorConversation();
    NewOrchestration n = newOrchestration(conductor, 2);

    OrchestrationRecord written = store.insert(n);
    OrchestrationRecord found = store.find(written.id()).orElseThrow();

    assertEquals(n.definitionSource(), written.definitionSource());
    assertEquals(n.definitionSource(), found.definitionSource());
    assertEquals(n.definitionOrigin(), written.definitionOrigin());
    assertEquals(n.definitionOrigin(), found.definitionOrigin());
    assertEquals(n.callerSession(), written.callerSession());
    assertEquals(n.callerSession(), found.callerSession());
    assertNull(found.pendingCap());
  }

  @Test
  void a_run_is_found_by_its_conductor_conversation() {
    String conductor = conductorConversation();
    OrchestrationRecord written = store.insert(newOrchestration(conductor, 2));

    OrchestrationRecord found = store.byConductorConversation(conductor).orElseThrow();

    assertEquals(written.id(), found.id());
  }

  // --- the two live states ----------------------------------------------------------------

  @Test
  void moving_between_running_and_asking_is_compare_and_set() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();

    assertTrue(
        store.moveTo(id, OrchestrationState.RUNNING, OrchestrationState.ASKING),
        "the first move wins");
    assertFalse(
        store.moveTo(id, OrchestrationState.RUNNING, OrchestrationState.ASKING),
        "the row is already ASKING, not RUNNING, so a second identical call matches" + " nothing");
  }

  // --- V52: nesting ------------------------------------------------------------------------

  @Test
  void a_child_records_its_parent_and_its_depth() {
    String parent = store.insert(newOrchestration(conductorConversation(), 2)).id();

    OrchestrationRecord child =
        store.insert(
            newOrchestration(
                conductorConversation(), "deep_research", "story", null, "enzo", 2, parent, 1));

    assertEquals(parent, child.parent());
    assertEquals(1, child.depth());
    OrchestrationRecord found = store.find(child.id()).orElseThrow();
    assertEquals(parent, found.parent());
    assertEquals(1, found.depth());
  }

  @Test
  void a_root_run_has_no_parent_and_is_at_depth_zero() {
    OrchestrationRecord root = store.insert(newOrchestration(conductorConversation(), 2));

    assertNull(root.parent());
    assertEquals(0, root.depth());
    assertNull(root.waitingFor());

    OrchestrationRecord found = store.find(root.id()).orElseThrow();
    assertNull(found.parent());
    assertEquals(0, found.depth());
    assertNull(found.waitingFor());
  }

  @Test
  void a_run_waits_for_a_child_and_wakes_again() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    String child =
        store
            .insert(
                newOrchestration(
                    conductorConversation(), "deep_research", "story", null, "enzo", 2, id, 1))
            .id();

    assertTrue(store.waitFor(id, child), "the first wait wins");
    OrchestrationRecord waiting = store.find(id).orElseThrow();
    assertEquals(OrchestrationState.WAITING, waiting.state());
    assertEquals(child, waiting.waitingFor());

    assertFalse(
        store.waitFor(id, child),
        "the row is WAITING, not RUNNING, so a second waitFor matches nothing");

    assertTrue(store.wake(id), "the first wake wins");
    OrchestrationRecord running = store.find(id).orElseThrow();
    assertEquals(OrchestrationState.RUNNING, running.state());
    assertNull(running.waitingFor());

    assertFalse(
        store.wake(id), "the row is RUNNING, not WAITING, so a second wake matches nothing");
  }

  @Test
  void a_waiting_run_can_be_stopped() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    String child =
        store
            .insert(
                newOrchestration(
                    conductorConversation(), "deep_research", "story", null, "enzo", 2, id, 1))
            .id();
    store.waitFor(id, child);

    assertTrue(store.stop(id, OrchestrationState.CANCELLED, "cancelled with its parent orc_1"));

    OrchestrationRecord after = store.find(id).orElseThrow();
    assertEquals(OrchestrationState.CANCELLED, after.state());
    assertEquals("cancelled with its parent orc_1", after.failure());
    assertNotNull(after.endedAt());
    assertNull(
        after.waitingFor(),
        "stopping a waiting row clears waiting_for along with"
            + " everything else, so a terminal row never still names a child to wait for");
  }

  @Test
  void a_parents_children_come_back_newest_first_and_live_ones_separately() {
    String parent = store.insert(newOrchestration(conductorConversation(), 2)).id();

    clock.set(T0);
    String first =
        store
            .insert(
                newOrchestration(
                    conductorConversation(),
                    "code_implementation",
                    "story",
                    null,
                    "enzo",
                    2,
                    parent,
                    1))
            .id();
    clock.set(T0.plus(1, ChronoUnit.MINUTES));
    String second =
        store
            .insert(
                newOrchestration(
                    conductorConversation(), "deep_research", "story", null, "enzo", 2, parent, 1))
            .id();
    store.finish(second, "done");
    clock.set(T0.plus(2, ChronoUnit.MINUTES));
    String third =
        store
            .insert(
                newOrchestration(
                    conductorConversation(), "review", "story", null, "enzo", 2, parent, 1))
            .id();

    assertEquals(
        List.of(third, second, first),
        store.children(parent).stream().map(OrchestrationRecord::id).toList(),
        "every child, newest first, live or ended");
    assertEquals(
        List.of(third, first),
        store.liveChildren(parent).stream().map(OrchestrationRecord::id).toList(),
        "only the ones that have not ended, still newest first");
  }

  @Test
  void a_row_that_waits_for_nothing_is_refused_by_the_database() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> jdbc.update("UPDATE orchestrations SET state = 'waiting' WHERE id = ?", id));

    assertTrue(
        thrown.getMessage().contains("orchestrations_waiting_for_iff_waiting"),
        "the database itself refuses a waiting row with nothing to wait for: "
            + thrown.getMessage());
  }

  @Test
  void a_row_cannot_be_its_own_parent() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                jdbc.update(
                    "UPDATE orchestrations SET parent = ?, depth = 1 WHERE id = ?", id, id));

    assertTrue(
        thrown.getMessage().contains("orchestrations_parent_is_not_itself"),
        "the database itself refuses a row naming itself as its own parent: "
            + thrown.getMessage());
  }

  @Test
  void a_negative_depth_is_refused_by_the_database() {
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                store.insert(
                    newOrchestration(
                        conductorConversation(),
                        "code_implementation",
                        "story",
                        null,
                        "enzo",
                        2,
                        null,
                        -1)));

    assertTrue(
        thrown.getMessage().contains("orchestrations_depth_natural"),
        "the database itself refuses a negative depth: " + thrown.getMessage());
  }

  @Test
  void a_root_row_must_be_at_depth_zero() {
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                store.insert(
                    newOrchestration(
                        conductorConversation(),
                        "code_implementation",
                        "story",
                        null,
                        "enzo",
                        2,
                        null,
                        1)));

    assertTrue(
        thrown.getMessage().contains("orchestrations_parent_iff_nested"),
        "the database itself refuses a depth of 1 with no parent: " + thrown.getMessage());
  }

  // --- ending a run -------------------------------------------------------------------------

  @Test
  void finishing_records_the_result_and_the_end_and_happens_once() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();

    assertTrue(store.finish(id, "twelve files touched, all green"));
    OrchestrationRecord after = store.find(id).orElseThrow();
    assertEquals(OrchestrationState.FINISHED, after.state());
    assertEquals("twelve files touched, all green", after.result());
    assertNotNull(after.endedAt());

    assertFalse(
        store.finish(id, "a second result"),
        "the row is no longer RUNNING, so a second finish matches nothing");
    assertFalse(
        store.stop(id, OrchestrationState.CANCELLED, "too late"),
        "a run that has already finished cannot also be stopped");
  }

  @Test
  void stopping_records_why_and_is_refused_once_ended() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.moveTo(id, OrchestrationState.RUNNING, OrchestrationState.ASKING);

    assertTrue(store.stop(id, OrchestrationState.CANCELLED, "cancelled by enzo"));
    OrchestrationRecord after = store.find(id).orElseThrow();
    assertEquals(OrchestrationState.CANCELLED, after.state());
    assertEquals("cancelled by enzo", after.failure());
    assertNotNull(after.endedAt());

    assertFalse(
        store.stop(id, OrchestrationState.FAILED, "a second reason"),
        "the row has already ended, so a second stop matches nothing");
  }

  /**
   * V49: stopping a run that was asking about a cap clears {@code pending_cap} along with
   * everything else {@link #stopping_records_why_and_is_refused_once_ended} already covers, so
   * {@code orchestrations_a_cap_is_pending_only_while_asking} never meets a terminal row that still
   * names one.
   */
  @Test
  void stopping_clears_a_pending_cap() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.askCap(id, "turn_cap", "raise the turn cap?");

    assertTrue(store.stop(id, OrchestrationState.CAPPED, "capped: no answer"));

    OrchestrationRecord after = store.find(id).orElseThrow();
    assertEquals(OrchestrationState.CAPPED, after.state());
    assertNull(after.pendingCap());
  }

  // --- Decision 6: the return count -----------------------------------------------------

  @Test
  void returns_count_only_while_running_and_under_the_limit() {
    String conductor = conductorConversation();
    store.insert(newOrchestration(conductor, 2));

    assertTrue(store.countReturn(conductor), "the first return, under the limit of two");
    assertTrue(store.countReturn(conductor), "the second return, exactly at the limit");
    assertFalse(
        store.countReturn(conductor),
        "returns_used already equals max_returns, so a third is refused");

    String freshConductor = conductorConversation();
    String freshId = store.insert(newOrchestration(freshConductor, 2)).id();
    store.moveTo(freshId, OrchestrationState.RUNNING, OrchestrationState.ASKING);

    assertFalse(
        store.countReturn(freshConductor),
        "well under its limit, but not RUNNING, so it may not count a return");
  }

  // --- questions and answers -----------------------------------------------------------

  @Test
  void an_answer_wins_once_and_only_while_asking() {
    String conductor = conductorConversation();
    String id = store.insert(newOrchestration(conductor, 2)).id();
    store.addMessage(
        id, Kind.QUESTION, "should the retry back off exponentially?", "code_implementation");
    store.moveTo(id, OrchestrationState.RUNNING, OrchestrationState.ASKING);

    assertTrue(store.answer(id, "yes, capped at five attempts", "enzo"));
    assertFalse(
        store.answer(id, "no, retry immediately", "enzo"),
        "the run is RUNNING again, so a second answer matches nothing");

    List<OrchestrationMessage> messages = store.messages(id);
    assertEquals(1, messages.stream().filter(m -> m.kind() == Kind.QUESTION).count());
    assertEquals(1, messages.stream().filter(m -> m.kind() == Kind.ANSWER).count());
    assertEquals(OrchestrationState.RUNNING, store.find(id).orElseThrow().state());
  }

  @Test
  void answering_marks_the_open_question_delivered() {
    String conductor = conductorConversation();
    String id = store.insert(newOrchestration(conductor, 2)).id();
    store.addMessage(id, Kind.QUESTION, "which database?", "code_implementation");
    store.moveTo(id, OrchestrationState.RUNNING, OrchestrationState.ASKING);
    assertEquals(1, store.undeliveredMessages().size(), "the question waits to be delivered");

    assertTrue(store.answer(id, "postgres", "enzo"));

    OrchestrationMessage question =
        store.messages(id).stream()
            .filter(m -> m.kind() == Kind.QUESTION)
            .findFirst()
            .orElseThrow();
    assertNotNull(question.deliveredAt(), "an answered question is not delivered again");
    assertTrue(store.undeliveredMessages().stream().noneMatch(m -> m.kind() == Kind.QUESTION));
    assertNull(
        store.messages(id).stream()
            .filter(m -> m.kind() == Kind.ANSWER)
            .findFirst()
            .orElseThrow()
            .deliveredAt(),
        "the answer itself still waits for the conductor");
  }

  /** V49 / Decision 7: a cap is a question to the caller, not an ending. */
  @Test
  void
      asking_about_a_cap_sets_pending_cap_and_answering_it_records_the_kind_on_the_answer_and_clears_the_row() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();

    OrchestrationMessage question =
        store.askCap(id, "turn_cap", "raise the turn cap?").orElseThrow();

    assertEquals(Kind.QUESTION, question.kind());
    assertEquals("harness", question.author());
    assertEquals(OrchestrationState.ASKING, store.find(id).orElseThrow().state());
    assertEquals("turn_cap", store.find(id).orElseThrow().pendingCap());

    assertTrue(
        store.askCap(id, "call_budget", "second question").isEmpty(),
        "the run is already ASKING, so a second askCap matches nothing");

    assertTrue(store.answer(id, "yes", "enzo"));

    OrchestrationRecord after = store.find(id).orElseThrow();
    assertEquals(OrchestrationState.RUNNING, after.state());
    assertNull(after.pendingCap(), "the answer clears the row's pending cap");

    OrchestrationMessage answer =
        store.messages(id).stream().filter(m -> m.kind() == Kind.ANSWER).findFirst().orElseThrow();
    assertEquals(
        "turn_cap",
        answer.capKind(),
        "the answer carries the kind of cap it answered, copied before the row's own"
            + " copy was cleared");
  }

  /**
   * V58: {@code stuck} is a harness question only the person answers (spec 2026-09-28). A model's
   * answer is refused inside the answer's own lock, so a run that went stuck after the model last
   * read it is still refused; the person's lands and carries the kind as a cap's does.
   */
  @Test
  void a_stuck_question_is_refused_to_a_model_and_answered_by_the_person() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.askCap(id, "stuck", "go on?").orElseThrow();
    assertEquals("stuck", store.find(id).orElseThrow().pendingCap());

    assertFalse(store.answerUnlessPersonOnly(id, "go on", "interlocutor"));
    assertEquals(OrchestrationState.ASKING, store.find(id).orElseThrow().state());
    assertTrue(store.messages(id).stream().noneMatch(m -> m.kind() == Kind.ANSWER));

    assertTrue(store.answer(id, "go on", "enzo"));
    OrchestrationMessage answer =
        store.messages(id).stream().filter(m -> m.kind() == Kind.ANSWER).findFirst().orElseThrow();
    assertEquals("stuck", answer.capKind());
    assertNull(store.find(id).orElseThrow().pendingCap());
  }

  @Test
  void a_model_s_stop_leaves_a_stuck_question_to_the_person_whose_stop_takes_it() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.askCap(id, "stuck", "go on?").orElseThrow();

    assertFalse(
        store.stopUnlessRunning(id, OrchestrationState.CANCELLED, "cancelled by interlocutor"));
    assertEquals(OrchestrationState.ASKING, store.find(id).orElseThrow().state());

    assertTrue(store.stop(id, OrchestrationState.CANCELLED, "cancelled by enzo"));
    assertEquals(OrchestrationState.CANCELLED, store.find(id).orElseThrow().state());
  }

  private static final String DIGEST = "a".repeat(64);
  private static final String OTHER_DIGEST = "b".repeat(64);

  /**
   * V65: the verifier's findings are counted per run; the person is asked with the digest of what
   * they are shown, and only the person may answer. {@code accept} makes that digest the waived one
   * and starts the count again; any other answer only starts the count again.
   */
  @Test
  void the_verifier_s_findings_are_counted_and_the_person_s_accept_waives_what_they_were_shown() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    assertEquals(1, store.verifierFound(id));
    assertEquals(2, store.verifierFound(id));
    assertEquals(3, store.verifierFound(id));
    assertEquals(Optional.empty(), store.verifierWaived(id));

    store.askUncovered(id, DIGEST, "does it stand?").orElseThrow();
    assertEquals("uncovered", store.find(id).orElseThrow().pendingCap());
    assertFalse(
        store.answerUnlessPersonOnly(id, "accept", "interlocutor"),
        "only the person may answer it");
    assertFalse(store.stopUnlessRunning(id, OrchestrationState.CANCELLED, "by a model"));
    assertTrue(store.answer(id, "accept", "enzo"));
    assertEquals(
        "uncovered",
        store.messages(id).stream()
            .filter(m -> m.kind() == Kind.ANSWER)
            .findFirst()
            .orElseThrow()
            .capKind());

    store.verifierSettled(id, true);
    assertEquals(Optional.of(DIGEST), store.verifierWaived(id));
    assertEquals(1, store.verifierFound(id), "the count starts again");
    store.verifierSettled(id, true);
    assertEquals(
        Optional.of(DIGEST),
        store.verifierWaived(id),
        "settling again, with nothing asked, keeps what was waived");
  }

  @Test
  void any_other_answer_starts_the_count_again_and_waives_nothing() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.verifierFound(id);
    store.verifierFound(id);
    store.askUncovered(id, OTHER_DIGEST, "does it stand?").orElseThrow();
    assertTrue(store.answer(id, "add a test per requirement", "enzo"));

    store.verifierSettled(id, false);

    assertEquals(Optional.empty(), store.verifierWaived(id));
    assertEquals(1, store.verifierFound(id));
  }

  /**
   * V69, V71 and V77 rewrote the pending-cap CHECK from the list before: every earlier kind holds.
   */
  @Test
  void the_pending_cap_check_keeps_every_earlier_kind_and_refuses_an_unknown_one() {
    for (String kind :
        List.of(
            "turn_cap",
            "call_budget",
            "stuck",
            "uncovered",
            "time_cap",
            "check_failures",
            "install",
            "concerns",
            "product_check")) {
      String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
      store.askCap(id, kind, "?").orElseThrow();
      assertTrue(store.answer(id, "yes", "enzo"));
      assertEquals(
          kind,
          store.messages(id).stream()
              .filter(m -> m.kind() == Kind.ANSWER)
              .findFirst()
              .orElseThrow()
              .capKind());
    }
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    assertThrows(DataIntegrityViolationException.class, () -> store.askCap(id, "vibes", "?"));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE orchestrations SET verifier_waived = 'not a digest' WHERE id = ?", id));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE orchestrations SET verifier_refusals = -1 WHERE id = ?", id));
  }

  /**
   * V77: the product check is the person's, asked with the digest of what they are shown; {@code
   * accept} makes it the accepted one, and settling again with nothing asked keeps it.
   */
  @Test
  void the_product_check_is_the_person_s_and_accept_keeps_what_they_were_shown() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    assertEquals(Optional.empty(), store.productAccepted(id));

    store.askProduct(id, DIGEST, "does it start?").orElseThrow();
    assertEquals("product_check", store.find(id).orElseThrow().pendingCap());
    assertFalse(
        store.answerUnlessPersonOnly(id, "accept", "interlocutor"),
        "only the person may answer it");
    assertTrue(store.answer(id, "accept", "enzo"));
    store.productSettled(id, true);
    assertEquals(Optional.of(DIGEST), store.productAccepted(id));
    store.productSettled(id, false);
    assertEquals(
        Optional.of(DIGEST),
        store.productAccepted(id),
        "settling again, with nothing asked, keeps what was accepted");

    store.askProduct(id, OTHER_DIGEST, "and now?").orElseThrow();
    assertTrue(store.answer(id, "the ship does not move", "enzo"));
    store.productSettled(id, false);
    assertEquals(Optional.of(DIGEST), store.productAccepted(id), "notes accept nothing new");
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE orchestrations SET product_accepted = 'not a digest' WHERE id = ?", id));
  }

  /** V77: a run's checker is pinned once, and a run without one has none. */
  @Test
  void a_run_s_checker_is_pinned_and_read_back() {
    String with = store.insert(newOrchestration(conductorConversation(), 2)).id();
    String without = store.insert(newOrchestration(conductorConversation(), 2)).id();

    store.pinChecker(with, "acceptance_checker");

    assertEquals(Optional.of("acceptance_checker"), store.checker(with));
    assertEquals(Optional.empty(), store.checker(without));
  }

  /** V77's concerns: numbered per run, moved through their states, refused out of shape. */
  @Test
  void concerns_are_numbered_per_run_and_kept_to_their_states() {
    OrchestrationConcerns concerns = new OrchestrationConcerns(jdbc, () -> Instant.EPOCH);
    String run = store.insert(newOrchestration(conductorConversation(), 2)).id();
    String other = store.insert(newOrchestration(conductorConversation(), 2)).id();

    Concerns.Concern first =
        concerns.raise(run, "sound plays", "mocked", Concerns.AT_PLAN, "why is a mock enough?");
    Concerns.Concern second =
        concerns.raise(run, "the game starts", "no-op", Concerns.AT_PLAN, null);
    Concerns.Concern elsewhere = concerns.raise(other, "x", "y", Concerns.AT_END, null);

    assertEquals("c1", first.id());
    assertEquals(Concerns.ASKED, first.state());
    assertEquals(1, first.rounds());
    assertEquals("c2", second.id());
    assertEquals(Concerns.OPEN, second.state());
    assertEquals("c1", elsewhere.id(), "numbered per run");

    concerns.answered(run, "c1", "the test covers it");
    concerns.askedAgain(run, "c1", "a mock plays nothing", "which line listens?");
    assertEquals(2, concerns.find(run, "c1").orElseThrow().rounds());
    concerns.askedAgain(run, "c1", "still", "again?");
    assertEquals(2, concerns.find(run, "c1").orElseThrow().rounds(), "never past two");
    concerns.forThePerson(run, "c1", "there is no such line");
    assertTrue(concerns.find(run, "c1").orElseThrow().forThePersonAtPlan());
    concerns.personAnswered(run, "c1", "add a check: line", false);
    assertEquals(Concerns.OPEN, concerns.find(run, "c1").orElseThrow().state());
    assertEquals("add a check: line", concerns.find(run, "c1").orElseThrow().personAnswer());

    concerns.checked(run, "c2", Concerns.DOES_NOT_HOLD, "def main(): pass", null);
    assertTrue(concerns.find(run, "c2").orElseThrow().doesNotHold());
    concerns.checked(run, "c1", Concerns.CANNOT_CHECK, "only a person hears", "listen");
    assertTrue(concerns.find(run, "c1").orElseThrow().forThePersonAtAcceptance());
    assertEquals(List.of("c1", "c2"), concerns.of(run).stream().map(Concerns.Concern::id).toList());

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE orchestration_concerns SET state = 'vibes' WHERE orchestration = ?", run));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE orchestration_concerns SET state = 'checked', verdict = 'cannot_check'"
                    + " WHERE orchestration = ? AND id = 'c1'",
                run));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE orchestration_concerns SET rounds = 3 WHERE orchestration = ?", run));
  }

  /** V71: install is asked like a cap, holds its structure, and is refused to a model. */
  @Test
  void an_install_question_is_the_person_s_and_keeps_what_it_would_install() throws Exception {
    String conductor = conductorConversation();
    String id = store.insert(newOrchestration(conductor, 2)).id();
    String structure =
        "{\"lead\":\"Install?\",\"questions\":[{\"header\":\"Store\",\"question\":\"Choose?\",\"multi\":false,\"options\":[{\"label\":\"Postgres\",\"description\":\"Use Postgres\"},{\"label\":\"SQLite\",\"description\":\"Use SQLite\"}]}],\"name\":\"t\",\"path\":\"artifacts/t.md\",\"sha256\":\"sha256:2a50ff7eb58912fe2e5bb9018dcd8b8e5fb366f4b3f23b354b8003bfeab360aa\",\"text\":\"---\\nname: t\"}";

    assertTrue(
        store
            .askInstall(
                id,
                "Install triage?",
                io.aeyer.plowshare.server.orchestrations.OrchestrationStructures.decode(structure))
            .isPresent());

    OrchestrationRecord row = store.find(id).orElseThrow();
    assertEquals("install", row.pendingCap());
    OrchestrationMessage question = store.openQuestion(id).orElseThrow();
    assertEquals("harness", question.author());
    assertEquals(JSON.readTree(structure), JSON.valueToTree(question.structure()));
    assertFalse(
        store.answerUnlessPersonOnly(id, "Install", "interlocutor"), "a model may not answer it");
    assertTrue(store.answer(id, "Install", "enzo"));
    assertEquals(
        "install",
        store.messages(id).stream()
            .filter(m -> m.kind() == Kind.ANSWER)
            .findFirst()
            .orElseThrow()
            .capKind());
  }

  /** V58: the prose mark is set with the first nudge and outlives the count's reset. */
  @Test
  void a_nudge_marks_the_run_as_having_ended_in_prose_and_progress_does_not_clear_it() {
    String conversation = conductorConversation();
    String id = store.insert(newOrchestration(conversation, 2)).id();
    assertFalse(store.find(id).orElseThrow().endedInProse());

    store.nudged(id);
    store.progressedIn(conversation);

    OrchestrationRecord found = store.find(id).orElseThrow();
    assertEquals(0, found.nudges());
    assertTrue(found.endedInProse());
  }

  @Test
  void a_model_answers_an_ordinary_question_or_a_cap_as_before() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.askCap(id, "turn_cap", "raise it?").orElseThrow();

    assertTrue(store.answerUnlessPersonOnly(id, "yes", "code_implementation"));
    assertEquals(OrchestrationState.RUNNING, store.find(id).orElseThrow().state());
  }

  @Test
  void an_answer_to_a_running_run_is_refused_and_writes_nothing() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();

    assertFalse(
        store.answer(id, "yes", "enzo"),
        "the run is RUNNING, not ASKING, and has asked nothing to answer");
    assertTrue(
        store.messages(id).isEmpty(),
        "a refused answer writes nothing, not a message nobody asked for");
  }

  /** V70: a question with options keeps them, and so does the answer that chose among them. */
  @Test
  void a_question_and_its_answer_keep_their_structure() throws Exception {
    String conductor = conductorConversation();
    String id = store.insert(newOrchestration(conductor, 2)).id();
    String asked =
        "{\"lead\":\"First:\",\"questions\":[{\"header\":\"Store\",\"question\":\"Choose?\",\"multi\":false,\"options\":[{\"label\":\"Postgres\",\"description\":\"Use Postgres\"},{\"label\":\"SQLite\",\"description\":\"Use SQLite\"}]}]}";

    OrchestrationMessage question =
        store
            .ask(
                id,
                "First: …",
                "code_implementation",
                io.aeyer.plowshare.server.orchestrations.OrchestrationStructures.decode(asked))
            .orElseThrow();
    assertEquals(OrchestrationStructures.decode(asked), question.structure());
    assertEquals(
        JSON.readTree(asked), JSON.valueToTree(store.openQuestion(id).orElseThrow().structure()));

    String chose = "{\"choices\":[{\"header\":\"Store\",\"chosen\":[\"Postgres\"]}]}";
    assertTrue(
        store.answer(
            id,
            "1. [Store] chose \"Postgres\"",
            "enzo",
            io.aeyer.plowshare.server.orchestrations.OrchestrationStructures.decode(chose)));

    OrchestrationMessage answer =
        store.messages(id).stream().filter(m -> m.kind() == Kind.ANSWER).findFirst().orElseThrow();
    assertEquals(JSON.readTree(chose), JSON.valueToTree(answer.structure()));
    assertEquals(
        JSON.readTree(chose),
        JSON.valueToTree(
            store.undeliveredMessages().stream()
                .filter(m -> m.kind() == Kind.ANSWER)
                .findFirst()
                .orElseThrow()
                .structure()));
  }

  @Test
  void a_plain_question_and_answer_have_no_structure() {
    String conductor = conductorConversation();
    String id = store.insert(newOrchestration(conductor, 2)).id();

    store.ask(id, "which database?", "code_implementation");
    assertTrue(store.answerUnlessPersonOnly(id, "postgres", "interlocutor"));

    assertTrue(store.messages(id).stream().allMatch(m -> m.structure() == null));
  }

  @Test
  void a_structure_that_is_not_an_object_is_refused_before_persistence() {
    String conductor = conductorConversation();
    String id = store.insert(newOrchestration(conductor, 2)).id();

    assertThrows(
        IllegalStateException.class,
        () ->
            store.ask(
                id,
                "which?",
                "code_implementation",
                io.aeyer.plowshare.server.orchestrations.OrchestrationStructures.decode("[1, 2]")));
  }

  // --- delivery bookkeeping ---------------------------------------------------------------

  @Test
  void undelivered_messages_and_endings_are_listed_until_marked() {
    String withQuestion = store.insert(newOrchestration(conductorConversation(), 2)).id();
    OrchestrationMessage question =
        store.addMessage(
            withQuestion,
            Kind.QUESTION,
            "which branch should this land on?",
            "code_implementation");

    assertTrue(store.undeliveredMessages().stream().anyMatch(m -> m.id().equals(question.id())));
    assertTrue(store.messageDelivered(question.id()), "the first delivery wins");
    assertFalse(
        store.messageDelivered(question.id()),
        "delivered_at is already set, so a second delivery matches nothing");
    assertTrue(store.undeliveredMessages().stream().noneMatch(m -> m.id().equals(question.id())));

    String ended = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.finish(ended, "shipped");

    assertTrue(store.undeliveredEndings().stream().anyMatch(r -> r.id().equals(ended)));
    assertTrue(store.resultDelivered(ended), "the first delivery wins");
    assertFalse(
        store.resultDelivered(ended),
        "result_delivered_at is already set, so a second delivery matches nothing");
    assertTrue(store.undeliveredEndings().stream().noneMatch(r -> r.id().equals(ended)));
  }

  /**
   * A stopped run's undelivered question is not something anybody is still waiting to answer --
   * {@link OrchestrationStore#undeliveredMessages} has to join back to the run's own state rather
   * than reading {@code orchestration_messages} alone.
   */
  @Test
  void a_message_of_a_stopped_run_is_not_listed_as_undelivered() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    OrchestrationMessage question =
        store.addMessage(id, Kind.QUESTION, "which branch?", "code_implementation");
    store.moveTo(id, OrchestrationState.RUNNING, OrchestrationState.ASKING);
    store.stop(id, OrchestrationState.CANCELLED, "cancelled by enzo");

    assertTrue(
        store.undeliveredMessages().stream().noneMatch(m -> m.id().equals(question.id())),
        "the run has ended, so nobody is waiting on this question any more");
  }

  // --- listing live runs ------------------------------------------------------------------

  @Test
  void live_lists_only_runs_that_have_not_ended() {
    String stillRunning = store.insert(newOrchestration(conductorConversation(), 2)).id();
    String ended = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.finish(ended, "done");

    List<String> live = store.live().stream().map(OrchestrationRecord::id).toList();

    assertTrue(live.contains(stillRunning));
    assertFalse(live.contains(ended));
  }

  // --- live definitions from a conversation ------------------------------------------------

  @Test
  void live_definitions_from_a_conversation_name_only_running_or_asking_runs_it_called() {
    String cnvA = conductorConversation();
    String cnvB = conductorConversation();

    store.insert(
        newOrchestration(conductorConversation(), "code_implementation", "story", cnvA, "enzo", 2));
    String asking =
        store
            .insert(
                newOrchestration(
                    conductorConversation(), "deep_research", "story", cnvA, "enzo", 2))
            .id();
    store.moveTo(asking, OrchestrationState.RUNNING, OrchestrationState.ASKING);
    String finished =
        store
            .insert(newOrchestration(conductorConversation(), "review", "story", cnvA, "enzo", 2))
            .id();
    store.finish(finished, "done");
    store.insert(newOrchestration(conductorConversation(), "other", "story", cnvB, "enzo", 2));

    assertEquals(Set.of("code_implementation", "deep_research"), store.liveDefinitionsFrom(cnvA));
  }

  // --- a caller's runs ----------------------------------------------------------------------

  @Test
  void a_callers_runs_are_newest_first_and_filter_by_project_and_state() {
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('mo', 'h')");

    clock.set(T0);
    String enzoStoryRunning =
        store
            .insert(
                newOrchestration(
                    conductorConversation(), "code_implementation", "story", null, "enzo", 2))
            .id();

    clock.set(T0.plus(1, ChronoUnit.MINUTES));
    String enzoStoryFinished =
        store
            .insert(newOrchestration(conductorConversation(), "review", "story", null, "enzo", 2))
            .id();
    store.finish(enzoStoryFinished, "done");

    clock.set(T0.plus(2, ChronoUnit.MINUTES));
    String enzoAtlasRunning =
        store
            .insert(
                newOrchestration(
                    conductorConversation(), "deep_research", "atlas", null, "enzo", 2))
            .id();

    clock.set(T0.plus(3, ChronoUnit.MINUTES));
    store.insert(newOrchestration(conductorConversation(), "other", "story", null, "mo", 2));

    assertEquals(
        List.of(enzoAtlasRunning, enzoStoryFinished, enzoStoryRunning),
        store.byCaller("enzo", null, null, 200).stream().map(OrchestrationRecord::id).toList(),
        "newest first, across both projects, one handle only");

    assertEquals(
        List.of(enzoStoryFinished, enzoStoryRunning),
        store.byCaller("enzo", "story", null, 200).stream().map(OrchestrationRecord::id).toList(),
        "the project filter");

    assertEquals(
        List.of(enzoAtlasRunning, enzoStoryRunning),
        store.byCaller("enzo", null, OrchestrationState.RUNNING, 200).stream()
            .map(OrchestrationRecord::id)
            .toList(),
        "the state filter");

    assertEquals(
        List.of(enzoStoryRunning),
        store.byCaller("enzo", "story", OrchestrationState.RUNNING, 200).stream()
            .map(OrchestrationRecord::id)
            .toList(),
        "project and state together");
  }

  @Test
  void a_limit_outside_one_to_two_hundred_is_refused() {
    IllegalArgumentException tooLow =
        assertThrows(IllegalArgumentException.class, () -> store.byCaller("enzo", null, null, 0));
    assertEquals("limit must be between 1 and 200, was 0", tooLow.getMessage());

    IllegalArgumentException tooHigh =
        assertThrows(IllegalArgumentException.class, () -> store.byCaller("enzo", null, null, 201));
    assertEquals("limit must be between 1 and 200, was 201", tooHigh.getMessage());
  }

  @Test
  void a_callers_runs_stop_at_the_limit() {
    clock.set(T0);
    store.insert(
        newOrchestration(conductorConversation(), "code_implementation", "story", null, "enzo", 2));
    clock.set(T0.plus(1, ChronoUnit.MINUTES));
    String second =
        store
            .insert(newOrchestration(conductorConversation(), "review", "story", null, "enzo", 2))
            .id();
    clock.set(T0.plus(2, ChronoUnit.MINUTES));
    String third =
        store
            .insert(
                newOrchestration(
                    conductorConversation(), "deep_research", "atlas", null, "enzo", 2))
            .id();

    List<String> ids =
        store.byCaller("enzo", null, null, 2).stream().map(OrchestrationRecord::id).toList();

    assertEquals(List.of(third, second), ids);
  }

  // --- nudges and restarts ----------------------------------------------------------------

  @Test
  void nudges_and_restarts_count_up() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();

    assertEquals(OptionalInt.of(1), store.nudged(id));
    assertEquals(OptionalInt.of(2), store.nudged(id));
    assertEquals(OptionalInt.of(1), store.restarted(id));
    assertEquals(OptionalInt.of(2), store.restarted(id));

    OrchestrationRecord found = store.find(id).orElseThrow();
    assertEquals(2, found.nudges());
    assertEquals(2, found.restarts());
  }

  @Test
  void nudging_or_restarting_a_finished_run_changes_nothing_and_answers_empty() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.finish(id, "done");

    assertEquals(OptionalInt.empty(), store.nudged(id));
    assertEquals(OptionalInt.empty(), store.restarted(id));

    OrchestrationRecord found = store.find(id).orElseThrow();
    assertEquals(0, found.nudges());
    assertEquals(0, found.restarts());
  }

  /**
   * The progress hooks' door: the run a conversation conducts forgets its nudges, an ended run
   * keeps its count, and a conversation that conducts nothing changes nothing.
   */
  @Test
  void progress_in_a_conductor_conversation_forgets_its_live_runs_nudges() {
    String conversation = conductorConversation();
    String id = store.insert(newOrchestration(conversation, 2)).id();
    String other = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.nudged(id);
    store.nudged(id);
    store.nudged(other);

    store.progressedIn(conversation);
    store.progressedIn("cnv_conducts_nothing");

    assertEquals(0, store.find(id).orElseThrow().nudges());
    assertEquals(1, store.find(other).orElseThrow().nudges(), "another run's count is its own");

    store.nudged(id);
    store.stop(id, OrchestrationState.FAILED, "stuck");
    store.progressedIn(conversation);
    assertEquals(1, store.find(id).orElseThrow().nudges(), "an ended run is left as it ended");
  }

  // --- asking a question --------------------------------------------------------------

  @Test
  void asking_moves_a_running_run_to_asking_and_writes_the_question() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();

    OrchestrationMessage question =
        store
            .ask(id, "should the retry back off exponentially?", "code_implementation")
            .orElseThrow();

    assertEquals(Kind.QUESTION, question.kind());
    assertEquals("should the retry back off exponentially?", question.text());
    assertEquals(OrchestrationState.ASKING, store.find(id).orElseThrow().state());
  }

  @Test
  void asking_a_run_that_is_already_asking_is_refused_and_writes_nothing() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.ask(id, "first question", "code_implementation");

    assertTrue(
        store.ask(id, "second question", "code_implementation").isEmpty(),
        "the run is already ASKING, so a second ask matches nothing");
    assertEquals(
        1,
        store.messages(id).size(),
        "a refused ask writes nothing, not a second question nobody asked for");
  }

  // --- the open question ---------------------------------------------------------------

  @Test
  void the_open_question_is_empty_while_running_and_answered_and_present_while_asking() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();

    assertTrue(store.openQuestion(id).isEmpty(), "nothing has been asked yet");

    store.addMessage(id, Kind.QUESTION, "which branch?", "code_implementation");
    store.moveTo(id, OrchestrationState.RUNNING, OrchestrationState.ASKING);

    assertEquals("which branch?", store.openQuestion(id).orElseThrow().text());

    store.answer(id, "main", "enzo");

    assertTrue(
        store.openQuestion(id).isEmpty(),
        "the run is RUNNING again, so there is no open question any more");
  }

  // --- bad arguments -------------------------------------------------------------------

  @Test
  void moveTo_refuses_a_terminal_state_on_either_side() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();

    assertThrows(
        IllegalArgumentException.class,
        () -> store.moveTo(id, OrchestrationState.RUNNING, OrchestrationState.FINISHED));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.moveTo(id, OrchestrationState.FINISHED, OrchestrationState.RUNNING));
  }

  /**
   * V52: {@code WAITING} is a third live state, but entering or leaving it also touches {@code
   * waiting_for}, which {@link OrchestrationStore#moveTo} does not — {@link
   * OrchestrationStore#waitFor} and {@link OrchestrationStore#wake} are its only doors.
   */
  @Test
  void moveTo_refuses_waiting_on_either_side() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();

    IllegalArgumentException enteringWaiting =
        assertThrows(
            IllegalArgumentException.class,
            () -> store.moveTo(id, OrchestrationState.RUNNING, OrchestrationState.WAITING));
    assertTrue(enteringWaiting.getMessage().contains("waitFor()"), enteringWaiting.getMessage());

    IllegalArgumentException leavingWaiting =
        assertThrows(
            IllegalArgumentException.class,
            () -> store.moveTo(id, OrchestrationState.WAITING, OrchestrationState.RUNNING));
    assertTrue(leavingWaiting.getMessage().contains("wake()"), leavingWaiting.getMessage());
  }

  /**
   * V52: the database would refuse a {@code WAITING} row with no {@code waiting_for} anyway ({@code
   * orchestrations_waiting_for_iff_waiting}), but {@link OrchestrationStore#waitFor} catches it
   * before that round trip, the way {@link OrchestrationStore#stop} names its own bad arguments up
   * front.
   */
  @Test
  void waitFor_refuses_a_null_child() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> store.waitFor(id, null));

    assertEquals("waitFor needs the child being waited for; null names none", thrown.getMessage());
  }

  /**
   * Rule 2 held at the moment of the stop (final review, 2026-09-27 in-flight work): a model's
   * cancel read the run as asking, and the person's answer moved it back to running before the stop
   * landed — so this stop matches only a row that is still asking or waiting, and a running one is
   * left exactly as it was.
   */
  @Test
  void a_stop_unless_running_leaves_a_running_row_and_stops_an_asking_or_waiting_one() {
    String running = store.insert(newOrchestration(conductorConversation(), 2)).id();
    String asking = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.moveTo(asking, OrchestrationState.RUNNING, OrchestrationState.ASKING);
    String waiting = store.insert(newOrchestration(conductorConversation(), 2)).id();
    String child =
        store
            .insert(
                newOrchestration(
                    conductorConversation(), "deep_research", "story", null, "enzo", 2, waiting, 1))
            .id();
    store.waitFor(waiting, child);

    assertFalse(
        store.stopUnlessRunning(running, OrchestrationState.CANCELLED, "cancelled by aristoxenus"));
    OrchestrationRecord untouched = store.find(running).orElseThrow();
    assertEquals(OrchestrationState.RUNNING, untouched.state());
    assertNull(untouched.failure());
    assertNull(untouched.endedAt());

    assertTrue(store.stopUnlessRunning(asking, OrchestrationState.CANCELLED, "cancelled by x"));
    assertEquals(OrchestrationState.CANCELLED, store.find(asking).orElseThrow().state());
    assertTrue(store.stopUnlessRunning(waiting, OrchestrationState.CANCELLED, "cancelled by x"));
    assertEquals(OrchestrationState.CANCELLED, store.find(waiting).orElseThrow().state());
    assertFalse(
        store.stopUnlessRunning(asking, OrchestrationState.CANCELLED, "again"),
        "an ended row is not stopped twice");
    assertThrows(
        IllegalArgumentException.class,
        () -> store.stopUnlessRunning(asking, OrchestrationState.FINISHED, "not a failure"));
  }

  @Test
  void stop_refuses_finished_and_a_non_terminal_state() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();

    assertThrows(
        IllegalArgumentException.class,
        () -> store.stop(id, OrchestrationState.FINISHED, "not a failure"));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.stop(id, OrchestrationState.RUNNING, "not terminal"));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.stop(id, OrchestrationState.ASKING, "not terminal"));
  }

  // --- running() ---------------------------------------------------------------------------

  @Test
  void running_lists_only_rows_in_the_running_state() {
    String running = store.insert(newOrchestration(conductorConversation(), 2)).id();
    String asking = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.moveTo(asking, OrchestrationState.RUNNING, OrchestrationState.ASKING);
    String finished = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.finish(finished, "done");

    List<String> ids = store.running().stream().map(OrchestrationRecord::id).toList();

    assertTrue(ids.contains(running));
    assertFalse(ids.contains(asking), "asking is not running");
    assertFalse(ids.contains(finished), "finished is not running");
  }

  // --- quietSince: the newest activity across a run's delegation tree ----------------------

  @Test
  void quiet_since_falls_back_to_created_at_when_the_tree_holds_no_entries() {
    String conductor = conductorConversation();
    OrchestrationRecord run = store.insert(newOrchestration(conductor, 2));

    assertEquals(run.createdAt(), store.quietSince(run));
  }

  @Test
  void quiet_since_is_the_newest_entry_in_the_conductor_conversation() {
    String conductor = conductorConversation();
    OrchestrationRecord run = store.insert(newOrchestration(conductor, 2));

    clock.set(T0.plus(1, ChronoUnit.MINUTES));
    entries.append(conductor, 1, LoggedEntry.utterance("first", Speaker.person(null)));
    clock.set(T0.plus(5, ChronoUnit.MINUTES));
    entries.append(conductor, 1, LoggedEntry.answer("second", List.of()));

    assertEquals(T0.plus(5, ChronoUnit.MINUTES), store.quietSince(run));
  }

  /**
   * A delegation conversation ({@code parent_id} = the conductor conversation) with a newer entry
   * than the conductor's own is what makes a run that is still busy inside a delegated child look
   * quiet if this walk stopped at the conductor conversation alone.
   */
  @Test
  void quiet_since_follows_the_delegation_tree_under_the_conductor() {
    String conductor = conductorConversation();
    OrchestrationRecord run = store.insert(newOrchestration(conductor, 2));

    clock.set(T0.plus(1, ChronoUnit.MINUTES));
    entries.append(conductor, 1, LoggedEntry.utterance("in the conductor", Speaker.person(null)));

    String delegated =
        conversations
            .log(Origin.DELEGATION, Home.of("story"), "code_implementation", conductor, null)
            .id();
    clock.set(T0.plus(10, ChronoUnit.MINUTES));
    entries.append(
        delegated, 1, LoggedEntry.utterance("in the delegated child", Speaker.person(null)));

    assertEquals(
        T0.plus(10, ChronoUnit.MINUTES),
        store.quietSince(run),
        "the delegated child's entry is newer than anything in the conductor itself");
  }

  // --- stalled_since: compare-and-set ------------------------------------------------------

  @Test
  void marking_a_run_stalled_is_compare_and_set() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    Instant since = T0.plus(3, ChronoUnit.MINUTES);

    assertTrue(store.markStalled(id, since), "the first mark wins");
    assertEquals(Optional.of(since), store.stalledSince(id));

    assertFalse(
        store.markStalled(id, since),
        "the same quiet period is already marked, so a second mark for it matches" + " nothing");
    assertEquals(
        Optional.of(since),
        store.stalledSince(id),
        "the losing call did not overwrite the winning mark's instant");
  }

  /**
   * A run can go straight from one stall to a later one with no sweep ever observing it active in
   * between (a caller's sweep is not guaranteed to run every minute on the dot) — the mark must
   * still move to the later quiet period, or the second stall is silently never reported.
   */
  @Test
  void marking_a_run_stalled_for_a_later_quiet_period_replaces_the_earlier_mark() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    Instant first = T0.plus(3, ChronoUnit.MINUTES);
    Instant later = T0.plus(20, ChronoUnit.MINUTES);
    store.markStalled(id, first);

    assertTrue(
        store.markStalled(id, later),
        "a later quiet period supersedes the earlier mark: a new stall, not the one"
            + " already reported");
    assertEquals(Optional.of(later), store.stalledSince(id));
  }

  /**
   * The mirror of the above: a {@code since} no later than the mark already on the row names no
   * newer stall, so it matches nothing — the ordering a sweep's own passes always keep, proved here
   * regardless.
   */
  @Test
  void marking_a_run_stalled_for_a_quiet_period_no_later_than_its_current_mark_matches_nothing() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    Instant later = T0.plus(20, ChronoUnit.MINUTES);
    Instant earlier = T0.plus(3, ChronoUnit.MINUTES);
    store.markStalled(id, later);

    assertFalse(store.markStalled(id, earlier), "not a newer stall than the one already marked");
    assertEquals(
        Optional.of(later),
        store.stalledSince(id),
        "the losing call did not overwrite the winning mark's instant");
  }

  @Test
  void clearing_a_stalled_run_lets_it_be_marked_again() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.markStalled(id, T0.plus(3, ChronoUnit.MINUTES));

    assertTrue(store.clearStalled(id), "the first clear wins");
    assertEquals(Optional.empty(), store.stalledSince(id));

    assertFalse(
        store.clearStalled(id), "stalled_since is already NULL, so a second clear matches nothing");

    assertTrue(
        store.markStalled(id, T0.plus(9, ChronoUnit.MINUTES)),
        "cleared, so the row can be marked stalled again");
  }

  @Test
  void a_run_that_is_not_running_cannot_be_marked_stalled() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.moveTo(id, OrchestrationState.RUNNING, OrchestrationState.ASKING);

    assertFalse(
        store.markStalled(id, T0.plus(1, ChronoUnit.MINUTES)),
        "the row is ASKING, not RUNNING, so nothing matches");
    assertEquals(Optional.empty(), store.stalledSince(id));
  }

  /**
   * V55's own CHECK: {@code stalled_since IS NULL OR state = 'running'}. Every UPDATE that moves a
   * run's state away from {@code running} must clear {@code stalled_since} in the same statement or
   * the database refuses the row.
   */
  @Test
  void moving_a_stalled_run_to_asking_clears_stalled_since() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.markStalled(id, T0.plus(1, ChronoUnit.MINUTES));

    assertTrue(store.moveTo(id, OrchestrationState.RUNNING, OrchestrationState.ASKING));

    assertEquals(Optional.empty(), store.stalledSince(id));
  }

  @Test
  void stopping_a_stalled_run_clears_stalled_since() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.markStalled(id, T0.plus(1, ChronoUnit.MINUTES));

    assertTrue(store.stop(id, OrchestrationState.CANCELLED, "cancelled by enzo"));

    assertEquals(Optional.empty(), store.stalledSince(id));
  }

  // --- V60: where a run writes, the phase it was started under, and caps it passed ---------

  @Test
  void a_run_remembers_its_directory_and_the_todo_it_was_started_under() {
    OrchestrationRecord parent = store.insert(newOrchestration(conductorConversation(), 2));
    OrchestrationRecord child =
        store.insert(
            newOrchestration(
                conductorConversation(),
                "code_implementation",
                "story",
                parent.conductorConversation(),
                "enzo",
                2,
                parent.id(),
                1));

    store.placed(child.id(), "docs/orchestrations/p/phases/03-character/c/", "td_phase3");

    assertEquals(
        Optional.of("docs/orchestrations/p/phases/03-character/c/"),
        store.artifactsDir(child.id()));
    assertEquals(child.id(), store.livePhaseRun(parent.id(), "td_phase3").orElseThrow().id());
    store.stop(child.id(), OrchestrationState.FAILED, "gone");
    assertTrue(store.livePhaseRun(parent.id(), "td_phase3").isEmpty(), "an ended run is not live");
  }

  @Test
  void caps_are_continued_up_to_the_most_and_no_further() {
    OrchestrationRecord run = store.insert(newOrchestration(conductorConversation(), 2));

    assertEquals(OptionalInt.of(1), store.capContinued(run.id(), 2));
    assertEquals(OptionalInt.of(2), store.capContinued(run.id(), 2));
    assertEquals(OptionalInt.empty(), store.capContinued(run.id(), 2));
  }

  /**
   * V69: a run's clock is the wall clock since it was created, less the time it spent asking — each
   * spell counted when it is answered, and a spell still open counted up to now.
   */
  @Test
  void a_run_s_clock_counts_from_its_start_and_leaves_out_the_time_it_spent_asking() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    Instant ten = T0.plus(10, ChronoUnit.MINUTES);
    assertEquals(new OrchestrationStore.RunClock(600, 0), store.clockOf(id, ten).orElseThrow());

    clock.set(ten);
    store.askCap(id, "turn_cap", "go on?").orElseThrow();
    Instant forty = T0.plus(40, ChronoUnit.MINUTES);
    assertEquals(
        600,
        store.clockOf(id, forty).orElseThrow().counted(),
        "a spell still asking is not counted");

    clock.set(forty);
    assertTrue(store.answer(id, "yes", "enzo"));
    assertEquals(
        1200,
        store.clockOf(id, T0.plus(50, ChronoUnit.MINUTES)).orElseThrow().counted(),
        "fifty minutes, thirty of them asking");

    assertTrue(store.timeSpanFrom(id, 1200));
    assertEquals(
        new OrchestrationStore.RunClock(1260, 1200),
        store.clockOf(id, T0.plus(51, ChronoUnit.MINUTES)).orElseThrow());
    assertEquals(60, store.clockOf(id, T0.plus(51, ChronoUnit.MINUTES)).orElseThrow().inSpan());
    assertTrue(store.clockOf("orc_nothing", forty).isEmpty());
  }

  @Test
  void a_run_s_check_failures_are_counted_until_the_person_settles_them() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();

    assertEquals(1, store.checkFailed(id));
    assertEquals(2, store.checkFailed(id));
    store.checkFailuresSettled(id);
    assertEquals(1, store.checkFailed(id), "the count starts again");

    store.stop(id, OrchestrationState.FAILED, "gone");
    assertEquals(0, store.checkFailed(id), "an ended run counts nothing");
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE orchestrations SET check_failures = -1 WHERE id = ?", id));
  }

  @Test
  void check_failures_is_the_person_s_to_answer_and_to_stop() {
    String id = store.insert(newOrchestration(conductorConversation(), 2)).id();
    store.askCap(id, "check_failures", "go on, or stop?").orElseThrow();

    assertFalse(store.answerUnlessPersonOnly(id, "go on", "interlocutor"));
    assertFalse(store.stopUnlessRunning(id, OrchestrationState.CANCELLED, "by a model"));
    assertTrue(store.answer(id, "go on", "enzo"));
  }
}
