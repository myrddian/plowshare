package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.delivery.PersonDelivery;
import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.swarm.DispatcherPools;
import io.aeyer.plowshare.server.swarm.SwarmProperties;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import io.aeyer.plowshare.server.swarm.SwarmScheduling;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The board — spec 2026-09-29, the project board and the swarm, step 2. Wires the store, the
 * swarm, the pot and the service; hands the dispatcher its wakes; and switches the swarm
 * scheduler on for seat runs, which step 1 built and left unwired.
 *
 * <p><b>One clock kind for the store, the board and the seat runner.</b> All three are given
 * {@code Instant::now}. A seat runner decides a wake was silent by asking the store for messages
 * posted since the wake began, so the instant it records a wake's start and the instant the
 * board stamps a message's {@code posted_at} must come from clocks that can be compared; a fixed
 * or offset clock on any one of the three would count every wake silent, or none.
 *
 * <p><b>The seams are set inside the {@code @Bean} methods, not after the context is up.</b>
 * {@code EventsConfig.eventsAtBoot} drains every waiting firing target on {@code
 * ApplicationReadyEvent}, and a wake left queued by the last run is among them; were the
 * dispatcher handed its {@link SeatRunner} any later than the runner's own construction, that
 * drain would find {@code Wakes.NONE} and refuse a wake that had a runner all along. Singletons
 * are built before the ready event, so setting the seam here is always first. {@link
 * JobRuntime#useScheduling} is set the same way for the same reason — a seat run started by that
 * drain must already be scheduled.
 *
 * <p><b>No bean cycle.</b> Nothing below the board — the dispatcher, {@code Turn}, {@code
 * Callers}, the job runtime, the scheduler — asks for any bean here, so every collaborator is
 * taken directly. {@link LogStages} is asked through a provider only because a context without
 * the log stages (a slice test) is a board with {@link LogStages#NONE}, as every other holder of
 * that seam is.
 */
@Configuration
public class BoardConfig {

    private static final Logger log = LoggerFactory.getLogger(BoardConfig.class);

    @Bean
    public BoardStore boardStore(JdbcTemplate jdbc) {
        return new BoardStore(jdbc, Instant::now);
    }

    /**
     * {@code swarm.md} as the project's resolved agents see it — the same resolution a run in
     * that project gets, without a session: a swarm is the project's, never one client's.
     */
    @Bean
    public SwarmDefinitions swarmDefinitions(DataLayout data, DefinitionResolver resolver,
            LlmDispatcher llm) {
        return new SwarmDefinitions(data,
                projectId -> resolver.forCaller(new DefinitionResolver.Caller(projectId, null)),
                new DispatcherPools(llm));
    }

    @Bean
    public BoardPot boardPot(BoardStore store) {
        return new BoardPot(store);
    }

    /**
     * A topic's project is a name; {@link Callers#callerFor} turns it into the id {@code
     * swarm.md} is looked up under, leniently — a name with no project row resolves to the global
     * tier's swarm, as a run's own caller would.
     *
     * <p><b>A bound closing reserve outside 0–99 is refused here</b>, for {@code SwarmConfig}'s
     * reason: the live read falls back to the bound value whenever the map holds nothing usable,
     * so a bad bound value would never be corrected at runtime. It would not fail loudly either —
     * {@code Board.reserveOf} clamps it into the pot, so a hundred or more would quietly leave
     * every topic's members one model call.
     */
    @Bean
    public Board board(BoardStore store, SwarmDefinitions swarms, Callers callers,
            ConversationStore conversations, FiringStore firings, Dispatcher dispatcher,
            UnitOfWork work, SwarmProperties properties, ObjectProvider<LogStages> logStages) {
        int reserve = properties.getClosingReserve();
        if (reserve < 0 || reserve >= 100) {
            throw new IllegalStateException("plowshare.swarm.closing-reserve is " + reserve
                    + "; a closing reserve is a percent of the pot, 0 to 99");
        }
        if (properties.getMaxDepth() < 0) {
            throw new IllegalStateException("plowshare.swarm.max-depth must be zero or greater");
        }
        Board board = new Board(store,
                project -> swarms.forProject(callers.callerFor(project, null).projectId()),
                conversations, firings, dispatcher::drain, work, properties::closingReserveNow,
                Instant::now);
        board.useMaxDepth(properties::maxDepthNow);
        LogStages stages = logStages.getIfAvailable();
        if (stages != null) {
            board.useLogStages(stages);
        }
        return board;
    }

    /**
     * The seat runner, and the dispatcher's wakes handed to it here — see this class's javadoc
     * for why here. A member's definition is read as its project's caller reads it, without the
     * exported check: a seat is the swarm's, and {@code swarm.md} already refused any member that
     * does not resolve. It is handed the dispatcher's drain as well, for the wakes it leaves
     * queued while their root's pot is leased out: a settle drains them.
     *
     * <p>A bound wake cap below one is refused here, for the closing reserve's reason above; the
     * runner's own floor of one would otherwise hide it as a cap of one step.
     */
    @Bean
    public SeatRunner seatRunner(BoardStore store, Board board, BoardPot pot, Turn turn,
            Callers callers, Dispatcher dispatcher, SwarmProperties properties, LlmDispatcher llm) {
        if (properties.getWakeCap() < 1) {
            throw new IllegalStateException("plowshare.swarm.wake-cap is "
                    + properties.getWakeCap() + "; a wake cap is a number of steps, at least 1");
        }
        SeatRunner runner = new SeatRunner(store, board, pot, new SeatRunner.Voice() {
            @Override
            public boolean isSpeaking(String conversation) {
                return turn.isSpeaking(conversation);
            }

            @Override
            public String speakToSeat(String conversation, AgentDefinition member,
                    String utterance, Budget lease, TurnCap wakeCap, Speaker speaker,
                    Consumer<Outcome> ended) {
                return turn.speakToSeat(conversation, member, utterance, lease, wakeCap, speaker,
                        ended);
            }
        }, (agent, home) -> callers.readAgent(agent, callers.callerFor(home.project(), null)),
                properties::wakeCapNow, Instant::now, dispatcher::drain, new DispatcherPools(llm));
        dispatcher.useWakes(runner);
        return runner;
    }

    @Bean
    public BoardDelegates boardDelegates(BoardStore store, Board board, BoardPot pot,
            ConversationStore conversations, Turn turn, Dispatcher dispatcher,
            SwarmProperties properties) {
        BoardDelegates delegates = new BoardDelegates(store, board, pot, conversations,
                properties::wakeCapNow, dispatcher::drain, turn::isSpeaking);
        turn.useDelegatedAllowances(delegates::lease);
        return delegates;
    }

    /** The swarm scheduler, switched on for seat runs — see this class's javadoc for why here. */
    @Bean
    public SwarmScheduling swarmScheduling(SwarmScheduler scheduler, BoardStore store,
            JobRuntime runtime, ConversationStore conversations, LlmDispatcher llm) {
        SwarmScheduling scheduling = new SwarmScheduling(scheduler, shareOf(store, conversations),
                new DispatcherPools(llm));
        runtime.useScheduling(scheduling);
        return scheduling;
    }

    /**
     * A run is scheduled when its conversation is a member's seat: as the root topic's account,
     * on this topic, as this member (spec §5's fair-share key). Every other run answers empty and
     * runs exactly as it always did.
     *
     * <p><b>An opener seat answers empty too</b> — the final review's I-3. The opener is the
     * person's own bot, and {@code swarm.md} checks only its members' models against the pools
     * that declare {@code swarm:} slots; a bot's model may be served by none of them, and its
     * scheduled run would then wait for a slot forever. The swarm slots ration members; the
     * opener runs unscheduled, as that bot's own turns in its person's conversation do.
     */
    public static Function<RunExtras.Context, Optional<SwarmScheduler.Share>> shareOf(
            BoardStore store) {
        return context -> Optional.ofNullable(context.conversationId())
                .flatMap(store::seatByConversation)
                .flatMap(seat -> store.topic(seat.topic())
                        .filter(topic -> !seat.isOpener() || BoardTopic.BY_MEMBER.equals(topic.openerKind()))
                        .map(topic ->
                        new SwarmScheduler.Share(store.topic(topic.root()).orElseThrow().account(),
                                topic.id(), seat.occupant())));
    }

    /** Delegations inherit the seat's share, including descendants of an opener seat. */
    public static Function<RunExtras.Context, Optional<SwarmScheduler.Share>> shareOf(
            BoardStore store, ConversationStore conversations) {
        return context -> {
            String id = context.conversationId();
            boolean delegated = false;
            while (id != null) {
                Optional<BoardSeat> found = store.seatByConversation(id);
                if (found.isPresent()) {
                    BoardSeat seat = found.get();
                    BoardTopic topic = store.topic(seat.topic()).orElseThrow();
                    if (seat.isOpener() && !delegated
                            && !BoardTopic.BY_MEMBER.equals(topic.openerKind())) { return Optional.empty(); }
                    BoardTopic root = store.topic(topic.root()).orElseThrow();
                    return Optional.of(new SwarmScheduler.Share(root.account(), topic.id(), seat.occupant()));
                }
                var row = conversations.find(id);
                if (row.isEmpty() || row.get().origin() != io.aeyer.plowshare.server.archive.Origin.DELEGATION) {
                    return Optional.empty();
                }
                delegated = true;
                id = row.get().parentId();
            }
            return Optional.empty();
        };
    }

    @Bean
    public BoardRunExtras boardRunExtras(BoardStore store, Board board,
            ConversationStore conversations) {
        return new BoardRunExtras(store, board, conversations);
    }

    @Bean
    public BoardDelivery boardDelivery(BoardStore store, Board board,
            ConversationStore conversations, Turn turn, Callers callers, Inbox inbox) {
        PersonDelivery people = new PersonDelivery(conversations, new PersonDelivery.Voice() {
            @Override
            public boolean isSpeaking(String conversation) {
                return turn.isSpeaking(conversation);
            }

            @Override
            public void speak(String conversation, String agent, String text, Speaker speaker) {
                AgentDefinition definition = callers.requireAgent(agent,
                        callers.callerForConversation(conversation, null));
                turn.deliver(conversation, definition, text, speaker, outcome -> { });
            }
        }, inbox::notify);
        BoardDelivery delivery = new BoardDelivery(store, people, turn::isSpeaking);
        board.useResolutions(delivery::resolved);
        board.useNotices(delivery::drainNotices);
        turn.whenFree(delivery::drainFor);
        return delivery;
    }

    /**
     * At boot: every seat of every open topic is re-owed what still addresses it, and every
     * resolution still owed is delivered (spec §5, Restart). Queued wakes survive a restart in
     * firings and are drained by the events' own boot listener; what this adds is the wakes a
     * restart lost with the runs it interrupted.
     */
    @Bean
    public org.springframework.beans.factory.InitializingBean boardAtBoot(BoardStore store, Board board,
            BoardDelivery delivery, SwarmProperties properties, Dispatcher dispatcher) {
        return () -> dispatcher.useWakeRecovery(() -> {
            if (properties.isRecoverAtBoot()) {
                recover(store, board, delivery);
            } else {
                log.info("plowshare.swarm.recover-at-boot is false: the board's lost wakes and"
                        + " owed resolutions wait for their next message or free conversation");
            }
        });
    }

    static void recover(BoardStore store, Board board, BoardDelivery delivery) {
        for (BoardTopic topic : store.openTopics()) {
            for (BoardSeat seat : store.seats(topic.id())) {
                try {
                    board.reowe(topic.id(), seat.occupant(), true);
                } catch (RuntimeException unowed) {
                    log.warn("boot: topic {} could not re-owe {}", topic.id(), seat.occupant(),
                            unowed);
                }
            }
        }
        store.openTopics().stream().map(BoardTopic::root).distinct().forEach(root -> {
            try { board.settled(root); }
            catch (RuntimeException failed) { log.warn("boot: lifecycle check failed for {}", root, failed); }
        });
        try {
            delivery.drainAll();
        } catch (RuntimeException undelivered) {
            log.warn("boot: owed resolutions could not be drained", undelivered);
        }
    }
}
