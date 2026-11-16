package io.aeyer.plowshare.server.session;

/** Durable identity only; live sockets and execution authority remain with their owners. */
public interface WorkspaceBindingRepository {
  /** Atomically create or verify a binding; refuse any incompatible reuse of the key hash. */
  String bind(
      String hash, String session, String account, String machine, String root, String project);
}
