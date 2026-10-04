package io.aeyer.plowshare.server.orchestrations.scripted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** A journal, not a mutable JavaScript stack. Raw paid results survive post-hook failure. */
public final class ScriptStore {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final JdbcTemplate jdbc;

  public ScriptStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Step(
      int sequence,
      String hash,
      JsonNode state,
      JsonNode command,
      String raw,
      String result,
      boolean started,
      String arguments) {
    public Step(
        int sequence, String hash, JsonNode state, JsonNode command, String raw, String result) {
      this(sequence, hash, state, command, raw, result, false, null);
    }
  }

  public Optional<Step> latest(String conversation) {
    return jdbc
        .query(
            "SELECT * FROM orchestration_script_steps WHERE conversation_id=? ORDER BY sequence DESC LIMIT 1",
            (row, n) ->
                new Step(
                    row.getInt("sequence"),
                    row.getString("source_hash"),
                    decode(row.getString("state")),
                    decode(row.getString("command")),
                    row.getString("raw_result"),
                    row.getString("result"),
                    row.getTimestamp("started_at") != null,
                    row.getString("effective_arguments")),
            conversation)
        .stream()
        .findFirst();
  }

  public void prepare(String conversation, Step step) {
    jdbc.update(
        "INSERT INTO orchestration_script_steps(conversation_id,sequence,source_hash,state,command) VALUES (?,?,?,?::jsonb,?::jsonb)",
        conversation,
        step.sequence(),
        step.hash(),
        step.state().toString(),
        step.command().toString());
  }

  public void started(String conversation, int sequence, String arguments) {
    if (jdbc.update(
            "UPDATE orchestration_script_steps SET started_at=now(),effective_arguments=? WHERE conversation_id=? AND sequence=? AND started_at IS NULL",
            arguments,
            conversation,
            sequence)
        != 1) throw new IllegalStateException("script command was already started");
  }

  /**
   * A completed delegated model call can be recovered even if its parent's receipt write was
   * interrupted.
   */
  public Optional<String> delegateResult(String conversation, int sequence) {
    var answers =
        jdbc.queryForList(
            "SELECT t.answer FROM conversations c JOIN turns t ON t.conversation_id=c.id WHERE c.parent_id=? AND c.opened_by_call=? AND t.ending='ANSWERED' ORDER BY t.ordinal DESC",
            String.class,
            conversation,
            "script_" + sequence);
    if (answers.size() > 1)
      throw new IllegalStateException("script command has multiple delegated answers");
    return answers.stream().findFirst();
  }

  public void executed(String conversation, int sequence, String raw) {
    if (jdbc.update(
            "UPDATE orchestration_script_steps SET raw_result=?,executed_at=now() WHERE conversation_id=? AND sequence=? AND raw_result IS NULL",
            raw,
            conversation,
            sequence)
        != 1) throw new IllegalStateException("script command receipt already exists");
  }

  /** A readiness observer has no acquisition, processing or model side effects to repeat. */
  public void retryReadinessObserver(String conversation, int sequence) {
    if (jdbc.update(
            "UPDATE orchestration_script_steps SET started_at=NULL WHERE conversation_id=? AND sequence=?"
                + " AND raw_result IS NULL AND result IS NULL AND command->>'tool'='information_read'"
                + " AND command->'arguments'->>'operation'='await' AND effective_arguments::jsonb->>'operation'='await'",
            conversation,
            sequence)
        != 1) throw new IllegalStateException("interrupted command is not a readiness observer");
  }

  /** Only a service-owned, atomic refusal proves there was no write to replay. */
  public void retryRefusedStage(String conversation, int sequence) {
    if (jdbc.update(
            "UPDATE orchestration_script_steps SET started_at=NULL,effective_arguments=NULL,raw_result=NULL,executed_at=NULL WHERE conversation_id=? AND sequence=? AND result IS NULL",
            conversation,
            sequence)
        != 1) throw new IllegalStateException("stage command cannot be retried");
  }

  public void completed(String conversation, int sequence, String result) {
    if (jdbc.update(
            "UPDATE orchestration_script_steps SET result=?,completed_at=now() WHERE conversation_id=? AND sequence=? AND result IS NULL AND raw_result IS NOT NULL",
            result,
            conversation,
            sequence)
        != 1) throw new IllegalStateException("script command has no durable execution receipt");
  }

  private static JsonNode decode(String source) {
    try {
      return JSON.readTree(source);
    } catch (Exception broken) {
      throw new IllegalStateException("invalid stored script JSON", broken);
    }
  }
}
