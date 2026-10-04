package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.agents.Job;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Pace;
import io.aeyer.plowshare.server.agents.RunLimits;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.archive.Origin;

/**
 * One job on the wire: how it is going, and — once it is done — how it ended.
 *
 * <p>{@code outcome} is null while the run is going, and that is the whole of the difference a
 * caller needs: a job with no outcome has not finished, and a job with one has, whatever its
 * ending. <b>A truncated run is never dressed as an answer</b> — {@code ending} carries which of
 * {@code Outcome.Ending}'s constants it was, and {@code answered} is the one bit a caller has to
 * read before believing {@code text}. (It said "which of the six" until there were seven. The name
 * is sent as a string and nothing here enumerates them, so a constant added on the server reaches a
 * caller without a change to this record — which is why the number was the only thing that could go
 * stale.)
 *
 * @param cancelRequested whether somebody has asked this run to stop. Reported separately from
 *     {@code state}, because a cancelled run is still {@code RUNNING} until it reaches its next
 *     turn boundary and a caller that could not see the request would think its cancel had been
 *     lost.
 * @param conversation the conversation this run speaks into, or {@code null} for a run that has
 *     none — {@code Job#conversation} carries the argument in full. <b>Not decoration.</b> It is
 *     the only thing that makes a stopped run continuable from anywhere but the conversation it
 *     belongs to: a person looking at a jobs listing rather than the conversation a run happened to
 *     start from has nothing else on this record to send to {@code POST
 *     /v1/conversations/&#123;id&#125;/resume}. A later reader finding no caller of this field and
 *     calling it unused would be deleting that continue button before it was ever built.
 * @param limits the two bounds this run is going under, or {@code null} for a job that is not one
 *     agent's run.
 *     <p><b>Read live and reported after the run has finished as well as during it</b>, which is
 *     what makes an uncapped run legible as one. An outcome says how a run ended and cannot say
 *     what it was allowed: a run that answered on its ninetieth turn and a run that answered on its
 *     ninetieth turn <em>with no cap over it</em> produce the same {@code ANSWERED}, and only one
 *     of them was a decision somebody took. This is where that decision is visible.
 *     <p>It is also what a caller reads back after {@code POST /v1/jobs/&#123;id&#125;/limits} to
 *     see that a ceiling moved.
 */
