package io.aeyer.plowshare.server.agents;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Which sessions asked to be sent the tokens as a model produces them, and which conversations each
 * session is showing.
 *
 * <p>Both are a socket's own state rather than a run's or a conversation's: neither outlives the
 * connection that asked for it, both are decided by whoever is reading rather than by whoever is
 * running, and both are forgotten together in {@link #forget} for that reason.
 *
 * <h2>Opt-in, and why it is the listener's choice rather than the run's</h2>
 *
 * <p>The alternative was a field on {@code agent.run} — "send me the deltas for this run" — and it
 * is the weaker of the two for a reason that is about roles rather than about effort. <b>The thing
 * that can or cannot keep up with a firehose is the listener, not the submitter.</b> They are the
 * same session today, but this server already separates the roles: a browser attaches a listener
 * without ever submitting a run, and the command line submits before its second socket exists. A
 * subscription that belonged to the run would be a decision taken by whoever asked the question on
 * behalf of whoever has to read the answer.
 *
 * <p><b>It is also the only one of the two that can be turned off.</b> A field on the run frame is
 * fixed for the life of the run; a person who opens the thinking, reads enough of it and wants it
 * to stop has no way to say so. That is a real thing to want and it costs one frame to support.
 *
 * <p>And the cheap argument, stated last because it is the least important: a field on the run
 * frame would have travelled through {@code Runs.Ask}, every one of its construction sites, the job
 * store and the runtime, to arrive at the same boolean this holds.
 *
 * <h2>Why this is its own bean and not a field on the channel</h2>
 *
 * <p><b>Because the channel cannot be injected into a frame handler.</b> {@code
 * EventChannelHandler} is constructed with the router, the router holds the handlers, and a handler
 * holding the channel closes that circle — which is the bean cycle that stopped this server booting
 * for fifteen commits on 2026-09-11 and was fixed by deferring an edge rather than by noticing it
 * in time. This depends on nothing, so both sides can depend on it.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p><b>No per-job subscription.</b> A session watches tokens or it does not. Per-job would mean a
 * set that grows with every run a session ever started and a decision about when an entry for a
 * finished job goes away; the coarser answer costs a client the deltas of a second run it did not
 * care about, which is cosmetic, and costs this class its entire lifecycle problem.
 */
@Component
public final class Watchers {

  private final Set<String> watching = ConcurrentHashMap.newKeySet();

  /** Immutable snapshots of each session's followed conversations. */
  private final Map<String, Set<String>> following = new ConcurrentHashMap<>();

  /**
   * Records what a session asked for.
   *
   * @param session the session asking, never null
   * @param on whether it wants deltas from now on
   */
  public void wants(String session, boolean on) {
    Objects.requireNonNull(session, "session");
    if (on) {
      watching.add(session);
    } else {
      watching.remove(session);
    }
  }

  /**
   * Whether this session asked for deltas.
   *
   * <p>A null session is nobody, which is the same answer {@code JobEvents.publish} gives it.
   */
  public boolean watching(String session) {
    return session != null && watching.contains(session);
  }

  /**
   * The legacy single-view operation: replace every earlier follow with this conversation.
   * Multi-view clients use {@link #follows(String, Set)}.
   */
  public void follows(String session, String conversation) {
    follows(session, Set.of(Objects.requireNonNull(conversation, "conversation")));
  }

  /**
   * Replace the session's complete set of follows atomically. An empty set follows nothing; another
   * session's follows are independent. Kept only in memory: reconnecting clients send their current
   * set again.
   */
  public void follows(String session, Set<String> conversations) {
    Objects.requireNonNull(session, "session");
    Set<String> snapshot = Set.copyOf(conversations);
    if (snapshot.isEmpty()) {
      following.remove(session);
    } else {
      following.put(session, snapshot);
    }
  }

  /** The sessions following {@code conversation} now; empty when none is. */
  public Set<String> followersOf(String conversation) {
    if (conversation == null) {
      return Set.of();
    }
    Set<String> followers = new HashSet<>();
    following.forEach(
        (session, followed) -> {
          if (followed.contains(conversation)) {
            followers.add(session);
          }
        });
    return Set.copyOf(followers);
  }

  /**
   * Forgets a session, because its listener went away.
   *
   * <p><b>A subscription does not outlive the socket that asked for it.</b> Without this the set
   * grows by one entry per session that ever asked, for the life of the process — and a client that
   * reconnected would be streaming again without having said so, which is the one thing opt-in is
   * for. The conversation it followed goes with it, on the same reasoning: a session that
   * reconnects re-sends {@code conversation.follow} on its own (task 7), and a follow that outlived
   * its socket would mean pushing {@code conversation.appended} into a queue nothing drains.
   */
  public void forget(String session) {
    if (session != null) {
      watching.remove(session);
      following.remove(session);
    }
  }
}
