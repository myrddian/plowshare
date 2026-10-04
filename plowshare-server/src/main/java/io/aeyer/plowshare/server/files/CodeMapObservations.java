package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileSource;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.documents.CodeProjection;
import java.util.Map;

/** Optional durable observations beside a run's code navigation cache. */
public interface CodeMapObservations {
  record Ticket(String workspace, long generation) {}

  default boolean supportsIndexes() {
    return false;
  }

  default CodeProjection cached(Home home, String key, FileSource source) {
    return null;
  }

  default CodeProjection retain(Home home, WorkspaceCodeMap.Entry file, byte[] source) {
    return null;
  }

  Ticket begin(Home home, String pattern);

  void publish(Ticket ticket, WorkspaceCodeMap.View view);

  void invalidate(Home home);

  default void completed(Home home) {}

  Map<String, Object> status(Home home);

  default void stop(Home home) {}

  CodeMapObservations NONE =
      new CodeMapObservations() {
        public Ticket begin(Home home, String pattern) {
          return null;
        }

        public void publish(Ticket ticket, WorkspaceCodeMap.View view) {}

        public void invalidate(Home home) {}

        public Map<String, Object> status(Home home) {
          return Map.of("state", "disabled");
        }
      };
}
