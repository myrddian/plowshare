package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Verifies the typed JDBC boundary with mocked rows; no PostgreSQL dependency is needed. */
class JdbcConversationContextRepositoryTest {
  @Test
  void bound_lookup_maps_global_project_and_personal_ownership() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var row = mock(ResultSet.class);
    when(row.getString(1)).thenReturn("alice");
    when(jdbc.query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<ConversationContextRepository.Ownership>>any(),
            eq("conversation")))
        .thenAnswer(
            invocation -> {
              RowMapper<ConversationContextRepository.Ownership> mapper = invocation.getArgument(1);
              return List.of(mapper.mapRow(row, 0));
            });
    var repository = new JdbcConversationContextRepository(jdbc);
    assertEquals(
        new ConversationContextRepository.Ownership("alice", null, null, false),
        repository.ownership("conversation").orElseThrow());
    when(row.getString(2)).thenReturn("7");
    when(row.getString(3)).thenReturn("project");
    assertEquals(
        new ConversationContextRepository.Ownership("alice", "7", "project", false),
        repository.ownership("conversation").orElseThrow());
    when(row.getBoolean(4)).thenReturn(true);
    assertTrue(repository.ownership("conversation").orElseThrow().personal());
    verify(jdbc, times(3))
        .query(
            contains("WHERE c.id=? AND c.owner_handle IS NOT NULL"),
            org.mockito.ArgumentMatchers.<RowMapper<ConversationContextRepository.Ownership>>any(),
            eq("conversation"));
  }

  @Test
  void missing_ownership_is_absent_and_invalid_input_is_rejected_before_jdbc() {
    var jdbc = mock(JdbcTemplate.class);
    var repository = new JdbcConversationContextRepository(jdbc);
    assertThrows(IllegalArgumentException.class, () -> repository.ownership(null));
    assertThrows(IllegalArgumentException.class, () -> repository.ownership(" "));
    verifyNoInteractions(jdbc);
    assertTrue(repository.ownership("missing").isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConversationContextRepository.Ownership("alice", "7", null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConversationContextRepository.Ownership(null, null, null, false));
  }
}
