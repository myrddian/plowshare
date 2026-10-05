package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.ScheduledWork;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable folder authority and file projections. Apply atomically commits timing, trigger and
 * source.
 */
public interface ScheduleDefinitionStore {
  record Source(long id, String account, Long projectId, String project, String source) {
    public Source {
      if (id < 0
          || account == null
          || account.isBlank()
          || account.length() > 256
          || !java.util.List.of("server", "workspace").contains(source)
          || projectId != null && projectId < 1
          || (projectId == null) != (project == null)
          || project != null && (project.isBlank() || project.length() > 256))
        throw new IllegalArgumentException("Invalid schedule source identity");
    }
  }

  Source register(String account, Long projectId, String source);

  List<Source> sources();

  List<ScheduledWork.File> files(Source source);

  /**
   * Retained runtime provenance survives a removed source; it must never become a legacy trigger.
   */
  boolean requiresDefinition(String internalName, String account);

  Optional<ScheduledWork.File> managed(String internalName, String account);

  Optional<Source> sourceOf(String internalName, String account);

  ScheduledWork.File apply(Source source, String name, ScheduledWork definition, Instant now);

  /**
   * Invalid/unavailable sources suspend old work and refuse queued firings; a valid read recovers
   * it.
   */
  void reject(Source source, String name, String error);

  void remove(Source source, String name);
}
