package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.delivery.PersonDelivery;
import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.EventPayload;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.swarm.DispatcherPools;
import io.aeyer.plowshare.server.swarm.RunShareProvider;
import io.aeyer.plowshare.server.swarm.SwarmProperties;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composes the public forum, participant seats, topic allowances and person deliveries.
 *
 * <p>Store, board and seat runner use the same wall-clock kind so wake silence and retained message
 * times remain comparable. Wake ownership and recovery are registered before application-ready
 * draining. Messaging registers its own typed wake family independently.
 */
@Configuration
public class BoardConfig {

  private static final Logger log = LoggerFactory.getLogger(BoardConfig.class);

  @Bean
  public BoardStore boardStore(JdbcTemplate jdbc, AllowanceRepository allowances) {
    return new BoardStore(jdbc, Instant::now, allowances);
  }

  /**
   * {@code swarm/} definitions as the project's resolved agents see it — the same resolution a run
   * in that project gets, without a session: a swarm is the project's, never one client's.
   */
  @Bean
  public SwarmDefinitions swarmDefinitions(
      DataLayout data,
      DefinitionResolver resolver,
      LlmDispatcher llm,
      ProjectWorkspaces projects,
      ApplicationPolicy applications,
      Callers callers) {
    return new SwarmDefinitions(
        new ServerSwarmSources(projects, applications, data),
        project -> resolver.forCaller(callers.callerFor(project, null)),
        new DispatcherPools(llm));
  }

  @Bean
  public BoardPot boardPot(BoardStore store) {
    return new BoardPot(store);
  }

  /**
   * New topics select a named project definition; all later board actions use retained
   * participants.
   *
   * <p><b>A bound closing reserve outside 0–99 is refused here</b>, for {@code SwarmConfig}'s
   * reason: the live read falls back to the bound value whenever the map holds nothing usable, so a
   * bad bound value would never be corrected at runtime. It would not fail loudly either — {@code
   * Board.reserveOf} clamps it into the pot, so a hundred or more would quietly leave every topic's
   * members one model call.
   */
  @Bean
  public Board board(
      BoardStore store,
      SwarmCatalog swarms,
      Callers callers,
      ConversationStore conversations,
      FiringStore firings,
      Dispatcher dispatcher,
      UnitOfWork work,
      SwarmProperties properties,
      ProjectMembers members,
      ObjectProvider<LogStages> logStages) {
    int reserve = properties.getClosingReserve();
    if (reserve < 0 || reserve >= 100) {
      throw new IllegalStateException(
          "plowshare.swarm.closing-reserve is "
              + reserve
              + "; a closing reserve is a percent of the pot, 0 to 99");
    }
    if (properties.getMaxDepth() < 0) {
      throw new IllegalStateException("plowshare.swarm.max-depth must be zero or greater");
    }
    Board board =
        new Board(
            store,
            swarms,
            conversations,
            firings,
            dispatcher::drain,
            work,
            properties::closingReserveNow,
            Instant::now,
            members);
    board.useMaxDepth(properties::maxDepthNow);
    LogStages stages = logStages.getIfAvailable();
    if (stages != null) {
      board.useLogStages(stages);
    }
    return board;
  }

  /**
   * The seat runner, and the dispatcher's wakes handed to it here — see this class's javadoc for
   * why here. A member's definition is read as its project's caller reads it, without the exported
   * check: a seat is the swarm's, and the selected swarm definition already refused any member that
   * does not resolve. It is handed the dispatcher's drain as well, for the wakes it leaves queued
   * while their root's pot is leased out: a settle drains them.
   *
   * <p>A bound wake cap below one is refused here, for the closing reserve's reason above; the
   * runner's own floor of one would otherwise hide it as a cap of one step.
   */
  @Bean
  public SeatRunner seatRunner(
      BoardStore store,
      Board board,
      BoardPot pot,
      Turn turn,
      Callers callers,
      Dispatcher dispatcher,
      SwarmProperties properties,
      LlmDispatcher llm) {
    if (properties.getWakeCap() < 1) {
      throw new IllegalStateException(
          "plowshare.swarm.wake-cap is "
              + properties.getWakeCap()
              + "; a wake cap is a number of steps, at least 1");
    }
    SeatRunner runner =
        new SeatRunner(
            store,
            board,
            pot,
            new SeatRunner.Voice() {
              @Override
              public boolean isSpeaking(String conversation) {
                return turn.isSpeaking(conversation);
              }

              @Override
              public String speakToSeat(
                  String conversation,
                  AgentDefinition member,
                  String utterance,
                  Budget lease,
                  TurnCap wakeCap,
                  Speaker speaker,
                  Consumer<Outcome> ended) {
                return turn.speakToSeat(
                    conversation, member, utterance, lease, wakeCap, speaker, ended);
              }
            },
            (agent, home) -> callers.readAgent(agent, callers.callerFor(home.project(), null)),
            properties::wakeCapNow,
            Instant::now,
            dispatcher::drain,
            new DispatcherPools(llm));
    dispatcher.registerWakes("board", wake -> wake.data() instanceof EventPayload.Seat, runner);
    return runner;
  }

