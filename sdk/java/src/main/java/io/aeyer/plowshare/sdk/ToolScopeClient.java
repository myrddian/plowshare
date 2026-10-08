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
    var result = connection.exchange("tool.scope.connect", request, ToolScopes.Connection.class);
    if (!request.project().equals(result.project())
        || !request.scope().equals(result.scope())
        || !request.provider().equals(result.sourceProvider())
        || !request.prefix().equals(result.prefix())
        || !request.grants().equals(result.grants())
        || !request.agents().equals(result.agents())
        || request.leaseSeconds() != result.leaseSeconds())
      throw new IOException("Foreign tool scope connection");
    return result;
  }

  public ToolScopes.Connections list(ToolScopes.Query request) throws IOException {
    var result = connection.exchange("tool.scope.list", request, ToolScopes.Connections.class);
    if (result.connections().stream().anyMatch(c -> !request.project().equals(c.project())))
      throw new IOException("Foreign tool scope project");
    return result;
  }

  public ToolScopes.Disconnected disconnect(ToolScopes.Disconnect request) throws IOException {
    var result =
        connection.exchange("tool.scope.disconnect", request, ToolScopes.Disconnected.class);
    if (!request.project().equals(result.project()) || !request.scope().equals(result.scope()))
      throw new IOException("Foreign disconnected tool scope");
    return result;
  }
}
