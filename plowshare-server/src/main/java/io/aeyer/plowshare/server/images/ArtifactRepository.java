package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.protocol.ImageAdmissions;
import io.aeyer.plowshare.server.relay.tools.RelayToolRepository;
import java.util.Optional;

/** Immutable invocation attachment receipts. Each row is held by its retained invocation FK. */
public interface ArtifactRepository {
  /**
   * Validate ordered result references under the caller's invocation lock. Existing admission
   * claims have the same owning lifetime; validation must not increment or duplicate them.
   */
  void validateResult(
      RelayToolRepository.Intent intent, io.aeyer.plowshare.protocol.ToolContent.Body content);

  Optional<ImageAdmissions.Receipt> find(ImageAdmissions.Request request);

  /**
   * Locks the owning invocation, rejects a new slot after settlement, and inserts one immutable
   * receipt. An identical committed retry returns its original timestamp. Conflicting reuse fails.
   * Joins the caller's transaction, which has already locked current hosted authority if
   * applicable.
   */
  ImageAdmissions.Receipt commit(
      RelayToolRepository.Intent intent, ImageAdmissions.Receipt receipt);
}
