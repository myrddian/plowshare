package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Accounts allowed to root a project and reach its files. */
@Component
public class ProjectMembers {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ProjectMembers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "dataSource")));
    }

    public boolean isMember(String project, String handle) {
        if (handle == null) {
            return false;
        }
        return ArchiveUnavailableException.translating("read project membership", () ->
                !jdbc.queryForList("SELECT 1 FROM project_members m JOIN projects p"
                        + " ON p.id = m.project_id WHERE p.name = ? AND m.handle = ?",
                        Integer.class, project, handle).isEmpty());
    }

    public List<String> members(String project) {
        return ArchiveUnavailableException.translating("list project members", () ->
                jdbc.queryForList("SELECT m.handle FROM project_members m JOIN projects p"
                        + " ON p.id = m.project_id WHERE p.name = ? ORDER BY m.handle",
                        String.class, project));
    }

    public void add(String project, String handle) {
        ArchiveUnavailableException.translating("add a project member", () -> transactions.execute(status -> {
            if (jdbc.queryForList("SELECT 1 FROM admins WHERE handle = ?", Integer.class, handle)
                    .isEmpty()) {
                throw new ArchiveRefusedException("no account named '" + handle + "'");
            }
            Long id = ProjectIds.toRead(jdbc, Home.of(project));
            if (id == ProjectIds.NONE) {
                throw new ArchiveRefusedException("no project named '" + project + "'");
            }
            lock(id);
            jdbc.update("INSERT INTO project_members (project_id, handle) VALUES (?, ?)"
                    + " ON CONFLICT DO NOTHING", id, handle);
            return null;
        }));
    }

    public void remove(String project, String handle) {
        ArchiveUnavailableException.translating("remove a project member", () ->
                transactions.execute(status -> {
                    Long id = ProjectIds.toRead(jdbc, Home.of(project));
                    if (id == ProjectIds.NONE) {
                        throw new ArchiveRefusedException("no project named '" + project + "'");
                    }
                    lock(id);
                    return jdbc.update("DELETE FROM project_members WHERE project_id = ? AND handle = ?",
                            id, handle);
                }));
    }

    /** Lock the project row so concurrent first claims cannot both become members. */
    public boolean mayUse(String project, String handle) {
        if (handle == null) {
            return false;
        }
        if (isMember(project, handle)) {
            return true;
        }
        return ArchiveUnavailableException.translating("claim project membership", () ->
                transactions.execute(status -> {
                    Long id = ProjectIds.toWrite(jdbc, Home.of(project));
                    lock(id);
                    List<String> members = jdbc.queryForList(
                            "SELECT handle FROM project_members WHERE project_id = ?", String.class, id);
                    if (!members.isEmpty()) {
                        return members.contains(handle);
                    }
                    jdbc.update("INSERT INTO project_members (project_id, handle) VALUES (?, ?)", id, handle);
                    return true;
                }));
    }

    private void lock(Long id) {
        jdbc.queryForObject("SELECT id FROM projects WHERE id = ? FOR UPDATE", Long.class, id);
    }
}
