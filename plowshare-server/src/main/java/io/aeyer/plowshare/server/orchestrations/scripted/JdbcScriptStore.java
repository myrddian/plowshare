package io.aeyer.plowshare.server.orchestrations.scripted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aeyer.plowshare.protocol.Orchestration.CheckpointRepair;
import io.aeyer.plowshare.server.information.InformationGateRepository;
import io.aeyer.plowshare.server.todos.TodoItem;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** A journal, not a mutable JavaScript stack. Raw paid results survive post-hook failure. */
public final class JdbcScriptStore implements ScriptStore {
  // Script input includes TodoItem.updatedAt; retain Java-time support at this JSON boundary.
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
  private final JdbcTemplate jdbc;
  private final InformationGateRepository reportReceipts;

  public JdbcScriptStore(JdbcTemplate jdbc) {
    this(jdbc, null);
  }

  public JdbcScriptStore(JdbcTemplate jdbc, InformationGateRepository reportReceipts) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    this.reportReceipts = reportReceipts;
  }

  /** Raw state remains inside this journal/sandbox boundary, never in the job runtime. */
  public Preparation prepareNext(String source, Input input, boolean mayDelegate) {
    var evaluated = evaluate(source, input, input.hash());
    if (evaluated.command() instanceof Tool tool && tool.name().equals("agent_run") && !mayDelegate)
      return new NeedsCall();
    return persist(input, evaluated);
  }

  @Override
  public Preparation prepareNext(String source, Input input, Repair repair) {
    String previousHash =
        input.sequence() == repair.boundary().sequence() + 1
            ? repair.boundary().sourceHash()
            : input.hash();
    var evaluated = evaluate(source, input, previousHash);
    requireRepairCommand(input, evaluated.command(), repair);
    return persist(input, evaluated);
  }

  @Override
  public void previewRepair(String source, Input input, Repair repair) {
    requireRepairCommand(
        input, evaluate(source, input, repair.boundary().sourceHash()).command(), repair);
  }

  private record Evaluated(Command command, String state, String encodedCommand) {}

  private Evaluated evaluate(String source, Input input, String previousHash) {
    java.util.Objects.requireNonNull(input, "input");
    if (source == null || source.length() > 524288)
      throw new IllegalStateException("invalid script source size");
    if (!ScriptProgram.sourceHash(source).equals(input.hash()))
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
                previousHash);
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
    return new Evaluated(
        ScriptCommands.read(evaluated.get("command")),
        evaluated.get("state").toString(),
        evaluated.get("command").toString());
  }

  private Prepared persist(Input input, Evaluated evaluated) {
    Step step =
        new Step(input.sequence(), input.hash(), evaluated.command(), null, null, false, null);
    jdbc.update(
        "INSERT INTO orchestration_script_steps(conversation_id,sequence,source_hash,state,command) VALUES (?,?,?,?::jsonb,?::jsonb)",
        input.run(),
        step.sequence(),
        step.hash(),
        evaluated.state(),
        evaluated.encodedCommand());
    return new Prepared(step);
  }

  @Override
  public Set<UUID> repairEvidence(String conversation, int checkpoint) {
    return Set.copyOf(
        jdbc.query(
            """
        SELECT DISTINCT effective_arguments::jsonb->>'revision' AS revision
        FROM orchestration_script_steps
        WHERE conversation_id=? AND sequence<? AND completed_at IS NOT NULL
          AND result LIKE '{%' AND command->>'tool'='information_read'
          AND effective_arguments::jsonb->>'operation'='read'
          AND effective_arguments::jsonb->>'revision' IS NOT NULL
        """,
            (row, n) -> UUID.fromString(row.getString("revision")), conversation, checkpoint));
  }

  private record PinnedHead(Step step, CheckpointRepair boundary) {}

  @Override
  public Head head(String conversation) {
    var found =
        jdbc.query(
            """
        SELECT s.*,r.checkpoint_sequence,r.previous_script_hash,r.replacement_script_hash
        FROM (SELECT * FROM orchestration_script_steps WHERE conversation_id=?
              ORDER BY sequence DESC LIMIT 1) s
        LEFT JOIN orchestrations o ON o.conductor_conversation=s.conversation_id
        LEFT JOIN orchestration_resume_receipts r ON r.run_id=o.id AND r.checkpoint_sequence IS NOT NULL
        """,
            (row, n) -> {
              Integer sequence = row.getObject("checkpoint_sequence", Integer.class);
              return new PinnedHead(
                  step(row),
                  sequence == null
                      ? null
                      : new CheckpointRepair(
                          sequence,
                          row.getString("previous_script_hash"),
                          row.getString("replacement_script_hash")));
            },
            conversation);
    if (found.isEmpty()) return new Head(Optional.empty(), Optional.empty());
    var current = found.getFirst();
    return new Head(
        Optional.of(current.step()),
        Optional.ofNullable(current.boundary())
            .map(
                boundary ->
                    new Repair(boundary, repairEvidence(conversation, boundary.sequence()))));
  }

  @Override
  public boolean repaired(String conversation) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS (SELECT 1 FROM orchestrations o JOIN orchestration_resume_receipts r ON r.run_id=o.id
          WHERE o.conductor_conversation=? AND r.checkpoint_sequence IS NOT NULL)
        """,
            Boolean.class,
            conversation));
  }

  private void requireRepairCommand(Input input, Command command, Repair repair) {
    if (!(command instanceof Tool tool))
      throw new IllegalStateException("Repaired checkpoints require a report continuation tool");
    requireRepairEffect(input.run(), input.sequence(), tool, input.todos(), repair);
  }

  @Override
  public void requireRepairEffect(
      String conversation, int sequence, Tool command, List<TodoItem> todos, Repair repair) {
    var effect = ReportRepairEffects.validate(command, conversation, sequence, todos, repair);
    if (effect == ReportRepairEffects.Effect.STAGE) return;
    // Check the journal, including any started write without a result. The current command
    // is excluded so an undispatched prepared command remains recoverable.
    var prior =
        jdbc.query(
            """
        SELECT CASE WHEN completed_at IS NOT NULL THEN raw_result END FROM orchestration_script_steps
        WHERE conversation_id=? AND sequence<? AND command->>'tool'='information_write'
        ORDER BY sequence DESC LIMIT 1
        """,
            (row, n) -> row.getString(1),
            conversation,
            sequence);
    if (effect == ReportRepairEffects.Effect.DRAFT && !prior.isEmpty())
      throw new IllegalStateException("A repaired checkpoint cannot attempt another draft save");
    if (effect == ReportRepairEffects.Effect.FINISH
        && (prior.isEmpty() || !ReportRepairEffects.savedDraft(prior.getFirst())))
      throw new IllegalStateException(
          "A repaired checkpoint requires a confirmed draft before finish");
  }

  public Optional<Step> latest(String conversation) {
    return jdbc
        .query(
            "SELECT * FROM orchestration_script_steps WHERE conversation_id=? ORDER BY sequence DESC LIMIT 1",
            (row, n) -> step(row),
            conversation)
        .stream()
        .findFirst();
  }

  private static Step step(java.sql.ResultSet row) throws java.sql.SQLException {
    JsonNode retained = decode(row.getString("command"));
    UUID retryOfReport = null;
    if (retained instanceof ObjectNode object && object.has("retryOfReport")) {
      retryOfReport = UUID.fromString(object.remove("retryOfReport").textValue());
    }
    Command command = ScriptCommands.read(retained);
    if (retryOfReport != null && ScriptCommands.reportRequest(command).isEmpty())
      throw new IllegalStateException("report retry has an invalid retained command");
    return new Step(
        row.getInt("sequence"),
        row.getString("source_hash"),
        command,
        row.getString("raw_result"),
        row.getString("result"),
        row.getTimestamp("started_at") != null,
        row.getString("effective_arguments"),
        retryOfReport);
  }

  @Override
  public Optional<ReportBlock> blockedReport(String account, Step step) {
    if (reportReceipts == null
        || step == null
        || step.result() == null
        || !step.result().equals(step.raw())
        || !step.result().startsWith("Information request refused: ")
        || !(step.command() instanceof Tool tool)
        || !ScriptStore.sameArguments(tool.arguments(), step.arguments())) return Optional.empty();
    return ScriptCommands.reportRequest(tool)
        .flatMap(
            request ->
                reportReceipts
                    .refusedReport(account, request)
                    .filter(
                        refusal ->
                            step.raw().equals("Information request refused: " + refusal.reason()))
                    .map(refusal -> new ReportBlock(step.sequence(), request, refusal.reason())));
  }

  @Override
  public void prepareReportRetry(String conversation, Step previous, ReportBlock refusal) {
    if (!(previous.command() instanceof Tool tool) || previous.sequence() != refusal.sequence())
      throw new IllegalArgumentException("report retry requires the original completed command");
    UUID request =
        UUID.nameUUIDFromBytes(
            (conversation + ":" + (previous.sequence() + 1)).getBytes(StandardCharsets.UTF_8));
    String retry = ScriptCommands.retainedReportRetry(tool, refusal.requestId(), request);
    // The original refusal is immutable. The resume claim and append commit together, so a
    // duplicate Resume never schedules another command, even after a subsequent failure.
    int inserted =
        jdbc.update(
            """
        INSERT INTO orchestration_script_steps(conversation_id,sequence,source_hash,state,command)
        SELECT conversation_id,sequence+1,source_hash,state,?::jsonb
        FROM orchestration_script_steps previous
        WHERE conversation_id=? AND sequence=? AND source_hash=? AND result=?
          AND raw_result=result AND effective_arguments::jsonb=command->'arguments'
          AND NOT EXISTS (
            SELECT 1 FROM orchestration_script_steps later
            WHERE later.conversation_id=previous.conversation_id AND later.sequence>previous.sequence
          )
        """,
            retry,
            conversation,
            previous.sequence(),
            previous.hash(),
            previous.result());
    if (inserted != 1) throw new IllegalStateException("retained report command changed");
  }

  @Override
  public Optional<Set<UUID>> reportRetryInputs(String account, UUID originalRequest) {
    return reportReceipts == null
        ? Optional.empty()
        : reportReceipts
            .refusedReport(account, originalRequest)
            .map(InformationGateRepository.ReportRefusal::inputs);
  }

  private record Recovery(Step step, long failed, long answered, long missingAnswers) {}

  /** One current statement pairs its retained latest step with that exact step's child evidence. */
  @Override
  public void requireRecoverable(String conversation) {
    var rows =
        jdbc.query(
            """
        SELECT s.*,
          (SELECT count(*) FROM conversations child JOIN turns t ON t.conversation_id=child.id
           WHERE child.parent_id=s.conversation_id AND child.opened_by_call='script_'||s.sequence
             AND t.ordinal=(SELECT MAX(last.ordinal) FROM turns last WHERE last.conversation_id=child.id)
             AND t.ending IN ('UNAVAILABLE','SESSION_GONE')) AS failed_children,
          (SELECT count(*) FROM conversations child JOIN turns t ON t.conversation_id=child.id
           WHERE child.parent_id=s.conversation_id AND child.opened_by_call='script_'||s.sequence
             AND t.ending='ANSWERED') AS answered_children,
          (SELECT count(*) FROM conversations child JOIN turns t ON t.conversation_id=child.id
           WHERE child.parent_id=s.conversation_id AND child.opened_by_call='script_'||s.sequence
             AND t.ending='ANSWERED' AND t.answer IS NULL) AS missing_answers
        FROM orchestration_script_steps s WHERE s.conversation_id=? ORDER BY s.sequence DESC LIMIT 1
        """,
            (row, index) ->
                new Recovery(
                    step(row),
                    row.getLong("failed_children"),
                    row.getLong("answered_children"),
                    row.getLong("missing_answers")),
            conversation);
    if (rows.isEmpty()) return;
    var recovery = rows.getFirst();
    var step = recovery.step();
    if (step.result() != null || !step.started() || step.raw() != null) return;
    if (step.command() instanceof Wait || step.command() instanceof Finish) return;
    if (step.command() instanceof Tool tool && tool.readinessObserver()) return;
    if (step.command() instanceof Tool tool && tool.name().equals("agent_run")) {
      if (recovery.failed() > 1)
        throw new IllegalStateException("script command has multiple delegated runs");
      if (recovery.failed() == 1) return;
    }
    if (recovery.answered() > 1)
      throw new IllegalStateException("script command has multiple delegated answers");
    if (recovery.missingAnswers() > 0)
      throw new IllegalStateException("script delegation lost its answer");
    if (recovery.answered() == 1) return;
    var report = ScriptCommands.reportRequest(step.command());
    if (report.isPresent()
        && step.command() instanceof Tool tool
        && ScriptStore.sameArguments(tool.arguments(), step.arguments())) {
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Report save has no execution receipt. Reconcile request "
              + report.get()
              + " before resuming. Another write was not submitted.");
    }
    throw new io.aeyer.plowshare.server.faults.CallerFault(
        "The interrupted script command has no durable result; it may have run. Reconcile it before resuming; replay is refused.");
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
