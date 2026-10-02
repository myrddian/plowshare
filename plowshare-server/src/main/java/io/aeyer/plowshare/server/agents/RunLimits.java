package io.aeyer.plowshare.server.agents;

import java.util.Objects;

/**
 * The two bounds a run is going under, together, so that something can hold both
 * without holding the run.
 *
 * <h2>Why a pair and not two fields</h2>
 *
 * <p>{@link Job} keeps one of these and {@code api.JobView} renders one. Both
 * want the pair or neither: a job that is not one agent's run — the curator's
 * pass, which takes the work itself rather than a definition — has no {@link
 * TurnCap} of this server's <em>and</em> no {@link Budget} this class ever saw,
 * so "absent" is one fact rather than two. A pair of nullable fields would let a
 * caller handle half of it.
 *
 * <h2>What it is not</h2>
 *
 * <p><b>Not a configuration record and not a snapshot.</b> Both components are
 * live, mutable objects that the run is reading as it goes: raising the ceiling
 * on one of these is raising the ceiling on the run. There is nothing here to
 * copy, and copying would be the mistake — a number that changed nowhere.
 *
 * <p><b>And not something a run can reach.</b> Nothing hands one to {@code
 * JobRuntime}, no tool takes one, and no schema mentions either component; a
 * run is handed the two objects it must obey and no handle on the thing that can
 * move them. {@link TurnCap}'s class javadoc carries the argument, and {@code
 * ModelSurfaceTest} is what fails if any of it leaks into a prompt.
 *
 * <p>The asymmetry between the two is the one thing a reader has to carry away:
 * <b>the budget is shared down a whole delegation tree and the cap is not</b>.
 * Moving the budget here moves it for a parent and every child; moving the cap
 * moves it for the one agent whose loop this is.
 *
 * @param budget the model calls this run and everything it delegates to may
 *     still spend. Never null
 * @param cap how many turns this run may take, or that nothing is stopping it.
 *     Never null — {@link TurnCap#none()} is how "no cap" is said
 */
public record RunLimits(Budget budget, TurnCap cap) {

    public RunLimits {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(cap, "cap");
    }
}
