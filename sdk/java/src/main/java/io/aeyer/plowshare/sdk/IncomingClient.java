package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Incoming;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Adapter-facing durable message receipt; HTTP protocols are outside the SDK. */
public final class IncomingClient {
  private static final ObjectMapper JSON = SdkJson.mapper();

  public static final class Refused extends IOException {
    private final String code;

    public Refused(String code) {
      super("Incoming operation refused: " + code);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  private final Plowshare sdk;

  public IncomingClient(Plowshare sdk) {
    this.sdk = Objects.requireNonNull(sdk);
  }

  /** Read the receiving agent's public command catalogue through the authenticated socket. */
  public Incoming.Catalog catalog(Incoming.CatalogQuery query) throws IOException {
    return AsyncSupport.await(sdk.defaultOptions(), options -> catalogAsync(query, options));
  }

  public Incoming.Task receive(Incoming.Receive request) throws IOException {
    return AsyncSupport.await(sdk.defaultOptions(), options -> receiveAsync(request, options));
  }

  public Incoming.Task status(Incoming.Id id) throws IOException {
    return AsyncSupport.await(sdk.defaultOptions(), options -> statusAsync(id, options));
  }

  public Incoming.Task cancel(Incoming.Id id) throws IOException {
    return AsyncSupport.await(sdk.defaultOptions(), options -> cancelAsync(id, options));
  }

  private Incoming.Task call(String type, Object value) throws IOException {
    return AsyncSupport.await(sdk.defaultOptions(), options -> callAsync(type, value, options));
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Incoming.Catalog> catalogAsync(
      Incoming.CatalogQuery query, RequestOptions options) {

    Objects.requireNonNull(query, "query");
    return AsyncSupport.map(
        sdk.exchangeAsync("incoming.catalog", query, Incoming.Catalog.class, options),
        asyncResult -> {
          var catalog = asyncResult;
          if (!catalog.name().equals(query.agent()) || !catalog.served())
            throw new IOException("The configured receiving agent is not served");
          return catalog;
        });
  }

  public java.util.concurrent.CompletionStage<Incoming.Catalog> catalogAsync(
      Incoming.CatalogQuery query) {
    return catalogAsync(query, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  private java.util.concurrent.CompletionStage<Incoming.Task> callAsync(
      String type, Object value, RequestOptions options) {

    Map<String, Object> payload =
        JSON.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    return AsyncSupport.map(
        sdk.requestAsync(type, payload, options),
        asyncResult -> {
          var reply = asyncResult;
          if (!reply.successful()) throw new Refused(reply.code());
          var node = reply.requirePayload();
          var task = SdkJson.decode(JSON, node, Incoming.Task.class);
          if (task.id() == null
              || task.context() == null
              || task.createdAt() == null
              || task.replies() == null)
            throw new IOException("Malformed incoming task; outcome is unresolved");
          if (value instanceof Incoming.Receive expected
              && (!expected.agent().equals(task.agent())
                  || expected.context() != null && !expected.context().equals(task.context())
                  || !Objects.equals(expected.source(), task.source())))
            throw new IOException("Foreign incoming receipt; outcome is unresolved");
          if (value instanceof Incoming.Id expected && !expected.id().equals(task.id()))
            throw new IOException("Foreign incoming task");
          return task;
        });
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Incoming.Task> receiveAsync(
      Incoming.Receive request, RequestOptions options) {

    return AsyncSupport.map(
        callAsync("incoming.receive", Objects.requireNonNull(request, "request"), options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Incoming.Task> receiveAsync(
      Incoming.Receive request) {
    return receiveAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Incoming.Task> statusAsync(
      Incoming.Id id, RequestOptions options) {

    return AsyncSupport.map(
        callAsync("incoming.status", Objects.requireNonNull(id, "id"), options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Incoming.Task> statusAsync(Incoming.Id id) {
    return statusAsync(id, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Incoming.Task> cancelAsync(
      Incoming.Id id, RequestOptions options) {

    return AsyncSupport.map(
        callAsync("incoming.cancel", Objects.requireNonNull(id, "id"), options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Incoming.Task> cancelAsync(Incoming.Id id) {
    return cancelAsync(id, null);
  }
}
