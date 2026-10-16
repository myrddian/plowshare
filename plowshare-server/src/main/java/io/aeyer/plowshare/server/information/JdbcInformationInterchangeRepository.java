package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.InformationInterchange.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQL is restricted to dependency selection, destination fencing and immutable origin persistence.
 */
public final class JdbcInformationInterchangeRepository
    implements InformationInterchangeRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final JdbcTemplate jdbc;

  public JdbcInformationInterchangeRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  public List<UUID> dependencies(UUID revision) {
    Objects.requireNonNull(revision);
    return jdbc.queryForList(
        "SELECT input_revision FROM information_inputs WHERE derived_revision=? UNION SELECT coalesce(o.pinned_revision,information_navigation_head(o.target)) FROM information_reference_obligations o JOIN information_revisions r ON r.resource_id=o.source WHERE r.id=? AND coalesce(o.pinned_revision,information_navigation_head(o.target))<>?",
        UUID.class,
        revision,
        revision,
        revision);
  }

  public List<Link> relationships(InformationContext context, List<UUID> revisions) {
    Objects.requireNonNull(context);
    revisions = List.copyOf(revisions);
    if (revisions.isEmpty()
        || revisions.size() > 16
        || new HashSet<>(revisions).size() != revisions.size())
      throw new IllegalArgumentException("Select 1–16 distinct revisions");
    // Each placeholder is generated from a bounded typed list; values remain bound parameters.
    String slots = String.join(",", Collections.nCopies(revisions.size(), "?"));
    var args = new ArrayList<Object>();
    args.addAll(revisions);
    args.addAll(revisions);
    args.addAll(revisions);
    var visible = InformationSql.relationshipNeighbour(context, true);
    args.add(context.account());
    args.add(context.selection().scope().name().toLowerCase(Locale.ROOT));
    args.add(context.selection().project());
    args.add(context.selection().includeShared());
    args.addAll(visible.arguments());
    // Historical bytes do not grant access to a resource's current relationships. Use the same
    // source/current-head and neighbour disclosure predicates as ordinary outgoing navigation.
    return jdbc
        .query(
            "SELECT e.source,e.target,e.pinned_revision,e.relation FROM information_relationships e WHERE NOT e.removed AND e.source IN (SELECT resource_id FROM information_revisions WHERE id IN ("
                + slots
                + ")) AND e.target IN (SELECT resource_id FROM information_revisions WHERE id IN ("
                + slots
                + ")) AND (e.pinned_revision IS NULL OR e.pinned_revision IN ("
                + slots
                + ")) AND information_readable(information_navigation_head(e.source),?,?,?,?) AND "
                + visible.sql()
                + " ORDER BY e.source,e.target,e.relation,e.pinned_revision LIMIT 65",
            (r, n) ->
                new Link(
                    r.getObject(1, UUID.class),
                    r.getObject(2, UUID.class),
                    r.getObject(3, UUID.class),
                    r.getString(4)),
            args.toArray())
        .stream()
        .distinct()
        .toList();
  }

  public void lockDestination(InformationContext context, String name) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
        Boolean.class,
        InformationContentTypeService.namespace(context) + ":" + name);
  }

  public void retain(UUID revision, Origin origin, List<UUID> dependencies) {
    Objects.requireNonNull(revision);
    Objects.requireNonNull(origin);
    dependencies = List.copyOf(dependencies);
    if (dependencies.size() > 17
        || dependencies.contains(revision)
        || new HashSet<>(dependencies).size() != dependencies.size())
      throw new IllegalArgumentException("Invalid retained import dependencies");
    jdbc.update(
        "INSERT INTO information_import_origins(revision_id,origin) VALUES(?,CAST(? AS jsonb))",
        revision,
        InformationJson.json(origin));
    for (UUID input : dependencies)
      jdbc.update(
          "INSERT INTO information_inputs(derived_revision,input_revision) VALUES(?,?) ON CONFLICT DO NOTHING",
          revision,
          input);
  }

  public Optional<Origin> origin(UUID revision) {
    Objects.requireNonNull(revision);
    return jdbc
        .query(
            "SELECT origin::text FROM information_import_origins WHERE revision_id=? AND NOT redacted",
            (r, n) -> {
              try {
                return JSON.readValue(r.getString(1), Origin.class);
              } catch (java.io.IOException invalid) {
                throw new IllegalStateException("Invalid retained import origin", invalid);
              }
            },
            revision)
        .stream()
        .findFirst();
  }
}
