package io.aeyer.plowshare.server.events;

/**
 * Durable record of Application schedule saves (ADR 0068). Each save writes one {@code admin_audit}
 * row with action {@code schedule.save.application}.
 */
public interface ApplicationScheduleAudit {
  /**
   * Records that {@code actor} saved an Application schedule.
   *
   * @param actor the authenticated account that saved it
   * @param target {@code <project>/<name>}; a MANAGED save appends {@code @<revision>}
   */
  void saved(String actor, String target);
}
