package io.aeyer.plowshare.server.relay;

import java.util.Optional;
import java.util.UUID;

/** Durable owning receipt for one-way work. A started admission without a receipt is uncertain. */
public interface RelayExecutions {
  /** Commits before invoking any owning work API. Only the winner may dispatch. */
  boolean begin(RelayReceiver.Request request);

  /** Records or repairs a proven owning receipt. Repeating the identical link is idempotent. */
  void accepted(
      RelayReceiver.Request request, RelayDeliveries.Receipt receipt, String conversation);

  Optional<Accepted> find(String account, long projectId, UUID requestId);

  /** Deletes at most limit known receipts after settled retention and owning log completion. */
  int prune(java.time.Instant now, java.time.Duration retention, int limit);

  record Accepted(RelayDeliveries.Receipt receipt, String conversation) {}
}
