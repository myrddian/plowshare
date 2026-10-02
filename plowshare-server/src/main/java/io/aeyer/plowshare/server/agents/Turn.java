package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One utterance in a conversation: a job, on the conversation's budget.
 *
 * <h2>A turn is a job and this class adds no execution model</h2>
 *
 * <p>What it does is bracket one: read the row, hand {@link JobStore} the {@link
 * Budget} that row records instead of letting it build a fresh one, and write
 * back what the turn spent when it ends. Everything between those two points is
 * the loop {@link JobRuntime} already runs — the eight endings, cancellation,
 * delegation, a session, a watch. A conversation is not a second machinery, and
 * what it changes about a run is where the allowance comes from and what the run
 * opens with.
 *
 * <h2>The transcript, which is the other half of the bracket</h2>
 *
 * <p>A conversation whose turns each ran alone would not be a conversation. So
 * the same bracket carries a {@link Compaction.TurnTranscript}: on the way in it
 * is what the run opens with — everything already said, projected out of the
 * conversation's log — and on the way out it holds the one number the
 * conversation learns from the run, what the model charged for this turn's
 * longest prompt.
 *
 * <p><b>The fold is the third thing that callback does, and it is the only one
 * nobody waits for.</b> A summarising call is the most expensive thing this
 * server does, and it used to happen at the <em>start</em> of the next turn, on
 * that turn's thread — so one person's question cost two model calls end to end.
 * It is taken here instead, once the outcome is written, and dispatched onto a
 * thread of its own; {@code Compaction.TurnTranscript.foldWhenTheTurnIsOver}
 * owns the guarantee that it neither blocks this callback nor throws into it.
 *
 * <p><b>Both halves land in the same ending callback</b>, before the job is
 * published as ended, for the reason that callback already existed: a person
 * whose next utterance follows the ENDED event must find a conversation that
 * already records what the last turn spent <em>and</em> what it said. A
 * transcript one turn behind would put the person's own last sentence out of the
 * history the next turn is answered from.
 *
 * <p><b>The home comes from the conversation and not from the caller.</b> A
 * conversation is opened in a {@code Home} and its turns run there; a request
 * that also named a project would have two answers to one question, and taking
 * this one silently is how a person comes to believe a run reached a project it
 * never touched. {@link Runs#start} refuses the pair rather than
 * choosing between them.
 *
 * <h2>Which package this is in, and the edge it does not add</h2>
 *
 * <p>{@code agents} → {@code archive}, which is the direction that already
 * existed in five files. {@code ConversationRecord}'s javadoc records the
 * <em>other</em> direction as new and unwelcome — {@code archive} importing
 * {@link Budget} — and nothing here deepens it: this class imports the store,
 * the record and {@link ArchiveException}, and {@code archive} learns nothing
 * about turns. The honest repair for the cycle is that {@code Budget} belongs to
 * neither package, and it is still not this task's.
 *
 * <h2>One turn at a time in a conversation</h2>
 *
 * <p>{@code ConversationRecord} leaves this question to "the task that builds
 * turns", and this is it. Two turns in flight on one conversation would each
 * read the row, each be handed a {@link Budget} built from the same count, and
 * each be told the whole remainder was theirs — so between them they could spend
 * twice what the conversation holds, and the row would come back a plausible
 * number that is simply wrong. That is the accounting error {@code
 * ConversationStore.turnEnded} exists to refuse one level down, arriving by a
 * route it cannot see.
 *
 * <p><b>So a second utterance is refused while one is in flight</b>, and the
 * refusal is temporary by construction: the slot is released from inside the
 * job's ending, in a {@code finally}, so a turn that ended at <em>any</em> of
 * the eight endings — including one whose write-back failed — frees the
 * conversation. The alternative shapes are worse: queueing would make a person's
 * second sentence run against a history they have not seen yet, and sharing one
 * live {@link Budget} object across concurrent turns would work in memory and
 * still need a rule for which of them writes the row.
 *
 * <p>This is not the same guarantee as a lock across a cluster. It is one
 * process's map, and jobs live in one process's memory — {@link JobStore}'s own
 * javadoc — so it is exactly as durable as the thing it is guarding.
 */
public final class Turn {

    private static final Logger log = LoggerFactory.getLogger(Turn.class);

    private final JobStore jobs;
    private final ConversationStore conversations;
    private final TurnStore turns;
    private final Compaction compaction;

    /**
     * The conversations with a turn in flight.
     *
     * <p>A set and not a map to the job id, deliberately. Registering the id
     * would mean writing it after {@link JobStore#submit} returns, and the job
     * may already have ended by then — so the write would resurrect an entry the
     * ending had removed and wedge that conversation for the life of the
     * process. The refusal names the conversation, which is what a person asked
     * about.
     */
    private final Set<String> speaking = ConcurrentHashMap.newKeySet();

    /**
     * Told every time a turn or a resume in some conversation ends, after that
     * conversation is free again.
     *
     * <p>{@code Dispatcher} (the next task) is the reason this exists: a
     * conversation's queued firing has to start the instant its own turn ends,
     * and the only way to know that moment is from inside this class — the one
     * place a turn's ending and {@link #speaking}'s removal happen together.
     */
    private final List<Consumer<String>> freed = new CopyOnWriteArrayList<>();

    /** A continuation of a delegation whose parent owns an external allowance (board seats). */
    public record DelegatedAllowance(Budget budget, Runnable settled) { }
    private volatile java.util.function.Function<ConversationRecord, DelegatedAllowance>
            delegatedAllowances = row -> { throw new Refused(
                    "this parent has no live allowance for a delegated continuation"); };

    public void useDelegatedAllowances(
            java.util.function.Function<ConversationRecord, DelegatedAllowance> allowances) {
        delegatedAllowances = Objects.requireNonNull(allowances, "allowances");
    }

    public boolean isSpeaking(String conversation) {
        return speaking.contains(conversation);
    }

    /** Tell {@code listener} each time a conversation can take another turn. */
    public void whenFree(Consumer<String> listener) {
        freed.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * The one door {@code speaking} is released through, so that every removal
     * — a turn ending, a resume ending, either one's own failed-to-start
     * cleanup — is also every listener hearing about it.
     *
     * <p><b>A listener that throws does not stop the others</b>, on {@link
     * JobStore#submit}'s reasoning: whatever this conversation's next step is,
     * one broken observer is not a reason to leave every other one waiting.
     */
    private void free(String conversation) {
        speaking.remove(conversation);
        for (Consumer<String> listener : freed) {
            try {
                listener.accept(conversation);
            } catch (RuntimeException broken) {
                log.warn("a listener waiting for conversation {} to be free threw", conversation,
                        broken);
            }
        }
    }

    public Turn(
            JobStore jobs, ConversationStore conversations, TurnStore turns,
            Compaction compaction) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.compaction = Objects.requireNonNull(compaction, "compaction");
    }

    /**
     * The home {@code conversation} is opened in, so a caller resolving what it
     * may run can ask for the conversation's own project tier before speaking
     * into it — {@link Runs#start} is that caller, needing this
     * <em>before</em> {@link #speak} runs, to build the {@code
     * DefinitionResolver.Caller} an unknown-agent refusal is judged against.
     *
     * <p>Reads the same row {@link #speak} reads and throws the identical
     * {@link ArchiveException} for the identical reason — "this class javadoc's
     * own rule, the home comes from the conversation and not from the caller",
     * one level earlier than {@link #speak} enforces it. A second read rather
     * than a cached one, on {@code ConversationStore.find}'s own reasoning: a
     * conversation's home cannot move once opened, but a read here still has
     * to name the same conversation {@link #speak} will fail to find if it has
     * been forgotten in between.
     *
     * @throws ArchiveException if no conversation has that id
     */
    public Home homeOf(String conversation) {
        return conversations.find(conversation)
                .orElseThrow(() -> new ArchiveException(
                        "no conversation has the id " + conversation))
                .home();
    }

    /**
     * Submit an utterance as a turn in {@code conversation}, and answer with the
     * job id at once.
     *
     * <p>The budget handed down is the row's, by reference, exactly as {@code
     * Curator.pass} shares one across a whole pass of many rulings — one level
     * up. Everything the turn delegates to spends the same object, and what it
     * came to is written back onto the row when the job ends.
     *
     * <p><b>The write-back happens for every ending and not only for {@code
     * ANSWERED}.</b> A turn that stopped at its turn cap made the model calls it
     * made, and a conversation that forgot them would hand the next utterance
     * calls that have already been spent. The ending is a fact about one turn;
     * the spending is a fact about the conversation.
     *
     * @param conversation the conversation this is an utterance in
     * @param definition the agent that answers
     * @param utterance what was said, in prose
     * @param sessionId the session the speaker is attached to, or {@code null}
     *     for a speaker that has none. <b>Nullable here for the same reason it
     *     is nullable at {@link JobStore#submit}</b>: a turn will normally carry
     *     one, and a conversation path that required it would make the
     *     conversation the one thing in this server that cannot be spoken into
     *     without a socket. Slice 3d's rule is a smaller set of filesystems, not
     *     a broken run — and a session <em>existing</em> is still not a file
     *     provider being attached, which is decided where the providers are
     *     built and not here
     * @return the job id, for {@code GET /v1/jobs/&#123;id&#125;} and for
     *     cancellation
     * @throws ArchiveException if no conversation has that id
     * @throws io.aeyer.plowshare.server.archive.ValidationException if the id is
     *     null or blank — {@code ConversationStore.find}'s refusal, not restated
     *     here, because a second message about one situation is a second thing
     *     to keep true
     * @throws Refused if the conversation has nothing left to spend, or already
     *     has a turn in flight
     */
    public String speak(
            String conversation, AgentDefinition definition, String utterance, String sessionId) {
        return speak(conversation, definition, utterance, sessionId, null, Speaker.person(null),
                outcome -> { });
    }

    /**
     * The same utterance, under a ceiling this one turn was given, naming who speaks it, and
     * told once more when the conversation is free again.
     *
     * <p><b>The turn cap is where the three levels meet, and the only place that can see all
     * three.</b> The agent's definition carries what it usually needs, the conversation's row
     * may override it for every turn in it, and this utterance may override that; {@link
     * TurnCap#chosen} is the rule and this is the caller that has the arguments for it.
     * Narrowest wins. An override is this turn's and does not touch the row. <b>There is no
     * budget override beside it</b>: a conversation's allowance is shared by every turn in it
     * by reference, and what an operator can do is raise it on the run, through {@code POST
     * /v1/jobs/&#123;id&#125;/limits}, which is written back onto the row when the turn ends.
     *
     * <p><b>Why the speaker is an argument here and not read off the session.</b> The log is
     * what a reader rebuilds the conversation from, and an utterance whose speaker is guessed
     * afterwards is one a harness delivery can be mistaken for "you said". Only the door knows
     * who came through it — the surface that holds the signed-in handle, or the runner that
     * fired the trigger — so it is said here, carried on the turn's transcript, and recorded
     * by {@link JobRuntime} beside the utterance itself. <b>The shorter overloads that
     * defaulted it to a person whose handle is not known are gone</b>, but for the one above,
     * which tests speak through: a caller that must name the speaker cannot pick up the wrong
     * one by leaving it out.
     *
     * <p>{@code alsoEnded} runs <b>after</b> {@link #free} has removed this conversation from
     * {@link #speaking} and every {@link #whenFree} listener has already been told — {@code
     * Dispatcher}'s door for starting a queued firing the instant a person's own turn in a
     * conversation ends, which has to see {@link #isSpeaking} answer false or it would be
     * refused the very turn it was woken to take.
     *
     * @param turnCap the ceiling for this turn, or {@code null} to take the conversation's, or
     *     the agent's when the conversation decides none
     * @param speaker who is speaking this utterance; never null
     * @param alsoEnded handed the outcome once the conversation is free; a no-op for a caller
     *     with nothing to do when a turn ends
     */
    public String speak(
            String conversation, AgentDefinition definition, String utterance, String sessionId,
            TurnCap turnCap, Speaker speaker, Consumer<Outcome> alsoEnded) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(alsoEnded, "alsoEnded");
        // incoming = true: every public overload of speak() is a person's own words arriving
        // fresh -- spec §6 -- which is what distinguishes this door from deliver()'s, immediately
        // below, and from speakToConductor's and speakToApprovedRun's further down.
        return speak(conversation, definition, utterance, sessionId, turnCap, null, alsoEnded,
                Turn::requireSpeakable, true, speaker, null);
    }

    /**
     * The harness delivering its own words into a person's conversation — never a person's own,
     * and never incoming.
     *
     * <h2>Why this is a separate method and not {@link #speak} widened</h2>
     *
     * <p>Every public overload of {@link #speak} states {@code incoming = true}: it is reached
     * from a person's own request, exactly as {@link #speakToConductor}'s own javadoc argues for
     * widening {@code requireSpeakable} instead of the admissibility check. This is the same
     * shape one level up — a harness delivery shares {@code speak}'s admissibility ({@link
     * #requireSpeakable}, {@link Origin#TURN} only) because it is genuinely a message landing in
     * an ordinary conversation, but it is the harness speaking on a caller's behalf — {@code
     * OrchestrationsConfig.callerVoice} relaying a conductor's own result, {@code
     * ApprovalsConfig}'s delivery relaying an answered question — and never a person typing.
     * Spec §6 names it beside a conductor turn, an approved-run continuation and a resume as a
     * door {@link TriggerNoticing} never reaches, and folding it into {@link #speak} would have
     * meant threading a fifth boolean through every one of that method's five overloads for a
     * caller with two.
     *
     * <p><b>No session and no per-call cap override, on {@code speakToConductor}'s reasoning
     * exactly.</b> A delivery reaches no client machine — nobody is holding a socket for the
     * harness's own message — and there is no third, narrower level here the way an utterance's
     * own override is for {@link #speak}: what runs is under whatever the conversation or the
     * definition decides, {@link TurnCap#chosen} resolved the identical way a plain {@link
     * #speak} call with no override would resolve it.
     *
     * @param conversation the conversation the harness is delivering into
     * @param definition the agent that conversation belongs to
     * @param utterance what the harness is delivering, in prose
     * @param alsoEnded handed the outcome once the conversation is free, {@link #speak}'s terms
     *     exactly
     * @return the job id
     * @throws ArchiveException if no conversation has that id
     * @throws Refused for every reason {@link #requireSpeakable} refuses {@link #speak}: a
     *     conversation that is not {@link Origin#TURN}, one that is not active, one with nothing
     *     left to spend, or one already speaking
     */
    public String deliver(String conversation, AgentDefinition definition, String utterance,
            Consumer<Outcome> alsoEnded) {
        return deliver(conversation, definition, utterance, Speaker.harness(), alsoEnded);
    }

    /**
     * {@link #deliver(String, AgentDefinition, String, Consumer)}, naming the harness's source —
     * the run whose question or ending this is, or the approval whose answer — so the log can
     * say which of the harness's voices spoke, and a reader never renders it as the person's.
     *
     * @param speaker who is delivering; the harness, with its source. Never null
     */
    public String deliver(String conversation, AgentDefinition definition, String utterance,
            Speaker speaker, Consumer<Outcome> alsoEnded) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(alsoEnded, "alsoEnded");
        return speak(conversation, definition, utterance, null, null, null, alsoEnded,
                Turn::requireSpeakable, false, speaker, null);
    }

    /**
     * The harness speaking to an orchestration's own conductor, on the
     * conversation's own origin's allowance — the only door into an
     * orchestration's conversation.
     *
     * <h2>Why this is a separate method and not {@link #speak} widened</h2>
     *
     * <p>{@link #speak} is reached from a person's own request — {@code
     * POST /v1/agents/&#123;name&#125;/runs} and the WS {@code agent.run}
     * frame both take a caller-supplied conversation id and hand it straight
     * to that method. Widening {@code requireSpeakable} to also accept {@link
     * Origin#ORCHESTRATION} would have let either door post into a
     * conductor's own conversation and spend its allowance, driven as
     * whichever agent the caller named — nothing at that door compares the
     * conversation's own {@link ConversationRecord#agent()} against the
     * definition it was handed. So the wider set of origins is not a wider
     * {@code requireSpeakable}; it is a second, narrower method that only the
     * harness itself can reach, because nothing routes a caller-supplied
     * conversation id to it. This is the harness talking to its own
     * conductor at every turn of the orchestration, never a person talking to
     * one.
     *
     * <p><b>Two refusals ahead of the ones {@link #speak} shares with this
     * method</b>: an origin that is not {@link Origin#ORCHESTRATION} at all,
     * and an origin that is one but names a different conductor. Beyond that
     * this behaves exactly like {@link #speak} — the same one-turn-at-a-time
     * claim, the same budget check, {@link #requireActive}, a submit under
     * {@code record.origin()}, and the same {@code ended} consumer.
     *
     * @param conversation the orchestration's own conversation
     * @param conductor the agent driving that orchestration's next turn.
     *     Refused if it does not name the same agent the conversation was
     *     logged under
     * @param utterance what the harness is telling the conductor
     * @param alsoEnded handed the outcome once the conversation is free,
     *     {@link #speak}'s terms exactly
     * @return the job id
     * @throws ArchiveException if no conversation has that id
     * @throws Refused if the conversation is not an orchestration's own, if
     *     it belongs to a different conductor, if it has nothing left to
     *     spend, or if a turn is already in flight
     */
    public String speakToConductor(
            String conversation, AgentDefinition conductor, String utterance,
            Consumer<Outcome> alsoEnded) {
        return speakToConductor(conversation, conductor, utterance, null, null, alsoEnded);
    }

    /**
     * Continue an unattended event or submission after its owning account answered an approval.
     * This is a harness-only door: it admits only an agent-owned root and requires the same agent.
     */
    public String speakToApprovedRun(String conversation, AgentDefinition definition,
            String utterance, Consumer<Outcome> alsoEnded) {
        return speakToApprovedRun(conversation, definition, utterance, Speaker.harness(),
                alsoEnded);
    }

    /**
     * {@link #speakToApprovedRun(String, AgentDefinition, String, Consumer)}, naming the
     * approval that continues the run: the answer is the harness relaying a decision, and the
     * log says whose decision it relayed.
     *
     * @param speaker who is continuing the run; the harness, with its source. Never null
     */
    public String speakToApprovedRun(String conversation, AgentDefinition definition,
            String utterance, Speaker speaker, Consumer<Outcome> alsoEnded) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(alsoEnded, "alsoEnded");
        // incoming = false: this is the harness continuing a run after its owning account
        // answered an approval, never a person's own words arriving fresh -- spec §6.
        return speak(conversation, definition, utterance, null, null, null, alsoEnded,
                record -> requireApprovedRun(record, definition), false, speaker, null);
    }

    /**
     * The harness resuming a conductor's sub-agent in its own conversation, after the person
     * answered the approval it stopped on — spec 2026-09-26 §4. It re-runs the command it asked
     * for because it keeps its own history, instead of a new delegation with a reworded task.
     *
     * <p>A delegation conversation owns no allowance and no lifecycle: both are its conductor's.
     * So the conductor's row is what is checked active, what is spent from and written back to,
     * and what is claimed as speaking alongside the child, so the conductor cannot run on the
     * same budget meanwhile.
     *
     * <p><b>Not {@link #speak}'s shared body</b>, which reads the budget and lifecycle off the
     * one row it is handed: a delegation's are both null, and the row they live on is a second
     * one this door has to claim as well.
     *
     * <p><b>Every claim is freed on every path.</b> A refusal after the claims frees both in the
     * {@code finally}; a started turn frees both in its ending, before {@code ended} hears it,
     * so whatever {@code ended} does next — speaking to the conductor — finds it free.
     *
     * @param child the sub-agent's own delegation conversation
     * @param conductorConversation the conversation that made the delegation, whose allowance
     *     and lifecycle are the child's
     * @param callee the sub-agent; refused unless it is the agent the child was logged under
     * @param callerHandle the account the resumed run acts for, so a second question it raises
     *     has somebody to be asked
     * @param ended handed the outcome once both conversations are free
     * @return the job id
     * @throws ArchiveException if either conversation does not exist
     * @throws Refused if the child is not a delegation the conductor made, belongs to another
     *     agent, either conversation has a turn in flight, the conductor is not active, or its
     *     budget is spent
     */
    public String speakToDelegate(String child, String conductorConversation,
            AgentDefinition callee, String utterance, String callerHandle,
            Consumer<Outcome> ended) {
        Objects.requireNonNull(callee, "callee");
        Objects.requireNonNull(ended, "ended");
        ConversationRecord delegate = conversations.find(child).orElseThrow(
                () -> new ArchiveException("no conversation has the id " + child));
        if (delegate.origin() != Origin.DELEGATION
                || !conductorConversation.equals(delegate.parentId())) {
            throw new Refused("conversation " + child + " is not a delegation made by conductor"
                    + " conversation " + conductorConversation + ", so it is not resumed there");
        }
        if (!callee.name().equals(delegate.agent())) {
            throw new Refused("conversation " + child + " belongs to the agent '"
                    + delegate.agent() + "', not '" + callee.name() + "'");
        }
        ConversationRecord conductor = conversations.find(conductorConversation).orElseThrow(
                () -> new ArchiveException("no conversation has the id " + conductorConversation));
        // The conductor first and then the child, each claimed before anything is read off it,
        // on speak()'s reasoning: two resumes arriving together must not both pass one check.
        if (!speaking.add(conductor.id())) {
            throw new Refused("conversation " + conductor.id() + " already has a turn in flight");
        }
        if (!speaking.add(child)) {
            free(conductor.id());
            throw new Refused("conversation " + child + " already has a turn in flight");
        }
        boolean started = false;
        DelegatedAllowance[] external = new DelegatedAllowance[1];
        try {
            ConversationRecord lifecycleOwner = conductor.lifecycle() == null
                    ? conversations.rootOf(conductor.id()).orElseThrow(
                            () -> new Refused("the delegation's lifecycle root is missing"))
                    : conductor;
            requireActive(lifecycleOwner, "continue");
            if (conductor.budget() == null) {
                external[0] = delegatedAllowances.apply(conductor);
            }
            Budget budget = external[0] == null ? conductor.budget() : external[0].budget();
            if (budget.exhausted()) {
                throw new Refused("conversation " + conductor.id() + " has spent all "
                        + budget.limit() + " model calls of its budget, so its sub-agent has"
                        + " nothing left to carry on with");
            }
            // The plain harness: a delegation is spoken to by nobody but the harness, and this
            // door is handed no approval id to name as its source -- the question the child
            // stopped on was asked, and answered, in its conductor's name.
            Compaction.TurnTranscript transcript =
                    compaction.transcriptFor(child, callee, Speaker.harness());
            // incoming = false: the harness carrying a sub-agent on after an approval, never a
            // person's own words arriving fresh -- spec §6.
            String job = jobs.submit(callee, utterance, delegate.home(), null, budget, transcript,
                    Origin.DELEGATION,
                    outcome -> {
                        try {
                            // The conductor's row, because the allowance spent was its own.
                            if (external[0] == null) {
                                conversations.turnEnded(conductor.id(), budget);
                            }
                            transcript.closed(utterance, outcome);
                        } finally {
                            free(child);
                            free(conductor.id());
                            try {
                                if (external[0] != null) { external[0].settled().run(); }
                            } catch (RuntimeException unsettled) {
                                log.warn("delegate allowance could not settle for {}", child, unsettled);
                            }
                            try {
                                ended.accept(outcome);
                            } catch (RuntimeException broken) {
                                log.warn("what was waiting on the delegate in {} to end threw",
                                        child, broken);
                            }
                        }
                    },
                    TurnCap.from(callee), List.of(), callerHandle, false);
            started = true;
            return job;
        } finally {
            if (!started) {
                free(child);
                free(conductor.id());
                if (external[0] != null) { external[0].settled().run(); }
            }
        }
    }

    /**
     * The harness speaking to an orchestration's own conductor, told which session to reach and
     * given a new total for the conversation's model-call budget — Decision 7's "a raise is
     * applied ... through {@code Turn.speakToConductor}'s {@code maxModelCalls} argument".
     *
     * <p><b>Why a session here, unlike the 4-argument door.</b> That overload is used for an
     * ordinary nudge or continuation where nobody is necessarily watching a socket; this one
     * exists for the harness answering a laptop session's own orchestration, which does hold one
     * — {@link #speak}'s {@code sessionId} is nullable for the identical reason, and the
     * argument here is threaded the same way, straight to {@code JobStore#submit}.
     *
     * <p><b>{@code maxModelCalls} is the conversation's new total, exactly as {@link #resume}
     * takes it</b> — not an increment, and applied through the same private {@link #grant}
     * before the exhausted check is read, so a raise lands before the very check it exists to
     * get past. {@code null} leaves the budget alone.
     *
     * @param sessionId the session the caller who will read the answer is attached to, or
     *     {@code null} for a caller with none
     * @param maxModelCalls what the conversation's whole model-call budget should now total, or
     *     {@code null} to leave it alone
     * @return the job id
     * @throws ArchiveException if no conversation has that id
     * @throws Refused if the conversation is not an orchestration's own, if it belongs to a
     *     different conductor, if {@code maxModelCalls} names a total below what the
     *     conversation has already spent — {@link #grant}'s refusal, converted here rather than
     *     left as the {@link CallerFault} it throws for {@link #resume}'s door, so the engine
     *     driving a conductor has one exception type to handle and not two — if it has nothing
     *     left to spend, or if a turn is already in flight
     */
    public String speakToConductor(
            String conversation, AgentDefinition conductor, String utterance, String sessionId,
            Integer maxModelCalls, Consumer<Outcome> alsoEnded) {
        Objects.requireNonNull(conductor, "conductor");
        Objects.requireNonNull(alsoEnded, "alsoEnded");
        // No turn cap: TurnCap.chosen falls back to the conductor's own
        // definition when the conversation decides nothing -- there is no
        // third, narrower level here the way an utterance's own override is
        // for speak().
        //
        // incoming = false: the harness driving a conductor is never a person's own words
        // arriving fresh -- spec §6.
        //
        // The plain harness: a conductor is spoken to only by the harness, and a child run's
        // report to its parent arrives through this door too, recorded the same way.
        return speak(conversation, conductor, utterance, sessionId, null, maxModelCalls, alsoEnded,
                record -> requireConductor(record, conductor), false, Speaker.harness(), null);
    }

    /**
     * A seat's wake — spec 2026-09-29, the project board and the swarm, §5–§7. The harness speaks
     * into a {@link Origin#BOARD} conversation as the agent it belongs to, spending {@code lease}
     * (handed over by the board from its root topic's pot) and capped at {@code wakeCap} steps.
     * Nothing is written back to the row: the board settles the lease when {@code alsoEnded}
     * runs.
     *
     * @throws Refused if the conversation is not a seat, belongs to another agent, is not active,
     *     the lease is empty, or a turn is already in flight
     */
    public String speakToSeat(String conversation, AgentDefinition member, String utterance,
            Budget lease, TurnCap wakeCap, Speaker speaker, Consumer<Outcome> alsoEnded) {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(wakeCap, "wakeCap");
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(alsoEnded, "alsoEnded");
        // incoming = false: a wake is the harness, never a person's own words arriving fresh.
        return speak(conversation, member, utterance, null, wakeCap, null, alsoEnded,
                record -> requireSeat(record, member), false, speaker, lease);
    }

    /**
     * The shared body of {@link #speak} and {@link #speakToConductor}, taking
     * the one thing that differs between a person's door and the harness's
     * own: which conversations are admissible and on what grounds.
     *
     * @param admissible thrown if not, before anything is spent — {@link
     *     #requireSpeakable} for {@link #speak}, {@link #requireConductor}
     *     for {@link #speakToConductor}
     * @param maxModelCalls what the conversation's whole model-call budget should now total, or
     *     {@code null} to leave it alone — {@link #speak}'s public overloads always pass {@code
     *     null}; only {@link #speakToConductor}'s 6-argument door ever passes a number, on
     *     Decision 7's terms
     * @param incoming whether this utterance is one of the doors spec §6 names — {@code true}
     *     for every public {@link #speak} overload, {@code false} for {@link #deliver}, {@link
     *     #speakToConductor} and {@link #speakToApprovedRun}. Threaded straight to {@link
     *     JobStore#submit} and from there to {@link JobRuntime#run}, which is the one place it is
     *     acted on
     * @param speaker who is speaking — every door names one, so the utterance {@link JobRuntime}
     *     records carries it on the turn's transcript rather than through {@link JobStore#submit}
     * @param given a lease the caller hands this turn to spend instead of the conversation's own
     *     allowance — only {@link #speakToSeat} passes one, because a seat's row holds none (its
     *     wakes spend the root topic's pot, spec 2026-09-29 §4). When non-null nothing is granted
     *     and nothing is written back to the row: the board settles the lease.
     */
    private String speak(
            String conversation, AgentDefinition definition, String utterance, String sessionId,
            TurnCap turnCap, Integer maxModelCalls, Consumer<Outcome> alsoEnded,
            Consumer<ConversationRecord> admissible, boolean incoming, Speaker speaker,
            Budget given) {
        ConversationRecord record = conversations.find(conversation)
                .orElseThrow(() -> new ArchiveException(
                        "no conversation has the id " + conversation));
        String id = record.id();

        // Claimed before the budget is read, not after: two utterances arriving
        // together must not both get past a check on the same row.
        if (!speaking.add(id)) {
            throw new Refused("conversation " + id + " already has a turn in flight, and a"
                    + " conversation holds one at a time. Two turns would each read this row and"
                    + " each be told the whole remaining budget was theirs. That turn will end,"
                    + " at one of the eight endings, and this conversation is free again when it"
                    + " does.");
        }
        boolean started = false;
        try {
            admissible.accept(record);
            Budget budget = given != null ? given : record.budget();
            // grant() before exhausted() is read, on resume()'s own ordering
            // and for the identical reason: a raise has to land before the
            // very check it exists to get past. grant() throws CallerFault --
            // right for resume()'s door, an operator's own HTTP request -- but
            // this door is the harness driving a conductor, and a caller that
            // only ever sees Refused should not have to catch a second type
            // for the one path that also grants a budget. speak()'s public
            // overloads never reach here with a non-null maxModelCalls, so
            // this catch is silent dead code from every one of them. A lease
            // is never granted either: it is what the board handed this turn,
            // fixed for the wake, and the row it would otherwise raise holds
            // no budget for a seat to begin with.
            if (given == null) {
                try {
                    grant(id, budget, maxModelCalls);
                } catch (CallerFault badGrant) {
                    throw new Refused(badGrant.getMessage());
                }
            }
            // exhausted() and not `remaining() == 0`, which is the same question
            // asked through a subtraction that a lifted conversation has no
            // numbers for. This line and the limit() in its message were the
            // first thing every utterance into a conversation opened with
            // `noBudget` reached, and both threw: `admissible` passes such a
            // row correctly, and it died one line later. Inside the branch the
            // limit is safe to name, because a budget with no ceiling is never
            // exhausted and so never gets here.
            if (budget.exhausted()) {
                throw new Refused("conversation " + id + " has spent all " + budget.limit()
                        + " model calls of its budget, so there is nothing left for this"
                        + " turn to spend. Turns spend a conversation's allowance and no turn"
                        + " raises it, and there is no run in flight for anyone else to raise it"
                        + " on, so what happens next is a person's decision and not this"
                        + " server's.");
            }
            Compaction.TurnTranscript transcript =
                    compaction.transcriptFor(id, definition, speaker);
            String job = jobs.submit(definition, utterance, record.home(), sessionId, budget,
                    transcript,
                    // record.origin(), and never anything `admissible` above has
                    // not already let through: Origin.TURN, by
                    // requireSpeakable, for a call arriving through speak();
                    // Origin.ORCHESTRATION, by requireConductor, for one
                    // arriving through speakToConductor(). This run's own copy
                    // of the conversation's own origin, which is what used to
                    // be hardcoded to Origin.TURN before an orchestration's
                    // conductor could be spoken into as well.
                    // Job#conversationOrigin is this run's own copy of that
                    // fact, so JobView can answer `resumable` honestly without a
                    // second lookup -- and originIsResumable stays Origin.TURN
                    // only, so an orchestration's job still answers false there.
                    record.origin(),
                    // The three levels, resolved where all three are visible.
                    // record.turnCap() is null for a conversation that decides
                    // nothing, which is not the same as one that decided there
                    // is to be no cap -- see ConversationRecord.
                    // Runs on the job's own thread, inside JobStore.finish, and
                    // before the job reports DONE at all — so neither route out
                    // of a finished turn, the ENDED event or a poll of GET
                    // /v1/jobs/{id}, can carry a person to their next utterance
                    // before this row write. JobStore.finish owns that ordering
                    // and says why. The finally is what makes the refusal above
                    // temporary even when the write-back fails: a conversation
                    // nobody can speak into because a database blinked would be
                    // a worse fault than a count that is one turn stale.
                    outcome -> {
                        try {
                            // The budget first, and the order is an argument
                            // rather than a habit. Both writes are the record of
                            // one turn, and if only one of them can land it must
                            // be this one: a conversation whose spending was not
                            // written hands the next utterance calls that have
                            // already been made, while a conversation missing a
                            // transcript row has a gap in a history. One
                            // overspends and the other under-remembers.
                            //
                            // A lease is the board's to settle (BoardPot.settle); a seat's row
                            // holds no numbers to write back.
                            if (given == null) {
                                conversations.turnEnded(id, budget);
                            }
                            // The entries, the turn's row and the fold, in that
                            // order and for the reasons `Transcript.closed`
                            // gives. It used to be three statements here; there
                            // are four doors into a run now and four copies of
                            // one ordering is three chances for a correction to
                            // land on some of them. What stayed behind is the
                            // budget write above, which is the one step the four
                            // doors genuinely do differently -- a delegated
                            // child writes none, because the allowance is its
                            // parent's.
                            transcript.closed(utterance, outcome);
                        } finally {
                            free(id);
                            try {
                                alsoEnded.accept(outcome);
                            } catch (RuntimeException broken) {
                                log.warn("what was waiting on turn in {} to end threw", id,
                                        broken);
                            }
                        }
                    },
                    TurnCap.chosen(turnCap, record.turnCap(), definition), List.of(), incoming);
            started = true;
            return job;
        } finally {
            if (!started) {
                free(id);
            }
        }
    }

    /**
     * Continue a run that stopped for want of allowance, as a new turn in the
     * same conversation.
     *
     * <h2>What this is, and why it is not {@code POST /v1/jobs/&#123;id&#125;/limits}</h2>
     *
     * <p>That endpoint moves a ceiling on a run that is still going, and it
     * refuses a finished job deliberately: "raising the ceiling on one is asking
     * for something that cannot happen, and answering 200 would say it had".
     * That reasoning is right and stands. <b>This is a different verb.</b> It
     * does not move a limit on a live run; it starts a new run continuing a
     * stopped one, and the conversation is the subject because the job is gone.
     *
     * <h2>A resumed run is a new turn</h2>
     *
     * <p>It takes the next ordinal, like any turn. Re-entering the stopped
     * turn's would mean rewriting {@code turns.ending} — the one table that
     * records how a turn ended, which {@code
     * turns_a_turn_that_stopped_says_how} exists to pin — and an append-only log
     * does not do that. Every per-turn measurement keys off {@code turn_ordinal}
     * as well: {@code sent} is a turn's first prompt, {@code added} its answer,
     * and a fold's reach is a turn ordinal, so a turn measured twice would break
     * the compaction trigger and the fold reach together.
     *
     * <p>What that costs is a log showing two turns for one question, and it
     * reads correctly rather than awkwardly: the first attempt genuinely reached
     * nothing and the transcript says so. <b>The two records say it differently
     * on purpose.</b> The {@code turns} row of the resumed turn carries the same
     * utterance as the turn it continues, because that table is the
     * human-readable transcript and the question this turn answers <em>is</em>
     * that one; the {@code entries} log holds the question once, on the turn
     * that carried it, because that is what a model reads and asking it twice
     * would be a conversation that never happened.
     *
     * <h2>What it opens with</h2>
     *
     * <p>{@code Compaction.ResumedTranscript} — everything older than the
     * stopped turn as any turn reads it, the stopped turn whole, and then the
     * note below, which is this run's opening message. The note is the {@code
     * userPrompt} because that is where {@code JobRuntime} puts a run's opening
     * message, and it is recorded as a {@code runtime_note} rather than an
     * utterance because on this path nobody said it.
     *
     * @param conversation the conversation whose last run is being continued
     * @param definition the agent answering. <b>This said "named by the caller
     *     and not recovered from the stopped turn ... recovering it would need a
     *     migration, which this deliberately does not take", and {@code
     *     V17__conversation_origin.sql} took it.</b> {@code turns.agent} records
     *     which agent answered, and {@code ConversationController.resume} reads
     *     it and refuses a body that names a different one — so what arrives
     *     here is the agent that answered the run being continued, resolved one
     *     layer up where the registry is. The resolution stays there rather than
     *     moving here because this class holds no {@code AgentRegistry} and
     *     giving it one to look up a name it is already handed would be a
     *     dependency for nothing
     * @param sessionId the session the speaker is attached to, or {@code null}.
     *     {@link #speak}'s terms exactly. A resumed run that is to reach the
     *     same client machine has to be told so again: a session is a socket
     *     somebody is holding now, not a fact the stopped run left behind
     * @param turnCap the ceiling for this run, or {@code null} to take the
     *     conversation's, or the agent's when the conversation decides none.
     *     <b>A new run counts its turns from zero</b>, so the same number that
     *     stopped the last one is a full grant again and not an empty one
     * @param maxModelCalls what the whole conversation may spend, raised, or
     *     {@code null} to leave it alone. Model calls are the conversation's and
     *     are shared by reference, so this moves the row's total — the one
     *     accounting story, not a second
     * @return the job id, for {@code GET /v1/jobs/&#123;id&#125;} and for
     *     cancellation
     * @throws ArchiveException if no conversation has that id
     * @throws CallerFault if {@code maxModelCalls} is below what the
     *     conversation has already spent, is not a budget at all, or names a
     *     number for a conversation whose allowance has no ceiling to raise —
     *     {@link #grant} says why the last of the three arrives as this type
     *     rather than as the {@code IllegalStateException} {@code
     *     Budget.changeTo} throws
     * @throws Refused if nothing has been said in the conversation, if its last
     *     turn ended at an ending a grant does not continue, if there is nothing
     *     left to spend, or if a turn is already in flight
     */
    public String resume(
            String conversation, AgentDefinition definition, String sessionId, TurnCap turnCap,
            Integer maxModelCalls) {
        Objects.requireNonNull(definition, "definition");
        ConversationRecord record = conversations.find(conversation)
                .orElseThrow(() -> new ArchiveException(
                        "no conversation has the id " + conversation));
        String id = record.id();

        // Claimed before anything is read, on speak's reasoning exactly: two
        // grants arriving together must not both get past a check on the same
        // row and start two runs continuing the same turn.
        if (!speaking.add(id)) {
            throw new Refused("conversation " + id + " already has a turn in flight, so there is"
                    + " nothing stopped in it to continue. A conversation holds one turn at a"
                    + " time. That turn will end, at one of the eight endings, and this"
                    + " conversation is free again when it does.");
        }
        boolean started = false;
        try {
            List<TurnRecord> spoken = turns.forConversation(id);
            if (spoken.isEmpty()) {
                throw new Refused("conversation " + id + " has nothing in it, so there is no run"
                        + " to continue. A conversation is continued from its last turn, and"
                        + " this one has not had a first.");
            }
            TurnRecord stopped = spoken.get(spoken.size() - 1);
            requireContinuable(id, stopped);
            requireResumable(record);
            Budget budget = record.budget();
            grant(id, budget, maxModelCalls);
            // {@link #speak}'s line, for {@link #speak}'s reason: a lifted
            // conversation has no remaining to compare and no limit to name, and
            // this is the continue path into one. Read after grant() rather than
            // before, which is what makes a CALL_BUDGET stop continuable at all
            // — the raise has landed by the time this asks.
            if (budget.exhausted()) {
                throw new Refused("conversation " + id + " has spent all " + budget.limit()
                        + " model calls of its budget, so there is nothing left for the run this"
                        + " would continue. Turns are the grant this asks for and model calls are"
                        + " the conversation's; send 'maxModelCalls' as well to raise them, which"
                        + " is a person's decision and not this server's.");
            }
            TurnCap under = TurnCap.chosen(turnCap, record.turnCap(), definition);
            Compaction.ResumedTranscript transcript =
                    compaction.resumedTranscriptFor(id, definition, stopped.ordinal());
            // Before the run and not after it, so that the first thing the
            // resumed turn's stretch of the log says is why it exists. It is a
            // diagnostic: role NULL, never projected, and no column and no
            // migration for a fact that is prose about the machinery.
            transcript.record(LoggedEntry.diagnostic(resumptionNote(
                    stopped, spoken.size() + 1, under, maxModelCalls)));
            String job = jobs.submit(definition, continuing(record), record.home(), sessionId,
                    budget, transcript,
                    // Origin.TURN: requireResumable above already refused every
                    // other origin before record was let this far, on the same
                    // terms requireSpeakable does for speak(). See the comment
                    // on that call.
                    Origin.TURN,
                    // The same three writes speak's callback makes, in the same
                    // order and for the same reasons, because a resumed turn is
                    // a turn. The utterance written into `turns` is the stopped
                    // turn's, which this method's javadoc argues.
                    outcome -> {
                        try {
                            conversations.turnEnded(id, budget);
                            transcript.closed(stopped.utterance(), outcome);
                        } finally {
                            free(id);
                        }
                    },
                    // The nine-argument overload, which states incoming = false: a resumed run
                    // is the harness continuing something that already ran out of allowance,
                    // never a person's own words arriving fresh -- spec §6.
                    under);
            started = true;
            return job;
        } finally {
            if (!started) {
                free(id);
            }
        }
    }

    /**
     * A person's conversation, or the refusal for one that is a machine's log.
     *
     * <h2>Why this guard appeared when every run got a conversation</h2>
     *
     * <p>Until V17 there was one kind of row in {@code conversations} and it was
     * always a person's. There are four now — a person's turns, a delegated
     * child, a curator's ruling and a submission — and the id of any of them can
     * be put in {@code RunAgentRequest.conversation} by anybody who read it out
     * of a trajectory. Without this, such an utterance would reach {@code
     * record.budget()} and find a null, because a delegated child holds no
     * allowance of its own by design, and the fault would surface as a null
     * dereference on a virtual thread rather than as an answer.
     *
     * <p><b>Only {@link Origin#TURN}, which is also what {@link
     * #requireResumable} settles on and for a different reason.</b> A submission
     * owns an allowance and is still not a conversation anybody speaks into: it
     * is the log of a run started on its own behalf, which {@code
     * JobStore.submit} describes as "a run nobody is going to speak to again",
     * and a second utterance in it would make a person's listing and a machine's
     * log the same kind of thing. Two guards rather than one shared predicate
     * because the two refusals say different things — this one is about what a
     * conversation is <em>for</em>, and that one is about what its row can
     * account for.
     *
     * <p><b>Not {@link Origin#ORCHESTRATION} either, and deliberately so.</b>
     * An orchestration's own conversation is spoken into, but never through
     * this door: this is the guard a caller-supplied conversation id reaches,
     * from {@code POST /v1/agents/&#123;name&#125;/runs} and the WS {@code
     * agent.run} frame alike, and neither compares the conversation's own
     * {@link ConversationRecord#agent()} against the definition it was
     * handed. Widening this set would let either one post into a conductor's
     * conversation and spend its allowance, driven as whatever agent the
     * caller named. {@link #requireConductor} is the narrower guard that
     * exists instead, reachable only from {@link #speakToConductor} — a
     * method nothing routes a caller-supplied id to.
     */
    private static void requireSpeakable(ConversationRecord record) {
        if (record.origin() == Origin.TURN) {
            requireActive(record, "speak into");
            return;
        }
        throw new Refused("conversation " + record.id() + " is the log of a "
                + record.origin().wireName() + " run of '" + record.agent() + "', not a"
                + " conversation somebody opened to speak into. It can be read back --  its"
                + " turns, its chat and its trajectory are all there -- and an utterance in it"
                + " would be a person speaking into a record of something the machine did."
                + " Open a conversation with POST /v1/conversations and speak into that.");
    }

    /**
     * An orchestration's own conversation, spoken to as the conductor it
     * belongs to — the admissibility check {@link #speakToConductor} passes
     * to the body it shares with {@link #speak}, on {@link #requireSpeakable}'s
     * shape exactly, for a narrower door.
     *
     * <p><b>Two refusals ahead of {@link #requireActive}'s.</b> The first is
     * {@link #requireSpeakable}'s own: a conversation that is not an
     * orchestration's at all is refused by name, the identical shape that
     * guard uses for every origin it does not accept. The second is the one
     * {@link #requireSpeakable} has no use for, because nothing reaching it
     * carries a definition to compare against: {@code record.agent()} is the
     * conductor this conversation was logged under, and a caller — here,
     * always the harness itself — naming a different one is refused rather
     * than silently driving somebody else's conductor as this one.
     */
    private static void requireConductor(ConversationRecord record, AgentDefinition conductor) {
        if (record.origin() != Origin.ORCHESTRATION) {
            throw new Refused("conversation " + record.id() + " is not an orchestration's own"
                    + " conversation; only the harness speaks to a conductor, and only there");
        }
        if (!record.agent().equals(conductor.name())) {
            throw new Refused("conversation " + record.id() + " belongs to the conductor '"
                    + record.agent() + "', not '" + conductor.name() + "'");
        }
        requireActive(record, "speak into");
    }

    /** A seat, spoken to as the agent it belongs to — {@link #requireConductor}'s shape. */
    private static void requireSeat(ConversationRecord record, AgentDefinition member) {
        if (record.origin() != Origin.BOARD) {
            throw new Refused("conversation " + record.id() + " is not a seat on a board; only"
                    + " the harness speaks into a seat, and only there");
        }
        if (!record.agent().equals(member.name())) {
            throw new Refused("conversation " + record.id() + " is the seat of '"
                    + record.agent() + "', not '" + member.name() + "'");
        }
        requireActive(record, "speak into");
    }

    private static void requireApprovedRun(ConversationRecord record,
            AgentDefinition definition) {
        if (record.origin() != Origin.EVENT && record.origin() != Origin.SUBMISSION) {
            throw new Refused("conversation " + record.id() + " is not an unattended event or"
                    + " submission awaiting an approval");
        }
        if (!record.agent().equals(definition.name())) {
            throw new Refused("conversation " + record.id() + " belongs to the agent '"
                    + record.agent() + "', not '" + definition.name() + "'");
        }
        requireActive(record, "continue");
    }

    /**
     * A person's conversation, or the refusal for a machine's log — and here the
     * reason is the accounting rather than the speaking.
     *
     * <p><b>A delegated child and a curator's ruling have everything a
     * resumption reads and no allowance to spend.</b> Since V17 they have
     * entries, a {@code turns} row, an ending and the agent that answered; what
     * they do not have is a budget, because a child spends the {@link Budget}
     * object of the run that delegated to it and a ruling spends the pass's.
     * That run is over, so there is nothing left in this process for a
     * continuation to spend. Granting one through {@code maxModelCalls} would
     * write an allowance onto a row {@code
     * conversations_an_allowance_is_owned_or_shared} refuses to let hold one —
     * and that constraint is what stops a shared budget being counted twice, so
     * the right answer is to say so rather than to relax it.
     *
     * <p><b>A submission is refused too, and it is the interesting case because
     * it <em>does</em> own an allowance.</b> This paragraph used to say that
     * what it lacked was a write-back — that {@code JobStore}'s submission
     * closed its log and stopped there, so the row said nought spent however
     * many calls the run made, and a resumption reading it would hand out the
     * whole allowance a second time. <b>That is no longer true</b>: {@code
     * implementation rationale} §11 is closed, and {@code
     * Compaction.TurnTranscript.writeBackWhatWasSpent} writes the spending onto
     * every row that owns its allowance.
     *
     * <p><b>The refusal stays, and on two grounds that survive the fix.</b>
     *
     * <ul>
     *   <li><b>What a submission is.</b> {@link #requireSpeakable} one method up
     *       refuses an utterance in a machine's log because it is the record of
     *       a run started on its own behalf — {@code JobStore.submit} calls it
     *       "a run nobody is going to speak to again" — and a resumption is a
     *       <em>turn</em> in that conversation by every other measure this class
     *       applies. Nothing about the accounting made that ground weaker.
     *   <li><b>The write-back lands where a run closes its log, and the runs
     *       most worth resuming do not reach it.</b> {@code
     *       documents.Summariser} closes its cascade root inside the
     *       document-level call, so a cascade that stops in a paragraph run —
     *       cancelled, or out of allowance, which is precisely the ingest
     *       somebody would want to continue — returns without closing the root
     *       at all, and that row still understates what the cascade spent. A
     *       resumption reading it would hand out calls already made. That gap is
     *       a defect in the cascade's logging rather than in this guard, and it
     *       is recorded rather than worked around here.
     * </ul>
     *
     * <p>So making a submission resumable is still a change with its own
     * argument — it now needs an answer to "what does continuing a cascade
     * mean", since {@code Turn.resume} runs one agent and a cascade is two
     * hundred — rather than a line in this guard.
     */
    private static void requireResumable(ConversationRecord record) {
        if (originIsResumable(record.origin())) {
            requireActive(record, "continue a run in");
            return;
        }
        throw new Refused("conversation " + record.id() + " is the log of a "
                + record.origin().wireName() + " run of '" + record.agent() + "', which is not a"
                + " conversation a run is continued in. "
                + (record.origin().ownsItsAllowance()
                        // A submission. Its row DOES account for what it spent
                        // now -- implementation rationale §11 is closed -- so the sentence
                        // that used to be here would be untrue, and a refusal
                        // giving a reason that is no longer the reason is worse
                        // than no reason at all.
                        ? "It owns the allowance it spent and its row records what that came"
                                + " to, so the accounting is not what refuses this: what does"
                                + " is what a submission is, a run started on its own behalf"
                                + " that nothing was going to speak to again."
                        : "A run continued from it would be spending an allowance this row"
                                + " cannot account for: a delegated child spends the budget of"
                                + " the run that delegated to it and a curator's ruling spends"
                                + " the pass's, so neither row holds one at all.")
                + " Its history is still readable at GET"
                + " /v1/conversations/" + record.id() + "/trajectory.");
    }

    /**
     * The lifecycle, asked by both guards above, because <b>archiving that does
     * not bite is decorative</b>.
     *
     * <h2>Why this is here and not only in the listing</h2>
     *
     * <p>{@code ConversationStore.inHome} takes an archived conversation off the
     * screen, and that is the half a person sees. It is not the half that
     * matters: an id survives a listing — a console tab left open, a script, a
     * harness holding one from last week — so a state enforced only by a query
     * is a state anything that already has the id can walk straight past. The
     * design names the failure it is guarding against exactly: a state added as
     * a listing filter that quietly does nothing else.
     *
     * <p><b>Both doors and not one</b>, and they are genuinely two: an utterance
     * is a person starting something new in a conversation they put away, and a
     * resumption is a run continuing inside one. A guard on {@link #speak} alone
     * would leave {@code POST /v1/conversations/&#123;id&#125;/resume} able to
     * start work in an archived conversation, and a run started that way writes
     * entries, spends the budget and ends in a {@code turns} row — the whole of
     * what archiving was supposed to stop, reached through the other verb.
     *
     * <p><b>{@code lifecycle()} is dereferenced without a guard</b>, and the
     * reason is one line up: this is only ever reached for {@link Origin#TURN}
     * or {@link Origin#ORCHESTRATION}, and {@code ConversationStore} gives
     * neither a parent — a person's conversation is opened by {@code open()},
     * which never takes one, and an orchestration's is written by {@code log()},
     * which refuses one for every origin but {@link Origin#DELEGATION}. Both are
     * roots, then, and roots carry a state — {@code
     * conversations_a_root_is_where_the_lifecycle_lives}. A delegated child
     * never reaches here at all, because the origin guard refuses it first and
     * says why.
     *
     * <p>The refusal <b>names the way back</b>, because the state is reversible
     * for two of the three and a person meeting a refusal with no next step
     * opens another conversation and loses the history. {@link
     * ConversationLifecycle#EJECTED} is the one that has no way back, and the
     * sentence says that rather than offering one.
     */
    private static void requireActive(ConversationRecord record, String verb) {
        ConversationLifecycle state = record.lifecycle();
        if (state.acceptsWork()) {
            return;
        }
        throw new Refused("conversation " + record.id() + " is " + state.wireName()
                + ", and only an active conversation takes work, so there is nothing here to "
                + verb + ". " + (state == ConversationLifecycle.EJECTED
                        ? "Its tool results have been ejected and it is not going to become"
                                + " active again; its history is still readable at GET"
                                + " /v1/conversations/" + record.id() + "/trajectory. Open a new"
                                + " conversation with POST /v1/conversations."
                        : "Nothing has been lost -- every turn, the whole trajectory and every"
                                + " stored result are where they were. Put it back with PUT"
                                + " /v1/conversations/" + record.id() + "/lifecycle naming"
                                + " 'active', and then go on."));
    }

    /**
     * The endings a grant continues, and the refusal for every other.
     *
     * <p>Not "the ones that failed" — the ones that stopped at a boundary where
     * the history is whole. {@code JobRuntime}'s loop checks the cap, then
     * cancellation, then the budget, all at the top of the loop and before the
     * model call, so a run stopped by any of the three has completed its
     * previous iteration and every call it made has its result already appended.
     *
     * <p>{@code CANCELLED} is in the set and the console will never offer it.
     * Mechanically it stops at the same boundary as the other two, so refusing
     * it would be an invented restriction; but the person asked it to stop, and
     * putting "continue?" in front of them answers a question they did not ask.
     * Reachable on request, never suggested.
     */
    private static final Set<Outcome.Ending> CONTINUABLE = EnumSet.of(
            Outcome.Ending.TURN_CAP, Outcome.Ending.CALL_BUDGET, Outcome.Ending.CANCELLED);

    /**
     * The endings this server <em>suggests</em> continuing, which is the set
     * above minus the one somebody asked to stop.
     *
     * <p><b>Derived from {@link #CONTINUABLE} rather than written out again</b>,
     * so that widening what a grant takes cannot leave a second list quietly
     * disagreeing about it. The one difference is stated once, as a removal, and
     * the reason is the one the paragraph above gives: a cancelled run is
     * reachable on request and never suggested.
     */
    private static final Set<Outcome.Ending> OFFERED = offered();

    private static Set<Outcome.Ending> offered() {
        EnumSet<Outcome.Ending> suggested = EnumSet.copyOf(CONTINUABLE);
        suggested.remove(Outcome.Ending.CANCELLED);
        return suggested;
    }

    /**
     * Whether a client should put "continue this?" in front of a person for a
     * run that ended this way.
     *
     * <h2>Why a client asks rather than knowing</h2>
     *
     * <p><b>Which endings continue is this server's decision and the table is
     * here</b>, beside the code that continues one — {@link #requireContinuable}
     * is where a grant is actually checked, against the row and not against a
     * job in memory. A console carrying its own copy of the list would offer a
     * grant this server refuses on the day the set changes, which is the drift
     * {@code TurnView} sends an ending's <em>name</em> rather than an enum
     * precisely to avoid. So {@code JobView.OutcomeView.resumable} answers it and
     * nothing that renders a run enumerates endings.
     *
     * <p><b>It is the offer and not the rule.</b> A caller that asks for {@code
     * CANCELLED} is still taken; what this decides is what a person is shown
     * unprompted. And it is only half of what the offer needs: a run stopped with
     * nothing left in the conversation's budget cannot be continued by a grant
     * that raises <em>turns</em>, which is the other half. That half used to be
     * pure arithmetic and is no longer, because it depends on what the grant for
     * this ending would raise — see {@link #grantRaisesTheBudget}, which is the
     * fact it now needs beside the count. {@code JobView} puts the two together,
     * because that is where both facts are on one handle.
     *
     * @param ending how the run ended
     * @return whether continuing it is worth offering
     */
    public static boolean continuationIsOffered(Outcome.Ending ending) {
        return OFFERED.contains(ending);
    }

    /**
     * Whether the grant that continues this ending raises the conversation's
     * model-call budget rather than the run's turn cap.
     *
     * <h2>A different question from {@link #continuationIsOffered}, and why it
     * had to be asked</h2>
     *
     * <p><b>That one decides <em>whether</em> to offer; this decides <em>what an
     * offer would move</em>.</b> The two used to be one because there was one
     * answer: a grant offered turns, so a run that had spent the conversation's
     * whole allowance could not be continued by one whatever its ending, and
     * {@code JobView} folded that in as arithmetic — {@code remaining() > 0}
     * beside the list. That arithmetic quietly encoded "the grant is always
     * turns", and it stopped being true the day a {@code CALL_BUDGET} stop
     * started being topped up with model calls: a run stopped at its budget has
     * nothing remaining <em>by construction</em>, so the old conjunct marked
     * precisely the ending the new grant was built for as not resumable, and the
     * offer could never appear.
     *
     * <p><b>Which is why this is a method here and not a second branch in {@code
     * JobView}.</b> It is the same fact the console reads off {@code
     * outcome.ending} to choose which field to send, and this server is the one
     * that has to agree with it: an ending whose grant raises the budget is
     * continuable when the budget is gone, and an ending whose grant raises
     * turns is not. Keeping it beside {@link #CONTINUABLE} means the day a third
     * ending joins the set, the question "what would a grant move for it?" is
     * asked in the same file as "is it offered at all?".
     *
     * <p><b>{@code CALL_BUDGET} and nothing else.</b> Every other continuable
     * ending stopped for a reason a turn cap describes, and {@code
     * ConversationController.resume} raises the budget only when a body names
     * {@code maxModelCalls} — which the console sends for this ending alone.
     *
     * @param ending how the run ended
     * @return whether a grant continuing it would raise the budget
     */
    public static boolean grantRaisesTheBudget(Outcome.Ending ending) {
        return ending == Outcome.Ending.CALL_BUDGET;
    }

    /**
     * Whether a conversation of this origin is one {@link #resume} will
     * continue, as opposed to refuse by name.
     *
     * <h2>Why this exists as its own method</h2>
     *
     * <p>{@link #requireResumable} below is the enforcement and used to be the
     * only place this question was asked — a conversation reaches {@link
     * #resume} or it does not, and the refusal is the whole of what a caller
     * sees. That stopped being true the moment {@code JobView.OutcomeView}
     * started answering {@code resumable} <em>before</em> anyone calls this
     * door: a bit computed from the ending and the budget alone said {@code
     * true} for a {@link Origin#SUBMISSION} run stopped at its budget, because
     * neither of those two facts has anything to say about origin, and this
     * class's own refusal — "not a conversation a run is continued in" — was the
     * only place that knew otherwise. A client that trusted the bit and pressed
     * a continue button built on it would be refused by a door that was never
     * consulted.
     *
     * <p><b>Exactly {@link Origin#TURN}</b>, on {@link #requireResumable}'s own
     * two grounds: what a submission or a delegated child or a curator's ruling
     * <em>is</em>, not an accounting gap this class used to have and closed.
     *
     * @param origin the conversation's origin, or {@code null} for a run that
     *     has no conversation at all — never resumable, for want of a door to
     *     send a grant through
     */
    public static boolean originIsResumable(Origin origin) {
        return origin == Origin.TURN;
    }

    /**
     * The refusal an ending a grant does not continue gets, in its own words.
     *
     * <p>Three different situations and three sentences, because an operator
     * does entirely different things about them and one message covering all
     * three would tell them which rule fired and not why.
     */
    private static void requireContinuable(String id, TurnRecord stopped) {
        if (CONTINUABLE.contains(stopped.ending())) {
            return;
        }
        String opening = "conversation " + id + "'s last turn ended " + stopped.ending() + ", ";
        throw new Refused(opening + switch (stopped.ending()) {
            case ANSWERED -> "so there is nothing to continue. The run reached an answer and"
                    + " stopped because it was finished. What comes next is another utterance,"
                    + " not more allowance for one that is over.";
            case STUCK -> "which is a run that kept asking for the same call and was stopped for"
                    + " it. Allowance was not the constraint, so granting more would buy the"
                    + " same loop again — which is offering to waste it. What that run needs is"
                    + " a different question, not a longer one.";
            case CALL_FAILURES -> "which is a run that kept writing tool calls as text instead of"
                    + " making them and was stopped for it. Allowance was not the constraint, so"
                    + " granting more would buy the same failure again. What that run needs is a"
                    + " new utterance, not a longer turn.";
            default -> "which can stop a run in the middle of a turn and leave a tool call with"
                    + " no result. A run continued from that history would be shown a call it"
                    + " made and no answer to it, and this server does not yet reconcile one."
                    + " The endings a grant continues are " + CONTINUABLE + ", which all stop at"
                    + " a turn boundary where the history is whole.";
        });
    }

    /**
     * Raise what the conversation may spend, or leave it exactly as it is.
     *
     * <p><b>The identical refusal {@code POST
     * /v1/jobs/&#123;id&#125;/limits} applies and for the identical reason</b>,
     * which is about the archive rather than about the run: a conversation's row
     * carries {@code budget_total} and {@code budget_spent} with {@code
     * conversations_spent_within_budget} between them, so a total below the
     * spending is a write-back Postgres refuses and a conversation that loses
     * the record of what it spent. Two accounting stories for one conversation
     * would be worse than one reused.
     *
     * <p>The new total is written onto the row when this turn ends, through the
     * same {@code turnEnded} every turn goes through — the raise is a decision
     * about the conversation and not about one run, which is what {@code
     * Limits#move} already relies on.
     *
     * <p>Read {@code spent()} once and compare against that: nothing else is
     * running, so this is a fact rather than a race, and naming a number in a
     * message that a second read could contradict is a habit worth not having.
     *
     * <p><b>The second refusal, and why it is caught here rather than at the
     * door.</b> {@code Budget.changeTo} throws {@code IllegalStateException} for
     * a conversation whose allowance has no ceiling — there is nothing for a
     * number to raise — and that sentence is written for exactly the person
     * sending this request, so it belongs in a 400 and not in a 500. {@code
     * Budget.changeToOrRefuse} catches both of {@code changeTo}'s exceptions
     * around the call itself and answers 400; this is that catch, in the
     * conversation-shaped door's own equivalent method. It is <em>not</em> done
     * by widening {@code ConversationController.resume}'s catch, which wraps the
     * whole of {@link #resume}: a rule that broad would turn every {@code
     * IllegalStateException} thrown anywhere under a resumption — the archive,
     * the compactor, the job store — into "you sent a bad request", which is the
     * blanket {@code ApiExceptionHandler} says in its own javadoc it removed for
     * being narrow only in its description.
     *
     * <p>The refusal is re-thrown as a {@link CallerFault} because from this
     * door it <em>is</em> one: {@code maxModelCalls} is an argument the caller
     * sent, and this conversation cannot take it. The message is passed through
     * unchanged so the operator reads {@code changeTo}'s sentence, which is the
     * one addressed to them.
     *
     * <p><b>{@code CallerFault} and not the {@code IllegalArgumentException}
     * both of these used to be.</b> That type has no row in {@code Faults}, so
     * its 400 lived entirely in {@code ConversationController.resume}'s catch:
     * right for the one caller that had it, and a 500 for the frame handler
     * that will call {@link #resume} without one. {@code Budget.of} and {@code
     * Budget.changeToOrRefuse} already answer the identical kind of refusal in
     * this type, so this is the door joining them rather than a new convention.
     */
    private static void grant(String id, Budget budget, Integer maxModelCalls) {
        if (maxModelCalls == null) {
            return;
        }
        int spent = budget.spent();
        if (maxModelCalls < spent) {
            throw new CallerFault("conversation " + id + " has already spent "
                    + spent + " model calls, and a budget of " + maxModelCalls + " cannot record"
                    + " that. A conversation's row holds what it was given and what it has"
                    + " spent, and the second may not exceed the first. Nothing was continued.");
        }
        try {
            budget.changeTo(maxModelCalls);
        } catch (IllegalStateException noCeiling) {
            throw new CallerFault(noCeiling.getMessage(), noCeiling);
        }
    }

    /**
     * What the harness tells a resumed run, as the message it opens with.
     *
     * <h2>Why the run is told, rather than the grant being given a deadline</h2>
     *
     * <p>A conversation stopped a week ago can still be continued, and the
     * workspace it read may have moved underneath it — its tool results are a
     * snapshot of a disk that no longer exists. Rather than invent an expiry,
     * the run is told when the history it is holding dates from, which is the
     * harness reporting useful intelligence to the model rather than guessing on
     * its behalf.
     *
     * <p><b>It says who is speaking</b>, on {@code Projection.NEVER_COMPLETED}'s
     * and {@code Compaction.ASK_FOR_A_SUMMARY}'s reasoning: everything else in
     * the user slot is something a person said, so a harness sentence sitting
     * there unattributed reads as one.
     *
     * <p>{@code last_turn_at} is when the last turn ended and the last turn is
     * the one being continued, so it is the moment the run stopped. A
     * conversation whose write-back failed has no such moment, and that is said
     * rather than filled in with the current time — a wrong timestamp here is a
     * model reasoning about staleness from a number nobody measured.
     */
    private static String continuing(ConversationRecord record) {
        return CONTINUING.formatted(record.lastTurnAt() == null
                ? "a moment this conversation did not record"
                : record.lastTurnAt().toString());
    }

    static final String CONTINUING = """
            This run is being continued. The run before it stopped at %s without reaching an \
            answer, and a person has since granted it more allowance. Everything above this \
            message is that run's own history, including every result its tool calls returned.

            Those results were read at the time above and describe things as they were then. \
            Nothing above needs doing again; anything that may have changed since is worth \
            checking rather than assumed.

            Carry on from where that run stopped. This message is from the harness that owns \
            this conversation, and nobody said it.""";

    /** What a resumption writes into the conversation about itself: prose about
     *  the machinery, in the third person, for whoever reads the conversation
     *  back. Nothing parses it, which is why it is a sentence and not a shape. */
    private static String resumptionNote(
            TurnRecord stopped, int asTurn, TurnCap under, Integer maxModelCalls) {
        String note = "Turn " + stopped.ordinal() + " ended " + stopped.ending() + " without"
                + " reaching an answer, and a person chose to continue it rather than let it"
                + " stand. This is that continuation, running as turn " + asTurn + " under a"
                + " cap of " + under.describe() + ", and it opened with everything turn "
                + stopped.ordinal()
                + " had learned — the results of the tools it called included.";
        return maxModelCalls == null
                ? note
                : note + " The conversation's whole model-call budget was raised to "
                        + maxModelCalls + " at the same time.";
    }

    /**
     * A conversation that is there and cannot take this turn.
     *
     * <p>Its own type, and not {@code archive.ArchiveRefusedException}, which
     * means the same thing at the same status. That class carries a list of
     * <em>every site</em> classified as a refusal, and every one of them is an
     * archive operation on a row; putting an agents-layer decision into it would
     * make the list describe two layers and be checkable at neither. Nothing was
     * refused by the archive here — no row operation was attempted at all.
     *
     * <p>Two situations reach this and each gets its own sentence, which is the
     * rule the endings are built on: a conversation with nothing left and a
     * conversation already speaking are opposite facts, and an operator does
     * entirely different things about them. They share a status because 409 is
     * what both mean — the row is there and the server will not do this to it.
     */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message);
        }
    }
}
