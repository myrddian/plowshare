package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.cache.*;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real bounded SQL, durable versions, isolation and tenant fences with notification hints withheld.
 */
@Tag("full-db")
@Testcontainers
class ManualProjectionDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource source;
  private JdbcTemplate jdbc;
  private UnitOfWork work;
  private final InformationContext own =
      new InformationContext("reader", InformationContext.Selection.personal()).withView(true);

  private record Retained(UUID resource, UUID revision) {}

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
    jdbc.update("INSERT INTO projects(name) VALUES('one'),('different')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','VIEWER' FROM projects WHERE name='one'");
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
  }

  private Retained retained(String owner, String project, String title, String tags) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind,tags)"
            + " VALUES(?,?,?,?,(SELECT id FROM projects WHERE name=?),'source',?::jsonb)",
        resource,
        project == null ? "account:" + owner : "project:" + project,
        resource + ".txt",
        owner,
        project,
        tags);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text)"
            + " VALUES(?,?,1,?,'text/plain','fixture',1,'retained text')",
        revision,
        resource,
        title);
    return new Retained(resource, revision);
  }

  /** Counts owning projection statements, excluding version probes and per-operation admission. */
  private static final class CountingJdbc extends JdbcTemplate {
    final List<String> projections = new ArrayList<>();

    CountingJdbc() {
      super(source);
    }

    private void record(String sql) {
      if (sql.contains("information_resources") && !sql.startsWith("UPDATE")) projections.add(sql);
    }

    @Override
    public List<java.util.Map<String, Object>> queryForList(String sql, Object... arguments) {
      record(sql);
      return super.queryForList(sql, arguments);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
      record(sql);
      return super.query(sql, mapper, arguments);
    }
  }

  /** A recovered listener deliberately delivers no hints: durable per-read checks must suffice. */
  private static ProjectionChangeSignals silentSignals() {
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

  private ProjectionCaches caches(CountingJdbc counted, long maxValueBytes) {
    return new ProjectionCaches(
        new JdbcProjectionVersions(counted, source, 128),
        silentSignals(),
        new ProjectionCacheProperties(
            64,
            8_388_608,
            4,
            16_777_216,
            64,
            16,
            1024,
            maxValueBytes,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            128,
            12,
            4));
  }

  private JdbcInformationCatalogueRepository catalogue(
      CountingJdbc counted, ProjectionCaches caches) {
    var domains = Set.of(ProjectionVersions.Domain.DOCUMENTS, ProjectionVersions.Domain.AUTHORITY);
    return new JdbcInformationCatalogueRepository(
        counted,
        Clock.systemUTC(),
        new JdbcAllowanceRepository(counted),
        ProjectionCache.direct(),
        ProjectionCache.direct(),
        caches.create("manual-revisions", domains),
        caches.create("manual-revision-facets", domains),
        new RecordedInformationEvents());
  }

  private JdbcInformationNavigationRepository navigation(
      CountingJdbc counted, ProjectionCaches caches) {
    var domains = Set.of(ProjectionVersions.Domain.DOCUMENTS, ProjectionVersions.Domain.AUTHORITY);
    return new JdbcInformationNavigationRepository(
        counted,
        new JdbcProjectMembers(counted),
        caches.create("manual-heads", domains),
        caches.create("manual-head-facets", domains));
  }

  private <T> T currentRead(
      JdbcInformationNavigationRepository navigation, java.util.function.Supplier<T> read) {
    return work.inTransaction(
        () -> {
          navigation.snapshotSingleProjection();
          navigation.requireActive("reader");
          return read.get();
        });
  }

  @Test
  void allFourTypedProjectionsReuseWarmValuesAndRefreshWithoutNotifications() {
    var first = retained("reader", null, "First", "[\"tag\"]");
    retained("other", null, "Hidden", "[\"secret\"]");
    var counted = new CountingJdbc();
    try (var caches = caches(counted, 1_048_576)) {
      var catalogue = catalogue(counted, caches);
      var navigation = navigation(counted, caches);
      var revisions = catalogue.list(own, 50, 0, null);
      var revisionFacets = catalogue.facets(own, null, null);
      var heads = currentRead(navigation, () -> navigation.documents(own, null, null, null, 50, 0));
      var headFacets = currentRead(navigation, () -> navigation.facets(own, null, null, null));
      assertEquals(
          List.of(first.revision()), revisions.stream().map(Information.Revision::id).toList());
      assertEquals(1, revisionFacets.total());
      assertEquals(1, heads.documents().size());
      assertEquals(1, headFacets.total());
      assertEquals(4, counted.projections.size());
      assertSame(revisions, catalogue.list(own, 50, 0, null));
      assertSame(revisionFacets, catalogue.facets(own, null, null));
      assertSame(
          heads, currentRead(navigation, () -> navigation.documents(own, null, null, null, 50, 0)));
      assertSame(
          headFacets, currentRead(navigation, () -> navigation.facets(own, null, null, null)));
      assertEquals(
          4, counted.projections.size(), "Warm reads must not repeat owning permission SQL");
      jdbc.update(
          "UPDATE information_resources SET tags='[\"changed\"]'::jsonb WHERE id=?",
          first.resource());
      assertEquals(List.of("changed"), catalogue.list(own, 50, 0, null).getFirst().tags());
      assertEquals(
          "changed", catalogue.facets(own, null, null).facets().get("tags").getFirst().value());
      assertEquals(
          List.of("changed"),
          navigation.documents(own, null, null, null, 50, 0).documents().getFirst().tags());
      assertEquals(
          "changed",
          navigation.facets(own, null, null, null).facets().get("tags").getFirst().value());
      assertEquals(8, counted.projections.size());
    }
  }

  @Test
  void accountSelectionReadCeilingAndRevocationNeverReuseAnotherAuthorizedCollection() {
    retained("reader", null, "Mine", "[]");
    retained("other", null, "Other", "[]");
    var project = retained("other", "one", "Project", "[]");
    var counted = new CountingJdbc();
    var selected =
        new InformationContext("reader", InformationContext.Selection.project("one"))
            .withView(true);
    var other =
        new InformationContext("other", InformationContext.Selection.personal()).withView(true);
    var ceiling = selected.within(KnowledgeReadScope.homeOnly(Home.of("different")));
    try (var caches = caches(counted, 1_048_576)) {
      var catalogue = catalogue(counted, caches);
      var navigation = navigation(counted, caches);
      assertEquals("Mine", catalogue.list(own, 50, 0, null).getFirst().title());
      assertEquals("Other", catalogue.list(other, 50, 0, null).getFirst().title());
      assertEquals(project.revision(), catalogue.list(selected, 50, 0, null).getFirst().id());
      assertTrue(catalogue.list(ceiling, 50, 0, null).isEmpty());
      assertEquals(1, navigation.facets(selected, null, null, null).total());
      assertEquals(0, navigation.facets(ceiling, null, null, null).total());
      jdbc.update("DELETE FROM project_members WHERE handle='reader'");
      assertTrue(catalogue.list(selected, 50, 0, null).isEmpty());
      assertEquals(0, navigation.facets(selected, null, null, null).total());
      jdbc.update("UPDATE admins SET enabled=false WHERE handle='reader'");
      assertTrue(catalogue.list(own, 50, 0, null).isEmpty());
      assertEquals(0, catalogue.facets(own, null, null).total());
    }
  }

  @Test
  void globalPublicationAndWithdrawalChangeOnlyExplicitlyReadableCollections() {
    var privateOther = retained("other", null, "Private then published", "[\"published-tag\"]");
    var global =
        new InformationContext("reader", InformationContext.Selection.globalRead()).withView(true);
    var counted = new CountingJdbc();
    try (var caches = caches(counted, 1_048_576)) {
      var catalogue = catalogue(counted, caches);
      var navigation = navigation(counted, caches);
      assertTrue(catalogue.list(global, 50, 0, null).isEmpty());
      assertEquals(0, catalogue.facets(global, null, null).total());
      assertTrue(navigation.documents(global, null, null, null, 50, 0).documents().isEmpty());
      assertEquals(0, navigation.facets(global, null, null, null).total());
      jdbc.update(
          "UPDATE information_resource_policies SET audience='GLOBAL_READ',version=version+1,reason='AUDIENCE',actor=(SELECT id FROM authority_accounts WHERE legacy_handle='other') WHERE resource_id=?",
          privateOther.resource());
      assertEquals(privateOther.revision(), catalogue.list(global, 50, 0, null).getFirst().id());
      assertEquals(1, catalogue.facets(global, null, null).total());
      assertEquals(
          privateOther.resource(),
          navigation.documents(global, null, null, null, 50, 0).documents().getFirst().resource());
      assertEquals(1, navigation.facets(global, null, null, null).total());
      var bounded = global.within(KnowledgeReadScope.homeOnly(Home.of("different")));
      assertTrue(catalogue.list(bounded, 50, 0, null).isEmpty());
      assertEquals(0, navigation.facets(bounded, null, null, null).total());
      jdbc.update(
          "UPDATE information_revisions SET availability='withdrawn' WHERE id=?",
          privateOther.revision());
      assertTrue(catalogue.list(global, 50, 0, null).isEmpty());
      assertEquals(0, catalogue.facets(global, null, null).total());
      assertTrue(navigation.documents(global, null, null, null, 50, 0).documents().isEmpty());
      assertEquals(0, navigation.facets(global, null, null, null).total());
    }
  }

  @Test
  void relatedMembershipRemovalInvalidatesEvenThoughItsRetainedObligationSurvives() {
    var parent = retained("reader", null, "Parent", "[]");
    var neighbour = retained("reader", null, "Neighbour", "[\"child\"]");
    UUID edge = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_relationships(id,source,target,relation,actor) VALUES(?,?,?,'cites','reader')",
        edge,
        parent.resource(),
        neighbour.resource());
    var counted = new CountingJdbc();
    try (var caches = caches(counted, 1_048_576)) {
      var navigation = navigation(counted, caches);
      var before = navigation.documents(own, parent.resource(), "cites", "outgoing", 50, 0);
      var facets = navigation.facets(own, parent.resource(), "cites", "outgoing");
      assertEquals(1, before.documents().size());
      assertSame(before, navigation.documents(own, parent.resource(), "cites", "outgoing", 50, 0));
      assertSame(facets, navigation.facets(own, parent.resource(), "cites", "outgoing"));
      long obligations =
          jdbc.queryForObject(
              "SELECT count(*) FROM information_reference_obligations WHERE relationship=?",
              Long.class,
              edge);
      assertEquals(1, obligations, "The removal fixture must retain a real obligation");
      jdbc.update("UPDATE information_relationships SET removed=true WHERE id=?", edge);
      assertTrue(
          navigation
              .documents(own, parent.resource(), "cites", "outgoing", 50, 0)
              .documents()
              .isEmpty());
      assertEquals(0, navigation.facets(own, parent.resource(), "cites", "outgoing").total());
      assertEquals(
          obligations,
          jdbc.queryForObject(
              "SELECT count(*) FROM information_reference_obligations WHERE relationship=?",
              Long.class,
              edge));
    }
  }

  @Test
  void dirtyAndHistoricalReadsStayOnTheirOwnSnapshotAndCannotPublishRolledBackData() {
    var first = retained("reader", null, "Before", "[]");
    var counted = new CountingJdbc();
    try (var caches = caches(counted, 1_048_576)) {
      var catalogue = catalogue(counted, caches);
      var navigation = navigation(counted, caches);
      var before = catalogue.list(own, 50, 0, null);
      assertThrows(
          IllegalStateException.class,
          () ->
              work.inTransaction(
                  () -> {
                    jdbc.update(
                        "UPDATE information_revisions SET title='Dirty' WHERE id=?",
                        first.revision());
                    assertEquals("Dirty", catalogue.list(own, 50, 0, null).getFirst().title());
                    throw new IllegalStateException("rollback fixture");
                  }));
      assertEquals("Before", catalogue.list(own, 50, 0, null).getFirst().title());
      work.inTransaction(
          () -> {
            navigation.snapshot();
            navigation.snapshotSingleProjection();
            assertEquals(
                "repeatable read", jdbc.queryForObject("SHOW transaction_isolation", String.class));
            var historical = navigation.documents(own, null, null, null, 50, 0);
            jdbc.queryForObject("SELECT 1", Integer.class);
            // A distinct DataSource identity keeps this commit outside Spring's caller-bound lease.
            new JdbcTemplate(
                    new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .update(
                    "UPDATE information_revisions SET title='After' WHERE id=?", first.revision());
            assertEquals(
                "Before",
                navigation.documents(own, null, null, null, 50, 0).documents().getFirst().title());
            assertNotSame(historical, navigation.documents(own, null, null, null, 50, 0));
            return null;
          });
      assertEquals(
          "After",
          navigation.documents(own, null, null, null, 50, 0).documents().getFirst().title());
      assertEquals(
          "Before", before.getFirst().title(), "Retained DTOs remain immutable historical values");
    }
  }

  @Test
  void guardedOversizeReadsReturnCompleteOrdinaryResultsWithoutRetainingThem() {
    String tag = "x".repeat(64);
    retained("reader", null, "Large " + "x".repeat(6000), "[\"" + tag + "\"]");
    var counted = new CountingJdbc();
    try (var caches = caches(counted, 18_000)) {
      var catalogue = catalogue(counted, caches);
      var navigation = navigation(counted, caches);
      var revisions = catalogue.list(own, 1, 0, null);
      var heads = navigation.documents(own, null, null, null, 1, 0);
      assertEquals(6006, revisions.getFirst().title().length());
      assertEquals(revisions.getFirst().title(), heads.documents().getFirst().title());
      assertNotSame(revisions, catalogue.list(own, 1, 0, null));
      assertNotSame(heads, navigation.documents(own, null, null, null, 1, 0));
      assertEquals(
          8, counted.projections.size(), "Each oversize read has one guard plus one full fallback");
    }
  }
}
