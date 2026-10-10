package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.GlobalAgentDefinitions;
import io.aeyer.plowshare.server.agents.GlobalAgentSnapshots;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Noticing;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.relay.Relay;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires cron schedules, triggers, firings and the user-inbox, and the one daemon thread that ticks
 * them.
 *
 * <h2>{@link AccountPushes} arrives through an {@link ObjectProvider}, resolved lazily</h2>
 *
 * <p>{@code EventChannelHandler} implements {@link AccountPushes} (as well as {@code JobEvents} and
 * {@link SpeakerHandles}), so the bean this reaches at runtime is built by {@code
 * EventChannelConfig}. Taking it as a hard constructor parameter here would make {@link #inbox}
 * depend on the event channel, which depends on {@code SessionRegistry} and a router built over the
 * agent layer this configuration itself feeds — a cycle the container can only refuse. The provider
 * is asked inside the lambda {@link Inbox} holds, on the first delivery, exactly as {@code
 * AgentsConfig#jobRuntime} resolves its own {@code AgentRegistry} supplier late for the identical
 * reason.
 */
@Configuration
@EnableConfigurationProperties(EventsProperties.class)
public class EventsConfig {

  private static final Logger log = LoggerFactory.getLogger(EventsConfig.class);

  @Bean
  public ScheduleStore scheduleStore(JdbcTemplate jdbc) {
    return new JdbcScheduleStore(jdbc);
  }

  @Bean
  public TriggerStore triggerStore(JdbcTemplate jdbc) {
    return new JdbcTriggerStore(jdbc);
  }

  @Bean
  @org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
  public FiringStore firingStore(JdbcTemplate jdbc) {
    var repository = new JdbcFiringStore(jdbc);
    // Refuse cutover before the ticker, event transport or any recovery listener can claim work.
    // Operators must first run this inventory against a restored deployment database copy.
    repository.preflight().requireCompatible();
    return repository;
  }

  @Bean
  public InboxStore inboxStore(JdbcTemplate jdbc) {
    return new JdbcInboxStore(jdbc);
  }

  @Bean
  public Inbox inbox(InboxStore store, ObjectProvider<AccountPushes> pushes) {
    // Lazy: the push side is the event channel, which is built after the frame router,
    // which needs this. Resolved on the first delivery, not at wiring.
    return new Inbox(
        store,
        (handle, body) -> pushes.getIfAvailable(() -> AccountPushes.NONE).push(handle, body),
        Instant::now);
  }

  /**
   * The notice a bot declaring {@code announces-inbox} gets, and the {@code inbox_read} tool that
   * reads it — wired onto {@link JobRuntime} by a setter rather than a constructor argument,
   * because {@code JobRuntime} is built by {@code AgentsConfig} before this bean exists. See {@code
   * JobRuntime.useNoticing} and {@link Noticing}.
   *
   * <p><b>The cycle this does not create.</b> {@code JobRuntime} takes this bean as a parameter and
   * calls a setter on it; it does not depend on anything from the {@code events} package to be
   * constructed, so the dependency runs one way only — from here into {@code agents}, exactly as
   * {@link #dispatcher} already does through {@code Callers}, {@code JobStore} and {@code Turn}.
   */
  @Bean
  public InboxNoticing inboxNoticing(
      InboxStore store, Inbox inbox, ObjectProvider<SpeakerHandles> speakers, JobRuntime runtime) {
    InboxNoticing noticing =
        new InboxNoticing(
            store,
            inbox,
            session -> speakers.getIfAvailable(() -> SpeakerHandles.NONE).handleOf(session));
    runtime.useNoticing(noticing);
    return noticing;
  }

  @Bean
  public Dispatcher dispatcher(
      FiringStore firings,
      TriggerStore triggers,
      Callers callers,
      JobStore jobs,
      Turn turns,
      Inbox inbox,
      ObjectProvider<ScheduleDefinitions> definitions,
      ObjectProvider<ScheduleDefinitions.Authority> authority,
      ObjectProvider<io.aeyer.plowshare.server.board.BoardMessaging> messages,
      ObjectProvider<io.aeyer.plowshare.server.personal.PersonalSpaces> personal) {
    AgentRunner runner = new AgentRunner(callers, jobs, turns);
    runner.useSchedules(
        definitions::getIfAvailable, authority::getIfAvailable, messages::getIfAvailable);
    if (personal.getIfAvailable() != null) runner.usePersonalSpaces(personal.getIfAvailable());
    Dispatcher dispatcher = new Dispatcher(firings, triggers, runner, inbox, Instant::now);
    turns.whenFree(conversation -> dispatcher.drain("conversation:" + conversation));
    return dispatcher;
  }

  @Bean
  public Intake intake(TriggerStore triggers, FiringStore firings, Dispatcher dispatcher) {
    return new Intake(triggers, firings, dispatcher, Instant::now);
  }

  /**
   * The registry is handed over as a provider for the scribe's reason: it is defined late, and a
   * reading asked for before it exists is refused rather than failing the boot.
   */
  @Bean
  public ScheduleReader scheduleReader(
      LlmDispatcher llm,
      ObjectProvider<AgentRegistry> agents,
      ObjectProvider<GlobalAgentDefinitions> globals,
      DefinitionResolver resolver,
      Callers callers) {
    return new ScheduleReader(llm, GlobalAgentSnapshots.supply(globals, agents), resolver, callers);
  }

  @Bean
  public ScheduledPublication scheduledPublication(
      ScheduleStore schedules, Relay relay, ScheduledArrival arrivals, UnitOfWork transactions) {
    return new RelayScheduledPublication(
        schedules,
        relay,
        arrivals,
        transactions,
        () -> {
          if (org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive())
            throw new IllegalStateException(
                "scheduled publication must start outside a database transaction");
        });
  }

  @Bean
  public Ticker ticker(
      ScheduleStore schedules,
      ScheduledPublication publications,
      ObjectProvider<ScheduleDefinitions> definitions) {
    Ticker ticker = new Ticker(schedules, publications);
    ticker.useReconciliation(
        () -> {
          var service = definitions.getIfAvailable();
          if (service != null) service.poll();
        });
    return ticker;
  }

  @Bean(destroyMethod = "shutdownNow")
  public ScheduledExecutorService eventsTickerThread() {
    return Executors.newSingleThreadScheduledExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, "events-ticker");
          thread.setDaemon(true);
          return thread;
        });
  }

  /**
   * At boot, in order: what was running when the server stopped is abandoned, long-missed ticks are
   * rolled forward, whatever was waiting is drained, and then the ticker starts.
   *
   * <p>Two switches, deliberately independent. {@code recover-at-boot} guards the first three: a
   * restart leaves firings {@code started} with no end, and each holds its target, so a server that
   * skipped recovery would never start anything for that target again — including a server whose
   * ticker is off and which only runs {@code event.fire}. {@code ticker-enabled} guards only the
   * thread. The test classpath turns both off, so no {@code @SpringBootTest} context touches
   * firings on boot or fires on a clock no test holds.
   */
  @Bean
  public ApplicationListener<ApplicationReadyEvent> eventsAtBoot(
      FiringStore firings,
      Dispatcher dispatcher,
      Ticker ticker,
      EventsProperties properties,
      ScheduledExecutorService eventsTickerThread,
      io.aeyer.plowshare.server.relay.RelayInternalWorkers relayInternalWorkers) {
    return ready -> {
      if (properties.isRecoverAtBoot()) {
        Instant now = Instant.now();
        firings.abandonUnfinished("the server restarted during this run", now);
        ticker.reconcile();
        ticker.rollForward(now, properties.tickIntervalNow().multipliedBy(2));
      } else {
        log.info(
            "plowshare.events.recover-at-boot is false: runs a restart interrupted"
                + " still hold their targets");
      }
      // Its own switch is independent of event recovery. Claims must be abandoned
      // before the board creates or starts any replacement wake.
      dispatcher.recoverWakes();
      relayInternalWorkers.start();
      if (properties.isRecoverAtBoot()) {
        firings.targetsWaiting().forEach(dispatcher::drain);
      }
      if (properties.isTickerEnabled()) {
        scheduleNext(eventsTickerThread, ticker, properties);
      } else {
        log.info("plowshare.events.ticker-enabled is false: no schedule fires by itself");
      }
    };
  }

  private static void scheduleNext(
      ScheduledExecutorService thread, Ticker ticker, EventsProperties properties) {
    Duration wait = properties.tickIntervalNow();
    thread.schedule(
        () -> {
          try {
            ticker.tick(Instant.now());
          } catch (RuntimeException broken) {
            log.warn("a tick failed; the next interval retries", broken);
          } finally {
            if (!thread.isShutdown()) {
              scheduleNext(thread, ticker, properties);
            }
          }
        },
        wait.toMillis(),
        TimeUnit.MILLISECONDS);
  }
}
