package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationRequest;
import io.aeyer.plowshare.protocol.InformationResponse;

/** Authenticated document navigation; runtime state is owned by the server repositories. */
public interface InformationNavigationService {
  InformationResponse execute(String account, InformationRequest request);

  /** Internal operation boundary, retained by the server rather than supplied by API payloads. */
  InformationResponse execute(
      String account,
      InformationRequest request,
      io.aeyer.plowshare.server.access.KnowledgeReadScope readBoundary);
}
