package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class UsageScopeReadBudgetTest {
  @Test
  void anExistingScopeDoesNotWriteTheProjectJustToObtainItsIdentity() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(
            "SELECT id::text FROM projects WHERE name=? FOR KEY SHARE", String.class, "one"))
        .thenReturn(List.of("7"));
    assertEquals("7", new JdbcUsageScopeRepository(jdbc).project(Home.of("one")));
    verify(jdbc)
        .queryForList(
            "SELECT id::text FROM projects WHERE name=? FOR KEY SHARE", String.class, "one");
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void concurrentRegistrationReadsTheWinningIdentityWithoutAConflictUpdate() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(
            "SELECT id::text FROM projects WHERE name=? FOR KEY SHARE", String.class, "one"))
        .thenReturn(List.of());
    when(jdbc.queryForList(startsWith("INSERT INTO projects"), eq(String.class), eq("one")))
        .thenReturn(List.of());
    when(jdbc.queryForObject(
            "SELECT id::text FROM projects WHERE name=? FOR KEY SHARE", String.class, "one"))
        .thenReturn("9");
    assertEquals("9", new JdbcUsageScopeRepository(jdbc).project(Home.of("one")));
    verify(jdbc)
        .queryForList(
            "SELECT id::text FROM projects WHERE name=? FOR KEY SHARE", String.class, "one");
    verify(jdbc).queryForList(contains("DO NOTHING RETURNING"), eq(String.class), eq("one"));
    verify(jdbc)
        .queryForObject(
            "SELECT id::text FROM projects WHERE name=? FOR KEY SHARE", String.class, "one");
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void globalAttributionNeedsNoProjectRegistration() {
    var jdbc = mock(JdbcTemplate.class);
    assertNull(new JdbcUsageScopeRepository(jdbc).project(Home.global()));
    verifyNoInteractions(jdbc);
  }
}
