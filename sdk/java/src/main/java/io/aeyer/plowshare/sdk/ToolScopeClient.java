package io.aeyer.plowshare.sdk;

import io.aeyer.plowshare.protocol.ToolScopes;
import java.io.IOException;
import java.util.Objects;

/** Socket-bound scope facade. No mutation is automatically replayed after uncertain delivery. */
public final class ToolScopeClient {
  private final Plowshare connection;

  public ToolScopeClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  /** Use the returned provider/account as the RelayTools provider binding. */
  public ToolScopes.Connection connect(ToolScopes.Connect request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> connectAsync(request, options));
  }

  public ToolScopes.Connections list(ToolScopes.Query request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> listAsync(request, options));
  }

  public ToolScopes.Disconnected disconnect(ToolScopes.Disconnect request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> disconnectAsync(request, options));
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<ToolScopes.Connection> connectAsync(
      ToolScopes.Connect request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync(
            "tool.scope.connect", request, ToolScopes.Connection.class, options),
        asyncResult -> {
          var result = asyncResult;
          if (!request.project().equals(result.project())
              || !request.scope().equals(result.scope())
              || !request.provider().equals(result.sourceProvider())
              || !request.prefix().equals(result.prefix())
              || !request.grants().equals(result.grants())
              || !request.agents().equals(result.agents())
              || request.leaseSeconds() != result.leaseSeconds())
            throw new IOException("Foreign tool scope connection");
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<ToolScopes.Connection> connectAsync(
      ToolScopes.Connect request) {
    return connectAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<ToolScopes.Connections> listAsync(
      ToolScopes.Query request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync("tool.scope.list", request, ToolScopes.Connections.class, options),
        asyncResult -> {
          var result = asyncResult;
          if (result.connections().stream().anyMatch(c -> !request.project().equals(c.project())))
            throw new IOException("Foreign tool scope project");
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<ToolScopes.Connections> listAsync(
      ToolScopes.Query request) {
    return listAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<ToolScopes.Disconnected> disconnectAsync(
      ToolScopes.Disconnect request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync(
            "tool.scope.disconnect", request, ToolScopes.Disconnected.class, options),
        asyncResult -> {
          var result = asyncResult;
          if (!request.project().equals(result.project())
              || !request.scope().equals(result.scope()))
            throw new IOException("Foreign disconnected tool scope");
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<ToolScopes.Disconnected> disconnectAsync(
      ToolScopes.Disconnect request) {
    return disconnectAsync(request, null);
  }
}
