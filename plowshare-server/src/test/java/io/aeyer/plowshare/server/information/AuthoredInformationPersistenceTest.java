package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.archive.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Actual immutable bindings, projection row remapping, rollback and persistent worker fences. */
@Tag("full-db")
@Testcontainers
class AuthoredInformationPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  JdbcTemplate jdbc;
  UnitOfWork work;
  JdbcInformationContentTypeRepository types;
  JdbcInformationContentRepository content;
  JdbcInformationProjectionRepository projections;
  JdbcInformationCatalogueRepository catalogue;
  final InformationContext own =
      new InformationContext("author", InformationContext.Selection.personal()).withView(true);
  final Map<String, String> configuration =
      InformationCatalogue.STAGES.stream()
          .collect(
              java.util.stream.Collectors.toMap(
                  stage -> stage,
                  stage -> InformationCatalogue.sha256(stage.getBytes(StandardCharsets.UTF_8))));

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
        "INSERT INTO admins(handle,password_hash) VALUES('author','fixture'),('other','fixture')");
    jdbc.update("UPDATE embedding_slots SET active_space_id=NULL,target_space_id=NULL");
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    types = new JdbcInformationContentTypeRepository(jdbc);
    content = new JdbcInformationContentRepository(jdbc, types);
    projections = new JdbcInformationProjectionRepository(jdbc, content);
    catalogue = new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC());
  }

  InformationContent.Contract published() {
    return work.inTransaction(
        () -> {
          var draft =
              types.create(
                  "account:author",
                  "author",
                  null,
                  null,
                  "incident",
                  "Incident",
                  "",
                  SchemaDefinition.fromWire(
                      Map.of(
                          "type",
                          "object",
                          "properties",
                          Map.of(
                              "severity", Map.of("type", "string", "enum", List.of("low", "high"))),
                          "required",
                          List.of("severity"),
                          "additionalProperties",
                          false)),
                  DocumentMetadata.empty());
          return types
              .transition(
                  "account:author", draft.contract().version(), draft.controlRevision(), true)
              .contract();
        });
  }

  InformationContent.Binding binding(InformationContent.Contract contract, String severity) {
    var metadata = DocumentMetadata.instance(SchemaValue.fromWire(Map.of("severity", severity)));
    return new InformationContent.Binding(contract, SchemaCodec.fingerprint(metadata), metadata);
  }

  UUID original(boolean derived, String kind) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,kind) VALUES(?,'account:author',?,'author',?)",
        resource,
        resource + ".md",
        kind);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,text_hash,byte_size,source_bytes,extracted_text,converter,outline_top_level,allowance_total) VALUES(?,?,1,'Incident','text/markdown',?,?,8,?,'Incident','fixture','[]'::jsonb,5)",
        revision,
        resource,
        "a".repeat(64),
        "b".repeat(64),
        "Incident".getBytes(StandardCharsets.UTF_8));
    for (String stage : InformationCatalogue.STAGES)
      jdbc.update(
          "INSERT INTO information_steps(revision_id,generation,stage,state,fingerprint) VALUES(?,1,?,?,?)",
          revision,
          stage,
          stage.equals("extract") || derived && InformationCatalogue.STAGES.indexOf(stage) < 5
              ? "ready"
              : "pending",
          configuration.get(stage));
    if (derived) {
      UUID chapter = UUID.randomUUID(),
          section = UUID.randomUUID(),
          paragraph = UUID.randomUUID(),
          chunk = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO documents(id,source_name,title,content_hash,text_hash,byte_size,ingested_at,ingested_by,information_namespace,summary,summary_embedding) VALUES(?,?,'Incident',?,?,8,now(),'author',?,'Summary',array_fill(0.1::real,ARRAY[768])::vector)",
          revision,
          resource + ".md",
          "a".repeat(64),
          "b".repeat(64),
          "revision:" + revision);
      jdbc.update(
          "INSERT INTO chapters(id,document_id,ordinal,title,is_synthetic,summary) VALUES(?,?,1,'Chapter',false,'Summary')",
          chapter,
          revision);
      jdbc.update(
          "INSERT INTO sections(id,document_id,chapter_id,ordinal,title,is_synthetic,summary) VALUES(?,?,?,1,'Section',false,'Summary')",
          section,
          revision,
          chapter);
      jdbc.update(
          "INSERT INTO paragraphs(id,document_id,section_id,text,content_hash,occurrence,ordinal,summary) VALUES(?,?,?,'Incident',?,1,1,'Summary')",
          paragraph,
          revision,
          section,
          "a".repeat(64));
      jdbc.update(
          "INSERT INTO chunks(id,paragraph_id,ordinal,text,byte_size,split_mid_sentence,embedding) VALUES(?,?,1,'Incident',8,false,array_fill(0.1::real,ARRAY[768])::vector)",
          chunk,
          paragraph);
      jdbc.update(
          "INSERT INTO information_document_policies(document_id,owner_handle,visibility,assigned_at) VALUES(?,'author','personal',now())",
          revision);
    }
    if (kind.equals("report"))
      jdbc.update(
          "INSERT INTO information_reports(revision_id,status,produced_by,definition_hash) VALUES(?,'final','original-run',?)",
          revision,
          "c".repeat(64));
    return revision;
  }

  UUID request() {
    UUID request = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_write_gates(account,request_id,fingerprint,operation,token,lease_until) VALUES('author',?,?,'content.revise',?,now()+interval '5 minutes')",
        request,
        "d".repeat(64),
        UUID.randomUUID());
    return request;
  }

  @Test
  void immutable_contract_binding_and_namespace_are_database_invariants() {
    var contract = published();
    UUID revision = original(false, "source");
    content.attach(revision, binding(contract, "low"));
    assertEquals(
        "low",
        ((SchemaValue.Text)
                content.binding(revision).orElseThrow().metadata().values().get("severity"))
            .value());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_content_types SET label='Changed' WHERE id=?",
                contract.type()));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_content_type_versions SET schema_definition='{}'::jsonb WHERE id=?",
                contract.version()));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE information_revision_content SET metadata='{}'::jsonb WHERE revision_id=?",
                revision));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> types.transition("account:author", contract.version(), 1, false));
    assertTrue(types.attached(revision, contract.version()).isPresent());
    assertTrue(types.version("account:other", contract.version(), false).isEmpty());
  }

  @Test
  void compatible_report_copy_has_independent_rows_and_no_automatic_work() {
    var contract = published();
    UUID original = original(true, "report"), request = request();
    content.attach(original, binding(contract, "low"));
    var prepared =
        work.inTransaction(
            () ->
                projections.prepare(
                    own, request, original, binding(contract, "high"), configuration));
    assertEquals(InformationCatalogue.STAGES.subList(0, 5), prepared.manifest().reusable());
    var admitted =
        work.inTransaction(() -> projections.copy(own, request, prepared, configuration));
    assertEquals("explicit_only", content.view(admitted.revision()).processingPolicy());
    assertEquals(5, content.view(admitted.revision()).reused().size());
    assertTrue(content.view(admitted.revision()).authorisedStages().isEmpty());
    assertEquals(
        "draft",
        jdbc.queryForObject(
            "SELECT status FROM information_reports WHERE revision_id=?",
            String.class,
            admitted.revision()));
    assertEquals(
        "original-run",
        jdbc.queryForObject(
            "SELECT produced_by FROM information_reports WHERE revision_id=?",
            String.class,
            admitted.revision()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT allowance_spent FROM information_revisions WHERE id=?",
            Integer.class,
            admitted.revision()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_model_steps WHERE revision_id=?",
            Integer.class,
            admitted.revision()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM paragraphs origin JOIN paragraphs copy ON copy.id=origin.id WHERE origin.document_id=? AND copy.document_id=?",
            Integer.class,
            original,
            admitted.revision()));
    assertFalse(catalogue.readable(own.withView(false), admitted.revision()));
    assertTrue(catalogue.readable(own, admitted.revision()));
    assertFalse(catalogue.readable(own.withView(false), original));
    assertEquals(2, catalogue.list(own, 10, 0, null).size());
    assertTrue(catalogue.list(own.withView(false), 10, 0, null).isEmpty());
    work.inTransaction(
        () -> {
          catalogue.availability(own, original, "deleted");
          return null;
        });
    assertTrue(content.binding(original).isEmpty());
    assertEquals(
        "Incident",
        jdbc.queryForObject(
            "SELECT text FROM paragraphs WHERE document_id=?", String.class, admitted.revision()));
    assertEquals(
        "high",
        ((SchemaValue.Text)
                content
                    .binding(admitted.revision())
                    .orElseThrow()
                    .metadata()
                    .values()
                    .get("severity"))
            .value());
  }

  @Test
  void incomplete_copy_waits_for_selected_stage_and_persistent_grants_fence_generations() {
    UUID original = original(false, "source"), request = request();
    var prepared =
        work.inTransaction(() -> projections.prepare(own, request, original, null, configuration));
    var copy = work.inTransaction(() -> projections.copy(own, request, prepared, configuration));
    assertEquals(List.of("extract"), prepared.manifest().reusable());
    // Isolate the new revision from the still-pending automatic origin.
    jdbc.update("UPDATE information_revisions SET availability='withdrawn' WHERE id=?", original);
    var worker =
        new JdbcInformationProcessingRepository(
            jdbc, Clock.systemUTC(), new JdbcProjectMembers(jdbc));
    assertTrue(work.inTransaction(() -> worker.candidate(null, false)).isEmpty());
    work.inTransaction(
        () -> {
          content.authorise(own, copy.revision(), UUID.randomUUID(), List.of("derive"));
          return null;
        });
    var lease = work.inTransaction(() -> worker.candidate(null, false)).orElseThrow().lease();
    worker.start(lease, configuration.get("derive"));
    assertEquals("derive", lease.stage());
    assertTrue(worker.renew(lease));
    jdbc.update(
        "UPDATE information_revisions SET generation=generation+1 WHERE id=?", copy.revision());
    assertFalse(worker.renew(lease));
    assertThrows(
        InformationLifecycle.StaleLease.class,
        () ->
            work.inTransaction(
                () -> {
                  worker.requireLease(lease);
                  return null;
                }));
  }

  @Test
  void configuration_or_source_change_rolls_back_copy_and_replays_exact_preparation() {
    UUID original = original(false, "source"), request = request();
    var prepared =
        work.inTransaction(() -> projections.prepare(own, request, original, null, configuration));
    assertEquals(
        prepared,
        work.inTransaction(() -> projections.prepare(own, request, original, null, Map.of())));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> work.inTransaction(() -> projections.copy(own, request, prepared, Map.of())));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM information_revisions", Integer.class));
    jdbc.update(
        "UPDATE information_steps SET state='failed' WHERE revision_id=? AND stage='derive'",
        original);
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> work.inTransaction(() -> projections.copy(own, request, prepared, configuration)));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM information_revisions", Integer.class));
  }

  @Test
  void dual_slot_reuse_copies_current_vectors_and_target_staging_with_destination_fences() {
    UUID origin = original(true, "source"), request = request();
    var spaces = new io.aeyer.plowshare.server.embedding.JdbcEmbeddingSpaceRepository(jdbc);
    for (String slot : List.of("code", "prose")) {
      var active =
          spaces.register(
              new io.aeyer.plowshare.server.embedding.EmbeddingSpace.Definition(
                  "fixture-" + slot,
                  "active",
                  3,
                  "",
                  "",
                  "cls",
                  io.aeyer.plowshare.server.embedding.EmbeddingSpace.Normalization.NONE,
                  "none"));
      var target =
          spaces.register(
              new io.aeyer.plowshare.server.embedding.EmbeddingSpace.Definition(
                  "fixture-" + slot,
                  "target",
                  3,
                  "",
                  "",
                  "cls",
                  io.aeyer.plowshare.server.embedding.EmbeddingSpace.Normalization.NONE,
                  "none"));
      jdbc.update(
          "UPDATE embedding_slots SET active_space_id=?,target_space_id=?,active_mode='exact',target_mode='exact',active_distance='COSINE',target_distance='COSINE' WHERE slot=?",
          active.id(),
          target.id(),
          slot);
      for (String table : List.of("chunks", "documents")) {
        String predicate =
            table.equals("documents")
                ? "id=?"
                : "paragraph_id IN (SELECT id FROM paragraphs WHERE document_id=?)";
        jdbc.update(
            "UPDATE "
                + table
                + " SET "
                + slot
                + "_embedding='[1,0,0]'::vector,"
                + slot
                + "_space_id=?,"
                + slot
                + "_source_revision=embedding_source_revision WHERE "
                + predicate,
            active.id(),
            origin);
        jdbc.update(
            "INSERT INTO "
                + table
                + "_embedding_staging(source_id,slot,space_id,source_revision,embedding) SELECT id,?,?,embedding_source_revision,'[0,1,0]'::vector FROM "
                + table
                + " WHERE "
                + predicate,
            slot,
            target.id(),
            origin);
      }
    }
    var prepared =
        work.inTransaction(() -> projections.prepare(own, request, origin, null, configuration));
    assertEquals(5, prepared.manifest().reusable().size());
    var copy = work.inTransaction(() -> projections.copy(own, request, prepared, configuration));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM documents_embedding_staging s JOIN documents d ON d.id=s.source_id WHERE d.id=? AND s.source_revision=d.embedding_source_revision",
            Integer.class,
            copy.revision()));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM chunks_embedding_staging s JOIN chunks c ON c.id=s.source_id JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=? AND s.source_revision=c.embedding_source_revision",
            Integer.class,
            copy.revision()));
    assertTrue(
        jdbc.queryForObject(
            "SELECT code_source_revision=embedding_source_revision AND prose_source_revision=embedding_source_revision FROM documents WHERE id=?",
            Boolean.class,
            copy.revision()));
    var runtime =
        new io.aeyer.plowshare.server.embedding.JdbcEmbeddingWorkRepository(jdbc, work, spaces);
    // Removing a copied vector cannot let global maintenance infer a new spending grant.
    jdbc.update(
        "UPDATE documents SET code_embedding=NULL,code_space_id=NULL,code_source_revision=NULL WHERE id=?",
        copy.revision());
    assertTrue(
        runtime
            .pending(
                runtime
                    .target(io.aeyer.plowshare.server.embedding.EmbeddingSlot.CODE)
                    .orElseThrow(),
                io.aeyer.plowshare.server.embedding.EmbeddingWorkRepository.Store.DOCUMENTS,
                100)
            .stream()
            .noneMatch(row -> row.key().id().equals(copy.revision().toString())));
    // Missing one required slot/space prevents reuse of embedding and every downstream stage.
    jdbc.update(
        "DELETE FROM chunks_embedding_staging staged USING chunks c,paragraphs p WHERE staged.source_id=c.id AND c.paragraph_id=p.id AND p.document_id=? AND staged.slot='prose'",
        copy.revision());
    UUID nextRequest = request();
    var incomplete =
        work.inTransaction(
            () -> projections.prepare(own, nextRequest, copy.revision(), null, configuration));
    assertEquals(List.of("extract", "derive"), incomplete.manifest().reusable());
  }

  @Test
  void concurrent_metadata_writers_publish_only_one_next_head() throws Exception {
    UUID origin = original(true, "source"), first = request(), second = request();
    var left =
        work.inTransaction(() -> projections.prepare(own, first, origin, null, configuration));
    var right =
        work.inTransaction(() -> projections.prepare(own, second, origin, null, configuration));
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var one =
          pool.submit(
              () -> {
                start.await();
                return work.inTransaction(() -> projections.copy(own, first, left, configuration));
              });
      var two =
          pool.submit(
              () -> {
                start.await();
                return work.inTransaction(
                    () -> projections.copy(own, second, right, configuration));
              });
      start.countDown();
      int committed = 0, conflicted = 0;
      for (var result : List.of(one, two)) {
        try {
          assertTrue(result.get(10, java.util.concurrent.TimeUnit.SECONDS).created());
          committed++;
        } catch (java.util.concurrent.ExecutionException failure) {
          assertInstanceOf(io.aeyer.plowshare.server.faults.CallerFault.class, failure.getCause());
          assertEquals("INFORMATION_REVISION_CONFLICT", failure.getCause().getMessage());
          conflicted++;
        }
      }
      assertEquals(1, committed);
      assertEquals(1, conflicted);
    }
    assertEquals(
        2, jdbc.queryForObject("SELECT count(*) FROM information_revisions", Integer.class));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM documents d JOIN information_revisions r ON r.id=d.id WHERE r.resource_id=(SELECT resource_id FROM information_revisions WHERE id=?)",
            Integer.class,
            origin));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM information_requests", Integer.class));
  }

  @Test
  void import_and_metadata_assignment_take_policy_before_contract_locks() throws Exception {
    var contract = published();
    UUID revision = original(false, "source"), request = request();
    var assigned = new java.util.concurrent.CountDownLatch(1);
    var proceed = new java.util.concurrent.CountDownLatch(1);
    var importLocked = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var metadata =
          pool.submit(
              () ->
                  work.inTransaction(
                      () -> {
                        types.version("account:author", contract.version(), true).orElseThrow();
                        assigned.countDown();
                        try {
                          if (!proceed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("release timeout");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        var prepared =
                            projections.prepare(own, request, revision, null, configuration);
                        return projections.copy(own, request, prepared, configuration);
                      }));
      assertTrue(assigned.await(10, java.util.concurrent.TimeUnit.SECONDS));
      var importing =
          pool.submit(
              () ->
                  work.inTransaction(
                      () -> {
                        new JdbcInformationNavigationRepository(
                                jdbc,
                                new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc))
                            .lock();
                        importLocked.countDown();
                        return types
                            .version("account:author", contract.version(), true)
                            .orElseThrow();
                      }));
      try {
        assertFalse(importLocked.await(200, java.util.concurrent.TimeUnit.MILLISECONDS));
      } finally {
        proceed.countDown();
      }
      assertTrue(metadata.get(10, java.util.concurrent.TimeUnit.SECONDS).created());
      assertEquals(
          contract.version(),
          importing.get(10, java.util.concurrent.TimeUnit.SECONDS).contract().version());
    }
  }

  @Test
  void concurrent_activation_serializes_type_before_versions_without_deadlock() throws Exception {
    var active = published();
    var second =
        work.inTransaction(
            () ->
                types.create(
                    "account:author",
                    "author",
                    active.type(),
                    3L,
                    null,
                    null,
                    null,
                    active.schema(),
                    active.presentation()));
    var third =
        work.inTransaction(
            () ->
                types.create(
                    "account:author",
                    "author",
                    active.type(),
                    second.controlRevision(),
                    null,
                    null,
                    null,
                    active.schema(),
                    active.presentation()));
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var one =
          pool.submit(
              () -> {
                start.await();
                return work.inTransaction(
                    () ->
                        types.transition(
                            "account:author",
                            second.contract().version(),
                            third.controlRevision(),
                            true));
              });
      var two =
          pool.submit(
              () -> {
                start.await();
                return work.inTransaction(
                    () ->
                        types.transition(
                            "account:author",
                            third.contract().version(),
                            third.controlRevision(),
                            true));
              });
      start.countDown();
      int committed = 0;
      for (var result : List.of(one, two)) {
        try {
          assertEquals("active", result.get(10, java.util.concurrent.TimeUnit.SECONDS).state());
          committed++;
        } catch (java.util.concurrent.ExecutionException failure) {
          assertInstanceOf(io.aeyer.plowshare.server.faults.CallerFault.class, failure.getCause());
          assertEquals("INFORMATION_TYPE_CONFLICT", failure.getCause().getMessage());
        }
      }
      assertEquals(1, committed);
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_content_type_versions WHERE state='active'",
            Integer.class));
  }
}
