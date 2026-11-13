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
  private final InformationLogVisibility visibility;

  public JdbcJobInformationRepository(JdbcTemplate jdbc) {
    this(jdbc, InformationLogVisibility.direct());
  }

  /**
   * Uses {@code visibility} for targeted log checks, so job listings and per-turn rechecks reuse
   * the account's current deny set instead of re-walking ancestry and retained inputs per call.
   * Component scanning constructs this class, so Spring must be told which constructor to use.
   */
  @org.springframework.beans.factory.annotation.Autowired
  public JdbcJobInformationRepository(JdbcTemplate jdbc, InformationLogVisibility visibility) {
    this.jdbc = jdbc;
    this.visibility = java.util.Objects.requireNonNull(visibility);
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

  /**
   * Writes this log's input selections, owned by the context's <em>attribution</em> axis.
   *
   * <p>{@code information_job_inputs.owner_handle} is {@code NOT NULL REFERENCES admins(handle)}
   * (V83) and records attribution independently of the reader's current ACL/RBAC grants. A
   * contained principal has no {@code admins} row by design (V218), so the acting axis cannot be
   * stored here at all: a contained run binding an input would violate the foreign key before any
   * authority question was reached. The log genuinely belongs to the container — that is the handle
   * {@code ConversationStore} wrote as its owner — so attribution is both the only axis this column
   * can hold and the correct one.
   *
   * <p>This is a change of which axis is read, not of what the column means and not of any input
   * attribution. Every uncontained caller's two axes hold one value, so the stored row is
   * byte-identical to the one this statement wrote before.
   */
  public void bind(String job, InformationContext context, List<UUID> revisions) {
    revisions = List.copyOf(revisions);
    // Bound parameter/JSON allocation while traversing the ancestor relation once per cohort.
    for (int start = 0; start < revisions.size(); start += 256) {
      var selected = revisions.subList(start, Math.min(start + 256, revisions.size()));
      jdbc.update(
          "WITH RECURSIVE edges(child,parent) AS (SELECT id,parent_id FROM conversations WHERE parent_id IS NOT NULL"
              + " UNION SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL"
              + " UNION SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent"
              + " UNION SELECT conductor_conversation,id FROM orchestrations), parents(id) AS (SELECT ?::text UNION SELECT e.parent FROM edges e JOIN parents p ON e.child=p.id)"
              + ", selected(revision_id) AS (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb))"
              + " INSERT INTO information_job_inputs SELECT p.id,?,s.revision_id,?,?,? FROM parents p CROSS JOIN selected s ON CONFLICT DO NOTHING",
          job,
          InformationJson.json(selected),
          context.attribution(),
          context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),
          context.selection().project(),
          context.selection().includeGlobal());
    }
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
    return visibility.readable(
        account,
        log,
        () ->
            jdbc.queryForObject(
                "SELECT information_log_readable(?,?)", Boolean.class, log, account));
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
        "SELECT DISTINCT revision_id,scope,project_name,include_shared FROM information_job_inputs WHERE job_id=?",
        (row, n) ->
            new Input(
                row.getObject(1, UUID.class),
                new InformationContext.Selection(
                    InformationContext.Scope.valueOf(row.getString(2).toUpperCase(Locale.ROOT)),
                    row.getString(3),
                    row.getBoolean(4))),
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
            selection.includeGlobal()));
  }
}
