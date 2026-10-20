package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.server.access.ScopedPermissions;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.cache.*;
import io.aeyer.plowshare.server.embedding.*;
import io.aeyer.plowshare.server.information.InformationAccess;
import io.aeyer.plowshare.server.information.InformationContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual bounded vector/lexical SQL and cache freshness inside caller-owned read transactions. */
@Tag("full-db")
@Testcontainers
class SearchCacheDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource source;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @Test
  void discoverySelectionPreservesPagingTotalsAndLivePolicyAndAccountWithdrawal() {
    try (var fixture = new Fixture()) {
      var reader = fixture.reader;
      assertEquals(1, reader.count(null));
      assertEquals(fixture.revision, reader.page(null, 1, 0).getFirst().document().id());
      assertTrue(reader.page(null, 1, 1).isEmpty());
      assertEquals(1, reader.count(fixture.revision.toString()));
      assertEquals(0, reader.count("not in source or title"));

      fixture.jdbc.update(
          "UPDATE information_resource_policies SET version=version+1,audience='QUARANTINED',reason='AUDIENCE',actor=(SELECT id FROM authority_accounts WHERE legacy_handle=?) WHERE resource_id=?",
          fixture.account,
          fixture.resource);
      assertEquals(0, reader.count(null));
      assertTrue(reader.page(null, 1, 0).isEmpty());
      assertEquals(0, reader.coverage().searchable());

      fixture.jdbc.update(
          "UPDATE information_resource_policies SET version=version+1,audience='HOME',reason='AUDIENCE',actor=(SELECT id FROM authority_accounts WHERE legacy_handle=?) WHERE resource_id=?",
          fixture.account,
          fixture.resource);
      assertEquals(1, reader.count(null));
      fixture.jdbc.update("UPDATE admins SET enabled=false WHERE handle=?", fixture.account);
      assertEquals(0, reader.count(null));
      assertTrue(reader.page(null, 1, 0).isEmpty());
    }
  }

  @Test
  void retrievalExistenceUsesTheCapturedSpaceAndCurrentSourceBeforeSkippingInference() {
    try (var fixture = new Fixture()) {
      assertTrue(fixture.reader.hasRetrievable(null));
      assertTrue(fixture.reader.hasRetrievable(fixture.revision));
      assertFalse(fixture.reader.hasRetrievable(UUID.randomUUID()));
      var spaces = new JdbcEmbeddingSpaceRepository(fixture.jdbc);
      var space =
          spaces.register(
              new EmbeddingSpace.Definition(
                  "fixture-prose",
                  "revision",
                  2,
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
                  EmbeddingSearchPolicy.Mode.EXACT, EmbeddingSearchPolicy.Distance.COSINE),
              1);
      fixture.reader.useDualEmbeddings(mock(DualEmbeddings.class));
      var reader = fixture.reader.profiled(profile);
      // The legacy vector exists, but it is not a vector in the captured serving space.
      assertFalse(reader.hasRetrievable(null));
      fixture.jdbc.update(
          "UPDATE chunks SET prose_embedding='[1,0]'::vector,prose_space_id=?,prose_source_revision=embedding_source_revision WHERE paragraph_id IN(SELECT id FROM paragraphs WHERE document_id=?)",
          space.id(),
          fixture.revision);
      assertTrue(reader.hasRetrievable(null));
      assertTrue(reader.hasRetrievable(fixture.revision));
      var other =
          spaces.register(
              new EmbeddingSpace.Definition(
                  "other-model",
                  "revision",
                  2,
                  "",
                  "",
                  "mean",
                  EmbeddingSpace.Normalization.NONE,
                  "none"));
      fixture.jdbc.update(
          "UPDATE chunks SET prose_space_id=? WHERE paragraph_id IN(SELECT id FROM paragraphs WHERE document_id=?)",
          other.id(),
          fixture.revision);
      assertFalse(reader.hasRetrievable(null));
      fixture.jdbc.update(
          "UPDATE chunks SET text=text || ' changed' WHERE paragraph_id IN(SELECT id FROM paragraphs WHERE document_id=?)",
          fixture.revision);
      assertFalse(reader.hasRetrievable(null));
      fixture.jdbc.update(
          "UPDATE chunks SET prose_embedding='[1,0]'::vector,prose_space_id=?,prose_source_revision=embedding_source_revision WHERE paragraph_id IN(SELECT id FROM paragraphs WHERE document_id=?)",
          space.id(),
          fixture.revision);
      assertTrue(reader.hasRetrievable(null));
      fixture.jdbc.update(
          "UPDATE information_revisions SET availability='withdrawn' WHERE id=?", fixture.revision);
      assertFalse(reader.hasRetrievable(null));
      assertFalse(reader.hasRetrievable(fixture.revision));
    }
  }

  @Test
  void warmHybridReadsSkipRankingAndCoverageButObserveCommittedSummaryAndWithdrawal() {
    try (var fixture = new Fixture()) {
      var first = fixture.search();
      assertFalse(first.vectorHits().isEmpty());
      assertFalse(first.lexicalHits().isEmpty());
      assertSame(first, fixture.search());
      assertEquals(2, fixture.jdbc.ranking.get());
      assertEquals(1, fixture.jdbc.coverage.get());

      fixture.jdbc.update(
          "UPDATE documents SET summary='new summary' WHERE id=?", fixture.revision);
      var changed = fixture.search();
      assertEquals("new summary", changed.vectorHits().getFirst().documentSummary());
      assertEquals(4, fixture.jdbc.ranking.get());
      fixture.jdbc.update(
          "UPDATE information_revisions SET availability='withdrawn' WHERE id=?", fixture.revision);
      var withdrawn = fixture.search();
      assertTrue(withdrawn.vectorHits().isEmpty());
      assertTrue(withdrawn.lexicalHits().isEmpty());
      assertEquals(0, withdrawn.coverage().searchable());
    }
  }

  @Test
  void oversizedSelectedTextUsesFullFallbackWithoutChangingRankingOrTruncatingContent() {
    try (var fixture = new Fixture()) {
      String large = "summary ".repeat(2000);
      fixture.jdbc.update("UPDATE documents SET summary=? WHERE id=?", large, fixture.revision);
      var result = fixture.search();
      assertEquals(large, result.vectorHits().getFirst().documentSummary());
      assertEquals(large, result.lexicalHits().getFirst().documentSummary());
      assertEquals(
          3,
          fixture.jdbc.ranking.get(),
          "Bounded vector refusal, then ordinary vector and lexical");
      assertEquals(2, fixture.jdbc.coverage.get());
    }
  }

  @Test
  void inlineLoadsPreserveReadLocksAndTheirOwnCommittedReaderDoesNotRetireTheWarmValue() {
    try (var fixture = new Fixture()) {
      var first =
          fixture.work.inTransaction(
              () -> {
                fixture.jdbc.queryForObject(
                    "SELECT id FROM information_revisions WHERE id=? FOR SHARE",
                    UUID.class,
                    fixture.revision);
                return fixture.search();
              });
      var second =
          fixture.work.inTransaction(
              () -> {
                fixture.jdbc.queryForObject(
                    "SELECT id FROM information_revisions WHERE id=? FOR SHARE",
                    UUID.class,
                    fixture.revision);
                return fixture.search();
              });
      assertSame(first, second);
      assertEquals(2, fixture.jdbc.ranking.get());
      assertEquals(1, fixture.jdbc.coverage.get());
    }
  }

  private static final class CountingJdbc extends JdbcTemplate {
    final AtomicInteger ranking = new AtomicInteger();
    final AtomicInteger coverage = new AtomicInteger();

    CountingJdbc() {
      super(source);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
      if (sql.contains("AS chunk_text")) ranking.incrementAndGet();
      return super.query(sql, mapper, args);
    }

    @Override
    public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... args) {
      if (sql.contains("AS searchable")) coverage.incrementAndGet();
      return super.query(sql, extractor, args);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final CountingJdbc jdbc = new CountingJdbc();
    final UnitOfWork work =
        new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    final UUID revision = UUID.randomUUID();
    final UUID resource = UUID.randomUUID();
    final String account = "search-reader-" + UUID.randomUUID();
    final ProjectionCaches caches;
    final DocumentStore reader;
    final float[] vector = new float[768];

    Fixture() {
      String name = revision + ".txt";
      jdbc.update("INSERT INTO admins(handle,password_hash) VALUES(?,'fixture')", account);
      jdbc.update(
          "INSERT INTO information_resources(id,namespace,source_name,owner_handle,kind) VALUES(?,?,?,?,'source')",
          resource,
          "account:" + account,
          name,
          account);
      jdbc.update(
          "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,text_hash,byte_size,extracted_text) VALUES(?,?,1,'alpha title','text/plain','source','text',20,'alpha beta source')",
          revision,
          resource);
      var writer = new DocumentStore(jdbc, work);
      byte[] bytes = "alpha beta source".getBytes(StandardCharsets.UTF_8);
      var extracted = TextExtraction.extract(name, bytes);
      writer.writeRevision(
          revision,
          name,
          extracted,
          bytes.length,
          account,
          Instant.now(),
          Derivation.derive(extracted, ShippedChunking.SHIPPED));
      vector[0] = 1;
      for (UUID chunk :
          jdbc.queryForList(
              "SELECT c.id FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=?",
              UUID.class,
              revision)) writer.attach(chunk, vector);
      var versions = new JdbcProjectionVersions(jdbc, source, 128);
      var properties =
          new ProjectionCacheProperties(
              16,
              2_000_000,
              4,
              4_000_000,
              16,
              8,
              128,
              64_000,
              Duration.ofSeconds(5),
              Duration.ofSeconds(1),
              Duration.ofSeconds(1),
              128,
              2,
              2);
      caches = new ProjectionCaches(versions, new ReadySignals(), properties);
      reader =
          new DocumentStore(
                  jdbc,
                  work,
                  caches.create(
                      "search",
                      Set.of(
                          ProjectionVersions.Domain.SEARCH, ProjectionVersions.Domain.AUTHORITY)))
              .scoped(
                  new InformationAccess(mock(ScopedPermissions.class)),
                  new InformationContext(account, InformationContext.Selection.personal()));
    }

    DocumentStore.SearchSnapshot search() {
      return reader.searchSnapshot("alpha", vector, 4, DocumentStore.SearchProjection.HYBRID);
    }

    public void close() {
      caches.close();
    }
  }

  private static final class ReadySignals implements ProjectionChangeSignals {
    public Subscription subscribe(Set<ProjectionVersions.Domain> domains, Observer observer) {
      observer.stateChanged(new State(1, true));
      return () -> {};
    }

    public void start() {}

    public boolean healthy() {
      return true;
    }

    public void close() {}
  }
}
