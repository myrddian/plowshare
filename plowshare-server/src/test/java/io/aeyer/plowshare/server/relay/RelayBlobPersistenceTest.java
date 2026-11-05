package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayBlob;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.blobs.BlobKey;
import io.aeyer.plowshare.server.blobs.BlobRepository;
import io.aeyer.plowshare.server.blobs.BlobUnavailableException;
import io.aeyer.plowshare.server.blobs.DiskBinaryBlobStorage;
import io.aeyer.plowshare.server.blobs.JdbcBlobRepository;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.images.DiskImageBlobAccess;
import io.aeyer.plowshare.server.images.ImageFence;
import io.aeyer.plowshare.server.images.ImageStore;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual migration, disk deduplication, owner transactions, retention and cursor semantics. */
@Tag("full-db")
@Testcontainers
class RelayBlobPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  static UnitOfWork work;
  static final Instant NOW = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  @TempDir Path root;
  JdbcBlobRepository registry;
  DiskBinaryBlobStorage storage;
  JdbcRelayRepository repository;
  Relay relay;
  ProjectRelayPorts ports;
  ProjectRelayBlobs blobs;
  RelayBlobProperties properties;
  Relay.TopicKey topic;
  long project;
  RelayBlob.Publish request;
  byte[] bytes;

  @BeforeAll
  static void migrate() {
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(data).target("225").load().migrate();
    jdbc = new JdbcTemplate(data);
    var template = new TransactionTemplate(new DataSourceTransactionManager(data));
    work =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> action) {
            return template.execute(status -> action.get());
          }
        };
    long id =
        jdbc.queryForObject(
            "INSERT INTO projects(name,workspace) VALUES('pre-blob','fixture') RETURNING id",
            Long.class);
    var before = new JdbcRelayRepository(jdbc, work, payloadStore());
    var key = new Relay.TopicKey(id, "legacy.text");
    before.registerTopic(key, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    before.append(
        key,
        new Relay.Draft("before", "writer", NOW, null, null, new RelayPayload.Text("preserved")),
        NOW);
    Flyway.configure().dataSource(data).load().migrate();
    assertEquals(
        "preserved",
        ((RelayPayload.Text) before.retained(key, "before").orElseThrow().event().payload())
            .text());
  }

  @BeforeEach
  void fixture() throws Exception {
    jdbc.update("DELETE FROM relay_topics WHERE scope_key<>'system'");
    jdbc.update("DELETE FROM binary_blob_claims");
    jdbc.update("DELETE FROM binary_blob_aliases");
    jdbc.update("DELETE FROM binary_blobs");
    jdbc.update("DELETE FROM projects WHERE name='binary-fixture'");
    project =
        jdbc.queryForObject(
            "INSERT INTO projects(name,workspace) VALUES('binary-fixture','fixture') RETURNING id",
            Long.class);
    topic = new Relay.TopicKey(project, "media.capture");
    registry = new JdbcBlobRepository(jdbc);
    storage =
        new DiskBinaryBlobStorage(root, registry, work, Clock.systemUTC(), Duration.ZERO, 4096);
    repository =
        new JdbcRelayRepository(jdbc, work, payloadStore(), RelayTextLimit.DEFAULT, registry);
    relay = durable(repository);
    var members = mock(ProjectMembers.class);
    var projects = mock(ProjectWorkspaces.class);
    when(members.mayWork("binary-fixture", "writer")).thenReturn(true);
    when(members.mayWork("binary-fixture", "reader")).thenReturn(true);
    when(projects.id("binary-fixture")).thenReturn(project);
    var grants = new RelayPortProperties();
    grants.setBindings(
        List.of(
            new RelayPortProperties.Binding(
                "binary-fixture",
                "media.capture",
                "writer",
                RelayPortProperties.Direction.INGRESS,
                List.of()),
            new RelayPortProperties.Binding(
                "binary-fixture",
                "media.capture",
                "reader",
                RelayPortProperties.Direction.EGRESS,
                List.of("workers"))));
    ports =
        new ProjectRelayPorts(
            relay, new JdbcRelayPortRepository(jdbc, work, relay), grants, members, projects, 8);
    properties = new RelayBlobProperties();
    properties.setEnabled(true);
    blobs = new ProjectRelayBlobs(ports, relay, storage, work, properties);
    request =
        new RelayBlob.Publish(
            UUID.randomUUID().toString(),
            "binary-fixture",
            "media.capture",
            NOW,
            "image/png",
            null,
            null,
            null);
    var output = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
        "png",
        output);
    bytes = output.toByteArray();
  }

  Relay durable(RelayRepository repository) {
    return new DurableRelay(repository, () -> NOW, ignored -> {});
  }

  RelayBlob.Published publish() {
    return blobs.publish("writer", request, bytes.length, new ByteArrayInputStream(bytes));
  }

  RelayBlob.Read reading() {
    return new RelayBlob.Read("binary-fixture", "media.capture", "workers", request.requestId());
  }

  ImageStore images() {
    return new ImageStore(
        ignored -> root.resolve("projects/" + project + "/images"),
        4096,
        ImageFence.NOTHING,
        new DiskImageBlobAccess(root, storage));
  }

  int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  Path canonical(RelayBlob.Published publication) {
    return root.resolve(
        registry
            .find(new BlobKey("projects/" + project, publication.blob().sha256()))
            .orElseThrow()
            .source()
            .file());
  }

  private static RelayPayloadStore payloadStore() {
    var configured = new RelayProperties();
    return new JdbcRelayPayloadStore(
        jdbc,
        work,
        configured.getPayloadStagingWindow(),
        configured.getPayloadDeferralCeiling(),
        configured.getSettledRetention());
  }

  @Test
  void binary_round_trip_retries_and_acknowledgement_preserve_existing_delivery_semantics() {
    var published = publish();
    assertEquals(published, publish());
    var consume =
        new RelayPort.Consume(
            "binary-fixture",
            "media.capture",
            "workers",
            UUID.randomUUID().toString(),
            RelayPort.Start.OLDEST_RETAINED,
            10,
            0);
    var batch = ports.consume("reader", consume);
    assertEquals(RelayPort.Status.DATA, batch.status());
    assertEquals(published.blob(), batch.events().getFirst().payload().blob());
    assertEquals(batch, ports.consume("reader", consume));
    assertArrayEquals(bytes, blobs.read("reader", reading()).bytes());
    ports.acknowledge(
        "reader",
        new RelayPort.Ack(
            "binary-fixture",
            "media.capture",
            "workers",
            consume.consumerId(),
            batch.batchId(),
            batch.fence(),
            null));
    assertEquals(RelayPort.Status.EMPTY, ports.consume("reader", consume).status());
    assertArrayEquals(bytes, blobs.read("reader", reading()).bytes());
    assertEquals(1, count("relay_blob_publications"));
    assertEquals(1, count("binary_blob_claims"));
    String payload =
        jdbc.queryForObject(
            "SELECT q.payload::text FROM relay_publications p JOIN relay_payloads q ON q.digest=p.payload_digest WHERE p.scope_key=?",
            String.class,
            "project:" + project);
    assertFalse(payload.contains("\"bytes\""));
    assertFalse(payload.contains("file"));
  }

  @Test
  void conflicting_bytes_or_media_type_preserve_original_publication_and_claim() {
    var original = publish();
    var changed =
        new RelayBlob.Publish(
            request.requestId(),
            request.project(),
            request.topic(),
            request.occurredAt(),
            "application/octet-stream",
            null,
            null,
            null);
    assertThrows(
        CallerFault.class,
        () -> blobs.publish("writer", changed, bytes.length, new ByteArrayInputStream(bytes)));
    assertThrows(
        CallerFault.class,
        () -> blobs.publish("writer", request, 1, new ByteArrayInputStream(new byte[] {7})));
    assertEquals(1, relay.topic(topic).lastPosition());
    assertEquals(1, count("relay_blob_publications"));
    assertEquals(original, publish());
    assertArrayEquals(bytes, blobs.read("reader", reading()).bytes());
  }

  @Test
  void expiry_releases_only_the_publication_claim_and_preserves_gap_acknowledgement()
      throws Exception {
    var original = publish();
    var file = canonical(original);
    var consume =
        new RelayPort.Consume(
            "binary-fixture",
            "media.capture",
            "workers",
            UUID.randomUUID().toString(),
            RelayPort.Start.OLDEST_RETAINED,
            10,
            0);
    var old = ports.consume("reader", consume);
    assertEquals(1, repository.prune(topic, NOW.plus(Duration.ofDays(5)), 100));
    assertEquals(0, count("binary_blob_claims"));
    assertThrows(CallerFault.class, () -> blobs.read("reader", reading()));
    var gap = ports.consume("reader", consume);
    assertEquals(RelayPort.Status.GAP, gap.status());
    assertEquals("1", gap.expiredThrough());
    assertThrows(
        CallerFault.class,
        () ->
            ports.acknowledge(
                "reader",
                new RelayPort.Ack(
                    "binary-fixture",
                    "media.capture",
                    "workers",
                    consume.consumerId(),
                    old.batchId(),
                    old.fence(),
                    null)));
    ports.acknowledge(
        "reader",
        new RelayPort.Ack(
            "binary-fixture",
            "media.capture",
            "workers",
            consume.consumerId(),
            gap.batchId(),
            gap.fence(),
            gap.expiredThrough()));
    assertEquals(1, storage.collect(10));
    assertFalse(Files.exists(file));
  }

  @Test
  void image_first_publication_reuses_legacy_file_and_expiry_does_not_release_image_owner()
      throws Exception {
    var images = images();
    var image = images.store(Home.of("binary-fixture"), "image.png", bytes);
    Path file = root.resolve("projects/" + project + "/images/" + image.fileName());
    var stamp = Files.getLastModifiedTime(file);
    var original = publish();
    assertEquals(file, canonical(original));
    assertEquals(stamp, Files.getLastModifiedTime(file));
    assertFalse(Files.exists(root.resolve("projects/" + project + "/blobs")));
    assertEquals(2, count("binary_blob_claims"));
    repository.prune(topic, NOW.plus(Duration.ofDays(5)), 10);
    assertEquals(1, count("binary_blob_claims"));
    assertEquals(0, storage.collect(10));
    assertEquals(
        "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes),
        images.dataUri(Home.of("binary-fixture"), image.id()));
    assertThrows(CallerFault.class, () -> blobs.read("reader", reading()));
  }

  @Test
  void blob_first_image_adds_sidecar_and_claim_without_a_second_byte_file() throws Exception {
    var original = publish();
    Path file = canonical(original);
    var stamp = Files.getLastModifiedTime(file);
    var images = images();
    var image = images.store(Home.of("binary-fixture"), "capture.png", bytes);
    assertEquals(stamp, Files.getLastModifiedTime(file));
    assertFalse(Files.exists(root.resolve("projects/" + project + "/images/" + image.fileName())));
    assertTrue(Files.exists(root.resolve("projects/" + project + "/images/" + image.recordName())));
    assertEquals(2, count("binary_blob_claims"));
    repository.prune(topic, NOW.plus(Duration.ofDays(5)), 10);
    assertEquals(0, storage.collect(10));
    assertTrue(
        images
            .dataUri(Home.of("binary-fixture"), image.id())
            .endsWith(Base64.getEncoder().encodeToString(bytes)));
  }

  @Test
  void project_cascade_releases_publication_claim_but_keeps_cleanup_location() throws Exception {
    var original = publish();
    Path file = canonical(original);
    jdbc.update("DELETE FROM projects WHERE id=?", project);
    assertEquals(0, count("relay_blob_publications"));
    assertEquals(0, count("binary_blob_claims"));
    assertEquals(1, count("binary_blobs"));
    assertEquals(1, storage.collect(10));
    assertFalse(Files.exists(file));
  }

  @Test
  void receipt_and_position_roll_back_if_claim_acquisition_fails() {
    BlobRepository failing = spy(registry);
    doAnswer(
            call -> {
              registry.acquire(call.getArgument(0), call.getArgument(1), call.getArgument(2));
              throw new IllegalStateException("fixture claim failure");
            })
        .when(failing)
        .acquire(any(), any(), anyLong());
    var faulty =
        durable(
            new JdbcRelayRepository(jdbc, work, payloadStore(), RelayTextLimit.DEFAULT, failing));
    var service = new ProjectRelayBlobs(ports, faulty, storage, work, properties);
    assertThrows(
        IllegalStateException.class,
        () -> service.publish("writer", request, bytes.length, new ByteArrayInputStream(bytes)));
    assertEquals(0, count("relay_blob_publications"));
    assertEquals(0, count("relay_publications"));
    assertEquals(0, count("binary_blob_claims"));
    assertEquals("1", publish().publication().position());
  }

  @Test
  void collection_winning_before_attachment_refuses_receipt_and_identical_retry_recovers() {
    BinaryBlobStorageCollecting collecting = new BinaryBlobStorageCollecting(storage);
    var service = new ProjectRelayBlobs(ports, relay, collecting, work, properties);
    assertThrows(
        BlobUnavailableException.class,
        () -> service.publish("writer", request, bytes.length, new ByteArrayInputStream(bytes)));
    assertEquals(0, count("relay_publications"));
    assertEquals(0, count("binary_blob_claims"));
    assertEquals("1", publish().publication().position());
    assertArrayEquals(bytes, blobs.read("reader", reading()).bytes());
  }

  @Test
  void concurrent_identical_publications_reconcile_one_position_and_one_claim() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(this::publish);
      var second = executor.submit(this::publish);
      assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }
    assertEquals(1, count("relay_publications"));
    assertEquals(1, count("relay_blob_publications"));
    assertEquals(1, count("binary_blob_claims"));
  }

  /** Only prepare is used by this negative service test; all other capabilities fail closed. */
  static final class BinaryBlobStorageCollecting
      implements io.aeyer.plowshare.server.blobs.BinaryBlobStorage {
    private final DiskBinaryBlobStorage actual;

    BinaryBlobStorageCollecting(DiskBinaryBlobStorage actual) {
      this.actual = actual;
    }

    @Override
    public BlobKey prepare(String scope, byte[] bytes) {
      var key = actual.prepare(scope, bytes);
      assertEquals(1, actual.collect(10));
      return key;
    }

    @Override
    public void store(
        io.aeyer.plowshare.server.blobs.BlobSource s,
        byte[] b,
        String a,
        io.aeyer.plowshare.server.blobs.BlobClaim c) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void adopt(
        io.aeyer.plowshare.server.blobs.BlobSource s,
        String a,
        io.aeyer.plowshare.server.blobs.BlobClaim c) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Optional<BlobKey> resolve(String s, String a) {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] read(BlobKey k, io.aeyer.plowshare.server.blobs.BlobClaim c) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Optional<byte[]> read(
        String s, String a, io.aeyer.plowshare.server.blobs.BlobClaim c) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void acquire(BlobKey k, io.aeyer.plowshare.server.blobs.BlobClaim c) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void release(BlobKey k, io.aeyer.plowshare.server.blobs.BlobClaim c) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int collect(int limit) {
      throw new UnsupportedOperationException();
    }
  }
}
