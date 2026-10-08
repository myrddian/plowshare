package io.aeyer.plowshare.server.orchestrations.scripted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** A journal, not a mutable JavaScript stack. Raw paid results survive post-hook failure. */
public final class JdbcScriptStore implements ScriptStore {
  // Script input includes TodoItem.updatedAt; retain Java-time support at this JSON boundary.
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
  private final JdbcTemplate jdbc;

  public JdbcScriptStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
  }

  /** Raw state remains inside this journal/sandbox boundary, never in the job runtime. */
  public Preparation prepareNext(String source, Input input, boolean mayDelegate) {
    java.util.Objects.requireNonNull(input, "input");
    if (source == null || source.length() > 524288)
      throw new IllegalStateException("invalid script source size");
    String actualHash;
    try {
      actualHash =
          "sha256:"
              + java.util.HexFormat.of()
                  .formatHex(
                      java.security.MessageDigest.getInstance("SHA-256")
                          .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    if (!actualHash.equals(input.hash()))
      throw new IllegalStateException("script source differs from the pinned journal");
    JsonNode state =
        input.previousSequence() == null
            ? JSON.nullNode()
            : jdbc.queryForObject(
                "SELECT state::text,result FROM orchestration_script_steps WHERE conversation_id=? AND sequence=? AND source_hash=? AND result IS NOT NULL",
                (row, n) -> {
                  if (!java.util.Objects.equals(input.result(), row.getString(2)))
                    throw new IllegalStateException(
                        "script input differs from the completed receipt");
                  return decode(row.getString(1));
                },
                input.run(),
                input.previousSequence(),
                input.hash());
    var encoded = JSON.valueToTree(input);
    var object = (com.fasterxml.jackson.databind.node.ObjectNode) encoded;
    object.remove("previousSequence");
    object.remove("hash");
    object.set("state", state);
    var evaluated = ScriptProgram.step(source, object);
    if (!evaluated.has("state") || !evaluated.path("command").isObject())
      throw new IllegalStateException("script step must return state and one command");
    if (evaluated.size() != 2)
      throw new IllegalStateException("script step has unsupported fields");
    Command command = ScriptCommands.read(evaluated.get("command"));
    if (command instanceof Tool tool && tool.name().equals("agent_run") && !mayDelegate)
      return new NeedsCall();
    Step step = new Step(input.sequence(), input.hash(), command, null, null, false, null);
    jdbc.update(
        "INSERT INTO orchestration_script_steps(conversation_id,sequence,source_hash,state,command) VALUES (?,?,?,?::jsonb,?::jsonb)",
        input.run(),
        step.sequence(),
        step.hash(),
        evaluated.get("state").toString(),
        evaluated.get("command").toString());
    return new Prepared(step);
  }

  public Optional<Step> latest(String conversation) {
    return jdbc
        .query(
            "SELECT * FROM orchestration_script_steps WHERE conversation_id=? ORDER BY sequence DESC LIMIT 1",
            (row, n) ->
                new Step(
                    row.getInt("sequence"),
                    row.getString("source_hash"),
                    ScriptCommands.read(decode(row.getString("command"))),
                    row.getString("raw_result"),
                    row.getString("result"),
                    row.getTimestamp("started_at") != null,
                    row.getString("effective_arguments")),
            conversation)
        .stream()
        .findFirst();
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

  @Override
  public Optional<String> resumeMessage(String conversation) {
    return jdbc
        .queryForList(
            "SELECT utterance FROM turns WHERE conversation_id=? ORDER BY ordinal DESC LIMIT 1",
            String.class,
            conversation)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<FailedDelegate> failedDelegate(String conversation) {
    var step = latest(conversation).orElse(null);
    if (step == null
        || step.result() != null
        || step.raw() != null
        || !step.started()
        || !(step.command() instanceof Tool tool)
        || !tool.name().equals("agent_run")) return Optional.empty();
    var children =
        jdbc.query(
            "SELECT c.id,t.agent FROM conversations c JOIN turns t ON t.conversation_id=c.id"
                + " WHERE c.parent_id=? AND c.opened_by_call=?"
                + " AND t.ordinal=(SELECT MAX(last.ordinal) FROM turns last WHERE last.conversation_id=c.id)"
                + " AND t.ending IN ('UNAVAILABLE','SESSION_GONE')",
            (row, index) ->
                new FailedDelegate(row.getString("id"), row.getString("agent"), step.sequence()),
            conversation,
            "script_" + step.sequence());
    if (children.size() > 1)
      throw new IllegalStateException("script command has multiple delegated runs");
    return children.stream().findFirst();
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
