package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SchedulePagingRepositoryTest {
  @Test
  void runtimePagesPutOwnershipOrProjectAndExclusiveCursorBeforeLimit() {
    var jdbc = mock(JdbcTemplate.class);
    var store = new JdbcScheduleStore(jdbc);
    store.page("owner", null, new SchedulePage("daily", 3));
    store.page("viewer", "application", new SchedulePage("scheduled-1-daily", 2));
    var sql = ArgumentCaptor.forClass(String.class);
    var args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, times(2))
        .query(sql.capture(), ArgumentMatchers.<RowMapper<Object>>any(), args.capture());
    verifyNoMoreInteractions(jdbc);
    assertTrue(
        sql.getAllValues()
            .getFirst()
            .contains("WHERE defined_by=? AND schedules.name>? ORDER BY schedules.name LIMIT ?"));
    assertEquals(
        java.util.List.of("owner", "daily", 3), Arrays.asList(args.getAllValues().getFirst()));
    assertTrue(sql.getValue().contains("f.internal_name=schedules.name AND p.name=?"));
    assertTrue(sql.getValue().contains("AND schedules.name>? ORDER BY schedules.name LIMIT ?"));
    assertEquals(
        java.util.List.of("application", "scheduled-1-daily", 2), Arrays.asList(args.getValue()));
  }

  @Test
  void invalidPagesCannotReachJdbcAndBoundaryValuesAreAccepted() {
    for (int limit : new int[] {-1, 0, 101})
      assertThrows(IllegalArgumentException.class, () -> new SchedulePage(null, limit));
    for (String cursor : new String[] {"", " daily", "daily ", "a\0b", "x".repeat(513)})
      assertThrows(IllegalArgumentException.class, () -> new SchedulePage(cursor, 1));
    assertEquals(1, new SchedulePage(null, 1).limit());
    assertEquals(100, new SchedulePage("x".repeat(512), 100).limit());
  }
}
