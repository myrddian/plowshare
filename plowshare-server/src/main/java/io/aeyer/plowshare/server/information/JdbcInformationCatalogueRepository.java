package io.aeyer.plowshare.server.information;

import static io.aeyer.plowshare.server.information.InformationCatalogue.STAGES;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.DocumentType;
import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.server.faults.*;
import io.aeyer.plowshare.server.information.InformationCatalogue.Admission;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns catalogue row codecs and fixed permission-scoped queries. */
public final class JdbcInformationCatalogueRepository implements InformationCatalogueRepository {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  public JdbcInformationCatalogueRepository(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.clock = Objects.requireNonNull(clock);
  }

  private static NotFoundFault absent() {
    return new NotFoundFault("information is unavailable in this selection");
  }

  private static <T> T decode(Map<String, Object> input, Class<T> type) {
    var row = new LinkedHashMap<String, Object>(input);
    row.replaceAll(
        (key, value) ->
            value instanceof java.sql.Timestamp time ? time.toInstant().toString() : value);
    for (String key : List.of("tags", "autoTag", "tagGroups", "details"))
      if (row.get(key) != null
          && !(row.get(key) instanceof List<?> || row.get(key) instanceof Map<?, ?>))
        try {
          row.put(key, JSON.readTree(row.get(key).toString()));
        } catch (java.io.IOException invalid) {
          throw new IllegalStateException("invalid stored catalogue metadata", invalid);
        }
    return JSON.convertValue(row, type);
  }

  public Snapshot row(UUID revision) {
    var rows =
        jdbc.query(
            "SELECT r.id,r.resource_id,r.document_type,r.document_subtype,r.availability,r.extracted_text,q.owner_handle,r.generation,r.allowance_spent FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE r.id=?",
            (rs, n) ->
                new Snapshot(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getLong(8),
                    rs.getInt(9)),
            revision);
    if (rows.isEmpty()) throw absent();
    return rows.getFirst();
  }

  public Owner lockOwner(String account, UUID revision) {
    var rows =
        jdbc.query(
            "SELECT q.id,q.namespace,q.source_name,q.owner_handle,q.project_id,p.name FROM information_resources q JOIN information_revisions r ON r.resource_id=q.id LEFT JOIN projects p ON p.id=q.project_id WHERE r.id=? AND q.owner_handle=? FOR UPDATE OF q",
            (rs, n) ->
                new Owner(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    (Long) rs.getObject(5),
                    rs.getString(6)),
            revision,
            account);
    if (rows.isEmpty()) throw absent();
    return rows.getFirst();
  }

  public boolean readable(InformationContext context, UUID revision) {
    var args = new ArrayList<Object>(selectionArgs(context));
    args.add(revision);
    return !jdbc.queryForList(SELECT + " WHERE " + READABLE + " AND r.id=?", args.toArray())
        .isEmpty();
  }

  public Information.Revision metadata(UUID revision) {
    return decode(jdbc.queryForMap(SELECT + " WHERE r.id=?", revision), Information.Revision.class);
  }

  public List<Information.Step> steps(UUID revision) {
    return jdbc
        .queryForList(
            "SELECT stage,state,attempt,error,generation,fingerprint,started_at,finished_at FROM information_steps WHERE revision_id=? ORDER BY generation,stage",
            revision)
        .stream()
        .map(row -> decode(row, Information.Step.class))
        .toList();
  }

  public Information.Progress progress(UUID revision) {
    return new Information.Progress(
        decode(
            jdbc.queryForMap(
                "SELECT count(*) AS total,count(*) FILTER(WHERE c.embedding IS NOT NULL) AS completed FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=?",
                revision),
            Information.Completion.class),
        decode(
            jdbc.queryForMap(
                "SELECT count(*) AS total,count(*) FILTER(WHERE summary IS NOT NULL) AS completed FROM (SELECT summary FROM paragraphs WHERE document_id=? UNION ALL SELECT summary FROM sections WHERE document_id=? UNION ALL SELECT summary FROM chapters WHERE document_id=? UNION ALL SELECT summary FROM documents WHERE id=?) summaries",
                revision,
                revision,
                revision,
                revision),
            Information.Completion.class));
  }

  public List<Information.Event> revisionEvents(UUID revision) {
    return jdbc
        .queryForList(
            "SELECT sequence,generation,stage,action,detail,recorded_at FROM information_events WHERE revision_id=? ORDER BY sequence",
            revision)
        .stream()
        .map(row -> decode(row, Information.Event.class))
        .toList();
  }

  public List<UUID> inputs(UUID revision) {
    return List.copyOf(
        jdbc.queryForList(
            "SELECT input_revision FROM information_inputs WHERE derived_revision=?",
            UUID.class,
            revision));
  }

  public Optional<Information.Report> report(UUID revision) {
    return jdbc
        .queryForList(
            "SELECT status,feedback_revision,finalised_at,details,produced_by,definition_hash FROM information_reports WHERE revision_id=?",
            revision)
        .stream()
        .map(row -> decode(row, Information.Report.class))
        .findFirst();
  }

  public List<UUID> citations(UUID revision) {
    return List.copyOf(
        jdbc.queryForList(
            "SELECT evidence_id FROM information_report_citations WHERE report_revision=?",
            UUID.class,
            revision));
  }

  public String text(UUID revision) {
    return jdbc.queryForObject(
        "SELECT extracted_text FROM information_revisions WHERE id=?", String.class, revision);
  }

  public byte[] bytes(UUID revision) {
    return jdbc.queryForObject(
        "SELECT source_bytes FROM information_revisions WHERE id=?", byte[].class, revision);
  }

  public Optional<Span> codeSpan(UUID revision, UUID chunk) {
    return jdbc
        .query(
            "SELECT start_offset,end_offset FROM code_passages WHERE document_id=? AND chunk_id=?",
            (row, n) -> new Span(row.getInt(1), row.getInt(2)),
            revision,
            chunk)
        .stream()
        .findFirst();
  }

  public List<UUID> discoveryRevisions(InformationContext context) {
    var selected = discovery(context, null);
    return List.copyOf(
        jdbc.queryForList(
            "SELECT r.id FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE "
                + selected.sql(),
            UUID.class,
            selected.arguments().toArray()));
  }

