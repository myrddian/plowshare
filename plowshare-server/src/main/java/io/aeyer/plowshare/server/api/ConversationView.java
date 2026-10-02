package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.archive.ConversationRecord;

/**
 * A conversation as it was opened: the id to speak into, where it lives, what it
 * is called, and how far it may go.
 *
 * <p>Answers with what was actually written rather than echoing the request, the
 * rule {@code ProjectView} states for the same reason one resource over: a
 * caller that named no project is told it got the global tier, which is the
 * single thing they read this answer to learn beyond the id.
 *
 * <p><b>{@code modelCallsSpent} was deferred until something could read a
 * conversation back, and this is the discharge of that.</b> The field was left
 * out while {@code POST} was the only verb, because a conversation is opened
 * unspent and the number could then only ever be zero — a column of a value that
 * cannot vary, which reads to a client as something worth polling. The sentence
 * ended "when something wants a conversation's remaining budget it wants a read
 * endpoint, and that is where the field goes"; {@code GET /v1/conversations} is
 * that endpoint, and the field came with it. A listing that reported every
 * conversation at its opening allowance would show the one with a single call
 * left as good as new, and the allowance is what decides whether a person can go
 * on speaking into it.
 *
 * @param id what {@code RunAgentRequest.conversation} carries to make a run a
 *     turn in this conversation
 * @param project the home it was opened in, or {@code null} for the global tier
 *     — the absence of a project, spelled as an absence here too, because {@code
 *     Home} refuses to let global be a name a project could take
 * @param maxModelCalls the allowance every turn in it spends from, or {@code
 *     null} for a conversation whose allowance has no ceiling — see {@code
 *     noBudget}. <b>No turn raises it, and that is a rule about the agent rather
 *     than about the operator.</b> This sentence read "fixed at what it was
 *     opened with; no turn raises it", which was right about the party it was
 *     written against and wrong as a description of the system: nothing a model
 *     does can move this number — no tool takes one, no schema mentions one, and
 *     a budget an agent could raise would not be a budget — while a person
 *     watching a run approach it may give it more, through {@code POST
 *     /v1/jobs/&#123;id&#125;/limits}, and the row is written with what it came
 *     to. The property that made the original sentence worth writing is the one
 *     that is kept; what has gone is the restriction on the only party that was
 *     never the risk
 *
 *     <p><b>Nullable now, {@code maxTurns}'s shape for the same reason.</b> A
 *     lifted budget has no total to report, and {@code Budget.limit()} refuses
 *     to invent one for it; a caller reading the null as zero would render a
 *     conversation that can spend nothing, and one reading it as some large
 *     safe {@code int} would render the exact invented number {@code Budget}
 *     exists to prevent. So it is left out rather than substituted, and {@code
 *     noBudget} is where "no ceiling" is actually said
 * @param maxTurns how many turns each turn in this conversation may take, or
 *     {@code null} for a conversation that leaves that to the agent answering —
 *     which is the ordinary answer, and is not the same as {@code noTurnCap}
 * @param noTurnCap whether this conversation says its turns run with no cap at
 *     all. <b>Sent as its own field rather than as a number</b>, because
 *     "uncapped" is a decision somebody took and a large number is not; the two
 *     are never both true
 * @param modelCallsSpent how much of that allowance the conversation has already
 *     used, as the row records it. Zero on the answer to {@code POST}, which is
 *     true rather than uninformative: it is what "opened with its whole allowance
 *     unspent" looks like. <b>A snapshot and not a live count</b> — {@code
 *     ConversationRecord} is what the row said when it was read, so a turn
 *     running now is not in this number. Reported whether or not the allowance
 *     has a ceiling — a lifted budget still measures what it has spent, which is
 *     the one number left for a person watching one to watch
 *
 *     <p><b>Nullable, and only for a conversation that owns no allowance at
 *     all.</b> This was an {@code int} and read {@code budget == null ? 0 :
 *     budget.spent()}, which is the one thing this whole design refuses: a
 *     number nobody measured, standing where an absent one should be, and
 *     indistinguishable on the wire from a conversation that has genuinely
 *     spent nothing. The null is unreachable through either door that builds
 *     one of these — see {@link #of} — so this is not a state a client will
 *     meet; it is the state the guard beside it has always claimed to handle,
 *     said honestly instead of filled in. Note which absence it is: {@code
 *     maxModelCalls} is null for the ordinary, reachable case of a lifted
 *     conversation, and this is null only for a conversation whose allowance is
 *     not its own to report
 * @param noBudget whether this conversation owns an allowance with no ceiling at
 *     all, reported as {@code budget != null &amp;&amp; !budget.capped()}.
 *     <b>Sent as its own field rather than left to be read off {@code
 *     maxModelCalls} being null</b>, {@code noTurnCap}'s reason applied to the
 *     other knob: "no ceiling" is a decision somebody took, and a null is not
 *     enough on its own to say that rather than "this view could not price it" —
 *     which is what a null would also have to mean for a conversation that spent
 *     an allowance it did not own, were this class ever handed one. See {@link
 *     #of}
 * @param title what the first turn in it was about, as {@code
 *     ConversationStore} derived it and the row holds it, or {@code null} for a
 *     conversation nothing has named. <b>Read and never computed here</b>: one
 *     derivation in one place is the whole point of the column, and a view that
 *     fell back to some second rule would put a name on the wire that the store
 *     would disagree with the moment the conversation was spoken into.
 *
 *     <p><b>The null is sent as a null, and no word is substituted for it.</b>
 *     It is not a rare state: there was no backfill, so every conversation that
 *     existed before the column has one permanently, and so does every
 *     conversation opened and never spoken into — including the one this view
 *     is built of on the way out of {@code POST}. An {@code "Untitled"} here
 *     would be a name this server invented, and three clients each inventing
 *     their own is exactly the state that made a console show {@code
 *     cnv_3134E666E2D847AD}. What a client shows for an unnamed conversation is
 *     the client's decision, and the id it needs to make it is on the same row.
 *
 *     <p><b>Present and empty rather than absent</b>, which is what carrying it
 *     on the record rather than behind a {@code @JsonInclude} buys: nothing here
 *     configures {@code NON_NULL}, so this arrives as {@code "title": null} and
 *     a client can tell a conversation with no name from a server too old to
 *     have names. {@code TurnView.promptTokens} states the same preference for
 *     the same reason.
 */
