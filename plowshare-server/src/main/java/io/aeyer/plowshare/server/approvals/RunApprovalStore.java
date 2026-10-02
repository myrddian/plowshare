package io.aeyer.plowshare.server.approvals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@code run_approvals}: the questions {@code run}'s gate asks, and the approvals they become.
 * Spec 2026-09-15, asking a person, §3.
 *
 * <p><b>Every change of state is a compare-and-set</b>, as {@code OrchestrationStore}'s are: an
 * answer moves {@code asked} and nothing else, a {@code once} approval is consumed from {@code
 * allowed} and nothing else, and a revoke moves a {@code project} approval from {@code allowed}.
 * A {@code false} is the other party having got there first.
 */
public class RunApprovalStore {

    public static final String PREFIX = "apr_";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<List<List<String>>> SETS = new TypeReference<>() {};

    private static final String SELECT = "SELECT id, project_id, conversation, asked_in, handle, agent, side,"
            + " argv::text AS argv, cwd, reason, state, scope, prefix::text AS prefix, answered_by,"
            + " answered_at, delivered_at, created_at, commands::text AS commands, judged"
            + " FROM run_approvals";

    private static final Logger log = LoggerFactory.getLogger(RunApprovalStore.class);

    private final JdbcTemplate jdbc;
    private final Supplier<Instant> clock;

    /** Told of every question and every winning answer; {@link ApprovalEvents#NONE} until
     *  wired — the orchestration record's approvals (spec 2026-09-28 §2). */
    private volatile ApprovalEvents events = ApprovalEvents.NONE;

