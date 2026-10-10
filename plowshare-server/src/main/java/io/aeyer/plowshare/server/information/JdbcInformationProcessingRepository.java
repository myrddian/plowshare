package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.auth.ServiceCredentials;
import io.aeyer.plowshare.server.documents.Extracted;
import io.aeyer.plowshare.server.information.InformationLifecycle.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns leases and committed checkpoints. Callers keep a fence and its write in one transaction. */
public final class JdbcInformationProcessingRepository implements InformationProcessingRepository {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final ProjectMembers members;

  // Token principals retain ownership; their owner's membership and credential ceiling determine
  // authority. Ordinary processing retains the existing explicit-membership requirement.
  private static final String OWNER_AUTHORIZED =
      "account_active(q.owner_handle) AND ((q.project_id IS NULL AND q.owner_handle NOT LIKE '@service/%')"
          + " OR (q.project_id IS NOT NULL AND ((q.owner_handle LIKE '@service/%' AND"
          + " service_project_role((SELECT name FROM projects WHERE id=q.project_id),q.owner_handle) IS NOT NULL)"
          + " OR (q.owner_handle NOT LIKE '@service/%' AND EXISTS(SELECT 1 FROM project_members m"
          + " WHERE m.project_id=q.project_id AND m.handle=q.owner_handle)))))";

  private record Authority(String owner, String project) {}

  private boolean authorized(Authority authority) {
    return authority.project() == null || members.mayUse(authority.project(), authority.owner());
  }

  private Authority authority(Lease lease) {
    return new Authority(lease.owner(), projectName(lease.project()));
  }

