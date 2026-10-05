package io.aeyer.plowshare.server.orchestrations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.MemoryIds;
import io.aeyer.plowshare.protocol.Orchestration.Structure;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.todos.StageRules;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The orchestrations this server is running, and the questions each has asked — {@code
 * orchestrations} and {@code orchestration_messages}, V48.
 *
 * <h2>Every change of state is a compare-and-set</h2>
 *
 * <p>{@code ConversationStore.turnEnded} and {@code ConversationStore.moveTo} are this class's
 * model: the database is the authority on where a run has got to, never a read the caller took a
 * moment ago. Every writer below names the state (or the set of states) it requires in its own
 * {@code WHERE}, and answers with whether it won rather than with the row — a second caller that
 * lost the race changes no row and is told so, instead of both callers believing they moved it.
 *
 * <h2>{@code answer} is the one write that is two statements</h2>
 *
 * <p>Everything else here is one UPDATE. Recording an answer is a compare-and-set from {@code
 * ASKING} to {@code RUNNING} <em>and</em> an insert of the answer message, and the second must not
 * happen unless the first won — an answer arriving twice, or arriving after somebody already moved
 * the run on some other way, must write nothing. That is a property of two statements taken
 * together, which is what {@link UnitOfWork} is for.
 */
public class OrchestrationStore {

  public static final String PREFIX = "orc_";
  public static final String MESSAGE_PREFIX = "orm_";

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String ORCHESTRATION_COLUMNS =
      "id, definition_name, tier,"
          + " definition_hash, definition_source, definition_origin, stages::text AS stages,"
          + " max_returns, returns_used, project, conductor_conversation, caller_conversation,"
          + " caller_agent, caller_handle, caller_session, parent, depth, waiting_for, state,"
          + " pending_cap, result, failure, restarts, nudges, ended_in_prose, result_delivered_at,"
          + " created_at, ended_at";

  private static final String SELECT_ORCHESTRATIONS =
      "SELECT " + ORCHESTRATION_COLUMNS + " FROM orchestrations";

  private static final String MESSAGE_COLUMNS =
      "id, orchestration, kind, text, author, created_at, delivered_at, cap_kind, structure";

  private static final String SELECT_MESSAGES =
      "SELECT " + MESSAGE_COLUMNS + " FROM orchestration_messages";

  /**
   * The latest question of a run currently waiting on one. Its own query rather than a splice of
   * {@link #SELECT_MESSAGES}: it joins {@code orchestrations} for the state, which no other read
   * here needs.
   */
  private static final String OPEN_QUESTION =
      "SELECT m.id, m.orchestration, m.kind, m.text,"
          + " m.author, m.created_at, m.delivered_at, m.cap_kind, m.structure"
          + " FROM orchestration_messages m"
          + " JOIN orchestrations o ON o.id = m.orchestration"
          + " WHERE m.orchestration = ? AND m.kind = ? AND o.state = ?"
          + " ORDER BY m.created_at DESC, m.id DESC LIMIT 1";

  /**
   * The newest {@code entries.recorded_at} across a run's whole delegation tree: the conductor
   * conversation named by {@code ?} and every conversation reachable from it by walking down {@code
   * parent_id} — a delegated child names its delegator there, {@code ConversationStore.log}'s own
   * rule for {@code Origin.DELEGATION}.
   *
   * <p><b>Not built by calling {@code ConversationStore.treeOf}</b>, which walks the identical
   * shape. {@code OrchestrationsConfig}'s own javadoc is explicit that this store depends on
   * nothing but {@code JdbcTemplate} and {@code UnitOfWork} — that is the reason {@code
   * TodosConfig.todoBoard} can ask for it with no wider graph behind it — and a dependency on
   * {@code ConversationStore} for one query would put a crack in exactly that. The recursion is
   * duplicated here rather than widening this class's dependencies for it.
   *
   * <p>{@code MAX} over a possibly empty join still returns one row, so this always answers with a
   * row; the row's one column is {@code NULL} when the tree holds no entries yet, which {@link
   * #quietSince} reads as "nothing to go on" rather than as a time.
   */
  private static final String QUIET_SINCE =
      """
            WITH RECURSIVE tree(id) AS (
                SELECT ?::text
              UNION ALL
                SELECT c.id FROM conversations c JOIN tree t ON c.parent_id = t.id
            )
            SELECT MAX(e.recorded_at) FROM entries e WHERE e.conversation_id IN (SELECT id FROM tree)""";

  private final JdbcTemplate jdbc;
  private final Supplier<Instant> clock;
  private final UnitOfWork unitOfWork;

  public OrchestrationStore(JdbcTemplate jdbc, Supplier<Instant> clock) {
    this(jdbc, clock, null);
  }

