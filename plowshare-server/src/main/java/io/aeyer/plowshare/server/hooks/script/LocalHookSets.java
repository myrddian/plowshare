package io.aeyer.plowshare.server.hooks.script;

import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookFile;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The loaded local hook sets: a stage's context's conversation, to the hash that log was pinned to
 * when it opened, to one loaded set shared by every log of the same owner with that hash (spec
 * 2026-09-30-local-hooks-are-served decisions 3 and 9, as amended): never across accounts, since
 * module state persists in a set's contexts.
 *
 * <p><b>Retired only when idle and unused</b> (plan choice 12, as amended in review): a set is
 * retired once nothing has fired for it for the pool's idle time <i>and</i> no stage holds it. A
 * stage holds its set through a {@link Lease} from {@link #acquire} until its last hook returned,
 * and releasing stamps the set as used, so no time window has to outlast a stage. Holding,
 * releasing, publishing a load and retiring all go through the map's per-key {@code compute}, so
 * none races another; loading itself is outside it, under the set's own lock. A retired set is
 * loaded again from the store on its next fire.
 *
 * <p><b>A snapshot that cannot be loaded</b> (gone from the store, the store failing, or the load
 * throwing) is an empty tier for the rest of that log in this process, said once (plan choice 11):
 * the log is remembered, so a later fire neither reads the store again nor starts running hooks
 * partway through the log. Only a failed pin lookup is asked again at the next fire, and each such
 * fire says so without spending the log's one notice.
 *
 * <p><b>Pins are cached in a bounded LRU; a miss only for {@link #MISS_BELIEVED}</b> (plan choice
 * 13, as amended in Task 6's review). A log's pin is written once, so a pin found is believed for
 * the life of the log. A miss is not final: every opener pins before {@code log.open} and before
 * any turn, but the log's row is committed first, so a parent's cancel cascade, or a REST cancel,
 * can reach {@code log.close} while the pin is still being read. A miss cached for good would then
 * hide a pin that landed a moment later; one believed for twice the read's deadline outlives any
 * pin still in flight and costs a log with no local hooks one lookup a minute.
 */
final class LocalHookSets implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(LocalHookSets.class);

  /** How many logs' pins, and withheld logs, are remembered; a miss costs one read. */
  static final int REMEMBERED = 4_096;

  /**
   * How long a log found with no pin is believed to have none: twice {@code
   * agents.ChannelHooks.DEADLINE} (30 s), the longest a pin can still be landing. Restated here
   * rather than referenced, so this package does not reach into {@code agents}.
   */
  static final Duration MISS_BELIEVED = Duration.ofSeconds(60);

  /**
   * A log's pin as last looked up, with the log's owner when there is a pin, and when: a miss is
   * asked again once it is stale.
   */
  private record Pin(Optional<String> hash, Optional<String> owner, Instant lookedUp) {}

  /**
   * A set handed to one stage; closing it says the stage is done with the set. Only the first close
   * lets go: a second would take another stage's hold (final review M4).
   */
  static final class Lease implements AutoCloseable {

    private static final Runnable NOTHING = () -> {};

    final ProjectHookSet set;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Lease(ProjectHookSet set, Runnable release) {
      this.set = set;
      this.release = release;
    }

    /** A set nothing needs to be told about when the stage is done with it. */
    static Lease of(ProjectHookSet set) {
      return new Lease(set, NOTHING);
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        release.run();
      }
    }
  }

  /**
   * A loaded set's key: the log's owner and its hash (spec 2026-09-30-local-hooks-are-served
   * decision 9, as amended). Module state lives in a set's contexts, so two accounts whose files
   * hash the same never share one; the store stays keyed by the hash alone.
   */
  private record Key(String owner, String hash) {}

  /** One key's set; {@code holders} and {@code used} change only inside the map's compute. */
  private static final class Shared {
    private volatile ProjectHookSet set;
    private Instant used;
    private int holders;
  }

  private final Function<String, Optional<String>> pinnedTo;
  private final Function<String, Optional<String>> ownerOf;
  private final Function<String, Optional<List<HookFile>>> stored;
  private final HookEngine engine;
  private final HooksProperties properties;
  private final Supplier<Instant> clock;
  private final ScheduledExecutorService timer;
  private final Map<Key, Shared> sets = new ConcurrentHashMap<>();
  private final Map<String, Pin> pins = remembering(REMEMBERED);
  private final Map<String, Boolean> withheld = remembering(REMEMBERED);

  /**
   * @param pinnedTo a log's pinned hash: {@code ConversationStore.localHooksOf}
   * @param ownerOf a log's owning account: {@code ConversationStore.ownerOf}
   * @param stored a hash's files: {@code LocalHookSetStore.find}
   */
  LocalHookSets(
      Function<String, Optional<String>> pinnedTo,
      Function<String, Optional<String>> ownerOf,
      Function<String, Optional<List<HookFile>>> stored,
      HookEngine engine,
      HooksProperties properties,
      Supplier<Instant> clock,
      ScheduledExecutorService timer) {
    this.pinnedTo = Objects.requireNonNull(pinnedTo, "pinnedTo");
    this.ownerOf = Objects.requireNonNull(ownerOf, "ownerOf");
    this.stored = Objects.requireNonNull(stored, "stored");
    this.engine = Objects.requireNonNull(engine, "engine");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.timer = Objects.requireNonNull(timer, "timer");
    long every = Math.max(1_000L, properties.getIdle().toMillis());
    timer.scheduleWithFixedDelay(this::sweepQuietly, every, every, TimeUnit.MILLISECONDS);
  }

  /**
   * The set this context's log opened with, held until the lease is closed; an empty set for a log
   * with none. Never throws.
   */
  Lease acquire(HookContext context) {
    String conversation = context.conversation();
    // get, not containsKey: only a get refreshes the access-ordered LRU, so a withheld log
    // that keeps firing stays remembered (final review M3).
    if (conversation == null || withheld.get(conversation) != null) {
      return Lease.of(ProjectHookSet.empty());
    }
    Pin pin;
    try {
      pin = pinOf(conversation);
    } catch (RuntimeException unread) {
      // Decision 7, third case: an empty tier and a record, never a failed run. Not cached,
      // and not the log's one notice: the next fire asks again (plan choice 11).
      return Lease.of(
          ProjectHookSet.withheld(
              "this log's local hooks could not be looked"
                  + " up, so none ran at this stage: "
                  + detail(unread)));
    }
    if (pin.hash().isEmpty()) {
      return Lease.of(ProjectHookSet.empty());
    }
    String hash = pin.hash().get();
    if (pin.owner().isEmpty()) {
      // A pin is written only for a log its owner's session served, so this is a row that
      // lost its owner; a set is never shared with no one's (decision 9, as amended).
      return Lease.of(
          withhold(
              conversation,
              "this log's local hooks have no owner on" + " record, so none run in this log"));
    }
    Key key = new Key(pin.owner().get(), hash);
    Instant now = clock.get();
    Shared shared =
        sets.compute(
            key,
            (ignored, held) -> {
              Shared holding = held != null ? held : new Shared();
              holding.used = now;
              holding.holders++;
              return holding;
            });
    try {
      return new Lease(loadedIn(key, hash, shared, conversation), () -> release(key, shared));
    } catch (Throwable unexpected) {
      release(key, shared);
      throw unexpected;
    }
  }

  private ProjectHookSet loadedIn(Key key, String hash, Shared shared, String conversation) {
    ProjectHookSet ready = shared.set;
    if (ready != null) {
      return ready;
    }
    synchronized (shared) {
      if (shared.set != null) {
        return shared.set;
      }
      if (withheld.get(conversation) != null) {
        // A fire in the same log failed this load while this one waited for the lock.
        return ProjectHookSet.empty();
      }
      Optional<List<HookFile>> files;
      try {
        files = Objects.requireNonNull(stored.apply(hash), "stored");
      } catch (RuntimeException unread) {
        return withhold(
            conversation,
            "the local hooks this log opened with could not be"
                + " read, so none run in this log: "
                + detail(unread));
      }
      if (files.isEmpty()) {
        return withhold(
            conversation,
            "the local hooks this log opened with ("
                + hash
                + ") are no longer stored, so none run in this log");
      }
      ProjectHookSet loaded;
      try {
        loaded = ProjectHookSet.load(hash, files.get(), engine, properties, clock, timer);
      } catch (RuntimeException broken) {
        return withhold(
            conversation,
            "the local hooks this log opened with could not be"
                + " loaded, so none run in this log: "
                + detail(broken));
      }
      // Published only into the entry still in the map: one that close() took away while
      // this loaded is gone for good, and a set put into it would never be closed.
      boolean[] published = new boolean[1];
      sets.computeIfPresent(
          key,
          (ignored, held) -> {
            if (held == shared) {
              held.set = loaded;
              published[0] = true;
            }
            return held;
          });
      if (!published[0]) {
        loaded.close();
        return ProjectHookSet.withheld(
            "the local hooks this log opened with were closed"
                + " while they loaded, so none ran at this stage");
      }
      return loaded;
    }
  }

  private void release(Key key, Shared shared) {
    Instant now = clock.get();
    sets.computeIfPresent(
        key,
        (ignored, held) -> {
          if (held == shared) {
            held.holders--;
            held.used = now;
          }
          return held;
        });
  }

  /** Retire, and close here, every set nobody fired for within the idle time and nobody holds. */
  void sweep() {
    retire().forEach(ProjectHookSet::close);
  }

  private List<ProjectHookSet> retire() {
    Instant cutoff = clock.get().minus(properties.getIdle());
    List<ProjectHookSet> retired = new ArrayList<>();
    for (Key key : List.copyOf(sets.keySet())) {
      sets.computeIfPresent(
          key,
          (ignored, shared) -> {
            if (shared.holders > 0 || !shared.used.isBefore(cutoff)) {
              return shared;
            }
            if (shared.set != null) {
              retired.add(shared.set);
            }
            return null;
          });
    }
    return retired;
  }

  /** How many sets are loaded now. */
  int loaded() {
    return (int) sets.values().stream().filter(shared -> shared.set != null).count();
  }

  @Override
  public void close() {
    for (Key key : List.copyOf(sets.keySet())) {
      Shared removed = sets.remove(key);
      ProjectHookSet held = removed == null ? null : removed.set;
      if (held != null) {
        held.close();
      }
    }
  }

  private void sweepQuietly() {
    // A scheduled task that throws is cancelled for good; a sweep that failed once must not
    // stop every later one.
    try {
      List<ProjectHookSet> retired = retire();
      if (!retired.isEmpty()) {
        // Closed off this thread: it is the one every hook call's time limit fires on,
        // and a limit must not wait for contexts to close.
        Thread.ofVirtual()
            .name("local-hooks-retire")
            .start(() -> retired.forEach(ProjectHookSet::close));
      }
    } catch (RuntimeException failed) {
      log.warn(
          "the local hook sets could not be swept; they are tried again later. Reason: {}",
          detail(failed));
    }
  }

  /**
   * The log's pin, and its owner when it has one: both are written once, so a pin found is cached
   * with its owner for good. A lookup of either that throws caches nothing.
   */
  private Pin pinOf(String conversation) {
    Instant now = clock.get();
    Pin known = pins.get(conversation);
    if (known != null
        && (known.hash().isPresent() || now.isBefore(known.lookedUp().plus(MISS_BELIEVED)))) {
      return known;
    }
    Optional<String> read = Objects.requireNonNull(pinnedTo.apply(conversation), "pinnedTo");
    Optional<String> owner =
        read.isEmpty()
            ? Optional.empty()
            : Objects.requireNonNull(ownerOf.apply(conversation), "ownerOf");
    Pin looked = new Pin(read, owner, now);
    pins.put(conversation, looked);
    return looked;
  }

  /** An empty tier for the rest of this log, said by the first fire to find it so. */
  private ProjectHookSet withhold(String conversation, String reason) {
    return withheld.putIfAbsent(conversation, Boolean.TRUE) == null
        ? ProjectHookSet.withheld(reason)
        : ProjectHookSet.empty();
  }

  private static <V> Map<String, V> remembering(int capacity) {
    return Collections.synchronizedMap(
        new LinkedHashMap<String, V>(64, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
            return size() > capacity;
          }
        });
  }

  private static String detail(RuntimeException failed) {
    String message = failed.getMessage();
    return message == null || message.isBlank()
        ? failed.getClass().getSimpleName()
        : failed.getClass().getSimpleName() + ": " + message;
  }
}
