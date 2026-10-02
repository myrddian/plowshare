package io.aeyer.plowshare.server.api;

/**
 * The body of {@code POST /v1/conversations}.
 *
 * @param project the home this conversation is held in, and every turn in it
 *     runs in. {@code null} means global, matching {@code Home}'s own rule and
 *     {@code RequestedHome.in} — a conversation opened without naming
 *     a project is the ordinary shape rather than a degenerate one, which is
 *     what {@code V6__conversations.sql} argues at length about the nullable
 *     column behind it. A <em>blank</em> one is refused rather than folded into
 *     global: sending {@code ""} says "here is my project" and names nothing,
 *     and reading that as global would put the conversation into the tier every
 *     agent everywhere reads
 * @param maxModelCalls the whole conversation's budget, across every turn in it
 *     and everything those turns delegate to, or {@code null} to open it at
 *     {@code plowshare.conversations.default-budget}.
 *
 *     <p><b>This said "required, unlike {@code CurateRequest.maxModelCalls}",
 *     and the asymmetry it drew is still real — what changed is what follows
 *     from it.</b> A curator pass has a knowable workload — a candidate set, two
 *     model calls a ruling — so {@code curator-budget} is arithmetic; how much a
 *     person is going to say has no such arithmetic behind it, and this record
 *     therefore refused a default on the grounds that "a configured default here
 *     would be a number with no method". <b>That is an argument against the
 *     server computing one, and it is kept: nothing computes one.</b> What it
 *     was read as saying, and should not have been, is that the number must
 *     therefore be asked for — which put a model-call field in front of a person
 *     opening a console conversation, asking them for the very number nobody has
 *     a method for. {@link ConversationsProperties} is where an operator states
 *     it instead: once, for a deployment, beside every other cost bound this
 *     server takes. A body that names a number is honoured exactly as before,
 *     and {@code 0} is still refused rather than folded into the default.
 *
 *     <p>No turn raises a conversation's total — that is a rule about the agent,
 *     and it is why a budget is a budget; a person watching a run may give it
 *     more, through {@code POST /v1/jobs/&#123;id&#125;/limits} or {@code
 *     ResumeRunRequest.maxModelCalls}, and the row is written with what it came
 *     to. The party that cannot revisit it is still the one it is there to
 *     bound
 * @param maxTurns how many turns each turn in this conversation may take, or
 *     {@code null} for a conversation that leaves it to the agent answering.
 *     <b>Optional, and it always was — where the budget only became so.</b> The
 *     two absences are answered at different levels and that is the whole of the
 *     difference now: a turn cap is a runaway guard every agent's own file
 *     already names a number for, so the level below this one answers, while a
 *     budget is what a conversation costs and there is no per-agent number that
 *     means anything for a conversation several agents may answer turns in — so
 *     the level above answers, in the operator's configuration. A conversation
 *     that says nothing here is the ordinary one
 * @param noTurnCap whether the turns in this conversation are to run with no cap
 *     at all. <b>A decision and not a large number</b> — {@code
 *     RequestedTurnCap} refuses a body that sends this beside {@code maxTurns},
 *     rather than choosing between them. This is the level to say it at for an
 *     automated conversation that will be spoken into many times; what bounds
 *     its cost is still the budget, and an automated caller is exactly the one
 *     that should go on stating that number rather than taking the operator's
 * @param noBudget whether this conversation owns an allowance with no ceiling at
 *     all, {@code noTurnCap}'s decision on the other knob. <b>A body naming
 *     both this and {@code maxModelCalls} is refused rather than resolved</b>,
 *     in {@code RequestedTurnCap.in}'s own voice: two answers to one question,
 *     where taking either silently is how a limit nobody chose gets applied.
 *     <b>Opt-in per conversation, and the deployment default is unmoved by
 *     it</b> — a body that names neither this nor {@code maxModelCalls} still
 *     takes {@code plowshare.conversations.default-budget} exactly as before,
 *     so an operator's existing number goes on meaning what it always meant.
 *     This is a caller stating something extra, not a caller opting out of the
 *     operator's policy
 */
public record OpenConversationRequest(
        String project, Integer maxModelCalls, Integer maxTurns, Boolean noTurnCap,
        Boolean noBudget) {}
