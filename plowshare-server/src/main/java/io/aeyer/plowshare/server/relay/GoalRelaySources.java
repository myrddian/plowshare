package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.relay.RelayTopicRegistry.Source;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Goal-facing source selection under the server-retained execution admission. */
public interface GoalRelaySources {
  /** Optional filters. The source scope always comes from the retained admission. */
  record Search(String prefix, RelayPayload.Kind kind, String after, int limit) {
    public Search {
      RelayValues.limit(limit, 100);
    }
  }

  /** A choice from discovery. A bare name cannot authorize a replacement topic. */
  record Selection(String topic, UUID generation) {
    public Selection {
      topic = RelayValues.topic(topic);
      Objects.requireNonNull(generation);
    }
  }

  /** Lists only eligible sources the retained Goal Agent can currently read. */
  List<Source> discover(Admission goal, Search search);

  /**
   * Rechecks live access and pins the selected source for the caller's current SQL transaction. The
   * caller must retain the binding in that transaction.
   */
  Source requireForBinding(Admission goal, Selection selection);

  /** Rechecks the stored source exactly before delivery into a turn. */
  Source requireForDelivery(Admission goal, Relay.TopicKey source, UUID generation);
}