    public RunApprovalStore(JdbcTemplate jdbc, Supplier<Instant> clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void useEvents(ApprovalEvents events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    /** Told the id of every question an answer settled — any decision, a denial, a withdrawal
     *  ({@link RunApproval#SUPERSEDED}, {@link RunApproval#RUN_ENDED}) — so the person's inbox
     *  notice about it leaves (V68). Nothing until wired; a throw is logged, never the answer's. */
    private volatile Consumer<String> settled = id -> { };

    public void whenSettled(Consumer<String> settled) {
        this.settled = Objects.requireNonNull(settled, "settled");
    }

    private static void tell(String what, Runnable told) {
        try {
            told.run();
        } catch (RuntimeException failed) {
            log.warn("{} was written but could not be told", what, failed);
        }
    }

    /** A new question, in state {@code asked}. */
    public RunApproval ask(long projectId, String conversation, String askedIn, String agent,
            String side, List<String> argv, String cwd, String reason) {
        return ask(projectId, conversation, askedIn, null, agent, side, argv, cwd, reason);
    }

    /** A new question, pinning the account for unattended delivery when there is one. */
    public RunApproval ask(long projectId, String conversation, String askedIn, String handle,
            String agent, String side,
            List<String> argv, String cwd, String reason) {
        String id = newId();
        jdbc.update("INSERT INTO run_approvals (id, project_id, conversation, asked_in, handle, agent, side,"
                        + " argv, cwd, reason, state, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                id, projectId, conversation, askedIn, handle, agent, side, json(argv), cwd, reason, RunApproval.ASKED,
                Timestamp.from(clock.get()));
        RunApproval asked = find(id).orElseThrow();
        tell("approval " + id, () -> events.asked(asked));
        return asked;
    }

    /**
     * A new question about an acceptance set, in state {@code asked}: ONE approval for every
     * command of the set (V67), its own {@code argv} the empty list, which no command matches.
     *
     * @param commands each command's argv, in the set's order; at least one
     * @param reason what the person is shown: every command, with what it is given and must show
     * @param judged why the command judge put it to the person, or null
     */
    public RunApproval askSet(long projectId, String conversation, String askedIn, String handle,
            String agent, String side, List<List<String>> commands, String cwd, String reason,
            String judged) {
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("a set approval asks about at least one command");
        }
        String id = newId();
        jdbc.update("INSERT INTO run_approvals (id, project_id, conversation, asked_in, handle,"
                        + " agent, side, argv, cwd, reason, state, created_at, commands, judged)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, '[]'::jsonb, ?, ?, ?, ?, ?::jsonb, ?)",
                id, projectId, conversation, askedIn, handle, agent, side, cwd, reason,
                RunApproval.ASKED, Timestamp.from(clock.get()), sets(commands), judged);
        RunApproval asked = find(id).orElseThrow();
        tell("approval " + id, () -> events.asked(asked));
        return asked;
    }

    /**
     * An approval nobody was asked: written already {@code allowed}, answered by {@code by} —
     * {@link RunApproval#JUDGE} — and told as an answer, never as a question, so no person is
     * shown a question already settled. Its scope is {@code once}, the narrowest answer a person
     * gives: the run tool may spend it on one run of the same command, which leaves it {@code
     * used} — and {@code used} lets a check or an acceptance command run every time its stage is
     * marked done, as a person's {@code once} does. A set's empty {@code argv} matches no run tool
     * call at all.
     *
     * @param argv the command, or the empty list for a set
     * @param commands the set's commands, or null for one command
     * @param judged the judge's one line about it
     */
    public RunApproval allowedBy(long projectId, String conversation, String askedIn,
            String handle, String agent, String side, List<String> argv,
            List<List<String>> commands, String cwd, String reason, String by, String judged) {
        String id = newId();
        Timestamp now = Timestamp.from(clock.get());
        jdbc.update("INSERT INTO run_approvals (id, project_id, conversation, asked_in, handle,"
                        + " agent, side, argv, cwd, reason, state, scope, answered_by, answered_at,"
                        + " created_at, commands, judged)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                id, projectId, conversation, askedIn, handle, agent, side,
                json(commands == null ? argv : List.of()), cwd, reason, RunApproval.ALLOWED,
                RunApproval.ONCE, by, now, now,
                commands == null ? null : sets(commands), judged);
        RunApproval allowed = find(id).orElseThrow();
        tell("approval " + id, () -> events.answered(allowed));
        return allowed;
    }

    private static String newId() {
        return PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public Optional<RunApproval> find(String id) {
        return jdbc.query(SELECT + " WHERE id = ?", ROW, id).stream().findFirst();
    }

    /** Open questions raised anywhere under one root and not yet delivered outside that run. */
    public List<RunApproval> undelivered(String conversation) {
        return jdbc.query(SELECT + " WHERE conversation = ? AND state = ? AND delivered_at IS NULL"
                + " ORDER BY created_at, id", ROW, conversation, RunApproval.ASKED);
    }

    /** Every open question still waiting for its account, for boot recovery. */
    public List<RunApproval> undelivered() {
        return jdbc.query(SELECT + " WHERE state = ? AND delivered_at IS NULL AND handle IS NOT NULL"
                + " ORDER BY created_at, id", ROW, RunApproval.ASKED);
    }

    public void delivered(String id) {
        jdbc.update("UPDATE run_approvals SET delivered_at = ? WHERE id = ? AND delivered_at IS NULL",
                Timestamp.from(clock.get()), id);
    }

    /** The questions a conversation has not had answered, oldest first. */
    public List<RunApproval> open(String conversation) {
        return jdbc.query(SELECT + " WHERE conversation = ? AND state = ? ORDER BY created_at, id",
                ROW, conversation, RunApproval.ASKED);
    }

    /** Every question an account has not had answered, wherever it was raised, oldest first. */
    public List<RunApproval> openFor(String handle) {
        return jdbc.query(SELECT + " WHERE handle = ? AND state = ? ORDER BY created_at, id",
                ROW, handle, RunApproval.ASKED);
    }

    /** A project's standing {@code project} approvals, newest first. */
    public List<RunApproval> standing(long projectId) {
        return jdbc.query(SELECT + " WHERE project_id = ? AND scope = ? AND state = ?"
                + " ORDER BY answered_at DESC, id", ROW, projectId, RunApproval.PROJECT, RunApproval.ALLOWED);
    }

    /**
     * Allow a question, for a scope. {@code false} when it was not {@code asked} any more.
     *
     * @param prefix required for {@code project}, ignored otherwise
     */
    public boolean allow(String id, String scope, List<String> prefix, String by) {
        String prefixJson = RunApproval.PROJECT.equals(scope) ? json(prefix) : null;
        boolean won = jdbc.update("UPDATE run_approvals SET state = ?, scope = ?, prefix = ?::jsonb,"
                        + " answered_by = ?, answered_at = ? WHERE id = ? AND state = ?",
                RunApproval.ALLOWED, scope, prefixJson, by, Timestamp.from(clock.get()), id,
                RunApproval.ASKED) == 1;
        if (won) {
            find(id).ifPresent(answered -> tell("approval " + id, () -> events.answered(answered)));
            tell("the settling of approval " + id, () -> settled.accept(id));
        }
        return won;
    }

    /** Deny a question. {@code false} when it was not {@code asked} any more. */
    public boolean deny(String id, String by) {
        boolean won = jdbc.update("UPDATE run_approvals SET state = ?, answered_by = ?, answered_at = ?"
                        + " WHERE id = ? AND state = ?",
                RunApproval.DENIED, by, Timestamp.from(clock.get()), id, RunApproval.ASKED) == 1;
        if (won) {
            find(id).ifPresent(answered -> tell("approval " + id, () -> events.answered(answered)));
            tell("the settling of approval " + id, () -> settled.accept(id));
        }
        return won;
    }

    /** Revoke a standing {@code project} approval. {@code false} when there was none to revoke. */
    public boolean revoke(String id) {
        return jdbc.update("UPDATE run_approvals SET state = ? WHERE id = ? AND scope = ? AND state = ?",
                RunApproval.REVOKED, id, RunApproval.PROJECT, RunApproval.ALLOWED) == 1;
    }

    /**
     * The approval that lets this call run, consuming it if it is {@code once}, or empty.
     *
     * <p>Conversation-scoped approvals first, then project ones: the narrower answer is the one the
     * person gave about this conversation. A {@code once} approval that another call consumed first is
     * skipped, not reported, because the next candidate may still match.
     */
    public Optional<RunApproval> consume(long projectId, String conversation, String side,
            List<String> argv, String cwd) {
        return consume(projectId, conversation, side, argv, cwd, null);
    }

    /**
     * {@link #consume(long, String, String, List, String)} for a call given {@code stdin}: a
     * {@code once} or {@code conversation} approval covers only the input its question showed
     * ({@link RunApproval#givenSameInput}); a {@code project} approval covers its prefix whatever
     * the input, as it covers whatever arguments follow the prefix.
     *
     * @param stdin the call's input, or null for none
     */
    public Optional<RunApproval> consume(long projectId, String conversation, String side,
            List<String> argv, String cwd, String stdin) {
        String argvJson = json(argv);
        List<RunApproval> exact = jdbc.query(SELECT + " WHERE project_id = ? AND conversation = ?"
                        + " AND side = ? AND argv = ?::jsonb AND cwd = ? AND state = ? AND scope IN (?, ?)"
                        + " ORDER BY CASE scope WHEN 'conversation' THEN 0 ELSE 1 END, answered_at, id",
                ROW, projectId, conversation, side, argvJson, cwd, RunApproval.ALLOWED,
                RunApproval.CONVERSATION, RunApproval.ONCE);
        for (RunApproval candidate : exact) {
            if (!RunApproval.givenSameInput(candidate.reason(), stdin)) {
                continue;
            }
            if (RunApproval.CONVERSATION.equals(candidate.scope())) {
                return Optional.of(candidate);
            }
            if (jdbc.update("UPDATE run_approvals SET state = ? WHERE id = ? AND state = ?",
                    RunApproval.USED, candidate.id(), RunApproval.ALLOWED) == 1) {
                return Optional.of(candidate);
            }
        }
        for (RunApproval standing : jdbc.query(SELECT + " WHERE project_id = ? AND side = ? AND scope = ?"
                + " AND state = ? ORDER BY answered_at, id", ROW, projectId, side, RunApproval.PROJECT,
                RunApproval.ALLOWED)) {
            if (covers(standing.prefix(), argv)) {
                return Optional.of(standing);
            }
        }
        return Optional.empty();
    }

    /** Whether a prefix is a non-empty leading part of a command, compared whole argument by argument. */
    public static boolean covers(List<String> prefix, List<String> argv) {
        return prefix != null && !prefix.isEmpty() && prefix.size() <= argv.size()
                && argv.subList(0, prefix.size()).equals(prefix);
    }

    private static String json(List<String> strings) {
        try {
            return JSON.writeValueAsString(strings);
        } catch (JsonProcessingException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }

    private static String sets(List<List<String>> commands) {
        try {
            return JSON.writeValueAsString(commands);
        } catch (JsonProcessingException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }

    private static List<List<String>> setOf(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, SETS);
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("run_approvals.commands is not a list of commands: "
                    + json, corrupt);
        }
    }

    private static List<String> strings(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, STRINGS);
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("run_approvals holds a JSON value that is not a list of"
                    + " strings: " + json, corrupt);
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp at = row.getTimestamp(column);
        return at == null ? null : at.toInstant();
    }

    private static final RowMapper<RunApproval> ROW = (row, n) -> new RunApproval(
            row.getString("id"), row.getLong("project_id"), row.getString("conversation"),
            row.getString("asked_in"), row.getString("handle"), row.getString("agent"), row.getString("side"), strings(row.getString("argv")),
            row.getString("cwd"), row.getString("reason"), row.getString("state"),
            row.getString("scope"), strings(row.getString("prefix")), row.getString("answered_by"),
            instant(row, "answered_at"), instant(row, "delivered_at"), instant(row, "created_at"),
            setOf(row.getString("commands")), row.getString("judged"));
}
