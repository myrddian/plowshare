package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CommandBindingReadBudgetTest {
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final JdbcCommandInvocationsRepository repository = new JdbcCommandInvocationsRepository(jdbc);
  final CommandCatalog.Entry command =
      new CommandCatalog.Entry(
          "/skill:inspect",
          List.of(),
          "skill",
          "inspect",
          "Inspect",
          null,
          "bot",
          "DIRECT",
          "fixture",
          "hash");

  CommandInvocations.Bound bound(String arguments) {
    return new CommandInvocations.Bound(
        "reader",
        UUID.randomUUID(),
        "conversation",
        "source",
        "bot",
        command.command(),
        command.kind(),
        command.name(),
        command.hash(),
        arguments,
        "DIRECT",
        "bound",
        null);
  }

  @Test
  void aSuccessfulInsertReturnsItsReceiptWithoutReloadingIt() {
    var saved = bound("original");
    when(jdbc.query(
            startsWith("INSERT INTO command_invocations"),
            ArgumentMatchers.<RowMapper<CommandInvocations.Bound>>any(),
            any(Object[].class)))
        .thenReturn(List.of(saved));
    assertSame(
        saved,
        repository.bind("reader", "conversation", "source", "bot", command, "original", "DIRECT"));
    verify(jdbc)
        .query(
            contains("RETURNING *"),
            ArgumentMatchers.<RowMapper<CommandInvocations.Bound>>any(),
            any(Object[].class));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void aConflictStillLoadsAndChecksTheExactRetainedCommand() {
    var saved = bound("original");
    when(jdbc.query(
            startsWith("INSERT INTO command_invocations"),
            ArgumentMatchers.<RowMapper<CommandInvocations.Bound>>any(),
            any(Object[].class)))
        .thenReturn(List.of());
    when(jdbc.query(
            startsWith("SELECT * FROM command_invocations"),
            ArgumentMatchers.<RowMapper<CommandInvocations.Bound>>any(),
            any(Object[].class)))
        .thenReturn(List.of(saved));
    assertSame(
        saved,
        repository.bind("reader", "conversation", "source", "bot", command, "original", "DIRECT"));
    assertThrows(
        IllegalStateException.class,
        () ->
            repository.bind(
                "reader", "conversation", "source", "bot", command, "changed", "DIRECT"));
    verify(jdbc, times(2))
        .query(
            startsWith("INSERT INTO command_invocations"),
            ArgumentMatchers.<RowMapper<CommandInvocations.Bound>>any(),
            any(Object[].class));
    verify(jdbc, times(2))
        .query(
            startsWith("SELECT * FROM command_invocations"),
            ArgumentMatchers.<RowMapper<CommandInvocations.Bound>>any(),
            any(Object[].class));
    verifyNoMoreInteractions(jdbc);
  }
}
