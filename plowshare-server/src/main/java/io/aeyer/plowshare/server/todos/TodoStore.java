package io.aeyer.plowshare.server.todos;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** A conversation's todo rows. Every read and write names the conversation. No rules here. */
public class TodoStore {

    public static final String PREFIX = "td_";

    private static final String COLUMNS =
            "id, conversation, parent, position, text, status, summary, locked, stage_id, updated_at";

    private final JdbcTemplate jdbc;

    public TodoStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TodoItem> list(String conversation) {
        return jdbc.query("SELECT " + COLUMNS + " FROM todos WHERE conversation = ?"
                + " ORDER BY parent NULLS FIRST, position, id", TodoStore::row, conversation);
    }

    public void insert(TodoItem item) {
        jdbc.update("INSERT INTO todos (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                item.id(), item.conversation(), item.parent(), item.position(), item.text(),
                item.status().wire(), item.summary(), item.locked(), item.stageId(),
                Timestamp.from(item.updatedAt()));
    }

    /**
     * Writes {@code next} over the row {@code previous} was read from, guarded by {@code
     * previous}' own {@code updated_at}: the row count tells the caller whether that guard held.
     *
     * @return how many rows matched and were written -- 0 means the row moved on since {@code
     *     previous} was read, and nothing here was changed
     */
    public int update(TodoItem previous, TodoItem next) {
        return jdbc.update("UPDATE todos SET position = ?, text = ?, status = ?, summary = ?,"
                + " updated_at = ? WHERE id = ? AND conversation = ? AND updated_at = ?",
                next.position(), next.text(), next.status().wire(), next.summary(),
                Timestamp.from(next.updatedAt()), next.id(), next.conversation(),
                Timestamp.from(previous.updatedAt()));
    }

    private static TodoItem row(ResultSet rs, int n) throws SQLException {
        return new TodoItem(rs.getString("id"), rs.getString("conversation"), rs.getString("parent"),
                rs.getInt("position"), rs.getString("text"),
                TodoStatus.fromWire(rs.getString("status")).orElseThrow(),
                rs.getString("summary"), rs.getBoolean("locked"), rs.getString("stage_id"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
