package io.aeyer.plowshare.server.proc;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Content-free reads over existing durable owners. A cycle-safe recursive walk resolves the root's
 * archive state for every origin, including internal logs. Counts and pages share a repeatable-read
 * snapshot; a cursor is only a position and every page independently rechecks authority.
 */
public final class JdbcProcRepository implements ProcRepository {
  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate reads;

  // A targeted inspection resolves its owning root with indexed parent lookups, then walks only
  // that tree. Rootless/cyclic origins remain unavailable exactly as in the full inventory.
  private static final String TREE =
      """
      WITH RECURSIVE tree(id,root,lifecycle,ancestors) AS (
        SELECT id,id,lifecycle,ARRAY[]::text[] FROM conversations WHERE parent_id IS NULL
          AND (CAST(:root AS text) IS NULL OR id=:root)
          AND (CAST(:conversation AS text) IS NULL OR id=(
            WITH RECURSIVE parents(id,parent_id,visited) AS (
              SELECT id,parent_id,ARRAY[id] FROM conversations WHERE id=:conversation
              UNION ALL
              SELECT c.id,c.parent_id,p.visited||c.id FROM conversations c
                JOIN parents p ON c.id=p.parent_id WHERE NOT c.id=ANY(p.visited)
            ) SELECT id FROM parents WHERE parent_id IS NULL))
        UNION ALL
        SELECT c.id,t.root,t.lifecycle,t.ancestors || t.id
        FROM conversations c JOIN tree t ON c.parent_id=t.id
        WHERE NOT c.id=ANY(t.ancestors)
      )
      """;

  private static final String JOIN =
      " FROM tree t JOIN conversations c ON c.id=t.id LEFT JOIN projects p ON p.id=c.project_id ";

  // Mine preserves private conversation custody and current project access. Fleet is an explicit,
  // separately checked administrator metadata read, not permission to fetch content/trajectories.
  private static final String VISIBLE =
      """
      t.lifecycle <> 'archived'
      AND (:fleet OR (c.owner_handle=:account AND
        (c.project_id IS NULL OR EXISTS(SELECT 1 FROM project_members m
          WHERE m.project_id=c.project_id AND m.handle=:account))))
      AND (:project IS NULL OR p.name=:project)
      AND (:conversation IS NULL OR c.id=:conversation OR :conversation=ANY(t.ancestors))
      AND (:root IS NULL OR t.root=:root)
      AND (:causation IS NULL OR c.relay_causation->>'rootId'=:causation)
      """;

  // PostgreSQL needs explicit types for null-only named selectors in prepared statements.
  private static final String WHERE =
      VISIBLE
          .replace(":project IS NULL", "CAST(:project AS text) IS NULL")
          .replace(":conversation IS NULL", "CAST(:conversation AS text) IS NULL")
          .replace(":root IS NULL", "CAST(:root AS text) IS NULL")
          .replace(":causation IS NULL", "CAST(:causation AS text) IS NULL");

  private static final String ACTIVE =
      "(c.id IN (:live) OR EXISTS(SELECT 1 FROM inference_calls ic"
          + " WHERE ic.conversation_id=c.id AND ic.ended_at IS NULL) OR EXISTS("
          + "SELECT 1 FROM information_revisions ir JOIN information_steps s"
          + " ON s.revision_id=ir.id AND s.generation=ir.generation"
          + " WHERE ir.processing_log=c.id AND s.state IN ('pending','running','blocked')) OR EXISTS("
          + "SELECT 1 FROM jobs WHERE conversation_id=c.id AND ended_at IS NULL))";

  private static final List<String> COVERAGE =
      List.of(
          "All retained non-archived conversation origins; no implicit date cutoff.",
          "Database projections may lag runtime; missing terminal records remain unknown.",
          "Parent, root, opened-by-call and Relay causation are recorded links, not guessed origins.");

