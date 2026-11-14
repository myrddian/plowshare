package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;
import java.util.Objects;
import java.util.UUID;

/**
 * Short atomic transactions own batch fencing and cursor acknowledgement. Never calls detectors.
 */
public interface RelayPortRepository extends RelayPublisherAccounts {
  default RelayPort.Batch consume(Relay.TopicKey topic, String account, RelayPort.Consume request) {
    return consume(topic, account, request, RelayReadBudget.SEGMENTED);
  }

  RelayPort.Batch consume(
      Relay.TopicKey topic, String account, RelayPort.Consume request, RelayReadBudget budget);

  /**
   * In-flight long polls retain the first cursor's incarnation, never resurrect a deleted group.
   */
  record Consumed(RelayPort.Batch batch, UUID generation) {
    public Consumed {
      Objects.requireNonNull(batch);
      Objects.requireNonNull(generation);
    }
  }

  /**
   * Issues/recovers a batch under the topic lock. A null expected generation is only for the first
   * read; later reads must match that cursor incarnation or fail without committing a replacement.
   * Waiting happens outside this transaction, and no acknowledgement is implied by the snapshot.
   */
  Consumed consumeObserved(
      Relay.TopicKey topic,
      String account,
      RelayPort.Consume request,
      RelayReadBudget budget,
      UUID expectedGeneration);

  RelayPort.Acknowledged acknowledge(Relay.TopicKey topic, String account, RelayPort.Ack request);

  /** Removes only a drained SDK group with the inspected incarnation and no live batch. */
  RelayPort.GroupDeleted deleteGroup(
      Relay.TopicKey topic, String account, RelayPort.DeleteGroup request);

  /** One exact current-state read, including absence; never creates a cursor or lease. */
  RelayPort.GroupState inspectGroup(
      Relay.TopicKey topic, String account, RelayPort.GroupQuery query);
}
