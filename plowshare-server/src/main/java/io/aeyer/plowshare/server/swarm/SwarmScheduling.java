package io.aeyer.plowshare.server.swarm;

import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.Scheduling;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The turn loop's {@link Scheduling}, answered by a {@link SwarmScheduler}. Which runs are
 * scheduled, and as whose share, is {@code shareOf}'s to say — the board's, from step 2: a seat
 * conversation's run is; everything else answers empty and runs exactly as it always did.
 */
public final class SwarmScheduling implements Scheduling {

    private final SwarmScheduler scheduler;
    private final SwarmScheduler.Pools pools;
    private final Function<RunExtras.Context, Optional<SwarmScheduler.Share>> shareOf;

    public SwarmScheduling(
            SwarmScheduler scheduler,
            Function<RunExtras.Context, Optional<SwarmScheduler.Share>> shareOf) {
        this(scheduler, shareOf, null);
    }

    public SwarmScheduling(SwarmScheduler scheduler,
            Function<RunExtras.Context, Optional<SwarmScheduler.Share>> shareOf,
            SwarmScheduler.Pools pools) {
        this.pools = pools;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.shareOf = Objects.requireNonNull(shareOf, "shareOf");
    }

    @Override
    public Turn forRun(RunExtras.Context context) {
        Optional<SwarmScheduler.Share> share = shareOf.apply(context);
        if (share.isEmpty()) {
            return Turn.ALWAYS;
        }
        SwarmScheduler.Turns turns = scheduler.enter(share.get());
        return (specifier, cancelled) -> {
            if (pools != null && pools.serving(specifier).isEmpty()) {
                throw new IllegalStateException("no pool with swarm slots serves delegated model '"
                        + specifier + "'");
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
