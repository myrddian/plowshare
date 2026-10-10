package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.ScheduledWork;
import java.util.List;
import java.util.Optional;

/** Authenticated schedule authoring and reconciliation over the existing durable event runtime. */
public interface ScheduleDefinitions {
  @FunctionalInterface
  interface Authority {
    void validate(ScheduleDefinitionStore.Source source, ScheduledWork definition);
  }

  /** Writes desired configuration first; reconciliation recovers a crash before projection. */
  ScheduledWork.File save(String account, ScheduledWork.Save request);

  /** Registers one owned folder and requires a complete, authorized scan. */
  List<ScheduledWork.File> sync(String account, ScheduledWork.Sync request);

  List<ScheduledWork.File> list(String account);

  /** Suspends unavailable sources without treating an incomplete scan as deletion. */
  void poll();

  boolean requiresDefinition(String internal, String account);

  Optional<ScheduledWork.File> managed(String internal, String account);

  Optional<ScheduleDefinitionStore.Source> sourceOf(String internal, String account);

  /**
   * Server sources are sessionless; workspace sources require the current authenticated provider.
   */
  String executionSession(ScheduleDefinitionStore.Source source);

  /**
   * Controls update both runtime projections. Mutable sources also rewrite desired files; deployed
   * Applications retain an operational override without modifying the release.
   */
  boolean pause(String internal, boolean paused, String account);

  boolean forget(String internal, String account);
}
