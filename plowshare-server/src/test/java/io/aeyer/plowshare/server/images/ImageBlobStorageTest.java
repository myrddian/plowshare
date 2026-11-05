package io.aeyer.plowshare.server.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.blobs.BlobClaim;
import io.aeyer.plowshare.server.blobs.BlobKey;
import io.aeyer.plowshare.server.blobs.BlobUnavailableException;
import io.aeyer.plowshare.server.blobs.DiskBinaryBlobStorage;
import io.aeyer.plowshare.server.blobs.MemoryBlobRepository;
import io.aeyer.plowshare.server.media.TikaMediaInspection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageBlobStorageTest {
  @TempDir Path root;
  final Home home = Home.of("atlas");
  Path directory;
  MemoryBlobRepository repository;
  DiskBinaryBlobStorage blobs;
  ImageStore images;
  byte[] png;

  @BeforeEach
  void fixture() throws Exception {
    directory = root.resolve("projects/1/images");
    repository = new MemoryBlobRepository();
    blobs =
        new DiskBinaryBlobStorage(
            root, repository, UnitOfWork.NONE, Clock.systemUTC(), Duration.ZERO, 4096);
    images =
        new ImageStore(
            ignored -> directory, 4096, (h, p) -> true, new DiskImageBlobAccess(root, blobs));
    png = ArtifactAdmissionsTest.png();
  }

  @Test
  void legacy_upload_adoption_preserves_id_sidecar_bytes_and_first_write_metadata()
      throws Exception {
    var legacy = new ImageStore(ignored -> directory, 4096);
    var original = legacy.store(home, "original.png", png);
    var sidecar = Files.readAllBytes(directory.resolve(original.recordName()));
    var modified = Files.getLastModifiedTime(directory.resolve(original.fileName()));
    var adopted = images.store(home, "second-name.png", png);
    assertEquals(original, adopted);
    assertEquals(ImageStore.idFor(png), adopted.id());
    assertArrayEquals(sidecar, Files.readAllBytes(directory.resolve(original.recordName())));
    assertEquals(modified, Files.getLastModifiedTime(directory.resolve(original.fileName())));
    assertEquals(1, repository.claimCount());
    assertEquals(
        "data:image/png;base64," + Base64.getEncoder().encodeToString(png),
        images.dataUri(home, adopted.id()));
    try (var files = Files.list(directory)) {
      assertEquals(2, files.count());
    }
  }

  @Test
  void ordinary_formats_keep_signature_only_admission_and_claims_remain_idempotent() {
    byte[][] ordinary = {
      png,
      new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0},
      new byte[] {'G', 'I', 'F', '8', '9', 'a', 0},
      new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0}
    };
    for (byte[] bytes : ordinary) {
      var stored = images.store(home, "display-name", bytes);
      assertEquals(stored, images.store(home, "retry", bytes));
      assertTrue(
          images.dataUri(home, stored.id()).endsWith(Base64.getEncoder().encodeToString(bytes)));
    }
    assertEquals(ordinary.length, repository.claimCount());
  }

  @Test
  void held_snapshot_status_and_repair_use_full_integrity_without_releasing_image_pin()
      throws Exception {
    var descriptor = new TikaMediaInspection(4096, 100, 100, 2).inspectImage(png);
    var stored = images.hold(home, "capture.png", png, descriptor);
    var invocation =
        new BlobClaim("invocation-admission", "invocation", 0, BlobClaim.Retention.RELEASABLE);
    var key = new BlobKey("projects/1", descriptor.sha256());
    blobs.acquire(key, invocation);
    assertTrue(images.available(home, stored.id(), descriptor));
    blobs.release(key, invocation);
    assertEquals(0, blobs.collect(10));
    byte[] corrupted = png.clone();
    corrupted[corrupted.length - 1] ^= 1;
    Files.write(directory.resolve(stored.fileName()), corrupted);
    assertFalse(images.available(home, stored.id(), descriptor));
    assertThrows(BlobUnavailableException.class, () -> images.dataUri(home, stored.id()));
    assertThrows(BlobUnavailableException.class, () -> images.store(home, "retry", png));
    Files.delete(directory.resolve(stored.fileName()));
    assertEquals(stored.id(), images.hold(home, "repair.png", png, descriptor).id());
    assertTrue(images.available(home, stored.id(), descriptor));
    assertEquals(1, repository.claimCount());
  }

  @Test
  void workspace_notes_stay_revocable_and_promotion_does_not_read_the_workspace_source()
      throws Exception {
    Path workspace = root.resolve("workspace.png");
    Files.write(workspace, png);
    var note = images.note(home, workspace, png);
    assertTrue(note.inWorkspace());
    assertEquals(0, repository.claimCount());
    Files.delete(workspace);
    var held = images.store(home, "held.png", png);
    assertEquals(note.id(), held.id());
    assertFalse(held.inWorkspace());
    assertEquals(1, repository.claimCount());
    assertTrue(images.dataUri(home, held.id()).startsWith("data:image/png;base64,"));
  }

  @Test
  void unadopted_legacy_reads_work_and_symlink_writes_are_refused() throws Exception {
    var legacy = new ImageStore(ignored -> directory, 4096);
    var original = legacy.store(home, "legacy.png", png);
    assertTrue(images.dataUri(home, original.id()).startsWith("data:image/png;base64,"));
    assertEquals(0, repository.claimCount());
    Path target = root.resolve("outside.png");
    Files.write(target, png);
    Files.delete(directory.resolve(original.fileName()));
    Files.createSymbolicLink(directory.resolve(original.fileName()), target);
    assertThrows(BlobUnavailableException.class, () -> images.store(home, "rejected", png));
    assertEquals(0, repository.claimCount());
  }

  @Test
  void registration_failure_leaves_protected_bytes_and_retry_keeps_original_metadata()
      throws Exception {
    var failing =
        new MemoryBlobRepository() {
          @Override
          public void adopt(
              io.aeyer.plowshare.server.blobs.BlobSource source,
              String alias,
              BlobClaim claim,
              java.time.Instant now) {
            throw new IllegalStateException("fixture database failure");
          }
        };
    var failedStorage =
        new DiskBinaryBlobStorage(
            root, failing, UnitOfWork.NONE, Clock.systemUTC(), Duration.ZERO, 4096);
    var writer =
        new ImageStore(
            ignored -> directory,
            4096,
            ImageFence.NOTHING,
            new DiskImageBlobAccess(root, failedStorage));
    assertThrows(IllegalStateException.class, () -> writer.store(home, "first.png", png));
    assertEquals(0, repository.claimCount());
    var original =
        new ImageStore(ignored -> directory, 4096).find(home, ImageStore.idFor(png)).orElseThrow();
    byte[] sidecar = Files.readAllBytes(directory.resolve(original.recordName()));
    assertEquals(0, blobs.collect(10));
    assertEquals(original, images.store(home, "retry.png", png));
    assertArrayEquals(sidecar, Files.readAllBytes(directory.resolve(original.recordName())));
    assertEquals(1, repository.claimCount());
  }

  @Test
  void blob_first_image_adds_only_a_sidecar_and_reuses_the_same_canonical_file() throws Exception {
    var key = blobs.prepare("projects/1", png);
    Path canonical = root.resolve("projects/1/blobs/" + key.sha256() + ".bin");
    assertTrue(Files.exists(canonical));
    var stored = images.store(home, "capture.png", png);
    assertFalse(Files.exists(directory.resolve(stored.fileName())));
    assertTrue(Files.exists(directory.resolve(stored.recordName())));
    assertTrue(images.find(home, stored.id()).isPresent());
    assertEquals(
        "data:image/png;base64," + Base64.getEncoder().encodeToString(png),
        images.dataUri(home, stored.id()));
    assertEquals(stored, images.store(home, "retry.png", png));
    var descriptor = new TikaMediaInspection(4096, 100, 100, 2).inspectImage(png);
    assertTrue(images.available(home, stored.id(), descriptor));
    assertEquals(stored.id(), images.hold(home, "hold.png", png, descriptor).id());
    assertEquals(1, repository.claimCount());
    try (var files = Files.list(directory)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void image_first_binary_ingestion_does_not_create_a_generic_copy() throws Exception {
    var stored = images.store(home, "capture.png", png);
    var key = blobs.prepare("projects/1", png);
    assertTrue(Files.exists(directory.resolve(stored.fileName())));
    assertFalse(Files.exists(root.resolve("projects/1/blobs")));
    var owner = new BlobClaim("fixture", "binary-reader", 0, BlobClaim.Retention.RELEASABLE);
    blobs.acquire(key, owner);
    assertArrayEquals(png, blobs.read(key, owner));
    blobs.release(key, owner);
    assertEquals(0, blobs.collect(10));
  }
}
