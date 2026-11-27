package io.aeyer.plowshare.server.relay;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Live ownership and Goal feed eligibility for registered Relay topic generations. The caller must
 * establish current Relay access to the exact scope before discovery or binding. Eligibility does
 * not confer read, subscription or execution authority.
 */
public interface RelayTopicRegistry {
  record Source(
      Relay.TopicKey key, UUID generation, RelayPayload.Kind kind, RelayTopicOwnership ownership) {
    public Source {
      Objects.requireNonNull(key);
      Objects.requireNonNull(generation);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(ownership);
    }
  }

  /** Includes internal and unclassified topics in an already authorized scope. */
  Optional<Source> find(Relay.TopicKey key);

  /** Returns an eligible source only if the selected generation still exists. */
  Optional<Source> eligible(Relay.TopicKey key, UUID generation);

  /**
   * Locks the eligible topic row through the caller's binding transaction. An exact generation
   * change or retirement cannot interleave between this check and the binding write.
   */
  Optional<Source> lockEligible(Relay.TopicKey key, UUID generation);

  /**
   * Lists at most 100 eligible sources by name. Prefix, payload kind and exclusive topic-name
   * cursor are optional. Eligibility is applied before the limit.
   */
  List<Source> eligible(
      Relay.Scope scope, String prefix, RelayPayload.Kind kind, String after, int limit);
}
