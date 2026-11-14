package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.relay.RelayConsumerRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Owning references and recovery discovery for the sole Relay child-ending consumer. */
public interface ChildCompletions {
  String TOPIC = "orchestration.child.completed";
  String GROUP = "builtin.orchestrations.children";

  /** Project IDs (zero means server/global scope), rotated by a numeric keyset. */
  List<Long> ready(long after, int limit);

  /** Bounded undelivered terminal attempts; owning records outlive broker retention. */
  List<Completion> pending(RelayConsumerRepository.Lease lease, int limit);

  /**
   * Scoped batch validation of trusted notice references against their current terminal attempts.
   */
  List<Completion> current(RelayConsumerRepository.Lease lease, List<Completion> notices);

  /** A reference, never result text or authority. */
  record Completion(long project, String child, String parent, Instant endedAt) {
    public Completion {
      if (project < 0
          || child == null
          || !child.matches("orc_[A-Za-z0-9]+")
          || parent == null
          || !parent.matches("orc_[A-Za-z0-9]+"))
        throw new IllegalArgumentException("Invalid child completion reference");
      Objects.requireNonNull(endedAt);
    }
  }
}
