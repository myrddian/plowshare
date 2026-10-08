package io.aeyer.plowshare.server.agents;

/** Fresh agent definition authority; callers must also check authenticated project membership. */
@FunctionalInterface
public interface ToolGrants {
  boolean permits(Long project, String account, String agent, String session, String tool);

  /** Fresh opt-in; implementations must resolve the authenticated caller's definition. */
  default boolean acceptsDynamic(Long project, String account, String agent, String session) {
    return false;
  }
}
