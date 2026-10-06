package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayCausation;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Traverses immutable admission input by delivery identity; no source bytes leave this store. */
public final class JdbcRelayForwardingHistory implements RelayForwardingHistory {
  private final JdbcTemplate jdbc;

  public JdbcRelayForwardingHistory(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public Optional<RelayCausation> causation(Relay.Publication publication, int maximum) {
    Objects.requireNonNull(publication);
    RelayValues.limit(maximum, 32);
    var carried = publication.event().causation();
    if (carried != null) return Optional.of(carried);
    String id = publication.event().eventId(), publisher = publication.event().publisher();
    String target = publication.topic().name();
    String pointer = publication.event().causationId();
    int depth = 0;
    while (publisher.equals("relay:relay.publish")) {
      if (depth == maximum) return Optional.empty();
      UUID delivery;
      try {
        if (!id.startsWith("relay:")) return Optional.empty();
        delivery = UUID.fromString(id.substring(6));
        if (!id.equals("relay:" + delivery)) return Optional.empty();
      } catch (IllegalArgumentException invalid) {
        return Optional.empty();
      }
      var parent =
          jdbc
              .query(
                  """
          SELECT a.event_id,a.publisher,a.topic,a.causation_id,a.relay_causation::text FROM relay_deliveries d JOIN relay_admissions a
            ON a.scope_key=d.scope_key AND a.topic=d.topic AND a.subscriber=d.subscriber
            AND a.publication_position=d.publication_position
          WHERE d.id=? AND d.scope_key=? AND d.receiver='relay.publish' AND d.publish_to=?
          """,
                  (row, index) ->
                      new Parent(
                          row.getString("event_id"),
                          row.getString("publisher"),
                          row.getString("topic"),
                          row.getString("causation_id"),
                          RelayCausationCodec.read(row.getString("relay_causation"))),
                  delivery,
                  RelayScopeCodec.write(publication.topic()),
                  target)
              .stream()
              .findFirst();
      if (parent.isEmpty()) return Optional.empty();
      if (pointer == null || !pointer.equals(parent.get().id())) return Optional.empty();
      id = parent.get().id();
      pointer = parent.get().pointer();
      publisher = parent.get().publisher();
      target = parent.get().topic();
      depth++;
      if (parent.get().causation() != null) {
        var inherited = parent.get().causation();
        if (inherited.depth() < 0 || inherited.depth() + depth > 32) return Optional.empty();
        return Optional.of(
            new RelayCausation(
                inherited.rootId(), publication.event().causationId(), inherited.depth() + depth));
      }
    }
    // A legacy lifecycle notice cannot prove the owning job was independent. External/plain
    // publications may start roots, but an unverifiable runtime-derived event may not.
    if (publisher.startsWith("relay:")
        || (publication.event().payload() instanceof RelayPayload.Lifecycle))
      return Optional.empty();
    return Optional.of(
        new RelayCausation(id, depth == 0 ? null : publication.event().causationId(), depth));
  }

  private record Parent(
      String id, String publisher, String topic, String pointer, RelayCausation causation) {}
}
