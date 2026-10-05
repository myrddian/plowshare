package io.aeyer.plowshare.server.orchestrations;

import java.util.UUID;

/** Owning idempotent start capability; retained request IDs never launch a second first turn. */
public interface OrchestrationStarts {
  OrchestrationRecord start(Orchestrations.Start start, UUID requestId, String payload);
}
