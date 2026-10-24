package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.protocol.frames.*;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.embedding.*;
import io.aeyer.plowshare.server.fetch.PageFetcher;
import io.aeyer.plowshare.server.harness.Harness;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.llm.*;
import io.aeyer.plowshare.server.llm.accounting.*;
import io.aeyer.plowshare.server.llm.dispatch.*;
import io.aeyer.plowshare.server.llm.tokens.*;
import io.aeyer.plowshare.server.ws.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Public frame admission over real policy, shared allowance, SYSTEM dispatch and publication SQL.
 */
@Tag("full-db")
@Testcontainers
class GlobalPreparationPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  JdbcTemplate jdbc;
  UnitOfWork work;
  InformationAccess access;
  InformationCatalogue catalogue;
  JdbcInformationProcessingRepository processing;
  BoundSystemOperations system;
  PreparationEmbeddings preparation;
  DocumentStore documents;
  FrameRouter router;
  final AtomicInteger embedded = new AtomicInteger(), chatted = new AtomicInteger();
  Runnable afterEmbedding = () -> {};
  Runnable afterChat = () -> {};
  String chatAnswer = "A grounded manual summary.";
  List<ChatMessage> chatMessages = List.of();
  final Tokenizer tokenizer =
      new Tokenizer() {
        public TokenCount count(String text) {
          return TokenCount.measured(text.length(), "fixture");
        }

        public String describe() {
          return "fixture";
        }
      };
  final Map<String, Object> destination =
      Map.of("kind", "account", "account", BuiltInIdentities.GLOBAL.value().toString());

  @BeforeAll
  static void migrate() {
    source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(source);
    io.aeyer.plowshare.server.testing.AuthorityFixtures.truncate(
        jdbc,
        "TRUNCATE documents,information_resources,memories,digests,embedding_queries,projects,admins CASCADE");
    jdbc.update(
        "UPDATE embedding_slots SET active_space_id=NULL,target_space_id=NULL,active_mode=NULL,target_mode=NULL,active_distance=NULL,target_distance=NULL");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin) VALUES('admin','fixture',false,true),('operator','fixture',false,true),('reader','fixture',false,false)");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin,account_kind) VALUES('app','fixture',false,false,'SERVICE')");
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    var identities = new JdbcIdentityRepository(jdbc);
    var grants = new JdbcScopedGrantRepository(jdbc);
    ApplicationPolicy applications =
        ignored -> new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of());
    var authority =
        new LiveScopedAuthority(
            grants, applications, new ApplicationGrantLineage(grants, applications), identities);
    var members = new JdbcProjectMembers(jdbc);
    access =
        new InformationAccess(
            new EffectiveProjectPermissions(members),
            io.aeyer.plowshare.server.requests.RequestProjects.EXPLICIT_ONLY,
            new LiveAccountKnowledgePermissions(identities, grants, authority));
    var records =
        new JdbcInformationCatalogueRepository(
            jdbc, Clock.systemUTC(), new RecordedInformationEvents());
    var readable = new java.util.concurrent.atomic.AtomicReference<InformationReadAccess>();
    var logs =
        new InformationJobs(
            new JdbcJobInformationRepository(jdbc),
            access,
            (context, revision) -> readable.get().requireReadable(context, revision));
    var gates =
        new InformationWriteGates(
            new JdbcInformationGateRepository(jdbc, Clock.systemUTC()),
            work,
            access,
            logs,
            new ConversationStore(jdbc),
            LogStages.NONE,
            Hooks.NONE,
            Harness.NONE);
    var registry = new JdbcInformationContentTypeRepository(jdbc);
    var settings = new InformationContentSettings(true);
    var types =
        new InformationConfig()
            .informationContentTypes(registry, access, members, records, work, gates, settings);
    var content = new JdbcInformationContentRepository(jdbc, registry);
    catalogue =
        new InformationCatalogue(
                records,
                work,
                access,
                logs,
                new InformationContentAdmission(content, types, access, settings),
                content,
                new JdbcInformationProjectionRepository(jdbc, content),
                gates)
            .withAllowance(() -> 30);
    readable.set(catalogue);
    documents = new DocumentStore(jdbc, work);
    processing = new JdbcInformationProcessingRepository(jdbc, Clock.systemUTC(), members);
    system =
        new BoundSystemOperations(
            identities,
            grants,
            authority,
            new JdbcSystemOperationRecords(jdbc),
            new OperationInference(
                mock(OperationAuthority.class), new JdbcInferenceAdmissions(jdbc)));
    preparation =
        new BoundPreparationEmbeddings(processing, work, new BoundSemanticEmbedding(system));
    var frames =
        new InformationFrames(
            catalogue,
            access,
            documents,
            mock(RetrievalService.class),
            mock(Deliberation.class),
            mock(JobStore.class),
            new DocumentsProperties(),
            mock(PageFetcher.class),
            mock(InformationContentTypes.class),
            mock(InformationContentSettings.class),
            mock(InformationNavigationService.class),
            mock(InformationInterchangeService.class));
    router = new FrameRouter(frames.frames());
    router.useAuthorization(
        new ProjectAuthorization(
            new JdbcResourceScopeRepository(jdbc), members, mock(AdminStore.class)));
  }

  QueryEmbeddings queries(Integer limit) {
    return new BoundQueryEmbeddings(
        new JdbcQueryEmbeddingRepository(jdbc),
        access,
        work,
        new BoundSemanticEmbedding(system),
        () -> limit);
  }

  Archive memoryQueries(LlmDispatcher dispatcher, boolean dual, Integer limit) {
    return memoryQueries(dispatcher, dual, limit, new MemoryStore(jdbc));
  }

  Archive memoryQueries(LlmDispatcher dispatcher, boolean dual, Integer limit, MemoryStore store) {
    store.protectInformation();
    var props = new LlmProperties();
    props.setEmbeddingModel("model");
    props.setEmbeddingDim(768);
    props.setEmbeddingMaxInputTokens(2048);
    var embeddings = new DispatchingEmbeddingClient(dispatcher, props, tokenizer);
    DualEmbeddings lanes = null;
    if (dual) {
      var records =
          new JdbcEmbeddingWorkRepository(jdbc, work, new JdbcEmbeddingSpaceRepository(jdbc));
      var space =
          new JdbcEmbeddingSpaceRepository(jdbc)
              .register(
                  new EmbeddingSpace.Definition(
                      "prose-encoder",
                      "memory-fixture",
                      768,
                      "query: ",
                      "document: ",
                      "cls",
                      EmbeddingSpace.Normalization.NONE,
                      "none"));
      records.inputLimit(space, 2048);
      records.configure(
          EmbeddingSlot.PROSE,
          space,
          new EmbeddingSearchPolicy(
              EmbeddingSearchPolicy.Mode.EXACT, EmbeddingSearchPolicy.Distance.COSINE));
      assertTrue(records.activate(records.target(EmbeddingSlot.PROSE).orElseThrow()));
      var counters = mock(EmbeddingTokenizers.class);
      when(counters.model(any())).thenReturn(tokenizer);
      lanes = new DispatchingDualEmbeddings(records, dispatcher::embed, counters);
    }
    var selected = lanes;
    var archive = new Archive(store, new JdbcReasonLog(jdbc), embeddings, work, 1000, 50, 30);
    archive.useQueries(
        new BoundMemoryQueries(
            store,
            new JdbcQueryEmbeddingRepository(jdbc),
            work,
            new BoundSemanticEmbedding(system),
            embeddings,
            () -> selected,
            () -> limit,
            Clock.systemUTC(),
            new EffectiveProjectPermissions(new JdbcProjectMembers(jdbc))));
    return archive;
  }

  Archive memoryWrites(LlmDispatcher dispatcher, boolean dual, Integer limit, boolean withScribe) {
    return memoryWrites(dispatcher, dual, limit, withScribe, new MemoryStore(jdbc));
  }

  Archive memoryWrites(
      LlmDispatcher dispatcher,
      boolean dual,
      Integer limit,
      boolean withScribe,
      MemoryStore store) {
    // Search disabled deliberately: it is not the admitted write's policy.
    var archive = memoryQueries(dispatcher, dual, 0);
    store.protectInformation();
    var records =
        new JdbcEmbeddingWorkRepository(jdbc, work, new JdbcEmbeddingSpaceRepository(jdbc));
    DualEmbeddings lanes = null;
    if (dual) {
      var prose = records.active(EmbeddingSlot.PROSE).orElseThrow();
      records.configure(EmbeddingSlot.CODE, prose.space(), prose.search());
      assertTrue(records.activate(records.target(EmbeddingSlot.CODE).orElseThrow()));
      var counters = mock(EmbeddingTokenizers.class);
      when(counters.model(any())).thenReturn(tokenizer);
      lanes = new DispatchingDualEmbeddings(records, dispatcher::embed, counters);
    }
    var props = new LlmProperties();
    props.setEmbeddingModel("model");
    props.setEmbeddingDim(768);
    props.setEmbeddingMaxInputTokens(2048);
    var embeddings = new DispatchingEmbeddingClient(dispatcher, props, tokenizer);
    var identities = new JdbcIdentityRepository(jdbc);
    var grants = new JdbcScopedGrantRepository(jdbc);
    ApplicationPolicy applications =
        ignored -> new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of());
    var authority =
        new LiveScopedAuthority(
            grants, applications, new ApplicationGrantLineage(grants, applications), identities);
    var registry =
        withScribe
            ? new AgentRegistry(
                Map.of(
                    "scribe",
                    new AgentDefinition(
                        "scribe",
                        "Scribe",
                        "model",
                        List.of(),
                        List.of(),
                        List.of(),
                        1,
                        1,
                        "Judge shape")))
            : null;
    var scribe =
        new io.aeyer.plowshare.server.agents.scribe.Scribe(
            dispatcher, archive, () -> registry, system);
    var selected = lanes;
    var writes =
        new BoundMemoryWrites(
            archive,
            scribe,
            store,
            new JdbcQueryEmbeddingRepository(jdbc),
            records,
            work,
            new BoundSemanticEmbedding(system),
            system,
            embeddings,
            () -> selected,
            () -> limit,
            new EffectiveProjectPermissions(new JdbcProjectMembers(jdbc)),
            authority,
            identities);
    archive.useWrites(() -> writes);
    return archive;
  }

  MemoryWrites.Request memoryWrite(String actor, Home home, String invocation) {
    return new MemoryWrites.Request(
        actor,
        home,
        new MemoryProposal("knowledge", "deployment", "The memory body", "writer", "fixture"),
        new MemoryWrites.Origin(invocation, () -> {}, () -> {}, null));
  }

  void memoryWriter() {
    memoryProject();
    jdbc.update(
        "UPDATE project_members SET role='CONTRIBUTOR' WHERE handle IN ('reader','app') AND project_id=(SELECT id FROM projects WHERE name='memory-project')");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void memoryWritesShareSystemSourceAndFenceBothEncoderModes(boolean dual) {
    memoryWriter();
    try (var dispatcher = dispatcher()) {
      var archive = memoryWrites(dispatcher, dual, null, true);
      seedMemory("mem_candidate", Home.of("memory-project"), true, dual);
      seedMemory("mem_global_decoy", Home.global(), true, dual);
      seedMemory("mem_private", Home.of("memory-project"), true, dual);
      var privateLog =
          new ConversationStore(jdbc)
              .log(
                  Origin.SUBMISSION,
                  Home.of("memory-project"),
                  "private",
                  null,
                  io.aeyer.plowshare.server.agents.Budget.of(1),
                  "reader",
                  null)
              .id();
      jdbc.update("INSERT INTO memory_provenance VALUES(?,?,1)", "mem_private", privateLog);
      jdbc.update(
          "INSERT INTO information_quarantined_payloads VALUES(?,'private fixture')", privateLog);
      chatAnswer = "{\"verdict\":\"new\",\"reason\":\"distinct claim\"}";
      var result = archive.write(memoryWrite("reader", Home.of("memory-project"), "write-one"));
      assertEquals(VerdictKind.NEW, result.kind());
      assertEquals(1, chatted.get());
      assertFalse(chatMessages.toString().contains("mem_private"));
      assertEquals(
          0,
          jdbc.queryForObject("SELECT uses FROM memories WHERE id='mem_private'", Integer.class));
      assertEquals(dual ? 3 : 1, embedded.get());
      assertEquals(
          dual ? 4 : 2,
          jdbc.queryForObject(
              "SELECT spent FROM embedding_query_allowances WHERE consumer_kind='MEMORY_WRITE'",
              Integer.class));
      assertTrue(
          jdbc.queryForObject(
              "SELECT closed AND call_limit IS NULL FROM embedding_query_allowances WHERE consumer_kind='MEMORY_WRITE'",
              Boolean.class));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT uses FROM memories WHERE id='mem_global_decoy'", Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject("SELECT uses FROM memories WHERE id='mem_candidate'", Integer.class));
      assertTrue(
          jdbc.queryForObject(
              "SELECT "
                  + (dual
                      ? "prose_embedding IS NOT NULL AND code_embedding IS NOT NULL"
                      : "embedding IS NOT NULL")
                  + " FROM memories WHERE id=?",
              Boolean.class,
              result.memoryId()));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM memory_reasons WHERE memory_id=?",
              Integer.class,
              result.memoryId()));
      assertThrows(
          RuntimeException.class,
          () -> archive.write(memoryWrite("reader", Home.of("memory-project"), "write-one")));
      assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM memories", Integer.class));
      assertThrows(
          IllegalStateException.class,
          () ->
              archive.applyVerdict(
                  new MemoryProposal("x", "y", "z", "a", "b"),
                  new Verdict(VerdictKind.NEW, null, "fixture"),
                  Home.of("memory-project")));
    }
  }

  @Test
  void memoryWriteDisabledRetainsNoCallAdmissionAndRejectsReplayAndReader() {
    memoryWriter();
    try (var dispatcher = dispatcher()) {
      var archive = memoryWrites(dispatcher, false, 0, true);
      var result =
          archive.write(memoryWrite("reader", Home.of("memory-project"), "disabled-write"));
      assertTrue(result.reason().contains("disabled by policy"));
      assertEquals(0, embedded.get());
      assertEquals(0, chatted.get());
      assertTrue(
          jdbc.queryForObject(
              "SELECT closed AND spent=0 AND call_limit=0 FROM embedding_query_allowances WHERE consumer_kind='MEMORY_WRITE'",
              Boolean.class));
      assertThrows(
          RuntimeException.class,
          () -> archive.write(memoryWrite("reader", Home.of("memory-project"), "disabled-write")));
      jdbc.update("UPDATE project_members SET role='VIEWER' WHERE handle='reader'");
      assertThrows(
          RuntimeException.class,
          () -> archive.write(memoryWrite("reader", Home.of("memory-project"), "reader-write")));
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memories", Integer.class));
      assertThrows(
          DataAccessException.class,
          () ->
              jdbc.update(
                  "UPDATE embedding_queries SET consumer_kind='QUERY' WHERE consumer_kind='MEMORY_WRITE'"));
    }
  }

  @Test
  void memoryWriteFinitePolicyDoesNotBorrowSearchOrFreshScribeAllowance() {
    memoryWriter();
    try (var dispatcher = dispatcher()) {
      var archive = memoryWrites(dispatcher, false, 1, true);
      seedMemory("mem_candidate", Home.of("memory-project"), true, false);
      var result = archive.write(memoryWrite("app", Home.of("memory-project"), "finite-write"));
      assertTrue(result.reason().contains("allowance exhausted"));
      assertEquals(1, embedded.get());
      assertEquals(0, chatted.get());
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT spent FROM embedding_query_allowances WHERE consumer_kind='MEMORY_WRITE'",
              Integer.class));
      assertTrue(
          jdbc.queryForObject(
              "SELECT embedding IS NOT NULL FROM memories WHERE id=?",
              Boolean.class,
              result.memoryId()));
    }
  }

  @Test
  void memoryWriteRevocationDuringCandidateInferenceCannotFileFlat() {
    memoryWriter();
    try (var dispatcher = dispatcher()) {
      var archive = memoryWrites(dispatcher, false, null, true);
      afterEmbedding = () -> jdbc.update("DELETE FROM project_members WHERE handle='reader'");
      assertThrows(
          RuntimeException.class,
          () -> archive.write(memoryWrite("reader", Home.of("memory-project"), "revoked-write")));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM memories", Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT spent FROM embedding_query_allowances WHERE consumer_kind='MEMORY_WRITE'",
              Integer.class));
    }
  }

  @Test
  void memoryWriteCommittedSystemIndexingSurvivesCreatorRevocation() {
    memoryWriter();
    try (var dispatcher = dispatcher()) {
      var archive = memoryWrites(dispatcher, false, null, false);
      afterEmbedding = () -> jdbc.update("DELETE FROM project_members WHERE handle='reader'");
      assertThrows(
          RuntimeException.class,
          () ->
              archive.write(memoryWrite("reader", Home.of("memory-project"), "postcommit-revoke")));
      assertTrue(jdbc.queryForObject("SELECT embedding IS NOT NULL FROM memories", Boolean.class));
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memory_reasons", Integer.class));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void memoryWriteStaleOutputCannotPublishAfterContentChanges(boolean dual) {
    memoryWriter();
    try (var dispatcher = dispatcher()) {
      var archive = memoryWrites(dispatcher, dual, null, false);
      afterEmbedding =
          () -> jdbc.update("UPDATE memories SET summary='changed' WHERE summary='knowledge'");
      var result =
          archive.write(memoryWrite("reader", Home.of("memory-project"), "changed-output"));
      assertTrue(
          jdbc.queryForObject(
              "SELECT embedding IS NULL AND prose_embedding IS NULL AND code_embedding IS NULL FROM memories WHERE id=?",
              Boolean.class,
              result.memoryId()));
      assertEquals(
          "changed",
          jdbc.queryForObject(
              "SELECT summary FROM memories WHERE id=?", String.class, result.memoryId()));
    }
  }

  @Test
  void memoryWriteGlobalRequiresHumanAdministrationAndUsesFixedScope() {
    try (var dispatcher = dispatcher()) {
      var archive = memoryWrites(dispatcher, false, null, false);
      assertThrows(
          RuntimeException.class,
          () -> archive.write(memoryWrite("reader", Home.global(), "global-reader")));
      assertThrows(
          RuntimeException.class,
          () -> archive.write(memoryWrite("app", Home.global(), "global-service")));
      var result = archive.write(memoryWrite("admin", Home.global(), "global-admin"));
      assertEquals(
          BuiltInIdentities.GLOBAL_KNOWLEDGE.value(),
          jdbc.queryForObject(
              "SELECT home_scope_id FROM memories WHERE id=?", UUID.class, result.memoryId()));
      assertTrue(
          jdbc.queryForObject(
              "SELECT embedding IS NOT NULL FROM memories WHERE id=?",
              Boolean.class,
              result.memoryId()));
    }
  }

  @Test
  void memoryWriteStaleScribeTargetCannotMutateEvenWithLiveWritePermission() {
    memoryWriter();
    try (var dispatcher = dispatcher()) {
      var archive = memoryWrites(dispatcher, false, null, true);
      seedMemory("mem_candidate", Home.of("memory-project"), true, false);
      chatAnswer =
          "{\"verdict\":\"merged_into\",\"target\":\"mem_candidate\",\"reason\":\"more detail\"}";
      afterChat =
          () ->
              jdbc.update(
                  "UPDATE memories SET body='changed by another write' WHERE id='mem_candidate'");
      assertThrows(
          RuntimeException.class,
          () -> archive.write(memoryWrite("reader", Home.of("memory-project"), "stale-target")));
      assertEquals(
          "changed by another write",
          jdbc.queryForObject("SELECT body FROM memories WHERE id='mem_candidate'", String.class));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM memory_reasons", Integer.class));
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT spent FROM embedding_query_allowances WHERE consumer_kind='MEMORY_WRITE'",
              Integer.class));
    }
  }

  MemoryWrites.Request learnedMemory(String log, EntryRecord entry, String invocation) {
    return new MemoryWrites.Request(
        null,
        Home.of("memory-project"),
        new MemoryProposal("lesson", "scope", "learned body", "learner", "window"),
        new MemoryWrites.Origin(
            invocation, () -> {}, () -> {}, new MemoryWrites.Learning(log, List.of(entry), 0)));
  }

  @Test
  void memoryWriteLearningUsesSystemWindowAndAtomicProvenanceWithoutCreatorMembership() {
    memoryWriter();
    String log =
        new ConversationStore(jdbc)
            .open(Home.of("memory-project"), io.aeyer.plowshare.server.agents.Budget.of(1))
            .id();
    var entry =
        new EntryStore(jdbc, work)
            .append(
                log,
                1,
                io.aeyer.plowshare.server.agents.LoggedEntry.utterance(
                    "stable source", io.aeyer.plowshare.server.agents.Speaker.person("reader")));
    jdbc.update("DELETE FROM project_members WHERE handle='reader'");
    try (var dispatcher = dispatcher()) {
      var archive = memoryWrites(dispatcher, false, null, false);
      var result = archive.write(learnedMemory(log, entry, "learning-window"));
      assertTrue(
          jdbc.queryForObject(
              "SELECT embedding IS NOT NULL FROM memories WHERE id=?",
              Boolean.class,
              result.memoryId()));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM memory_provenance WHERE memory_id=? AND conversation_id=? AND turn_ordinal=1",
              Integer.class,
              result.memoryId(),
              log));
      assertEquals(
          BuiltInIdentities.SYSTEM.value(),
          jdbc.queryForObject(
              "SELECT account_id FROM embedding_query_allowances WHERE consumer_kind='MEMORY_WRITE'",
              UUID.class));
      assertThrows(
          RuntimeException.class,
          () -> archive.write(learnedMemory(log, entry, "learning-window")));
      jdbc.update("UPDATE entries SET content='changed source' WHERE conversation_id=?", log);
      assertThrows(
          RuntimeException.class, () -> archive.write(learnedMemory(log, entry, "changed-window")));
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memories", Integer.class));
    }
  }

  @Test
  void memoryWriteLearningProvenanceFailureRollsBackContentAndReason() {
    memoryWriter();
    String log =
        new ConversationStore(jdbc)
            .open(Home.of("memory-project"), io.aeyer.plowshare.server.agents.Budget.of(1))
            .id();
    var entry =
        new EntryStore(jdbc, work)
            .append(
                log,
                1,
                io.aeyer.plowshare.server.agents.LoggedEntry.utterance(
                    "stable source", io.aeyer.plowshare.server.agents.Speaker.person("reader")));
    var store =
        new MemoryStore(jdbc) {
          @Override
          public void provenance(String id, MemoryWrites.Learning source) {
            super.provenance(id, source);
            throw new IllegalStateException("fixture provenance failure");
          }
        };
    try (var dispatcher = dispatcher()) {
      var archive = memoryWrites(dispatcher, false, 0, false, store);
      assertThrows(
          RuntimeException.class,
          () -> archive.write(learnedMemory(log, entry, "rollback-window")));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM memories", Integer.class));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM memory_reasons", Integer.class));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM memory_provenance", Integer.class));
      assertEquals(0, embedded.get());
      assertTrue(
          jdbc.queryForObject(
              "SELECT closed AND spent=0 FROM embedding_query_allowances WHERE consumer_kind='MEMORY_WRITE'",
              Boolean.class));
    }
  }

  @Test
  void memoryWriteConcurrentAdmissionsDoNotDuplicateContentOrInference() throws Exception {
    memoryWriter();
    try (var dispatcher = dispatcher();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var archive = memoryWrites(dispatcher, false, 1, false);
      var start = new CountDownLatch(1);
      var attempts = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++)
        attempts.add(
            workers.submit(
                () -> {
                  start.await();
                  try {
                    archive.write(
                        memoryWrite("reader", Home.of("memory-project"), "concurrent-write"));
                    return true;
                  } catch (RuntimeException denied) {
                    return false;
                  }
                }));
      start.countDown();
      int accepted = 0;
      for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) accepted++;
      assertEquals(1, accepted);
      assertEquals(1, embedded.get());
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memories", Integer.class));
      assertTrue(
          jdbc.queryForObject(
              "SELECT closed AND spent=1 AND call_limit=1 FROM embedding_query_allowances WHERE consumer_kind='MEMORY_WRITE'",
              Boolean.class));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"merged_into", "supersedes"})
  void memoryWriteVerdictsRetainExistingMergeAndSupersessionLifecycle(String verdict) {
    memoryWriter();
    try (var dispatcher = dispatcher()) {
      var archive = memoryWrites(dispatcher, false, null, true);
      seedMemory("mem_candidate", Home.of("memory-project"), true, false);
      chatAnswer =
          "{\"verdict\":\""
              + verdict
              + "\",\"target\":\"mem_candidate\",\"reason\":\"revised evidence\"}";
      var result = archive.write(memoryWrite("reader", Home.of("memory-project"), "verdict-write"));
      assertEquals(verdict, result.kind().wireName());
      assertEquals("mem_candidate", result.targetId());
      assertEquals(1, embedded.get());
      assertEquals(1, chatted.get());
      if (verdict.equals("merged_into")) {
        assertEquals("mem_candidate", result.memoryId());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memories", Integer.class));
        assertTrue(
            jdbc.queryForObject(
                "SELECT body LIKE '%The memory body%' AND state='active' FROM memories WHERE id='mem_candidate'",
                Boolean.class));
      } else {
        assertNotEquals("mem_candidate", result.memoryId());
        assertEquals(
            "superseded",
            jdbc.queryForObject(
                "SELECT state FROM memories WHERE id='mem_candidate'", String.class));
        assertTrue(
            jdbc.queryForObject(
                "SELECT embedding IS NOT NULL FROM memories WHERE id=?",
                Boolean.class,
                result.memoryId()));
      }
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM memory_reasons WHERE memory_id=?",
              Integer.class,
              result.memoryId()));
    }
  }

  void memoryProject() {
    jdbc.update("INSERT INTO projects(name) VALUES('memory-project')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,actor,'VIEWER' FROM projects CROSS JOIN (VALUES('reader'),('app')) callers(actor) WHERE name='memory-project'");
  }

  void seedMemory(String id, Home home, boolean searchable, boolean dual) {
    var store = new MemoryStore(jdbc);
    store.save(
        new Memory(
            id,
            "knowledge " + id,
            "deployment",
            new Provenance(Instant.now(), "fixture", "fixture"),
            MemoryState.ACTIVE,
            false,
            0,
            null,
            "body " + id,
            null,
            null,
            null,
            home));
    if (!searchable) return;
    float[] vector = new float[768];
    vector[0] = 1;
    if (dual) {
      var records =
          new JdbcEmbeddingWorkRepository(jdbc, work, new JdbcEmbeddingSpaceRepository(jdbc));
      assertTrue(
          records.publish(
              records.active(EmbeddingSlot.PROSE).orElseThrow(),
              records
                  .source(
                      EmbeddingWorkRepository.Key.of(EmbeddingWorkRepository.Store.MEMORIES, id))
                  .orElseThrow(),
              vector));
    } else store.saveEmbedding(id, vector);
  }

  MemoryQueries.Request memoryRead(
      String actor, boolean runBound, String invocation, MemoryQueries.Kind kind) {
    return new MemoryQueries.Request(
        actor,
        Home.of("memory-project"),
        kind,
        "knowledge",
        5,
        runBound,
        new QueryEmbeddings.Origin(invocation, () -> {}));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void memoryRecallUsesSystemAndFiltersPrivacyAndRetainedTiersInBothEncoderModes(boolean dual) {
    memoryProject();
    try (var dispatcher = dispatcher()) {
      var archive = memoryQueries(dispatcher, dual, null);
      seedMemory("mem_project", Home.of("memory-project"), true, dual);
      seedMemory("mem_global", Home.global(), true, dual);
      seedMemory("mem_missing", Home.global(), false, dual);
      seedMemory("mem_private", Home.global(), true, dual);
      var privateLog =
          new ConversationStore(jdbc)
              .log(
                  Origin.SUBMISSION,
                  Home.of("memory-project"),
                  "private",
                  null,
                  io.aeyer.plowshare.server.agents.Budget.of(1),
                  "reader",
                  null)
              .id();
      jdbc.update("INSERT INTO memory_provenance VALUES(?,?,1)", "mem_private", privateLog);
      jdbc.update(
          "INSERT INTO information_quarantined_payloads VALUES(?,'private fixture')", privateLog);
      var handler = new MemoryRecallHandler(archive);
      for (String actor : List.of("reader", "app")) {
        var result =
            handler.handle(
                Map.of("project", "memory-project", "question", "knowledge"),
                new Asking(null, actor));
        assertEquals(Code.OK, result.code());
        var response =
            assertInstanceOf(io.aeyer.plowshare.server.api.RecallResponse.class, result.payload());
        assertEquals(
            List.of("mem_project", "mem_global"),
            response.memories().stream().map(Memory::id).toList());
        assertEquals(1, response.unsearchable());
      }
      var confined =
          archive.recall(memoryRead("reader", true, "memory:run:1", MemoryQueries.Kind.RECALL));
      assertEquals(List.of("mem_project"), confined.memories().stream().map(Memory::id).toList());
      assertEquals(0, confined.unsearchable());
      var survey =
          archive.survey(
              memoryRead("reader", true, "memory:reminder:1", MemoryQueries.Kind.REMINDER));
      assertEquals(1, survey.found().size());
      assertEquals(
          3,
          jdbc.queryForObject("SELECT uses FROM memories WHERE id='mem_project'", Integer.class));
      assertEquals(
          2, jdbc.queryForObject("SELECT uses FROM memories WHERE id='mem_global'", Integer.class));
      assertEquals(
          0,
          jdbc.queryForObject("SELECT uses FROM memories WHERE id='mem_private'", Integer.class));
      assertEquals(4, embedded.get());
      assertEquals(
          4,
          jdbc.queryForObject(
              "SELECT count(*) FROM embedding_query_allowances WHERE call_limit IS NULL AND spent=1 AND closed",
              Integer.class));
    }
  }

  @Test
  void memoryToolAndReminderKeepReadOnlyParentAndDoNotBorrowItsFiniteBudget() {
    memoryProject();
    var identities = new JdbcIdentityRepository(jdbc);
    var grants = new JdbcScopedGrantRepository(jdbc);
    ApplicationPolicy applications =
        ignored -> new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of());
    var authority =
        new LiveScopedAuthority(
            grants, applications, new ApplicationGrantLineage(grants, applications), identities);
    var operations =
        new PinnedOperationAuthority(
            identities,
            grants,
            authority,
            new JdbcProjectMembers(jdbc),
            new JdbcOperationRecords(jdbc),
            new JdbcResourceScopeRepository(jdbc));
    var home = Home.of("memory-project");
    var parent = operations.admit("reader", operations.scope(home), Set.of(AuthorityAction.READ));
    var log =
        new ConversationStore(jdbc)
            .log(
                Origin.SUBMISSION,
                home,
                "memory_reader",
                null,
                io.aeyer.plowshare.server.agents.Budget.of(1),
                "reader",
                null)
            .id();
    var alive = new java.util.concurrent.atomic.AtomicBoolean(true);
    Runnable valid =
        () -> {
          if (!alive.get()) throw new IllegalStateException("turn ended");
          operations.requireTargets(parent, home, List.of());
        };
    try (var dispatcher = dispatcher()) {
      var archive = memoryQueries(dispatcher, false, null);
      seedMemory("mem_project", home, true, false);
      var tool =
          new io.aeyer.plowshare.server.agents.MemoryTools.Recall(archive)
              .forRun("reader", log, valid);
      tool.calledAs("call-1");
      assertTrue(tool.run("{\"question\":\"knowledge\"}", home).contains("mem_project"));
      assertThrows(RuntimeException.class, () -> tool.run("{\"question\":\"knowledge\"}", home));
      var reminder =
          new io.aeyer.plowshare.server.agents.learner.Reminder(archive)
              .forQueries(
                  "reader",
                  home,
                  () -> new QueryEmbeddings.Origin("memory-reminder:" + log + ":1", valid));
      var definition =
          new AgentDefinition(
              "memory_reader",
              "read",
              "model",
              List.of(io.aeyer.plowshare.server.agents.MemoryTools.RECALL_NAME),
              List.of(),
              List.of(),
              1,
              1,
              "read");
      assertTrue(reminder.whatTheArchiveHolds(definition, home, "knowledge").isPresent());
      assertTrue(reminder.whatTheArchiveHolds(definition, home, "knowledge").isEmpty());
      alive.set(false);
      tool.calledAs("call-2");
      assertThrows(RuntimeException.class, () -> tool.run("{\"question\":\"knowledge\"}", home));
      assertEquals(2, embedded.get());
      assertEquals(
          1,
          jdbc.queryForObject("SELECT uses FROM memories WHERE id='mem_project'", Integer.class));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT budget_spent FROM conversations WHERE id=?", Integer.class, log));
      assertFalse(parent.actions().contains(AuthorityAction.RUN_MODEL));
    }
  }

  @Test
  void disabledMemoryReadsAndRevocationDuringProviderIoCannotDiscloseOrChargeAnotherSource() {
    memoryProject();
    try (var dispatcher = dispatcher()) {
      seedMemory("mem_project", Home.of("memory-project"), true, false);
      var disabled = memoryQueries(dispatcher, false, 0);
      assertThrows(
          RuntimeException.class,
          () -> disabled.recall(memoryRead("reader", true, "disabled", MemoryQueries.Kind.RECALL)));
      assertEquals(0, embedded.get());
      var archive = memoryQueries(dispatcher, false, 1);
      afterEmbedding =
          () ->
              jdbc.update(
                  "DELETE FROM project_members WHERE handle='reader' AND project_id=(SELECT id FROM projects WHERE name='memory-project')");
      assertThrows(
          RuntimeException.class,
          () -> archive.recall(memoryRead("reader", true, "revoked", MemoryQueries.Kind.RECALL)));
      assertEquals(1, embedded.get());
      assertEquals(
          0,
          jdbc.queryForObject("SELECT uses FROM memories WHERE id='mem_project'", Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM embedding_query_allowances WHERE call_limit=1 AND spent=1 AND closed",
              Integer.class));
    }
  }

  @Test
  void memoryAccountReplacementCannotReuseAnOldReadEvenWithRestoredMembership() {
    memoryProject();
    try (var dispatcher = dispatcher()) {
      var archive = memoryQueries(dispatcher, false, null);
      seedMemory("mem_project", Home.of("memory-project"), true, false);
      afterEmbedding =
          () -> {
            jdbc.update("DELETE FROM admins WHERE handle='reader'");
            jdbc.update(
                "INSERT INTO admins(handle,password_hash,must_change_password,server_admin) VALUES('reader','fixture',false,false)");
            jdbc.update(
                "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','VIEWER' FROM projects WHERE name='memory-project'");
          };
      assertThrows(
          RuntimeException.class,
          () ->
              archive.recall(memoryRead("reader", true, "old-account", MemoryQueries.Kind.RECALL)));
      assertEquals(1, embedded.get());
      assertEquals(
          0,
          jdbc.queryForObject("SELECT uses FROM memories WHERE id='mem_project'", Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM embedding_query_allowances WHERE spent=1 AND closed",
              Integer.class));
    }
  }

  @Test
  void memoryDisclosureOrdersInvalidationAndOnlyUpdatesUseTelemetry() throws Exception {
    memoryProject();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var attempted = new CountDownLatch(1);
    var store = spy(new MemoryStore(jdbc));
    doAnswer(
            invocation -> {
              entered.countDown();
              assertTrue(release.await(10, TimeUnit.SECONDS));
              return invocation.callRealMethod();
            })
        .when(store)
        .used(any(), any());
    try (var dispatcher = dispatcher();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var archive = memoryQueries(dispatcher, false, null, store);
      seedMemory("mem_project", Home.of("memory-project"), true, false);
      var read =
          workers.submit(
              () ->
                  archive.recall(
                      memoryRead(
                          "reader", true, "before-invalidation", MemoryQueries.Kind.RECALL)));
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      var invalidation =
          workers.submit(
              () -> {
                attempted.countDown();
                return jdbc.update(
                    "UPDATE memories SET state='invalidated',invalidated_at=now(),invalidated_by='admin',invalidated_why='fixture',body='new body' WHERE id='mem_project'");
              });
      try {
        assertTrue(attempted.await(10, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> invalidation.get(100, TimeUnit.MILLISECONDS));
      } finally {
        release.countDown();
      }
      assertEquals(1, read.get(10, TimeUnit.SECONDS).memories().size());
      assertEquals(1, invalidation.get(10, TimeUnit.SECONDS));
      var later =
          archive.recall(
              memoryRead("reader", true, "after-invalidation", MemoryQueries.Kind.RECALL));
      assertTrue(later.memories().isEmpty());
      assertEquals(0, later.unsearchable());
      assertEquals(
          "new body",
          jdbc.queryForObject("SELECT body FROM memories WHERE id='mem_project'", String.class));
      assertEquals(
          1,
          jdbc.queryForObject("SELECT uses FROM memories WHERE id='mem_project'", Integer.class));
    } finally {
      release.countDown();
    }
  }

  @Test
  void memoryCancellationDuringAssemblyRollsBackUsesButRetainsSubmittedClaim() {
    memoryProject();
    var alive = new java.util.concurrent.atomic.AtomicBoolean(true);
    var store = spy(new MemoryStore(jdbc));
    doAnswer(
            invocation -> {
              var result = invocation.callRealMethod();
              alive.set(false);
              return result;
            })
        .when(store)
        .used(any(), any());
    try (var dispatcher = dispatcher()) {
      var archive = memoryQueries(dispatcher, false, null, store);
      seedMemory("mem_project", Home.of("memory-project"), true, false);
      var request =
          new MemoryQueries.Request(
              "reader",
              Home.of("memory-project"),
              MemoryQueries.Kind.RECALL,
              "knowledge",
              5,
              true,
              new QueryEmbeddings.Origin(
                  "cancelled-during-read",
                  () -> {
                    if (!alive.get()) throw new IllegalStateException("turn ended");
                  }));
      assertThrows(IllegalStateException.class, () -> archive.recall(request));
      assertEquals(1, embedded.get());
      assertEquals(
          0,
          jdbc.queryForObject("SELECT uses FROM memories WHERE id='mem_project'", Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM embedding_query_allowances WHERE spent=1 AND closed",
              Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_admissions WHERE submitted AND query_id IS NOT NULL",
              Integer.class));
    }
  }

  RetrievalService queryReader(LlmDispatcher dispatcher, boolean dual) {
    var props = new LlmProperties();
    props.setEmbeddingModel("model");
    props.setEmbeddingDim(768);
    props.setEmbeddingMaxInputTokens(2048);
    var reader =
        new RetrievalService(
            documents, new DispatchingEmbeddingClient(dispatcher, props, tokenizer), "model", 768);
    reader.useQueries(
        queries(new EmbeddingProperties(false, null, null, false, null).searchLimit()));
    if (dual) {
      var records =
          new JdbcEmbeddingWorkRepository(jdbc, work, new JdbcEmbeddingSpaceRepository(jdbc));
      for (var slot : EmbeddingSlot.values()) assertTrue(records.active(slot).isPresent());
      var counters = mock(EmbeddingTokenizers.class);
      when(counters.model(any())).thenReturn(tokenizer);
      reader.useDualEmbeddings(new DispatchingDualEmbeddings(records, dispatcher::embed, counters));
    }
    return reader;
  }

  Outcome searchFrame(RetrievalService reader, String actor) {
    var frames =
        new InformationFrames(
            catalogue,
            access,
            documents,
            reader,
            mock(Deliberation.class),
            mock(JobStore.class),
            new DocumentsProperties(),
            mock(PageFetcher.class),
            mock(InformationContentTypes.class),
            mock(InformationContentSettings.class),
            mock(InformationNavigationService.class),
            mock(InformationInterchangeService.class));
    return new FrameRouter(frames.frames())
        .route(
            new Envelope(
                UUID.randomUUID().toString(),
                "information.search",
                Envelope.CURRENT_VERSION,
                Map.of("query", "deployment knowledge", "scope", Map.of("kind", "global_read"))),
            new Asking("acceptance", actor));
  }

  Outcome call(String actor, String verb, Map<String, Object> payload) {
    var fields = new LinkedHashMap<>(payload);
    fields.put("scope", destination);
    return router.route(
        new Envelope(
            UUID.randomUUID().toString(), "information." + verb, Envelope.CURRENT_VERSION, fields),
        new Asking("acceptance", actor));
  }

  UUID upload(UUID request) {
    var result =
        call(
            "admin",
            "upload",
            Map.of(
                "requestId",
                request.toString(),
                "name",
                "manual.md",
                "text",
                "# Manual\n\nRetained deployment knowledge."));
    assertEquals(Code.ACCEPTED, result.code(), result.said());
    return assertInstanceOf(InformationCatalogue.Admission.class, result.payload()).revision();
  }

  @Test
  void destinationKeepsCustodySeparateAndLiveWritesCannotBorrowCreatorOrReaderAuthority() {
    var request = UUID.randomUUID();
    var revision = upload(request);
    assertEquals(revision, upload(request));
    assertEquals(
        "GLOBAL",
        jdbc.queryForObject(
            "SELECT q.owner_handle FROM information_resources q JOIN information_revisions r ON r.resource_id=q.id WHERE r.id=?",
            String.class,
            revision));
    assertEquals(
        "admin",
        jdbc.queryForObject(
            "SELECT a.legacy_handle FROM information_resources q JOIN information_revisions r ON r.resource_id=q.id JOIN authority_accounts a ON a.id=q.created_by WHERE r.id=?",
            String.class,
            revision));
    assertEquals(BuiltInIdentities.GLOBAL_KNOWLEDGE.value(), processing.home(revision).value());
    for (String actor : List.of("reader", "app")) {
      assertEquals(Code.OK, call(actor, "list", Map.of()).code());
      assertNotEquals(
          Code.OK,
          call(
                  actor,
                  "tags",
                  Map.of(
                      "revision",
                      revision.toString(),
                      "requestId",
                      UUID.randomUUID().toString(),
                      "tags",
                      List.of("stolen")))
              .code());
      assertNotEquals(
          Code.OK,
          call(
                  actor,
                  "upload",
                  Map.of(
                      "requestId",
                      UUID.randomUUID().toString(),
                      "name",
                      "denied.md",
                      "text",
                      "denied"))
              .code());
    }
    assertEquals(
        Code.OK,
        call(
                "admin",
                "tags",
                Map.of(
                    "revision",
                    revision.toString(),
                    "requestId",
                    UUID.randomUUID().toString(),
                    "tags",
                    List.of("manual")))
            .code());
    jdbc.update("UPDATE admins SET server_admin=false WHERE handle='admin'");
    assertNotEquals(
        Code.OK,
        call(
                "admin",
                "tags",
                Map.of(
                    "revision",
                    revision.toString(),
                    "requestId",
                    UUID.randomUUID().toString(),
                    "tags",
                    List.of("lost")))
            .code());
    assertEquals(
        Code.OK,
        call(
                "operator",
                "tags",
                Map.of(
                    "revision",
                    revision.toString(),
                    "requestId",
                    UUID.randomUUID().toString(),
                    "tags",
                    List.of("managed")))
            .code());
    assertEquals(Code.BAD_REQUEST, call("operator", "types.list", Map.of()).code());
  }

  LlmDispatcher dispatcher() {
    var transport =
        new LlmTransport() {
          public void close() {}

          public String poolName() {
            return "fixture";
          }

          public Completion complete(
              String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            chatted.incrementAndGet();
            chatMessages = List.copyOf(messages);
            afterChat.run();
            return new Completion(chatAnswer, "stop", TokenUsage.UNKNOWN, List.of());
          }

          public Completion stream(
              String model,
              List<ChatMessage> messages,
              Sampling sampling,
              List<ToolSchema> tools,
              Deltas sink,
              java.util.function.BooleanSupplier cancelled) {
            return complete(model, messages, sampling, tools);
          }

          public Embeddings embed(String model, List<String> texts) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            embedded.incrementAndGet();
            afterEmbedding.run();
            return new Embeddings(
                texts.stream()
                    .map(
                        t -> {
                          float[] v = new float[768];
                          v[0] = 1;
                          return v;
                        })
                    .toList(),
                null);
          }
        };
    return new LlmDispatcher(
        List.of(
            new LlmPool(
                "fixture",
                List.of("model", "code-encoder", "prose-encoder"),
                Map.of("reasoning", "model"),
                4,
                1,
                Duration.ofSeconds(5),
                transport)),
        new NoOpTokenLedger(),
        ignored -> "model",
        InferenceAccounting.NONE,
        null,
        null,
        io.aeyer.plowshare.server.security.ChatFiltering.NONE,
        new RetainedInferenceBoundary(new JdbcInferenceAdmissions(jdbc)));
  }

  InformationLifecycle lifecycle(LlmDispatcher dispatcher, boolean dual) {
    var definitions = new LinkedHashMap<String, AgentDefinition>();
    for (String name :
        List.of(
            Summariser.PARAGRAPH,
            Summariser.SPAN,
            Summariser.SECTION,
            Summariser.CHAPTER,
            Summariser.DOCUMENT))
      definitions.put(
          name,
          new AgentDefinition(
              name,
              "summary",
              "model",
              List.of(),
              List.of(),
              List.of(),
              1,
              1,
              "Summarise supplied evidence"));
    var runtime = new JobRuntime(dispatcher, List.of());
    var summariser =
        new Summariser(
            documents, runtime, () -> new AgentRegistry(definitions), 12, null, null, system);
    var props = new LlmProperties();
    props.setEmbeddingModel("model");
    props.setEmbeddingDim(768);
    props.setEmbeddingMaxInputTokens(2048);
    var embeddings = new DispatchingEmbeddingClient(dispatcher, props, tokenizer);
    var properties = new DocumentsProperties();
    UsageOwners owners =
        new UsageOwners() {
          public UsageAttribution in(
              Home home, String actor, UsageAttribution.Operation operation) {
            return UsageAttribution.system(home.project(), operation);
          }

          public UsageAttribution processing(String log, UsageAttribution.Operation operation) {
            return UsageAttribution.system(null, operation);
          }
        };
    DualEmbeddings lanes = null;
    if (dual) {
      var spaces = new JdbcEmbeddingSpaceRepository(jdbc);
      var records = new JdbcEmbeddingWorkRepository(jdbc, work, spaces);
      for (var slot : EmbeddingSlot.values()) {
        var space =
            spaces.register(
                new EmbeddingSpace.Definition(
                    slot.stored() + "-encoder",
                    "fixture-weights",
                    768,
                    "query: ",
                    "document: ",
                    "cls",
                    EmbeddingSpace.Normalization.NONE,
                    "none"));
        records.inputLimit(space, 2048);
        records.configure(
            slot,
            space,
            new EmbeddingSearchPolicy(
                EmbeddingSearchPolicy.Mode.EXACT, EmbeddingSearchPolicy.Distance.COSINE));
        // Match startup: activate the empty serving corpus before this admission derives chunks.
        assertTrue(
            records.activate(records.target(slot).orElseThrow()),
            () ->
                java.util.Arrays.stream(EmbeddingWorkRepository.Store.values())
                    .map(
                        store ->
                            store
                                + ":"
                                + records
                                    .pending(records.target(slot).orElseThrow(), store, 100)
                                    .size())
                    .toList()
                    .toString());
      }
      var counters = mock(EmbeddingTokenizers.class);
      when(counters.model(any())).thenReturn(tokenizer);
      lanes = new DispatchingDualEmbeddings(records, dispatcher::embed, counters);
      documents.useDualEmbeddings(lanes);
    }
    var processor =
        InformationLifecycle.processing(
            processing,
            work,
            catalogue,
            documents,
            embeddings,
            new Chunking(tokenizer, 512, 2048),
            32,
            768,
            () -> summariser,
            properties,
            null,
            owners,
            lanes,
            preparation);
    var inputs = new InformationJobs(new JdbcJobInformationRepository(jdbc), access);
    var hooks =
        new InformationStageHooks(
            new JdbcInformationStageRepository(jdbc),
            catalogue,
            new ConversationStore(jdbc),
            LogStages.NONE,
            inputs,
            Hooks.NONE,
            Harness.NONE,
            () -> null,
            system,
            processing);
    return new InformationLifecycle(processing, work, catalogue, processor, hooks);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void globalPreparationContinuesAfterCreatorLosesAdminAndChargesEveryInference(boolean dual) {
    UUID revision = upload(UUID.randomUUID());
    jdbc.update("UPDATE admins SET server_admin=false WHERE handle='admin'");
    try (var dispatcher = dispatcher();
        var lifecycle = lifecycle(dispatcher, dual)) {
      for (int i = 0; i < 8; i++) {
        if (ready(revision)) break;
        assertTrue(
            lifecycle.drainOne(revision),
            () ->
                jdbc.queryForList(
                        "SELECT stage,state,error FROM information_steps WHERE revision_id=?",
                        revision)
                    .toString());
      }
      assertTrue(
          ready(revision),
          jdbc.queryForList(
                  "SELECT stage,state,error FROM information_steps WHERE revision_id=?", revision)
              .toString());
      assertTrue(chatted.get() > 0);
      assertEquals(dual ? 4 : 2, embedded.get());
      var source = processing.budgetSource(revision);
      assertEquals(chatted.get() + embedded.get(), source.spent());
      assertEquals(
          source.spent(),
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_admissions WHERE budget_id=?",
              Integer.class,
              source.id()));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(DISTINCT budget_id) FROM inference_admissions", Integer.class));
      assertEquals(
          Code.OK,
          call("reader", "read", Map.of("revision", revision.toString(), "limit", 500)).code());
      assertEquals(
          Code.OK,
          call("app", "read", Map.of("revision", revision.toString(), "limit", 500)).code());
      assertFalse(
          new InformationJobs(new JdbcJobInformationRepository(jdbc), access)
              .logAllowed(source.log(), "reader"));
      var reopened =
          new JdbcInformationProcessingRepository(
              jdbc, Clock.systemUTC(), new JdbcProjectMembers(jdbc));
      assertEquals(source, reopened.budgetSource(revision));
      assertTrue(ready(revision));
      assertEquals(chatted.get() + embedded.get(), reopened.budgetSource(revision).spent());
      var reader = queryReader(dispatcher, dual);
      int preparedCalls = embedded.get();
      for (String actor : List.of("reader", "app")) {
        var result = searchFrame(reader, actor);
        assertEquals(Code.OK, result.code(), result.said());
        assertFalse(assertInstanceOf(List.class, result.payload()).isEmpty());
      }
      var scoped =
          reader
              .scoped(access, access.resolve("reader", InformationContext.Selection.globalRead()))
              .forQueries(QueryEmbeddings.Origin.request(() -> {}));
      assertFalse(scoped.rank("deployment knowledge", 5).documents().isEmpty());
      assertTrue(scoped.stance(revision, "deployment knowledge").isPresent());
      assertEquals(preparedCalls + 4, embedded.get());
      assertEquals(
          4,
          jdbc.queryForObject(
              "SELECT count(*) FROM embedding_query_allowances WHERE spent=1 AND call_limit IS NULL AND closed",
              Integer.class));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM embedding_query_allowances WHERE budget_id=?",
              Integer.class,
              source.id()));
      assertEquals(source.spent(), reopened.budgetSource(revision).spent());
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(ints = {1})
  void querySourceClaimsAreRetainedAndKnownToolCallsNeverAcquireReplacementAllowances(
      Integer limit) {
    var context = access.resolve("reader", InformationContext.Selection.globalRead());
    var query = new QueryEmbeddings.Query(QueryEmbeddings.Kind.SEARCH, "query", null, 5, "HYBRID");
    var origin = new QueryEmbeddings.Origin("retained-log:call-1", () -> {});
    var request = EmbeddingRequest.of("model", List.of("query"));
    var disabled = queries(0).open(context, query, origin);
    assertThrows(RuntimeException.class, () -> disabled.authorize(request));
    disabled.close();
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM embedding_query_allowances", Integer.class));
    var read = queries(limit).open(context, query, origin);
    var permit = read.authorize(request);
    new RetainedInferenceBoundary(new JdbcInferenceAdmissions(jdbc)).submitted(permit);
    assertThrows(RuntimeException.class, () -> read.authorize(request));
    try (var reopened = queries(limit == null ? 1 : null).open(context, query, origin)) {
      assertThrows(RuntimeException.class, () -> reopened.authorize(request));
    }
    assertEquals(
        1, jdbc.queryForObject("SELECT sum(spent) FROM embedding_query_allowances", Integer.class));
    read.close();
    assertThrows(RuntimeException.class, permit::require);
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE execution_allowances SET spent=0 WHERE id IN (SELECT budget_id FROM embedding_queries)"));
    assertThrows(
        DataAccessException.class, () -> jdbc.update("DELETE FROM embedding_query_allowances"));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE inference_admissions SET query_task_hash=? WHERE query_id IS NOT NULL",
                "a".repeat(64)));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                limit == null
                    ? "UPDATE embedding_queries SET call_limit=5"
                    : "UPDATE embedding_queries SET call_limit=NULL"));
    assertEquals(
        limit,
        jdbc.queryForObject("SELECT call_limit FROM embedding_query_allowances", Integer.class));
    assertEquals(
        limit,
        jdbc.queryForObject(
            "SELECT call_limit FROM inference_admissions WHERE query_id IS NOT NULL",
            Integer.class));
  }

  @Test
  void retainedReaderToolUsesSystemSearchAndKeepsItsParentAndInputLedger() {
    var revision = upload(UUID.randomUUID());
    try (var dispatcher = dispatcher();
        var lifecycle = lifecycle(dispatcher, false)) {
      for (int i = 0; i < 8 && !ready(revision); i++) lifecycle.drainOne(revision);
      assertTrue(ready(revision));
      jdbc.update("INSERT INTO projects(name) VALUES('reader-project')");
      jdbc.update(
          "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','VIEWER' FROM projects WHERE name='reader-project'");
      var identities = new JdbcIdentityRepository(jdbc);
      var grants = new JdbcScopedGrantRepository(jdbc);
      ApplicationPolicy applications =
          ignored -> new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of());
      var authority =
          new LiveScopedAuthority(
              grants, applications, new ApplicationGrantLineage(grants, applications), identities);
      var operations =
          new PinnedOperationAuthority(
              identities,
              grants,
              authority,
              new JdbcProjectMembers(jdbc),
              new JdbcOperationRecords(jdbc),
              new JdbcResourceScopeRepository(jdbc),
              new LiveAccountKnowledgePermissions(identities, grants, authority));
      var home = Home.of("reader-project");
      var parent = operations.admit("reader", operations.scope(home), Set.of(AuthorityAction.READ));
      assertTrue(parent.globalRead());
      var log =
          new ConversationStore(jdbc)
              .log(
                  Origin.SUBMISSION,
                  home,
                  "reader_tool",
                  null,
                  io.aeyer.plowshare.server.agents.Budget.of(1),
                  "reader",
                  null)
              .id();
      var inputs = new InformationJobs(new JdbcJobInformationRepository(jdbc), access, catalogue);
      var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
      var tool =
          new io.aeyer.plowshare.server.agents.InformationTool(false, () -> catalogue)
              .withRetrieval(queryReader(dispatcher, false))
              .forRun(access.within(KnowledgeReadScope.retained(parent)), inputs, "reader", log)
              .withQueryValidity(
                  () -> {
                    if (cancelled.get()) throw new IllegalStateException("run ended");
                    operations.require(parent);
                  });
      tool.calledAs("call-1");
      int before = embedded.get();
      assertTrue(
          tool.run("{\"operation\":\"rank\",\"query\":\"deployment knowledge\"}", home)
              .startsWith("{"));
      assertEquals(before + 1, embedded.get());
      assertEquals(List.of(revision), inputs.inputsOf(log, "reader"));
      assertThrows(
          RuntimeException.class,
          () -> tool.run("{\"operation\":\"rank\",\"query\":\"deployment knowledge\"}", home));
      assertEquals(before + 1, embedded.get());
      cancelled.set(true);
      tool.calledAs("call-2");
      assertThrows(
          RuntimeException.class,
          () -> tool.run("{\"operation\":\"rank\",\"query\":\"deployment knowledge\"}", home));
      assertEquals(before + 1, embedded.get());
      assertFalse(parent.actions().contains(AuthorityAction.RUN_MODEL));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT budget_spent FROM conversations WHERE id=?", Integer.class, log));
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(ints = {1})
  void concurrentQueryClaimsRespectPolicyAndClosedSourcesCannotBeReopened(Integer limit)
      throws Exception {
    var context = access.resolve("reader", InformationContext.Selection.globalRead());
    var records = new JdbcQueryEmbeddingRepository(jdbc);
    var source =
        work.inTransaction(
            () -> records.open(context, records.pin(context), "a".repeat(64), null, limit));
    int expected = limit == null ? 2 : 1;
    var start = new CountDownLatch(1);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++)
        futures.add(
            workers.submit(
                () -> {
                  start.await();
                  try {
                    work.inTransaction(() -> records.claim(source));
                    return true;
                  } catch (IllegalStateException exhausted) {
                    return false;
                  }
                }));
      start.countDown();
      int won = 0;
      for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) won++;
      assertEquals(expected, won);
    }
    assertThrows(
        RuntimeException.class,
        () ->
            records.claim(
                new QueryEmbeddingRepository.Source(
                    source.id(), source.budget(), limit == null ? 1 : null)));
    work.inTransaction(
        () -> {
          records.close(source);
          return null;
        });
    assertThrows(RuntimeException.class, () -> work.inTransaction(() -> records.claim(source)));
    assertEquals(
        expected,
        jdbc.queryForObject(
            "SELECT spent FROM embedding_query_allowances WHERE id=?", Integer.class, source.id()));
  }

  @Test
  void unlimitedQueryClaimsStillRequireTheExactChargedSourceAndTask() {
    var context = access.resolve("reader", InformationContext.Selection.globalRead());
    var records = new JdbcQueryEmbeddingRepository(jdbc);
    assertThrows(
        IllegalArgumentException.class,
        () -> records.open(context, records.pin(context), "a".repeat(64), null, -1));
    var source =
        work.inTransaction(
            () -> records.open(context, records.pin(context), "a".repeat(64), null, null));
    var claim = work.inTransaction(() -> records.claim(source));
    assertNull(claim.limit());
    var ledger = new JdbcInferenceAdmissions(jdbc);
    var forged =
        new InferenceBinding(
            UUID.randomUUID(), UUID.randomUUID(), source.budget(), claim.ordinal(), 5);
    assertThrows(DataAccessException.class, () -> ledger.retain(forged));
    assertThrows(RuntimeException.class, () -> records.bind(source, forged, "b".repeat(64)));
    var second = work.inTransaction(() -> records.claim(source));
    var actual =
        new InferenceBinding(
            UUID.randomUUID(), UUID.randomUUID(), source.budget(), second.ordinal(), null);
    ledger.retain(actual);
    records.bind(source, actual, "c".repeat(64));
    ledger.submitted(actual);
    assertThrows(IllegalStateException.class, () -> ledger.submitted(actual));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE embedding_queries SET spent=-1 WHERE id=?", source.id()));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE embedding_queries SET call_limit=0 WHERE id=?", source.id()));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT spent FROM embedding_query_allowances WHERE id=?", Integer.class, source.id()));
  }

  @Test
  void spendingPolicyMigrationPreservesExistingFiniteSourcesAndClaims() {
    String schema = "query_policy_" + UUID.randomUUID().toString().replace("-", "");
    var upgrade = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    new JdbcTemplate(upgrade).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    upgrade.setConnectionProperties(properties);
    Flyway.configure()
        .dataSource(upgrade)
        .schemas(schema)
        .defaultSchema(schema)
        .target("165")
        .load()
        .migrate();
    var old = new JdbcTemplate(upgrade);
    var query = UUID.randomUUID();
    var budget = UUID.randomUUID();
    var binding = new InferenceBinding(UUID.randomUUID(), UUID.randomUUID(), budget, 1, 3);
    old.update(
        "INSERT INTO embedding_queries(id,budget_id,account_id,scope_id,fingerprint,call_limit,spent) VALUES(?,?,?,?,?,3,1)",
        query,
        budget,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64));
    var records = new JdbcQueryEmbeddingRepository(old);
    var ledger = new JdbcInferenceAdmissions(old);
    // Seed historical evidence with the shipped schema, rather than running a new repository
    // against a schema that deliberately predates its canonical allowance contract.
    old.update(
        "INSERT INTO operation_inference_budgets(operation_id,budget_id) VALUES(?,?)",
        binding.operation(),
        budget);
    old.update(
        "INSERT INTO inference_admissions(id,operation_id,budget_id,ordinal,call_limit) VALUES(?,?,?,?,?)",
        binding.id(),
        binding.operation(),
        budget,
        1,
        3);
    records.bind(new QueryEmbeddingRepository.Source(query, budget, 3), binding, "b".repeat(64));
    ledger.submitted(binding);
    Flyway.configure().dataSource(upgrade).schemas(schema).defaultSchema(schema).load().migrate();
    ledger.require(binding);
    assertEquals(
        3,
        old.queryForObject(
            "SELECT call_limit FROM embedding_query_allowances WHERE id=?", Integer.class, query));
    assertEquals(
        1,
        old.queryForObject(
            "SELECT spent FROM embedding_query_allowances WHERE id=?", Integer.class, query));
    var retained = new QueryEmbeddingRepository.Source(query, budget, 3);
    assertEquals(2, records.claim(retained).ordinal());
    assertEquals(3, records.claim(retained).ordinal());
    assertThrows(IllegalStateException.class, () -> records.claim(retained));
    assertThrows(
        DataAccessException.class,
        () -> old.update("UPDATE embedding_queries SET call_limit=NULL WHERE id=?", query));
  }

  @Test
  void withdrawalDuringProviderExecutionStopsTargetDisclosureAndKeepsItsClaim() {
    var revision = upload(UUID.randomUUID());
    try (var dispatcher = dispatcher();
        var lifecycle = lifecycle(dispatcher, false)) {
      // Upload retains text before deriving searchable passages. Exercise actual preparation so
      // the bounded existence gate admits a real query; otherwise skipping inference is correct.
      for (int attempt = 0; attempt < 8 && !ready(revision); attempt++)
        assertTrue(lifecycle.drainOne(revision));
      assertTrue(ready(revision));
      embedded.set(0);
      afterEmbedding =
          () ->
              call(
                  "operator",
                  "withdraw",
                  Map.of(
                      "revision", revision.toString(), "requestId", UUID.randomUUID().toString()));
      var reader =
          queryReader(dispatcher, false)
              .scoped(access, access.resolve("reader", InformationContext.Selection.globalRead()))
              .forQueries(QueryEmbeddings.Origin.request(() -> {}));
      assertThrows(RuntimeException.class, () -> reader.retrieve("query", revision, 5));
      assertEquals(1, embedded.get());
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM embedding_query_allowances WHERE spent=1 AND closed",
              Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_admissions WHERE query_id IS NOT NULL AND submitted",
              Integer.class));
    }
  }

  @Test
  void finalReadFenceOrdersWithdrawalAndRefusesFollowingDisclosure() throws Exception {
    var revision = upload(UUID.randomUUID());
    var context = access.resolve("reader", InformationContext.Selection.globalRead());
    var read =
        queries(1)
            .open(
                context,
                new QueryEmbeddings.Query(
                    QueryEmbeddings.Kind.RETRIEVE, "query", revision, 5, null),
                QueryEmbeddings.Origin.request(() -> {}));
    var admitted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var result =
          workers.submit(
              () ->
                  read.disclose(
                      () -> {
                        admitted.countDown();
                        try {
                          if (!release.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("timeout");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                        return "visible";
                      }));
      assertTrue(admitted.await(10, TimeUnit.SECONDS));
      var withdraw =
          workers.submit(
              () ->
                  call(
                      "operator",
                      "withdraw",
                      Map.of(
                          "revision",
                          revision.toString(),
                          "requestId",
                          UUID.randomUUID().toString())));
      try {
        assertThrows(TimeoutException.class, () -> withdraw.get(200, TimeUnit.MILLISECONDS));
      } finally {
        release.countDown();
      }
      assertEquals("visible", result.get(10, TimeUnit.SECONDS));
      assertEquals(Code.OK, withdraw.get(10, TimeUnit.SECONDS).code());
    }
    assertThrows(RuntimeException.class, () -> read.disclose(() -> "must not disclose"));
    read.close();
  }

  boolean ready(UUID revision) {
    return jdbc.queryForObject(
        "SELECT count(*)=5 FROM information_steps WHERE revision_id=? AND stage IN ('extract','derive','embed','summarise','summary_embed') AND state='ready'",
        Boolean.class,
        revision);
  }

  @Test
  void explicitAllowanceIncreaseResumesWithoutResettingSpendingOrRepeatingCompletedEmbeddings() {
    UUID revision = upload(UUID.randomUUID());
    jdbc.update(
        "UPDATE execution_allowances SET call_limit=1 WHERE id=(SELECT processing_budget_id FROM information_revisions WHERE id=?)",
        revision);
    UUID budget = processing.budgetSource(revision).id();
    try (var dispatcher = dispatcher();
        var lifecycle = lifecycle(dispatcher, false)) {
      for (int i = 0; i < 5; i++) lifecycle.drainOne(revision);
      assertFalse(ready(revision));
      assertEquals(1, embedded.get());
      assertEquals(0, chatted.get());
      assertEquals(1, processing.budgetSource(revision).spent());
      assertEquals(
          Code.OK,
          call(
                  "operator",
                  "allowance",
                  Map.of(
                      "revision", revision.toString(),
                      "requestId", UUID.randomUUID().toString(),
                      "maxModelCalls", 40))
              .code());
      assertEquals(
          Code.OK,
          call(
                  "operator",
                  "retry",
                  Map.of(
                      "revision", revision.toString(),
                      "requestId", UUID.randomUUID().toString(),
                      "stages", List.of("summarise")))
              .code());
      for (int i = 0; i < 8 && !ready(revision); i++) lifecycle.drainOne(revision);
      assertTrue(ready(revision));
      assertEquals(2, embedded.get());
      var retained = processing.budgetSource(revision);
      assertEquals(budget, retained.id());
      assertEquals(chatted.get() + embedded.get(), retained.spent());
    }
  }

  InformationLifecycle.Lease embeddingLease(UUID revision) {
    jdbc.update(
        "UPDATE information_steps SET state='ready' WHERE revision_id=? AND stage IN ('extract','derive')",
        revision);
    // A tiny retained projection supplies an exact summary output target without reading private
    // data.
    jdbc.update(
        "UPDATE information_steps SET state='ready' WHERE revision_id=? AND stage IN ('embed','summarise')",
        revision);
    return work.inTransaction(
        () -> {
          var lease = processing.candidate(revision, false).orElseThrow().lease();
          processing.start(lease, null);
          return lease;
        });
  }

  @Test
  void scopedAdminRevocationWaitsForTheAdmittedMutationAndThenStopsNewWrites() throws Exception {
    var revision = upload(UUID.randomUUID());
    var context =
        access.resolve(
            "admin", InformationContext.Selection.account(BuiltInIdentities.GLOBAL.value()));
    var records =
        new JdbcInformationCatalogueRepository(
            jdbc, Clock.systemUTC(), new RecordedInformationEvents());
    var admitted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var mutation =
          workers.submit(
              () ->
                  work.inTransaction(
                      () -> {
                        records.lockResource(context, revision, false);
                        admitted.countDown();
                        try {
                          if (!release.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("fixture timeout");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        records.tags(context, revision, List.of("committed"));
                        return true;
                      }));
      assertTrue(admitted.await(10, TimeUnit.SECONDS));
      var revoke =
          workers.submit(
              () -> jdbc.update("UPDATE admins SET server_admin=false WHERE handle='admin'"));
      try {
        assertThrows(TimeoutException.class, () -> revoke.get(200, TimeUnit.MILLISECONDS));
      } finally {
        release.countDown();
      }
      assertTrue(mutation.get(10, TimeUnit.SECONDS));
      revoke.get(10, TimeUnit.SECONDS);
    }
    assertThrows(
        RuntimeException.class,
        () -> work.inTransaction(() -> records.lockResource(context, revision, false)));
  }

  @Test
  void concurrentLastCallClaimsCannotOverdrawOrReplayAndReopenedBudgetStaysExhausted()
      throws Exception {
    UUID revision = upload(UUID.randomUUID());
    jdbc.update(
        "UPDATE execution_allowances SET call_limit=1 WHERE id=(SELECT processing_budget_id FROM information_revisions WHERE id=?)",
        revision);
    var lease = embeddingLease(revision);
    var key =
        EmbeddingWorkRepository.Key.of(
            EmbeddingWorkRepository.Store.DOCUMENTS, revision.toString());
    var start = new CountDownLatch(1);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++)
        futures.add(
            workers.submit(
                () -> {
                  start.await();
                  try {
                    new BoundPreparationEmbeddings(
                            processing, work, new BoundSemanticEmbedding(system))
                        .forSources(lease, List.of(key))
                        .authorize(EmbeddingRequest.of("model", List.of("retained summary")));
                    return true;
                  } catch (IllegalStateException refused) {
                    return false;
                  }
                }));
      start.countDown();
      int winners = 0;
      for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) winners++;
      assertEquals(1, winners);
    }
    var reopened =
        new BoundPreparationEmbeddings(processing, work, new BoundSemanticEmbedding(system));
    assertTrue(reopened.budget(lease).tryClaim().isEmpty());
    assertEquals(1, processing.budgetSource(revision).spent());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM inference_admissions WHERE preparation_revision=?",
            Integer.class,
            revision));
  }

  @Test
  void pendingClaimBlocksReplayAndSharesStableBudgetWithChat() {
    UUID revision = upload(UUID.randomUUID());
    var lease = embeddingLease(revision);
    var key =
        EmbeddingWorkRepository.Key.of(
            EmbeddingWorkRepository.Store.DOCUMENTS, revision.toString());
    var permit = preparation.forSources(lease, List.of(key));
    var request = EmbeddingRequest.of("model", List.of("retained summary"));
    var captured = permit.authorize(request);
    var source = processing.budgetSource(revision);
    assertEquals(source.id(), captured.binding().budget());
    assertEquals(1, source.spent());
    assertThrows(
        RuntimeException.class,
        () ->
            captured.requireRequest(
                EmbeddingRequest.of("model", List.of("substituted private input"))));
    new RetainedInferenceBoundary(new JdbcInferenceAdmissions(jdbc)).submitted(captured);
    var reopened =
        new BoundPreparationEmbeddings(
            new JdbcInformationProcessingRepository(
                jdbc, Clock.systemUTC(), new JdbcProjectMembers(jdbc)),
            work,
            new BoundSemanticEmbedding(system));
    assertThrows(
        IllegalStateException.class,
        () -> reopened.forSources(lease, List.of(key)).authorize(request));
    assertEquals(1, processing.budgetSource(revision).spent());
    permit.published();
    var budget = reopened.budget(lease);
    assertEquals(source.id(), budget.tryClaim().orElseThrow().budget());
    assertEquals(2, processing.budgetSource(revision).spent());
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_revisions SET processing_budget_id=? WHERE id=?",
                UUID.randomUUID(),
                revision));
    jdbc.update("UPDATE information_revisions SET availability='withdrawn' WHERE id=?", revision);
    assertThrows(RuntimeException.class, captured::require);
  }
}
