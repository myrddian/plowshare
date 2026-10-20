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
   * The DTO alone has no durable invalidation generation. Shared read reuse requires the owning
   * projection cache's complete version/authority fences. It cannot replace current source locks,
   * execution checks or pause's expected-definition comparison. Reconciliation, pause, reject,
   * removal and source/project changes are separate dependencies.
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

  /** Lightweight source identities only: owner selection, or every registrar in one project. */
  List<Source> sourcesForInspection(String account, String project);

  /**
   * Operation-local source admission. Services establish current authority; repositories retain
   * these complete source identities and scope predicates before LIMIT. The DTO is not an
   * authorization lease.
   */
  record FileSelection(String account, String project, List<Source> sources) {
    public FileSelection {
      if (account == null || account.isBlank() || account.length() > 256)
        throw new IllegalArgumentException("Authenticated schedule account required");
      sources = sources.stream().sorted(java.util.Comparator.comparingLong(Source::id)).toList();
      if (sources.stream().map(Source::id).distinct().count() != sources.size()
          || sources.stream()
              .anyMatch(
                  s ->
                      s.id() < 1
                          || (project == null
                              ? !account.equals(s.account())
                              : !project.equals(s.project()))))
        throw new IllegalArgumentException("Schedule sources differ from the admitted selection");
    }
  }

  /**
   * Bounded typed file projection ordered by its unique internal name, under the supplied scope.
   */
  List<ScheduledWork.File> filesPage(FileSelection selection, SchedulePage page);

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
