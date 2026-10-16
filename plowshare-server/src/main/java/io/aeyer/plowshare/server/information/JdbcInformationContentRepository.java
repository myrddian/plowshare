package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Typed codec boundary and specialised SQL for immutable revision bindings and selective repair.
 */
public final class JdbcInformationContentRepository implements InformationContentRepository {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
  private final JdbcTemplate jdbc;
  private final InformationContentTypeRepository types;

  public JdbcInformationContentRepository(
      JdbcTemplate jdbc, InformationContentTypeRepository types) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.types = Objects.requireNonNull(types);
  }

  @Override
  public Optional<Head> head(String namespace, String name, boolean lock) {
    return jdbc
        .query(
            "SELECT q.id,r.id,q.owner_handle,q.kind,r.availability,EXISTS(SELECT 1 FROM information_revision_content binding WHERE binding.revision_id=r.id) AS typed FROM information_resources q JOIN information_revisions r ON r.resource_id=q.id"
                + " WHERE q.namespace=? AND q.source_name=? AND r.ordinal=(SELECT max(last.ordinal) FROM information_revisions last WHERE last.resource_id=q.id)"
                + (lock ? " FOR UPDATE OF q,r" : ""),
            (row, index) ->
                new Head(
                    row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class),
                    row.getString(3),
                    row.getString(4),
                    row.getString(5),
                    row.getBoolean(6)),
            namespace,
            name)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<InformationContent.Binding> binding(UUID revision) {
    return jdbc
        .query(
            "SELECT type_version,metadata_hash,metadata FROM information_revision_content WHERE revision_id=? AND NOT redacted",
            (row, index) -> {
              try {
                var version = row.getObject("type_version", UUID.class);
                var contract =
                    types
                        .attached(revision, version)
                        .orElseThrow(() -> new IllegalStateException("Retained contract missing"));
                var metadata =
                    DocumentMetadata.instance(
                        JSON.readValue(row.getString("metadata"), SchemaValue.class));
                return new InformationContent.Binding(
                    contract, row.getString("metadata_hash"), metadata);
              } catch (java.io.IOException invalid) {
                throw new IllegalStateException("Invalid retained metadata", invalid);
              }
            },
            revision)
        .stream()
        .findFirst();
  }

  @Override
  public void attach(UUID revision, InformationContent.Binding binding) {
    Objects.requireNonNull(binding);
    jdbc.update(
        "INSERT INTO information_revision_content(revision_id,type_version,profile,definition_hash,metadata_hash,metadata) VALUES(?,?,?,?,?,CAST(? AS jsonb))",
        revision,
        binding.contract().version(),
        binding.contract().profile(),
        binding.contract().definitionHash(),
        binding.metadataHash(),
        SchemaCodec.compactJson(binding.metadata()));
  }

  @Override
  public InformationContent.View view(UUID revision) {
    return jdbc.queryForObject(
        "SELECT processing_policy,starting_revision,revised_by,generation FROM information_revisions WHERE id=?",
        (row, index) -> {
          var reused =
              jdbc.query(
                  "SELECT stage,origin_revision,origin_generation,fingerprint FROM information_projection_reuse WHERE revision_id=? ORDER BY stage",
                  (origin, n) ->
                      new InformationContent.Reuse(
                          origin.getString(1),
                          origin.getObject(2, UUID.class),
                          origin.getLong(3),
                          origin.getString(4)),
                  revision);
          var stages =
              jdbc.query(
                  "SELECT stage FROM information_stage_authorizations WHERE revision_id=? AND generation=? ORDER BY stage",
                  (authorised, n) -> authorised.getString(1),
                  revision,
                  row.getLong("generation"));
          return new InformationContent.View(
              revision,
              binding(revision).orElse(null),
              row.getObject("starting_revision", UUID.class),
              row.getString("revised_by"),
              row.getString("processing_policy"),
              reused,
              stages);
        },
        revision);
  }

  @Override
  public boolean explicitOnly(UUID revision) {
    return "explicit_only"
        .equals(
            jdbc.queryForObject(
                "SELECT processing_policy FROM information_revisions WHERE id=?",
                String.class,
                revision));
  }

  @Override
  public void authorise(UUID revision, String actor, UUID request, List<String> stages) {
    if (request == null || stages.isEmpty() || stages.size() > 7)
      throw new IllegalArgumentException("Processing needs a stable request and stages");
    for (String stage : stages) {
      if (!InformationCatalogue.STAGES.contains(stage))
        throw new IllegalArgumentException("Invalid processing stage");
      if (jdbc.update(
                  "INSERT INTO information_stage_authorizations(revision_id,generation,stage,actor,request_id)"
                      + " SELECT r.id,r.generation,?,?,? FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id"
                      + " WHERE r.id=? AND q.owner_handle=? AND r.availability='active' ON CONFLICT (revision_id,generation,stage) DO NOTHING",
                  stage,
                  actor,
                  request,
                  revision,
                  actor)
              == 0
          && !Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM information_stage_authorizations a JOIN information_revisions r ON r.id=a.revision_id"
                      + " JOIN information_resources q ON q.id=r.resource_id WHERE a.revision_id=? AND a.generation=r.generation AND a.stage=? AND q.owner_handle=? AND r.availability='active')",
                  Boolean.class,
                  revision,
                  stage,
                  actor))) throw new IllegalStateException("Processing authorisation fence failed");
    }
  }

  @Override
  public void redact(UUID revision) {
    jdbc.update(
        "UPDATE information_revision_content SET metadata='{}'::jsonb,redacted=true WHERE revision_id=? AND NOT redacted",
        revision);
  }
}
