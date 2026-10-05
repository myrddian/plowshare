package io.aeyer.plowshare.server.events;

import java.util.List;

/** Bounded schedule-folder IO on the machine owning the definitions; unavailable is never empty. */
public interface ScheduleFiles {
  record Entry(String name, String text) {}

  /** Live authenticated definition session for workspace sources; server tiers are sessionless. */
  default String executionSession(ScheduleDefinitionStore.Source source) {
    return null;
  }

  List<Entry> read(ScheduleDefinitionStore.Source source);

  void write(ScheduleDefinitionStore.Source source, String name, String text, boolean overwrite);

  void delete(ScheduleDefinitionStore.Source source, String name);
}
