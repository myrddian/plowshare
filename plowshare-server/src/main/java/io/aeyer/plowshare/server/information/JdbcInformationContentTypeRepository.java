package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.*;
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
  private static final String SELECT =
      "SELECT v.*,t.slug,t.label,t.description,t.control_revision FROM information_content_type_versions v JOIN information_content_types t ON t.id=v.type_id";
  private final JdbcTemplate jdbc;

  public JdbcInformationContentTypeRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  private static InformationContent.Version decode(ResultSet row, int index) throws SQLException {
    try {
      var schema = JSON.readValue(row.getString("schema_definition"), SchemaDefinition.class);
      var presentation =
          DocumentMetadata.presentation(
              schema, JSON.readValue(row.getString("presentation"), SchemaValue.class));
      return new InformationContent.Version(
          new InformationContent.Contract(
              row.getObject("type_id", UUID.class),
              row.getObject("id", UUID.class),
              row.getInt("ordinal"),
              row.getString("slug"),
              row.getString("label"),
              row.getString("description"),
              row.getString("profile"),
              row.getString("definition_hash"),
              schema,
              presentation),
          row.getString("state"),
          row.getLong("control_revision"),
          row.getTimestamp("created_at").toInstant().toString());
    } catch (java.io.IOException | IllegalArgumentException invalid) {
      throw new IllegalStateException("Invalid retained content type definition", invalid);
    }
  }

  @Override
  public InformationContent.Versions list(String namespace, boolean drafts, int limit, int offset) {
    if (limit < 1 || limit > 100 || offset < 0)
      throw new IllegalArgumentException("Invalid registry page");
    var rows =
        jdbc.query(
            SELECT
                + " WHERE t.namespace=?"
                + (drafts ? "" : " AND v.state<>'draft'")
                + " ORDER BY t.slug,v.ordinal DESC OFFSET ? LIMIT ?",
            JdbcInformationContentTypeRepository::decode,
            namespace,
            offset,
            limit + 1);
    return new InformationContent.Versions(
        rows.stream().limit(limit).toList(), rows.size() > limit);
  }

  @Override
  public Optional<InformationContent.Version> version(
      String namespace, UUID version, boolean lock) {
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
    var rows =
        jdbc.query(
            SELECT + " WHERE t.namespace=? AND v.id=?" + (lock ? " FOR UPDATE OF v" : ""),
            JdbcInformationContentTypeRepository::decode,
            namespace,
            Objects.requireNonNull(version));
    return rows.stream().findFirst();
  }

  @Override
  public Optional<InformationContent.Contract> attached(UUID revision, UUID version) {
    return jdbc
        .query(
            SELECT
                + " JOIN information_revision_content c ON c.type_version=v.id"
                + " JOIN information_revisions r ON r.id=c.revision_id WHERE c.revision_id=? AND c.type_version=?"
                + " AND NOT c.redacted AND r.availability<>'deleted'",
            JdbcInformationContentTypeRepository::decode,
            revision,
            version)
        .stream()
        .map(InformationContent.Version::contract)
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
            "SELECT next_version,control_revision FROM information_content_types WHERE id=? AND namespace=? FOR UPDATE",
            (row, index) -> new Control(row.getInt(1), row.getLong(2)),
            type,
            namespace);
    if (rows.isEmpty()) throw new CallerFault("Content type is unavailable");
    var control = rows.getFirst();
    if (expected != null && expected != control.revision())
      throw new CallerFault("INFORMATION_TYPE_CONFLICT");
    if (control.next() == Integer.MAX_VALUE)
      throw new CallerFault("Content type version allocation exhausted");
    UUID version = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_content_type_versions(id,type_id,ordinal,schema_definition,presentation,profile,definition_hash,created_by) VALUES(?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?,?)",
        version,
        type,
        control.next(),
        SchemaCodec.compactJson(schema),
        SchemaCodec.compactJson(presentation),
        DocumentMetadata.PROFILE,
        SchemaCodec.documentFingerprint(schema, DocumentMetadata.PROFILE, presentation),
        actor);
    jdbc.update(
        "UPDATE information_content_types SET next_version=next_version+1,control_revision=control_revision+1 WHERE id=?",
        type);
    return version(namespace, version, false).orElseThrow();
  }

  private record Control(int next, long revision) {}

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
    jdbc.update(
        "UPDATE information_content_type_versions SET state=? WHERE id=?",
        activate ? "active" : "deprecated",
        version);
    jdbc.update(
        "UPDATE information_content_types SET control_revision=control_revision+1 WHERE id=?",
        current.contract().type());
    return version(namespace, version, false).orElseThrow();
  }
}
