package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.FiringRecord;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a queued wake into a seat's turn — spec 2026-09-29 §5–§9. The dispatcher asks it to start a
 * wake; it takes a lease from the root's pot, tells the seat why it was woken, and speaks to it as
 * its agent, capped at the wake cap. When the turn ends it settles the lease, counts a wake that
 * said nothing as silent, and notes a failed one on the topic.
 */
public final class SeatRunner implements Dispatcher.Wakes {

  private static final Logger log = LoggerFactory.getLogger(SeatRunner.class);

  private static final String SPENT =
      "the topic's budget is spent, so this wake was not"
          + " started; the topic is exhausted until someone tops it up";

  /**
   * Endings that stop a seat until someone @-mentions it (spec §9). CALL_FAILURES is here with
   * STUCK because §9 names both: a wake whose model calls kept failing is not a seat that chose to
   * say nothing, and counting it silent would leave it re-woken by the next reply, failing the same
   * way on the pot's money each time.
   */
  private static final Set<Outcome.Ending> FAILED =
      EnumSet.of(
          Outcome.Ending.STUCK,
          Outcome.Ending.CALL_FAILURES,
          Outcome.Ending.UNAVAILABLE,
          Outcome.Ending.SUB_AGENT_FAILED,
          Outcome.Ending.SESSION_GONE);

  /** {@code Turn}'s seat door, as the runner needs it. */
  public interface Voice {

    boolean isSpeaking(String conversation);

    String speakToSeat(
        String conversation,
        AgentDefinition member,
        String utterance,
        Budget lease,
        TurnCap wakeCap,
        Speaker speaker,
        Consumer<Outcome> ended);
  }

  /** An agent definition as it resolves in a project. */
  public interface Definitions {

    AgentDefinition of(String agent, Home home);
  }

  private record Running(String topic, TurnCap cap, Budget lease) {}

  private final Map<String, Running> running = new ConcurrentHashMap<>();

  private final BoardStore store;
  private final Board board;
  private final BoardPot pot;
  private final Voice voice;
  private final Definitions definitions;
  private final IntSupplier wakeCap;
  private final Supplier<Instant> clock;
  private final Consumer<String> drain;
  private final SwarmScheduler.Pools pools;

  /**
   * @param drain the dispatcher's drain, asked for each target still waiting in a root's tree once
   *     a lease on that root settles — see {@link #busy}
   */
  public SeatRunner(
      BoardStore store,
      Board board,
      BoardPot pot,
      Voice voice,
      Definitions definitions,
      IntSupplier wakeCap,
      Supplier<Instant> clock,
      Consumer<String> drain,
      SwarmScheduler.Pools pools) {
    this.store = Objects.requireNonNull(store, "store");
    this.board = Objects.requireNonNull(board, "board");
    this.pot = Objects.requireNonNull(pot, "pot");
    this.voice = Objects.requireNonNull(voice, "voice");
    this.definitions = Objects.requireNonNull(definitions, "definitions");
    this.wakeCap = Objects.requireNonNull(wakeCap, "wakeCap");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.drain = Objects.requireNonNull(drain, "drain");
    this.pools = Objects.requireNonNull(pools, "pools");
    board.whenClosed(
        topic ->
            running.values().stream()
                .filter(wake -> wake.topic().equals(topic))
                .forEach(
                    wake -> {
                      wake.cap().changeTo(1);
                      // Delegated calls share this lease, so they also stop at their next boundary.
                      wake.lease().changeTo(Math.max(1, wake.lease().spent()));
                    }));
  }

  /**
   * Whether the wake must wait: its seat has a turn in flight, or its root's pot is leased out to
   * other wakes but not spent. The second is the final review's I-2. A pot of 20 with a reserve of
   * 2 and a wake cap of 12 has nothing for a three-member swarm's third member while the first two
   * run, though it has spent nothing; answering "not busy" there sent the wake to {@link #start},
   * which found an empty lease, refused it and exhausted the topic on its first open. Answering
   * busy leaves it queued in the dispatcher, and the settle that frees capacity drains it ({@link
   * #afterWake}).
   *
   * <p>Anything that stops the wake for good — no seat, a closed topic, an exhausted root for a
   * member, a spent pot — answers not busy, so {@link #start} is reached and refuses it with its
   * reason, rather than it waiting for a settle that frees nothing it could use.
   */
  @Override
  public boolean busy(FiringRecord wake) {
    String conversation = conversationOf(wake);
    if (voice.isSpeaking(conversation)) {
      return true;
    }
    Optional<BoardSeat> seat = store.seatByConversation(conversation);
    if (seat.isEmpty()) {
      return false;
    }
    Optional<BoardTopic> topic = store.topic(seat.get().topic());
    if (topic.isEmpty() || topic.get().isClosed() || refusedAsExhausted(topic.get(), seat.get())) {
      return false;
    }
    String root = topic.get().root();
    boolean opener = seat.get().isOpener();
    return !pot.leasable(root, opener) && !pot.spent(root, opener);
  }

