package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.server.faults.Faults;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL error classification only; the database suite verifies the real guard and rollback. */
class ProjectMembershipFailureTest {
  private static final String REFUSAL =
      "Replacing your issuing membership requires another manager";
  private JdbcTemplate jdbc;
  private Connection connection;
  private ProjectMembers members;

  @BeforeEach
  void setup() throws Exception {
    jdbc = mock(JdbcTemplate.class);
    var source = mock(DataSource.class);
    connection = mock(Connection.class);
    when(jdbc.getDataSource()).thenReturn(source);
    when(source.getConnection()).thenReturn(connection);
    when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    when(jdbc.queryForList(anyString(), eq(Integer.class), any(Object[].class)))
        .thenReturn(List.of(1));
    when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
        .thenReturn(List.of("MANAGER"));
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
    when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class)))
        .thenReturn("ledger");
    members = new JdbcProjectMembers(jdbc);
  }

  @Test
  void self_replacement_is_a_conflict_without_sql_details_and_rolls_back() throws Exception {
    failIssue("P0001", REFUSAL);

    var refused =
        assertThrows(
            ArchiveRefusedException.class,
            () -> members.assign("ledger", "alice", ProjectRole.MANAGER, "alice", false));

    assertEquals(REFUSAL, refused.getMessage());
    assertEquals(Code.CONFLICT, Faults.of(refused).code());
    assertEquals(REFUSAL, Faults.of(refused).detail());
    verify(connection).rollback();
    verify(connection, never()).commit();
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void unrelated_database_errors_are_not_disguised_as_policy_refusals() {
    DataAccessException failure = failIssue("P0001", "A different database failure");

    assertSame(
        failure,
        assertThrows(
            DataAccessException.class,
            () -> members.assign("ledger", "alice", ProjectRole.MANAGER, "alice", false)));
  }

  @Test
  void matching_text_without_the_guard_sqlstate_is_still_a_database_failure() {
    DataAccessException failure = failIssue("23514", REFUSAL);

    assertSame(
        failure,
        assertThrows(
            DataAccessException.class,
            () -> members.assign("ledger", "alice", ProjectRole.MANAGER, "alice", false)));
  }

  private DataAccessException failIssue(String state, String message) {
    var postgres =
        new PSQLException(new ServerErrorMessage("SERROR\0C" + state + "\0M" + message + "\0\0"));
    var failure = new UncategorizedSQLException("issue membership", "fixture SQL", postgres);
    when(jdbc.queryForObject(
            eq("SELECT authority_issue_membership(?,?,?,?)"), eq(UUID.class), any(Object[].class)))
        .thenThrow(failure);
    return failure;
  }
}
