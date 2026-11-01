package io.aeyer.plowshare.server.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ImageAdmissions;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.media.TikaMediaInspection;
import io.aeyer.plowshare.server.relay.tools.RelayToolCodec;
import io.aeyer.plowshare.server.relay.tools.RelayToolDefinition;
import io.aeyer.plowshare.server.relay.tools.RelayToolRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Admission rules use a small repository fake; real SQL/locking coverage is separately tagged. */
class ArtifactAdmissionsTest {
  static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
  @TempDir Path root;
  ImageStore images;
  ArtifactAuthority authority;
  RelayToolRepository.Stored invocation;
  ImageAdmissions.Request request;
  MemoryReceipts receipts;
  ArtifactAdmissions admissions;
  byte[] png;

  @BeforeEach
  void fixture() throws Exception {
    images = new ImageStore(home -> root.resolve(home.project()), 4096, (home, path) -> true);
    authority = mock(ArtifactAuthority.class);
    invocation = new RelayToolRepository.Stored(intent(UUID.randomUUID()), null);
    request =
        new ImageAdmissions.Request(
            invocation.intent().id(), 0, ImageAdmissions.requestId(invocation.intent().id(), 0));
    when(authority.require("producer", request.invocation())).thenReturn(invocation);
    receipts = new MemoryReceipts();
    admissions = service(receipts, images, () -> true);
    png = png();
  }

  static RelayToolRepository.Intent intent(UUID id) {
    var binding =
        new RelayToolDefinition(
            "atlas", "browser", "producer", "screenshot", "Capture", List.of(), 30);
    var request =
        new RelayToolCodec.Request(
            RelayToolCodec.VERSION,
            id.toString(),
            "atlas",
            "browser",
            "screenshot",
            "owner",
            "run",
            "call",
            NOW.plusSeconds(30).toString(),
            Map.of());
    return new RelayToolRepository.Intent(
        id,
        1L,
        RelayPort.hash("intent"),
        binding,
        request,
        NOW,
        new RelayCausation("root", "run", 1));
  }

  ArtifactAdmissions service(
      ArtifactRepository repository,
      ImageSnapshots snapshots,
      java.util.function.BooleanSupplier enabled) {
    return new InvocationArtifactAdmissions(
        authority,
        repository,
        snapshots,
        new TikaMediaInspection(4096, 100, 100, 8),
        UnitOfWork.NONE,
        Clock.fixed(NOW, ZoneOffset.UTC),
        enabled);
  }

