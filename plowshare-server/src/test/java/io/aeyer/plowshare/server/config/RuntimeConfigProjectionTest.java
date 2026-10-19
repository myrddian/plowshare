package io.aeyer.plowshare.server.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class RuntimeConfigProjectionTest {
  @Test
  void liveValuesStayAuthoritativeAndUseOnlyTheRequiredColumn() {
    var jdbc = mock(JdbcTemplate.class);
    var values = new RuntimeConfig(jdbc);
    when(jdbc.queryForList(
            "SELECT value FROM runtime_config WHERE key = ?", String.class, "budget"))
        .thenReturn(List.of("10"))
        .thenReturn(List.of("20"))
        .thenThrow(new DataAccessResourceFailureException("fixture outage"));
    assertEquals(10, values.intOr("budget", 5));
    assertEquals(20, values.intOr("budget", 5));
    assertEquals(20, values.intOr("budget", 5));
    verify(jdbc, times(3))
        .queryForList("SELECT value FROM runtime_config WHERE key = ?", String.class, "budget");
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void blankAndAbsentRemainDifferentAndAuthorLookupDoesNotHydrateAWholeEntry() {
    var jdbc = mock(JdbcTemplate.class);
    var values = new RuntimeConfig(jdbc);
    when(jdbc.queryForList("SELECT value FROM runtime_config WHERE key = ?", String.class, "blank"))
        .thenReturn(List.of(""));
    when(jdbc.queryForList(
            "SELECT value FROM runtime_config WHERE key = ?", String.class, "absent"))
        .thenReturn(List.of());
    when(jdbc.queryForList(
            "SELECT updated_by FROM runtime_config WHERE key = ?", String.class, "blank"))
        .thenReturn(List.of("operator"));
    assertEquals("", values.get("blank").orElseThrow());
    assertTrue(values.get("absent").isEmpty());
    assertEquals("operator", values.authorOf("blank").orElseThrow());
    verify(jdbc)
        .queryForList("SELECT value FROM runtime_config WHERE key = ?", String.class, "blank");
    verify(jdbc)
        .queryForList("SELECT value FROM runtime_config WHERE key = ?", String.class, "absent");
    verify(jdbc)
        .queryForList("SELECT updated_by FROM runtime_config WHERE key = ?", String.class, "blank");
    verifyNoMoreInteractions(jdbc);
  }
}
