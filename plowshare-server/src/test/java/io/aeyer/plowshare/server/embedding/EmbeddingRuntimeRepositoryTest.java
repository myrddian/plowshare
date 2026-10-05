package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
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
  }

  @BeforeEach
  void fixtures() {
    jdbc.execute("TRUNCATE memories,documents,conversations,digests,retrieval_sources CASCADE");
    jdbc.update(
        "UPDATE embedding_slots SET active_space_id=NULL,target_space_id=NULL,active_mode=NULL,target_mode=NULL,active_distance=NULL,target_distance=NULL");
    repository = new JdbcEmbeddingWorkRepository(jdbc, work, spaces);
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
    var access = new InformationAccess(mock(ProjectMembers.class));
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
    var restarted = new JdbcEmbeddingWorkRepository(jdbc, work, spaces);
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
    jdbc.execute("TRUNCATE memories,documents,conversations,digests,retrieval_sources CASCADE");
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
}