  /**
   * @throws IllegalStateException refusing the wake — no seat, a closed topic, an exhausted root
   *     for a member, or a spent pot (which also moves the root to exhausted); the dispatcher
   *     records the reason. Also thrown, not as a refusal, when the pot was leased out between
   *     {@link #busy} and here: {@link #busy} then answers true, and the dispatcher releases the
   *     wake back to its queue instead of refusing it
   */
  @Override
  public String start(FiringRecord wake, BiConsumer<String, Outcome> ended) {
    String conversation = conversationOf(wake);
    BoardSeat seat =
        store
            .seatByConversation(conversation)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "conversation " + conversation + " is no seat on any" + " board"));
    BoardTopic topic = store.topic(seat.topic()).orElseThrow();
    if (topic.isClosed()) {
      throw new IllegalStateException("topic closed");
    }
    if (refusedAsExhausted(topic, seat)) {
      throw new IllegalStateException(SPENT);
    }
    String agent = seat.isOpener() ? topic.opener() : seat.occupant();
    AgentDefinition definition = definitions.of(agent, Home.of(topic.project()));
    // Members retain their ordinary definition tools, grants and hooks.
    boolean swarmSeat = !seat.isOpener() || BoardTopic.BY_MEMBER.equals(topic.openerKind());
    if (swarmSeat && pools.serving(definition.model()).isEmpty()) {
      throw new IllegalStateException(
          "'"
              + agent
              + "' now runs on the model '"
              + definition.model()
              + "', which no pool with swarm slots serves, so this"
              + " wake was not started");
    }
    // Everything that can throw or hit the database — parsing the wake's data, resolving its
    // reason, and counting what is unread — runs before pot.lease is ever asked. Once a lease
    // is granted, nothing stands between it and the guarded speakToSeat call but clock.get():
    // an unknown reason or a countAfter failure must refuse the wake outright rather than
    // leave calls stuck in BoardPot.leased with no settle to give them back.
    SeatWake data = ((io.aeyer.plowshare.server.events.EventPayload.Seat) wake.data()).wake();
    String utterance =
        WakeUtterance.of(
            topic,
            data.reasonKind(),
            data.by() == null ? "" : data.by(),
            store.countAfter(topic.id(), seat.seenThrough()));
    int cap = Math.max(1, wakeCap.getAsInt());
    if (data.maxTurns() != null) cap = data.maxTurns();
    // Only a spent pot exhausts the root. One that is merely leased out to other wakes is a
    // wake that waits: busy() answered false a moment ago, so another wake took the last of
    // it since. Thrown, not refused — busy() now answers true, and the dispatcher's
    // "start threw and the target is busy" path puts the wake back in its queue, where the
    // next settle on this root drains it.
    if (pot.spent(topic.root(), seat.isOpener())) {
      // The dispatcher calls settled after refusing this firing, once the topic is idle.
      throw new IllegalStateException(SPENT);
    }
    Optional<Budget> leased = pot.lease(topic.root(), seat.isOpener(), cap);
    if (leased.isEmpty()) {
      throw new IllegalStateException(
          "the topic's budget is leased out to other wakes;"
              + " this one waits for one of them to end");
    }
    Budget lease = leased.get();
    Instant began = clock.get();
    TurnCap capForWake = TurnCap.of(cap);
    running.put(conversation, new Running(topic.id(), capForWake, lease));
    try {
      return voice.speakToSeat(
          conversation,
          definition,
          utterance,
          lease,
          capForWake,
          Speaker.board(topic.id()),
          outcome -> {
            running.remove(conversation);
            try {
              afterWake(topic, seat, lease, began, outcome);
            } finally {
              ended.accept(conversation, outcome);
            }
          });
    } catch (RuntimeException refused) {
      running.remove(conversation);
      // Nothing ran, so nothing was spent: the whole lease comes back. Its own try/catch —
      // a settle failure here must never replace the refusal actually being reported, which
      // is what the caller (and the dispatcher's queue) needs to see.
      try {
        pot.settle(topic.root(), lease);
      } catch (RuntimeException unsettled) {
        log.warn(
            "a wake refused to start on topic {} and its lease could not be settled" + " back",
            topic.id(),
            unsettled);
        throw refused;
      }
      // Given back, so drained like any settle: a wake on another seat may have found this
      // lease outstanding in the moment it was held and be waiting for it now. This wake's
      // own target is still claimed, so the drain passes over it.
      drainTree(topic.root());
      throw refused;
    }
  }

  @Override
  public void settled(FiringRecord wake) {
    store.topic(wake.topic()).ifPresent(topic -> board.settled(topic.root()));
  }

  private void afterWake(
      BoardTopic topic, BoardSeat seat, Budget lease, Instant began, Outcome outcome) {
    pot.settle(topic.root(), lease);
    // Settlement returns the unused reservation and starts wakes that busy() left waiting
    // on this root. Before this wake's own end reaches the dispatcher: its
    // firing is still started, so its own target is passed over here and drained by the
    // dispatcher's wakeEnded once it is finished.
    drainTree(topic.root());
    try {
      if (FAILED.contains(outcome.ending())) {
        store.recordFailure(topic.id(), seat.occupant(), outcome.ending().name());
        board.note(
            topic.id(),
            seatName(topic, seat)
                + " stopped: its wake ended "
                + outcome.ending().name()
                + (seat.isOpener()
                    ? ". It is woken again by a reply or alert."
                    : ". It is not woken again until someone @-mentions it."));
      } else if (outcome.ending() != Outcome.Ending.CANCELLED
          && store.messagesSince(topic.id(), seat.conversation(), began) == 0) {
        // Neither posted nor passed: the narrate-instead-of-act failure, made visible.
        store.recordSilent(topic.id(), seat.occupant());
      }
    } catch (RuntimeException unrecorded) {
      log.warn("the end of a wake on topic {} could not be recorded", topic.id(), unrecorded);
    }
    if (outcome.ending() == Outcome.Ending.TURN_CAP) {
      try {
        // Other endings leave any mid-wake messages queued for the dispatcher's drain.
        // Re-owing an unread opening after ANSWERED loops until the pot is spent (R1).
        board.reowe(topic.id(), seat.occupant());
      } catch (RuntimeException unowed) {
        log.warn(
            "topic {}: a fresh wake for {} could not be owed", topic.id(), seat.occupant(), unowed);
      }
    }
  }

  /**
   * Drains every target with a wake queued in {@code root}'s tree. Logged, never thrown: it runs on
   * a wake's way out, where a failure must not stop the wake's own end from being recorded, and a
   * wake it could not start stays queued for the next settle or the boot drain.
   */
  private void drainTree(String root) {
    List<String> targets;
    try {
      targets = store.queuedWakeTargets(root);
    } catch (RuntimeException unread) {
      log.warn("the wakes waiting on root {} could not be listed to drain", root, unread);
      return;
    }
    for (String target : targets) {
      try {
        drain.accept(target);
      } catch (RuntimeException undrained) {
        log.warn("draining {} after a settle on root {} failed", target, root, undrained);
      }
    }
  }

  /** A member's wake under an exhausted root: only an opener still runs there (spec §9). */
  private boolean refusedAsExhausted(BoardTopic topic, BoardSeat seat) {
    if (seat.isOpener()) {
      return false;
    }
    BoardTopic root = topic.isRoot() ? topic : store.topic(topic.root()).orElse(topic);
    return BoardTopic.EXHAUSTED.equals(root.state());
  }

  private static String seatName(BoardTopic topic, BoardSeat seat) {
    return seat.isOpener() ? topic.opener() + " (the opener)" : seat.occupant();
  }

  private static String conversationOf(FiringRecord wake) {
    String target = wake.target();
    if (target == null || !target.startsWith("conversation:")) {
      throw new IllegalStateException("wake " + wake.id() + " names no seat conversation");
    }
    return target.substring("conversation:".length());
  }
}
