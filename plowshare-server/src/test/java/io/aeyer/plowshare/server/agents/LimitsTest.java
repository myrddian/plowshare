package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Moving a running job's ceilings: the whole decision, below the surface that
 * asked for it.
 *
 * <p>{@code AgentControllerTest} still measures the same refusals through
 * HTTP, and deliberately: what that file proves is that the status and the
 * body a caller sees did not move when the decision did. This file proves the
 * decision itself — every refusal, and above all <b>the order they are reached
 * in</b>, which is the half no status can show.
 *
 * <p><b>{@link #a_refused_budget_leaves_the_turn_cap_where_it_was} is the
 * point of this class.</b> The budget is moved before the cap is, so a body
 * naming both with the budget refused does not leave the cap silently applied.
 * Nothing throws for the order; get it backwards and a refused request still
 * mutates a run, with no refusal anywhere near the bug.
 */
class LimitsTest {

    private JobStore jobs;
    private Limits limits;

    @BeforeEach
    void setUp() {
        jobs = mock(JobStore.class);
        limits = new Limits(jobs);
    }

    // --- what a move that is not refused does ------------------------------------

    /** The proof a ceiling landed is that the object the run reads has moved,
     *  not that a call returned. */
    @Test
    void a_named_cap_moves_the_ceiling_the_run_reads() {
        RunLimits bounds = new RunLimits(Budget.of(40), TurnCap.of(16));
        when(jobs.get("job_000001")).thenReturn(runningUnder("job_000001", bounds));

        Job job = limits.move("job_000001", 36, null, null);

        assertEquals("job_000001", job.id());
        assertEquals(36, bounds.cap().turns());
        assertFalse(bounds.cap().stops(20), "the run is no longer past its ceiling");
    }

    @Test
    void a_cap_can_be_taken_off_altogether() {
        RunLimits bounds = new RunLimits(Budget.of(40), TurnCap.of(16));
        when(jobs.get("job_000001")).thenReturn(runningUnder("job_000001", bounds));

        limits.move("job_000001", null, true, null);

        assertFalse(bounds.cap().capped());
    }

    /** The budget is shared by a whole delegation tree, so this moves it for
     *  every job in one. */
    @Test
    void a_named_budget_moves_what_the_whole_tree_may_spend() {
        Budget budget = Budget.of(16);
        budget.trySpend();
        RunLimits bounds = new RunLimits(budget, TurnCap.of(100));
        when(jobs.get("job_000001")).thenReturn(runningUnder("job_000001", bounds));

        limits.move("job_000001", null, null, 60);

        assertEquals(60, budget.limit());
        assertEquals(1, budget.spent(), "and raising a ceiling un-spends nothing");
    }

    // --- the refusals ------------------------------------------------------------

    /** A curator pass builds its own budget and shares it across every ruling
     *  in it; nothing on this handle can move it. */
    @Test
    void a_job_that_is_not_an_agent_run_has_no_limits_to_move() {
        when(jobs.get("job_000001")).thenReturn(JobAccess.newJob("job_000001", "curator"));

        CallerFault refused = assertThrows(CallerFault.class,
                () -> limits.move("job_000001", 36, null, null));

        assertTrue(refused.getMessage().contains(
                "job job_000001 is not an agent run and has no limits this server holds"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("Nothing was changed."), refused.getMessage());
    }

    /** Raising a ceiling on a finished run is asking for something that cannot
     *  happen, and answering as though it did would say it had. */
    @Test
    void moving_the_limits_of_a_finished_job_is_refused() {
        RunLimits bounds = new RunLimits(Budget.of(40), TurnCap.of(16));
        Job job = runningUnder("job_000001", bounds);
        JobAccess.finish(job, new Outcome(Outcome.Ending.ANSWERED, "done", 1, 1, ""));
        when(jobs.get("job_000001")).thenReturn(job);

        CallerFault refused = assertThrows(CallerFault.class,
                () -> limits.move("job_000001", 36, null, null));

        assertTrue(refused.getMessage().contains(
                "job job_000001 has already finished, so there is nothing left for a limit to"
                        + " bound."), refused.getMessage());
        assertEquals(16, bounds.cap().turns(), "and nothing was changed");
    }

    /** A body naming nothing would answer as though a limit had been moved. */
    @Test
    void a_body_naming_no_limit_is_refused_rather_than_answered() {
        RunLimits bounds = new RunLimits(Budget.of(40), TurnCap.of(16));
        when(jobs.get("job_000001")).thenReturn(runningUnder("job_000001", bounds));

        CallerFault refused = assertThrows(CallerFault.class,
                () -> limits.move("job_000001", null, null, null));

        assertTrue(refused.getMessage().contains("this body names no limit to move"),
                refused.getMessage());
    }

    // --- the ordering ------------------------------------------------------------

    /**
     * <b>The rule nothing refuses.</b> The budget moves first and the cap only
     * once it has either moved or been refused, so a body naming both with the
     * budget refused leaves neither half applied.
     *
     * <p>Reverse the two statements and this run keeps a cap of 36 from a
     * request that was answered with a refusal — the caller is told nothing
     * was changed, and something was.
     */
    @Test
    void a_refused_budget_leaves_the_turn_cap_where_it_was() {
        Budget budget = Budget.of(16);
        budget.trySpend();
        budget.trySpend();
        budget.trySpend();
        RunLimits bounds = new RunLimits(budget, TurnCap.of(16));
        when(jobs.get("job_000001")).thenReturn(runningUnder("job_000001", bounds));

        CallerFault refused = assertThrows(CallerFault.class,
                () -> limits.move("job_000001", 36, null, 1));

        assertTrue(refused.getMessage().contains("already spent 3"), refused.getMessage());
        assertEquals(16, bounds.cap().turns(),
                "a refused budget must not leave the cap half of the same body applied");
        assertEquals(16, budget.limit(), "and the budget itself did not move either");
    }

    /**
     * The job is fetched before the body is read at all, so an id this process
     * does not know is a miss rather than a complaint about the body.
     */
    @Test
    void an_unknown_job_is_a_miss_before_the_body_is_read() {
        when(jobs.get("job_000001")).thenThrow(new NotFoundFault("no job job_000001"));

        assertThrows(NotFoundFault.class, () -> limits.move("job_000001", null, null, null));
    }

    /**
     * The body's own shape before the job's kind: a curator pass asked to
     * change nothing hears that it named nothing, which is the correction that
     * is true of every job.
     */
    @Test
    void a_body_naming_nothing_is_refused_before_the_jobs_kind_is() {
        when(jobs.get("job_000001")).thenReturn(JobAccess.newJob("job_000001", "curator"));

        CallerFault refused = assertThrows(CallerFault.class,
                () -> limits.move("job_000001", null, null, null));

        assertTrue(refused.getMessage().contains("this body names no limit to move"),
                refused.getMessage());
    }

    /**
     * And the job's kind before its state: a finished curator pass is told it
     * is not an agent run, which is the fact that would still be true had it
     * been running.
     */
    @Test
    void a_job_with_no_limits_is_refused_before_its_state_is_read() {
        Job job = JobAccess.newJob("job_000001", "curator");
        JobAccess.finish(job, new Outcome(Outcome.Ending.ANSWERED, "done", 1, 1, ""));
        when(jobs.get("job_000001")).thenReturn(job);

        CallerFault refused = assertThrows(CallerFault.class,
                () -> limits.move("job_000001", 36, null, null));

        assertTrue(refused.getMessage().contains("is not an agent run"), refused.getMessage());
    }

    /** A turn cap said twice is {@code RequestedTurnCap}'s own refusal, and it
     *  wins over this door's "names no limit" — the nesting Task 1 built. */
    @Test
    void a_turn_cap_said_twice_is_refused_in_its_own_words() {
        RunLimits bounds = new RunLimits(Budget.of(40), TurnCap.of(16));
        when(jobs.get("job_000001")).thenReturn(runningUnder("job_000001", bounds));

        CallerFault refused = assertThrows(CallerFault.class,
                () -> limits.move("job_000001", 36, true, null));

        assertFalse(refused.getMessage().contains("names no limit to move"),
                refused.getMessage());
        assertEquals(16, bounds.cap().turns(), "and nothing was changed");
    }

    // --- fixtures ----------------------------------------------------------------

    /** A running job whose bounds this server holds — the shape every agent run
     *  has, and the one a curator pass does not. */
    private static Job runningUnder(String id, RunLimits bounds) {
        return JobAccess.newJob(id, "promotion_judge", bounds, "cnv_000001", Origin.TURN);
    }
}