  public Information.Facets facets(InformationContext context, String kind, List<UUID> pinned) {
    var selected = discovery(context, kind);
    if (pinned != null) {
      var args = new ArrayList<Object>(selected.arguments());
      args.add(
          "{"
              + pinned.stream()
                  .map(UUID::toString)
                  .collect(java.util.stream.Collectors.joining(","))
              + "}");
      selected =
          new InformationSql.Filter(selected.sql() + " AND r.id=ANY(CAST(? AS uuid[]))", args);
    }
    return facetCounts(selected);
  }

  public List<Information.Revision> list(
      InformationContext context, int limit, int offset, String kind) {
    if (offset < 0) throw new CallerFault("offset must be nonnegative");
    if (kind != null && !List.of("source", "report").contains(kind))
      throw new CallerFault("kind must be source or report");
    var selected = discovery(context, kind);
    var args = new ArrayList<Object>(selected.arguments());
    args.add(Math.max(1, Math.min(limit, 100)));
    args.add(offset);
    return jdbc
        .queryForList(
            SELECT
                + " WHERE "
                + selected.sql()
                + " ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?",
            args.toArray())
        .stream()
        .map(row -> decode(row, Information.Revision.class))
        .toList();
  }

  private io.aeyer.plowshare.server.information.InformationSql.Filter discovery(
      InformationContext context, String kind) {
    if (kind != null && !List.of("source", "report").contains(kind))
      throw new CallerFault("kind must be source or report");
    var facets =
        io.aeyer.plowshare.server.information.InformationFacetSql.facets(
            context.facets(), "r", "q");
    var args = new ArrayList<Object>(selectionArgs(context));
    args.add(context.corpus().documentType());
    if (kind != null) args.add(kind);
    args.addAll(facets.arguments());
    return new io.aeyer.plowshare.server.information.InformationSql.Filter(
        READABLE
            + " AND r.document_type=? AND NOT r.excluded"
            + (kind == null ? "" : " AND q.kind=?")
            + " AND NOT EXISTS(SELECT 1 FROM information_reports report WHERE report.revision_id=r.id AND report.status NOT IN ('draft','final')) AND "
            + facets.sql(),
        args);
  }

  private Information.Facets facetCounts(
      io.aeyer.plowshare.server.information.InformationSql.Filter selected) {
    String from =
        " FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE "
            + selected.sql();
    var counts = new java.util.LinkedHashMap<String, List<Information.FacetCount>>();
    var more = new java.util.LinkedHashMap<String, Boolean>();
    for (String name : InformationFacets.NAMES) {
      String expression =
          switch (name) {
            case "kind" -> "q.kind";
            case "author" -> "q.owner_handle";
            case "documentAuthor" ->
                io.aeyer.plowshare.server.information.InformationFacetSql.documentAuthor("r", "q");
            case "tagGroup" -> InformationFacetSql.visibleGroups("r", "q");
            case "subtype" -> "r.document_subtype";
            case "when" -> "to_char(r.created_at AT TIME ZONE 'UTC','YYYY-MM')";
            case "tags" -> "q.tags";
            default -> io.aeyer.plowshare.server.information.InformationFacetSql.readyTags("r");
          };
      String sql =
          name.equals("tags") || name.equals("autoTag")
              ? "SELECT value,count(*) AS count FROM (SELECT "
                  + expression
                  + " AS tags"
                  + from
                  + ") candidates CROSS JOIN LATERAL jsonb_array_elements_text(candidates.tags) AS tag(value) GROUP BY value ORDER BY count(*) DESC,value LIMIT 101"
              : name.equals("tagGroup")
                  ? "SELECT value,count(*) AS count FROM (SELECT "
                      + expression
                      + " AS groups"
                      + from
                      + ") candidates CROSS JOIN LATERAL jsonb_object_keys(candidates.groups) AS g(value) GROUP BY value ORDER BY count(*) DESC,value LIMIT 101"
                  : "SELECT "
                      + expression
                      + " AS value,count(*) AS count"
                      + from
                      + " GROUP BY value HAVING "
                      + expression
                      + " IS NOT NULL ORDER BY count(*) DESC,value LIMIT 101";
      var rows = jdbc.queryForList(sql, selected.arguments().toArray());
      more.put(name, rows.size() > 100);
      counts.put(
          name,
          rows.stream().limit(100).map(row -> decode(row, Information.FacetCount.class)).toList());
    }
    var edges =
        jdbc.queryForList(
            "SELECT g.key AS \"group\",t.tag,count(*) AS count FROM (SELECT "
                + InformationFacetSql.visibleGroups("r", "q")
                + " AS groups"
                + from
                + ") candidates"
                + " CROSS JOIN LATERAL jsonb_each(candidates.groups) g CROSS JOIN LATERAL jsonb_array_elements_text(g.value) t(tag)"
                + " GROUP BY g.key,t.tag ORDER BY count(*) DESC,g.key,t.tag LIMIT 1001",
            selected.arguments().toArray());
    return new Information.Facets(
        jdbc.queryForObject("SELECT count(*)" + from, Long.class, selected.arguments().toArray()),
        counts,
        more,
        new Information.TagGraph(
            edges.stream().limit(1000).map(row -> decode(row, Information.TagEdge.class)).toList(),
            edges.size() > 1000));
  }

  public List<Information.Revision> inventory(InformationContext context, int limit, int offset) {
    if (limit < 1 || limit > 100 || offset < 0)
      throw new CallerFault("inventory needs limit 1..100 and nonnegative offset");
    String project = context.selection().project();
    if (context.selection().scope() == InformationContext.Scope.SHARED) return List.of();
    return jdbc
        .queryForList(
            "SELECT r.id,r.resource_id,r.ordinal,q.source_name,q.source_name AS title,q.kind,q.tags,r.document_type,r.document_subtype,r.availability,r.excluded,r.generation,r.created_at,r.allowance_total,r.allowance_spent"
                + " FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE q.owner_handle=?"
                + " AND ((?::text IS NULL AND q.project_id IS NULL) OR q.project_id=(SELECT id FROM projects WHERE name=?)) AND r.document_type=? ORDER BY r.created_at DESC,r.id OFFSET ? LIMIT ?",
            context.account(),
            project,
            project,
            context.corpus().documentType(),
            offset,
            limit)
        .stream()
        .map(row -> decode(row, Information.Revision.class))
        .toList();
  }

