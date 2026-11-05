package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.blobs.*;
import io.aeyer.plowshare.server.llm.accounting.*;
import io.aeyer.plowshare.server.llm.dispatch.*;
import io.aeyer.plowshare.server.relay.tools.RelayToolCodec;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Migration, atomic append/claims, retention, concurrency and reconstruction need PostgreSQL. */
@Tag("full-db")
@Testcontainers
class TrajectoryArtifactsPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  static TransactionTemplate transactions;
  @TempDir Path root;
  UnitOfWork work;
  EntryStore entries;
  JdbcBlobRepository blobs;
  JdbcTrajectoryArtifacts artifacts;
  DiskBinaryBlobStorage storage;
  UUID invocation;
  String conversation;
  long project;
  String image;
  BlobKey key;
  byte[] png;
  ToolContent.Body body;
  UsageAttribution owner;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).target("227").load().migrate();
    jdbc = new JdbcTemplate(source);
    jdbc.update("INSERT INTO projects(name,workspace) VALUES('trajectory-upgrade','fixture')");
    // Seed the two pre-format tables at V227, then prove the upgrade backfills PNG.
    long legacyProject =
        jdbc.queryForObject("SELECT id FROM projects WHERE name='trajectory-upgrade'", Long.class);
    UUID legacyInvocation = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO relay_tool_invocations(id,project_id,account,run_id,call_id,fingerprint,binding,request,occurred_at,causation) VALUES(?,?,'owner','legacy','capture',?,'{}','{}',now(),'{\"rootId\":\"root\",\"parentId\":\"job\",\"depth\":1}'::jsonb)",
        legacyInvocation,
        legacyProject,
        "a".repeat(64));
    jdbc.update(
        "INSERT INTO invocation_image_admissions VALUES(?,0,?,?,?,2,2,10,now())",
        legacyInvocation,
        ImageAdmissions.requestId(legacyInvocation, 0),
        "img_" + "a".repeat(32),
        "a".repeat(64));
    var legacyConversation =
        new ConversationStore(jdbc)
            .open(Home.of("trajectory-upgrade"), Budget.of(20), null, "owner")
            .id();
    var legacyEntry =
        new EntryStore(jdbc)
            .append(legacyConversation, 1, LoggedEntry.toolResult("capture", "legacy"), "job");
    jdbc.update(
        "INSERT INTO binary_blobs(scope,sha256,file,byte_length,state,unclaimed_at) VALUES(?,?,?,10,'READY',now())",
        "projects/" + legacyProject,
        "a".repeat(64),
        "projects/" + legacyProject + "/images/legacy.png");
    jdbc.update(
        "INSERT INTO trajectory_image_references VALUES(?,?,0,?,?,?,10,false)",
        legacyConversation,
        legacyEntry.ordinal(),
        "img_" + "a".repeat(32),
        "projects/" + legacyProject,
        "a".repeat(64));
    Flyway.configure().dataSource(source).load().migrate();
    assertEquals(
        "image/png",
        jdbc.queryForObject(
            "SELECT mime_type FROM invocation_image_admissions WHERE invocation_id=?",
            String.class,
            legacyInvocation));
    assertEquals(
        "image/png",
        jdbc.queryForObject(
            "SELECT mime_type FROM trajectory_image_references WHERE conversation_id=?",
            String.class,
            legacyConversation));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM projects WHERE name='trajectory-upgrade'", Integer.class));
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  @BeforeEach
  void fixture() throws Exception {
    fixture("image/png");
  }

  void fixture(String mimeType) throws Exception {
    jdbc.execute("TRUNCATE conversations,relay_tool_invocations,binary_blobs CASCADE");
    project =
        jdbc.queryForObject(
            "INSERT INTO projects(name,workspace) VALUES('image-project','fixture') ON CONFLICT(name) DO UPDATE SET workspace=excluded.workspace RETURNING id",
            Long.class);
    work =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> action) {
            return transactions.execute(status -> action.get());
          }
        };
    blobs = new JdbcBlobRepository(jdbc);
    artifacts = new JdbcTrajectoryArtifacts(jdbc, blobs);
    entries = new EntryStore(jdbc, work, artifacts);
    storage = new DiskBinaryBlobStorage(root, blobs, work, Clock.systemUTC(), Duration.ZERO, 4096);
    conversation =
        new ConversationStore(jdbc)
            .open(Home.of("image-project"), Budget.of(20), null, "owner")
            .id();
    owner = executionOwner(1, null);
    assertNotEquals("job", owner.runs().id());
    var output = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
        "png",
        output);
    png = output.toByteArray();
    if (mimeType.equals("image/jpeg")) {
      output.reset();
      javax.imageio.ImageIO.write(
          new java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
          "jpeg",
          output);
      png = output.toByteArray();
    } else if (mimeType.equals("image/webp")) {
      try (var input = getClass().getResourceAsStream("/media/still-lossless.webp.base64")) {
        assertNotNull(input);
        png = java.util.Base64.getMimeDecoder().decode(input.readAllBytes());
      }
    }
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
    image = "img_" + digest.substring(0, 32);
    var descriptor =
        new io.aeyer.plowshare.server.media.TikaMediaInspection(4096, 100, 100, 1)
            .inspectImage(png);
    key = new BlobKey("projects/" + project, digest);
    invocation = UUID.randomUUID();
    body =
        new ToolContent.Body(
            "content/1",
            List.of(
                ToolContent.Part.text("captured"),
                new ToolContent.Part(
                    ToolContent.Kind.IMAGE, null, image, 0, ToolContent.Use.OBSERVATION)));
    var result =
        new RelayToolCodec.Result(
            RelayToolCodec.CONTENT_VERSION,
            invocation.toString(),
            "image-project",
            "capture",
            "screenshot",
            RelayToolCodec.State.COMPLETED,
            body.text(),
            body);
    jdbc.update(
        "INSERT INTO relay_tool_invocations(id,project_id,account,run_id,call_id,fingerprint,binding,request,occurred_at,causation,result,completed_at) VALUES(?,?,'owner',?,'capture',?,'{}','{}',now(),'{\"rootId\":\"root\",\"parentId\":\"job\",\"depth\":1}'::jsonb,?,now())",
        invocation,
        project,
        owner.runs().id(),
        "a".repeat(64),
        RelayToolCodec.write(result));
    storage.store(
        new BlobSource(key, "projects/" + project + "/images/" + image + ".png", png.length),
        png,
        image,
        new BlobClaim(
            "invocation-admission", invocation.toString(), 0, BlobClaim.Retention.RELEASABLE));
    jdbc.update(
        "INSERT INTO invocation_image_admissions VALUES(?,0,?,?,?,?,?,?,now(),?)",
        invocation,
        ImageAdmissions.requestId(invocation, 0),
        image,
        digest,
        descriptor.width(),
        descriptor.height(),
        png.length,
        mimeType);
    entries.append(conversation, 1, LoggedEntry.utterance("Inspect", null), "job");
    entries.append(
        conversation,
        1,
        LoggedEntry.answer("", List.of(new ToolCall("capture", "screenshot", "{}"))),
        "job");
  }

  UsageAttribution executionOwner(int turn, Integer continuing) {
    var executions =
        new UsageExecutionStore(
            jdbc,
            new DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getDataSource())),
            new com.fasterxml.jackson.databind.ObjectMapper());
    Transcript transcript =
        new Transcript() {
          public List<ChatMessage> before() {
            return List.of();
          }

          public String conversationId() {
            return conversation;
          }

          public Spoken spokenIn() {
            return new Spoken(conversation, turn);
          }

          public Integer continuingTurn() {
            return continuing;
          }

          public void promptMeasured(int count) {}
        };
    return executions.start(Home.of("image-project"), transcript, "viewer", "owner").usage();
  }

  LoggedEntry result(boolean effective) {
    return LoggedEntry.toolResult("capture", "captured " + image)
        .observing(new ToolObservation(invocation, body, effective ? body : null));
  }

  int claims(String kind) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM binary_blob_claims WHERE kind=?", Integer.class, kind);
  }

  int rows() {
    return entries.forConversation(conversation).size();
  }

  ChatRequest request(EntryStore store) {
    return ChatRequest.of("model", Projection.of(store.forConversation(conversation), false))
        .withAttribution(owner);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"image/jpeg", "image/webp"})
  void actual_format_survives_entry_claim_and_invocation_removal(String mime) throws Exception {
    fixture(mime);
    var written = entries.append(conversation, 1, result(true), "job");
    assertEquals(
        mime,
        jdbc.queryForObject(
            "SELECT mime_type FROM trajectory_image_references WHERE conversation_id=? AND entry_ordinal=?",
            String.class,
            conversation,
            written.ordinal()));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE invocation_image_admissions SET mime_type='image/gif' WHERE invocation_id=?",
                invocation));
    jdbc.update("DELETE FROM relay_tool_invocations WHERE id=?", invocation);
    var reopened =
        new EntryStore(jdbc, work, new JdbcTrajectoryArtifacts(jdbc, new JdbcBlobRepository(jdbc)));
    var materialized = new TrajectoryPromptImages(artifacts, storage).resolve(request(reopened));
    var nativeImage = (Content.Image) materialized.messages().getLast().parts().getLast();
    assertEquals(
        "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(png),
        nativeImage.dataUri());
    assertEquals(1, claims("trajectory-entry"));
    assertEquals(0, storage.collect(10));
  }

  @Test
  void append_restart_and_invocation_retention_keep_exact_pixels_under_independent_entry_claim() {
    var written = entries.append(conversation, 1, result(true), "job");
    assertEquals(1, claims("trajectory-entry"));
    assertEquals(1, claims("invocation-admission"));
    assertEquals(body, written.observation().original());
    jdbc.update("DELETE FROM relay_tool_invocations WHERE id=?", invocation);
    assertEquals(0, claims("invocation-admission"));
    assertEquals(0, storage.collect(10));
    var reopened =
        new EntryStore(jdbc, work, new JdbcTrajectoryArtifacts(jdbc, new JdbcBlobRepository(jdbc)));
    var materialized = new TrajectoryPromptImages(artifacts, storage).resolve(request(reopened));
    var restored =
        materialized.messages().stream()
            .flatMap(m -> m.parts().stream())
            .filter(Content.Image.class::isInstance)
            .map(Content.Image.class::cast)
            .findFirst()
            .orElseThrow();
    assertArrayEquals(png, Base64.getDecoder().decode(restored.dataUri().split(",", 2)[1]));
    assertEquals(image, restored.uid());
    assertFalse(reopened.forConversation(conversation).getLast().content().contains("base64"));
    assertEquals(
        written.observation(), reopened.forConversation(conversation).getLast().observation());
  }

  @Test
  void foreign_run_call_owner_project_or_provider_body_rolls_back_the_entry_and_claims() {
    jdbc.update("UPDATE relay_tool_invocations SET run_id='foreign' WHERE id=?", invocation);
    assertThrows(
        RuntimeException.class, () -> entries.append(conversation, 1, result(true), "job"));
    jdbc.update(
        "UPDATE relay_tool_invocations SET run_id=? WHERE id=?", owner.runs().id(), invocation);
    assertThrows(
        RuntimeException.class,
        () ->
            entries.append(
                conversation,
                1,
                LoggedEntry.toolResult("other", "captured").observing(result(true).observation()),
                "job"));
    var changed =
        new ToolContent.Body(
            "content/1", List.of(ToolContent.Part.text("changed"), body.images().getFirst()));
    assertThrows(
        RuntimeException.class,
        () ->
            entries.append(
                conversation,
                1,
                LoggedEntry.toolResult("capture", "captured")
                    .observing(new ToolObservation(invocation, changed, changed)),
                "job"));
    jdbc.update("UPDATE conversations SET owner_handle='foreign' WHERE id=?", conversation);
    assertThrows(
        RuntimeException.class, () -> entries.append(conversation, 1, result(true), "job"));
    jdbc.update(
        "UPDATE conversations SET owner_handle='owner',project_id=(SELECT id FROM projects WHERE name='trajectory-upgrade') WHERE id=?",
        conversation);
    assertThrows(
        RuntimeException.class, () -> entries.append(conversation, 1, result(true), "job"));
    assertEquals(2, rows());
    assertEquals(0, claims("trajectory-entry"));
    assertEquals(1, claims("invocation-admission"));
  }

  @Test
  void missing_admission_or_failed_claim_acquisition_rolls_back_append() {
    jdbc.update("DELETE FROM invocation_image_admissions WHERE invocation_id=?", invocation);
    assertThrows(
        RuntimeException.class, () -> entries.append(conversation, 1, result(true), "job"));
    assertEquals(2, rows());
    assertEquals(0, claims("trajectory-entry"));
  }

  @Test
  void ejection_and_entry_deletion_release_only_the_entry_claim_and_rollback_is_atomic() {
    var written = entries.append(conversation, 1, result(true), "job");
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  entries.ejectPayload(conversation, written.ordinal(), Instant.now(), null);
                  throw new IllegalStateException("rollback");
                }));
    assertEquals(1, claims("trajectory-entry"));
    assertNotNull(entries.forConversation(conversation).getLast().observation());
    assertTrue(entries.ejectPayload(conversation, written.ordinal(), Instant.now(), null));
    assertFalse(entries.ejectPayload(conversation, written.ordinal(), Instant.now(), null));
    assertEquals(0, claims("trajectory-entry"));
    assertEquals(1, claims("invocation-admission"));
    assertNull(entries.forConversation(conversation).getLast().observation());
    var second = entries.append(conversation, 1, result(true), "job");
    assertEquals(1, claims("trajectory-entry"));
    assertNotNull(second.observation());
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> jdbc.update("DELETE FROM conversations WHERE id=?", conversation));
    jdbc.update(
        "DELETE FROM entries WHERE conversation_id=? AND ordinal=?",
        conversation,
        second.ordinal());
    assertEquals(0, claims("trajectory-entry"));
    assertEquals(1, claims("invocation-admission"));
  }

  @Test
  void withheld_output_and_foreign_context_cannot_resolve_pixels() {
    var written = entries.append(conversation, 1, result(false), "job");
    assertEquals(1, claims("trajectory-entry"));
    var address = new Content.StoredImage(conversation, written.ordinal(), 0, image);
    assertThrows(RuntimeException.class, () -> artifacts.read(List.of(address), owner));
    assertTrue(Projection.images(written).isEmpty());
    entries.append(conversation, 1, result(true), "job");
    var visible = Projection.images(entries.forConversation(conversation).getLast());
    assertThrows(RuntimeException.class, () -> artifacts.read(visible, UsageAttribution.LEGACY));
    assertThrows(
        RuntimeException.class,
        () ->
            artifacts.read(
                visible,
                owner.withExecution(
                    UsageLineage.root("cnv_0000000000000001"),
                    owner.runs(),
                    owner.orchestrations(),
                    "viewer",
                    1L,
                    1L)));
    var foreign =
        UsageAttribution.project(
                "other", Long.toString(project), UsageAttribution.Operation.AGENT_CHAT)
            .withExecution(
                owner.conversations(), owner.runs(), owner.orchestrations(), "viewer", 1L, 1L);
    assertThrows(RuntimeException.class, () -> artifacts.read(visible, foreign));
    assertEquals(1, artifacts.read(visible, owner).size());
  }

  @Test
  void deletion_waits_for_atomic_copy_and_later_collection_respects_trajectory_claim()
      throws Exception {
    CountDownLatch copied = new CountDownLatch(1), release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var append =
          executor.submit(
              () ->
                  work.inTransaction(
                      () -> {
                        var row = entries.append(conversation, 1, result(true), "job");
                        copied.countDown();
                        try {
                          if (!release.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("fixture timeout");
                        } catch (InterruptedException stopped) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(stopped);
                        }
                        return row;
                      }));
      assertTrue(copied.await(5, TimeUnit.SECONDS));
      var deleted =
          executor.submit(
              () -> jdbc.update("DELETE FROM relay_tool_invocations WHERE id=?", invocation));
      assertThrows(TimeoutException.class, () -> deleted.get(100, TimeUnit.MILLISECONDS));
      release.countDown();
      assertNotNull(append.get(5, TimeUnit.SECONDS));
      assertEquals(1, deleted.get(5, TimeUnit.SECONDS));
      assertEquals(1, claims("trajectory-entry"));
      assertEquals(0, storage.collect(10));
      assertTrue(
          new TrajectoryPromptImages(artifacts, storage)
              .resolve(request(entries)).messages().stream().anyMatch(ChatMessage::carriesAnImage));
    } finally {
      release.countDown();
    }
  }

  @Test
  void fold_preserves_claims_and_disk_corruption_is_an_explicit_failure() throws Exception {
    var row = entries.append(conversation, 1, result(true), "job");
    var reference = Projection.images(row).getFirst();
    var summary =
        entries.append(conversation, 1, LoggedEntry.summary("Screenshot inspected"), "job");
    entries.supersede(conversation, 0, 1, summary.ordinal());
    assertEquals(1, claims("trajectory-entry"));
    assertFalse(
        Projection.of(entries.forConversation(conversation), false).stream()
            .anyMatch(ChatMessage::carriesAnImage));
    var source = blobs.find(key).orElseThrow().source();
    Files.write(root.resolve(source.file()), new byte[] {1, 2, 3});
    var direct =
        ChatRequest.of("model", List.of(Projection.images(List.of(reference))))
            .withAttribution(owner);
    assertThrows(
        BlobUnavailableException.class,
        () -> new TrajectoryPromptImages(artifacts, storage).resolve(direct));
  }

  @Test
  void final_claim_release_allows_collection_without_retaining_a_second_binary_copy() {
    var row = entries.append(conversation, 1, result(true), "job");
    var source = blobs.find(key).orElseThrow().source();
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM binary_blobs", Integer.class));
    jdbc.update("DELETE FROM relay_tool_invocations WHERE id=?", invocation);
    assertEquals(0, storage.collect(10));
    entries.ejectPayload(conversation, row.ordinal(), Instant.now(), null);
    assertEquals(1, storage.collect(10));
    assertFalse(Files.exists(root.resolve(source.file())));
  }

  @Test
  void corrupt_typed_history_fails_reconstruction_without_returning_a_text_only_log() {
    var row = entries.append(conversation, 1, result(true), "job");
    jdbc.update(
        "UPDATE entries SET tool_observation=tool_observation || '{\"unexpected\":true}'::jsonb WHERE conversation_id=? AND ordinal=?",
        conversation,
        row.ordinal());
    assertThrows(
        InvalidToolObservationException.class, () -> entries.forConversation(conversation));
    assertEquals(1, claims("trajectory-entry"));
  }

  @Test
  void continued_jobs_reuse_the_retained_accounting_run_without_equating_it_to_a_job() {
    String originalRun = owner.runs().id();
    owner = executionOwner(2, 1);
    assertEquals(originalRun, owner.runs().id());
    var row = entries.append(conversation, 2, result(true), "job_continuation");
    assertEquals(2, row.turnOrdinal());
    assertEquals(
        "job_continuation",
        jdbc.queryForObject(
            "SELECT job_id FROM entries WHERE conversation_id=? AND ordinal=?",
            String.class,
            conversation,
            row.ordinal()));
    assertEquals(1, claims("trajectory-entry"));
    assertEquals(1, artifacts.read(Projection.images(row), owner).size());
  }

  @Test
  void nontransactional_typed_append_fails_before_inserting_a_row() {
    assertThrows(
        IllegalStateException.class,
        () -> new EntryStore(jdbc).append(conversation, 1, result(true), "job"));
    assertEquals(2, rows());
    assertEquals(0, claims("trajectory-entry"));
  }
}
