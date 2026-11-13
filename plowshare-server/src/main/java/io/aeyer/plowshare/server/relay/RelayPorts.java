package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Authenticated topic ingress/egress. Implementations recheck deployment grants on every call. */
public interface RelayPorts {
  /** Rechecks live ingress authority for an already identified publisher, without publishing. */
  Relay.TopicKey authorizePublisher(String account, String project, String topic);

  /** Exact deployment ingress grants under one current project-authority snapshot. */
  default Map<String, Relay.TopicKey> authorizePublishers(
      String account, String project, List<String> topics) {
    var authorized = new LinkedHashMap<String, Relay.TopicKey>();
    for (String topic : List.copyOf(topics))
      authorized.put(topic, authorizePublisher(account, project, topic));
    return Map.copyOf(authorized);
  }

  /** Binary ports require explicit deployment grants, never dynamic tool-provider privileges. */
  default Relay.TopicKey authorizeBlob(
      String account,
      String project,
      String topic,
      RelayPortProperties.Direction direction,
      String group) {
    throw new CallerFault("Relay binary ports are unavailable");
  }

  /** Reuse the existing parent inspection and effect-depth policy for either payload family. */
  default RelayCausation ancestry(
      String account, String project, String requestId, String parentTopic, String parentEventId) {
    throw new CallerFault("Relay publication ancestry is unavailable");
  }

  RelayPort.Published publish(String account, RelayPort.Publish request);

  default RelayPort.Batch consume(String account, RelayPort.Consume request) {
    return consume(account, request, RelayReadBudget.SEGMENTED);
  }

  RelayPort.Batch consume(String account, RelayPort.Consume request, RelayReadBudget budget);

  /** Connection observer validity cannot acknowledge, undo or replay an admitted batch claim. */
  default RelayPort.Batch observe(
      String account, RelayPort.Consume request, RelayReadBudget budget, Runnable validity) {
    validity.run();
    var batch = consume(account, request, budget);
    validity.run();
    return batch;
  }

  RelayPort.Acknowledged acknowledge(String account, RelayPort.Ack request);

  /** Removes only a drained SDK group with the inspected incarnation and no live batch. */
  RelayPort.GroupDeleted deleteGroup(String account, RelayPort.DeleteGroup request);

  /** One exact current-state read, including absence; never creates a cursor or lease. */
  RelayPort.GroupState inspectGroup(String account, RelayPort.GroupQuery query);
}
