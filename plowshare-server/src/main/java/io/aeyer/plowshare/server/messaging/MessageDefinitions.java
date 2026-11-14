package io.aeyer.plowshare.server.messaging;

import java.util.Set;

/** Current project definition inventory under the inbox account's read authority. */
@FunctionalInterface
public interface MessageDefinitions {
  /** Missing/offline/unauthorized sources fail; only an authoritative inventory proves absence. */
  Set<String> names(String account, String project);
}
