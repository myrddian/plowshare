package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aeyer.plowshare.protocol.InformationDiscovery;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One authorized candidate relation feeds filters, pages and retained inputs. Counts retain all
 * contributors server-side in that same statement; they never hydrate a full corpus in the agent.
 * No permission/result cache survives a request. Read selections stored on the log are the actual
 * source selections, independently of the execution's unchanged write home.
 */
public final class JdbcInformationDiscoveryRepository implements InformationDiscoveryRepository {
  private static final JsonMapper JSON =
      JsonMapper.builder().addModule(new JavaTimeModule()).build();
  private final JdbcTemplate jdbc;

  public JdbcInformationDiscoveryRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  public List<InformationDiscovery.Scope> scopes(Reader reader, String after, int limit) {
    return jdbc.query(
        """
        WITH authority AS MATERIALIZED (SELECT * FROM knowledge_read_authority(?)),
        choices AS (
         SELECT s.id::text AS id,s.kind,coalesce(p.name,s.builtin,'Account knowledge') AS name,p.name AS project
         FROM authority a JOIN authority_scopes s ON s.id=a.scope_id
         LEFT JOIN projects p ON p.id=s.project_id
         WHERE (?::text IS NULL OR p.name=? OR (s.kind='CONTAINED' AND contained_scope_in_home(s.id,?)))
         UNION ALL SELECT 'global_read','GLOBAL_READ','Generally readable knowledge',NULL
         WHERE ?::text IS NULL AND EXISTS(SELECT 1 FROM authority WHERE general_member)
        ) SELECT * FROM choices WHERE (?::text IS NULL OR id COLLATE "C">? COLLATE "C")
        ORDER BY id COLLATE "C" LIMIT ?
        """,
        (row, n) ->
            new InformationDiscovery.Scope(
                row.getString("id"),
                row.getString("kind"),
                row.getString("name"),
                row.getString("project")),
        reader.actor(),
        reader.boundaryHome(),
        reader.boundaryHome(),
        reader.boundaryHome(),
        reader.boundaryHome(),
        after,
        after,
        limit);
  }

  private record Documents(boolean allowed, List<InformationDiscovery.Item> items) {}

  private record Values(boolean allowed, List<InformationDiscovery.Facet> items) {}

  private record Retained(boolean allowed, Text text) {}

