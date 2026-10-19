package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.information.InformationLifecycle.Lease;
import io.aeyer.plowshare.server.information.InformationLifecycle.StaleLease;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InformationProcessingAuthorityTest {
  @Test
  void missingSystemAuthorityRefusesRenewalBeforeExtendingTheLease() {
    var jdbc = mock(JdbcTemplate.class);
    var members = mock(ProjectMembers.class);
    String principal = "@service/" + UUID.randomUUID();
    var lease =
        new Lease(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "extract",
            1,
            UUID.randomUUID(),
            principal,
            12L);
    when(jdbc.queryForObject("SELECT name FROM projects WHERE id=?", String.class, 12L))
        .thenReturn("application");
    when(jdbc.queryForObject(
            "SELECT information_system_scope_valid(?)", Boolean.class, "application"))
        .thenReturn(false);
    var repository = new JdbcInformationProcessingRepository(jdbc, Clock.systemUTC(), members);
    assertFalse(repository.renew(lease));
    verifyNoInteractions(members);
    verify(jdbc)
        .queryForObject("SELECT information_system_scope_valid(?)", Boolean.class, "application");
    verify(jdbc).queryForObject("SELECT name FROM projects WHERE id=?", String.class, 12L);
    verifyNoMoreInteractions(jdbc, members);
  }

  @Test
  void missingSystemAuthorityRefusesCheckpointWithTheOriginalProvenance() {
    var jdbc = mock(JdbcTemplate.class);
    var members = mock(ProjectMembers.class);
    String principal = "@service/" + UUID.randomUUID();
    var lease =
        new Lease(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "extract",
            1,
            UUID.randomUUID(),
            principal,
            12L);
    when(jdbc.queryForObject("SELECT name FROM projects WHERE id=?", String.class, 12L))
        .thenReturn("application");
    when(jdbc.queryForObject(
            "SELECT information_system_scope_valid(?)", Boolean.class, "application"))
        .thenReturn(false);
    var repository = new JdbcInformationProcessingRepository(jdbc, Clock.systemUTC(), members);
    assertThrows(StaleLease.class, () -> repository.requireLease(lease));
    verifyNoInteractions(members);
    verify(jdbc)
        .queryForObject("SELECT information_system_scope_valid(?)", Boolean.class, "application");
    verify(jdbc).queryForObject("SELECT name FROM projects WHERE id=?", String.class, 12L);
    verifyNoMoreInteractions(jdbc, members);
  }
}
