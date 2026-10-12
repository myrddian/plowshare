package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.protocol.ToolScopes;
import java.util.*;

/**
 * Existing instance scopes remain ephemeral; hosted assignments use separate revisioned mutations.
 */
public final class CombinedToolScopeConnections implements ToolScopeConnections {
  private final ToolScopeConnections local;
  private final HostedToolScopes hosted;

  public CombinedToolScopeConnections(ToolScopeConnections local, HostedToolScopes hosted) {
    this.local = local;
    this.hosted = hosted;
  }

  public ToolScopes.Connection connect(
      String actor, String session, String socket, ToolScopes.Connect request) {
    if (hosted
        .list(actor, new io.aeyer.plowshare.protocol.HostedToolScopes.Query(request.project()))
        .stream()
        .anyMatch(
            b ->
                !b.deleted()
                    && (b.definition().scope().equals(request.scope())
                        || b.definition().prefix().startsWith(request.prefix())
                        || request.prefix().startsWith(b.definition().prefix()))))
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Disconnect/change the retained hosted assignment before reusing its scope/prefix");
    return local.connect(actor, session, socket, request);
  }

  public ToolScopes.Connections list(String actor, String session, ToolScopes.Query request) {
    local.list(actor, session, request);
    return new ToolScopes.Connections(connections(request.project(), actor));
  }

  public ToolScopes.Disconnected disconnect(
      String actor, String session, ToolScopes.Disconnect request) {
    if (hosted
        .list(actor, new io.aeyer.plowshare.protocol.HostedToolScopes.Query(request.project()))
        .stream()
        .anyMatch(b -> !b.deleted() && b.definition().scope().equals(request.scope())))
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Hosted assignments require an expected generation and hosted change operation");
    return local.disconnect(actor, session, request);
  }

  public List<ToolScopes.Connection> connections(String project, String actor) {
    var values = new ArrayList<>(local.connections(project, actor));
    values.addAll(hosted.connections(project, actor));
    return List.copyOf(values);
  }

  public boolean permits(
      ToolScopes.Connection connection, String actor, String agent, String session, String tool) {
    return io.aeyer.plowshare.protocol.HostedToolScopes.hosted(connection.provider())
        ? hosted.permits(connection, actor, agent, session, tool)
        : local.permits(connection, actor, agent, session, tool);
  }
}
