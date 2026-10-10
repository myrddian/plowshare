package io.aeyer.plowshare.server.agents;

/** Current editable global orchestrations layered over installed release resources. */
public interface GlobalOrchestrationDefinitions {
  /** Refresh file and agent dependencies before returning an immutable validated snapshot. */
  OrchestrationRegistry.Loaded current();
}
