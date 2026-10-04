package io.aeyer.plowshare.server.union;

import io.aeyer.plowshare.server.files.SessionCloseListener;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which copy of a union is authoritative right now, and the one lock every change to a hub or its
 * tree is taken under. Spec §5.3 and §11.6–7.
 *
 * <ul>
 *   <li>{@code OFFLINE} — no client has claimed it. Agents use the mirror; a push is never
 *       admitted.
 *   <li>{@code SYNCING} — a client sent {@code union.begin}. The tree is committed, mirror writes
 *       wait, a push is admitted.
 *   <li>{@code LIVE} — that client sent {@code union.ready}. Agents use its live files; a push is
 *       admitted; mirror writes are refused.
 * </ul>
 *
 * <p>One lock for every project: holding it through a checkout blocks other projects' mirror writes
 * for that long, which is accepted for v1.
 *
 * <h2>Why a push needs its own guard and not just a state check</h2>
 *
 * <p>{@link #admitPush} and {@link #pushEnded} bracket the git servlet's receive of one push,
 * independently of the {@code SYNCING}/{@code LIVE} claim. A claim can end mid-receive — {@code
 * SYNCING} expires after {@link #syncPatience} of silence, or the owning session's socket closes —
 * and if that alone flipped the project back to {@code OFFLINE}, a mirror write or a run-end commit
 * could land on the tree in the gap between the push landing and {@link #pushed} resetting the tree
 * to it, or {@link #pushed} could reset a tree a write had just changed underneath it. So a receive
 * in flight holds off {@link #write} and {@link #runEnded} the same way {@code SYNCING} does, for
 * up to {@link #syncPatience} past its own admission, whatever the claim itself is doing meanwhile.
 *
 * <h2>Why {@link #advanced} is called through an outbox, never inline</h2>
 *
 * <p>Calling {@code advanced} while holding {@link #lock} would deadlock a consumer that itself
 * calls back into this gate. Calling it right after releasing the lock, as a separate step, avoids
 * that deadlock but loses ordering: another thread can finish its own locked section and deliver
 * its own event first, so a consumer chaining two related commits could see them arrive out of the
 * order they actually happened in.
 *
 * <p>So every event produced under {@code lock} is appended to {@link #outbox} before the lock is
 * released, and {@link #drain} — serialised by the separate {@link #emitting} lock, held only while
 * a delivery is in progress — is what actually calls {@code advanced}, always in the order events
 * were enqueued and never while {@code lock} is held. A consumer that throws is logged and does not
 * stop later events from being delivered; a consumer that calls back into the gate runs holding
 * only {@code emitting}, so it cannot deadlock against {@code lock}.
 */
public final class UnionGate implements SessionCloseListener {

  private static final Logger log = LoggerFactory.getLogger(UnionGate.class);

  public enum State {
    OFFLINE,
    SYNCING,
    LIVE
  }

  public static final Duration WRITE_PATIENCE = Duration.ofSeconds(30);
  public static final Duration SYNC_PATIENCE = Duration.ofMinutes(2);

  private record Held(State state, String session, Instant since) {}

  private final Hubs hubs;
  private final Consumer<UnionAdvanced> advanced;
  private final Supplier<Instant> clock;
  private final Duration writePatience;
  private final Duration syncPatience;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition settled = lock.newCondition();
  private final Map<String, Held> held = new HashMap<>();
  private final Map<String, Deque<Instant>> pushesInFlight = new HashMap<>();
  private final ConcurrentLinkedQueue<UnionAdvanced> outbox = new ConcurrentLinkedQueue<>();
  private final ReentrantLock emitting = new ReentrantLock();

  public UnionGate(
      Hubs hubs,
      Consumer<UnionAdvanced> advanced,
      Supplier<Instant> clock,
      Duration writePatience,
      Duration syncPatience) {
    this.hubs = Objects.requireNonNull(hubs, "hubs");
    this.advanced = Objects.requireNonNull(advanced, "advanced");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.writePatience = Objects.requireNonNull(writePatience, "writePatience");
    this.syncPatience = Objects.requireNonNull(syncPatience, "syncPatience");
  }

  public State state(String project) {
    lock.lock();
    try {
      return current(project).state();
    } finally {
      lock.unlock();
    }
  }

  public boolean live(String project, String session) {
    lock.lock();
    try {
      Held now = current(project);
      return now.state() == State.LIVE && now.session().equals(session);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Whether the git servlet may start receiving a push for {@code project} — {@code false} while
   * {@code OFFLINE}. A {@code true} answer records a receive in flight; the caller must eventually
   * call {@link #pushEnded}, win or lose, or the mark is dropped on its own after {@link
   * #syncPatience}.
   */
  public boolean admitPush(String project) {
    lock.lock();
    try {
      if (current(project).state() == State.OFFLINE) {
        return false;
      }
      pushesInFlight.computeIfAbsent(project, ignored -> new ArrayDeque<>()).addLast(clock.get());
      return true;
    } finally {
      lock.unlock();
    }
  }

  /** Clears one receive in flight for {@code project}. Safe when none is recorded. */
  public void pushEnded(String project) {
    lock.lock();
    try {
      Deque<Instant> inFlight = pushesInFlight.get(project);
      if (inFlight != null) {
        inFlight.pollFirst();
        if (inFlight.isEmpty()) {
          pushesInFlight.remove(project);
        }
      }
      settled.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Claims {@code project} for {@code session} as {@code SYNCING}, first committing the tree.
   * Waits, like {@link #write}, for a receive in flight to end — committing while a push's ref
   * update may already have landed would commit onto a {@code main} the tree was not reset to — and
   * throws {@link IllegalStateException} if it does not end within the write patience.
   */
  public void begin(String project, String session) {
    lock.lock();
    try {
      long remaining = writePatience.toNanos();
      while (hasReceiveInFlight(project)) {
        if (remaining <= 0) {
          throw new IllegalStateException(
              "project '"
                  + project
                  + "' is receiving a push from"
                  + " its machine; retry in a moment");
        }
        remaining = settled.awaitNanos(remaining);
      }
      Hub hub = hub(project);
      if (hub.main().isPresent()) {
        commit(
            project, hub, Hub.SERVER_AUTHOR, "partial: writes in flight when a client connected");
      }
      // A client retrying a failed sync re-sends begin; keeping the first start
      // lets SYNC_PATIENCE still expire, so mirror writes are not held off forever.
      Held was = current(project);
      Instant since =
          was.state() == State.SYNCING && was.session().equals(session) ? was.since() : clock.get();
      held.put(project, new Held(State.SYNCING, session, since));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("union.begin for project '" + project + "' was interrupted");
    } finally {
      lock.unlock();
    }
    drain();
  }

  /**
   * Ends {@code session}'s {@code SYNCING} claim on {@code project} — a client whose sync failed
   * says so rather than leaving mirror writes waiting for the claim to expire. A no-op for any
   * other state or session.
   */
  public void abort(String project, String session) {
    lock.lock();
    try {
      Held now = current(project);
      if (now.state() == State.SYNCING && now.session().equals(session)) {
        held.remove(project);
        settled.signalAll();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Restarts {@code project}'s sync patience if it is {@code SYNCING} — called when a push's
   * receive starts, so a first push slower than the patience is not expired underneath itself. A
   * no-op otherwise.
   */
  public void touch(String project) {
    lock.lock();
    try {
      Held now = current(project);
      if (now.state() == State.SYNCING) {
        held.put(project, new Held(State.SYNCING, now.session(), clock.get()));
      }
    } finally {
      lock.unlock();
    }
  }

  public void ready(String project, String session, String commit) {
    lock.lock();
    try {
      Held now = current(project);
      if (now.state() != State.SYNCING || !now.session().equals(session)) {
        throw new IllegalStateException(
            "project '"
                + project
                + "' is not syncing with this"
                + " session; send union.begin first");
      }
      Hub hub = hub(project);
      if (!hub.main().map(commit::equals).orElse(false)) {
        throw new IllegalStateException(
            "the hub's main for '"
                + project
                + "' is not "
                + commit
                + "; fetch, merge and push again");
      }
      hub.resetTree(commit);
      held.put(project, new Held(State.LIVE, session, clock.get()));
      settled.signalAll();
    } finally {
      lock.unlock();
    }
  }

  public void pushed(String project, String oldCommit, String newCommit) {
    lock.lock();
    try {
      hub(project).resetTree(newCommit);
      outbox.add(new UnionAdvanced(project, oldCommit, newCommit));
    } finally {
      lock.unlock();
    }
    drain();
  }

  public void write(String project, Runnable write) {
    lock.lock();
    try {
      long remaining = writePatience.toNanos();
      boolean syncing;
      boolean receivingPush;
      while ((syncing = current(project).state() == State.SYNCING)
          | (receivingPush = hasReceiveInFlight(project))) {
        if (remaining <= 0) {
          throw new WorkspaceRefusedException(
              syncing
                  ? "project '"
                      + project
                      + "' is syncing with its machine; retry this"
                      + " write in a moment"
                  : "project '"
                      + project
                      + "' is receiving a push from its machine;"
                      + " retry this write in a moment");
        }
        remaining = settled.awaitNanos(remaining);
      }
      if (current(project).state() == State.LIVE) {
        throw new WorkspaceRefusedException(
            "the files of project '"
                + project
                + "' are"
                + " being served live from its machine now; retry and the call will reach them");
      }
      write.run();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new WorkspaceRefusedException("the write to project '" + project + "' was interrupted");
    } finally {
      lock.unlock();
    }
  }

  public void runEnded(String project, String runId, String agent) {
    lock.lock();
    try {
      if (current(project).state() != State.OFFLINE || hasReceiveInFlight(project)) {
        return;
      }
      Optional<Hub> hub = hubs.of(project);
      if (hub.isPresent() && hub.get().exists() && hub.get().main().isPresent()) {
        commit(project, hub.get(), agent, "run " + runId);
      }
    } finally {
      lock.unlock();
    }
    drain();
  }

  public <T> T locked(String project, Supplier<T> work) {
    lock.lock();
    try {
      return work.get();
    } finally {
      lock.unlock();
    }
  }

  public void forget(String project) {
    lock.lock();
    try {
      held.remove(project);
      pushesInFlight.remove(project);
      settled.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void sessionClosed(String session) {
    lock.lock();
    try {
      held.values().removeIf(h -> h.session().equals(session));
      settled.signalAll();
    } finally {
      lock.unlock();
    }
  }

  private Held current(String project) {
    Held now = held.get(project);
    if (now == null) {
      return new Held(State.OFFLINE, "", clock.get());
    }
    if (now.state() == State.SYNCING && now.since().plus(syncPatience).isBefore(clock.get())) {
      held.remove(project);
      settled.signalAll();
      return new Held(State.OFFLINE, "", clock.get());
    }
    return now;
  }

  /**
   * Whether a push for {@code project} is between {@link #admitPush} and {@link #pushEnded}. A mark
   * older than {@link #syncPatience} is treated as abandoned and dropped here, the same way an
   * expired {@code SYNCING} claim is dropped in {@link #current}, so a caller that never calls
   * {@link #pushEnded} cannot block writes forever.
   */
  private boolean hasReceiveInFlight(String project) {
    Deque<Instant> inFlight = pushesInFlight.get(project);
    if (inFlight == null) {
      return false;
    }
    Instant now = clock.get();
    inFlight.removeIf(since -> since.plus(syncPatience).isBefore(now));
    if (inFlight.isEmpty()) {
      pushesInFlight.remove(project);
      return false;
    }
    return true;
  }

  private Hub hub(String project) {
    return hubs.of(project)
        .filter(Hub::exists)
        .orElseThrow(
            () -> new IllegalStateException("project '" + project + "' has no hub on this server"));
  }

  /**
   * Commits {@code hub}'s dirty tree and, if the tree was dirty, appends the resulting {@link
   * UnionAdvanced} to {@link #outbox} — never emits it directly. Always called with {@link #lock}
   * held; the caller is responsible for calling {@link #drain} once it releases the lock.
   */
  private void commit(String project, Hub hub, String author, String message) {
    String before = hub.main().orElse(null);
    hub.commitTree(author, message)
        .ifPresent(after -> outbox.add(new UnionAdvanced(project, before, after)));
  }

  /**
   * Delivers every event in {@link #outbox}, in order, to {@link #advanced}. Never called while
   * {@link #lock} is held.
   *
   * <p>Delivery is serialised by {@link #emitting} rather than by {@code lock}: if another thread
   * is already draining, this call returns immediately, trusting that drainer to notice and deliver
   * the event(s) this call's caller just enqueued — the loop below only stops once it has observed
   * the outbox empty <em>after</em> releasing {@code emitting}, so an event enqueued in the gap
   * between the last poll and the unlock is caught by looping back rather than left stranded.
   */
  private void drain() {
    while (true) {
      if (!emitting.tryLock()) {
        return;
      }
      try {
        UnionAdvanced event;
        while ((event = outbox.poll()) != null) {
          try {
            advanced.accept(event);
          } catch (RuntimeException failed) {
            log.warn(
                "the union.advanced consumer threw delivering {}; continuing with"
                    + " later events",
                event,
                failed);
          }
        }
      } finally {
        emitting.unlock();
      }
      if (outbox.isEmpty()) {
        break;
      }
    }
  }
}
