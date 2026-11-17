package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.api.LogSearchView;
import io.aeyer.plowshare.server.cache.BoundedSnapshotCache;
import io.aeyer.plowshare.server.cache.JdbcProjectionVersions;
import io.aeyer.plowshare.server.cache.ProjectionChangeSignals;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.cache.VersionedProjectionCache;
import io.aeyer.plowshare.server.documents.Chunking;
import io.aeyer.plowshare.server.embedding.DualEmbeddings;
import io.aeyer.plowshare.server.embedding.EmbeddingIndexPlan;
import io.aeyer.plowshare.server.embedding.EmbeddingProfile;
import io.aeyer.plowshare.server.embedding.EmbeddingQuery;
import io.aeyer.plowshare.server.embedding.EmbeddingSearchPolicy;
import io.aeyer.plowshare.server.embedding.EmbeddingSlot;
import io.aeyer.plowshare.server.embedding.EmbeddingSpace;
import io.aeyer.plowshare.server.embedding.EmbeddingWorkRepository;
import io.aeyer.plowshare.server.embedding.JdbcEmbeddingSpaceRepository;
import io.aeyer.plowshare.server.information.InformationLogVisibility;
import io.aeyer.plowshare.server.information.JdbcInformationLogVisibility;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.tokens.FixtureTokenizer;
import io.aeyer.plowshare.server.pool.JdbcExecutionTrace;
import io.aeyer.plowshare.server.pool.TracedDataSource;
import io.aeyer.plowshare.server.reads.BoundedReadAdmission;
import io.aeyer.plowshare.server.reads.ReadCapacityProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Explicit isolated benchmark: real SQL/cache/service paging; deterministic provider, no HTTP. */
@Tag("full-db")
@Testcontainers
class ConversationPaginationBenchmarkTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg16-bookworm")
          .withSharedMemorySize(1024L * 1024 * 1024);

  private record Sample(
      String mode,
      int round,
      int offset,
      int hits,
      int total,
      double elapsedMs,
      int statements,
      double sqlMs,
      double maxSqlMs,
      int annCalls,
      int embeddingCalls) {}

  private record Report(
      String scope,
      int passages,
      int sources,
      int dimensions,
      int rounds,
      int pageSize,
      List<Sample> samples) {}

  @Test
  @Timeout(180)
  void benchmarkFirstAndSubsequentSnapshotPages() throws Exception {
    var config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    config.setMaximumPoolSize(4);
    config.setMinimumIdle(1);
    config.setConnectionTimeout(2000);
    try (var pool = new HikariDataSource(config)) {
      Flyway.configure().dataSource(pool).load().migrate();
      var trace = new Trace();
      var source = new TracedDataSource(pool, trace);
      var jdbc = new JdbcTemplate(source);
      var template = new TransactionTemplate(new DataSourceTransactionManager(source));
      UnitOfWork transactions =
          new UnitOfWork() {
            public <T> T inTransaction(Supplier<T> work) {
              return template.execute(status -> work.get());
            }
          };
      Home home = Home.of("pagination-benchmark");
      var conversations = new ConversationStore(jdbc);
      for (Home selected : List.of(home, Home.of("outside-pagination-benchmark"))) {
        String id = conversations.open(selected, Budget.of(10)).id();
        jdbc.update(
            "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content) SELECT ?,g,1,'utterance','user','server benchmark evidence ' || g FROM generate_series(1,5000) g",
            id);
      }
      var space =
          new JdbcEmbeddingSpaceRepository(jdbc)
              .register(
                  new EmbeddingSpace.Definition(
                      "pagination-benchmark",
                      "v1",
                      768,
                      "",
                      "",
                      "mean",
                      EmbeddingSpace.Normalization.NONE,
                      "none"));
      var profile =
          new EmbeddingProfile(
              EmbeddingSlot.PROSE,
              space,
              new EmbeddingSearchPolicy(
                  EmbeddingSearchPolicy.Mode.HNSW_VECTOR, EmbeddingSearchPolicy.Distance.COSINE),
              1);
      jdbc.update(
          "UPDATE retrieval_sources SET status='ready',generation='pagination-benchmark' WHERE source_type='entry'");
      jdbc.update(
          "INSERT INTO retrieval_passages(source_type,source_id,position,passage) SELECT s.source_type,s.source_id,g,'server benchmark evidence ' || s.ordinal || ' passage ' || g FROM retrieval_sources s CROSS JOIN generate_series(0,4) g WHERE source_type='entry'");
      jdbc.update(
          "UPDATE retrieval_passages p SET prose_space_id=?,prose_embedding=(ARRAY[1.0,s.ordinal::float8/5000,0] || array_fill(0::float8,ARRAY[765]))::vector,prose_source_revision=p.embedding_source_revision FROM retrieval_sources s WHERE p.source_type=s.source_type AND p.source_id=s.source_id",
          space.id());
      jdbc.execute("SET maintenance_work_mem='512MB'");
      jdbc.execute(
          "CREATE INDEX "
              + new EmbeddingIndexPlan(profile).indexName(EmbeddingWorkRepository.Store.PASSAGES)
              + " ON retrieval_passages USING hnsw ((prose_embedding::vector(768)) vector_cosine_ops) WHERE prose_space_id='"
              + space.id()
              + "'");
      for (String table :
          List.of("retrieval_passages", "retrieval_sources", "entries", "conversations"))
        jdbc.execute("ANALYZE " + table);

      var versions = new JdbcProjectionVersions(jdbc, source, 256);
      var domains =
          Set.of(ProjectionVersions.Domain.READABILITY, ProjectionVersions.Domain.AUTHORITY);
      var signals = mock(ProjectionChangeSignals.class);
      when(signals.subscribe(eq(domains), any()))
          .thenAnswer(
              call -> {
                ProjectionChangeSignals.Observer observer = call.getArgument(1);
                observer.stateChanged(new ProjectionChangeSignals.State(1, true));
                return (ProjectionChangeSignals.Subscription) () -> {};
              });
      // No notified-staleness allowance: actual PostgreSQL versions fence every cache read.
      try (var cache =
          new VersionedProjectionCache<InformationLogVisibility.Facts>(
              new BoundedSnapshotCache<>(
                  new SnapshotCache.Namespace(
                      versions.databaseIdentity().toString(), "pagination-benchmark"),
                  new SnapshotCache.Limits(
                      8, 4_000_000, 2, 4_000_000, 64, 64, 2, 32, Duration.ofMinutes(1))),
              versions,
              signals,
              domains,
              1_000_000)) {
        var visibility =
            new JdbcInformationLogVisibility(
                jdbc,
                cache,
                versions,
                new BoundedReadAdmission(new ReadCapacityProperties(16, 4, 2, 1, 64, 4)));
        var entries = new EntryStore(jdbc, transactions).useVisibility(visibility);
        var repository =
            new JdbcPassageRepository(jdbc, transactions, Duration.ofSeconds(2), visibility);
        var index =
            new PassageIndex(
                repository,
                mock(EmbeddingClient.class),
                new Chunking(new FixtureTokenizer(4), 80, 100),
                "pagination-benchmark",
                768);
        var embeddings = new AtomicInteger();
        var dual = mock(DualEmbeddings.class);
        when(dual.active(EmbeddingSlot.PROSE)).thenReturn(profile);
        float[] vector = new float[768];
        vector[0] = 1;
        when(dual.query(eq(profile), anyString(), any(UsageAttribution.class)))
            .thenAnswer(
                call -> {
                  embeddings.incrementAndGet();
                  return new EmbeddingQuery(profile, vector);
                });
        when(dual.read(eq(profile), any()))
            .thenAnswer(
                call -> {
                  Supplier<Object> read = call.getArgument(1);
                  return read.get();
                });
        index.useDualEmbeddings(dual);
        var root = new ConversationSearch(entries, index);
        entries.retrieving(() -> root);
        var search = root.forAccount("benchmark-reader");
        var samples = new ArrayList<Sample>();
        for (String mode : List.of("semantic", "hybrid")) {
          for (int round = 0; round < 3; round++) {
            var seen = new HashSet<String>();
            String snapshot = null;
            int total = -1;
            for (int offset = 0; offset <= 200; offset += 20) {
              trace.reset();
              int before = embeddings.get();
              long started = System.nanoTime();
              LogSearchView page =
                  search.search(home, "server benchmark", offset, 20, mode, snapshot);
              double elapsed = (System.nanoTime() - started) / 1_000_000.0;
              samples.add(
                  new Sample(
                      mode,
                      round,
                      offset,
                      page.hits().size(),
                      page.total(),
                      elapsed,
                      trace.statements,
                      trace.sqlNanos / 1_000_000.0,
                      trace.maxSqlNanos / 1_000_000.0,
                      trace.ann,
                      embeddings.get() - before));
              if (offset == 0) {
                snapshot = page.retrieval().snapshot();
                total = page.total();
                assertEquals(1, trace.ann);
                assertEquals(1, embeddings.get() - before);
              } else {
                assertEquals(snapshot, page.retrieval().snapshot());
                assertEquals(0, trace.ann);
                assertEquals(before, embeddings.get());
              }
              assertEquals(total, page.total());
              for (var hit : page.hits())
                assertTrue(
                    seen.add(hit.conversationId() + ":" + hit.ordinal()),
                    "pagination must not duplicate a representative");
            }
            assertEquals(200, total);
            assertEquals(total, seen.size());
          }
        }
        var report =
            new Report(
                "Isolated PostgreSQL 16/pgvector 0.8.7; actual modified search/cache/repository and real version fences; deterministic query-vector provider and profile guard, no network transport/provider latency; round 0 cold-ish, rounds 1-2 warm; first page plus ten subsequent requests",
                50000,
                10000,
                768,
                3,
                20,
                samples);
        Path output = Path.of("build/reports/benchmarks/conversation-pagination.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
      }
    }
  }

  private static final class Trace implements JdbcExecutionTrace {
    int statements, ann;
    long sqlNanos, maxSqlNanos;

    void reset() {
      statements = 0;
      ann = 0;
      sqlNanos = 0;
      maxSqlNanos = 0;
    }

    public Span begin(String sql, String method) {
      statements++;
      if (sql.startsWith("SELECT p.source_id,p.position,s.source_hash,p.embedding_source_revision"))
        ann++;
      long start = System.nanoTime();
      return new Span() {
        public void failed() {}

        public void close() {
          long elapsed = System.nanoTime() - start;
          sqlNanos += elapsed;
          maxSqlNanos = Math.max(maxSqlNanos, elapsed);
        }
      };
    }
  }
}
