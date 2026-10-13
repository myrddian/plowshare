package io.aeyer.plowshare.server.events;

/** Live authorization for operational control of an immutable Application schedule. */
@FunctionalInterface
public interface ScheduleControlAuthority {
  /**
   * Requires either current project management authority or the manifest execution account with
   * current CONTRIBUTOR access. This grants no source editing, enrollment or deletion authority.
   * Source execution/action grants remain separately validated under the registered owner.
   */
  void requireApplicationControl(ScheduleDefinitionStore.Source source, String account);
}
