package io.aeyer.plowshare.server.swarm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who runs now — spec 2026-09-29, the project board and the swarm, §5.
 *
 * <p>A run that is scheduled asks {@link Turns#await} before each model call and gives the
 * {@link Grant} back when the call returns. A grant is a slot on one pool, and a pool admits at
 * most its {@link Pools#slots} swarm calls at once — a ceiling below its chat slots, so the chat
 * slots above it are never taken by swarm work. They are not reserved for a person: a person's
 * turn, which is never scheduled, shares them with every other unscheduled run.
 *
 * <p>Waiting is never fatal: nothing here times a wait out. Only the caller's own flag ends one,
 * polled every {@code poll}, because the runtime cancels by flag and never interrupts
 * ({@code JobStore.cancel}). A wait longer than {@code waitWarning} is logged once and reported
 * {@code overdue} in the {@link #snapshot}.
 *
 * <p>The quantum and the wait warning are live, read from the config store, which in production
 * is a database. They are read once per wait and once per snapshot, and never under the lock: a
 * slow store would otherwise hold every waiter, and every {@link Grant#release} in a run's
 * {@code finally}, behind one round-trip. A read that fails is not the wait's failure — it falls
 * back to the last value read, or to the defaults when none ever was, and says so once.
 *
 * <p>In memory and per process, like {@code LlmPool}: a restart forgets every waiter, and the
 * board owes its seats fresh wakes (spec §5, Restart).
 */
public final class SwarmScheduler {

    private static final Logger log = LoggerFactory.getLogger(SwarmScheduler.class);

    /** What a failed read of the quantum falls back to when no read has ever succeeded; the
     *  bound default of {@code plowshare.swarm.quantum}. */
    static final int DEFAULT_QUANTUM = 4;
    /** Likewise for {@code plowshare.swarm.wait-warning}. */
    static final Duration DEFAULT_WAIT_WARNING = Duration.ofMinutes(10);

    /** Whose turn it is: the account that opened the root topic, the topic, the member. */
    public record Share(String account, String topic, String member) {
        public Share {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(member, "member");
        }
    }

    /** What the scheduler needs to know about pools. */
    public interface Pools {

        /** The pools that serve {@code specifier} and declare swarm slots, in declared order. */
        List<String> serving(String specifier);

        /** The pool's swarm slots; 0 when it declares none or is unknown. */
        int slots(String pool);

        /** Every pool declaring swarm slots, in declared order. */
        List<String> all();
    }

    /**
     * The scheduler at one moment.
     *
     * <p>{@code position} is arrival order among the waiting — those inside their quantum first —
     * and not a promise of service order: fair-share may serve a later arrival from another
     * account first.
     */
    public record Snapshot(List<PoolUse> pools, List<Waiting> ready) {}

    public record PoolUse(String pool, int slots, int used) {}

    public record Waiting(
            Share share, String specifier, int position, Duration waited, boolean overdue) {}

    private final Pools pools;
    private final IntSupplier quantum;
    private final Supplier<Duration> waitWarning;
    private final Clock clock;
    private final Duration poll;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    /** Swarm slots in use, per pool. Guarded by {@link #lock}. */
    private final Map<String, Integer> used = new HashMap<>();
    /** Waiters not inside a quantum, in arrival order. Guarded by {@link #lock}. */
    private final List<Entry> fresh = new ArrayList<>();
    /** Waiters inside their quantum, served before every fresh one, FIFO. Guarded by {@link #lock}. */
    private final List<Entry> continuing = new ArrayList<>();
    /** Fair-share order for fresh waiters. Guarded by {@link #lock}. */
    private final Rotation rotation = new Rotation();

    /** The last quantum read, and what the next failed read falls back to. */
    private volatile int lastQuantum = DEFAULT_QUANTUM;
    /** The last wait warning read, and what the next failed read falls back to. */
    private volatile Duration lastWaitWarning = DEFAULT_WAIT_WARNING;
    /** Whether the quantum's reads are failing, so an outage is logged once and not per wait. */
    private final AtomicBoolean quantumFailing = new AtomicBoolean();
    /** Likewise for the wait warning. */
    private final AtomicBoolean waitWarningFailing = new AtomicBoolean();

    public SwarmScheduler(
            Pools pools,
            IntSupplier quantum,
            Supplier<Duration> waitWarning,
            Clock clock,
            Duration poll) {
        this.pools = Objects.requireNonNull(pools, "pools");
        this.quantum = Objects.requireNonNull(quantum, "quantum");
        this.waitWarning = Objects.requireNonNull(waitWarning, "waitWarning");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.poll = Objects.requireNonNull(poll, "poll");
    }

    /** A run's place with this scheduler, for as long as it lives. */
    public Turns enter(Share share) {
        return new Turns(Objects.requireNonNull(share, "share"));
    }

    public Snapshot snapshot() {
        Duration warning = waitWarningNow();
        lock.lock();
        try {
            List<PoolUse> uses = new ArrayList<>();
            for (String pool : pools.all()) {
                uses.add(new PoolUse(pool, pools.slots(pool), used.getOrDefault(pool, 0)));
            }
            Instant now = clock.instant();
            List<Waiting> ready = new ArrayList<>();
            int position = 1;
            for (Entry entry : waiting()) {
                Duration waited = Duration.between(entry.since, now);
                ready.add(new Waiting(entry.turns.share, entry.specifier, position++, waited,
                        waited.compareTo(warning) > 0));
            }
            return new Snapshot(List.copyOf(uses), List.copyOf(ready));
        } finally {
            lock.unlock();
        }
    }

    /** One wait, from arrival until it is granted or leaves. */
    private static final class Entry {
        final Turns turns;
        final String specifier;
        final List<String> candidates;
        final Instant since;
        /** The pool granted, once one is. Guarded by the scheduler's lock. */
        String granted;
        /** Whether the grant came from the fresh waiters rather than within a quantum. */
        boolean fromFresh;

        Entry(Turns turns, String specifier, List<String> candidates, Instant since) {
            this.turns = turns;
            this.specifier = specifier;
            this.candidates = candidates;
            this.since = since;
        }
    }

    public final class Turns {

        private final Share share;
        /** Steps granted in the current quantum. Guarded by the scheduler's lock. */
        private int spent;

        private Turns(Share share) {
            this.share = share;
        }

        public Share share() {
            return share;
        }

        /**
         * Waits until this run may make its next model call.
         *
         * <p>The quantum and the wait warning are read once, here, before the lock — a live change
         * is taken at the next wait, which is the next step boundary (spec §5), and never
         * mid-wait.
         *
         * @return the slot, or {@code null} when {@code cancelled} answered true, or the thread
         *     was interrupted, while waiting. A {@code null} holds nothing: the caller has spent
         *     nothing and has nothing to release. A returned grant was not cancelled at the moment
         *     it was returned — a grant that landed after the cancel is given back, not handed on.
         * @throws RuntimeException whatever failed under the lock after this wait had queued (an
         *     {@code Error} is handled alike), having first taken the wait back out: its entry
         *     leaves the queue, or the slot it had just been granted goes back — so a wait that
         *     throws, too, holds nothing.
         */
        public Grant await(String specifier, BooleanSupplier cancelled) {
            Objects.requireNonNull(specifier, "specifier");
            Objects.requireNonNull(cancelled, "cancelled");
            List<String> candidates = List.copyOf(pools.serving(specifier));
            int quantum = quantumNow();
            Duration warning = waitWarningNow();
            lock.lock();
            try {
                Entry mine = new Entry(this, specifier, candidates, clock.instant());
                // Inside the quantum: back ahead of every fresh waiter, the slot having been given
                // up only for the tools. Spent (or not yet begun): a fresh waiter, in the rotation.
                if (spent > 0 && spent < quantum) {
                    continuing.add(mine);
                } else {
                    fresh.add(mine);
                }
                try {
                    return waitFor(mine, cancelled, warning);
                } catch (RuntimeException | Error failure) {
                    // Queued, and failing: left alone, the entry stays in the queue, a later
                    // dispatch grants it, and nobody ever releases that slot. Withdraw it first;
                    // should the withdrawal's own dispatch fail too, the slot has already gone
                    // back, and the first failure is the one worth reporting.
                    try {
                        withdraw(mine);
                    } catch (RuntimeException | Error again) {
                        failure.addSuppressed(again);
                    }
                    throw failure;
                }
            } finally {
                lock.unlock();
            }
        }

        /** The wait itself, from a queued entry to its grant or its leaving. Under the lock. */
        private Grant waitFor(Entry mine, BooleanSupplier cancelled, Duration warning) {
            dispatch();
            boolean warned = false;
            while (mine.granted == null) {
                if (cancelled.getAsBoolean()) {
                    leave(mine);
                    return null;
                }
                if (!warned && overdue(mine, warning)) {
                    warned = true;
                    log.warn("swarm: {} has waited {} for a slot for '{}' on {} ({} waiting)",
                            share, Duration.between(mine.since, clock.instant()), mine.specifier,
                            mine.candidates.isEmpty()
                                    ? "no pool with swarm slots" : mine.candidates,
                            waiting().size());
                }
                try {
                    changed.await(poll.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    // dispatch() may have granted mine before the interrupt was seen; withdraw
                    // gives that slot back rather than leak it — a null return holds nothing. The
                    // interrupt is restored first, so it survives should the withdrawal throw.
                    Thread.currentThread().interrupt();
                    withdraw(mine);
                    return null;
                }
            }
            // The cancel is checked only before each sleep, so one set during the sleep can meet
            // a grant made before the waiter woke. The grant loses: a run cancelled while waiting
            // must end having spent no model call, and the slot is the next waiter's.
            if (cancelled.getAsBoolean()) {
                withdraw(mine);
                return null;
            }
            // A grant out of the rotation opens a new quantum; one within a quantum spends it.
            // A run alone in the rotation is granted at once, so it "keeps going" by opening
            // quantum after quantum — nobody is made to wait for nobody.
            spent = mine.fromFresh ? 1 : spent + 1;
            return new Grant(mine.granted);
        }
    }

    /** A slot on one pool, held across one model call. */
    public final class Grant {

        private final String pool;
        private final AtomicBoolean released = new AtomicBoolean();

        private Grant(String pool) {
            this.pool = pool;
        }

        public String pool() {
            return pool;
        }

        /** Gives the slot back. Idempotent: a second release gives back nothing. */
        public void release() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            lock.lock();
            try {
                used.merge(pool, -1, Integer::sum);
                dispatch();
            } finally {
                lock.unlock();
            }
        }
    }

    /** Every waiter, in snapshot order: within a quantum first, then fresh. Under the lock. */
    private List<Entry> waiting() {
        List<Entry> all = new ArrayList<>(continuing);
        all.addAll(fresh);
        return all;
    }

    /** Hands free slots to waiters until none can be placed: within a quantum first, in arrival
     *  order; then fresh ones, in fair-share order. Under the lock. */
    private void dispatch() {
        boolean placed = false;
        for (Iterator<Entry> it = continuing.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            String pool = freePool(entry.candidates);
            if (pool != null) {
                grant(entry, pool, false);
                it.remove();
                placed = true;
            }
        }
        Entry next;
        while ((next = nextFresh()) != null) {
            grant(next, freePool(next.candidates), true);
            fresh.remove(next);
            placed = true;
        }
        if (placed) {
            changed.signalAll();
        }
    }

    /** The fresh waiter fair-share serves next, among those a free slot can take. Under the lock. */
    private Entry nextFresh() {
        List<Entry> placeable = new ArrayList<>();
        for (Entry entry : fresh) {
            if (freePool(entry.candidates) != null) {
                placeable.add(entry);
            }
        }
        return placeable.isEmpty() ? null : rotation.next(fresh, placeable);
    }

    private void grant(Entry entry, String pool, boolean fromFresh) {
        used.merge(pool, 1, Integer::sum);
        entry.granted = pool;
        entry.fromFresh = fromFresh;
    }

    /** The least used candidate with a free slot, the first declared on a tie; null if none. */
    private String freePool(List<String> candidates) {
        String best = null;
        double lightest = Double.POSITIVE_INFINITY;
        for (String pool : candidates) {
            int slots = pools.slots(pool);
            int inUse = used.getOrDefault(pool, 0);
            if (slots <= 0 || inUse >= slots) {
                continue;
            }
            double load = (double) inUse / slots;
            if (load < lightest) {
                best = pool;
                lightest = load;
            }
        }
        return best;
    }

    private void leave(Entry entry) {
        continuing.remove(entry);
        fresh.remove(entry);
    }

    /**
     * Takes a wait that will not return its grant back out of the scheduler: a queued entry
     * leaves the queue; one already granted gives its slot back and lets the next waiter have it.
     * Clears the grant before dispatching, so a second withdrawal — after this one's dispatch
     * failed — gives nothing back twice. Under the lock.
     */
    private void withdraw(Entry entry) {
        String pool = entry.granted;
        if (pool == null) {
            leave(entry);
            return;
        }
        entry.granted = null;
        used.merge(pool, -1, Integer::sum);
        dispatch();
    }

    private boolean overdue(Entry entry, Duration warning) {
        return Duration.between(entry.since, clock.instant()).compareTo(warning) > 0;
    }

    /** Asked at every wait, so a change is taken at the next step boundary; floored at one. Never
     *  under the lock, and never failing: a read that throws falls back to the last one. */
    private int quantumNow() {
        try {
            int value = Math.max(1, quantum.getAsInt());
            lastQuantum = value;
            quantumFailing.set(false);
            return value;
        } catch (RuntimeException unread) {
            int fallback = lastQuantum;
            if (quantumFailing.compareAndSet(false, true)) {
                log.warn("swarm: the quantum could not be read; using {} until it can be",
                        fallback, unread);
            }
            return fallback;
        }
    }

    /** Asked at every wait and every snapshot, never under the lock, and falling back to the
     *  last value read just as {@link #quantumNow} does. */
    private Duration waitWarningNow() {
        try {
            Duration value = Objects.requireNonNull(waitWarning.get(), "wait warning");
            lastWaitWarning = value;
            waitWarningFailing.set(false);
            return value;
        } catch (RuntimeException unread) {
            Duration fallback = lastWaitWarning;
            if (waitWarningFailing.compareAndSet(false, true)) {
                log.warn("swarm: the wait warning could not be read; using {} until it can be",
                        fallback, unread);
            }
            return fallback;
        }
    }

    /**
     * Fair-share: round-robin over accounts, then over the chosen account's topics, then over the
     * chosen topic's members. A key is kept in its ring only while someone under it is waiting;
     * one that empties drops out and re-enters at the back, which is what round-robin means for a
     * newcomer. Under the lock.
     */
    private static final class Rotation {

        private final Ring accounts = new Ring();
        private final Map<String, Ring> topics = new HashMap<>();
        private final Map<List<String>, Ring> members = new HashMap<>();

        Entry next(List<Entry> waiting, List<Entry> placeable) {
            List<String> waitingAccounts = keys(waiting, entry -> entry.turns.share.account());
            List<String> placeableAccounts = keys(placeable, entry -> entry.turns.share.account());
            String account = accounts.next(waitingAccounts, placeableAccounts);
            List<Entry> waitingOfAccount = waiting.stream()
                    .filter(entry -> entry.turns.share.account().equals(account)).toList();
            List<Entry> placeableOfAccount = placeable.stream()
                    .filter(entry -> entry.turns.share.account().equals(account)).toList();
            String topic = topics.computeIfAbsent(account, key -> new Ring())
                    .next(keys(waitingOfAccount, entry -> entry.turns.share.topic()),
                            keys(placeableOfAccount, entry -> entry.turns.share.topic()));
            List<Entry> waitingOfTopic = waitingOfAccount.stream()
                    .filter(entry -> entry.turns.share.topic().equals(topic)).toList();
            List<Entry> placeableOfTopic = placeableOfAccount.stream()
                    .filter(entry -> entry.turns.share.topic().equals(topic)).toList();
            String member = members.computeIfAbsent(List.of(account, topic), key -> new Ring())
                    .next(keys(waitingOfTopic, entry -> entry.turns.share.member()),
                            keys(placeableOfTopic, entry -> entry.turns.share.member()));
            // Rings for keys nobody is waiting under are dropped, so the maps stay as small as
            // the queue; pruning (like ring membership above) goes by who is still WAITING, not
            // who is placeable this round — a key stays in its ring while its pool is merely
            // full, and only leaves when nobody under it is waiting at all.
            topics.keySet().retainAll(waitingAccounts);
            members.keySet().retainAll(waiting.stream()
                    .map(entry -> List.of(entry.turns.share.account(), entry.turns.share.topic()))
                    .collect(java.util.stream.Collectors.toSet()));
            return placeableOfTopic.stream()
                    .filter(entry -> entry.turns.share.member().equals(member))
                    .findFirst().orElseThrow();
        }

        private static List<String> keys(
                List<Entry> entries, java.util.function.Function<Entry, String> key) {
            Set<String> seen = new LinkedHashSet<>();
            for (Entry entry : entries) {
                seen.add(key.apply(entry));
            }
            return List.copyOf(seen);
        }
    }

    /** One level of round-robin: keys in the order first seen, and the last one served. */
    private static final class Ring {

        private final List<String> order = new ArrayList<>();
        private String last;

        /**
         * The first waiting-and-placeable key after the last served, cyclically. A key joins the
         * ring at the back the first time it is seen among {@code waiting}, placeable or not, and
         * stays there — keeping its position — for as long as it keeps waiting, even through
         * rounds where it isn't placeable and so cannot be {@code chosen}.
         */
        String next(List<String> waiting, List<String> placeable) {
            for (String key : waiting) {
                if (!order.contains(key)) {
                    order.add(key);
                }
            }
            int start = last == null ? 0 : order.indexOf(last) + 1;
            String chosen = null;
            for (int i = 0; i < order.size(); i++) {
                String key = order.get((start + i) % order.size());
                if (placeable.contains(key)) {
                    chosen = key;
                    break;
                }
            }
            order.retainAll(waiting);
            last = chosen;
            return chosen;
        }
    }
}
