package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.protocol.ToolScopes;
import java.util.List;

/** Ephemeral connection authority; Relay remains the durable owner of effects and receipts. */
public interface ToolScopeConnections {
  ToolScopes.Connection connect(
      String account, String session, String connection, ToolScopes.Connect request);

  ToolScopes.Connections list(String account, String session, ToolScopes.Query request);

  ToolScopes.Disconnected disconnect(String account, String session, ToolScopes.Disconnect request);

  /** Available, fresh owner-bound connections. Null account is internal cache invalidation only. */
  List<ToolScopes.Connection> connections(String project, String account);

  /**
   * Rechecked per call; an agent's opt-in and current project membership are checked separately.
   */
  boolean permits(
      ToolScopes.Connection connection, String account, String agent, String session, String tool);

  /** Authenticated socket identity, not merely a reusable session name. */
  @FunctionalInterface
  interface Sessions {
    boolean live(String account, String session, String connection);
  }

  ToolScopeConnections NONE =
      new ToolScopeConnections() {
        public ToolScopes.Connection connect(String a, String s, String c, ToolScopes.Connect r) {
          throw new IllegalStateException("Scope connections unavailable");
        }

        public ToolScopes.Connections list(String a, String s, ToolScopes.Query r) {
          return new ToolScopes.Connections(List.of());
        }

        public ToolScopes.Disconnected disconnect(String a, String s, ToolScopes.Disconnect r) {
          return new ToolScopes.Disconnected(r.project(), r.scope(), false);
        }

        public List<ToolScopes.Connection> connections(String p, String a) {
          return List.of();
        }

        public boolean permits(ToolScopes.Connection c, String a, String n, String s, String t) {
          return false;
        }
      };
}
