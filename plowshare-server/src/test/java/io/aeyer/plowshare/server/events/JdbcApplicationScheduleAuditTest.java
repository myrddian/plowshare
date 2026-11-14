package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcApplicationScheduleAuditTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final JdbcApplicationScheduleAudit audit = new JdbcApplicationScheduleAudit(jdbc);

  @Test
  void recordsTheSaveAsAnAdminAuditRow() {
    audit.saved("manager", "project/daily");
    verify(jdbc)
        .update(
            "INSERT INTO admin_audit(actor,action,target) VALUES (?,?,?)",
            "manager",
            "schedule.save.application",
            "project/daily");
  }

  @Test
  void refusesBlankActorsAndTargets() {
    assertThrows(IllegalArgumentException.class, () -> audit.saved(" ", "project/daily"));
    assertThrows(IllegalArgumentException.class, () -> audit.saved("manager", ""));
    assertThrows(IllegalArgumentException.class, () -> audit.saved("manager", "a\0b"));
    verifyNoInteractions(jdbc);
  }
}
