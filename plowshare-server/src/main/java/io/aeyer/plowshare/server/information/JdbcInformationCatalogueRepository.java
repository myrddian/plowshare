package io.aeyer.plowshare.server.information;

import static io.aeyer.plowshare.server.information.InformationCatalogue.STAGES;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.DocumentType;
import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.faults.*;
import io.aeyer.plowshare.server.information.InformationCatalogue.Admission;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns catalogue row codecs and fixed permission-scoped queries. */
public final class JdbcInformationCatalogueRepository implements InformationCatalogueRepository {
  private final JdbcTemplate jdbc;
  private final AllowanceRepository allowances;
  private final Clock clock;
  private final ProjectionCache<Optional<AuthorizedMetadata>> metadataCache;
  private final ProjectionCache<Optional<AuthorizedText>> textCache;
  private final ProjectionCache<List<Information.Revision>> listCache;
  private final ProjectionCache<Information.Facets> facetsCache;
  private final InformationEvents events;
  private final ProjectionCache<java.util.Map<UUID, ExtractionState>> readinessCache;
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  /**
   * Every constructor requires an event publisher, because every catalogue mutation announces
   * itself. There is deliberately no overload that leaves it out: a composition that cannot
   * announce a change would stop invalidating what readers hold, silently, and a default chosen
   * here would be that composition's excuse.
   */
  public JdbcInformationCatalogueRepository(
      JdbcTemplate jdbc, Clock clock, InformationEvents events) {
    this(jdbc, clock, new JdbcAllowanceRepository(jdbc), events);
  }

  public JdbcInformationCatalogueRepository(
      JdbcTemplate jdbc, Clock clock, AllowanceRepository allowances, InformationEvents events) {
    this(jdbc, clock, allowances, ProjectionCache.direct(), ProjectionCache.direct(), events);
  }

  public JdbcInformationCatalogueRepository(
      JdbcTemplate jdbc,
      Clock clock,
      AllowanceRepository allowances,
      ProjectionCache<Optional<AuthorizedMetadata>> metadataCache,
      ProjectionCache<Optional<AuthorizedText>> textCache,
      InformationEvents events) {
    this(
        jdbc,
        clock,
        allowances,
        metadataCache,
        textCache,
        ProjectionCache.direct(),
        ProjectionCache.direct(),
        events);
  }

  public JdbcInformationCatalogueRepository(
      JdbcTemplate jdbc,
      Clock clock,
      AllowanceRepository allowances,
      ProjectionCache<Optional<AuthorizedMetadata>> metadataCache,
      ProjectionCache<Optional<AuthorizedText>> textCache,
      ProjectionCache<List<Information.Revision>> listCache,
      ProjectionCache<Information.Facets> facetsCache,
      InformationEvents events) {
    this(
        jdbc,
        clock,
        allowances,
        metadataCache,
        textCache,
        listCache,
        facetsCache,
        ProjectionCache.direct(),
        events);
  }

  public JdbcInformationCatalogueRepository(
      JdbcTemplate jdbc,
      Clock clock,
      AllowanceRepository allowances,
      ProjectionCache<Optional<AuthorizedMetadata>> metadataCache,
      ProjectionCache<Optional<AuthorizedText>> textCache,
      ProjectionCache<List<Information.Revision>> listCache,
      ProjectionCache<Information.Facets> facetsCache,
      ProjectionCache<java.util.Map<UUID, ExtractionState>> readinessCache,
      InformationEvents events) {
    this.readinessCache = Objects.requireNonNull(readinessCache);
    this.events = Objects.requireNonNull(events);
    this.jdbc = Objects.requireNonNull(jdbc);
    this.clock = Objects.requireNonNull(clock);
    this.allowances = Objects.requireNonNull(allowances);
    this.metadataCache = Objects.requireNonNull(metadataCache);
    this.textCache = Objects.requireNonNull(textCache);
    this.listCache = Objects.requireNonNull(listCache);
    this.facetsCache = Objects.requireNonNull(facetsCache);
  }

  private static NotFoundFault absent() {
    return new NotFoundFault("information is unavailable in this selection");
  }

