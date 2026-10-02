package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The triggers table. A trigger is owned by the account that defined it: it directs a run's
 * result into that account's user-inbox, so letting another account replace, pause or forget it
 * would be writing across accounts. Listing stays open to every account.
 */
public class TriggerStore {

    private static final String COLUMNS = "name, event, project, conversation, agent, task,"
            + " max_model_calls, max_turns, queue_cap, paused, defined_by";

    private final JdbcTemplate jdbc;

    public TriggerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Defines {@code t}, or replaces the trigger of that name if {@code t.definedBy()} defined
     * it. The ownership test is in the upsert's own {@code WHERE}, not a read before it, so two
     * accounts racing for one name cannot both win; a conflict it declines returns no row.
     */
    public TriggerRecord define(TriggerRecord t) {
        List<TriggerRecord> stored = jdbc.query("""
                INSERT INTO triggers (name, event, project, conversation, agent, task,
                    max_model_calls, max_turns, queue_cap, paused, defined_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET event = EXCLUDED.event,
                    project = EXCLUDED.project, conversation = EXCLUDED.conversation,
                    agent = EXCLUDED.agent, task = EXCLUDED.task,
                    max_model_calls = EXCLUDED.max_model_calls, max_turns = EXCLUDED.max_turns,
                    queue_cap = EXCLUDED.queue_cap, paused = EXCLUDED.paused,
                    defined_at = now()
                WHERE triggers.defined_by = EXCLUDED.defined_by
                RETURNING
                """ + COLUMNS,
                TriggerStore::row,
                t.name(), t.event(), t.project(), t.conversation(), t.agent(), t.task(),
                t.maxModelCalls(), t.maxTurns(), t.queueCap(), t.paused(), t.definedBy());
        if (stored.isEmpty()) {
            throw new CallerFault("a trigger named " + t.name() + " belongs to another account;"
                    + " choose another name. Nothing was defined.");
        }
        return stored.get(0);
    }

    public List<TriggerRecord> list() {
        return jdbc.query("SELECT " + COLUMNS + " FROM triggers ORDER BY name", TriggerStore::row);
    }

    public Optional<TriggerRecord> find(String name) {
        return jdbc.query("SELECT " + COLUMNS + " FROM triggers WHERE name = ?",
                TriggerStore::row, name).stream().findFirst();
    }

    public List<TriggerRecord> listening(String event) {
        return jdbc.query("SELECT " + COLUMNS + " FROM triggers WHERE event = ? AND NOT paused"
                + " ORDER BY name", TriggerStore::row, event);
    }

    /** Scoped by {@code handle}: another account's trigger answers exactly as no trigger does. */
    public void pause(String name, boolean paused, String handle) {
        if (jdbc.update("UPDATE triggers SET paused = ? WHERE name = ? AND defined_by = ?",
                paused, name, handle) == 0) {
            throw notYours(name);
        }
    }

    /** Scoped by {@code handle}: another account's trigger answers exactly as no trigger does. */
    public void forget(String name, String handle) {
        if (jdbc.update("DELETE FROM triggers WHERE name = ? AND defined_by = ?", name, handle) == 0) {
            throw notYours(name);
        }
    }

    private static ArchiveException notYours(String name) {
        return new ArchiveException("no trigger named " + name + " is yours");
    }

    private static TriggerRecord row(ResultSet rs, int n) throws SQLException {
        return new TriggerRecord(rs.getString("name"), rs.getString("event"),
                rs.getString("project"), rs.getString("conversation"), rs.getString("agent"),
                rs.getString("task"), (Integer) rs.getObject("max_model_calls"),
                (Integer) rs.getObject("max_turns"), rs.getInt("queue_cap"),
                rs.getBoolean("paused"), rs.getString("defined_by"));
    }
}
