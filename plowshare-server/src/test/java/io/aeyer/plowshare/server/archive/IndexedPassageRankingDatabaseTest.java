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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
      var ids = new ArrayList<String>();
      var result =
          super.query(
              sql,
              setter,
              (row, n) -> {
                if (selection) ids.add(row.getString("source_id"));
                return mapper.mapRow(row, n);
              });
      if (selection) {
        selectionSql = sql;
        selections++;
        candidateRows = result.size();
        if (!ids.isEmpty()) afterSelection.accept(ids.getFirst());
      }
      if (sql.startsWith("SELECT selected.source_id")) textRows = result.size();
      return result;
    }
  }
}
