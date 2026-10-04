package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.agents.CallerAccess;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.Commands;
import io.aeyer.plowshare.server.agents.ConductorActions;
import io.aeyer.plowshare.server.agents.ConductorTools;
import io.aeyer.plowshare.server.agents.DefinitionChecks;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.Environments;
import io.aeyer.plowshare.server.agents.Job;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.agents.OrchestrationWriter;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.RunLimits;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.StudioTools;
import io.aeyer.plowshare.server.agents.TodoTools;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnEnd;
import io.aeyer.plowshare.server.approvals.ApprovalDelivery;
import io.aeyer.plowshare.server.approvals.ConductorContinuation;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.RunApprovalStore;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.board.BoardRunExtras;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.orchestrations.OrchestrationMessage.Kind;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.TodoBoard;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
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
 * Wires the orchestration engine into the running server: its store, its delivery to callers, the
 * engine itself, the tools a conductor is handed, the drains a turn's free runs, and what a boot
 * picks back up.
 *
 * <h2>No cycle outwards, one provider inwards</h2>
 *
 * <p>The store depends only on {@link JdbcTemplate} and {@link UnitOfWork}, which is why {@code
 * TodosConfig.todoBoard} can ask for it. Everything else here depends one way into {@code agents}
 * ({@link Turn}, {@link JobRuntime}, {@link JobStore}, {@link Callers}) and {@code events} ({@link
 * Inbox}), and nothing there depends back on a bean defined here: the runtime's extras and the
 * turn's drain are handed over through setters, on {@code EventsConfig.inboxNoticing}'s and {@code
 * EventsConfig.dispatcher}'s pattern.
 *
 * <p>The one cycle is between two beans defined here: {@link Orchestrations} is handed the {@link
 * Delivery} built before it, and that delivery needs the engine back to speak a child's report into
 * its parent. That edge goes through an {@link ObjectProvider}, read when a report is carried up
 * the tree rather than when the bean is built.
 */
@Configuration
@EnableConfigurationProperties(OrchestrationsProperties.class)
public class OrchestrationsConfig {

  private static final Logger log = LoggerFactory.getLogger(OrchestrationsConfig.class);

  /** What a refusal from the definition checks names as where the conductor came from. */
  static final String PINNED = "a pinned orchestration";

  /** The push a run's account is told with, on every committed change to its state. */
  public static final String CHANGED = "orchestration.changed";

  @Bean
  public OrchestrationStore orchestrationStore(JdbcTemplate jdbc, UnitOfWork work) {
    return new OrchestrationStore(jdbc, Instant::now, work);
  }

  /**
   * The record's store — spec 2026-09-28, the orchestration record. The unit of work is what
   * serialises a tree's appends; {@link RecordStore} says why it needs one.
   */
  @Bean
  public RecordStore recordStore(JdbcTemplate jdbc, UnitOfWork work) {
    return new RecordStore(jdbc, Instant::now, work);
  }

  /**
   * The one writer of the record, installed at the three sources outside this package as it is
   * built — the todo board, the turn loop and the approval store — on {@link
   * #orchestrationRunExtras}' setter pattern. The engine, the stall sweep and the stage checks are
   * handed it below.
   *
   * <p>{@code pushes} arrives through a provider on {@link #orchestrations}' reason: a context
   * without the event channel still records, pushing nobody.
   */
  @Bean
  public RecordKeeper recordKeeper(
      RecordStore records,
      OrchestrationStore store,
      ObjectProvider<AccountPushes> pushes,
      TodoBoard board,
      JobRuntime runtime,
      RunApprovalStore approvals) {
    RecordKeeper keeper =
        new RecordKeeper(records, store, () -> pushes.getIfAvailable(() -> AccountPushes.NONE));
    install(keeper, board, runtime, approvals);
    return keeper;
  }

  /**
   * The record's three doors outside this package, each a setter its own package owns so none of
   * them depends on this one.
   */
  static void install(
      OrchestrationRecorder recorder,
      TodoBoard board,
      JobRuntime runtime,
      RunApprovalStore approvals) {
    board.whenMoved(recorder);
    runtime.useActivity(recorder);
    approvals.useEvents(recorder);
  }

  /** Where a run's check is stored — spec 2026-09-26, {@code orchestration_check}. */
  @Bean
  public OrchestrationChecks orchestrationChecks(JdbcTemplate jdbc) {
    return new OrchestrationChecks(jdbc);
  }

  /** Where a run's acceptance commands are registered — spec 2026-09-29 §1b, V60's table. */
  @Bean
  public OrchestrationAcceptance orchestrationAcceptance(JdbcTemplate jdbc, UnitOfWork work) {
    return new OrchestrationAcceptance(jdbc, work);
  }

  /** The acceptance checker's concerns — spec 2026-10-01, V77's table. */
  @Bean
  public OrchestrationConcerns orchestrationConcerns(JdbcTemplate jdbc) {
    return new OrchestrationConcerns(jdbc, Instant::now);
  }

  /**
   * The acceptance checker's harness side (spec 2026-10-01): the concerns, the checker the harness
   * runs — {@code harness.ModelAcceptanceChecker}, or {@link AcceptanceChecker#NONE} in a context
   * without one, which has nothing to say and leaves every concern to the person — and the record
   * its work is told to.
   */
  @Bean
  public Checking checking(
      OrchestrationConcerns concerns,
      ObjectProvider<AcceptanceChecker> checker,
      RecordKeeper recordKeeper) {
    return new Checking(
        concerns, checker.getIfAvailable(() -> AcceptanceChecker.NONE), recordKeeper);
  }

  /** The record's one read, for the frame and the endpoint alike. */
  @Bean
  public RecordReads recordReads(RecordStore records) {
    return new RecordReads(records);
  }

  /**
   * Delivery, with all three of its routes: the caller's conversation, the caller's inbox, and a
   * live parent conductor's own conversation.
   *
   * <p>The engine arrives through a provider because this bean is built first — the engine is
   * handed this delivery — and {@link ParentVoice} needs it only at the moment a report is carried
   * up the tree, long after both exist.
   */
  @Bean
  public Delivery orchestrationDelivery(
      OrchestrationStore store,
      ConversationStore conversations,
      Turn turn,
      Callers callers,
      Inbox inbox,
      ObjectProvider<Orchestrations> orchestrations) {
    return new Delivery(
        store,
        conversations,
        callerVoice(turn, callers),
        inboxPort(inbox),
        turn::isSpeaking,
        parentVoice(store, turn::isSpeaking, orchestrations));
  }

