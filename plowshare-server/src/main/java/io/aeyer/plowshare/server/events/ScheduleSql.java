package io.aeyer.plowshare.server.events;

/** The Scheduler's current emitter predicate, also used when projecting its next occurrence. */
public final class ScheduleSql {
  private ScheduleSql() {}

  // The repository rechecks execution authority when claiming, not just when a file is enrolled.
  // A deleted source cannot fall through to the legacy administrator-only emitter rule.
  private static final String EMITTER_AUTHORIZED =
      """
      EXISTS (
        SELECT 1 FROM admins a
        WHERE a.handle=schedules.defined_by AND a.enabled AND NOT a.bootstrap AND (
          (NOT schedules.file_managed AND a.server_admin)
          OR (schedules.file_managed AND EXISTS (
            SELECT 1 FROM schedule_files f JOIN schedule_sources s ON s.id=f.source_id
            WHERE f.internal_name=schedules.name AND f.status='active' AND s.account=a.handle
              AND ((s.project_id IS NULL AND a.server_admin) OR EXISTS (
                SELECT 1 FROM projects p WHERE p.id=s.project_id AND (
                  p.personal_owner=a.handle OR (p.personal_owner IS NULL AND (
                    (a.server_admin AND p.name NOT LIKE 'client:%' AND p.name NOT LIKE 'personal:%'
                      AND p.name NOT LIKE 'contained:%')
                    OR EXISTS (SELECT 1 FROM project_members m WHERE m.project_id=p.id
                      AND m.handle=a.handle AND m.role IN ('CONTRIBUTOR','MANAGER'))
                  ))
                )
              ))
          ))
        )
      )
      """;

  public static String emitterAuthorized(String relation) {
    return switch (relation) {
      case "schedules" -> EMITTER_AUTHORIZED;
      case "timer" -> EMITTER_AUTHORIZED.replace("schedules.", "timer.");
      default -> throw new IllegalArgumentException("Unknown Scheduler relation");
    };
  }
}