  public List<InformationDiscovery.Item> list(
      Reader reader,
      InformationDiscovery.Query query,
      InformationFacets filter,
      Position after,
      int limit) {
    var args = new ArrayList<Object>();
    String prefix = candidates(reader, query.scopes(), null, filter, query.query(), args);
    args.add(after == null ? null : OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC));
    args.add(after == null ? null : OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC));
    args.add(after == null ? null : UUID.fromString(after.key()));
    args.add(limit);
    args.add(limit - 1);
    String sql =
        prefix
            + """
        , page AS MATERIALIZED (
         SELECT * FROM filtered WHERE (?::timestamptz IS NULL OR (created_at,id)<(?::timestamptz,?::uuid))
         ORDER BY created_at DESC,id DESC LIMIT ?
        ), visible_page AS MATERIALIZED (
         SELECT * FROM page ORDER BY created_at DESC,id DESC LIMIT ?
        )
        """
            + audit(reader, "visible_page", args)
            + """
        SELECT jsonb_build_object('allowed',NOT EXISTS(SELECT 1 FROM selected WHERE NOT allowed),
         'items',coalesce((SELECT jsonb_agg(jsonb_build_object(
          'resource',p.resource_id,'revision',p.id,'scope',p.home_scope_id,'project',p.project_name,
          'sourceName',p.source_name,'title',p.title,'kind',p.kind,'reportStatus',p.report_status,
          'createdAt',p.created_at,'tags',p.tags,'autoTag',p.auto_tag,'documentAuthor',p.document_author,
          'subtype',p.document_subtype,'contentIndexed',p.content_indexed)
          ORDER BY p.created_at DESC,p.id DESC) FROM page p),'[]'::jsonb))::text
        """;
    var result = decode(jdbc.queryForObject(sql, String.class, args.toArray()), Documents.class);
    requireAllowed(result.allowed());
    return result.items();
  }

  public List<InformationDiscovery.Facet> facets(
      Reader reader,
      InformationDiscovery.Query query,
      InformationFacets filter,
      String after,
      int limit) {
    var args = new ArrayList<Object>();
    String prefix = candidates(reader, query.scopes(), null, filter, null, args);
    // Every SQL identifier/expression is code-owned; the requested facet selects an allowlisted
    // arm.
    String values =
        switch (query.facet()) {
          case "tags" -> "jsonb_array_elements_text(f.tags)";
          case "autoTag" -> "jsonb_array_elements_text(f.auto_tag)";
          case "tagGroup" -> "jsonb_object_keys(f.tag_groups)";
          case "kind" -> "unnest(ARRAY[f.kind])";
          case "documentAuthor" -> "unnest(ARRAY[f.document_author])";
          case "subtype" -> "unnest(ARRAY[f.document_subtype])";
          case "when" -> "unnest(ARRAY[to_char(f.created_at AT TIME ZONE 'UTC','YYYY-MM')])";
          default -> throw new IllegalArgumentException("Unknown facet");
        };
    args.add(after);
    args.add(after);
    args.add(limit);
    String sql =
        prefix
            + ", facet_values AS MATERIALIZED (SELECT value,count(DISTINCT f.resource_id) AS count FROM filtered f CROSS JOIN LATERAL "
            + values
            + " value WHERE value IS NOT NULL GROUP BY value), page AS (SELECT * FROM facet_values WHERE (?::text IS NULL OR value COLLATE \"C\">? COLLATE \"C\") ORDER BY value COLLATE \"C\" LIMIT ?)"
            + audit(reader, "filtered", args)
            + """
        SELECT jsonb_build_object('allowed',NOT EXISTS(SELECT 1 FROM selected WHERE NOT allowed),
         'items',coalesce((SELECT jsonb_agg(jsonb_build_object('value',value,'count',count)
          ORDER BY value COLLATE "C") FROM page),'[]'::jsonb))::text
        """;
    var result = decode(jdbc.queryForObject(sql, String.class, args.toArray()), Values.class);
    requireAllowed(result.allowed());
    return result.items();
  }

  public Text read(Reader reader, InformationDiscovery.Read request) {
    var args = new ArrayList<Object>();
    addSelection(reader, request.scopes(), request.revision(), args);
    String sql =
        """
        WITH selected AS MATERIALIZED (
         SELECT * FROM information_discovery_candidates(?,CAST(? AS uuid[]),?,?,?::uuid)
        ), filtered AS MATERIALIZED (
         SELECT revision AS id,read_scope,read_project FROM selected WHERE allowed
        )
        """
            + audit(reader, "filtered", args)
            + """
        SELECT jsonb_build_object('allowed',NOT EXISTS(SELECT 1 FROM selected WHERE NOT allowed),
         'text',(SELECT jsonb_build_object('revision',r.id,'text',r.extracted_text,
          'documentType',r.document_type,'subtype',r.document_subtype)
          FROM filtered f JOIN information_revisions r ON r.id=f.id))::text
        """;
    var result = decode(jdbc.queryForObject(sql, String.class, args.toArray()), Retained.class);
    requireAllowed(result.allowed());
    if (result.text() == null) throw unavailable();
    return result.text();
  }

  private String candidates(
      Reader reader,
      List<String> scopes,
      UUID revision,
      InformationFacets filter,
      String query,
      List<Object> args) {
    addSelection(reader, scopes, revision, args);
    var facets = InformationFacetSql.facets(filter, "r", "q");
    args.addAll(facets.arguments());
    String search = "";
    if (query != null) {
      search =
          """
          AND (to_tsvector('simple',coalesce(r.title,'')) @@ websearch_to_tsquery('simple',?)
           OR to_tsvector('simple',q.source_name || ' ' || q.tags::text) @@ websearch_to_tsquery('simple',?)
           OR to_tsvector('simple',
          """
              + InformationFacetSql.readyTags("r")
              + """
           ::text) @@ websearch_to_tsquery('simple',?)
           OR EXISTS(SELECT 1 FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id
            JOIN documents d ON d.id=p.document_id WHERE p.document_id=r.id
             AND d.text_hash=r.text_hash AND c.text_search @@ websearch_to_tsquery('english',?)))
          """;
      args.addAll(List.of(query, query, query, query));
    }
    return """
        WITH selected AS MATERIALIZED (
         SELECT * FROM information_discovery_candidates(?,CAST(? AS uuid[]),?,?,?::uuid)
        ), filtered AS MATERIALIZED (
         SELECT r.id,r.resource_id,r.created_at,r.title,r.document_subtype,q.home_scope_id,
          scope_project.name AS project_name,q.source_name,q.kind,q.tags,selected.read_scope,selected.read_project,
          report.status AS report_status,
        """
        + InformationFacetSql.readyTags("r")
        + " AS auto_tag,"
        + InformationFacetSql.documentAuthor("r", "q")
        + " AS document_author,"
        + InformationFacetSql.visibleGroups("r", "q")
        + " AS tag_groups,"
        + "EXISTS(SELECT 1 FROM documents d JOIN paragraphs p ON p.document_id=d.id JOIN chunks c ON c.paragraph_id=p.id WHERE d.id=r.id AND d.text_hash=r.text_hash) AS content_indexed"
        + " FROM selected JOIN information_revisions r ON r.id=selected.revision JOIN information_resources q ON q.id=r.resource_id"
        + " JOIN authority_scopes scope ON scope.id=q.home_scope_id LEFT JOIN projects scope_project ON scope_project.id=scope.project_id"
        + " LEFT JOIN information_reports report ON report.revision_id=r.id WHERE selected.allowed AND "
        + facets.sql()
        + (revision == null
            ? " AND (report.status IS NULL OR report.status IN ('draft','final')) "
            : " ")
        + search
        + ")";
  }

  private static void addSelection(
      Reader reader, List<String> scopes, UUID revision, List<Object> args) {
    args.add(reader.actor());
    args.add(
        "{"
            + String.join(",", scopes.stream().filter(s -> !s.equals("global_read")).toList())
            + "}");
    args.add(scopes.contains("global_read"));
    args.add(reader.boundaryHome());
    args.add(revision);
  }

  private String audit(Reader reader, String relation, List<Object> args) {
    if (reader.audit() == null) return "";
    if (!List.of("visible_page", "filtered").contains(relation))
      throw new IllegalArgumentException("Unknown audit relation");
    args.add(reader.audit().attribution());
    args.add(reader.audit().log());
    return ", retained AS (INSERT INTO information_job_inputs(job_id,owner_handle,revision_id,scope,project_name,include_shared)"
        + " SELECT parent.id,?,f.id,f.read_scope,f.read_project,false FROM "
        + relation
        + " f CROSS JOIN information_discovery_ancestors(?) parent ON CONFLICT DO NOTHING RETURNING revision_id)";
  }

  private static <T> T decode(String value, Class<T> type) {
    try {
      return JSON.readValue(value, type);
    } catch (IOException invalid) {
      throw new IllegalStateException("Invalid discovery projection", invalid);
    }
  }

  private static void requireAllowed(boolean allowed) {
    if (!allowed) throw unavailable();
  }

  private static NotFoundFault unavailable() {
    return new NotFoundFault("Information is unavailable in the selected scopes");
  }
}
