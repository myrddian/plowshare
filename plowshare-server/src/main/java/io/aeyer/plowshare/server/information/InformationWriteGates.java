package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.harness.*;
import io.aeyer.plowshare.server.hooks.*;
import io.aeyer.plowshare.server.llm.accounting.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Gates a prepared, validated database transition before making its new information visible. No
 * model/network/hook runs in a database transaction. Approved receipts resume without refiring
 * hooks.
 */
public final class InformationWriteGates implements UsageAware {
  private UsageOwners usageOwners = UsageOwners.NONE;

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = java.util.Objects.requireNonNull(source);
  }

  private final InformationGateRepository repository;
  private final UnitOfWork work;
  private final InformationAccess access;
  private final InformationJobs inputs;
  private final ConversationStore conversations;
  private final LogStages logs;
  private final Hooks configured;
  private final Harness harness;

  public InformationWriteGates(
      InformationGateRepository repository,
      UnitOfWork work,
      InformationAccess access,
      InformationJobs inputs,
      ConversationStore conversations,
      LogStages logs,
      Hooks configured,
      Harness harness) {
    this.repository = Objects.requireNonNull(repository);
    this.work = work;
    this.access = access;
    this.inputs = inputs;
    this.conversations = conversations;
    this.logs = logs;
    this.configured = configured;
    this.harness = harness;
  }

  public <T extends InformationGateResult> T execute(
      InformationContext context,
      UUID request,
      String operation,
      InformationGateIdentity identity,
      List<UUID> sources,
      String session,
      Class<T> resultType,
      Supplier<T> transition) {
    access.requireSelection(context);
    if (request == null) throw new CallerFault("stage transition needs a stable requestId");
    String fingerprint = InformationGateFingerprint.of(operation, context.selection(), identity);
    var receipt =
        work.inTransaction(
            () -> repository.claim(context.account(), request, fingerprint, operation, resultType));
    if (receipt.state() == InformationGateRepository.State.COMPLETED) return receipt.response();
    HarnessRun run = harness.begin();
    try {
      if (receipt.state() != InformationGateRepository.State.APPROVED) {
        String log = receipt.log();
        if (log == null) {
          String project = context.selection().project();
          Home home = project == null ? Home.global() : Home.of(project);
          log =
              conversations
                  .log(
                      Origin.SUBMISSION,
                      home,
                      "document_pipeline",
                      null,
                      Budget.of(1),
                      context.account(),
                      null)
                  .id();
          inputs.bind(log, context, sources);
          logs.opened(
              new LogStages.LogOpened(
                  log, Origin.SUBMISSION, home, "document_pipeline", false, null, session, null));
          String opened = log;
          work.inTransaction(
              () -> {
                fence(context, request, receipt);
                repository.opened(context.account(), request, opened);
                return null;
              });
        }
        inputs.requireLog(log, context.account());
        var hookContext =
            HookContext.forLog(
                    "submission", "document_pipeline", false, context.selection().project(), log)
                .withUsage(usageOwners.conversation(log, 0, UsageAttribution.Operation.HOOK_MODEL))
                .about(
                    new HookContext.Document(
                        operation,
                        null,
                        sources.isEmpty() ? null : sources.getFirst().toString(),
                        1,
                        operation,
                        1));
        Hooks chain = Hooks.chain(run.forModel(null), configured);
        var shown = new StageShown(operation, operation, 0, 1);
        Gate pre = chain.stagePre(hookContext, new StageStart(shown, null, null));
        repository.preGate(context.account(), request, receipt.token(), pre);
        if (pre.isDenied()) throw new CallerFault(pre.denied());
        Gate post =
            chain.stagePost(
                hookContext,
                new StageDone(shown, "validated information transition prepared", null));
        repository.postGate(context.account(), request, receipt.token(), post);
        if (post.isDenied()) throw new CallerFault(post.denied());
        work.inTransaction(
            () -> {
              fence(context, request, receipt);
              inputs.requireLog(openedLog(context, request), context.account());
              repository.approved(context.account(), request);
              return null;
            });
      }
      return work.inTransaction(
          () -> {
            fence(context, request, receipt);
            access.requireSelection(context);
            inputs.requireLog(openedLog(context, request), context.account());
            T result = transition.get();
            repository.completed(context.account(), request, result);
            return result;
          });
    } catch (RuntimeException failure) {
      repository.blocked(
          context.account(),
          request,
          receipt.token(),
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
      throw failure;
    } finally {
      repository.finishRecords(context.account(), request, receipt.token(), run.finish());
    }
  }

  private String openedLog(InformationContext context, UUID request) {
    return repository.openedLog(context.account(), request);
  }

  private void fence(
      InformationContext context, UUID request, InformationGateRepository.Receipt<?> receipt) {
    repository.fence(context.account(), request, receipt.token());
  }
}
