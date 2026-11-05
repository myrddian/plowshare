package io.aeyer.plowshare.server.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.ImageAdmissions;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.tools.RelayToolCodec;
import io.aeyer.plowshare.server.relay.tools.RelayToolRepository;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL upgrade, constraints, rollback and admission/settlement row-lock assertions. */
@Tag("full-db")
@Testcontainers
class JdbcArtifactRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  static TransactionTemplate transactions;
  JdbcArtifactRepository repository;
  RelayToolRepository.Intent intent;
  ImageAdmissions.Receipt receipt;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).target("223").load().migrate();
    jdbc = new JdbcTemplate(source);
    jdbc.update("INSERT INTO projects(name,workspace) VALUES('admission-upgrade','fixture')");
    Flyway.configure().dataSource(source).load().migrate();
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM projects WHERE name='admission-upgrade'", Integer.class));
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  @BeforeEach
  void fixture() {
    jdbc.update("DELETE FROM invocation_image_admissions");
    jdbc.update("DELETE FROM relay_tool_invocations WHERE account='owner'");
    jdbc.update("DELETE FROM binary_blob_claims");
    jdbc.update("DELETE FROM binary_blob_aliases");
    jdbc.update("DELETE FROM binary_blobs");
    long project =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('atlas','fixture') ON CONFLICT(name) DO UPDATE SET workspace=excluded.workspace RETURNING id",
                Long.class));
    var base = ArtifactAdmissionsTest.intent(UUID.randomUUID());
    intent =
        new RelayToolRepository.Intent(
            base.id(),
            project,
            base.fingerprint(),
            base.binding(),
            base.request(),
            base.occurredAt(),
            base.causation());
    jdbc.update(
        "INSERT INTO relay_tool_invocations(id,project_id,account,run_id,call_id,fingerprint,binding,request,occurred_at,causation) VALUES(?,?,'owner','run','call',?,?,?,?,'{\"rootId\":\"root\",\"parentId\":\"run\",\"depth\":1}'::jsonb)",
        intent.id(),
        project,
        intent.fingerprint(),
        RelayToolCodec.write(intent.binding()),
        RelayToolCodec.write(intent.request()),
        java.sql.Timestamp.from(intent.occurredAt()));
    var blobs = new io.aeyer.plowshare.server.blobs.JdbcBlobRepository(jdbc);
    repository = new JdbcArtifactRepository(jdbc, blobs);
    var request =
        new ImageAdmissions.Request(intent.id(), 0, ImageAdmissions.requestId(intent.id(), 0));
    receipt =
        new ImageAdmissions.Receipt(
            request,
            "atlas",
            "owner",
            "producer",
            "img_" + "a".repeat(32),
            "image/png",
            3,
            2,
            128,
            "a".repeat(64),
            ArtifactAdmissionsTest.NOW.toString());
    transactions.execute(
        status -> {
          blobs.adopt(
              new io.aeyer.plowshare.server.blobs.BlobSource(
                  new io.aeyer.plowshare.server.blobs.BlobKey(
                      "projects/" + project, receipt.sha256()),
                  "projects/" + project + "/images/" + receipt.imageId() + ".png",
                  receipt.byteLength()),
              receipt.imageId(),
              ImageBlobAccess.retained(receipt.imageId()),
              ArtifactAdmissionsTest.NOW);
          return null;
        });
  }

  private io.aeyer.plowshare.protocol.ToolContent.Body body(String imageId) {
    return new io.aeyer.plowshare.protocol.ToolContent.Body(
        "content/1",
        java.util.List.of(
            io.aeyer.plowshare.protocol.ToolContent.Part.text("before"),
            new io.aeyer.plowshare.protocol.ToolContent.Part(
                io.aeyer.plowshare.protocol.ToolContent.Kind.IMAGE,
                null,
                imageId,
                0,
                io.aeyer.plowshare.protocol.ToolContent.Use.OBSERVATION),
            io.aeyer.plowshare.protocol.ToolContent.Part.text("after")));
  }

  private io.aeyer.plowshare.server.relay.tools.JdbcRelayToolRepository contentRepository() {
    var b = intent.binding();
    var q = intent.request();
    var binding =
        new io.aeyer.plowshare.server.relay.tools.RelayToolDefinition(
            b.project(),
            b.provider(),
            b.account(),
            b.name(),
            b.description(),
            b.parameters(),
            b.timeoutSeconds(),
            b.inputSchema(),
            "content/1");
    var request =
        new RelayToolCodec.Request(
            RelayToolCodec.CONTENT_VERSION,
            q.invocationId(),
            q.project(),
            q.provider(),
            q.tool(),
            q.account(),
            q.run(),
            q.call(),
            q.deadline(),
            q.arguments(),
            q.schemaFingerprint(),
            "content/1");
    intent =
        new RelayToolRepository.Intent(
            intent.id(),
            intent.projectId(),
            intent.fingerprint(),
            binding,
            request,
            intent.occurredAt(),
            intent.causation());
    jdbc.update(
        "UPDATE relay_tool_invocations SET binding=?,request=? WHERE id=?",
        RelayToolCodec.write(binding),
        RelayToolCodec.write(request),
        intent.id());
    io.aeyer.plowshare.server.archive.UnitOfWork work =
        new io.aeyer.plowshare.server.archive.UnitOfWork() {
          @Override
          public <T> T inTransaction(java.util.function.Supplier<T> action) {
            return transactions.execute(status -> action.get());
          }
        };
    return new io.aeyer.plowshare.server.relay.tools.JdbcRelayToolRepository(
        jdbc,
        work,
        org.mockito.Mockito.mock(io.aeyer.plowshare.server.relay.Relay.class),
        io.aeyer.plowshare.server.relay.tools.HostedInvocationAuthority.NONE,
        repository);
  }

  private RelayToolCodec.Result result(io.aeyer.plowshare.protocol.ToolContent.Body body) {
    return new RelayToolCodec.Result(
        RelayToolCodec.CONTENT_VERSION,
        intent.id().toString(),
        intent.binding().project(),
        intent.binding().provider(),
        intent.binding().name(),
        RelayToolCodec.State.COMPLETED,
        body.text(),
        body);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"image/jpeg", "image/webp"})
  void validated_format_round_trips_and_is_part_of_retry_identity(String mime) {
    var candidate =
        new ImageAdmissions.Receipt(
            receipt.request(),
            receipt.project(),
            receipt.owner(),
            receipt.producer(),
            receipt.imageId(),
            mime,
            receipt.width(),
            receipt.height(),
            receipt.byteLength(),
            receipt.sha256(),
            receipt.admittedAt());
    transactions.execute(status -> repository.commit(intent, candidate));
    assertEquals(candidate, repository.find(candidate.request()).orElseThrow());
    assertThrows(
        CallerFault.class,
        () -> transactions.execute(status -> repository.commit(intent, receipt)));
    assertEquals(candidate, repository.find(candidate.request()).orElseThrow());
  }

  @Test
  void typed_completion_reuses_the_admission_claim_and_survives_repository_restart() {
    var tools = contentRepository();
    transactions.execute(status -> repository.commit(intent, receipt));
    var completed = result(body(receipt.imageId()));
    assertEquals(completed, tools.finish(intent, completed).result());
    assertEquals(completed, tools.finish(intent, completed).result());
    assertEquals(
        completed, tools.find(intent.projectId(), "owner", intent.id()).orElseThrow().result());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM binary_blob_claims WHERE kind='invocation-admission'",
            Integer.class));
    var changed =
        new io.aeyer.plowshare.protocol.ToolContent.Body(
            "content/1",
            java.util.List.of(
                io.aeyer.plowshare.protocol.ToolContent.Part.text("changed"),
                body(receipt.imageId()).images().getFirst()));
    assertThrows(IllegalStateException.class, () -> tools.finish(intent, result(changed)));
  }

  @Test
  void foreign_or_missing_admission_cannot_settle_or_acquire_a_claim() {
    var tools = contentRepository();
    assertThrows(CallerFault.class, () -> tools.finish(intent, result(body(receipt.imageId()))));
    transactions.execute(status -> repository.commit(intent, receipt));
    assertThrows(
        CallerFault.class, () -> tools.finish(intent, result(body("img_" + "b".repeat(32)))));
    jdbc.update("DELETE FROM binary_blob_claims WHERE kind='invocation-admission'");
    assertThrows(CallerFault.class, () -> tools.finish(intent, result(body(receipt.imageId()))));
    org.junit.jupiter.api.Assertions.assertNull(
        tools.find(intent.projectId(), "owner", intent.id()).orElseThrow().result());
  }

  @Test
  void result_completion_rollback_keeps_the_original_admission_and_unsettled_invocation() {
    var tools = contentRepository();
    transactions.execute(status -> repository.commit(intent, receipt));
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.execute(
                status -> {
                  tools.finish(intent, result(body(receipt.imageId())));
                  throw new IllegalStateException("fixture rollback");
                }));
    org.junit.jupiter.api.Assertions.assertNull(
        tools.find(intent.projectId(), "owner", intent.id()).orElseThrow().result());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM binary_blob_claims WHERE kind='invocation-admission'",
            Integer.class));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            tools.finish(
                intent,
                new RelayToolCodec.Result(
                    RelayToolCodec.VERSION,
                    intent.id().toString(),
                    intent.binding().project(),
                    intent.binding().provider(),
                    intent.binding().name(),
                    RelayToolCodec.State.COMPLETED,
                    "legacy result")));
  }

  @Test
  void receipt_round_trips_and_identical_retry_is_one_invocation_hold() {
    assertEquals(receipt, transactions.execute(status -> repository.commit(intent, receipt)));
    assertEquals(receipt, repository.find(receipt.request()).orElseThrow());
    assertEquals(receipt, transactions.execute(status -> repository.commit(intent, receipt)));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM invocation_image_admissions", Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM binary_blob_claims WHERE kind='invocation-admission'",
            Integer.class));
    assertThrows(IllegalStateException.class, () -> repository.commit(intent, receipt));
  }

  @Test
  void conflicting_reuse_and_rollback_do_not_change_the_original_receipt() {
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.execute(
                status -> {
                  repository.commit(intent, receipt);
                  throw new IllegalStateException("rollback");
                }));
    assertTrue(repository.find(receipt.request()).isEmpty());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM binary_blob_claims WHERE kind='invocation-admission'",
            Integer.class));
    transactions.execute(status -> repository.commit(intent, receipt));
    var conflict =
        new ImageAdmissions.Receipt(
            receipt.request(),
            "atlas",
            "owner",
            "producer",
            "img_" + "b".repeat(32),
            "image/png",
            3,
            2,
            128,
            "b".repeat(64),
            receipt.admittedAt());
    assertThrows(
        CallerFault.class,
        () -> transactions.execute(status -> repository.commit(intent, conflict)));
    assertEquals(receipt, repository.find(receipt.request()).orElseThrow());
  }

  @Test
  void database_constraints_reject_bad_descriptor_and_foreign_invocation() {
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO invocation_image_admissions VALUES(?,0,?,'img_'||repeat('a',32),repeat('b',64),3,2,128,now(),'image/png')",
                intent.id(),
                UUID.randomUUID()));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO invocation_image_admissions VALUES(?,0,?,'img_'||repeat('a',32),repeat('a',64),3,2,128,now(),'image/png')",
                UUID.randomUUID(),
                UUID.randomUUID()));
  }

  @Test
  void admission_waits_for_settlement_and_rejects_the_new_slot_after_commit() throws Exception {
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var settle =
          executor.submit(
              () ->
                  transactions.execute(
                      status -> {
                        jdbc.queryForObject(
                            "SELECT id FROM relay_tool_invocations WHERE id=? FOR UPDATE",
                            UUID.class,
                            intent.id());
                        locked.countDown();
                        try {
                          if (!release.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("release timeout");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError(interrupted);
                        }
                        jdbc.update(
                            "UPDATE relay_tool_invocations SET result='settled',completed_at=now() WHERE id=?",
                            intent.id());
                        return true;
                      }));
      assertTrue(locked.await(10, TimeUnit.SECONDS));
      var admitted =
          executor.submit(() -> transactions.execute(status -> repository.commit(intent, receipt)));
      release.countDown();
      assertTrue(settle.get(10, TimeUnit.SECONDS));
      var failure =
          assertThrows(ExecutionException.class, () -> admitted.get(10, TimeUnit.SECONDS));
      assertInstanceOf(CallerFault.class, failure.getCause());
      assertTrue(repository.find(receipt.request()).isEmpty());
    }
  }

  @Test
  void deleting_invocation_removes_its_logical_hold_without_a_disk_delete() {
    transactions.execute(status -> repository.commit(intent, receipt));
    jdbc.update("DELETE FROM relay_tool_invocations WHERE id=?", intent.id());
    assertTrue(repository.find(receipt.request()).isEmpty());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM binary_blob_claims WHERE kind='invocation-admission'",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM binary_blob_claims WHERE kind='image-retention'", Integer.class));
  }

  @Test
  void project_cascade_releases_admission_claim_but_preserves_the_image_location() {
    transactions.execute(status -> repository.commit(intent, receipt));
    jdbc.update("DELETE FROM projects WHERE id=?", intent.projectId());
    assertTrue(repository.find(receipt.request()).isEmpty());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM binary_blob_claims WHERE kind='invocation-admission'",
            Integer.class));
    assertTrue(
        jdbc.queryForObject(
                "SELECT count(*) FROM binary_blobs WHERE scope=?",
                Integer.class,
                "projects/" + intent.projectId())
            > 0);
  }

  @Test
  void missing_blob_binding_rolls_back_the_receipt_instead_of_acknowledging_an_unclaimed_image() {
    jdbc.update("DELETE FROM binary_blob_aliases WHERE scope=?", "projects/" + intent.projectId());
    assertThrows(
        io.aeyer.plowshare.server.blobs.BlobUnavailableException.class,
        () -> transactions.execute(status -> repository.commit(intent, receipt)));
    assertTrue(repository.find(receipt.request()).isEmpty());
  }

  @Test
  void owner_delete_rollback_restores_the_admission_and_its_exact_claim() {
    transactions.execute(status -> repository.commit(intent, receipt));
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.execute(
                status -> {
                  jdbc.update("DELETE FROM relay_tool_invocations WHERE id=?", intent.id());
                  throw new IllegalStateException("fixture rollback");
                }));
    assertEquals(receipt, repository.find(receipt.request()).orElseThrow());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM binary_blob_claims WHERE kind='invocation-admission'",
            Integer.class));
  }

  @Test
  void real_disk_image_remains_readable_after_the_invocation_claim_is_released(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
    var registry = new io.aeyer.plowshare.server.blobs.JdbcBlobRepository(jdbc);
    var work =
        new io.aeyer.plowshare.server.archive.UnitOfWork() {
          @Override
          public <T> T inTransaction(java.util.function.Supplier<T> operation) {
            return transactions.execute(status -> operation.get());
          }
        };
    var blobs =
        new io.aeyer.plowshare.server.blobs.DiskBinaryBlobStorage(
            root, registry, work, java.time.Clock.systemUTC(), java.time.Duration.ZERO, 4096);
    var images =
        new ImageStore(
            ignored -> root.resolve("projects/" + intent.projectId() + "/images"),
            4096,
            ImageFence.NOTHING,
            new DiskImageBlobAccess(root, blobs));
    byte[] png = ArtifactAdmissionsTest.png();
    var descriptor =
        new io.aeyer.plowshare.server.media.TikaMediaInspection(4096, 100, 100, 2)
            .inspectImage(png);
    var held =
        images.hold(io.aeyer.plowshare.protocol.Home.of("atlas"), "capture.png", png, descriptor);
    var captured =
        new ImageAdmissions.Receipt(
            receipt.request(),
            "atlas",
            "owner",
            "producer",
            held.id(),
            "image/png",
            descriptor.width(),
            descriptor.height(),
            descriptor.byteLength(),
            descriptor.sha256(),
            receipt.admittedAt());
    assertEquals(captured, transactions.execute(status -> repository.commit(intent, captured)));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM binary_blob_claims WHERE sha256=?",
            Integer.class,
            descriptor.sha256()));
    jdbc.update("DELETE FROM relay_tool_invocations WHERE id=?", intent.id());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM binary_blob_claims WHERE sha256=?",
            Integer.class,
            descriptor.sha256()));
    assertEquals(0, blobs.collect(10));
    assertTrue(
        images.available(io.aeyer.plowshare.protocol.Home.of("atlas"), held.id(), descriptor));
    assertTrue(
        images
            .dataUri(io.aeyer.plowshare.protocol.Home.of("atlas"), held.id())
            .endsWith(java.util.Base64.getEncoder().encodeToString(png)));
  }

  @Test
  void an_unsettled_invocation_retains_late_captured_output_without_an_independent_deadline() {
    var late =
        new ImageAdmissions.Receipt(
            receipt.request(),
            receipt.project(),
            receipt.owner(),
            receipt.producer(),
            receipt.imageId(),
            receipt.mimeType(),
            receipt.width(),
            receipt.height(),
            receipt.byteLength(),
            receipt.sha256(),
            ArtifactAdmissionsTest.NOW.plusSeconds(60).toString());
    assertEquals(late, transactions.execute(status -> repository.commit(intent, late)));
  }
}