  public JdbcProcRepository(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
    this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(jdbc));
    reads = new TransactionTemplate(Objects.requireNonNull(transactions));
    reads.setReadOnly(true);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    reads.setTimeout(5);
  }

  private <T> T read(Supplier<T> action) {
    return Objects.requireNonNull(
        reads.execute(
            ignored -> {
              // These bounded metadata reads must not spend seconds compiling inlined eligibility
              // expressions. The production plan spent 2.9s in JIT after redundant scans were
              // removed.
              // This setting is transaction-local; neither pooled connections nor other workloads
              // inherit it.
              jdbc.getJdbcTemplate().execute("SET LOCAL jit=off");
              return action.get();
            }));
  }

  private MapSqlParameterSource parameters(String account, ProcQuery q) {
    // Repository boundary defends callers other than the socket service too.
    Objects.requireNonNull(q);
    if (account == null || account.isBlank())
      throw new CallerFault("PROC needs a signed-in account");
    var args =
        new MapSqlParameterSource("account", account)
            .addValue("fleet", q.fleet())
            .addValue("project", q.project())
            .addValue("conversation", q.conversation())
            .addValue("root", q.root())
            .addValue("causation", q.causation())
            .addValue("after", q.after())
            .addValue("limit", q.limit() + 1);
    var allowed =
        jdbc.queryForList(
            "SELECT 1 FROM admins WHERE handle=:account AND account_active(handle)"
                + " AND account_kind='USER' AND NOT bootstrap"
                + (q.fleet() ? " AND server_admin" : ""),
            args,
            Integer.class);
    if (allowed.isEmpty()) throw new CallerFault("PROC visibility is unavailable to this account");
    return args;
  }

  /**
   * Fix the authorized page before reading per-conversation aggregates. Without this
   * materialization PostgreSQL can enrich every retained conversation before sorting and limiting,
   * turning a small audit page into repeated full-table scans. Call facts are then loaded once for
   * that page and reused by totals, activity and repetition groups. The direct conversation index
   * keeps this selection independent of retained calls in unrelated conversations. Counts cover the
   * entire authorized selection. Materialize that narrow selection once for both count and page;
   * retain a count-only row when the cursor is beyond its end, without enriching off-page rows.
   */
  static String conversationPageSql(boolean active) {
    String filter = WHERE + (active ? " AND " + ACTIVE : "");
    return TREE
        + ", authorized AS MATERIALIZED (SELECT c.id,t.root AS audit_root,t.lifecycle AS audit_lifecycle,p.name AS audit_project"
        + JOIN
        + " WHERE "
        + filter
        + "), totals AS (SELECT count(*) AS audit_total FROM authorized)"
        + ", page_selection AS MATERIALIZED (SELECT * FROM authorized"
        + " WHERE CAST(:after AS text) IS NULL OR id>:after ORDER BY id LIMIT :limit)"
        + ", page AS MATERIALIZED (SELECT c.*,a.audit_root,a.audit_lifecycle,a.audit_project"
        + " FROM page_selection a JOIN conversations c ON c.id=a.id)"
        + """
          , page_calls AS MATERIALIZED (
            SELECT call.conversation_id,call.attempt_count,call.ended_at,call.attribution_status,
              call.account_handle,call.agent_name,call.operation,call.pool,call.wire_model
            FROM inference_calls call JOIN page selected ON selected.id=call.conversation_id
          ), call_totals AS MATERIALIZED (
            SELECT conversation_id,count(*) AS calls,sum(attempt_count) AS attempts,
              count(*) FILTER(WHERE ended_at IS NULL) AS active_calls,
              CASE WHEN bool_and(attribution_status='SYSTEM') THEN 'SYSTEM'
                WHEN bool_and(attribution_status='ATTRIBUTED') AND count(DISTINCT account_handle)=1 THEN max(account_handle)
                ELSE 'MIXED_OR_UNKNOWN' END AS principal
            FROM page_calls GROUP BY conversation_id
          ), call_repetitions AS MATERIALIZED (
            SELECT conversation_id,max(n) AS repeated FROM (
              SELECT conversation_id,agent_name,operation,pool,wire_model,count(*) AS n
              FROM page_calls GROUP BY conversation_id,agent_name,operation,pool,wire_model
            ) repetition GROUP BY conversation_id
          ), enriched AS (
          SELECT c.id,c.audit_root AS root,c.parent_id,c.origin,c.audit_lifecycle AS lifecycle,c.audit_project AS project,c.owner_handle,
            acting.application,acting.actor,acting.subject AS acting_subject,c.agent,c.opened_by_call,source.resource,source.revision,c.relay_causation->>'rootId' AS causation_root,
            c.relay_causation->>'parentId' AS causation_parent,
            CASE WHEN (c.relay_causation->>'depth')::integer>=0
              THEN (c.relay_causation->>'depth')::integer ELSE NULL END AS causation_depth,c.created_at,c.last_turn_at,
            CASE WHEN c.id IN (:live) THEN 'RUNNING'
              WHEN COALESCE(ps.running,0)>0 THEN 'PROCESSING'
              WHEN COALESCE(ps.pending,0)>0 THEN 'QUEUED'
              WHEN COALESCE(ps.blocked,0)>0 THEN 'BLOCKED'
              WHEN COALESCE(ic.active_calls,0)>0 THEN 'RECORDED_ACTIVE'
              WHEN j.id IS NOT NULL AND j.ended_at IS NULL THEN 'UNKNOWN'
              WHEN c.last_turn_at IS NOT NULL OR j.ended_at IS NOT NULL THEN 'COMPLETED' ELSE 'IDLE' END AS state,
            CASE WHEN c.id IN (:live) THEN 'local runtime snapshot'
              WHEN COALESCE(ps.running,0)>0 THEN 'unexpired processing lease'
              WHEN COALESCE(ps.pending,0)>0 THEN 'pending or expired processing lease'
              WHEN j.id IS NOT NULL AND j.ended_at IS NULL THEN 'no terminal job receipt; liveness unknown'
              ELSE 'retained database snapshot' END AS evidence,
            j.id AS latest_job,j.ending,
            (SELECT count(*) FROM conversations child WHERE child.parent_id=c.id) AS children,
            COALESCE(ic.calls,0) AS calls,COALESCE(ic.attempts,0) AS attempts,
            COALESCE(ic.active_calls,0) AS active_calls,COALESCE(repetition.repeated,0) AS repeated,
            COALESCE(ic.principal,'UNKNOWN') AS principal
          """
        + " FROM page c "
        + """
          LEFT JOIN acting_work_conversations acting ON acting.conversation=c.audit_root
          LEFT JOIN LATERAL(SELECT q.source_name AS resource,r.id::text AS revision
            FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
            WHERE r.processing_log=c.audit_root AND r.availability='active' AND NOT r.excluded
            ORDER BY r.created_at DESC,r.id DESC LIMIT 1) source ON true
          LEFT JOIN LATERAL(SELECT id,ending,ended_at FROM jobs WHERE conversation_id=c.id
            ORDER BY started_at DESC,id DESC LIMIT 1) j ON true
          LEFT JOIN LATERAL(SELECT count(*) FILTER(WHERE s.state='running' AND s.lease_until>now()) AS running,
            count(*) FILTER(WHERE s.state='pending' OR (s.state='running' AND (s.lease_until IS NULL OR s.lease_until<=now()))) AS pending,
            count(*) FILTER(WHERE s.state='blocked') AS blocked
            FROM information_revisions r JOIN information_steps s ON s.revision_id=r.id AND s.generation=r.generation
            WHERE r.processing_log=c.id AND r.availability='active' AND NOT r.excluded) ps ON true
          LEFT JOIN call_totals ic ON ic.conversation_id=c.id
          LEFT JOIN call_repetitions repetition ON repetition.conversation_id=c.id
          ) SELECT totals.audit_total,enriched.* FROM totals LEFT JOIN enriched ON true
          ORDER BY enriched.id
          """;
  }

  @Override
  public ProcViews.Conversations conversations(String account, ProcQuery q, Live live) {
    return read(
        () -> {
          var args =
              parameters(account, q)
                  .addValue(
                      "live",
                      live.runningConversations().isEmpty()
                          ? List.of("")
                          : live.runningConversations());
          String sql = conversationPageSql(Boolean.TRUE.equals(q.active()));
          return jdbc.query(
              sql,
              args,
              result -> {
                long total = 0;
                var rows = new ArrayList<ProcViews.Conversation>();
                while (result.next()) {
                  total = result.getLong("audit_total");
                  // The LEFT JOIN preserves the selection total on an empty cursor page.
                  if (result.getString("id") != null) rows.add(conversation(result));
                }
                boolean more = rows.size() > q.limit();
                var page = rows.stream().limit(q.limit()).toList();
                return new ProcViews.Conversations(
                    Instant.now(), q, page, total, more ? page.getLast().id() : null, COVERAGE);
              });
        });
  }

  private static ProcViews.Conversation conversation(ResultSet r) throws SQLException {
    return new ProcViews.Conversation(
        r.getString("id"),
        r.getString("root"),
        r.getString("parent_id"),
        r.getString("origin"),
        r.getString("lifecycle"),
        r.getString("project"),
        r.getString("owner_handle"),
        r.getString("application"),
        r.getString("actor"),
        r.getString("acting_subject"),
        r.getString("agent"),
        r.getString("principal"),
        r.getString("opened_by_call"),
        r.getString("resource"),
        r.getString("revision"),
        r.getString("causation_root"),
        r.getString("causation_parent"),
        r.getObject("causation_depth", Integer.class),
        instant(r, "created_at"),
        instant(r, "last_turn_at"),
        r.getString("state"),
        r.getString("evidence"),
        r.getString("latest_job"),
        r.getString("ending"),
        r.getLong("children"),
        r.getLong("calls"),
        r.getLong("attempts"),
        r.getLong("active_calls"),
        r.getLong("repeated"));
  }

  private static String revisionSelection() {
    return """
        SELECT r.id,r.resource_id,r.generation,r.processing_log,r.processing_policy,r.availability,r.excluded,
          r.auto_tag,r.auto_tag_generated,r.tag_groups_generated,r.tag_groups_input_tags,
          r.allowance_total,r.allowance_spent,
          q.tags,q.tag_groups_manual,q.source_name,q.owner_handle,q.project_id,p.name AS project,
          t.id AS audit_conversation
        FROM information_revision_allowances r JOIN information_resources q ON q.id=r.resource_id
        LEFT JOIN projects p ON p.id=q.project_id
        LEFT JOIN tree t ON t.id=r.processing_log
        LEFT JOIN conversations c ON c.id=t.id
        WHERE r.availability='active' AND NOT r.excluded
          AND (t.id IS NULL OR t.lifecycle<>'archived')
          AND (:fleet OR (q.owner_handle=:account AND (q.project_id IS NULL OR EXISTS(
            SELECT 1 FROM project_members m WHERE m.project_id=q.project_id AND m.handle=:account))))
          AND (CAST(:project AS text) IS NULL OR p.name=:project)
          AND (CAST(:conversation AS text) IS NULL OR t.id=:conversation OR :conversation=ANY(t.ancestors)
            OR EXISTS(SELECT 1 FROM tree selected WHERE selected.id=:conversation AND t.id=ANY(selected.ancestors)))
          AND (CAST(:root AS text) IS NULL OR t.root=:root)
          AND (CAST(:causation AS text) IS NULL OR c.relay_causation->>'rootId'=:causation)
        """;
  }

  /**
   * Produce summary and cursor page from one materialized counter set. The summary covers the
   * entire authorized selection, including when the cursor page is empty. Both eligibility branches
   * share one statement-local worker authority set, rather than inlining it per revision.
   */
  static String queueSql(boolean activeOnly) {
    String counters =
        TREE
            + ", "
            + io.aeyer.plowshare.server.information.InformationQueueSql.processingAuthority()
            + ", revisions AS MATERIALIZED ("
            + revisionSelection()
            + """
                    ), eligibility_candidates AS MATERIALIZED (
                      SELECT r.*,s.stage AS candidate_stage,s.state AS candidate_state,
                        CASE WHEN s.stage='autoTag' AND r.processing_policy='automatic'
                          AND r.owner_handle IS NOT NULL AND jsonb_array_length(r.tags)=0
                          AND jsonb_array_length(r.auto_tag)=0 AND NOT r.auto_tag_generated
                          AND r.allowance_spent<r.allowance_total THEN (
                            SELECT CASE WHEN source.extracted_text IS NOT NULL AND btrim(source.extracted_text)<>''
                              THEN 'present' END FROM information_revisions source WHERE source.id=r.id)
                          END AS extracted_text
                      FROM revisions r JOIN information_steps s
                        ON s.revision_id=r.id AND s.generation=r.generation
                      WHERE s.stage IN ('autoTag','tagGroups') AND s.state IN ('skipped','ready')
                    ), counters AS MATERIALIZED (
                    """
            + """
          SELECT r.id::text||':stages' AS id,'stages' AS unit,'processing' AS stage,
            r.project,r.owner_handle,r.processing_log,r.id::text AS revision,r.source_name AS resource,
            count(*) AS total,count(*) FILTER(WHERE s.state='ready') AS completed,
            count(*) FILTER(WHERE s.state='pending' OR (s.state='running' AND (s.lease_until IS NULL OR s.lease_until<=now()))) AS pending,
            count(*) FILTER(WHERE s.state='running' AND s.lease_until>now()) AS running,
            count(*) FILTER(WHERE s.state='blocked') AS blocked,
            count(*) FILTER(WHERE s.state IN ('failed','cancelled')) AS failed,
            count(*) FILTER(WHERE s.state='skipped') AS skipped,
            NULL::timestamptz AS oldest,
            max(s.lease_until) FILTER(WHERE s.state='running') AS lease_until,
            'Current generation; pending includes expired leases awaiting recovery; failed includes cancelled.' AS coverage
          FROM revisions r JOIN information_steps s ON s.revision_id=r.id AND s.generation=r.generation
          GROUP BY r.id,r.source_name,r.project,r.owner_handle,r.processing_log
          UNION ALL
          SELECT r.id::text||':paragraphs','paragraphs','summarise',r.project,r.owner_handle,
            r.processing_log,r.id::text,r.source_name,count(*),count(*) FILTER(WHERE p.summary IS NOT NULL),
            count(*) FILTER(WHERE p.summary IS NULL),NULL::bigint,0,0,0,
            NULL::timestamptz,NULL::timestamptz,
            'Committed paragraph checkpoints; pending includes an item in flight. Later hierarchy work is additional.'
          FROM revisions r JOIN paragraphs p ON p.document_id=r.id
          GROUP BY r.id,r.source_name,r.project,r.owner_handle,r.processing_log
          UNION ALL
          SELECT r.id::text||':chunks','chunks','embed',r.project,r.owner_handle,r.processing_log,
            r.id::text,r.source_name,count(*),count(*) FILTER(WHERE ch.embedding IS NOT NULL),
            count(*) FILTER(WHERE ch.embedding IS NULL),NULL::bigint,0,0,0,
            NULL::timestamptz,NULL::timestamptz,
            'Legacy chunk-vector checkpoints; pending includes an item in flight.'
          FROM revisions r JOIN paragraphs p ON p.document_id=r.id JOIN chunks ch ON ch.paragraph_id=p.id
          WHERE NOT EXISTS(SELECT 1 FROM embedding_slots WHERE target_space_id IS NOT NULL)
          GROUP BY r.id,r.source_name,r.project,r.owner_handle,r.processing_log
          UNION ALL
          SELECT r.id::text||':chunks:'||target.slot,'chunks','embed:'||target.slot,r.project,
            r.owner_handle,r.processing_log,r.id::text,r.source_name,count(*),count(*) FILTER(WHERE ready.value),
            count(*) FILTER(WHERE NOT ready.value),NULL::bigint,0,0,0,NULL::timestamptz,NULL::timestamptz,
            'Current configured slot target and source revision, including staged vectors. Pending includes work in flight.'
          FROM revisions r JOIN paragraphs p ON p.document_id=r.id JOIN chunks ch ON ch.paragraph_id=p.id
          CROSS JOIN embedding_slots target
          CROSS JOIN LATERAL(SELECT COALESCE(CASE target.slot WHEN 'code'
            THEN ch.code_embedding IS NOT NULL AND ch.code_space_id=target.target_space_id
              AND ch.code_source_revision=ch.embedding_source_revision
            ELSE ch.prose_embedding IS NOT NULL AND ch.prose_space_id=target.target_space_id
              AND ch.prose_source_revision=ch.embedding_source_revision END,false)
            OR EXISTS(SELECT 1 FROM chunks_embedding_staging staged WHERE staged.source_id=ch.id
              AND staged.slot=target.slot AND staged.space_id=target.target_space_id
              AND staged.source_revision=ch.embedding_source_revision) AS value) ready
          WHERE target.target_space_id IS NOT NULL
          GROUP BY r.id,r.source_name,r.project,r.owner_handle,r.processing_log,target.slot
          UNION ALL
          """
            + eligible(
                "autoTag", io.aeyer.plowshare.server.information.InformationQueueSql.tagging())
            + " UNION ALL "
            + eligible(
                "tagGroups", io.aeyer.plowshare.server.information.InformationQueueSql.grouping())
            + ") ";
    return counters
        + ", selected AS MATERIALIZED (SELECT * FROM counters"
        + (activeOnly ? " WHERE pending>0 OR running>0 OR blocked>0" : "")
        + ") SELECT * FROM ("
        + """
          SELECT true AS summary,'summary:'||unit||':'||stage AS id,unit,stage,NULL::text AS project,
            NULL::text AS owner_handle,NULL::text AS processing_log,NULL::text AS revision,NULL::text AS resource,
            sum(total)::bigint AS total,sum(completed)::bigint AS completed,sum(pending)::bigint AS pending,
            sum(running)::bigint AS running,sum(blocked)::bigint AS blocked,sum(failed)::bigint AS failed,sum(skipped)::bigint AS skipped,
            min(oldest) AS oldest,max(lease_until) AS lease_until,
            'Entire authorized selection; independent of page cursor. Unknown per-item running counts remain null.' AS coverage
          FROM selected GROUP BY unit,stage
          UNION ALL
          (SELECT false AS summary,* FROM selected
           WHERE CAST(:after AS text) IS NULL OR id>:after ORDER BY id LIMIT :limit)
          ) result ORDER BY summary DESC,id
          """;
  }

  private record QueueRow(boolean summary, ProcViews.Queue value) {}

  @Override
  public ProcViews.Queues queues(String account, ProcQuery q) {
    return read(
        () -> {
          var args = parameters(account, q);
          var result =
              jdbc.query(
                  queueSql(Boolean.TRUE.equals(q.active())),
                  args,
                  (r, n) -> new QueueRow(r.getBoolean("summary"), queue(r)));
          var summaries = result.stream().filter(QueueRow::summary).map(QueueRow::value).toList();
          var rows = result.stream().filter(row -> !row.summary()).map(QueueRow::value).toList();
          boolean more = rows.size() > q.limit();
          var page = rows.stream().limit(q.limit()).toList();
          return new ProcViews.Queues(
              Instant.now(),
              q,
              page,
              summaries,
              List.of(),
              more ? page.getLast().id() : null,
              List.of(
                  "Persisted current-generation processing stages and committed iteration checkpoints.",
                  "Stage counts, item counts and provider attempts are different units; do not add them.",
                  "Fleet pool occupancy is approximate local executor evidence; upstream queues remain unknown.",
                  "Queue admission timestamps are not retained for every stage; oldest waiting age remains unknown.",
                  "Sweep-eligible revisions are separate from enqueued stages; arbitrary script loop totals and upstream queues remain unknown."));
        });
  }

  /**
   * Materialize current authority once, and join it to attributed calls outside an OR predicate.
   * The former IN-subplan beneath that OR could rescan a materialized conversation selection for
   * every retained call when PostgreSQL estimated it would not fit a hashed subplan. Keep orphan
   * calls in a separate disjoint branch with their own current account/project authorization.
   */
  static String callsPageSql(boolean active) {
    String cursor =
        " AND (CAST(:after AS text) IS NULL OR ic.call_id::text>:after)"
            + (active ? " AND ic.ended_at IS NULL" : "");
    return TREE
        + ", selected AS MATERIALIZED (SELECT c.id"
        + JOIN
        + " WHERE "
        + WHERE
        + ")"
        + ", matched AS (SELECT ic.call_id FROM selected s JOIN inference_calls ic ON ic.conversation_id=s.id WHERE true"
        + cursor
        + " UNION ALL SELECT ic.call_id FROM inference_calls ic LEFT JOIN projects p ON p.id::text=ic.project_id"
        + " WHERE ic.conversation_id IS NULL AND CAST(:conversation AS text) IS NULL"
        + " AND CAST(:root AS text) IS NULL AND CAST(:causation AS text) IS NULL"
        + " AND (:fleet OR (ic.account_handle=:account AND (ic.project_id IS NULL OR EXISTS("
        + "SELECT 1 FROM project_members m WHERE m.project_id::text=ic.project_id AND m.handle=:account))))"
        + " AND (CAST(:project AS text) IS NULL OR p.name=:project)"
        + cursor
        + ")"
        + ", page AS MATERIALIZED (SELECT * FROM matched ORDER BY call_id LIMIT :limit)"
        + " SELECT ic.call_id,ic.conversation_id,ic.root_conversation_id,ic.run_id,ic.attribution_status,"
        + "ic.account_handle,ic.agent_name,ic.operation,ic.pool,ic.wire_model,ic.lane,ic.billing_route,"
        + "ic.instance_id,ic.lifecycle,ic.created_at,ic.ended_at,ic.attempt_count,"
        + "p.name AS project,c.owner_handle AS owner FROM page selected"
        + " JOIN inference_calls ic ON ic.call_id=selected.call_id"
        + " LEFT JOIN conversations c ON c.id=ic.conversation_id"
        + " LEFT JOIN projects p ON p.id::text=ic.project_id ORDER BY ic.call_id";
  }

  @Override
  public ProcViews.Calls calls(String account, ProcQuery q) {
    return read(
        () -> {
          var args = parameters(account, q);
          String sql = callsPageSql(Boolean.TRUE.equals(q.active()));
          var rows =
              jdbc.query(
                  sql,
                  args,
                  (r, n) ->
                      new ProcViews.Call(
                          r.getString("call_id"),
                          r.getString("conversation_id"),
                          r.getString("root_conversation_id"),
                          r.getString("run_id"),
                          r.getString("owner"),
                          r.getString("attribution_status").equals("SYSTEM")
                              ? "SYSTEM"
                              : r.getString("account_handle"),
                          r.getString("project"),
                          r.getString("agent_name"),
                          r.getString("operation"),
                          r.getString("pool"),
                          r.getString("wire_model"),
                          r.getString("lane"),
                          r.getString("billing_route"),
                          r.getString("instance_id"),
                          r.getString("ended_at") == null
                              ? "RECORDED_" + r.getString("lifecycle")
                              : r.getString("lifecycle"),
                          instant(r, "created_at"),
                          instant(r, "ended_at"),
                          r.getInt("attempt_count"),
                          List.of()));
          boolean more = rows.size() > q.limit();
          var selectedCalls = rows.stream().limit(q.limit()).toList();
          var observations = attemptsForCalls(selectedCalls);
          var page = new ArrayList<ProcViews.Call>();
          for (var call : selectedCalls) {
            var attempts = observations.getOrDefault(call.id(), List.of());
            page.add(
                new ProcViews.Call(
                    call.id(),
                    call.conversation(),
                    call.root(),
                    call.run(),
                    call.owner(),
                    call.principal(),
                    call.project(),
                    call.agent(),
                    call.operation(),
                    call.pool(),
                    call.model(),
                    call.lane(),
                    call.route(),
                    call.runtime(),
                    call.state(),
                    call.createdAt(),
                    call.endedAt(),
                    call.attempts(),
                    attempts));
          }
          return new ProcViews.Calls(
              Instant.now(),
              q,
              page,
              more ? page.getLast().id() : null,
              List.of(
                  "Durable accounting projection; recorded QUEUED/RUNNING is not an independent runtime heartbeat.",
                  "Each call includes its first 100 attempts; compare attempts with observations length for truncation.",
                  "Pool, model and billing route are captured destinations. Provider-internal queues/hosts are unknown.",
                  "No prompts, model outputs, tool arguments, credentials or provider error bodies are returned."));
        });
  }

  private record CallAttempt(String call, ProcViews.Attempt attempt) {}

  /** UUID equality preserves the existing call/attempt index and an independent cap per call. */
  private Map<String, List<ProcViews.Attempt>> attemptsForCalls(List<ProcViews.Call> calls) {
    var found = new LinkedHashMap<String, List<ProcViews.Attempt>>();
    for (int start = 0; start < calls.size(); start += 128) {
      var part = calls.subList(start, Math.min(start + 128, calls.size()));
      var parameters = new MapSqlParameterSource();
      var marks = new ArrayList<String>();
      for (int index = 0; index < part.size(); index++) {
        parameters.addValue("id" + index, UUID.fromString(part.get(index).id()));
        marks.add("(CAST(:id" + index + " AS uuid))");
      }
      var rows =
          jdbc.query(
              "SELECT chosen.call_id::text AS parent_call,attempt.* FROM (VALUES "
                  + String.join(",", marks)
                  + ") chosen(call_id) CROSS JOIN LATERAL (SELECT * FROM inference_attempts"
                  + " WHERE call_id=chosen.call_id ORDER BY attempt_number LIMIT 100) attempt ORDER BY chosen.call_id,attempt.attempt_number",
              parameters,
              (r, index) ->
                  new CallAttempt(
                      r.getString("parent_call"),
                      new ProcViews.Attempt(
                          r.getString("attempt_id"),
                          r.getInt("attempt_number"),
                          r.getString("outcome"),
                          instant(r, "started_at"),
                          instant(r, "ended_at"),
                          r.getObject("http_status", Integer.class),
                          r.getString("finish_reason"),
                          r.getString("input_tokens"),
                          r.getString("output_tokens"),
                          r.getString("cost_kind"),
                          r.getString("cost_amount"),
                          r.getString("cost_currency"))));
      for (var row : rows)
        found.computeIfAbsent(row.call(), ignored -> new ArrayList<>()).add(row.attempt());
    }
    return found;
  }

  @Override
  public List<ProcViews.Tool> tools(String account, ProcQuery q) {
    return read(
        () -> {
          var args = parameters(account, q);
          return jdbc.query(
              TREE
                  + """
          SELECT e.conversation_id||':'||e.ordinal::text||':'||(tool->>'id') AS id,e.conversation_id,e.job_id,
            tool->>'name' AS name,e.recorded_at,
            CASE WHEN EXISTS(SELECT 1 FROM entries result WHERE result.conversation_id=e.conversation_id
              AND result.tool_call_id=tool->>'id') THEN 'RESULT_RECORDED' ELSE 'UNKNOWN' END AS state
          """
                  + JOIN
                  + " JOIN entries e ON e.conversation_id=c.id CROSS JOIN LATERAL "
                  + "jsonb_array_elements(COALESCE(e.tool_calls,'[]'::jsonb)) tool WHERE "
                  + WHERE
                  + (Boolean.TRUE.equals(q.active())
                      ? " AND NOT EXISTS(SELECT 1 FROM entries result WHERE result.conversation_id=e.conversation_id AND result.tool_call_id=tool->>'id')"
                      : "")
                  + " AND (CAST(:after AS text) IS NULL OR e.conversation_id||':'||e.ordinal::text||':'||(tool->>'id')>:after)"
                  + " ORDER BY id LIMIT :limit",
              args,
              (r, n) ->
                  new ProcViews.Tool(
                      r.getString("id"),
                      r.getString("conversation_id"),
                      r.getString("job_id"),
                      r.getString("name"),
                      r.getString("state"),
                      instant(r, "recorded_at")));
        });
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    var time = row.getObject(column, OffsetDateTime.class);
    return time == null ? null : time.toInstant();
  }

  private static String eligible(String stage, String predicate) {
    if (!java.util.Set.of("autoTag", "tagGroups").contains(stage))
      throw new IllegalArgumentException("unknown audit stage");
    return "SELECT r.id::text||':eligible:"
        + stage
        + "','revisions','"
        + stage
        + ":eligible-not-enqueued',"
        + "r.project,r.owner_handle,r.processing_log,r.id::text,r.source_name,1,0,1,NULL::bigint,0,0,0,NULL::timestamptz,NULL::timestamptz,"
        + "'Eligible under the owning sweep predicate; not yet enqueued. The next sweep batch does not bound this count.' "
        + "FROM eligibility_candidates r WHERE r.candidate_stage='"
        + stage
        + "' AND "
        + predicate.replace("q.", "r.").replace("s.state", "r.candidate_state");
  }

  private static ProcViews.Queue queue(ResultSet r) throws SQLException {
    return new ProcViews.Queue(
        r.getString("id"),
        r.getString("unit"),
        r.getString("stage"),
        r.getString("project"),
        r.getString("owner_handle"),
        r.getString("processing_log"),
        r.getString("revision"),
        r.getString("resource"),
        r.getLong("total"),
        r.getLong("completed"),
        r.getLong("pending"),
        r.getObject("running", Long.class),
        r.getLong("blocked"),
        r.getLong("failed"),
        r.getLong("skipped"),
        instant(r, "oldest"),
        instant(r, "lease_until"),
        r.getString("coverage"));
  }
}
