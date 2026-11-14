package io.aeyer.plowshare.server.swarm;

import io.aeyer.plowshare.server.agents.RunExtras;
import java.util.Objects;
import java.util.Optional;

/** Resolves one lifecycle-owned scheduling share without coupling the scheduler to that domain. */
@FunctionalInterface
public interface RunShareProvider {
  /** Empty means this owner does not schedule the run. Composition stops at the first selection. */
  Optional<Selection> select(RunExtras.Context context);

  /** Share and model fallback policy are one snapshot, preventing repeated persistence reads. */
  record Selection(SwarmScheduler.Share share, boolean ordinaryModels) {
    public Selection {
      Objects.requireNonNull(share, "share");
    }
  }
}
