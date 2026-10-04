package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;

/**
 * Told when a run finishes, after its outcome is filed. A union commits what the run wrote into the
 * server's copy here (spec §4.4). {@code home} is null for a job that runs in no tier.
 */
@FunctionalInterface
public interface RunEnds {

  RunEnds NONE = (runId, agent, home) -> {};

  void ended(String runId, String agent, Home home);
}
