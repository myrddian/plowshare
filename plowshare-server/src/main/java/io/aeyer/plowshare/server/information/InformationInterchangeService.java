package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationRequest;
import io.aeyer.plowshare.protocol.InformationResponse;

/**
 * Bounded portable exchange. Imports use existing durable transition gates and never infer scope.
 */
public interface InformationInterchangeService {
  InformationResponse execute(String account, InformationRequest request, String session);
}