  /**
   * The account's inbox as delivery uses it: news, a question's notice naming what it asks about,
   * and settling one (V68). Every method bound, not {@code inbox::notify} — that binds the
   * three-argument notice alone, and a question's notice would never leave the inbox.
   *
   * @param inbox the account inbox
   * @return the seam {@link Delivery} writes and settles through
   */
  static InboxPort inboxPort(Inbox inbox) {
    return new InboxPort() {
      @Override
      public void notify(String handle, String kind, String text) {
        inbox.notify(handle, kind, text);
      }

      @Override
      public void notify(String handle, String kind, String text, String about) {
        inbox.notify(handle, kind, text, about);
      }

      @Override
      public void notifyFromLog(
          String handle, String kind, String text, String about, String source) {
        inbox.notifyFromLog(handle, kind, text, source, about);
      }

      @Override
      public void settle(String about) {
        inbox.settle(about);
      }
    };
  }

  /**
   * The engine, and the two things it hands the rest of the server as it is built: every run's
   * extras, keyed on a live conductor's conversation, and the drains every freed conversation runs.
   *
   * <p>{@code pushes} arrives through a provider on {@code TodosConfig.todoBoard}'s reason: the
   * push side is the event channel, built after the frame router, and a context without it still
   * builds the engine, pushing nobody.
   *
   * <p>The {@link JobStore} is here only for {@link OrchestrationCancel#jobsIn}: the engine cancels
   * a cascaded descendant's live job through that seam, and knows nothing else about jobs. {@code
   * OrchestrationCancel} keeps the root's own job, so a run that finished on the turn still winding
   * down keeps the turn that delivers its result.
   */
  @Bean
  public Orchestrations orchestrations(
      OrchestrationStore store,
      ConversationStore conversations,
      TodoBoard board,
      UnitOfWork work,
      Turn turn,
      Delivery orchestrationDelivery,
      JobRuntime runtime,
      JobStore jobs,
      DefinitionChecks checks,
      SessionRegistry sessions,
      ObjectProvider<AccountPushes> pushes,
      OrchestrationsProperties properties,
      OrchestrationChecks orchestrationChecks,
      RunApprovalStore approvals,
      Environments environments,
      AgentRegistry agents,
      RecordKeeper recordKeeper,
      OrchestrationAcceptance orchestrationAcceptance,
      CapsSource capsSource,
      ObjectProvider<CommandJudge> judge,
      Checking checking,
      CallerAccess access,
      org.springframework.jdbc.core.JdbcTemplate jdbc) {
    runtime.useScripts(new io.aeyer.plowshare.server.orchestrations.scripted.ScriptStore(jdbc));
    Orchestrations orchestrations =
        new Orchestrations(
            store,
            conversations,
            board,
            board,
            work,
            conductorVoice(turn, agents),
            orchestrationDelivery,
            runtime.knownTools(),
            conductorChecks(checks),
            sessionLive(sessions),
            Instant::now,
            pushChanges(pushes),
            OrchestrationCancel.jobsIn(jobs, conversations),
            properties.getMaxDepth(),
            access);
    orchestrations.useApprovalDrain(runtime::deliverApprovalQuestions);
    orchestrations.useRecorder(recordKeeper);
    // A conductor's agent_run that returned is progress: its nudge count starts again.
    runtime.useDelegationReturned(store::progressedIn);
    // Rule 6 (spec 2026-09-29 §3): a conductor's agent_run hands down the run's real paths.
    runtime.useHandoffs(orchestrations::handoffNote);
    // And, above the task, a reviewer that runs nothing is handed the run's check as the
    // harness last ran it (measured 2026-09-30, orc_3190C667F18B8E57).
    runtime.useTaskFacts(orchestrations::checkFacts);
    orchestrations.useChecks(
        orchestrationChecks, checkConsent(environments, approvals), approvals::find);
    // A check is shown to the command judge before the person is asked about it (V67).
    orchestrations.useJudge(judge.getIfAvailable(() -> CommandJudge.NONE));
    orchestrations.useAcceptance(orchestrationAcceptance);
    // The acceptance checker's harness side (spec 2026-10-01): checker_answer, and the
    // person's answers to its questions.
    orchestrations.useChecking(checking);
    // An ended run's still-asked approvals are withdrawn, not left on the person's list.
    orchestrations.useWithdrawal(id -> approvals.deny(id, RunApproval.RUN_ENDED));
    // The person's caps (spec 2026-09-29 §2): read at every start and rebuilt turn, and moved
    // into a turn in flight through the job's own limits.
    orchestrations.useCaps(capsSource, liveLimits(jobs));
    turn.whenFree(
        drains(
            orchestrationDelivery::drainCaller,
            conversation ->
                store
                    .byConductorConversation(conversation)
                    .ifPresent(run -> orchestrations.speakAnswerIfPending(run.id())),
            conversation ->
                store
                    .byConductorConversation(conversation)
                    .ifPresent(run -> orchestrations.speakDelegateResultIfPending(run.id())),
            conversation ->
                store
                    .byConductorConversation(conversation)
                    .ifPresent(run -> orchestrations.speakAcceptanceAnswersIfPending(run.id()))));
    return orchestrations;
  }

  /**
   * A project's caps, from the server's file and the rooting session's — spec 2026-09-29 §2. The
   * rooting session is whoever serves the project now; with nobody rooting it only the server's
   * file counts, since there is no machine to read the person's from.
   *
   * @param environments where both files are read
   * @param presences which session roots a project
   * @return the caps source the engine and {@code orchestration.caps} read
   */
  @Bean
  public CapsSource capsSource(Environments environments, PresenceRegistry presences) {
    return project ->
        environments.caps(project, presences.serving(project).map(Presence::session).orElse(null));
  }

