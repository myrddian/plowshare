package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.hooks.Approving;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.StageDone;
import io.aeyer.plowshare.server.hooks.StageStart;
import io.aeyer.plowshare.server.hooks.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One run's {@link RunHooks}: its context and its chain, asked per gate so a run rerouted to its
 * fallback model runs that model's profile from then on, as every run stage does.
 *
 * <p><b>Guarded as {@code JobRuntime} guards a tool stage</b>: {@code Hooks} promises not to throw,
 * and a gate that throws anyway — or a chain that cannot be built — denies, recorded as {@code
 * harness:hooks} (spec 2026-09-28-hooks-reach-the-log §2.4).
 */
public final class InTurnHooks implements RunHooks {

  /** What the run is shown when the chain itself failed; the detail is the record's. */
  static final String UNJUDGED = "a hook failed while judging this, and a failed check refuses";

  private final HookContext context;
  private final Supplier<Hooks> chain;
  private final List<HookRecord> parked = new ArrayList<>();

  /**
   * @param context the run's context, its log's origin already set ({@link HookContext#inLog})
   * @param chain the run's chain as the next step would ask it
   */
  public InTurnHooks(HookContext context, Supplier<Hooks> chain) {
    this.context = Objects.requireNonNull(context, "context");
    this.chain = Objects.requireNonNull(chain, "chain");
  }

  @Override
  public Gate stagePre(HookContext.Orchestration orchestration, StageStart start) {
    return guarded(
        Stage.STAGE_PRE, () -> chain.get().stagePre(context.about(orchestration), start));
  }

  @Override
  public Gate stagePost(HookContext.Orchestration orchestration, StageDone done) {
    return guarded(
        Stage.STAGE_POST, () -> chain.get().stagePost(context.about(orchestration), done));
  }

  @Override
  public Gate approvalPre(
      HookContext.RunEnvironment environment,
      HookContext.Orchestration orchestration,
      Approving approving) {
    return guarded(
        Stage.APPROVAL_PRE,
        () -> chain.get().approvalPre(context.with(environment).about(orchestration), approving));
  }

  @Override
  public synchronized void record(List<HookRecord> records) {
    parked.addAll(records);
  }

  /** Everything parked since the last drain, in the order it was parked; parks nothing after. */
  public synchronized List<HookRecord> drain() {
    List<HookRecord> drained = List.copyOf(parked);
    parked.clear();
    return drained;
  }

  private static Gate guarded(Stage stage, Supplier<Gate> fire) {
    try {
      return Objects.requireNonNull(fire.get(), "a hook chain answered nothing");
    } catch (RuntimeException broken) {
      return new Gate(
          UNJUDGED,
          List.of(),
          List.of(
              new HookRecord(
                  "harness:hooks",
                  null,
                  Tier.HARNESS,
                  stage,
                  null,
                  HookRecord.FAILED,
                  JobRuntime.describe(broken),
                  null,
                  null,
                  0)));
    }
  }
}
