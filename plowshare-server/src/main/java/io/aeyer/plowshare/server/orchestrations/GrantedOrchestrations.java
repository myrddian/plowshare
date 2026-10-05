package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import java.util.Map;

/** Reachable definitions explicitly granted to the caller agent, resolved under live ownership. */
public interface GrantedOrchestrations {
  Map<String, OrchestrationDefinition> granted(
      AgentDefinition agent, Home home, String conversation, String session, String handle);
}