  @Bean
  public BoardDelegates boardDelegates(
      BoardStore store,
      Board board,
      BoardPot pot,
      ConversationStore conversations,
      Turn turn,
      Dispatcher dispatcher,
      SwarmProperties properties) {
    BoardDelegates delegates =
        new BoardDelegates(
            store,
            board,
            pot,
            conversations,
            properties::wakeCapNow,
            dispatcher::drain,
            turn::isSpeaking);
    turn.useDelegatedAllowances(delegates::lease);
    return delegates;
  }

  /** Board shares compose with other lifecycle owners in the scheduler configuration. */
  @Bean
  @Order(20)
  public RunShareProvider boardShares(BoardStore store, ConversationStore conversations) {
    return context ->
        shareOf(store, conversations)
            .apply(context)
            .map(share -> new RunShareProvider.Selection(share, false));
  }

  /**
   * A run is scheduled when its conversation is a member's seat: as the root topic's account, on
   * this topic, as this member (spec §5's fair-share key). Every other run answers empty and runs
   * exactly as it always did.
   *
   * <p><b>An opener seat answers empty too</b> — the final review's I-3. The opener is the person's
   * own bot, and the selected swarm definition checks only its members' models against the pools
   * that declare {@code swarm:} slots; a bot's model may be served by none of them, and its
   * scheduled run would then wait for a slot forever. The swarm slots ration members; the opener
   * runs unscheduled, as that bot's own turns in its person's conversation do.
   */
  public static Function<RunExtras.Context, Optional<SwarmScheduler.Share>> shareOf(
      BoardStore store) {
    return context ->
        Optional.ofNullable(context.conversationId())
            .flatMap(store::seatByConversation)
            .flatMap(
                seat ->
                    store
                        .topic(seat.topic())
                        .filter(
                            topic ->
                                !seat.isOpener() || BoardTopic.BY_MEMBER.equals(topic.openerKind()))
                        .map(
                            topic ->
                                new SwarmScheduler.Share(
                                    store.topic(topic.root()).orElseThrow().account(),
                                    topic.id(),
                                    seat.occupant())));
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
          if (seat.isOpener() && !delegated && !BoardTopic.BY_MEMBER.equals(topic.openerKind())) {
            return Optional.empty();
          }
          BoardTopic root = store.topic(topic.root()).orElseThrow();
          return Optional.of(new SwarmScheduler.Share(root.account(), topic.id(), seat.occupant()));
        }
        var row = conversations.find(id);
        if (row.isEmpty() || row.get().origin() != Origin.DELEGATION) {
          return Optional.empty();
        }
        delegated = true;
        id = row.get().parentId();
      }
      return Optional.empty();
    };
  }

  @Bean
  public BoardRunExtras boardRunExtras(
      BoardStore store, Board board, ConversationStore conversations) {
    return new BoardRunExtras(store, board, conversations);
  }

  @Bean
  public BoardDelivery boardDelivery(
      BoardStore store,
      Board board,
      ConversationStore conversations,
      Turn turn,
      Callers callers,
      Inbox inbox) {
    PersonDelivery people =
        new PersonDelivery(
            conversations,
            new PersonDelivery.Voice() {
              @Override
              public boolean isSpeaking(String conversation) {
                return turn.isSpeaking(conversation);
              }

              @Override
              public void speakForAccount(
                  String conversation, String agent, String text, Speaker speaker, String account) {
                callers.requireConversationProject(conversation, account);
                try {
                  callers.requireWork(turn.homeOf(conversation).project(), account);
                } catch (CallerFault denied) {
                  throw new Turn.Refused("The recipient cannot execute this delivery");
                }
                AgentDefinition definition =
                    callers.requireAgent(agent, callers.callerForConversation(conversation, null));
                turn.deliverForExecutor(
                    conversation, definition, text, speaker, outcome -> {}, account);
              }
            },
            inbox::notify);
    BoardDelivery delivery = new BoardDelivery(store, people, turn::isSpeaking);
    board.useResolutions(delivery::resolved);
    board.useNotices(delivery::drainNotices);
    turn.whenFree(delivery::drainFor);
    return delivery;
  }

  /**
   * At boot: every seat of every open topic is re-owed what still addresses it, and every
   * resolution still owed is delivered (spec §5, Restart). Queued wakes survive a restart in
   * firings and are drained by the events' own boot listener; what this adds is the wakes a restart
   * lost with the runs it interrupted.
   */
  @Bean
  public InitializingBean boardAtBoot(
      BoardStore store,
      Board board,
      BoardDelivery delivery,
      SwarmProperties properties,
      Dispatcher dispatcher) {
    return () ->
        dispatcher.registerWakeRecovery(
            "board",
            () -> {
              if (properties.isRecoverAtBoot()) {
                recover(store, board, delivery);
              } else {
                log.info(
                    "plowshare.swarm.recover-at-boot is false: the board's lost wakes and"
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
          log.warn("boot: topic {} could not re-owe {}", topic.id(), seat.occupant(), unowed);
        }
      }
    }
    store.openTopics().stream()
        .map(BoardTopic::root)
        .distinct()
        .forEach(
            root -> {
              try {
                board.settled(root);
              } catch (RuntimeException failed) {
                log.warn("boot: lifecycle check failed for {}", root, failed);
              }
            });
    try {
      delivery.drainAll();
    } catch (RuntimeException undelivered) {
      log.warn("boot: owed resolutions could not be drained", undelivered);
    }
  }
}
