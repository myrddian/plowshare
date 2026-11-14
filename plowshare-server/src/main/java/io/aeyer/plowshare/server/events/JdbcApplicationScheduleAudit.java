package io.aeyer.plowshare.server.events;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes Application schedule saves to {@code admin_audit}, beside the other administrative audit
 * rows. The insert runs in the caller's transaction when there is one, and otherwise commits alone.
 */
public final class JdbcApplicationScheduleAudit implements ApplicationScheduleAudit {
  static final String ACTION = "schedule.save.application";
  private final JdbcTemplate jdbc;

  public JdbcApplicationScheduleAudit(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void saved(String actor, String target) {
    if (actor == null || actor.isBlank() || actor.length() > 256)
      throw new IllegalArgumentException("Audit actor required");
    if (target == null || target.isBlank() || target.length() > 1024 || target.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Audit target required");
    jdbc.update(
        "INSERT INTO admin_audit(actor,action,target) VALUES (?,?,?)", actor, ACTION, target);
  }
}
