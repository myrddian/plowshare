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
    return AsyncSupport.await(connection.defaultOptions(), options -> listAsync(request, options));
  }

  public Binding status(Status request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> statusAsync(request, options));
  }

  public Receipt save(Save request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> saveAsync(request, options));
  }

  public Receipt change(Change request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> changeAsync(request, options));
  }

  public Receipt receipt(ReceiptQuery request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> receiptAsync(request, options));
  }

  public Registration register(Register request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> registerAsync(request, options));
  }

  public Registration renew(Worker request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> renewAsync(request, options));
  }

  public WorkerStatus workerStatus(WorkerQuery request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> workerStatusAsync(request, options));
  }

  public Credential credential(CredentialRequest request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> credentialAsync(request, options));
  }

  public Reconciliation read(Read request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> readAsync(request, options));
  }

  public RelayPort.Published publish(Publish request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> publishAsync(request, options));
  }

  public RelayPort.Batch consume(Consume request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> consumeAsync(request, options));
  }

  public RelayPort.Acknowledged acknowledge(Ack request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> acknowledgeAsync(request, options));
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<List<Binding>> listAsync(
      Query request, RequestOptions options) {

    return AsyncSupport.map(
        connection.requestAsync(
            "tool.scope.host.list", Map.of("project", request.project()), options),
        asyncResult -> {
          var rows = asyncResult.requirePayload();
          check(rows.isArray() && rows.size() <= 256);
          var result = new ArrayList<Binding>();
          for (var row : rows) {
            var b = SdkJson.decode(SdkJson.mapper(), row, Binding.class);
            check(b.project().equals(request.project()));
            result.add(b);
          }
          return List.copyOf(result);
        });
  }

  public java.util.concurrent.CompletionStage<List<Binding>> listAsync(Query request) {
    return listAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Binding> statusAsync(
      Status request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync("tool.scope.host.status", request, Binding.class, options),
        asyncResult -> {
          var b = asyncResult;
          check(b.project().equals(request.project()) && b.binding().equals(request.binding()));
          return b;
        });
  }

  public java.util.concurrent.CompletionStage<Binding> statusAsync(Status request) {
    return statusAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Receipt> saveAsync(
      Save request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync("tool.scope.host.save", request, Receipt.class, options),
        asyncResult -> {
          var r = asyncResult;
          check(
              r.requestId().equals(request.requestId())
                  && r.operation().equals("tool.scope.host.save")
                  && r.binding().project().equals(request.project())
                  && r.binding().binding().equals(request.binding())
                  && r.binding().generation()
                      == (request.expectedGeneration() == null
                          ? 1
                          : request.expectedGeneration() + 1)
                  && r.binding().definition().equals(request.definition()));
          return r;
        });
  }

  public java.util.concurrent.CompletionStage<Receipt> saveAsync(Save request) {
    return saveAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Receipt> changeAsync(
      Change request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync("tool.scope.host.change", request, Receipt.class, options),
        asyncResult -> {
          var r = asyncResult;
          check(
              r.requestId().equals(request.requestId())
                  && r.operation().equals("tool.scope.host.change")
                  && r.binding().project().equals(request.project())
                  && r.binding().binding().equals(request.binding())
                  && r.binding().generation() == request.expectedGeneration() + 1
                  && r.binding().enabled() == (request.action() == Action.ENABLE)
                  && r.binding().deleted() == (request.action() == Action.DELETE));
          return r;
        });
  }

  public java.util.concurrent.CompletionStage<Receipt> changeAsync(Change request) {
    return changeAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Receipt> receiptAsync(
      ReceiptQuery request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync("tool.scope.host.receipt", request, Receipt.class, options),
        asyncResult -> {
          var r = asyncResult;
          check(
              r.requestId().equals(request.requestId())
                  && r.binding().project().equals(request.project()));
          return r;
        });
  }

  public java.util.concurrent.CompletionStage<Receipt> receiptAsync(ReceiptQuery request) {
    return receiptAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Registration> registerAsync(
      Register request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync(
            "tool.scope.worker.register", request, Registration.class, options),
        asyncResult -> {
          var r = asyncResult;
          check(
              r.binding().binding().equals(request.binding())
                  && r.binding().generation() == request.expectedGeneration()
                  && r.registration().equals(request.requestId()));
          return r;
        });
  }

  public java.util.concurrent.CompletionStage<Registration> registerAsync(Register request) {
    return registerAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Registration> renewAsync(
      Worker request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync("tool.scope.worker.renew", request, Registration.class, options),
        asyncResult -> {
          var r = asyncResult;
          check(
              r.binding().binding().equals(request.binding())
                  && r.registration().equals(request.registration()));
          return r;
        });
  }

  public java.util.concurrent.CompletionStage<Registration> renewAsync(Worker request) {
    return renewAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<WorkerStatus> workerStatusAsync(
      WorkerQuery request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync("tool.scope.worker.status", request, WorkerStatus.class, options),
        asyncResult -> {
          var r = asyncResult;
          check(r.binding().equals(request.binding()));
          return r;
        });
  }

  public java.util.concurrent.CompletionStage<WorkerStatus> workerStatusAsync(WorkerQuery request) {
    return workerStatusAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Credential> credentialAsync(
      CredentialRequest request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync(
            "tool.scope.worker.credential", request, Credential.class, options),
        asyncResult -> {
          var r = asyncResult;
          check(
              r.binding().equals(request.binding())
                  && r.registration().equals(request.registration())
                  && r.purpose() == request.purpose()
                  && Objects.equals(r.invocation(), request.invocation()));
          return r;
        });
  }

  public java.util.concurrent.CompletionStage<Credential> credentialAsync(
      CredentialRequest request) {
    return credentialAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Reconciliation> readAsync(
      Read request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync("tool.scope.worker.read", request, Reconciliation.class, options),
        asyncResult -> {
          var r = asyncResult;
          check(
              r.binding().equals(request.binding())
                  && r.registration().equals(request.registration())
                  && r.invocation().equals(request.invocation()));
          return r;
        });
  }

  public java.util.concurrent.CompletionStage<Reconciliation> readAsync(Read request) {
    return readAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<RelayPort.Published> publishAsync(
      Publish request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync(
            "tool.scope.worker.publish", request, RelayPort.Published.class, options),
        asyncResult -> {
          var r = asyncResult;
          var q = request.publication();
          check(
              r.requestId().equals(q.requestId())
                  && r.project().equals(q.project())
                  && r.topic().equals(q.topic()));
          return r;
        });
  }

  public java.util.concurrent.CompletionStage<RelayPort.Published> publishAsync(Publish request) {
    return publishAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<RelayPort.Batch> consumeAsync(
      Consume request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync(
            "tool.scope.worker.consume", request, RelayPort.Batch.class, options),
        asyncResult -> {
          var r = asyncResult;
          var q = request.consumption();
          check(
              r.project().equals(q.project())
                  && r.topic().equals(q.topic())
                  && r.group().equals(q.group())
                  && r.consumerId().equals(q.consumerId()));
          check(r.events().size() <= (q.limit() == null ? 100 : q.limit()));
          return r;
        });
  }

  public java.util.concurrent.CompletionStage<RelayPort.Batch> consumeAsync(Consume request) {
    return consumeAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<RelayPort.Acknowledged> acknowledgeAsync(
      Ack request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync(
            "tool.scope.worker.ack", request, RelayPort.Acknowledged.class, options),
        asyncResult -> {
          var r = asyncResult;
          var q = request.acknowledgement();
          check(
              r.project().equals(q.project())
                  && r.topic().equals(q.topic())
                  && r.group().equals(q.group())
                  && r.batchId().equals(q.batchId()));
          return r;
        });
  }

  public java.util.concurrent.CompletionStage<RelayPort.Acknowledged> acknowledgeAsync(
      Ack request) {
    return acknowledgeAsync(request, null);
  }
}
