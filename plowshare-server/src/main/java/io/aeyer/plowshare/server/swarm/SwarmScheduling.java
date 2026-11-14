package io.aeyer.plowshare.server.swarm;

import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.Scheduling;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The turn loop's {@link Scheduling}, answered by a {@link SwarmScheduler}. Which runs are
 * scheduled, and as whose share, belongs to each registered lifecycle owner. Unclaimed contexts run
 * without a swarm slot, preserving ordinary model scheduling.
 */
public final class SwarmScheduling implements Scheduling {

  private final SwarmScheduler scheduler;
  private final SwarmScheduler.Pools pools;
  private final RunShareProvider shares;

  public SwarmScheduling(
      SwarmScheduler scheduler,
      Function<RunExtras.Context, Optional<SwarmScheduler.Share>> shareOf) {
    this(scheduler, shareOf, null);
  }

  public SwarmScheduling(
      SwarmScheduler scheduler,
      Function<RunExtras.Context, Optional<SwarmScheduler.Share>> shareOf,
      SwarmScheduler.Pools pools) {
    this(scheduler, shareOf, pools, context -> false);
  }

  /** Messaging participants may also use ordinary models for fallbacks and delegates. */
  public SwarmScheduling(
      SwarmScheduler scheduler,
      Function<RunExtras.Context, Optional<SwarmScheduler.Share>> shareOf,
      SwarmScheduler.Pools pools,
      Predicate<RunExtras.Context> ordinaryModels) {
    Objects.requireNonNull(ordinaryModels, "ordinaryModels");
    this.pools = pools;
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    Objects.requireNonNull(shareOf, "shareOf");
    this.shares =
        context ->
            shareOf
                .apply(context)
                .map(share -> new RunShareProvider.Selection(share, ordinaryModels.test(context)));
  }

  /** Domain owners are ordered by composition and each resolves at most once per run. */
  public SwarmScheduling(
      SwarmScheduler scheduler, List<RunShareProvider> providers, SwarmScheduler.Pools pools) {
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.pools = pools;
    List<RunShareProvider> owners = List.copyOf(providers);
    this.shares =
        context -> {
          for (RunShareProvider owner : owners) {
            var selection = owner.select(context);
            if (selection.isPresent()) return selection;
          }
          return Optional.empty();
        };
  }

  @Override
  public Turn forRun(RunExtras.Context context) {
    Optional<RunShareProvider.Selection> share = shares.select(context);
    if (share.isEmpty()) {
      return Turn.ALWAYS;
    }
    SwarmScheduler.Turns turns = scheduler.enter(share.get().share());
    boolean ordinary = share.get().ordinaryModels();
    return (specifier, cancelled) -> {
      if (pools != null && pools.serving(specifier).isEmpty()) {
        if (ordinary) return Slot.ANY;
        throw new IllegalStateException(
            "no pool with swarm slots serves delegated model '" + specifier + "'");
      }
      SwarmScheduler.Grant grant = turns.await(specifier, cancelled);
      if (grant == null) {
        return null;
      }
      return new Slot() {
        @Override
        public String pool() {
          return grant.pool();
        }

        @Override
        public void release() {
          grant.release();
        }
      };
    };
  }
}