  public JdbcInformationProcessingRepository(
      JdbcTemplate jdbc, Clock clock, ProjectMembers members) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.clock = Objects.requireNonNull(clock);
    this.members = Objects.requireNonNull(members);
  }

  private OffsetDateTime now() {
    return clock.instant().atOffset(ZoneOffset.UTC);
  }

  private OffsetDateTime expires() {
    return clock.instant().plusSeconds(300).atOffset(ZoneOffset.UTC);
  }

  private record SweepRow(Queued queued, Authority authority) {}

  public List<Queued> sweepUntagged() {

    var candidates =
        sweepCandidates(
            "SELECT r.id,r.generation,q.owner_handle,(SELECT name FROM projects WHERE id=q.project_id) AS project_name FROM information_revisions r"
                + " JOIN information_resources q ON q.id=r.resource_id"
                + " JOIN information_steps s ON s.revision_id=r.id AND s.generation=r.generation AND s.stage='autoTag'"
                + " WHERE r.availability='active' AND NOT r.excluded AND q.owner_handle IS NOT NULL"
                + " AND jsonb_array_length(q.tags)=0 AND jsonb_array_length(r.auto_tag)=0 AND NOT r.auto_tag_generated"
                + " AND r.allowance_spent<r.allowance_total AND r.extracted_text IS NOT NULL AND btrim(r.extracted_text)<>''"
                + " AND s.state IN ('skipped','ready')"
                + " AND (SELECT count(*) FROM information_steps prior WHERE prior.revision_id=r.id AND prior.generation=r.generation"
                + " AND prior.stage IN ('extract','derive') AND prior.state IN ('ready','skipped'))=2"
                + " AND NOT EXISTS(SELECT 1 FROM information_acquisitions a WHERE a.revision_id=r.id AND a.state<>'succeeded')"
                + " AND "
                + OWNER_AUTHORIZED
                + " AND ((q.namespace<>'legacy' AND NOT EXISTS(SELECT 1 FROM documents d WHERE d.id=r.id))"
                + " OR information_readable(r.id,q.owner_handle,CASE WHEN q.project_id IS NULL THEN 'personal' ELSE 'project' END,"
                + " (SELECT p.name FROM projects p WHERE p.id=q.project_id),q.owner_handle NOT LIKE '@service/%'))"
                + " AND NOT EXISTS(SELECT 1 FROM information_inputs i WHERE i.derived_revision=r.id AND NOT information_readable(i.input_revision,"
                + " q.owner_handle,CASE WHEN q.project_id IS NULL THEN 'personal' ELSE 'project' END,(SELECT p.name FROM projects p WHERE p.id=q.project_id),q.owner_handle NOT LIKE '@service/%'))");
    for (var candidate : candidates) {
      jdbc.update(
          "UPDATE information_steps SET state='pending',error=NULL,fingerprint=NULL,finished_at=NULL WHERE revision_id=? AND generation=? AND stage='autoTag'",
          candidate.queued().revision(),
          candidate.queued().generation());
    }
    return candidates.stream().map(SweepRow::queued).toList();
  }

  public List<Queued> sweepTagGroups() {

    String tags = InformationFacetSql.visibleTags("r", "q");
    var candidates =
        sweepCandidates(
            "SELECT r.id,r.generation,q.owner_handle,(SELECT name FROM projects WHERE id=q.project_id) AS project_name FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id"
                + " JOIN information_steps s ON s.revision_id=r.id AND s.generation=r.generation AND s.stage='tagGroups'"
                + " WHERE r.availability='active' AND NOT r.excluded AND q.owner_handle IS NOT NULL AND NOT q.tag_groups_manual"
                + " AND jsonb_array_length("
                + tags
                + ")>0 AND (s.state='skipped' OR NOT r.tag_groups_generated OR r.tag_groups_input_tags<>"
                + tags
                + ")"
                + " AND (r.allowance_spent<r.allowance_total OR (r.tag_groups_generated AND r.tag_groups_input_tags="
                + tags
                + ")) AND s.state IN ('skipped','ready')"
                + " AND NOT EXISTS(SELECT 1 FROM information_acquisitions a WHERE a.revision_id=r.id AND a.state<>'succeeded')"
                + " AND "
                + OWNER_AUTHORIZED
                + " AND ((q.namespace<>'legacy' AND NOT EXISTS(SELECT 1 FROM documents d WHERE d.id=r.id)) OR information_readable(r.id,q.owner_handle,"
                + " CASE WHEN q.project_id IS NULL THEN 'personal' ELSE 'project' END,(SELECT p.name FROM projects p WHERE p.id=q.project_id),q.owner_handle NOT LIKE '@service/%'))"
                + " AND NOT EXISTS(SELECT 1 FROM information_inputs i WHERE i.derived_revision=r.id AND NOT information_readable(i.input_revision,q.owner_handle,"
                + " CASE WHEN q.project_id IS NULL THEN 'personal' ELSE 'project' END,(SELECT p.name FROM projects p WHERE p.id=q.project_id),q.owner_handle NOT LIKE '@service/%'))");
    for (var candidate : candidates) {
      jdbc.update(
          "UPDATE information_steps SET state='pending',error=NULL,fingerprint=NULL,finished_at=NULL WHERE revision_id=? AND generation=? AND stage='tagGroups'",
          candidate.queued().revision(),
          candidate.queued().generation());
    }
    return candidates.stream().map(SweepRow::queued).toList();
  }

  // A full page of withdrawn Application grants must not hide eligible work in other projects.
  // Excluding principal/project pairs is safe because that live authority applies to every row.
  private List<SweepRow> sweepCandidates(String stageQuery) {
    List<Authority> denied = new ArrayList<>();
    while (true) {
      List<Object> parameters = new ArrayList<>();
      StringBuilder excluded = new StringBuilder();
      for (Authority authority : denied) {
        excluded.append(
            " AND NOT (q.owner_handle=? AND (SELECT name FROM projects WHERE id=q.project_id) IS NOT DISTINCT FROM ?)");
        parameters.add(authority.owner());
        parameters.add(authority.project());
      }
      var rows =
          jdbc.query(
              stageQuery
                  + excluded
                  + " ORDER BY r.created_at,r.id LIMIT 100 FOR UPDATE OF r,s SKIP LOCKED",
              (row, index) ->
                  new SweepRow(
                      new Queued(
                          row.getObject("id", UUID.class),
                          row.getLong("generation"),
                          row.getString("owner_handle")),
                      new Authority(row.getString("owner_handle"), row.getString("project_name"))),
              parameters.toArray());
      if (rows.isEmpty()) return List.of();
      Map<Authority, Boolean> grants = new HashMap<>();
      var allowed =
          rows.stream()
              .filter(row -> grants.computeIfAbsent(row.authority(), this::authorized))
              .toList();
      if (!allowed.isEmpty()) return allowed;
      denied.addAll(grants.keySet());
    }
  }

  public Optional<Candidate> candidate(UUID revision, boolean syntaxOnly) {
    List<Authority> denied = new ArrayList<>();
    while (true) {
      List<Object> parameters = new ArrayList<>();
      parameters.add(now());
      if (revision != null) parameters.add(revision);
      StringBuilder excluded = new StringBuilder();
      for (Authority authority : denied) {
        excluded.append(" AND NOT (q.owner_handle=? AND p.name IS NOT DISTINCT FROM ?)");
        parameters.add(authority.owner());
        parameters.add(authority.project());
      }
      var rows =
          jdbc.query(
              "SELECT s.*,r.resource_id,q.owner_handle,q.project_id,p.name AS project_name FROM information_steps s"
                  + " JOIN information_revisions r ON r.id=s.revision_id JOIN information_resources q ON q.id=r.resource_id LEFT JOIN projects p ON p.id=q.project_id"
                  + " WHERE r.availability='active' AND r.generation=s.generation AND q.owner_handle IS NOT NULL"
                  + " AND NOT EXISTS(SELECT 1 FROM information_acquisitions a WHERE a.revision_id=r.id AND a.state<>'succeeded')"
                  + " AND "
                  + OWNER_AUTHORIZED
                  + " AND (s.state='pending' OR (s.state='running' AND s.lease_until<?))"
                  + " AND NOT EXISTS(SELECT 1 FROM information_steps prior WHERE prior.revision_id=s.revision_id AND prior.generation=s.generation"
                  + " AND ((s.stage='autoTag' AND prior.stage IN ('extract','derive') AND prior.state NOT IN ('ready','skipped'))"
                  + " OR (s.stage='tagGroups' AND prior.stage IN ('extract','derive','autoTag') AND prior.state IN ('pending','running','blocked'))"
                  + " OR (s.stage NOT IN ('autoTag','tagGroups') AND array_position(ARRAY['extract','derive','embed','summarise','summary_embed'],prior.stage)"
                  + " < array_position(ARRAY['extract','derive','embed','summarise','summary_embed'],s.stage) AND prior.state NOT IN ('ready','skipped'))))"
                  + (revision == null ? "" : " AND r.id=?")
                  + (syntaxOnly
                      ? " AND r.document_type='code' AND s.stage IN ('extract','derive')"
                      : "")
                  + excluded
                  + " ORDER BY array_position(ARRAY['extract','derive','embed','summarise','summary_embed','autoTag','tagGroups'],s.stage),r.created_at,r.id"
                  + " LIMIT 1 FOR UPDATE OF s SKIP LOCKED",
              (row, index) ->
                  new CandidateRow(
                      new Candidate(
                          new Lease(
                              row.getObject("revision_id", UUID.class),
                              row.getObject("resource_id", UUID.class),
                              row.getLong("generation"),
                              row.getString("stage"),
                              row.getInt("attempt") + 1,
                              UUID.randomUUID(),
                              row.getString("owner_handle"),
                              row.getObject("project_id", Long.class)),
                          row.getString("fingerprint")),
                      new Authority(row.getString("owner_handle"), row.getString("project_name"))),
              parameters.toArray());
      if (rows.isEmpty()) return Optional.empty();
      var row = rows.getFirst();
      if (authorized(row.authority())) return Optional.of(row.candidate());
      denied.add(row.authority());
    }
  }

  private record CandidateRow(Candidate candidate, Authority authority) {}

  public void configurationChanged(Lease lease) {
    jdbc.update(
        "UPDATE information_steps SET state='failed',error='configuration changed; explicitly rebuild this projection' WHERE revision_id=? AND generation=? AND stage=?",
        lease.revision(),
        lease.generation(),
        lease.stage());
  }

  public void start(Lease lease, String expected) {
    jdbc.update(
        "UPDATE information_steps SET state='running',attempt=?,lease_token=?,lease_until=?,error=NULL,fingerprint=?,started_at=?,finished_at=NULL"
            + " WHERE revision_id=? AND generation=? AND stage=?",
        lease.attempt(),
        lease.token(),
        expires(),
        expected,
        now(),
        lease.revision(),
        lease.generation(),
        lease.stage());
  }

  public boolean renew(Lease lease) {
    if (!authorized(authority(lease))) return false;
    return jdbc.update(
            "UPDATE information_steps s SET lease_until=? FROM information_revisions r,information_resources q"
                + " WHERE r.id=s.revision_id AND q.id=r.resource_id AND "
                + OWNER_AUTHORIZED
                + " AND NOT EXISTS(SELECT 1 FROM information_inputs i WHERE i.derived_revision=r.id"
                + " AND NOT information_readable(i.input_revision,q.owner_handle,CASE WHEN q.project_id IS NULL THEN 'personal' ELSE 'project' END,"
                + " (SELECT name FROM projects WHERE id=q.project_id),q.owner_handle NOT LIKE '@service/%'))"
                + " AND q.owner_handle=? AND q.project_id IS NOT DISTINCT FROM ?"
                + " AND r.availability='active' AND r.generation=s.generation AND s.revision_id=?"
                + " AND s.generation=? AND s.stage=? AND s.lease_token=? AND s.state='running' AND s.lease_until>=?",
            expires(),
            lease.owner(),
            lease.project(),
            lease.revision(),
            lease.generation(),
            lease.stage(),
            lease.token(),
            now())
        == 1;
  }

  public void requireLease(Lease lease) {

    if (!authorized(authority(lease))) throw new StaleLease();
    String selectedProject = projectName(lease.project());
    if (jdbc.queryForObject(
            "SELECT count(*) FROM information_inputs WHERE derived_revision=? AND NOT information_readable(input_revision,?,?,?,?)",
            Integer.class,
            lease.revision(),
            lease.owner(),
            selectedProject == null ? "personal" : "project",
            selectedProject,
            !ServiceCredentials.principal(lease.owner()))
        != 0) throw new StaleLease();
    List<UUID> valid =
        jdbc.query(
            "SELECT r.id FROM information_revisions r JOIN information_steps s ON s.revision_id=r.id JOIN information_resources q ON q.id=r.resource_id"
                + " WHERE "
                + OWNER_AUTHORIZED
                + " AND q.owner_handle=? AND q.project_id IS NOT DISTINCT FROM ?"
                + " AND r.id=? AND r.availability='active' AND r.generation=? AND s.generation=r.generation AND s.stage=?"
                + " AND s.lease_token=? AND s.state='running' AND s.lease_until>=? FOR UPDATE OF r,s",
            (row, index) -> row.getObject("id", UUID.class),
            lease.owner(),
            lease.project(),
            lease.revision(),
            lease.generation(),
            lease.stage(),
            lease.token(),
            now());
    if (valid.isEmpty()) throw new StaleLease();
  }

  public void finish(Lease lease, String state, String detail) {
    if (!Set.of("ready", "blocked", "failed", "skipped").contains(state))
      throw new IllegalArgumentException("invalid stage completion state");
    jdbc.update(
        "UPDATE information_steps SET state=?,error=?,finished_at=?,lease_token=NULL,lease_until=NULL WHERE revision_id=? AND generation=? AND stage=?",
        state,
        detail,
        now(),
        lease.revision(),
        lease.generation(),
        lease.stage());
  }

  public boolean taggingEligible(UUID revision) {

    Boolean eligible =
        jdbc.queryForObject(
            "SELECT r.auto_tag_requested OR r.auto_tag_generated OR (NOT r.excluded AND jsonb_array_length(q.tags)=0 AND jsonb_array_length(r.auto_tag)=0)"
                + " FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE r.id=?",
            Boolean.class,
            revision);

    return Boolean.TRUE.equals(eligible);
  }

  public boolean groupingEligible(UUID revision) {
    return !Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT q.tag_groups_manual OR r.excluded FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE r.id=?",
            Boolean.class,
            revision));
  }

  public Revision readRevision(UUID revision) {
    return jdbc.queryForObject(
        "SELECT r.*,q.source_name,q.kind FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE r.id=?",
        (r, n) ->
            new Revision(
                r.getString("source_name"),
                r.getString("source_uri"),
                r.getString("media_type"),
                r.getString("document_type"),
                r.getString("document_subtype"),
                r.getString("kind"),
                r.getString("content_hash"),
                r.getBytes("source_bytes"),
                r.getString("extracted_text"),
                r.getString("title"),
                r.getString("converter"),
                strings(r.getString("outline_top_level")),
                r.getLong("byte_size"),
                r.getInt("allowance_total"),
                r.getInt("allowance_spent"),
                r.getString("processing_log"),
                r.getBoolean("auto_tag_generated")),
        Objects.requireNonNull(revision));
  }

  private static List<String> strings(String value) {
    if (value == null) return List.of();
    try {
      var result =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readValue(
                  value, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
      return List.copyOf(result);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("invalid stored string list", e);
    }
  }

  public String projectName(Long id) {
    return id == null
        ? null
        : jdbc.queryForObject("SELECT name FROM projects WHERE id=?", String.class, id);
  }

  public void extracted(UUID revision, Extracted extracted) {
    jdbc.update(
        "UPDATE information_revisions SET extracted_text=?,text_hash=?,title=?,converter=?,outline_top_level=CAST(? AS jsonb) WHERE id=?",
        extracted.text(),
        InformationCatalogue.sha256(
            extracted.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        extracted.title(),
        extracted.converter(),
        InformationJson.json(extracted.outlineTopLevel()),
        revision);
  }

  public void documentPolicy(Lease lease) {
    jdbc.update(
        "INSERT INTO information_document_policies(document_id,owner_handle,visibility,project_id,assigned_at) VALUES(?,?,?,?,now())",
        lease.revision(),
        lease.owner(),
        lease.project() == null ? "personal" : "project",
        lease.project());
  }

  public void spend(UUID revision) {
    if (jdbc.update(
            "UPDATE information_revisions SET allowance_spent=allowance_spent+1 WHERE id=? AND allowance_spent<allowance_total",
            revision)
        != 1)
      throw new IllegalStateException(
          "processing allowance exhausted; increase it explicitly before retrying");
    jdbc.update(
        "UPDATE conversations c SET budget_total=r.allowance_total,budget_spent=r.allowance_spent FROM information_revisions r WHERE r.id=? AND c.id=r.processing_log",
        revision);
  }

  public void autoTags(UUID revision, InformationMetadata metadata) {
    jdbc.update(
        "UPDATE information_revisions SET auto_tag=CAST(? AS jsonb),auto_tag_generated=true,auto_tag_requested=false,document_author=?,document_author_source=?,document_author_evidence=? WHERE id=?",
        InformationJson.json(metadata.tags()),
        metadata.author(),
        metadata.authorSource(),
        metadata.authorEvidence(),
        revision);
    if (metadata.groups() != null)
      jdbc.update(
          "UPDATE information_revisions r SET auto_tag_groups=CAST(? AS jsonb),tag_groups_input_tags=information_visible_tags('[]'::jsonb,r.auto_tag),tag_groups_generated=(jsonb_array_length(q.tags)=0) FROM information_resources q WHERE q.id=r.resource_id AND r.id=?",
          InformationJson.json(metadata.groups()),
          revision);
  }

  public Grouping grouping(UUID revision) {
    String encoded =
        jdbc.queryForObject(
            "SELECT "
                + InformationFacetSql.visibleTags("r", "q")
                + " FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE r.id=?",
            String.class,
            revision);
    List<String> tags = InformationFacets.tags(strings(encoded));
    boolean generated =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT tag_groups_generated AND tag_groups_input_tags=CAST(? AS jsonb) FROM information_revisions WHERE id=?",
                Boolean.class,
                encoded,
                revision));
    return new Grouping(tags, generated);
  }

  public void groups(UUID revision, Map<String, List<String>> groups, List<String> tags) {
    var checked = InformationTagGroups.from(groups, tags);
    jdbc.update(
        "UPDATE information_revisions SET auto_tag_groups=CAST(? AS jsonb),tag_groups_input_tags=CAST(? AS jsonb),tag_groups_generated=true WHERE id=?",
        InformationJson.json(checked),
        InformationJson.json(tags),
        revision);
  }
}
