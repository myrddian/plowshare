package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationRequest;
import io.aeyer.plowshare.protocol.InformationResponse;

/** Authenticated document navigation; runtime state is owned by the server repositories. */
public interface InformationNavigationService {
  InformationResponse execute(String account, InformationRequest request);
}
