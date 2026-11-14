package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL facts for {@link KnowledgeReadability}. Each read is one statement over primary keys
 * and the indexes on {@code information_inputs(derived_revision,…)}, {@code
 * information_links(revision_id,…)}, {@code information_revision_content(revision_id)} and {@code
 * information_reference_obligations(source)}. Edge arrays read one row past {@link
 * KnowledgeFact#MAX_EDGES}, so an oversized row is detected without being hydrated further.
 */
public final class JdbcKnowledgeFactRepository implements KnowledgeFactRepository {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int EDGE_PROBE = KnowledgeFact.MAX_EDGES + 1;

  // information_revision_allowances is information_revisions JOIN execution_allowances; the
  // targeted check reads it as its outer filter, so the join's presence is a fact of its own.
  private static final String REVISION =
      """
      SELECT r.id,r.resource_id,r.availability='active' AS active,
       EXISTS(SELECT 1 FROM execution_allowances a WHERE a.id=r.processing_budget_id) AS allowance,
       EXISTS(SELECT 1 FROM information_revision_content c WHERE c.revision_id=r.id) AS typed,
       coalesce(r.processing_policy='explicit_only',false) AS explicit_only,
       ARRAY(SELECT i.input_revision FROM information_inputs i WHERE i.derived_revision=r.id
        ORDER BY i.input_revision LIMIT ?) AS inputs,
       ARRAY(SELECT l.project_id FROM information_links l WHERE l.revision_id=r.id
        ORDER BY l.project_id LIMIT ?) AS links
      FROM information_revisions r WHERE r.id=?
      """;

  // Both obligation arrays share one order, the relationship primary key, so they pair by index.
  private static final String RESOURCE =
      """
      SELECT q.id,q.home_scope_id,p.resource_id IS NOT NULL AS has_policy,p.audience,
       p.private_only,p.privacy_principal,information_navigation_policy(q.id)::text AS navigation,
       information_navigation_head(q.id) AS head,
       ARRAY(SELECT o.target FROM information_reference_obligations o WHERE o.source=q.id
        ORDER BY o.relationship LIMIT ?) AS targets,
       ARRAY(SELECT o.pinned_revision FROM information_reference_obligations o WHERE o.source=q.id
        ORDER BY o.relationship LIMIT ?) AS pins
      FROM information_resources q
      LEFT JOIN information_resource_policies p ON p.resource_id=q.id WHERE q.id=?
      """;

  private final JdbcTemplate jdbc;

  public JdbcKnowledgeFactRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public KnowledgeFact revision(UUID id) {
    Objects.requireNonNull(id, "revision");
    var rows = jdbc.query(REVISION, (row, index) -> revision(id, row), EDGE_PROBE, EDGE_PROBE, id);
    return rows.isEmpty() ? new KnowledgeFact.Absent(id) : rows.getFirst();
  }

  @Override
  public KnowledgeFact resource(UUID id) {
    Objects.requireNonNull(id, "resource");
    var rows = jdbc.query(RESOURCE, (row, index) -> resource(id, row), EDGE_PROBE, EDGE_PROBE, id);
    return rows.isEmpty() ? new KnowledgeFact.Absent(id) : rows.getFirst();
  }

  private static KnowledgeFact revision(UUID id, ResultSet row) throws SQLException {
    var inputs = uuids(row.getArray("inputs"));
    var links = longs(row.getArray("links"));
    if (inputs.size() > KnowledgeFact.MAX_EDGES || links.size() > KnowledgeFact.MAX_EDGES)
      return new KnowledgeFact.Oversized(id);
    return new KnowledgeFact.Revision(
        row.getObject("id", UUID.class),
        row.getObject("resource_id", UUID.class),
        row.getBoolean("active"),
        row.getBoolean("allowance"),
        row.getBoolean("typed"),
        row.getBoolean("explicit_only"),
        inputs,
        links);
  }

  private static KnowledgeFact resource(UUID id, ResultSet row) throws SQLException {
    var targets = uuids(row.getArray("targets"));
    var pins = nullableUuids(row.getArray("pins"));
    if (targets.size() > KnowledgeFact.MAX_EDGES) return new KnowledgeFact.Oversized(id);
    if (targets.size() != pins.size())
      throw new IllegalStateException("Reference obligation arrays disagree");
    var obligations = new ArrayList<KnowledgeFact.Obligation>();
    for (int index = 0; index < targets.size(); index++)
      obligations.add(new KnowledgeFact.Obligation(targets.get(index), pins.get(index)));
    Optional<KnowledgeFact.RootPolicy> policy =
        row.getBoolean("has_policy")
            ? Optional.of(
                new KnowledgeFact.RootPolicy(
                    audience(row.getString("audience")),
                    row.getBoolean("private_only"),
                    Optional.ofNullable(row.getObject("privacy_principal", UUID.class))))
            : Optional.empty();
    return new KnowledgeFact.Resource(
        row.getObject("id", UUID.class),
        Optional.ofNullable(row.getObject("home_scope_id", UUID.class)),
        policy,
        navigation(row.getString("navigation")),
        Optional.ofNullable(row.getObject("head", UUID.class)),
        obligations);
  }

  private static KnowledgeFact.Audience audience(String stored) {
    return switch (stored) {
      case "HOME" -> KnowledgeFact.Audience.HOME;
      case "GLOBAL_READ" -> KnowledgeFact.Audience.GLOBAL_READ;
      case "QUARANTINED" -> KnowledgeFact.Audience.QUARANTINED;
      case null, default -> throw new IllegalStateException("Unknown stored resource audience");
    };
  }

  /**
   * {@code coalesce((policy->>'ownerOnly')::boolean,true)} over the routine's object. The routine
   * always builds {@code ownerOnly} as a JSON boolean; any other shape is rejected rather than
   * coerced.
   */
  static KnowledgeFact.NavigationPolicy navigation(String stored) {
    if (stored == null) throw new IllegalStateException("Resource has no navigation policy");
    JsonNode policy;
    try {
      policy = JSON.readTree(stored);
    } catch (IOException invalid) {
      throw new IllegalStateException("Invalid navigation policy", invalid);
    }
    if (policy == null || !policy.isObject())
      throw new IllegalStateException("Invalid navigation policy");
    var ownerOnly = policy.get("ownerOnly");
    if (ownerOnly == null || ownerOnly.isNull()) return new KnowledgeFact.NavigationPolicy(true);
    if (!ownerOnly.isBoolean()) throw new IllegalStateException("Invalid navigation ownerOnly");
    return new KnowledgeFact.NavigationPolicy(ownerOnly.booleanValue());
  }

  private static List<UUID> uuids(Array array) throws SQLException {
    var values = new ArrayList<UUID>();
    for (var value : nullableUuids(array))
      values.add(value.orElseThrow(() -> new IllegalStateException("Unexpected NULL edge")));
    return values;
  }

  private static List<Optional<UUID>> nullableUuids(Array array) throws SQLException {
    var values = new ArrayList<Optional<UUID>>();
    for (Object value : (Object[]) array.getArray()) values.add(Optional.ofNullable((UUID) value));
    return values;
  }

  private static List<Long> longs(Array array) throws SQLException {
    var values = new ArrayList<Long>();
    for (Object value : (Object[]) array.getArray())
      values.add((Long) Objects.requireNonNull(value));
    return values;
  }
}
