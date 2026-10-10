package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.documents.Chunking;
import io.aeyer.plowshare.server.documents.Derivation;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.documents.TextExtraction;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/** Real migrations, membership, policy filters and transactional assignment audit. */
@Tag("full-db")
@Testcontainers
class DocumentPoliciesTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;
  private ProjectMembers members;
  private InformationAccess access;
  private DocumentPolicyAssignments assignments;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    var template = new TransactionTemplate(new DataSourceTransactionManager(source));
    transactions =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> work) {
            return template.execute(status -> work.get());
          }
        };
  }

  @BeforeEach
  void reset() {
    jdbc.execute("TRUNCATE documents, projects, admins, information_policy_assignments CASCADE");
    jdbc.update(
        "INSERT INTO admins (handle, password_hash) VALUES"
            + " ('alice', 'hash'), ('bob', 'hash'), ('operator', 'hash')");
    jdbc.update("INSERT INTO projects (name) VALUES ('research')");
    jdbc.update(
        "INSERT INTO project_members (project_id, handle)"
            + " SELECT id, 'alice' FROM projects WHERE name = 'research'");
    members = new JdbcProjectMembers(jdbc);
    access = new InformationAccess(members);
    assignments =
        new DocumentPolicyAssignments(
            new JdbcDocumentPolicyRepository(jdbc),
            new InformationJobs(new JdbcJobInformationRepository(jdbc), access),
            transactions,
            access,
            CLOCK,
            "operator");
  }

  private UUID document(String name) {
    UUID id = UUID.randomUUID();
    insertDocument(jdbc, "", id, name);
    jdbc.update("INSERT INTO information_document_policies (document_id) VALUES (?)", id);
    return id;
  }

  private static void insertDocument(JdbcTemplate target, String schema, UUID id, String name) {
    target.update(
        "INSERT INTO "
            + schema
            + "documents"
            + " (id, source_name, title, content_hash, text_hash, byte_size, ingested_at, ingested_by)"
            + " VALUES (?, ?, ?, 'content', 'text', 10, now(), 'alice')",
        id,
        name,
        name);
  }

  private void assign(UUID id, String owner, InformationContext.Scope scope, String project) {
    assignments.assign("operator", id, owner, scope, project, "explicit legacy review");
  }

  private List<UUID> read(
      io.aeyer.plowshare.server.information.InformationSql.Filter filter, String suffix) {
    return jdbc.queryForList(
        "SELECT d.id FROM documents d WHERE " + filter.sql() + " ORDER BY d.title " + suffix,
        UUID.class,
        filter.arguments().toArray());
  }

  @Test
  void filters_exclude_hidden_rows_before_paging_and_totals() {
    UUID hidden = document("a-hidden");
    UUID personal = document("b-personal");
    UUID shared = document("c-shared");
    UUID project = document("d-project");
    document("e-quarantined");
    assign(hidden, "bob", InformationContext.Scope.PERSONAL, null);
    assign(personal, "alice", InformationContext.Scope.PERSONAL, null);
    assign(shared, "bob", InformationContext.Scope.SHARED, null);
    assign(project, "alice", InformationContext.Scope.PROJECT, "research");

    var personalFilter =
        io.aeyer.plowshare.server.information.InformationSql.read(
            access.admitted(access.resolve("alice", null)), "d");
    assertEquals(List.of(personal, shared), read(personalFilter, ""));
    assertEquals(List.of(personal), read(personalFilter, "LIMIT 1"));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM documents d WHERE " + personalFilter.sql(),
            Integer.class,
            personalFilter.arguments().toArray()));
    var projectFilter =
        io.aeyer.plowshare.server.information.InformationSql.read(
            access.admitted(
                access.resolve("alice", InformationContext.Selection.project("research"))),
            "d");
    assertEquals(List.of(shared, project), read(projectFilter, ""));
    var sharedFilter =
        io.aeyer.plowshare.server.information.InformationSql.read(
            access.admitted(access.resolve("bob", InformationContext.Selection.shared())), "d");
    assertEquals(List.of(shared), read(sharedFilter, ""));
    var noShared = new InformationContext.Selection(InformationContext.Scope.PERSONAL, null, false);
    assertEquals(
        List.of(personal),
        read(
            io.aeyer.plowshare.server.information.InformationSql.read(
                access.admitted(access.resolve("alice", noShared)), "d"),
            ""));
  }

  @Test
  void revoked_membership_is_checked_in_the_query_not_only_when_context_is_created() {
    UUID id = document("project-paper");
    assign(id, "alice", InformationContext.Scope.PROJECT, "research");
    var context = access.resolve("alice", InformationContext.Selection.project("research"));
    var oldFilter =
        io.aeyer.plowshare.server.information.InformationSql.read(access.admitted(context), "d");
    assertEquals(List.of(id), read(oldFilter, ""));
    members.remove("research", "alice");
    assertTrue(read(oldFilter, "").isEmpty());
    assertThrows(
        CallerFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationSql.read(
                access.admitted(context), "d"));
  }

  @Test
  void denied_selection_never_creates_a_project_or_claims_membership() {
    assertThrows(
        CallerFault.class,
        () -> access.resolve("bob", InformationContext.Selection.project("research")));
    assertThrows(
        CallerFault.class,
        () -> access.resolve("alice", InformationContext.Selection.project("unknown")));
    assertEquals(List.of("alice"), members.members("research"));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM projects", Integer.class));
  }

  @Test
  void being_an_account_does_not_grant_migration_authority_and_assignment_is_audited() {
    UUID id = document("legacy");
    assertThrows(
        CallerFault.class,
        () ->
            assignments.assign(
                "alice", id, "alice", InformationContext.Scope.SHARED, null, "self appointment"));
    var disabled =
        new DocumentPolicyAssignments(
            new JdbcDocumentPolicyRepository(jdbc),
            new InformationJobs(new JdbcJobInformationRepository(jdbc), access),
            transactions,
            access,
            CLOCK,
            null);
    assertThrows(
        CallerFault.class,
        () ->
            disabled.assign(
                "operator",
                id,
                "alice",
                InformationContext.Scope.PERSONAL,
                null,
                "no configured operator"));
    assertTrue(
        read(
                io.aeyer.plowshare.server.information.InformationSql.read(
                    access.admitted(access.resolve("alice", null)), "d"),
                "")
            .isEmpty());
    assign(id, "bob", InformationContext.Scope.PERSONAL, null);
    assertEquals(
        "bob",
        jdbc.queryForObject(
            "SELECT owner_handle FROM information_document_policies" + " WHERE document_id = ?",
            String.class,
            id));
    assertEquals(
        "operator",
        jdbc.queryForObject(
            "SELECT actor_handle FROM information_policy_assignments" + " WHERE document_id = ?",
            String.class,
            id));
    assertThrows(
        NotFoundFault.class, () -> assign(id, "alice", InformationContext.Scope.SHARED, null));
    assertEquals(
        1,
        jdbc.queryForObject("SELECT count(*) FROM information_policy_assignments", Integer.class));
  }

  @Test
  void audit_failure_rolls_back_the_ownership_change() {
    UUID id = document("legacy");
    var missingActor =
        new DocumentPolicyAssignments(
            new JdbcDocumentPolicyRepository(jdbc),
            new InformationJobs(new JdbcJobInformationRepository(jdbc), access),
            transactions,
            access,
            CLOCK,
            "missing");
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            missingActor.assign(
                "missing",
                id,
                "alice",
                InformationContext.Scope.PERSONAL,
                null,
                "unregistered operator"));
    assertEquals(
        "quarantined",
        jdbc.queryForObject(
            "SELECT visibility FROM information_document_policies" + " WHERE document_id = ?",
            String.class,
            id));
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM information_policy_assignments", Integer.class));
  }

  @Test
  void concurrent_assignments_cannot_overwrite_ownership() throws Exception {
    UUID id = document("legacy");
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var alice = pool.submit(() -> attemptAssignment(start, id, "alice"));
      var bob = pool.submit(() -> attemptAssignment(start, id, "bob"));
      start.countDown();
      assertNotEquals(alice.get(10, TimeUnit.SECONDS), bob.get(10, TimeUnit.SECONDS));
    }
    assertEquals(
        1,
        jdbc.queryForObject("SELECT count(*) FROM information_policy_assignments", Integer.class));
  }

  private boolean attemptAssignment(CountDownLatch start, UUID id, String owner)
      throws InterruptedException {
    start.await();
    try {
      assign(id, owner, InformationContext.Scope.PERSONAL, null);
      return true;
    } catch (NotFoundFault refused) {
      return false;
    }
  }

  @Test
  void migration_preserves_ids_and_quarantines_even_matching_attribution() {
    String schema = "before_information";
    var before =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .target("81")
            .load();
    before.migrate();
    jdbc.update(
        "INSERT INTO before_information.admins (handle, password_hash) VALUES ('alice', 'hash')");
    UUID id = UUID.randomUUID();
    insertDocument(jdbc, "before_information.", id, "attributed-to-alice");
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    assertEquals(
        id,
        jdbc.queryForObject(
            "SELECT document_id FROM before_information.information_document_policies",
            UUID.class));
    assertNull(
        jdbc.queryForObject(
            "SELECT owner_handle FROM before_information.information_document_policies",
            String.class));
    assertEquals(
        "quarantined",
        jdbc.queryForObject(
            "SELECT visibility FROM before_information.information_document_policies",
            String.class));
    assertEquals(
        "alice",
        jdbc.queryForObject("SELECT ingested_by FROM before_information.documents", String.class));
  }

  @Test
  void missing_policy_and_invalid_policy_shapes_fail_closed() {
    UUID id = UUID.randomUUID();
    insertDocument(jdbc, "", id, "unregistered");
    assertTrue(
        read(
                io.aeyer.plowshare.server.information.InformationSql.read(
                    access.admitted(access.resolve("alice", null)), "d"),
                "")
            .isEmpty());
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO information_document_policies (document_id, visibility) VALUES (?, 'shared')",
                id));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO information_document_policies (document_id, owner_handle) VALUES (?, 'alice')",
                id));
  }

  @Test
  void scoped_corpus_filters_candidates_counts_hierarchy_and_direct_reads() {
    DocumentStore store = new DocumentStore(jdbc, transactions);
    UUID hidden = projected(store, "hidden", "Budget budget budget retry.");
    UUID visible = projected(store, "visible", "Budget retry remains bounded.");
    assign(hidden, "bob", InformationContext.Scope.PERSONAL, null);
    assign(visible, "alice", InformationContext.Scope.PERSONAL, null);
    InformationContext context = access.resolve("alice", null);
    DocumentStore scoped = store.scoped(access, context);
    float[] query = new float[768];
    query[0] = 1;
    for (UUID id : List.of(hidden, visible)) {
      float[] vector = new float[768];
      vector[id.equals(hidden) ? 0 : 1] = 1;
      for (var chunk : store.unembedded(id)) store.attach(chunk.id(), vector);
      store.attachDocumentSummary(id, "The budget is bounded.");
      store.attachSummaryEmbedding(id, vector);
    }
    var embeddings = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class);
    org.mockito.Mockito.when(embeddings.embed(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(query);
    RetrievalService retrieval = new RetrievalService(scoped, embeddings, "test", 768);
    assertEquals(visible, retrieval.search("budget", 1).hits().getFirst().documentId());
    assertEquals(
        visible,
        retrieval
            .search("budget", 1, RetrievalService.Mode.LEXICAL)
            .hits()
            .getFirst()
            .documentId());
    assertEquals(visible, retrieval.retrieve("budget", null, 1).getFirst().chunk().documentId());
    assertEquals(visible, retrieval.rank("budget", 1).documents().getFirst().document().id());
    assertEquals(1, scoped.count(null));
    assertEquals(1, scoped.coverage().searchable());
    assertEquals(1, scoped.ranking().rankable());
    assertEquals(visible, scoped.page(null, 1, 0).getFirst().document().id());
    assertEquals(0, scoped.count("hidden"));
    assertTrue(scoped.find(hidden).isEmpty());
    assertTrue(scoped.find("hidden").isEmpty());
    assertTrue(scoped.hierarchy(hidden).isEmpty());
    assertNull(scoped.documentSummary(hidden));
    assertFalse(scoped.hierarchy(visible).isEmpty());
    UUID hiddenChunk =
        jdbc.queryForObject(
            "SELECT c.id FROM chunks c JOIN paragraphs p"
                + " ON p.id = c.paragraph_id WHERE p.document_id = ? LIMIT 1",
            UUID.class,
            hidden);
    assertTrue(scoped.chunk(hiddenChunk).isEmpty());
    assertTrue(retrieval.within(hidden, "budget", 1).isEmpty());
  }

  private InformationCatalogue catalogue() {
    return io.aeyer.plowshare.server.information.InformationFixtures.catalogue(
        jdbc, transactions, access, CLOCK);
  }

  @Test
  void migration_inspection_and_release_are_audited_and_keep_live_input_permissions() {
    var catalogue = catalogue();
    var alice =
        access.resolve(
            "alice",
            new InformationContext.Selection(InformationContext.Scope.PROJECT, "research", false));
    var revision =
        catalogue
            .admit(
                alice,
                UUID.randomUUID(),
                "reviewed-source",
                "Retained reviewed material".getBytes(),
                "text/plain",
                null)
            .revision();
    var conversations = new io.aeyer.plowshare.server.archive.ConversationStore(jdbc);
    String log =
        conversations
            .log(
                io.aeyer.plowshare.server.archive.Origin.SUBMISSION,
                io.aeyer.plowshare.protocol.Home.global(),
                "research",
                null,
                io.aeyer.plowshare.server.agents.Budget.of(5),
                null,
                null)
            .id();
    new io.aeyer.plowshare.server.archive.EntryStore(jdbc)
        .append(
            log,
            1,
            io.aeyer.plowshare.server.agents.LoggedEntry.utterance(
                "Reviewed payload", io.aeyer.plowshare.server.agents.Speaker.person("alice")));
    jdbc.update(
        "INSERT INTO information_quarantined_payloads VALUES(?,'fixture: unknown provenance')",
        log);
    assertThrows(CallerFault.class, () -> assignments.inspect("alice", log, "review"));
    assertThrows(CallerFault.class, () -> assignments.inspect("operator", log, " "));
    assertEquals(1, assignments.inspect("operator", log, "source audit", 1, 0).entries().size());
    assertTrue(assignments.inspect("operator", log, "source audit", 1, 1).entries().isEmpty());
    var inputs =
        new InformationJobs(
            new io.aeyer.plowshare.server.information.JdbcJobInformationRepository(jdbc),
            access,
            catalogue);
    assertFalse(inputs.logAllowed(log, "alice"));
    assignments.useWriteGates(
        new InformationWriteGates(
            new io.aeyer.plowshare.server.information.JdbcInformationGateRepository(jdbc, CLOCK),
            transactions,
            access,
            inputs,
            conversations,
            io.aeyer.plowshare.server.agents.LogStages.NONE,
            io.aeyer.plowshare.server.hooks.Hooks.NONE,
            io.aeyer.plowshare.server.harness.Harness.NONE));
    assertThrows(
        CallerFault.class,
        () ->
            assignments.release(
                "operator", log, "alice", alice.selection(), List.of(revision), "reviewed"));
    UUID request = UUID.randomUUID();
    assignments.release(
        "operator",
        log,
        "alice",
        alice.selection(),
        List.of(revision),
        "reviewed",
        request,
        "operator-session");
    assignments.release(
        "operator",
        log,
        "alice",
        alice.selection(),
        List.of(revision),
        "reviewed",
        request,
        "other-session");
    assertEquals(
        "alice",
        jdbc.queryForObject(
            "SELECT owner_handle FROM conversations WHERE id=?", String.class, log));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_payload_assignments WHERE payload_id=? AND action='release'",
            Integer.class,
            log));
    assertTrue(inputs.logAllowed(log, "alice"));
    assertFalse(inputs.logAllowed(log, "bob"));
    catalogue.availability(alice, revision, "withdrawn");
    assertFalse(inputs.logAllowed(log, "alice"));
  }

  @Test
  void restricted_logs_cannot_reappear_as_memories_or_digest_summaries() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var revision = admitted(catalogue, "alice", "restricted", "A private claim.").revision();
    var conversations = new io.aeyer.plowshare.server.archive.ConversationStore(jdbc);
    var home = io.aeyer.plowshare.protocol.Home.global();
    String log =
        conversations
            .log(
                io.aeyer.plowshare.server.archive.Origin.SUBMISSION,
                home,
                "research",
                null,
                io.aeyer.plowshare.server.agents.Budget.of(5),
                "alice",
                null)
            .id();
    var memory =
        io.aeyer.plowshare.protocol.Memory.formed(
            "restricted-memory",
            "Private claim",
            "source",
            "private body",
            new io.aeyer.plowshare.protocol.Provenance(CLOCK.instant(), "test", "fixture"),
            home);
    var memories = new io.aeyer.plowshare.server.archive.MemoryStore(jdbc);
    memories.save(memory);
    var digests = new io.aeyer.plowshare.server.archive.DigestStore(jdbc, transactions);
    digests.provenance(memory.id(), log, List.of(1));
    var entries = new io.aeyer.plowshare.server.archive.EntryStore(jdbc);
    entries.append(
        log,
        1,
        io.aeyer.plowshare.server.agents.LoggedEntry.utterance(
            "Private claim", io.aeyer.plowshare.server.agents.Speaker.person("alice")));
    entries.append(log, 1, io.aeyer.plowshare.server.agents.LoggedEntry.summary("Private summary"));
    assertFalse(digests.roots(home).isEmpty());
    new InformationJobs(
            new io.aeyer.plowshare.server.information.JdbcJobInformationRepository(jdbc),
            access,
            catalogue)
        .bind(log, alice, List.of(revision));
    memories.protectInformation();
    digests.protectInformation();
    assertTrue(memories.load(memory.id()).isEmpty());
    assertTrue(memories.index(home).isEmpty());
    assertTrue(memories.loadAll(home).isEmpty());
    assertTrue(digests.roots(home).isEmpty());
    assertTrue(digests.provenance(memory.id()).isEmpty());
    assertTrue(
        digests
            .source(new io.aeyer.plowshare.server.archive.DigestStore.Span(log, 0, 1))
            .isEmpty());
    catalogue.availability(alice, revision, "withdrawn");
    assertTrue(memories.load(memory.id()).isEmpty());
    assertTrue(digests.roots(home).isEmpty());
  }

  @Test
  void message_input_inheritance_keeps_original_selection_and_later_revocation() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var revision = admitted(catalogue, "alice", "message-source", "A retained claim.").revision();
    var conversations = new io.aeyer.plowshare.server.archive.ConversationStore(jdbc);
    String source =
        conversations
            .log(
                io.aeyer.plowshare.server.archive.Origin.SUBMISSION,
                io.aeyer.plowshare.protocol.Home.global(),
                "sender",
                null,
                io.aeyer.plowshare.server.agents.Budget.of(5),
                "alice",
                null)
            .id();
    String recipient =
        conversations
            .log(
                io.aeyer.plowshare.server.archive.Origin.BOARD,
                io.aeyer.plowshare.protocol.Home.of("research"),
                "reviewer",
                null,
                null,
                "alice",
                null)
            .id();
    var inputs =
        new InformationJobs(
            new io.aeyer.plowshare.server.information.JdbcJobInformationRepository(jdbc),
            access,
            catalogue);
    inputs.bind(source, alice, List.of(revision));
    transactions.inTransaction(
        () -> {
          inputs.inherit(source, recipient, "alice");
          return null;
        });
    transactions.inTransaction(
        () -> {
          inputs.inherit(source, recipient, "alice");
          return null;
        });
    assertEquals(List.of(revision), inputs.inputsOf(recipient, "alice"));
    assertEquals(
        "personal",
        jdbc.queryForObject(
            "SELECT scope FROM information_job_inputs WHERE job_id=?", String.class, recipient));
    assertFalse(inputs.logAllowed(recipient, "bob"));
    catalogue.availability(alice, revision, "withdrawn");
    assertFalse(inputs.logAllowed(recipient, "alice"));
    assertThrows(NotFoundFault.class, () -> inputs.inherit(source, recipient, "alice"));
  }

  @Test
  void report_findings_keep_unchecked_verdicts_and_pin_real_evidence_and_producing_runs() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var revision = admitted(catalogue, "alice", "claim-source", "A retained claim.").revision();
    var pipeline =
        pipeline(
            catalogue,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class),
            new InformationLifecycle.Gates() {});
    pipeline.drainOne();
    pipeline.drainOne();
    UUID evidence =
        catalogue.evidence(alice, revision, 0, 16, "A retained claim", "extracted-text:utf16");
    var conversations = new io.aeyer.plowshare.server.archive.ConversationStore(jdbc);
    String producer =
        conversations
            .log(
                io.aeyer.plowshare.server.archive.Origin.SUBMISSION,
                io.aeyer.plowshare.protocol.Home.global(),
                "research",
                null,
                io.aeyer.plowshare.server.agents.Budget.of(5),
                "alice",
                null)
            .id();
    var inputs =
        new InformationJobs(
            new io.aeyer.plowshare.server.information.JdbcJobInformationRepository(jdbc),
            access,
            catalogue);
    inputs.bind(producer, alice, List.of(revision));
    catalogue.useWriteGates(
        new InformationWriteGates(
            new io.aeyer.plowshare.server.information.JdbcInformationGateRepository(jdbc, CLOCK),
            transactions,
            access,
            inputs,
            conversations,
            io.aeyer.plowshare.server.agents.LogStages.NONE,
            io.aeyer.plowshare.server.hooks.Hooks.NONE,
            io.aeyer.plowshare.server.harness.Harness.NONE));
    var detail =
        io.aeyer.plowshare.server.information.InformationReportDetailsDecoder.from(
            Map.of(
                "objectives",
                List.of("Check the claim"),
                "findings",
                List.of(
                    Map.of(
                        "id",
                        "1",
                        "objective",
                        "Check the claim",
                        "claim",
                        "The source makes a claim.",
                        "support",
                        List.of(evidence.toString()),
                        "rationale",
                        "Source retained; independent critique unavailable.",
                        "verdict",
                        "not_checked")),
                "reviews",
                List.of(
                    Map.of(
                        "stage",
                        "critic",
                        "outcome",
                        "incomplete",
                        "text",
                        "Endpoint unavailable"))));
    UUID request = UUID.randomUUID();
    var report =
        catalogue.reportDetailsForSession(
            alice,
            request,
            "structured-report",
            "An unchecked finding.",
            List.of(revision),
            List.of(),
            null,
            "alice-session",
            detail,
            producer);
    assertEquals(
        report.revision(),
        catalogue
            .reportDetailsForSession(
                alice,
                request,
                "structured-report",
                "An unchecked finding.",
                List.of(revision),
                List.of(),
                null,
                "alice-session",
                detail,
                producer)
            .revision());
    assertEquals(
        producer,
        jdbc.queryForObject(
            "SELECT produced_by FROM information_reports WHERE revision_id=?",
            String.class,
            report.revision()));
    assertEquals(
        "not_checked",
        jdbc.queryForObject(
            "SELECT details->'findings'->0->>'verdict' FROM information_reports WHERE revision_id=?",
            String.class,
            report.revision()));
    assertEquals(
        evidence,
        jdbc.queryForObject(
            "SELECT evidence_id FROM information_report_citations WHERE report_revision=?",
            UUID.class,
            report.revision()));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_reports SET details='{}' WHERE revision_id=?",
                report.revision()));
    assertThrows(
        CallerFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationReportDetailsDecoder.from(
                Map.of(
                    "objectives",
                    List.of("A"),
                    "findings",
                    List.of(
                        Map.of(
                            "id",
                            "1",
                            "objective",
                            "B",
                            "claim",
                            "claim",
                            "rationale",
                            "unchecked",
                            "verdict",
                            "holds")))));
    assertThrows(
        CallerFault.class,
        () ->
            catalogue.report(
                alice,
                UUID.randomUUID(),
                "wrong-parent",
                "feedback",
                List.of(revision),
                List.of(),
                revision));
    catalogue.availability(alice, report.revision(), "deleted");
    assertEquals(
        "true",
        jdbc.queryForObject(
            "SELECT details->>'deleted' FROM information_reports WHERE revision_id=?",
            String.class,
            report.revision()));
  }

  private InformationLifecycle pipeline(
      InformationCatalogue catalogue,
      io.aeyer.plowshare.server.llm.EmbeddingClient embeddings,
      InformationLifecycle.Gates gates) {
    var store = new DocumentStore(jdbc, transactions);
    return new InformationLifecycle(
        new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
            jdbc, CLOCK, new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
        transactions,
        catalogue,
        InformationLifecycle.processing(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc,
                java.time.Clock.systemUTC(),
                new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
            transactions,
            catalogue,
            store,
            embeddings,
            new Chunking(new io.aeyer.plowshare.server.llm.tokens.FixtureTokenizer(4), 512, 2048),
            16,
            768,
            () -> null,
            new io.aeyer.plowshare.server.documents.DocumentsProperties()),
        gates);
  }

  private InformationCatalogue.Admission admitted(
      InformationCatalogue catalogue, String account, String name, String text) {
    return catalogue.admit(
        access.resolve(account, null),
        UUID.randomUUID(),
        name,
        text.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        "text/plain",
        null);
  }

  @Test
  void prepared_writes_gate_in_project_then_local_order_and_completed_receipts_never_refire() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var seen = new java.util.ArrayList<String>();
    var deny = new java.util.concurrent.atomic.AtomicBoolean(true);
    var project =
        new io.aeyer.plowshare.server.hooks.Hooks() {
          @Override
          public io.aeyer.plowshare.server.hooks.Gate stagePre(
              io.aeyer.plowshare.server.hooks.HookContext context,
              io.aeyer.plowshare.server.hooks.StageStart stage) {
            assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            seen.add("project.pre");
            return io.aeyer.plowshare.server.hooks.Gate.NOTHING;
          }

          @Override
          public io.aeyer.plowshare.server.hooks.Gate stagePost(
              io.aeyer.plowshare.server.hooks.HookContext context,
              io.aeyer.plowshare.server.hooks.StageDone stage) {
            seen.add("project.post");
            return io.aeyer.plowshare.server.hooks.Gate.NOTHING;
          }
        };
    var local =
        new io.aeyer.plowshare.server.hooks.Hooks() {
          @Override
          public io.aeyer.plowshare.server.hooks.Gate stagePre(
              io.aeyer.plowshare.server.hooks.HookContext context,
              io.aeyer.plowshare.server.hooks.StageStart stage) {
            seen.add("local.pre");
            return io.aeyer.plowshare.server.hooks.Gate.NOTHING;
          }

          @Override
          public io.aeyer.plowshare.server.hooks.Gate stagePost(
              io.aeyer.plowshare.server.hooks.HookContext context,
              io.aeyer.plowshare.server.hooks.StageDone stage) {
            assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            seen.add("local.post");
            return deny.get()
                ? new io.aeyer.plowshare.server.hooks.Gate(
                    "review prepared transition", List.of(), List.of())
                : io.aeyer.plowshare.server.hooks.Gate.NOTHING;
          }
        };
    var opened = new java.util.ArrayList<io.aeyer.plowshare.server.agents.LogStages.LogOpened>();
    var gates =
        new InformationWriteGates(
            new io.aeyer.plowshare.server.information.JdbcInformationGateRepository(jdbc, CLOCK),
            transactions,
            access,
            new InformationJobs(
                new io.aeyer.plowshare.server.information.JdbcJobInformationRepository(jdbc),
                access,
                catalogue),
            new io.aeyer.plowshare.server.archive.ConversationStore(jdbc),
            new io.aeyer.plowshare.server.agents.LogStages() {
              @Override
              public void opened(LogOpened log) {
                opened.add(log);
              }
            },
            io.aeyer.plowshare.server.hooks.Hooks.chain(project, local),
            io.aeyer.plowshare.server.harness.Harness.NONE);
    catalogue.useWriteGates(gates);
    UUID blocked = UUID.randomUUID();
    assertThrows(
        CallerFault.class,
        () ->
            catalogue.admitForSession(
                alice,
                blocked,
                "gated",
                "A claim.".getBytes(),
                "text/plain",
                null,
                "alice-session"));
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM information_resources", Integer.class));
    assertEquals(List.of("project.pre", "local.pre", "project.post", "local.post"), seen);
    assertEquals("alice-session", opened.getFirst().session());
    deny.set(false);
    seen.clear();
    UUID request = UUID.randomUUID();
    var first =
        catalogue.admitForSession(
            alice, request, "gated", "A claim.".getBytes(), "text/plain", null, "alice-session");
    var replay =
        catalogue.admitForSession(
            alice, request, "gated", "A claim.".getBytes(), "text/plain", null, "bob-session");
    assertEquals(first.revision(), replay.revision());
    assertEquals(4, seen.size());
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM information_resources", Integer.class));
    assertThrows(
        CallerFault.class,
        () ->
            catalogue.admitForSession(
                alice,
                blocked,
                "gated",
                "A claim.".getBytes(),
                "text/plain",
                null,
                "alice-session"));
  }

  @Test
  void exclusion_hides_discovery_but_keeps_evidence_and_restore_does_not_reverse_it() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var revision = admitted(catalogue, "alice", "exclude", "An exact retained claim.").revision();
    var pipeline =
        pipeline(
            catalogue,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class),
            new InformationLifecycle.Gates() {});
    pipeline.drainOne();
    pipeline.drainOne();
    UUID evidence =
        catalogue.evidence(alice, revision, 0, 2, "An", "extracted-text:utf16", UUID.randomUUID());
    catalogue.availability(alice, revision, "excluded");
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(alice, 10, 0))
            .isEmpty());
    assertEquals(
        "An",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.evidence(alice, evidence))
            .get("quote"));
    var scoped = new DocumentStore(jdbc, transactions).scoped(access, alice);
    assertEquals(0, scoped.count(null));
    assertTrue(scoped.find(revision).isPresent());
    catalogue.availability(alice, revision, "withdrawn");
    catalogue.availability(alice, revision, "active");
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(alice, 10, 0))
            .isEmpty());
    assertEquals(
        "An",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.evidence(alice, evidence))
            .get("quote"));
    catalogue.availability(alice, revision, "included");
    assertEquals(1, scoped.count(null));
  }

  @Test
  void a_model_post_gate_keeps_its_paid_response_and_retry_rechecks_without_another_call() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var revision = admitted(catalogue, "alice", "model-hook", "A retained statement.").revision();
    var lifecycle =
        pipeline(
            catalogue,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class),
            new InformationLifecycle.Gates() {});
    var lease = lifecycle.claim();
    var denied = new java.util.concurrent.atomic.AtomicBoolean(true);
    var preCalls = new java.util.concurrent.atomic.AtomicInteger();
    var hooks =
        new io.aeyer.plowshare.server.hooks.Hooks() {
          @Override
          public io.aeyer.plowshare.server.hooks.Gate stagePre(
              io.aeyer.plowshare.server.hooks.HookContext context,
              io.aeyer.plowshare.server.hooks.StageStart stage) {
            assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            assertEquals("summarise", context.document().operation());
            assertEquals(revision.toString(), context.document().revision());
            assertEquals("paragraph_summariser", context.document().stage());
            preCalls.incrementAndGet();
            return new io.aeyer.plowshare.server.hooks.Gate(
                null, List.of("Retain provenance."), List.of());
          }

          @Override
          public io.aeyer.plowshare.server.hooks.Gate stagePost(
              io.aeyer.plowshare.server.hooks.HookContext context,
              io.aeyer.plowshare.server.hooks.StageDone stage) {
            assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            return denied.get()
                ? new io.aeyer.plowshare.server.hooks.Gate(
                    "human review needed", List.of(), List.of())
                : io.aeyer.plowshare.server.hooks.Gate.NOTHING;
          }
        };
    var models =
        new InformationModelStages(
            new io.aeyer.plowshare.server.information.JdbcInformationStageRepository(jdbc),
            transactions,
            catalogue,
            new InformationJobs(
                new io.aeyer.plowshare.server.information.JdbcJobInformationRepository(jdbc),
                access,
                catalogue),
            hooks,
            io.aeyer.plowshare.server.harness.Harness.NONE);
    var stages = models.forLease(lease, () -> lifecycle.requireLease(lease));
    var log =
        new io.aeyer.plowshare.server.archive.ConversationStore(jdbc)
            .log(
                io.aeyer.plowshare.server.archive.Origin.SUBMISSION,
                io.aeyer.plowshare.protocol.Home.global(),
                "document_pipeline",
                null,
                io.aeyer.plowshare.server.agents.Budget.of(1000),
                "alice",
                null)
            .id();
    new InformationJobs(
            new io.aeyer.plowshare.server.information.JdbcJobInformationRepository(jdbc),
            access,
            catalogue)
        .bind(log, alice, List.of(revision));
    var definition =
        new io.aeyer.plowshare.server.agents.AgentDefinition(
            "paragraph_summariser",
            "summarises",
            "reasoning",
            List.of(),
            List.of(),
            List.of(),
            1,
            1,
            "Summarise evidence.");
    var paid = new java.util.concurrent.atomic.AtomicInteger();
    java.util.function.Function<String, io.aeyer.plowshare.server.agents.Outcome> work =
        task -> {
          assertTrue(task.contains("Retain provenance."));
          paid.incrementAndGet();
          return new io.aeyer.plowshare.server.agents.Outcome(
              io.aeyer.plowshare.server.agents.Outcome.Ending.ANSWERED,
              "A retained summary.",
              1,
              1,
              "");
        };
    assertThrows(
        io.aeyer.plowshare.server.documents.DocumentStages.Blocked.class,
        () ->
            stages.run(
                definition,
                "Source paragraph.",
                log,
                io.aeyer.plowshare.protocol.Home.global(),
                "alice",
                work));
    assertEquals(
        "blocked",
        jdbc.queryForObject(
            "SELECT state FROM information_model_steps WHERE revision_id=?",
            String.class,
            revision));
    denied.set(false);
    var resumed =
        stages.run(
            definition,
            "Source paragraph.",
            log,
            io.aeyer.plowshare.protocol.Home.global(),
            "alice",
            work);
    assertEquals("A retained summary.", resumed.text());
    assertEquals(0, resumed.modelCalls());
    assertEquals(1, paid.get());
    assertEquals(2, preCalls.get());
    assertEquals(
        "ready",
        jdbc.queryForObject(
            "SELECT state FROM information_model_steps WHERE revision_id=?",
            String.class,
            revision));
  }

  @Test
  void management_gates_can_deny_restore_but_cannot_delay_withdrawal() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var revision =
        admitted(catalogue, "alice", "management-hook", "A retained statement.").revision();
    var observed = new java.util.ArrayList<String>();
    catalogue.withGates(
        new InformationLifecycle.Gates() {
          @Override
          public io.aeyer.plowshare.server.hooks.Gate before(InformationLifecycle.Lease lease) {
            assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            observed.add(lease.stage());
            if (lease.stage().equals("withdrawn")) {
              assertEquals("withdrawn", catalogue.row(revision).availability());
              throw new IllegalStateException("hook unavailable");
            }
            return new io.aeyer.plowshare.server.hooks.Gate("review needed", List.of(), List.of());
          }
        });
    catalogue.availability(alice, revision, "withdrawn");
    assertThrows(NotFoundFault.class, () -> catalogue.bytes(alice, revision));
    assertThrows(CallerFault.class, () -> catalogue.availability(alice, revision, "active"));
    assertEquals("withdrawn", catalogue.row(revision).availability());
    assertEquals(List.of("withdrawn", "active"), observed);
    catalogue.withGates(new InformationLifecycle.Gates() {});
    catalogue.availability(alice, revision, "deleted");
    assertThrows(CallerFault.class, () -> catalogue.availability(alice, revision, "active"));
  }

  @Test
  void management_replays_do_not_repeat_mutations_or_hooks_and_a_key_cannot_change_its_command() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var revision = admitted(catalogue, "alice", "receipt", "A retained statement.").revision();
    var hooks = new java.util.concurrent.atomic.AtomicInteger();
    catalogue.withGates(
        new InformationLifecycle.Gates() {
          @Override
          public io.aeyer.plowshare.server.hooks.Gate before(InformationLifecycle.Lease lease) {
            hooks.incrementAndGet();
            return io.aeyer.plowshare.server.hooks.Gate.NOTHING;
          }

          @Override
          public io.aeyer.plowshare.server.hooks.Gate after(InformationLifecycle.Lease lease) {
            hooks.incrementAndGet();
            return io.aeyer.plowshare.server.hooks.Gate.NOTHING;
          }
        });
    UUID request = UUID.randomUUID();
    catalogue.availability(alice, revision, "withdrawn", request);
    assertEquals(2L, catalogue.generation(revision));
    assertEquals(2, hooks.get());
    catalogue.availability(alice, revision, "withdrawn", request);
    assertEquals(2L, catalogue.generation(revision));
    assertEquals(2, hooks.get());
    assertThrows(
        CallerFault.class, () -> catalogue.availability(alice, revision, "active", request));
    assertThrows(
        NotFoundFault.class,
        () -> catalogue.availability(access.resolve("bob", null), revision, "withdrawn", request));
    assertEquals(2L, catalogue.generation(revision));
    assertEquals(
        "completed",
        jdbc.queryForObject(
            "SELECT state FROM information_commands WHERE account='alice' AND request_id=?",
            String.class,
            request));
  }

  @Test
  void post_denial_never_publishes_a_grant_or_restores_withdrawn_content() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var revision = admitted(catalogue, "alice", "publication", "A retained statement.").revision();
    var embedding = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class);
    var pipeline = pipeline(catalogue, embedding, new InformationLifecycle.Gates() {});
    pipeline.drainOne();
    pipeline.drainOne();
    catalogue.withGates(
        new InformationLifecycle.Gates() {
          @Override
          public io.aeyer.plowshare.server.hooks.Gate after(InformationLifecycle.Lease lease) {
            assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            return new io.aeyer.plowshare.server.hooks.Gate(
                "publication review needed", List.of(), List.of());
          }
        });
    assertThrows(
        CallerFault.class, () -> catalogue.share(alice, revision, true, UUID.randomUUID()));
    assertThrows(
        NotFoundFault.class,
        () -> catalogue.requireReadable(access.resolve("bob", null), revision));
    assertEquals(
        "personal",
        jdbc.queryForObject(
            "SELECT visibility FROM information_document_policies WHERE document_id=?",
            String.class,
            revision));
    catalogue.availability(alice, revision, "withdrawn", UUID.randomUUID());
    assertThrows(
        CallerFault.class,
        () -> catalogue.availability(alice, revision, "active", UUID.randomUUID()));
    assertEquals("withdrawn", catalogue.row(revision).availability());
  }

  @Test
  void event_delivery_resumes_after_failure_without_losing_committed_invalidation() {
    var catalogue = catalogue();
    var revision = admitted(catalogue, "alice", "events", "A retained statement.").revision();
    jdbc.update("UPDATE information_event_dispatch SET cursor=0,token=NULL,lease_until=NULL");
    var attempts = new java.util.ArrayList<Object>();
    var first =
        new InformationEventPublisher(
            new JdbcInformationEventRepository(jdbc, transactions),
            (account, body) -> {
              attempts.add(body);
              throw new IllegalStateException("socket stopped");
            },
            CLOCK);
    assertThrows(IllegalStateException.class, first::drainOne);
    assertEquals(
        0L, jdbc.queryForObject("SELECT cursor FROM information_event_dispatch", Long.class));
    jdbc.update("UPDATE information_event_dispatch SET lease_until=now()-interval '1 second'");
    var delivered = new java.util.ArrayList<Object>();
    var restarted =
        new InformationEventPublisher(
            new JdbcInformationEventRepository(jdbc, transactions),
            (account, body) -> {
              assertEquals("alice", account);
              delivered.add(body);
            },
            java.time.Clock.systemUTC());
    assertTrue(restarted.drainOne());
    assertEquals(attempts, delivered);
    assertFalse(restarted.drainOne());
    assertTrue(
        jdbc.queryForObject("SELECT cursor FROM information_event_dispatch", Long.class) > 0);
  }

  @Test
  void failed_acquisition_is_durable_and_post_gate_retry_reuses_original_response_bytes() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var fetcher = org.mockito.Mockito.mock(io.aeyer.plowshare.server.fetch.PageFetcher.class);
    var postDenied = new java.util.concurrent.atomic.AtomicBoolean(true);
    var hooks =
        new io.aeyer.plowshare.server.hooks.Hooks() {
          @Override
          public io.aeyer.plowshare.server.hooks.Gate stagePre(
              io.aeyer.plowshare.server.hooks.HookContext context,
              io.aeyer.plowshare.server.hooks.StageStart stage) {
            assertEquals("https://source.example/paper", context.document().sourceUri());
            assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            return io.aeyer.plowshare.server.hooks.Gate.NOTHING;
          }

          @Override
          public io.aeyer.plowshare.server.hooks.Gate stagePost(
              io.aeyer.plowshare.server.hooks.HookContext context,
              io.aeyer.plowshare.server.hooks.StageDone stage) {
            assertNotNull(context.document().revision());
            return postDenied.get()
                ? new io.aeyer.plowshare.server.hooks.Gate(
                    "review the captured source", List.of(), List.of())
                : io.aeyer.plowshare.server.hooks.Gate.NOTHING;
          }
        };
    java.util.function.Supplier<InformationAcquisitions> service =
        () ->
            new InformationAcquisitions(
                new io.aeyer.plowshare.server.information.JdbcAcquisitionRepository(jdbc, CLOCK),
                transactions,
                access,
                catalogue,
                fetcher,
                () -> 12,
                new io.aeyer.plowshare.server.archive.ConversationStore(jdbc),
                io.aeyer.plowshare.server.agents.LogStages.NONE,
                hooks,
                io.aeyer.plowshare.server.harness.Harness.NONE,
                new InformationJobs(
                    new io.aeyer.plowshare.server.information.JdbcJobInformationRepository(jdbc),
                    access,
                    catalogue));
    var first = service.get();
    UUID request = UUID.randomUUID();
    var submitted = first.submit(alice, request, "https://source.example/paper", "paper", null);
    UUID ticket = submitted.id();
    assertEquals("queued", submitted.state());
    org.mockito.Mockito.verifyNoInteractions(fetcher);
    assertEquals(
        ticket, first.submit(alice, request, "https://source.example/paper", "paper", null).id());
    assertThrows(
        CallerFault.class,
        () -> first.submit(alice, request, "https://changed.example/paper", "paper", null));
    org.mockito.Mockito.when(fetcher.fetch("https://source.example/paper"))
        .thenReturn(
            io.aeyer.plowshare.server.fetch.FetchAnswer.failed(
                "https://source.example/paper",
                io.aeyer.plowshare.server.fetch.FetchFailure.REMOTE_STATUS,
                "HTTP 503"));
    first.drainOne();
    assertEquals("failed", first.status(alice, ticket).state());
    assertThrows(NotFoundFault.class, () -> first.status(access.resolve("bob", null), ticket));
    byte[] original = "A captured source statement.".getBytes();
    org.mockito.Mockito.when(fetcher.fetch("https://source.example/paper"))
        .thenReturn(
            new io.aeyer.plowshare.server.fetch.FetchAnswer(
                "https://source.example/paper",
                null,
                null,
                "",
                original,
                "text/plain",
                "https://source.example/final"));
    var restarted = service.get();
    restarted.retry(alice, ticket);
    restarted.drainOne();
    var captured = restarted.status(alice, ticket);
    assertEquals("blocked", captured.state());
    UUID revision = captured.revisionId();
    assertArrayEquals(original, catalogue.bytes(alice, revision));
    assertEquals(
        12,
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(alice, revision))
            .get("allowance_total"));
    assertFalse(
        pipeline(
                catalogue,
                org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class),
                new InformationLifecycle.Gates() {})
            .drainOne());
    postDenied.set(false);
    restarted.retry(alice, ticket);
    restarted.drainOne();
    assertEquals("succeeded", restarted.status(alice, ticket).state());
    assertTrue(
        pipeline(
                catalogue,
                org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class),
                new InformationLifecycle.Gates() {})
            .drainOne());
    org.mockito.Mockito.verify(fetcher, org.mockito.Mockito.times(2))
        .fetch("https://source.example/paper");
    assertEquals(
        "https://source.example/final",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(alice, revision))
            .get("source_uri"));
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(alice, 100, 0))
            .stream()
            .anyMatch(source -> revision.equals(source.get("id"))),
        "Fetched content is a catalogue document before any research report cites it");
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_report_citations WHERE evidence_id IN (SELECT id FROM information_evidence WHERE revision_id=?)",
            Integer.class,
            revision));
  }

  @Test
  void the_complete_cascade_keeps_durable_spending_and_resumes_only_missing_summaries() {
    var catalogue = catalogue().withAllowance(() -> 2);
    var alice = access.resolve("alice", null);
    var revision = admitted(catalogue, "alice", "complete", "A retained budget claim.").revision();
    // Isolate the prose cascade from the separate automatic tagging worker.
    jdbc.update(
        "UPDATE information_steps SET state='skipped' WHERE revision_id=? AND stage IN ('autoTag','tagGroups')",
        revision);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var transport =
        new io.aeyer.plowshare.server.llm.dispatch.LlmTransport() {
          @Override
          public String poolName() {
            return "information-test";
          }

          @Override
          public void close() {}

          @Override
          public io.aeyer.plowshare.server.llm.dispatch.Embeddings embed(
              String model, List<String> texts) {
            throw new UnsupportedOperationException();
          }

          @Override
          public io.aeyer.plowshare.server.llm.dispatch.Completion stream(
              String model,
              List<io.aeyer.plowshare.server.llm.dispatch.ChatMessage> messages,
              io.aeyer.plowshare.server.llm.dispatch.Sampling sampling,
              List<io.aeyer.plowshare.server.llm.dispatch.ToolSchema> tools,
              io.aeyer.plowshare.server.llm.dispatch.Deltas sink,
              java.util.function.BooleanSupplier cancelled) {
            return complete(model, messages, sampling, tools);
          }

          @Override
          public io.aeyer.plowshare.server.llm.dispatch.Completion complete(
              String model,
              List<io.aeyer.plowshare.server.llm.dispatch.ChatMessage> messages,
              io.aeyer.plowshare.server.llm.dispatch.Sampling sampling,
              List<io.aeyer.plowshare.server.llm.dispatch.ToolSchema> tools) {
            assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            calls.incrementAndGet();
            return new io.aeyer.plowshare.server.llm.dispatch.Completion(
                "A grounded summary.",
                "stop",
                io.aeyer.plowshare.server.llm.dispatch.TokenUsage.UNKNOWN,
                List.of());
          }
        };
    var dispatcher =
        new io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher(
            List.of(
                new io.aeyer.plowshare.server.llm.dispatch.LlmPool(
                    "information-test",
                    List.of("model"),
                    java.util.Map.of("reasoning", "model"),
                    4,
                    1,
                    java.time.Duration.ofSeconds(5),
                    transport)),
            new io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger());
    var definitions =
        new java.util.LinkedHashMap<String, io.aeyer.plowshare.server.agents.AgentDefinition>();
    for (String name :
        List.of(
            io.aeyer.plowshare.server.documents.Summariser.PARAGRAPH,
            io.aeyer.plowshare.server.documents.Summariser.SPAN,
            io.aeyer.plowshare.server.documents.Summariser.SECTION,
            io.aeyer.plowshare.server.documents.Summariser.CHAPTER,
            io.aeyer.plowshare.server.documents.Summariser.DOCUMENT))
      definitions.put(
          name,
          new io.aeyer.plowshare.server.agents.AgentDefinition(
              name,
              "summarises",
              "reasoning",
              List.of(),
              List.of(),
              List.of(),
              1,
              1,
              "Summarise the given evidence."));
    var registry = new io.aeyer.plowshare.server.agents.AgentRegistry(definitions);
    var runtime = new io.aeyer.plowshare.server.agents.JobRuntime(dispatcher, List.of());
    var store = new DocumentStore(jdbc, transactions);
    var summariser =
        new io.aeyer.plowshare.server.documents.Summariser(store, runtime, () -> registry, 12);
    var embeddings = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class);
    org.mockito.Mockito.when(embeddings.embedAll(org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(
            call ->
                ((List<?>) call.getArgument(0))
                    .stream()
                        .map(
                            t -> {
                              float[] v = new float[768];
                              v[0] = 1;
                              return v;
                            })
                        .toList());
    org.mockito.Mockito.when(embeddings.embed(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            call -> {
              float[] v = new float[768];
              v[0] = 1;
              return v;
            });
    var processor =
        InformationLifecycle.processing(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc,
                java.time.Clock.systemUTC(),
                new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
            transactions,
            catalogue,
            store,
            embeddings,
            new Chunking(new io.aeyer.plowshare.server.llm.tokens.FixtureTokenizer(4), 512, 2048),
            16,
            768,
            () -> summariser,
            new io.aeyer.plowshare.server.documents.DocumentsProperties());
    var first =
        new InformationLifecycle(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc, CLOCK, new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
            transactions,
            catalogue,
            processor,
            new InformationLifecycle.Gates() {});
    first.drainOne();
    first.drainOne();
    first.drainOne();
    first.drainOne();
    assertEquals(2, calls.get());
    assertEquals(
        2,
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(alice, revision))
            .get("allowance_spent"));
    assertEquals(
        "failed",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND stage='summarise'",
            String.class,
            revision));
    assertThrows(CallerFault.class, () -> catalogue.allowance(alice, revision, 1));
    catalogue.allowance(alice, revision, 10);
    catalogue.retry(alice, revision);
    var recovered =
        new InformationLifecycle(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc, CLOCK, new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
            transactions,
            catalogue,
            processor,
            new InformationLifecycle.Gates() {});
    recovered.drainOne();
    recovered.drainOne();
    assertEquals(4, calls.get());
    assertEquals(
        4,
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(alice, revision))
            .get("allowance_spent"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_steps WHERE revision_id=? AND stage NOT IN ('autoTag','tagGroups') AND state<>'ready'",
            Integer.class,
            revision));
    assertEquals(0, store.unsummarisedUnits(revision));
    assertNotNull(store.documentSummary(revision));
    assertFalse(recovered.drainOne());
    org.mockito.Mockito.verify(embeddings, org.mockito.Mockito.times(1))
        .embedAll(org.mockito.ArgumentMatchers.anyList());
    catalogue.withAllowance(() -> 20);
    UUID evidence =
        catalogue.evidence(alice, revision, 0, 1, "A", "extracted-text:utf16", UUID.randomUUID());
    UUID reportRequest = UUID.randomUUID();
    var report =
        catalogue.report(
            alice,
            reportRequest,
            "retained-report",
            "The retained claim is documented.",
            List.of(revision),
            List.of(evidence),
            null);
    jdbc.update(
        "UPDATE information_steps SET state='skipped' WHERE revision_id=? AND stage IN ('autoTag','tagGroups')",
        report.revision());
    assertThrows(CallerFault.class, () -> catalogue.finalise(alice, report.revision()));
    for (int step = 0; step < 5; step++) assertTrue(recovered.drainOne());
    catalogue.finalise(alice, report.revision());
    assertEquals(
        report.revision(),
        catalogue
            .report(
                alice,
                reportRequest,
                "retained-report",
                "The retained claim is documented.",
                List.of(revision),
                List.of(evidence),
                null)
            .revision());
    var conversations = new io.aeyer.plowshare.server.archive.ConversationStore(jdbc);
    var reader =
        conversations.log(
            io.aeyer.plowshare.server.archive.Origin.SUBMISSION,
            io.aeyer.plowshare.protocol.Home.global(),
            "reader",
            null,
            io.aeyer.plowshare.server.agents.Budget.of(10),
            "alice",
            null);
    var inputs =
        new InformationJobs(
            new io.aeyer.plowshare.server.information.JdbcJobInformationRepository(jdbc),
            access,
            catalogue);
    var tool =
        new io.aeyer.plowshare.server.agents.InformationTool(false, () -> catalogue)
            .forRun(access, inputs, "alice", reader.id());
    assertTrue(
        tool.run(
                "{\"operation\":\"evidence\",\"evidence\":\"" + evidence + "\"}",
                io.aeyer.plowshare.protocol.Home.global())
            .contains("extracted-text:utf16"));
    var feedback =
        catalogue.report(
            alice,
            UUID.randomUUID(),
            "retained-report",
            "Changed objective: check the contrary evidence.",
            List.of(revision),
            List.of(evidence),
            report.revision());
    jdbc.update(
        "UPDATE information_steps SET state='skipped' WHERE revision_id=? AND stage IN ('autoTag','tagGroups')",
        feedback.revision());
    for (int step = 0; step < 5; step++) assertTrue(recovered.drainOne());
    assertEquals(report.resource(), feedback.resource());
    assertNotEquals(report.revision(), feedback.revision());
    assertEquals(
        report.revision(),
        ((Map<?, ?>)
                io.aeyer.plowshare.server.information.InformationFixtures.view(
                        catalogue.status(alice, feedback.revision()))
                    .get("report"))
            .get("feedback_revision"));
    var newer = admitted(catalogue, "alice", "complete", "A changed origin claim.");
    recovered.drainOne();
    recovered.drainOne();
    assertNotEquals(revision, newer.revision());
    assertEquals(
        "A",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.evidence(alice, evidence))
            .get("quote"));
    catalogue.availability(alice, revision, "deleted");
    assertThrows(
        NotFoundFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.evidence(alice, evidence)));
    assertThrows(NotFoundFault.class, () -> catalogue.requireReadable(alice, feedback.revision()));
    assertFalse(inputs.logAllowed(reader.id(), "alice"));
  }

  @Test
  void expired_leases_resume_with_a_new_token_and_always_release_hooks() {
    var catalogue = catalogue();
    var admitted = admitted(catalogue, "alice", "lease", "Retained work survives a process.");
    var old =
        pipeline(
                catalogue,
                org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class),
                new InformationLifecycle.Gates() {})
            .claim();
    assertEquals("extract", old.stage());
    assertNull(
        pipeline(
                catalogue,
                org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class),
                new InformationLifecycle.Gates() {})
            .claim());
    var later = Clock.fixed(CLOCK.instant().plusSeconds(301), ZoneOffset.UTC);
    var released = new java.util.concurrent.atomic.AtomicInteger();
    var recovered =
        new InformationLifecycle(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc, later, new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
            transactions,
            catalogue,
            (lease, cancelled, fence) -> {
              assertEquals(2, lease.attempt());
              assertNotEquals(old.token(), lease.token());
              assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              throw new IllegalStateException("conversion failed after recovery");
            },
            new InformationLifecycle.Gates() {
              @Override
              public void finished(InformationLifecycle.Lease lease) {
                released.incrementAndGet();
              }
            });
    assertTrue(recovered.drainOne());
    assertEquals(1, released.get());
    assertEquals(
        "failed",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND stage='extract'",
            String.class,
            admitted.revision()));
    assertThrows(
        InformationLifecycle.StaleLease.class,
        () ->
            transactions.inTransaction(
                () -> {
                  recovered.requireLease(old);
                  return null;
                }));
    assertArrayEquals(
        "Retained work survives a process.".getBytes(),
        catalogue.bytes(access.resolve("alice", null), admitted.revision()));
  }

  @Test
  void rebuild_invalidates_only_downstream_work_and_keeps_evidence_identity() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var revision = admitted(catalogue, "alice", "rebuild", "A retained statement.").revision();
    var embeddings = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class);
    org.mockito.Mockito.when(embeddings.embedAll(org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(
            call ->
                ((List<?>) call.getArgument(0))
                    .stream()
                        .map(
                            t -> {
                              float[] v = new float[768];
                              v[0] = 1;
                              return v;
                            })
                        .toList());
    var pipeline = pipeline(catalogue, embeddings, new InformationLifecycle.Gates() {});
    pipeline.drainOne();
    pipeline.drainOne();
    pipeline.drainOne();
    pipeline.drainOne();
    UUID paragraph =
        jdbc.queryForObject(
            "SELECT id FROM paragraphs WHERE document_id=? LIMIT 1", UUID.class, revision);
    UUID evidence = catalogue.evidence(alice, revision, 2, 10, "retained", "extracted-text:utf16");
    catalogue.rebuild(alice, revision, "embed");
    assertEquals(2L, catalogue.generation(revision));
    assertEquals(
        "ready",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND generation=2 AND stage='derive'",
            String.class,
            revision));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=? AND c.embedding IS NOT NULL",
            Integer.class,
            revision));
    assertEquals(
        paragraph,
        jdbc.queryForObject(
            "SELECT id FROM paragraphs WHERE document_id=? LIMIT 1", UUID.class, revision));
    assertEquals(
        "retained",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.evidence(alice, evidence))
            .get("quote"));
    assertThrows(CallerFault.class, () -> catalogue.rebuild(alice, revision, "extract"));
    assertThrows(
        NotFoundFault.class,
        () -> catalogue.rebuild(access.resolve("bob", null), revision, "embed"));
    catalogue.allowance(alice, revision, 25);
    assertEquals(
        25,
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(alice, revision))
            .get("allowance_total"));
    assertEquals(
        "retained",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.window(alice, revision, 2, 8))
            .get("text"));
    assertThrows(
        CallerFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.window(alice, revision, 0, 32769)));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_revisions SET source_uri='https://changed.invalid' WHERE id=?",
                revision));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_revisions SET source_bytes=? WHERE id=?",
                "replaced".getBytes(),
                revision));
    var feed =
        io.aeyer.plowshare.server.information.InformationFixtures.view(
            catalogue.events(alice, 0, 100));
    assertFalse(((List<?>) feed.get("events")).isEmpty());
    assertTrue(
        ((List<?>)
                io.aeyer.plowshare.server.information.InformationFixtures.view(
                        catalogue.events(access.resolve("bob", null), 0, 100))
                    .get("events"))
            .isEmpty());
    assertTrue(
        ((List<?>)
                io.aeyer.plowshare.server.information.InformationFixtures.view(
                        catalogue.events(alice, ((Number) feed.get("cursor")).longValue(), 100))
                    .get("events"))
            .isEmpty());
  }

  @Test
  void an_embedding_space_change_is_visible_and_repair_preserves_paid_summaries() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var model = new java.util.concurrent.atomic.AtomicReference<>("old-model");
    catalogue.useConfiguration(
        () ->
            Map.of(
                "extract",
                "extract-v1",
                "derive",
                "derive-v1",
                "embed",
                InformationConfiguration.embedding(model.get(), 768),
                "summarise",
                "summarise-v1",
                "summary_embed",
                InformationConfiguration.embedding(model.get(), 768)));
    var revision = admitted(catalogue, "alice", "space", "A retained claim.").revision();
    var embeddings = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class);
    float[] vector = new float[768];
    vector[0] = 1;
    org.mockito.Mockito.when(embeddings.embed(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(vector);
    org.mockito.Mockito.when(embeddings.embedAll(org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(call -> ((List<?>) call.getArgument(0)).stream().map(t -> vector).toList());
    var pipeline = pipeline(catalogue, embeddings, new InformationLifecycle.Gates() {});
    pipeline.drainOne();
    pipeline.drainOne();
    pipeline.drainOne();
    jdbc.update(
        "UPDATE documents SET summary='Paid summary',summary_embedding=CAST(? AS vector) WHERE id=?",
        java.util.Arrays.toString(vector),
        revision);
    jdbc.update("UPDATE paragraphs SET summary='Paid paragraph' WHERE document_id=?", revision);
    jdbc.update(
        "UPDATE information_steps SET state='ready',fingerprint=CASE WHEN stage='summarise' THEN 'summarise-v1' ELSE ? END WHERE revision_id=? AND stage IN ('summarise','summary_embed')",
        InformationConfiguration.embedding(model.get(), 768),
        revision);
    var store = new DocumentStore(jdbc, transactions);
    assertEquals(
        1, store.embeddingSpace(model.get(), 768).scoped(access, alice).coverage().searchable());
    model.set("new-model");
    var current = store.embeddingSpace(model.get(), 768).scoped(access, alice);
    assertEquals(0, current.coverage().searchable());
    assertEquals(1, current.coverage().unsearchable());
    assertEquals(0, current.ranking().rankable());
    assertEquals(1, current.ranking().unranked());
    assertTrue(
        new RetrievalService(current, embeddings, model.get(), 768)
            .rank("claim", 10)
            .documents()
            .isEmpty());
    var steps =
        (List<?>)
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                    catalogue.status(alice, revision))
                .get("steps");
    assertFalse(
        (Boolean)
            steps.stream()
                .map(step -> (Map<?, ?>) step)
                .filter(step -> step.get("stage").equals("embed"))
                .findFirst()
                .orElseThrow()
                .get("compatible"));
    catalogue.rebuild(alice, revision, "embed");
    assertEquals("Paid summary", store.documentSummary(revision));
    assertEquals(
        "ready",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND generation=2 AND stage='summarise'",
            String.class,
            revision));
    pipeline.drainOne();
    pipeline.drainOne();
    assertEquals(1, current.coverage().searchable());
    assertEquals(1, current.ranking().rankable());
    assertEquals("Paid summary", store.documentSummary(revision));
  }

  @Test
  void intake_is_idempotent_scoped_and_retains_bytes_before_processing() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    UUID request = UUID.randomUUID();
    byte[] bytes = "A retained budget.".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var first = catalogue.admit(alice, request, "same", bytes, "text/plain", null);
    assertTrue(first.created());
    var replay = catalogue.admit(alice, request, "same", bytes, "text/plain", null);
    assertEquals(first.revision(), replay.revision());
    assertFalse(replay.created());
    assertArrayEquals(bytes, catalogue.bytes(alice, first.revision()));
    assertEquals(
        1,
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(alice, 20, 0))
            .size());
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(access.resolve("bob", null), 20, 0))
            .isEmpty());
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(
                    access.resolve("alice", InformationContext.Selection.shared()), 20, 0))
            .isEmpty());
    assertThrows(
        CallerFault.class,
        () -> catalogue.admit(alice, request, "same", "changed".getBytes(), "text/plain", null));
    var bob = admitted(catalogue, "bob", "same", "Different account, same public name.");
    assertNotEquals(first.resource(), bob.resource());
  }

  @Test
  void lifecycle_failures_preserve_readiness_and_revision_evidence_never_repoints() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var first = admitted(catalogue, "alice", "same", "The original budget remains bounded.");
    var embeddings = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class);
    org.mockito.Mockito.when(embeddings.embedAll(org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(
            call -> {
              assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              List<?> texts = call.getArgument(0);
              return texts.stream()
                  .map(
                      t -> {
                        float[] v = new float[768];
                        v[0] = 1;
                        return v;
                      })
                  .toList();
            });
    var pipeline = pipeline(catalogue, embeddings, new InformationLifecycle.Gates() {});
    assertTrue(pipeline.drainOne()); // extraction
    assertTrue(pipeline.drainOne()); // derivation
    assertTrue(pipeline.drainOne()); // embeddings
    assertTrue(pipeline.drainOne()); // missing cascade is a failed step, not success
    assertTrue(
        pipeline.drainOne()); // independent automatic tagging also fails without a model runtime
    assertTrue(pipeline.drainOne()); // empty tag grouping settles without a model call
    assertFalse(pipeline.drainOne());
    assertEquals(
        "failed",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND stage='summarise'",
            String.class,
            first.revision()));
    assertEquals(
        "ready",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND stage='embed'",
            String.class,
            first.revision()));
    assertArrayEquals(
        "The original budget remains bounded.".getBytes(),
        catalogue.bytes(alice, first.revision()));
    UUID evidence =
        catalogue.evidence(
            alice, first.revision(), 4, 19, "original budget", "extracted-text:utf16");
    assertThrows(
        CallerFault.class,
        () ->
            catalogue.evidence(
                alice, first.revision(), 4, 19, "invented budget", "extracted-text:utf16"));
    var second = admitted(catalogue, "alice", "same", "The revised budget has a new limit.");
    assertEquals(first.resource(), second.resource());
    assertNotEquals(first.revision(), second.revision());
    pipeline.drainOne();
    pipeline.drainOne();
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM documents WHERE source_name='same'", Integer.class));
    assertEquals(
        "original budget",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.evidence(alice, evidence))
            .get("quote"));
    assertEquals(
        first.revision(),
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.evidence(alice, evidence))
            .get("revision_id"));
    assertThrows(
        IllegalStateException.class,
        () ->
            new DocumentStore(jdbc, transactions)
                .scoped(access, alice)
                .attachDocumentSummary(first.revision(), "a reader cannot mutate"));
  }

  @Test
  void a_late_embedding_cannot_commit_after_withdrawal_and_restore_is_a_new_generation() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var first = admitted(catalogue, "alice", "race", "A bounded retry budget.");
    var embeddings = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class);
    org.mockito.Mockito.when(embeddings.embedAll(org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(
            call -> {
              catalogue.availability(alice, first.revision(), "withdrawn");
              return ((List<?>) call.getArgument(0))
                  .stream()
                      .map(
                          t -> {
                            float[] vector = new float[768];
                            vector[0] = 1;
                            return vector;
                          })
                      .toList();
            });
    var pipeline = pipeline(catalogue, embeddings, new InformationLifecycle.Gates() {});
    pipeline.drainOne();
    pipeline.drainOne();
    pipeline.drainOne();
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=? AND c.embedding IS NOT NULL",
            Integer.class,
            first.revision()));
    assertThrows(NotFoundFault.class, () -> catalogue.text(alice, first.revision()));
    assertEquals(
        "withdrawn",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(alice, first.revision()))
            .get("availability"));
    assertTrue(
        new DocumentStore(jdbc, transactions)
            .scoped(access, alice)
            .find(first.revision())
            .isEmpty());
    catalogue.availability(alice, first.revision(), "active");
    assertEquals(3L, catalogue.generation(first.revision()));
    assertEquals(
        "ready",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND generation=3 AND stage='derive'",
            String.class,
            first.revision()));
    assertEquals(
        "pending",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND generation=3 AND stage='embed'",
            String.class,
            first.revision()));
  }

  @Test
  void post_gate_denial_preserves_work_and_retry_does_not_repeat_embeddings() {
    var catalogue = catalogue();
    var first = admitted(catalogue, "alice", "hooked", "A budget claim.");
    var embeddings = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class);
    org.mockito.Mockito.when(embeddings.embedAll(org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(
            call ->
                ((List<?>) call.getArgument(0))
                    .stream()
                        .map(
                            t -> {
                              float[] vector = new float[768];
                              vector[0] = 1;
                              return vector;
                            })
                        .toList());
    java.util.concurrent.atomic.AtomicBoolean deny =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    var gates =
        new InformationLifecycle.Gates() {
          @Override
          public io.aeyer.plowshare.server.hooks.Gate after(InformationLifecycle.Lease lease) {
            assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            return lease.stage().equals("embed") && deny.get()
                ? new io.aeyer.plowshare.server.hooks.Gate("review needed", List.of(), List.of())
                : io.aeyer.plowshare.server.hooks.Gate.NOTHING;
          }
        };
    var pipeline = pipeline(catalogue, embeddings, gates);
    pipeline.drainOne();
    pipeline.drainOne();
    pipeline.drainOne();
    assertEquals(
        "blocked",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND stage='embed'",
            String.class,
            first.revision()));
    deny.set(false);
    catalogue.retry(access.resolve("alice", null), first.revision());
    pipeline.drainOne();
    org.mockito.Mockito.verify(embeddings, org.mockito.Mockito.times(1))
        .embedAll(org.mockito.ArgumentMatchers.anyList());
    assertEquals(
        "ready",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND stage='embed'",
            String.class,
            first.revision()));
  }

  @Test
  void derived_reports_inherit_every_input_and_unlinking_never_deletes() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var visible = admitted(catalogue, "alice", "public-input", "A public statement.");
    var privateInput = admitted(catalogue, "alice", "private-input", "A private statement.");
    var embeddings = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class);
    org.mockito.Mockito.when(embeddings.embedAll(org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(
            call ->
                ((List<?>) call.getArgument(0))
                    .stream()
                        .map(
                            t -> {
                              float[] vector = new float[768];
                              vector[0] = 1;
                              return vector;
                            })
                        .toList());
    var pipeline = pipeline(catalogue, embeddings, new InformationLifecycle.Gates() {});
    for (int i = 0; i < 8; i++) pipeline.drainOne();
    catalogue.share(alice, visible.revision(), true);
    var report =
        catalogue.report(
            alice,
            UUID.randomUUID(),
            "report",
            "Derived from two inputs.",
            List.of(visible.revision(), privateInput.revision()),
            List.of(),
            null);
    pipeline.drainOne();
    pipeline.drainOne();
    assertThrows(CallerFault.class, () -> catalogue.share(alice, report.revision(), true));
    assertTrue(
        new DocumentStore(jdbc, transactions)
            .scoped(access, access.resolve("bob", null))
            .find(report.revision())
            .isEmpty());
    catalogue.link(alice, privateInput.revision(), "research", false);
    catalogue.link(alice, privateInput.revision(), "research", true);
    assertTrue(new DocumentStore(jdbc, transactions).find(privateInput.revision()).isPresent());
    catalogue.availability(alice, privateInput.revision(), "withdrawn");
    assertTrue(
        new DocumentStore(jdbc, transactions)
            .scoped(access, alice)
            .find(report.revision())
            .isEmpty());
    assertThrows(NotFoundFault.class, () -> catalogue.text(alice, report.revision()));
  }

  @Test
  void input_ledgers_propagate_to_parents_and_filter_log_search_before_counts_and_paging() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var source = admitted(catalogue, "alice", "private", "A private budget claim.");
    var embeddings = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class);
    org.mockito.Mockito.when(embeddings.embedAll(org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(
            call ->
                ((List<?>) call.getArgument(0))
                    .stream()
                        .map(
                            t -> {
                              float[] v = new float[768];
                              v[0] = 1;
                              return v;
                            })
                        .toList());
    var pipeline = pipeline(catalogue, embeddings, new InformationLifecycle.Gates() {});
    pipeline.drainOne();
    pipeline.drainOne();
    var conversations = new io.aeyer.plowshare.server.archive.ConversationStore(jdbc);
    var root =
        conversations.log(
            io.aeyer.plowshare.server.archive.Origin.SUBMISSION,
            io.aeyer.plowshare.protocol.Home.global(),
            "test",
            null,
            io.aeyer.plowshare.server.agents.Budget.of(1000),
            "alice",
            null);
    var child =
        conversations.log(
            io.aeyer.plowshare.server.archive.Origin.DELEGATION,
            io.aeyer.plowshare.protocol.Home.global(),
            "child",
            root.id(),
            null,
            "alice",
            null);
    var entries = new io.aeyer.plowshare.server.archive.EntryStore(jdbc, transactions);
    entries.append(
        root.id(),
        1,
        io.aeyer.plowshare.server.agents.LoggedEntry.utterance(
            "Private budget budget budget.", io.aeyer.plowshare.server.agents.Speaker.harness()));
    entries.append(
        child.id(),
        1,
        io.aeyer.plowshare.server.agents.LoggedEntry.utterance(
            "Private budget conclusion.", io.aeyer.plowshare.server.agents.Speaker.harness()));
    var policies =
        new InformationJobs(
            new io.aeyer.plowshare.server.information.JdbcJobInformationRepository(jdbc),
            access,
            catalogue);
    policies.bind(child.id(), alice, List.of(source.revision()));
    assertTrue(policies.logAllowed(root.id(), "alice"));
    var later =
        conversations.log(
            io.aeyer.plowshare.server.archive.Origin.DELEGATION,
            io.aeyer.plowshare.protocol.Home.global(),
            "later",
            root.id(),
            null,
            "alice",
            null);
    assertEquals(List.of(source.revision()), policies.inputsOf(later.id(), "alice"));
    assertFalse(policies.logAllowed(root.id(), "bob"));
    assertEquals(
        2,
        entries
            .forAccount("alice")
            .search(io.aeyer.plowshare.protocol.Home.global(), "budget", 0, 1)
            .total());
    assertEquals(
        0,
        entries
            .forAccount("bob")
            .search(io.aeyer.plowshare.protocol.Home.global(), "budget", 0, 1)
            .total());
    assertThrows(NotFoundFault.class, () -> entries.forAccount("bob").forConversation(child.id()));
    var inbox = new io.aeyer.plowshare.server.events.JdbcInboxStore(jdbc);
    inbox.useInformationInputs(policies);
    inbox.deliver("alice", null, root.id(), "answered", "Private conclusion", CLOCK.instant());
    assertEquals(1, inbox.unread("alice"));
    catalogue.availability(alice, source.revision(), "withdrawn");
    assertFalse(policies.logAllowed(root.id(), "alice"));
    assertEquals(
        0,
        entries
            .forAccount("alice")
            .search(io.aeyer.plowshare.protocol.Home.global(), "budget", 0, 1)
            .total());
    assertTrue(inbox.list("alice", false, 0, 20).isEmpty());
    assertEquals(0, inbox.unread("alice"));
  }

  @Test
  void websocket_information_intake_uses_the_socket_account_and_validates_before_fetching() {
    var catalogue = catalogue();
    var documents = new DocumentStore(jdbc, transactions);
    var embeddings = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class);
    var retrieval = new RetrievalService(documents, embeddings, "test", 768);
    var fetcher = org.mockito.Mockito.mock(io.aeyer.plowshare.server.fetch.PageFetcher.class);
    var frames =
        new io.aeyer.plowshare.server.ws.InformationFrames(
                catalogue,
                access,
                documents,
                retrieval,
                org.mockito.Mockito.mock(io.aeyer.plowshare.server.documents.Deliberation.class),
                org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.JobStore.class),
                new io.aeyer.plowshare.server.documents.DocumentsProperties(),
                fetcher)
            .frames();
    var payload =
        java.util.Map.<String, Object>of(
            "requestId",
            UUID.randomUUID().toString(),
            "name",
            "from-socket",
            "text",
            "A budget claim.",
            "account",
            "bob");
    assertThrows(
        CallerFault.class,
        () ->
            frames
                .get("information.upload")
                .handle(payload, new io.aeyer.plowshare.server.ws.Asking("session")));
    var accepted =
        frames
            .get("information.upload")
            .handle(payload, new io.aeyer.plowshare.server.ws.Asking("session", "alice"));
    var admitted = (InformationCatalogue.Admission) accepted.payload();
    assertEquals("alice", catalogue.row(admitted.revision()).ownerHandle());
    assertEquals(io.aeyer.plowshare.protocol.frames.Code.ACCEPTED, accepted.code());
    assertThrows(
        CallerFault.class,
        () ->
            frames
                .get("information.acquire")
                .handle(
                    java.util.Map.of(
                        "scope",
                        java.util.Map.of("kind", "project", "project", "research"),
                        "requestId",
                        UUID.randomUUID().toString(),
                        "url",
                        "https://example.org"),
                    new io.aeyer.plowshare.server.ws.Asking("session", "bob")));
    org.mockito.Mockito.verifyNoInteractions(fetcher);
    assertThrows(
        NotFoundFault.class,
        () ->
            frames
                .get("information.read")
                .handle(
                    java.util.Map.of("revision", admitted.revision().toString()),
                    new io.aeyer.plowshare.server.ws.Asking("session", "bob")));
    assertEquals(
        io.aeyer.plowshare.protocol.frames.InformationOperations.ALL.size(), frames.size());
    frames.keySet().forEach(io.aeyer.plowshare.server.ws.FrameTypes::requireWellFormed);
  }

  private UUID projected(DocumentStore store, String name, String text) {
    byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var extracted = TextExtraction.extract(name, bytes);
    UUID id =
        store
            .write(
                name,
                extracted,
                bytes.length,
                "attribution",
                CLOCK.instant(),
                Derivation.derive(
                    extracted,
                    new Chunking(
                        new io.aeyer.plowshare.server.llm.tokens.FixtureTokenizer(4), 512, 2048)))
            .documentId();
    jdbc.update("INSERT INTO information_document_policies (document_id) VALUES (?)", id);
    return id;
  }

  @Test
  void uncited_sources_stay_in_the_catalogue_and_restrict_the_report_that_consulted_them() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var used = admitted(catalogue, "alice", "cited source", "A retained claim.");
    var unused =
        admitted(catalogue, "alice", "uncited source", "Consulted but not used in the report.");
    var pipeline =
        pipeline(
            catalogue,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class),
            new InformationLifecycle.Gates() {});
    assertTrue(pipeline.drainOne());
    assertTrue(pipeline.drainOne());
    var evidence =
        catalogue.evidence(
            alice, used.revision(), 0, 16, "A retained claim", "extracted-text:utf16");
    var report =
        catalogue.report(
            alice,
            UUID.randomUUID(),
            "consulted-report",
            "A retained claim.",
            List.of(used.revision(), unused.revision()),
            List.of(evidence),
            null);
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(alice, 100, 0))
            .stream()
            .anyMatch(row -> unused.revision().equals(row.get("id"))));
    assertArrayEquals(
        "Consulted but not used in the report.".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        catalogue.bytes(alice, unused.revision()));
    assertEquals(
        List.of(evidence),
        jdbc.queryForList(
            "SELECT evidence_id FROM information_report_citations WHERE report_revision=?",
            UUID.class,
            report.revision()));
    catalogue.requireReadable(alice, report.revision());
    catalogue.availability(alice, unused.revision(), "withdrawn");
    assertThrows(NotFoundFault.class, () -> catalogue.requireReadable(alice, report.revision()));
  }

  @Test
  void
      socket_readiness_fence_observes_durable_extraction_and_unavailability_in_the_callers_scope() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var source = admitted(catalogue, "alice", "fenced-source", "Retained source text.");
    var documents = new DocumentStore(jdbc, transactions);
    var embeddings = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class);
    var frames =
        new io.aeyer.plowshare.server.ws.InformationFrames(
                catalogue,
                access,
                documents,
                new RetrievalService(documents, embeddings, "test", 768),
                org.mockito.Mockito.mock(io.aeyer.plowshare.server.documents.Deliberation.class),
                org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.JobStore.class),
                new io.aeyer.plowshare.server.documents.DocumentsProperties(),
                org.mockito.Mockito.mock(io.aeyer.plowshare.server.fetch.PageFetcher.class))
            .frames();
    var payload =
        java.util.Map.<String, Object>of(
            "sources",
            List.of(java.util.Map.of("revision", source.revision().toString())),
            "waitMs",
            0);
    var asking = new io.aeyer.plowshare.server.ws.Asking("session", "alice");
    assertEquals(
        false,
        ((io.aeyer.plowshare.protocol.Information.Readiness)
                frames.get("information.await").handle(payload, asking).payload())
            .complete());
    assertTrue(pipeline(catalogue, embeddings, new InformationLifecycle.Gates() {}).drainOne());
    var ready =
        (io.aeyer.plowshare.protocol.Information.Readiness)
            frames.get("information.await").handle(payload, asking).payload();
    assertEquals(true, ready.complete());
    assertEquals(1, ready.ready());
    var hidden =
        (io.aeyer.plowshare.protocol.Information.Readiness)
            frames
                .get("information.await")
                .handle(payload, new io.aeyer.plowshare.server.ws.Asking("session", "bob"))
                .payload();
    assertEquals(true, hidden.complete());
    assertEquals(0, hidden.ready());
    assertFalse(hidden.toString().contains("fenced-source"));
    catalogue.availability(alice, source.revision(), "withdrawn");
    var unavailable =
        (io.aeyer.plowshare.protocol.Information.Readiness)
            frames.get("information.await").handle(payload, asking).payload();
    assertEquals(true, unavailable.complete());
    assertEquals(0, unavailable.ready());
  }

  @Test
  void indexed_passages_must_match_retained_text_at_exact_utf16_coordinates() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var admitted =
        admitted(catalogue, "alice", "coordinates", "Prefix 😀\n  exact retained quotation.\nEnd");
    pipeline(
            catalogue,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class),
            new InformationLifecycle.Gates() {})
        .drainOne();
    var located =
        io.aeyer.plowshare.server.information.InformationFixtures.view(
            catalogue.locateWindow(alice, admitted.revision(), "  exact retained quotation."));
    assertEquals(true, located.get("matched"));
    assertEquals(10, located.get("start"));
    assertEquals("  exact retained quotation.", located.get("text"));
    assertEquals(
        false,
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.locateWindow(alice, admitted.revision(), "a generated paraphrase"))
            .get("matched"));
    assertThrows(
        NotFoundFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.locateWindow(
                    access.resolve("bob", null),
                    admitted.revision(),
                    "  exact retained quotation.")));
  }

  @Test
  void batch_sources_are_extracted_before_older_paid_summary_work() {
    var catalogue = catalogue();
    var first = admitted(catalogue, "alice", "older source", "Older retained text.");
    jdbc.update(
        "UPDATE information_steps SET state='ready' WHERE revision_id=? AND stage IN ('extract','derive','embed')",
        first.revision());
    var second = admitted(catalogue, "alice", "new source", "New retained evidence.");
    var pipeline =
        pipeline(
            catalogue,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class),
            new InformationLifecycle.Gates() {});
    assertTrue(pipeline.drainOne());
    assertEquals(
        "ready",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND stage='extract'",
            String.class,
            second.revision()));
    assertEquals(
        "pending",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND stage='summarise'",
            String.class,
            first.revision()));
  }

  @Test
  void deleting_an_input_removes_script_state_and_raw_receipts_that_contain_its_text() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var source = admitted(catalogue, "alice", "sensitive source", "Sensitive retained source.");
    var conversation =
        new io.aeyer.plowshare.server.archive.ConversationStore(jdbc)
            .log(
                io.aeyer.plowshare.server.archive.Origin.ORCHESTRATION,
                io.aeyer.plowshare.protocol.Home.global(),
                "research",
                null,
                io.aeyer.plowshare.server.agents.Budget.of(10),
                "alice");
    new InformationJobs(
            new io.aeyer.plowshare.server.information.JdbcJobInformationRepository(jdbc),
            access,
            catalogue)
        .bind(conversation.id(), alice, List.of(source.revision()));
    var journal = new io.aeyer.plowshare.server.orchestrations.scripted.JdbcScriptStore(jdbc);
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    jdbc.update(
        "INSERT INTO orchestration_script_steps(conversation_id,sequence,source_hash,state,command) VALUES (?,0,?,?::jsonb,?::jsonb)",
        conversation.id(),
        "hash",
        json.createObjectNode().put("quote", "Sensitive retained source.").toString(),
        "{\"tool\":\"information_read\",\"arguments\":{}}");
    journal.started(conversation.id(), 0, "{}");
    journal.executed(conversation.id(), 0, "Sensitive retained source.");
    catalogue.availability(alice, source.revision(), "deleted");
    assertTrue(journal.latest(conversation.id()).isEmpty());
  }

  @Test
  void code_revisions_preserve_source_and_are_isolated_before_discovery_and_ranking() {
    var catalogue = catalogue();
    var alice = access.resolve("alice", null);
    var codeContext =
        alice.withCorpus(io.aeyer.plowshare.server.information.InformationContext.Corpus.CODE);
    String source = "class Main {\n    void repeat() {}\n    void repeat() {}\n}\n".repeat(90);
    var code = admitted(catalogue, "alice", "Main.java", source);
    var prose = admitted(catalogue, "alice", "design.md", "A design document about Main.");
    assertEquals(
        List.of(prose.revision()),
        io.aeyer.plowshare.server.information.InformationFixtures.views(catalogue.list(alice, 1, 0))
            .stream()
            .map(r -> r.get("id"))
            .toList());
    assertEquals(
        List.of(code.revision()),
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(codeContext, 1, 0))
            .stream()
            .map(r -> r.get("id"))
            .toList());
    for (Object row :
        (List<?>)
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                    catalogue.events(alice, 0, 100))
                .get("events"))
      assertEquals(prose.revision(), ((Map<?, ?>) row).get("revision_id"));
    for (Object row :
        (List<?>)
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                    catalogue.events(codeContext, 0, 100))
                .get("events")) assertEquals(code.revision(), ((Map<?, ?>) row).get("revision_id"));
    assertEquals(
        "java",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(codeContext, code.revision()))
            .get("document_subtype"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_revisions SET document_type='document' WHERE id=?",
                code.revision()));
    assertThrows(
        NotFoundFault.class,
        () ->
            catalogue.text(
                access
                    .resolve("bob", null)
                    .withCorpus(
                        io.aeyer.plowshare.server.information.InformationContext.Corpus.CODE),
                code.revision()));

    var embeddings = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class);
    org.mockito.Mockito.when(embeddings.embedAll(org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(
            call -> {
              List<?> texts = call.getArgument(0);
              return texts.stream()
                  .map(
                      t -> {
                        float[] vector = new float[768];
                        vector[0] = 1;
                        return vector;
                      })
                  .toList();
            });
    var pipeline = pipeline(catalogue, embeddings, new InformationLifecycle.Gates() {});
    for (int i = 0; i < 12; i++) if (!pipeline.drainOne()) break;
    assertEquals(source, catalogue.text(codeContext, code.revision()));
    assertEquals(
        "skipped",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND stage='summarise'",
            String.class,
            code.revision()));
    var store = new DocumentStore(jdbc, transactions);
    assertEquals(1, store.scoped(access, alice).count(null));
    assertEquals(1, store.scoped(access, codeContext).count(null));
    assertTrue(store.scoped(access, alice).find(code.revision()).isEmpty());
    assertTrue(store.scoped(access, codeContext).find(prose.revision()).isEmpty());
    assertEquals(
        0,
        store
            .scoped(
                access,
                access
                    .resolve("bob", null)
                    .withCorpus(
                        io.aeyer.plowshare.server.information.InformationContext.Corpus.CODE))
            .count(null));
    var chunks =
        jdbc.queryForList(
            "SELECT c.id,c.text FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=? ORDER BY c.ordinal",
            code.revision());
    assertTrue(chunks.size() > 1);
    assertEquals(
        source,
        chunks.stream()
            .map(c -> (String) c.get("text"))
            .collect(java.util.stream.Collectors.joining()));
    int offset = 0;
    for (var chunk : chunks) {
      var located =
          io.aeyer.plowshare.server.information.InformationFixtures.view(
              catalogue.locateCodeWindow(codeContext, code.revision(), (UUID) chunk.get("id")));
      assertEquals(offset, located.get("start"));
      assertEquals(chunk.get("text"), located.get("text"));
      offset += ((String) chunk.get("text")).length();
    }
    assertEquals(1, store.scoped(access, alice).coverage().searchable());
    assertEquals(chunks.size(), store.scoped(access, codeContext).coverage().searchable());
    assertEquals(
        1, store.count(null), "ordinary corpus discovery also stays separate on internal readers");
    org.mockito.Mockito.when(embeddings.embed("Main"))
        .thenAnswer(
            call -> {
              float[] vector = new float[768];
              vector[0] = 1;
              return vector;
            });
    var retrieval = new RetrievalService(store, embeddings, "test", 768);
    assertEquals(
        prose.revision(),
        retrieval.scoped(access, alice).retrieve("Main", null, 1).getFirst().chunk().documentId());
    assertEquals(
        code.revision(),
        retrieval
            .scoped(access, codeContext)
            .retrieve("Main", null, 1)
            .getFirst()
            .chunk()
            .documentId());
    assertTrue(
        retrieval
            .scoped(
                access,
                access
                    .resolve("bob", null)
                    .withCorpus(
                        io.aeyer.plowshare.server.information.InformationContext.Corpus.CODE))
            .retrieve("Main", null, 1)
            .isEmpty());
    catalogue.rebuild(codeContext, code.revision(), "embed");
    assertEquals(
        "skipped",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND generation=2 AND stage='summary_embed'",
            String.class,
            code.revision()));
    assertThrows(
        CallerFault.class, () -> catalogue.rebuild(codeContext, code.revision(), "summarise"));
    var scope = Map.of("kind", "personal");
    var frames =
        new io.aeyer.plowshare.server.ws.InformationFrames(
                catalogue,
                access,
                store,
                null,
                null,
                null,
                new io.aeyer.plowshare.server.documents.DocumentsProperties(),
                null)
            .frames();
    var rows =
        (List<?>)
            frames
                .get("information.list")
                .handle(
                    Map.of("scope", scope, "corpus", "code"),
                    new io.aeyer.plowshare.server.ws.Asking("session", "alice"))
                .payload();
    assertEquals(
        rows,
        frames
            .get("information.list")
            .handle(
                Map.of("scope", scope, "corpus", "code", "kind", "source"),
                new io.aeyer.plowshare.server.ws.Asking("session", "alice"))
            .payload());
    assertEquals(
        List.of(),
        frames
            .get("information.list")
            .handle(
                Map.of("scope", scope, "corpus", "code", "kind", "report"),
                new io.aeyer.plowshare.server.ws.Asking("session", "alice"))
            .payload());
    assertEquals(1, rows.size());
    assertThrows(
        CallerFault.class,
        () ->
            frames
                .get("information.list")
                .handle(
                    Map.of("scope", scope, "corpus", "everything"),
                    new io.aeyer.plowshare.server.ws.Asking("session", "alice")));
  }

  @Test
  void syntax_navigation_round_trips_retained_source_and_rechecks_live_permissions() {
    var catalogue = catalogue();
    var alice =
        access
            .resolve("alice", null)
            .withCorpus(io.aeyer.plowshare.server.information.InformationContext.Corpus.CODE);
    String source =
        "// 😀 function fake() {}\nexport function load() { return '😀'; }\nclass Store { load() { return load(); } }\n";
    var admitted =
        catalogue.admit(
            alice,
            UUID.randomUUID(),
            "src/load.ts",
            source.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "text/plain",
            null);
    assertEquals(
        "pending",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.outline(alice, admitted.revision(), 0, 20))
            .get("status"));
    var embeddings = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class);
    org.mockito.Mockito.when(embeddings.embedAll(org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(
            call -> {
              List<?> texts = call.getArgument(0);
              return texts.stream().map(t -> new float[768]).toList();
            });
    var pipeline = pipeline(catalogue, embeddings, new InformationLifecycle.Gates() {});
    assertTrue(pipeline.drainOne());
    assertTrue(pipeline.drainOne());
    var outline =
        io.aeyer.plowshare.server.information.InformationFixtures.view(
            catalogue.outline(alice, admitted.revision(), 0, 1));
    assertEquals("ready", outline.get("status"));
    assertEquals(true, outline.get("has_more"));
    assertEquals(3, outline.get("symbol_count"));
    var first = (Map<?, ?>) ((List<?>) outline.get("symbols")).getFirst();
    int start = ((Number) first.get("start_offset")).intValue(),
        end = ((Number) first.get("end_offset")).intValue();
    String quote =
        (String)
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                    catalogue.window(alice, admitted.revision(), start, end - start))
                .get("text");
    assertEquals("function load() { return '😀'; }", quote);
    assertNotNull(
        catalogue.evidence(alice, admitted.revision(), start, end, quote, "extracted-text:utf16"));
    assertEquals(
        2,
        ((List<?>)
                io.aeyer.plowshare.server.information.InformationFixtures.view(
                        catalogue.outline(alice, admitted.revision(), 1, 100))
                    .get("symbols"))
            .size());
    assertThrows(
        CallerFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.outline(access.resolve("alice", null), admitted.revision(), 0, 20)));
    assertThrows(
        NotFoundFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.outline(
                    access
                        .resolve("bob", null)
                        .withCorpus(
                            io.aeyer.plowshare.server.information.InformationContext.Corpus.CODE),
                    admitted.revision(),
                    0,
                    20)));
    assertThrows(
        CallerFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.symbols(alice, "load", null, 0, 101)));
    assertThrows(
        CallerFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.symbols(alice, " ", null, 0, 20)));
    var matches =
        io.aeyer.plowshare.server.information.InformationFixtures.view(
            catalogue.symbols(alice, "LOAD", null, 0, 1));
    assertEquals(true, matches.get("has_more"));
    assertEquals(
        admitted.revision(),
        ((Map<?, ?>) ((List<?>) matches.get("symbols")).getFirst()).get("revision"));
    assertTrue(
        ((List<?>)
                io.aeyer.plowshare.server.information.InformationFixtures.view(
                        catalogue.symbols(alice, "%", null, 0, 20))
                    .get("symbols"))
            .isEmpty(),
        "wildcards are literal prefixes");
    org.mockito.Mockito.verifyNoInteractions(embeddings);

    var bob =
        access
            .resolve("bob", null)
            .withCorpus(io.aeyer.plowshare.server.information.InformationContext.Corpus.CODE);
    var other =
        catalogue.admit(
            bob, UUID.randomUUID(), "load.ts", "function load() {}".getBytes(), "text/plain", null);
    var unsupported =
        catalogue.admit(
            alice, UUID.randomUUID(), "src/main.rs", "fn load() {}".getBytes(), "text/plain", null);
    for (int i = 0; i < 20; i++) if (!pipeline.drainOne()) break;
    assertEquals(
        "unsupported",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.outline(alice, unsupported.revision(), 0, 20))
            .get("status"));
    assertEquals(
        1,
        ((List<?>)
                io.aeyer.plowshare.server.information.InformationFixtures.view(
                        catalogue.symbols(bob, "load", null, 0, 1))
                    .get("symbols"))
            .size());
    assertEquals(
        other.revision(),
        ((Map<?, ?>)
                ((List<?>)
                        io.aeyer.plowshare.server.information.InformationFixtures.view(
                                catalogue.symbols(bob, "load", null, 0, 1))
                            .get("symbols"))
                    .getFirst())
            .get("revision"));
    assertEquals(
        admitted.revision(),
        ((Map<?, ?>)
                ((List<?>)
                        io.aeyer.plowshare.server.information.InformationFixtures.view(
                                catalogue.symbols(alice, "load", null, 0, 1))
                            .get("symbols"))
                    .getFirst())
            .get("revision"));

    var store = new DocumentStore(jdbc, transactions);
    var frames =
        new io.aeyer.plowshare.server.ws.InformationFrames(
                catalogue,
                access,
                store,
                null,
                null,
                null,
                new io.aeyer.plowshare.server.documents.DocumentsProperties(),
                null)
            .frames();
    var payload =
        Map.<String, Object>of(
            "scope",
            Map.of("kind", "personal"),
            "corpus",
            "code",
            "revision",
            admitted.revision().toString());
    var response =
        (io.aeyer.plowshare.protocol.Information.Outline)
            frames
                .get("information.outline")
                .handle(payload, new io.aeyer.plowshare.server.ws.Asking("s", "alice"))
                .payload();
    assertEquals("ready", response.status());
    var symbolPayload = new java.util.LinkedHashMap<>(payload);
    symbolPayload.put("query", "load");
    assertEquals(
        2,
        ((List<?>)
                ((io.aeyer.plowshare.protocol.Information.Symbols)
                        frames
                            .get("information.symbols")
                            .handle(
                                symbolPayload,
                                new io.aeyer.plowshare.server.ws.Asking("s", "alice"))
                            .payload())
                    .symbols())
            .size());
    assertThrows(
        NotFoundFault.class,
        () ->
            frames
                .get("information.outline")
                .handle(payload, new io.aeyer.plowshare.server.ws.Asking("s", "bob")));
    jdbc.update(
        "UPDATE code_outlines SET parser_version='older' WHERE document_id=?", admitted.revision());
    assertEquals(
        "stale",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.outline(alice, admitted.revision(), 0, 20))
            .get("status"));
    assertTrue(
        ((List<?>)
                io.aeyer.plowshare.server.information.InformationFixtures.view(
                        catalogue.symbols(alice, "load", null, 0, 20))
                    .get("symbols"))
            .isEmpty());

    var project =
        access
            .resolve("alice", InformationContext.Selection.project("research"))
            .withCorpus(io.aeyer.plowshare.server.information.InformationContext.Corpus.CODE);
    var projectSource =
        catalogue.admit(
            project,
            UUID.randomUUID(),
            "Project.java",
            "class Project { void load() {} }".getBytes(),
            "text/plain",
            null);
    for (int i = 0; i < 8; i++) if (!pipeline.drainOne()) break;
    members.add("research", "bob");
    var member =
        access
            .resolve("bob", InformationContext.Selection.project("research"))
            .withCorpus(io.aeyer.plowshare.server.information.InformationContext.Corpus.CODE);
    assertEquals(
        1,
        ((List<?>)
                io.aeyer.plowshare.server.information.InformationFixtures.view(
                        catalogue.symbols(member, "load", null, 0, 1))
                    .get("symbols"))
            .size());
    assertEquals(
        "ready",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.outline(member, projectSource.revision(), 0, 20))
            .get("status"));
    members.remove("research", "bob");
    assertThrows(
        CallerFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.symbols(member, "load", null, 0, 1)));
    assertThrows(
        CallerFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.outline(member, projectSource.revision(), 0, 20)));
    catalogue.availability(bob, other.revision(), "withdrawn");
    assertTrue(
        ((List<?>)
                io.aeyer.plowshare.server.information.InformationFixtures.view(
                        catalogue.symbols(bob, "load", null, 0, 1))
                    .get("symbols"))
            .isEmpty());
    assertThrows(
        NotFoundFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.outline(bob, other.revision(), 0, 20)));
  }
}
