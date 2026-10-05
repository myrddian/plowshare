package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.MemoryIds;
import io.aeyer.plowshare.server.agents.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable evidence that a job ran. A row with no outcome may be active or lost to a restart; only
 * the live job runtime decides whether work is running. Pruning is explicit and idempotent.
 */
public interface JobLog {
  String PREFIX = "job_";

  /** Mint without database access, using the same instant supplied to started. */
  static String newId(Instant at) {
    return MemoryIds.mint(PREFIX, java.util.Objects.requireNonNull(at));
  }

  void started(String id, String agent, Home home, Instant at);

  /** False if pruning already removed the row; counters must be nonnegative. */
  boolean ended(String id, Outcome.Ending ending, int steps, int modelCalls, Instant at);

  Optional<JobRecord> find(String id);

  List<JobRecord> all();

  int pruneStartedBefore(Instant cutoff);
}
