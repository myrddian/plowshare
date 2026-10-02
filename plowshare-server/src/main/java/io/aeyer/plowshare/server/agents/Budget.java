package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * How many model calls a run — and everything it delegates to — may still make.
 *
 * <h2>Why this is an object and not an {@code int}</h2>
 *
 * <p><b>A budget is a property of the job tree, not of one agent.</b> Two agents
 * each comfortably under their own {@code max-model-calls} can still eat the box
 * between them, and a box here is one machine serving one 9b model. Passing a
 * number down would give the child a fresh copy of the parent's allowance; this
 * is passed by reference, so a child's spending is spending the parent no longer
 * has. Task 5 is where that matters and where it is tested end to end — a
 * per-agent copy passes every single-agent test in {@code JobRuntimeTest} — but
 * the shape has to be right from here, because retrofitting sharing onto an
 * {@code int} means changing every signature that carries it.
 *
 * <p>{@link AgentDefinition#maxModelCalls()} is what a budget is built from when
 * a job is started on its own. It is deliberately <em>not</em> read again inside
 * the loop: a child job started by {@code agent_run} runs against the budget its
 * parent was given, and re-reading the child's own number there is exactly the
 * per-agent copy this class exists to prevent.
 *
 * <h2>Atomic, though the tree serialises</h2>
 *
 * <p>Task 5's design serialises sub-agent calls within a turn, and a parent
 * blocked on a child is not running, so no two <em>related</em> jobs spend
 * concurrently today. The compare-and-set is here anyway because that is a
 * property of one task's scheduling policy, and a policy is a weaker thing to
 * rest a counter's correctness on than an atomic. It costs a CAS per model call,
 * against a call that takes seconds.
 *
 * <p>{@link #trySpend} is one operation and not a {@code remaining() > 0} test
 * followed by a decrement, for the same reason: two jobs reading a remaining
 * count of one would both proceed.
 *
 * <h2>The ceiling moves</h2>
 *
 * <p><b>{@link #changeTo} is what makes a run something an operator can rescue
 * rather than something that dies at a wall.</b> The limit is read on every
 * claim, so a budget raised while a run is in flight is picked up by that run's
 * next model call without it being restarted, and a budget lowered stops it at
 * its next turn boundary. Both are set out at {@link #limit} and {@link
 * #changeTo}, along with the part that has to be deliberate: there is one
 * ceiling in a delegation tree, so moving it moves it for the parent and every
 * child.
 *
 * <h2>No ceiling is a state and not a number</h2>
 *
 * <p>{@link #none()} is a budget nothing stops: {@link #capped()} answers
 * false and {@link #trySpend} always succeeds. It is expressed as a state
 * rather than as {@code -1} or {@code Integer.MAX_VALUE} standing in for
 * infinity, because both of those are numbers, and a number is exactly what a
 * later comparison enforces. {@code -1} read by code that forgot to check for
 * it first becomes a budget nothing can spend against; {@code
 * Integer.MAX_VALUE} survives every ordinary comparison right up until
 * something adds to it, at which point it overflows into a small or negative
 * number instead of staying the largest one. Either way a ceiling nobody set
 * gets enforced later by code that had no way to tell "no limit" from "a very
 * large limit" apart, which is the exact failure a sentinel invites and a
 * separate state does not. {@link TurnCap}'s javadoc makes the same argument
 * for the same reason; this is the sibling state, on the type that is shared
 * down a whole delegation tree rather than owned by one agent's loop.
 */
public final class Budget {

    /**
     * The ceiling, which an operator may move while a run is going, or {@code
     * null} for a budget nothing stops.
     *
     * <p><b>Mutable, and {@link #trySpend} reads it per call rather than
     * capturing it.</b> A run that is nearly out no longer dies at a wall: the
     * console shows it approaching and a person gives it more, and the next call
     * sees the new number. That is the interaction this field being {@code
     * final} used to rule out.
     *
     * <p><b>Raising this raises it for a parent and every child, and that is
     * deliberate.</b> This object is shared by reference down a whole delegation
     * tree — the argument at the top of this class — so there is exactly one
     * ceiling in a tree and one place to move it. An operator granting a run
     * twenty more calls is granting them to the work that run is doing,
     * wherever in the tree it is being done, which is the only reading that
     * matches what a budget is for.
     *
     * <p><b>Volatile and not guarded by the same compare-and-set as {@code
     * spent}.</b> The two are not a pair: {@link #trySpend}'s loop re-reads both
     * on every attempt, so a limit that moves between the read and the
     * compare-and-set is a limit that moved during the call, and the next call
     * asks again. What the loop guarantees is that no call is made past the
     * limit <em>as it stood when the call was claimed</em>, which is the
     * strongest thing a moving ceiling can promise and the thing a caller
     * depends on.
     *
     * <p><b>A boxed {@link Integer}, not an {@code int} and a {@code boolean},
     * for {@link TurnCap#turns}'s reason: one field means no pair to read
     * between two writes and no combination of the two that means nothing.</b>
     * The box is allocated when a budget is built or moved and read once per
     * claim, against a call that takes seconds.
     */
    private volatile Integer limit;

    private final AtomicInteger spent = new AtomicInteger();
    private final Runnable charge;
    private final ReentrantLock claiming = new ReentrantLock();

    private Budget(Integer limit) {
        this(limit, null);
    }

    private Budget(Integer limit, Runnable charge) {
        this.limit = limit;
        this.charge = charge;
    }

    /**
     * A budget of {@code maxModelCalls} calls.
     *
     * <p>Non-positive is refused here rather than treated as "no calls allowed".
     * A job that may make no model calls is a job that can only end at its
     * budget, which is a configuration mistake presenting as a run that never
     * does anything; {@code AgentRegistry} already refuses a non-positive
     * {@code max-model-calls} at boot, naming the file, and this is the guard
     * for a budget built anywhere else.
     *
     * <p><b>{@link CallerFault} and not {@code IllegalArgumentException}.</b>
     * This used to throw {@code IllegalArgumentException} and leave every HTTP
     * caller to catch it and restate it as a 400 itself — {@code
     * api.AgentController.curate} did exactly that, and {@code
     * requests.RequestedBudget#in} still shows the shape of the translation
     * this replaces. The domain check belongs here either way, so the fault it
     * raises belongs here too: {@link CallerFault}'s own javadoc is the general
     * argument, made once for code with no HTTP surface of its own.
     *
     * @throws CallerFault if {@code maxModelCalls} is non-positive
     */
    public static Budget of(int maxModelCalls) {
        if (maxModelCalls <= 0) {
            throw new CallerFault(tooSmall(maxModelCalls));
        }
        return new Budget(maxModelCalls);
    }

    /**
     * A shared budget whose claims must be durably charged before a model request is sent.
     * The charge runs once per permitted call, never for an exhausted budget. A failed charge
     * propagates without claiming the call. Claims are serialized so concurrent delegates
     * cannot charge beyond the ceiling; the callback must commit before returning.
     */
    public static Budget of(int maxModelCalls, Runnable charge) {
        if (maxModelCalls <= 0) {
            throw new CallerFault(tooSmall(maxModelCalls));
        }
        return new Budget(maxModelCalls, Objects.requireNonNull(charge, "charge"));
    }

    /**
     * The one sentence a non-positive allowance is refused with, whichever
     * door it arrived at.
     *
     * <p><b>Derived rather than pinned by a test, and that is the choice.</b>
     * {@link #of} and {@link #changeTo} refuse the same number for the same
     * reason and must say the same thing: {@link #changeToOrRefuse} catches
     * {@link #changeTo}'s {@code IllegalArgumentException} and re-raises it as
     * a {@link CallerFault} carrying the message unchanged, so {@code POST
     * /v1/jobs/&#123;id&#125;/limits} and {@code POST /v1/curate} answer a
     * caller who sent {@code 0} out of the same string. Derivation rather
     * than a test asserting the two equal, because one method they both call
     * leaves no second string to drift — {@code Definitions.Defined}'s own
     * rule applied to a sentence rather than to a shape. {@code
     * BudgetTest.both_doors_refuse_a_non_positive_allowance_in_one_sentence}
     * pins it anyway, and what it guards is the re-inlining: a future edit
     * that puts a literal back in one of the two is what the test sees and
     * the derivation cannot.
     *
     * <p>The two exception <em>types</em> stay different, and deliberately:
     * {@link #of} answers a caller, {@link #changeTo} answers code with no
     * request of its own to fail. What is shared here is the sentence, not
     * the refusal — {@code requests}' own package rule, one level down.
     */
    private static String tooSmall(int maxModelCalls) {
        return "a budget needs at least one model call; got " + maxModelCalls;
    }

    /**
     * A budget with no ceiling at all — nothing in this tree is stopped by it.
     *
     * <p>Not a very large number. See the class javadoc: the whole reason this
     * type exists rather than an {@code int} is that "no limit" and "a limit
     * large enough not to matter yet" are different facts, and only one of them
     * is a decision somebody made.
     */
    public static Budget none() {
        return new Budget(null);
    }

    /**
     * A budget that has already been spent against — one read back off a row.
     *
     * <p><b>This exists so that a conversation's budget is this class and not a
     * second one.</b> {@code archive.ConversationStore} keeps a conversation's
     * limit and its spending in two columns, and a {@link #of} that always
     * starts at zero would leave the store with nowhere to put the second
     * number: it would have to hand its caller a pair of {@code int}s and let
     * every caller decide what "shared" means, which is the per-agent-copy
     * mistake this class's own javadoc argues against, one level up.
     *
     * <p><b>It is not a way to un-spend.</b> There is no setter for the
     * spending and no decrement; a caller can only construct a budget at a count
     * somebody has already recorded, and {@link #trySpend} is still the only
     * thing that moves it afterwards. {@code ConversationStore.turnEnded}
     * refuses a write that would lower a row's spending, so the number this can
     * be built from never goes backwards either.
     *
     * <p><b>{@link #changeTo} moves the other number and not this one</b>, and
     * the asymmetry is the rule: what a budget has spent is a measurement and
     * only the spending changes it, while what it may spend is a decision and an
     * operator may take a different one.
     *
     * @param alreadySpent what has been spent against it, as the row records it
     * @throws CallerFault if the limit is non-positive — {@link #of}'s own
     *     refusal, reached through the call this method opens with
     * @throws IllegalArgumentException if {@code alreadySpent} is negative, or
     *     if it exceeds the limit. The last is the invariant {@link #trySpend}
     *     holds and {@code conversations_spent_within_budget} holds in
     *     Postgres; a row that broke it would be a conversation that has
     *     already had more model calls than anybody granted it, and reading it
     *     back in silence would launder that into a fresh allowance
     */
    public static Budget resumed(int maxModelCalls, int alreadySpent) {
        Budget budget = of(maxModelCalls);
        if (alreadySpent < 0) {
            throw new IllegalArgumentException(
                    "a budget cannot have spent " + alreadySpent + " model calls");
        }
        if (alreadySpent > maxModelCalls) {
            throw new IllegalArgumentException("a budget of " + maxModelCalls
                    + " model calls cannot have spent " + alreadySpent);
        }
        budget.spent.set(alreadySpent);
        return budget;
    }

    /**
     * An uncapped budget that has already been spent against — one read back
     * off a row that recorded no ceiling.
     *
     * <p>{@link #resumed}'s reason, for {@link #none}'s state: a conversation
     * rehydrated from a row needs somewhere to put what it has already spent,
     * and {@link #none} alone starts every budget at zero. There is no limit
     * here for {@code alreadySpent} to be checked against — that is the point
     * of the state being rehydrated — so unlike {@link #resumed} this only
     * refuses a negative count, not one that exceeds a ceiling that does not
     * exist.
     *
     * @param alreadySpent what has been spent against it, as the row records it
     * @throws IllegalArgumentException if {@code alreadySpent} is negative
     */
    public static Budget lifted(int alreadySpent) {
        if (alreadySpent < 0) {
            throw new IllegalArgumentException(
                    "a budget cannot have spent " + alreadySpent + " model calls");
        }
        Budget budget = none();
        budget.spent.set(alreadySpent);
        return budget;
    }

    /**
     * Claim one model call, if there is one left.
     *
     * @return true if the caller may make the call, in which case it has already
     *     been counted. A false return counts nothing: the number a run reports
     *     is what it spent, not what it tried to.
     */
    public boolean trySpend() {
        if (charge != null) {
            claiming.lock();
            try {
                Integer ceiling = limit;
                if (ceiling != null && spent.get() >= ceiling) {
                    return false;
                }
                charge.run();
                spent.incrementAndGet();
                return true;
            } finally {
                claiming.unlock();
            }
        }
        // The retry arm of this loop — a compare-and-set losing to another
        // thread — has no test and cannot get a deterministic one: it needs two
        // threads inside these four lines at once. Said here so a green suite is
        // not read as covering it. What is tested is the invariant the loop
        // exists to hold, that the limit is never exceeded, which is the part a
        // caller depends on.
        while (true) {
            Integer ceiling = limit;
            if (ceiling == null) {
                // Nothing to compare against, so nothing to lose a race on: the
                // spend is counted and the call is always permitted. A budget
                // with no ceiling is still a budget with spending to report —
                // see #lifted — and incrementAndGet is itself atomic, so this
                // does not need the compare-and-set below it.
                spent.incrementAndGet();
                return true;
            }
            int now = spent.get();
            if (now >= ceiling) {
                return false;
            }
            if (spent.compareAndSet(now, now + 1)) {
                return true;
            }
        }
    }

    /** How many calls have been made against this budget, by every job holding
     *  it. */
    public int spent() {
        return spent.get();
    }

    /**
     * How many calls are left, never negative.
     *
     * <p><b>The clamp was belt and braces and is now load-bearing, which is a
     * change {@link #changeTo} made rather than a claim this sentence always
     * carried.</b> It used to say that no test could distinguish it from its own
     * absence, because {@link #trySpend} refuses past the limit and the
     * subtraction could not go negative. A ceiling that moves breaks that: an
     * operator who lowers the limit below what a tree has already spent leaves
     * {@code limit - spent} negative, and this is what turns it into the zero
     * that is true — there is nothing left. {@code
     * a_budget_lowered_below_what_it_has_spent_has_nothing_left} is what
     * distinguishes it now.
     *
     * <p>The original reason it was kept still holds and is the same one: this
     * is a public accessor a caller may render into a message, and "-1 model
     * calls remaining" would send whoever read it looking for an accounting bug
     * that is not there.
     *
     * @throws IllegalStateException if this budget has no ceiling. {@code limit()
     *     - spent()} with no {@code limit()} is the invented number this whole
     *     type exists to refuse, not a subtraction to make some other way — a
     *     budget nothing stops has no remaining to run out of, so a caller that
     *     wants to render one asks {@link #capped} first, same as {@link #limit}
     */
    public int remaining() {
        Integer ceiling = limit;
        if (ceiling == null) {
            throw new IllegalStateException("this budget has no ceiling, so it has no calls"
                    + " remaining to report; ask capped() first");
        }
        return Math.max(0, ceiling - spent.get());
    }

    /** Whether there is a ceiling at all. */
    public boolean capped() {
        return limit != null;
    }

    /**
     * Whether there is nothing left to spend — the question {@link #remaining}
     * was being asked to answer, asked in a way that has an answer in both
     * states.
     *
     * <p><b>This is {@link TurnCap#stops}'s shape, and it is here for the reason
     * that predicate keeps {@code TurnCap} safe.</b> A turn cap survives being
     * uncapped because every caller asks it {@code stops(steps)} — true or false
     * in both states — so no caller ever needs {@code turns()}. This type had
     * {@link #trySpend} as the equivalent for the one caller that is about to
     * make a call, and nothing at all for the callers that only want to know
     * whether a call could be made: they reached past it for {@code remaining()
     * == 0}, which is a subtraction, and a subtraction needs a ceiling. Four
     * separate 500s were that one missing predicate — {@code Turn.speak}, {@code
     * Turn.resume}, and both halves of {@code api.JobView} — each of them a
     * caller asking a yes-or-no question through a number.
     *
     * <p><b>A budget with no ceiling is never exhausted</b>, which is not a
     * special case bolted on but the plain reading of {@link #none}: it permits
     * every call a tree could make, so there is never a call it has run out of
     * room for. That is exactly what {@link #trySpend} already answers for one
     * call, and this answers for none — no spend is claimed and nothing is
     * counted, so a caller deciding whether to offer, refuse or render may ask
     * it as often as it likes.
     *
     * <p><b>Not the negation of "has calls left" as a number.</b> A caller that
     * genuinely needs the count — a message naming how many, a meter — still
     * asks {@link #capped} and then {@link #remaining}, because that caller
     * really is after a number and there really is no number to give it. This
     * one is for the callers that only ever compared it to zero.
     */
    public boolean exhausted() {
        Integer ceiling = limit;
        return ceiling != null && spent.get() >= ceiling;
    }

    /**
     * The ceiling as it now stands, for messages.
     *
     * <p>Was "the number this budget was built with" and is no longer: {@link
     * #changeTo} moves it, so this answers what the budget allows now rather
     * than what it allowed when it was opened. Nothing records the opening
     * number in memory — {@code conversations.budget_total} is where a
     * conversation's allowance is durable, and it is written when it changes.
     *
     * @throws IllegalStateException if this budget has no ceiling. A number is
     *     not invented for a budget that has none — that is the substitution
     *     {@link #none} exists to refuse — so a caller that wants to render one
     *     asks {@link #capped} first, {@link TurnCap#turns}'s discipline.
     */
    public int limit() {
        Integer ceiling = limit;
        if (ceiling == null) {
            throw new IllegalStateException("this budget has no ceiling, so it has no limit to"
                    + " report; ask capped() first");
        }
        return ceiling;
    }

    /**
     * Move the ceiling on a budget that is already being spent.
     *
     * <p><b>Raising it is the point</b>, and the whole delegation tree holding
     * this object is raised with it; see {@link #limit}.
     *
     * <p><b>Lowering it below what has already been spent is permitted and is
     * defined.</b> {@link #remaining} answers zero, {@link #trySpend} refuses,
     * and every run holding this budget stops at its next turn boundary with
     * {@code CALL_BUDGET} — the ordinary ending for a budget with nothing left,
     * reached by a different route. Nothing is unwound and nothing is refunded:
     * the calls that were made were made, and {@link #spent} goes on reporting
     * them. The alternative — refusing a lowering that a run has already
     * overtaken — would leave an operator trying to stop a runaway tree unable
     * to, which is the situation the lowering is for.
     *
     * <p><b>Note what that means for a conversation, and where it is refused.</b>
     * {@code conversations_spent_within_budget} refuses a row whose spending
     * exceeds its total, and {@code ConversationStore.turnEnded} writes this
     * limit back onto the row when the turn ends — so a lowering that has
     * already been overtaken is a number that table cannot hold. Nothing here
     * refuses it, because at this level it is well defined and useful; the
     * refusal is at {@link #changeToOrRefuse}, the guarded entry point {@code
     * Limits#move} actually calls, which names the two numbers
     * and names cancellation as the verb for stopping a run. {@code
     * a_budget_cannot_be_lowered_below_what_a_run_has_already_spent} is what
     * holds it there.
     *
     * <p><b>Refused when the budget has no ceiling, and not for {@link
     * #limit}'s reason.</b> {@link Limits#move} is where {@code
     * POST /v1/jobs/{id}/limits} reaches this: an operator granting more
     * allowance to a run that looks like it is running out. A budget built by
     * {@link #none} is not running out and cannot be — it already permits
     * every call a tree could make — so there is nothing for a number handed
     * here to grant. Setting one anyway would not raise an allowance, it would
     * invent a ceiling where {@link #none} said there should be none, quietly
     * taking back a decision this method was never asked to revisit. The
     * refusal says that, in place of a number, so the operator who sent it
     * reads an answer to what they actually asked for rather than a null
     * pointer surfacing three calls into their own request.
     *
     * @throws IllegalArgumentException if {@code maxModelCalls} is non-positive,
     *     which is {@link #of}'s refusal for {@link #of}'s reason: a budget that
     *     permits no calls is not a smaller budget, it is a configuration
     *     mistake presenting as a run that never does anything
     * @throws IllegalStateException if this budget has no ceiling to raise
     */
    public void changeTo(int maxModelCalls) {
        if (maxModelCalls <= 0) {
            // The identical sentence of() gives, out of the one method that
            // holds it -- see tooSmall's own javadoc for why these two share
            // the string and not the exception type.
            throw new IllegalArgumentException(tooSmall(maxModelCalls));
        }
        if (limit == null) {
            throw new IllegalStateException("this budget has no ceiling, so there is nothing"
                    + " for changeTo() to raise; it already permits every call a run could"
                    + " make");
        }
        this.limit = maxModelCalls;
    }

    /**
     * {@link #changeTo}, refused rather than applied when {@code
     * maxModelCalls} would leave this budget's total below what it has
     * already spent — the guarded entry point {@code POST
     * /v1/jobs/&#123;id&#125;/limits} calls, in place of {@link #changeTo}
     * directly.
     *
     * <h2>Why this is not simply {@link #changeTo}</h2>
     *
     * <p>{@link #changeTo}'s own javadoc permits and defines a lowering below
     * what has already been spent, in general: a run stops at its next turn
     * boundary and nothing is refunded, which is well defined and useful on
     * its own. It is the wrong answer for a caller whose ceiling is about to
     * be written back onto a conversation row — {@code
     * conversations_spent_within_budget} refuses a row whose spending exceeds
     * its total, so a total below the spending here is a number that table
     * cannot hold. Read {@link #spent} once and compare against that, rather
     * than asking twice: the run is spending while this is decided, so a
     * second read could refuse against a number the message did not name.
     *
     * <p><b>{@code changeTo} throws two different exceptions and both are
     * caught here.</b> {@code IllegalArgumentException} is {@link #changeTo}'s
     * own refusal of a non-positive number — the identical reasoning {@link
     * #of} gives for the same number, and now the identical sentence, both
     * taken from {@code tooSmall}; the <em>type</em> still differs, since
     * {@link #of} raises {@link CallerFault} directly and {@link #changeTo}
     * answers a caller with no request of its own to fail;
     * {@code IllegalStateException} is a budget built by {@link #none}, which
     * has no ceiling to raise — a sentence written for exactly this caller, an
     * operator granting more calls to a run that looks like it is running out
     * when the run in front of them was never going to run out.
     *
     * <p><b>{@link CallerFault} and not {@code BadRequestException}.</b> This
     * class has no request and no status of its own — {@code
     * agents.Limits} is the one caller today — so a refusal here is a
     * {@link CallerFault}: the same 400 by the time it reaches a caller,
     * thrown by code that does not import the HTTP surface to say so. See
     * {@code archive.Conversations}' own class javadoc for the identical
     * argument, made first.
     *
     * @param id the job this budget belongs to, named in the refusal
     * @param maxModelCalls the ceiling to move to
     * @throws CallerFault if {@code maxModelCalls} is below what this budget
     *     has already spent, or if {@link #changeTo} itself refuses
     */
    public void changeToOrRefuse(String id, int maxModelCalls) {
        int spent = spent();
        if (maxModelCalls < spent) {
            throw new CallerFault("job " + id + " has already spent " + spent
                    + " model calls, and a budget of " + maxModelCalls + " cannot record that."
                    + " A conversation's row holds what it was given and what it has spent, and"
                    + " the second may not exceed the first. To stop this run, cancel it:"
                    + " POST /v1/jobs/" + id + "/cancel stops it at the same turn boundary and"
                    + " leaves the accounting true. Nothing was changed.");
        }
        try {
            changeTo(maxModelCalls);
        } catch (IllegalArgumentException | IllegalStateException refused) {
            throw new CallerFault(refused.getMessage(), refused);
        }
    }

    /**
     * This budget in words, for a log line or a debugger — the one accessor
     * here that must never throw.
     *
     * <p>Unlike {@link #limit} and {@link #remaining}, which refuse to invent a
     * number, this has a real one to report even for an uncapped budget: {@link
     * #spent} is a measurement that exists in both states, and "no ceiling" is
     * itself the answer for the other half — not a gap standing in for one, the
     * way {@link #limit}'s absence is. A caller reaching for {@code toString}
     * is usually already mid-diagnosis, often of something else entirely, and
     * making it throw would be a second failure landing on top of the first.
     */
    @Override
    public String toString() {
        Integer ceiling = limit;
        return ceiling == null
                ? "Budget[" + spent() + " model calls, no ceiling]"
                : "Budget[" + spent() + "/" + ceiling + " model calls]";
    }
}
