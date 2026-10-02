package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.Redemption;
import io.aeyer.plowshare.server.archive.StoredResults;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a conversation puts in front of one run, and the one number it takes
 * back.
 *
 * <h2>Why the two directions are one seam</h2>
 *
 * <p>They are the same relationship read each way. A turn is a job that opens
 * with everything said before it, and the only thing the conversation needs back
 * from that job is how much the model charged for it — {@code
 * usage.prompt_tokens}, counted by the model's own tokenizer, which is what
 * decides whether the <em>next</em> turn still fits. Two parameters would have
 * put the same conversation into {@link JobRuntime#run} twice under two names.
 *
 * <p><b>{@link JobRuntime} knows nothing about conversations through this
 * interface and gains nothing it can be tempted to use.</b> It asks for
 * messages, appends its own, and reports an integer. Everything about which
 * turns those messages came from, whether some of them were summarised, and what
 * is done with the number is on the other side of the seam, in {@code
 * Compaction} and {@code Turn}.
 *
 * <h2>Why not the ledger, and why not {@link Outcome}</h2>
 *
 * <p>Three routes could carry a prompt-token count out of a run, and this is the
 * third. The other two were measured and rejected, and the reasons are recorded
 * here because the alternatives keep looking cheaper than they are:
 *
 * <ul>
 *   <li><b>Widening {@link Outcome}</b> touches 21 construction sites in 6 files
 *       — 5 of them in {@code main} — and, worse, adds a field that is honest
 *       for one of {@code Outcome}'s two producers and meaningless for the
 *       other: {@code agents.curator.Curator} returns an {@code Outcome} for a
 *       whole pass of many runs, and there is no single prompt whose cost that
 *       would be. A field that is always null for a producer is a field every
 *       reader has to learn the exception for.
 *   <li><b>Cumulative accounting</b> now records every actual attempt, including failures.
 *       It answers consumed usage across an execution tree; this seam still supplies the last
 *       prompt observation used for context occupancy and compaction.</li>
 * </ul>
 *
 * <p>This seam reads the {@code Completion}, which is what the plan asked for.
 * <b>It is plumbing and not a second reading path</b>: nothing here consults the
 * ledger, and the number that arrives at {@link #promptMeasured(int)} came off
 * the same {@code usage} object {@code LlmDispatcher} hands the ledger, one
 * frame earlier.
 *
 * <p><b>{@code JobWatch} is untouched, and that is the point of not using
 * it.</b> Slice 3d's guarantee is that a {@code JobEvent} carries no payload
 * <em>by signature</em>; nothing on this interface becomes an event, no method
 * here is reachable from {@code JobEvents}, and the containment argument
 * therefore survives unexamined rather than surviving because somebody checked
 * an integer was harmless. A widened {@code JobWatch.ended} would have been the
 * version that needed the argument re-made.
 *
 * <p>Implementations are called from the job's own virtual thread, one run at a
 * time, and may block: {@link #before()} reads a conversation's log out of
 * Postgres.
 */
public interface Transcript {

    /**
     * A run in no conversation: nothing before it, and nothing listening.
     *
     * <h2>What this used to be, and the two callers it has left</h2>
     *
     * <p>This javadoc read "every caller of {@link JobRuntime#run} but a turn —
     * a delegated child, a curator's ruling, a plain submission, a test — and
     * the same shape {@code JobWatch.UNWATCHED} takes for the same reason.
     * Nobody is keeping a transcript is an ordinary state of a correct run."
     * <b>That was true before entries existed and is not true now.</b> Measured
     * in this code, what it cost every run that took it was: no entries, so no
     * trajectory; no prompt measurement, so no compaction at all; no stored
     * results, so {@code result_read} meant nothing there; and no log to resume
     * from. A {@code code_reviewer} run left no trace of what it did — its
     * caller's log held the {@code agent_run} call and the result, and nothing
     * in between. That is not an ordinary state of a correct run; it is a run
     * this server cannot answer any question about afterwards.
     *
     * <p>So every run has a conversation now. A delegated child gets one through
     * {@link #delegate}, a curator's ruling and a submission get one where they
     * are started, and this constant has exactly two callers left:
     *
     * <ul>
     *   <li><b>{@code JobRuntime.schemasOfferedTo}</b>, which builds a run's
     *       tools in order to read their schemas and runs none of them. It is a
     *       read endpoint pricing an agent's fixed prefix, so there is genuinely
     *       no conversation and nothing to record — the one production site
     *       where the old sentence is still exactly right.
     *   <li><b>fixtures</b>, which is said out loud rather than disguised. A
     *       test that exercises the turn loop and asserts nothing about a log
     *       should not have to stand up a database to run, and this is what
     *       {@code JobWatch.UNWATCHED} is for one seam over.
     * </ul>
     *
     * <p><b>It is not deprecated and it is not private</b>, because both callers
     * are legitimate and a run genuinely in no conversation is representable.
     * What has changed is that no <em>production path that runs an agent</em>
     * takes it any more, and a new one that did would be reintroducing the
     * defect above rather than choosing a lighter option.
     */
    Transcript NONE = new Transcript() {

        @Override
        public List<ChatMessage> before() {
            return List.of();
        }

        @Override
        public void promptMeasured(int promptTokens) {
            // Nothing is counting. A log line here would be one per model call
            // forever, for every run in the server.
        }
    };

    /** Accounting metadata for this execution; legacy only for unwired/disabled fixtures. */
    default UsageAttribution usage() { return UsageAttribution.LEGACY; }

    /** Attach a validated immutable snapshot to this turn, including its later async fold. */
    default void accounted(UsageAttribution owner) { }

    /** Immutable parent execution captured by a delegation's caller. */
    default UsageAttribution parentUsage() { return UsageAttribution.LEGACY; }

    /** A resumed run's prior turn, allowing its execution identity to survive the new turn ordinal. */
    default Integer continuingTurn() { return null; }

    /**
     * Everything said in this conversation before this utterance, as messages.
     *
     * <p>Asked once, at the start of a run, and placed before this turn's
     * utterance. Never null; empty for the first turn of a conversation and for
     * a run in none.
     *
     * <p><b>This said "placed between the agent's system prompt and this turn's
     * utterance", and the only implementation that returns anything falsifies
     * it.</b> {@code Compaction} introduces a seam as a {@code SYSTEM} message,
     * and a system message is never placed between anything: {@code
     * JobRuntime.opening} lifts it to the front and merges it with the agent's
     * own prompt, because two system messages is a request {@code qwen3.5-9b}
     * refuses outright and {@code ChatRequest} now refuses first. So an
     * implementation may return a {@code SYSTEM} message and should expect it at
     * index zero rather than where it put it; everything else keeps the order it
     * was given in.
     *
     * <p><b>A method and not a list handed in at submission time</b>, because
     * what a turn opens with is what the conversation held when the run
     * started, and a list built when the utterance was posted would be one
     * taken before whatever finished in between. Reading it on the job's own
     * thread is also what keeps the read off the thread of whoever posted the
     * utterance.
     *
     * <p><b>A compaction used to happen here and no longer does.</b> It was the
     * reason this method was allowed to block, and it made one person's
     * question cost two model calls end to end; {@code Compaction} takes the
     * fold when a turn ends instead, on a thread nobody waits for. What is left
     * on this path is reads, so an implementation that makes a model call here
     * is one that has regressed.
     *
     * <p><b>It must not throw.</b> A run is not worth losing over a history that
     * could not be assembled, and an exception here would reach {@code
     * JobStore}'s last-resort catch, which reports zero turns and zero model
     * calls for a run that has not made any yet but whose budget row still has
     * to be written back. An implementation that cannot read its own history
     * says so in the log and answers with what it has.
     */
    List<ChatMessage> before();

    /**
     * One thing that happened in this run, for the conversation's log.
     *
     * <p>Called for every message {@link JobRuntime} appends to the history it
     * is sending, and for the utterance that opened the run — so what a model
     * can be shown is exactly what has been recorded, which is the whole
     * property this log exists to have. It is <b>not</b> called for the history
     * the run opened with: that came out of the log already, and recording it
     * again would double every turn on every turn.
     *
     * <p><b>It must not throw</b>, on {@link #before()}'s terms exactly and for
     * a sharper version of its reason. This is called from inside the turn loop,
     * once per message, so an exception here would end a run at {@code
     * UNAVAILABLE} — the runtime's wide {@code catch} around the model call does
     * not cover it, and {@code JobStore}'s last-resort one reports zero turns and
     * zero model calls for a run that made several. A run is not worth losing
     * over a row that could not be written; an implementation that cannot record
     * says so in the log and carries on.
     *
     * <p><b>The default does nothing</b>, which is the honest behaviour for a
     * run in no conversation — {@link #NONE} and every fixture — and is why this
     * is a {@code default} rather than an abstract method. Nothing is counting,
     * and a log line here would be one per message forever, for every run in the
     * server.
     *
     * @param entry what happened, in the shape its kind allows. Never null
     */
    default void record(LoggedEntry entry) {
        // Nobody is keeping a log. See above.
    }

    /**
     * The conversation this run is writing into, or {@code null} for a run in
     * none.
     *
     * <h2>Why this is not {@link #spokenIn}</h2>
     *
     * <p>{@link #spokenIn} answers a conversation <em>and a turn ordinal</em>,
     * and that second half is what makes it the wrong method for this: settling
     * the ordinal the first time it is asked reads {@code turns} and can throw,
     * so {@code Deliberation} — its one caller — is only safe to ask it
     * <em>after</em> a run has already paid for several model calls, with a
     * result worth losing the citation rather than the answer over. {@code
     * JobStore} asks before a run has made any: {@link Compaction#logFor} opens the
     * conversation and hands back a transcript in the same moment, and the id
     * this method answers is that transcript's own field, set once at
     * construction and never re-read from a row. No database call is on this
     * path and nothing here can throw.
     *
     * <p><b>Known at construction and never mutated</b>, which is what makes it
     * safe to call the instant a run is registered rather than only once it has
     * finished. {@code Job#conversation} is the one production reader, and its
     * own javadoc carries the rest of the argument: a run that stops needs this
     * id available from the moment it starts, because a person may ask to
     * continue it before the run's own conversation view has answered anything
     * back.
     *
     * <p><b>The default is {@code null} and every implementation but {@link
     * Compaction.TurnTranscript} and {@link Compaction.ResumedTranscript} keeps
     * it</b>, additive on {@link #spokenIn}'s terms exactly: {@link #NONE},
     * every fixture and every curator's ruling answer the same nothing they
     * always did, honestly, because there is nothing to name.
     *
     * @return the conversation's id, or {@code null}
     */
    default String conversationId() {
        return null;
    }

    /**
     * Who spoke the utterance this run opens with, which {@link JobRuntime} records on it.
     *
     * <p><b>The default is {@code null}</b> — nobody said — which a reader of the log takes as a
     * person, exactly as it takes every utterance written before speakers were recorded. The
     * turn's door says who it is: {@code Turn}'s person path, its harness paths, and every
     * {@code Compaction.logFor} run the harness opens.
     */
    default Speaker speaker() {
        return null;
    }

    /**
     * The log's fixed opening: what its {@code log.open} hooks added when it opened, sent after the
     * agent's prompt in every request of the log, byte for byte (spec
     * 2026-09-28-hooks-reach-the-log decision 9).
     *
     * <p><b>The default is the empty string</b>: a run in no log, and every fixture, has none.
     * <b>It must not throw</b>, on {@link #before()}'s terms.
     */
    default String opening() {
        return "";
    }

    /**
     * The origin of the log this run writes to — what an in-turn log stage ({@code stage.*},
     * {@code approval.pre}) is filtered by (spec 2026-09-28-hooks-reach-the-log §2.6).
     *
     * <p><b>The default is {@code null}</b>: a run in no log, and every fixture, has none, and a
     * hook that names origins does not fire for it.
     */
    default Origin origin() {
        return null;
    }

    /**
     * Where this run is being written down, or {@code null} for a run in no
     * conversation.
     *
     * <h2>One method for two numbers, because the schema says they are one
     * fact</h2>
     *
     * <p>{@code citations_a_turn_is_in_a_conversation} is {@code
     * (conversation_id IS NULL) = (turn_ordinal IS NULL)} — a turn is in a
     * conversation or there is neither. Two accessors would let a caller hold
     * one and not the other and discover that at the insert; one nullable
     * record is the same fact in the shape Postgres already refuses the
     * alternatives to.
     *
     * <h2>Why anything needs to ask</h2>
     *
     * <p>Nearly nothing does. A run's citations are written by {@code
     * Compaction.TurnTranscript.closed}, which holds both numbers already and
     * hands them to {@link Citing} — so the ordinary path never asks and this
     * method exists for the one caller that cannot use that path: {@code
     * documents.Deliberation}. Its synthesiser declares {@code tools: []}, so
     * {@link AgentDefinition#canCite()} is false and {@code Citing} correctly
     * writes nothing — the corpus was never granted to that agent, the
     * orchestrator handed it the passages. The deliberation therefore records
     * its own citations, and only the ones whose quotation it checked against
     * the paragraph, which is a narrower rule than reading uuids out of prose.
     * What it would otherwise lose is the conversation those citations were
     * spoken in, leaving {@code CitationStore.madeIn} unable to find them and
     * every row claiming to be from a run started on its own behalf.
     *
     * <p><b>The default is {@code null} and every implementation but one keeps
     * it</b>, which is what makes this additive: {@link #NONE}, every fixture
     * and every delegated child answer the same thing they always did.
     *
     * <p><b>It must not throw</b>, on {@link #record}'s terms and for a sharper
     * version of its reason. This is asked <em>after</em> a run has finished, by
     * a caller holding an answer several model calls have already been paid for,
     * so an exception here would lose the answer to a row that could not be
     * read. An implementation that cannot say which turn this is answers {@code
     * null} — a citation filed with no conversation is harder to find, and that
     * is a smaller loss than the answer.
     */
    default Spoken spokenIn() {
        return null;
    }

    /**
     * A conversation and which turn of it.
     *
     * @param conversationId the row, never null
     * @param turnOrdinal which turn, counted from one
     */
    record Spoken(String conversationId, int turnOrdinal) {

        public Spoken {
            if (conversationId == null || conversationId.isBlank()) {
                throw new IllegalArgumentException(
                        "a turn is in a conversation or there is neither; this one names no"
                                + " conversation");
            }
            if (turnOrdinal < 1) {
                throw new IllegalArgumentException(
                        "a turn is counted from one; this one is " + turnOrdinal);
            }
        }
    }

    /**
     * The stored tool result a handle addresses, if this conversation holds one.
     *
     * <h2>Why redemption comes through this seam and not through a conversation
     * id</h2>
     *
     * <p>{@code ResultTools.Read} needs one conversation's stored results, and
     * the obvious way to give it one is to hand {@code JobRuntime} the
     * conversation id and let it build the tool. <b>That is exactly what this
     * interface exists not to do.</b> This class's javadoc says {@code
     * JobRuntime} "knows nothing about conversations through this interface and
     * gains nothing it can be tempted to use", and an id would be a thing it
     * could use — to read a log, to widen a scope, to correlate two runs. A
     * method here keeps the whole of the conversation on this side: the runtime
     * builds the tool over <em>a transcript</em>, and which conversation that is
     * is never a fact it holds.
     *
     * <p><b>That is also the scoping.</b> The implementation is one turn's
     * transcript of one conversation, so a handle asked of it can only ever be
     * answered out of that conversation — there is no parameter naming another
     * one to get wrong. {@code EntryStore.redeem} names the conversation again
     * in its own WHERE, which is the second copy: unguessable is not the same as
     * unauthorised, and this project doubles enforcement.
     *
     * <p><b>A superseded result is still redeemable</b>, and the implementation
     * has to make sure of it. A fold hides the reference; it does not withdraw
     * the address. That is what makes compaction a view over a complete log
     * rather than a loss.
     *
     * <p><b>Nothing about redemption is remembered.</b> A result redeemed on one
     * turn is referenced again on the next, and the reason is not thrash but
     * purity: the projection is a function of the log, and a redemption that
     * pinned a result into later prompts would make it a function of what
     * appeared in an earlier prompt — which is nowhere in the log and cannot be
     * recovered from one. So there is no state here to keep, and an
     * implementation that kept some would have made {@code Projection} depend on
     * something no reader of the conversation can see.
     *
     * <p><b>The default answers empty</b>, which is the honest behaviour for a
     * run in no conversation — {@link #NONE} and every fixture. A run with no
     * history has no stored results, so a handle it is given came from nowhere it
     * can read, and that is the same answer a handle from another conversation
     * gets. The tool turns the empty answer into prose the model can correct.
     *
     * <p><b>It must not throw</b>, on {@link #record}'s terms: this is called
     * from inside the turn loop, and a database that could not be reached is not
     * worth a run. An implementation that cannot read says so in the log and
     * answers empty — which the model reads as "that result is not here", the
     * same sentence it would get for a handle it invented.
     *
     * <p><b>The answer is a {@link Redemption} and not the text, and that is
     * the one change retention made to this interface.</b> A payload can now
     * have been ejected — the row is still here, with its handle, its size and
     * its timing, and the bytes are not — and a signature with only "the text"
     * and "nothing" has nowhere to put that. Reporting it as nothing is the
     * outcome the retention design refuses by name: a model told there is no
     * result at that address concludes it invented the handle, when the truth is
     * that the result was here and has gone somewhere a person can still fetch
     * it from.
     *
     * @param handle the address the model sent, already parsed. Null answers
     *     empty rather than raising
     * @return the stored result, or the account of where it went, or empty if
     *     this conversation holds no result at that address at all
     */
    default Optional<Redemption> redeem(UUID handle) {
        // Nobody is keeping a log, so there is nothing stored to address.
        return Optional.empty();
    }

    /**
     * The stored results this conversation still holds behind a seam, newest
     * first, and how many there are.
     *
     * <h2>What it is for, and why {@link #redeem} was not enough</h2>
     *
     * <p>A handle survives the fold that hid it — that is what makes compaction
     * a view over a complete log rather than a loss — but the <em>reference
     * line</em> does not, because a fold supersedes it along with the turn that
     * carried it. So a result behind a seam stays redeemable and stops being
     * addressable: nothing in the prompt tells the model the handle. This is
     * where the address comes back from, and {@code result_list} is what asks.
     *
     * <p><b>Behind a seam and nothing else</b>, which is the scope and not an
     * economy. A result no fold has covered still has its own reference line in
     * the prompt, carrying the same three facts, so a listing that included it
     * would spend a bounded page on addresses the model is already holding.
     *
     * <p><b>Bounded, because a long conversation holds hundreds.</b> The caller
     * says how many and from where; what a sensible page is belongs to the tool
     * that has to put it in front of a model, exactly as {@code
     * FileTools.MAX_READ_LINES} is the tool's number and not the provider's.
     *
     * <p><b>Scoped by construction, as {@link #redeem} is.</b> An implementation
     * is one conversation's transcript and there is no parameter naming another;
     * {@code EntryStore} names the conversation again in its own WHERE.
     *
     * <p><b>The default answers with nothing</b>, which is the honest behaviour
     * for a run in no conversation — {@link #NONE} and every fixture — and is the
     * same answer a conversation nothing has folded gets. Nothing has been
     * summarised away, so there is nothing whose line was taken.
     *
     * <p><b>It must not throw</b>, on {@link #redeem}'s terms: this is called
     * from inside the turn loop, and a database that could not be reached is not
     * worth a run. An implementation that cannot read says so in the log and
     * answers with nothing.
     *
     * @param skip how many of the most recent to pass over, from 0 — a model
     *     paging through a listing longer than one answer
     * @param most how many to return, which the caller has already bounded
     * @return the page and the conversation's own total, never null
     */
    default StoredResults stored(int skip, int most) {
        // Nobody is keeping a log, so nothing has been summarised away from one.
        return StoredResults.NONE;
    }

    /**
     * The log a run this one delegates to keeps, opened as a child of this one.
     *
     * <h2>Why the seam is here and not a conversation id in {@link JobRuntime}</h2>
     *
     * <p>{@code AgentRunTool} starts a child run and the child needs a log of
     * its own. The obvious way to give it one is to hand {@link JobRuntime} the
     * parent's conversation id and let it open a child; <b>that is exactly what
     * this interface exists not to do</b>, and {@link #redeem} already refuses
     * it for the same reason. This class's javadoc says {@link JobRuntime}
     * "knows nothing about conversations through this interface and gains
     * nothing it can be tempted to use", and an id would be a thing it could use
     * — to read another log, to widen a scope, to correlate two runs. A method
     * here keeps the whole of the conversation on this side: the runtime asks a
     * transcript for a child transcript, and which conversations those are is
     * never a fact it holds.
     *
     * <p><b>A separate conversation is what makes the delegation guarantee
     * structural.</b> A child's entries must never reach the parent's prompt —
     * the whole justification for delegation is that the caller does not hold
     * the callee's noise — and a child with its own conversation has its own
     * log and its own projection, so there is no filter anywhere that could be
     * got wrong. The alternative, child entries in the parent's log excluded by
     * ownership, would add a second axis to a projection rule that today says
     * {@code kind decides}.
     *
     * <p><b>The child owns no allowance and this method takes none.</b> A
     * delegated run spends its parent's {@link Budget} by reference, down the
     * whole tree — {@code AgentRunTool} passes the same object to {@code
     * JobRuntime.run} — and a child row carrying a copy of the numbers would be
     * a second allowance of the same size that double-counts the first time
     * anything sums it. {@code ConversationStore.log} refuses to write one and
     * {@code conversations_an_allowance_is_owned_or_shared} refuses it again.
     *
     * <p><b>It must not throw</b>, on {@link #record}'s terms and for its
     * reason: this is called from inside the parent's turn loop, and a child run
     * is not worth losing over a row that could not be written. An
     * implementation that cannot open a child says so in the log and answers
     * {@link #NONE} — which is the pre-V17 behaviour for that one run, degraded
     * rather than fatal.
     *
     * <p><b>The default answers {@link #NONE}</b>, which is the honest behaviour
     * for a run that is itself in no conversation: a child of nothing is
     * nothing. That is what a fixture gets and what {@code
     * JobRuntime.schemasOfferedTo} gets.
     *
     * @param callee the agent being delegated to, whose name is the whole life
     *     of the child conversation and goes on its row
     * @param home the tier the child runs in, which is the parent's: a run's
     *     home is fixed at submission and no agent widens it
     * @return the child's transcript, never null
     */
    default Transcript delegate(AgentDefinition callee, Home home) {
        return delegate(callee, home, null);
    }

    /**
     * {@link #delegate(AgentDefinition, Home)}, naming the tool call that opens the child, so a
     * reader can go from that call's row to the log it started (spec 2026-09-29 §6).
     */
    default Transcript delegate(AgentDefinition callee, Home home, String openedBy) {
        return NONE;
    }

    /**
     * Whether the answer the previous turn came to was written by the agent's
     * fallback rather than by its own model.
     *
     * <p>Telemetry's question and, later, an affinity rule's: see {@code
     * EntryStore.lastAnswerWasAFallback}. The answer never reaches a model.
     *
     * <p><b>It must not throw</b>, on {@link #record}'s terms: an implementation
     * that cannot read says false, which only under-counts a measurement.
     *
     * <p><b>The default is false</b>, which is honest for a run in no
     * conversation: there is no previous turn.
     */
    default boolean followsAFallback() {
        return false;
    }

    /**
     * The run is over: write down what it came to, and fold if the history has
     * outgrown its window.
     *
     * <h2>Why closing is on this seam rather than at each of the four doors</h2>
     *
     * <p>A run's last three acts on its conversation are always the same three:
     * an {@code attempt_failed} entry and a closing {@code answer} for every
     * ending but {@code ANSWERED} and {@code AWAITING} — an {@code AWAITING}
     * turn records only its question as the closing answer, with no {@code
     * attempt_failed} — the {@code turns} row carrying what the prompt cost and
     * which agent answered, and the fold decision taken from that row. {@code
     * Turn} did all three inline while it was the only door;
     * there are four now, and four copies of a three-step ordering is three
     * chances for one of them to be corrected and the others not.
     *
     * <p><b>The budget write-back is here too, and only for a conversation
     * whose own row is the grant.</b> That is {@code
     * ConversationStore.turnEnded} and it belongs to whoever owns the allowance
     * — a delegated child writes nothing because the allowance is its parent's,
     * a curator's ruling writes nothing because it is the pass's, and folding a
     * write into every implementation of this method would give a child a second
     * budget by the back door.
     *
     * <p>This paragraph used to say the write-back was <em>deliberately not
     * here</em>, on the ground that the four doors do it differently. Two of
     * them do it by doing nothing, and the fourth — a submission — was doing
     * nothing by accident: {@code implementation rationale} §11 records that its row said
     * nought spent for ever, which stopped being a rounding error the day a
     * document ingest became a submission with two hundred children under it.
     * So the distinction is not which door closed the run; it is <b>whether the
     * row owns the allowance</b>, which is exactly what {@code
     * conversations_an_allowance_is_owned_or_shared} already says, and {@code
     * Compaction.TurnTranscript.writeBackWhatWasSpent} is where it is answered.
     * A person's turn is still written by {@code Turn}, which is the only caller
     * that can see the run's, the conversation's and the definition's ceilings
     * at once.
     *
     * <p><b>It must not throw</b>, on {@link #record}'s terms. What is lost when
     * a write fails is a row of a history; what is kept is the answer and the
     * spending, and an implementation that cannot write says so in the log.
     *
     * <p><b>The default does nothing</b>, which is honest for a run in no
     * conversation: there is no row to close.
     *
     * @param utterance what this run was asked, as its {@code turns} row records
     *     it. For a resumed run it is the stopped turn's own question, because
     *     that table is the human-readable transcript and the question really is
     *     that one
     * @param outcome what the run came to, whatever the ending. Never null
     */
    default void closed(String utterance, Outcome outcome) {
        // Nobody is keeping a log, so there is no row to close. See above.
    }

    /**
     * What the model charged for one call's prompt, as its own tokenizer counted
     * it.
     *
     * <p>Called once per model call that came back with a {@code usage} object,
     * and not at all for a call to an endpoint that omits one — {@code
     * TokenUsage.UNKNOWN} is the type that says so, and an absent count must
     * never arrive here as a zero. {@code turns_prompt_tokens_are_a_measurement}
     * refuses that zero one layer down, because a history measured at nought is
     * a history that never needs compacting.
     *
     * <p>A turn makes several calls and each one's prompt is longer than the
     * last, since the turn's own tool results are appended as it goes. What an
     * implementation keeps out of the sequence is its business; {@code
     * Compaction} keeps the largest, because the context bound applies to every
     * call and a turn fits only if its longest prompt does.
     *
     * @param promptTokens the count, always above zero
     */
    void promptMeasured(int promptTokens);

    /**
     * What the model charged for one call's generation, as its own tokenizer
     * counted it.
     *
     * <p>Called once per model call that came back with a {@code usage} object
     * carrying a completion count, on {@link #promptMeasured(int)}'s terms
     * exactly: never for an endpoint that omits one, and never as a zero.
     *
     * <p><b>Why a second number and not an addend on the first.</b> A prompt
     * measurement is what a conversation costs to <em>send</em>; a generation is
     * what it produced, and the two live in different places afterwards. Every
     * generation but a turn's last is appended to the history that turn goes on
     * sending, so it turns up inside a later prompt measurement; the last one
     * never does, because the turn returns with it. So the last generation is
     * the one thing a turn produces that is in no prompt measurement at all, and
     * an implementation asking what a turn <em>added</em> to its conversation —
     * {@code Compaction.TurnTranscript.added} is the only one — needs it apart
     * from the prompt counts rather than summed into them.
     *
     * <p><b>The default does nothing</b>, which is honest for a run in no
     * conversation and is why this is a {@code default} where {@link
     * #promptMeasured(int)} is abstract: that one was the reason this interface
     * exists and every implementation has to answer it, and this one is a second
     * measurement only one implementation has a use for.
     *
     * @param completionTokens the count, always above zero
     */
    default void answerMeasured(int completionTokens) {
        // Nothing is counting. See promptMeasured.
    }

    /**
     * A tool-call step of this turn has ended — every result it asked for is in {@code history}
     * and in the log — and the next model call has not been made: the one place a fold can run
     * <b>inside</b> a turn (spec 2026-09-30-fold-at-60-and-80 §1).
     *
     * <p>An implementation may rewrite {@code history} in place: the older steps of the turn,
     * and any earlier turns no fold stands for yet, replaced by one summary where the steps
     * were, keeping the turn's opening request and its most recent whole steps. Whatever it
     * does to the list it must have written to the log first, so that a projection rebuilt from
     * the log shows the same fold. It may also only note, in the log, that a fold has become
     * due; nothing about that is said to the model.
     *
     * <p><b>It costs the run nothing it counts</b>: a summarising call made here is neither a
     * step against the turn's cap nor a call against the run's budget, which is why the loop
     * calls this outside both and an implementation makes its call on nobody's allowance.
     *
     * <p><b>It must not throw</b>, on {@link #record}'s terms: a fold that fails leaves the turn
     * running on its whole history, and says so in the log.
     *
     * <p><b>The default does nothing</b>, which is honest for a run in no conversation: there is
     * no log to fold into.
     *
     * @param history the run's own message list, which the next model call will send; mutable
     * @param openedAt where in {@code history} this turn's opening request is
     * @return where the opening request is afterwards, which moves when earlier turns were
     *     folded out from in front of it
     */
    default int stepEnded(List<ChatMessage> history, int openedAt) {
        return openedAt;
    }
}