  /**
   * A conductor conversation's turn in flight, and its limits: the one {@link Job} running on it. A
   * turn's {@link RunLimits} are the objects its loop reads at every step boundary, which is what
   * lets a cap applied now reach a turn already going.
   */
  static Function<String, Optional<RunLimits>> liveLimits(JobStore jobs) {
    return conversation ->
        jobs.jobs().stream()
            .filter(
                job -> conversation.equals(job.conversation()) && job.state() == Job.State.RUNNING)
            .findFirst()
            .flatMap(Job::limits);
  }

  /**
   * An approval raised under a conductor is continued by the engine; see {@link
   * ConductorContinuation}.
   */
  @Bean
  public ConductorContinuation conductorContinuation(Orchestrations orchestrations) {
    return orchestrations::continueApproved;
  }

  @Bean
  public OrchestrationCancel orchestrationCancel(
      Orchestrations orchestrations,
      OrchestrationStore store,
      JobStore jobs,
      ConversationStore conversations) {
    return new OrchestrationCancel(orchestrations, store, jobs, conversations);
  }

  /**
   * Install the complete extras provider after both the engine and cancellation exist, and the
   * keyword trap alongside it: both read the same {@link ObjectProvider}, so neither notices nor
   * offers anything before a {@link CallerOrchestrations} exists.
   *
   * <p>The Studio (spec 2026-09-29-orchestration-studio §3) is built here, beside the extras that
   * hand its tools, and made the engine's installer. Its two resolvers and the data directory
   * arrive through providers: they are {@code AgentsConfig}'s and {@code DataConfig}'s, and a
   * context without them still builds the engine, as one without an {@link OrchestrationResolver}
   * still builds it without a {@link CallerOrchestrations}. With any of them missing there is no
   * Studio: no conductor is handed its tools, and the engine keeps {@link
   * Orchestrations.Installer#NONE}.
   */
  @Bean
  public RunExtras orchestrationRunExtras(
      JobRuntime runtime,
      OrchestrationStore store,
      Orchestrations orchestrations,
      ObjectProvider<CallerOrchestrations> callers,
      TodoBoard board,
      OrchestrationChecks orchestrationChecks,
      RunApprovalStore approvals,
      RecordKeeper recordKeeper,
      OrchestrationAcceptance orchestrationAcceptance,
      Environments environments,
      Checking checking,
      ObjectProvider<CommandJudge> judge,
      ConversationStore conversations,
      Callers accounts,
      ObjectProvider<DefinitionResolver> definitions,
      ObjectProvider<OrchestrationResolver> resolver,
      ObjectProvider<DataLayout> data,
      ObjectProvider<BoardRunExtras> boards) {
    Studio studio =
        studio(
            orchestrations,
            store,
            conversations,
            accounts,
            definitions.getIfAvailable(),
            resolver.getIfAvailable(),
            data.getIfAvailable(),
            runtime.knownTools());
    RunExtras extras =
        combinedRunExtras(
            combinedRunExtras(
                runExtras(
                    store,
                    orchestrations,
                    board,
                    orchestrationChecks,
                    approvals,
                    recordKeeper,
                    orchestrationAcceptance,
                    checkConsent(environments, approvals),
                    checking,
                    orchestrations.person(),
                    judge.getIfAvailable(() -> CommandJudge.NONE),
                    studio),
                context -> {
                  CallerOrchestrations available = callers.getIfAvailable();
                  return available == null ? RunExtras.Extras.NONE : available.forRun(context);
                },
                store),
            context -> {
              BoardRunExtras boardExtras = boards.getIfAvailable();
              return boardExtras == null ? RunExtras.Extras.NONE : boardExtras.forRun(context);
            },
            store);
    runtime.useRunExtras(extras);
    runtime.useTriggers(
        (definition, utterance, home, session, conversation) -> {
          CallerOrchestrations available = callers.getIfAvailable();
          return available == null
              ? Optional.empty()
              : new OrchestrationNoticing(available, store)
                  .noticeFor(definition, utterance, home, session, conversation);
        });
    return extras;
  }

  /**
   * At boot, when {@code recover-at-boot} is on: every run a restart interrupted is picked back up,
   * and everything undelivered is delivered. The test classpath turns it off.
   */
  @Bean
  public ApplicationListener<ApplicationReadyEvent> orchestrationsAtBoot(
      OrchestrationsProperties properties,
      OrchestrationStore store,
      Orchestrations orchestrations,
      Delivery orchestrationDelivery) {
    return ready -> {
      if (properties.isRecoverAtBoot()) {
        recover(store, orchestrations, orchestrationDelivery);
      } else {
        log.info(
            "plowshare.orchestrations.recover-at-boot is false: runs a restart"
                + " interrupted are not picked back up");
      }
    };
  }

  /**
   * {@link StallSweep}'s own three seams: the store, the open-approvals door named by {@link
   * RunApprovalStore#open}, and the same account inbox {@link #orchestrationDelivery} falls back to
   * — spec 2026-09-27 §4; the record, which is told each run it newly judges quiet; and the board,
   * which says whether a run is at its acceptance stage — the one place an acceptance command's
   * approval is what the run waits on.
   */
  @Bean
  public StallSweep stallSweep(
      OrchestrationStore store,
      RunApprovalStore approvals,
      Inbox inbox,
      RecordKeeper recordKeeper,
      TodoBoard board) {
    return new StallSweep(
        store,
        approvals::open,
        inbox::notify,
        Duration.ofMinutes(15),
        recordKeeper,
        run -> StallSweep.atAcceptance(run, board.list(run.conductorConversation())));
  }

