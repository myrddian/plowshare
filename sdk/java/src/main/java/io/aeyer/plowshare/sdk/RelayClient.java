package io.aeyer.plowshare.sdk;

import io.aeyer.plowshare.protocol.RelayControl;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.util.Objects;

/** Broker inspection and explicit bounded processing over the authenticated WebSocket. */
public final class RelayClient {
  private final Plowshare connection;

  public RelayClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  /** Explicit ingress. Retain the request UUID and reconcile uncertain delivery without replay. */
  public RelayPort.Published publish(RelayPort.Publish request) throws IOException {
    var result = connection.exchange("relay.publish", request, RelayPort.Published.class);
    if (!request.requestId().equals(result.requestId())
        || !request.project().equals(result.project())
        || !request.topic().equals(result.topic()))
      throw new IOException("Foreign Relay publication");
    return result;
  }

  /** Bounded long-poll egress. Receiving a batch never advances the group's durable cursor. */
  public RelayPort.Batch consume(RelayPort.Consume request) throws IOException {
    var result = connection.exchange("relay.consume", request, RelayPort.Batch.class);
    if (!request.project().equals(result.project())
        || !request.topic().equals(result.topic())
        || !request.group().equals(result.group())
        || !request.consumerId().equals(result.consumerId()))
      throw new IOException("Foreign Relay consumer batch");
    if (result.events().size() > (request.limit() == null ? 100 : request.limit()))
      throw new IOException("Relay batch exceeded requested limit");
    return result;
  }

  /** Explicit whole-batch acknowledgement; stale or expired tokens are refused by the server. */
  public RelayPort.Acknowledged acknowledge(RelayPort.Ack request) throws IOException {
    var result = connection.exchange("relay.ack", request, RelayPort.Acknowledged.class);
    if (!request.project().equals(result.project())
        || !request.topic().equals(result.topic())
        || !request.group().equals(result.group())
        || !request.batchId().equals(result.batchId())
        || result.gap() != (request.expiredThrough() != null))
      throw new IOException("Foreign Relay acknowledgement");
    return result;
  }

  /** Submit one bounded pass. Uncertain transport delivery must not be retried automatically. */
  public RelayLog.Processed process(RelayLog.Process query) throws IOException {
    var result = connection.exchange("relay.process", query, RelayLog.Processed.class);
    if (!query.project().equals(result.project()))
      throw new IOException("Foreign Relay processing result");
    int limit = query.limit() == null ? 32 : query.limit();
    if (result.admitted() > limit || result.dispatched() > limit)
      throw new IOException("Relay processing exceeded requested limit");
    return result;
  }

  /** Explicit administration, bound to inspected generations. Never automatically replayed. */
  public RelayControl.Result operate(RelayControl.Request request) throws IOException {
    var result = connection.exchange("relay.operate", request, RelayControl.Result.class);
    if (!request.requestId().equals(result.requestId())
        || !request.project().equals(result.project())
        || !request.topic().equals(result.topic())
        || request.action() != result.action()
        || !Objects.equals(request.subscriber(), result.subscriber())
        || !Objects.equals(request.deliveryId(), result.deliveryId()))
      throw new IOException("Foreign Relay control result");
    return result;
  }

  public RelayLog.Topics topics(RelayLog.TopicsQuery query) throws IOException {
    var result = connection.exchange("relay.topics", query, RelayLog.Topics.class);
    scope(result.scope(), query.project(), query.system());
    if (result.topics().size() > (query.limit() == null ? 100 : query.limit()))
      throw new IOException("Relay topic page exceeded requested limit");
    return result;
  }

  public RelayLog.Page log(RelayLog.Query query) throws IOException {
    var result = connection.exchange("relay.log", query, RelayLog.Page.class);
    scope(result.scope(), query.project(), query.system());
    if (!query.topic().equals(result.topic().name())
        || !Objects.equals(query.after() == null ? "0" : query.after(), result.after()))
      throw new IOException("Foreign Relay topic log");
    int limit = query.limit() == null ? 100 : query.limit();
    if (result.events().size() > limit || result.branches().size() > limit)
      throw new IOException("Relay log page exceeded requested limit");
    return result;
  }

  private static void scope(RelayLog.Scope scope, String project, boolean system)
      throws IOException {
    if (!Objects.equals(scope.project(), project) || scope.system() != system)
      throw new IOException("Foreign Relay scope");
  }
}
