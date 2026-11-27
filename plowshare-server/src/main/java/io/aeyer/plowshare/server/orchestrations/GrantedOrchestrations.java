package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import java.util.Map;
import java.util.Optional;

/** Reachable definitions explicitly granted to the caller agent, resolved under live ownership. */
public interface GrantedOrchestrations {
  Map<String, OrchestrationDefinition> granted(
      AgentDefinition agent, Home home, String conversation, String session, String handle);

  /** Resolves one exact grant using an already resolved conversation owner and project. */
  Optional<OrchestrationDefinition> granted(
      AgentDefinition agent, DefinitionResolver.Caller caller, String name);
}
