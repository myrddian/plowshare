package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Objects;

/**
 * How a run ended, and what it did on the way.
 *
 * <h2>A truncated run is never dressed as an answer</h2>
 *
 * <p>This is the type that rule lives in, and it is the reason the type exists
 * at all rather than {@code run} returning a string. Excalibur returned a
 * model's own deliberation as an answer when a run ran out of turns, and a
 * failed tool call as a considered reply; a caller could not tell <em>it
 * decided</em> from <em>it stopped</em>, and neither could a person reading the
 * memory that got written from it.
 *
 * <p>So: {@link #ending} is what a caller branches on, and for every ending but
 * {@link Ending#ANSWERED} <b>{@link #text} is not the model's last prose.</b>
 * {@link JobRuntime} constructs those texts itself and never from {@code
 * Completion.content()}, and {@code no_ending_but_answered_carries_the_model_s_last_prose}
 * is what holds that line: it runs every stopping ending with the model saying
 * one distinctive sentence, and fails if any of them repeats it. (It said "the
 * four" when there were four and stayed at four through two more — a count in
 * prose about a set that grows, which is the drift this file has now met three
 * times in one commit.)
 *
 * @param ending how the run ended. Never null.
 * @param text what to show whoever asked. For {@link Ending#ANSWERED} out of
 *     {@link JobRuntime} this is the model's answer verbatim, empty string
 *     included — a model that stops on its first token has answered with
 *     nothing, which is a decision and not a failure, and manufacturing prose
 *     for it would be this rule's mirror image. For every other ending it is a
 *     sentence this runtime wrote, naming the ending and listing the tools the
 *     run called in order. Never null and, for a stopping ending, never blank:
 *     an empty string reads to a human as a bug, while the tool trail says how
 *     far the run got. Excalibur's {@code _partial_text}, for the same reason.
 *     <p><b>"the model's answer verbatim" is about a run of one agent, and this
 *     type has a second producer.</b> {@code agents.curator.Curator} returns an
 *     {@link Outcome} for a whole pass — many judgements, each of them a run —
 *     and its {@code ANSWERED} text is the pass's own account of what it
 *     promoted and proposed, never a judge's prose. That does not weaken the
 *     rule this type exists for, which is that a run that <em>stopped</em> must
 *     not read as one that decided: the curator's endings are as distinct as
 *     the runtime's, and none of its stopping texts is built out of anything a
 *     model said either.
 * @param steps how many <b>steps</b> completed — a step being one model call
 *     plus every tool result it asked for. A call that failed did not complete a
 *     step and is not counted here; see {@link #modelCalls}, which does count
 *     it.
 *
 *     <p><b>A step is not a turn, and this field was called {@code turns}.</b>
 *     The word means something else everywhere else in this system: a
 *     <em>turn</em> is one thing a person said and everything that answered it,
 *     which is what {@code entries.turn_ordinal} and the {@code turns} table
 *     hold and what {@code agents.Turn} orchestrates. This number counts the
 *     iterations of one agent's loop <em>inside</em> one of those. The two meet
 *     in text a person reads — a client rendering "ANSWERED after 4 turns" for a
 *     single question was telling somebody they had spoken four times — so they
 *     are two words now, decided here rather than in a renderer, because {@code
 *     Outcome} and the responses built from it are what the console, the CLI and
 *     a foreign harness over MCP all read.
 *
 *     <p><b>What kept the old word is named rather than left to be discovered.</b>
 *     {@code Ending.TURN_CAP} is stored as its own name and {@code
 *     turns_ending_is_known} in {@code V7__turns.sql} holds the closed list, so
 *     renaming that constant is a migration; {@code max-turns} is in every agent
 *     definition, so renaming the key is a breaking config change; and {@code
 *     conversations.turn_cap} is a shipped column. So {@link TurnCap} keeps its
 *     name while <em>what it counts</em> is said in steps — see its {@code
 *     describe}.
 * @param modelCalls how many model calls this run spent, including one that
 *     failed. The budget is claimed before the call is made, so a run that dies
 *     mid-call has spent that call and this number says so. It counts this job
 *     only: a shared {@link Budget} knows what a whole tree spent, and Task 5 is
 *     where that distinction gets a caller.
 * @param detail what a caller cannot get from {@code ending} and {@code text}.
 *     Empty string for "nothing to add", never null. For {@link
 *     Ending#UNAVAILABLE} and {@link Ending#SESSION_GONE} it names the failure —
 *     the exception type, its message, and the tool if a tool raised it. For
 *     {@link Ending#ANSWERED} it is empty unless the model's own generation was
 *     cut off, which is the one truncation these endings cannot express; see
 *     {@link JobRuntime} for why no constant was added for it. (That paragraph
 *     read "these six endings" and "no seventh constant" until a seventh
 *     arrived and was not it. The argument is unchanged and the counting was
 *     never part of it, which is why the numbers are gone rather than
 *     incremented.)
 * @param pace how the run went at the model — tool calls, tokens, time to first
 *     delta, speed. {@link Pace#NONE} for an outcome no loop measured; never null
 * @param requested whether a harness tool asked for this ending through the run's
 *     {@link TurnEnd}, so that {@code text} is that tool's sentence rather than
 *     anything the model said. <b>Needed because {@code ANSWERED} has two sources
 *     that a reader of {@code ending} alone cannot tell apart</b>: a model's last
 *     prose, and a tool's — {@code orchestration_finish}'s result, or rule 1's
 *     "is working; its result will be delivered to you" (spec 2026-09-27 §2).
 *     {@code Orchestrations.route} finishes a conductor that ended in prose with
 *     every stage done, on the strength of that prose; handed rule 1's sentence
 *     the same way, it finished a tree whose last child had just ended with the
 *     harness's own words as its result (final review, 2026-09-27). Kept off the
 *     wire: it is a fact for the harness's own routing, and the clients that read
 *     an outcome already have the ending and the text.
 */
