package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Tag("full-db")
@Testcontainers
class CodeWorkspaceMonitorTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork work;
  private static final Home HOME = Home.of("code-monitor-test");
  private static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path temp;
  private CodeWorkspaceStore store;
  private Clock clock;
  private Path root, file;
  private FileProvider provider;
  private AtomicBoolean permitted;
  private CodeWorkspaceMonitor.Access access;
  private final List<CodeWorkspaceMonitor> opened = new ArrayList<>();

  private CodeWorkspaceStore.Scope scope() {
    return new CodeWorkspaceStore.Scope(HOME, "alice", "coder", "session-a");
  }

  @BeforeAll
  static void database() {
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(data).load().migrate();
    jdbc = new JdbcTemplate(data);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
    work =
        new UnitOfWork() {
          public <T> T inTransaction(java.util.function.Supplier<T> action) {
            return transaction.execute(status -> action.get());
          }
        };
  }

  @BeforeEach
  void prepare() throws Exception {
    jdbc.execute("TRUNCATE projects CASCADE");
    jdbc.execute("TRUNCATE code_workspaces CASCADE");
    jdbc.execute("TRUNCATE code_workspace_mutations");
    jdbc.update("INSERT INTO projects(name) VALUES (?)", HOME.project());
    root = temp.toRealPath();
    file = Files.writeString(root.resolve("A.java"), "class Before {}");
    clock = Clock.fixed(Instant.parse("2026-10-03T01:00:00Z"), ZoneOffset.UTC);
    store = new JdbcCodeWorkspaceStore(jdbc, work, clock, Duration.ofSeconds(30), 64);
    provider =
        spy(
            LocalProvider.over(
                FileAccess.of(List.of(root), List.of()),
                List.of(new Grant(Scope.WORKSPACE, Mode.WRITE))));
    permitted = new AtomicBoolean(true);
    access =
        scope -> {
          if (!permitted.get()) throw new WorkspaceRefusedException("revoked");
          return new ProviderRouter(home -> List.of(provider));
        };
  }

  @AfterEach
  void stop() {
    opened.forEach(CodeWorkspaceMonitor::close);
  }

  private CodeWorkspaceMonitor monitor(CodeWorkspaceStore store) {
    var monitor = new CodeWorkspaceMonitor(store, access, true);
    opened.add(monitor);
    return monitor;
  }

  private WorkspaceCodeMap enrolled(CodeWorkspaceMonitor monitor) {
    var map =
        new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false)
            .observing(monitor.observations("coder", "session-a", "alice"));
    map.reconcile(HOME, "**");
    return map;
  }

  private void due() {
    jdbc.update(
        "UPDATE code_workspaces SET next_poll = ?",
        java.sql.Timestamp.from(clock.instant().minusSeconds(1)));
  }

  private String hash() {
    return jdbc.queryForObject(
        "SELECT source_hash FROM code_workspace_sources WHERE workspace_id = ?",
        String.class,
        scope().id());
  }

  @Test
  void a_new_service_recovers_the_durable_subscription_and_external_changes_without_an_agent_run()
      throws Exception {
    var first = monitor(store);
    enrolled(first);
    String old = hash();
    first.close();
    Files.writeString(file, "class After {}");
    clearInvocations(provider);
    due();
    var restarted =
        monitor(new JdbcCodeWorkspaceStore(jdbc, work, clock, Duration.ofSeconds(30), 64));
    assertTrue(restarted.pollOnce());
    assertNotEquals(old, hash());
    assertEquals(FileContents.sha256(Files.readAllBytes(file)), hash());
    verify(provider, never()).snapshot(any(), any(), anyInt());
    assertEquals("observed", store.status(scope()).state());
    Files.move(file, root.resolve("Moved.java"));
    Files.writeString(root.resolve("Extra.py"), "def added(): pass");
    due();
    restarted.pollOnce();
    assertEquals(
        List.of("Extra.py", "Moved.java"),
        jdbc.queryForList(
            "SELECT relative_path FROM code_workspace_sources ORDER BY relative_path",
            String.class));
    Files.delete(root.resolve("Moved.java"));
    due();
    restarted.pollOnce();
    assertEquals(
        List.of("Extra.py"),
        jdbc.queryForList("SELECT relative_path FROM code_workspace_sources", String.class));
  }

  @Test
  void periodic_reconciliation_runs_while_the_agent_is_idle() throws Exception {
    var monitor = monitor(store);
    enrolled(monitor);
    Files.writeString(file, "class IdleChange {}");
    due();
    monitor.start();
    String expected = FileContents.sha256(Files.readAllBytes(file));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
    while (System.nanoTime() < deadline && !expected.equals(hash())) Thread.sleep(25);
    assertEquals(expected, hash());
  }

  @Test
  void revocation_omits_old_sources_and_recovery_rechecks_actual_files() throws Exception {
    var monitor = monitor(store);
    enrolled(monitor);
    permitted.set(false);
    clearInvocations(provider);
    due();
    assertTrue(monitor.pollOnce());
    assertEquals("unavailable", store.status(scope()).state());
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM code_workspace_sources", Integer.class));
    verifyNoInteractions(provider);
    permitted.set(true);
    due();
    assertTrue(monitor.pollOnce());
    assertEquals("observed", store.status(scope()).state());
  }

  @Test
  void leases_generation_fences_and_live_writers_prevent_obsolete_publication() {
    var monitor = monitor(store);
    var map = enrolled(monitor);
    var old = store.begin(scope(), "**");
    var newer = store.begin(scope(), "**");
    var view = map.reconcile(HOME, null);
    assertFalse(store.publish(old, view));
    assertFalse(store.publish(newer, view), "the map started an even newer foreground scan");
    var last = store.begin(scope(), "**");
    assertTrue(store.publish(last, view));
    due();
    var claimed = store.claim().orElseThrow();
    assertTrue(store.claim().isEmpty(), "lease prevents a second worker claiming the same scope");
    String writer = store.mutationStarted(scope());
    assertFalse(store.publish(claimed.ticket(), view));
    assertTrue(
        store.claim().isEmpty(), "no scan while a command may still be changing this workspace");
    store.mutationFinished(scope(), writer);
    assertTrue(store.claim().isPresent());
  }

  @Test
  void foreground_mutations_keep_the_persistent_map_dirty_even_if_this_run_never_looked_it_up() {
    var monitor = monitor(store);
    enrolled(monitor);
    var separate =
        new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false)
            .observing(monitor.observations("coder", "session-a", "alice"));
    separate.beforeMutation(HOME);
    assertEquals("dirty", store.status(scope()).state());
    assertTrue(store.claim().isEmpty());
    separate.afterMutation(HOME, List.of(file));
    assertEquals("dirty", store.status(scope()).state());
    assertTrue(monitor.pollOnce());
    assertEquals("observed", store.status(scope()).state());
  }

  @Test
  void unattributed_project_writers_fence_existing_manifests_but_cannot_enroll() {
    var monitor = monitor(store);
    enrolled(monitor);
    var observer = monitor.observations("batch-worker", null, null);
    var map =
        new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false)
            .observing(observer);
    map.beforeMutation(HOME);
    assertTrue(store.claim().isEmpty());
    assertEquals("dirty", store.status(scope()).state());
    map.afterMutation(HOME, List.of());
    map.reconcile(HOME, "**");
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM code_workspaces", Integer.class));
    assertEquals("disabled", observer.status(HOME).state());
    assertTrue(monitor.pollOnce());
  }

  @Test
  void an_actual_nonzero_shell_command_releases_the_writer_lease_and_updates_the_durable_hash()
      throws Exception {
    var monitor = monitor(store);
    var map = enrolled(monitor);
    String before = hash();
    Path environment =
        Files.writeString(
            root.resolve("environment.yml"), "server:\n  mode: open\n  shells: true\n");
    var environments = new Environments(name -> 1L, id -> environment, null);
    var tool =
        new RunTool(new ProviderRouter(home -> List.of(provider)), () -> environments, () -> false)
            .withCodeMap(map);
    doAnswer(
            call -> {
              assertTrue(
                  store.claim().isEmpty(),
                  "the command holds the project writer fence before launch");
              return call.callRealMethod();
            })
        .when(provider)
        .run(any(), anyList(), any(), any(), any(), any());
    String out =
        tool.run(
            JSON.writeValueAsString(
                Map.of(
                    "command",
                    List.of("sh", "-c", "printf 'class ShellChange {}' > A.java; exit 7"))),
            HOME);
    assertTrue(out.startsWith("exit 7 after"), out);
    assertNotEquals(before, hash());
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM code_workspace_mutations", Integer.class));
    assertEquals("observed", store.status(scope()).state());
  }

  @Test
  void crashed_scan_and_writer_leases_expire_without_replaying_any_command() {
    var monitor = monitor(store);
    enrolled(monitor);
    due();
    var abandoned = store.claim().orElseThrow();
    jdbc.update(
        "UPDATE code_workspaces SET lease_until = ?, next_poll = ?",
        java.sql.Timestamp.from(clock.instant().minusSeconds(1)),
        java.sql.Timestamp.from(clock.instant().minusSeconds(1)));
    var replacement = store.claim().orElseThrow();
    assertTrue(replacement.ticket().generation() > abandoned.ticket().generation());
    store.mutationStarted(scope());
    jdbc.update(
        "UPDATE code_workspace_mutations SET expires_at = ?",
        java.sql.Timestamp.from(clock.instant().minusSeconds(1)));
    assertTrue(store.claim().isPresent());
    verify(provider, never()).run(any(), anyList(), any(), any(), any());
  }

  @Test
  void registration_capacity_unwatch_and_accounts_are_independent() throws Exception {
    var limited = new JdbcCodeWorkspaceStore(jdbc, work, clock, Duration.ofSeconds(30), 1);
    var monitor = monitor(limited);
    var map = enrolled(monitor);
    assertNotEquals(
        scope().id(), new CodeWorkspaceStore.Scope(HOME, "bob", "coder", "session-a").id());
    assertEquals(
        scope().id(),
        new CodeWorkspaceStore.Scope(HOME, "alice", "coder", "session-b").id(),
        "a project follows its live provider rather than creating a subscription per socket");
    assertThrows(
        WorkspaceRefusedException.class,
        () -> limited.begin(new CodeWorkspaceStore.Scope(HOME, "bob", "coder", null), "**"));
    var observations = monitor.observations("coder", "session-a", "alice");
    map =
        new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false)
            .observing(observations);
    var tool = new CodeMapTool(map, new FileTools.Reads(map));
    assertEquals(
        "inactive",
        JSON.readTree(tool.run("{\"operation\":\"unwatch\"}", HOME))
            .path("tracking_status")
            .path("state")
            .asText());
    tool.run("{\"operation\":\"files\"}", HOME);
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM code_workspaces", Integer.class),
        "unwatch is sticky for the rest of this run");
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM code_workspace_sources", Integer.class));
    limited.begin(new CodeWorkspaceStore.Scope(HOME, "bob", "coder", null), "**");
    assertEquals("disabled", monitor.observations("coder", null, null).status(HOME).state());
  }

  @Test
  void simultaneous_workers_lease_distinct_scopes_and_admission_respects_the_bound()
      throws Exception {
    var monitor = monitor(store);
    enrolled(monitor);
    store.begin(new CodeWorkspaceStore.Scope(HOME, "bob", "coder", null), "**");
    jdbc.update("UPDATE code_workspaces SET lease_until = NULL");
    due();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var ready = new CountDownLatch(2);
      var start = new CountDownLatch(1);
      java.util.concurrent.Callable<CodeWorkspaceStore.Scan> claim =
          () -> {
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return store.claim().orElseThrow();
          };
      var one = pool.submit(claim);
      var two = pool.submit(claim);
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      assertNotEquals(
          one.get(5, TimeUnit.SECONDS).ticket().workspace(),
          two.get(5, TimeUnit.SECONDS).ticket().workspace());
    }
    jdbc.execute("TRUNCATE code_workspaces CASCADE");
    var limited = new JdbcCodeWorkspaceStore(jdbc, work, clock, Duration.ofSeconds(30), 1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      java.util.function.Function<String, Boolean> enroll =
          owner -> {
            try {
              limited.begin(new CodeWorkspaceStore.Scope(HOME, owner, "coder", null), "**");
              return true;
            } catch (CodeWorkspaceStore.CapacityReached full) {
              return false;
            }
          };
      var one = pool.submit(() -> enroll.apply("alice"));
      var two = pool.submit(() -> enroll.apply("bob"));
      assertNotEquals(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM code_workspaces", Integer.class));
    }
  }

  @Test
  void tracking_storage_outage_never_masks_or_replays_an_executed_command() throws Exception {
    var monitor = monitor(store);
    var map = enrolled(monitor);
    Path environment =
        Files.writeString(
            root.resolve("environment.yml"), "server:\n  mode: open\n  shells: true\n");
    var environments = new Environments(name -> 1L, id -> environment, null);
    var tool =
        new RunTool(new ProviderRouter(home -> List.of(provider)), () -> environments, () -> false)
            .withCodeMap(map);
    jdbc.execute("ALTER TABLE code_workspaces RENAME TO code_workspaces_outage");
    try {
      String result =
          tool.run(
              JSON.writeValueAsString(
                  Map.of("command", List.of("sh", "-c", "printf x >> once.txt; exit 7"))),
              HOME);
      assertTrue(result.startsWith("exit 7 after"), result);
      assertEquals("x", Files.readString(root.resolve("once.txt")));
      assertEquals("unavailable", map.trackingStatus(HOME).state());
    } finally {
      jdbc.execute("ALTER TABLE code_workspaces_outage RENAME TO code_workspaces");
    }
  }

  @Test
  void a_failed_manifest_transaction_rolls_back_the_status_and_all_sources() {
    var monitor = monitor(store);
    var map = enrolled(monitor);
    String original = hash();
    jdbc.execute(
        "ALTER TABLE code_workspace_sources ADD CONSTRAINT reject_test CHECK (source_bytes = -1) NOT VALID");
    var view =
        new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false)
            .reconcile(HOME, "**");
    var ticket = store.begin(scope(), "**");
    try {
      assertThrows(RuntimeException.class, () -> store.publish(ticket, view));
    } finally {
      jdbc.execute("ALTER TABLE code_workspace_sources DROP CONSTRAINT reject_test");
    }
    // DELETE/INSERT and the status update roll back as a single transaction.
    assertEquals(original, hash());
    assertNotEquals("observed", store.status(scope()).state());
  }
}
