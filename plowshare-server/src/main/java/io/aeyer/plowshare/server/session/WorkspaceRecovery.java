package io.aeyer.plowshare.server.session;

/** Authenticated, capability-bound recovery of one client workspace's logical session. */
public interface WorkspaceRecovery {
  String LOGICAL_PREFIX = "workspace_";
  String FILE_PROTOCOL_PREFIX = "plowshare-file-recovery-v1.";

  /**
   * Bind a fresh transport session to its original logical session. The opaque key is supplied from
   * private client storage; it is never an authorization grant. Missing keys retain legacy session
   * isolation. A key cannot be reused for another account, machine, folder or project.
   */
  String attach(
      String key, String session, String account, String machine, String root, String project);
}
