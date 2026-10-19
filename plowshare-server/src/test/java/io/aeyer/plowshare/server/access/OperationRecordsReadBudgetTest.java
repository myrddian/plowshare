package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class OperationRecordsReadBudgetTest {
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final UUID operation = UUID.randomUUID(),
      account = UUID.randomUUID(),
      scope = UUID.randomUUID(),
      grant = UUID.randomUUID(),
      allowance = UUID.randomUUID();
  JdbcOperationRecords records;

  @BeforeEach
  void setup() throws Exception {
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(jdbc.getDataSource()).thenReturn(source);
    records = new JdbcOperationRecords(jdbc);
    clearInvocations(jdbc);
  }

  private ResultSet row() throws Exception {
    var rows = mock(ResultSet.class);
    when(rows.next()).thenReturn(true, false);
    when(rows.getObject("id", UUID.class)).thenReturn(operation);
    when(rows.getObject("operation", UUID.class)).thenReturn(operation);
    when(rows.getObject("executor", UUID.class)).thenReturn(account);
    when(rows.getObject("source_account", UUID.class)).thenReturn(account);
    when(rows.getObject("scope", UUID.class)).thenReturn(scope);
    when(rows.getObject("grant_id", UUID.class)).thenReturn(grant);
    when(rows.getObject("allowance_id", UUID.class)).thenReturn(allowance);
    when(rows.getString("executor_handle")).thenReturn("reader");
    when(rows.getString("project_name")).thenReturn("one");
    when(rows.getString("support_action")).thenReturn("READ");
    when(rows.getString("conversation")).thenReturn("conversation");
    when(rows.getString("agent")).thenReturn("bot");
    when(rows.getString("capabilities")).thenReturn("a".repeat(64));
    return rows;
  }

  @Test
  void conversationHydratesAdmissionAndSupportWithoutNestedQueries() throws Exception {
    var rows = row();
    when(jdbc.query(
            anyString(),
            ArgumentMatchers.<ResultSetExtractor<Optional<OperationAuthority.Conversation>>>any(),
            eq("conversation")))
        .thenAnswer(
            call -> {
              ResultSetExtractor<Optional<OperationAuthority.Conversation>> extractor =
                  call.getArgument(1);
              return extractor.extractData(rows);
            });
    var value = records.conversation("conversation").orElseThrow();
    assertEquals(operation, value.admission().id());
    assertEquals(
        List.of(new OperationAuthority.Support(AuthorityAction.READ, grant)),
        value.admission().support());
    assertEquals(new AllowanceId(allowance), value.budget());
    verify(jdbc)
        .query(
            contains("LEFT JOIN operation_admission_support"),
            ArgumentMatchers.<ResultSetExtractor<Optional<OperationAuthority.Conversation>>>any(),
            eq("conversation"));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void successfulBindReturnsOnlyItsImmutableTupleAndNeverLoadsAdmissionSupport() throws Exception {
    var rows = row();
    when(jdbc.query(
            startsWith("INSERT INTO operation_conversations"),
            ArgumentMatchers.<RowMapper<Object>>any(),
            any(Object[].class)))
        .thenAnswer(
            call -> {
              RowMapper<?> mapper = call.getArgument(1);
              return List.of(mapper.mapRow(rows, 0));
            });
    var admission =
        new OperationAuthority.Admission(
            operation,
            "reader",
            new AuthorityIds.AccountId(account),
            new AuthorityIds.ScopeId(scope),
            Home.of("one"),
            GrantRecipient.account(new AuthorityIds.AccountId(account)),
            List.of(new OperationAuthority.Support(AuthorityAction.READ, grant)));
    records.bind(
        new OperationAuthority.Conversation(
            "conversation", "bot", admission, null, new AllowanceId(allowance), "a".repeat(64)));
    verify(jdbc)
        .query(
            contains("DO NOTHING RETURNING conversation"),
            ArgumentMatchers.<RowMapper<Object>>any(),
            any(Object[].class));
    verifyNoMoreInteractions(jdbc);
  }
}
