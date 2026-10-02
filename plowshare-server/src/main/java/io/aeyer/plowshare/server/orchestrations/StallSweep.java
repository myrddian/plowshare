package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports a running orchestration that has gone quiet to the person who owns it — never to the
 * model that started it (spec 2026-09-27 §4). Measured twice, a caller that decided a run was stuck
 * cancelled it while it was working; whether a stall is a concern is the person's call.
 *
 * <p>Quiet is the whole conversation tree (a sub-agent writes to its own delegation conversation).
 * A run waiting on something that already reaches the person — an open approval — or on its own
 * children is not a stall, unless those children are only waiting on it in turn (asking it a
 * question, rule 3 of spec 2026-09-29). "Once per stall" is the store's compare-and-set, so
 * several servers sharing one database report it once.
 */
public final class StallSweep {

    private static final Logger log = LoggerFactory.getLogger(StallSweep.class);

    private final OrchestrationStore store;
    private final Function<String, List<RunApproval>> openApprovals;
    private final InboxPort inbox;
    private final Duration quiet;
    private final OrchestrationRecorder recorder;
    private final Predicate<OrchestrationRecord> atAcceptance;

    public StallSweep(OrchestrationStore store, Function<String, List<RunApproval>> openApprovals,
            InboxPort inbox, Duration quiet) {
        this(store, openApprovals, inbox, quiet, OrchestrationRecorder.NONE);
    }

    /** @param recorder told of each stall this sweep is the one to report — spec 2026-09-28 */
    public StallSweep(OrchestrationStore store, Function<String, List<RunApproval>> openApprovals,
            InboxPort inbox, Duration quiet, OrchestrationRecorder recorder) {
        this(store, openApprovals, inbox, quiet, recorder, run -> false);
    }

    /**
     * @param atAcceptance whether a run's conductor is at its {@code acceptance: required} stage
     *     — the one place an acceptance command's approval is what it waits on ({@link
     *     #atAcceptance(OrchestrationRecord, List)})
     */
    public StallSweep(OrchestrationStore store, Function<String, List<RunApproval>> openApprovals,
            InboxPort inbox, Duration quiet, OrchestrationRecorder recorder,
            Predicate<OrchestrationRecord> atAcceptance) {
        this.atAcceptance = Objects.requireNonNull(atAcceptance, "atAcceptance");
        this.store = Objects.requireNonNull(store, "store");
        this.openApprovals = Objects.requireNonNull(openApprovals, "openApprovals");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.quiet = Objects.requireNonNull(quiet, "quiet");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    /**
     * One pass over every {@code running} run, judged against {@code now}. Each run's own
     * judgement is isolated from every other's — {@link #sweepOne} is where a store failure or a
     * refused delivery on one row is caught and logged, so it never stops the pass from reaching
     * the rest.
     *
     * @return how many runs this call is the one that newly reported stalled — never a run some
     *     earlier sweep (this server's or another's) already reported, because {@link
     *     OrchestrationStore#markStalled} is the compare-and-set that decides that, and never a
     *     run whose own delivery failed, which is rolled back to unmarked for the next sweep to
     *     try again
     */
    public int sweep(Instant now) {
        int reported = 0;
        for (OrchestrationRecord run : store.running()) {
            try {
                if (sweepOne(run, now)) {
                    reported++;
                }
            } catch (RuntimeException failed) {
                log.warn("the stall sweep could not judge orchestration {}; the next sweep tries"
                        + " again", run.id(), failed);
            }
        }
        return reported;
    }

    /**
     * One run's own judgement against {@code now}.
     *
     * <p>A run that is active again, or that is now excused, has any stall mark it still carries
     * cleared — {@code stalled_since} means "still true", not "was true once", because the
     * terminal's waiting list and the next sweep both read it as the current judgement. A mark
     * clears exactly when {@code quietSince} has moved past it (new activity arrived since it was
     * set) or the run is excused now, whichever applies; a run neither active nor excused is left
     * with its existing mark, or given a fresh one if it has none yet — {@link
     * OrchestrationStore#markStalled} itself supersedes a mark from an earlier, superseded quiet
     * period, so a stall is never lost just because no sweep observed the run active in between.
     *
     * @return whether this call is the one that newly reported this run stalled
     */
    private boolean sweepOne(OrchestrationRecord run, Instant now) {
        Instant quietSince = store.quietSince(run);
        boolean stalled = !quietSince.plus(quiet).isAfter(now);
        // RULE 3 (spec 2026-09-29 §3): a child asking its parent is waiting on this run, not
        // working for it, so it is no excuse. A child asking the person (stuck) still is.
        // Measured 2026-09-28 23:51, orc_31893856D8F462A1: a phase asking its parent about its cap
        // excused that parent, and the person was never told of five hours deadlocked.
        boolean childWorking = store.liveChildren(run.id()).stream()
                .anyMatch(child -> !waitsOnItsParent(child));
        // An acceptance command's approval is asked at the spec stage, and the conductor goes on
        // with its plan meanwhile (plan choice 4): it is waited on only at the acceptance stage.
        // Anywhere else it excused a run that was not waiting on it at all (final review).
        boolean excused = openApprovals.apply(run.conductorConversation()).stream()
                .anyMatch(approval -> !AcceptanceGate.isAcceptance(approval.reason())
                        || atAcceptance.test(run))
                || childWorking;
        if (!stalled || excused) {
            store.stalledSince(run.id())
                    .filter(since -> excused || quietSince.isAfter(since))
                    .ifPresent(since -> store.clearStalled(run.id()));
            return false;
        }
        if (!store.markStalled(run.id(), quietSince)) {
            return false;
        }
        recorder.stalled(run, "has done nothing for " + quiet.toMinutes() + " minutes");
        if (run.callerHandle() == null) {
            log.warn("orchestration {} has done nothing since {}, and has no account to tell",
                    run.id(), quietSince);
            return true;
        }
        try {
            inbox.notify(run.callerHandle(), Delivery.INBOX_KIND, "`" + run.id() + "` (`"
                    + run.definitionName() + "`) has done nothing for " + quiet.toMinutes()
                    + " minutes: `/runs " + run.id() + "` to look, `/cancel " + run.id()
                    + "` to stop it");
            return true;
        } catch (RuntimeException failed) {
            // Marked, but never told: put the mark back rather than leave this quiet period
            // silently reported to nobody, so the next sweep treats it as still unreported and
            // tries the delivery again.
            store.clearStalled(run.id());
            throw failed;
        }
    }

    /**
     * Whether {@code run}'s conductor is at its {@code acceptance: required} stage.
     *
     * @param run the run
     * @param todos its conductor's list
     * @return whether that stage's item is in progress
     */
    public static boolean atAcceptance(OrchestrationRecord run, List<TodoItem> todos) {
        Set<String> required = run.stages().stream()
                .filter(stage -> OrchestrationDefinition.ACCEPTANCE_REQUIRED.equals(
                        stage.acceptance()))
                .map(StageRules.Stage::id).collect(Collectors.toSet());
        return todos.stream().anyMatch(item -> item.locked() && required.contains(item.stageId())
                && item.status() == TodoStatus.IN_PROGRESS);
    }

    /** Whether a live child is asking its parent — a question other than one only the person
     *  may answer. */
    static boolean waitsOnItsParent(OrchestrationRecord child) {
        return child.state() == OrchestrationState.ASKING
                && !Orchestrations.personOnly(child.pendingCap());
    }
}
