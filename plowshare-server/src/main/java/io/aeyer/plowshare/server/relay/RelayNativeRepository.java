package io.aeyer.plowshare.server.relay;

import java.time.Instant;
import java.util.List;

/** The built-in wake adapter owns native inbox admission and never replays unknown starts. */
public interface RelayNativeRepository {
  /** Bind only an existing queued owning wake, with its actual account/project authority. */
  void bind(Relay.TopicKey topic, RelayPayload.WakeRequested wake);

  boolean routes(String target);

  /** Keyset discovery returns active inboxes, including work surviving topic expiration. */
  List<Binding> ready(String after, int limit);

  /** Cursor advancement proves already durable native inbox admission under a live group lease. */
  void advance(RelayConsumerRepository.Lease lease, long position);

  /** Records explicit owning-inbox gap recovery before acknowledging the expired boundary. */
  void recoverGap(RelayConsumerRepository.Lease lease, long boundary);

  /** Atomically fences the native start claim, including expiry while blocked on its target. */
  boolean claim(Binding binding, RelayConsumerRepository.Lease lease, String firing, Instant at);

  /**
   * Bounded cleanup of old gap audits and offsets whose owning conversation is gone. Pending
   * inboxes are never evicted.
   */
  int prune(java.time.Duration retention, int limit);

  record Binding(
      Relay.SubscriptionKey subscription, String target, String account, long projectId) {
    public Binding {
      java.util.Objects.requireNonNull(subscription);
      if (subscription.topic().scope() != Relay.SystemScope.SERVER
          || !subscription.subscriber().startsWith("builtin.wakes."))
        throw new IllegalArgumentException("Native wake subscriptions require server scope");
      target = io.aeyer.plowshare.server.events.EventPayload.identity(target, "wake target");
      account = RelayValues.identity(account, "native account");
      if (!target.startsWith("conversation:") || target.length() <= 13 || projectId < 1)
        throw new IllegalArgumentException("Invalid native inbox binding");
    }

    public String cursor() {
      return RelaySourceRepository.cursor(subscription.topic()) + "/" + subscription.subscriber();
    }
  }
}