  static byte[] png() throws Exception {
    var output = new ByteArrayOutputStream();
    var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, 0xff204060);
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"image/jpeg", "image/webp"})
  void admits_actual_format_and_reconciles_exact_receipt_without_changing_uploads(String mime)
      throws Exception {
    byte[] pixels;
    if (mime.equals("image/webp")) {
      try (var input = getClass().getResourceAsStream("/media/still-lossless.webp.base64")) {
        org.junit.jupiter.api.Assertions.assertNotNull(input);
        pixels = java.util.Base64.getMimeDecoder().decode(input.readAllBytes());
      }
    } else {
      var output = new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "jpeg", output);
      pixels = output.toByteArray();
    }
    var receipt = admissions.admit("producer", request, "misleading.png", pixels);
    assertEquals(mime, receipt.mimeType());
    assertEquals(receipt, admissions.admit("producer", request, "another.png", pixels));
    assertEquals(ImageAdmissions.State.COMMITTED, admissions.status("producer", request).state());
    assertEquals(mime, InvocationArtifactAdmissions.descriptor(receipt).mimeType());
    assertThrows(CallerFault.class, () -> admissions.admit("producer", request, "same.png", png));
  }

  @Test
  void duplicate_is_one_original_receipt_and_existing_image_identity_survives_restart() {
    var first = admissions.admit("producer", request, "first.png", png);
    assertEquals(first, admissions.admit("producer", request, "another.png", png));
    assertEquals("owner", first.owner());
    assertEquals("producer", first.producer());
    assertEquals(ImageStore.idFor(png), first.imageId());
    assertEquals(1, receipts.saved.size());
    var restarted =
        service(receipts, new ImageStore(home -> root.resolve(home.project()), 4096), () -> true);
    assertEquals(ImageAdmissions.State.COMMITTED, restarted.status("producer", request).state());
    assertEquals(
        "first.png", images.find(Home.of("atlas"), first.imageId()).orElseThrow().filename());
  }

  @Test
  void bytes_after_lost_commit_are_reconciled_without_a_new_capture_or_identity() {
    ArtifactRepository failed = mock(ArtifactRepository.class);
    when(failed.find(request)).thenReturn(Optional.empty());
    when(failed.commit(any(), any()))
        .thenThrow(new IllegalStateException("simulated lost database write"));
    assertThrows(
        IllegalStateException.class,
        () -> service(failed, images, () -> true).admit("producer", request, "capture.png", png));
    assertTrue(images.find(Home.of("atlas"), ImageStore.idFor(png)).isPresent());
    assertEquals(ImageAdmissions.State.ABSENT, admissions.status("producer", request).state());
    var committed = admissions.admit("producer", request, "capture.png", png);
    assertEquals(ImageStore.idFor(png), committed.imageId());
    assertEquals(committed, admissions.status("producer", request).receipt());
  }

  @Test
  void unavailable_held_file_is_never_substituted_with_workspace_or_other_project_bytes()
      throws Exception {
    var receipt = admissions.admit("producer", request, "capture.png", png);
    byte[] corrupted = png.clone();
    corrupted[corrupted.length - 1] ^=
        1; // Same length and UID path: only full digest detects this.
    Files.write(root.resolve("atlas").resolve(receipt.imageId() + ".png"), corrupted);
    assertEquals(ImageAdmissions.State.UNAVAILABLE, admissions.status("producer", request).state());
    assertThrows(
        IllegalStateException.class,
        () -> admissions.admit("producer", request, "capture.png", png));
  }

  @Test
  void existing_workspace_note_and_sidecar_first_crash_are_promoted_or_repaired() throws Exception {
    Path source = root.resolve("source.png");
    Files.write(source, png);
    var note = images.note(Home.of("atlas"), source.toAbsolutePath(), png);
    assertTrue(note.inWorkspace());
    var receipt = admissions.admit("producer", request, "capture.png", png);
    Files.delete(source);
    assertFalse(images.find(Home.of("atlas"), receipt.imageId()).orElseThrow().inWorkspace());
    Path held = root.resolve("atlas").resolve(receipt.imageId() + ".png");
    Files.delete(held);
    assertEquals(ImageAdmissions.State.UNAVAILABLE, admissions.status("producer", request).state());
    assertEquals(receipt, admissions.admit("producer", request, "repair.png", png));
    assertEquals(ImageAdmissions.State.COMMITTED, admissions.status("producer", request).state());
  }

  @Test
  void revoked_producer_and_disabled_feature_do_no_work() {
    when(authority.require("foreign", request.invocation())).thenThrow(new CallerFault("refused"));
    assertThrows(CallerFault.class, () -> admissions.admit("foreign", request, "image.png", png));
    assertThrows(
        CallerFault.class,
        () -> service(receipts, images, () -> false).admit("producer", request, "image.png", png));
    assertFalse(Files.exists(root.resolve("atlas")));
  }

  @Test
  void settlement_refuses_a_new_slot_but_does_not_revoke_an_existing_receipt() {
    var receipt = admissions.admit("producer", request, "capture.png", png);
    var result =
        new RelayToolCodec.Result(
            RelayToolCodec.VERSION,
            request.invocation().toString(),
            "atlas",
            "browser",
            "screenshot",
            RelayToolCodec.State.COMPLETED,
            "done");
    when(authority.require("producer", request.invocation()))
        .thenReturn(new RelayToolRepository.Stored(invocation.intent(), result));
    var second =
        new ImageAdmissions.Request(
            request.invocation(), 1, ImageAdmissions.requestId(request.invocation(), 1));
    assertThrows(CallerFault.class, () -> admissions.admit("producer", second, "capture.png", png));
    assertEquals(receipt, admissions.admit("producer", request, "capture.png", png));
  }

  @Test
  void waiting_timeout_does_not_discard_captured_output_from_an_unsettled_invocation() {
    var late =
        new InvocationArtifactAdmissions(
            authority,
            receipts,
            images,
            new TikaMediaInspection(4096, 100, 100, 2),
            UnitOfWork.NONE,
            Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC),
            () -> true);
    assertEquals(
        ImageStore.idFor(png), late.admit("producer", request, "capture.png", png).imageId());
  }

  @Test
  void authority_is_rechecked_after_disk_write_before_receipt_commit() {
    when(authority.require("producer", request.invocation()))
        .thenReturn(invocation)
        .thenThrow(new CallerFault("revoked"));
    assertThrows(
        CallerFault.class, () -> admissions.admit("producer", request, "capture.png", png));
    assertTrue(receipts.saved.isEmpty());
    assertTrue(images.find(Home.of("atlas"), ImageStore.idFor(png)).isPresent());
  }

  @Test
  void conflict_and_invalid_png_cannot_replace_a_committed_slot() throws Exception {
    var first = admissions.admit("producer", request, "capture.png", png);
    assertThrows(
        RuntimeException.class,
        () -> admissions.admit("producer", request, "bad.png", new byte[12]));
    var output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
    assertThrows(
        CallerFault.class,
        () -> admissions.admit("producer", request, "different.png", output.toByteArray()));
    assertEquals(first, admissions.status("producer", request).receipt());
  }

  @Test
  void legacy_writers_and_two_store_instances_cannot_replace_the_finalized_snapshot()
      throws Exception {
    Path source = root.resolve("source.png");
    Files.write(source, png);
    ImageStore other =
        new ImageStore(home -> root.resolve(home.project()), 4096, (home, path) -> true);
    try (var threads = Executors.newFixedThreadPool(6)) {
      var calls =
          List.<Callable<Object>>of(
              () -> admissions.admit("producer", request, "capture.png", png),
              () -> admissions.admit("producer", request, "capture.png", png),
              () -> other.store(Home.of("atlas"), "legacy.png", png),
              () -> other.note(Home.of("atlas"), source.toAbsolutePath(), png));
      for (var result : threads.invokeAll(calls)) result.get(10, TimeUnit.SECONDS);
    }
    assertEquals(ImageAdmissions.State.COMMITTED, admissions.status("producer", request).state());
    assertFalse(images.find(Home.of("atlas"), ImageStore.idFor(png)).orElseThrow().inWorkspace());
  }

  static final class MemoryReceipts implements ArtifactRepository {
    @Override
    public void validateResult(
        RelayToolRepository.Intent intent, io.aeyer.plowshare.protocol.ToolContent.Body content) {
      throw new UnsupportedOperationException("fixture only handles admissions");
    }

    final Map<ImageAdmissions.Request, ImageAdmissions.Receipt> saved = new ConcurrentHashMap<>();

    public Optional<ImageAdmissions.Receipt> find(ImageAdmissions.Request request) {
      return Optional.ofNullable(saved.get(request));
    }

    public synchronized ImageAdmissions.Receipt commit(
        RelayToolRepository.Intent intent, ImageAdmissions.Receipt receipt) {
      var old = saved.putIfAbsent(receipt.request(), receipt);
      if (old == null) return receipt;
      InvocationArtifactAdmissions.requireSame(old, receipt);
      return old;
    }
  }
}
