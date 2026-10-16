package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram;

/** Refuses independent execution consumers that do not yet retain delegated authority. */
public final class RetainedAgent {
  private RetainedAgent() {}

  /** External admission requires an exported root; private children use ordinary calls grants. */
  public static void requireRoot(AgentDefinition definition) {
    if (!definition.exported()) throw new CallerFault("Choose an exported agent");
    require(definition);
  }

  public static void require(AgentDefinition definition) {
    AgentExposure.requireIntegration(definition);
    if (definition.tools().stream().anyMatch(tool -> !RetainedToolAccess.supported(tool))
        || !definition.skills().isEmpty()
        || !definition.orchestrations().isEmpty()
        || definition.board()
        || definition.dynamic()
        || definition.announcesInbox()
        || definition.reviewWith() != null
        || !AgentDefinition.Fallback.NONE.equals(definition.fallback())
        || definition.nativeOnly()
        || ScriptProgram.isScript(definition.prompt())) {
      throw new CallerFault("Agent declares capabilities that do not yet retain acting authority");
    }
  }
}
