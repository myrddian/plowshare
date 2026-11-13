package io.aeyer.plowshare.server.cache;

import java.time.Duration;
import java.util.Set;

/** Database infrastructure boundary owning one dedicated, unpooled notification session. */
public interface ProjectionNotificationSource {
  Session open();

  interface Session extends AutoCloseable {
    /** Commits LISTEN registration before returning; recovery baseline must happen afterwards. */
    void listen();

    /** Bounded blocking poll; results contain fixed domain hints, never source content. */
    Set<ProjectionVersions.Domain> poll(Duration timeout);

    /**
     * Commits a delivery probe addressed to this session only. PostgreSQL delivers notifications to
     * a listener in commit order, so once a poll reports the probe's echo, every change committed
     * before the probe has been returned by this or an earlier poll. Returns false when this
     * session cannot probe; a failed or timed-out send throws like a failed poll.
     */
    default boolean heartbeat(long sequence) {
      return false;
    }

    /** Highest probe sequence echoed to this session by polls so far, or 0 before the first. */
    default long heartbeatsReceived() {
      return 0;
    }

    /** May run concurrently with poll to unblock lifecycle shutdown. */
    @Override
    void close();
  }
}
