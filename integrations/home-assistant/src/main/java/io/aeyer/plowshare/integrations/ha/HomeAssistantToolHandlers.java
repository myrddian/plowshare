package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.sdk.RelayTools;
import java.io.IOException;
import java.util.List;

/** Adapter-owned named tools. Declarations describe inputs; server scopes grant authority. */
public interface HomeAssistantToolHandlers {
  List<RelayTools.Declaration> declarations();

  /** Decode the complete SDK input before resource work; never reset the supplied budget. */
  RelayTools.Result execute(RelayTools.Call call, OperationBudget budget) throws IOException;
}
