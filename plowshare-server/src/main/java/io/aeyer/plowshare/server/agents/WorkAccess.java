package io.aeyer.plowshare.server.agents;

/** Live transport-session ownership and project work checks, without routing or agent selection. */
public interface WorkAccess {
  void requireSession(String session, String account);

  void requireWork(String project, String account);
}
