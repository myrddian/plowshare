package io.aeyer.plowshare.server.events;

import java.util.List;

/** Bounded schedule-folder IO on the machine owning the definitions; unavailable is never empty. */
public interface ScheduleFiles {
  record Entry(String name, String text) {}

  /** Live authenticated definition session for workspace sources; server tiers are sessionless. */
  default String executionSession(ScheduleDefinitionStore.Source source) {
    return null;
  }

  /**
   * Whether this source's folder is an Application root's {@code schedules/}. Ordinary {@link
   * #write} and {@link #delete} refuse such sources; a project Manager's save reaches them only
   * through {@link #writeApplication} (ADR 0068).
   */
  default boolean applicationOwned(ScheduleDefinitionStore.Source source) {
    return false;
  }

  List<Entry> read(ScheduleDefinitionStore.Source source);

  void write(ScheduleDefinitionStore.Source source, String name, String text, boolean overwrite);

  /**
   * Writes {@code schedules/<name>.json} in place inside a non-MANAGED Application root. The caller
   * has already authorized the save and validated the definition; this owns only the file boundary:
   * the name rule, the per-file and folder limits, path containment and symlink refusal, and an
   * atomic rename so a concurrent scan never reads a partial file.
   *
   * @param overwrite {@code false} refuses an existing file; {@code true} replaces it
   * @throws IllegalArgumentException for a source that is not an Application root, an invalid name
   *     or an oversized file
   */
  void writeApplication(
      ScheduleDefinitionStore.Source source, String name, String text, boolean overwrite);

  void delete(ScheduleDefinitionStore.Source source, String name);
}
