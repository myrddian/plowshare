package io.aeyer.plowshare.server.union;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Which client-rooted projects are unions, what hidden paths they may sync, and
 * the conflicts a reconnecting client set aside. Spec §3.1 and §6.3.
 *
 * <h2>No {@code ArchiveUnavailableException} here</h2>
 *
 * <p>{@code ArchiveUnavailableException.translating} is package-private to
 * {@code archive} — {@link io.aeyer.plowshare.server.files.WorkspaceUnavailableException}'s
 * javadoc says so, and it does not compile from here, in another package. This
 * class is in the same position {@code RuntimeConfig} names for itself: it
 * raises Spring's {@code DataAccessException} as it comes, and a caller that
 * wants a translated fault wraps it at its own boundary, the way {@code
 * RuntimeConfigController} wraps {@code RuntimeConfig} in {@code
 * ConfigUnavailableException} rather than reusing the archive's.
 */
public final class UnionStore {

    public record Union(long projectId, String name, String machine, String workspace,
            Instant since, List<String> syncHidden, List<String> exclusions) {
        public boolean enabled() {
            return since != null;
        }
    }

    public record NewConflict(String path, String baseBlob, String oursBlob, String theirsBlob,
            String theirsAuthor, String runId) {}

    public record Conflict(int n, String path, String baseBlob, String oursBlob,
            String theirsBlob, String theirsAuthor, String runId, Instant openedAt) {}

    private static final String UNION_COLUMNS =
            "id, name, machine, workspace, union_since, sync_hidden, exclusions";

    private static final String CONFLICT_COLUMNS = "n, path, base_blob, ours_blob, theirs_blob,"
            + " theirs_author, run_id, opened_at";

    private final JdbcTemplate jdbc;

    public UnionStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * A client-rooted project by name, whether or not it is enabled as a union
     * yet. A server-rooted project ({@code machine IS NULL}) is never a
     * candidate, so this is empty for one regardless of its other columns.
     */
    public Optional<Union> find(String project) {
        return jdbc.query("SELECT " + UNION_COLUMNS + " FROM projects"
                        + " WHERE name = ? AND machine IS NOT NULL", UnionStore::union, project)
                .stream().findFirst();
    }

    public List<Union> enabled() {
        return jdbc.query("SELECT " + UNION_COLUMNS + " FROM projects"
                + " WHERE union_since IS NOT NULL ORDER BY name", UnionStore::union);
    }

    public void enable(String project, Instant at) {
        jdbc.update("UPDATE projects SET union_since = ? WHERE name = ?"
                + " AND union_since IS NULL", utc(at), project);
    }

    public void disable(String project) {
        jdbc.update("DELETE FROM union_conflicts WHERE project_id ="
                + " (SELECT id FROM projects WHERE name = ?)", project);
        jdbc.update("UPDATE projects SET union_since = NULL WHERE name = ?", project);
    }

    public void setHidden(String project, List<String> hidden) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "UPDATE projects SET sync_hidden = ? WHERE name = ?");
            // createArrayOf and not a hand-built '{...}' literal: ProjectStore's
            // own reason for exclusions -- a path may contain a comma, a brace,
            // a quote or a backslash, and the driver escapes it correctly.
            statement.setArray(1, connection.createArrayOf("text", hidden.toArray()));
            statement.setString(2, project);
            return statement;
        });
    }

    /**
     * Files a new conflict under the next number for this project, starting at
     * 1. The numbering is computed and inserted in one statement so two
     * conflicts opened for the same project at once cannot both claim the same
     * {@code n}; the rare loser of that race retries rather than fails.
     *
     * @return the assigned {@code n}
     */
    public int openConflict(long projectId, NewConflict conflict, Instant at) {
        for (int attempt = 0; ; attempt++) {
            try {
                Integer n = jdbc.queryForObject("INSERT INTO union_conflicts (project_id, n, path,"
                        + " base_blob, ours_blob, theirs_blob, theirs_author, run_id, opened_at)"
                        + " SELECT ?, COALESCE(MAX(n), 0) + 1, ?, ?, ?, ?, ?, ?, ?"
                        + " FROM union_conflicts WHERE project_id = ? RETURNING n",
                        Integer.class, projectId, conflict.path(), conflict.baseBlob(),
                        conflict.oursBlob(), conflict.theirsBlob(), conflict.theirsAuthor(),
                        conflict.runId(), utc(at), projectId);
                return Objects.requireNonNull(n, "n");
            } catch (DuplicateKeyException raced) {
                if (attempt >= 3) {
                    throw raced;
                }
            }
        }
    }

    public List<Conflict> open(long projectId) {
        return jdbc.query("SELECT " + CONFLICT_COLUMNS + " FROM union_conflicts"
                + " WHERE project_id = ? AND resolved_at IS NULL ORDER BY n",
                UnionStore::conflict, projectId);
    }

    /**
     * @return whether this call was the one that resolved it. An already
     *     resolved conflict stays resolved and this answers false, rather than
     *     overwriting an earlier resolution with a later, possibly different one.
     */
    public boolean resolve(long projectId, int n, String resolution, Instant at) {
        int changed = jdbc.update("UPDATE union_conflicts SET resolution = ?, resolved_at = ?"
                + " WHERE project_id = ? AND n = ? AND resolved_at IS NULL",
                resolution, utc(at), projectId, n);
        return changed == 1;
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private static Union union(ResultSet rs, int row) throws SQLException {
        OffsetDateTime since = rs.getObject("union_since", OffsetDateTime.class);
        return new Union(rs.getLong("id"), rs.getString("name"), rs.getString("machine"),
                rs.getString("workspace"), since == null ? null : since.toInstant(),
                strings(rs.getArray("sync_hidden")), strings(rs.getArray("exclusions")));
    }

    private static Conflict conflict(ResultSet rs, int row) throws SQLException {
        return new Conflict(rs.getInt("n"), rs.getString("path"), rs.getString("base_blob"),
                rs.getString("ours_blob"), rs.getString("theirs_blob"),
                rs.getString("theirs_author"), rs.getString("run_id"),
                rs.getObject("opened_at", OffsetDateTime.class).toInstant());
    }

    /*
     * No null branch on the array. sync_hidden and exclusions are both NOT NULL
     * DEFAULT '{}', so there is no absent list to decode for either -- the shape
     * ProjectStore.paths takes for the same reason.
     */
    private static List<String> strings(Array column) throws SQLException {
        return List.of((String[]) column.getArray());
    }
}
