package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReadinessControllerTest {
  @Test
  void startup_and_database_outage_are_not_ready_and_responses_have_no_body() {
    var jdbc = mock(JdbcTemplate.class);
    var probe = new ReadinessController(jdbc);
    assertEquals(503, probe.probe().getStatusCode().value());
    verifyNoInteractions(jdbc);
    probe.ready(null);
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    assertEquals(204, probe.probe().getStatusCode().value());
    assertNull(probe.probe().getBody());
    when(jdbc.queryForObject("SELECT 1", Integer.class))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
    assertEquals(503, probe.probe().getStatusCode().value());
    assertNull(probe.probe().getBody());
  }
}
