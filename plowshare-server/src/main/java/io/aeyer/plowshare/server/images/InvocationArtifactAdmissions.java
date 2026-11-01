package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ImageAdmissions;
import io.aeyer.plowshare.protocol.ImageFormat;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.media.ImageDescriptor;
import io.aeyer.plowshare.server.media.MediaInspection;
import java.time.Clock;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Decoding/disk I/O precede the receipt transaction; a crash can leave bytes, never a false
 * receipt.
 */
public final class InvocationArtifactAdmissions implements ArtifactAdmissions {
  private final ArtifactAuthority authority;
  private final ArtifactRepository repository;
  private final ImageSnapshots images;
  private final MediaInspection inspection;
  private final UnitOfWork work;
  private final Clock clock;
  private final BooleanSupplier enabled;

  public InvocationArtifactAdmissions(
      ArtifactAuthority authority,
      ArtifactRepository repository,
      ImageSnapshots images,
      MediaInspection inspection,
      UnitOfWork work,
      Clock clock,
      BooleanSupplier enabled) {
    this.authority = Objects.requireNonNull(authority);
    this.repository = Objects.requireNonNull(repository);
    this.images = Objects.requireNonNull(images);
    this.inspection = Objects.requireNonNull(inspection);
    this.work = Objects.requireNonNull(work);
    this.clock = Objects.requireNonNull(clock);
    this.enabled = Objects.requireNonNull(enabled);
  }

  @Override
  public ImageAdmissions.Receipt admit(
      String actor, ImageAdmissions.Request request, String filename, byte[] bytes) {
    if (!enabled.getAsBoolean()) throw new CallerFault("invocation image admission is disabled");
    Objects.requireNonNull(request);
    if (filename != null
        && (filename.isBlank()
            || filename.length() > 256
            || filename
                .codePoints()
                .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029)))
      throw new CallerFault("invalid artifact display filename");
    var stored = work.inTransaction(() -> authority.require(actor, request.invocation()));
    var existing = repository.find(request);
    if (existing.isEmpty() && stored.result() != null) {
      throw new CallerFault("invocation has already settled");
    }
    // Own the array that is both inspected and written; the inspector's private snapshot alone
    // would not prevent its caller from mutating the later disk upload.
    if (bytes != null && bytes.length > images.maxBytes())
      throw new CallerFault("artifact exceeds the image byte limit");
    byte[] snapshot = bytes == null ? null : bytes.clone();
    ImageDescriptor descriptor = inspection.inspectImage(snapshot);
    var intent = stored.intent();
    var receipt =
        new ImageAdmissions.Receipt(
            request,
            intent.binding().project(),
            intent.request().account(),
            actor,
            "img_" + descriptor.sha256().substring(0, 32),
            descriptor.mimeType(),
            descriptor.width(),
            descriptor.height(),
            descriptor.byteLength(),
            descriptor.sha256(),
            clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
    existing.ifPresent(original -> requireSame(original, receipt));
    images.hold(Home.of(receipt.project()), filename, snapshot, descriptor);
    return work.inTransaction(
        () -> {
          // This fresh read is deliberate: decode/write may have raced revocation or settlement.
          var current = authority.require(actor, request.invocation());
          if (!current.intent().equals(intent))
            throw new CallerFault("artifact invocation binding changed");
          return repository.commit(current.intent(), receipt);
        });
  }

  @Override
  public ImageAdmissions.Status status(String actor, ImageAdmissions.Request request) {
    Objects.requireNonNull(request);
    work.inTransaction(() -> authority.require(actor, request.invocation()));
    var retained = repository.find(request);
    if (retained.isEmpty()) return new ImageAdmissions.Status(ImageAdmissions.State.ABSENT, null);
    var receipt = retained.get();
    if (!actor.equals(receipt.producer()))
      throw new CallerFault("artifact producer is unavailable");
    return new ImageAdmissions.Status(
        images.available(Home.of(receipt.project()), receipt.imageId(), descriptor(receipt))
            ? ImageAdmissions.State.COMMITTED
            : ImageAdmissions.State.UNAVAILABLE,
        receipt);
  }

  static ImageDescriptor descriptor(ImageAdmissions.Receipt receipt) {
    return new ImageDescriptor(
        java.util.Arrays.stream(ImageFormat.values())
            .filter(format -> format.mediaType().equals(receipt.mimeType()))
            .findFirst()
            .orElseThrow(),
        receipt.width(),
        receipt.height(),
        receipt.byteLength(),
        receipt.sha256());
  }

  static void requireSame(ImageAdmissions.Receipt original, ImageAdmissions.Receipt candidate) {
    if (!original.request().equals(candidate.request())
        || !original.project().equals(candidate.project())
        || !original.owner().equals(candidate.owner())
        || !original.producer().equals(candidate.producer())
        || !original.imageId().equals(candidate.imageId())
        || !descriptor(original).equals(descriptor(candidate))) {
      throw new CallerFault("artifact slot was reused for different bytes or provenance");
    }
  }
}
