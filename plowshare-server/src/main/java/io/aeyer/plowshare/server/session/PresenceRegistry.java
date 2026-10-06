package io.aeyer.plowshare.server.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live sessions serving each project's single client location.
 *
 * <p>Multiple clients may serve the same machine and root. The first remains the routing presence
 * until it withdraws; the next surviving declaration then serves new work. Reconnecting a session
 * updates its own claim without changing that order. A different location is refused, including a
 * move by the primary while another client still serves the old location.
 *
 * <p>Each session roots at most one project. Declarations and withdrawals share a lock because
 * moving a session and choosing a replacement are compound changes. Readers see immutable ordered
 * snapshots through a concurrent map, never a partially edited list. Project keys are the friendly
 * names carried by {@code Home}; no filesystem paths are resolved on this server.
 *
 * <p>Presence is routing metadata, not authorization. {@code FileChannelHandler} authenticates the
 * session and checks project membership before declaring. {@code RunProviders} checks the run
 * owner's project role and Personal ownership before using a presence. Every file request remains
 * bounded by server grants and the client workspace fence. Co-located clients receive no additional
 * authority by joining this registry.
 *
 * <p>Withdrawal changes routing for future work only. Existing providers retain their session,
 * whose outstanding requests fail when its socket closes. They are never replayed through another
 * client: a failed file write may already have reached disk.
 */
public final class PresenceRegistry implements ProjectPresences {
  private static final Logger log = LoggerFactory.getLogger(PresenceRegistry.class);

  private final Map<String, List<Presence>> byProject = new ConcurrentHashMap<>();
  private final ReentrantLock claiming = new ReentrantLock();

  /**
   * Declare one session's location, freeing its previous project only after validation.
   *
   * @return the accepted claim; it need not be the primary returned by {@link #serving}
   * @throws PresenceConflictException if any other session serves a different location for this
   *     project. A refusal preserves every existing claim, including this session's previous one.
   */
  public Presence declare(Presence presence) {
    Objects.requireNonNull(presence, "presence");
    claiming.lock();
    try {
      List<Presence> held = byProject.getOrDefault(presence.project(), List.of());
      for (Presence peer : held) {
        if (!peer.session().equals(presence.session()) && !peer.sameLocation(presence)) {
          throw new PresenceConflictException(
              peer.canonicalName()
                  + " already roots '"
                  + presence.project()
                  + "'; "
                  + presence.canonicalName()
                  + " cannot also root it. A project exists in exactly one location."
                  + " Connect both clients to the same directory, or close the client at the wrong"
                  + " location before moving it. (sessions '"
                  + peer.session()
                  + "' and '"
                  + presence.session()
                  + "')");
        }
      }
      // Replace in place so a primary reconnect never promotes a standby or changes routing.
      List<Presence> next = new ArrayList<>(held);
      int index = -1;
      for (int i = 0; i < next.size(); i++) {
        if (next.get(i).session().equals(presence.session())) {
          index = i;
          break;
        }
      }
      if (index >= 0) {
        next.set(index, presence);
      } else {
        withdrawWhileHolding(presence.session());
        next.add(presence);
      }
      byProject.put(presence.project(), List.copyOf(next));
      log.info(
          "Session '{}' roots the project '{}' at {}.",
          presence.session(),
          presence.project(),
          presence.canonicalName());
      return presence;
    } finally {
      claiming.unlock();
    }
  }

  /** The oldest surviving declaration, or empty for a global or currently unserved project. */
  @Override
  public Optional<Presence> serving(String project) {
    if (project == null || project.isBlank()) return Optional.empty();
    List<Presence> held = byProject.get(project);
    return held == null ? Optional.empty() : Optional.of(held.getFirst());
  }

  /** A session's own claim, including a standby serving the same location as the primary. */
  public Optional<Presence> rootedBy(String session) {
    if (session == null) return Optional.empty();
    return byProject.values().stream()
        .flatMap(List::stream)
        .filter(presence -> presence.session().equals(session))
        .findFirst();
  }

  /**
   * Withdraw only this session. Closing the primary promotes the next surviving co-located client;
   * closing a standby or an already withdrawn session leaves primary routing unchanged.
   *
   * @return whether a claim was removed
   */
  public boolean withdraw(String session) {
    Objects.requireNonNull(session, "session");
    claiming.lock();
    try {
      return withdrawWhileHolding(session);
    } finally {
      claiming.unlock();
    }
  }

  /** Number of projects with at least one declaration; visible to registry tests. */
  int count() {
    return byProject.size();
  }

  private boolean withdrawWhileHolding(String session) {
    boolean removed = false;
    for (Map.Entry<String, List<Presence>> entry : byProject.entrySet()) {
      List<Presence> next =
          entry.getValue().stream().filter(peer -> !peer.session().equals(session)).toList();
      if (next.size() == entry.getValue().size()) continue;
      if (next.isEmpty()) byProject.remove(entry.getKey());
      else byProject.put(entry.getKey(), next);
      removed = true;
    }
    return removed;
  }
}
