package io.aeyer.plowshare.server.relay.tools;

import java.time.Duration;

/**
 * Wake-up hints for a caller waiting on a Relay tool result.
 *
 * <p>A wake-up never carries a result or an authorization decision: the waiter must still read the
 * retained result and re-check authority itself. A missed hint is therefore harmless, because every
 * wait is bounded by the caller's ceiling.
 */
public interface RelayToolWakeups {
  /**
   * Registers a waiter. Register before the first read so a change between reading and waiting
   * wakes the next {@link Wakeup#await}. The caller must close the registration on every exit.
   */
  Wakeup open();

  /** One owner awaits each handle. */
  interface Wakeup extends AutoCloseable {
    /**
     * Blocks for at most {@code maximum}, returning early on any hint.
     *
     * @return whether an authority change (or loss of the notification transport) was signalled
     *     since the previous call; a relay publication or a timeout returns {@code false}
     */
    boolean await(Duration maximum) throws InterruptedException;

    @Override
    void close();
  }

  /**
   * Without a signal source the waiter sleeps for the whole ceiling. The caller's staleness bound,
   * not this wait, then drives authority rechecks.
   */
  RelayToolWakeups TIMED =
      () ->
          new Wakeup() {
            @Override
            public boolean await(Duration maximum) throws InterruptedException {
              Thread.sleep(maximum);
              return false;
            }

            @Override
            public void close() {}
          };
}
