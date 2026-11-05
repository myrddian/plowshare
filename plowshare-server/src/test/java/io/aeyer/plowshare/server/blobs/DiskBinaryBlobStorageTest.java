package io.aeyer.plowshare.server.blobs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskBinaryBlobStorageTest {
  static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
  static final BlobClaim FIRST = claim("first");
  static final BlobClaim SECOND = claim("second");
  @TempDir Path root;
  MemoryBlobRepository repository;
  DiskBinaryBlobStorage storage;
  BlobSource source;
  Path file;
  byte[] bytes = new byte[] {1, 2, 3, 4, 5};

  @BeforeEach
  void fixture() throws Exception {
    repository = new MemoryBlobRepository();
    storage = storage(repository, Duration.ZERO);
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    source =
        new BlobSource(
            new BlobKey("projects/1", digest), "projects/1/images/fixture.bin", bytes.length);
    file = root.resolve(source.file());
    Files.createDirectories(file.getParent());
    Files.write(file, bytes);
  }

  DiskBinaryBlobStorage storage(BlobRepository repo, Duration grace) {
    return new DiskBinaryBlobStorage(
        root, repo, UnitOfWork.NONE, Clock.fixed(NOW, ZoneOffset.UTC), grace, 4096);
  }

  static BlobClaim claim(String owner) {
    return new BlobClaim("fixture", owner, 0, BlobClaim.Retention.RELEASABLE);
  }

  @Test
  void adoption_reuses_file_and_retries_reuse_one_claim() throws Exception {
    var modified = Files.getLastModifiedTime(file);
    storage.adopt(source, "held", FIRST);
    storage.adopt(source, "held", FIRST);
    storage.acquire(source.key(), SECOND);
    assertEquals(2, repository.claimCount());
    assertEquals(modified, Files.getLastModifiedTime(file));
    try (var files = Files.list(file.getParent())) {
      assertEquals(List.of(file), files.toList());
    }
    assertArrayEquals(bytes, storage.read("projects/1", "held", SECOND).orElseThrow());
  }

  @Test
  void unknown_alias_is_distinct_from_foreign_or_corrupt_managed_bytes() throws Exception {
    assertTrue(storage.read("projects/1", "held", FIRST).isEmpty());
    storage.adopt(source, "held", FIRST);
    assertThrows(BlobUnavailableException.class, () -> storage.read("projects/1", "held", SECOND));
    assertTrue(storage.read("projects/2", "held", FIRST).isEmpty());
    Files.write(file, new byte[] {1, 2, 3, 4, 6});
    assertThrows(BlobUnavailableException.class, () -> storage.read("projects/1", "held", FIRST));
    assertThrows(BlobUnavailableException.class, () -> storage.adopt(source, "held", FIRST));
  }

  @Test
  void zero_claim_deletion_keeps_tombstone_and_releases_only_exact_slots() throws Exception {
    storage.adopt(source, "held", FIRST);
    storage.acquire(source.key(), SECOND);
    storage.release(source.key(), FIRST);
    storage.release(source.key(), FIRST);
    assertEquals(0, storage.collect(10));
    assertTrue(Files.exists(file));
    storage.release(source.key(), SECOND);
    assertEquals(1, storage.collect(10));
    assertFalse(Files.exists(file));
    assertEquals(0, storage.collect(10));
    assertThrows(BlobUnavailableException.class, () -> storage.read("projects/1", "held", FIRST));
    assertThrows(BlobUnavailableException.class, () -> storage.acquire(source.key(), FIRST));
    Files.write(file, bytes);
    storage.adopt(source, "held", FIRST);
    assertEquals(2, repository.bound("projects/1", "held").orElseThrow().generation());
    assertArrayEquals(bytes, storage.read("projects/1", "held", FIRST).orElseThrow());
  }

  @Test
  void pinned_images_and_unknown_files_are_not_collectible() throws Exception {
    var pinned = new BlobClaim("image-retention", "image", 0, BlobClaim.Retention.PINNED);
    storage.adopt(source, "held", pinned);
    Files.write(file.resolveSibling("unknown.bin"), bytes);
    assertThrows(IllegalArgumentException.class, () -> storage.release(source.key(), pinned));
    assertThrows(
        BlobUnavailableException.class,
        () ->
            storage.release(
                source.key(),
                new BlobClaim(
                    pinned.kind(), pinned.owner(), pinned.slot(), BlobClaim.Retention.RELEASABLE)));
    assertEquals(0, storage.collect(10));
    assertTrue(Files.exists(file.resolveSibling("unknown.bin")));
    assertTrue(Files.exists(file));
  }

  @Test
  void recovery_grace_and_interrupted_unlink_are_reconcilable() throws Exception {
    storage.adopt(source, "held", FIRST);
    storage.release(source.key(), FIRST);
    assertEquals(0, storage(repository, Duration.ofMinutes(10)).collect(10));
    var marker = repository.deletions(NOW, 1).getFirst();
    assertThrows(BlobUnavailableException.class, () -> storage.acquire(source.key(), SECOND));
    Files.delete(file); // Crash after unlink, before the SQL tombstone commit.
    assertEquals(1, storage(repository, Duration.ofMinutes(10)).collect(10));
    assertFalse(repository.deleting(marker));
  }

  @Test
  void marked_deletion_retries_after_restart_without_losing_other_files() throws Exception {
    storage.adopt(source, "held", FIRST);
    storage.release(source.key(), FIRST);
    repository.deletions(NOW, 1);
    Files.write(file.resolveSibling("other.bin"), bytes);
    assertEquals(1, storage(repository, Duration.ofDays(1)).collect(10));
    assertTrue(Files.exists(file.resolveSibling("other.bin")));
  }

  @Test
  void paths_symlinks_and_byte_bounds_fail_before_registry_publication() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> new BlobSource(source.key(), "projects/1/../file", 1));
    assertThrows(
        IllegalArgumentException.class, () -> new BlobSource(source.key(), "projects/2/file", 1));
    assertThrows(
        IllegalArgumentException.class, () -> new BlobKey("projects/../2", source.key().sha256()));
    assertThrows(IllegalArgumentException.class, () -> new BlobKey("global", "a".repeat(32)));
    assertThrows(
        BlobUnavailableException.class,
        () ->
            new DiskBinaryBlobStorage(
                    root, repository, UnitOfWork.NONE, Clock.systemUTC(), Duration.ZERO, 1)
                .adopt(source, "held", FIRST));
    Path outside = Files.createTempFile(root, "outside", ".bin");
    Files.write(outside, bytes);
    Files.delete(file);
    Files.createSymbolicLink(file, outside);
    assertThrows(BlobUnavailableException.class, () -> storage.adopt(source, "held", FIRST));
    Files.delete(file);
    Files.delete(file.getParent());
    Files.createSymbolicLink(file.getParent(), root);
    assertThrows(BlobUnavailableException.class, () -> storage.adopt(source, "held", FIRST));
    assertEquals(0, repository.claimCount());
  }

  @Test
  void active_read_completes_before_last_claim_cleanup_can_unlink() throws Exception {
    var reading = new CountDownLatch(1);
    var resumeRead = new CountDownLatch(1);
    var marked = new CountDownLatch(1);
    var blocking =
        new MemoryBlobRepository() {
          @Override
          public boolean readable(Entry entry, BlobClaim claim) {
            boolean allowed = super.readable(entry, claim);
            reading.countDown();
            await(resumeRead);
            return allowed;
          }

          @Override
          public List<Entry> deletions(Instant through, int limit) {
            var entries = super.deletions(through, limit);
            marked.countDown();
            return entries;
          }
        };
    var service = storage(blocking, Duration.ZERO);
    service.adopt(source, "held", FIRST);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var read = executor.submit(() -> service.read("projects/1", "held", FIRST).orElseThrow());
      try {
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        service.release(source.key(), FIRST);
        var collect = executor.submit(() -> service.collect(1));
        assertTrue(marked.await(5, TimeUnit.SECONDS));
        assertFalse(collect.isDone());
        assertTrue(Files.exists(file));
        resumeRead.countDown();
        assertArrayEquals(bytes, read.get(5, TimeUnit.SECONDS));
        assertEquals(1, collect.get(5, TimeUnit.SECONDS));
      } finally {
        resumeRead.countDown();
      }
    }
    assertFalse(Files.exists(file));
  }

  @Test
  void a_stale_sweeper_cannot_unlink_a_newly_registered_generation() throws Exception {
    var selected = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var firstPass = new java.util.concurrent.atomic.AtomicBoolean(true);
    var delayed =
        new MemoryBlobRepository() {
          @Override
          public List<Entry> deletions(Instant through, int limit) {
            var entries = super.deletions(through, limit);
            if (firstPass.compareAndSet(true, false)) {
              selected.countDown();
              await(resume);
            }
            return entries;
          }
        };
    var original = storage(delayed, Duration.ZERO);
    var restarted = storage(delayed, Duration.ZERO);
    original.adopt(source, "held", FIRST);
    original.release(source.key(), FIRST);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var stale = executor.submit(() -> original.collect(1));
      try {
        assertTrue(selected.await(5, TimeUnit.SECONDS));
        assertEquals(1, restarted.collect(1));
        Files.write(file, bytes);
        restarted.adopt(source, "held", SECOND);
        resume.countDown();
        assertEquals(0, stale.get(5, TimeUnit.SECONDS));
        assertArrayEquals(bytes, restarted.read("projects/1", "held", SECOND).orElseThrow());
      } finally {
        resume.countDown();
      }
    }
  }

  @Test
  void cleanup_integrity_failure_retains_a_marker_for_explicit_repair() throws Exception {
    storage.adopt(source, "held", FIRST);
    storage.release(source.key(), FIRST);
    Files.write(file, new byte[] {1, 2, 3, 4, 6});
    assertThrows(BlobUnavailableException.class, () -> storage.collect(1));
    assertTrue(Files.exists(file));
    assertEquals(
        BlobRepository.State.DELETING,
        repository.bound("projects/1", "held").orElseThrow().state());
    Files.write(file, bytes);
    assertEquals(1, storage(repository, Duration.ofMinutes(10)).collect(1));
  }

  @Test
  void an_unavailable_disk_lock_is_a_sanitized_managed_failure() throws Exception {
    storage.adopt(source, "held", FIRST);
    Path lock = file.getParent().getParent().resolveSibling(".1.image-write.lock");
    Files.delete(lock);
    Files.createSymbolicLink(lock, file);
    var failure =
        assertThrows(
            BlobUnavailableException.class, () -> storage.read("projects/1", "held", FIRST));
    assertFalse(failure.getMessage().contains(root.toString()));
    assertEquals(1, repository.claimCount());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }
}
