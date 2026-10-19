package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.harness.Harness;
import io.aeyer.plowshare.server.harness.HarnessRun;
import io.aeyer.plowshare.server.hooks.*;
import io.aeyer.plowshare.server.llm.accounting.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Lifecycle gates use the ordinary hook engine and an owner-pinned, durable processing log. */
public final class InformationStageHooks implements InformationLifecycle.Gates, UsageAware {
  private UsageOwners usageOwners = UsageOwners.NONE;

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = java.util.Objects.requireNonNull(source);
  }

  private final InformationStageRepository stages;
  private final InformationCatalogue catalogue;
  private final ConversationStore conversations;
  private final LogStages logStages;
  private final InformationJobs inputs;
  private final Hooks configured;
  private final Harness harness;
  private final Supplier<AgentRegistry> agents;
  private final Map<UUID, HarnessRun> runs = new ConcurrentHashMap<>();
  private final Map<UUID, HookContext> controls = new ConcurrentHashMap<>();

  private io.aeyer.plowshare.server.access.SystemOperations system;
  private InformationProcessingRepository processing;

  /** Production queue gates retain independent SYSTEM execution and exact knowledge homes. */
  public InformationStageHooks(
      InformationStageRepository stages,
      InformationCatalogue catalogue,
      ConversationStore conversations,
      LogStages logStages,
      InformationJobs inputs,
      Hooks configured,
      Harness harness,
      java.util.function.Supplier<AgentRegistry> agents,
      io.aeyer.plowshare.server.access.SystemOperations system,
      InformationProcessingRepository processing) {
    this(stages, catalogue, conversations, logStages, inputs, configured, harness, agents);
    this.system = java.util.Objects.requireNonNull(system);
    this.processing = java.util.Objects.requireNonNull(processing);
  }

  public InformationStageHooks(
      InformationStageRepository stages,
      InformationCatalogue catalogue,
      ConversationStore conversations,
      LogStages logStages,
      InformationJobs inputs,
      Hooks configured,
      Harness harness,
      Supplier<AgentRegistry> agents) {
    this.catalogue = catalogue;
    this.stages = stages;
    this.conversations = conversations;
    this.logStages = logStages;
    this.inputs = inputs;
    this.configured = configured;
    this.harness = harness;
    this.agents = agents;
  }

  private HookContext context(InformationLifecycle.Lease lease) {
    var state = stages.logState(lease.revision(), lease.project());
    String project = state.project();
    Home home = project == null ? Home.global() : Home.of(project);
    String log = state.log();
    if (log == null || !state.systemCompatible()) {
      String previous = log;
      io.aeyer.plowshare.server.agents.Budget budget;
      if (processing != null) {
        InformationProcessingRepository.BudgetSource selected =
            processing.budgetSource(lease.revision());
        budget =
            io.aeyer.plowshare.server.agents.Budget.restored(
                selected.id(), selected.limit(), selected.spent());
      } else
        budget = io.aeyer.plowshare.server.agents.Budget.resumed(state.allowance(), state.spent());
      if (system == null) {
        log =
            conversations
                .log(
                    Origin.SUBMISSION, home, "document_pipeline", null, budget, lease.owner(), null)
                .id();
      } else {
        var definition =
            new AgentDefinition(
                "document_pipeline",
                "Internal processing log",
                "system",
                List.of(),
                List.of(),
                List.of(),
                1,
                Math.max(1, state.allowance()),
                "Processing log");
        var work =
            system.admit(
                io.aeyer.plowshare.server.access.SystemOperations.Purpose.DOCUMENT_PREPARATION,
                home,
                processing.home(lease.revision()),
                definition,
                lease.revision() + ":" + lease.generation() + ":" + lease.stage(),
                () -> processing.requireLease(lease));
        log =
            conversations
                .logSystem(
                    Origin.SUBMISSION,
                    home,
                    "document_pipeline",
                    null,
                    budget,
                    lease.owner(),
                    null,
                    work)
                .id();
      }
      inputs.bind(
          log,
          new InformationContext(
                  lease.owner(),
                  project == null
                      ? InformationContext.Selection.personal()
                      : InformationAccess.projectSelection(lease.owner(), project))
              .withView(true),
          List.of(lease.revision()));
      // Retained usage stays immutable. A legacy user-owned pipeline gets a new log for future
      // work, with the same owner, source binding, allowance and hook snapshot policy.
      boolean pinned =
          previous == null
              ? stages.pinLog(lease.revision(), log)
              : stages.replaceLog(lease.revision(), previous, log);
      if (!pinned) log = stages.logState(lease.revision(), lease.project()).log();
      else {
        String session = state.session();
        logStages.opened(
            new LogStages.LogOpened(
                log, Origin.SUBMISSION, home, "document_pipeline", false, null, session, null));
      }
    }
    return about(lease, project, log);
  }

  private HookContext about(InformationLifecycle.Lease lease, String project, String log) {
    return HookContext.forLog("submission", "document_pipeline", false, project, log)
        .withUsage(usageOwners.processing(log, UsageAttribution.Operation.HOOK_MODEL))
        .about(
            new HookContext.Document(
                (InformationCatalogue.STAGES.contains(lease.stage())
                    ? "processing"
                    : lease.stage()),
                lease.resource().toString(),
                lease.revision().toString(),
                lease.generation(),
                lease.stage(),
                lease.attempt()));
  }

  private HookContext controlContext(InformationLifecycle.Control control) {
    control.requireCurrent().run();
    var event = control.event();
    return controls.computeIfAbsent(
        event.token(),
        ignored -> {
          var state = stages.logState(event.revision(), event.project());
          String project = state.project();
          Home home = project == null ? Home.global() : Home.of(project);
          io.aeyer.plowshare.server.agents.Budget budget;
          if (processing == null)
            budget =
                io.aeyer.plowshare.server.agents.Budget.resumed(state.allowance(), state.spent());
          else {
            var selected = processing.budgetSource(event.revision());
            budget =
                io.aeyer.plowshare.server.agents.Budget.restored(
                    selected.id(), selected.limit(), selected.spent());
          }
          String log;
          if (system == null)
            log =
                conversations
                    .log(
                        Origin.SUBMISSION,
                        home,
                        "document_pipeline",
                        null,
                        budget,
                        event.owner(),
                        null)
                    .id();
          else {
            var definition =
                new AgentDefinition(
                    "document_pipeline",
                    "Document control hooks",
                    "system",
                    List.of(),
                    List.of(),
                    List.of(),
                    1,
                    Math.max(1, state.allowance()),
                    "Control hooks");
            var work =
                system.admit(
                    io.aeyer.plowshare.server.access.SystemOperations.Purpose.DOCUMENT_PREPARATION,
                    home,
                    processing.home(event.revision()),
                    definition,
                    "control:" + event.revision() + ":" + event.stage() + ":" + event.token(),
                    control.requireCurrent());
            log =
                conversations
                    .logSystem(
                        Origin.SUBMISSION,
                        home,
                        "document_pipeline",
                        null,
                        budget,
                        event.owner(),
                        null,
                        work)
                    .id();
          }
          inputs.bind(
              log,
              new InformationContext(
                      event.owner(),
                      project == null
                          ? InformationContext.Selection.personal()
                          : InformationAccess.projectSelection(event.owner(), project))
                  .withView(true),
              List.of(event.revision()));
          // A manual command gets its own frozen hook snapshot and SYSTEM log.
          // Never pin it as the processing log: queue workers must retain their
          // independent lease authority even after the caller loses access.
          logStages.opened(
              new LogStages.LogOpened(
                  log,
                  Origin.SUBMISSION,
                  home,
                  "document_pipeline",
                  false,
                  null,
                  state.session(),
                  null));
          return about(event, project, log);
        });
  }

  private Hooks chain(InformationLifecycle.Lease lease) {
    String model = null;
    AgentRegistry registry = agents.get();
    if (registry != null && registry.names().contains("document_summariser"))
      model = registry.get("document_summariser").model();
    return Hooks.chain(
        runs.computeIfAbsent(lease.token(), ignored -> harness.begin()).forModel(model),
        configured);
  }

  private StageShown shown(InformationLifecycle.Lease lease) {
    return new StageShown(
        lease.stage(),
        lease.stage(),
        Math.max(0, InformationCatalogue.STAGES.indexOf(lease.stage())),
        InformationCatalogue.STAGES.contains(lease.stage())
            ? InformationCatalogue.STAGES.size()
            : 1);
  }

  @Override
  public Gate before(InformationLifecycle.Lease lease) {
    return chain(lease).stagePre(context(lease), new StageStart(shown(lease), null, null));
  }

  @Override
  public Gate after(InformationLifecycle.Lease lease) {
    return chain(lease)
        .stagePost(
            context(lease), new StageDone(shown(lease), "processing checkpoints committed", null));
  }

  @Override
  public Gate beforeControl(InformationLifecycle.Control control) {
    return chain(control.event())
        .stagePre(controlContext(control), new StageStart(shown(control.event()), null, null));
  }

  @Override
  public Gate afterControl(InformationLifecycle.Control control) {
    return chain(control.event())
        .stagePost(
            controlContext(control),
            new StageDone(shown(control.event()), "document control checkpoint committed", null));
  }

  @Override
  public void finished(InformationLifecycle.Lease lease) {
    controls.remove(lease.token());
    HarnessRun run = runs.remove(lease.token());
    if (run != null) {
      var records = run.finish();
      if (!records.isEmpty()) {
        String detail;
        try {
          detail = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(records);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
          throw new IllegalStateException(invalid);
        }
        catalogue.event(
            lease.revision(),
            lease.generation(),
            lease.owner(),
            lease.stage(),
            "hook.finish",
            detail);
      }
    }
  }
}
