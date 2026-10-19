package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.DocumentMetadata;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.InformationContent;
import io.aeyer.plowshare.protocol.SchemaCodec;
import io.aeyer.plowshare.protocol.SchemaDefinition;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.cache.*;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual PostgreSQL row codecs and equivalence of the live authorization predicates. */
@Tag("full-db")
@Testcontainers
class InformationCatalogueReadPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  JdbcTemplate jdbc;
  JdbcInformationCatalogueRepository repository;
  UnitOfWork work;
  final InformationContext own =
      new InformationContext("reader", InformationContext.Selection.personal()).withView(true);

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(source);
    io.aeyer.plowshare.server.testing.AuthorityFixtures.truncate(
        jdbc, "TRUNCATE admins,projects CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES('reader','fixture'),('other','fixture')");
    jdbc.update("INSERT INTO projects(name) VALUES('one')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','VIEWER' FROM projects WHERE name='one'");
    repository = new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC());
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
  }

  UUID revision(String owner, String project, String policy, String text) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind,tags) VALUES(?,?,?,?,(SELECT id FROM projects WHERE name=?),'source','[\"tag\"]'::jsonb)",
        resource,
        project == null ? "account:" + owner : "project:" + project,
        resource + ".txt",
        owner,
        project);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,text_hash,byte_size,created_at,extracted_text,processing_policy) VALUES(?,?,1,'Retained title','text/plain','source-hash',?,20,now(),?,?)",
        revision,
        resource,
        text == null ? null : "extraction-hash",
        text,
        policy);
    return revision;
  }

  void equivalent(InformationContext context, UUID revision, boolean expected) {
    assertEquals(expected, repository.readable(context, revision));
    var metadata = repository.authorizedMetadata(context, revision);
    var text = repository.authorizedText(context, revision);
    assertEquals(expected, metadata.isPresent());
    assertEquals(expected, text.isPresent());
    if (expected) {
      assertEquals(context, metadata.orElseThrow().context());
      assertEquals(repository.metadata(revision), metadata.orElseThrow().revision());
      assertEquals(context, text.orElseThrow().context());
      assertEquals(revision, text.orElseThrow().revision());
    }
  }

  @Test
  void
      snapshotsMapActualHashesClassificationAndNullableExtractionWithoutTreatingGenerationAsMetadataVersion() {
    UUID retained = revision("reader", null, "automatic", "a🔎retained text");
    var text = repository.authorizedText(own, retained).orElseThrow();
    assertEquals("source-hash", text.contentHash());
    assertEquals("extraction-hash", text.textHash());
    assertEquals("a🔎retained text", text.text());
    assertEquals("document", text.documentType());
    assertEquals("text", text.documentSubtype());
    assertEquals(1, text.processingGeneration());
    var before = repository.authorizedMetadata(own, retained).orElseThrow().revision();
    jdbc.update(
        "UPDATE information_resources SET tags='[\"changed\"]'::jsonb WHERE id=?", text.resource());
    var after = repository.authorizedMetadata(own, retained).orElseThrow().revision();
    assertEquals(before.generation(), after.generation());
    assertEquals(List.of("tag"), before.tags());
    assertEquals(List.of("changed"), after.tags());
    assertThrows(UnsupportedOperationException.class, () -> after.tags().add("mutate"));
    UUID pending = revision("reader", null, "automatic", null);
    var unextracted = repository.authorizedText(own, pending).orElseThrow();
    assertNull(unextracted.text());
    assertNull(unextracted.textHash());
    equivalent(own, retained, true);
  }

  @Test
  void authorizedProjectionsMatchCurrentReadabilityForPrivateProjectTypedAndUnavailableRows() {
    UUID personal = revision("reader", null, "automatic", "retained");
    UUID privateOther = revision("other", null, "automatic", "private");
    UUID project = revision("other", "one", "automatic", "project");
    UUID explicit = revision("reader", null, "explicit_only", "explicit");
    equivalent(own, personal, true);
    equivalent(own, privateOther, false);
    equivalent(own, explicit, true);
    equivalent(own.withView(false), explicit, false);
    equivalent(own, UUID.randomUUID(), false);
    var selected =
        new InformationContext("reader", InformationContext.Selection.project("one"))
            .withView(true);
    equivalent(selected, project, true);
    equivalent(selected.within(KnowledgeReadScope.homeOnly(Home.of("different"))), project, false);
    jdbc.update("DELETE FROM project_members WHERE handle='reader'");
    equivalent(selected, project, false);
    jdbc.update("UPDATE information_revisions SET availability='withdrawn' WHERE id=?", personal);
    equivalent(own, personal, false);
  }

  @Test
  void typedBindingUsesTheExistingLegacyReadFenceAndMapsImmutableTypeIdentity() {
    UUID retained = revision("reader", null, "automatic", "typed");
    var types = new JdbcInformationContentTypeRepository(jdbc);
    InformationContent.Contract contract =
        work.inTransaction(
            () -> {
              var draft =
                  types.create(
                      "account:reader",
                      "reader",
                      null,
                      null,
                      "note",
                      "Note",
                      "",
                      SchemaDefinition.fromWire(
                          Map.of("type", "object", "additionalProperties", false)),
                      DocumentMetadata.empty());
              return types
                  .transition(
                      "account:reader", draft.contract().version(), draft.controlRevision(), true)
                  .contract();
            });
    var metadata = DocumentMetadata.empty();
    new JdbcInformationContentRepository(jdbc, types)
        .attach(
            retained,
            new InformationContent.Binding(contract, SchemaCodec.fingerprint(metadata), metadata));
    equivalent(own.withView(false), retained, false);
    equivalent(own, retained, true);
    assertEquals(
        contract.version(),
        repository
            .authorizedMetadata(own, retained)
            .orElseThrow()
            .revision()
            .contentType()
            .version());
  }

  private static final class CountingJdbc extends JdbcTemplate {
    int metadataLoads, textLoads, metadataAuthorization, textSizeReads;
    Runnable afterTextSize = () -> {};

    CountingJdbc() {
      super(source);
    }

    @Override
    public List<Map<String, Object>> queryForList(String sql, Object... args) {
      if (sql.startsWith("WITH projected") || sql.startsWith("SELECT r.id,r.resource_id,r.ordinal"))
        metadataLoads++;
      return super.queryForList(sql, args);
    }

    @Override
    public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
      if (sql.startsWith("SELECT 1 FROM information_revision_allowances")) metadataAuthorization++;
      var rows = super.queryForList(sql, type, args);
      if (sql.startsWith("SELECT COALESCE(octet_length(r.extracted_text)")) {
        textSizeReads++;
        var callback = afterTextSize;
        afterTextSize = () -> {};
        callback.run();
      }
      return rows;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
      if (sql.startsWith("SELECT r.id,r.resource_id,r.document_type")) textLoads++;
      return super.query(sql, mapper, args);
    }
  }

  private static ProjectionCacheProperties cacheProperties() {
    return new ProjectionCacheProperties(
        256,
        33_554_432,
        8,
        67_108_864,
        256,
        64,
        8192,
        262_144,
        Duration.ofSeconds(5),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        128,
        8,
        4);
  }

  private static ProjectionChangeSignals readySignals() {
    return new ProjectionChangeSignals() {
      public Subscription subscribe(Set<ProjectionVersions.Domain> domains, Observer observer) {
        observer.stateChanged(new State(1, true));
        return () -> {};
      }

      public void start() {}

      public boolean healthy() {
        return true;
      }

      public void close() {}
    };
  }

  private ProjectionCaches caches(CountingJdbc counted) {
    return new ProjectionCaches(
        new JdbcProjectionVersions(counted, source, 128), readySignals(), cacheProperties());
  }

  private JdbcInformationCatalogueRepository cached(CountingJdbc counted, ProjectionCaches caches) {
    var domains = Set.of(ProjectionVersions.Domain.DOCUMENTS, ProjectionVersions.Domain.AUTHORITY);
    return new JdbcInformationCatalogueRepository(
        counted,
        Clock.systemUTC(),
        new JdbcAllowanceRepository(counted),
        caches.create("metadata-fixture", domains),
        caches.create("extraction-fixture", domains));
  }

  @Test
  void configuredCachesSkipWarmHydrationButKeepCurrentAuthorizationAndNullableText() {
    UUID retained = revision("reader", null, "automatic", null);
    var counted = new CountingJdbc();
    try (var caches = caches(counted)) {
      var configured = cached(counted, caches);
      var metadata = configured.authorizedMetadata(own, retained);
      var text = configured.authorizedText(own, retained);
      assertNull(text.orElseThrow().text());
      assertNull(text.orElseThrow().textHash());
      assertSame(metadata, configured.authorizedMetadata(own, retained));
      assertSame(text, configured.authorizedText(own, retained));
      assertEquals(
          1, counted.metadataLoads, "Warm metadata must not rerun its materialized projection");
      assertEquals(1, counted.textLoads);
      assertEquals(2, counted.metadataAuthorization);
      assertEquals(2, counted.textSizeReads);
      jdbc.update("UPDATE information_revisions SET availability='withdrawn' WHERE id=?", retained);
      assertTrue(configured.authorizedMetadata(own, retained).isEmpty());
      assertTrue(configured.authorizedText(own, retained).isEmpty());
      assertEquals(1, counted.metadataLoads);
      assertEquals(1, counted.textLoads);
    }
  }

  @Test
  void configuredMetadataSizeGuardAndTextGrowthPreserveCompleteUncachedResults() {
    UUID retained = revision("reader", null, "automatic", null);
    String title = "x".repeat(12_000), extraction = "a🔎".repeat(40_000);
    jdbc.update("UPDATE information_revisions SET title=? WHERE id=?", title, retained);
    var counted = new CountingJdbc();
    try (var caches = caches(counted)) {
      var configured = cached(counted, caches);
      assertEquals(
          title, configured.authorizedMetadata(own, retained).orElseThrow().revision().title());
      assertEquals(
          2, counted.metadataLoads, "Guard refusal must execute exactly one full fallback");
      counted.afterTextSize =
          () ->
              jdbc.update(
                  "UPDATE information_revisions SET extracted_text=?,text_hash='extraction-hash' WHERE id=?",
                  extraction,
                  retained);
      assertEquals(extraction, configured.authorizedText(own, retained).orElseThrow().text());
      assertEquals(
          2,
          counted.textLoads,
          "Growth after scalar preflight must use guarded read then full fallback");
      assertEquals(extraction, configured.authorizedText(own, retained).orElseThrow().text());
      assertEquals(3, counted.textLoads, "Known oversize extraction must skip the bounded loader");
    }
  }

  @Test
  void dirtyMetadataReadsStayOnTheCallerTransactionAndCannotPublishAfterRollback() {
    UUID retained = revision("reader", null, "automatic", "retained");
    UUID resource = repository.authorizedText(own, retained).orElseThrow().resource();
    var counted = new CountingJdbc();
    try (var caches = caches(counted)) {
      var configured = cached(counted, caches);
      assertEquals(
          List.of("tag"),
          configured.authorizedMetadata(own, retained).orElseThrow().revision().tags());
      assertThrows(
          IllegalStateException.class,
          () ->
              work.inTransaction(
                  () -> {
                    jdbc.update(
                        "UPDATE information_resources SET tags='[\"uncommitted\"]'::jsonb WHERE id=?",
                        resource);
                    assertEquals(
                        List.of("uncommitted"),
                        configured
                            .authorizedMetadata(own, retained)
                            .orElseThrow()
                            .revision()
                            .tags());
                    throw new IllegalStateException("fixture rollback");
                  }));
      assertEquals(
          List.of("tag"),
          configured.authorizedMetadata(own, retained).orElseThrow().revision().tags());
    }
  }
}
