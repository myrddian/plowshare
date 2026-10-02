package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;

/** Resolves and persists a run's immutable ownership before any inference or delegation. */
@FunctionalInterface
public interface RunUsage {
    RunUsage NONE = (home, transcript, agent, account) -> transcript;

    Transcript start(Home home, Transcript transcript, String agent, String account);
}