  public Information.Events events(InformationContext context, long after, int limit) {
    if (after < 0 || limit < 1 || limit > 100)
      throw new CallerFault("event cursor must be nonnegative and limit between 1 and 100");
    List<Object> args = new ArrayList<>(selectionArgs(context));
    args.add(context.corpus().documentType());
    args.add(after);
    args.add(limit);
    List<Map<String, Object>> events =
        jdbc.queryForList(
            "SELECT e.sequence,e.revision_id,e.generation,e.stage,e.action,e.detail,e.recorded_at,r.document_type,r.document_subtype"
                + " FROM information_events e JOIN information_revisions r ON r.id=e.revision_id JOIN information_resources q ON q.id=r.resource_id"
                + " WHERE "
                + READABLE
                + " AND r.document_type=? AND e.sequence>? ORDER BY e.sequence LIMIT ?",
            args.toArray());
    long cursor =
        events.isEmpty() ? after : ((Number) events.getLast().get("sequence")).longValue();
    return new Information.Events(
        events.stream().map(row -> decode(row, Information.Event.class)).toList(), cursor);
  }

  public Information.Evidence evidence(InformationContext context, UUID id) {
    var args = new ArrayList<Object>(selectionArgs(context));
    args.add(id);
    List<Map<String, Object>> found =
        jdbc.queryForList(
            "SELECT e.id,e.revision_id,e.paragraph_id,e.start_offset,e.end_offset,e.quote,e.locator,e.created_at FROM information_evidence e JOIN information_revisions r ON r.id=e.revision_id JOIN information_resources q ON q.id=r.resource_id WHERE "
                + READABLE
                + " AND e.id=?",
            args.toArray());
    if (found.isEmpty()) throw absent();
    return decode(found.getFirst(), Information.Evidence.class);
  }

