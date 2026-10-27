package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Specialist registry repository; type-row locks serialize allocation and lifecycle changes. */
public final class JdbcInformationContentTypeRepository
    implements InformationContentTypeRepository {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
  private static final String CONTRACT =
      "SELECT v.id,v.type_id,v.ordinal,v.schema_definition,v.presentation,v.profile,v.definition_hash,t.slug,t.label,t.description";
  private static final String FROM =
      " FROM information_content_type_versions v JOIN information_content_types t ON t.id=v.type_id";
  private static final String SELECT = CONTRACT + ",v.state,v.created_at,t.control_revision" + FROM;
  private final JdbcTemplate jdbc;
  private final ProjectionCache<InformationContent.Versions> lists;
  private final ProjectionCache<Optional<InformationContent.Version>> versions;
  private final ProjectionCache<Optional<InformationContent.Contract>> attachments;

  public JdbcInformationContentTypeRepository(JdbcTemplate jdbc) {
    this(jdbc, ProjectionCache.direct(), ProjectionCache.direct(), ProjectionCache.direct());
  }

  /** Current caller admission remains outside these TYPES/AUTHORITY-fenced projections. */
  public JdbcInformationContentTypeRepository(
      JdbcTemplate jdbc,
      ProjectionCache<InformationContent.Versions> lists,
      ProjectionCache<Optional<InformationContent.Version>> versions,
      ProjectionCache<Optional<InformationContent.Contract>> attachments) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.lists = Objects.requireNonNull(lists);
    this.versions = Objects.requireNonNull(versions);
    this.attachments = Objects.requireNonNull(attachments);
  }

  private static SnapshotCache.Key key(String projection, Object... identity) {
    return new SnapshotCache.Key(
        projection, ProjectionCache.fingerprint(InformationJson.json(identity)));
  }

  private static String namespace(String namespace) {
    return InformationContent.text(namespace, "namespace", 2048, true);
  }

  /**
   * A whole page guard, including the lookahead row, runs inside PostgreSQL before JDBC sees any
   * schema/presentation text. The bound conservatively charges JSON graph nodes, not only strings.
   * An oversized sentinel refuses sharing; the original projection then returns the complete
   * result.
   */
  private static String bounded(String sql) {
    return "WITH registry_rows AS MATERIALIZED ("
        + sql
        + "), registry_bounds AS ("
        + "SELECT count(*) AS cache_rows,4096::bigint+COALESCE(sum(8192::bigint+256::bigint*octet_length(to_jsonb(registry_rows)::text)),0) AS cache_bytes FROM registry_rows) "
        + "SELECT registry_rows.*,registry_bounds.cache_rows,registry_bounds.cache_bytes "
        + "FROM registry_bounds LEFT JOIN registry_rows ON registry_bounds.cache_bytes<=? "
        + "ORDER BY registry_rows.slug,registry_rows.ordinal DESC";
  }

  private static long contractWeight(InformationContent.Contract value) {
    // Schema and presentation contain bounded JSON graphs. Charge graph nodes as well as their
    // serialized characters; SQL's larger factor additionally bounds temporary decode allocation.
    return 8192L + 128L * InformationJson.json(value).length();
  }

  private static long versionWeight(InformationContent.Version value) {
    return 4096L + contractWeight(value.contract());
  }

  private static long listWeight(InformationContent.Versions value) {
    return 4096L
        + value.results().stream()
            .mapToLong(JdbcInformationContentTypeRepository::versionWeight)
            .sum();
  }

  static InformationContent.Contract decodeContract(ResultSet row) throws SQLException {
    try {
      var schema = JSON.readValue(row.getString("schema_definition"), SchemaDefinition.class);
      var presentation =
          DocumentMetadata.presentation(
              schema, JSON.readValue(row.getString("presentation"), SchemaValue.class));
      return new InformationContent.Contract(
          row.getObject("type_id", UUID.class),
          row.getObject("id", UUID.class),
          row.getInt("ordinal"),
          row.getString("slug"),
          row.getString("label"),
          row.getString("description"),
          row.getString("profile"),
          row.getString("definition_hash"),
          schema,
          presentation);
    } catch (java.io.IOException | IllegalArgumentException invalid) {
      throw new IllegalStateException("Invalid retained content type definition", invalid);
    }
  }

  private static InformationContent.Version decode(ResultSet row, int index) throws SQLException {
    return new InformationContent.Version(
        decodeContract(row),
        row.getString("state"),
        row.getLong("control_revision"),
        row.getTimestamp("created_at").toInstant().toString());
  }

  @Override
  public InformationContent.Versions list(String namespace, boolean drafts, int limit, int offset) {
    if (limit < 1 || limit > 100 || offset < 0)
      throw new IllegalArgumentException("Invalid registry page");
    namespace(namespace);
    if (!lists.enabled()) return list(namespace, drafts, limit, offset, null);
    return lists.read(
        key("content-type-list-v1", namespace, drafts, limit, offset),
        lists.maxWeight() >= 12_288,
        () -> list(namespace, drafts, limit, offset, lists.maxWeight()),
        () -> list(namespace, drafts, limit, offset, null),
        JdbcInformationContentTypeRepository::listWeight);
  }

  private InformationContent.Versions list(
      String namespace, boolean drafts, int limit, int offset, Long maximum) {
    String sql =
        SELECT
            + " WHERE t.namespace=?"
            + (drafts ? "" : " AND v.state<>'draft'")
            + " ORDER BY t.slug,v.ordinal DESC OFFSET ? LIMIT ?";
    var arguments = new ArrayList<Object>(List.of(namespace, offset, limit + 1));
    if (maximum != null) arguments.add(maximum);
    var rows =
        jdbc.query(
            maximum == null ? sql : bounded(sql),
            (row, index) -> {
              if (maximum != null) {
                if (row.getLong("cache_bytes") > maximum) throw new ProjectionCache.Bypass();
                if (row.getLong("cache_rows") == 0) return new PageRow(Optional.empty(), false);
              }
              return index < limit
                  ? new PageRow(Optional.of(decode(row, index)), false)
                  : new PageRow(Optional.empty(), true);
            },
            arguments.toArray());
    return new InformationContent.Versions(
        rows.stream().flatMap(row -> row.version().stream()).toList(),
        rows.stream().anyMatch(PageRow::more));
  }

  private record PageRow(Optional<InformationContent.Version> version, boolean more) {}

  @Override
  public Optional<InformationContent.Version> version(
      String namespace, UUID version, boolean lock) {
    namespace(namespace);
    Objects.requireNonNull(version);
    if (lock || !versions.enabled()) return version(namespace, version, lock, null);
    return versions.read(
        key("content-type-version-v1", namespace, version),
        versions.maxWeight() >= 12_288,
        () -> version(namespace, version, false, versions.maxWeight()),
        () -> version(namespace, version, false, null),
        value -> value.map(JdbcInformationContentTypeRepository::versionWeight).orElse(4096L));
  }

  private Optional<InformationContent.Version> version(
      String namespace, UUID version, boolean lock, Long maximum) {
    // Serialise a type before locking any version. Otherwise two activations can each hold a
    // different version while waiting for the same type and the other's active-version update.
    if (lock) {
      // Import takes the navigation policy lock before resolving contracts. Keep assignment's
      // order identical: a metadata writer must not hold a type while waiting on that importer.
      jdbc.queryForObject(
          "SELECT pg_advisory_xact_lock_shared(hashtextextended('information-navigation-control',0)) IS NULL",
          Boolean.class);
      jdbc.query(
          "SELECT t.id FROM information_content_types t WHERE t.namespace=? AND t.id=(SELECT type_id FROM information_content_type_versions WHERE id=?) FOR UPDATE OF t",
          (row, index) -> row.getObject(1, UUID.class),
          namespace,
          version);
    }
    String sql = SELECT + " WHERE t.namespace=? AND v.id=?" + (lock ? " FOR UPDATE OF v" : "");
    return one(sql, new ArrayList<>(List.of(namespace, version)), maximum);
  }

  private Optional<InformationContent.Version> one(
      String sql, List<Object> arguments, Long maximum) {
    if (maximum == null)
      return jdbc
          .query(sql, JdbcInformationContentTypeRepository::decode, arguments.toArray())
          .stream()
          .findFirst();
    var bound = new ArrayList<>(arguments);
    bound.add(maximum);
    return jdbc
        .query(
            bounded(sql),
            (row, index) -> {
              if (row.getLong("cache_bytes") > maximum) throw new ProjectionCache.Bypass();
              return row.getLong("cache_rows") == 0
                  ? Optional.<InformationContent.Version>empty()
                  : Optional.of(decode(row, index));
            },
            bound.toArray())
        .stream()
        .flatMap(Optional::stream)
        .findFirst();
  }

  @Override
  public Optional<InformationContent.Contract> attached(UUID revision, UUID version) {
    Objects.requireNonNull(revision);
    Objects.requireNonNull(version);
    if (!attachments.enabled()) return attached(revision, version, null);
    return attachments.read(
        key("content-type-attached-v1", revision, version),
        attachments.maxWeight() >= 12_288,
        () -> attached(revision, version, attachments.maxWeight()),
        () -> attached(revision, version, null),
        value -> value.map(JdbcInformationContentTypeRepository::contractWeight).orElse(4096L));
  }

  private Optional<InformationContent.Contract> attached(
      UUID revision, UUID version, Long maximum) {
    String sql =
        CONTRACT
            + FROM
            + " JOIN information_revision_content c ON c.type_version=v.id"
            + " JOIN information_revision_allowances r ON r.id=c.revision_id WHERE c.revision_id=? AND c.type_version=?"
            + " AND NOT c.redacted AND r.availability<>'deleted'";
    if (maximum == null)
      return jdbc.query(sql, (row, index) -> decodeContract(row), revision, version).stream()
          .findFirst();
    return jdbc
        .query(
            bounded(sql),
            (row, index) -> {
              if (row.getLong("cache_bytes") > maximum) throw new ProjectionCache.Bypass();
              return row.getLong("cache_rows") == 0
                  ? Optional.<InformationContent.Contract>empty()
                  : Optional.of(decodeContract(row));
            },
            revision,
            version,
            maximum)
        .stream()
        .flatMap(Optional::stream)
        .findFirst();
  }

  @Override
  public InformationContent.Version create(
      String namespace,
      String actor,
      UUID type,
      Long expected,
      String slug,
      String label,
      String description,
      SchemaDefinition schema,
      SchemaValue.ObjectValue presentation) {
    namespace(namespace);
    if (type != null && (expected == null || expected < 1))
      throw new IllegalArgumentException("Existing type needs control revision");
    DocumentMetadata.definition(schema);
    presentation = DocumentMetadata.presentation(schema, presentation);
    if (type == null) {
      if (expected != null)
        throw new IllegalArgumentException("New type has no prior control revision");
      type = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO information_content_types(id,namespace,slug,label,description,created_by) VALUES(?,?,?,?,?,?)",
          type,
          namespace,
          InformationContent.slug(slug),
          InformationContent.text(label, "label", 120, true),
          InformationContent.text(description, "description", 4096, false),
          actor);
    }
    var rows =
        jdbc.query(
            "SELECT next_version,control_revision,slug,label,description FROM information_content_types WHERE id=? AND namespace=? FOR UPDATE",
            (row, index) ->
                new Control(
                    row.getInt(1),
                    row.getLong(2),
                    row.getString(3),
                    row.getString(4),
                    row.getString(5)),
            type,
            namespace);
    if (rows.isEmpty()) throw new CallerFault("Content type is unavailable");
    var control = rows.getFirst();
    if (expected != null && expected != control.revision())
      throw new CallerFault("INFORMATION_TYPE_CONFLICT");
    if (control.next() == Integer.MAX_VALUE)
      throw new CallerFault("Content type version allocation exhausted");
    UUID version = UUID.randomUUID();
    String fingerprint =
        SchemaCodec.documentFingerprint(schema, DocumentMetadata.PROFILE, presentation);
    var retained =
        jdbc.queryForObject(
            "INSERT INTO information_content_type_versions(id,type_id,ordinal,schema_definition,presentation,profile,definition_hash,created_by) VALUES(?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?,?) RETURNING state,created_at",
            JdbcInformationContentTypeRepository::lifecycle,
            version,
            type,
            control.next(),
            SchemaCodec.compactJson(schema),
            SchemaCodec.compactJson(presentation),
            DocumentMetadata.PROFILE,
            fingerprint,
            actor);
    long revision =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "UPDATE information_content_types SET next_version=next_version+1,control_revision=control_revision+1 WHERE id=? RETURNING control_revision",
                Long.class,
                type));
    var contract =
        new InformationContent.Contract(
            type,
            version,
            control.next(),
            control.slug(),
            control.label(),
            control.description(),
            DocumentMetadata.PROFILE,
            fingerprint,
            schema,
            presentation);
    return retainedVersion(contract, Objects.requireNonNull(retained), revision);
  }

  private record Control(int next, long revision, String slug, String label, String description) {}

  private record Lifecycle(String state, String createdAt) {}

  private static Lifecycle lifecycle(ResultSet row, int index) throws SQLException {
    return new Lifecycle(
        row.getString("state"), row.getTimestamp("created_at").toInstant().toString());
  }

  private static InformationContent.Version retainedVersion(
      InformationContent.Contract contract, Lifecycle lifecycle, long controlRevision) {
    return new InformationContent.Version(
        contract, lifecycle.state(), controlRevision, lifecycle.createdAt());
  }

  @Override
  public InformationContent.Version transition(
      String namespace, UUID version, long expected, boolean activate) {
    var current =
        version(namespace, version, true)
            .orElseThrow(() -> new CallerFault("Content type is unavailable"));
    if (expected != current.controlRevision()) throw new CallerFault("INFORMATION_TYPE_CONFLICT");
    if (activate && !current.state().equals("draft")
        || !activate && !current.state().equals("active"))
      throw new CallerFault("Content type lifecycle transition is unavailable");
    if (activate)
      jdbc.update(
          "UPDATE information_content_type_versions SET state='deprecated' WHERE type_id=? AND state='active'",
          current.contract().type());
    var retained =
        jdbc.queryForObject(
            "UPDATE information_content_type_versions SET state=? WHERE id=? RETURNING state,created_at",
            JdbcInformationContentTypeRepository::lifecycle,
            activate ? "active" : "deprecated",
            version);
    long revision =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "UPDATE information_content_types SET control_revision=control_revision+1 WHERE id=? RETURNING control_revision",
                Long.class,
                current.contract().type()));
    // V143 forbids definition/identity changes. Reuse the already locked, validated contract;
    // RETURNING supplies actual lifecycle and control facts without reparsing the entire schema.
    return retainedVersion(current.contract(), Objects.requireNonNull(retained), revision);
  }
}
