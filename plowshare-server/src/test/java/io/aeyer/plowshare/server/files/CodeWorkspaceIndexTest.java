package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.harness.Harness;
import io.aeyer.plowshare.server.hooks.*;
import io.aeyer.plowshare.server.information.*;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
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
class CodeWorkspaceIndexTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork work;
  private static final Home HOME = Home.of("indexed-code");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-03T01:00:00Z"), ZoneOffset.UTC);
  private static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path temp;
  private FileProvider provider;
  private CodeWorkspaceStore store;
  private CodeWorkspaceMonitor monitor;
  private InformationCatalogue catalogue;
  private InformationLifecycle lifecycle;
  private EmbeddingClient embeddings;
  private InformationContext context;
  private final AtomicBoolean deny = new AtomicBoolean(), permitted = new AtomicBoolean(true);
  private final AtomicInteger intakeHooks = new AtomicInteger();

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
    jdbc.execute("TRUNCATE documents, projects, admins CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES ('alice','fixture'),('bob','fixture')");
    jdbc.update("INSERT INTO projects(name) VALUES (?)", HOME.project());
    jdbc.update("INSERT INTO project_members SELECT id,'alice' FROM projects");
    var access = new InformationAccess(new JdbcProjectMembers(jdbc));
    context =
        access
            .forRun("alice", HOME)
            .withCorpus(io.aeyer.plowshare.server.information.InformationContext.Corpus.CODE);
    catalogue =
        io.aeyer.plowshare.server.information.InformationFixtures.catalogue(
            jdbc, work, access, CLOCK);
    catalogue.useWriteGates(
        new InformationWriteGates(
            new io.aeyer.plowshare.server.information.JdbcInformationGateRepository(jdbc, CLOCK),
            work,
            access,
            new InformationJobs(
                new io.aeyer.plowshare.server.information.JdbcJobInformationRepository(jdbc),
                access,
                catalogue),
            new ConversationStore(jdbc),
            LogStages.NONE,
            new Hooks() {
              @Override
              public Gate stagePre(HookContext context, StageStart stage) {
                assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                intakeHooks.incrementAndGet();
                return deny.get() ? new Gate("intake denied", List.of(), List.of()) : Gate.NOTHING;
              }
            },
            Harness.NONE));
    embeddings = mock(EmbeddingClient.class);
    lifecycle =
        new InformationLifecycle(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc, CLOCK),
            work,
            catalogue,
            InformationLifecycle.processing(
                new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                    jdbc, java.time.Clock.systemUTC()),
                work,
                catalogue,
                new DocumentStore(jdbc, work),
                embeddings,
                new Chunking(
                    new io.aeyer.plowshare.server.llm.tokens.FixtureTokenizer(4), 512, 2048),
                16,
                768,
                () -> null,
                new DocumentsProperties()),
            new InformationLifecycle.Gates() {});
    store = new JdbcCodeWorkspaceStore(jdbc, work, CLOCK, Duration.ofSeconds(30), 64);
    provider =
        spy(
            LocalProvider.over(
                FileAccess.of(List.of(temp.toRealPath()), List.of()),
                List.of(new Grant(Scope.WORKSPACE, Mode.WRITE))));
    monitor =
        new CodeWorkspaceMonitor(
                store,
                scope -> {
                  if (!permitted.get()) throw new WorkspaceRefusedException("revoked");
                  return new ProviderRouter(home -> List.of(provider));
                },
                true)
            .indexing(() -> new CodeWorkspaceIndex(store, catalogue, lifecycle));
  }

  @AfterEach
  void close() {
    monitor.close();
    lifecycle.close();
    verifyNoInteractions(embeddings);
  }

  private WorkspaceCodeMap map() {
    return map("alice", "coder");
  }

  private WorkspaceCodeMap map(String owner, String agent) {
    return new WorkspaceCodeMap(
            new ProviderRouter(home -> permitted.get() ? List.of(provider) : List.of()),
            () -> false)
        .observing(monitor.observations(agent, "session", owner));
  }

  private Path source(String name, String text) throws Exception {
    Path path = temp.toRealPath().resolve(name);
    Files.createDirectories(path.getParent());
    return Files.writeString(path, text);
  }

  private int revisions() {
    return jdbc.queryForObject("SELECT count(*) FROM information_revisions", Integer.class);
  }

  private void due() {
    jdbc.update(
        "UPDATE code_workspaces SET next_poll=?",
        java.sql.Timestamp.from(CLOCK.instant().minusSeconds(1)));
  }

  @Test
  void next_run_reuses_immutable_source_and_symbols_after_live_hash_checks() throws Exception {
    Path path = source("A.java", "\uFEFF// 😀\r\nclass A { void alpha() {} }\r\n");
    var cold = map().reconcile(HOME, "**");
    assertEquals("observed", cold.state(), cold.issues().toString());
    var first = cold.files().getFirst();
    assertNotNull(first.revision());
    assertEquals(1, revisions());
    assertEquals(1, intakeHooks.get());
    clearInvocations(provider);
    var warm = map().reconcile(HOME, "**");
    assertEquals(first.revision(), warm.files().getFirst().revision());
    verify(provider, never()).snapshot(any(), any(), anyInt());
    verify(provider).fingerprint(path);
    assertEquals(1, warm.measurements().get("index_hits"));
    assertEquals(0, warm.measurements().get("live_parses"));
    assertEquals(1, intakeHooks.get());
    assertEquals(first.outline(), warm.files().getFirst().outline());
    assertEquals(first.text(), catalogue.text(context, first.revision()));
    var declaration = first.outline().symbols().getLast();
    var evidence =
        catalogue.evidence(
            context,
            first.revision(),
            declaration.start(),
            declaration.end(),
            first.text().substring(declaration.start(), declaration.end()),
            "extracted-text:utf16",
            UUID.randomUUID());
    assertNotNull(evidence);
    assertEquals(
        List.of("skipped", "skipped", "skipped"),
        jdbc.queryForList(
            "SELECT state FROM information_steps WHERE stage IN ('embed','summarise','summary_embed') ORDER BY stage",
            String.class));
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(
                    context.withCorpus(
                        io.aeyer.plowshare.server.information.InformationContext.Corpus.DOCUMENTS),
                    10,
                    0))
            .isEmpty());
    assertEquals(
        1,
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(context, 10, 0))
            .size());
  }

  @Test
  void one_changed_file_creates_one_revision_and_deletion_preserves_history() throws Exception {
    Path a = source("A.java", "class A {}"), b = source("B.py", "def before(): pass");
    var first = map().reconcile(HOME, "**");
    UUID old =
        first.files().stream().filter(f -> f.path().equals(b)).findFirst().orElseThrow().revision();
    Files.writeString(b, "def after(): pass");
    clearInvocations(provider);
    var changed = map().reconcile(HOME, "**");
    assertEquals(3, revisions());
    assertEquals(1, changed.measurements().get("snapshot_reads"));
    verify(provider, never()).snapshot(eq(a), any(), anyInt());
    assertEquals("def before(): pass", catalogue.text(context, old));
    Files.delete(b);
    due();
    assertTrue(monitor.pollOnce());
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM code_workspace_sources", Integer.class));
    assertEquals(3, revisions());
    assertEquals("def before(): pass", catalogue.text(context, old));
  }

  @Test
  void restarted_background_monitor_reuses_indexes_and_ingests_external_changes() throws Exception {
    Path path = source("A.java", "class Before {}");
    map().reconcile(HOME, "**");
    monitor.close();
    monitor =
        new CodeWorkspaceMonitor(
                store, scope -> new ProviderRouter(home -> List.of(provider)), true)
            .indexing(() -> new CodeWorkspaceIndex(store, catalogue, lifecycle));
    clearInvocations(provider);
    due();
    assertTrue(monitor.pollOnce());
    verify(provider, never()).snapshot(any(), any(), anyInt());
    Files.writeString(path, "class After {}");
    due();
    assertTrue(monitor.pollOnce());
    assertEquals(2, revisions());
    UUID current =
        jdbc.queryForObject("SELECT revision_id FROM code_workspace_sources", UUID.class);
    assertEquals(
        "After",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                        catalogue.outline(context, current, 0, 10))
                    .get("symbols")
                instanceof List<?> rows
            ? ((Map<?, ?>) rows.getFirst()).get("name")
            : null);
  }

  @Test
  void intake_denial_is_durable_and_does_not_poison_live_navigation_or_repeat_hooks()
      throws Exception {
    source("A.java", "class A {}");
    deny.set(true);
    var run = map();
    var first = run.reconcile(HOME, "**");
    assertEquals("partial", first.state());
    assertNull(first.files().getFirst().revision());
    assertEquals("A", first.files().getFirst().outline().symbols().getFirst().name());
    assertEquals(0, revisions());
    assertEquals("partial", run.reconcile(HOME, "**").state());
    assertEquals(1, intakeHooks.get());
    assertEquals(0, revisions());
  }

  @Test
  void revoked_access_and_changed_roots_do_not_deliver_stored_sources() throws Exception {
    source("A.java", "class A {}");
    map().reconcile(HOME, "**");
    permitted.set(false);
    clearInvocations(provider);
    assertTrue(map().reconcile(HOME, "**").files().isEmpty());
    verifyNoInteractions(provider);
    due();
    monitor.pollOnce();
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM code_workspace_sources", Integer.class));
    assertEquals(1, revisions());
  }

  @Test
  void account_and_agent_scopes_do_not_reuse_each_others_indexes() throws Exception {
    source("A.java", "class A {}");
    var first = map().reconcile(HOME, "**").files().getFirst();
    jdbc.update("INSERT INTO project_members SELECT id,'bob' FROM projects");
    var other = map("bob", "coder").reconcile(HOME, "**");
    assertNotEquals(first.revision(), other.files().getFirst().revision());
    var secondAgent = map("alice", "reviewer").reconcile(HOME, "**");
    assertNotEquals(first.revision(), secondAgent.files().getFirst().revision());
    assertEquals(3, revisions());
  }

  @Test
  void empty_and_whitespace_code_are_exact_retained_revisions() throws Exception {
    source("empty.py", "");
    source("blank.java", "\r\n \r\n");
    var view = map().reconcile(HOME, "**");
    assertEquals(
        "observed",
        view.state(),
        view.issues()
            + " steps="
            + jdbc.queryForList("SELECT stage,state,error FROM information_steps"));
    assertEquals(2, revisions());
    for (var file : view.files()) {
      assertNotNull(file.revision());
      assertEquals(file.text(), catalogue.text(context, file.revision()));
    }
  }

  @Test
  void overview_is_bounded_and_links_declarations_to_exact_revisions() throws Exception {
    for (int i = 0; i < 30; i++)
      source("src/F" + i + ".java", "class F" + i + " { void alpha() {} }");
    var map = map();
    var tool = new CodeMapTool(map, new FileTools.Reads(map));
    var result = JSON.readTree(tool.run("{\"operation\":\"overview\",\"limit\":2}", HOME));
    assertEquals(30, result.path("total").asInt());
    assertEquals(2, result.path("results").size());
    assertTrue(result.path("has_more").asBoolean());
    var file = result.path("results").get(0);
    assertTrue(file.has("revision"));
    assertEquals("extracted-text:utf16", file.path("retained_locator").asText());
    assertFalse(file.path("declarations").isEmpty());
    assertEquals(1, result.path("directory_total").asInt());
    assertTrue(
        tool.run("{\"operation\":\"overview\",\"limit\":26}", HOME).startsWith("Code map refused"));
  }

  @Test
  void revert_reuses_history_and_unwatch_preserves_immutable_revisions() throws Exception {
    Path path = source("A.java", "class Before {}");
    var original = map().reconcile(HOME, "**").files().getFirst();
    Files.writeString(path, "class After {}");
    map().reconcile(HOME, "**");
    Files.writeString(path, "class Before {}");
    clearInvocations(provider);
    var run = map();
    var reverted = run.reconcile(HOME, "**").files().getFirst();
    assertEquals(original.revision(), reverted.revision());
    verify(provider, never()).snapshot(any(), any(), anyInt());
    run.stopTracking(HOME);
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM code_workspace_indexes", Integer.class));
    assertEquals(2, revisions());
    assertEquals("class Before {}", catalogue.text(context, original.revision()));
    var stopped = run.reconcile(HOME, "**");
    assertNull(stopped.files().getFirst().revision());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM code_workspaces", Integer.class));
    assertEquals(original.revision(), map().reconcile(HOME, "**").files().getFirst().revision());
    assertEquals(2, revisions());
  }

  @Test
  void withdrawn_revisions_cannot_return_as_retained_index_links() throws Exception {
    source("A.java", "class A {}");
    var run = map();
    var first = run.reconcile(HOME, "**").files().getFirst();
    catalogue.availability(context, first.revision(), "withdrawn");
    var view = run.reconcile(HOME, "**");
    assertEquals("partial", view.state());
    assertNull(view.files().getFirst().revision());
    assertEquals("A", view.files().getFirst().outline().symbols().getFirst().name());
    assertEquals(1, revisions());
    assertNull(jdbc.queryForObject("SELECT revision_id FROM code_workspace_sources", UUID.class));
  }

  @Test
  void stage_post_denial_does_not_expose_committed_declarations_until_explicit_retry()
      throws Exception {
    source("A.java", "class A {}");
    var processor =
        InformationLifecycle.processing(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc, java.time.Clock.systemUTC()),
            work,
            catalogue,
            new DocumentStore(jdbc, work),
            embeddings,
            new Chunking(new io.aeyer.plowshare.server.llm.tokens.FixtureTokenizer(4), 512, 2048),
            16,
            768,
            () -> null,
            new DocumentsProperties());
    var block = new AtomicBoolean(true);
    lifecycle.close();
    lifecycle =
        new InformationLifecycle(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc, CLOCK),
            work,
            catalogue,
            processor,
            new InformationLifecycle.Gates() {
              @Override
              public Gate after(InformationLifecycle.Lease lease) {
                return lease.stage().equals("derive") && block.get()
                    ? new Gate("hold declarations", List.of(), List.of())
                    : Gate.NOTHING;
              }
            });
    var first = map().reconcile(HOME, "**");
    assertEquals("partial", first.state());
    assertNull(first.files().getFirst().revision());
    UUID retained = jdbc.queryForObject("SELECT id FROM information_revisions", UUID.class);
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM code_symbols", Integer.class));
    assertNull(
        catalogue.codeProjection(
            context, retained, first.files().getFirst().fingerprint().sha256()));
    block.set(false);
    catalogue.retry(context, retained);
    assertEquals(retained, map().reconcile(HOME, "**").files().getFirst().revision());
    assertEquals(1, revisions());
  }

  @Test
  void displayed_revisions_are_bound_to_the_run_input_ledger() throws Exception {
    for (int i = 0; i < 3; i++) source("F" + i + ".java", "class F" + i + " {}");
    var jobs =
        new InformationJobs(
            new io.aeyer.plowshare.server.information.JdbcJobInformationRepository(jdbc),
            new InformationAccess(new JdbcProjectMembers(jdbc)),
            catalogue);
    String log =
        new ConversationStore(jdbc)
            .log(Origin.SUBMISSION, HOME, "coder", null, Budget.of(1), "alice", null)
            .id();
    var map = map().onRevisionRead((home, revision) -> jobs.reads(log, context).accept(revision));
    var tool = new CodeMapTool(map, new FileTools.Reads(map));
    var result = JSON.readTree(tool.run("{\"operation\":\"overview\",\"limit\":2}", HOME));
    assertEquals(2, jobs.inputsOf(log, "alice").size());
    UUID shown = UUID.fromString(result.path("results").get(0).path("revision").asText());
    catalogue.availability(context, shown, "withdrawn");
    assertFalse(jobs.allowed(log, "alice"));
  }

  @Test
  void a_new_parser_version_gets_a_new_revision_instead_of_rewriting_old_coordinates()
      throws Exception {
    source("A.java", "class A {}");
    var file =
        new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false)
            .reconcile(HOME, "**")
            .files()
            .getFirst();
    var scope = new CodeWorkspaceStore.Scope(HOME, "alice", "coder", "session");
    store.begin(scope, "**");
    String identity = scope.id() + ":" + CodeWorkspaceStore.sourceKey(file.key());
    var old =
        catalogue.admitCodeSnapshot(
            context,
            UUID.nameUUIDFromBytes(
                (identity + ":" + file.fingerprint().sha256() + ":old-parser")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "workspace-code/" + identity + "/A.java",
            Files.readAllBytes(file.path()),
            "workspace-code:" + identity,
            "session");
    lifecycle.drainCodeSyntax(old.revision());
    lifecycle.drainCodeSyntax(old.revision());
    jdbc.update(
        "UPDATE code_outlines SET parser_version='old-parser' WHERE document_id=?", old.revision());
    store.index(scope, file.key(), file.fingerprint().sha256(), "old-parser", old.revision());
    var current = map().reconcile(HOME, "**").files().getFirst();
    assertNotEquals(old.revision(), current.revision());
    assertEquals(2, revisions());
    assertEquals(
        old.resource(),
        jdbc.queryForObject(
            "SELECT resource_id FROM information_revisions WHERE id=?",
            UUID.class,
            current.revision()));
    assertEquals(
        "old-parser",
        jdbc.queryForObject(
            "SELECT parser_version FROM code_outlines WHERE document_id=?",
            String.class,
            old.revision()));
  }

  @Test
  void benchmark_cold_next_run_and_single_file_reindex() throws Exception {
    for (int i = 0; i < 40; i++)
      source("src/F" + i + ".java", "// 😀\nclass F" + i + " { void action() {} }\n");
    var cold = map().reconcile(HOME, "**");
    var warm = map().reconcile(HOME, "**");
    Files.writeString(temp.resolve("src/F7.java"), "class Changed { void action() {} }");
    var changed = map().reconcile(HOME, "**");
    assertEquals(40, cold.measurements().get("snapshot_reads"));
    assertEquals(0, warm.measurements().get("snapshot_reads"));
    assertEquals(40, warm.measurements().get("index_hits"));
    assertEquals(1, changed.measurements().get("snapshot_reads"));
    assertEquals(39, changed.measurements().get("index_hits"));
    assertEquals(41, revisions());
    var report =
        Map.of(
            "fixture",
            "40 local Java files with real PostgreSQL intake gates and Tree-sitter; one sample per phase; not deployment latency",
            "cold",
            cold.measurements(),
            "next_run",
            warm.measurements(),
            "single_file_change",
            changed.measurements(),
            "revisions",
            revisions(),
            "model_calls",
            0);
    Path output = Path.of("build/reports/code-index-benchmark.json");
    Files.createDirectories(output.getParent());
    Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
  }
}
