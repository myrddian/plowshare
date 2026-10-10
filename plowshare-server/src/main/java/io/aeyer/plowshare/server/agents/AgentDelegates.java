package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Caller-scoped delegate definitions; a model cannot select the account, session or project. */
public interface AgentDelegates {
  /**
   * Current descriptions after source/read authorization; this never starts a child. A null home
   * requests current global metadata without an execution identity.
   */
  AgentRegistry visible(Home home, String session, String account);

  /**
   * Rechecks current work/session authority and resolves a delegate in the inherited home. Missing
   * or disabled definitions are empty; authorization failures are CallerFault refusals. Exported
   * status gates outside submissions, not an agent's explicitly granted calls.
   */
  Optional<AgentDefinition> find(Home home, String session, String account, String name);

  /** Isolated runtimes with one immutable registry; production uses caller-scoped resolution. */
  static AgentDelegates fixed(Supplier<AgentRegistry> registry) {
    Objects.requireNonNull(registry);
    return new AgentDelegates() {
      @Override
      public AgentRegistry visible(Home home, String session, String account) {
        return Objects.requireNonNull(registry.get(), "delegate registry");
      }

      @Override
      public Optional<AgentDefinition> find(
          Home home, String session, String account, String name) {
        return Objects.requireNonNull(registry.get(), "delegate registry").find(name);
      }
    };
  }
}
