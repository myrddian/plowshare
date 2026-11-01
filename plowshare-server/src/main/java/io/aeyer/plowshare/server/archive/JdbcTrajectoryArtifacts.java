package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.ToolContent;
import io.aeyer.plowshare.server.blobs.BlobClaim;
import io.aeyer.plowshare.server.blobs.BlobKey;
import io.aeyer.plowshare.server.blobs.BlobRepository;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import io.aeyer.plowshare.server.relay.tools.RelayToolCodec;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Admission provenance is copied once under the invocation lock; later reads need only entry
 * claims.
 */
public final class JdbcTrajectoryArtifacts implements TrajectoryArtifacts {
  private final JdbcTemplate jdbc;
  private final BlobRepository blobs;

  public JdbcTrajectoryArtifacts(JdbcTemplate jdbc, BlobRepository blobs) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.blobs = Objects.requireNonNull(blobs);
  }

  @Override
  public void retain(EntryRecord entry, String job) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Trajectory claims require the entry transaction");
    if (entry.observation() == null || job == null)
      throw new IllegalArgumentException("Typed entry requires invocation and owning job");
    // Accounting runs survive job replacement. Validate the retained turn ownership instead of
    // equating i.run_id with the entry's job. Same invocation lock as settlement/admission
    // deletion;
    // never hold it across disk I/O.
    var retained =
        jdbc.query(
            """
        SELECT i.result FROM relay_tool_invocations i
        JOIN conversations c ON c.project_id=i.project_id AND c.owner_handle=i.account
        JOIN inference_run_ownership o ON o.conversation_id=c.id AND o.turn_ordinal=?
        WHERE c.id=? AND i.id=? AND i.call_id=? AND i.result IS NOT NULL
          AND o.attribution->>'accountHandle'=i.account
          AND o.attribution->>'projectId'=i.project_id::text
          AND o.attribution->'runs'->>'id'=i.run_id
          AND o.attribution->>'status'='ATTRIBUTED'
        FOR UPDATE OF i
        """,
            (row, n) -> RelayToolCodec.result(row.getString(1)),
            entry.turnOrdinal(),
            entry.conversationId(),
            entry.observation().invocation(),
            entry.toolCallId());
    if (retained.size() != 1
        || !entry.observation().original().equals(retained.getFirst().content()))
      throw new CallerFault("Tool observation does not belong to this invocation and conversation");
    var parts = entry.observation().original().images();
    var parameters =
        new MapSqlParameterSource()
            .addValue("invocation", entry.observation().invocation())
            .addValue("slots", parts.stream().map(ToolContent.Part::ordinal).toList());
    record Admission(int slot, String id, BlobKey key, long bytes, String mimeType) {}
    var admissions =
        new NamedParameterJdbcTemplate(jdbc)
            .query(
                """
        SELECT a.ordinal,a.image_id,a.sha256,a.byte_length,c.scope,a.mime_type
        FROM invocation_image_admissions a
        JOIN relay_tool_invocations i ON i.id=a.invocation_id
        JOIN binary_blob_claims c ON c.scope='projects/' || i.project_id
          AND c.sha256=a.sha256 AND c.kind='invocation-admission'
          AND c.owner=i.id::text AND c.slot=a.ordinal AND c.retention='RELEASABLE'
        JOIN binary_blob_aliases x ON x.scope=c.scope AND x.alias=a.image_id AND x.sha256=a.sha256
        WHERE a.invocation_id=:invocation AND a.ordinal IN (:slots)
        ORDER BY c.scope,a.sha256,a.ordinal
        """,
                parameters,
                (row, n) ->
                    new Admission(
                        row.getInt(1),
                        row.getString(2),
                        new BlobKey(row.getString(5), row.getString(3)),
                        row.getLong(4),
                        row.getString(6)));
    if (admissions.size() != parts.size()
        || parts.stream()
            .anyMatch(
                part ->
                    admissions.stream()
                        .noneMatch(
                            a -> a.slot() == part.ordinal() && a.id().equals(part.imageId()))))
      throw new CallerFault("Tool observation admission is unavailable");
    for (var admission : admissions) {
      var part =
          parts.stream().filter(p -> p.ordinal() == admission.slot()).findFirst().orElseThrow();
      boolean observe =
          entry.observation().effective() != null && part.use() != ToolContent.Use.OUTPUT;
      var claim = claim(entry.conversationId(), entry.ordinal(), admission.slot());
      blobs.acquire(admission.key(), claim, admission.bytes());
      if (jdbc.update(
              """
          INSERT INTO trajectory_image_references(conversation_id,entry_ordinal,slot,image_id,
            blob_scope,sha256,byte_length,observe,mime_type)
          VALUES(?,?,?,?,?,?,?,?,?)
          """,
              entry.conversationId(),
              entry.ordinal(),
              admission.slot(),
              admission.id(),
              admission.key().scope(),
              admission.key().sha256(),
              admission.bytes(),
              observe,
              admission.mimeType())
          != 1) throw new IllegalStateException("Trajectory image reference was not retained");
    }
  }

  @Override
  public List<Image> read(List<Content.StoredImage> images, UsageAttribution usage) {
    if (images.isEmpty()) return List.of();
    if (images.size() > 16
        || usage.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED
        || usage.scope() != UsageAttribution.Scope.PROJECT
        || usage.conversations().id() == null
        || images.stream()
            .anyMatch(image -> !image.conversation().equals(usage.conversations().id())))
      throw new CallerFault("Image observations require their owning conversation");
    var parameters =
        new MapSqlParameterSource()
            .addValue("conversation", usage.conversations().id())
            .addValue("project", usage.projectId())
            .addValue("account", usage.accountHandle())
            .addValue("system", usage.status() == UsageAttribution.Status.SYSTEM)
            .addValue(
                "entries", images.stream().map(Content.StoredImage::entry).distinct().toList());
    // The live inference authorization is checked by the dispatcher. Attribution narrows the
    // immutable source; it never grants access by itself. No per-image entity/permission reloads.
    var retained =
        new NamedParameterJdbcTemplate(jdbc)
            .query(
                """
        SELECT r.* FROM trajectory_image_references r
        JOIN entries e ON e.conversation_id=r.conversation_id AND e.ordinal=r.entry_ordinal
        JOIN conversations c ON c.id=e.conversation_id
        WHERE c.id=:conversation AND c.project_id::text=:project
          AND r.blob_scope='projects/' || c.project_id
          AND (:system OR c.owner_handle=:account) AND e.ejected_at IS NULL AND r.observe
          AND r.entry_ordinal IN (:entries)
        """,
                parameters,
                (row, n) -> {
                  var address =
                      new Content.StoredImage(
                          row.getString("conversation_id"),
                          row.getInt("entry_ordinal"),
                          row.getInt("slot"),
                          row.getString("image_id"));
                  return new Image(
                      address,
                      new BlobKey(row.getString("blob_scope"), row.getString("sha256")),
                      claim(address.conversation(), address.entry(), address.slot()),
                      row.getLong("byte_length"),
                      row.getString("mime_type"));
                });
    return images.stream()
        .map(
            image ->
                retained.stream()
                    .filter(i -> i.address().equals(image))
                    .findFirst()
                    .orElseThrow(() -> new CallerFault("Trajectory image is unavailable")))
        .toList();
  }

  private static BlobClaim claim(String conversation, int ordinal, int slot) {
    return new BlobClaim(
        "trajectory-entry", conversation + ":" + ordinal, slot, BlobClaim.Retention.RELEASABLE);
  }
}
