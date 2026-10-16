package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.auth.ServiceCredentials;
import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * Exposure of trusted SYSTEM definitions. This is checked at external discovery and admission,
 * never as a global runtime permission. Server-owned SYSTEM workers keep their existing execution
 * paths. An acting request remains an integration request even when its subject is human.
 */
public final class AgentExposure {
  private AgentExposure() {}

  /** Classification belongs to the resolved definition, never its name or alias. */
  public static boolean operatorOnly(AgentDefinition definition) {
    return definition.isSystem();
  }

  /** A null account denotes an internal caller, not an Application identity. */
  public static boolean availableTo(AgentDefinition definition, String account) {
    return !ServiceCredentials.principal(account) || !operatorOnly(definition);
  }

  /** Check exposure against the request actor before external admission. */
  public static AgentDefinition requireAvailable(AgentDefinition definition, String account) {
    if (!availableTo(definition, account)) throw refused();
    return definition;
  }

  /** Retained acting work cannot gain a SYSTEM definition through its human subject. */
  public static void requireIntegration(AgentDefinition definition) {
    if (operatorOnly(definition)) throw refused();
  }

  private static CallerFault refused() {
    return new CallerFault(
        "SYSTEM agents are reserved for direct human or internal use; Applications and acting requests cannot access them");
  }
}