public record Outcome(
        Ending ending, String text, int steps, int modelCalls, String detail, Pace pace,
        @JsonIgnore boolean requested) {

    /** An ending the runtime or a model reached — not one a tool asked for. */
    public Outcome(Ending ending, String text, int steps, int modelCalls, String detail,
            Pace pace) {
        this(ending, text, steps, modelCalls, detail, pace, false);
    }

    /** An outcome with nothing measured about its pace — every ending the runtime
     *  did not reach through a model call, and every caller that is not the loop. */
    public Outcome(Ending ending, String text, int steps, int modelCalls, String detail) {
        this(ending, text, steps, modelCalls, detail, Pace.NONE);
    }

    /**
     * The ways a run can end, each distinct because a caller has to be able to
     * tell them apart.
     *
     * <p>(It said "the seven" and there are now eight. The count is gone rather
     * than incremented, for the reason the paragraphs below have twice had to
     * record: a number in prose about a set that grows is a sentence whose only
     * job is to go stale.)
     *
     * <p>Distinct in the enum and distinct in {@link Outcome#text}: two endings
     * that produced the same sentence would be one ending as far as anybody
     * reading a job's result is concerned.
     *
     * <p><b>A constant added here is not added here alone.</b> These places in
     * Java, none of which is a compile error when this set grows:
     *
     * <ul>
     *   <li>{@code AgentRunTool.propagates} — whether a child ending kills its
     *       parent;
     *   <li>{@code AgentRunTool.SubAgentFailed.sentence()} — which words a
     *       parent reports a dead child with. <b>Omitted from this list when it
     *       was first written</b>, which is the one-owner rule failing at the
     *       owner: it became a branch in the same commit that added the seventh
     *       constant, and an eighth would silently inherit the endpoint wording;
     *   <li>{@code Curator}'s switch over this enum — whether a pass goes on to
     *       the next candidate. A switch <em>statement</em>, so a missing label
     *       falls through in silence;
     *   <li>{@code Turn.requireContinuable} — which words a grant that cannot
     *       continue this ending is refused with. Its default arm speaks of a run
     *       that died mid-call, which is wrong for most new constants;
     *   <li>{@code Orchestrations.routeFrom} and {@code routeWhileWaiting} — what
     *       a conductor's turn ending this way does to its run. Their default
     *       fails the run with the ending's own text.
     * </ul>
     *
     * <p><b>And one place that is not Java at all, which this list did not have
     * when it was written.</b> {@code V7__turns.sql} stores a turn's ending as
     * {@code name()} and held {@code turns_ending_is_known} over the constants
     * that existed then, for the reason V5 gives about {@code
     * memory_reasons.kind}: one this table does not know is a row {@code
     * Ending.valueOf} refuses to read back, so it would be written successfully
     * and be unreadable for ever. A constant added here therefore needs a
     * migration of its own — Flyway checksums a shipped file, so V7 itself
     * cannot be edited once it is out — and {@code
     * TurnStoreTest.every_ending_this_server_can_reach_is_a_turn_this_table_holds}
     * is what fails when one arrives without it, rather than the first
     * conversation that reaches it.
     *
     * <p><b>{@link #STUCK} is the constant that paid that price</b>, and {@code
     * V9__stuck_ending.sql} is the migration: a {@code CHECK} cannot be added to
     * without being dropped and written out again, so that file restates the
     * whole list. <b>Two lists of the same strings, in two languages, that
     * nothing holds together</b> — a compile error is not available across
     * that seam, and the test named above is the only thing standing in for
     * one. Said here rather than made into a scheme: generating the constraint
     * from the enum would put a build step between a schema and its own file,
     * which is a larger thing than the check it would automate.
     *
     * <p>And one further place outside Java, which is not a correctness
     * requirement: {@code plowshare-console}'s {@code ENDINGS} map and {@code
     * plowshare-tui}'s {@code describeEnding} turn a constant into a sentence
     * for a person. Each is deliberately not exhaustive
     * — an unknown name renders as itself — so a constant that never reaches it
     * degrades rather than breaks.
     *
     * <p>And one test, which is not a place the constant goes but a place it has
     * to be added or the rule above stops being held: {@code
     * no_ending_but_answered_carries_the_model_s_last_prose}.
     */
    public enum Ending {

        /** The model stopped asking for tools and said something. The only
         *  ending whose {@code text} is the model's own words. */
        ANSWERED,

        /**
         * The run used every step it was allowed without answering.
         *
         * <p><b>The constant keeps the older word</b>, and it is not free to
         * lose it: {@code turns_ending_is_known} in {@code V7__turns.sql} holds
         * the closed list of endings and stores each as its own {@code name()},
         * so renaming this one is a migration and a row an older build cannot
         * read back. What it bounds is steps, which is what {@link
         * TurnCap#describe} now says out loud.
         *
         * <p><b>A runaway guard, and reaching it means the guard was the
         * binding constraint — which is a fact about the configuration rather
         * than about the work.</b> The measurement this constant used to cite
         * said runs against qwen3.5-9b terminate naturally in three steps, so
         * sixteen was a generous backstop; it stopped being one when the
         * workload became file reading, paging and delegation, and on 2026-09-02
         * a single find-a-heading-and-read-it task spent all sixteen and
         * answered nothing. The cap is now a hundred on the two agents that
         * loop, and {@code interlocutor.md} carries that argument.
         *
         * <p>The cap is no longer only what a definition allows: a conversation
         * or a single run may set its own, and an operator may move it while the
         * run is going. {@link TurnCap} is the whole of that. What this ending
         * says is unchanged — the run reached whatever ceiling was over it —
         * and the fix for it is the same one it always was: give it more room,
         * or find out why it needed so much.
         */
        TURN_CAP,

        /**
         * The run — or the tree it belongs to — used every model call the shared
         * {@link Budget} allows.
         *
         * <p>Distinct from {@link #TURN_CAP} because the fix is different: one
         * is an agent that needs more room, the other is a tree that is spending
         * more than it was given.
         *
         * <p><b>That was true in principle and indistinguishable in practice
         * until this slice, and the reason is worth keeping.</b> Both shipped
         * looping agents carried {@code max-turns: 16} and {@code
         * max-model-calls: 16}, so the two fired at the same moment for the same
         * run and the distinction the paragraph above draws was one no operator
         * could act on. They now do different jobs at different numbers: the
         * budget is the cost bound, set per conversation by whoever is paying,
         * and the cap is a runaway guard nobody normally touches.
         */
        CALL_BUDGET,

        /** Somebody asked for the run to stop and it did, at its next turn
         *  boundary. */
        CANCELLED,

        /**
         * The run kept asking for one call, with the same arguments, and was
         * stopped rather than left to spend everything it had finding out that
         * the answer was not going to change.
         *
         * <p><b>Distinct from {@link #TURN_CAP} because the two say opposite
         * things about the same run.</b> A capped run was doing something and
         * ran out of room; this one had room and was not doing anything with it.
         * One is answered by raising a limit and the other is not — raising a
         * limit for a run that ends here buys more of exactly what already
         * failed — and an operator with one word for both would learn nothing
         * from either.
         *
         * <p><b>It is the constant that makes a large or absent turn cap
         * responsible</b>, and it landed in the change that raised the cap
         * rather than after it. {@code JobRuntime.Repeats} is the whole
         * mechanism: consecutive identical calls, name and arguments byte for
         * byte, with two advisory notes before this. <b>Paging is not
         * repetition</b> — a windowed read with an advancing offset differs in
         * its arguments and does not count — and {@code
         * a_read_that_pages_forward_is_never_a_repeat} is what holds that.
         *
         * <p><b>Nothing about it is said to the model when it fires.</b> The run
         * ends; there is no further turn to say anything in. The one reader of
         * {@link Outcome#text} here is whoever asked — or a parent agent, which
         * reads a child's stopping outcome through {@code AgentRunTool.render}
         * like any other.
         */
        STUCK,

        /** Something the run depends on could not be reached: the model
         *  endpoint, or an endpoint a tool needs. Not a mistake the model made
         *  and not one it can correct, which is the whole reason this is an
         *  ending rather than a tool result. */
        UNAVAILABLE,

        /** A delegated child job failed and its parent could not go on. Task 5
         *  is what produces this; it is declared here because the set of endings
         *  is the contract callers switch on, and a set that grows later is one
         *  every switch has already been written without. */
        SUB_AGENT_FAILED,

        /**
         * The client session whose files this run was working in disconnected.
         *
         * <p>Distinct from {@link #UNAVAILABLE} because it is a different fact
         * about the run, not a different severity of the same one. {@code
         * UNAVAILABLE} sends an operator to look at something that is supposed
         * to be up — an endpoint, a database, a directory on this server — and
         * every one of those is somebody's to restart. This one says a person's
         * machine went away, which is nobody's fault and nothing to repair; and
         * <b>nobody is waiting for the answer</b>.
         *
         * <p><b>That last clause was hedged until a session existed, and the
         * hedge is now discharged rather than deleted.</b> It read "<em>where</em>
         * the client that opened the channel is also the party that asked for the
         * run", and slice 3b recorded that nothing checked it. Something does
         * now, by construction: a run's remote provider is built by {@code
         * AgentsConfig.runProviders} out of the session id the job was
         * <em>submitted</em> under, and only when that session holds {@link
         * io.aeyer.plowshare.server.session.Role#FILE_PROVIDER}. So the socket
         * that can raise this ending for a run is the one attached to that run's
         * own session and can be no other — there is one slot per role, the
         * provider is bound to the id at the moment the run asks, and no path
         * exists by which one client's channel serves another client's job.
         * {@code RemoteWiringTest.the_remote_provider_is_bound_to_the_session_the_run_was_submitted_under}
         * is what holds it, against a registry in which two sessions hold a
         * provider.
         *
         * <p><b>One honest qualifier survives, and slice 4 changed what it
         * says.</b> A session id is still not a secret — {@code
         * SessionRegistry.attach} is {@code computeIfAbsent} and {@code find}
         * asks only that an id be non-blank — so anything that knows an id can
         * submit under it and attach to it. What has changed is who that
         * anything can be: {@code AuthFilter} gates every {@code /v1} path
         * including both sockets, so reaching either takes the operator's access
         * token. <b>Holding it means being the operator, and the operator may
         * attach to any session by name.</b> That is correct for a single-user
         * system and it is the honest description of what was already true. So
         * what is established is that the channel and the run are the same
         * <em>session</em>, which is the strongest identity this system has, and
         * the authentication mechanism the spec used to say did not exist now
         * does.
         *
         * <p><b>And a socket closing is not what produces this.</b> A run that
         * never needed the client's disk is untouched by that client going away:
         * the provider set is re-asked per routing call, so such a run simply
         * has a smaller one for the rest of its life. This ending comes from a
         * <em>file request that cannot be served</em>, and from nothing else.
         *
         * <p><b>Contentless, like every ending but {@link #ANSWERED}.</b> "Partial
         * output is kept
         * rather than discarded" looks like it argues for the opposite and does
         * not: what is kept is what the run <em>produced</em>, and {@link
         * JobStore}'s class javadoc owns that rule — a job is a handle on a run
         * and not a container for its output. A file written before the socket
         * closed is on that disk; a memory written is in Postgres. What is not
         * kept is half-finished prose dressed as an answer, which is what this
         * whole type exists for. {@code
         * what_a_run_wrote_before_the_session_died_is_still_on_the_clients_disk}
         * is the instrument, over a real socket that really closed.
         *
         * <p>Produced from {@code files.SessionGoneException}, which is where
         * the line between a client that went away and a client that is merely
         * slow is drawn and argued.
         */
        SESSION_GONE,

        /**
         * The conductor of an orchestration asked the question its run cannot
         * go on without, and its turn ended to wait for the answer.
         *
         * <p>Not a failure — the conductor decided, correctly, that it needed
         * something from outside before it could go on — and not continuable:
         * {@link io.aeyer.plowshare.server.agents.Turn}'s {@code CONTINUABLE}
         * does not name it, because granting more allowance to a turn that
         * ended here would not make the answer arrive any sooner. The next
         * turn <em>is</em> the answer, spoken into the conductor's conversation
         * like any other, once one comes back.
         *
         * <p>Reached from a conductor's own tool, or from {@code run}'s approval
         * gate. {@code AgentRunTool} propagates the latter through each parent's
         * {@link TurnEnd}; it is deliberately not a {@code SubAgentFailed}.
         */
        AWAITING,

        /**
         * The run kept writing tool calls as text instead of making them, and was stopped (spec
         * 2026-09-28-call-failures §4).
         *
         * <p>A <em>call failure</em>, not a tool failure: the calling procedure broke, not a
         * tool. Each call written as text -- a call spelled out, or a tool's arguments alone --
         * is held and the model warned; each in a row takes one of a per-turn allowance of three,
         * and a real call restores it. A failure that finds the allowance empty ends the turn here.
         * One on the run's last step or last budgeted call is withheld too, but ends the turn with
         * the constraint that stopped it -- TURN_CAP or CALL_BUDGET -- since one slip there is not a
         * run that kept writing calls (final review, 2026-09-28). The written call is never
         * delivered: a tool call is the model's business and never the person's reading.
         *
         * <p>Distinct from {@link #STUCK}, which is a run repeating calls it did make; this is a
         * run that made none. Not propagated to a parent, for STUCK's reason, and not
         * continuable: allowance was not the constraint.
         */
        CALL_FAILURES
    }

    public Outcome {
        Objects.requireNonNull(ending, "ending");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(detail, "detail");
        // Null normalised rather than refused: an outcome read back from before
        // pace existed has none, and that is Pace.NONE's meaning exactly.
        pace = pace == null ? Pace.NONE : pace;
        if (steps < 0 || modelCalls < 0) {
            throw new IllegalArgumentException(
                    "a run cannot have taken " + steps + " steps and made " + modelCalls
                            + " model calls");
        }
    }

    /** Whether the model reached a conclusion, as opposed to the run stopping
     *  around it. One method so that no caller writes {@code ending ==
     *  ANSWERED} and later forgets to revisit it when a further ending that also
     *  counts as an answer appears. (It said "a seventh ending" until the
     *  seventh landed and was {@link Ending#SESSION_GONE}, which is a run that
     *  stopped — so the sentence was about a number that has since been used
     *  rather than about the rule it is making.) */
    /** The same outcome, with how the run went at the model. */
    public Outcome paced(Pace measured) {
        return new Outcome(ending, text, steps, modelCalls, detail, measured, requested);
    }

    public boolean answered() {
        return ending == Ending.ANSWERED;
    }
}
