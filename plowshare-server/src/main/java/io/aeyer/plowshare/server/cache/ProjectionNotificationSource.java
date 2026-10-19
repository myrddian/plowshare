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

    /** May run concurrently with poll to unblock lifecycle shutdown. */
    @Override
    void close();
  }
}
