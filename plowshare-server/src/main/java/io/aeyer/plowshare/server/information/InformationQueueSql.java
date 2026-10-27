package io.aeyer.plowshare.server.information;

/**
 * Repository-only eligibility predicates shared by queue admission and its read-only audit. The
 * fixed r/q/s aliases denote a revision allowance view, its resource, and its current stage. No
 * caller-controlled identifiers or values enter these expressions.
 */
public final class InformationQueueSql {
  private InformationQueueSql() {}

  /**
   * One statement-local set of SYSTEM processing facts. Callers include this CTE once before using
   * the eligibility predicates. Materialization prevents the planner from rescanning all eligible
   * revisions for every candidate; it does not cache authority between statements or claims.
   */
  public static String processingAuthority() {
    return "processing_authority AS MATERIALIZED ("
        + "SELECT revision FROM information_system_processing_revisions())";
  }

  public static String tagging() {
    return """
        r.processing_policy='automatic' AND r.availability='active' AND NOT r.excluded
        AND q.owner_handle IS NOT NULL AND jsonb_array_length(q.tags)=0
        AND jsonb_array_length(r.auto_tag)=0 AND NOT r.auto_tag_generated
        AND r.allowance_spent<r.allowance_total AND r.extracted_text IS NOT NULL AND btrim(r.extracted_text)<>''
        AND s.state IN ('skipped','ready')
        AND (SELECT count(*) FROM information_steps prior WHERE prior.revision_id=r.id
          AND prior.generation=r.generation AND prior.stage IN ('extract','derive') AND prior.state IN ('ready','skipped'))=2
        AND NOT EXISTS(SELECT 1 FROM information_acquisitions a WHERE a.revision_id=r.id AND a.state<>'succeeded')
        AND r.id IN (SELECT revision FROM processing_authority)
        """;
  }

  public static String grouping() {
    String tags = InformationFacetSql.visibleTags("r", "q");
    return "r.processing_policy='automatic' AND r.availability='active' AND NOT r.excluded"
        + " AND q.owner_handle IS NOT NULL AND NOT q.tag_groups_manual AND jsonb_array_length("
        + tags
        + ")>0"
        + " AND (s.state='skipped' OR NOT r.tag_groups_generated OR r.tag_groups_input_tags<>"
        + tags
        + ")"
        + " AND (r.allowance_spent<r.allowance_total OR (r.tag_groups_generated AND r.tag_groups_input_tags="
        + tags
        + "))"
        + " AND s.state IN ('skipped','ready')"
        + " AND NOT EXISTS(SELECT 1 FROM information_acquisitions a WHERE a.revision_id=r.id AND a.state<>'succeeded')"
        + " AND r.id IN (SELECT revision FROM processing_authority)";
  }
}
