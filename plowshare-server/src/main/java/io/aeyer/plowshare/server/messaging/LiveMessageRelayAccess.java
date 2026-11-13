package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPorts;
import io.aeyer.plowshare.server.relay.RelayPublishingProperties;
import java.util.List;
import java.util.Objects;

/** Reuses one live project-authority snapshot for exact source and return deployment grants. */
public final class LiveMessageRelayAccess implements MessageRelayAccess {
  private final RelayPorts ports;
  private final RelayPublishingProperties publishing;

  public LiveMessageRelayAccess(RelayPorts ports, RelayPublishingProperties publishing) {
    this.ports = Objects.requireNonNull(ports);
    this.publishing = Objects.requireNonNull(publishing);
  }

  @Override
  public void require(MessageRelayRepository.Family family) {
    var authorized =
        ports.authorizePublishers(
            family.account(), family.project(), List.of(family.sourceTopic(), family.replyTopic()));
    if (!new Relay.TopicKey(family.projectId(), family.sourceTopic())
            .equals(authorized.get(family.sourceTopic()))
        || !family.replies().equals(authorized.get(family.replyTopic()))
        || family.executionDefinition() != null
            && !publishing.permits(
                family.project(), family.executionDefinition(), family.sourceTopic()))
      throw new MessageRefused("The native Relay source or return authority is unavailable");
  }
}
