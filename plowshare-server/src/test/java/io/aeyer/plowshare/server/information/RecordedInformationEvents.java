package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Information;
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

  /**
   * Deliberately unimplemented. Reading an event feed is a question about delivery authority — who
   * may receive an event for which scope — and this fake has no scopes, no subscriptions and no
   * read authority to answer it with. Returning everything to everyone would make a test asserting
   * "an account without access sees nothing" pass against an implementation that delivered to it.
   * Such a test needs a real broker; {@code RelayInformationEventsDatabaseTest} is where it lives.
   */
  @Override
  public Information.Events read(InformationContext context, long after, int limit) {
    throw new UnsupportedOperationException(
        "an information event feed needs a real Relay broker, not a recording fake");
  }

  /** Everything announced since this fake was created, in announcement order. */
  public synchronized List<Occurrence> announced() {
    return List.copyOf(announced);
  }
}