public record ConversationView(
        String id, String project, Integer maxModelCalls, Integer modelCallsSpent,
        Integer maxTurns,
        boolean noTurnCap, boolean noBudget, String title) {

    /**
     * <b>Only ever given a {@code turn}-origin row</b> — {@code
     * ConversationController.open} writes one and {@code
     * ConversationController.list} reads only those, since {@code
     * ConversationStore.inHome} filters on origin, so a machine's log never
     * reaches this view. That is why the id and the project are read without a
     * second thought about what kind of row this is.
     *
     * <p><b>The budget itself is guarded, the way the turn cap two lines below
     * already was.</b> This used to say the guard was unnecessary because a
     * turn-origin row always owns its allowance, and that half is still true —
     * but "owns an allowance" stopped meaning "has a number" the day V31 gave a
     * turn-origin conversation a third state, an allowance with no ceiling at
     * all, and {@code Budget.limit()} throws for it rather than inventing one.
     * Asking {@code capped()} first, exactly as {@code cap.capped()} is asked
     * three lines down, is what keeps opening such a conversation from crashing
     * the request that opened it.
     *
     * <p><b>The null-budget guard beside it is not reachable through the one
     * door that calls this method today</b> — a delegated child or a curator's
     * ruling never reaches {@code list}, and nothing else calls {@code of}. It
     * is kept anyway, at the cost of one ternary, against the day that
     * invariant loosens: a conversation which spends an allowance it does not
     * own would otherwise turn a wrong caller into a null pointer exception
     * instead of into a view with nothing to say about a budget that is not its
     * own to describe.
     *
     * <p><b>And "nothing to say" is now said as nothing.</b> The spend used to
     * come out of that guard as {@code 0}, which is not nothing — it is a
     * measurement, and a wrong one, on the wire beside a real conversation that
     * has spent nothing and is indistinguishable from it. Both halves of the
     * budget are reported as absent for a row this view cannot price, which
     * costs {@code modelCallsSpent} its primitive type and is the whole of the
     * cost. Refusing the row outright was the alternative and was rejected: a
     * single unpriceable row would take down the listing every other
     * conversation is in, and the paragraph above is explicit that the point of
     * this guard is to degrade rather than to fail.
     */
    public static ConversationView of(ConversationRecord conversation) {
        TurnCap cap = conversation.turnCap();
        Budget budget = conversation.budget();
        return new ConversationView(
                conversation.id(),
                conversation.home().project(),
                budget != null && budget.capped() ? budget.limit() : null,
                budget == null ? null : budget.spent(),
                // Three states across two fields, exactly as the row holds them
                // and exactly as the request body says them. Asking a cap that
                // is not there for a number is the substitution TurnCap refuses,
                // so it is not asked.
                cap != null && cap.capped() ? cap.turns() : null,
                cap != null && !cap.capped(),
                budget != null && !budget.capped(),
                // Straight through, null included. The only writer of this
                // column is the first turn, and a view that filled the gap in
                // would be a second one.
                conversation.title());
    }
}