public record JobView(
    String id,
    String agent,
    String state,
    boolean cancelRequested,
    String conversation,
    OutcomeView outcome,
    LimitsView limits) {

  /**
   * What a run is bounded by, as a caller reads it.
   *
   * <p>Five fields for two bounds, because <b>both</b> of them have three states: a number, or no
   * ceiling at all, said in separate fields for the reason {@code RequestedTurnCap} sets out —
   * uncapped is a decision and never a large number. The same pairs a caller sends to set them.
   *
   * <p><b>The budget pair was one field until it 500ed.</b> The turn cap had its
   * nullable-number-beside-a-boolean from the start and the budget did not, because a budget could
   * not say "no ceiling"; once it could, {@code Budget.limit()} started throwing here for every job
   * in a lifted delegation tree — the parent and each delegated child, since {@code JobStore} puts
   * one shared {@code Budget} into every one of their {@code RunLimits} — and {@code GET
   * /v1/jobs/&#123;id&#125;} answered 500 to a console polling it on a timer. {@code
   * ConversationView} had already been given this exact treatment for the same reason; this is it
   * applied to the other record that reports the same budget.
   *
   * @param maxTurns the ceiling as it now stands, or {@code null} when there is none. <b>It bounds
   *     steps and keeps the older word</b>, because it is the wire form of the {@code max-turns}
   *     key every agent definition carries and renaming that key is a breaking config change;
   *     {@code TurnCap} sets out what else is frozen with it
   * @param noTurnCap whether this run is running with no turn cap at all. Never true beside a
   *     number
   * @param maxModelCalls what this run and its whole delegation tree may spend, as it now stands —
   *     not necessarily what it was started with, since an operator may have moved it — or {@code
   *     null} when the allowance it is spending has no ceiling
   * @param noBudget whether this run is spending an allowance with no ceiling at all. Never true
   *     beside a number, and its own field rather than a null to read: {@code
   *     ConversationView.noBudget} carries the argument, which is that "no ceiling" is a decision
   *     somebody took and a bare null cannot say so on its own
   * @param modelCallsSpent how much of that has gone, across the whole tree. <b>Not {@code
   *     Outcome.modelCalls}</b>, which counts one job's own calls; this is the shared count a
   *     delegating run's children spend from too. Reported whether or not there is a ceiling,
   *     because a lifted budget still measures what it spent and that is the one number left to
   *     watch
   */
  public record LimitsView(
      Integer maxTurns,
      boolean noTurnCap,
      Integer maxModelCalls,
      boolean noBudget,
      int modelCallsSpent) {

    static LimitsView of(RunLimits limits) {
      return new LimitsView(
          limits.cap().capped() ? limits.cap().turns() : null,
          !limits.cap().capped(),
          // capped() asked first, exactly as it is two lines above for
          // the cap. The two bounds are read the same way because they
          // now hold the same shape of answer.
          limits.budget().capped() ? limits.budget().limit() : null,
          !limits.budget().capped(),
          limits.budget().spent());
    }
  }

  /**
   * @param answered the one bit that separates "it decided" from "it stopped"
   * @param resumable whether a person should be offered the chance to continue this run, as {@code
   *     POST /v1/conversations/&#123;id&#125;/resume}.
   *     <p><b>Here so that no client enumerates endings.</b> Which endings a grant continues is
   *     this server's decision — {@code Turn} holds the table beside the code that continues one,
   *     because the check is against the conversation's last turn rather than against a job in
   *     memory — and a console with its own copy would go on offering a grant this server had
   *     stopped taking, with nothing failing. It is the same reason {@code ending} travels as a
   *     name rather than as an enum: the list is the server's to change.
   *     <p><b>Three facts and not one</b>, which is why it is a bit rather than the ending read
   *     again. {@code Turn.continuationIsOffered} says the history is whole and the offer is one a
   *     person did not already answer — {@code CANCELLED} is continuable on request and never
   *     suggested — the {@code limits} say whether a grant could carry the run any further, and
   *     {@code Job#conversationOrigin} says whether this run is one {@code Turn.resume} takes a
   *     conversation for at all. {@link #couldBeContinued} is where the three are put together, and
   *     it carries the argument — including the correction to the sentence this paragraph used to
   *     end on, which said {@code CALL_BUDGET} was never offered in practice and called that a
   *     happy consequence of the arithmetic rather than a second list. It was a consequence of the
   *     arithmetic, and it was wrong: a grant now raises whichever number stopped the run, so the
   *     one ending that arithmetic excluded is the one the newer grant was built for.
   *     <p><b>The origin conjunct is not decoration either, and was missing for exactly as long as
   *     {@code Job#conversationOrigin} did not exist.</b> A {@code Origin.SUBMISSION} run owns its
   *     allowance, so it has {@code limits}, and its row records what it spent, so it can stop at a
   *     continuable ending same as any turn — the arithmetic above says yes on both counts. {@code
   *     Turn.requireResumable} still refuses it: it is a run nobody was going to speak to again,
   *     not a person's turn. Before this conjunct existed, this field agreed with the arithmetic
   *     and disagreed with the door — answering {@code true} for a run {@code POST
   *     /v1/conversations/&#123;id&#125;/resume} would then answer 409 to, which is the shape of
   *     failure this field exists to prevent for every <em>other</em> mismatch between an offer and
   *     the door that has to honour it.
   *     <p><b>Never a promise.</b> The endpoint is still the authority and answers 409 for a
   *     conversation that cannot take this — one with a turn already in flight — which is not a
   *     fact about a finished job
   * @param steps how many steps the run completed — one model call plus the tool results it asked
   *     for, each time round the loop. <b>Not turns</b>, which is what this field was called and
   *     what {@code turn_ordinal} and the {@code turns} table mean: one thing a person said. A
   *     client that rendered this as "after 4 turns" for one question told somebody they had spoken
   *     four times, and correcting a single renderer would have left the others saying it. {@code
   *     Outcome} carries the whole argument and names what kept the older word elsewhere
   * @param pace how the run went at the model — tool calls, completion and reasoning tokens, time
   *     to first delta, tokens per second — or null for a run that measured nothing, which is
   *     {@link Pace#NONE}: an ending reached before any call came back. Null rather than a row of
   *     absent numbers, so a client keeping the last run's pace on screen can tell "nothing to say"
   *     from "something to say, all of it unknown"
   */
  public record OutcomeView(
      String ending,
      boolean answered,
      boolean resumable,
      String text,
      int steps,
      int modelCalls,
      String detail,
      Pace pace) {

    /**
     * @param limits the bounds on the handle this outcome came off, or {@code null} for a job that
     *     is not one agent's run. Only {@link #couldBeContinued} reads them, and it says what a
     *     null means there
     * @param conversationOrigin {@code Job#conversationOrigin} off the same handle, or {@code null}
     *     for a job with no conversation at all. {@link #couldBeContinued} is the one reader
     */
    static OutcomeView of(Outcome outcome, RunLimits limits, Origin conversationOrigin) {
      return new OutcomeView(
          outcome.ending().name(),
          outcome.answered(),
          couldBeContinued(outcome.ending(), limits, conversationOrigin),
          outcome.text(),
          outcome.steps(),
          outcome.modelCalls(),
          outcome.detail(),
          Pace.NONE.equals(outcome.pace()) ? null : outcome.pace());
    }

    /**
     * Whether a grant could actually continue this run: the offer's list and the offer's
     * arithmetic, met on one handle.
     *
     * <h2>What the arithmetic used to assume, and why it stopped being true</h2>
     *
     * <p>This was {@code continuationIsOffered(ending) && limits != null &&
     * limits.budget().remaining() > 0}, and the last conjunct was an assumption in numeric
     * clothing: <b>that the only grant on offer is turns</b>, and turns will not continue a run
     * that ran out of model calls. Under that assumption the conjunct was exactly right and {@code
     * CALL_BUDGET} fell out of the offered set for free — a run that ended at its budget has {@code
     * remaining() == 0} by construction.
     *
     * <p>A grant now raises whichever number stopped the run. So the conjunct marked the one ending
     * the new grant exists for as not resumable, the console returned before its own new branch was
     * reached, and nothing failed anywhere: the server said "do not offer", the console did not
     * offer, and the feature was simply absent. The console's {@code wire.ts} even wrote the stale
     * premise down — "the grant on offer is turns and turns will not continue a run that ran out of
     * model calls" — describing a server rule that had to change and had not.
     *
     * <h2>What it says now</h2>
     *
     * <p><b>A stop still has to be worth continuing, and what makes it worth continuing depends on
     * what the grant would raise.</b> {@code Turn.grantRaisesTheBudget} answers that, and it is the
     * server's to answer for the same reason the offered list is. An ending whose grant raises the
     * budget is continuable precisely <em>because</em> the budget is what stopped it — {@code
     * Turn.resume} applies the raise before it asks whether anything is left, so by the time that
     * check runs there is. Every other offered ending stopped for some other reason and still needs
     * spendable budget behind it, or {@code Turn.resume} would refuse the continuation this offer
     * promised.
     *
     * <p><b>{@code exhausted()} and not {@code remaining() > 0}</b>, which is the same question
     * without a subtraction. The old form threw for a lifted budget, and since {@code JobStore}
     * shares one {@code Budget} by reference down a whole delegation tree, that was every job in a
     * lifted tree — parent and children alike — 500ing on a poll.
     *
     * @param limits the bounds on the handle this outcome came off, or {@code null} for a job that
     *     is not one agent's run. A null is read as nothing to continue with rather than as no
     *     bound: a curator pass has no conversation for a grant to continue, so there is nothing to
     *     offer whatever it ended at
     * @param conversationOrigin {@code Job#conversationOrigin} off the same handle, or {@code null}
     *     for a job with no conversation at all. {@code Turn.originIsResumable} is the door's own
     *     rule and the third gate here, added after a run stopped at its budget with a {@link
     *     io.aeyer.plowshare.server.archive.Origin#SUBMISSION} conversation passed the other two
     *     and reported {@code true} for a resume {@code Turn.requireResumable} then refused by name
     *     — the arithmetic was never wrong, it was simply never asked whether this run was a
     *     person's turn to begin with
     */
    private static boolean couldBeContinued(
        Outcome.Ending ending, RunLimits limits, Origin conversationOrigin) {
      if (!Turn.continuationIsOffered(ending)
          || limits == null
          || !Turn.originIsResumable(conversationOrigin)) {
        return false;
      }
      return Turn.grantRaisesTheBudget(ending) || !limits.budget().exhausted();
    }
  }

  public static JobView of(Job job) {
    RunLimits limits = job.limits().orElse(null);
    Origin conversationOrigin = job.conversationOrigin();
    return new JobView(
        job.id(),
        job.agent(),
        job.state().name(),
        job.cancelRequested(),
        job.conversation(),
        job.outcome()
            .map(outcome -> OutcomeView.of(outcome, limits, conversationOrigin))
            .orElse(null),
        limits == null ? null : LimitsView.of(limits));
  }
}
