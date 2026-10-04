package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.agents.Budget;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One in-memory lease per wake or delegated continuation, drawn from a root topic's pot. Members
 * leave the closing reserve for opener seats. Each shared {@link Budget} commits its charge before
 * permitting a model call, including calls made by delegates. Restart loses only unused
 * reservations; calls already claimed remain in {@code pot_spent}.
 *
 * <p>The grant remembers its original size because an operator can change the live budget's
 * ceiling. Every permitted call is charged, even beyond an operator-raised grant; only unused
 * capacity from the original grant is returned when the wake finishes.
 */
public final class BoardPot {

  private static final Logger log = LoggerFactory.getLogger(BoardPot.class);

  private final BoardStore store;
  private final ReentrantLock lock = new ReentrantLock();

  /** Unused calls reserved by live grants, per root. Guarded by {@link #lock}. */
  private final Map<String, Integer> leased = new HashMap<>();

  private final Map<Budget, Grant> granted = new IdentityHashMap<>();

  private static final class Grant {
    final String root;
    final int size;
    int charged;
    boolean active = true;

    Grant(String root, int size) {
      this.root = root;
      this.size = size;
    }

    int unused() {
      return Math.max(0, size - charged);
    }
  }

  public BoardPot(BoardStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  /**
   * A lease for one wake, or empty when not even one call is available to this seat right now —
   * which is either a {@link #spent} pot or one merely leased out to other wakes; a caller that
   * needs to tell them apart asks {@link #spent}.
   *
   * @throws IllegalArgumentException if {@code root} names no root topic, or if {@code wakeCap} is
   *     below one step. Every caller floors its own cap before reaching here ({@code
   *     SwarmProperties.wakeCapNow}, {@code SeatRunner}), so a {@code wakeCap} under one is not a
   *     shape this pot should paper over with {@code Math.max(1, wakeCap)} — that would quietly
   *     turn a caller's bug into a one-call lease instead of surfacing it
   */
  public Optional<Budget> lease(String root, boolean forOpener, int wakeCap) {
    if (wakeCap < 1) {
      throw new IllegalArgumentException("a wake cap is at least one step; it was " + wakeCap);
    }
    lock.lock();
    try {
      BoardTopic pot = rootTopic(root);
      int available =
          pot.potTotal() - pot.potSpent() - leased.getOrDefault(root, 0) - floor(pot, forOpener);
      int size = Math.min(wakeCap, available);
      if (size < 1) {
        return Optional.empty();
      }
      leased.merge(root, size, Integer::sum);
      Grant grant = new Grant(root, size);
      Budget lease = Budget.of(size, () -> charge(grant));
      granted.put(lease, grant);
      return Optional.of(lease);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Commits before releasing reserved capacity. If persistence fails, the grant and allowance stay
   * untouched and the caller cannot send the model request. The budget serializes its shared
   * claims; this lock serializes charging against leasing and settlement of the root.
   */
  private void charge(Grant grant) {
    lock.lock();
    try {
      if (!grant.active) {
        throw new IllegalStateException("this board lease has already settled");
      }
      store.spend(grant.root, 1);
      if (grant.unused() > 0) {
        release(grant.root, 1);
      }
      grant.charged++;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Releases unused capacity once. Charges are already durable, so settlement never charges them
   * again. The grant's own root is authoritative even if a caller names the wrong root.
   */
  public void settle(String root, Budget lease) {
    lock.lock();
    try {
      Grant grant = granted.remove(lease);
      if (grant == null) {
        log.warn("settle({}) named an unknown or already settled board lease", root);
        return;
      }
      if (!grant.root.equals(root)) {
        log.warn(
            "settle({}) named a lease granted against {}; releasing its own root",
            root,
            grant.root);
      }
      grant.active = false;
      release(grant.root, grant.unused());
    } finally {
      lock.unlock();
    }
  }

  private void release(String root, int calls) {
    leased.computeIfPresent(root, (key, held) -> held - calls <= 0 ? null : held - calls);
  }

  /**
   * Whether {@code root}'s pot has nothing left for this seat <em>even once every outstanding lease
   * is settled</em>: {@code potTotal − potSpent − floor < 1}, the floor being the openers' reserve
   * for a member and nothing for an opener.
   *
   * <p><b>Spent is not the same as {@link #leasable} answering false</b>, and the final review's
   * I-2 is what confusing the two cost. {@link #lease} subtracts what other wakes hold right now,
   * so it comes back empty whenever the pot is merely <em>leased out</em> — with a pot of 20, a
   * reserve of 2 and a wake cap of 12, the third member of a three-member swarm finds nothing on
   * the topic's very first open, though nothing has been spent. Treating that as spent refused the
   * wake and exhausted the root. Only this answer may exhaust a root; a pot that is leased out but
   * not spent is a wake that waits for a settle.
   *
   * @throws IllegalArgumentException if {@code root} names no root topic
   */
  public boolean spent(String root, boolean forOpener) {
    BoardTopic pot = rootTopic(root);
    return pot.potTotal() - pot.potSpent() - floor(pot, forOpener) < 1;
  }

  /**
   * Whether {@link #lease} would grant this seat at least one call right now — after what is spent,
   * the floor, and every lease still outstanding. Under {@link #lock}, so it reads the same {@link
   * #leased} a lease would; a lease can still come back empty after this said true, if another wake
   * leased the last call in between, which is why {@code SeatRunner} treats an empty lease as
   * "wait" and not as "spent".
   *
   * @throws IllegalArgumentException if {@code root} names no root topic
   */
  public boolean leasable(String root, boolean forOpener) {
    lock.lock();
    try {
      BoardTopic pot = rootTopic(root);
      return pot.potTotal() - pot.potSpent() - leased.getOrDefault(root, 0) - floor(pot, forOpener)
          >= 1;
    } finally {
      lock.unlock();
    }
  }

  private BoardTopic rootTopic(String root) {
    return store
        .topic(root)
        .filter(BoardTopic::isRoot)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    root + " is not a root topic;" + " a pot is held by the root of a topic tree"));
  }

  private static int floor(BoardTopic pot, boolean forOpener) {
    return forOpener ? 0 : pot.reserve();
  }

  /** Unused calls reserved from {@code root} by live leases. */
  public int leased(String root) {
    lock.lock();
    try {
      return leased.getOrDefault(root, 0);
    } finally {
      lock.unlock();
    }
  }
}
