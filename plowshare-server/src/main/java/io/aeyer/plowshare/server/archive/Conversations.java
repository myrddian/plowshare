package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The domain rules a conversation is read and continued under, above {@link
 * ConversationStore} and {@link TurnStore} and below {@code
 * ConversationController}.
 *
 * <h2>Why these five left the controller and not the other ten</h2>
 *
 * <p>{@code ConversationController} holds two shapes of private helper: one
 * parses a request field and refuses it — {@code RequestedLifecycle} and
 * {@code RequestedHome}'s shape, now the {@code requests} package's job —
 * and the other reads a store and <em>decides</em> something about what it
 * read, refusing with a reason that argues from the data rather than from
 * the request. {@link
 * #whoAnswered}, {@link #whoToProjectAs}, {@link #whoToContinueAs}, {@link
 * #requireExistsOrThereIsNo} and {@link #aTurnThatHappened} are the second
 * shape, and moving them here is what lets a dispatcher that is not {@code
 * ConversationController} reach the same rules without going through HTTP to
 * get them.
 *
 * <h2>Two doors onto the existence rule, and one sentence behind them</h2>
 *
 * <p>{@link #requireExistsOrThereIsNo} takes the whole trailing phrase and
 * {@link #requireExists} takes a noun, and the second is written in terms of
 * the first rather than beside it. The shorter door came first and fit four of
 * the six readings; {@code turns} and {@code compactions} say "there is no
 * history to read back" and "there is no transcript to read the seams of",
 * which no noun substituted into "{@code X} to read" produces, and so those two
 * went on spelling the exception inline — one sentence said twice, in a class
 * that exists so it is said once. The wider door is what let them stop.
 * <b>A third way to refuse a missing conversation would undo that</b>: whatever
 * a new reading needs to say, it says it as a phrase through {@link
 * #requireExistsOrThereIsNo}.
 *
 * <h2>Fetching and deciding are split, and that is why this class is testable
 * without a container</h2>
 *
 * <p>Every other class under {@code archive} is a store, and every test of one
 * stands up a real Postgres through Testcontainers, because a store's whole job
 * is the query. The rule {@link #whoToContinueAs} enforces is not a query — it
 * reads a conversation's turns and then decides which agent may continue them
 * — so the decision is pulled out as {@link #continuedAs}, a package-private
 * static that takes the data {@link #whoToContinueAs} would otherwise have
 * fetched and decides with no I/O of its own. {@link #whoAnswered} splits the
 * same way, into {@link #answeredBy}. {@code ConversationsTest} is a unit test
 * of both decisions alone, built from plain {@link TurnRecord}s — including the
 * branch that prefers a conversation's own {@code agent} column over its last
 * turn, which no controller test reaches: every {@code ConversationRecord}
 * {@code ConversationControllerTest} builds names no agent of its own. The
 * fetching each keeps stays covered by the controller tests that already
 * exercise this class through {@code ConversationController}.
 *
 * <h2>{@link CallerFault} and not {@code BadRequestException}</h2>
 *
 * <p>{@code BadRequestException} belongs to the HTTP surface — {@code
 * ApiExceptionHandler} maps it, and only a controller may throw it. This class
 * has no request and no status of its own, so a refusal here is a {@link
 * CallerFault}: the same 400 by the time it reaches a caller, thrown by code
 * that does not import HTTP to say so.
 */
@Service
public final class Conversations {

    private final ConversationStore conversations;
    private final TurnStore turns;

    public Conversations(ConversationStore conversations, TurnStore turns) {
        this.conversations = conversations;
        this.turns = turns;
    }

    /**
     * Which agent this conversation's next prompt would carry the block of, when
     * the caller did not say. Fetches this conversation's own agent column and
     * delegates the decision to {@link #answeredBy}.
     *
     * <h2>What V17 changed here, and the part of the parameter it did not
     * change</h2>
     *
     * <p>This read's javadoc used to say the agent is a parameter <em>because
     * the conversation has none</em>, and {@code ContextView} named "nothing
     * records which agent answered a turn" as the reason it refuses to split a
     * measurement. Both facts have expired: {@code conversations.agent} names
     * the one agent a machine's log is, and {@code turns.agent} names who
     * answered each turn of a person's.
     *
     * <p><b>So the default changed and the parameter did not.</b> The two are
     * different questions and only one of them the server can answer. "What
     * would this conversation's prompt cost if I asked {@code X} next" is a
     * question about an agent the caller is choosing, and a conversation that
     * holds two agents' turns has no opinion about which comes next — so the
     * parameter stays, and it stays an override rather than a hint. What the
     * server <em>can</em> answer is "what has this conversation been costing",
     * and that is the agent that last answered in it; a caller that omits the
     * parameter used to get no prefix at all, which was an absence standing in
     * for a fact nobody had recorded.
     *
     * <p><b>The conversation's own column first, and the last turn's second.</b>
     * A machine's log is one agent's from end to end and says so on its row
     * before it has had a turn at all — which is every delegated child between
     * being opened and its one run finishing. A person's conversation names none,
     * so the last turn is the only answer there is.
     *
     * @return the agent to price, or {@code null} for a conversation that has
     *     had no turn and names no agent — a person's, opened and not yet spoken
     *     into, which answers without a prefix exactly as it did before
     */
    public String whoAnswered(String id, List<TurnRecord> spoken) {
        String named = conversations.find(id)
                .map(ConversationRecord::agent)
                .orElse(null);
        return answeredBy(named, spoken);
    }

    /**
     * The decision {@link #whoAnswered} makes, pulled out to take its data
     * rather than fetch it — pure, static and package-private so a test can
     * exercise it with plain {@link TurnRecord}s and no database.
     *
     * @param named this conversation's own {@code agent} column, as {@link
     *     #whoAnswered} already read it
     * @param spoken this conversation's turns, in order, as {@link #whoAnswered}
     *     already read them
     * @return the agent to price, or {@code null} for a conversation that has
     *     had no turn and names no agent
     */
    static String answeredBy(String named, List<TurnRecord> spoken) {
        if (named != null) {
            return named;
        }
        for (int i = spoken.size() - 1; i >= 0; i--) {
            // Backwards, and skipping the nulls rather than stopping at one: a
            // turn written before V17 records no agent, and a conversation that
            // predates the column and has been spoken into since should be
            // priced from the turn that knows rather than not at all.
            String answered = spoken.get(i).agent();
            if (answered != null) {
                return answered;
            }
        }
        return null;
    }

    /**
     * Which agent a projection is computed against: the one the caller named,
     * or the one that answered — and a refusal when there is neither.
     *
     * <h2>Why {@link #whoAnswered} could not simply be called twice</h2>
     *
     * <p>{@code context} and {@code projection} ask the same question and can
     * do different things with the same silence. A conversation with no turn
     * and no agent column answers {@code context} with no prefix, which {@code
     * ContextView} can hold; a projection has nothing to build a system message
     * out of, so it is refused. That refusal was the last decision {@code
     * ConversationController} made from data rather than from a request field,
     * spelled inline as a {@code BadRequestException} — which is to say the
     * {@code conversation.projection} frame would have had to restate it or go
     * without it. It argues from what the conversation records, so it belongs
     * beside {@link #answeredBy} rather than in a surface.
     *
     * @param asked the {@code agent} the caller named, or null. <b>An override
     *     and not a hint</b>, exactly as it is for {@link #whoAnswered}: a
     *     caller asking what {@code X} would be sent is asking about {@code X}
     * @param spoken this conversation's turns, in order, as the caller already
     *     read them
     * @return the agent whose prompt the projection opens with, never null
     * @throws CallerFault if the caller named none and the conversation
     *     records none
     */
    public String whoToProjectAs(String id, String asked, List<TurnRecord> spoken) {
        String named = asked != null ? asked : whoAnswered(id, spoken);
        if (named == null) {
            throw new CallerFault(
                    "conversation " + id + " has had no turn and names no agent, so there is no"
                            + " agent whose prompt this next turn's projection would open with."
                            + " Name one with 'agent' to ask what it would send.");
        }
        return named;
    }

    /**
     * Which agent continues a stopped run: the one that answered it. Fetches
     * this conversation's turns and delegates the decision to {@link
     * #continuedAs}.
     *
     * <h2>Why this is read now and was taken from the caller before</h2>
     *
     * <p>{@code ConversationController.resume} used to take the agent in its
     * body, and {@code ResumeRunRequest.agent} said exactly why: "a
     * conversation names no agent ... and {@code turns} holds no column saying
     * which one answered, so a grant cannot recover it and says instead". {@code
     * turns.agent} is that column, and the reason for the field has gone with
     * it.
     *
     * <p><b>What that removes is a way to be wrong that nothing noticed.</b> A
     * resumption opens with the stopped run's whole history — every tool result
     * it collected, in the shape that agent's own {@code tools:} line produced —
     * and continuing it under a different agent puts one agent's working in
     * front of another, with a different system prompt and possibly a different
     * set of tools to answer it with. Nothing refused that and nothing could:
     * the request named an agent, the agent existed, and the run started.
     *
     * <p><b>A body that names the same agent is accepted and one that names a
     * different agent is refused</b>, rather than the recorded one silently
     * winning. A caller sending a name is asserting something about the run it
     * is continuing, and an assertion this server knows to be false is worth a
     * message rather than a quiet correction — the same position {@code
     * agents.Runs.start} takes on a body carrying both a conversation and a
     * project.
     *
     * <p><b>The caller's own field is kept for the rows the column could not
     * backfill.</b> A turn written before V17 records no agent and nothing
     * anywhere can recover it, so a grant continuing one has to be told — which
     * is the pre-V17 arrangement, surviving for exactly the conversations that
     * predate the column and for no others.
     *
     * @param asked the agent the caller named to continue as, or {@code null}
     *     for a body that left it out
     * @return the agent's name, for {@code RequestedAgent} to resolve
     * @throws CallerFault if neither the turn nor the caller says, or if they
     *     disagree
     */
    public String whoToContinueAs(String id, String asked) {
        return continuedAs(id, asked, turns.forConversation(id));
    }

    /**
     * The decision {@link #whoToContinueAs} makes, pulled out to take its data
     * rather than fetch it — pure, static and package-private so a test can
     * exercise it with plain {@link TurnRecord}s and no database.
     *
     * @param spoken this conversation's turns, in order, as {@link
     *     #whoToContinueAs} already read them
     * @throws CallerFault if neither the last turn nor the caller says which
     *     agent answered, or if they disagree
     */
    static String continuedAs(String id, String asked, List<TurnRecord> spoken) {
        String answered = spoken.stream()
                .reduce((first, second) -> second)
                .map(TurnRecord::agent)
                .orElse(null);
        if (answered == null) {
            if (asked == null) {
                throw new CallerFault(
                        "conversation " + id + "'s last turn does not record which agent"
                                + " answered it, which is true of every turn written before this"
                                + " server had a column for it, so 'agent' has to say. Read the"
                                + " conversation back at GET /v1/conversations/" + id + "/turns"
                                + " if you are not sure which one it was.");
            }
            return asked;
        }
        if (asked != null && !asked.equals(answered)) {
            throw new CallerFault(
                    "conversation " + id + "'s last turn was answered by '" + answered + "' and"
                            + " this asks to continue it as '" + asked + "'. A resumption opens"
                            + " with that run's whole history -- every tool result it collected,"
                            + " in the shape that agent's own tools produced -- so continuing it"
                            + " as another agent would put one agent's working in front of"
                            + " another under a different system prompt. Leave 'agent' out to"
                            + " continue as '" + answered + "', which is what this endpoint now"
                            + " does on its own.");
        }
        return answered;
    }

    /**
     * The conversation exists, or the read is a 404 naming the id and naming
     * what there is none of.
     *
     * <p>Six reads on {@code ConversationController} ask this, and the reason is
     * theirs word for word: a store answers the empty page both for a
     * conversation nobody has spoken into and for one that was never opened, and
     * those are opposite facts. It matters most on {@code chat} and {@code
     * trajectory} — a client restoring a saved id would render an empty
     * trajectory that looks like a run which did nothing, rather than one that
     * never existed. {@code conversation.follow} asks it too, and is not a read:
     * "there is no log to follow" — a follow of an id nothing opened would wait
     * on pushes for a log that never grows.
     *
     * <p><b>The tail is the caller's whole phrase and not a noun</b>, which is
     * the only shape that fits all six. Four of them read a thing and say "there
     * is no {@code X} to read", and {@link #requireExists} is the shorter way of
     * asking for those. The other two do not: {@code turns} says "there is no
     * history to read back" and {@code compactions} says "there is no transcript
     * to read the seams of". Both spelled the {@link ArchiveException} inline
     * until this method existed, which is how a frame surface calling the
     * service would have come to refuse the same request in different words
     * than the endpoint does.
     *
     * @param thereIsNo everything after "so there is no", as the caller would
     *     say it — {@code "history to read back"}, not {@code "history"}
     */
    public void requireExistsOrThereIsNo(String id, String thereIsNo) {
        if (conversations.find(id).isEmpty()) {
            throw new ArchiveException(
                    "no conversation has the id " + id + ", so there is no " + thereIsNo);
        }
    }

    /**
     * The conversation this agent is still having in one tier, or nothing
     * because it is not having one yet.
     *
     * <h2>Which conversation, and why it is derived rather than stored</h2>
     *
     * <p>A bot has one conversation and talking to it continues that one. Which
     * one is <b>the newest in this tier that the agent has answered in</b> — a
     * derivation over rows that already exist, which is the reason there is no
     * {@code current_conversation} column anywhere: a stored pointer can
     * disagree with what the table holds, and a derivation cannot. With no way
     * to open a second thread there is never more than one to find.
     *
     * <p><b>This is a view of an append-only log and not a session being
     * restored.</b> What comes back is an id; what the log holds under it is
     * whatever {@code turns} and {@code compactions} hold <em>now</em>. A
     * conversation that has been compacted comes back with its seams in it, and
     * nothing here or anywhere else can unfold one.
     *
     * <p>The three filters and the ordering are {@link
     * ConversationStore#latestFor}'s, including the finding this rule had to be
     * built around: {@code conversations.agent} is NULL for exactly a person's
     * conversation, so who answered is {@code turns.agent} and the match is over
     * the turns.
     *
     * <p><b>A door and not a decision</b>, which makes it the thin method in a
     * class of rules. It is here because the rule above is a domain rule — the
     * frame surface asks "which conversation does this bot continue", not "run
     * me this query" — and a handler reaching {@link ConversationStore} for it
     * would be the second place that rule is written the day a second caller
     * wants it.
     *
     * @param home the tier, since memory and conversations are both per tier
     * @param agent who is answering, as {@code agent.list} declared the name
     * @return the conversation to continue, or empty for an agent with no
     *     conversation here — which is the ordinary first run, and which a
     *     caller answers by opening one on the first message rather than by
     *     refusing
     * @throws ValidationException if the agent cannot be named
     */
    public Optional<ConversationRecord> toContinue(Home home, String agent) {
        return conversations.latestFor(home, agent);
    }

    /**
     * The conversation exists, or the read is a 404 naming the id — for a
     * reading whose sentence ends "{@code <reading>} to read".
     *
     * <p>{@code chat}, {@code trajectory}, {@code context} and {@code
     * projection} are the four that fit, and this is what they ask through.
     * <b>It builds no sentence of its own</b>: it finishes the phrase and hands
     * it to {@link #requireExistsOrThereIsNo}, so one place knows how a missing
     * conversation is said rather than two that agree until one is edited.
     *
     * @param reading what the caller asked for, so the sentence names it
     */
    public void requireExists(String id, String reading) {
        requireExistsOrThereIsNo(id, reading + " to read");
    }

    /**
     * The asked-for turn is one this conversation actually had, or the read is a
     * 400 saying which turns there are.
     *
     * <h2>Refused and never clamped</h2>
     *
     * <p><b>Answering the nearest turn would invent a record.</b> This endpoint
     * is read by somebody auditing what a turn was shown, so a projection
     * returned under a turn number that never existed is worse than no answer:
     * it is a history, correctly assembled, of a moment that did not happen, and
     * nothing in the response would say so. The same argument rules out
     * answering an empty list — {@code EntryStore.thatProjectedAt} would happily
     * return one for turn 900, and it would read as "that turn was shown
     * nothing" rather than as "there was no such turn".
     *
     * <p><b>Both ends, and the message names both.</b> Above the last is the
     * case that occurs; below the first is the same question with the same
     * non-answer, and refusing one while clamping the other would be a rule
     * nobody could predict. The bound comes from {@code turns} rather than from
     * the entries, because a turn is what {@code turns} is the record of — {@code
     * TurnStore.forConversation} answers in ordinal order, so the ends of that
     * list are the ends of the conversation.
     *
     * <p><b>A turn in the list and not a turn between the ends.</b> The ends are
     * what the message names; what it <em>checks</em> is membership, because the
     * two questions come apart the moment a conversation's ordinals are not
     * contiguous, and a swallowed {@code turns.record} makes them so. The
     * consequence of getting it wrong is not a nicer message: a number inside the
     * range with no row behind it goes on to {@code EntryStore.thatProjectedAt},
     * which refuses a turn the log holds nothing for by throwing, and the caller
     * is handed a 500 for a question this method had already promised to answer
     * with a 400.
     *
     * <h2>A turn still running is refused, and that is the intended answer</h2>
     *
     * <p><b>A turn writes its {@code turns} row when it closes</b> and writes its
     * utterance entry when it opens, so a turn in flight has entries and no row
     * — and this refuses it, naming the last turn that finished. That is the
     * behaviour wanted rather than a gap in it. The screen is an audit of what a
     * turn was shown, and a turn mid-run is still collecting the things it will
     * be shown; answering would hand back a prompt that is about to be true of a
     * different list, with nothing in the response to say it was provisional.
     * The refusal names the range, so a caller polling a running conversation
     * sees the turn appear in the range the moment it is a settled fact.
     *
     * <p>Deciding it the other way is a change to this method and to nothing
     * else, since {@code EntryStore.thatProjectedAt} needs only an entry
     * belonging to that turn and the in-flight turn's utterance is already one.
     * What would have to come with it is a way for the answer to say the turn
     * has not finished, which {@code ProjectionView} has no field for today.
     *
     * @param spoken this conversation's turns, in order, as the caller already
     *     read them — not re-read here, because the two reads could disagree and
     *     the one the answer is computed from is the one to check
     */
    public void aTurnThatHappened(String id, List<TurnRecord> spoken, int turn) {
        if (spoken.isEmpty()) {
            throw new CallerFault(
                    "conversation " + id + " has had no turn at all, so there is no turn " + turn
                            + " to read what was shown at. Ask without 'turn' for what its next"
                            + " prompt would carry.");
        }
        int first = spoken.getFirst().ordinal();
        int last = spoken.getLast().ordinal();
        // Membership and not `first <= turn <= last`. The two agree only while
        // the ordinals are contiguous, and nothing enforces that: a turn whose
        // `turns.record` was refused -- logged and stepped over by
        // `Compaction.TurnTranscript.closed`, so that an audit write cannot take
        // a turn's work with it -- leaves a hole inside the range. A hole read as
        // a turn reaches `EntryStore.thatProjectedAt`, which throws
        // IllegalStateException for a turn the log holds nothing for, and the
        // caller gets a 500 where this method's own javadoc promises a 400
        // naming the turns there are. The list is the one already read, so the
        // scan costs a conversation's turns and no second query.
        if (spoken.stream().noneMatch(had -> had.ordinal() == turn)) {
            // "recorded ... numbered" and not "has turns first to last": the
            // ends are the ends, and a conversation with a hole in it has fewer
            // turns than the range spans. A message promising every number
            // between them would be the one contradicted by the case this check
            // exists for.
            throw new CallerFault(
                    "conversation " + id + " recorded " + spoken.size() + " turns, numbered "
                            + first + " to " + last + ", and turn " + turn + " is not one of them,"
                            + " so there is nothing to read what was shown at. Ask for a turn this"
                            + " conversation had, or without 'turn' for what its next prompt would"
                            + " carry.");
        }
    }
}
