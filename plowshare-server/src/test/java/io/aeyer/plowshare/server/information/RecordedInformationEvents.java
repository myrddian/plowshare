package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Information;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An in-memory {@link InformationEvents} for tests that assert on catalogue rows rather than on
 * Relay. It records what a mutation announced so a test can assert on it, and reads back what it
 * recorded, which makes it a usable fake rather than a silent discard.
 *
 * <p>It is deliberately <b>not</b> a production fallback. It applies no scope mapping, no read
 * authority and no retention, so a test that cares about any of those — who receives an event, what
 * a home scope maps to, what a scopeless home refuses — must compose the real publisher against a
 * real broker. {@code RelayInformationEventsDatabaseTest} does exactly that.
 */
public final class RecordedInformationEvents implements InformationEvents {
  private final List<Occurrence> announced = new ArrayList<>();

  @Override
  public synchronized void publish(Occurrence event) {
    announced.add(event);
  }

  @Override
  public synchronized Information.Events read(InformationContext context, long after, int limit) {
    var page = new ArrayList<Information.Event>();
    long cursor = after;
    for (long position = after + 1;
        position <= announced.size() && page.size() < limit;
        position++) {
      var event = announced.get((int) position - 1);
      cursor = position;
      page.add(
          new Information.Event(
              position,
              event.revision(),
              event.generation(),
              event.stage(),
              event.action(),
              event.detail() == null ? "" : event.detail(),
              Instant.EPOCH,
              context.corpus().documentType(),
              null));
    }
    return new Information.Events(page, cursor);
  }

  /** Everything announced since this fake was created, in announcement order. */
  public synchronized List<Occurrence> announced() {
    return List.copyOf(announced);
  }
}
