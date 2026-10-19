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

  /** Stable persisted file address; a removed/recreated row can reuse this address. */
  record FileIdentity(long sourceId, String name) {
    public FileIdentity {
      if (sourceId < 1) throw new IllegalArgumentException("Persisted schedule source required");
      name = ScheduledWork.identity(name, "file name");
    }
  }

  /**
   * Immutable, operation-local database projection retaining source content and its operational
   * override together. ScheduledWork.version is the file schema version, not a row-change version.
   * These rows have no durable invalidation generation: this DTO must not enter a shared cache or
   * replace current source locks, authority checks or pause's expected-definition comparison.
   * Reconciliation, pause, reject, removal and source/project changes are separate dependencies.
   */
  record FileSnapshot(
      Source source,
      String name,
      String internalName,
      ScheduledWork rawDefinition,
      Boolean pausedOverride,
      String status,
      String error) {
    public FileSnapshot {
      java.util.Objects.requireNonNull(source, "source");
      new FileIdentity(source.id(), name);
      validateError(error);
      if (internalName == null
          || internalName.isBlank()
          || !internalName.equals(internalName.strip())
          || internalName.length() > 512
          || internalName.codePoints().anyMatch(Character::isISOControl)
          || !internalName.equals("scheduled-" + source.id() + "-" + name)
          || status == null
          || !java.util.Set.of("active", "refused").contains(status))
        throw new IllegalArgumentException("Invalid retained schedule file");
    }

    /** Diagnostics retain newlines, but neither oversized text nor NUL is a valid stored value. */
    static void validateError(String error) {
      if (error != null && (error.length() > 1000 || error.indexOf('\0') >= 0))
        throw new IllegalArgumentException(
            "Schedule diagnostics must be at most 1000 characters without NUL");
    }

    public FileIdentity identity() {
      return new FileIdentity(source.id(), name);
    }

    /** Full source definition and override are value records; no live runtime objects are held. */
    public ScheduledWork effectiveDefinition() {
      if (rawDefinition == null || pausedOverride == null) return rawDefinition;
      return new ScheduledWork(
          rawDefinition.version(),
          rawDefinition.cron(),
          rawDefinition.zone(),
          pausedOverride,
          rawDefinition.action(),
          rawDefinition.target(),
          rawDefinition.limits());
    }

    public ScheduledWork.File effective() {
      return new ScheduledWork.File(
          name,
          source.project(),
          source.source(),
          (source.source().equals("workspace") ? ".plowshare/" : "")
              + "schedules/"
              + name
              + ".json",
          internalName,
          effectiveDefinition(),
          status,
          error);
    }
  }

  Source register(String account, Long projectId, String source);

  List<Source> sources();

  List<ScheduledWork.File> files(Source source);

  /** Current keyed projection; transaction callers must obtain the source lock before reuse. */
  Optional<FileSnapshot> snapshot(Source source, String name);

  /**
   * Retained runtime provenance survives a removed source; it must never become a legacy trigger.
   */
  boolean requiresDefinition(String internalName, String account);

  Optional<ScheduledWork.File> managed(String internalName, String account);

  Optional<Source> sourceOf(String internalName, String account);

  /** Resolve runtime provenance only; callers must authorize before exposing or controlling it. */
  Optional<Source> sourceOf(String internalName);

  ScheduledWork.File apply(Source source, String name, ScheduledWork definition, Instant now);

  /**
   * Invalid/unavailable sources suspend old work and refuse queued firings; a valid read recovers
   * it.
   */
  void reject(Source source, String name, String error);

  /**
   * Atomically retain an operational pause override and project it to schedule/trigger state.
   * Expected is the effective definition last inspected by the service; a changed definition
   * refuses this control. Reconciliation and restart preserve the override for unchanged source. A
   * changed source definition or deletion clears it. Resume skips missed occurrences.
   */
  void pause(Source source, String name, ScheduledWork expected, boolean paused, Instant now);

  void remove(Source source, String name);
}
