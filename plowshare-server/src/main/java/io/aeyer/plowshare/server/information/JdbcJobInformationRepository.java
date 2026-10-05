package io.aeyer.plowshare.server.information;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns recursive durable input propagation and typed reconstruction of stored selections. */
@Repository
public class JdbcJobInformationRepository implements JobInformationRepository {
  private final JdbcTemplate jdbc;

  public JdbcJobInformationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean hasInputs(String log) {
    if (log == null) return false;
    return jdbc.queryForObject(
        "WITH RECURSIVE edges(child,parent) AS (SELECT id,parent_id FROM conversations WHERE parent_id IS NOT NULL"
            + " UNION SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL"
            + " UNION SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent"
            + " UNION SELECT conductor_conversation,id FROM orchestrations), parents(id) AS (SELECT ?::text UNION SELECT e.parent FROM edges e JOIN parents p ON e.child=p.id) SELECT EXISTS(SELECT 1 FROM parents p JOIN information_job_inputs i ON i.job_id=p.id)",
        Boolean.class,
        log);
  }

  public void bind(String job, InformationContext context, List<UUID> revisions) {
    for (UUID revision : revisions)
      jdbc.update(
          "WITH RECURSIVE edges(child,parent) AS (SELECT id,parent_id FROM conversations WHERE parent_id IS NOT NULL"
              + " UNION SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL"
              + " UNION SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent"
              + " UNION SELECT conductor_conversation,id FROM orchestrations), parents(id) AS (SELECT ?::text UNION SELECT e.parent FROM edges e JOIN parents p ON e.child=p.id)"
              + " INSERT INTO information_job_inputs SELECT id,?,?,?,?,? FROM parents ON CONFLICT DO NOTHING",
          job,
          context.account(),
          revision,
          context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),
          context.selection().project(),
          context.selection().includeShared());
  }

  public void inherit(String source, String target) {
    jdbc.update(
        "WITH RECURSIVE edges(child,parent) AS (SELECT id,parent_id FROM conversations WHERE parent_id IS NOT NULL"
            + " UNION SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL"
            + " UNION SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent"
            + " UNION SELECT conductor_conversation,id FROM orchestrations), parents(id) AS"
            + " (SELECT ?::text UNION SELECT e.parent FROM edges e JOIN parents p ON e.child=p.id)"
            + " INSERT INTO information_job_inputs(job_id,owner_handle,revision_id,scope,project_name,include_shared)"
            + " SELECT DISTINCT ?,i.owner_handle,i.revision_id,i.scope,i.project_name,i.include_shared"
            + " FROM information_job_inputs i JOIN parents p ON p.id=i.job_id ON CONFLICT DO NOTHING",
        source,
        target);
  }

  public boolean logAllowed(String log, String account) {
    return jdbc.queryForObject("SELECT information_log_readable(?,?)", Boolean.class, log, account);
  }

  public List<UUID> inputsOf(String log) {
    return jdbc.queryForList(
        "WITH RECURSIVE edges(child,parent) AS (SELECT id,parent_id FROM conversations WHERE parent_id IS NOT NULL UNION SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL UNION SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent UNION SELECT conductor_conversation,id FROM orchestrations), parents(id) AS (SELECT ?::text UNION SELECT e.parent FROM edges e JOIN parents p ON e.child=p.id) SELECT DISTINCT i.revision_id FROM information_job_inputs i JOIN parents p ON p.id=i.job_id ORDER BY i.revision_id",
        UUID.class,
        log);
  }

  @Override
  public List<Input> inputs(String job) {
    return jdbc.query(
        "SELECT owner_handle,revision_id,scope,project_name,include_shared FROM information_job_inputs WHERE job_id=?",
        (row, n) ->
            new Input(
                row.getString(1),
                row.getObject(2, UUID.class),
                new InformationContext.Selection(
                    InformationContext.Scope.valueOf(row.getString(3).toUpperCase(Locale.ROOT)),
                    row.getString(4),
                    row.getBoolean(5))),
        job);
  }

  @Override
  public boolean readable(UUID revision, String account, InformationContext.Selection selection) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT information_readable(?,?,?,?,?)",
            Boolean.class,
            revision,
            account,
            selection.scope().name().toLowerCase(Locale.ROOT),
            selection.project(),
            selection.includeShared()));
  }
}
