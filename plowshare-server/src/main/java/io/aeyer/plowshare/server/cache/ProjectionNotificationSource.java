package io.aeyer.plowshare.server.cache;

import java.time.Duration;
import java.util.Set;

/** Database infrastructure boundary owning one dedicated, unpooled notification session. */
public interface ProjectionNotificationSource {
  Session open();

  /**
   * The hints of one poll: changed domains, and changed items of item-tracked domains. An item
   * {@link ProjectionVersions.Item#isAll} stands for every item of its domain. Both sets are
   * bounded; hints may be coalesced or duplicated, and never carry source content.
   */
  record Changes(Set<ProjectionVersions.Domain> domains, Set<ProjectionVersions.Item> items) {
    public static final Changes NONE = new Changes(Set.of(), Set.of());

    public Changes {
      domains = Set.copyOf(domains);
      items = Set.copyOf(items);
    }

    public boolean isEmpty() {
      return domains.isEmpty() && items.isEmpty();
    }
  }

  interface Session extends AutoCloseable {
    /** Commits LISTEN registration before returning; recovery baseline must happen afterwards. */
    void listen();

    /** Bounded blocking poll; results contain fixed domain hints, never source content. */
    Set<ProjectionVersions.Domain> poll(Duration timeout);

    /**
     * Bounded blocking poll returning item hints as well. Sessions without keyed notifications
     * report domains only, which item-scoped observers never mistake for item changes.
     */
    default Changes pollChanges(Duration timeout) {
      return new Changes(poll(timeout), Set.of());
    }

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
