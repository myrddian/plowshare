package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.protocol.InformationNavigation.*;
import io.aeyer.plowshare.protocol.InformationNavigation.Readable;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL owns filtering before counts/pages and rechecks authority inside mutation transactions. */
public final class JdbcInformationNavigationRepository implements InformationNavigationRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final PolicySettings DEFAULT =
      new PolicySettings(Policy.DEFAULT, Policy.NONE, true, "Restricted document");
  private final JdbcTemplate jdbc;
  private final io.aeyer.plowshare.server.archive.ProjectMembers members;

  public JdbcInformationNavigationRepository(
      JdbcTemplate jdbc, io.aeyer.plowshare.server.archive.ProjectMembers members) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.members = Objects.requireNonNull(members);
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
            c.selection().includeShared()));
  }

  private static final String HEADS =
      " FROM information_resources q JOIN information_revisions r ON r.id=information_navigation_head(q.id)";

  private InformationSql.Filter selected(
      InformationContext c, UUID relatedTo, String relation, String direction) {
    var a =
        relatedTo == null
            ? args(c)
            : new ArrayList<Object>(
                Arrays.asList(
                    c.account(), c.selection().project(), c.account(), c.selection().project()));
    var filter = InformationFacetSql.facets(c.facets(), "r", "q");
    String sql =
        (relatedTo == null
                ? "information_readable(r.id,?,?,?,?)"
                : "information_navigation_own_readable(r.id,?,?) AND information_reference_readable(r.id,?,?)")
            + " AND r.document_type='document' AND NOT r.excluded AND "
            + filter.sql();
    a.addAll(filter.arguments());
    if (relatedTo != null) {
      boolean outgoing = !"backlinks".equals(direction);
      sql +=
          " AND information_readable(information_navigation_head(?),?,?,?,?) AND EXISTS(SELECT 1 FROM information_relationships e WHERE NOT e.removed AND e."
              + (outgoing ? "source" : "target")
              + "=? AND e."
              + (outgoing ? "target" : "source")
              + "=q.id"
              + (relation == null ? "" : " AND e.relation=?")
              + ")";
      a.add(relatedTo);
      a.addAll(args(c));
      a.add(relatedTo);
      if (relation != null) a.add(relation);
    }
    return new InformationSql.Filter(sql, a);
  }

  private static Document document(java.sql.ResultSet rs, boolean includeShared)
      throws java.sql.SQLException {
    return new Document(
        rs.getObject("resource", UUID.class),
        rs.getObject("revision", UUID.class),
        rs.getString("title"),
        rs.getString("source_name"),
        List.of(decode(rs.getString("tags"), String[].class)),
        new io.aeyer.plowshare.protocol.InformationRequest.Scope(
            rs.getString("visibility").equals("shared")
                ? "shared"
                : rs.getString("project_name") == null ? "personal" : "project",
            rs.getString("visibility").equals("shared") ? null : rs.getString("project_name"),
            includeShared && !rs.getString("visibility").equals("shared")),
        rs.getString("owner"));
  }

  private static final String DOCUMENT =
      "SELECT q.id AS resource,r.id AS revision,r.title,q.source_name,q.owner_handle AS owner,q.tags::text,(SELECT name FROM projects WHERE id=q.project_id) AS project_name,coalesce((SELECT CASE WHEN audience IN ('GLOBAL_READ','LEGACY_SHARED') THEN 'shared' ELSE 'home' END FROM information_resource_policies WHERE resource_id=q.id),'pending') AS visibility";

  public Documents documents(
      InformationContext c,
      UUID relatedTo,
      String relation,
      String direction,
      int limit,
      int offset) {
    var f = selected(c, relatedTo, relation, direction);
    var a = new ArrayList<>(f.arguments());
    a.add(limit);
    a.add(offset);
    return new Documents(
        jdbc.query(
            DOCUMENT
                + HEADS
                + " WHERE "
                + f.sql()
                + " ORDER BY r.created_at DESC,q.id LIMIT ? OFFSET ?",
            (rs, n) ->
                document(
                    rs, !io.aeyer.plowshare.server.auth.ServiceCredentials.principal(c.account())),
            a.toArray()),
        limit,
        offset);
  }

  public Information.Facets facets(
      InformationContext c, UUID relatedTo, String relation, String direction) {
    var f = selected(c, relatedTo, relation, direction);
    String from = HEADS + " WHERE " + f.sql();
    long total =
        Objects.requireNonNull(
            jdbc.queryForObject("SELECT count(*)" + from, Long.class, f.arguments().toArray()));
    var counts = new LinkedHashMap<String, List<Information.FacetCount>>();
    var truncated = new LinkedHashMap<String, Boolean>();
    var names = new ArrayList<>(InformationFacets.NAMES);
    names.addAll(List.of("contentType", "contentTypeVersion"));
    for (String name : names) {
      String expression =
          switch (name) {
            case "tags" -> "jsonb_array_elements_text(q.tags)";
            case "autoTag" ->
                "jsonb_array_elements_text(" + InformationFacetSql.readyTags("r") + ")";
            case "tagGroup" ->
                "jsonb_object_keys(" + InformationFacetSql.visibleGroups("r", "q") + ")";
            case "documentAuthor" -> InformationFacetSql.documentAuthor("r", "q");
            case "subtype" -> "r.document_subtype";
            case "when" -> "to_char(r.created_at AT TIME ZONE 'UTC','YYYY-MM')";
            case "kind" -> "q.kind";
            case "author" -> "q.owner_handle";
            case "contentType" ->
                "(SELECT v.type_id::text FROM information_revision_content c JOIN information_content_type_versions v ON v.id=c.type_version WHERE c.revision_id=r.id AND NOT c.redacted)";
            default ->
                "(SELECT c.type_version::text FROM information_revision_content c WHERE c.revision_id=r.id AND NOT c.redacted)";
          };
      var values =
          jdbc.query(
              "SELECT value,count(*) AS count FROM (SELECT "
                  + expression
                  + " AS value"
                  + from
                  + ") x WHERE value IS NOT NULL GROUP BY value ORDER BY count(*) DESC,value LIMIT 101",
              (rs, n) -> new Information.FacetCount(rs.getString(1), rs.getLong(2)),
              f.arguments().toArray());
      counts.put(name, values.stream().limit(100).toList());
      truncated.put(name, values.size() > 100);
    }
    var edges =
        jdbc.query(
            "SELECT category,tag,count(*) FROM (SELECT "
                + InformationFacetSql.visibleGroups("r", "q")
                + " AS groups"
                + from
                + ") candidates CROSS JOIN LATERAL jsonb_each(candidates.groups) g(category,tags) CROSS JOIN LATERAL jsonb_array_elements_text(g.tags) t(tag) GROUP BY category,tag ORDER BY count(*) DESC,category,tag LIMIT 1001",
            (rs, n) -> new Information.TagEdge(rs.getString(1), rs.getString(2), rs.getLong(3)),
            f.arguments().toArray());
    return new Information.Facets(
        total,
        counts,
        truncated,
        new Information.TagGraph(edges.stream().limit(1000).toList(), edges.size() > 1000));
  }

  public Selection select(InformationContext c, Target target) {
    var a = args(c);
    a.add(target.resource());
    if (target.revision() != null) a.add(target.revision());
    String from =
        " FROM information_resources q JOIN information_revisions r ON r.resource_id=q.id AND r.id="
            + (target.revision() == null ? "information_navigation_head(q.id)" : "?");
    // Keep parameter order explicit for pinned revisions, which precede the access predicate.
    if (target.revision() != null) {
      a.removeLast();
      a.addFirst(target.revision());
    }
    var rows =
        jdbc.query(
            DOCUMENT + from + " WHERE information_readable(r.id,?,?,?,?) AND q.id=?",
            (rs, n) ->
                document(
                    rs, !io.aeyer.plowshare.server.auth.ServiceCredentials.principal(c.account())),
            a.toArray());
    return rows.isEmpty() ? new Unavailable() : new Readable(rows.getFirst());
  }

  private boolean ownReadable(InformationContext c, Target target) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT coalesce(information_navigation_own_readable(coalesce(?,information_navigation_head(?)),?,?) AND information_reference_readable(coalesce(?,information_navigation_head(?)),?,?),false)",
            Boolean.class,
            target.revision(),
            target.resource(),
            c.account(),
            c.selection().project(),
            target.revision(),
            target.resource(),
            c.account(),
            c.selection().project()));
  }

  private boolean referenceVisible(InformationContext c, UUID target) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT information_navigation_reference_visible(?,?,?)",
            Boolean.class,
            target,
            c.account(),
            c.selection().project()));
  }

  private Selection relatedSelection(InformationContext c, UUID resource, UUID revision) {
    var rows =
        jdbc.query(
            DOCUMENT
                + " FROM information_resources q JOIN information_revisions r ON r.resource_id=q.id AND r.id=coalesce(?,information_navigation_head(q.id)) WHERE q.id=? AND information_navigation_own_readable(r.id,?,?) AND information_reference_readable(r.id,?,?)",
            (rs, n) ->
                document(
                    rs, !io.aeyer.plowshare.server.auth.ServiceCredentials.principal(c.account())),
            revision,
            resource,
            c.account(),
            c.selection().project(),
            c.account(),
            c.selection().project());
    if (!rows.isEmpty()) return new Readable(rows.getFirst());
    if (!referenceVisible(c, resource)) return new Unavailable();
    String label =
        jdbc.queryForObject(
            "SELECT information_navigation_policy(?)->>'referenceLabel'", String.class, resource);
    return new Locked(resource, label);
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
    if (!(select(c, new Target(resource, null)) instanceof Readable)) throw unavailable();
    boolean outgoing = direction.equals("outgoing");
    // Filter hidden neighbours BEFORE paging. Returned relationship fields never contain captions.
    String neighbour = outgoing ? "e.target" : "e.source";
    var visible = InformationSql.relationshipNeighbour(c, outgoing);
    var a = new ArrayList<Object>();
    a.add(resource);
    a.addAll(args(c));
    if (relation != null) a.add(relation);
    if (otherResource != null) a.add(otherResource);
    a.addAll(visible.arguments());
    a.add(limit);
    a.add(offset);
    var edges =
        jdbc.query(
            "SELECT e.* FROM information_relationships e WHERE NOT e.removed AND e."
                + (outgoing ? "source" : "target")
                + "=? AND information_readable(information_navigation_head(e."
                + (outgoing ? "source" : "target")
                + "),?,?,?,?)"
                + (relation == null ? "" : " AND e.relation=?")
                + (otherResource == null ? "" : " AND " + neighbour + "=?")
                + " AND "
                + visible.sql()
                + " ORDER BY e.id LIMIT ? OFFSET ?",
            (rs, n) -> relationship(rs),
            a.toArray());
    var result = new ArrayList<Connection>();
    for (var e : edges) {
      Selection choice =
          relatedSelection(
              c, outgoing ? e.target() : e.source(), outgoing ? e.pinnedRevision() : null);
      if (!(choice instanceof Unavailable)) {
        var visibleEdge =
            choice instanceof Locked
                ? new Relationship(
                    e.id(), e.source(), e.target(), null, e.relation(), e.version(), e.removed())
                : e;
        result.add(new Connection(visibleEdge, choice));
      }
    }
    return new Connections(result, limit, offset);
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
            "SELECT r.id FROM information_resources q JOIN information_revisions r ON r.id=information_navigation_head(q.id) WHERE q.id=? AND information_lock_knowledge(r.id,?,?,?,?,'WRITE') AND information_readable(r.id,?,?,?,?)",
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
                "SELECT id FROM information_revisions WHERE id=? AND resource_id=?",
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
