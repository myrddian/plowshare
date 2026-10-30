package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.information.*;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.util.*;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Actual index administration, filtered queries, source fencing and cross-store activation. */
@Tag("full-db")
@Testcontainers
class EmbeddingRuntimeRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg16-bookworm");

  static JdbcTemplate jdbc;
  static UnitOfWork work;
  static EmbeddingSpaceRepository spaces;
  JdbcEmbeddingWorkRepository repository;
  UUID document, chunk, privateDocument, codeDocument;

  @BeforeAll
  static void database() {
    var ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    jdbc = new JdbcTemplate(ds);
    var template = new TransactionTemplate(new DataSourceTransactionManager(ds));
    work =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> callback) {
            return template.execute(status -> callback.get());
          }
        };
    spaces = new JdbcEmbeddingSpaceRepository(jdbc);
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES('alice','fixture'),('bob','fixture')");
    for (String handle : java.util.List.of("alice", "bob"))
      io.aeyer.plowshare.server.testing.AuthorityFixtures.personalIdentity(jdbc, handle);
  }

  @BeforeEach
  void fixtures() {
    jdbc.execute(
        "TRUNCATE memories,information_resources,documents,conversations,digests,retrieval_sources CASCADE");
    jdbc.update(
        "UPDATE embedding_slots SET active_space_id=NULL,target_space_id=NULL,active_mode=NULL,target_mode=NULL,active_distance=NULL,target_distance=NULL");
    repository = new JdbcEmbeddingWorkRepository(jdbc, work);
    jdbc.update(
        "INSERT INTO memories(id,summary,scope,body,state,formed_at,formed_by,formed_where) VALUES('memory','needle','scope','body','active',now(),'fixture','fixture')");
    jdbc.update(
        "INSERT INTO memories(id,summary,scope,body,state,formed_at,formed_by,formed_where) VALUES('near','needle','scope','body','active',now(),'fixture','fixture')");
    document = document("visible.txt", "document", "alice");
    privateDocument = document("private.txt", "document", "bob");
    codeDocument = document("source.java", "code", "alice");
    chunk =
        jdbc.queryForObject(
            "SELECT c.id FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=?",
            UUID.class,
            document);
    jdbc.update(
        "UPDATE retrieval_sources SET status='ready',generation='fixture' WHERE source_type='digest'");
    jdbc.update(
        "INSERT INTO retrieval_passages(source_type,source_id,position,passage) SELECT 'digest',id,0,summary FROM digests");
  }

  private UUID document(String name, String type, String account) {
    UUID id = UUID.randomUUID(), paragraph = UUID.randomUUID(), chunkId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO documents(id,source_name,title,content_hash,text_hash,byte_size,ingested_at,ingested_by,summary,document_type) VALUES(?,?,'needle','hash','text-hash',1,now(),'fixture','needle',?)",
        id,
        name,
        type);
    jdbc.update(
        "INSERT INTO paragraphs(id,document_id,text,content_hash,occurrence,ordinal) VALUES(?,?,'needle paragraph','hash',1,1)",
        paragraph,
        id);
    jdbc.update(
        "INSERT INTO chunks(id,paragraph_id,ordinal,text,byte_size,split_mid_sentence) VALUES(?,?,1,'needle chunk',1,false)",
        chunkId,
        paragraph);
    jdbc.update(
        "INSERT INTO information_document_policies(document_id,owner_handle,visibility,assigned_at) VALUES(?,?,'personal',now())",
        id,
        account);
    // Live catalogue discovery uses substrate homes. Legacy projection rows alone are not an
    // authorized knowledge resource; retain the owning resource/revision just as ingestion does.
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,kind,home_scope_id) SELECT ?,?,?,?,'source',s.id FROM authority_scopes s JOIN authority_accounts a ON a.id=s.account_id WHERE a.legacy_handle=? AND s.kind='ACCOUNT_KNOWLEDGE' AND s.builtin IS NULL",
        id,
        "account:" + account,
        name,
        account,
        account);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,text_hash,byte_size,document_type) VALUES(?,?,1,'needle','text/plain','hash','text-hash',1,?)",
        id,
        id,
        type);
    return id;
  }

  private EmbeddingProfile target(EmbeddingSlot slot, EmbeddingSearchPolicy.Mode mode, int width) {
    var space =
        spaces.register(
            new EmbeddingSpace.Definition(
                slot.stored() + UUID.randomUUID(),
                "weights",
                width,
                "",
                "",
                "cls",
                EmbeddingSpace.Normalization.NONE,
                "none"));
    repository.inputLimit(space, 1000);
    return repository.configure(
        slot, space, new EmbeddingSearchPolicy(mode, EmbeddingSearchPolicy.Distance.COSINE));
  }

  private static float[] vector(EmbeddingProfile profile) {
    float[] result = new float[profile.space().definition().dimensions()];
    result[profile.slot() == EmbeddingSlot.CODE ? 1 : 0] = 1;
    return result;
  }

  private void populate(EmbeddingProfile profile) {
    for (var store : EmbeddingWorkRepository.Store.values())
      for (var source : repository.pending(profile, store, 100)) {
        float[] vector = vector(profile);
        if (source.key().store() == EmbeddingWorkRepository.Store.MEMORIES
            && profile.slot() == EmbeddingSlot.PROSE)
          vector =
              source.key().id().equals("near")
                  ? new float[] {1, 0.01f, 0, 0}
                  : new float[] {0.8f, 0.6f, 0, 0};
        assertTrue(repository.publish(profile, source, vector));
      }
  }

  private DualEmbeddings readers() {
    return new DualEmbeddings() {
      public String fingerprint() {
        return "fixture";
      }

      public EmbeddingProfile active(EmbeddingSlot slot) {
        return repository.active(slot).orElseThrow();
      }

      public EmbeddingQuery query(EmbeddingProfile profile, String text, UsageAttribution owner) {
        return new EmbeddingQuery(profile, vector(profile));
      }

      public List<EmbeddingQuery> queries(
          EmbeddingProfile profile, List<String> texts, UsageAttribution owner) {
        return texts.stream().map(t -> query(profile, t, owner)).toList();
      }

      public <T> T read(EmbeddingProfile profile, Supplier<T> callback) {
        return repository.read(profile, callback);
      }

      public boolean repair(EmbeddingWorkRepository.Key key, UsageAttribution owner) {
        throw new UnsupportedOperationException();
      }
    };
  }

  static java.util.stream.Stream<Arguments> policies() {
    return java.util.Arrays.stream(EmbeddingSearchPolicy.Mode.values())
        .flatMap(
            mode ->
                java.util.Arrays.stream(EmbeddingSearchPolicy.Distance.values())
                    .filter(
                        distance ->
                            distance != EmbeddingSearchPolicy.Distance.L1
                                || (mode != EmbeddingSearchPolicy.Mode.IVFFLAT_VECTOR
                                    && mode != EmbeddingSearchPolicy.Mode.IVFFLAT_HALF))
                    .map(distance -> Arguments.of(mode, distance)));
  }

  @ParameterizedTest
  @MethodSource("policies")
  void everyModeBuildsItsOwnIndexesAndReadsOnlyVisibleCurrentVectors(
      EmbeddingSearchPolicy.Mode mode, EmbeddingSearchPolicy.Distance distance) {
    var code = target(EmbeddingSlot.CODE, mode, 3);
    var initial = target(EmbeddingSlot.PROSE, mode, 4);
    var prose =
        repository.configure(
            EmbeddingSlot.PROSE, initial.space(), new EmbeddingSearchPolicy(mode, distance));
    assertFalse(repository.activate(prose));
    populate(code);
    populate(prose);
    assertTrue(repository.activate(code));
    assertTrue(repository.activate(prose));
    var dual = readers();
    var profile = dual.active(EmbeddingSlot.PROSE);
    var query = dual.query(profile, "needle", UsageAttribution.LEGACY);
    var memories = new MemoryStore(jdbc);
    memories.useDualEmbeddings(dual);
    memories.protectInformation();
    assertEquals(
        List.of("near", "memory"),
        dual.read(profile, () -> memories.searchByVector(query, Home.global(), 10)).stream()
            .map(m -> m.id())
            .toList());
    assertTrue(memories.unsearchable(Home.global()).isEmpty());
    assertEquals(0, memories.countUnsearchable(Home.global()));
    var store = new DocumentStore(jdbc, work);
    var service =
        new RetrievalService(store, mock(EmbeddingClient.class), "unused legacy", 768)
            .checkingConfiguration();
    service.useDualEmbeddings(dual);
    // A personal selection is an alias for the account's personal project, so it is checked as an
    // ordinary project read. The real membership source answers from the rows the fixture
    // provisioned; a mock would deny alice her own space and say nothing about embeddings.
    var access =
        new InformationAccess(new EffectiveProjectPermissions(new JdbcProjectMembers(jdbc)));
    var personal = access.resolve("alice", InformationContext.Selection.personal());
    var reader = service.scoped(access, personal);
    assertEquals(
        List.of(document),
        reader.search("needle", 5, RetrievalService.Mode.VECTOR).hits().stream()
            .map(DocumentStore.Hit::documentId)
            .distinct()
            .toList());
    assertEquals(1, reader.rank("needle", 5).documents().size());
    assertEquals(1, reader.within(document, "needle", 10).size());
    assertEquals(1, reader.retrieve("needle", document, 10).size());
    assertEquals(0, reader.retrieve("needle", privateDocument, 10).size());
    assertTrue(reader.stance(document, "needle").isPresent());
    assertTrue(reader.stance(privateDocument, "needle").isEmpty());
    var codeReader = service.scoped(access, personal.withCorpus(InformationContext.Corpus.CODE));
    assertEquals(
        List.of(codeDocument),
        codeReader
            .search("natural language question", 5, RetrievalService.Mode.VECTOR)
            .hits()
            .stream()
            .map(DocumentStore.Hit::documentId)
            .distinct()
            .toList());
    var passages = new JdbcPassageRepository(jdbc, work);
    assertFalse(
        dual.read(
                profile,
                () ->
                    passages.rank(
                        Home.global(),
                        "digest",
                        query,
                        10,
                        "fixture",
                        PassageRepository.ReadScope.UNRESTRICTED))
            .isEmpty());
    assertTrue(
        passages
            .coverage(
                Home.global(),
                "digest",
                "fixture",
                PassageRepository.ReadScope.UNRESTRICTED,
                Optional.of(profile))
            .complete());
    if (mode != EmbeddingSearchPolicy.Mode.EXACT)
      for (var owner : EmbeddingWorkRepository.Store.values())
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relname=?",
                Integer.class,
                new EmbeddingIndexPlan(profile).indexName(owner)));
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT vector_dims(prose_embedding) FROM memories WHERE id='memory'", Integer.class));
  }

  @Test
  void rebuildKeepsOldSlotUntilAllStoresAreCurrentAndRejectsRacedReads() {
    var first = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    populate(first);
    assertTrue(repository.activate(first));
    var active = repository.active(EmbeddingSlot.PROSE).orElseThrow();
    var replacement = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.HNSW_BINARY, 4);
    assertEquals(first.space(), repository.active(EmbeddingSlot.PROSE).orElseThrow().space());
    populate(replacement);
    var source =
        repository
            .source(
                EmbeddingWorkRepository.Key.of(
                    EmbeddingWorkRepository.Store.CHUNKS, chunk.toString()))
            .orElseThrow();
    jdbc.update("UPDATE chunks SET text='changed source' WHERE id=?", chunk);
    assertFalse(repository.publish(replacement, source, vector(replacement)));
    var currentSource = repository.source(source.key()).orElseThrow();
    var serving = repository.active(EmbeddingSlot.PROSE).orElseThrow();
    assertTrue(repository.publish(serving, currentSource, vector(serving)));
    assertEquals(
        first.space().id(),
        jdbc.queryForObject("SELECT prose_space_id FROM chunks WHERE id=?", String.class, chunk));
    // Serving-generation repair is not replacement coverage.
    assertFalse(repository.activate(replacement));
    populate(replacement);
    assertTrue(repository.activate(replacement));
    assertEquals(replacement.space(), repository.active(EmbeddingSlot.PROSE).orElseThrow().space());
    assertThrows(EmbeddingException.class, () -> repository.read(active, () -> "must not run"));
  }

  @Test
  void policyChangeDoesNotRequestAnyReembeddingAndFailureBackoffIsDurable() {
    var first = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    populate(first);
    assertTrue(repository.activate(first));
    var current = repository.active(EmbeddingSlot.PROSE).orElseThrow();
    var changed =
        repository.configure(
            EmbeddingSlot.PROSE,
            current.space(),
            new EmbeddingSearchPolicy(
                EmbeddingSearchPolicy.Mode.HNSW_HALF, EmbeddingSearchPolicy.Distance.COSINE));
    for (var owner : EmbeddingWorkRepository.Store.values())
      assertTrue(repository.pending(changed, owner, 100).isEmpty());
    assertTrue(repository.activate(changed));
    var code = target(EmbeddingSlot.CODE, EmbeddingSearchPolicy.Mode.EXACT, 3);
    var memory = repository.pending(code, EmbeddingWorkRepository.Store.MEMORIES, 100).getFirst();
    repository.failed(code, memory, "EmbeddingException");
    assertEquals(1, repository.pending(code, EmbeddingWorkRepository.Store.MEMORIES, 100).size());
    var restarted = new JdbcEmbeddingWorkRepository(jdbc, work);
    assertEquals(1, restarted.pending(code, EmbeddingWorkRepository.Store.MEMORIES, 100).size());
    jdbc.update("UPDATE memories SET summary='new source' WHERE id='memory'");
    assertEquals(2, restarted.pending(code, EmbeddingWorkRepository.Store.MEMORIES, 100).size());
  }

  @Test
  void activationWaitsForAnEntireCapturedReadOperation() throws Exception {
    var first = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    populate(first);
    assertTrue(repository.activate(first));
    var replacement = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.HNSW_VECTOR, 4);
    populate(replacement);
    var captured = repository.active(EmbeddingSlot.PROSE).orElseThrow();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var reader =
          executor.submit(
              () ->
                  repository.read(
                      captured,
                      () -> {
                        entered.countDown();
                        try {
                          if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("fixture timed out");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return repository.active(EmbeddingSlot.PROSE).orElseThrow().space();
                      }));
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      var activating = executor.submit(() -> repository.activate(replacement));
      try {
        assertThrows(
            java.util.concurrent.TimeoutException.class,
            () -> activating.get(150, java.util.concurrent.TimeUnit.MILLISECONDS));
      } finally {
        release.countDown();
      }
      assertEquals(first.space(), reader.get(5, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(activating.get(5, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(
          replacement.space(), repository.active(EmbeddingSlot.PROSE).orElseThrow().space());
    }
  }

  @Test
  void anIvfflatIndexBuiltOnAnEmptyStoreServesItsFirstCommittedVector() {
    jdbc.execute(
        "TRUNCATE memories,information_resources,documents,conversations,digests,retrieval_sources CASCADE");
    var target = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.IVFFLAT_HALF, 4);
    assertTrue(repository.activate(target));
    var active = repository.active(EmbeddingSlot.PROSE).orElseThrow();
    jdbc.update(
        "INSERT INTO memories(id,summary,scope,body,state,formed_at,formed_by,formed_where) VALUES('first','needle','scope','body','active',now(),'fixture','fixture')");
    var source =
        repository
            .source(EmbeddingWorkRepository.Key.of(EmbeddingWorkRepository.Store.MEMORIES, "first"))
            .orElseThrow();
    assertTrue(repository.publish(active, source, vector(active)));
    var query = new EmbeddingQuery(active, vector(active));
    var memories = new MemoryStore(jdbc);
    memories.useDualEmbeddings(readers());
    var hits =
        repository.read(
            active,
            () -> {
              jdbc.execute("SET LOCAL enable_seqscan=off");
              jdbc.execute("SET LOCAL enable_bitmapscan=off");
              return memories.searchByVector(query, Home.global(), 10);
            });
    assertEquals(List.of("first"), hits.stream().map(m -> m.id()).toList());
  }

  @Test
  void legacyRepairPublicationRequiresTheCurrentSourceAndItsLiveOperation() {
    var key = EmbeddingWorkRepository.Key.of(EmbeddingWorkRepository.Store.MEMORIES, "memory");
    var old = repository.source(key).orElseThrow();
    var vector = new float[768];
    vector[0] = 1;
    jdbc.update("UPDATE memories SET summary='changed' WHERE id='memory'");
    assertFalse(repository.publishLegacy(old, vector, () -> {}));
    assertNull(
        jdbc.queryForObject(
            "SELECT embedding::text FROM memories WHERE id='memory'", String.class));
    var current = repository.source(key).orElseThrow();
    assertThrows(
        IllegalStateException.class,
        () ->
            repository.publishLegacy(
                current,
                vector,
                () -> {
                  throw new IllegalStateException("operation ended");
                }));
    assertTrue(repository.publishLegacy(current, vector, () -> {}));
    assertNotNull(
        jdbc.queryForObject(
            "SELECT embedding::text FROM memories WHERE id='memory'", String.class));
  }

  @Test
  void discoveryDisablesCompilationForGenericAndCustomPlansWithoutLeakingSessionSettings() {
    var target = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    for (String mode : List.of("force_generic_plan", "force_custom_plan")) {
      var explained = new java.util.concurrent.atomic.AtomicInteger();
      var observed =
          new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override
            public <T> T queryForObject(
                String sql,
                org.springframework.jdbc.core.RowMapper<T> mapper,
                Object... arguments) {
              if (sql.startsWith("WITH page AS MATERIALIZED")) {
                assertEquals("off", super.queryForObject("SHOW jit", String.class));
                String plan =
                    super.queryForObject("EXPLAIN (FORMAT JSON) " + sql, String.class, arguments);
                try {
                  assertFalse(
                      new com.fasterxml.jackson.databind.ObjectMapper()
                          .readTree(plan)
                          .get(0)
                          .has("JIT"));
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                  throw new IllegalStateException(invalid);
                }
                explained.incrementAndGet();
              }
              return super.queryForObject(sql, mapper, arguments);
            }

            @Override
            public <T> List<T> query(
                String sql,
                org.springframework.jdbc.core.RowMapper<T> mapper,
                Object... arguments) {
              if (sql.startsWith("WITH page AS MATERIALIZED")
                  || sql.startsWith(
                      "SELECT source_type,source_id,position FROM embedding_repair_work")) {
                assertEquals("off", super.queryForObject("SHOW jit", String.class));
                // Planning DML is read-only. Never execute a second discovery mutation just to
                // profile
                // it; the actual repository statement below owns its one atomic execution.
                String plan =
                    super.queryForObject("EXPLAIN (FORMAT JSON) " + sql, String.class, arguments);
                try {
                  assertFalse(
                      new com.fasterxml.jackson.databind.ObjectMapper()
                          .readTree(plan)
                          .get(0)
                          .has("JIT"));
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                  throw new IllegalStateException(invalid);
                }
                explained.incrementAndGet();
              }
              return super.query(sql, mapper, arguments);
            }
          };
      var measured = new JdbcEmbeddingWorkRepository(observed, work);
      work.inTransaction(
          () -> {
            jdbc.execute("SET LOCAL jit=on");
            jdbc.execute("SET LOCAL jit_above_cost=0");
            jdbc.execute("SET LOCAL plan_cache_mode=" + mode);
            jdbc.execute("SET LOCAL statement_timeout='7s'");
            jdbc.update(
                "UPDATE embedding_repair_scans SET next_at=now() WHERE slot=? AND space_id=?",
                target.slot().stored(),
                target.space().id());
            for (int request = 0; request < 7; request++)
              measured.pending(target, EmbeddingWorkRepository.Store.CHUNKS, 4);
            assertTrue(explained.get() >= 8);
            assertEquals("on", jdbc.queryForObject("SHOW jit", String.class));
            assertEquals("7s", jdbc.queryForObject("SHOW statement_timeout", String.class));
            return null;
          });
    }
  }

  @Test
  void bootstrapKeepsExistingRetryDeadlinesAndUncertainAttemptsInTheDurableQueue() {
    var target = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    var key = EmbeddingWorkRepository.Key.of(EmbeddingWorkRepository.Store.MEMORIES, "memory");
    var source = repository.source(key).orElseThrow();
    // This models an existing failure from before the queue migration: no pending key exists yet.
    repository.failed(target, source, "EmbeddingException");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM embedding_repair_work WHERE source_id='memory'", Integer.class),
        "failure writes enqueue their own deadline before discovery");
    jdbc.update("DELETE FROM embedding_repair_work WHERE source_id='memory'");
    assertFalse(repository.pending(target, key.store(), 100).contains(source));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM embedding_repair_work q JOIN memories_embedding_failures f ON f.source_id=q.source_id AND f.slot=q.slot AND f.space_id=q.space_id WHERE q.source_id='memory' AND q.available_at=f.retry_at",
            Integer.class));
    jdbc.update(
        "UPDATE memories_embedding_failures SET retry_at=now()-interval '1 second' WHERE source_id='memory'");
    assertTrue(
        repository.pending(target, key.store(), 100).contains(source),
        "a retained retry wakes immediately, without waiting for another recovery sweep");
    assertTrue(repository.beginRepair(target, source));
    // Simulate queue re-bootstrap while the retained in-flight attempt remains authoritative.
    jdbc.update("DELETE FROM embedding_repair_work WHERE source_id='memory'");
    jdbc.update(
        "UPDATE embedding_repair_scans SET next_at=now(),source_id=NULL,enumerated=false WHERE slot='prose' AND store='memories'");
    assertFalse(repository.pending(target, key.store(), 100).contains(source));
    assertTrue(
        jdbc.queryForObject(
            "SELECT available_at='infinity'::timestamptz FROM embedding_repair_work WHERE source_id='memory' AND slot='prose'",
            Boolean.class));
    assertFalse(repository.beginRepair(target, source));
  }

  @Test
  void aLateSourceCommitBehindTheRecoveryCursorIsStillDiscovered() throws Exception {
    var target = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    UUID paragraph =
        jdbc.queryForObject("SELECT paragraph_id FROM chunks WHERE id=?", UUID.class, chunk);
    UUID late = new UUID(0, 1);
    try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
      connection.setAutoCommit(false);
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO chunks(id,paragraph_id,ordinal,text,byte_size,split_mid_sentence) VALUES(?,?,2,'x',1,false)")) {
        insert.setObject(1, late);
        insert.setObject(2, paragraph);
        insert.executeUpdate();
      }
      assertFalse(
          repository.pending(target, EmbeddingWorkRepository.Store.CHUNKS, 100).stream()
              .anyMatch(source -> source.key().id().equals(late.toString())));
      connection.commit();
      assertTrue(
          repository.pending(target, EmbeddingWorkRepository.Store.CHUNKS, 100).stream()
              .anyMatch(source -> source.key().id().equals(late.toString())),
          "source enqueue is atomic with the late commit, independent of cursor position");
    }
  }

  @Test
  void reconciliationIsKeysetPagedAndConcurrentWorkersReserveDistinctSources() throws Exception {
    UUID paragraph =
        jdbc.queryForObject("SELECT paragraph_id FROM chunks WHERE id=?", UUID.class, chunk);
    jdbc.update(
        "INSERT INTO chunks(id,paragraph_id,ordinal,text,byte_size,split_mid_sentence) SELECT gen_random_uuid(),?,n,'x',1,false FROM generate_series(2,1006) n",
        paragraph);
    var target = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    assertFalse(repository.activationCandidate(target));
    assertEquals(100, repository.pending(target, EmbeddingWorkRepository.Store.CHUNKS, 100).size());
    assertEquals(
        1000,
        jdbc.queryForObject(
            "SELECT count(*) FROM embedding_repair_work WHERE store='chunks'", Integer.class));
    assertNotNull(
        jdbc.queryForObject(
            "SELECT source_id FROM embedding_repair_scans WHERE store='chunks' AND slot='prose'",
            String.class));
    assertEquals(100, repository.pending(target, EmbeddingWorkRepository.Store.CHUNKS, 100).size());
    assertEquals(
        1008,
        jdbc.queryForObject(
            "SELECT count(*) FROM embedding_repair_work WHERE store='chunks'", Integer.class));
    var next =
        jdbc.queryForObject(
            "SELECT next_at FROM embedding_repair_scans WHERE store='chunks' AND slot='prose'",
            java.time.OffsetDateTime.class);
    assertTrue(next.isAfter(java.time.OffsetDateTime.now().plusMinutes(4)));
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<List<EmbeddingWorkRepository.Source>> claim =
          () -> {
            assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return new JdbcEmbeddingWorkRepository(jdbc, work)
                .claimPending(target, EmbeddingWorkRepository.Store.CHUNKS, 4);
          };
      var first = executor.submit(claim);
      var second = executor.submit(claim);
      start.countDown();
      var a = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
      var b = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(4, a.size());
      assertEquals(4, b.size());
      var keys = new HashSet<EmbeddingWorkRepository.Key>();
      a.forEach(source -> keys.add(source.key()));
      b.forEach(source -> keys.add(source.key()));
      assertEquals(8, keys.size());
    }
  }

  @Test
  void discoveryReservationsSurviveReopenAndDoNotReplayAnUncertainSubmission() {
    var target = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    var first = repository.claimPending(target, EmbeddingWorkRepository.Store.MEMORIES, 100);
    assertEquals(2, first.size());
    var reopened = new JdbcEmbeddingWorkRepository(jdbc, work);
    assertTrue(
        reopened.claimPending(target, EmbeddingWorkRepository.Store.MEMORIES, 100).isEmpty());
    // A crashed discoverer did not submit anything; only this reservation can expire.
    jdbc.update("UPDATE embedding_repair_work SET available_at=now()-INTERVAL '1 second'");
    var recovered = reopened.claimPending(target, EmbeddingWorkRepository.Store.MEMORIES, 100);
    assertEquals(new HashSet<>(first), new HashSet<>(recovered));
    var submitted = recovered.getFirst();
    assertTrue(reopened.beginRepair(target, submitted));
    jdbc.update("UPDATE embedding_repair_work SET available_at=now()-INTERVAL '1 second'");
    assertFalse(reopened.pending(target, submitted.key().store(), 100).contains(submitted));
    assertFalse(reopened.beginRepair(target, submitted));
    jdbc.update("UPDATE memories SET summary='fresh source' WHERE id=?", submitted.key().id());
    assertTrue(
        reopened.claimPending(target, submitted.key().store(), 100).stream()
            .anyMatch(
                source ->
                    source.key().equals(submitted.key())
                        && source.revision() != submitted.revision()));
  }

  private EmbeddingRepair.Attempt retainedAttempt() {
    var profile = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    var source = repository.pending(profile, EmbeddingWorkRepository.Store.MEMORIES, 4).getFirst();
    return repository.beginRepairAttempt(profile, source).orElseThrow();
  }

  @Test
  void attemptIdentityAndReplayFenceRollbackTogether() {
    var profile = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    var source = repository.pending(profile, EmbeddingWorkRepository.Store.MEMORIES, 4).getFirst();
    var identity = new java.util.concurrent.atomic.AtomicReference<UUID>();
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  identity.set(repository.beginRepairAttempt(profile, source).orElseThrow().id());
                  throw new IllegalStateException("rollback the intent");
                }));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM embedding_repair_attempts WHERE id=?",
            Integer.class,
            identity.get()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM memories_embedding_failures WHERE source_id=? AND slot=? AND space_id=?",
            Integer.class,
            source.key().id(),
            profile.slot().stored(),
            profile.space().id()));
    assertTrue(repository.beginRepairAttempt(profile, source).isPresent());
  }

  @Test
  void provenNonDispatchStoresReasonAndFiniteBackoffWithoutReplayingAnUncertainAttempt() {
    var attempt = retainedAttempt();
    var call = UUID.randomUUID();
    repository.repairFailed(
        attempt,
        new EmbeddingRepair.Progress(
            EmbeddingRepair.Phase.EXECUTION_FINISHED,
            call,
            null,
            null,
            io.aeyer.plowshare.server.llm.accounting.CallLifecycle.NOT_DISPATCHED),
        EmbeddingRepair.Reason.CAPACITY_EXHAUSTED,
        "LlmSaturatedException");
    var row =
        jdbc.queryForMap(
            "SELECT phase,reason,failure_phase,call_id,prepared_at FROM embedding_repair_attempts WHERE id=?",
            attempt.id());
    assertEquals("NOT_DISPATCHED", row.get("phase"));
    assertEquals("CAPACITY_EXHAUSTED", row.get("reason"));
    assertEquals("PREPARED", row.get("failure_phase"));
    assertEquals(call, row.get("call_id"));
    assertNotNull(row.get("prepared_at"));
    assertTrue(
        jdbc.queryForObject(
            "SELECT isfinite(retry_at) AND retry_at>now() FROM memories_embedding_failures WHERE source_id=? AND slot=? AND space_id=?",
            Boolean.class,
            attempt.source().key().id(),
            attempt.profile().slot().stored(),
            attempt.profile().space().id()));
  }

  @Test
  void unknownWireOutcomeRetainsFenceAndCorrelatesExactCallAndAttempt() {
    var attempt = retainedAttempt();
    var call = UUID.randomUUID();
    var wire = UUID.randomUUID();
    repository.repairProgress(
        attempt,
        new EmbeddingRepair.Progress(EmbeddingRepair.Phase.ACCOUNTED, call, null, null, null));
    repository.repairProgress(
        attempt,
        new EmbeddingRepair.Progress(
            EmbeddingRepair.Phase.ATTEMPT_STARTED, null, wire, null, null));
    var evidence =
        new EmbeddingRepair.Progress(
            EmbeddingRepair.Phase.EXECUTION_FINISHED,
            call,
            wire,
            null,
            io.aeyer.plowshare.server.llm.accounting.CallLifecycle.FAILED);
    repository.repairFailed(
        attempt,
        evidence,
        EmbeddingRepair.Reason.DISPATCH_FAILED,
        "java.net.SocketTimeoutException");
    var row =
        jdbc.queryForMap(
            "SELECT phase,reason,failure_phase,call_id,wire_attempt_id FROM embedding_repair_attempts WHERE id=?",
            attempt.id());
    assertEquals("OUTCOME_UNKNOWN", row.get("phase"));
    assertEquals("TRANSPORT_OUTCOME_UNKNOWN", row.get("reason"));
    assertEquals("ATTEMPT_STARTED", row.get("failure_phase"));
    assertEquals(call, row.get("call_id"));
    assertEquals(wire, row.get("wire_attempt_id"));
    assertTrue(
        new JdbcEmbeddingWorkRepository(jdbc, work)
            .beginRepairAttempt(attempt.profile(), attempt.source())
            .isEmpty());
    assertThrows(
        IllegalStateException.class,
        () ->
            repository.repairProgress(
                attempt,
                new EmbeddingRepair.Progress(
                    EmbeddingRepair.Phase.ATTEMPT_STARTED, null, UUID.randomUUID(), null, null)));
  }

  @Test
  void failedPublicationRollsBackTheVectorButCommitsItsFailureInTheFollowingTransaction() {
    var attempt = retainedAttempt();
    var wire = UUID.randomUUID();
    repository.repairProgress(
        attempt,
        new EmbeddingRepair.Progress(
            EmbeddingRepair.Phase.ATTEMPT_STARTED, null, wire, null, null));
    repository.repairProgress(
        attempt, EmbeddingRepair.Progress.phase(EmbeddingRepair.Phase.PUBLISHING));
    jdbc.execute(
        "CREATE FUNCTION fixture_reject_repair_success() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.phase='PUBLISHED' THEN RAISE EXCEPTION 'fixture receipt failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER fixture_reject_repair_success BEFORE UPDATE ON embedding_repair_attempts FOR EACH ROW EXECUTE FUNCTION fixture_reject_repair_success()");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> repository.publishRepair(attempt, vector(attempt.profile()), () -> {}));
    } finally {
      jdbc.execute("DROP TRIGGER fixture_reject_repair_success ON embedding_repair_attempts");
      jdbc.execute("DROP FUNCTION fixture_reject_repair_success()");
    }
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM memories_embedding_staging WHERE source_id=? AND slot=? AND space_id=?",
            Integer.class,
            attempt.source().key().id(),
            attempt.profile().slot().stored(),
            attempt.profile().space().id()));
    repository.repairFailed(
        attempt,
        new EmbeddingRepair.Progress(
            EmbeddingRepair.Phase.EXECUTION_FINISHED,
            null,
            wire,
            200,
            io.aeyer.plowshare.server.llm.accounting.CallLifecycle.SUCCEEDED),
        EmbeddingRepair.Reason.PUBLICATION_FAILED,
        "DataAccessException");
    var row =
        jdbc.queryForMap(
            "SELECT phase,reason,failure_phase,http_status FROM embedding_repair_attempts WHERE id=?",
            attempt.id());
    assertEquals("FAILED", row.get("phase"));
    assertEquals("PUBLICATION_FAILED", row.get("reason"));
    assertEquals("PUBLISHING", row.get("failure_phase"));
    assertEquals(200, row.get("http_status"));
    assertTrue(repository.beginRepairAttempt(attempt.profile(), attempt.source()).isEmpty());
  }

  @Test
  void publishedVectorAndSuccessReceiptCommitTogetherAndCannotBeOverwrittenByLateFailure() {
    var attempt = retainedAttempt();
    repository.repairProgress(
        attempt, EmbeddingRepair.Progress.phase(EmbeddingRepair.Phase.PUBLISHING));
    assertTrue(repository.publishRepair(attempt, vector(attempt.profile()), () -> {}));
    repository.repairFailed(
        attempt,
        EmbeddingRepair.Progress.phase(EmbeddingRepair.Phase.EXECUTION_FINISHED),
        EmbeddingRepair.Reason.DISPATCH_FAILED,
        "IllegalStateException");
    assertEquals(
        "PUBLISHED",
        jdbc.queryForObject(
            "SELECT phase FROM embedding_repair_attempts WHERE id=?", String.class, attempt.id()));
    assertTrue(repository.ready(attempt.profile(), attempt.source()));
    assertNotNull(
        jdbc.queryForObject(
            "SELECT result_received_at FROM embedding_repair_attempts WHERE id=?",
            java.time.OffsetDateTime.class,
            attempt.id()));
  }

  @Test
  void publicationRequiresItsExactAttemptAndThePublicationCheckpoint() {
    var attempt = retainedAttempt();
    assertThrows(
        IllegalStateException.class,
        () -> repository.publishRepair(attempt, vector(attempt.profile()), () -> {}));
    var other =
        repository
            .source(
                EmbeddingWorkRepository.Key.of(
                    EmbeddingWorkRepository.Store.MEMORIES,
                    attempt.source().key().id().equals("near") ? "memory" : "near"))
            .orElseThrow();
    var forged = new EmbeddingRepair.Attempt(attempt.id(), attempt.profile(), other);
    assertThrows(
        org.springframework.dao.EmptyResultDataAccessException.class,
        () ->
            repository.repairProgress(
                forged, EmbeddingRepair.Progress.phase(EmbeddingRepair.Phase.PUBLISHING)));
    assertFalse(repository.ready(attempt.profile(), attempt.source()));
  }

  @Test
  void failedReasonWriteCannotCommitAFiniteRetryWithoutItsOutcome() {
    var attempt = retainedAttempt();
    var evidence = EmbeddingRepair.Progress.phase(EmbeddingRepair.Phase.EXECUTION_FINISHED);
    jdbc.execute(
        "CREATE FUNCTION fixture_reject_repair_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.phase='NOT_DISPATCHED' THEN RAISE EXCEPTION 'fixture failure checkpoint'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER fixture_reject_repair_failure BEFORE UPDATE ON embedding_repair_attempts FOR EACH ROW EXECUTE FUNCTION fixture_reject_repair_failure()");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              repository.repairFailed(
                  attempt, evidence, EmbeddingRepair.Reason.CAPACITY_EXHAUSTED, "Fixture"));
    } finally {
      jdbc.execute("DROP TRIGGER fixture_reject_repair_failure ON embedding_repair_attempts");
      jdbc.execute("DROP FUNCTION fixture_reject_repair_failure()");
    }
    assertEquals(
        "PREPARED",
        jdbc.queryForObject(
            "SELECT phase FROM embedding_repair_attempts WHERE id=?", String.class, attempt.id()));
    assertTrue(
        jdbc.queryForObject(
            "SELECT retry_at='infinity'::timestamptz FROM memories_embedding_failures WHERE source_id=? AND slot=? AND space_id=?",
            Boolean.class,
            attempt.source().key().id(),
            attempt.profile().slot().stored(),
            attempt.profile().space().id()));
    repository.repairFailed(
        attempt, evidence, EmbeddingRepair.Reason.CAPACITY_EXHAUSTED, "A".repeat(256));
    assertEquals(
        256,
        jdbc.queryForObject(
            "SELECT length(failure_type) FROM embedding_repair_attempts WHERE id=?",
            Integer.class,
            attempt.id()));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE embedding_repair_attempts SET failure_type=? WHERE id=?",
                "A".repeat(257),
                attempt.id()));
  }

  @Test
  void publicationRevocationRetainsItsAuthorizationReasonAndNeverPublishes() {
    var attempt = retainedAttempt();
    repository.repairProgress(
        attempt,
        new EmbeddingRepair.Progress(
            EmbeddingRepair.Phase.ATTEMPT_STARTED,
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            null));
    repository.repairProgress(
        attempt, EmbeddingRepair.Progress.phase(EmbeddingRepair.Phase.PUBLISHING));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () ->
            repository.publishRepair(
                attempt,
                vector(attempt.profile()),
                () -> {
                  throw new io.aeyer.plowshare.server.faults.CallerFault(
                      "fixture authority revoked");
                }));
    repository.repairFailed(
        attempt,
        EmbeddingRepair.Progress.phase(EmbeddingRepair.Phase.EXECUTION_FINISHED),
        EmbeddingRepair.Reason.AUTHORIZATION_REJECTED,
        "CallerFault");
    var row =
        jdbc.queryForMap(
            "SELECT phase,reason,failure_phase FROM embedding_repair_attempts WHERE id=?",
            attempt.id());
    assertEquals("BLOCKED", row.get("phase"));
    assertEquals("AUTHORIZATION_REJECTED", row.get("reason"));
    assertEquals("PUBLISHING", row.get("failure_phase"));
    assertFalse(repository.ready(attempt.profile(), attempt.source()));
    assertTrue(repository.beginRepairAttempt(attempt.profile(), attempt.source()).isEmpty());
  }

  @Test
  void failureRecordingRejectsAnActiveOwnerTransactionAndMalformedEvidence() {
    var attempt = retainedAttempt();
    var evidence = EmbeddingRepair.Progress.phase(EmbeddingRepair.Phase.EXECUTION_FINISHED);
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  repository.repairFailed(
                      attempt, evidence, EmbeddingRepair.Reason.DISPATCH_FAILED, "Fixture");
                  return true;
                }));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.repairFailed(
                attempt,
                evidence,
                EmbeddingRepair.Reason.DISPATCH_FAILED,
                "raw error text is not a category"));
    assertEquals(
        "PREPARED",
        jdbc.queryForObject(
            "SELECT phase FROM embedding_repair_attempts WHERE id=?", String.class, attempt.id()));
  }

  @Test
  void legacyMigrationPreservesUnknownReasonWithoutInventingSubmissionEvidence() {
    jdbc.execute("CREATE DATABASE repair_legacy");
    try {
      var ds =
          new DriverManagerDataSource(
              POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/repair_legacy"),
              POSTGRES.getUsername(),
              POSTGRES.getPassword());
      Flyway.configure().dataSource(ds).target("208").load().migrate();
      var old = new JdbcTemplate(ds);
      var template = new TransactionTemplate(new DataSourceTransactionManager(ds));
      UnitOfWork transaction =
          new UnitOfWork() {
            public <T> T inTransaction(Supplier<T> callback) {
              return template.execute(status -> callback.get());
            }
          };
      var legacy = new JdbcEmbeddingWorkRepository(old, transaction);
      var space =
          new JdbcEmbeddingSpaceRepository(old)
              .register(
                  new EmbeddingSpace.Definition(
                      "fixture-legacy",
                      "weights",
                      4,
                      "",
                      "",
                      "cls",
                      EmbeddingSpace.Normalization.NONE,
                      "none"));
      var profile =
          legacy.configure(
              EmbeddingSlot.PROSE,
              space,
              new EmbeddingSearchPolicy(
                  EmbeddingSearchPolicy.Mode.EXACT, EmbeddingSearchPolicy.Distance.COSINE));
      old.update(
          "INSERT INTO memories(id,summary,scope,body,state,formed_at,formed_by,formed_where) VALUES('legacy','fixture','scope','body','active',now(),'fixture','fixture')");
      var source =
          legacy
              .source(
                  EmbeddingWorkRepository.Key.of(EmbeddingWorkRepository.Store.MEMORIES, "legacy"))
              .orElseThrow();
      assertTrue(legacy.beginRepair(profile, source));
      Flyway.configure().dataSource(ds).load().migrate();
      var row =
          old.queryForMap(
              "SELECT phase,reason,legacy_imported,call_id,wire_attempt_id,prepared_at,provider_started_at FROM embedding_repair_attempts WHERE source_id='legacy'");
      assertEquals("OUTCOME_UNKNOWN", row.get("phase"));
      assertEquals("LEGACY_UNCORRELATED", row.get("reason"));
      assertEquals(true, row.get("legacy_imported"));
      for (String field :
          List.of("call_id", "wire_attempt_id", "prepared_at", "provider_started_at"))
        assertNull(row.get(field), field);
      assertTrue(
          old.queryForObject(
              "SELECT retry_at='infinity'::timestamptz AND category='RepairInFlight' FROM memories_embedding_failures WHERE source_id='legacy'",
              Boolean.class));
      assertTrue(legacy.beginRepairAttempt(profile, source).isEmpty());
    } finally {
      jdbc.execute("DROP DATABASE repair_legacy WITH (FORCE)");
    }
  }

  @Test
  void dueSnapshotUsesOneExecuteAndSelectsOnlyItsStoreWithoutActivationChurn() {
    var p = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    populate(p);
    assertTrue(repository.activate(p));
    for (var store : EmbeddingWorkRepository.Store.values())
      repository.pending(repository.target(EmbeddingSlot.PROSE).orElseThrow(), store, 4);
    var trace = mock(io.aeyer.plowshare.server.pool.JdbcExecutionTrace.class);
    when(trace.begin(anyString(), anyString()))
        .thenReturn(mock(io.aeyer.plowshare.server.pool.JdbcExecutionTrace.Span.class));
    var traced = new io.aeyer.plowshare.server.pool.TracedDataSource(jdbc.getDataSource(), trace);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(traced));
    var measured =
        new JdbcEmbeddingWorkRepository(
            new JdbcTemplate(traced),
            new UnitOfWork() {
              public <T> T inTransaction(Supplier<T> callback) {
                return transaction.execute(status -> callback.get());
              }
            });
    var idle = measured.maintenancePlan(false);
    assertTrue(idle.repairs().isEmpty());
    assertTrue(idle.activations().isEmpty());
    verify(trace, times(1)).begin(startsWith("WITH wake AS MATERIALIZED"), eq("executeQuery"));
    verifyNoMoreInteractions(trace);
    assertEquals(
        List.of(repository.target(EmbeddingSlot.PROSE).orElseThrow()),
        measured.maintenancePlan(true).activations());

    jdbc.update(
        "UPDATE embedding_repair_scans SET next_at=now() WHERE slot='prose' AND store='chunks'");
    var selected = measured.maintenancePlan(false);
    assertEquals(
        List.of(
            new EmbeddingWorkRepository.RepairWork(
                repository.target(EmbeddingSlot.PROSE).orElseThrow(),
                EmbeddingWorkRepository.Store.CHUNKS)),
        selected.repairs());
    assertTrue(selected.activations().isEmpty(), "an already-active slot has no activation work");
    clearInvocations(trace);
    assertTrue(
        measured
            .claimPending(
                selected.repairs().getFirst().profile(), EmbeddingWorkRepository.Store.CHUNKS, 4)
            .isEmpty());
    verify(trace, times(8)).begin(anyString(), anyString());
    verify(trace, times(1)).begin(startsWith("WITH page AS MATERIALIZED"), eq("executeQuery"));
    // One plan execute plus eight for the actual due recovery store, instead of the old
    // 105-execute all-store pass before any recovery page. No index/other-store probes.

    jdbc.update("UPDATE chunks SET text='changed after snapshot' WHERE id=?", chunk);
    assertTrue(
        repository
            .claimPending(
                selected.repairs().getFirst().profile(), EmbeddingWorkRepository.Store.CHUNKS, 4)
            .stream()
            .anyMatch(x -> x.key().id().equals(chunk.toString())));
    var stale = selected.repairs().getFirst().profile();
    target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    assertTrue(
        repository.claimPending(stale, EmbeddingWorkRepository.Store.CHUNKS, 4).isEmpty(),
        "a scheduling snapshot cannot claim after its slot configuration changes");
  }

  @Test
  void duePlanPreservesServingGenerationAndStorePriorityDuringRebuild() {
    var old = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    populate(old);
    assertTrue(repository.activate(old));
    var replacement = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    jdbc.update("UPDATE embedding_repair_scans SET next_at=now() WHERE slot='prose'");
    var plan = repository.maintenancePlan(false);
    assertEquals(10, plan.repairs().size());
    for (int i = 0; i < 10; i++) {
      assertEquals(
          i < 5 ? old.space() : replacement.space(), plan.repairs().get(i).profile().space());
      assertEquals(EmbeddingWorkRepository.Store.values()[i % 5], plan.repairs().get(i).store());
    }
    assertTrue(plan.activations().isEmpty(), "rebuild coverage is not established yet");
  }

  @Test
  void retainedInfiniteAttemptIsNotScheduledOrReplayedAfterReopen() {
    var p = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    populate(p);
    assertTrue(repository.activate(p));
    p = repository.target(EmbeddingSlot.PROSE).orElseThrow();
    for (var store : EmbeddingWorkRepository.Store.values()) repository.pending(p, store, 4);
    jdbc.update("UPDATE chunks SET text='new retained input' WHERE id=?", chunk);
    var source =
        repository.claimPending(p, EmbeddingWorkRepository.Store.CHUNKS, 4).stream()
            .filter(x -> x.key().id().equals(chunk.toString()))
            .findFirst()
            .orElseThrow();
    assertTrue(repository.beginRepair(p, source));
    var reopened = new JdbcEmbeddingWorkRepository(jdbc, work);
    var idle = reopened.maintenancePlan(false);
    assertTrue(idle.repairs().isEmpty());
    assertTrue(idle.activations().isEmpty());
    assertFalse(reopened.beginRepair(p, source));
    jdbc.update("UPDATE chunks SET text='later committed revision' WHERE id=?", chunk);
    assertEquals(1, reopened.maintenancePlan(false).repairs().size());
    assertTrue(
        reopened.claimPending(p, EmbeddingWorkRepository.Store.CHUNKS, 4).stream()
            .anyMatch(x -> x.revision() != source.revision()));
  }

  @Test
  void idleDiscoveryDoesNotScanTheCorpusAndRestoresJitAndTimeoutInsideTheCallerTransaction() {
    var target = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    populate(target);
    assertTrue(repository.activate(target));
    for (var store : EmbeddingWorkRepository.Store.values())
      assertTrue(
          repository
              .pending(repository.target(EmbeddingSlot.PROSE).orElseThrow(), store, 4)
              .isEmpty());
    assertFalse(repository.discoveryDue());
    assertTrue(
        repository.activationCandidate(repository.target(EmbeddingSlot.PROSE).orElseThrow()));
    for (String mode : List.of("force_generic_plan", "force_custom_plan")) {
      work.inTransaction(
          () -> {
            jdbc.execute("SET LOCAL jit=on");
            jdbc.execute("SET LOCAL jit_above_cost=0");
            jdbc.execute("SET LOCAL plan_cache_mode=" + mode);
            jdbc.execute("SET LOCAL statement_timeout='7s'");
            var scan =
                jdbc.queryForMap(
                    "SELECT * FROM embedding_repair_scans WHERE slot=? AND space_id=? AND store='chunks'",
                    target.slot().stored(),
                    target.space().id());
            for (int attempt = 0; attempt < 7; attempt++)
              assertTrue(
                  repository
                      .claimPending(target, EmbeddingWorkRepository.Store.CHUNKS, 4)
                      .isEmpty());
            assertEquals("on", jdbc.queryForObject("SHOW jit", String.class));
            assertEquals("7s", jdbc.queryForObject("SHOW statement_timeout", String.class));
            assertEquals(
                scan,
                jdbc.queryForMap(
                    "SELECT * FROM embedding_repair_scans WHERE slot=? AND space_id=? AND store='chunks'",
                    target.slot().stored(),
                    target.space().id()));
            return null;
          });
    }
  }

  @Test
  void pendingDiscoveryAndSourceEnqueueRollbackTogether() {
    var target = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    var source = repository.pending(target, EmbeddingWorkRepository.Store.MEMORIES, 100).getFirst();
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  jdbc.update(
                      "UPDATE memories SET summary='rolled back' WHERE id=?", source.key().id());
                  assertNotEquals(
                      source.revision(), repository.source(source.key()).orElseThrow().revision());
                  throw new IllegalStateException("rollback");
                }));
    assertEquals(source, repository.source(source.key()).orElseThrow());
    assertTrue(repository.pending(target, source.key().store(), 100).contains(source));
  }

  @Test
  void automaticRepairRetainsUnknownSubmissionWithoutReplayAcrossReopen() {
    var target = target(EmbeddingSlot.PROSE, EmbeddingSearchPolicy.Mode.EXACT, 4);
    var key = EmbeddingWorkRepository.Key.of(EmbeddingWorkRepository.Store.MEMORIES, "memory");
    var source = repository.source(key).orElseThrow();
    assertEquals(
        List.of(
            new io.aeyer.plowshare.server.access.AccessRequest.Resource(
                io.aeyer.plowshare.server.access.ResourceScopeRepository.Kind.MEMORY, "memory")),
        new DigestStore(jdbc, work).readTargets(Home.global(), "dig_m_memory"));
    assertTrue(repository.beginRepair(target, source));
    var reopened = new JdbcEmbeddingWorkRepository(jdbc, work);
    assertFalse(reopened.beginRepair(target, source));
    assertFalse(reopened.pending(target, key.store(), 100).contains(source));
    jdbc.update("UPDATE memories SET summary='new content' WHERE id='memory'");
    var changed = reopened.source(key).orElseThrow();
    assertTrue(reopened.beginRepair(target, changed));
    assertTrue(reopened.publish(target, changed, vector(target)));
    assertTrue(reopened.ready(target, changed));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM memories_embedding_failures WHERE source_id='memory'",
            Integer.class));
  }

  @Test
  void legacyPassageRepairRetainsExactQueuePositionAndEncoderWithoutReplay() {
    var passages = new JdbcPassageRepository(jdbc, work);
    var source = passages.pending("next").getFirst();
    assertTrue(passages.beginRepair(source, "next"));
    var reopened = new JdbcPassageRepository(jdbc, work);
    assertFalse(reopened.beginRepair(source, "next"));
    assertFalse(reopened.pending("next").contains(source));
    assertTrue(reopened.pending("replacement").stream().anyMatch(s -> s.id().equals(source.id())));
    assertTrue(reopened.beginRepair(source, "replacement"));
  }

  @Test
  void globalNavigationFiltersConversationTreesBeforeCoverageAndRanking() {
    var passages = new JdbcPassageRepository(jdbc, work);
    var scope = new PassageRepository.ReadScope(true, null, true);
    var before = passages.coverage(Home.global(), "digest", "fixture", scope);
    jdbc.update(
        "INSERT INTO conversations(id,created_at,budget_total,budget_spent,origin) VALUES('historical',now(),2,0,'turn')");
    jdbc.update(
        "INSERT INTO digests(id,depth,summary) VALUES('historical-digest',0,'private historical text')");
    jdbc.update(
        "INSERT INTO digest_spans(digest_id,conversation_id,since_turn,through_turn) VALUES('historical-digest','historical',0,1)");
    jdbc.update(
        "UPDATE retrieval_sources SET status='ready',generation='fixture' WHERE source_id='historical-digest'");
    var vector = new float[768];
    vector[0] = 1;
    jdbc.update(
        "INSERT INTO retrieval_passages(source_type,source_id,position,passage,embedding) VALUES('digest','historical-digest',0,'private historical text',CAST(? AS vector))",
        Arrays.toString(vector));
    assertEquals(before, passages.coverage(Home.global(), "digest", "fixture", scope));
    assertTrue(passages.rank(Home.global(), "digest", vector, 32, "fixture", scope).isEmpty());
    assertEquals(
        before.eligible() + 1,
        passages
            .coverage(Home.global(), "digest", "fixture", PassageRepository.ReadScope.UNRESTRICTED)
            .eligible());
  }
}
