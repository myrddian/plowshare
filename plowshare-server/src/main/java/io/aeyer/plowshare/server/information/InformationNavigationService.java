package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationRequest;
import io.aeyer.plowshare.protocol.InformationResponse;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import io.aeyer.plowshare.server.access.OperationFence;

/** Authenticated document navigation; runtime state is owned by the server repositories. */
public interface InformationNavigationService {
  InformationResponse execute(String account, InformationRequest request);

  /** Internal operation boundary, retained by the server rather than supplied by API payloads. */
  default InformationResponse execute(
      String account, InformationRequest request, KnowledgeReadScope readBoundary) {
    return execute(account, request, readBoundary, OperationFence.none());
  }

  /**
   * As the bounded overload, and a navigation write runs {@code fence} inside its transaction after
   * the navigation and account locks and the live work check, before any receipt lookup or write.
   * Reads never run the fence.
   */
  InformationResponse execute(
      String account,
      InformationRequest request,
      KnowledgeReadScope readBoundary,
      OperationFence fence);
}
