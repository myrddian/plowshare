package io.aeyer.plowshare.server.relay;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Traverses immutable admission input by delivery identity; no source bytes leave this store. */
public final class JdbcRelayForwardingHistory implements RelayForwardingHistory {
  private final JdbcTemplate jdbc;

  public JdbcRelayForwardingHistory(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public OptionalInt depth(Relay.Publication publication, int maximum) {
    Objects.requireNonNull(publication);
    RelayValues.limit(maximum, 32);
    String id = publication.event().eventId(), publisher = publication.event().publisher();
    String target = publication.topic().name();
    int depth = 0;
    while (publisher.equals("relay:relay.publish")) {
      if (depth == maximum) return OptionalInt.of(depth);
      UUID delivery;
      try {
        if (!id.startsWith("relay:")) return OptionalInt.empty();
        delivery = UUID.fromString(id.substring(6));
        if (!id.equals("relay:" + delivery)) return OptionalInt.empty();
      } catch (IllegalArgumentException invalid) {
        return OptionalInt.empty();
      }
      var parent =
          jdbc
              .query(
                  """
          SELECT a.event_id,a.publisher,a.topic FROM relay_deliveries d JOIN relay_admissions a
            ON a.scope_key=d.scope_key AND a.topic=d.topic AND a.subscriber=d.subscriber
            AND a.publication_position=d.publication_position
          WHERE d.id=? AND d.scope_key=? AND d.receiver='relay.publish' AND d.publish_to=?
          """,
                  (row, index) ->
                      new Parent(
                          row.getString("event_id"),
                          row.getString("publisher"),
                          row.getString("topic")),
                  delivery,
                  RelayScopeCodec.write(publication.topic()),
                  target)
              .stream()
              .findFirst();
      if (parent.isEmpty()) return OptionalInt.empty();
      id = parent.get().id();
      publisher = parent.get().publisher();
      target = parent.get().topic();
      depth++;
    }
    return OptionalInt.of(depth);
  }

  private record Parent(String id, String publisher, String topic) {}
}
