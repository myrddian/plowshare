package io.aeyer.plowshare.server.agents;

import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;

/** Composition-only lazy suppliers: production refreshes; isolated contexts may provide a seed. */
public final class GlobalAgentSnapshots {
  private GlobalAgentSnapshots() {}

  /**
   * Resolve per admission/consult, not during bean construction. An absent refresh service uses the
   * context's explicit seed; a failed refresh propagates and never falls back to stale grants. The
   * seed may be absent in degraded contexts whose existing consumer reports that refusal.
   */
  public static Supplier<AgentRegistry> supply(
      ObjectProvider<GlobalAgentDefinitions> globals, ObjectProvider<AgentRegistry> seed) {
    return () -> {
      var current = globals.getIfAvailable();
      return current == null ? seed.getIfAvailable() : current.current();
    };
  }
}
