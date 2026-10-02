package io.aeyer.plowshare.server.todos;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * What a conversation's todo notice last said, so {@link TodoBoard#noticeFor} can tell whether
 * anything changed since. Backed by {@code todo_notices}; {@code V47__todo_notices.sql} says why.
 */
public interface TodoNotices {

    /** A hash of the rendered list, and the compaction ordinal the notice was produced against. */
    record Seen(String listHash, int compactedThrough) {}

    /** Remembers nothing, so {@link #seen} is always empty and the notice is sent every time --
     *  slice 1's behaviour, for a caller still on {@link TodoBoard}'s five-argument constructor. */
    TodoNotices NONE = new TodoNotices() {
        @Override
        public Optional<Seen> seen(String conversation) {
            return Optional.empty();
        }

        @Override
        public void remember(String conversation, Seen seen) {
            // Nothing to remember.
        }

        @Override
        public void forget(String conversation) {
            // Nothing to forget.
        }
    };

    Optional<Seen> seen(String conversation);

    void remember(String conversation, Seen seen);

    /** The next {@link TodoBoard#noticeFor} produces a notice again, whether or not one is remembered. */
    void forget(String conversation);

    /** {@code todo_notices}, one row per conversation, upserted on every {@link #remember}. */
    final class Jdbc implements TodoNotices {

        private static final RowMapper<Seen> ROW_MAPPER = (rs, rowNum) ->
                new Seen(rs.getString("list_hash"), rs.getInt("compacted_through"));

        private final JdbcTemplate jdbc;

        public Jdbc(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public Optional<Seen> seen(String conversation) {
            return jdbc.query("SELECT list_hash, compacted_through FROM todo_notices"
                    + " WHERE conversation = ?", ROW_MAPPER, conversation).stream().findFirst();
        }

        @Override
        public void remember(String conversation, Seen seen) {
            jdbc.update("INSERT INTO todo_notices (conversation, list_hash, compacted_through)"
                    + " VALUES (?, ?, ?)"
                    + " ON CONFLICT (conversation) DO UPDATE SET list_hash = EXCLUDED.list_hash,"
                    + " compacted_through = EXCLUDED.compacted_through, noticed_at = now()",
                    conversation, seen.listHash(), seen.compactedThrough());
        }

        @Override
        public void forget(String conversation) {
            jdbc.update("DELETE FROM todo_notices WHERE conversation = ?", conversation);
        }
    }
}
