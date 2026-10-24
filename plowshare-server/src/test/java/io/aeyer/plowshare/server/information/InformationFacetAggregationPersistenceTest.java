package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.DocumentMetadata;
import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.protocol.InformationContent;
import io.aeyer.plowshare.protocol.SchemaCodec;
import io.aeyer.plowshare.protocol.SchemaDefinition;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Clock;
import java.util.ArrayList;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL materialization, JSON expansion, collation, visibility and sentinel-row contracts. */
@Tag("full-db")
@Testcontainers
class InformationFacetAggregationPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource source;
  private JdbcTemplate jdbc;
  private UnitOfWork work;
  private JdbcInformationCatalogueRepository catalogue;
  private JdbcInformationNavigationRepository navigation;
  private final InformationContext own =
      new InformationContext("reader", InformationContext.Selection.personal());

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
    io.aeyer.plowshare.server.testing.AuthorityFixtures.truncate(jdbc, "TRUNCATE admins CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES('reader','fixture'),('other','fixture')");
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    catalogue =
        new JdbcInformationCatalogueRepository(
            jdbc, Clock.systemUTC(), new RecordedInformationEvents());
    navigation = new JdbcInformationNavigationRepository(jdbc, new JdbcProjectMembers(jdbc));
  }

  private Retained retained(String owner, String tags, String automatic, String groups) {
    UUID resource = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,kind,tags,tag_groups,tag_groups_manual)"
            + " VALUES(?,?,?,?,'source',?::jsonb,?::jsonb,true)",
        resource,
        "account:" + owner,
        resource + ".txt",
        owner,
        tags,
        groups);
    return new Retained(resource, revision(resource, 1, automatic));
  }

  private UUID revision(UUID resource, int ordinal, String automatic) {
    UUID revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,created_at,extracted_text,auto_tag,auto_tag_generated)"
            + " VALUES(?,?,?,'Vector notes','text/plain','fixture',1,'2024-10-14T12:00:00Z','PostgreSQL vector search',?::jsonb,true)",
        revision,
        resource,
        ordinal,
        automatic);
    jdbc.update(
        "INSERT INTO information_steps(revision_id,generation,stage,state) VALUES(?,1,'autoTag','ready')",
        revision);
    return revision;
  }

  @Test
  void emptySelectionsStillReturnEveryDimensionAndTheZeroTotal() {
    var revisions = catalogue.facets(own, null, null);
    assertEquals(0, revisions.total());
    assertEquals(Set.copyOf(InformationFacets.NAMES), revisions.facets().keySet());
    assertTrue(revisions.facets().values().stream().allMatch(List::isEmpty));
    assertTrue(revisions.hasMore().values().stream().noneMatch(Boolean::booleanValue));
    assertEquals(new Information.TagGraph(List.of(), false), revisions.tagGraph());

    var heads = navigation.facets(own.withView(true), null, null, null);
    assertEquals(0, heads.total());
    assertEquals(10, heads.facets().size());
    assertTrue(heads.facets().values().stream().allMatch(List::isEmpty));
    assertTrue(heads.hasMore().values().stream().noneMatch(Boolean::booleanValue));
    assertEquals(new Information.TagGraph(List.of(), false), heads.tagGraph());
  }

  @Test
  void duplicatesReadinessAndPrivateRowsKeepTheirExistingAggregateSemantics() {
    var wanted =
        retained(
            "reader",
            "[\"duplicate\",\"duplicate\"]",
            "[\"automatic\",\"automatic\"]",
            "{\"category\":[\"duplicate\",\"automatic\",\"absent\"]}");
    retained("other", "[\"secret\"]", "[\"hidden\"]", "{}");
    var excluded = retained("reader", "[\"excluded\"]", "[]", "{}");
    jdbc.update("UPDATE information_revisions SET excluded=true WHERE id=?", excluded.revision());
    var withdrawn = retained("reader", "[\"withdrawn\"]", "[]", "{}");
    jdbc.update(
        "UPDATE information_revisions SET availability='withdrawn' WHERE id=?",
        withdrawn.revision());

    var revisions = catalogue.facets(own, null, null);
    assertEquals(1, revisions.total());
    assertEquals(
        List.of(new Information.FacetCount("duplicate", 2)), revisions.facets().get("tags"));
    assertEquals(
        List.of(new Information.FacetCount("automatic", 2)), revisions.facets().get("autoTag"));
    assertEquals(
        List.of(
            new Information.TagEdge("category", "automatic", 1),
            new Information.TagEdge("category", "duplicate", 1)),
        revisions.tagGraph().edges());
    var heads = navigation.facets(own.withView(true), null, null, null);
    assertEquals(List.of(new Information.FacetCount("duplicate", 2)), heads.facets().get("tags"));
    assertEquals(
        List.of(new Information.FacetCount("automatic", 2)), heads.facets().get("autoTag"));
    assertEquals(revisions.tagGraph(), heads.tagGraph());

    var filtered =
        own.withFacets(
            InformationInputs.facets(
                Map.of(
                    "tags", List.of("duplicate"),
                    "autoTag", List.of("automatic"),
                    "tagGroup", "category",
                    "when", "2024-10",
                    "search", "vector")));
    assertEquals(revisions, catalogue.facets(filtered, "source", null));
    assertEquals(heads, navigation.facets(filtered.withView(true), null, null, null));
    jdbc.update(
        "UPDATE information_steps SET state='blocked' WHERE revision_id=? AND stage='autoTag'",
        wanted.revision());
    assertTrue(catalogue.facets(own, null, null).facets().get("autoTag").isEmpty());
    assertEquals(0, catalogue.facets(filtered, null, null).total());
  }

  @Test
  void malformedNullTagsRemainRejectedByCatalogueAndOmittedByNavigation() {
    retained("reader", "[\"valid\",null]", "[\"automatic\",null]", "{}");
    // Catalogue historically includes null array elements in its SQL counts. Both its former
    // JSON mapper and the typed mapper reject those malformed values at the FacetCount boundary.
    var legacyRow = new java.util.LinkedHashMap<String, Object>();
    legacyRow.put("value", null);
    legacyRow.put("count", 1L);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new com.fasterxml.jackson.databind.ObjectMapper()
                .convertValue(legacyRow, Information.FacetCount.class));
    assertThrows(IllegalArgumentException.class, () -> catalogue.facets(own, null, null));

    var heads = navigation.facets(own.withView(true), null, null, null);
    assertEquals(1, heads.total());
    assertEquals(List.of(new Information.FacetCount("valid", 1)), heads.facets().get("tags"));
    assertEquals(
        List.of(new Information.FacetCount("automatic", 1)), heads.facets().get("autoTag"));
  }

  @Test
  void navigationHeadsAndRelationshipsDoNotChangeCatalogueRevisionOrPinnedSelection() {
    var old = retained("reader", "[\"selected\"]", "[\"older\"]", "{}");
    revision(old.resource(), 2, "[\"newer\"]");
    assertEquals(2, catalogue.facets(own, null, null).total());
    assertEquals(
        List.of(new Information.FacetCount("newer", 1)),
        navigation.facets(own.withView(true), null, null, null).facets().get("autoTag"));
    assertEquals(
        List.of(new Information.FacetCount("older", 1)),
        catalogue.facets(own, null, List.of(old.revision())).facets().get("autoTag"));
    assertEquals(0, catalogue.facets(own, null, List.of()).total());

    var neighbour = retained("reader", "[\"neighbour\"]", "[]", "{}");
    jdbc.update(
        "INSERT INTO information_relationships(id,source,target,relation,actor) VALUES(?,?,?,'cites','reader')",
        UUID.randomUUID(),
        old.resource(),
        neighbour.resource());
    var outgoing = navigation.facets(own.withView(true), old.resource(), "cites", "outgoing");
    assertEquals(1, outgoing.total());
    assertEquals(
        List.of(new Information.FacetCount("neighbour", 1)), outgoing.facets().get("tags"));
    assertEquals(
        0, navigation.facets(own.withView(true), old.resource(), "different", "outgoing").total());
  }

  private InformationContent.Contract publishedType() {
    return work.inTransaction(
        () -> {
          var types = new JdbcInformationContentTypeRepository(jdbc);
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
  }

  @Test
  void typedDimensionsRespectLegacyOmissionExactContractAndUntypedFilters() {
    var typed = retained("reader", "[\"typed\"]", "[]", "{}");
    retained("reader", "[\"untyped\"]", "[]", "{}");
    var contract = publishedType();
    var metadata = DocumentMetadata.empty();
    new JdbcInformationContentRepository(jdbc, new JdbcInformationContentTypeRepository(jdbc))
        .attach(
            typed.revision(),
            new InformationContent.Binding(contract, SchemaCodec.fingerprint(metadata), metadata));

    var legacy = catalogue.facets(own, null, null);
    assertEquals(1, legacy.total());
    assertFalse(legacy.facets().containsKey("contentType"));
    var supported = catalogue.facets(own.withView(true), null, null);
    assertEquals(2, supported.total());
    assertEquals(
        List.of(new Information.FacetCount(contract.type().toString(), 1)),
        supported.facets().get("contentType"));
    assertEquals(
        List.of(new Information.FacetCount(contract.version().toString(), 1)),
        supported.facets().get("contentTypeVersion"));
    assertEquals(supported, navigation.facets(own.withView(true), null, null, null));
    var selected =
        own.withView(true)
            .withFacets(
                InformationInputs.facets(
                    Map.of("contentTypeVersion", contract.version().toString())));
    assertEquals(1, catalogue.facets(selected, null, null).total());
    assertEquals(1, navigation.facets(selected, null, null, null).total());
    var untyped = own.withView(true).withFacets(InformationInputs.facets(Map.of("untyped", true)));
    assertEquals(
        List.of(new Information.FacetCount("untyped", 1)),
        catalogue.facets(untyped, null, null).facets().get("tags"));
  }

  @Test
  void sentinelLimitsPreserveDatabaseOrderingAndGraphTruncation() {
    for (int document = 0; document < 34; document++) {
      var tags = new ArrayList<String>();
      for (int offset = 0; offset < 32; offset++)
        tags.add("tag%04d".formatted(document * 32 + offset));
      retained(
          "reader",
          InformationJson.json(tags),
          "[]",
          InformationJson.json(Map.of("category", tags)));
    }
    var revisions = catalogue.facets(own.withView(true), null, null);
    var heads = navigation.facets(own.withView(true), null, null, null);
    assertEquals(revisions, heads);
    assertEquals(34, revisions.total());
    assertEquals(100, revisions.facets().get("tags").size());
    assertEquals("tag0000", revisions.facets().get("tags").getFirst().value());
    assertEquals("tag0099", revisions.facets().get("tags").getLast().value());
    assertTrue(revisions.hasMore().get("tags"));
    assertFalse(revisions.hasMore().get("tagGroup"));
    assertEquals(1000, revisions.tagGraph().edges().size());
    assertEquals("tag0000", revisions.tagGraph().edges().getFirst().tag());
    assertEquals("tag0999", revisions.tagGraph().edges().getLast().tag());
    assertTrue(revisions.tagGraph().hasMore());
  }

  @Test
  void theAuthorizedCandidatePredicateRunsOncePerSourceRowAcrossAllAggregates() {
    retained("reader", "[\"first\"]", "[]", "{}");
    retained("reader", "[\"second\"]", "[]", "{}");
    retained("other", "[\"private\"]", "[]", "{}");
    work.inTransaction(
        () -> {
          // A volatile fixture records evaluation without replacing the real visibility predicate.
          // The transaction keeps the temporary objects and repository read on one connection.
          jdbc.execute("CREATE TEMP TABLE facet_evaluations(revision UUID) ON COMMIT DROP");
          jdbc.execute(
              "CREATE FUNCTION pg_temp.count_facet_candidate(revision UUID) RETURNS boolean LANGUAGE plpgsql VOLATILE AS $$"
                  + " BEGIN INSERT INTO facet_evaluations VALUES(revision);"
                  + " RETURN information_readable(revision,'reader','personal',NULL,true); END $$");
          var facets =
              new JdbcInformationFacets(jdbc)
                  .read(
                      new InformationSql.Filter("pg_temp.count_facet_candidate(r.id)", List.of()),
                      JdbcInformationFacets.Candidates.REVISIONS,
                      true);
          assertEquals(2, facets.total());
          assertEquals(
              3, jdbc.queryForObject("SELECT count(*) FROM facet_evaluations", Integer.class));
          return null;
        });
  }
}