  public io.aeyer.plowshare.server.documents.CodeProjection codeProjection(
      InformationContext context, UUID revision, String rawHash) {

    if (!readable(context, revision)) throw absent();
    var filter = io.aeyer.plowshare.server.information.InformationSql.read(context, "r");
    var args =
        new ArrayList<Object>(
            List.of(revision, rawHash, io.aeyer.plowshare.server.documents.CodeOutline.VERSION));
    args.addAll(filter.arguments());
    var rows =
        jdbc.queryForList(
            "SELECT r.extracted_text,o.* FROM information_revisions r JOIN code_outlines o ON o.document_id=r.id"
                + " JOIN information_steps s ON s.revision_id=r.id AND s.generation=r.generation AND s.stage='derive' AND s.state='ready'"
                + " WHERE r.id=? AND r.content_hash=? AND r.document_type='code' AND r.availability='active' AND NOT r.excluded AND o.parser_version=? AND o.source_hash=r.text_hash AND "
                + filter.sql(),
            args.toArray());
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    var symbolArgs = new ArrayList<Object>(List.of(revision));
    symbolArgs.addAll(filter.arguments());
    var symbols =
        jdbc.query(
            "SELECT s.ordinal,s.name,s.kind,s.qualified_name,s.parent_ordinal,s.signature,s.start_offset,s.end_offset,s.start_line,s.end_line"
                + " FROM code_symbols s JOIN information_revisions r ON r.id=s.document_id WHERE r.id=? AND "
                + filter.sql()
                + " ORDER BY s.ordinal LIMIT 10001",
            (rs, n) ->
                new io.aeyer.plowshare.server.documents.CodeOutline.Symbol(
                    rs.getInt(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    (Integer) rs.getObject(5),
                    rs.getString(6),
                    rs.getInt(7),
                    rs.getInt(8),
                    rs.getInt(9),
                    rs.getInt(10)),
            symbolArgs.toArray());
    String text = (String) row.get("extracted_text");
    if (text == null
        || text.length() > io.aeyer.plowshare.server.documents.CodeOutline.MAX_SOURCE_CHARS
        || symbols.size() > 10000) return null;
    if (!readable(context, revision)) throw absent();
    return new io.aeyer.plowshare.server.documents.CodeProjection(
        revision,
        text,
        new io.aeyer.plowshare.server.documents.CodeOutline(
            (String) row.get("status"),
            (String) row.get("reason"),
            (String) row.get("language"),
            (String) row.get("parser_version"),
            (String) row.get("source_hash"),
            symbols));
  }

  public Information.Outline outline(
      InformationContext context, UUID revision, int offset, int limit) {
    page(offset, limit);
    if (!readable(context, revision)) throw absent();
    var filter = io.aeyer.plowshare.server.information.InformationSql.read(context, "r");
    var arguments = new ArrayList<Object>();
    arguments.add(revision);
    arguments.addAll(filter.arguments());
    var sources =
        jdbc.queryForList(
            "SELECT document_type,document_subtype,text_hash FROM information_revisions r WHERE r.id=? AND "
                + filter.sql(),
            arguments.toArray());
    if (sources.isEmpty()) throw absent();
    var source = sources.getFirst();
    if (!"code".equals(source.get("document_type")))
      throw new CallerFault("outline requires a code revision");
    var rows =
        jdbc.queryForList(
            "SELECT source_hash,language,parser_version,status,reason,symbol_count FROM code_outlines o JOIN information_revisions r ON r.id=o.document_id WHERE r.id=? AND "
                + filter.sql(),
            arguments.toArray());
    var result = new java.util.LinkedHashMap<String, Object>();
    result.put("revision", revision);
    result.put("language", source.get("document_subtype"));
    result.put("locator", "extracted-text:utf16");
    result.put("source_kind", "retained_revision");
    result.put(
        "role",
        "Syntax declarations for navigation; signatures are abbreviated, not verbatim evidence. No reference or call resolution.");
    result.put("offset", offset);
    if (rows.isEmpty()) {
      boolean derived =
          !jdbc.queryForList("SELECT id FROM documents WHERE id=?", revision).isEmpty();
      result.put("status", derived ? "unavailable" : "pending");
      result.put("reason", derived ? "outline_not_indexed" : "derivation_pending");
      result.put("source_hash", source.get("text_hash"));
      result.put("parser_version", io.aeyer.plowshare.server.documents.CodeOutline.VERSION);
      result.put("symbol_count", 0);
      result.put("symbols", List.of());
      result.put("has_more", false);
      return decode(result, Information.Outline.class);
    }
    var indexed = rows.getFirst();
    result.putAll(indexed);
    if (!java.util.Objects.equals(source.get("text_hash"), indexed.get("source_hash"))
        || !io.aeyer.plowshare.server.documents.CodeOutline.VERSION.equals(
            indexed.get("parser_version"))) {
      result.put("status", "stale");
      result.put("reason", "projection_version_changed");
      result.put("symbols", List.of());
      result.put("has_more", false);
      return decode(result, Information.Outline.class);
    }
    arguments.add(offset);
    arguments.add(limit + 1);
    var symbols =
        jdbc.queryForList(
            "SELECT s.ordinal,s.name,s.kind,s.qualified_name,s.parent_ordinal,s.signature,s.start_offset,s.end_offset,s.start_line,s.end_line FROM code_symbols s JOIN information_revisions r ON r.id=s.document_id WHERE r.id=? AND "
                + filter.sql()
                + " ORDER BY s.ordinal OFFSET ? LIMIT ?",
            arguments.toArray());
    result.put("has_more", symbols.size() > limit);
    result.put("symbols", symbols.size() > limit ? symbols.subList(0, limit) : symbols);
    return decode(result, Information.Outline.class);
  }

  public Information.Symbols symbols(
      InformationContext context, String query, UUID revision, int offset, int limit) {
    page(offset, limit);
    if (query == null || query.isBlank() || query.length() > 128)
      throw new CallerFault("symbol query must have 1 to 128 characters");
    if (revision != null) {
      if (!readable(context, revision)) throw absent();
      if (!"code"
          .equals(
              jdbc.queryForObject(
                  "SELECT document_type FROM information_revisions WHERE id=?",
                  String.class,
                  revision))) throw new CallerFault("symbols requires a code revision");
    }
    io.aeyer.plowshare.server.information.InformationSql.Filter filter =
        io.aeyer.plowshare.server.information.InformationSql.read(context, "r");
    String prefix =
        query
                .toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_")
            + "%";
    var arguments = new ArrayList<Object>(filter.arguments());
    arguments.add(io.aeyer.plowshare.server.documents.CodeOutline.VERSION);
    arguments.add(prefix);
    if (revision != null) arguments.add(revision);
    arguments.add(query.toLowerCase(java.util.Locale.ROOT));
    arguments.add(offset);
    arguments.add(limit + 1);
    var symbols =
        jdbc.queryForList(
            "SELECT r.id AS revision,q.source_name,r.document_subtype AS language,o.status AS outline_status,o.source_hash,o.parser_version,"
                + "s.ordinal,s.name,s.kind,s.qualified_name,s.parent_ordinal,s.signature,s.start_offset,s.end_offset,s.start_line,s.end_line"
                + " FROM code_symbols s JOIN code_outlines o ON o.document_id=s.document_id"
                + " JOIN information_revisions r ON r.id=s.document_id JOIN information_resources q ON q.id=r.resource_id"
                + " WHERE "
                + filter.sql()
                + " AND r.document_type='code' AND NOT r.excluded"
                + " AND o.source_hash=r.text_hash AND o.parser_version=? AND o.status IN ('ready','partial','limited')"
                + " AND lower(s.name) LIKE ? ESCAPE '!'"
                + (revision == null ? "" : " AND r.id=?")
                + " ORDER BY (lower(s.name)=?) DESC,length(s.name),lower(s.name),q.source_name,r.id,s.ordinal OFFSET ? LIMIT ?",
            arguments.toArray());
    return decode(
        Map.of(
            "query",
            query,
            "offset",
            offset,
            "has_more",
            symbols.size() > limit,
            "symbols",
            symbols.size() > limit ? symbols.subList(0, limit) : symbols,
            "locator",
            "extracted-text:utf16",
            "source_kind",
            "retained_revision",
            "role",
            "Syntax declaration matches, not resolved references. Only indexed code revisions are searched; these are not live filesystem versions."),
        Information.Symbols.class);
  }

  private static void page(int offset, int limit) {
    if (offset < 0 || limit < 1 || limit > 100)
      throw new CallerFault("offset must be nonnegative and limit must be between 1 and 100");
  }

  private static List<Object> selectionArgs(InformationContext context) {
    return java.util.Arrays.asList(
        context.account(),
        context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),
        context.selection().project(),
        context.selection().includeShared(),
        context.account(),
        context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),
        context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),
        context.selection().project(),
        context.account(),
        context.account(),
        context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),
        context.selection().project(),
        context.selection().includeShared());
  }

  private static final String SELECT =
      "SELECT r.id,r.resource_id,r.ordinal,r.title,r.media_type,r.document_type,r.document_subtype,r.source_uri,r.content_hash,r.text_hash,"
          + "r.byte_size,r.created_at,r.availability,r.excluded,r.generation,r.converter,r.allowance_total,r.allowance_spent,q.source_name,q.kind,q.owner_handle AS author,q.tags,"
          + io.aeyer.plowshare.server.information.InformationFacetSql.readyTags("r")
          + " AS \"autoTag\",r.auto_tag_generated,"
          + io.aeyer.plowshare.server.information.InformationFacetSql.documentAuthor("r", "q")
          + " AS \"documentAuthor\","
          + io.aeyer.plowshare.server.information.InformationFacetSql.documentAuthorSource("r")
          + " AS \"documentAuthorSource\","
          + InformationFacetSql.visibleGroups("r", "q")
          + " AS \"tagGroups\",CASE WHEN q.tag_groups_manual THEN 'manual' ELSE 'automatic' END AS \"tagGroupsSource\","
          + "(SELECT report.status FROM information_reports report WHERE report.revision_id=r.id) AS report_status FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id";
  // Pending sources have no corpus row yet; ownership and project admission still apply.
  // Dependencies always require live permission.
  private static final String READABLE =
      "r.availability='active' AND (information_readable(r.id,?,?,?,?) OR ("
          + "NOT EXISTS(SELECT 1 FROM documents d WHERE d.id=r.id) AND q.owner_handle=? AND q.namespace<>'legacy'"
          + " AND ((q.project_id IS NULL AND ?='personal') OR (q.project_id IS NOT NULL AND ?='project'"
          + " AND EXISTS(SELECT 1 FROM projects p JOIN project_members m ON m.project_id=p.id"
          + " WHERE p.id=q.project_id AND p.name=? AND m.handle=?)))"
          + " AND NOT EXISTS(SELECT 1 FROM information_inputs i WHERE i.derived_revision=r.id"
          + " AND NOT information_readable(i.input_revision,?,?,?,?))))";

  public void event(
      UUID revision, long generation, String actor, String stage, String action, String detail) {
    if (generation < 1) throw new IllegalArgumentException("event generation must be positive");

    // The event foreign key needs this lock too. Take it before the global commit-order
    // fence: a worker can already hold FOR UPDATE on this revision when publishing its
    // own event. Holding the fence while waiting for that worker creates a deadlock.
    // Transactions publishing multiple revisions must lock all of them before the fence.
    jdbc.queryForObject(
        "SELECT id FROM information_revisions WHERE id=? FOR KEY SHARE", UUID.class, revision);
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended('information-event-order',0)) IS NULL",
        Boolean.class);
    Long sequence =
        jdbc.queryForObject(
            "INSERT INTO information_events(revision_id,generation,actor_handle,stage,action,detail) VALUES(?,?,?,?,?,?) RETURNING sequence",
            Long.class,
            revision,
            generation,
            actor,
            stage,
            action,
            detail == null ? "" : detail);
    jdbc.update(
        "INSERT INTO information_event_recipients(sequence,account) SELECT ?,a.handle FROM admins a WHERE information_readable(?,a.handle,'personal',NULL,true)"
            + " OR EXISTS(SELECT 1 FROM projects p JOIN project_members m ON m.project_id=p.id WHERE m.handle=a.handle AND information_readable(?,a.handle,'project',p.name,true)) ON CONFLICT DO NOTHING",
        sequence,
        revision,
        revision);
    if (List.of("withdrawn", "excluded", "deleted", "unshared", "unlinked").contains(action))
      jdbc.update(
          "INSERT INTO information_event_recipients(sequence,account) SELECT ?,x.account FROM information_event_recipients x JOIN information_events e ON e.sequence=x.sequence"
              + " WHERE e.revision_id=? AND e.sequence<? ON CONFLICT DO NOTHING",
          sequence,
          revision,
          sequence);
  }

  private long generation(UUID revision) {
    return row(revision).generation();
  }

  private static String canonical(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("cannot encode catalogue record", invalid);
    }
  }

  public Admission admit(InformationContext context, UUID request, AdmissionWrite write) {
    Objects.requireNonNull(context);
    Objects.requireNonNull(request);
    String name = write.name();
    byte[] retained = write.bytes();
    String hash = InformationCatalogue.sha256(retained);
    String mediaType = write.mediaType();
    String sourceUri = write.sourceUri();
    String kind = write.kind();
    List<UUID> inputs = write.inputs();
    String session = write.session();
    int modelAllowance = write.allowance();
    int capturedAllowance = write.capturedAllowance();
    boolean syntaxOnly = write.syntaxOnly();
    DocumentType documentType = write.documentType();
    String fingerprint = write.fingerprint();
    String namespace =
        context.selection().scope() == InformationContext.Scope.PROJECT
            ? "project:" + context.selection().project()
            : "account:" + context.account();
    if (context.selection().scope() == InformationContext.Scope.SHARED)
      throw new CallerFault("shared namespace cannot admit source bytes");

    // Serialises this idempotency key and this namespace/name across processes, without model
    // calls.
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
        Boolean.class,
        context.account() + ":" + request);
    List<Map<String, Object>> prior =
        jdbc.queryForList(
            "SELECT fingerprint,revision_id FROM information_requests"
                + " WHERE account=? AND request_id=?",
            context.account(),
            request);
    if (!prior.isEmpty()) {
      if (!fingerprint.equals(prior.getFirst().get("fingerprint")))
        throw new CallerFault("requestId was already used for different information");
      UUID id = (UUID) prior.getFirst().get("revision_id");
      return new Admission(id, row(id).resourceId(), false);
    }
    if (capturedAllowance == 0
        && jdbc.queryForObject(
                "SELECT count(*) FROM information_acquisitions WHERE account=? AND request_id=?",
                Integer.class,
                context.account(),
                request)
            != 0) throw new CallerFault("requestId was already used for URL acquisition");
    for (UUID input : inputs) if (!readable(context, input)) throw absent();
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
        Boolean.class,
        namespace + ":" + name);
    List<Map<String, Object>> resources =
        jdbc.queryForList(
            "SELECT id,owner_handle,kind FROM information_resources"
                + " WHERE namespace=? AND source_name=? FOR UPDATE",
            namespace,
            name);
    UUID resource;
    if (resources.isEmpty()) {
      resource = UUID.randomUUID();
      Long project =
          context.selection().project() == null
              ? null
              : jdbc.queryForObject(
                  "SELECT id FROM projects WHERE name=?",
                  Long.class,
                  context.selection().project());
      jdbc.update(
          "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind)"
              + " VALUES(?,?,?,?,?,?)",
          resource,
          namespace,
          name,
          context.account(),
          project,
          kind);
    } else {
      if (!context.account().equals(resources.getFirst().get("owner_handle"))) throw absent();
      if (!kind.equals(resources.getFirst().get("kind")))
        throw new CallerFault("this name already belongs to a different resource kind");
      resource = (UUID) resources.getFirst().get("id");
    }
    int ordinal =
        jdbc.queryForObject(
            "SELECT coalesce(max(ordinal),0)+1 FROM information_revisions" + " WHERE resource_id=?",
            Integer.class,
            resource);
    UUID revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,source_uri,"
            + "content_hash,byte_size,source_bytes,allowance_total,caller_session,document_type,document_subtype) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        revision,
        resource,
        ordinal,
        name,
        mediaType == null ? "application/octet-stream" : mediaType,
        sourceUri,
        hash,
        retained.length,
        retained,
        modelAllowance,
        session,
        documentType.type(),
        documentType.subtype());
    for (String stage : STAGES)
      jdbc.update(
          "INSERT INTO information_steps(revision_id,generation,stage,state)" + " VALUES(?,1,?,?)",
          revision,
          stage,
          (syntaxOnly && List.of("embed", "autoTag", "tagGroups").contains(stage))
                  || (documentType.isCode()
                      && List.of("summarise", "summary_embed").contains(stage))
              ? "skipped"
              : "pending");
    for (UUID input : inputs)
      jdbc.update("INSERT INTO information_inputs VALUES(?,?)", revision, input);
    jdbc.update(
        "INSERT INTO information_requests VALUES(?,?,?,?)",
        context.account(),
        request,
        fingerprint,
        revision);
    event(revision, 1, context.account(), "intake", "retained", "");
    return new Admission(revision, resource, true);
  }

  public void link(InformationContext context, UUID revision, String project, boolean remove) {

    lockOwner(context.account(), revision);
    if (remove)
      jdbc.update(
          "DELETE FROM information_links WHERE revision_id=? AND project_id=(SELECT id FROM projects WHERE name=?)",
          revision,
          project);
    else
      jdbc.update(
          "INSERT INTO information_links(revision_id,project_id,actor_handle)"
              + " SELECT ?,id,? FROM projects WHERE name=? ON CONFLICT DO NOTHING",
          revision,
          context.account(),
          project);
    event(
        revision,
        generation(revision),
        context.account(),
        "collection",
        remove ? "unlinked" : "linked",
        project);
    return;
  }

  public void share(InformationContext context, UUID revision, boolean shared) {

    var resource = lockOwner(context.account(), revision);
    if (shared && !readable(context, revision)) throw absent();
    if (shared
        && !jdbc.queryForObject(
            "SELECT coalesce(bool_and(information_readable(input_revision,?,'shared',NULL,true)),true)"
                + " FROM information_inputs WHERE derived_revision=?",
            Boolean.class,
            context.account(),
            revision))
      throw new CallerFault(
          "every input must already be shared before a derived revision can be shared");
    String visibility = shared ? "shared" : resource.projectId() == null ? "personal" : "project";
    Long project = shared ? null : resource.projectId();
    if (jdbc.update(
            "UPDATE information_document_policies SET visibility=?,project_id=? WHERE document_id=?",
            visibility,
            project,
            revision)
        != 1) throw new CallerFault("derive this revision before sharing it");
    event(
        revision,
        generation(revision),
        context.account(),
        "sharing",
        shared ? "shared" : "unshared",
        "");
    return;
  }

  public void availability(InformationContext context, UUID revision, String state) {
    if (!Set.of("active", "excluded", "included", "withdrawn", "deleted").contains(state))
      throw new IllegalArgumentException("invalid availability");

    lockOwner(context.account(), revision);
    if (state.equals("excluded") || state.equals("included")) {
      if (jdbc.update(
              "UPDATE information_revisions SET excluded=? WHERE id=? AND availability<>'deleted'",
              state.equals("excluded"),
              revision)
          != 1) throw new CallerFault("deleted revisions have no discovery state to change");
      event(revision, generation(revision), context.account(), "discovery", state, "");
      return;
    }
    String old =
        jdbc.queryForObject(
            "SELECT availability FROM information_revisions WHERE id=? FOR UPDATE",
            String.class,
            revision);
    if (old.equals(state)) return;
    if (old.equals("deleted")) throw new CallerFault("deleted source bytes cannot be restored");
    long previous = generation(revision);
    jdbc.update(
        "UPDATE information_revisions SET availability=?,generation=generation+1 WHERE id=?",
        state,
        revision);
    jdbc.update(
        "UPDATE information_steps SET state='cancelled',lease_token=NULL,lease_until=NULL"
            + " WHERE revision_id=? AND generation=? AND state IN ('pending','running','blocked','failed')",
        revision,
        previous);
    if (state.equals("deleted")) {
      jdbc.update(
          "UPDATE information_revisions SET source_bytes=NULL,extracted_text=NULL,document_author=NULL,document_author_source=NULL,document_author_evidence=NULL,auto_tag_groups='{}'::jsonb,tag_groups_input_tags='[]'::jsonb,tag_groups_generated=false WHERE id=?",
          revision);
      jdbc.update(
          "UPDATE information_evidence SET quote='[deleted]' WHERE revision_id=?", revision);
      jdbc.update("DELETE FROM documents WHERE id=?", revision);
      jdbc.update("DELETE FROM information_model_steps WHERE revision_id=?", revision);
      jdbc.update(
          "DELETE FROM orchestration_script_steps WHERE conversation_id IN (SELECT job_id FROM information_job_inputs WHERE revision_id=?)",
          revision);
      jdbc.update(
          "UPDATE information_reports SET details=CAST('{\"deleted\":true}' AS jsonb) WHERE revision_id=?",
          revision);
      jdbc.update("UPDATE information_events SET detail='[deleted]' WHERE revision_id=?", revision);
    }
    if (state.equals("active")) {
      for (String stage : STAGES)
        jdbc.update(
            "INSERT INTO information_steps(revision_id,generation,stage,state)"
                + " SELECT ?,?,?,coalesce((SELECT CASE WHEN state IN ('ready','skipped') THEN state ELSE 'pending' END"
                + " FROM information_steps WHERE revision_id=? AND generation<=? AND stage=? ORDER BY generation DESC LIMIT 1),'pending')",
            revision,
            previous + 1,
            stage,
            revision,
            previous,
            stage);
    }
    if (state.equals("active"))
      jdbc.update(
          "UPDATE information_steps next SET fingerprint=prior.fingerprint FROM information_steps prior WHERE next.revision_id=? AND next.generation=? AND next.state IN ('ready','skipped') AND prior.revision_id=next.revision_id AND prior.generation=(SELECT max(last.generation) FROM information_steps last WHERE last.revision_id=next.revision_id AND last.stage=next.stage AND last.generation<?) AND prior.stage=next.stage",
          revision,
          previous + 1,
          previous + 1);
    event(revision, previous + 1, context.account(), "availability", state, "");
    return;
  }

  public void retry(InformationContext context, UUID revision) {

    lockOwner(context.account(), revision);
    if (!"active".equals(row(revision).availability()))
      throw new CallerFault("restore availability before retrying");
    jdbc.update(
        "UPDATE information_steps SET state='pending',error=NULL WHERE revision_id=? AND generation=?"
            + " AND state IN ('failed','blocked','cancelled')",
        revision,
        generation(revision));
    event(revision, generation(revision), context.account(), "processing", "retry", "");
    return;
  }

  public void rebuild(InformationContext context, UUID revision, String from) {
    int first = STAGES.indexOf(from);
    if (first < 2) throw new CallerFault("invalid rebuild stage");

    lockOwner(context.account(), revision);
    if (!readable(context, revision)) throw absent();
    boolean code = "code".equals(row(revision).documentType());
    boolean metadata = List.of("autoTag", "tagGroups").contains(from);
    if (code && first > 2 && !metadata)
      throw new CallerFault("code documents do not run the prose summary cascade");
    long old = generation(revision);
    jdbc.queryForMap("SELECT id FROM information_revisions WHERE id=? FOR UPDATE", revision);
    jdbc.update("UPDATE information_revisions SET generation=generation+1 WHERE id=?", revision);
    jdbc.update(
        "UPDATE information_steps SET state='cancelled',lease_token=NULL,lease_until=NULL WHERE revision_id=? AND generation=? AND state<>'ready' AND "
            + (metadata
                ? (from.equals("autoTag")
                    ? "stage IN ('autoTag','tagGroups')"
                    : "stage='tagGroups'")
                : "stage NOT IN ('autoTag','tagGroups')"),
        revision,
        old);
    for (int i = 0; i < STAGES.size(); i++) {
      String stage = STAGES.get(i);
      boolean preserve =
          metadata
              ? !stage.equals(from) && !(from.equals("autoTag") && stage.equals("tagGroups"))
              : List.of("autoTag", "tagGroups").contains(stage)
                  || i < first
                  || (first == 2 && stage.equals("summarise"));
      String state =
          preserve
              ? jdbc.queryForObject(
                  "SELECT state FROM information_steps WHERE revision_id=? AND generation=? AND stage=?",
                  String.class,
                  revision,
                  old,
                  stage)
              : "pending";
      if (code && List.of("summarise", "summary_embed").contains(stage)) state = "skipped";
      if ((metadata ? i < 2 : i < first) && !List.of("ready", "skipped").contains(state))
        throw new CallerFault("complete earlier stages before rebuilding a downstream projection");
      jdbc.update(
          "INSERT INTO information_steps(revision_id,generation,stage,state) VALUES(?,?,?,?)",
          revision,
          old + 1,
          stage,
          state);
      if (preserve)
        jdbc.update(
            "UPDATE information_steps next SET fingerprint=prior.fingerprint FROM information_steps prior WHERE next.revision_id=? AND next.generation=? AND next.stage=? AND prior.revision_id=next.revision_id AND prior.generation=? AND prior.stage=next.stage",
            revision,
            old + 1,
            stage,
            old);
    }
    if (first == 2)
      jdbc.update(
          "UPDATE chunks SET embedding=NULL WHERE paragraph_id IN (SELECT id FROM paragraphs WHERE document_id=?)",
          revision);
    if (first == 3) {
      jdbc.update("UPDATE paragraphs SET summary=NULL WHERE document_id=?", revision);
      jdbc.update("UPDATE sections SET summary=NULL WHERE document_id=?", revision);
      jdbc.update("UPDATE chapters SET summary=NULL WHERE document_id=?", revision);
      jdbc.update("UPDATE documents SET summary=NULL WHERE id=?", revision);
    }
    if (from.equals("autoTag"))
      jdbc.update(
          "UPDATE information_revisions SET auto_tag='[]'::jsonb,auto_tag_generated=false,auto_tag_requested=true,document_author=NULL,document_author_source=NULL,document_author_evidence=NULL WHERE id=?",
          revision);
    if (metadata)
      jdbc.update(
          "UPDATE information_revisions SET auto_tag_groups='{}'::jsonb,tag_groups_input_tags='[]'::jsonb,tag_groups_generated=false WHERE id=?",
          revision);
    else jdbc.update("UPDATE documents SET summary_embedding=NULL WHERE id=?", revision);
    event(revision, old + 1, context.account(), "processing", "rebuild", from);
    return;
  }

  public void allowance(InformationContext context, UUID revision, int total) {
    if (total < 1) throw new IllegalArgumentException("allowance must be positive");

    lockOwner(context.account(), revision);
    jdbc.queryForMap("SELECT id FROM information_revisions WHERE id=? FOR UPDATE", revision);
    if (total < row(revision).allowanceSpent())
      throw new CallerFault("allowance cannot be lowered below actual spending");
    jdbc.update("UPDATE information_revisions SET allowance_total=? WHERE id=?", total, revision);
    jdbc.update(
        "UPDATE conversations SET budget_total=? WHERE id=(SELECT processing_log FROM information_revisions WHERE id=?)",
        total,
        revision);
    event(
        revision,
        generation(revision),
        context.account(),
        "processing",
        "allowance",
        Integer.toString(total));
    return;
  }

  public void cancelProcessing(InformationContext context, UUID revision) {
    lockOwner(context.account(), revision);
    long prior = generation(revision);
    jdbc.update("UPDATE information_revisions SET generation=generation+1 WHERE id=?", revision);
    for (String stage : STAGES)
      jdbc.update(
          "INSERT INTO information_steps(revision_id,generation,stage,state)"
              + " SELECT ?,?,?,coalesce((SELECT CASE WHEN state='ready' THEN 'ready' ELSE 'cancelled' END"
              + " FROM information_steps WHERE revision_id=? AND generation=? AND stage=?),'cancelled')",
          revision,
          prior + 1,
          stage,
          revision,
          prior,
          stage);
    jdbc.update(
        "UPDATE information_steps SET state='cancelled',lease_token=NULL,lease_until=NULL"
            + " WHERE revision_id=? AND state IN ('pending','running')",
        revision);
    event(revision, generation(revision), context.account(), "processing", "cancelled", "");
  }

  public void callerSession(Admission admission, String session) {

    if (admission.created() && session != null)
      jdbc.update(
          "UPDATE information_revisions SET caller_session=? WHERE id=? AND caller_session IS NULL",
          session,
          admission.revision());
  }

  public void tagGroups(UUID resource, Map<String, List<String>> groups) {
    if (groups != null) groups = InformationTagGroups.from(groups, null);
    changed(
        jdbc.update(
            "UPDATE information_resources SET tag_groups=CAST(? AS jsonb),tag_groups_manual=? WHERE id=?",
            canonical(groups == null ? Map.of() : groups),
            groups != null,
            resource));
  }

  public void tags(UUID resource, List<String> tags) {
    var checked = InformationFacets.tags(tags);
    changed(
        jdbc.update(
            "UPDATE information_resources SET tags=CAST(? AS jsonb) WHERE id=?",
            canonical(checked),
            resource));
  }

  public Command reserve(
      InformationContext context, UUID revision, UUID request, String fingerprint) {
    Objects.requireNonNull(request);
    if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("invalid command identity");

    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
        Boolean.class,
        context.account() + ":command:" + request);
    var rows =
        jdbc.queryForList(
            "SELECT * FROM information_commands WHERE account=? AND request_id=? FOR UPDATE",
            context.account(),
            request);
    UUID token = UUID.randomUUID();
    if (!rows.isEmpty()) {
      var prior = rows.getFirst();
      if (!fingerprint.equals(prior.get("fingerprint")))
        throw new CallerFault("requestId was already used for a different information command");
      String state = (String) prior.get("state");
      if (state.equals("completed"))
        return new Command(request, (UUID) prior.get("token"), true, true);
      if (List.of("blocked", "failed").contains(state))
        throw new CallerFault((String) prior.get("error"));
      if (state.equals("applied")) {
        if (((java.sql.Timestamp) prior.get("lease_until")).toInstant().isAfter(clock.instant()))
          throw new CallerFault(
              "information command is still completing; retry the same requestId");
        jdbc.update(
            "UPDATE information_commands SET token=?,lease_until=? WHERE account=? AND request_id=?",
            token,
            clock.instant().plusSeconds(300).atOffset(java.time.ZoneOffset.UTC),
            context.account(),
            request);
        return new Command(request, token, true, false);
      }
      if (((java.sql.Timestamp) prior.get("lease_until")).toInstant().isAfter(clock.instant()))
        throw new CallerFault("information command is still in progress; retry the same requestId");
      jdbc.update(
          "UPDATE information_commands SET token=?,lease_until=? WHERE account=? AND request_id=?",
          token,
          clock.instant().plusSeconds(300).atOffset(java.time.ZoneOffset.UTC),
          context.account(),
          request);
    } else
      jdbc.update(
          "INSERT INTO information_commands(account,request_id,revision_id,fingerprint,state,token,lease_until) VALUES(?,?,?,?,'reserved',?,?)",
          context.account(),
          request,
          revision,
          fingerprint,
          token,
          clock.instant().plusSeconds(300).atOffset(java.time.ZoneOffset.UTC));
    return new Command(request, token, false, false);
  }

  public void commandState(
      InformationContext context, Command command, CommandState state, String error) {

    if (command != null)
      changed(
          jdbc.update(
              "UPDATE information_commands SET state=?,error=? WHERE account=? AND request_id=? AND token=?",
              state.name().toLowerCase(java.util.Locale.ROOT),
              error,
              context.account(),
              command.request(),
              command.token()));
  }

  public void lockReserved(InformationContext context, Command command) {
    if (command != null
        && jdbc.queryForList(
                "SELECT request_id FROM information_commands WHERE account=? AND request_id=? AND token=? AND state='reserved' AND lease_until>=? FOR UPDATE",
                context.account(),
                command.request(),
                command.token(),
                clock.instant().atOffset(java.time.ZoneOffset.UTC))
            .isEmpty())
      throw new CallerFault("information command lease expired; retry the same requestId");
  }

  public UUID recordEvidence(
      InformationContext context,
      UUID revision,
      int start,
      int end,
      String quote,
      String locator,
      UUID request) {

    if (!readable(context, revision)) throw absent();
    String text = text(revision);
    if (start < 0
        || end <= start
        || end > text.length()
        || !text.substring(start, end).equals(quote))
      throw new CallerFault(
          "evidence must quote the retained extracted text at the supplied offsets");
    if (!"extracted-text:utf16".equals(locator))
      throw new CallerFault(
          "evidence locator must be extracted-text:utf16; offsets name the immutable retained extraction");
    if (request != null) {
      jdbc.queryForObject(
          "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
          Boolean.class,
          context.account() + ":evidence:" + request);
      var prior =
          jdbc.queryForList(
              "SELECT id,revision_id,start_offset,end_offset,quote,locator FROM information_evidence WHERE owner_handle=? AND request_id=?",
              context.account(),
              request);
      if (!prior.isEmpty()) {
        var row = prior.getFirst();
        if (!revision.equals(row.get("revision_id"))
            || start != ((Number) row.get("start_offset")).intValue()
            || end != ((Number) row.get("end_offset")).intValue()
            || !quote.equals(row.get("quote"))
            || !locator.equals(row.get("locator")))
          throw new CallerFault("requestId was already used for different evidence");
        return (UUID) row.get("id");
      }
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_evidence(id,revision_id,start_offset,end_offset,quote,locator,owner_handle,request_id)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        revision,
        start,
        end,
        quote,
        locator,
        context.account(),
        request);
    return id;
  }

  public void recordReport(
      Admission result,
      UUID feedback,
      io.aeyer.plowshare.protocol.InformationReportDetails details,
      String producer,
      List<UUID> evidence) {
    String hash =
        producer == null
            ? null
            : jdbc
                .queryForList(
                    "SELECT definition_hash FROM orchestrations WHERE conductor_conversation=?",
                    String.class,
                    producer)
                .stream()
                .findFirst()
                .orElse(null);
    jdbc.update(
        "INSERT INTO information_reports(revision_id,feedback_revision,details,produced_by,definition_hash) VALUES(?,?,CAST(? AS jsonb),?,?)",
        result.revision(),
        feedback,
        canonical(details),
        producer,
        hash);
    for (UUID id : evidence)
      jdbc.update("INSERT INTO information_report_citations VALUES(?,?)", result.revision(), id);
  }

  public void finalise(
      InformationContext context, UUID revision, Map<String, String> fingerprints) {

    lockOwner(context.account(), revision);
    if (!readable(context, revision)) throw absent();
    if (jdbc.queryForObject(
            "SELECT count(*) FROM information_steps WHERE revision_id=? AND generation=? AND stage NOT IN ('autoTag','tagGroups') AND state='ready'",
            Integer.class,
            revision,
            generation(revision))
        != 5) throw new CallerFault("a final report must complete processing first");
    for (var step :
        jdbc.queryForList(
            "SELECT stage,fingerprint FROM information_steps WHERE revision_id=? AND generation=? AND stage NOT IN ('autoTag','tagGroups')",
            revision,
            generation(revision))) {
      String expected = fingerprints.get((String) step.get("stage"));
      if (expected != null && !expected.equals(step.get("fingerprint")))
        throw new CallerFault(
            "a final report needs projections compatible with the configured pipeline; explicitly rebuild incompatible projections");
    }
    jdbc.update(
        "UPDATE information_reports SET status='superseded' WHERE status='final' AND revision_id IN"
            + " (SELECT older.id FROM information_revisions older JOIN information_revisions current ON older.resource_id=current.resource_id WHERE current.id=? AND older.id<>?)",
        revision,
        revision);
    if (jdbc.update(
            "UPDATE information_reports SET status='final',finalised_at=? WHERE revision_id=? AND status='draft'",
            clock.instant().atOffset(java.time.ZoneOffset.UTC),
            revision)
        != 1) throw new CallerFault("this revision is not a draft report");
    event(revision, generation(revision), context.account(), "report", "finalised", "");
    return;
  }

  private static void changed(int count) {
    if (count != 1) throw new CallerFault("information transition lost its row or lease");
  }
}
