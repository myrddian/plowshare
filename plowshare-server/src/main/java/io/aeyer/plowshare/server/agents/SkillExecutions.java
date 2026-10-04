package io.aeyer.plowshare.server.agents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable accepted skill instructions. An ambiguous invocation is inspected, never replayed. */
public final class SkillExecutions {
  public record Execution(
      String account,
      UUID invocation,
      String payload,
      String parent,
      String conversation,
      String executor,
      SkillDefinition skill,
      SkillDefinition.Mode mode,
      String state,
      String result) {}

  private final JdbcTemplate jdbc;

  public SkillExecutions(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final org.springframework.jdbc.core.RowMapper<Execution> ROW =
      (rs, n) ->
          new Execution(
              rs.getString("account_handle"),
              rs.getObject("invocation", UUID.class),
              rs.getString("payload"),
              rs.getString("parent_conversation"),
              rs.getString("conversation"),
              rs.getString("executor"),
              SkillDefinition.parse(
                  new DefinitionSource.Definition(
                      rs.getString("skill"), rs.getString("origin"), rs.getString("source")),
                  OrchestrationDefinition.Tier.valueOf(rs.getString("tier"))),
              SkillDefinition.Mode.valueOf(rs.getString("mode")),
              rs.getString("state"),
              rs.getString("result"));

  public Optional<Execution> find(String account, UUID invocation) {
    return jdbc
        .query(
            "SELECT * FROM skill_executions WHERE account_handle = ? AND invocation = ?",
            ROW,
            account,
            invocation)
        .stream()
        .findFirst();
  }

  /** False means somebody already owns this invocation; no new child or paid work is allowed. */
  public boolean claim(
      String account,
      UUID id,
      String payload,
      String parent,
      String executor,
      SkillDefinition skill,
      SkillDefinition.Mode mode) {
    return jdbc.update(
            """
                INSERT INTO skill_executions(account_handle, invocation, payload, parent_conversation,
                    executor, skill, source, origin, tier, mode)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_handle, invocation) DO NOTHING
                """,
            account,
            id,
            payload,
            parent,
            executor,
            skill.name(),
            skill.source(),
            skill.origin(),
            skill.tier().name(),
            mode.name())
        == 1;
  }

  public void running(String account, UUID id, String conversation) {
    if (conversation == null || conversation.isBlank())
      throw new IllegalStateException("a skill needs a durable log");
    if (jdbc.update(
            "UPDATE skill_executions SET conversation = ?, state = 'running' "
                + "WHERE account_handle = ? AND invocation = ? AND state = 'claimed'",
            conversation,
            account,
            id)
        != 1) throw new IllegalStateException("skill invocation was already started");
  }

  public List<Execution> active(String conversation) {
    if (conversation == null) return List.of();
    return jdbc.query(
        "SELECT * FROM skill_executions WHERE conversation = ? "
            + "AND state IN ('running', 'awaiting') ORDER BY created_at, invocation",
        ROW,
        conversation);
  }

  public void closed(String conversation, Outcome outcome) {
    if (conversation == null) return;
    String state =
        outcome.ending() == Outcome.Ending.AWAITING
            ? "awaiting"
            : outcome.answered() ? "finished" : "failed";
    jdbc.update(
        "UPDATE skill_executions SET state = ?, result = ? WHERE conversation = ? "
            + "AND state IN ('running', 'awaiting')",
        state,
        outcome.text(),
        conversation);
  }

  public void failed(String account, UUID id, String reason) {
    jdbc.update(
        "UPDATE skill_executions SET state = 'failed', result = ? "
            + "WHERE account_handle = ? AND invocation = ? AND state IN ('claimed', 'running')",
        reason,
        account,
        id);
  }

  public void context(String account, UUID id, String snapshot, String prompt, int through) {
    if (jdbc.update(
            "UPDATE skill_executions SET context_snapshot = CAST(? AS JSONB), context_prompt = ?, source_through = ? "
                + "WHERE account_handle = ? AND invocation = ? AND state = 'claimed' AND context_snapshot IS NULL",
            snapshot,
            prompt,
            through,
            account,
            id)
        != 1) throw new IllegalStateException("skill context was already pinned");
  }

  /** Only handles actually captured in this child's immutable snapshot can be redeemed. */
  public Optional<String> contextResult(String account, String conversation, UUID handle) {
    if (account == null || conversation == null || handle == null) return Optional.empty();
    return jdbc
        .query(
            """
                SELECT item->>'content' FROM skill_executions s,
                    LATERAL jsonb_array_elements(s.context_snapshot) item
                WHERE s.account_handle = ? AND s.conversation = ? AND item->>'kind' = 'tool_result'
                    AND item->>'handle' = ? AND item->>'content' IS NOT NULL
                """,
            (rs, n) -> rs.getString(1),
            account,
            conversation,
            handle.toString())
        .stream()
        .findFirst();
  }
}
