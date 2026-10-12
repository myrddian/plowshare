package io.aeyer.plowshare.sdk;

import io.aeyer.plowshare.protocol.HostedToolScopes.*;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.util.*;

/**
 * Durable scopes and narrow delegated worker operations. Mutations are never automatically
 * replayed.
 */
public final class HostedToolScopeClient {
  private final Plowshare connection;

  public HostedToolScopeClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  private static void check(boolean valid) throws IOException {
    if (!valid) throw new IOException("Foreign hosted scope reply");
  }

  public List<Binding> list(Query request) throws IOException {
    var rows =
        connection
            .request("tool.scope.host.list", Map.of("project", request.project()))
            .requirePayload();
    check(rows.isArray() && rows.size() <= 256);
    var result = new ArrayList<Binding>();
    for (var row : rows) {
      var b = SdkJson.decode(SdkJson.mapper(), row, Binding.class);
      check(b.project().equals(request.project()));
      result.add(b);
    }
    return List.copyOf(result);
  }

  public Binding status(Status request) throws IOException {
    var b = connection.exchange("tool.scope.host.status", request, Binding.class);
    check(b.project().equals(request.project()) && b.binding().equals(request.binding()));
    return b;
  }

  public Receipt save(Save request) throws IOException {
    var r = connection.exchange("tool.scope.host.save", request, Receipt.class);
    check(
        r.requestId().equals(request.requestId())
            && r.operation().equals("tool.scope.host.save")
            && r.binding().project().equals(request.project())
            && r.binding().binding().equals(request.binding())
            && r.binding().generation()
                == (request.expectedGeneration() == null ? 1 : request.expectedGeneration() + 1)
            && r.binding().definition().equals(request.definition()));
    return r;
  }

  public Receipt change(Change request) throws IOException {
    var r = connection.exchange("tool.scope.host.change", request, Receipt.class);
    check(
        r.requestId().equals(request.requestId())
            && r.operation().equals("tool.scope.host.change")
            && r.binding().project().equals(request.project())
            && r.binding().binding().equals(request.binding())
            && r.binding().generation() == request.expectedGeneration() + 1
            && r.binding().enabled() == (request.action() == Action.ENABLE)
            && r.binding().deleted() == (request.action() == Action.DELETE));
    return r;
  }

  public Receipt receipt(ReceiptQuery request) throws IOException {
    var r = connection.exchange("tool.scope.host.receipt", request, Receipt.class);
    check(
        r.requestId().equals(request.requestId())
            && r.binding().project().equals(request.project()));
    return r;
  }

  public Registration register(Register request) throws IOException {
    var r = connection.exchange("tool.scope.worker.register", request, Registration.class);
    check(
        r.binding().binding().equals(request.binding())
            && r.binding().generation() == request.expectedGeneration()
            && r.registration().equals(request.requestId()));
    return r;
  }

  public Registration renew(Worker request) throws IOException {
    var r = connection.exchange("tool.scope.worker.renew", request, Registration.class);
    check(
        r.binding().binding().equals(request.binding())
            && r.registration().equals(request.registration()));
    return r;
  }

  public WorkerStatus workerStatus(WorkerQuery request) throws IOException {
    var r = connection.exchange("tool.scope.worker.status", request, WorkerStatus.class);
    check(r.binding().equals(request.binding()));
    return r;
  }

  public Credential credential(CredentialRequest request) throws IOException {
    var r = connection.exchange("tool.scope.worker.credential", request, Credential.class);
    check(
        r.binding().equals(request.binding())
            && r.registration().equals(request.registration())
            && r.purpose() == request.purpose()
            && Objects.equals(r.invocation(), request.invocation()));
    return r;
  }

  public Reconciliation read(Read request) throws IOException {
    var r = connection.exchange("tool.scope.worker.read", request, Reconciliation.class);
    check(
        r.binding().equals(request.binding())
            && r.registration().equals(request.registration())
            && r.invocation().equals(request.invocation()));
    return r;
  }

  public RelayPort.Published publish(Publish request) throws IOException {
    var r = connection.exchange("tool.scope.worker.publish", request, RelayPort.Published.class);
    var q = request.publication();
    check(
        r.requestId().equals(q.requestId())
            && r.project().equals(q.project())
            && r.topic().equals(q.topic()));
    return r;
  }

  public RelayPort.Batch consume(Consume request) throws IOException {
    var r = connection.exchange("tool.scope.worker.consume", request, RelayPort.Batch.class);
    var q = request.consumption();
    check(
        r.project().equals(q.project())
            && r.topic().equals(q.topic())
            && r.group().equals(q.group())
            && r.consumerId().equals(q.consumerId()));
    check(r.events().size() <= (q.limit() == null ? 100 : q.limit()));
    return r;
  }

  public RelayPort.Acknowledged acknowledge(Ack request) throws IOException {
    var r = connection.exchange("tool.scope.worker.ack", request, RelayPort.Acknowledged.class);
    var q = request.acknowledgement();
    check(
        r.project().equals(q.project())
            && r.topic().equals(q.topic())
            && r.group().equals(q.group())
            && r.batchId().equals(q.batchId()));
    return r;
  }
}
