package io.aeyer.plowshare.server.information;

/**
 * Requires authenticated ownership and current readability of a durable log's information inputs.
 */
@FunctionalInterface
public interface InformationLogAccess {
  void requireLog(String log, String account);
}