  private static <T> T decode(Map<String, Object> input, Class<T> type) {
    var row = new LinkedHashMap<String, Object>(input);
    row.replaceAll(
        (key, value) ->
            value instanceof java.sql.Timestamp time ? time.toInstant().toString() : value);
    for (String key : List.of("tags", "autoTag", "tagGroups", "details", "contentType"))
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
            "SELECT r.id,r.resource_id,r.document_type,r.document_subtype,r.availability,r.extracted_text,q.owner_handle,r.generation,r.allowance_spent FROM information_revision_allowances r JOIN information_resources q ON q.id=r.resource_id WHERE r.id=?",
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

  public Resource lockResource(InformationContext context, UUID revision, boolean audience) {
    if (audience)
      jdbc.queryForObject(
          "SELECT pg_advisory_xact_lock(hashtextextended('information-navigation-control',0)) IS NULL",
          Boolean.class);
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT information_lock_knowledge(?,?,?,?,?,?)",
            Boolean.class,
            revision,
            context.account(),
            context.selection().scope().name().toLowerCase(Locale.ROOT),
            context.selection().project(),
            context.selection().includeGlobal(),
            audience ? "SET_AUDIENCE" : "WRITE"))) throw absent();
    return jdbc.queryForObject(
        "SELECT q.id,q.namespace,q.source_name,q.owner_handle,q.project_id,p.name,q.home_scope_id,q.created_by FROM information_resources q JOIN information_revision_allowances r ON r.resource_id=q.id LEFT JOIN projects p ON p.id=q.project_id WHERE r.id=?",
        (rs, n) ->
            new Resource(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                (Long) rs.getObject(5),
                rs.getString(6),
                new io.aeyer.plowshare.server.access.AuthorityIds.ScopeId(
                    rs.getObject(7, UUID.class)),
                Optional.ofNullable(rs.getObject(8, UUID.class))
                    .map(io.aeyer.plowshare.server.access.AuthorityIds.AccountId::new)),
        revision);
  }

  public boolean readable(InformationContext context, UUID revision) {
    var args = new ArrayList<Object>(selectionArgs(context));
    args.add(revision);
    return !jdbc.queryForList(
            "SELECT 1 FROM information_revision_allowances r WHERE "
                + readableSql(context)
                + " AND r.id=?",
            Integer.class,
            args.toArray())
        .isEmpty();
  }

  @Override
  public java.util.Map<UUID, ExtractionState> extractionStates(
      InformationContext context, List<UUID> revisions) {
    if (revisions == null || revisions.size() > 100)
      throw new io.aeyer.plowshare.server.faults.CallerFault("Readiness has at most 100 revisions");
    var ids = List.copyOf(revisions).stream().distinct().sorted().toList();
    if (ids.isEmpty()) return java.util.Map.of();
    if (!readinessCache.enabled()) return loadExtractionStates(context, ids, null);
    long available = readinessCache.maxWeight() - contextWeight(context) - 1024L * ids.size();
    long bound = Math.max(0, available / (4L * ids.size()));
    return readinessCache.read(
        new SnapshotCache.Key(
            "readiness",
            ProjectionCache.fingerprint(InformationJson.json(context), InformationJson.json(ids))),
        bound > 0,
        () -> loadExtractionStates(context, ids, bound),
        () -> loadExtractionStates(context, ids, null),
        value ->
            contextWeight(context)
                + 4L * InformationJson.json(value).length()
                + 1024L * value.size());
  }

  private java.util.Map<UUID, ExtractionState> loadExtractionStates(
      InformationContext context, List<UUID> ids, Long maximumBytes) {
    var boundary = context.readBoundary();
    var args =
        new ArrayList<Object>(
            java.util.Arrays.asList(
                context.account(),
                context.selection().scope().name().toLowerCase(Locale.ROOT),
                context.selection().project(),
                context.selection().includeGlobal(),
                boundary == null ? null : boundary.home().project(),
                boundary == null ? null : boundary.globalRead(),
                "{"
                    + ids.stream()
                        .map(UUID::toString)
                        .collect(java.util.stream.Collectors.joining(","))
                    + "}"));
    String bytes = "(octet_length(coalesce(r.source_uri,''))+octet_length(coalesce(s.error,'')))";
    String fits = maximumBytes == null ? "true" : bytes + "<=?";
    if (maximumBytes != null) {
      args.add(maximumBytes);
      args.add(maximumBytes);
    }
    var content = InformationSql.content(context, "r");
    args.addAll(content.arguments());
    var rows =
        jdbc.query(
            "WITH admitted AS MATERIALIZED (SELECT revision FROM knowledge_readable_targets(?,?,?,?,?,?,?::uuid[],NULL,false,NULL,NULL,NULL)) "
                + "SELECT r.id,r.generation,CASE WHEN "
                + fits
                + " THEN r.source_uri END AS source_uri,s.stage,s.state,s.attempt,"
                + "CASE WHEN "
                + fits
                + " THEN s.error END AS error,s.started_at,s.finished_at,"
                + bytes
                + " AS projection_bytes "
                + "FROM admitted JOIN information_revisions r ON r.id=admitted.revision "
                + "LEFT JOIN information_steps s ON s.revision_id=r.id AND s.generation=r.generation AND s.stage='extract' WHERE "
                + content.sql(),
            (row, index) -> {
              if (maximumBytes != null && row.getLong("projection_bytes") > maximumBytes)
                throw new ProjectionCache.Bypass();
              return new ExtractionState(
                  row.getObject("id", UUID.class),
                  row.getLong("generation"),
                  row.getString("source_uri"),
                  row.getString("stage") == null
                      ? null
                      : new Information.Step(
                          row.getString("stage"),
                          row.getString("state"),
                          row.getInt("attempt"),
                          row.getString("error"),
                          row.getLong("generation"),
                          null,
                          row.getObject("started_at", java.time.OffsetDateTime.class) == null
                              ? null
                              : row.getObject("started_at", java.time.OffsetDateTime.class)
                                  .toInstant(),
                          row.getObject("finished_at", java.time.OffsetDateTime.class) == null
                              ? null
                              : row.getObject("finished_at", java.time.OffsetDateTime.class)
                                  .toInstant(),
                          null));
            },
            args.toArray());
    return rows.stream()
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                ExtractionState::revision, value -> value));
  }

  public Optional<AuthorizedMetadata> authorizedMetadata(
      InformationContext context, UUID revision) {
    if (!metadataCache.enabled()) return loadMetadata(context, revision, null);
    long bound = projectionBound(metadataCache.maxWeight(), context, 64);
    if (!readable(context, revision)) return Optional.empty();
    return metadataCache.read(
        readKey(context, revision),
        bound > 0,
        () -> loadMetadata(context, revision, bound),
        () -> loadMetadata(context, revision, null),
        JdbcInformationCatalogueRepository::metadataWeight);
  }

  private Optional<AuthorizedMetadata> loadMetadata(
      InformationContext context, UUID revision, Long maximumBytes) {
    var args = new ArrayList<Object>(selectionArgs(context));
    args.add(revision);
    String sql = SELECT + " WHERE " + readableSql(context) + " AND r.id=?";
    if (maximumBytes != null) {
      sql =
          "WITH projected AS MATERIALIZED ("
              + sql
              + ") SELECT * FROM projected WHERE octet_length(to_jsonb(projected)::text)<=?";
      args.add(maximumBytes);
    }
    var rows = jdbc.queryForList(sql, args.toArray());
    // A source may grow, disappear or lose readability after preflight. Never cache a truncated
    // projection or a false negative: retry the original current SQL outside shared admission.
    if (rows.isEmpty() && maximumBytes != null) throw new ProjectionCache.Bypass();
    return rows.stream()
        .map(row -> new AuthorizedMetadata(context, decode(row, Information.Revision.class)))
        .findFirst();
  }

  public Optional<AuthorizedText> authorizedText(InformationContext context, UUID revision) {
    if (!textCache.enabled()) return loadText(context, revision, null);
    long bound = projectionBound(textCache.maxWeight(), context, 4);
    var sizes =
        jdbc.queryForList(
            "SELECT "
                + TEXT_BYTES
                + " FROM information_revision_allowances r WHERE "
                + readableSql(context)
                + " AND r.id=?",
            Long.class,
            readArguments(context, revision));
    if (sizes.isEmpty()) return Optional.empty();
    return textCache.read(
        readKey(context, revision),
        bound > 0 && sizes.getFirst() <= bound,
        () -> loadText(context, revision, bound),
        () -> loadText(context, revision, null),
        JdbcInformationCatalogueRepository::textWeight);
  }

  private Optional<AuthorizedText> loadText(
      InformationContext context, UUID revision, Long maximumBytes) {
    var args = new ArrayList<Object>(selectionArgs(context));
    args.add(revision);
    if (maximumBytes != null) args.add(maximumBytes);
    var rows =
        jdbc.query(
            "SELECT r.id,r.resource_id,r.document_type,r.document_subtype,r.content_hash,r.text_hash,r.generation,r.extracted_text FROM information_revision_allowances r WHERE "
                + readableSql(context)
                + " AND r.id=?"
                + (maximumBytes == null ? "" : " AND " + TEXT_BYTES + "<=?"),
            (rs, n) ->
                new AuthorizedText(
                    context,
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getLong(7),
                    rs.getString(8)),
            args.toArray());
    if (rows.isEmpty() && maximumBytes != null) throw new ProjectionCache.Bypass();
    return rows.stream().findFirst();
  }

  private static final String TEXT_BYTES =
      "COALESCE(octet_length(r.extracted_text),0)::bigint+octet_length(r.content_hash)"
          + "+COALESCE(octet_length(r.text_hash),0)+octet_length(r.document_type)+octet_length(r.document_subtype)";

  private static Object[] readArguments(InformationContext context, UUID revision) {
    var args = new ArrayList<Object>(selectionArgs(context));
    args.add(revision);
    return args.toArray();
  }

  private static SnapshotCache.Key readKey(InformationContext context, UUID revision) {
    return new SnapshotCache.Key(
        revision.toString(), ProjectionCache.fingerprint(InformationJson.json(context)));
  }

  private static long contextWeight(InformationContext context) {
    return 4096L + 4L * InformationJson.json(context).length();
  }

  private static long projectionBound(
      long maximumWeight, InformationContext context, int expansion) {
    long available = maximumWeight - contextWeight(context) - 4096;
    return available <= 0 ? 0 : available / expansion;
  }

  private static long stringsWeight(String... values) {
    long weight = 0;
    for (String value : values) if (value != null) weight += 64L + 4L * value.length();
    return weight;
  }

  private static long textWeight(Optional<AuthorizedText> loaded) {
    if (loaded.isEmpty()) return 64;
    var value = loaded.orElseThrow();
    return contextWeight(value.context())
        + stringsWeight(
            value.documentType(),
            value.documentSubtype(),
            value.contentHash(),
            value.textHash(),
            value.text());
  }

  private static long metadataWeight(Optional<AuthorizedMetadata> loaded) {
    if (loaded.isEmpty()) return 64;
    var value = loaded.orElseThrow();
    var r = value.revision();
    long weight =
        contextWeight(value.context())
            + stringsWeight(
                r.title(),
                r.mediaType(),
                r.documentType(),
                r.documentSubtype(),
                r.sourceUri(),
                r.contentHash(),
                r.textHash(),
                r.availability(),
                r.converter(),
                r.sourceName(),
                r.kind(),
                r.author(),
                r.documentAuthor(),
                r.documentAuthorSource(),
                r.tagGroupsSource(),
                r.reportStatus(),
                r.processingPolicy());
    for (String tag : r.tags()) weight += stringsWeight(tag);
    if (r.autoTag() != null) for (String tag : r.autoTag()) weight += stringsWeight(tag);
    if (r.tagGroups() != null)
      for (var group : r.tagGroups().entrySet()) {
        weight += 128 + stringsWeight(group.getKey());
        for (String tag : group.getValue()) weight += stringsWeight(tag);
      }
    if (r.contentType() != null)
      weight +=
          256
              + stringsWeight(
                  r.contentType().slug(),
                  r.contentType().label(),
                  r.contentType().definitionHash());
    return weight;
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

  /**
   * One revision's own event history, owner-only, read by revision and never by cursor. {@code
   * sequence} is the Relay position the publication was given, so this history and {@code
   * information.events} name the same event by the same number.
   */
  public List<Information.Event> revisionEvents(UUID revision) {
    return jdbc
        .queryForList(
            "SELECT relay_position AS sequence,generation,stage,action,detail,recorded_at"
                + " FROM information_revision_events WHERE revision_id=?"
                + " ORDER BY recorded_at,relay_project,relay_position",
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
        "SELECT extracted_text FROM information_revision_allowances WHERE id=?",
        String.class,
        revision);
  }

  public byte[] bytes(UUID revision) {
    return jdbc.queryForObject(
        "SELECT source_bytes FROM information_revision_allowances WHERE id=?",
        byte[].class,
        revision);
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
            "SELECT r.id FROM information_revision_allowances r JOIN information_resources q ON q.id=r.resource_id WHERE "
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
    var reader = new JdbcInformationFacets(jdbc, facetsCache);
    // The run's complete pin list remains part of selection. Oversized pin bookkeeping bypasses
    // sharing rather than shortening the key or changing the existing SQL intersection.
    if (!facetsCache.enabled() || pinned != null && pinned.size() > 1024)
      return reader.read(selected, JdbcInformationFacets.Candidates.REVISIONS, context.typedView());
    return reader.read(
        selected,
        JdbcInformationFacets.Candidates.REVISIONS,
        context.typedView(),
        InformationCatalogueFingerprint.readKey(
            "catalogue-facets-v1", context, Arrays.asList(kind, pinned)));
  }

  public List<Information.Revision> list(
      InformationContext context, int limit, int offset, String kind) {
    if (offset < 0) throw new CallerFault("offset must be nonnegative");
    if (kind != null && !List.of("source", "report").contains(kind))
      throw new CallerFault("kind must be source or report");
    var selected = discovery(context, kind);
    int applied = Math.max(1, Math.min(limit, 100));
    if (!listCache.enabled()) return loadList(selected, applied, offset, null);
    long maximum = listCache.maxWeight() <= 4096 ? 0 : (listCache.maxWeight() - 4096) / 32;
    return listCache.read(
        InformationCatalogueFingerprint.readKey(
            "catalogue-list-v1", context, Arrays.asList(kind, applied, offset)),
        maximum > 0,
        () -> loadList(selected, applied, offset, maximum),
        () -> loadList(selected, applied, offset, null),
        revisions -> {
          long weight = 4096;
          for (var revision : revisions)
            weight += metadataWeight(Optional.of(new AuthorizedMetadata(context, revision)));
          return weight;
        });
  }

  private List<Information.Revision> loadList(
      InformationSql.Filter selected, int limit, int offset, Long maximumBytes) {
    var args = new ArrayList<Object>(selected.arguments());
    args.add(limit);
    args.add(offset);
    String sql =
        SELECT + " WHERE " + selected.sql() + " ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?";
    if (maximumBytes == null)
      return jdbc.queryForList(sql, args.toArray()).stream()
          .map(row -> decode(row, Information.Revision.class))
          .toList();
    // Limit/page first, then bound the complete selected projection. The left-join sentinel never
    // transfers oversized text and still represents an empty page without a second query.
    sql =
        "WITH catalogue_page AS MATERIALIZED ("
            + sql
            + "), page_bounds AS (SELECT count(*) AS cache_rows,"
            + "COALESCE(sum(256::bigint+octet_length(to_jsonb(catalogue_page)::text)),0) AS cache_bytes FROM catalogue_page)"
            + " SELECT catalogue_page.*,page_bounds.cache_rows,page_bounds.cache_bytes FROM page_bounds LEFT JOIN catalogue_page"
            + " ON page_bounds.cache_bytes<=? ORDER BY catalogue_page.created_at DESC,catalogue_page.id";
    args.add(maximumBytes);
    var rows = jdbc.queryForList(sql, args.toArray());
    var revisions = new ArrayList<Information.Revision>();
    for (var row : rows) {
      if (((Number) row.get("cache_bytes")).longValue() > maximumBytes)
        throw new ProjectionCache.Bypass();
      if (((Number) row.get("cache_rows")).longValue() == 0) continue;
      var value = new LinkedHashMap<String, Object>(row);
      value.remove("cache_bytes");
      value.remove("cache_rows");
      revisions.add(decode(value, Information.Revision.class));
    }
    return List.copyOf(revisions);
  }

  private io.aeyer.plowshare.server.information.InformationSql.Filter discovery(
      InformationContext context, String kind) {
    if (kind != null && !List.of("source", "report").contains(kind))
      throw new CallerFault("kind must be source or report");
    var facets =
        io.aeyer.plowshare.server.information.InformationFacetSql.facets(
            context.facets(), "r", "q");
    var selected = InformationSelectionSql.catalogue(context, kind);
    var content = InformationSql.content(context, "r");
    var args = new ArrayList<Object>(selected.arguments());
    args.addAll(content.arguments());
    args.addAll(facets.arguments());
    return new io.aeyer.plowshare.server.information.InformationSql.Filter(
        selected.sql()
            + " AND "
            + content.sql()
            + " AND NOT EXISTS(SELECT 1 FROM information_reports report WHERE report.revision_id=r.id AND report.status NOT IN ('draft','final')) AND "
            + facets.sql(),
        args);
  }

  public List<Information.Revision> inventory(InformationContext context, int limit, int offset) {
    if (limit < 1 || limit > 100 || offset < 0)
      throw new CallerFault("inventory needs limit 1..100 and nonnegative offset");
    String project = context.selection().project();
    if (context.selection().scope() == InformationContext.Scope.GLOBAL_READ) return List.of();
    return jdbc
        .queryForList(
            SELECT
                + " WHERE information_knowledge_allows(r.id,?,?,?,?, 'WRITE')"
                + " AND r.document_type=? AND "
                + InformationSql.content(context, "r").sql()
                + " ORDER BY r.created_at DESC,r.id OFFSET ? LIMIT ?",
            context.account(),
            context.selection().scope().name().toLowerCase(Locale.ROOT),
            project,
            context.selection().includeGlobal(),
            context.corpus().documentType(),
            context.typedView(),
            offset,
            limit)
        .stream()
        .map(row -> decode(row, Information.Revision.class))
        .toList();
  }

  /**
   * Reads one page of this account's own durable Relay subscription (decision 0037).
   *
   * <p>The {@code sequence > cursor} scan this replaced read a global {@code BIGSERIAL}, which is
   * why its writer needed a commit-order fence: a sequence maximum is not commit order, so a reader
   * could advance past a lower sequence that committed later and lose it. A Relay topic's positions
   * are gapless and commit-ordered by construction, so the whole question disappears with the
   * cursor.
   */
  public Information.Events events(InformationContext context, long after, int limit) {
    return events.read(context, after, limit);
  }

  public Information.Evidence evidence(InformationContext context, UUID id) {
    var args = new ArrayList<Object>(selectionArgs(context));
    args.add(id);
    List<Map<String, Object>> found =
        jdbc.queryForList(
            "SELECT e.id,e.revision_id,e.paragraph_id,e.start_offset,e.end_offset,e.quote,e.locator,e.created_at FROM information_evidence e JOIN information_revision_allowances r ON r.id=e.revision_id JOIN information_resources q ON q.id=r.resource_id WHERE "
                + readableSql(context)
                + " AND e.id=?",
            args.toArray());
    if (found.isEmpty()) throw absent();
    return decode(found.getFirst(), Information.Evidence.class);
  }

  @Override
  public Map<UUID, io.aeyer.plowshare.server.documents.CodeOutline> codeStates(
      InformationContext context, Map<UUID, String> hashes) {
    if (hashes == null || hashes.size() > 100)
      throw new IllegalArgumentException("Code status has at most 100 revisions");
    if (hashes.isEmpty()) return Map.of();
    var ids = hashes.keySet().stream().sorted().toList();
    var boundary = context.readBoundary();
    var args =
        new ArrayList<Object>(
            Arrays.asList(
                context.account(),
                context.selection().scope().name().toLowerCase(Locale.ROOT),
                context.selection().project(),
                context.selection().includeGlobal(),
                boundary == null ? null : boundary.home().project(),
                boundary == null ? null : boundary.globalRead(),
                "{"
                    + ids.stream()
                        .map(UUID::toString)
                        .collect(java.util.stream.Collectors.joining(","))
                    + "}"));
    var content = InformationSql.content(context, "r");
    args.add(io.aeyer.plowshare.server.documents.CodeOutline.VERSION);
    args.addAll(content.arguments());
    var result = new HashMap<UUID, io.aeyer.plowshare.server.documents.CodeOutline>();
    jdbc.query(
        "WITH admitted AS MATERIALIZED (SELECT revision FROM knowledge_readable_targets(?,?,?,?,?,?,?::uuid[],NULL,false,NULL,NULL,NULL)) "
            + "SELECT r.id,r.content_hash,o.status,o.reason,o.language,o.parser_version,o.source_hash "
            + "FROM admitted JOIN information_revisions r ON r.id=admitted.revision JOIN code_outlines o ON o.document_id=r.id "
            + "JOIN information_steps step ON step.revision_id=r.id AND step.generation=r.generation AND step.stage='derive' AND step.state='ready' "
            + "WHERE r.document_type='code' AND NOT r.excluded AND o.parser_version=? AND o.source_hash=r.text_hash AND "
            + content.sql(),
        (org.springframework.jdbc.core.RowCallbackHandler)
            row -> {
              UUID id = row.getObject("id", UUID.class);
              if (Objects.equals(hashes.get(id), row.getString("content_hash")))
                result.put(
                    id,
                    new io.aeyer.plowshare.server.documents.CodeOutline(
                        row.getString("status"),
                        row.getString("reason"),
                        row.getString("language"),
                        row.getString("parser_version"),
                        row.getString("source_hash"),
                        List.of()));
            },
        args.toArray());
    return Map.copyOf(result);
  }

  public io.aeyer.plowshare.server.documents.CodeProjection codeProjection(
      InformationContext context, UUID revision, String rawHash) {
    var filter = InformationSql.read(context, "r");
    var args = new ArrayList<Object>();
    args.add(revision);
    args.addAll(filter.arguments());
    args.add(rawHash);
    args.add(io.aeyer.plowshare.server.documents.CodeOutline.VERSION);
    // One authorized relation feeds both metadata and symbols. JSON is only a JDBC-boundary
    // encoding, so source text is transferred once instead of once per symbol. The final current
    // READ remains a concurrency fence, not another hydration of this immutable projection.
    var rows =
        jdbc.queryForList(
            "WITH allowed AS MATERIALIZED (SELECT r.id FROM information_revision_allowances r WHERE r.id=? AND "
                + filter.sql()
                + "), "
                + "projection AS MATERIALIZED (SELECT r.id,CASE WHEN length(r.extracted_text)<=1048576 THEN r.extracted_text END AS extracted_text,o.* "
                + "FROM allowed JOIN information_revisions r ON r.id=allowed.id JOIN code_outlines o ON o.document_id=r.id "
                + "JOIN information_steps step ON step.revision_id=r.id AND step.generation=r.generation AND step.stage='derive' AND step.state='ready' "
                + "WHERE r.content_hash=? AND r.document_type='code' AND r.availability='active' AND NOT r.excluded AND o.parser_version=? AND o.source_hash=r.text_hash) "
                + "SELECT EXISTS(SELECT 1 FROM allowed) AS readable,p.*, "
                + "(SELECT coalesce(jsonb_agg(jsonb_build_object('ordinal',s.ordinal,'name',s.name,'kind',s.kind,'qualifiedName',s.qualified_name,"
                + "'parentOrdinal',s.parent_ordinal,'signature',s.signature,'start',s.start_offset,'end',s.end_offset,'startLine',s.start_line,'endLine',s.end_line) ORDER BY s.ordinal),'[]'::jsonb)::text "
                + "FROM (SELECT * FROM code_symbols WHERE document_id=p.id ORDER BY ordinal LIMIT 10001) s) AS symbols "
                + "FROM (SELECT 1) seed LEFT JOIN projection p ON true",
            args.toArray());
    var row = rows.getFirst();
    if (!Boolean.TRUE.equals(row.get("readable"))) throw absent();
    if (row.get("extracted_text") == null || row.get("status") == null) return null;
    List<io.aeyer.plowshare.server.documents.CodeOutline.Symbol> symbols;
    try {
      symbols =
          JSON.readValue(
              (String) row.get("symbols"),
              new com.fasterxml.jackson.core.type.TypeReference<
                  List<io.aeyer.plowshare.server.documents.CodeOutline.Symbol>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("Invalid retained code symbols", invalid);
    }
    if (symbols.size() > 10000) return null;
    if (!readable(context, revision)) throw absent();
    return new io.aeyer.plowshare.server.documents.CodeProjection(
        revision,
        (String) row.get("extracted_text"),
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
            "SELECT document_type,document_subtype,text_hash FROM information_revision_allowances r WHERE r.id=? AND "
                + filter.sql(),
            arguments.toArray());
    if (sources.isEmpty()) throw absent();
    var source = sources.getFirst();
    if (!"code".equals(source.get("document_type")))
      throw new CallerFault("outline requires a code revision");
    var rows =
        jdbc.queryForList(
            "SELECT source_hash,language,parser_version,status,reason,symbol_count FROM code_outlines o JOIN information_revision_allowances r ON r.id=o.document_id WHERE r.id=? AND "
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
            "SELECT s.ordinal,s.name,s.kind,s.qualified_name,s.parent_ordinal,s.signature,s.start_offset,s.end_offset,s.start_line,s.end_line FROM code_symbols s JOIN information_revision_allowances r ON r.id=s.document_id WHERE r.id=? AND "
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
                  "SELECT document_type FROM information_revision_allowances WHERE id=?",
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
                + " JOIN information_revision_allowances r ON r.id=s.document_id JOIN information_resources q ON q.id=r.resource_id"
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
    return InformationSql.read(context, "r").arguments();
  }

  private static String readableSql(InformationContext context) {
    return "r.availability='active' AND " + InformationSql.read(context, "r").sql();
  }

  private static final String SELECT =
      "SELECT r.id,r.resource_id,r.ordinal,r.title,r.media_type,r.document_type,r.document_subtype,r.source_uri,r.content_hash,r.text_hash,"
          + "r.byte_size,r.created_at,r.availability,r.excluded,r.generation,r.converter,r.allowance_total,r.allowance_spent,q.source_name,q.kind,q.owner_handle AS author,q.tags,q.tags_version AS \"tagsVersion\","
          + io.aeyer.plowshare.server.information.InformationFacetSql.readyTags("r")
          + " AS \"autoTag\",r.auto_tag_generated,"
          + io.aeyer.plowshare.server.information.InformationFacetSql.documentAuthor("r", "q")
          + " AS \"documentAuthor\","
          + io.aeyer.plowshare.server.information.InformationFacetSql.documentAuthorSource("r")
          + " AS \"documentAuthorSource\","
          + InformationFacetSql.visibleGroups("r", "q")
          + " AS \"tagGroups\",CASE WHEN q.tag_groups_manual THEN 'manual' ELSE 'automatic' END AS \"tagGroupsSource\","
          + "(SELECT jsonb_build_object('type',v.type_id,'version',v.id,'ordinal',v.ordinal,'slug',t.slug,'label',t.label,'definitionHash',v.definition_hash) FROM information_revision_content c JOIN information_content_type_versions v ON v.id=c.type_version JOIN information_content_types t ON t.id=v.type_id WHERE c.revision_id=r.id) AS \"contentType\",CASE WHEN r.processing_policy='explicit_only' THEN r.processing_policy END AS \"processingPolicy\","
          + "(SELECT report.status FROM information_reports report WHERE report.revision_id=r.id) AS report_status FROM information_revision_allowances r JOIN information_resources q ON q.id=r.resource_id";

  /**
   * Announces one catalogue change on Relay (decision 0037).
   *
   * <p>No persistence of its own remains here, which is the point. What stood in this method was a
   * second durable log: a {@code pg_advisory_xact_lock} on one key hashed from a fixed name — one
   * key for the whole installation, held to the end of the transaction — an {@code INSERT …
   * RETURNING} against a global {@code BIGSERIAL}, and a recipient fan-out that asked {@code
   * information_readable} once per administrator and once per administrator-project pair inside
   * that critical section. One event with 13 administrators measured 3,996 ms and serialized every
   * other event in the installation for its duration.
   *
   * <p>Relay owns all of it now: its lock is on one topic row in one project, positions are gapless
   * per topic, and the recipients are that topic's subscribers. The revision row lock moved into
   * {@link InformationEventRecords#resolve}, which still takes it first and for the same deadlock
   * reason.
   */
  public void event(
      UUID revision, long generation, String actor, String stage, String action, String detail) {
    events.publish(
        new InformationEvents.Occurrence(revision, generation, actor, stage, action, detail));
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

  /** Creation has no revision to lock yet; validate the exact destination and live role. */
  private void requireDestination(InformationContext context) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT information_lock_destination(?,?,?,?)",
            Boolean.class,
            context.account(),
            context.selection().scope().name().toLowerCase(Locale.ROOT),
            context.selection().project(),
            context.selection().includeGlobal()))) throw absent();
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
    String namespace = context.namespace();
    if (context.selection().scope() == InformationContext.Scope.GLOBAL_READ)
      throw new CallerFault("shared namespace cannot admit source bytes");

    requireDestination(context);

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
                + " WHERE home_scope_id=information_namespace_home(?) AND source_name=? FOR UPDATE",
            namespace,
            name);
    UUID resource;
    if (resources.isEmpty()) {
      if (write.expectedRevision() != null) throw new CallerFault("INFORMATION_REVISION_CONFLICT");
      resource = UUID.randomUUID();
      Long project =
          context.selection().project() == null
              ? null
              : jdbc.queryForObject(
                  "SELECT id FROM projects WHERE name=?",
                  Long.class,
                  context.selection().project());
      jdbc.update(
          "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind,created_by,creator_evidence)"
              + " VALUES(?,?,?,?,?,?,(SELECT id FROM authority_accounts WHERE legacy_handle=?),'authenticated_admission')",
          resource,
          namespace,
          name,
          context.selection().scope() == InformationContext.Scope.ACCOUNT
              ? jdbc.queryForObject(
                  "SELECT legacy_handle FROM authority_accounts WHERE id=?",
                  String.class,
                  context.selection().account())
              : context.account(),
          project,
          kind,
          context.account());
    } else {

      if (!kind.equals(resources.getFirst().get("kind")))
        throw new CallerFault("this name already belongs to a different resource kind");
      resource = (UUID) resources.getFirst().get("id");
      UUID head =
          jdbc.queryForObject(
              "SELECT id FROM information_revision_allowances WHERE resource_id=? ORDER BY ordinal DESC LIMIT 1",
              UUID.class,
              resource);
      lockResource(context, head, false);
      boolean typed =
          Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM information_revision_content WHERE revision_id=?)",
                  Boolean.class,
                  head));
      if (typed && !write.typingExplicit()) throw new CallerFault("INFORMATION_TYPING_REQUIRED");
      if ((write.typingExplicit() || write.expectedRevision() != null)
          && !Objects.equals(head, write.expectedRevision()))
        throw new CallerFault("INFORMATION_REVISION_CONFLICT");
    }
    int ordinal =
        jdbc.queryForObject(
            "SELECT coalesce(max(ordinal),0)+1 FROM information_revision_allowances"
                + " WHERE resource_id=?",
            Integer.class,
            resource);
    UUID revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,source_uri,"
            + "content_hash,byte_size,source_bytes,allowance_total,caller_session,document_type,document_subtype,starting_revision,revised_by,processing_policy,created_by,creator_evidence) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,(SELECT id FROM authority_accounts WHERE legacy_handle=?),'authenticated_admission')",
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
        documentType.subtype(),
        write.expectedRevision(),
        write.typingExplicit() ? context.account() : null,
        write.processingPolicy(),
        context.account());
    if (write.binding() != null) {
      var binding = write.binding();
      jdbc.update(
          "INSERT INTO information_revision_content(revision_id,type_version,profile,definition_hash,metadata_hash,metadata) VALUES(?,?,?,?,?,CAST(? AS jsonb))",
          revision,
          binding.contract().version(),
          binding.contract().profile(),
          binding.contract().definitionHash(),
          binding.metadataHash(),
          io.aeyer.plowshare.protocol.SchemaCodec.compactJson(binding.metadata()));
    }
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

    requireDestination(
        new InformationContext(
            context.account(), InformationAccess.projectSelection(context.account(), project)));
    lockResource(context, revision, false);
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

    // Root policy changes serialize with navigation policy and reference changes. This lock must
    // precede the resource lock; upgrading a content writer's shared lock can deadlock publishers.
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended('information-navigation-control',0)) IS NULL",
        Boolean.class);
    lockResource(context, revision, true);
    if (shared && !readable(context, revision)) throw absent();
    if (shared
        && !Boolean.TRUE.equals(
            jdbc.queryForObject("SELECT information_root_publishable(?)", Boolean.class, revision)))
      throw new CallerFault("Private or restricted dependencies cannot authorize GLOBAL_READ");
    Long version =
        jdbc.queryForObject(
            "SELECT information_set_root_audience(?,?,?,?,?,?)",
            Long.class,
            revision,
            context.account(),
            context.selection().scope().name().toLowerCase(Locale.ROOT),
            context.selection().project(),
            context.selection().includeGlobal(),
            shared);
    if (version == null)
      throw new IllegalStateException("Root policy transition returned no version");
    UUID resource =
        jdbc.queryForObject(
            "SELECT resource_id FROM information_revision_allowances WHERE id=?",
            UUID.class,
            revision);
    // Lock all revisions before the commit-order event fence. Every cached revision needs an
    // invalidation when its root audience changes, including historical and pinned reads.
    var revisions =
        jdbc.query(
            "SELECT id FROM information_revision_allowances WHERE resource_id=? ORDER BY id FOR KEY SHARE",
            (row, index) -> row.getObject(1, UUID.class),
            resource);
    for (UUID affected : revisions)
      event(
          affected,
          generation(affected),
          context.account(),
          "sharing",
          shared ? "shared" : "unshared",
          "root policy version " + version);

    return;
  }

  public void availability(InformationContext context, UUID revision, String state) {
    if (!Set.of("active", "excluded", "included", "withdrawn", "deleted").contains(state))
      throw new IllegalArgumentException("invalid availability");

    lockResource(context, revision, false);
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
            "SELECT availability FROM information_revision_allowances WHERE id=? FOR UPDATE",
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
          "UPDATE information_reuse_preparations prepared SET payload=payload-'binding' WHERE payload->'manifest'->>'revision'=? OR EXISTS(SELECT 1 FROM information_write_gates gate WHERE gate.account=prepared.actor AND gate.request_id=prepared.request_id AND gate.response->>'revision'=?)",
          revision.toString(),
          revision.toString());
      jdbc.update(
          "UPDATE information_acquisitions SET content_intent=jsonb_set(jsonb_set(content_intent,'{binding}', 'null'::jsonb),'{typing,metadata}', '{}'::jsonb),state=CASE WHEN state IN ('queued','running') THEN 'blocked' ELSE state END,error='Retained typing origin deleted',token=NULL,lease_until=NULL WHERE content_intent IS NOT NULL AND (revision_id=? OR content_intent->>'expectedRevision'=?)",
          revision,
          revision.toString());
      jdbc.update(
          "UPDATE information_revision_content SET metadata='{}'::jsonb,redacted=true WHERE revision_id=? AND NOT redacted",
          revision);
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
      // A Relay publication is immutable, so the copy a subscriber may still read expires with
      // the topic's retention rather than being scrubbed. The durable history is scrubbed here,
      // which is where a deleted revision's detail would otherwise outlive the deletion.
      jdbc.update(
          "UPDATE information_revision_events SET detail='[deleted]' WHERE revision_id=?",
          revision);
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

    lockResource(context, revision, false);
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

    lockResource(context, revision, false);
    if (!readable(context, revision)) throw absent();
    boolean code = "code".equals(row(revision).documentType());
    boolean metadata = List.of("autoTag", "tagGroups").contains(from);
    if (code && first > 2 && !metadata)
      throw new CallerFault("code documents do not run the prose summary cascade");
    long old = generation(revision);
    jdbc.queryForMap(
        "SELECT id FROM information_revision_allowances WHERE id=? FOR UPDATE", revision);
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

    lockResource(context, revision, false);
    jdbc.queryForMap(
        "SELECT id FROM information_revision_allowances WHERE id=? FOR UPDATE", revision);
    if (total < row(revision).allowanceSpent())
      throw new CallerFault("allowance cannot be lowered below actual spending");
    UUID source =
        jdbc.queryForObject(
            "SELECT processing_budget_id FROM information_revisions WHERE id=?",
            UUID.class,
            revision);
    allowances.ceiling(new AllowanceId(source), total);
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
    lockResource(context, revision, false);
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

  public void tagGroups(
      InformationContext context, UUID revision, Map<String, List<String>> groups) {
    UUID resource = lockResource(context, revision, false).id();
    if (groups != null) groups = InformationTagGroups.from(groups, null);
    changed(
        jdbc.update(
            "UPDATE information_resources SET tag_groups=CAST(? AS jsonb),tag_groups_manual=? WHERE id=?",
            canonical(groups == null ? Map.of() : groups),
            groups != null,
            resource));
  }

  public void tags(InformationContext context, UUID revision, List<String> tags) {
    UUID resource = lockResource(context, revision, false).id();
    var checked = InformationFacets.tags(tags);
    changed(
        jdbc.update(
            "UPDATE information_resources SET tags=CAST(? AS jsonb) WHERE id=?",
            canonical(checked),
            resource));
  }

  public void replaceTags(Resource resource, List<String> tags, long expectedVersion) {
    if (expectedVersion < 0 || expectedVersion > 9007199254740991L)
      throw new IllegalArgumentException("invalid expected tag version");
    Objects.requireNonNull(resource);
    var checked = InformationFacets.tags(tags);
    // The existing resource lock fences live authorization; the predicate also protects this
    // repository invariant from stale callers. The trigger versions legacy/import writes too.
    if (jdbc.update(
            "UPDATE information_resources SET tags=CAST(? AS jsonb) WHERE id=? AND tags_version=?",
            canonical(checked),
            resource.id(),
            expectedVersion)
        != 1)
      throw new CallerFault(
          "INFORMATION_TAGS_CONFLICT: Tags changed; refresh and review before replacing");
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

  @Override
  public void requireCommand(InformationContext context, UUID revision, Command command) {
    if (command != null
        && jdbc.queryForList(
                "SELECT request_id FROM information_commands WHERE account=? AND request_id=? AND revision_id=? AND token=? AND state IN ('reserved','applied') AND lease_until>=? FOR UPDATE",
                context.account(),
                command.request(),
                revision,
                command.token(),
                clock.instant().atOffset(java.time.ZoneOffset.UTC))
            .isEmpty())
      throw new CallerFault("information command lease expired; recover the same requestId");
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

    lockResource(context, revision, false);
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
            + " (SELECT older.id FROM information_revision_allowances older JOIN information_revision_allowances current ON older.resource_id=current.resource_id WHERE current.id=? AND older.id<>?)",
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
