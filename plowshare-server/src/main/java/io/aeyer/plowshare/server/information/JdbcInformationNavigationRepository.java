package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.protocol.InformationContent;
import io.aeyer.plowshare.protocol.InformationNavigation;
import io.aeyer.plowshare.protocol.InformationNavigation.*;
import io.aeyer.plowshare.protocol.InformationNavigation.Readable;
import io.aeyer.plowshare.protocol.SchemaValue;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL owns filtering before counts/pages and rechecks authority inside mutation transactions. */
public final class JdbcInformationNavigationRepository implements InformationNavigationRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonMapper METADATA_JSON =
      JsonMapper.builder()
          .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();
  private static final PolicySettings DEFAULT =
      new PolicySettings(Policy.DEFAULT, Policy.NONE, true, "Restricted document");
  private final JdbcTemplate jdbc;
  private final io.aeyer.plowshare.server.archive.ProjectMembers members;
  private final ProjectionCache<Documents> documentsCache;
  private final ProjectionCache<Information.Facets> facetsCache;
  private final ProjectionCache<Connections> connectionsCache;

  public JdbcInformationNavigationRepository(
      JdbcTemplate jdbc, io.aeyer.plowshare.server.archive.ProjectMembers members) {
    this(jdbc, members, ProjectionCache.direct(), ProjectionCache.direct());
  }

  /** Composition owns typed DOCUMENTS/AUTHORITY caches; standalone callers retain direct reads. */
  public JdbcInformationNavigationRepository(
      JdbcTemplate jdbc,
      io.aeyer.plowshare.server.archive.ProjectMembers members,
      ProjectionCache<Documents> documentsCache,
      ProjectionCache<Information.Facets> facetsCache) {
    this(jdbc, members, documentsCache, facetsCache, ProjectionCache.direct());
  }

  /** All graph DTO dependencies are watched by DOCUMENTS/AUTHORITY; no authority is cached. */
  public JdbcInformationNavigationRepository(
      JdbcTemplate jdbc,
      io.aeyer.plowshare.server.archive.ProjectMembers members,
      ProjectionCache<Documents> documentsCache,
      ProjectionCache<Information.Facets> facetsCache,
      ProjectionCache<Connections> connectionsCache) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.members = Objects.requireNonNull(members);
    this.documentsCache = Objects.requireNonNull(documentsCache);
    this.facetsCache = Objects.requireNonNull(facetsCache);
    this.connectionsCache = Objects.requireNonNull(connectionsCache);
  }

  public void requireActive(String account) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM admins WHERE handle=? AND account_active(handle) AND NOT bootstrap)",
            Boolean.class,
            account))) throw unavailable();
  }

  public void lockAccount(String account) {
    lockIdentity(account, null);
    requireActive(account);
  }

  public void snapshot() {
    jdbc.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
  }

  public void snapshotSingleProjection() {
    // Do not downgrade a caller's historical snapshot. Version admission independently requires
    // READ COMMITTED, so an ambient REPEATABLE READ transaction preserves its direct SQL reads.
    jdbc.execute("SET TRANSACTION READ ONLY");
  }

  public String fingerprint(io.aeyer.plowshare.protocol.InformationRequest command) {
    return InformationCatalogue.sha256(
        (command.operation() + ":" + json(command))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public void lock() {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended('information-navigation-control',0)) IS NULL",
        Boolean.class);
  }

  private void requireGraphBound(boolean creating) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM (SELECT id FROM information_relationships LIMIT 10001) bounded",
            Long.class);
    if (count == null || count > (creating ? 9999 : 10000))
      throw new CallerFault("reference graph exceeds 10000 relationships");
  }

  private static NotFoundFault unavailable() {
    return new NotFoundFault("information is unavailable in this selection");
  }

  private static CallerFault conflict() {
    return new CallerFault(
        "navigation-conflict: refresh and retain the original request ID for receipt recovery");
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("cannot encode navigation value", e);
    }
  }

  private static <T> T decode(String value, Class<T> type) {
    try {
      return JSON.readValue(value, type);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("invalid stored navigation value", e);
    }
  }

  private String key(PolicyTarget target) {
    return switch (target.kind()) {
      case "platform" -> "platform";
      case "document" -> {
        if (jdbc.queryForList(
                "SELECT id FROM information_resources WHERE id=?", UUID.class, target.resource())
            .isEmpty()) throw unavailable();
        yield "document:" + target.resource();
      }
      case "project" -> "project:" + projectId(target.project());
      default -> throw new IllegalArgumentException("invalid policy target");
    };
  }

  private long projectId(String project) {
    var rows = jdbc.queryForList("SELECT id FROM projects WHERE name=?", Long.class, project);
    if (rows.isEmpty()) throw unavailable();
    return rows.getFirst();
  }

  private String managementProject(PolicyTarget target) {
    if (target.kind().equals("project")) return target.project();
    if (!target.kind().equals("document")) return null;
    var rows =
        jdbc.queryForList(
            "SELECT p.name FROM information_resources q LEFT JOIN projects p ON p.id=q.project_id WHERE q.id=?",
            String.class,
            target.resource());
    if (rows.isEmpty()) throw unavailable();
    return rows.getFirst();
  }

  private UUID controlScope(PolicyTarget target) {
    if (target.kind().equals("platform"))
      return jdbc.queryForObject(
          "SELECT id FROM authority_scopes WHERE builtin='SYSTEM_ADMINISTRATION'", UUID.class);
    if (target.kind().equals("project"))
      return jdbc.queryForObject(
          "SELECT s.id FROM authority_scopes s JOIN projects p ON p.id=s.project_id WHERE p.name=?",
          UUID.class,
          target.project());
    var scopes =
        jdbc.queryForList(
            "SELECT home_scope_id FROM information_resources WHERE id=? AND home_scope_id IS NOT NULL",
            UUID.class,
            target.resource());
    if (scopes.isEmpty()) throw unavailable();
    return scopes.getFirst();
  }

  public boolean canManage(String account, PolicyTarget target) {
    UUID scope = controlScope(target);
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT information_scope_control(?,?)", Boolean.class, account, scope))) return false;
    String project = managementProject(target);
    return project == null
        || members.mayAdminister(project, account)
        || members
            .role(project, account)
            .filter(role -> role == io.aeyer.plowshare.server.archive.ProjectRole.MANAGER)
            .isPresent();
  }

  public void requireManager(String account, PolicyTarget target) {
    // Match content and role mutation order, and retain live lineage through policy commit.
    jdbc.queryForObject(
        "SELECT information_lock_scope(?,?)", Object.class, account, controlScope(target));
    if (!canManage(account, target)) throw unavailable();
  }

  private void lockIdentity(String account, String project) {
    if (jdbc.queryForList(
            "SELECT handle FROM admins WHERE handle=? AND enabled AND NOT bootstrap FOR SHARE",
            String.class,
            account)
        .isEmpty()) throw unavailable();
    if (project != null)
      jdbc.queryForList(
          "SELECT m.handle FROM project_members m JOIN projects p ON p.id=m.project_id WHERE p.name=? AND m.handle=? FOR SHARE OF m",
          String.class,
          project,
          account);
  }

  private PolicySettings settings(String key) {
    var rows =
        jdbc.queryForList(
            "SELECT settings::text FROM information_navigation_policies WHERE policy_key=?",
            String.class,
            key);
    return rows.isEmpty() ? DEFAULT : decode(rows.getFirst(), PolicySettings.class);
  }

  public PolicyView policy(String account, PolicyTarget target) {
    String key = key(target);
    var versions =
        jdbc.queryForList(
            "SELECT version FROM information_navigation_policies WHERE policy_key=?",
            Long.class,
            key);
    PolicySettings chosen = settings(key);
    if (versions.isEmpty()) {
      if (target.kind().equals("project")) chosen = settings("platform");
      if (target.kind().equals("document")) {
        String project = managementProject(target);
        chosen = project == null ? settings("platform") : settings("project:" + projectId(project));
      }
    }
    Policy effective;
    if (target.resource() != null) effective = effective(target.resource());
    else {
      PolicySettings platform = settings("platform");
      effective = chosen.defaults().constrain(chosen.mandatory()).constrain(platform.mandatory());
    }
    long retained =
        target.resource() == null
            ? 0
            : Objects.requireNonNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM information_reference_obligations WHERE source=?",
                    Long.class,
                    target.resource()));
    return new PolicyView(
        target,
        versions.isEmpty() ? 0 : versions.getFirst(),
        chosen,
        effective,
        canManage(account, target),
        retained);
  }

  private Policy effective(UUID resource) {
    String value =
        jdbc.queryForObject(
            "SELECT (information_navigation_policy(?)-'referenceLabel')::text",
            String.class,
            resource);
    if (value == null) throw unavailable();
    return decode(value, Policy.class);
  }

  public PolicyView setPolicy(
      String account, PolicyTarget target, long expectedVersion, PolicySettings value) {
    requireManager(account, target);
    requireGraphBound(false);
    String key = key(target);
    PolicyView before = policy(account, target);
    if (before.version() != expectedVersion) throw conflict();
    // Capture every resource's effective restrictions before changing inherited defaults. A project
    // change must not indirectly widen a stricter document override by disabling overrides.
    String affected =
        switch (target.kind()) {
          case "document" -> "q.id=?";
          case "project" -> "q.project_id=?";
          default -> "true";
        };
    Object[] affectedArguments =
        target.resource() != null
            ? new Object[] {target.resource()}
            : target.project() != null
                ? new Object[] {projectId(target.project())}
                : new Object[] {};
    var previous =
        jdbc.query(
            "SELECT q.id,(information_navigation_policy(q.id)-'referenceLabel')::text AS policy, EXISTS(SELECT 1 FROM information_relationships e WHERE e.target=q.id) OR EXISTS(SELECT 1 FROM information_reference_obligations o WHERE o.target=q.id OR o.source=q.id) AS referenced FROM information_resources q WHERE "
                + affected
                + " LIMIT 10001",
            (rs, n) ->
                new Previous(
                    rs.getObject(1, UUID.class),
                    decode(rs.getString(2), Policy.class),
                    rs.getBoolean(3)),
            affectedArguments);
    if (previous.size() > 10000)
      throw new CallerFault("policy transition exceeds 10000 affected documents");
    jdbc.update(
        "INSERT INTO information_navigation_policies(policy_key,version,settings,actor) VALUES(?,1,CAST(? AS jsonb),?) ON CONFLICT(policy_key) DO UPDATE SET version=information_navigation_policies.version+1,settings=excluded.settings,actor=excluded.actor,updated_at=now()",
        key,
        json(value),
        account);
    for (var old : previous) {
      if (effective(old.resource()).widens(old.policy())
          && (expectedVersion != 0 || old.referenced()))
        throw new CallerFault(
            "policy-release-required: retained restrictions cannot be widened in this version");
    }
    // The obligation trigger runs in this transaction. No later read or asynchronous sweep is
    // needed.
    requireManager(account, target);
    return policy(account, target);
  }

  private record Previous(UUID resource, Policy policy, boolean referenced) {}

  private static List<Object> args(InformationContext c) {
    return new ArrayList<>(
        Arrays.asList(
            c.account(),
            c.selection().scope().name().toLowerCase(Locale.ROOT),
            c.selection().project(),
            c.selection().includeGlobal()));
  }

  private static final String HEADS =
      " FROM information_resources q JOIN information_revision_allowances r ON r.id=information_navigation_head(q.id)";

  private InformationSql.Filter selected(
      InformationContext c, UUID relatedTo, String relation, String direction) {
    requireTraversal(c, relatedTo, relation, direction);
    var selection = InformationSelectionSql.navigation(c, relatedTo, relation, direction);
    var facets = InformationFacetSql.facets(c.facets(), "r", "q");
    var arguments = new ArrayList<Object>(selection.arguments());
    arguments.addAll(facets.arguments());
    return new InformationSql.Filter(selection.sql() + " AND " + facets.sql(), arguments);
  }

  private static void requireTraversal(
      InformationContext context, UUID relatedTo, String relation, String direction) {
    Objects.requireNonNull(context);
    if (relatedTo == null && (relation != null || direction != null))
      throw new IllegalArgumentException("traversal needs a source");
    if (relation != null) io.aeyer.plowshare.protocol.InformationNavigation.relation(relation);
    if (direction != null) io.aeyer.plowshare.protocol.InformationNavigation.direction(direction);
  }

  private static Document document(java.sql.ResultSet rs, boolean includeGlobal)
      throws java.sql.SQLException {
    return document(rs, includeGlobal, null);
  }

  private static Document document(
      java.sql.ResultSet rs, boolean includeGlobal, InformationContent.Metadata metadata)
      throws java.sql.SQLException {
    return new Document(
        rs.getObject("resource", UUID.class),
        rs.getObject("revision", UUID.class),
        rs.getString("title"),
        rs.getString("source_name"),
        List.of(decode(rs.getString("tags"), String[].class)),
        new io.aeyer.plowshare.protocol.InformationRequest.Scope(
            rs.getString("visibility").equals("shared")
                ? "global_read"
                : rs.getString("project_name") == null ? "personal" : "project",
            rs.getString("visibility").equals("shared") ? null : rs.getString("project_name"),
            rs.getString("visibility").equals("shared") ? null : includeGlobal),
        rs.getString("owner"),
        metadata);
  }

  private static final String DOCUMENT =
      "SELECT q.id AS resource,r.id AS revision,r.title,q.source_name,q.owner_handle AS owner,q.tags::text,(SELECT name FROM projects WHERE id=q.project_id) AS project_name,coalesce((SELECT CASE WHEN audience='GLOBAL_READ' THEN 'shared' ELSE 'home' END FROM information_resource_policies WHERE resource_id=q.id),'pending') AS visibility";

  /**
   * The original bounded page is materialized once; only accepted text crosses the JDBC boundary.
   */
  private static final String BOUNDED_DOCUMENTS =
      """
      SELECT p.resource,p.revision,
        CASE WHEN length(p.title)<=cap.chars THEN p.title END AS title,
        CASE WHEN length(p.source_name)<=cap.chars THEN p.source_name END AS source_name,
        CASE WHEN length(p.owner)<=cap.chars THEN p.owner END AS owner,
        CASE WHEN length(p.tags)<=cap.chars THEN p.tags END AS tags,
        CASE WHEN length(p.project_name)<=cap.chars THEN p.project_name END AS project_name,
        p.visibility,
        (coalesce(length(p.title),0)>cap.chars
          OR coalesce(length(p.source_name),0)>cap.chars
          OR coalesce(length(p.owner),0)>cap.chars
          OR coalesce(length(p.tags),0)>cap.chars
          OR coalesce(length(p.project_name),0)>cap.chars) AS cache_oversized
      FROM navigation_page p CROSS JOIN (SELECT ?::integer AS chars) cap
      ORDER BY p.sort_created_at DESC,p.resource
      """;

  public Documents documents(
      InformationContext c,
      UUID relatedTo,
      String relation,
      String direction,
      int limit,
      int offset) {
    requireTraversal(c, relatedTo, relation, direction);
    if (limit < 1 || limit > 100 || offset < 0)
      throw new IllegalArgumentException("invalid document page bounds");
    if (!documentsCache.enabled())
      return documents(c, relatedTo, relation, direction, limit, offset, 0);
    var key =
        InformationCatalogueFingerprint.readKey(
            "navigation-documents-v1",
            c,
            Arrays.asList(relatedTo, relation, direction, limit, offset));
    // Row overhead includes immutable DTO/list/tag nodes. Tags' JSON and every other variable
    // string are gated in SQL; the generous per-character charge also covers decoded tag strings.
    long allowance = documentsCache.maxWeight() - 8192L - 4096L * limit;
    int chars = (int) Math.min(8192L, Math.max(1L, allowance / (48L * limit)));
    return documentsCache.read(
        key,
        allowance > 0,
        () -> documents(c, relatedTo, relation, direction, limit, offset, chars),
        () -> documents(c, relatedTo, relation, direction, limit, offset, 0),
        JdbcInformationNavigationRepository::documentsWeight);
  }

  private Documents documents(
      InformationContext c,
      UUID relatedTo,
      String relation,
      String direction,
      int limit,
      int offset,
      int chars) {
    var f = selected(c, relatedTo, relation, direction);
    var a = new ArrayList<>(f.arguments());
    a.add(limit);
    a.add(offset);
    String page =
        DOCUMENT
            + (chars == 0 ? "" : ",r.created_at AS sort_created_at")
            + HEADS
            + " WHERE "
            + f.sql()
            + " ORDER BY r.created_at DESC,q.id LIMIT ? OFFSET ?";
    String sql = page;
    if (chars > 0) {
      sql = "WITH navigation_page AS MATERIALIZED (" + page + ") " + BOUNDED_DOCUMENTS;
      a.add(chars);
    }
    return new Documents(
        jdbc.query(
            sql,
            (rs, n) -> {
              if (chars > 0 && rs.getBoolean("cache_oversized")) throw new ProjectionCache.Bypass();
              return document(rs, c.selection().includeGlobal());
            },
            a.toArray()),
        limit,
        offset);
  }

  public Documents documentsWithMetadata(
      InformationContext c,
      UUID relatedTo,
      String relation,
      String direction,
      int limit,
      int offset) {
    requireTraversal(c, relatedTo, relation, direction);
    if (limit < 1 || limit > 100 || offset < 0)
      throw new IllegalArgumentException("invalid document page bounds");
    var selected = selected(c, relatedTo, relation, direction);
    var arguments = new ArrayList<>(selected.arguments());
    arguments.add(limit);
    arguments.add(offset);
    arguments.add(InformationNavigation.METADATA_PAGE_BYTES);
    // Materialize the authorized, ordered page once. Joining a revision's immutable binding after
    // LIMIT does not re-run access closure or fetch schemas/source/extractions for every result.
    // Gate aggregate metadata bytes in SQL so oversized JSON never crosses the JDBC boundary.
    String sql =
        "WITH navigation_page AS MATERIALIZED ("
            + DOCUMENT
            + ",r.created_at AS sort_created_at"
            + HEADS
            + " WHERE "
            + selected.sql()
            + " ORDER BY r.created_at DESC,q.id LIMIT ? OFFSET ?), metadata_page AS MATERIALIZED ("
            + "SELECT p.*,c.metadata,c.metadata_hash,v.id AS type_version,v.type_id,v.ordinal,"
            + "v.definition_hash,t.slug,t.label,"
            + "sum(coalesce(octet_length(c.metadata::text),0)) OVER () AS metadata_bytes"
            + " FROM navigation_page p"
            + " LEFT JOIN information_revision_content c ON c.revision_id=p.revision AND NOT c.redacted"
            + " LEFT JOIN information_content_type_versions v ON v.id=c.type_version"
            + " LEFT JOIN information_content_types t ON t.id=v.type_id)"
            + " SELECT p.resource,p.revision,p.title,p.source_name,p.owner,p.tags,p.project_name,p.visibility,"
            + "p.metadata_hash,p.type_version,p.type_id,p.ordinal,p.definition_hash,p.slug,p.label,p.metadata_bytes,"
            + "CASE WHEN p.metadata_bytes<=? THEN p.metadata::text END AS metadata_json"
            + " FROM metadata_page p ORDER BY p.sort_created_at DESC,p.resource";
    return new Documents(
        jdbc.query(
            sql,
            (row, index) -> {
              if (row.getLong("metadata_bytes") > InformationNavigation.METADATA_PAGE_BYTES)
                throw new CallerFault(
                    "Metadata page exceeds 512 KiB; reduce the document page limit");
              return document(row, c.selection().includeGlobal(), metadata(row));
            },
            arguments.toArray()),
        limit,
        offset,
        InformationNavigation.METADATA_PROJECTION);
  }

  private static InformationContent.Metadata metadata(java.sql.ResultSet row)
      throws java.sql.SQLException {
    UUID version = row.getObject("type_version", UUID.class);
    if (version == null) return null;
    try {
      return new InformationContent.Metadata(
          new InformationContent.Identity(
              row.getObject("type_id", UUID.class),
              version,
              row.getInt("ordinal"),
              row.getString("slug"),
              row.getString("label"),
              row.getString("definition_hash")),
          row.getString("metadata_hash"),
          METADATA_JSON.readValue(row.getString("metadata_json"), SchemaValue.ObjectValue.class));
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("Invalid retained metadata", invalid);
    }
  }

  private static long documentsWeight(Documents page) {
    long weight = 8192;
    for (Document document : page.documents()) {
      weight += 4096;
      for (String text :
          Arrays.asList(
              document.title(),
              document.sourceName(),
              document.owner(),
              document.scope().project())) if (text != null) weight += 4L * text.length();
      for (String tag : document.tags()) weight += 4L * tag.length();
    }
    return weight;
  }

  public Information.Facets facets(
      InformationContext c, UUID relatedTo, String relation, String direction) {
    return new JdbcInformationFacets(jdbc, facetsCache)
        .read(
            selected(c, relatedTo, relation, direction),
            JdbcInformationFacets.Candidates.HEADS,
            true,
            InformationCatalogueFingerprint.readKey(
                "navigation-facets-v1", c, Arrays.asList(relatedTo, relation, direction)));
  }

  public Selection select(InformationContext c, Target target) {
    var filter = InformationSql.read(c, "r");
    var a = new ArrayList<Object>(filter.arguments());
    a.add(target.resource());
    if (target.revision() != null) a.add(target.revision());
    String from =
        " FROM information_resources q JOIN information_revision_allowances r ON r.resource_id=q.id AND r.id="
            + (target.revision() == null ? "information_navigation_head(q.id)" : "?");
    // Keep parameter order explicit for pinned revisions, which precede the access predicate.
    if (target.revision() != null) {
      a.removeLast();
      a.addFirst(target.revision());
    }
    var rows =
        jdbc.query(
            DOCUMENT + from + " WHERE " + filter.sql() + " AND q.id=?",
            (rs, n) -> document(rs, c.selection().includeGlobal()),
            a.toArray());
    return rows.isEmpty() ? new Unavailable() : new Readable(rows.getFirst());
  }

  private boolean ownReadable(InformationContext c, Target target) {
    var revision = "coalesce(?,information_navigation_head(?))";
    var boundary = InformationSql.boundary(c, revision);
    var a =
        new ArrayList<Object>(
            Arrays.asList(
                target.revision(), target.resource(), c.account(), c.selection().project()));
    if (c.readBoundary() != null) {
      a.add(target.revision());
      a.add(target.resource());
    }
    a.addAll(boundary.arguments());
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT coalesce(information_navigation_own_readable("
                + revision
                + ",?,?),false) AND "
                + boundary.sql(),
            Boolean.class,
            a.toArray()));
  }

  private boolean referenceVisible(InformationContext c, UUID target) {
    var boundary = InformationSql.boundary(c, "information_navigation_head(?)");
    var a = new ArrayList<Object>(Arrays.asList(target, c.account(), c.selection().project()));
    if (c.readBoundary() != null) a.add(target);
    a.addAll(boundary.arguments());
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT information_navigation_reference_visible(?,?,?) AND " + boundary.sql(),
            Boolean.class,
            a.toArray()));
  }

  private static Relationship relationship(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Relationship(
        rs.getObject("id", UUID.class),
        rs.getObject("source", UUID.class),
        rs.getObject("target", UUID.class),
        rs.getObject("pinned_revision", UUID.class),
        rs.getString("relation"),
        rs.getLong("version"),
        rs.getBoolean("removed"));
  }

  public Connections relationships(
      InformationContext c,
      UUID resource,
      String direction,
      String relation,
      UUID otherResource,
      int limit,
      int offset) {
    Objects.requireNonNull(c);
    Objects.requireNonNull(resource);
    io.aeyer.plowshare.protocol.InformationNavigation.direction(direction);
    if (relation != null) io.aeyer.plowshare.protocol.InformationNavigation.relation(relation);
    if (limit < 1 || limit > 100 || offset < 0)
      throw new IllegalArgumentException("invalid relationship page bounds");
    if (!connectionsCache.enabled())
      return relationships(c, resource, direction, relation, otherResource, limit, offset, 0);
    var key =
        InformationCatalogueFingerprint.readKey(
            "navigation-connections-v1",
            c,
            Arrays.asList(resource, direction, relation, otherResource, limit, offset));
    long byteBudget = Math.max(0, (connectionsCache.maxWeight() - 8192) / 64);
    return connectionsCache.read(
        key,
        byteBudget >= 256L * limit,
        () ->
            relationships(
                c, resource, direction, relation, otherResource, limit, offset, byteBudget),
        () -> relationships(c, resource, direction, relation, otherResource, limit, offset, 0),
        JdbcInformationNavigationRepository::connectionsWeight);
  }

  private Connections relationships(
      InformationContext c,
      UUID resource,
      String direction,
      String relation,
      UUID otherResource,
      int limit,
      int offset,
      long byteBudget) {
    boolean outgoing = direction.equals("outgoing");
    String neighbour = outgoing ? "e.target" : "e.source";
    String selectedRevision =
        outgoing
            ? "coalesce(e.pinned_revision,information_navigation_head(e.target))"
            : "coalesce(NULL::uuid,information_navigation_head(e.source))";
    var parent = InformationSql.read(c, "r");
    var boundary = InformationSql.boundary(c, selectedRevision);
    var headBoundary = InformationSql.boundary(c, "r.id");
    var args = new ArrayList<Object>(parent.arguments());
    args.add(resource);
    args.add(resource);
    if (relation != null) args.add(relation);
    if (otherResource != null) args.add(otherResource);
    args.add(c.account());
    args.add(c.account());
    args.add(c.selection().project());
    args.addAll(boundary.arguments());
    args.add(limit);
    args.add(offset);
    args.addAll(headBoundary.arguments());
    // Materialize the parent gate once, then the exact pre-page neighbour eligibility. V172's
    // own/reference readability functions are both knowledge_dependencies_readable; one result
    // feeds page membership AND metadata mapping. Locked references never hydrate private fields.
    String sql =
        "WITH source AS MATERIALIZED (SELECT EXISTS(SELECT 1 FROM information_resources q "
            + "JOIN information_revision_allowances r ON r.resource_id=q.id "
            + "AND r.id=information_navigation_head(q.id) WHERE "
            + parent.sql()
            + " AND q.id=?) AS parent_readable), edge_base AS MATERIALIZED ("
            + "SELECT e.*, "
            + neighbour
            + " AS neighbour,"
            + selectedRevision
            + " AS selected_revision FROM information_relationships e CROSS JOIN source s "
            + "WHERE s.parent_readable AND NOT e.removed AND e."
            + (outgoing ? "source" : "target")
            + "=?"
            + (relation == null ? "" : " AND e.relation=?")
            + (otherResource == null ? "" : " AND " + neighbour + "=?")
            + "), edge_policy AS MATERIALIZED (SELECT e.*,"
            + "coalesce(knowledge_dependencies_readable(e.selected_revision,?),false) AS dependency_readable,"
            + "information_navigation_reference_visible(e.neighbour,?,?) AS reference_visible,"
            + boundary.sql()
            + " AS selection_boundary FROM edge_base e), "
            + "edge_page AS MATERIALIZED (SELECT * FROM edge_policy WHERE "
            + "(dependency_readable OR reference_visible) AND selection_boundary ORDER BY id LIMIT ? OFFSET ?) "
            + "SELECT s.parent_readable,p.id,p.source,p.target,p.pinned_revision,p.relation,p.version,"
            + "p.removed,p.neighbour,d.*,coalesce(label.locked_visible,false) AS locked_visible,label.locked_label "
            + "FROM source s LEFT JOIN edge_page p ON true LEFT JOIN LATERAL ("
            + DOCUMENT
            + " FROM information_resources q JOIN information_revision_allowances r ON r.resource_id=q.id "
            + "AND r.id=p.selected_revision WHERE q.id=p.neighbour AND p.dependency_readable) d ON true "
            + "LEFT JOIN LATERAL (SELECT true AS locked_visible,"
            + "information_navigation_policy(p.neighbour)->>'referenceLabel' AS locked_label "
            + "WHERE d.resource IS NULL AND p.reference_visible AND coalesce((SELECT "
            + headBoundary.sql()
            + " FROM information_revision_allowances r "
            + "WHERE r.id=information_navigation_head(p.neighbour)),false)) label ON true ORDER BY p.id";
    if (byteBudget > 0) {
      // The scalar-only sentinel is checked before reading any document/label text. Oversized
      // graphs keep the exact uncached projection, including complete titles and tags.
      sql =
          "WITH connection_rows AS MATERIALIZED ("
              + sql
              + "), connection_bounds AS "
              + "(SELECT coalesce(sum(256::bigint+octet_length(to_jsonb(p)::text)),0) AS cache_bytes "
              + "FROM connection_rows p) SELECT p.*,b.cache_bytes FROM connection_bounds b "
              + "LEFT JOIN connection_rows p ON b.cache_bytes<=? ORDER BY p.id";
      args.add(byteBudget);
    }
    return jdbc.query(
        sql,
        (org.springframework.jdbc.core.ResultSetExtractor<Connections>)
            rs -> {
              var connections = new ArrayList<Connection>();
              while (rs.next()) {
                if (byteBudget > 0 && rs.getLong("cache_bytes") > byteBudget)
                  throw new ProjectionCache.Bypass();
                if (!rs.getBoolean("parent_readable")) throw unavailable();
                if (rs.getObject("id", UUID.class) == null) continue;
                var edge = relationship(rs);
                Selection choice;
                if (rs.getObject("resource", UUID.class) != null)
                  choice = new Readable(document(rs, c.selection().includeGlobal()));
                else if (rs.getBoolean("locked_visible"))
                  choice =
                      new Locked(
                          rs.getObject("neighbour", UUID.class), rs.getString("locked_label"));
                else continue;
                if (choice instanceof Locked)
                  edge =
                      new Relationship(
                          edge.id(),
                          edge.source(),
                          edge.target(),
                          null,
                          edge.relation(),
                          edge.version(),
                          edge.removed());
                connections.add(new Connection(edge, choice));
              }
              return new Connections(connections, limit, offset);
            },
        args.toArray());
  }

  private static long connectionsWeight(Connections page) {
    long weight = 8192 + 8192L * page.connections().size();
    for (var connection : page.connections()) {
      if (connection.selection() instanceof Readable readable)
        weight += documentsWeight(new Documents(List.of(readable.document()), 1, 0));
      else if (connection.selection() instanceof Locked locked)
        weight += 4L * locked.label().length();
    }
    return weight;
  }

  private UUID writable(InformationContext c, UUID resource) {
    var projects =
        jdbc.queryForList(
            "SELECT p.name FROM information_resources q LEFT JOIN projects p ON p.id=q.project_id WHERE q.id=?",
            String.class,
            resource);
    if (projects.isEmpty()) throw unavailable();
    String project = projects.getFirst();
    lockIdentity(c.account(), project);
    var a = new ArrayList<Object>();
    a.add(resource);
    a.addAll(args(c));
    a.addAll(args(c));
    var rows =
        jdbc.queryForList(
            "SELECT r.id FROM information_resources q JOIN information_revision_allowances r ON r.id=information_navigation_head(q.id) WHERE q.id=? AND information_lock_knowledge(r.id,?,?,?,?,'WRITE') AND information_readable(r.id,?,?,?,?)",
            UUID.class,
            a.toArray());
    if (rows.isEmpty()) throw unavailable();
    return rows.getFirst();
  }

  public Relationship create(
      InformationContext c,
      UUID id,
      UUID source,
      Target target,
      String relation,
      UUID expectedHead) {
    requireGraphBound(true);
    if (!writable(c, source).equals(expectedHead)) throw conflict();
    var targetRows =
        jdbc.queryForList(
            "SELECT id FROM information_resources WHERE id=? FOR UPDATE",
            UUID.class,
            target.resource());
    if (targetRows.isEmpty()) throw unavailable();
    if (target.revision() != null
        && jdbc.queryForList(
                "SELECT id FROM information_revision_allowances WHERE id=? AND resource_id=?",
                UUID.class,
                target.revision(),
                target.resource())
            .isEmpty()) throw unavailable();
    if (!ownReadable(c, target)
        && !(referenceVisible(c, target.resource()) && !effective(target.resource()).propagate()))
      throw unavailable();
    jdbc.update(
        "INSERT INTO information_relationships(id,source,target,pinned_revision,relation,actor) VALUES(?,?,?,?,?,?)",
        id,
        source,
        target.resource(),
        target.revision(),
        relation,
        c.account());
    return new Relationship(id, source, target.resource(), target.revision(), relation, 1, false);
  }

  public Changed remove(InformationContext c, UUID id, long version) {
    var edges =
        jdbc.query(
            "SELECT * FROM information_relationships WHERE id=?", (rs, n) -> relationship(rs), id);
    if (edges.isEmpty()) throw unavailable();
    writable(c, edges.getFirst().source());
    if (jdbc.update(
            "UPDATE information_relationships SET removed=true,version=version+1,updated_at=now() WHERE id=? AND version=? AND NOT removed",
            id,
            version)
        != 1) throw conflict();
    return new Changed(
        id,
        version + 1,
        !jdbc.queryForList(
                "SELECT relationship FROM information_reference_obligations WHERE relationship=?",
                UUID.class,
                id)
            .isEmpty());
  }

  public Views views(String account) {
    return new Views(
        jdbc.query(
            "SELECT * FROM information_navigation_views WHERE account=? AND NOT deleted ORDER BY label,id LIMIT 100",
            (rs, n) -> saved(rs),
            account));
  }

  private static SavedView saved(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new SavedView(
        rs.getObject("id", UUID.class),
        rs.getString("label"),
        decode(rs.getString("path"), Path.class),
        rs.getLong("version"),
        rs.getBoolean("deleted"));
  }

  public SavedView view(String account, UUID id) {
    var rows =
        jdbc.query(
            "SELECT * FROM information_navigation_views WHERE account=? AND id=? AND NOT deleted",
            (rs, n) -> saved(rs),
            account,
            id);
    if (rows.isEmpty()) throw unavailable();
    return rows.getFirst();
  }

  public SavedView save(String account, UUID id, long version, String label, Path path) {
    if (version == 0
        && Objects.requireNonNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM information_navigation_views WHERE account=? AND NOT deleted",
                    Long.class,
                    account))
            >= 100) throw new CallerFault("saved view limit is 100 per account");
    int count =
        version == 0
            ? jdbc.update(
                "INSERT INTO information_navigation_views(account,id,label,path,version) VALUES(?,?,?,CAST(? AS jsonb),1) ON CONFLICT DO NOTHING",
                account,
                id,
                label,
                json(path))
            : jdbc.update(
                "UPDATE information_navigation_views SET label=?,path=CAST(? AS jsonb),version=version+1 WHERE account=? AND id=? AND version=? AND NOT deleted",
                label,
                json(path),
                account,
                id,
                version);
    if (count != 1) throw conflict();
    return view(account, id);
  }

  public Changed delete(String account, UUID id, long version) {
    if (jdbc.update(
            "UPDATE information_navigation_views SET deleted=true,version=version+1 WHERE account=? AND id=? AND version=? AND NOT deleted",
            account,
            id,
            version)
        != 1) throw conflict();
    return new Changed(id, version + 1, false);
  }

  public void requireReceiptReadable(InformationContext context, Receipt result) {
    if (result.relationship() == null) return;
    var edge = result.relationship();
    writable(context, edge.source());
    if (!ownReadable(context, new Target(edge.target(), edge.pinnedRevision()))
        && !referenceVisible(context, edge.target())) throw unavailable();
  }

  public Optional<StoredReceipt> receipt(String account, UUID requestId) {
    return jdbc
        .query(
            "SELECT fingerprint,result::text FROM information_navigation_receipts WHERE account=? AND request_id=?",
            (rs, n) -> new StoredReceipt(rs.getString(1), decode(rs.getString(2), Receipt.class)),
            account,
            requestId)
        .stream()
        .findFirst();
  }

  public void record(
      String account,
      UUID requestId,
      String fingerprint,
      Receipt result,
      String action,
      String subject,
      String reason,
      Optional<Receipt> previous) {
    jdbc.update(
        "INSERT INTO information_navigation_receipts(account,request_id,fingerprint,result) VALUES(?,?,?,CAST(? AS jsonb))",
        account,
        requestId,
        fingerprint,
        json(result));
    jdbc.update(
        "INSERT INTO information_navigation_audit(account,request_id,action,subject,reason,previous_value,next_value) VALUES(?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb))",
        account,
        requestId,
        action,
        subject,
        reason,
        previous.map(JdbcInformationNavigationRepository::json).orElse(null),
        json(result));
  }
}
