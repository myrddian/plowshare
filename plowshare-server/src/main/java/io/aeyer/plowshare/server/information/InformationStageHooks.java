package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Home;
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
      var budget =
          io.aeyer.plowshare.server.agents.Budget.resumed(state.allowance(), state.spent());
      log =
          conversations
              .log(Origin.SUBMISSION, home, "document_pipeline", null, budget, lease.owner(), null)
              .id();
      inputs.bind(
          log,
          new InformationContext(
              lease.owner(),
              project == null
                  ? InformationContext.Selection.personal()
                  : InformationAccess.projectSelection(lease.owner(), project)),
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
  public void finished(InformationLifecycle.Lease lease) {
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
