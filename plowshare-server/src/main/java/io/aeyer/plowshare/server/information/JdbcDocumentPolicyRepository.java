package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.InformationMigration.*;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bound quarantine queries. The owning service supplies the authorized account and transaction. */
public final class JdbcDocumentPolicyRepository implements DocumentPolicyRepository {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final JdbcTemplate jdbc;

  public JdbcDocumentPolicyRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  private static void page(int limit, int offset) {
    if (limit < 1 || limit > 100 || offset < 0)
      throw new IllegalArgumentException("limit 1..100 and nonnegative offset required");
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank() || value.length() > 8192 || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException(name + " required");
    return value;
  }

  public Inventory inventory(int limit, int offset) {
    page(limit, offset);
    return new Inventory(
        jdbc.query(
            "SELECT d.id,d.source_name,d.title,p.visibility FROM documents d JOIN information_document_policies p ON p.document_id=d.id WHERE p.visibility='quarantined' ORDER BY d.id LIMIT ? OFFSET ?",
            (r, n) ->
                new Document(
                    r.getObject("id", UUID.class),
                    r.getString("source_name"),
                    r.getString("title"),
                    r.getString("visibility")),
            limit,
            offset),
        jdbc.query(
            "SELECT payload_id,reason FROM information_quarantined_payloads ORDER BY payload_id LIMIT ? OFFSET ?",
            (r, n) -> new Payload(r.getString("payload_id"), r.getString("reason")),
            limit,
            offset));
  }

  public Inspection inspect(String payload, int limit, int offset) {
    required(payload, "payload");
    page(limit, offset);
    if (jdbc.queryForList(
            "SELECT payload_id FROM information_quarantined_payloads WHERE payload_id=?",
            String.class,
            payload)
        .isEmpty()) throw new NotFoundFault("no quarantined payload available");
    return new Inspection(
        jdbc.query(
            "SELECT id,owner_handle,agent FROM conversations WHERE id=?",
            (r, n) -> new Log(r.getString("id"), r.getString("owner_handle"), r.getString("agent")),
            payload),
        jdbc.query(
            "SELECT ordinal,kind,content,tool_calls FROM entries WHERE conversation_id=? ORDER BY ordinal LIMIT ? OFFSET ?",
            (r, n) ->
                new Entry(
                    r.getInt("ordinal"),
                    r.getString("kind"),
                    r.getString("content"),
                    calls(r.getString("tool_calls"))),
            payload,
            limit,
            offset),
        jdbc.query(
            "SELECT id,agent,ending FROM jobs WHERE id=?",
            (r, n) -> new Job(r.getString("id"), r.getString("agent"), r.getString("ending")),
            payload));
  }

  private static List<ToolCall> calls(String encoded) {
    if (encoded == null) return null;
    try {
      return List.copyOf(JSON.readValue(encoded, new TypeReference<List<ToolCall>>() {}));
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("invalid persisted tool calls", invalid);
    }
  }

  public void lockPayload(String payload) {
    if (jdbc.queryForList(
            "SELECT payload_id FROM information_quarantined_payloads WHERE payload_id=? FOR UPDATE",
            String.class,
            required(payload, "payload"))
        .isEmpty()) throw new NotFoundFault("no quarantined payload available");
  }

  public void requireReadable(UUID revision, String owner, InformationContext.Selection selection) {
    Objects.requireNonNull(revision);
    Objects.requireNonNull(selection);
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT information_readable(?,?,?,?,?)",
            Boolean.class,
            revision,
            required(owner, "owner"),
            selection.scope().name().toLowerCase(java.util.Locale.ROOT),
            selection.project(),
            selection.includeShared())))
      throw new CallerFault(
          "adopt and authorise every reviewed source before releasing its payload");
  }

  public void adoptPayload(String payload, String owner) {
    required(payload, "payload");
    required(owner, "owner");
    var owners =
        jdbc.query(
            "SELECT owner_handle FROM conversations WHERE id=?",
            (r, n) -> java.util.Optional.ofNullable(r.getString("owner_handle")),
            payload);
    if (!owners.isEmpty()
        && owners.getFirst().isPresent()
        && !owner.equals(owners.getFirst().get()))
      throw new CallerFault("payload already belongs to a different account");
    jdbc.update(
        "UPDATE conversations SET owner_handle=? WHERE id=? AND owner_handle IS NULL",
        owner,
        payload);
  }

  public void releasePayload(String payload) {
    if (jdbc.update(
            "DELETE FROM information_quarantined_payloads WHERE payload_id=?",
            required(payload, "payload"))
        != 1) throw new NotFoundFault("no quarantined payload available");
  }

  public void audit(
      String actor,
      String payload,
      String owner,
      String action,
      List<UUID> revisions,
      String reason,
      Instant at) {
    if (!java.util.Set.of("inspect", "release").contains(action))
      throw new IllegalArgumentException("unknown adoption action");
    jdbc.update(
        "INSERT INTO information_payload_assignments VALUES(?,?,?,?,?,CAST(? AS jsonb),?,?)",
        UUID.randomUUID(),
        required(payload, "payload"),
        required(actor, "actor"),
        owner,
        action,
        InformationJson.json(List.copyOf(revisions)),
        required(reason, "reason"),
        Objects.requireNonNull(at).atOffset(ZoneOffset.UTC));
  }

  public void assign(
      String actor,
      UUID document,
      String owner,
      InformationContext.Selection destination,
      String reason,
      Instant at) {
    Objects.requireNonNull(document);
    Objects.requireNonNull(destination);
    required(actor, "actor");
    required(owner, "owner");
    required(reason, "reason");
    Objects.requireNonNull(at);
    String project = destination.project();
    Long projectId =
        project == null
            ? null
            : jdbc.queryForObject("SELECT id FROM projects WHERE name = ?", Long.class, project);
    String policy = destination.scope().name().toLowerCase(java.util.Locale.ROOT);
    if (jdbc.update(
            "UPDATE information_document_policies SET owner_handle = ?, visibility = ?, project_id = ?, assigned_at = ? WHERE document_id = ? AND visibility = 'quarantined' AND owner_handle IS NULL",
            owner,
            policy,
            projectId,
            at.atOffset(ZoneOffset.UTC),
            document)
        != 1) throw new NotFoundFault("no quarantined document is available for assignment");
    jdbc.update(
        "UPDATE information_resources SET owner_handle=?,project_id=?,namespace=? WHERE id=? AND namespace='legacy' AND owner_handle IS NULL",
        owner,
        projectId,
        project == null ? "account:" + owner : "project:" + project,
        document);
    jdbc.update(
        "INSERT INTO information_policy_assignments (id, document_id, actor_handle, owner_handle, visibility, project_name, reason, assigned_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        document,
        actor,
        owner,
        policy,
        project,
        reason,
        at.atOffset(ZoneOffset.UTC));
  }
}
