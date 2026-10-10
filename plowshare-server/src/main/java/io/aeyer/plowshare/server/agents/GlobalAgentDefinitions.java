package io.aeyer.plowshare.server.agents;

/** Immutable current global definitions; packaged resources remain release-owned. */
public interface GlobalAgentDefinitions {
  /** Refresh editable global files before returning a snapshot; never retain stale authority. */
  AgentRegistry current();

  /** Explicit immutable seed for isolated contexts that have no writable global refresh service. */
  static GlobalAgentDefinitions fixed(AgentRegistry registry) {
    java.util.Objects.requireNonNull(registry);
    return new GlobalAgentDefinitions() {
      public AgentRegistry current() {
        return registry;
      }

      public void invalidate() {}
    };
  }

  /** Successful owning writes invalidate even edits preserving size and filesystem timestamps. */
  void invalidate();
}
