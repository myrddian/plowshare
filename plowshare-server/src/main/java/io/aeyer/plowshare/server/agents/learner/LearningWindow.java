package io.aeyer.plowshare.server.agents.learner;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the learner is shown, and — this is the whole of the design — what the
 * <em>system</em> decided to show it.
 *
 * <h2>THE SYSTEM COMPUTES THE WINDOW. THE AGENT DOES NOT CHOOSE IT</h2>
 *
 * <p>The alternative was to give the learner a reader and let it decide what to
 * look at, and it was refused for four reasons. The second is the load-bearing
 * one.
 *
 * <ol>
 *   <li><b>Deterministic and testable.</b> A window an agent chooses is a model
 *       decision: not reproducible, not auditable, and free to skip a span or
 *       read one twice. A window the system computes is a query with a test
 *       under it, and every test in {@code LearningWindowTest} is a sentence
 *       about policy that would otherwise be a sentence in a prompt.
 *   <li><b>{@code learned_at} stays a fact the system owns.</b> If the agent
 *       chose its own window, marking a row learned would depend on the agent
 *       <em>reporting</em> what it read — a marker exactly as reliable as a
 *       model's self-report of its own attention. Because the window is handed
 *       over, {@link Learner} marks precisely {@link #ordinals()} and the state
 *       machine stops depending on good behaviour.
 *   <li><b>The policy lives in code and not in a prompt.</b> This repository has
 *       measured twice that behaviour carried in prompt text moves invisibly —
 *       the {@code file_grep} sentence, and redemption going 5/5 to 0/5 on
 *       paragraph order ({@code implementation rationale
 *       .md}). What is worth learning from, and in what order, must not live
 *       where a model can reinterpret it.
 *   <li><b>It is the only way to prioritise</b>, which is how the ejection tag
 *       gets an effect without becoming a gate. See {@link #rank}.
 * </ol>
 *
 * <h2>What a finished tree changes, which is more than the order</h2>
 *
 * <p>A tree that takes no further turn does not merely go to the front: its
 * <b>live</b> rows become eligible at all. That is the correction the ranking
 * on its own could not make, and the reason is arithmetic rather than policy —
 * {@link #rank} orders a shortlist and cannot put a conversation on one. A run
 * that never folds supersedes nothing, so on a folded-only queue it had no row
 * in the shortlist and {@code archived} and {@code to_be_ejected} could only
 * reorder conversations that had folded something. They were decorative for
 * exactly the shape they were introduced for, and that shape is the common one:
 * tool results do not push a fold, so "ask a question, read one file, answer,
 * done" is a whole run with nothing superseded in it.
 *
 * <p>The ground is the premise "only folded" rested on and not a new policy. A
 * live row is skipped because it is still in front of the model and there is a
 * later window for it — and {@code agents.Turn.speak} refuses a turn into
 * anything that is not {@code ACTIVE}, so for every other state there is no
 * later window and the skip is a loss. {@code EntryStore.awaitingTheLearner}
 * asks {@link ConversationLifecycle#acceptsWork} of the <em>root</em> this
 * class resolved, so a branch of a live tree stays out however long it has been
 * sitting there.
 *
 * <h2>Three triggers, one provider</h2>
 *
 * <p>Every trigger says the same thing — <em>there is new folded material</em> —
 * and nothing about where. What they mean is a difference in urgency, and it is
 * this class that reads it off the tree's lifecycle rather than the caller that
 * declares it:
 *
 * <table>
 *   <caption>What each trigger means for the window</caption>
 *   <tr><td>a fold</td>
 *       <td>material is about to become invisible — extract before it does</td></tr>
 *   <tr><td>{@code archived}</td>
 *       <td>a person called this done, so its span is finished — everything it
 *       holds becomes eligible, and it goes ahead of anything live</td></tr>
 *   <tr><td>{@code to_be_ejected}</td>
 *       <td>the same, and last chance before the payload leaves; it goes to the
 *       front</td></tr>
 * </table>
 *
 * <p><b>One conversation and not one tree, and it is a bound rather than a
 * disagreement with the design.</b> The unit of work is a tree — a person's turn
 * plus everything delegated beneath it — and that is exactly what {@link #rank}
 * ranks: a conversation is chosen by the state of the tree it belongs to. What a
 * single window then holds is one conversation's rows, because {@code ordinal}
 * and {@code turn_ordinal} only order a log within one conversation, and a
 * window that interleaved several would be handing a model a transcript nothing
 * in this server can produce. The rest of the tree is drained by the windows
 * after this one; the queue is what makes that safe, since a row already given
 * is never offered again.
 *
 * @param conversationId the conversation this material came from
 * @param home the project a memory formed from it belongs to, which is the
 *     conversation's own and never the root's — a delegated child may be run in
 *     a different project from its parent, and what was said in it was said
 *     there
 * @param standing the state of the tree this conversation belongs to, which is
 *     why this window was chosen ahead of the others. Never null: {@code
 *     conversations_a_root_is_where_the_lifecycle_lives} guarantees a root
 *     carries one
 * @param entries what was handed over, in conversation order. Never empty — a
 *     window with nothing in it is {@link Optional#empty()} from {@link #next},
 *     because "there was nothing to learn from" and "here is nothing" are
 *     different answers and only the first is true
 * @param awaiting how many rows that conversation had waiting when this window
 *     was taken, which is at least {@link #shown()}. Both numbers travel, for
 *     {@code ResultTools.Listing}'s reason: a short answer with no total cannot
 *     be told apart from the end of the queue
 */
public record LearningWindow(
        String conversationId,
        Home home,
        ConversationLifecycle standing,
        List<EntryRecord> entries,
        int awaiting) {

    /**
     * How many conversations are ranked before one is chosen.
     *
     * <p>A shortlist to rank and not a work list to get through. The read behind
     * it is ordered oldest-material-first so nothing starves, and this is how far
     * down that ordering a tree marked for ejection can be and still overtake
     * everything above it. It costs one indexed walk per candidate, which is what
     * bounds it: a number large enough to make a pass expensive before it has
     * asked a model anything would be paying for the ranking rather than for the
     * learning.
     */
    static final int MOST_CONSIDERED = 20;

    /**
     * The most entries one window holds.
     *
     * <p><b>A bound on what a model can think about in one call</b>, which is
     * {@code FileTools.MAX_READ_LINES}' kind of number and not a transport's.
     * <b>It is a judgement and not a measurement, and saying so is better than a
     * test that appears to pin it</b> — {@code ResultTools.Listing.MOST_LISTED}
     * makes the same admission. What it is reasoned from is what a window is
     * for: a span of conversation somebody could read in one sitting and answer
     * one question about. Forty entries is roughly twenty exchanges, which is
     * more than a fold usually covers and less than a long conversation holds.
     *
     * <p>A window that stopped here rather than at {@link #MOST_CHARACTERS} is
     * not a failure and is not retried: the rest is still in the queue, and the
     * next window takes it.
     */
    static final int MOST_ENTRIES = 40;

    /**
     * And the most characters, because one bound without the other is not a
     * bound.
     *
     * <p>{@code EntryStore.MOST_CHARACTERS_PER_ENTRY} makes the same argument
     * about a page: forty entries that were each a screen of prose is a
     * different quantity from forty that were each a paragraph, and only the
     * character count is what the model actually pays for. 24 000 is well under
     * {@code FileTools.MAX_DISPLAY_CHARS} — the learner is reading a
     * conversation and not a file — and leaves a small model room for its own
     * instructions and its answer beside it.
     *
     * <p><b>An entry is never split to fit.</b> Whatever is handed over is handed
     * over whole, because {@code learned_at} is a claim that the learner was
     * shown this row and half a row would make that claim false. An entry larger
     * than this cap on its own is therefore taken alone rather than skipped: a
     * window that refused it would leave it at the head of the queue for ever,
     * blocking everything behind it, which is the one way this design could
     * deadlock.
     */
    static final int MOST_CHARACTERS = 24_000;

    public LearningWindow {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(standing, "standing");
        entries = List.copyOf(entries);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "a learning window holds the rows it handed over, and an empty one is the"
                            + " absence of a window rather than a window of nothing");
        }
        if (awaiting < entries.size()) {
            throw new IllegalArgumentException(
                    "a window cannot hold more rows than were waiting: " + entries.size()
                            + " shown against " + awaiting + " waiting");
        }
    }

    /**
     * The next window this server would give the learner, or empty if nothing is
     * waiting.
     *
     * <h2>How the one conversation is chosen</h2>
     *
     * <p>The queue answers which conversations hold unlearned material — folded,
     * or anything at all in a tree nobody can speak into again — oldest first.
     * Each is resolved to the root of its tree, one indexed walk up {@code
     * parent_id}, and ranked by that root's lifecycle, so a tree somebody marked
     * for ejection overtakes a tree somebody archived, which overtakes everything
     * that is merely live. Ties keep the queue's own order, so a pass over an
     * unchanged database gives the same window twice.
     *
     * <p><b>The root is read once and used twice</b>, which is what keeps the
     * shortlist and the window from disagreeing: the same {@code standing} that
     * decided the rank is handed to {@code EntryStore.awaitingTheLearner} and
     * decides what that conversation may offer. A tree archived between the two
     * reads simply offers more than the shortlist expected, and one unarchived
     * between them offers less — which is the empty case below, already handled
     * as a race.
     *
     * <p><b>A candidate whose root cannot be read is skipped and not raised
     * on.</b> Nothing in this server deletes a conversation, so the only way to
     * reach that is a tree deeper than {@code ConversationStore
     * .DEEPEST_DELEGATION}, which is a database with a cycle in it; a pass that
     * stopped on one would stop for every other conversation on the server too.
     *
     * @param entries the log
     * @param conversations where a tree's state is read from
     * @return the window, or empty when nothing is waiting anywhere
     */
    public static Optional<LearningWindow> next(
            EntryStore entries, ConversationStore conversations) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(conversations, "conversations");

        String chosen = null;
        ConversationLifecycle standing = null;
        int best = Integer.MAX_VALUE;
        for (String candidate : entries.conversationsAwaitingTheLearner(MOST_CONSIDERED)) {
            Optional<ConversationRecord> root = conversations.rootOf(candidate);
            if (root.isEmpty()) {
                continue;
            }
            int rank = rank(root.get().lifecycle());
            if (rank < best) {
                best = rank;
                chosen = candidate;
                standing = root.get().lifecycle();
            }
        }
        if (chosen == null) {
            return Optional.empty();
        }
        Optional<ConversationRecord> conversation = conversations.find(chosen);
        if (conversation.isEmpty()) {
            return Optional.empty();
        }
        List<EntryRecord> waiting = entries.awaitingTheLearner(chosen, standing, MOST_ENTRIES);
        if (waiting.isEmpty()) {
            // Read between the two queries and marked by another pass. Empty
            // rather than raised, and rather than falling through to the next
            // candidate: the trigger fires often, so the cheapest correct answer
            // to a race is to do nothing this time.
            return Optional.empty();
        }
        return Optional.of(new LearningWindow(chosen, conversation.get().home(), standing,
                upTo(waiting), entries.countAwaitingTheLearner(chosen, standing)));
    }

    /**
     * Which tree is mined first, and the only relationship there is between the
     * two workflows.
     *
     * <p><b>The ejection tag orders and never gates.</b> Learning does not hold up
     * an ejection and an ejection does not wait for a learner — {@code
     * archive.Retention} has never read {@code learned_at} and must not start —
     * but a tree whose payload is about to leave is the one whose material this
     * server will not get another chance at, so it goes to the front.
     *
     * <p>It is no longer the tag's <em>only</em> effect, and the difference is
     * worth naming. The tag also widens what that tree may offer, because a tree
     * that takes no further turn has no later window; that is eligibility and
     * this is order, and neither is a gate on the sweep.
     *
     * <p>{@code ARCHIVED} is next because a person said they were done with it:
     * its span is finished, and nothing further will be added to be mined later.
     * {@code ACTIVE} is the ordinary case, and it is last because the
     * conversation is still going — whatever this window misses, the next fold
     * offers again.
     *
     * <p>{@code EJECTED} is last of all rather than excluded. Its payloads are
     * gone, but its utterances and answers are not — nothing ejects those — so
     * there is still something to learn from, and it is simply the least urgent
     * material on the server: the deadline it had has already passed. Its live
     * rows are eligible on the same terms as an archived tree's, and for a
     * stronger version of the same reason: {@link
     * ConversationLifecycle#acceptsWork} is one question and not a list of the
     * two states somebody happens to <em>move</em> a conversation to.
     */
    private static int rank(ConversationLifecycle standing) {
        return switch (standing) {
            case TO_BE_EJECTED -> 0;
            case ARCHIVED -> 1;
            case ACTIVE -> 2;
            case EJECTED -> 3;
        };
    }

    /**
     * As many whole entries as fit in {@link #MOST_CHARACTERS}, and at least
     * one.
     *
     * <p>The "at least one" is the anti-deadlock rule and is argued on that
     * constant. Everything after the first is taken only if the whole of it
     * fits, so nothing is ever half-shown and then marked as seen.
     */
    private static List<EntryRecord> upTo(List<EntryRecord> waiting) {
        List<EntryRecord> held = new ArrayList<>();
        int characters = 0;
        for (EntryRecord entry : waiting) {
            int length = entry.content() == null ? 0 : entry.content().length();
            if (!held.isEmpty() && characters + length > MOST_CHARACTERS) {
                break;
            }
            held.add(entry);
            characters += length;
        }
        return held;
    }

    /** How many rows this window holds, against {@link #awaiting()}. */
    public int shown() {
        return entries.size();
    }

    /**
     * Exactly what {@link Learner} marks, and the reason this method exists
     * rather than the caller deriving a span: the marking names rows, so a row
     * that arrived after the window was taken cannot be swept into it.
     */
    public List<Integer> ordinals() {
        return entries.stream().map(EntryRecord::ordinal).toList();
    }

    /**
     * The last turn this window reaches, which is where the learner's own
     * {@code diagnostic} is filed.
     *
     * <p>An entry has to be filed against a turn — {@code
     * entries_belong_to_a_turn_numbered_from_one} — and the truthful number for a
     * note about a span is the far end of the span it covers.
     */
    public int through() {
        return entries.get(entries.size() - 1).turnOrdinal();
    }
}