  /**
   * The one daemon thread the stall sweep ticks on — {@code EventsConfig.eventsTickerThread}'s own
   * shape, a second thread rather than sharing that one so a sweep stuck on the database never
   * delays a schedule's own tick, or the other way round.
   */
  @Bean(destroyMethod = "shutdownNow")
  public ScheduledExecutorService orchestrationStallsThread() {
    return Executors.newSingleThreadScheduledExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, "orchestration-stalls");
          thread.setDaemon(true);
          return thread;
        });
  }

  /**
   * At boot, when {@code stall-sweep-enabled} is on: the sweep runs every minute, forever, on its
   * own thread. The test classpath turns it off, {@code EventsConfig.eventsAtBoot}'s own reason for
   * its ticker: no {@code @SpringBootTest} context should judge a run stalled on a clock no test
   * controls.
   */
  @Bean
  public ApplicationListener<ApplicationReadyEvent> orchestrationStallSweepAtBoot(
      OrchestrationsProperties properties,
      StallSweep stallSweep,
      Orchestrations orchestrations,
      ScheduledExecutorService orchestrationStallsThread) {
    return ready -> {
      if (properties.isStallSweepEnabled()) {
        orchestrationStallsThread.scheduleWithFixedDelay(
            () -> {
              try {
                stallSweep.sweep(Instant.now());
              } catch (RuntimeException failed) {
                log.warn("a stall sweep failed; the next minute retries", failed);
              }
              // The time cap (V69) on the same minute: a turn in flight past it is stopped
              // at its next step boundary, and asked about as its cap.
              try {
                orchestrations.sweepTimeCaps();
              } catch (RuntimeException failed) {
                log.warn("a time cap sweep failed; the next minute retries", failed);
              }
            },
            1,
            1,
            TimeUnit.MINUTES);
      } else {
        log.info(
            "plowshare.orchestrations.stall-sweep-enabled is false: no run is"
                + " reported stalled by itself");
      }
    };
  }

  // --- the bindings, static so each is testable without the beans ----------------------------

  /**
   * The Studio over this server's beans, made the engine's installer — or null, installing nothing,
   * when a resolver or the data directory is missing: it could neither trial a draft nor write one.
   * Its {@link Studio.Asker} is the engine's own {@link Orchestrations#askInstall}, which is why
   * the engine can hold it as its installer without a cycle.
   *
   * @param knownTools the tools this server binds, which the catalog offers and a trial checks
   * @return the Studio the conductor's tools reach, or null for none
   */
  static Studio studio(
      Orchestrations engine,
      OrchestrationStore store,
      ConversationStore conversations,
      Callers callers,
      DefinitionResolver agents,
      OrchestrationResolver resolver,
      DataLayout data,
      Set<String> knownTools) {
    if (agents == null || resolver == null || data == null) {
      log.info(
          "no Studio on this server: it has no {}, so no conductor is handed the"
              + " Studio's tools and an install answer installs nothing",
          agents == null
              ? "agent resolver"
              : resolver == null ? "orchestration resolver" : "data directory");
      return null;
    }
    Studio studio =
        new Studio(
            store,
            conversations,
            callers,
            agents,
            resolver,
            new OrchestrationWriter(data),
            knownTools,
            engine::askInstall);
    engine.useInstaller(studio);
    return studio;
  }

  /**
   * The one production consent, for the run's check and its acceptance commands alike (spec
   * 2026-09-29 §1b): one {@link RunApproval} for the check, and one for each acceptance set (V67),
   * raised in the conductor's own conversation so the engine's {@code continueApproved} is what
   * continues it — or written already allowed by the command judge. One place, so the two cannot
   * drift in how a person is asked; they differ only in the reason shown.
   *
   * @param environments where a project's id on this server is found
   * @param approvals where the approval is raised
   * @return the consent the engine and the acceptance gate both ask through
   */
  static CheckConsent checkConsent(Environments environments, RunApprovalStore approvals) {
    return new CheckConsent() {
      @Override
      public Optional<Asked> ask(
          OrchestrationRecord run, List<String> argv, String side, String cwd) {
        return ask(run, argv, side, cwd, CHECK_WHY);
      }

      @Override
      public boolean canAsk(OrchestrationRecord run) {
        return environments.projectId(run.project()) != null;
      }

      @Override
      public Optional<Asked> ask(
          OrchestrationRecord run, List<String> argv, String side, String cwd, String why) {
        Long projectId = environments.projectId(run.project());
        if (projectId == null) {
          // RunTool's own case: a project with no id on this server (no data directory,
          // or a lookup that failed) is one nobody can be asked to allow anything for.
          return Optional.empty();
        }
        RunApproval approval =
            approvals.ask(
                projectId,
                run.conductorConversation(),
                run.conductorConversation(),
                run.callerHandle(),
                run.definitionName(),
                side,
                argv,
                cwd,
                why);
        return Optional.of(new Asked(approval.id(), ApprovalDelivery.question(approval)));
      }

      @Override
      public Optional<Asked> askSet(
          OrchestrationRecord run,
          List<List<String>> commands,
          String side,
          String cwd,
          String why,
          String judged) {
        Long projectId = environments.projectId(run.project());
        if (projectId == null) {
          return Optional.empty();
        }
        RunApproval approval =
            approvals.askSet(
                projectId,
                run.conductorConversation(),
                run.conductorConversation(),
                run.callerHandle(),
                run.definitionName(),
                side,
                commands,
                cwd,
                why,
                judged);
        return Optional.of(new Asked(approval.id(), ApprovalDelivery.question(approval)));
      }

      @Override
      public Optional<String> allowedByJudge(
          OrchestrationRecord run,
          List<String> argv,
          List<List<String>> commands,
          String side,
          String cwd,
          String why,
          String judged) {
        Long projectId = environments.projectId(run.project());
        if (projectId == null) {
          return Optional.empty();
        }
        return Optional.of(
            approvals
                .allowedBy(
                    projectId,
                    run.conductorConversation(),
                    run.conductorConversation(),
                    run.callerHandle(),
                    run.definitionName(),
                    side,
                    argv,
                    commands,
                    cwd,
                    why,
                    RunApproval.JUDGE,
                    judged)
                .id());
      }

      @Override
      public Optional<String> standing(OrchestrationRecord run, List<String> argv, String side) {
        Long projectId = environments.projectId(run.project());
        if (projectId == null) {
          return Optional.empty();
        }
        return approvals.standing(projectId).stream()
            .filter(
                each -> each.side().equals(side) && RunApprovalStore.covers(each.prefix(), argv))
            .map(RunApproval::id)
            .findFirst();
      }
    };
  }

  /**
   * Tools for a non-terminal run's own conductor conversation, and {@link RunExtras.Extras#NONE}
   * for every other run. Shares the run's own {@link TurnEnd} off {@code context.end()} when {@code
   * JobRuntime} already made one (spec 2026-09-27 §2), and only otherwise makes a fresh one --
   * either way, scoped to this call, so one run's tools never trip another's turn. No checked
   * {@code todo_write}: see the 5-argument overload for a run that can also have one.
   */
  static RunExtras runExtras(OrchestrationStore store, ConductorActions actions) {
    return runExtras(store, actions, null, null, null);
  }

  /**
   * As above, plus a {@code todo_write} that runs the run's check before a checked stage is marked
   * done — {@link StageChecks} in front of the board — appended <b>after</b> the conductor tools,
   * when this run has a checked stage and somewhere to run its check. Spec 2026-09-26 §3.
   *
   * <p>A checked run always gets a checked {@code todo_write}, never the plain one: with no port,
   * or {@code checks}/{@code approvals} null, it is {@link StageChecks#cannotRun}, which refuses a
   * checked stage's done move. {@code board} null (the 2-argument overload above) has nothing to
   * write through, so a checked run is then offered no todo tools at all.
   */
  static RunExtras runExtras(
      OrchestrationStore store,
      ConductorActions actions,
      TodoBoard board,
      OrchestrationChecks checks,
      RunApprovalStore approvals) {
    return runExtras(store, actions, board, checks, approvals, OrchestrationRecorder.NONE);
  }

  /** As above, with the checked {@code todo_write}'s check told to {@code recorder}. */
  static RunExtras runExtras(
      OrchestrationStore store,
      ConductorActions actions,
      TodoBoard board,
      OrchestrationChecks checks,
      RunApprovalStore approvals,
      OrchestrationRecorder recorder) {
    return runExtras(
        store,
        actions,
        board,
        checks,
        approvals,
        recorder,
        null,
        null,
        null,
        AcceptanceGate.Person.NONE);
  }

  /**
   * As above, plus the acceptance gate (spec 2026-09-29 §1b) for a run with a stage that has an
   * {@code acceptance:} key: {@link AcceptanceGate} after the phase gate and before the check, so a
   * stage that is both is registered or run before its check spends minutes on it. A run that has
   * such a stage and no port, store or consent to run it with gets {@link
   * AcceptanceGate#cannotRun}, failing closed as the check does. A project's {@code stage.pre} and
   * {@code stage.post}, through the run's {@link RunExtras.Context#hooks}, stand last: {@link
   * StageHooks} after every system gate (spec 2026-09-28-hooks-reach-the-log decision 2).
   *
   * @param acceptance where the commands are registered, or null for a server that has none
   * @param consent how a person is asked to allow one, or null
   * @param checking the acceptance checker's harness side (spec 2026-10-01), or null for a server
   *     with none: a run that pinned a checker is then checked by nobody
   * @param person who is asked whether a run goes on and to check its product ({@link
   *     Orchestrations#person}), or {@link AcceptanceGate.Person#NONE}
   */
  static RunExtras runExtras(
      OrchestrationStore store,
      ConductorActions actions,
      TodoBoard board,
      OrchestrationChecks checks,
      RunApprovalStore approvals,
      OrchestrationRecorder recorder,
      OrchestrationAcceptance acceptance,
      CheckConsent consent,
      Checking checking,
      AcceptanceGate.Person person) {
    return runExtras(
        store,
        actions,
        board,
        checks,
        approvals,
        recorder,
        acceptance,
        consent,
        checking,
        person,
        CommandJudge.NONE);
  }

  /**
   * As above, with the command judge (V67) the acceptance gate shows a set to before it asks the
   * person about it.
   *
   * @param judge the judge, or {@link CommandJudge#NONE} to ask the person every time
   */
  static RunExtras runExtras(
      OrchestrationStore store,
      ConductorActions actions,
      TodoBoard board,
      OrchestrationChecks checks,
      RunApprovalStore approvals,
      OrchestrationRecorder recorder,
      OrchestrationAcceptance acceptance,
      CheckConsent consent,
      Checking checking,
      AcceptanceGate.Person person,
      CommandJudge judge) {
    return runExtras(
        store,
        actions,
        board,
        checks,
        approvals,
        recorder,
        acceptance,
        consent,
        checking,
        person,
        judge,
        null);
  }

  /**
   * As above, with the Studio's tools (spec 2026-09-29-orchestration-studio §3) for a conductor
   * whose definition declares them: each one it names, and no other, built on this run.
   *
   * @param studio what the tools reach — {@link #studio} — or null for a server that has none,
   *     which hands no Studio tool to any conductor
   */
  static RunExtras runExtras(
      OrchestrationStore store,
      ConductorActions actions,
      TodoBoard board,
      OrchestrationChecks checks,
      RunApprovalStore approvals,
      OrchestrationRecorder recorder,
      OrchestrationAcceptance acceptance,
      CheckConsent consent,
      Checking checking,
      AcceptanceGate.Person person,
      CommandJudge judge,
      StudioTools.Port studio) {
    return context -> {
      String conversation = context.conversationId();
      if (conversation == null) {
        return RunExtras.Extras.NONE;
      }
      return store
          .byConductorConversation(conversation)
          .filter(run -> !run.state().terminal())
          .map(
              run -> {
                // The run's own TurnEnd when JobRuntime already made one (spec 2026-09-27
                // §2) -- so a caller-side tool sharing that same object can end this run's
                // turn too -- and only a fresh one for a caller built before a run had one.
                TurnEnd end = context.end() != null ? context.end() : new TurnEnd();
                // Rule 4 (spec 2026-09-29 §3): this conductor's writes reach its own
                // artifacts and no further, from the directory V60 stored at start. Here,
                // on the run, and not on the definition: the coder's file_edit is the
                // same tool and is not fenced.
                RunExtras.Fence fence =
                    ArtifactsFence.of(
                        store.artifactsDir(run.id()).orElse(null),
                        ArtifactsFence.refusalFor(
                            run.definitionName(),
                            context.definition() == null
                                ? List.of()
                                : context.definition().calls()));
                boolean checked = run.stages().stream().anyMatch(StageRules.Stage::checked);
                // A run with no checked stage has no check to set, so it is not offered
                // the tool at all: orchestration_check needs a place to run its command,
                // which only a run with a filesystem to reach — context.commands() — has.
                Commands.Port commands = checked ? context.commands() : null;
                List<AgentTool> tools =
                    new ArrayList<>(
                        ConductorTools.forRun(
                            actions,
                            run.id(),
                            end,
                            commands,
                            context.hooks(),
                            new HookContext.Orchestration(run.id(), run.definitionName(), null)));
                // THE STUDIO'S TOOLS, for a conductor that declared them (spec
                // 2026-09-29-orchestration-studio §3): built on this run, reading drafts
                // where its files are — the run's own Commands.Port, not the check's,
                // which only a checked run is handed — and ending the turn through its
                // TurnEnd when install asks.
                // checker_answer (spec 2026-10-01 §3), for a run that pinned a checker: the
                // conductor's way to answer the checker's WHY questions.
                String checker = checking == null ? null : store.checker(run.id()).orElse(null);
                if (checker != null) {
                  tools.add(ConductorTools.checkerAnswer(actions, run.id(), end));
                }
                if (studio != null && context.definition() != null) {
                  tools.addAll(
                      StudioTools.forRun(
                          studio,
                          run.id(),
                          end,
                          context.commands(),
                          context.home(),
                          context.definition().tools()));
                }
                // Acceptance (§1b): only a root run carries these stages — a phase run's
                // are dropped at start — and the port is the run's own, whether or not
                // it is checked: a command runs where the check would.
                boolean accepting =
                    run.stages().stream().anyMatch(stage -> stage.acceptance() != null);
                if (board == null) {
                  // Nothing to write through: no todo tools at all for a checked or an
                  // accepting run — JobRuntime's plain pair would mark its stages done
                  // unguarded — and that plain pair for any other (keepsTodos).
                  return new RunExtras.Extras(
                      tools, end, !checked && !accepting, hasEndedATurnInProse(run), fence);
                }
                // EVERY CONDUCTOR GETS THE HARNESS todo_write (spec 2026-09-29 §3): rule
                // 1's phase gate binds a run with no checked stage too.
                List<TodoTools.BeforeApply> gates = new ArrayList<>();
                gates.add(new PhaseRunsGate(store, board));
                // THE ACCEPTANCE CHECKER (spec 2026-10-01 §3), before the acceptance gate:
                // its plan pass rides the plan move, no stage moves while it waits on an
                // answer, and its end pass runs as acceptance starts, before any command.
                if (checker != null && context.commands() != null) {
                  gates.add(
                      new CheckerGate(
                          store::byConductorConversation,
                          store::checker,
                          store::artifactsDir,
                          board,
                          context.commands(),
                          checking,
                          context.sessionId(),
                          person::checkFailed,
                          end));
                }
                if (accepting) {
                  gates.add(
                      context.commands() == null
                              || acceptance == null
                              || approvals == null
                              || consent == null
                          ? AcceptanceGate.cannotRun(run, board)
                          : new AcceptanceGate(
                                  store::byConductorConversation,
                                  store::artifactsDir,
                                  board,
                                  context.commands(),
                                  acceptance::find,
                                  acceptance::requirements,
                                  acceptance::replace,
                                  approvals::find,
                                  consent,
                                  checking == null ? id -> List.of() : checking.concerns()::of,
                                  recorder,
                                  end,
                                  approvals::deny,
                                  person,
                                  judge,
                                  context.hooks())
                              .withUsage(context.transcript().usage()));
                }
                if (checked) {
                  // FAIL CLOSED (final review F6), as before. A checked run always
                  // gets a checked todo_write: the one that runs the check when it
                  // can, and one that refuses a checked stage's done move when it
                  // cannot — no port, or no store to read the check or its approval
                  // from. Handing it nothing here left JobRuntime's plain todo_write
                  // in its place, and a checked stage was marked done with no check
                  // run at all.
                  gates.add(
                      commands != null && checks != null && approvals != null
                          // Each failure counted against the project's failed-checks
                          // (V69), through the same person the acceptance gate asks.
                          ? new StageChecks(
                              store::byConductorConversation,
                              board,
                              checks::find,
                              approvals::find,
                              commands,
                              recorder,
                              person::checkFailed,
                              end)
                          : StageChecks.cannotRun(run, board));
                }
                // USER HOOKS LAST (spec 2026-09-28-hooks-reach-the-log decision 2): every
                // system gate above has refused the batch or passed it, so the run's
                // chain (its profile's harness hooks, then the project's) sees on
                // stage.pre / stage.post only a move the harness allows, and the summary
                // stage.post is shown is the one the board will store.
                gates.add(
                    new StageHooks(
                        store::byConductorConversation,
                        board,
                        checks == null ? id -> Optional.empty() : checks::find,
                        context.hooks()));
                tools.add(
                    new TodoTools.Write(
                        board,
                        context.transcript(),
                        context.sessionId(),
                        TodoTools.BeforeApply.of(gates)));
                return new RunExtras.Extras(tools, end, true, hasEndedATurnInProse(run), fence);
              })
          .orElse(RunExtras.Extras.NONE);
    };
  }

  /**
   * Whether this run has already ended a turn in plain text, and so may not end another that way.
   *
   * <h2>{@code ended_in_prose} is the record of exactly that, and only a nudge sets it</h2>
   *
   * <p>{@code Orchestrations.nudgeOrRestart} counts a nudge only for a turn that ended {@code
   * ANSWERED} with the row still {@code RUNNING} — a conductor that neither asked nor finished —
   * and the same statement sets the mark. Restarts are counted separately, so a server restart does
   * not put a run under this. So the mark means precisely "this conductor has already done the one
   * thing that cannot be allowed twice".
   *
   * <p><b>Not {@code nudges > 0}, which it was.</b> Since spec 2026-09-28 progress resets {@code
   * nudges} — a stage moved, a delegation returned, the person's "go on" — because that count is
   * the {@code stuck} limit's. Read here, a reset would have lifted the forced tool call from a
   * conductor that had narrated before, which is the one it exists for. The mark is never cleared
   * (V58).
   *
   * <h2>Why the first turn is left alone</h2>
   *
   * <p><b>The constraint is a correction, not a policy.</b> Two of the three runs in the tree that
   * produced it narrated; the third called {@code orchestration_finish} properly once nudged, and a
   * run that gets it right first time should send the request it would have sent before any of this
   * existed. Applying it from the first turn would also make {@code
   * a_request_with_no_tools_is_byte_identical_to_what_slice_two_sent}'s sibling claim — that a
   * conductor's ordinary turn is unchanged — quietly false.
   *
   * <p><b>And it is not free.</b> Under {@code tool_choice: required} the model cannot answer in
   * prose at all, so a conductor with nothing left to do must call <em>something</em>: the right
   * something is {@code orchestration_finish} or {@code orchestration_ask}, and the wrong one is
   * another {@code todo_write}. A run that keeps choosing wrongly spends its turn cap instead of
   * failing {@code stuck} at the third nudge — a worse failure for a run that was never going to
   * finish, and the trade is taken because the measured case is the opposite one: a conductor whose
   * work was done and whose only fault was the shape of its last message.
   */
  static boolean hasEndedATurnInProse(OrchestrationRecord run) {
    return run.endedInProse();
  }

  /**
   * Pushes a run's account, for a run with an account to push — {@code Orchestrations}'s {@code
   * changed} listener.
   */
  static Consumer<OrchestrationRecord> pushChanges(ObjectProvider<AccountPushes> pushes) {
    return run -> {
      if (run.callerHandle() == null) {
        return;
      }
      pushes
          .getIfAvailable(() -> AccountPushes.NONE)
          .push(
              run.callerHandle(),
              Map.of("kind", CHANGED, "orchestration", run.id(), "state", run.state().wire()));
    };
  }

  /**
   * The conductor's and caller's independent additions, without either hiding the other.
   *
   * <p><b>Every component of both is carried</b>, {@code requiresAToolCall} and the conductor's
   * {@code fence} included. It was built with the three-argument constructor, which set it false
   * for every conductor that also holds an orchestrations grant — so 65f2b26c's constraint never
   * reached the one conductor that nests, and implement_specification, the conductor that nests,
   * would have lost rule 4's fence the same way.
   *
   * <p>A conductor's status of the child it waits on ending its turn used to be built here, as a
   * wrapper only this merge could reach — {@code YieldingStatus}, spec 2026-09-25. Rule 1 (spec
   * 2026-09-27 §2) replaced it: every caller's {@code orchestration_status}, conductor or not,
   * checks the same thing on the run it is asking after, so nothing needs wrapping here any more.
   */
  static RunExtras combinedRunExtras(
      RunExtras conductor, RunExtras caller, OrchestrationStore store) {
    return context -> {
      RunExtras.Extras own = conductor.forRun(context);
      RunExtras.Extras granted = caller.forRun(context);
      if (own == RunExtras.Extras.NONE) {
        return granted;
      }
      if (granted == RunExtras.Extras.NONE) {
        return own;
      }
      var tools = new ArrayList<>(own.tools());
      tools.addAll(granted.tools());
      return new RunExtras.Extras(
          tools,
          own.end() != null ? own.end() : granted.end(),
          own.keepsTodos() || granted.keepsTodos(),
          own.requiresAToolCall() || granted.requiresAToolCall(),
          // The conductor's: a caller's grant fences nothing (65f2b26c's lesson again).
          own.fence());
    };
  }

  /**
   * What a freed conversation drains: what waits to be delivered to it as a caller, then any answer
   * waiting for it as a conductor. Each is guarded on its own, so a delivery that throws still lets
   * a pending answer be spoken, and neither reaches {@code Turn}'s free.
   */
  static Consumer<String> drains(
      Consumer<String> drainCaller, Consumer<String> speakPendingAnswers) {
    return drains(drainCaller, speakPendingAnswers, conversation -> {});
  }

  /**
   * As above, then a resumed sub-agent's result that found the conductor busy — after the answers,
   * so a pending answer takes the free first and the result waits for the next one rather than the
   * other way round; either order speaks both, one per free.
   */
  static Consumer<String> drains(
      Consumer<String> drainCaller,
      Consumer<String> speakPendingAnswers,
      Consumer<String> speakPendingDelegateResults) {
    return drains(
        drainCaller, speakPendingAnswers, speakPendingDelegateResults, conversation -> {});
  }

  /**
   * As above, then the answers to a run's acceptance commands that found the conductor speaking
   * (Task 10 review, finding 5) — last, for the reason the delegate's result comes after the
   * answers: one per free, and each drain a no-op when nothing waits.
   */
  static Consumer<String> drains(
      Consumer<String> drainCaller,
      Consumer<String> speakPendingAnswers,
      Consumer<String> speakPendingDelegateResults,
      Consumer<String> speakPendingAcceptanceAnswers) {
    return conversation -> {
      guarded(
          "delivering what waits on conversation " + conversation,
          () -> drainCaller.accept(conversation));
      guarded(
          "speaking what waits for the conductor of conversation " + conversation,
          () -> speakPendingAnswers.accept(conversation));
      guarded(
          "speaking a sub-agent's result that waits for the conductor of conversation "
              + conversation,
          () -> speakPendingDelegateResults.accept(conversation));
      guarded(
          "speaking the acceptance answers that wait for the conductor of conversation "
              + conversation,
          () -> speakPendingAcceptanceAnswers.accept(conversation));
    };
  }

  /**
   * Picks up what a restart interrupted.
   *
   * <ul>
   *   <li>A {@code running} run with an answer recorded but never spoken has that answer spoken:
   *       its conductor was waiting on it, not mid-work, so it is not a restart. A refusal from a
   *       conductor that is not speaking fails the run there. One refused while a turn was in
   *       flight, and left running with the answer undelivered, is restarted instead — nothing else
   *       would ever free its conversation to retry.
   *   <li>Every other {@code running} run is restarted, which counts against its restarts — except
   *       one with a child still running, which {@code nudgeOrRestart} leaves alone: that child's
   *       report is what drives its next turn, and the child is picked up here on its own row.
   *   <li>An {@code asking} run is left alone: it waits on its caller, and {@code nudgeOrRestart}
   *       must never see one. (An answered run is {@code running} again by the time its answer is
   *       recorded, which is why the first case reads {@code running}.)
   *   <li>A {@code waiting} run is left alone too, and has nothing to restart: a child's report is
   *       what wakes it, and that report is undelivered on the child's own row — the drain below is
   *       what gets it moving again.
   *   <li>Then every undelivered question and ending is delivered.
   * </ul>
   *
   * <p>Each run is guarded on its own, so one that throws does not keep the rest waiting.
   */
  static void recover(OrchestrationStore store, Orchestrations orchestrations, Delivery delivery) {
    for (OrchestrationRecord run : store.live()) {
      if (run.state() != OrchestrationState.RUNNING) {
        continue;
      }
      guarded(
          "picking orchestration " + run.id() + " back up after a restart",
          () -> {
            boolean answerPending =
                store.messages(run.id()).stream()
                    .anyMatch(m -> m.kind() == Kind.ANSWER && m.deliveredAt() == null);
            if (answerPending) {
              if (!orchestrations.speakAnswerIfPending(run.id())
                  && strandedWithAnAnswer(store, run)) {
                // Refused, and nothing else will free this conversation to retry it:
                // restart the conductor, and the answer is spoken when that turn frees.
                orchestrations.nudgeOrRestart(run, Utterances.restart(), true);
              }
            } else {
              orchestrations.nudgeOrRestart(run, Utterances.restart(), true);
            }
          });
    }
    guarded(
        "delivering every undelivered orchestration message after a restart", delivery::drainAll);
  }

  /**
   * Still running and its answer still undelivered. {@code nudgeOrRestart} itself checks that no
   * turn is in flight.
   */
  private static boolean strandedWithAnAnswer(OrchestrationStore store, OrchestrationRecord run) {
    return store.find(run.id()).filter(now -> now.state() == OrchestrationState.RUNNING).isPresent()
        && store.messages(run.id()).stream()
            .anyMatch(m -> m.kind() == Kind.ANSWER && m.deliveredAt() == null);
  }

  /**
   * The server's definition checks over one rebuilt conductor: the definition they serve, or an
   * {@link IllegalStateException} carrying the reason they refused it — {@code
   * OrchestrationRegistry.read}'s own use of them, for one.
   */
  static UnaryOperator<AgentDefinition> conductorChecks(DefinitionChecks checks) {
    return conductor -> {
      AgentRegistry.Loaded checked =
          checks.applyTo(
              new AgentRegistry.Loaded(Map.of(conductor.name(), conductor), Map.of(), Map.of()),
              PINNED);
      String refused = checked.disabled().get(conductor.name());
      if (refused != null) {
        throw new IllegalStateException(refused);
      }
      AgentDefinition served = checked.enabled().get(conductor.name());
      if (served == null) {
        throw new IllegalStateException(
            "the definition checks neither served nor refused"
                + " the conductor '"
                + conductor.name()
                + "'");
      }
      return served;
    };
  }

  /**
   * {@code Turn}'s conductor door: the 6-argument {@code speakToConductor}, and {@code isSpeaking};
   * and its delegate door, with the sub-agent resolved against {@code agents} — the boot set {@code
   * AgentRunTool} delegated into, so a sub-agent resumes as the definition it was started as.
   */
  static ConductorVoice conductorVoice(Turn turn, AgentRegistry agents) {
    return new ConductorVoice() {
      @Override
      public boolean isSpeaking(String conversation) {
        return turn.isSpeaking(conversation);
      }

      @Override
      public String speak(
          String conversation,
          AgentDefinition conductor,
          String utterance,
          String sessionId,
          Integer maxModelCalls,
          Consumer<Outcome> ended) {
        return turn.speakToConductor(
            conversation, conductor, utterance, sessionId, maxModelCalls, ended);
      }

      @Override
      public String resumeDelegate(
          String child,
          String agent,
          String conductorConversation,
          String utterance,
          String callerHandle,
          Consumer<Outcome> ended) {
        AgentDefinition callee =
            agents
                .find(agent)
                .orElseThrow(
                    () ->
                        new Turn.Refused(
                            "the agent '" + agent + "' no longer resolves, so it cannot carry on"));
        return turn.speakToDelegate(
            child, conductorConversation, callee, utterance, callerHandle, ended);
      }
    };
  }

  /**
   * Speaks into a caller's conversation as the caller's own agent, resolved as that conversation's
   * project resolves it. An agent that no longer resolves is a {@link Turn.Refused}, which {@link
   * Delivery} answers with the inbox.
   */
  static CallerVoice callerVoice(Turn turn, Callers callers) {
    return new CallerVoice() {
      @Override
      public boolean isSpeaking(String conversation) {
        return turn.isSpeaking(conversation);
      }

      @Override
      public void speak(
          String conversation, String callerAgent, String utterance, Speaker speaker) {
        AgentDefinition agent;
        try {
          agent =
              callers.requireAgent(callerAgent, callers.callerForConversation(conversation, null));
        } catch (RuntimeException unresolved) {
          throw new Turn.Refused(
              unresolved.getMessage() != null ? unresolved.getMessage() : unresolved.toString());
        }
        turn.deliver(conversation, agent, utterance, speaker, outcome -> {});
      }
    };
  }

  /**
   * The door a child's report climbs back through: {@code Orchestrations.speakToParent}, which
   * wakes a waiting parent before it speaks, so the row is already {@code running} by the time the
   * turn starts and {@link Delivery} never touches the state itself.
   *
   * <p>A parent is live while its row is not terminal, and it is speaking while its own conductor
   * conversation has a turn in flight — which is the ordinary case while a parent waits, because
   * the wait is taken inside that turn's own tool call. A row that is gone is neither.
   *
   * @param speaking whether a conversation has a turn in flight — {@code Turn.isSpeaking}
   */
  static ParentVoice parentVoice(
      OrchestrationStore store,
      Predicate<String> speaking,
      ObjectProvider<Orchestrations> orchestrations) {
    return new ParentVoice() {
      @Override
      public boolean isLiveParent(String orchestration) {
        return store.find(orchestration).filter(run -> !run.state().terminal()).isPresent();
      }

      @Override
      public boolean isSpeaking(String parentOrchestration) {
        return store
            .find(parentOrchestration)
            .map(parent -> speaking.test(parent.conductorConversation()))
            .orElse(false);
      }

      @Override
      public void speak(String parentOrchestration, String utterance) {
        orchestrations.getObject().speakToParent(parentOrchestration, utterance);
      }
    };
  }

  /** {@code AgentsConfig.sessionLive}'s rule: a session is live while a file provider holds it. */
  private static Predicate<String> sessionLive(SessionRegistry sessions) {
    return id -> sessions.find(id).filter(live -> live.has(Role.FILE_PROVIDER)).isPresent();
  }

  private static void guarded(String what, Runnable step) {
    try {
      step.run();
    } catch (RuntimeException failed) {
      log.warn("{} failed; it is left for the next drain or the next boot", what, failed);
    }
  }
}