  /**
   * @param unitOfWork what {@link #answer} runs its two statements inside, or {@code null} for a
   *     store that will never be asked to record one — every other method here is a single
   *     statement and needs none
   */
  public OrchestrationStore(JdbcTemplate jdbc, Supplier<Instant> clock, UnitOfWork unitOfWork) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.unitOfWork = unitOfWork;
  }

  /**
   * Start a run. It begins {@link OrchestrationState#RUNNING}, with no returns spent and no caller
   * waiting on anything yet.
   */
  public OrchestrationRecord insert(NewOrchestration n) {
    Instant at = now();
    String id = MemoryIds.mint(PREFIX, at);
    jdbc.update(
        "INSERT INTO orchestrations (id, definition_name, tier, definition_hash,"
            + " definition_source, definition_origin, stages, max_returns, returns_used,"
            + " project, conductor_conversation, caller_conversation, caller_agent,"
            + " caller_handle, caller_session, parent, depth, state, restarts, nudges,"
            + " created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?)",
        id,
        n.definitionName(),
        n.tier().name(),
        n.definitionHash(),
        n.definitionSource(),
        n.definitionOrigin(),
        stagesJson(n.stages()),
        n.maxReturns(),
        n.project(),
        n.conductorConversation(),
        n.callerConversation(),
        n.callerAgent(),
        n.callerHandle(),
        n.callerSession(),
        n.parent(),
        n.depth(),
        OrchestrationState.RUNNING.wire(),
        utc(at));
    return new OrchestrationRecord(
        id,
        n.definitionName(),
        n.tier(),
        n.definitionHash(),
        n.definitionSource(),
        n.definitionOrigin(),
        n.stages(),
        n.maxReturns(),
        0,
        n.project(),
        n.conductorConversation(),
        n.callerConversation(),
        n.callerAgent(),
        n.callerHandle(),
        n.callerSession(),
        n.parent(),
        n.depth(),
        null,
        OrchestrationState.RUNNING,
        null,
        null,
        null,
        0,
        0,
        false,
        null,
        at,
        null);
  }

  public record StartReceipt(java.util.UUID requestId, String id, boolean created) {}

  /** Called inside the start transaction: receipt and run commit together. */
  public StartReceipt receiveStart(
      String account,
      java.util.UUID requestId,
      String payload,
      Supplier<OrchestrationRecord> insert) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
        Boolean.class,
        account + ":orchestration.start:" + requestId);
    var prior =
        jdbc.queryForList(
            "SELECT run_id, payload = CAST(? AS jsonb) AS matches"
                + " FROM orchestration_start_receipts WHERE account=? AND request_id=?",
            payload,
            account,
            requestId);
    if (!prior.isEmpty()) {
      if (!Boolean.TRUE.equals(prior.getFirst().get("matches"))) {
        throw new io.aeyer.plowshare.server.faults.CallerFault(
            "requestId was already used for a different orchestration start");
      }
      return new StartReceipt(requestId, (String) prior.getFirst().get("run_id"), false);
    }
    var run = insert.get();
    jdbc.update(
        "INSERT INTO orchestration_start_receipts(account,request_id,payload,run_id)"
            + " VALUES(?,?,CAST(? AS jsonb),?)",
        account,
        requestId,
        payload,
        run.id());
    return new StartReceipt(requestId, run.id(), true);
  }

  public Optional<StartReceipt> startReceipt(String account, java.util.UUID requestId) {
    return jdbc
        .query(
            "SELECT run_id FROM orchestration_start_receipts" + " WHERE account=? AND request_id=?",
            (row, index) -> new StartReceipt(requestId, row.getString("run_id"), false),
            account,
            requestId)
        .stream()
        .findFirst();
  }

  public Optional<OrchestrationRecord> find(String id) {
    return jdbc
        .query(SELECT_ORCHESTRATIONS + " WHERE id = ?", ORCHESTRATION_ROW_MAPPER, id)
        .stream()
        .findFirst();
  }

  public Optional<OrchestrationRecord> byConductorConversation(String conversation) {
    return jdbc
        .query(
            SELECT_ORCHESTRATIONS + " WHERE conductor_conversation = ?",
            ORCHESTRATION_ROW_MAPPER,
            conversation)
        .stream()
        .findFirst();
  }

  /**
   * Move a run between its two non-terminal states, by compare-and-set. {@code RUNNING}→{@code
   * ASKING} is the conductor asking a question.
   *
   * <p>{@code WAITING} is a third live state but not one of these two: entering it also sets {@code
   * waiting_for} and leaving it also clears that column, so {@link #waitFor} and {@link #wake} are
   * its only sanctioned doors — a plain {@code state} flip through this method would leave {@code
   * waiting_for} out of step and {@code orchestrations_waiting_for_iff_waiting} would refuse the
   * row.
   *
   * @return whether this call is the one that moved it
   * @throws IllegalArgumentException if either state is terminal — this method is for the two live
   *     states only, and a terminal state is reached through {@link #finish} or {@link #stop} — or
   *     if either state is {@code WAITING}, reached only through {@link #waitFor} and left only
   *     through {@link #wake}
   */
  public boolean moveTo(String id, OrchestrationState from, OrchestrationState to) {
    if (from.terminal() || to.terminal()) {
      throw new IllegalArgumentException(
          "moveTo is for a run's two non-terminal states only; "
              + from
              + " -> "
              + to
              + " names a terminal one. A terminal state is reached through finish()"
              + " or stop(), never through moveTo");
    }
    if (from == OrchestrationState.WAITING || to == OrchestrationState.WAITING) {
      throw new IllegalArgumentException(
          "moveTo is for RUNNING and ASKING only; "
              + from
              + " -> "
              + to
              + " names"
              + " WAITING, which is entered only through waitFor() and left only"
              + " through wake(), never through moveTo");
    }
    // stalled_since = NULL unconditionally: this move can leave RUNNING (in which case a
    // stall mark, if any, must go or orchestrations_only_a_running_run_is_stalled refuses the
    // row) or leave ASKING (which never carries one, so clearing it is a no-op).
    return jdbc.update(
            "UPDATE orchestrations SET state = ?, stalled_since = NULL WHERE id = ? AND state = ?",
            to.wire(),
            id,
            from.wire())
        == 1;
  }

  /**
   * A conductor starts a child and has nothing to do until it reports: {@code RUNNING} → {@code
   * WAITING}, naming the child in {@code waiting_for}.
   *
   * @return whether this call is the one that moved it
   * @throws IllegalArgumentException if {@code child} is {@code null} — {@code
   *     orchestrations_waiting_for_iff_waiting} would refuse the row, but this method catches it
   *     before that round trip, the way {@link #stop} names its own bad arguments up front
   */
  public boolean waitFor(String id, String child) {
    if (child == null) {
      throw new IllegalArgumentException(
          "waitFor needs the child being waited for; null names none");
    }
    // stalled_since = NULL: this leaves RUNNING for WAITING, which
    // orchestrations_only_a_running_run_is_stalled refuses to carry a mark into.
    return jdbc.update(
            "UPDATE orchestrations SET state = ?, waiting_for = ?,"
                + " stalled_since = NULL WHERE id = ? AND state = ?",
            OrchestrationState.WAITING.wire(),
            child,
            id,
            OrchestrationState.RUNNING.wire())
        == 1;
  }

  /**
   * A waiting parent is woken by a child's report: {@code WAITING} → {@code RUNNING}, clearing
   * {@code waiting_for}. Any live child's report wakes it, not only the one named in {@code
   * waiting_for} — a parent may hold several children live at once.
   *
   * @return whether this call is the one that woke it
   */
  public boolean wake(String id) {
    return jdbc.update(
            "UPDATE orchestrations SET state = ?, waiting_for = NULL"
                + " WHERE id = ? AND state = ?",
            OrchestrationState.RUNNING.wire(),
            id,
            OrchestrationState.WAITING.wire())
        == 1;
  }

  /**
   * {@link #wake}, but only while the row is waiting on {@code child} in particular — for a wait
   * that has lost its reason, such as a cancel of the very child it names, where a parent waiting
   * on some other child must be left waiting.
   *
   * @return whether this call is the one that woke it
   */
  public boolean wakeFrom(String id, String child) {
    return jdbc.update(
            "UPDATE orchestrations SET state = ?, waiting_for = NULL"
                + " WHERE id = ? AND state = ? AND waiting_for = ?",
            OrchestrationState.RUNNING.wire(),
            id,
            OrchestrationState.WAITING.wire(),
            child)
        == 1;
  }

  /**
   * End a run successfully. {@code RUNNING}→{@code FINISHED} only: a run waiting on an answer has
   * nothing to finish with, and must be answered or stopped first.
   *
   * @return whether this call is the one that ended it
   */
  public boolean finish(String id, String result) {
    Instant at = now();
    // stalled_since = NULL: this leaves RUNNING for FINISHED, which
    // orchestrations_only_a_running_run_is_stalled refuses to carry a mark into.
    return jdbc.update(
            "UPDATE orchestrations SET state = ?, result = ?, ended_at = ?,"
                + " stalled_since = NULL WHERE id = ? AND state = ?",
            OrchestrationState.FINISHED.wire(),
            result,
            utc(at),
            id,
            OrchestrationState.RUNNING.wire())
        == 1;
  }

  /**
   * End a run by a failure, a cap, or a cancellation, from any of its live states — including
   * {@code WAITING}, so a cascade can stop a waiting parent along with the child that triggered it.
   *
   * @return whether this call is the one that ended it
   * @throws IllegalArgumentException if {@code terminal} is not one of {@code FAILED}, {@code
   *     CAPPED} or {@code CANCELLED} — {@code FINISHED} goes through {@link #finish}, which writes
   *     a result rather than a failure, and {@code orchestrations_failure_only_when_it_stopped}
   *     refuses the row this method would otherwise write for it; and a non-terminal state is not a
   *     stopping state at all
   */
  public boolean stop(String id, OrchestrationState terminal, String failure) {
    return stopFrom(
        id,
        terminal,
        failure,
        false,
        OrchestrationState.RUNNING,
        OrchestrationState.ASKING,
        OrchestrationState.WAITING);
  }

  /**
   * {@link #stop}, but only from {@code ASKING} or {@code WAITING}: a {@code RUNNING} row is left
   * exactly as it is. Rule 2's own stop (spec 2026-09-27 §3) — a model caller never cancels running
   * work — held in the same statement that stops the row, because the model's tool reads the state
   * first, and the person answering an asking run between that read and an unguarded stop would
   * have had the model stop a run that was working again.
   *
   * <p><b>Nor one asking the person whether it goes on</b> ({@code stuck}, spec 2026-09-28): that
   * decision is the person's, cancelling included — a model that cannot answer it must not settle
   * it the other way. The person's own {@code /cancel} is {@link #stop}.
   *
   * @return whether this call is the one that ended it; false for a running, a stuck-asking or an
   *     ended row
   * @throws IllegalArgumentException as {@link #stop} does
   */
  public boolean stopUnlessRunning(String id, OrchestrationState terminal, String failure) {
    return stopFrom(
        id, terminal, failure, true, OrchestrationState.ASKING, OrchestrationState.WAITING);
  }

  /**
   * @param byAModel leave alone a row asking a question only the person may answer ({@code stuck},
   *     V58; {@code uncovered}, V65) — checked in the stop's own WHERE, so a run that went stuck
   *     after the model read it is not stopped by it either
   */
  private boolean stopFrom(
      String id,
      OrchestrationState terminal,
      String failure,
      boolean byAModel,
      OrchestrationState... from) {
    if (terminal == OrchestrationState.FINISHED) {
      throw new IllegalArgumentException(
          "stop is for FAILED, CAPPED or CANCELLED; FINISHED is reached through"
              + " finish() instead, with a result rather than a failure");
    }
    if (!terminal.terminal()) {
      throw new IllegalArgumentException(
          "stop is for a terminal state; " + terminal + " is not a stopping state");
    }
    Instant at = now();
    // stalled_since = NULL: a RUNNING row among the states this WHERE matches may carry a
    // mark, and orchestrations_only_a_running_run_is_stalled refuses it landing in a
    // terminal state; ASKING and WAITING never carry one, so clearing it there is a no-op.
    // Arrays.asList, not List.of: a stop's failure may be null, and List.of refuses one.
    List<Object> args = new ArrayList<>(Arrays.asList(terminal.wire(), failure, utc(at), id));
    for (OrchestrationState state : from) {
      args.add(state.wire());
    }
    return jdbc.update(
            "UPDATE orchestrations SET state = ?, pending_cap = NULL,"
                + " waiting_for = NULL, failure = ?, ended_at = ?, stalled_since = NULL"
                + " WHERE id = ? AND state IN ("
                + String.join(", ", Collections.nCopies(from.length, "?"))
                + ")"
                + (byAModel
                    ? " AND (pending_cap IS NULL OR pending_cap NOT IN ("
                        + Orchestrations.PERSON_ONLY.stream()
                            .map(kind -> "'" + kind + "'")
                            .sorted()
                            .collect(Collectors.joining(", "))
                        + "))"
                    : ""),
            args.toArray())
        == 1;
  }

  /**
   * Decision 6: count one more return against this run's limit, while it is running and under its
   * cap.
   *
   * @return whether this call is the one that counted it
   */
  public boolean countReturn(String conductorConversation) {
    return jdbc.update(
            "UPDATE orchestrations SET returns_used = returns_used + 1"
                + " WHERE conductor_conversation = ? AND state = ? AND returns_used < max_returns",
            conductorConversation,
            OrchestrationState.RUNNING.wire())
        == 1;
  }

  /**
   * Count one more nudge against a live run, and record for good that its conductor has ended a
   * turn in prose. The two are one event with two readers: the count is the {@code stuck} limit's
   * and progress resets it ({@link #progressed}); the mark is the forced tool call's ({@code
   * OrchestrationsConfig.hasEndedATurnInProse}) and nothing clears it, so a conductor forgiven its
   * count for moving a stage is not also let off calling a tool (V58).
   *
   * @return the new count, or empty if this run has already ended
   */
  public OptionalInt nudged(String id) {
    List<Integer> rows =
        jdbc.query(
            "UPDATE orchestrations SET nudges = nudges + 1, ended_in_prose = true"
                + " WHERE id = ? AND ended_at IS NULL RETURNING nudges",
            (rs, n) -> rs.getInt("nudges"),
            id);
    return rows.isEmpty() ? OptionalInt.empty() : OptionalInt.of(rows.get(0));
  }

  /**
   * Forget a live run's nudges, because it made progress: {@link Orchestrations#nudgeOrRestart}
   * fails a run {@code stuck} on its third nudge, which is meant for a conductor that has stopped
   * moving, not for prose endings spread across a run that is building phase after phase.
   */
  public void progressed(String id) {
    jdbc.update("UPDATE orchestrations SET nudges = 0 WHERE id = ? AND ended_at IS NULL", id);
  }

  /**
   * {@link #progressed}, for the live run a conversation conducts, if it conducts one — the shape
   * the two progress hooks outside this package are handed: a {@code todo_write} batch that moved a
   * stage ({@code TodosConfig}) and an {@code agent_run} that returned ({@code JobRuntime}). One
   * statement, so a conversation that conducts nothing costs one update that matches no row.
   */
  public void progressedIn(String conductorConversation) {
    jdbc.update(
        "UPDATE orchestrations SET nudges = 0"
            + " WHERE conductor_conversation = ? AND ended_at IS NULL",
        conductorConversation);
  }

  /**
   * Count one more restart against a live run.
   *
   * @return the new count, or empty if this run has already ended
   */
  public OptionalInt restarted(String id) {
    List<Integer> rows =
        jdbc.query(
            "UPDATE orchestrations SET restarts = restarts + 1"
                + " WHERE id = ? AND ended_at IS NULL RETURNING restarts",
            (rs, n) -> rs.getInt("restarts"),
            id);
    return rows.isEmpty() ? OptionalInt.empty() : OptionalInt.of(rows.get(0));
  }

  /**
   * Count one more time the acceptance verifier found requirements no command observes, at a run's
   * {@code acceptance: written} stage — V65. The gate asks the person on the third.
   *
   * @return the new count, or 0 if this run has already ended
   */
  public int verifierFound(String id) {
    List<Integer> rows =
        jdbc.query(
            "UPDATE orchestrations SET verifier_refusals = verifier_refusals + 1"
                + " WHERE id = ? AND ended_at IS NULL RETURNING verifier_refusals",
            (rs, n) -> rs.getInt("verifier_refusals"),
            id);
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  /** What the person accepted as written, as {@link #askUncovered}'s digest, or empty. */
  public Optional<String> verifierWaived(String id) {
    return jdbc
        .query(
            "SELECT verifier_waived FROM orchestrations WHERE id = ?",
            (rs, n) -> rs.getString("verifier_waived"),
            id)
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * The person answered {@link #askUncovered}: the count starts again, and on {@code accept} the
   * digest they were shown becomes the waived one. Settling again with nothing asked — a speak
   * retried once the conductor is free — keeps what was waived.
   *
   * @param accepted whether the person let the section stand as written
   */
  public void verifierSettled(String id, boolean accepted) {
    jdbc.update(
        "UPDATE orchestrations SET verifier_refusals = 0, verifier_waived = CASE"
            + " WHEN ? AND verifier_asked IS NOT NULL THEN verifier_asked"
            + " ELSE verifier_waived END, verifier_asked = NULL WHERE id = ?",
        accepted,
        id);
  }

  /**
   * Write a question or an answer outright.
   *
   * <p>For an answer arriving while the run may or may not still be waiting on one, use {@link
   * #answer} instead — it is the compare-and-set that decides whether the answer is allowed to land
   * at all. This method writes unconditionally, which is right for a question (nothing to compare
   * against) and for an answer a caller has already gated some other way.
   */
  public OrchestrationMessage addMessage(
      String orchestration, OrchestrationMessage.Kind kind, String text, String author) {
    Instant at = now();
    String id = MemoryIds.mint(MESSAGE_PREFIX, at);
    jdbc.update(
        "INSERT INTO orchestration_messages (id, orchestration, kind, text, author,"
            + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
        id,
        orchestration,
        kind.wire(),
        text,
        author,
        utc(at));
    return new OrchestrationMessage(id, orchestration, kind, text, author, at, null, null);
  }

  /**
   * Record an answer, if the run is still waiting for one.
   *
   * <p>One transaction: the compare-and-set from {@code ASKING} to {@code RUNNING} — which also
   * clears {@code pending_cap}, V49 — then the answer message, written only if that compare-and-set
   * won. An answer to a run that is not {@code ASKING} — running already, or ended — writes nothing
   * at all.
   *
   * <p><b>The row's {@code pending_cap} is read before it is cleared</b>, with a {@code SELECT ...
   * FOR UPDATE} that also locks the row against a second answer racing this one, and carried onto
   * the answer message's own {@code cap_kind} — Decision 7: an answer to a cap records which cap it
   * answered, because the row's own copy is gone the moment this write lands and a busy conductor
   * may be spoken to, and asked again, later.
   *
   * @return whether this answer is the one that won
   * @throws IllegalStateException if this store was built without a {@link UnitOfWork}
   */
  public boolean answer(String orchestration, String text, String author) {
    return answer(orchestration, text, author, null, true);
  }

  /** {@link #answer(String, String, String)}, recording the choices it made (V70). */
  public boolean answer(String orchestration, String text, String author, Structure structure) {
    return answer(orchestration, text, author, structure, true);
  }

  /**
   * {@link #answer}, refused while the open question is one only the person may answer — {@code
   * stuck}, V58, or {@code uncovered}, V65. A model's answer: the bot that started the run, or a
   * parent conductor. Refused inside the same {@code FOR UPDATE} that reads {@code pending_cap}, so
   * a run that went stuck after the model last read it is refused too, and nothing is written.
   *
   * @return whether this answer is the one that won; false also for a person-only question
   */
  public boolean answerUnlessPersonOnly(String orchestration, String text, String author) {
    return answer(orchestration, text, author, null, false);
  }

  /** {@link #answerUnlessPersonOnly(String, String, String)}, recording the choices (V70). */
  public boolean answerUnlessPersonOnly(
      String orchestration, String text, String author, Structure structure) {
    return answer(orchestration, text, author, structure, false);
  }

  private boolean answer(
      String orchestration, String text, String author, Structure structure, boolean byPerson) {
    if (unitOfWork == null) {
      throw new IllegalStateException(
          "answer() needs the UnitOfWork constructor; this store was built with the"
              + " two-argument one, which has none");
    }
    Instant at = now();
    return unitOfWork.inTransaction(
        () -> {
          List<String> found =
              jdbc.query(
                  "SELECT pending_cap FROM orchestrations WHERE id = ? FOR UPDATE",
                  (rs, n) -> rs.getString("pending_cap"),
                  orchestration);
          if (found.isEmpty()) {
            return false;
          }
          String capKind = found.get(0);
          if (!byPerson && Orchestrations.personOnly(capKind)) {
            return false;
          }
          // The spell spent asking is counted off the run's clock (V69) as it ends.
          int moved =
              jdbc.update(
                  "UPDATE orchestrations SET state = ?, pending_cap = NULL,"
                      + " asked_seconds = asked_seconds + COALESCE(GREATEST(0, FLOOR("
                      + "EXTRACT(EPOCH FROM (?::timestamptz - asking_since))))::bigint, 0),"
                      + " asking_since = NULL WHERE id = ? AND state = ?",
                  OrchestrationState.RUNNING.wire(),
                  utc(at),
                  orchestration,
                  OrchestrationState.ASKING.wire());
          if (moved != 1) {
            return false;
          }
          // The question is answered, so nobody is waiting for it to reach the caller any more:
          // a drain after this must not hand it back to be answered again.
          jdbc.update(
              "UPDATE orchestration_messages SET delivered_at = ?"
                  + " WHERE orchestration = ? AND kind = ? AND delivered_at IS NULL",
              utc(at),
              orchestration,
              OrchestrationMessage.Kind.QUESTION.wire());
          String id = MemoryIds.mint(MESSAGE_PREFIX, at);
          jdbc.update(
              "INSERT INTO orchestration_messages (id, orchestration, kind, text,"
                  + " author, created_at, cap_kind, structure)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
              id,
              orchestration,
              OrchestrationMessage.Kind.ANSWER.wire(),
              text,
              author,
              utc(at),
              capKind,
              OrchestrationStructures.encode(structure));
          return true;
        });
  }

  /**
   * Ask a question, if the run is still running.
   *
   * <p>One transaction, {@link #answer}'s own shape reversed: the compare-and-set from {@code
   * RUNNING} to {@code ASKING}, then the question message, written only if that compare-and-set
   * won. A run that is not {@code RUNNING} — already asking, or ended — has nothing to ask from and
   * this writes nothing at all.
   *
   * @return the question, or empty if the compare-and-set lost
   * @throws IllegalStateException if this store was built without a {@link UnitOfWork}
   */
  public Optional<OrchestrationMessage> ask(String orchestration, String question, String author) {
    return ask(orchestration, question, author, null);
  }

  /**
   * {@link #ask(String, String, String)}, with the question's options (V70) written in the same
   * compare-and-set.
   *
   * @param structure the question's {@code StructuredQuestions.structure}, or null for a plain one
   */
  public Optional<OrchestrationMessage> ask(
      String orchestration, String question, String author, Structure structure) {
    if (unitOfWork == null) {
      throw new IllegalStateException(
          "ask() needs the UnitOfWork constructor; this store was built with the"
              + " two-argument one, which has none");
    }
    Instant at = now();
    return unitOfWork.inTransaction(
        () -> {
          // stalled_since = NULL: this leaves RUNNING for ASKING, which
          // orchestrations_only_a_running_run_is_stalled refuses to carry a mark into.
          int moved =
              jdbc.update(
                  "UPDATE orchestrations SET state = ?, stalled_since = NULL, asking_since = ?"
                      + " WHERE id = ? AND state = ?",
                  OrchestrationState.ASKING.wire(),
                  utc(at),
                  orchestration,
                  OrchestrationState.RUNNING.wire());
          if (moved != 1) {
            return Optional.<OrchestrationMessage>empty();
          }
          String id = MemoryIds.mint(MESSAGE_PREFIX, at);
          jdbc.update(
              "INSERT INTO orchestration_messages (id, orchestration, kind, text,"
                  + " author, created_at, structure) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
              id,
              orchestration,
              OrchestrationMessage.Kind.QUESTION.wire(),
              question,
              author,
              utc(at),
              OrchestrationStructures.encode(structure));
          return Optional.of(
              new OrchestrationMessage(
                  id,
                  orchestration,
                  OrchestrationMessage.Kind.QUESTION,
                  question,
                  author,
                  at,
                  null,
                  null,
                  structure));
        });
  }

  /**
   * Ask about a cap the conductor's turn hit, if the run is still running — Decision 7: a cap is a
   * question to the caller, not an ending.
   *
   * <p>{@link #ask}'s own shape, plus {@code pending_cap} set in the same compare-and-set from
   * {@code RUNNING} to {@code ASKING}, so that only a run genuinely waiting on this exact question
   * ever carries it — {@code orchestrations_a_cap_is_pending_only_while_asking} refuses any other
   * row. The author is always {@code harness}: nobody else asks about a cap.
   *
   * @param capKind {@code turn_cap}, {@code call_budget} or {@code time_cap} (V69), or {@code
   *     stuck} for a run that ended three turns without progress (V58) or {@code check_failures}
   *     for one whose check kept failing (V69), which only the person may answer
   * @return the question, or empty if the compare-and-set lost
   * @throws IllegalStateException if this store was built without a {@link UnitOfWork}
   */
  public Optional<OrchestrationMessage> askCap(
      String orchestration, String capKind, String question) {
    return askCap(orchestration, capKind, question, null, null, null);
  }

  /**
   * {@link #askCap} for the person's question about the verifier's findings ({@code uncovered},
   * V65), with the digest of what the question shows them written in the same compare-and-set — the
   * answer settles exactly that ({@link #verifierSettled}), whatever spec.md says by then.
   *
   * @param digest what the person is shown: the requirements and the command lines, hashed
   * @return the question, or empty if the compare-and-set lost
   */
  public Optional<OrchestrationMessage> askUncovered(
      String orchestration, String digest, String question) {
    return askCap(
        orchestration,
        Orchestrations.UNCOVERED,
        question,
        Objects.requireNonNull(digest, "digest"),
        "verifier_asked",
        null);
  }

  /**
   * Ask the person to check the product (spec 2026-10-01, the acceptance checker §1): {@link
   * #askCap}'s compare-and-set with {@code pending_cap = 'product_check'}, and the digest of what
   * the question shows written with it — their {@code accept} accepts exactly that ({@link
   * #productSettled}), whatever spec.md says by then.
   *
   * @return the question, or empty if the compare-and-set lost
   */
  public Optional<OrchestrationMessage> askProduct(
      String orchestration, String digest, String question) {
    return askCap(
        orchestration,
        Orchestrations.PRODUCT_CHECK,
        question,
        Objects.requireNonNull(digest, "digest"),
        "product_asked",
        null);
  }

  /** The product check the person accepted, as {@link #askProduct}'s digest, or empty. */
  public Optional<String> productAccepted(String id) {
    return jdbc
        .query(
            "SELECT product_accepted FROM orchestrations WHERE id = ?",
            (rs, n) -> rs.getString("product_accepted"),
            id)
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * The person answered {@link #askProduct}: on {@code accept} the digest they were shown becomes
   * the accepted one; otherwise nothing is accepted, and a later check asks again. Settling again
   * with nothing asked — a speak retried once the conductor is free — keeps what was accepted.
   */
  public void productSettled(String id, boolean accepted) {
    jdbc.update(
        "UPDATE orchestrations SET product_accepted = CASE"
            + " WHEN ? AND product_asked IS NOT NULL THEN product_asked"
            + " ELSE product_accepted END, product_asked = NULL WHERE id = ?",
        accepted,
        id);
  }

  /**
   * Pin a run's acceptance checker (V77): the agent its definition's {@code checker:} named,
   * written once at start for a run that keeps its {@code acceptance: required} stage.
   */
  public void pinChecker(String id, String checker) {
    jdbc.update("UPDATE orchestrations SET checker = ? WHERE id = ?", checker, id);
  }

  /** The acceptance checker a run pinned at start, or empty for none. */
  public Optional<String> checker(String id) {
    return jdbc
        .query(
            "SELECT checker FROM orchestrations WHERE id = ?",
            (rs, n) -> rs.getString("checker"),
            id)
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * Ask the person whether to install a draft (V71, spec 2026-09-29-orchestration-studio §3.4):
   * {@link #askCap}'s compare-and-set with {@code pending_cap = 'install'}, and the question's
   * structure — its options, and the draft's whole text — written with it.
   *
   * @return the question, or empty if the compare-and-set lost
   */
  public Optional<OrchestrationMessage> askInstall(
      String orchestration, String question, Structure structure) {
    return askCap(
        orchestration,
        Orchestrations.INSTALL,
        question,
        null,
        null,
        Objects.requireNonNull(structure, "structure"));
  }

  /**
   * @param digest what the question shows, hashed, or null
   * @param digestColumn the column the digest is written to — {@code verifier_asked} or {@code
   *     product_asked}, never a caller's text — or null with no digest
   */
  private Optional<OrchestrationMessage> askCap(
      String orchestration,
      String capKind,
      String question,
      String digest,
      String digestColumn,
      Structure structure) {
    if (unitOfWork == null) {
      throw new IllegalStateException(
          "askCap() needs the UnitOfWork constructor; this store was built with the"
              + " two-argument one, which has none");
    }
    Instant at = now();
    String author = "harness";
    return unitOfWork.inTransaction(
        () -> {
          // stalled_since = NULL: this leaves RUNNING for ASKING, which
          // orchestrations_only_a_running_run_is_stalled refuses to carry a mark into.
          int moved =
              digest == null
                  ? jdbc.update(
                      "UPDATE orchestrations SET state = ?, pending_cap = ?,"
                          + " stalled_since = NULL, asking_since = ? WHERE id = ? AND state = ?",
                      OrchestrationState.ASKING.wire(),
                      capKind,
                      utc(at),
                      orchestration,
                      OrchestrationState.RUNNING.wire())
                  : jdbc.update(
                      "UPDATE orchestrations SET state = ?, pending_cap = ?,"
                          + " stalled_since = NULL, asking_since = ?, "
                          + digestColumn
                          + " = ?"
                          + " WHERE id = ? AND state = ?",
                      OrchestrationState.ASKING.wire(),
                      capKind,
                      utc(at),
                      digest,
                      orchestration,
                      OrchestrationState.RUNNING.wire());
          if (moved != 1) {
            return Optional.<OrchestrationMessage>empty();
          }
          String id = MemoryIds.mint(MESSAGE_PREFIX, at);
          jdbc.update(
              "INSERT INTO orchestration_messages (id, orchestration, kind, text,"
                  + " author, created_at, structure) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
              id,
              orchestration,
              OrchestrationMessage.Kind.QUESTION.wire(),
              question,
              author,
              utc(at),
              OrchestrationStructures.encode(structure));
          return Optional.of(
              new OrchestrationMessage(
                  id,
                  orchestration,
                  OrchestrationMessage.Kind.QUESTION,
                  question,
                  author,
                  at,
                  null,
                  null,
                  structure));
        });
  }

  /** The latest question of a run that is currently {@code ASKING} — the one an answer answers. */
  public Optional<OrchestrationMessage> openQuestion(String orchestration) {
    return jdbc
        .query(
            OPEN_QUESTION,
            MESSAGE_ROW_MAPPER,
            orchestration,
            OrchestrationMessage.Kind.QUESTION.wire(),
            OrchestrationState.ASKING.wire())
        .stream()
        .findFirst();
  }

  public List<OrchestrationMessage> messages(String orchestration) {
    return jdbc.query(
        SELECT_MESSAGES + " WHERE orchestration = ? ORDER BY created_at, id",
        MESSAGE_ROW_MAPPER,
        orchestration);
  }

  /**
   * @return whether this call is the one that marked it delivered
   */
  public boolean messageDelivered(String messageId) {
    return jdbc.update(
            "UPDATE orchestration_messages SET delivered_at = ?"
                + " WHERE id = ? AND delivered_at IS NULL",
            utc(now()),
            messageId)
        == 1;
  }

  /**
   * Every undelivered message of a run that has not ended — a stopped run's undelivered question is
   * not something anybody is waiting to answer any more.
   */
  public List<OrchestrationMessage> undeliveredMessages() {
    return jdbc.query(
        "SELECT m.id, m.orchestration, m.kind, m.text, m.author,"
            + " m.created_at, m.delivered_at, m.cap_kind, m.structure"
            + " FROM orchestration_messages m"
            + " JOIN orchestrations o ON o.id = m.orchestration"
            + " WHERE m.delivered_at IS NULL AND o.ended_at IS NULL"
            + " ORDER BY m.created_at, m.id",
        MESSAGE_ROW_MAPPER);
  }

  /**
   * @return whether this call is the one that marked it delivered
   */
  public boolean resultDelivered(String id) {
    return jdbc.update(
            "UPDATE orchestrations SET result_delivered_at = ?"
                + " WHERE id = ? AND result_delivered_at IS NULL",
            utc(now()),
            id)
        == 1;
  }

  /** Marks only the named terminal attempt, so delayed delivery cannot settle a resumed run. */
  public boolean resultDelivered(String id, Instant endedAt) {
    Objects.requireNonNull(endedAt, "endedAt");
    return jdbc.update(
            "UPDATE orchestrations SET result_delivered_at=? WHERE id=? AND ended_at=? AND result_delivered_at IS NULL",
            utc(now()),
            id,
            utc(endedAt))
        == 1;
  }

  /** Every terminal run whose ending nobody has been told about yet. */
  public List<OrchestrationRecord> undeliveredEndings() {
    return jdbc.query(
        SELECT_ORCHESTRATIONS
            + " WHERE ended_at IS NOT NULL AND result_delivered_at IS NULL"
            + " ORDER BY ended_at, id",
        ORCHESTRATION_ROW_MAPPER);
  }

  /** Every run that has not ended. */
  public List<OrchestrationRecord> live() {
    return jdbc.query(
        SELECT_ORCHESTRATIONS + " WHERE ended_at IS NULL" + " ORDER BY created_at, id",
        ORCHESTRATION_ROW_MAPPER);
  }

  /**
   * Every run in state {@code running} — a stall sweep's own starting set, spec §4: only a running
   * run can go quiet, since {@code ASKING} and {@code WAITING} are already waiting on somebody else
   * by design and a terminal state has nothing left to do.
   */
  public List<OrchestrationRecord> running() {
    return jdbc.query(
        SELECT_ORCHESTRATIONS + " WHERE state = ?",
        ORCHESTRATION_ROW_MAPPER,
        OrchestrationState.RUNNING.wire());
  }

  /**
   * When {@code run} last did anything, anywhere in its delegation tree — the newest {@code
   * entries.recorded_at} under its conductor conversation, or {@link OrchestrationRecord#createdAt}
   * when the tree holds no entries yet (a run whose conductor has not written its first entry, or
   * whose every entry predates {@code recorded_at} itself, V16).
   *
   * @see #QUIET_SINCE for why this walks {@code parent_id} itself rather than through {@code
   *     ConversationStore}
   */
  public Instant quietSince(OrchestrationRecord run) {
    OffsetDateTime latest =
        jdbc.queryForObject(QUIET_SINCE, OffsetDateTime.class, run.conductorConversation());
    return latest == null ? run.createdAt() : latest.toInstant();
  }

  /**
   * Record that a stall sweep judged this run quiet since {@code since}, if it is still running and
   * nobody has already recorded a mark for this same or a later quiet period — compare-and-set, so
   * a stall is reported once per quiet period even when several servers share this database and two
   * sweeps race to the same row.
   *
   * <p>A {@code since} later than the mark already on the row still wins, replacing it: a sweep is
   * not guaranteed to run while a run is briefly active between two stalls, so the row can carry a
   * mark from an earlier quiet period with nothing having cleared it when a later one begins.
   * Without this, the CAS below would see a mark already there and refuse the row, silently
   * dropping the second, later stall. A {@code since} no later than the mark already there — the
   * same quiet period the last winning call reported, or an out-of-order one older than it — is
   * refused, which is what keeps a quiet period reported exactly once.
   *
   * @return whether this call is the one that set it
   */
  public boolean markStalled(String id, Instant since) {
    return jdbc.update(
            "UPDATE orchestrations SET stalled_since = ? WHERE id = ? AND state = ?"
                + " AND (stalled_since IS NULL OR stalled_since < ?)",
            utc(since),
            id,
            OrchestrationState.RUNNING.wire(),
            utc(since))
        == 1;
  }

  /**
   * Clear a run's stall mark — a run moving again is no longer the sweep's own judgement of quiet,
   * whatever moved it.
   *
   * @return whether this call is the one that cleared it
   */
  public boolean clearStalled(String id) {
    return jdbc.update(
            "UPDATE orchestrations SET stalled_since = NULL"
                + " WHERE id = ? AND stalled_since IS NOT NULL",
            id)
        == 1;
  }

  /**
   * When a stall sweep last marked this run quiet, or empty while it has none — no row named {@code
   * id} answers the same as a row with none, since either way there is nothing to report.
   */
  public Optional<Instant> stalledSince(String id) {
    List<Instant> found =
        jdbc.query(
            "SELECT stalled_since FROM orchestrations WHERE id = ?",
            (rs, n) -> instant(rs, "stalled_since"),
            id);
    return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.get(0));
  }

  /**
   * Every child a run has started, newest first, whether it has ended or not — the ending cascade
   * and a status view both want the full history, not only what is still live.
   */
  public List<OrchestrationRecord> children(String parent) {
    return jdbc.query(
        SELECT_ORCHESTRATIONS + " WHERE parent = ?" + " ORDER BY created_at DESC, id DESC",
        ORCHESTRATION_ROW_MAPPER,
        parent);
  }

  /**
   * A run's children that have not ended — what an ending cascade must stop and what {@code
   * orchestration_finish} must refuse while any remain, newest first.
   */
  public List<OrchestrationRecord> liveChildren(String parent) {
    return jdbc.query(
        SELECT_ORCHESTRATIONS
            + " WHERE parent = ? AND ended_at IS NULL"
            + " ORDER BY created_at DESC, id DESC",
        ORCHESTRATION_ROW_MAPPER,
        parent);
  }

  /**
   * Names of the definitions with a run still live (running, asking, or waiting) whose caller spoke
   * from this conversation.
   */
  public Set<String> liveDefinitionsFrom(String callerConversation) {
    return Set.copyOf(
        jdbc.query(
            "SELECT definition_name FROM orchestrations"
                + " WHERE caller_conversation = ? AND ended_at IS NULL",
            (rs, n) -> rs.getString("definition_name"),
            callerConversation));
  }

  /**
   * One account's runs, newest first. {@code project} and {@code state} are optional filters
   * ({@code null} = any).
   *
   * @throws IllegalArgumentException if {@code limit} is outside 1..200
   */
  private boolean informationProtected;

  public void protectInformation() {
    informationProtected = true;
  }

  public List<OrchestrationRecord> byCaller(
      String handle, String project, OrchestrationState state, int limit) {
    if (limit < 1 || limit > 200) {
      throw new IllegalArgumentException("limit must be between 1 and 200, was " + limit);
    }
    StringBuilder sql = new StringBuilder(SELECT_ORCHESTRATIONS).append(" WHERE caller_handle = ?");
    List<Object> params = new ArrayList<>();
    params.add(handle);
    if (informationProtected) {
      sql.append(" AND information_log_readable(id,?)");
      params.add(handle);
    }
    if (project != null) {
      sql.append(" AND project = ?");
      params.add(project);
    }
    if (state != null) {
      sql.append(" AND state = ?");
      params.add(state.wire());
    }
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
    params.add(limit);
    return jdbc.query(sql.toString(), ORCHESTRATION_ROW_MAPPER, params.toArray());
  }

  /**
   * One account's live runs in one project, oldest first — every one, where {@link #byCaller} is a
   * page of the newest: {@code Orchestrations.applyCaps} must reach each run still going, however
   * many ended since.
   *
   * @param handle the account
   * @param project the project
   * @return its runs there that have not ended
   */
  public List<OrchestrationRecord> liveByCaller(String handle, String project) {
    return jdbc.query(
        SELECT_ORCHESTRATIONS
            + " WHERE caller_handle = ? AND project = ?"
            + " AND ended_at IS NULL ORDER BY created_at, id",
        ORCHESTRATION_ROW_MAPPER,
        handle,
        project);
  }

  /** Where a run writes and, for a phase, the parent todo it was started under — V60. */
  public void placed(String id, String artifactsDir, String phaseTodo) {
    jdbc.update(
        "UPDATE orchestrations SET artifacts_dir = ?, phase_todo = ? WHERE id = ?",
        artifactsDir,
        phaseTodo,
        id);
  }

  /** The directory a run's first message named, or empty for one that named none — V60. */
  public Optional<String> artifactsDir(String id) {
    return jdbc
        .query(
            "SELECT artifacts_dir FROM orchestrations WHERE id = ?",
            (rs, n) -> rs.getString("artifacts_dir"),
            id)
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  /** The live child of {@code parent} started under its todo item {@code todoItem} — rule 1. */
  public Optional<OrchestrationRecord> livePhaseRun(String parent, String todoItem) {
    return jdbc
        .query(
            SELECT_ORCHESTRATIONS
                + " WHERE parent = ? AND phase_todo = ?"
                + " AND ended_at IS NULL ORDER BY created_at DESC LIMIT 1",
            ORCHESTRATION_ROW_MAPPER,
            parent,
            todoItem)
        .stream()
        .findFirst();
  }

  /**
   * Project-enabled grants have no configured continuation count; the durable counter remains
   * finite.
   */
  public OptionalInt capIncreased(String id) {
    List<Integer> rows =
        jdbc.query(
            "UPDATE orchestrations SET cap_continues = cap_continues + 1"
                + " WHERE id = ? AND ended_at IS NULL AND cap_continues < 2147483647"
                + " RETURNING cap_continues",
            (rs, n) -> rs.getInt("cap_continues"),
            id);
    return rows.isEmpty() ? OptionalInt.empty() : OptionalInt.of(rows.get(0));
  }

  /**
   * Count one cap a live run passes without asking, while fewer than {@code most} have been — spec
   * 2026-09-29 §2's auto-continue.
   *
   * @return the new count, or empty when {@code most} are already spent or the run has ended
   */
  public OptionalInt capContinued(String id, int most) {
    List<Integer> rows =
        jdbc.query(
            "UPDATE orchestrations SET cap_continues = cap_continues + 1"
                + " WHERE id = ? AND ended_at IS NULL AND cap_continues < ?"
                + " RETURNING cap_continues",
            (rs, n) -> rs.getInt("cap_continues"),
            id,
            most);
    return rows.isEmpty() ? OptionalInt.empty() : OptionalInt.of(rows.get(0));
  }

  /**
   * A run's clock for its time cap (V69): the seconds it has run — the wall clock since it was
   * created, less every spell it spent asking, the one still open included — and where its current
   * span began on that count.
   *
   * @param counted seconds run, never negative
   * @param spanFrom the count at which the current span began: 0, or the count at the last "go on"
   *     to its time cap
   */
  public record RunClock(long counted, long spanFrom) {

    /**
     * @return the seconds run in the current span
     */
    public long inSpan() {
      return Math.max(0, counted - spanFrom);
    }
  }

  /**
   * {@link RunClock} as it reads at {@code now}.
   *
   * @return the clock, or empty for no such run
   */
  public Optional<RunClock> clockOf(String id, Instant now) {
    return jdbc
        .query(
            "SELECT created_at, asked_seconds, asking_since, time_span_from"
                + " FROM orchestrations WHERE id = ?",
            (rs, n) -> {
              Instant created = instant(rs, "created_at");
              Instant asking = instant(rs, "asking_since");
              long run =
                  Duration.between(created, now).toSeconds()
                      - rs.getLong("asked_seconds")
                      - (asking == null
                          ? 0
                          : Math.max(0, Duration.between(asking, now).toSeconds()));
              return new RunClock(Math.max(0, run), rs.getLong("time_span_from"));
            },
            id)
        .stream()
        .findFirst();
  }

  /**
   * A live run's time span starts again at {@code counted} — a "go on" to its time cap.
   *
   * @return whether a live run was moved
   */
  public boolean timeSpanFrom(String id, long counted) {
    return jdbc.update(
            "UPDATE orchestrations SET time_span_from = ?" + " WHERE id = ? AND ended_at IS NULL",
            Math.max(0, counted),
            id)
        == 1;
  }

  /**
   * Count one more failure of a live run's check or acceptance commands (V69).
   *
   * @return the count since it started or since the person last answered, or 0 for a run that has
   *     ended
   */
  public int checkFailed(String id) {
    List<Integer> rows =
        jdbc.query(
            "UPDATE orchestrations SET check_failures = check_failures + 1"
                + " WHERE id = ? AND ended_at IS NULL RETURNING check_failures",
            (rs, n) -> rs.getInt("check_failures"),
            id);
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  /** The person answered {@code check_failures} "go on": the count starts again. */
  public void checkFailuresSettled(String id) {
    jdbc.update("UPDATE orchestrations SET check_failures = 0 WHERE id = ?", id);
  }

  /** The newest answer a run was given, or empty. */
  public Optional<OrchestrationMessage> latestAnswer(String orchestration) {
    List<OrchestrationMessage> answers =
        jdbc.query(
            SELECT_MESSAGES
                + " WHERE orchestration = ? AND kind = ? ORDER BY created_at DESC, id DESC LIMIT 1",
            MESSAGE_ROW_MAPPER,
            orchestration,
            OrchestrationMessage.Kind.ANSWER.wire());
    return answers.stream().findFirst();
  }

  private Instant now() {
    return clock.get().truncatedTo(ChronoUnit.MICROS);
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  /**
   * {@code [{"id":…,"may_return_to":[…]}]} — the pinned stages, V48's own comment on the column.
   * Snake case because it is what the harness and a psql session both read, not a name Jackson
   * would derive from {@link StageRules.Stage}'s own field names, so this is built by hand rather
   * than handed the record.
   *
   * <p>{@code "check":true} is written only for a checked stage — spec 2026-09-26 — and left out
   * otherwise, so a row written before checked stages existed still reads back byte-for-byte the
   * same and {@link #parseStages} still defaults it to {@code false}. {@code "acceptance"} is the
   * same: written only when the stage has one — spec 2026-09-29 §1b — and absent otherwise reads
   * back as {@code null}. {@code "holds_phases":true} likewise, only on the stage whose child todos
   * are the phases, and absent reads back as {@code false}.
   */
  private static String stagesJson(List<StageRules.Stage> stages) {
    ArrayNode array = JSON.createArrayNode();
    for (StageRules.Stage stage : stages) {
      ObjectNode node = JSON.createObjectNode();
      node.put("id", stage.id());
      ArrayNode mayReturnTo = node.putArray("may_return_to");
      stage.mayReturnTo().forEach(mayReturnTo::add);
      if (stage.checked()) {
        node.put("check", true);
      }
      if (stage.acceptance() != null) {
        node.put("acceptance", stage.acceptance());
      }
      if (stage.holdsPhases()) {
        node.put("holds_phases", true);
      }
      array.add(node);
    }
    return array.toString();
  }

  private static List<StageRules.Stage> parseStages(String json) {
    try {
      JsonNode array = JSON.readTree(json);
      List<StageRules.Stage> stages = new ArrayList<>();
      for (JsonNode node : array) {
        List<String> mayReturnTo = new ArrayList<>();
        node.get("may_return_to").forEach(m -> mayReturnTo.add(m.asText()));
        stages.add(
            new StageRules.Stage(
                node.get("id").asText(),
                mayReturnTo,
                node.path("check").asBoolean(false),
                node.hasNonNull("acceptance") ? node.get("acceptance").asText() : null,
                node.path("holds_phases").asBoolean(false)));
      }
      return stages;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "stages column held text that is not the JSON this" + " store writes: " + json, e);
    }
  }

  private static final RowMapper<OrchestrationRecord> ORCHESTRATION_ROW_MAPPER =
      (rs, n) ->
          new OrchestrationRecord(
              rs.getString("id"),
              rs.getString("definition_name"),
              OrchestrationDefinition.Tier.valueOf(rs.getString("tier")),
              rs.getString("definition_hash"),
              rs.getString("definition_source"),
              rs.getString("definition_origin"),
              parseStages(rs.getString("stages")),
              rs.getInt("max_returns"),
              rs.getInt("returns_used"),
              rs.getString("project"),
              rs.getString("conductor_conversation"),
              rs.getString("caller_conversation"),
              rs.getString("caller_agent"),
              rs.getString("caller_handle"),
              rs.getString("caller_session"),
              rs.getString("parent"),
              rs.getInt("depth"),
              rs.getString("waiting_for"),
              OrchestrationState.of(rs.getString("state")),
              rs.getString("pending_cap"),
              rs.getString("result"),
              rs.getString("failure"),
              rs.getInt("restarts"),
              rs.getInt("nudges"),
              rs.getBoolean("ended_in_prose"),
              instant(rs, "result_delivered_at"),
              instant(rs, "created_at"),
              instant(rs, "ended_at"));

  private static final RowMapper<OrchestrationMessage> MESSAGE_ROW_MAPPER =
      (rs, n) ->
          new OrchestrationMessage(
              rs.getString("id"),
              rs.getString("orchestration"),
              OrchestrationMessage.Kind.of(rs.getString("kind")),
              rs.getString("text"),
              rs.getString("author"),
              instant(rs, "created_at"),
              instant(rs, "delivered_at"),
              rs.getString("cap_kind"),
              OrchestrationStructures.decode(rs.getString("structure")));

  /**
   * What starts a run. {@code stages} is pinned onto the row at this moment, so an edited
   * definition cannot strand a run already under way — V48's own comment on the column. {@code
   * definitionSource} and {@code definitionOrigin} are pinned the same way, since V49, so the
   * engine can rebuild the conductor after a restart without re-resolving a tier that may have
   * changed or, for a laptop session, gone.
   *
   * @param parent the orchestration that started this run, or {@code null} for a root run — V52
   * @param depth how many parents this run has; a root run is 0 — V52
   */
  public record NewOrchestration(
      String definitionName,
      OrchestrationDefinition.Tier tier,
      String definitionHash,
      String definitionSource,
      String definitionOrigin,
      List<StageRules.Stage> stages,
      int maxReturns,
      String project,
      String conductorConversation,
      String callerConversation,
      String callerAgent,
      String callerHandle,
      String callerSession,
      String parent,
      int depth) {

    public NewOrchestration {
      stages = List.copyOf(stages);
    }
  }
}
