package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ActionDescription;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Description;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Page;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Revision;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Search;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Binding-local, policy-filtered resource metadata. Discovery never grants a read or an action.
 * Implementations filter before paging/counting and never distinguish a denied reference from an
 * absent one. All calls share the caller's finite budget, including connection/queue waits.
 */
public interface ResourceDiscovery {
  /**
   * Pages one authorized metadata revision. A stale cursor is refused rather than silently reset.
   */
  Page search(Search search, OperationBudget budget) throws IOException;

  /** Uses the supplied revision; a renamed entity retains its reference but gets new metadata. */
  Optional<Description> describe(Reference reference, Revision revision, OperationBudget budget)
      throws IOException;

  /**
   * Lists configured profiles applicable to this exact reference, including explicit unsupported
   * descriptions. A discovered HA service alone is never an executable profile.
   */
  List<ActionDescription> actions(Reference reference, Revision revision, OperationBudget budget)
      throws IOException;
}
