package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.faults.*;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import java.time.Clock;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Tag("full-db")
@Testcontainers
class InformationFacetsTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  JdbcTemplate jdbc;
  InformationCatalogue catalogue;
  InformationAccess access;
  UnitOfWork work;
  final InformationContext own =
      new InformationContext("reader", InformationContext.Selection.personal());

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void fresh() {
    jdbc = new JdbcTemplate(source);
    jdbc.execute("TRUNCATE admins CASCADE");
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('reader','h'),('other','h')");
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    access = new InformationAccess(new JdbcProjectMembers(jdbc));
    catalogue =
        io.aeyer.plowshare.server.information.InformationFixtures.catalogue(
                jdbc, work, access, Clock.systemUTC())
            .withAllowance(() -> 5);
  }

  UUID revision(String owner, String tags, String automatic, String kind) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,kind,tags) VALUES(?,?,?,?,?,CAST(? AS jsonb))",
        resource,
        "account:" + owner,
        resource + ".txt",
        owner,
        kind,
        tags);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,created_at,extracted_text,auto_tag,auto_tag_generated) VALUES(?,?,1,'Vector database notes','text/plain','fixture',1,'2024-10-14T12:00:00Z','PostgreSQL vector search',CAST(? AS jsonb),true)",
        revision,
        resource,
        automatic);
    jdbc.update(
        "INSERT INTO information_steps(revision_id,generation,stage,state) VALUES(?,1,'autoTag','ready')",
        revision);
    if (kind.equals("report"))
      jdbc.update(
          "INSERT INTO information_reports(revision_id,status) VALUES(?,'draft')", revision);
    return revision;
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> facet(Map<String, Object> answer, String name) {
    return (List<Map<String, Object>>) ((Map<String, Object>) answer.get("facets")).get(name);
  }

  @Test
  void intersections_counts_and_paging_use_the_same_readable_candidates() {
    UUID target = revision("reader", "[\"research\",\"databases\"]", "[\"postgresql\"]", "source");
    for (int i = 0; i < 105; i++) revision("reader", "[\"other\"]", "[\"postgresql\"]", "source");
    UUID foreign = revision("other", "[\"research\"]", "[\"secret topic\"]", "source");
    UUID excluded = revision("reader", "[\"research\"]", "[\"hidden topic\"]", "source");
    jdbc.update("UPDATE information_revisions SET excluded=true WHERE id=?", excluded);
    UUID withdrawn = revision("reader", "[\"research\"]", "[\"withdrawn topic\"]", "source");
    jdbc.update("UPDATE information_revisions SET availability='withdrawn' WHERE id=?", withdrawn);
    var context =
        own.withFacets(
            io.aeyer.plowshare.server.information.InformationInputs.facets(
                Map.of(
                    "tags",
                    List.of(" RESEARCH ", "databases"),
                    "autoTag",
                    List.of("PostgreSQL"),
                    "search",
                    "vector",
                    "author",
                    "reader")));
    assertEquals(
        List.of(target),
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(context, 1, 0))
            .stream()
            .map(row -> row.get("id"))
            .toList());
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(context, 1, 1))
            .isEmpty());
    var counts =
        io.aeyer.plowshare.server.information.InformationFixtures.view(
            catalogue.facets(context, null));
    assertEquals(1L, counts.get("total"));
    var contributing = new LinkedHashSet<UUID>();
    assertEquals(
        1L,
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.facetsForRun(context, contributing::add))
            .get("total"));
    assertEquals(Set.of(target), contributing);
    assertEquals(List.of(Map.of("value", "postgresql", "count", 1L)), facet(counts, "autoTag"));
    assertEquals(2, facet(counts, "tags").size());
    assertEquals(
        List.of("research", "databases"),
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(own, target))
            .get("tags"));
    assertFalse(
        io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.facets(own, null))
            .toString()
            .contains("secret topic"));
    assertThrows(
        NotFoundFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(own, foreign)));
    assertEquals(
        1L,
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.facets(
                    context.withFacets(
                        io.aeyer.plowshare.server.information.InformationInputs.facets(
                            Map.of("when", "2024-10", "tags", List.of("research", "databases")))),
                    null))
            .get("total"));
    assertEquals(
        0L,
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.facets(
                    context.withFacets(
                        io.aeyer.plowshare.server.information.InformationInputs.facets(
                            Map.of("when", "2024-11", "tags", List.of("research", "databases")))),
                    null))
            .get("total"));
  }

  @Test
  void user_tags_are_owner_managed_idempotent_and_independent_from_generated_tags() {
    UUID target = revision("reader", "[]", "[\"postgresql\"]", "source"),
        request = UUID.randomUUID();
    catalogue.tags(own, target, List.of(" My Project ", "my project", "Database"), request);
    catalogue.tags(own, target, List.of("database", "my project"), request);
    assertEquals(
        List.of("database", "my project"),
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(own, target))
            .get("tags"));
    assertEquals(
        List.of("postgresql"),
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(own, target))
            .get("autoTag"));
    assertThrows(
        CallerFault.class, () -> catalogue.tags(own, target, List.of("different"), request));
    var other = new InformationContext("other", InformationContext.Selection.personal());
    assertThrows(
        NotFoundFault.class,
        () -> catalogue.tags(other, target, List.of("take over"), UUID.randomUUID()));
    catalogue.tags(own, target, List.of(), UUID.randomUUID());
    assertEquals(
        List.of(),
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(own, target))
            .get("tags"));
  }

  @Test
  void lexical_retrieval_filters_candidates_before_the_hit_limit_and_coverage() {
    var store = new DocumentStore(jdbc, work);
    var chunking =
        new Chunking(new io.aeyer.plowshare.server.llm.tokens.RatioTokenizer(4), 1000, 2000);
    var processor =
        InformationLifecycle.processing(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc,
                java.time.Clock.systemUTC(),
                new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
            work,
            catalogue,
            store,
            mock(EmbeddingClient.class),
            chunking,
            10,
            2,
            () -> null,
            new DocumentsProperties());
    try (var queue =
        new InformationLifecycle(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc,
                Clock.systemUTC(),
                new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
            work,
            catalogue,
            processor,
            new InformationLifecycle.Gates() {})) {
      var wanted =
          catalogue.admit(
              own,
              UUID.randomUUID(),
              "wanted.txt",
              "PostgreSQL vector search".getBytes(),
              "text/plain",
              null);
      var other =
          catalogue.admit(
              own,
              UUID.randomUUID(),
              "other.txt",
              "PostgreSQL vector search".getBytes(),
              "text/plain",
              null);
      for (UUID id : List.of(wanted.revision(), other.revision())) {
        assertTrue(queue.drainOne(id));
        assertTrue(queue.drainOne(id));
      }
      catalogue.tags(own, wanted.revision(), List.of("selected"), UUID.randomUUID());
      float[] vector = new float[768];
      vector[0] = 1;
      for (UUID id : List.of(wanted.revision(), other.revision()))
        for (var chunk : store.unembedded(id)) store.attach(chunk.id(), vector);
      for (UUID id : List.of(wanted.revision(), other.revision())) {
        store.attachDocumentSummary(id, "PostgreSQL vector search");
        store.attachSummaryEmbedding(id, vector);
      }
      var embeddings = mock(EmbeddingClient.class);
      when(embeddings.embed(anyString())).thenReturn(vector);
      var retrieval = new RetrievalService(store, embeddings, "fixture", 768);
      var selected =
          retrieval
              .scoped(
                  access,
                  own.withFacets(
                      io.aeyer.plowshare.server.information.InformationInputs.facets(
                          Map.of("tags", List.of("selected")))))
              .search("PostgreSQL", 1, RetrievalService.Mode.LEXICAL);
      assertEquals(
          List.of(wanted.revision()),
          selected.hits().stream().map(hit -> hit.documentId()).toList());
      assertEquals(1, selected.searchable());
      var ranked =
          retrieval
              .scoped(
                  access,
                  own.withFacets(
                      io.aeyer.plowshare.server.information.InformationInputs.facets(
                          Map.of("tags", List.of("selected")))))
              .rank("PostgreSQL", 1);
      assertEquals(
          List.of(wanted.revision()),
          ranked.documents().stream().map(row -> row.document().id()).toList());
      assertTrue(
          retrieval
              .scoped(
                  access,
                  own.withFacets(
                      io.aeyer.plowshare.server.information.InformationInputs.facets(
                          Map.of("tags", List.of("absent")))))
              .within(wanted.revision(), "PostgreSQL", 1)
              .isEmpty());
      assertFalse(
          retrieval
              .scoped(
                  access,
                  own.withFacets(
                      io.aeyer.plowshare.server.information.InformationInputs.facets(
                          Map.of("tags", List.of("selected")))))
              .within(wanted.revision(), "PostgreSQL", 1)
              .isEmpty());
      assertTrue(
          retrieval
              .scoped(
                  access,
                  own.withFacets(
                      io.aeyer.plowshare.server.information.InformationInputs.facets(
                          Map.of("tags", List.of("absent")))))
              .search("PostgreSQL", 10, RetrievalService.Mode.LEXICAL)
              .hits()
              .isEmpty());
      jdbc.update(
          "UPDATE information_revisions SET document_author='Ada Lovelace',document_author_source='person',document_author_evidence='Written by Ada Lovelace.' WHERE id=?",
          wanted.revision());
      jdbc.update(
          "UPDATE information_steps SET state='ready' WHERE revision_id=? AND stage='autoTag'",
          wanted.revision());
      var authored =
          retrieval.scoped(
              access,
              own.withFacets(
                  io.aeyer.plowshare.server.information.InformationInputs.facets(
                      Map.of("documentAuthor", "Ada Lovelace"))));
      assertEquals(
          List.of(wanted.revision()),
          authored.search("PostgreSQL", 1, RetrievalService.Mode.LEXICAL).hits().stream()
              .map(hit -> hit.documentId())
              .toList());
      assertEquals(
          List.of(wanted.revision()),
          authored.rank("PostgreSQL", 1).documents().stream()
              .map(row -> row.document().id())
              .toList());
    }
  }

  @Test
  void automatic_tagging_is_budgeted_fenced_retryable_and_never_changes_user_tags() {
    var admitted =
        catalogue.admit(
            own,
            UUID.randomUUID(),
            "notes.txt",
            "PostgreSQL vector search".getBytes(),
            "text/plain",
            null);
    UUID id = admitted.revision();
    catalogue.tags(own, id, List.of("my project"), UUID.randomUUID());
    jdbc.update(
        "UPDATE information_revisions SET extracted_text='Written by Ada Lovelace. PostgreSQL vector search' WHERE id=?",
        id);
    jdbc.update(
        "UPDATE information_steps SET state=CASE WHEN stage IN ('extract','derive') THEN 'ready' WHEN stage='autoTag' THEN 'pending' ELSE 'failed' END WHERE revision_id=?",
        id);
    // Explicit metadata rebuilds remain available even when the owner has curated tags.
    catalogue.rebuild(own, id, "autoTag", UUID.randomUUID());
    var store = mock(DocumentStore.class);
    when(store.fenced(any())).thenReturn(store);
    var summariser = mock(Summariser.class);
    when(summariser.forRevision(any(), any(), anyString(), nullable(String.class)))
        .thenReturn(summariser);
    when(summariser.autoTag(anyString(), anyString(), any(), any()))
        .thenAnswer(
            call -> {
              assertTrue(((Budget) call.getArgument(2)).trySpend());
              assertTrue(((String) call.getArgument(1)).contains("Written by Ada Lovelace."));
              return new InformationMetadata(
                  List.of("postgresql", "vector search"),
                  "Ada Lovelace",
                  "person",
                  "Written by Ada Lovelace.");
            });
    var processor =
        InformationLifecycle.processing(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc,
                java.time.Clock.systemUTC(),
                new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
            work,
            catalogue,
            store,
            mock(EmbeddingClient.class),
            null,
            10,
            2,
            () -> summariser,
            new DocumentsProperties());
    try (var queue =
        new InformationLifecycle(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc,
                Clock.systemUTC(),
                new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
            work,
            catalogue,
            processor,
            new InformationLifecycle.Gates() {})) {
      assertTrue(queue.drainOne(id));
      assertTrue(
          queue.drainOne(
              id)); // grouping is independent and fails without a supplied group response
      assertFalse(queue.drainOne(id));
      assertEquals(
          List.of("postgresql", "vector search"),
          io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
              .get("autoTag"));
      assertEquals(
          List.of("my project"),
          io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
              .get("tags"));
      assertEquals(
          "Ada Lovelace",
          io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
              .get("documentAuthor"));
      assertEquals(
          "person",
          io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
              .get("documentAuthorSource"));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT allowance_spent FROM information_revisions WHERE id=?", Integer.class, id));
      catalogue.rebuild(own, id, "autoTag", UUID.randomUUID());
      assertEquals(
          "reader",
          io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
              .get("documentAuthor"));
      assertEquals(
          "account",
          io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
              .get("documentAuthorSource"));
      assertTrue(queue.drainOne(id));
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT allowance_spent FROM information_revisions WHERE id=?", Integer.class, id));
    }
    verify(summariser, times(2)).autoTag(anyString(), anyString(), any(), any());
  }

  @Test
  void document_author_is_separate_from_ownership_and_permission_filtered() {
    UUID person = revision("reader", "[]", "[]", "source"),
        org = revision("reader", "[]", "[]", "source"),
        account = revision("reader", "[]", "[]", "source");
    UUID foreign = revision("other", "[]", "[]", "source");
    jdbc.update(
        "UPDATE information_revisions SET document_author='Ada Lovelace',document_author_source='person',document_author_evidence='Written by Ada Lovelace.' WHERE id=?",
        person);
    jdbc.update(
        "UPDATE information_revisions SET document_author='Analytical Society',document_author_source='organisation',document_author_evidence='Published by Analytical Society.' WHERE id=?",
        org);
    jdbc.update(
        "UPDATE information_revisions SET document_author='Private Person',document_author_source='person',document_author_evidence='Written by Private Person.' WHERE id=?",
        foreign);
    var selected =
        own.withFacets(
            io.aeyer.plowshare.server.information.InformationInputs.facets(
                Map.of("documentAuthor", "Ada Lovelace", "author", "reader")));
    assertEquals(
        List.of(person),
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(selected, 1, 0))
            .stream()
            .map(row -> row.get("id"))
            .toList());
    assertEquals(
        List.of(Map.of("value", "Ada Lovelace", "count", 1L)),
        facet(
            io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.facets(selected, null)),
            "documentAuthor"));
    assertEquals(
        "reader",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(own, person))
            .get("author"));
    assertEquals(
        "organisation",
        io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, org))
            .get("documentAuthorSource"));
    assertEquals(
        "reader",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(own, account))
            .get("documentAuthor"));
    assertEquals(
        "account",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(own, account))
            .get("documentAuthorSource"));
    assertFalse(
        io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.facets(own, null))
            .toString()
            .contains("Private Person"));
    jdbc.update(
        "UPDATE information_steps SET state='blocked' WHERE revision_id=? AND stage='autoTag'",
        person);
    assertEquals(
        "reader",
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(own, person))
            .get("documentAuthor"));
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(selected, 10, 0))
            .isEmpty());
    jdbc.update(
        "UPDATE information_steps SET state='ready' WHERE revision_id=? AND stage='autoTag'",
        person);
    catalogue.availability(own, person, "deleted");
    assertNull(
        jdbc.queryForObject(
            "SELECT document_author_evidence FROM information_revisions WHERE id=?",
            String.class,
            person));
    assertFalse(
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(own, person))
            .containsKey("documentAuthor"));
  }

  @Test
  void blocked_generated_tags_stay_out_of_discovery() {
    UUID id = revision("reader", "[]", "[\"postgresql\"]", "source");
    jdbc.update(
        "UPDATE information_steps SET state='blocked' WHERE revision_id=? AND stage='autoTag'", id);
    assertEquals(
        List.of(),
        io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
            .get("autoTag"));
    assertTrue(
        facet(
                io.aeyer.plowshare.server.information.InformationFixtures.view(
                    catalogue.facets(own, null)),
                "autoTag")
            .isEmpty());
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(
                    own.withFacets(
                        io.aeyer.plowshare.server.information.InformationInputs.facets(
                            Map.of("autoTag", List.of("postgresql")))),
                    10,
                    0))
            .isEmpty());
    jdbc.update(
        "UPDATE information_steps SET state='ready' WHERE revision_id=? AND stage='autoTag'", id);
    assertEquals(
        List.of("postgresql"),
        io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
            .get("autoTag"));
  }

  @Test
  void a_paid_tag_response_survives_a_model_post_gate_denial() {
    UUID id =
        catalogue
            .admit(own, UUID.randomUUID(), "paid.txt", "PostgreSQL".getBytes(), "text/plain", null)
            .revision();
    var definition =
        io.aeyer.plowshare.server.agents.AgentRegistry.of(
                java.nio.file.Path.of("src/main/resources/agents"),
                io.aeyer.plowshare.server.agents.BoundTools.boundByThisServer())
            .get("information_tagger");
    var denied = new java.util.concurrent.atomic.AtomicBoolean(true);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var hooks =
        new io.aeyer.plowshare.server.hooks.Hooks() {
          @Override
          public io.aeyer.plowshare.server.hooks.Gate stagePost(
              io.aeyer.plowshare.server.hooks.HookContext context,
              io.aeyer.plowshare.server.hooks.StageDone done) {
            return denied.get()
                ? new io.aeyer.plowshare.server.hooks.Gate("review pending", List.of(), List.of())
                : io.aeyer.plowshare.server.hooks.Gate.NOTHING;
          }
        };
    String log =
        new ConversationStore(jdbc)
            .log(
                Origin.SUBMISSION,
                io.aeyer.plowshare.protocol.Home.global(),
                "information_tagger",
                null,
                Budget.of(1),
                "reader",
                null)
            .id();
    var stages =
        new InformationModelStages(
                new io.aeyer.plowshare.server.information.JdbcInformationStageRepository(jdbc),
                work,
                catalogue,
                mock(InformationJobs.class),
                hooks,
                io.aeyer.plowshare.server.harness.Harness.NONE)
            .forLease(
                new InformationLifecycle.Lease(
                    id, UUID.randomUUID(), 1, "autoTag", 1, UUID.randomUUID(), "reader", null),
                () -> {});
    java.util.function.Function<String, io.aeyer.plowshare.server.agents.Outcome> paid =
        task -> {
          calls.incrementAndGet();
          return new io.aeyer.plowshare.server.agents.Outcome(
              io.aeyer.plowshare.server.agents.Outcome.Ending.ANSWERED,
              "[\"postgresql\"]",
              1,
              1,
              "");
        };
    assertThrows(
        DocumentStages.Blocked.class,
        () ->
            stages.run(
                definition,
                "retained text",
                log,
                io.aeyer.plowshare.protocol.Home.global(),
                "reader",
                paid));
    denied.set(false);
    var reused =
        stages.run(
            definition,
            "retained text",
            log,
            io.aeyer.plowshare.protocol.Home.global(),
            "reader",
            paid);
    assertEquals(1, calls.get());
    assertEquals(0, reused.modelCalls());
    assertEquals("[\"postgresql\"]", reused.text());
  }

  @Test
  void invalid_or_unknown_facets_fail_closed() {
    for (Object raw :
        List.of(
            Map.of("unknown", "anything"),
            Map.of("kind", "howto"),
            Map.of("when", "2024-13"),
            Map.of("tags", "one"),
            Map.of("autoTag", List.of("")),
            "tags:postgresql"))
      assertThrows(
          CallerFault.class,
          () ->
              own.withFacets(io.aeyer.plowshare.server.information.InformationInputs.facets(raw)));
    assertThrows(CallerFault.class, () -> InformationFacets.tags(List.of("x".repeat(65))));
  }

  UUID sweepCandidate(String name, String retained) {
    UUID id =
        catalogue
            .admit(own, UUID.randomUUID(), name, retained.getBytes(), "text/plain", null)
            .revision();
    jdbc.update("UPDATE information_revisions SET extracted_text=? WHERE id=?", retained, id);
    jdbc.update(
        "UPDATE information_steps SET state=CASE WHEN stage IN ('extract','derive') THEN 'ready' WHEN stage='tagGroups' THEN 'failed' ELSE 'skipped' END WHERE revision_id=?",
        id);
    return id;
  }

  InformationLifecycle tagWorker(
      java.util.function.Function<String, InformationMetadata> metadata,
      java.util.concurrent.atomic.AtomicInteger calls,
      InformationLifecycle.Gates gates) {
    var store = mock(DocumentStore.class);
    when(store.fenced(any())).thenReturn(store);
    var summariser = mock(Summariser.class);
    when(summariser.forRevision(any(), any(), anyString(), nullable(String.class)))
        .thenReturn(summariser);
    when(summariser.autoTag(anyString(), anyString(), any(), any()))
        .thenAnswer(
            call -> {
              String retained = call.getArgument(1);
              assertTrue(((String) call.getArgument(0)).contains(retained));
              assertTrue(((Budget) call.getArgument(2)).trySpend());
              calls.incrementAndGet();
              return metadata.apply(retained);
            });
    var processor =
        InformationLifecycle.processing(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc,
                java.time.Clock.systemUTC(),
                new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
            work,
            catalogue,
            store,
            mock(EmbeddingClient.class),
            null,
            10,
            2,
            () -> summariser,
            new DocumentsProperties());
    return new InformationLifecycle(
        new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
            jdbc,
            Clock.systemUTC(),
            new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
        work,
        catalogue,
        processor,
        gates);
  }

  @Test
  void sweep_reads_old_untagged_documents_then_calls_the_model_once_including_empty_results() {
    UUID tagged = sweepCandidate("older.txt", "Written by Ada Lovelace. PostgreSQL vector search");
    UUID empty = sweepCandidate("no-topic.txt", "Nothing topical here.");
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var queue =
        tagWorker(
            text ->
                text.contains("PostgreSQL")
                    ? new InformationMetadata(
                        List.of("postgresql"), "Ada Lovelace", "person", "Written by Ada Lovelace.")
                    : new InformationMetadata(List.of(), null, null, null),
            calls,
            new InformationLifecycle.Gates() {})) {
      assertEquals(2, queue.sweepUntagged());
      assertEquals(0, calls.get(), "a sweep only queues work");
      assertEquals(0, queue.sweepUntagged(), "pending work must not be queued twice");
      assertTrue(queue.drainOne(tagged));
      assertTrue(queue.drainOne(empty));
      assertEquals(2, calls.get());
      assertEquals(
          List.of("postgresql"),
          io.aeyer.plowshare.server.information.InformationFixtures.view(
                  catalogue.status(own, tagged))
              .get("autoTag"));
      assertEquals(
          List.of(),
          io.aeyer.plowshare.server.information.InformationFixtures.view(
                  catalogue.status(own, tagged))
              .get("tags"));
      assertEquals(
          "Ada Lovelace",
          io.aeyer.plowshare.server.information.InformationFixtures.view(
                  catalogue.status(own, tagged))
              .get("documentAuthor"));
      assertEquals(
          List.of(),
          io.aeyer.plowshare.server.information.InformationFixtures.view(
                  catalogue.status(own, empty))
              .get("autoTag"));
      assertEquals(
          true,
          io.aeyer.plowshare.server.information.InformationFixtures.view(
                  catalogue.status(own, empty))
              .get("auto_tag_generated"));
      assertEquals(0, queue.sweepUntagged());
      assertFalse(queue.drainOne());
      for (UUID id : List.of(tagged, empty))
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT allowance_spent FROM information_revisions WHERE id=?", Integer.class, id));
    }
  }

  @Test
  void tagged_documents_are_skipped_without_a_model_call_and_clearing_user_tags_admits_them() {
    UUID id = sweepCandidate("curated.txt", "PostgreSQL vector search");
    catalogue.tags(own, id, List.of("reviewed"), UUID.randomUUID());
    jdbc.update(
        "UPDATE information_steps SET state='pending' WHERE revision_id=? AND stage='autoTag'", id);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var queue =
        tagWorker(
            text -> new InformationMetadata(List.of("postgresql"), null, null, null),
            calls,
            new InformationLifecycle.Gates() {})) {
      assertEquals(0, queue.sweepUntagged());
      assertTrue(queue.drainOne(id));
      assertEquals(
          "skipped",
          jdbc.queryForObject(
              "SELECT state FROM information_steps WHERE revision_id=? AND stage='autoTag'",
              String.class,
              id));
      assertEquals(0, calls.get());
      assertEquals(
          List.of("reviewed"),
          io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
              .get("tags"));
      catalogue.tags(own, id, List.of(), UUID.randomUUID());
      assertEquals(1, queue.sweepUntagged());
      assertTrue(queue.drainOne(id));
      assertEquals(1, calls.get());
      assertEquals(0, queue.sweepUntagged());
    }
  }

  @Test
  void eligibility_is_rechecked_after_stage_hooks_before_any_paid_tagging() {
    UUID id = sweepCandidate("changed.txt", "PostgreSQL vector search");
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var gates =
        new InformationLifecycle.Gates() {
          @Override
          public io.aeyer.plowshare.server.hooks.Gate before(InformationLifecycle.Lease lease) {
            catalogue.tags(own, id, List.of("just curated"), UUID.randomUUID());
            return io.aeyer.plowshare.server.hooks.Gate.NOTHING;
          }
        };
    try (var queue =
        tagWorker(
            text -> new InformationMetadata(List.of("postgresql"), null, null, null),
            calls,
            gates)) {
      assertEquals(1, queue.sweepUntagged());
      assertTrue(queue.drainOne(id));
      assertEquals(0, calls.get());
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT allowance_spent FROM information_revisions WHERE id=?", Integer.class, id));
      assertEquals(
          List.of("just curated"),
          io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
              .get("tags"));
    }
  }

  @Test
  void sweep_avoids_unreadable_unavailable_unprepared_exhausted_and_failed_documents() {
    UUID eligible = sweepCandidate("eligible.txt", "PostgreSQL");
    UUID excluded = sweepCandidate("excluded.txt", "PostgreSQL");
    jdbc.update("UPDATE information_revisions SET excluded=true WHERE id=?", excluded);
    UUID withdrawn = sweepCandidate("withdrawn.txt", "PostgreSQL");
    jdbc.update("UPDATE information_revisions SET availability='withdrawn' WHERE id=?", withdrawn);
    UUID exhausted = sweepCandidate("exhausted.txt", "PostgreSQL");
    jdbc.update(
        "UPDATE information_revisions SET allowance_spent=allowance_total WHERE id=?", exhausted);
    UUID blank = sweepCandidate("blank.txt", " ");
    UUID unprepared = sweepCandidate("unprepared.txt", "PostgreSQL");
    jdbc.update(
        "UPDATE information_steps SET state='failed' WHERE revision_id=? AND stage='derive'",
        unprepared);
    UUID failed = sweepCandidate("failed.txt", "PostgreSQL");
    jdbc.update(
        "UPDATE information_steps SET state='failed' WHERE revision_id=? AND stage='autoTag'",
        failed);
    UUID blocked = sweepCandidate("blocked.txt", "PostgreSQL");
    jdbc.update(
        "UPDATE information_steps SET state='blocked' WHERE revision_id=? AND stage='autoTag'",
        blocked);
    UUID tagged = sweepCandidate("tagged.txt", "PostgreSQL");
    jdbc.update(
        "UPDATE information_revisions SET auto_tag='[\"existing\"]'::jsonb WHERE id=?", tagged);
    UUID dependency = sweepCandidate("restricted-report.txt", "PostgreSQL"),
        privateInput = revision("other", "[]", "[]", "source");
    jdbc.update("INSERT INTO information_inputs VALUES(?,?)", dependency, privateInput);
    UUID ownerless = sweepCandidate("ownerless.txt", "PostgreSQL");
    jdbc.update(
        "UPDATE information_resources SET owner_handle=NULL WHERE id=(SELECT resource_id FROM information_revisions WHERE id=?)",
        ownerless);
    try (var queue =
        new InformationLifecycle(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc,
                Clock.systemUTC(),
                new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
            work,
            catalogue,
            (lease, cancelled, fence) -> {},
            new InformationLifecycle.Gates() {})) {
      assertEquals(1, queue.sweepUntagged());
      assertEquals(eligible, queue.claim().revision());
      assertEquals(0, queue.sweepUntagged());
    }
  }

  @Test
  void each_sweep_queues_at_most_one_hundred_documents_without_duplicate_work() {
    for (int i = 0; i < 105; i++) sweepCandidate("batch-" + i + ".txt", "PostgreSQL");
    try (var queue =
        new InformationLifecycle(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc,
                Clock.systemUTC(),
                new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
            work,
            catalogue,
            (lease, cancelled, fence) -> {},
            new InformationLifecycle.Gates() {})) {
      assertEquals(100, queue.sweepUntagged());
      assertEquals(5, queue.sweepUntagged());
      assertEquals(0, queue.sweepUntagged());
      assertEquals(
          105,
          jdbc.queryForObject(
              "SELECT count(*) FROM information_steps WHERE stage='autoTag' AND state='pending'",
              Integer.class));
    }
  }

  @Test
  void starting_the_worker_automatically_sweeps_and_processes_retained_untagged_documents()
      throws Exception {
    UUID id = sweepCandidate("startup.txt", "PostgreSQL vector search");
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var queue =
        tagWorker(
            text -> new InformationMetadata(List.of("postgresql"), null, null, null),
            calls,
            new InformationLifecycle.Gates() {})) {
      queue.start();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (!"ready"
              .equals(
                  jdbc.queryForObject(
                      "SELECT state FROM information_steps WHERE revision_id=? AND stage='autoTag'",
                      String.class,
                      id))
          && System.nanoTime() < deadline) Thread.sleep(20);
      assertEquals(
          List.of("postgresql"),
          io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
              .get("autoTag"));
      assertEquals(1, calls.get());
    }
  }

  void automaticGroups(UUID id, Map<String, List<String>> groups) {
    jdbc.update(
        "INSERT INTO information_steps(revision_id,generation,stage,state) VALUES(?,1,'tagGroups','ready') ON CONFLICT(revision_id,generation,stage) DO UPDATE SET state='ready'",
        id);
    jdbc.update(
        "UPDATE information_revisions r SET auto_tag_groups=CAST(? AS jsonb),tag_groups_input_tags="
            + InformationFacetSql.visibleTags("r", "q")
            + ",tag_groups_generated=true FROM information_resources q WHERE q.id=r.resource_id AND r.id=?",
        io.aeyer.plowshare.server.information.InformationJson.json(groups),
        id);
  }

  @Test
  void group_graph_and_filters_share_live_permissions_and_revision_counts() {
    UUID wanted = revision("reader", "[\"research\"]", "[\"postgresql\",\"sqlite\"]", "source");
    UUID second = revision("reader", "[]", "[\"postgresql\"]", "source");
    UUID privateId = revision("other", "[]", "[\"secret\"]", "source");
    automaticGroups(
        wanted,
        Map.of("databases", List.of("postgresql", "sqlite"), "software", List.of("postgresql")));
    automaticGroups(second, Map.of("databases", List.of("postgresql")));
    automaticGroups(privateId, Map.of("private group", List.of("secret")));
    var all =
        io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.facets(own, null));
    assertFalse(all.toString().contains("secret"));
    assertEquals(
        List.of(
            Map.of("value", "databases", "count", 2L), Map.of("value", "software", "count", 1L)),
        facet(all, "tagGroup"));
    var edges = (List<?>) ((Map<?, ?>) all.get("tagGraph")).get("edges");
    assertTrue(edges.contains(Map.of("group", "databases", "tag", "postgresql", "count", 2L)));
    assertTrue(edges.contains(Map.of("group", "databases", "tag", "sqlite", "count", 1L)));
    var selected =
        own.withFacets(
            io.aeyer.plowshare.server.information.InformationInputs.facets(
                Map.of("tagGroup", "Software", "autoTag", List.of("postgresql"))));
    assertEquals(
        List.of(wanted),
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(selected, 1, 0))
            .stream()
            .map(row -> row.get("id"))
            .toList());
    assertEquals(
        1L,
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.facets(selected, null))
            .get("total"));
    var inherited = new HashSet<UUID>();
    io.aeyer.plowshare.server.information.InformationFixtures.view(
        catalogue.facetsForRun(selected, inherited::add));
    assertEquals(Set.of(wanted), inherited);
    catalogue.availability(own, wanted, "withdrawn");
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(selected, 10, 0))
            .isEmpty());
    assertFalse(
        io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.facets(own, null))
            .toString()
            .contains("software"));
  }

  @Test
  void manual_groups_replace_automatic_groups_and_survive_revisions_until_released() {
    UUID id =
        catalogue
            .admit(
                own,
                UUID.randomUUID(),
                "groups.txt",
                "PostgreSQL notes".getBytes(),
                "text/plain",
                null)
            .revision();
    catalogue.tags(own, id, List.of("postgresql", "sqlite"), UUID.randomUUID());
    automaticGroups(id, Map.of("databases", List.of("postgresql", "sqlite")));
    UUID request = UUID.randomUUID();
    var override = Map.of("My Work", List.of("PostgreSQL", "sqlite"));
    catalogue.tagGroups(own, id, override, request);
    catalogue.tagGroups(own, id, override, request);
    assertEquals(
        Map.of("my work", List.of("postgresql", "sqlite")),
        io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
            .get("tagGroups"));
    assertEquals(
        "manual",
        io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
            .get("tagGroupsSource"));
    assertThrows(
        CallerFault.class,
        () -> catalogue.tagGroups(own, id, Map.of("other", List.of("postgresql")), request));
    assertThrows(
        CallerFault.class,
        () ->
            catalogue.tagGroups(own, id, Map.of("unknown", List.of("absent")), UUID.randomUUID()));
    assertThrows(
        NotFoundFault.class,
        () ->
            catalogue.tagGroups(
                new InformationContext("other", InformationContext.Selection.personal()),
                id,
                override,
                UUID.randomUUID()));
    var newer =
        catalogue.admit(
            own,
            UUID.randomUUID(),
            "groups.txt",
            "New PostgreSQL notes".getBytes(),
            "text/plain",
            null);
    assertEquals(
        Map.of("my work", List.of("postgresql", "sqlite")),
        io.aeyer.plowshare.server.information.InformationFixtures.view(
                catalogue.status(own, newer.revision()))
            .get("tagGroups"));
    catalogue.tagGroups(own, id, Map.of(), UUID.randomUUID());
    assertEquals(
        Map.of(),
        io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
            .get("tagGroups"));
    catalogue.tagGroups(own, id, null, UUID.randomUUID());
    assertEquals(
        "automatic",
        io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
            .get("tagGroupsSource"));
    assertEquals(
        Map.of("databases", List.of("postgresql", "sqlite")),
        io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
            .get("tagGroups"));
  }

  @Test
  void owner_tag_edits_hide_stale_model_groups_and_prune_manual_membership() {
    UUID id = revision("reader", "[\"postgresql\",\"sqlite\"]", "[]", "source");
    automaticGroups(id, Map.of("databases", List.of("postgresql", "sqlite")));
    catalogue.tags(own, id, List.of("postgresql"), UUID.randomUUID());
    assertEquals(
        Map.of(),
        io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
            .get("tagGroups"));
    catalogue.tagGroups(own, id, Map.of("chosen", List.of("postgresql")), UUID.randomUUID());
    catalogue.tags(own, id, List.of(), UUID.randomUUID());
    assertEquals(
        Map.of(),
        io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
            .get("tagGroups"));
  }

  InformationLifecycle groupingWorker(Summariser summariser) {
    var store = mock(DocumentStore.class);
    when(store.fenced(any())).thenReturn(store);
    when(summariser.forRevision(any(), any(), anyString(), nullable(String.class)))
        .thenReturn(summariser);
    return new InformationLifecycle(
        new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
            jdbc,
            Clock.systemUTC(),
            new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
        work,
        catalogue,
        InformationLifecycle.processing(
            new io.aeyer.plowshare.server.information.JdbcInformationProcessingRepository(
                jdbc,
                java.time.Clock.systemUTC(),
                new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)),
            work,
            catalogue,
            store,
            mock(EmbeddingClient.class),
            null,
            10,
            2,
            () -> summariser,
            new DocumentsProperties()),
        new InformationLifecycle.Gates() {});
  }

  @Test
  void tagging_and_grouping_share_one_paid_response_for_new_untagged_sources() {
    UUID id = sweepCandidate("one-call.txt", "PostgreSQL notes");
    jdbc.update("UPDATE information_revisions SET allowance_total=1 WHERE id=?", id);
    jdbc.update(
        "UPDATE information_steps SET state='skipped' WHERE revision_id=? AND stage='tagGroups'",
        id);
    var summariser = mock(Summariser.class);
    when(summariser.autoTag(anyString(), anyString(), any(), any()))
        .thenAnswer(
            call -> {
              assertTrue(((Budget) call.getArgument(2)).trySpend());
              return new InformationMetadata(
                  List.of("postgresql"),
                  null,
                  null,
                  null,
                  Map.of("databases", List.of("postgresql")));
            });
    try (var queue = groupingWorker(summariser)) {
      assertEquals(1, queue.sweepUntagged());
      assertTrue(queue.drainOne(id));
      assertEquals(1, queue.sweepTagGroups());
      assertTrue(queue.drainOne(id));
      assertEquals(
          Map.of("databases", List.of("postgresql")),
          io.aeyer.plowshare.server.information.InformationFixtures.view(catalogue.status(own, id))
              .get("tagGroups"));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT allowance_spent FROM information_revisions WHERE id=?", Integer.class, id));
      assertEquals(0, queue.sweepTagGroups());
    }
    verify(summariser, never()).tagGroups(anyString(), anyList(), any(), any());
  }

  @Test
  void sweep_groups_existing_tags_once_and_reclassifies_after_owner_edits() {
    UUID id = sweepCandidate("curated-groups.txt", "PostgreSQL notes");
    catalogue.tags(own, id, List.of("postgresql"), UUID.randomUUID());
    jdbc.update(
        "UPDATE information_steps SET state='skipped' WHERE revision_id=? AND stage='tagGroups'",
        id);
    var summariser = mock(Summariser.class);
    when(summariser.tagGroups(anyString(), anyList(), any(), any()))
        .thenAnswer(
            call -> {
              assertTrue(((Budget) call.getArgument(2)).trySpend());
              return Map.of();
            });
    try (var queue = groupingWorker(summariser)) {
      assertEquals(1, queue.sweepTagGroups());
      assertEquals(0, queue.sweepTagGroups());
      assertTrue(queue.drainOne(id));
      assertEquals(0, queue.sweepTagGroups());
      catalogue.tags(own, id, List.of("postgresql", "sqlite"), UUID.randomUUID());
      assertEquals(1, queue.sweepTagGroups());
      assertTrue(queue.drainOne(id));
      assertEquals(0, queue.sweepTagGroups());
      catalogue.tagGroups(own, id, Map.of(), UUID.randomUUID());
      catalogue.tags(own, id, List.of("sqlite"), UUID.randomUUID());
      assertEquals(0, queue.sweepTagGroups());
      catalogue.tagGroups(own, id, null, UUID.randomUUID());
      assertEquals(1, queue.sweepTagGroups());
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT allowance_spent FROM information_revisions WHERE id=?", Integer.class, id));
    }
    verify(summariser, times(2)).tagGroups(anyString(), anyList(), any(), any());
    verify(summariser, never()).autoTag(anyString(), anyString(), any(), any());
  }
}
