package io.aeyer.plowshare.server.agents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bound arguments are never reconstructed from a model's prose or tool arguments. */
public final class CommandInvocations {
  public record Bound(
      String account,
      UUID id,
      String conversation,
      String sourceRun,
      String caller,
      String command,
      String kind,
      String name,
      String hash,
      String arguments,
      String mode,
      String state,
      String result) {}

  private final JdbcTemplate jdbc;

  public CommandInvocations(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final org.springframework.jdbc.core.RowMapper<Bound> ROW =
      (rs, n) ->
          new Bound(
              rs.getString("account_handle"),
              rs.getObject("invocation", UUID.class),
              rs.getString("conversation"),
              rs.getString("source_run"),
              rs.getString("caller"),
              rs.getString("command"),
              rs.getString("kind"),
              rs.getString("name"),
              rs.getString("definition_hash"),
              rs.getString("arguments"),
              rs.getString("mode"),
              rs.getString("state"),
              rs.getString("result"));

  public Bound bind(
      String account,
      String conversation,
      String source,
      String caller,
      CommandCatalog.Entry command,
      String arguments,
      String mode) {
    jdbc.update(
        """
                INSERT INTO command_invocations(account_handle, invocation, conversation, source_run, caller,
                    command, kind, name, definition_hash, arguments, mode)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_handle, conversation, source_run) DO NOTHING
                """,
        account,
        UUID.randomUUID(),
        conversation,
        source,
        caller,
        command.command(),
        command.kind(),
        command.name(),
        command.hash(),
        arguments,
        mode);
    Bound bound =
        jdbc.query(
                "SELECT * FROM command_invocations WHERE account_handle=? AND conversation=? AND source_run=?",
                ROW,
                account,
                conversation,
                source)
            .getFirst();
    if (!bound.caller().equals(caller)
        || !bound.command().equals(command.command())
        || !bound.arguments().equals(arguments)
        || !java.util.Objects.equals(bound.mode(), mode)
        || !bound.hash().equals(command.hash()))
      throw new IllegalStateException("This run already binds another command. Nothing ran.");
    return bound;
  }

  public Optional<Bound> find(String account, String conversation, String caller, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM command_invocations WHERE account_handle=? AND conversation=? AND caller=? AND invocation=?",
            ROW,
            account,
            conversation,
            caller,
            id)
        .stream()
        .findFirst();
  }

  public List<Bound> pending(String account, String conversation, String caller) {
    if (account == null || conversation == null) return List.of();
    return jdbc.query(
        "SELECT * FROM command_invocations WHERE account_handle=? AND conversation=? AND caller=? AND state IN ('bound', 'dispatching', 'failed') ORDER BY created_at",
        ROW,
        account,
        conversation,
        caller);
  }

  public boolean claim(Bound bound) {
    return jdbc.update(
            "UPDATE command_invocations SET state='dispatching' WHERE account_handle=? AND invocation=? AND state='bound'",
            bound.account(),
            bound.id())
        == 1;
  }

  public void ended(Bound bound, String state, String result) {
    jdbc.update(
        "UPDATE command_invocations SET state=?, result=? WHERE account_handle=? AND invocation=? AND state='dispatching'",
        state,
        result,
        bound.account(),
        bound.id());
  }
}
