package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

class SearchProjectSelectionTest {
  @Test
  void oneSearchReusesItsProjectSelectionButWritesAndLaterOperationsDoNot() {
    var jdbc = mock(JdbcTemplate.class);
    var home = Home.of("research");
    String lookup = "SELECT id FROM projects WHERE name = ?";
    when(jdbc.query(
            eq(lookup),
            any(PreparedStatementSetter.class),
            ArgumentMatchers.<RowMapper<Long>>any()))
        .thenReturn(List.of(7L));
    when(jdbc.queryForList(lookup, Long.class, home.project())).thenReturn(List.of(11L));
    ReadDeadline.within(
        Duration.ofSeconds(2),
        () ->
            ProjectIds.withinReadSelection(
                jdbc,
                home,
                () -> {
                  assertEquals(7L, ProjectIds.toRead(jdbc, home));
                  assertEquals(7L, ProjectIds.toRead(jdbc, home));
                  assertEquals(11L, ProjectIds.toWrite(jdbc, home));
                  return ProjectIds.withinReadSelection(
                      jdbc,
                      home,
                      () -> {
                        assertEquals(7L, ProjectIds.toRead(jdbc, home));
                        return null;
                      });
                }));
    assertEquals(11L, ProjectIds.toRead(jdbc, home));
    verify(jdbc, times(1))
        .query(
            eq(lookup),
            any(PreparedStatementSetter.class),
            ArgumentMatchers.<RowMapper<Long>>any());
    verify(jdbc, times(2)).queryForList(lookup, Long.class, home.project());
  }
}
