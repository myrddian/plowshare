package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ConfiguredAction;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import java.io.IOException;

/** Nonblocking local freshness fence for queued actions; no refresh, transport or hidden retry. */
@FunctionalInterface
public interface ActionOfferValidator {
  void requireCurrent(Reference reference, ConfiguredAction offered, OperationBudget budget)
      throws IOException;
}
