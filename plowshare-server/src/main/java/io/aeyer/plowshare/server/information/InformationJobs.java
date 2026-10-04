package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Input dependencies are durable before execution; polling rechecks the original selection. */
public final class InformationJobs {
  private final JdbcTemplate jdbc;
  private final InformationAccess access;
  private InformationCatalogue catalogue;

  public InformationJobs(JdbcTemplate jdbc, InformationAccess access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  public InformationJobs(
      JdbcTemplate jdbc, InformationAccess access, InformationCatalogue catalogue) {
    this(jdbc, access);
    this.catalogue = catalogue;
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

  public List<UUID> inputsOf(String log, String account) {
    requireLog(log, account);
    return jdbc.queryForList(
        "WITH RECURSIVE edges(child,parent) AS (SELECT id,parent_id FROM conversations WHERE parent_id IS NOT NULL UNION SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL UNION SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent UNION SELECT conductor_conversation,id FROM orchestrations), parents(id) AS (SELECT ?::text UNION SELECT e.parent FROM edges e JOIN parents p ON e.child=p.id) SELECT DISTINCT i.revision_id FROM information_job_inputs i JOIN parents p ON p.id=i.job_id ORDER BY i.revision_id",
        UUID.class,
        log);
  }

  /** A message carries its source log's exact input selections into the receiving log. */
  public void inherit(String source, String target, String account) {
    requireLog(source, account);
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
    requireLog(target, account);
  }

  public boolean allowed(String job, String account) {
    List<Map<String, Object>> inputs =
        jdbc.queryForList("SELECT * FROM information_job_inputs WHERE job_id=?", job);
    if (inputs.isEmpty())
      return jdbc.queryForObject(
          "SELECT information_log_readable(?,?)", Boolean.class, job, account);
    if (account == null || !account.equals(inputs.getFirst().get("owner_handle"))) return false;
    for (Map<String, Object> input : inputs) {
      var selection =
          new InformationContext.Selection(
              InformationContext.Scope.valueOf(
                  ((String) input.get("scope")).toUpperCase(java.util.Locale.ROOT)),
              (String) input.get("project_name"),
              (Boolean) input.get("include_shared"));
      try {
        var context = access.resolve(account, selection);
        if (catalogue != null) {
          catalogue.requireReadable(context, (UUID) input.get("revision_id"));
          continue;
        }
      } catch (RuntimeException denied) {
        return false;
      }
      if (!jdbc.queryForObject(
          "SELECT information_readable(?,?,?,?,?)",
          Boolean.class,
          input.get("revision_id"),
          account,
          input.get("scope"),
          input.get("project_name"),
          input.get("include_shared"))) return false;
    }
    return true;
  }

  public boolean logAllowed(String log, String account) {
    return jdbc.queryForObject("SELECT information_log_readable(?,?)", Boolean.class, log, account);
  }

  public void requireLog(String log, String account) {
    if (log != null && !logAllowed(log, account))
      throw new NotFoundFault("log inputs are unavailable to this account");
  }

  public java.util.function.Consumer<UUID> reads(String log, InformationContext context) {
    return revision -> {
      if (log == null) throw new IllegalStateException("a corpus-reading run needs a durable log");
      bind(log, context, List.of(revision));
      requireLog(log, context.account());
    };
  }

  public void require(String job, String account) {
    if (!allowed(job, account)) throw new NotFoundFault("job is unavailable to this account");
  }
}
