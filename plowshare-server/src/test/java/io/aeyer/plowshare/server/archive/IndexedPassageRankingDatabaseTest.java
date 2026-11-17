package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.embedding.EmbeddingIndexPlan;
import io.aeyer.plowshare.server.embedding.EmbeddingProfile;
import io.aeyer.plowshare.server.embedding.EmbeddingQuery;
import io.aeyer.plowshare.server.embedding.EmbeddingSearchPolicy;
import io.aeyer.plowshare.server.embedding.EmbeddingSlot;
import io.aeyer.plowshare.server.embedding.EmbeddingSpace;
import io.aeyer.plowshare.server.embedding.EmbeddingWorkRepository;
import io.aeyer.plowshare.server.embedding.JdbcEmbeddingSpaceRepository;
import io.aeyer.plowshare.server.information.InformationLogVisibility;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The real filtered SQL must retain an ANN access path, not merely mention a distance operator. */
@Tag("full-db")
@Testcontainers
class IndexedPassageRankingDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg16-bookworm");

  static RecordingJdbc jdbc;
  static UnitOfWork transactions;
  static EmbeddingProfile profile;
  static String selected;
  static String excluded;
  static String hidden;
  static String indexName;
  static final Home HOME = Home.of("indexed-passages");

  @BeforeAll
  static void database() {
    var ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    jdbc = new RecordingJdbc(ds);
    var template = new TransactionTemplate(new DataSourceTransactionManager(ds));
    transactions =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> action) {
            return template.execute(status -> action.get());
          }
        };
    var conversations = new ConversationStore(jdbc);
    selected = conversations.open(HOME, Budget.of(10)).id();
    hidden = conversations.open(HOME, Budget.of(10)).id();
    excluded = conversations.open(Home.of("outside-indexed-passages"), Budget.of(10)).id();
    for (String conversation : List.of(selected, excluded, hidden))
      jdbc.update(
          "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content) SELECT ?,g,1,'utterance','user','evidence ' || g FROM generate_series(1,3000) g",
          conversation);
    var space =
        new JdbcEmbeddingSpaceRepository(jdbc)
            .register(
                new EmbeddingSpace.Definition(
                    "indexed-fixture",
                    "v1",
                    3,
                    "",
                    "",
                    "mean",
                    EmbeddingSpace.Normalization.NONE,
                    "none"));
    profile =
        new EmbeddingProfile(
            EmbeddingSlot.PROSE,
            space,
            new EmbeddingSearchPolicy(
                EmbeddingSearchPolicy.Mode.HNSW_VECTOR, EmbeddingSearchPolicy.Distance.COSINE),
            1);
    jdbc.update(
        "UPDATE retrieval_sources SET status='ready',generation='indexed-fixture' WHERE source_type='entry'");
    jdbc.update(
        "INSERT INTO retrieval_passages(source_type,source_id,position,passage) SELECT s.source_type,s.source_id,0,'evidence ' || s.ordinal FROM retrieval_sources s WHERE source_type='entry'");
    // Passage triggers own the source revision; retain their value for current vectors.
    jdbc.update(
        "UPDATE retrieval_passages p SET prose_space_id=?,prose_embedding=ARRAY[1.0,(s.ordinal % 100)::float8/100,0]::vector,prose_source_revision=p.embedding_source_revision FROM retrieval_sources s WHERE p.source_type=s.source_type AND p.source_id=s.source_id",
        space.id());
    jdbc.update(
        "INSERT INTO information_quarantined_payloads(payload_id,reason) VALUES(?,'fixture')",
        hidden);
    indexName = new EmbeddingIndexPlan(profile).indexName(EmbeddingWorkRepository.Store.PASSAGES);
    jdbc.execute(
        "CREATE INDEX "
            + indexName
            + " ON retrieval_passages USING hnsw ((prose_embedding::vector(3)) vector_cosine_ops) WHERE prose_space_id='"
            + space.id()
            + "'");
    for (String table :
        List.of("retrieval_passages", "retrieval_sources", "entries", "conversations"))
      jdbc.execute("ANALYZE " + table);
  }

  @Test
  void currentVisibilityFactsPreserveCandidatesAndCoverageWithoutRepeatingTheGraph() {
    var facts =
        new InformationLogVisibility.Facts(
            Optional.of(
                jdbc.queryForList(
                    "SELECT log FROM information_unreadable_logs(?)", String.class, "reader")));
    var visibility = mock(InformationLogVisibility.class);
    when(visibility.read(eq("reader"), any()))
        .thenAnswer(
            call -> {
              Function<InformationLogVisibility.Facts, Object> read = call.getArgument(1);
              return read.apply(facts);
            });
    var direct = new JdbcPassageRepository(jdbc, transactions);
    var shared = new JdbcPassageRepository(jdbc, transactions, Duration.ofSeconds(2), visibility);
    var query = new EmbeddingQuery(profile, new float[] {1, 0, 0});
    var scope = new PassageRepository.ReadScope(true, "reader");
    var expected = direct.rankWindow(HOME, "entry", query, 10, "indexed-fixture", scope);
    var actual = shared.rankWindow(HOME, "entry", query, 10, "indexed-fixture", scope);
    assertEquals(expected, actual);
    assertTrue(jdbc.selectionSql.contains("unnest(?::text[])"), jdbc.selectionSql);
    assertFalse(jdbc.selectionSql.contains("information_unreadable_logs"), jdbc.selectionSql);
    assertEquals(
        direct.coverage(HOME, "entry", "indexed-fixture", scope, Optional.of(profile)),
        shared.coverage(HOME, "entry", "indexed-fixture", scope, Optional.of(profile)));
    verify(visibility, times(2)).read(eq("reader"), any());
  }

  @Test
  void aRacedVisibilityFenceRefusesTheCandidateReadWithoutReplayingIt() {
    var visibility = mock(InformationLogVisibility.class);
    when(visibility.read(eq("reader"), any()))
        .thenAnswer(
            call -> {
              Function<InformationLogVisibility.Facts, Object> read = call.getArgument(1);
              read.apply(new InformationLogVisibility.Facts(Optional.of(List.of(hidden))));
              throw ReadFailedException.invalidated();
            });
    var repository =
        new JdbcPassageRepository(jdbc, transactions, Duration.ofSeconds(2), visibility);
    int before = jdbc.selections;
    var failure =
        assertThrows(
            ReadFailedException.class,
            () ->
                repository.rankWindow(
                    HOME,
                    "entry",
                    new EmbeddingQuery(profile, new float[] {1, 0, 0}),
                    10,
                    "indexed-fixture",
                    new PassageRepository.ReadScope(true, "reader")));
    assertEquals(ReadFailedException.Reason.INVALIDATED, failure.reason());
    assertEquals(before + 1, jdbc.selections);
    verify(visibility).read(eq("reader"), any());
  }

  private static PassageRepository.RankingReadUnit units(
      List<Long> connections, List<Long> deadlines, Consumer<Integer> before) {
    return new PassageRepository.RankingReadUnit() {
      int count;

      public <T> T independent(Supplier<T> read) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(ReadDeadline.currentDeadlineNanos().isEmpty());
        before.accept(++count);
        var result =
            ReadDeadline.within(
                Duration.ofSeconds(2),
                () ->
                    transactions.inTransaction(
                        () -> {
                          connections.add(
                              jdbc.queryForObject("SELECT pg_backend_pid()::bigint", Long.class));
                          deadlines.add(ReadDeadline.deadlineNanos(Duration.ofSeconds(2)));
                          return read.get();
                        }));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        return result;
      }

      public <T> T joined(Supplier<T> read) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        return read.get();
      }
    };
  }

  @Test
  void independentCandidatePagesMatchTheWholeWindowAndReleaseEachActualTransaction() {
    var repository = new JdbcPassageRepository(jdbc, transactions);
    var query = new EmbeddingQuery(profile, new float[] {1, 0, 0});
    var scope = new PassageRepository.ReadScope(true, "reader");
    var expected = repository.rankWindow(HOME, "entry", query, 200, "indexed-fixture", scope);
    var expectedKeys = Set.copyOf(jdbc.selectedKeys);
    int first = jdbc.windows.size();
    var connections = new ArrayList<Long>();
    var deadlines = new ArrayList<Long>();
    var actual =
        repository.rankWindow(
            HOME,
            "entry",
            query,
            200,
            "indexed-fixture",
            scope,
            units(connections, deadlines, ignored -> {}));
    assertEquals(expected, actual);
    var pages = jdbc.windows.subList(first, jdbc.windows.size());
    assertEquals(List.of(500, 500, 500, 500), pages.stream().map(List::size).toList());
    var actualKeys = pages.stream().flatMap(List::stream).toList();
    assertEquals(2000, actualKeys.size());
    assertEquals(expectedKeys, Set.copyOf(actualKeys));
    assertEquals(5, connections.size());
    assertEquals(
        5, Set.copyOf(connections).size(), "each actual unit releases its JDBC connection");
    assertEquals(deadlines.stream().sorted().toList(), deadlines);
    assertEquals(5, Set.copyOf(deadlines).size());
    assertTrue(jdbc.selectionSql.contains("LIMIT ? OFFSET ?) SELECT"), jdbc.selectionSql);
    assertTrue(jdbc.plan.contains(indexName), jdbc.plan);
    assertTrue(jdbc.plan.contains("hashed SubPlan"), jdbc.plan);
    assertTrue(ReadDeadline.currentDeadlineNanos().isEmpty());
  }

  @Test
  void multiplePassagePositionsKeepDistinctRevisionTokensAndTheSameGlobalGrouping() {
    jdbc.update(
        "INSERT INTO retrieval_passages(source_type,source_id,position,passage)"
            + " SELECT source_type,source_id,1,passage FROM retrieval_passages WHERE source_id LIKE ? AND position=0",
        selected + ":%");
    jdbc.update(
        "UPDATE retrieval_passages p SET prose_space_id=original.prose_space_id,"
            + " prose_embedding=original.prose_embedding,prose_source_revision=p.embedding_source_revision"
            + " FROM retrieval_passages original WHERE p.source_id=original.source_id"
            + " AND p.source_type=original.source_type AND original.position=0 AND p.position=1");
    try {
      var repository = new JdbcPassageRepository(jdbc, transactions);
      var query = new EmbeddingQuery(profile, new float[] {1, 0, 0});
      var scope = new PassageRepository.ReadScope(true, "reader");
      var expected = repository.rankWindow(HOME, "entry", query, 200, "indexed-fixture", scope);
      var original = Set.copyOf(jdbc.selectedKeys);
      int first = jdbc.windows.size();
      var actual =
          repository.rankWindow(
              HOME,
              "entry",
              query,
              200,
              "indexed-fixture",
              scope,
              units(new ArrayList<>(), new ArrayList<>(), ignored -> {}));
      var keys =
          jdbc.windows.subList(first, jdbc.windows.size()).stream().flatMap(List::stream).toList();
      assertEquals(2000, keys.size());
      assertTrue(keys.stream().anyMatch(key -> key.position() == 1));
      assertEquals(original, Set.copyOf(keys));
      assertEquals(expected, actual);
    } finally {
      jdbc.update(
          "DELETE FROM retrieval_passages WHERE position=1 AND source_id LIKE ?", selected + ":%");
    }
  }

  @Test
  void aCallerTransactionUsesTheOriginalSingleWindowAndEarlierDeadline() {
    var repository = new JdbcPassageRepository(jdbc, transactions);
    int before = jdbc.selections;
    transactions.inTransaction(
        () ->
            ReadDeadline.within(
                Duration.ofMillis(900),
                () -> {
                  long deadline = ReadDeadline.deadlineNanos(Duration.ofSeconds(2));
                  var joined =
                      new PassageRepository.RankingReadUnit() {
                        public <T> T independent(Supplier<T> read) {
                          throw new AssertionError(
                              "caller transaction must not be split or renewed");
                        }

                        public <T> T joined(Supplier<T> read) {
                          assertEquals(deadline, ReadDeadline.deadlineNanos(Duration.ofSeconds(2)));
                          return read.get();
                        }
                      };
                  repository.rankWindow(
                      HOME,
                      "entry",
                      new EmbeddingQuery(profile, new float[] {1, 0, 0}),
                      200,
                      "indexed-fixture",
                      new PassageRepository.ReadScope(true, "reader"),
                      joined);
                  assertEquals(deadline, ReadDeadline.deadlineNanos(Duration.ofSeconds(2)));
                  return null;
                }));
    assertEquals(before + 1, jdbc.selections);
    assertFalse(jdbc.selectionSql.contains("OFFSET"));
  }

  @Test
  void failureInTheSecondUnitReturnsNoPartialRankingOrRetry() {
    int before = jdbc.selections;
    jdbc.afterSelection =
        ignored -> {
          if (jdbc.selections == before + 2) {
            jdbc.execute("SET LOCAL statement_timeout='5ms'");
            ArchiveUnavailableException.translating(
                "exercise second candidate unit cancellation",
                () -> jdbc.queryForObject("SELECT pg_sleep(0.1)", Object.class));
          }
        };
    try {
      var actual =
          assertThrows(
              ReadFailedException.class,
              () ->
                  new JdbcPassageRepository(jdbc, transactions)
                      .rankWindow(
                          HOME,
                          "entry",
                          new EmbeddingQuery(profile, new float[] {1, 0, 0}),
                          200,
                          "indexed-fixture",
                          new PassageRepository.ReadScope(true, "reader"),
                          units(new ArrayList<>(), new ArrayList<>(), ignored -> {})));
      assertEquals(ReadFailedException.Reason.QUERY_CANCELLED, actual.reason());
      assertEquals(before + 2, jdbc.selections);
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
    } finally {
      jdbc.afterSelection = ignored -> {};
    }
  }

  @Test
  void finalUnitRejectsRevocationAfterTheEarlierCandidateTransactionsEnded() {
    try {
      var failure =
          assertThrows(
              ReadFailedException.class,
              () ->
                  new JdbcPassageRepository(jdbc, transactions)
                      .rankWindow(
                          HOME,
                          "entry",
                          new EmbeddingQuery(profile, new float[] {1, 0, 0}),
                          200,
                          "indexed-fixture",
                          new PassageRepository.ReadScope(true, "reader"),
                          units(
                              new ArrayList<>(),
                              new ArrayList<>(),
                              unit -> {
                                if (unit == 5)
                                  jdbc.update(
                                      "INSERT INTO information_quarantined_payloads(payload_id,reason) VALUES(?,'fixture')",
                                      selected);
                              })));
      assertEquals(ReadFailedException.Reason.INVALIDATED, failure.reason());
    } finally {
      jdbc.update("DELETE FROM information_quarantined_payloads WHERE payload_id=?", selected);
    }
  }

  @Test
  void finalUnitRejectsSourceChangesAfterTheEarlierCandidateTransactionsEnded() {
    String id = selected + ":100";
    String hash =
        jdbc.queryForObject(
            "SELECT source_hash FROM retrieval_sources WHERE source_id=?", String.class, id);
    try {
      var failure =
          assertThrows(
              ReadFailedException.class,
              () ->
                  new JdbcPassageRepository(jdbc, transactions)
                      .rankWindow(
                          HOME,
                          "entry",
                          new EmbeddingQuery(profile, new float[] {1, 0, 0}),
                          200,
                          "indexed-fixture",
                          new PassageRepository.ReadScope(true, "reader"),
                          units(
                              new ArrayList<>(),
                              new ArrayList<>(),
                              unit -> {
                                if (unit == 5)
                                  jdbc.update(
                                      "UPDATE retrieval_sources SET source_hash='changed' WHERE source_id=?",
                                      id);
                              })));
      assertEquals(ReadFailedException.Reason.INVALIDATED, failure.reason());
    } finally {
      jdbc.update("UPDATE retrieval_sources SET source_hash=? WHERE source_id=?", hash, id);
    }
  }

  @Test
  void scopedCandidatesUseTheVectorIndexBeforeBoundedRerankingAndGrouping() {
    var repository = new JdbcPassageRepository(jdbc, transactions);
    var ranking =
        repository.rankWindow(
            HOME,
            "entry",
            new EmbeddingQuery(profile, new float[] {1, 0, 0}),
            10,
            "indexed-fixture",
            new PassageRepository.ReadScope(true, "reader"));
    assertTrue(ranking.approximate());
    assertTrue(ranking.truncated());
    assertEquals(10, ranking.matches().size());
    assertTrue(ranking.matches().stream().allMatch(match -> match.id().startsWith(selected + ":")));
    assertTrue(jdbc.plan.contains(indexName), jdbc.plan);
    assertTrue(jdbc.plan.contains("CTE authorized_sources"), jdbc.plan);
    assertTrue(jdbc.plan.contains("CTE candidate_keys"), jdbc.plan);
    assertTrue(jdbc.plan.contains("hashed SubPlan"), jdbc.plan);
    assertFalse(jdbc.plan.contains("Seq Scan on retrieval_passages"), jdbc.plan);
    assertTrue(jdbc.candidateRows <= 100, "full precision/text loading must remain bounded");
    assertTrue(jdbc.textRows <= jdbc.candidateRows);
    assertEquals(
        "off",
        jdbc.queryForObject(
            "SELECT coalesce(current_setting('hnsw.iterative_scan',true),'off')", String.class));
    assertEquals("on", jdbc.queryForObject("SELECT current_setting('enable_sort')", String.class));
  }

  @Test
  void authorizedHashReusePreservesTheCandidateWindowAndUsesOneTimeHashJoins() throws Exception {
    // A second visible log supplies repeated source hashes and tied vector distances. Hashes are
    // projected by source identity, never used to merge distinct authorized occurrences.
    jdbc.update("DELETE FROM information_quarantined_payloads WHERE payload_id=?", hidden);
    try {
      var query = new EmbeddingQuery(profile, new float[] {1, 0, 0});
      var plan = new EmbeddingIndexPlan(profile);
      int most = 200;
      List<Key> original =
          transactions.inTransaction(
              () -> {
                jdbc.execute(
                    "SET LOCAL hnsw.iterative_scan='strict_order'; SET LOCAL enable_sort=off; SET LOCAL jit=off");
                // The previously shipped candidate shape is retained only as this parity oracle. It
                // probes source hashes again for each accepted passage instead of reusing the
                // projection.
                return jdbc.query(
                    "WITH authorized_sources AS MATERIALIZED (SELECT s.source_id FROM retrieval_sources s"
                        + " JOIN entries e ON e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal"
                        + " JOIN conversations owner ON owner.id=e.conversation_id"
                        + " WHERE s.source_type='entry' AND s.status='ready' AND s.generation=?"
                        + " AND owner.project_id IS NOT DISTINCT FROM CAST(? AS bigint)"
                        + " AND e.content IS NOT NULL AND e.role IS NOT NULL AND e.ejected_at IS NULL"
                        + " AND s.conversation_id NOT IN (SELECT log FROM information_unreadable_logs(?))"
                        + ") SELECT p.source_id,p.position,s.source_hash,p.embedding_source_revision"
                        + " FROM retrieval_passages p JOIN retrieval_sources s USING(source_type,source_id)"
                        + " WHERE p.source_type='entry'"
                        + " AND (p.source_id IN (SELECT source_id FROM authorized_sources)) IS TRUE"
                        + " AND NOT p.supplied_context"
                        + " AND "
                        + plan.present("p")
                        + " ORDER BY "
                        + plan.candidateDistance("p.prose_embedding")
                        + " LIMIT ?",
                    (row, n) -> key(row),
                    "indexed-fixture",
                    ProjectIds.toRead(jdbc, HOME),
                    "reader",
                    query.vectorText(),
                    plan.candidates(most));
              });
      jdbc.analyzeNextSelection = true;
      var ranking =
          new JdbcPassageRepository(jdbc, transactions)
              .rankWindow(
                  HOME,
                  "entry",
                  query,
                  most,
                  "indexed-fixture",
                  new PassageRepository.ReadScope(true, "reader"));
      assertEquals(2000, original.size());
      assertEquals(original.size(), jdbc.selectedKeys.size());
      assertEquals(Set.copyOf(original), Set.copyOf(jdbc.selectedKeys));
      assertTrue(
          jdbc.selectedKeys.stream().map(Key::hash).distinct().count() < jdbc.selectedKeys.size());
      var originalPassages =
          jdbc.query(
              "SELECT p.source_id,p.position,p.passage,s.source_hash,"
                  + plan.denseDistance("p.prose_embedding")
                  + " AS distance"
                  + " FROM retrieval_passages p JOIN retrieval_sources s USING(source_type,source_id)"
                  + " WHERE p.source_type='entry' AND p.source_id=ANY(CAST(? AS text[]))",
              (row, n) ->
                  new PassageRanking.Candidate(
                      row.getString("source_id"),
                      row.getInt("position"),
                      row.getString("passage"),
                      row.getString("source_hash"),
                      row.getDouble("distance")),
              query.vectorText(),
              new SqlArrayValue("text", original.stream().map(Key::id).toArray()));
      assertEquals(PassageRanking.group(originalPassages, most, true, true), ranking.matches());

      JsonNode actual = new ObjectMapper().readTree(jdbc.analyzedPlan).get(0).path("Plan");
      assertEquals("Hash Join", actual.path("Node Type").asText(), jdbc.analyzedPlan);
      assertEquals(1, actual.path("Actual Loops").asInt(), jdbc.analyzedPlan);
      var nodes = nodes(actual);
      assertTrue(
          nodes.stream().anyMatch(node -> indexName.equals(node.path("Index Name").asText())));
      assertFalse(
          nodes.stream()
              .anyMatch(node -> "retrieval_sources_pkey".equals(node.path("Index Name").asText())),
          jdbc.analyzedPlan);
      var projectionReads =
          nodes.stream()
              .filter(node -> "authorized_sources".equals(node.path("CTE Name").asText()))
              .toList();
      assertEquals(2, projectionReads.size(), jdbc.analyzedPlan);
      assertTrue(
          projectionReads.stream().allMatch(node -> node.path("Actual Loops").asInt() == 1),
          jdbc.analyzedPlan);
    } finally {
      jdbc.analyzeNextSelection = false;
      jdbc.update(
          "INSERT INTO information_quarantined_payloads(payload_id,reason) VALUES(?,'fixture')",
          hidden);
    }
  }

  private record Key(String id, int position, String hash, long revision) {}

  private static Key key(ResultSet row) throws SQLException {
    return new Key(
        row.getString("source_id"),
        row.getInt("position"),
        row.getString("source_hash"),
        row.getLong("embedding_source_revision"));
  }

  private static List<JsonNode> nodes(JsonNode root) {
    var nodes = new ArrayList<JsonNode>();
    nodes.add(root);
    root.path("Plans").forEach(child -> nodes.addAll(nodes(child)));
    return nodes;
  }

  @Test
  void staleSourceGenerationsAreExcludedBeforeTheAnnCandidateLimit() {
    String stale = selected + ":100";
    jdbc.update(
        "UPDATE retrieval_sources SET generation='stale-fixture' WHERE source_type='entry' AND source_id=?",
        stale);
    try {
      var ranking =
          new JdbcPassageRepository(jdbc, transactions)
              .rankWindow(
                  HOME,
                  "entry",
                  new EmbeddingQuery(profile, new float[] {1, 0, 0}),
                  10,
                  "indexed-fixture",
                  new PassageRepository.ReadScope(true, "reader", false, selected));
      assertEquals(10, ranking.matches().size());
      assertTrue(
          ranking.matches().stream()
              .flatMap(match -> match.sources().stream())
              .noneMatch(source -> source.id().equals(stale)));
      assertTrue(jdbc.plan.contains("hashed SubPlan"), jdbc.plan);
    } finally {
      jdbc.update(
          "UPDATE retrieval_sources SET generation='indexed-fixture' WHERE source_type='entry' AND source_id=?",
          stale);
    }
  }

  @Test
  void anEditAndRepublishBetweenSelectionAndTextLoadingCannotReuseTheOldCandidate() {
    var changed = new AtomicReference<String>();
    jdbc.afterSelection =
        id -> {
          changed.set(id);
          jdbc.update(
              "UPDATE retrieval_passages SET passage=passage || ' changed' WHERE source_id=?", id);
          jdbc.update(
              "UPDATE retrieval_passages SET prose_embedding=CAST('[1,0,0]' AS vector),prose_space_id=?,prose_source_revision=embedding_source_revision WHERE source_id=?",
              profile.space().id(),
              id);
        };
    try {
      var repository = new JdbcPassageRepository(jdbc, transactions);
      var ranking =
          repository.rankWindow(
              HOME,
              "entry",
              new EmbeddingQuery(profile, new float[] {1, 0, 0}),
              10,
              "indexed-fixture",
              new PassageRepository.ReadScope(false, null, false, selected));
      assertFalse(ranking.matches().isEmpty());
      assertTrue(
          ranking.matches().stream()
              .flatMap(match -> match.sources().stream())
              .noneMatch(source -> source.id().equals(changed.get())));
      assertTrue(jdbc.textRows < jdbc.candidateRows);
    } finally {
      jdbc.afterSelection = ignored -> {};
    }
  }

  @Test
  void indexedStagesCanUseASubSecondRemainderAndRestoreCallerSettings() {
    transactions.inTransaction(
        () -> {
          jdbc.execute(
              "SET LOCAL hnsw.iterative_scan='relaxed_order'; SET LOCAL enable_sort=on; SET LOCAL jit=on");
          var repository = new JdbcPassageRepository(jdbc, transactions);
          var result =
              ReadDeadline.within(
                  Duration.ofMillis(900),
                  () ->
                      repository.rankWindow(
                          HOME,
                          "entry",
                          new EmbeddingQuery(profile, new float[] {1, 0, 0}),
                          10,
                          "indexed-fixture",
                          new PassageRepository.ReadScope(true, "reader")));
          assertFalse(result.matches().isEmpty());
          assertEquals(
              "relaxed_order",
              jdbc.queryForObject("SELECT current_setting('hnsw.iterative_scan')", String.class));
          assertEquals(
              "on", jdbc.queryForObject("SELECT current_setting('enable_sort')", String.class));
          assertEquals("on", jdbc.queryForObject("SELECT current_setting('jit')", String.class));
          return null;
        });
  }

  @Test
  void suppliedContextAndConversationFiltersPrecedeTheCandidateLimit() {
    jdbc.update(
        "UPDATE retrieval_passages SET supplied_context=true WHERE source_id=?", selected + ":100");
    var repository = new JdbcPassageRepository(jdbc, transactions);
    var scope = new PassageRepository.ReadScope(false, null, false, selected);
    var ranking =
        repository.rankWindow(
            HOME,
            "entry",
            new EmbeddingQuery(profile, new float[] {1, 0, 0}),
            10,
            "indexed-fixture",
            scope);
    assertTrue(ranking.matches().stream().allMatch(match -> match.id().startsWith(selected + ":")));
    assertTrue(ranking.matches().stream().noneMatch(match -> match.id().equals(selected + ":100")));
    assertFalse(ranking.matches().isEmpty());
    jdbc.update(
        "UPDATE retrieval_passages SET supplied_context=false WHERE source_id=?",
        selected + ":100");
  }

  private static final class RecordingJdbc extends JdbcTemplate {
    String plan = "";
    int candidateRows;
    int textRows;
    String selectionSql = "";
    int selections;
    List<Key> selectedKeys = List.of();
    final List<List<Key>> windows = new ArrayList<>();
    boolean analyzeNextSelection;
    String analyzedPlan = "";
    Consumer<String> afterSelection = ignored -> {};

    RecordingJdbc(DriverManagerDataSource ds) {
      super(ds);
    }

    @Override
    public <T> List<T> query(String sql, PreparedStatementSetter setter, RowMapper<T> mapper) {
      boolean selection =
          sql.startsWith("WITH authorized_sources AS MATERIALIZED")
              || sql.startsWith(
                  "SELECT p.source_id,p.position,s.source_hash,p.embedding_source_revision");
      if (selection)
        plan =
            String.join("\n", super.query("EXPLAIN " + sql, setter, (row, n) -> row.getString(1)));
      if (selection && analyzeNextSelection) {
        analyzeNextSelection = false;
        analyzedPlan =
            String.join(
                "\n",
                super.query(
                    "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) " + sql,
                    setter,
                    (row, n) -> row.getString(1)));
      }
      var ids = new ArrayList<String>();
      var keys = new ArrayList<Key>();
      var result =
          super.query(
              sql,
              setter,
              (row, n) -> {
                if (selection) {
                  ids.add(row.getString("source_id"));
                  keys.add(key(row));
                }
                return mapper.mapRow(row, n);
              });
      if (selection) {
        selectionSql = sql;
        selections++;
        candidateRows = result.size();
        selectedKeys = List.copyOf(keys);
        windows.add(selectedKeys);
        if (!ids.isEmpty()) afterSelection.accept(ids.getFirst());
      }
      if (sql.startsWith("SELECT selected.source_id")) textRows = result.size();
      return result;
    }
  }
}
