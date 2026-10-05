package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual PostgreSQL constraints, migration compatibility and publication locking. No model calls.
 */
@Tag("full-db")
@Testcontainers
class EmbeddingSchemaTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg16-bookworm");

  static DriverManagerDataSource dataSource;
  static JdbcTemplate jdbc;
  static EmbeddingSpaceRepository spaces;
  static List<Source> legacy;
  static final String LEGACY_VECTOR = vector(768);

  @BeforeAll
  static void upgrade() {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    Flyway.configure().dataSource(dataSource).target("114").load().migrate();
    // Seed all stores before the new migration, as a deployed database would contain them.
    legacy = fixtures();
    var flyway = Flyway.configure().dataSource(dataSource).load();
    flyway.migrate();
    flyway.validate();
    assertEquals(0, flyway.migrate().migrationsExecuted);
    spaces = new JdbcEmbeddingSpaceRepository(jdbc);
  }

  @ParameterizedTest
  @EnumSource(EmbeddingSearchPolicy.Mode.class)
  void fixedSearchModesUseTheSameStorageAtTheirMaximumWidth(EmbeddingSearchPolicy.Mode mode) {
    int width = mode.maximumDimensions();
    var policy = new EmbeddingSearchPolicy(mode, EmbeddingSearchPolicy.Distance.COSINE);
    policy.validate(definition("search-mode-fixture", width, EmbeddingSpace.Normalization.L2));
    // Actual pgvector casts/index builds require PostgreSQL. A session-local fixture
    // keeps this capability check independent of production index administration.
    new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        .execute(
            status -> {
              jdbc.execute(
                  "CREATE TEMP TABLE embedding_search_fixture(id integer PRIMARY KEY, embedding vector NOT NULL) ON COMMIT DROP");
              jdbc.update(
                  "INSERT INTO embedding_search_fixture VALUES (1, ?::vector), (2, ?::vector)",
                  vector(width),
                  "[0,1" + ",0".repeat(width - 2) + "]");
              String expression;
              String operator;
              String operatorClass;
              switch (mode) {
                case EXACT -> {
                  expression = "embedding";
                  operator = "<=>";
                  operatorClass = null;
                }
                case HNSW_VECTOR, IVFFLAT_VECTOR -> {
                  expression = "embedding::vector(" + width + ")";
                  operator = "<=>";
                  operatorClass = "vector_cosine_ops";
                }
                case HNSW_HALF, IVFFLAT_HALF -> {
                  expression = "embedding::halfvec(" + width + ")";
                  operator = "<=>";
                  operatorClass = "halfvec_cosine_ops";
                }
                case HNSW_BINARY, IVFFLAT_BINARY -> {
                  expression = "binary_quantize(embedding)::bit(" + width + ")";
                  operator = "<~>";
                  operatorClass = "bit_hamming_ops";
                }
                default -> throw new IllegalStateException("unhandled fixed search mode");
              }
              if (mode != EmbeddingSearchPolicy.Mode.EXACT) {
                boolean ivfflat =
                    mode == EmbeddingSearchPolicy.Mode.IVFFLAT_VECTOR
                        || mode == EmbeddingSearchPolicy.Mode.IVFFLAT_HALF
                        || mode == EmbeddingSearchPolicy.Mode.IVFFLAT_BINARY;
                jdbc.execute(
                    "CREATE INDEX embedding_search_fixture_index ON embedding_search_fixture USING "
                        + (ivfflat ? "ivfflat" : "hnsw")
                        + " (("
                        + expression
                        + ") "
                        + operatorClass
                        + ")"
                        + (ivfflat ? " WITH (lists=1)" : ""));
                jdbc.execute("SET LOCAL enable_seqscan=off");
                jdbc.execute("SET LOCAL jit=off");
              }
              String ranking =
                  "SELECT id FROM embedding_search_fixture ORDER BY "
                      + expression
                      + " "
                      + operator
                      + " "
                      + expression.replace("embedding", "?::vector")
                      + " LIMIT 2";
              assertEquals(List.of(1, 2), jdbc.queryForList(ranking, Integer.class, vector(width)));
              if (mode != EmbeddingSearchPolicy.Mode.EXACT) {
                String plan =
                    String.join(
                        "\n", jdbc.queryForList("EXPLAIN " + ranking, String.class, vector(width)));
                assertTrue(plan.contains("embedding_search_fixture_index"), plan);
              }
              assertEquals(
                  width,
                  jdbc.queryForObject(
                      "SELECT vector_dims(embedding) FROM embedding_search_fixture WHERE id=1",
                      Integer.class));
              // Candidate quantization never changes the retained original or its final score.
              assertEquals(
                  0.0,
                  jdbc.queryForObject(
                      "SELECT embedding <=> ?::vector FROM embedding_search_fixture WHERE id=1",
                      Double.class,
                      vector(width)));
              return null;
            });
  }

  @Test
  void fixedDistanceMenuMatchesInstalledIndexOperatorClasses() {
    for (var mode : EmbeddingSearchPolicy.Mode.values()) {
      if (mode == EmbeddingSearchPolicy.Mode.EXACT) continue;
      String method =
          switch (mode) {
            case HNSW_VECTOR, HNSW_HALF, HNSW_BINARY -> "hnsw";
            case IVFFLAT_VECTOR, IVFFLAT_HALF, IVFFLAT_BINARY -> "ivfflat";
            default -> throw new IllegalStateException("unhandled indexed mode");
          };
      String representation =
          switch (mode) {
            case HNSW_VECTOR, IVFFLAT_VECTOR -> "vector";
            case HNSW_HALF, IVFFLAT_HALF -> "halfvec";
            case HNSW_BINARY, IVFFLAT_BINARY -> "bit";
            default -> throw new IllegalStateException("unhandled indexed mode");
          };
      for (var distance : EmbeddingSearchPolicy.Distance.values()) {
        String metric =
            representation.equals("bit")
                ? "hamming"
                : switch (distance) {
                  case COSINE -> "cosine";
                  case L2 -> "l2";
                  case INNER_PRODUCT -> "ip";
                  case L1 -> "l1";
                };
        int available =
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_opclass c JOIN pg_am a ON a.oid=c.opcmethod WHERE a.amname=? AND c.opcname=?",
                Integer.class,
                method,
                representation + "_" + metric + "_ops");
        if (available == 0) {
          assertThrows(
              IllegalArgumentException.class, () -> new EmbeddingSearchPolicy(mode, distance));
        } else {
          assertEquals(1, available);
          assertDoesNotThrow(() -> new EmbeddingSearchPolicy(mode, distance));
        }
      }
    }
  }

  @Test
  void legacyVectorsSurviveWithoutInventedModelAttribution() {
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM embedding_slots", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM embedding_slots WHERE active_space_id IS NOT NULL",
            Integer.class));
    for (Source source : legacy) {
      assertEquals(LEGACY_VECTOR, source.text(source.kind.legacyColumn + "::text"));
      assertNull(source.text("code_space_id"));
      assertNull(source.text("prose_space_id"));
      assertEquals(0L, source.revision());
      // -1 is PostgreSQL's unconstrained vector typmod; it is not vector(768).
      assertEquals(
          -1,
          jdbc.queryForObject(
              "SELECT atttypmod FROM pg_attribute WHERE attrelid=?::regclass AND attname='code_embedding'",
              Integer.class,
              source.kind.table));
      assertEquals(
          -1,
          jdbc.queryForObject(
              "SELECT atttypmod FROM pg_attribute WHERE attrelid=?::regclass AND attname='prose_embedding'",
              Integer.class,
              source.kind.table));
    }
  }

  @Test
  void immutableSpacesIdentifyEveryPreprocessingChoiceAndRegisterIdempotently() {
    var definition = definition("identity-test", 3, EmbeddingSpace.Normalization.NONE);
    var registered = spaces.register(definition);
    assertEquals(registered, spaces.register(definition));
    assertEquals(registered, spaces.find(registered.id()).orElseThrow());
    assertTrue(spaces.find("0".repeat(64)).isEmpty());
    var changes =
        List.of(
            new EmbeddingSpace.Definition(
                "other-model",
                "revision-1",
                3,
                "query: ",
                "document: ",
                "mean",
                EmbeddingSpace.Normalization.NONE,
                "none"),
            new EmbeddingSpace.Definition(
                "identity-test",
                "revision-2",
                3,
                "query: ",
                "document: ",
                "mean",
                EmbeddingSpace.Normalization.NONE,
                "none"),
            new EmbeddingSpace.Definition(
                "identity-test",
                "revision-1",
                4,
                "query: ",
                "document: ",
                "mean",
                EmbeddingSpace.Normalization.NONE,
                "none"),
            new EmbeddingSpace.Definition(
                "identity-test",
                "revision-1",
                3,
                "query:",
                "document: ",
                "mean",
                EmbeddingSpace.Normalization.NONE,
                "none"),
            new EmbeddingSpace.Definition(
                "identity-test",
                "revision-1",
                3,
                "query: ",
                "document:",
                "mean",
                EmbeddingSpace.Normalization.NONE,
                "none"),
            new EmbeddingSpace.Definition(
                "identity-test",
                "revision-1",
                3,
                "query: ",
                "document: ",
                "cls",
                EmbeddingSpace.Normalization.NONE,
                "none"),
            new EmbeddingSpace.Definition(
                "identity-test",
                "revision-1",
                3,
                "query: ",
                "document: ",
                "mean",
                EmbeddingSpace.Normalization.L2,
                "none"),
            new EmbeddingSpace.Definition(
                "identity-test",
                "revision-1",
                3,
                "query: ",
                "document: ",
                "mean",
                EmbeddingSpace.Normalization.NONE,
                "mrl-3"));
    for (var changed : changes) assertNotEquals(registered.id(), spaces.register(changed).id());
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE embedding_spaces SET model_id='changed' WHERE id=?", registered.id()));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM embedding_spaces WHERE id=?", registered.id()));
    assertThrows(
        DataAccessException.class, () -> jdbc.execute("TRUNCATE embedding_spaces CASCADE"));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO embedding_spaces(id, model_id, model_revision, dimensions, query_prefix, document_prefix, pooling, normalization, reduction) VALUES (?, 'bogus-id', 'r1', 3, '', '', 'mean', 'none', 'none')",
                "0".repeat(64)));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.execute(
                "INSERT INTO embedding_spaces(model_id, model_revision, dimensions, query_prefix, document_prefix, pooling, normalization, reduction) VALUES ('too-wide', 'r1', 16001, '', '', 'mean', 'none', 'none')"));
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  void bothSlotsUseIndependentWidthsAndSourceChangesInvalidateBoth(Kind kind) {
    Source source = fixtures().stream().filter(s -> s.kind == kind).findFirst().orElseThrow();
    long before = source.revision();
    var code = register("code-fixture", 3);
    var prose = register("prose-fixture", 4);
    source.publish("code", code.id(), vector(3), before);
    source.publish("prose", prose.id(), vector(4), before);
    source.publish("code", code.id(), "[0,1,0]", before);
    assertEquals(prose.id(), source.text("prose_space_id"));
    assertEquals(vector(4), source.text("prose_embedding::text"));
    source.change();
    assertTrue(source.revision() > before);
    for (String slot : List.of("code", "prose")) {
      assertNull(source.text(slot + "_embedding::text"));
      assertNull(source.text(slot + "_space_id"));
      assertNull(source.text(slot + "_source_revision::text"));
    }
    assertThrows(
        DataAccessException.class, () -> source.publish("code", code.id(), vector(3), before));
    source.publish("code", code.id(), vector(3), source.revision());
    assertTrue(source.revision() > before);
    assertEquals(LEGACY_VECTOR, source.text(kind.legacyColumn + "::text"));
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  void allStoresAcceptFullPgvectorWidthAndRejectInvalidPairs(Kind kind) {
    Source source = fixtures(null).stream().filter(s -> s.kind == kind).findFirst().orElseThrow();
    long before = source.revision();
    assertNull(source.text(kind.legacyColumn + "::text"));
    var maximum = register("maximum-width", 16000);
    source.publish("code", maximum.id(), vector(16000), before);
    source.publish("prose", maximum.id(), vector(16000), before);
    assertEquals("16000", source.text("vector_dims(code_embedding)::text"));
    assertEquals("16000", source.text("vector_dims(prose_embedding)::text"));
    for (String slot : List.of("code", "prose")) {
      target(slot, maximum.id());
      source.stage(slot, maximum.id(), vector(16000), before);
    }
    assertEquals(2, source.staged());
    assertThrows(
        DataAccessException.class,
        () -> source.publish("code", maximum.id(), vector(15999), before));
    assertThrows(
        DataAccessException.class,
        () -> source.publish("code", maximum.id(), vector(16001), before));
    assertThrows(
        DataAccessException.class, () -> source.publish("code", null, vector(16000), before));
    assertThrows(
        DataAccessException.class, () -> source.publish("code", maximum.id(), null, before));
    assertThrows(
        DataAccessException.class,
        () -> source.publish("code", "0".repeat(64), vector(16000), before));
    var small = register("small-unscaled", 3);
    assertThrows(
        DataAccessException.class, () -> source.publish("code", small.id(), "[0,0,0]", before));
    assertThrows(
        DataAccessException.class, () -> source.publish("code", small.id(), "[NaN,0,0]", before));
    var normalized = spaces.register(definition("small-unit", 3, EmbeddingSpace.Normalization.L2));
    assertThrows(
        DataAccessException.class,
        () -> source.publish("code", normalized.id(), "[2,0,0]", before));
    source.publish("code", normalized.id(), "[0.6,0.8,0]", before);
    assertEquals(maximum.id(), source.text("prose_space_id"));
    assertThrows(DataAccessException.class, () -> source.update("embedding_source_revision=5"));
    assertThrows(DataAccessException.class, () -> source.update("code_source_revision=NULL"));
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  void stagingFencesTargetsAndSourceVersionsAndCascadesOnDeletion(Kind kind) {
    Source source = fixtures().stream().filter(s -> s.kind == kind).findFirst().orElseThrow();
    long before = source.revision();
    var old = register("serving-fixture", 3);
    var replacement = register("replacement-fixture", 4);
    source.publish("code", old.id(), vector(3), before);
    target("code", replacement.id());
    assertThrows(
        DataAccessException.class, () -> source.stage("code", old.id(), vector(3), before));
    source.stage("code", replacement.id(), vector(4), before);
    assertEquals(old.id(), source.text("code_space_id"));
    source.change();
    assertThrows(
        DataAccessException.class, () -> source.stage("code", replacement.id(), vector(4), before));
    // Old and new source revisions coexist for diagnosis; neither is automatically activated.
    source.stage("code", replacement.id(), vector(4), source.revision());
    assertEquals(2, source.staged());
    assertNull(source.text("code_space_id"));
    long rebuiltRevision = source.revision();
    target("code", old.id());
    assertThrows(
        DataAccessException.class,
        () -> source.stage("code", replacement.id(), vector(4), source.revision()));
    source.delete();
    assertEquals(0, source.staged());
    assertThrows(
        DataAccessException.class,
        () -> source.stage("code", old.id(), vector(3), rebuiltRevision));
  }

  @Test
  void slotIdentitiesAndConfigurationVersionsAreFenced() {
    var space = register("version-fixture", 3);
    long before =
        jdbc.queryForObject("SELECT version FROM embedding_slots WHERE slot='prose'", Long.class);
    assertEquals(
        1,
        jdbc.update(
            "UPDATE embedding_slots SET target_space_id=? WHERE slot='prose' AND version=?",
            space.id(),
            before));
    assertEquals(
        0,
        jdbc.update(
            "UPDATE embedding_slots SET target_space_id=? WHERE slot='prose' AND version=?",
            space.id(),
            before));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.execute("DELETE FROM embedding_slots WHERE slot='prose'"));
    assertThrows(DataAccessException.class, () -> jdbc.execute("TRUNCATE embedding_slots CASCADE"));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.execute("UPDATE embedding_slots SET slot='prose' WHERE slot='code'"));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.execute("INSERT INTO embedding_slots(slot) VALUES ('other')"));
  }

  @Test
  void summaryRemovalAndUnrelatedMetadataRespectCoverage() {
    var source = fixtures().stream().filter(s -> s.kind == Kind.DOCUMENT).findFirst().orElseThrow();
    long before = source.revision();
    var space = register("summary-fixture", 3);
    source.publish("code", space.id(), vector(3), before);
    source.publish("prose", space.id(), vector(3), before);
    source.update("title='renamed'");
    assertEquals(before, source.revision());
    assertEquals(space.id(), source.text("code_space_id"));
    source.update("summary=NULL");
    assertTrue(source.revision() > before);
    assertThrows(
        DataAccessException.class,
        () -> source.publish("code", space.id(), vector(3), source.revision()));
    target("code", space.id());
    assertThrows(
        DataAccessException.class,
        () -> source.stage("code", space.id(), vector(3), source.revision()));
  }

  @Test
  void invalidPublicationRollsBackItsWholeTransaction() {
    var source = fixtures().getFirst();
    long before = source.revision();
    var space = register("rollback-fixture", 3);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    assertThrows(
        DataAccessException.class,
        () ->
            transaction.execute(
                status -> {
                  source.change();
                  source.publish("code", space.id(), vector(3), before);
                  return null;
                }));
    assertEquals(before, source.revision());
    assertNull(source.text("code_space_id"));
  }

  @Test
  void changingSourceAndPublishingReplacementInOneWriteIsRejected() {
    var source = fixtures().getFirst();
    long before = source.revision();
    var space = register("combined-write", 3);
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE memories SET summary='new input', code_space_id=?, code_embedding=?::vector, code_source_revision=1 WHERE id=?",
                space.id(),
                vector(3),
                source.id));
    assertEquals(before, source.revision());
    assertNull(source.text("code_space_id"));
  }

  @Test
  void deletionAndRecreationCannotReuseAPendingResultsFreshnessToken() {
    var source = fixtures().stream().filter(s -> s.kind == Kind.PASSAGE).findFirst().orElseThrow();
    long before = source.revision();
    var space = register("recreation-fixture", 3);
    target("code", space.id());
    source.stage("code", space.id(), vector(3), before);
    source.delete();
    jdbc.update(
        "INSERT INTO retrieval_passages(source_type, source_id, position, passage, embedding) VALUES ('entry', ?, 0, 'different source', ?::vector)",
        source.id,
        LEGACY_VECTOR);
    assertTrue(source.revision() > before);
    assertEquals(0, source.staged());
    assertThrows(
        DataAccessException.class, () -> source.stage("code", space.id(), vector(3), before));
    assertThrows(
        DataAccessException.class, () -> source.publish("code", space.id(), vector(3), before));
    source.publish("code", space.id(), vector(3), source.revision());
  }

  @Test
  void stagedPublicationHoldsTargetSnapshotUntilCommit() throws Exception {
    var source = fixtures().getFirst();
    long before = source.revision();
    var currentTarget = register("locked-target", 3);
    var changedTarget = register("changed-target", 3);
    target("code", currentTarget.id());
    try (var executor = Executors.newSingleThreadExecutor();
        Connection staging = dataSource.getConnection()) {
      staging.setAutoCommit(false);
      try (var insert =
          staging.prepareStatement(
              "INSERT INTO memories_embedding_staging(source_id,slot,space_id,embedding,source_revision) VALUES (?,'code',?,?::vector,?)")) {
        insert.setString(1, source.id);
        insert.setString(2, currentTarget.id());
        insert.setString(3, vector(3));
        insert.setLong(4, before);
        insert.executeUpdate();
      }
      var entered = new CountDownLatch(1);
      var reconfiguration =
          executor.submit(
              () -> {
                entered.countDown();
                target("code", changedTarget.id());
              });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      try {
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> {
              while (jdbc.queryForObject(
                      "SELECT count(*) FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%UPDATE embedding_slots%'",
                      Integer.class)
                  == 0) {
                Thread.sleep(20);
              }
            });
      } finally {
        staging.commit();
      }
      reconfiguration.get(5, TimeUnit.SECONDS);
      assertEquals(1, source.staged());
      assertThrows(
          DataAccessException.class,
          () -> source.stage("code", currentTarget.id(), vector(3), before));
    }
  }

  @Test
  void equalWidthSpacesRemainIsolatedDuringExactRanking() {
    var first = fixtures().getFirst();
    var other = fixtures().getFirst();
    var expectedSpace = register("ranking-one", 3);
    var differentSpace = register("ranking-two", 3);
    first.publish("code", expectedSpace.id(), "[0,1,0]", first.revision());
    other.publish("code", differentSpace.id(), "[1,0,0]", other.revision());
    var results =
        jdbc.queryForList(
            "SELECT id FROM memories WHERE id IN (?, ?) AND code_space_id=? AND code_source_revision=embedding_source_revision ORDER BY code_embedding <=> ?::vector",
            String.class,
            first.id,
            other.id,
            expectedSpace.id(),
            "[1,0,0]");
    assertEquals(List.of(first.id), results);
  }

  @Test
  void concurrentRegistrationResolvesToOneImmutableIdentity() throws Exception {
    var descriptor =
        definition("concurrent-" + UUID.randomUUID(), 3, EmbeddingSpace.Normalization.NONE);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return spaces.register(descriptor);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return spaces.register(descriptor);
              });
      start.countDown();
      assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM embedding_spaces WHERE model_id=?",
              Integer.class,
              descriptor.modelId()));
    }
  }

  @Test
  void staleStageWaitsForSourceWriterAndIsRejectedAfterCommit() throws Exception {
    var source = fixtures().getFirst();
    long before = source.revision();
    var space = register("race-fixture", 3);
    target("code", space.id());
    try (var executor = Executors.newSingleThreadExecutor();
        Connection writer = dataSource.getConnection()) {
      writer.setAutoCommit(false);
      try (var update =
          writer.prepareStatement("UPDATE memories SET summary='racing edit' WHERE id=?")) {
        update.setString(1, source.id);
        update.executeUpdate();
      }
      var entered = new CountDownLatch(1);
      var publication =
          executor.submit(
              () -> {
                entered.countDown();
                source.stage("code", space.id(), vector(3), before);
              });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      try {
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> {
              while (jdbc.queryForObject(
                      "SELECT count(*) FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%INSERT INTO memories_embedding_staging%'",
                      Integer.class)
                  == 0) {
                Thread.sleep(20);
              }
            });
      } finally {
        // Always release the row lock, even if the observation assertion fails.
        writer.commit();
      }
      var failure =
          assertThrows(ExecutionException.class, () -> publication.get(5, TimeUnit.SECONDS));
      assertInstanceOf(DataAccessException.class, failure.getCause());
      assertEquals(0, source.staged());
      assertTrue(source.revision() > before);
    }
  }

  private static EmbeddingSpace.Definition definition(
      String model, int width, EmbeddingSpace.Normalization normalization) {
    return new EmbeddingSpace.Definition(
        model, "revision-1", width, "query: ", "document: ", "mean", normalization, "none");
  }

  private static EmbeddingSpace register(String model, int width) {
    return spaces.register(definition(model, width, EmbeddingSpace.Normalization.NONE));
  }

  private static void target(String slot, String id) {
    jdbc.update("UPDATE embedding_slots SET target_space_id=? WHERE slot=?", id, slot);
  }

  private static String vector(int width) {
    return "[1," + String.join(",", Collections.nCopies(width - 1, "0")) + "]";
  }

  private static List<Source> fixtures() {
    return fixtures(LEGACY_VECTOR);
  }

  private static List<Source> fixtures(String legacyEmbedding) {
    String id = UUID.randomUUID().toString();
    UUID document = UUID.randomUUID();
    UUID paragraph = UUID.randomUUID();
    UUID chunk = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO memories(id, summary, scope, body, state, formed_at, formed_by, formed_where, embedding) VALUES (?, 'memory', 'scope', 'body', 'active', now(), 'fixture', 'fixture', ?::vector)",
        id,
        legacyEmbedding);
    jdbc.update(
        "INSERT INTO digests(id, depth, summary, embedding) VALUES (?, 0, 'digest', ?::vector)",
        id,
        legacyEmbedding);
    jdbc.update(
        "INSERT INTO documents(id, source_name, title, content_hash, text_hash, byte_size, ingested_at, ingested_by, summary, summary_embedding) VALUES (?, ?, 'fixture', 'hash', 'text-hash', 1, now(), 'fixture', 'summary', ?::vector)",
        document,
        id,
        legacyEmbedding);
    jdbc.update(
        "INSERT INTO paragraphs(id, document_id, text, content_hash, occurrence, ordinal) VALUES (?, ?, 'paragraph', 'hash', 1, 1)",
        paragraph,
        document);
    jdbc.update(
        "INSERT INTO chunks(id, paragraph_id, ordinal, text, byte_size, split_mid_sentence, embedding) VALUES (?, ?, 1, 'chunk', 1, false, ?::vector)",
        chunk,
        paragraph,
        legacyEmbedding);
    jdbc.update(
        "INSERT INTO retrieval_sources(source_type, source_id, source_hash) VALUES ('entry', ?, 'hash')",
        id);
    jdbc.update(
        "INSERT INTO retrieval_passages(source_type, source_id, position, passage, embedding) VALUES ('entry', ?, 0, 'passage', ?::vector)",
        id,
        legacyEmbedding);
    return List.of(
        new Source(Kind.MEMORY, id),
        new Source(Kind.DIGEST, id),
        new Source(Kind.CHUNK, chunk.toString()),
        new Source(Kind.DOCUMENT, document.toString()),
        new Source(Kind.PASSAGE, id));
  }

  private enum Kind {
    MEMORY("memories", "embedding", "summary='changed memory'"),
    DIGEST("digests", "embedding", "summary='changed digest', revision=revision+1"),
    CHUNK("chunks", "embedding", "text='changed chunk'"),
    DOCUMENT("documents", "summary_embedding", "summary='changed summary'"),
    PASSAGE("retrieval_passages", "embedding", "passage='changed passage'");

    final String table;
    final String legacyColumn;
    final String edit;

    Kind(String table, String legacyColumn, String edit) {
      this.table = table;
      this.legacyColumn = legacyColumn;
      this.edit = edit;
    }
  }

  /** Identifiers come only from the fixture enum and the two constant slot choices above. */
  private record Source(Kind kind, String id) {
    Object key() {
      return kind == Kind.CHUNK || kind == Kind.DOCUMENT ? UUID.fromString(id) : id;
    }

    String predicate() {
      return kind == Kind.PASSAGE ? "source_type='entry' AND source_id=? AND position=0" : "id=?";
    }

    String stagedPredicate() {
      return kind == Kind.PASSAGE ? predicate() : "source_id=?";
    }

    String text(String expression) {
      return jdbc.queryForObject(
          "SELECT " + expression + " FROM " + kind.table + " WHERE " + predicate(),
          String.class,
          key());
    }

    long revision() {
      return Long.parseLong(text("embedding_source_revision::text"));
    }

    void publish(String slot, String space, String value, long revision) {
      jdbc.update(
          "UPDATE "
              + kind.table
              + " SET "
              + slot
              + "_space_id=?, "
              + slot
              + "_embedding=?::vector, "
              + slot
              + "_source_revision=? WHERE "
              + predicate(),
          space,
          value,
          revision,
          key());
    }

    void change() {
      update(kind.edit);
    }

    void update(String assignments) {
      jdbc.update("UPDATE " + kind.table + " SET " + assignments + " WHERE " + predicate(), key());
    }

    void stage(String slot, String space, String value, long revision) {
      String columns = kind == Kind.PASSAGE ? "source_type, position, source_id" : "source_id";
      String values = kind == Kind.PASSAGE ? "'entry', 0, ?" : "?";
      jdbc.update(
          "INSERT INTO "
              + kind.table
              + "_embedding_staging("
              + columns
              + ", slot, space_id, embedding, source_revision) VALUES ("
              + values
              + ", ?, ?, ?::vector, ?)",
          key(),
          slot,
          space,
          value,
          revision);
    }

    int staged() {
      return jdbc.queryForObject(
          "SELECT count(*) FROM " + kind.table + "_embedding_staging WHERE " + stagedPredicate(),
          Integer.class,
          key());
    }

    void delete() {
      // The fixture's automatically created digest owns a historical link. Production
      // memories remain tombstones; unlink only this synthetic fixture to test stage FKs.
      if (kind == Kind.MEMORY) {
        jdbc.update("DELETE FROM digest_memories WHERE memory_id=?", key());
      }
      jdbc.update("DELETE FROM " + kind.table + " WHERE " + predicate(), key());
    }
  }
}
