package io.aeyer.plowshare.server.archive;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One retention sweep: eject what was marked last time, then mark what policy
 * selects now.
 *
 * <h2>The trigger, which had nothing to build on</h2>
 *
 * <p>This server has <b>no scheduler</b> — the reference harness has cron and
 * this does not, and {@code V17__conversation_origin.sql} refuses a {@code
 * schedule} origin on exactly that ground. So the choice was between a timer
 * started at boot and an explicit operation, and it is <b>an explicit
 * operation</b>: {@code POST /v1/retention/sweep}. Four reasons, and the first
 * two are the ones that decide it.
 *
 * <ul>
 *   <li><b>A sweep deletes bytes, and a timer deletes them with nobody
 *       watching.</b> Every other periodic thing this server might have wanted
 *       is idempotent and reversible; this one ends with a file body that is not
 *       in the database any more. The first tick of a schedule an operator
 *       configured by accident is the tick that runs at three in the morning
 *       against a misconfigured export directory.
 *   <li><b>A timer at boot fires in the test suite.</b> This tree stands up
 *       Spring contexts against Testcontainers in dozens of tests, so a
 *       {@code @Scheduled} or a {@code ScheduledExecutorService} started from a
 *       bean would be running retention against a live database in every one of
 *       them — deleting fixture rows on a clock no test controls, which is the
 *       async-test-with-a-sleep failure this project forbids, arriving through
 *       the container instead of through a test.
 *   <li><b>Under presence there may be several servers on one database.</b> A
 *       timer per process is a sweep per process, all racing on the same rows.
 *       The compare-and-set in {@link ConversationStore#moveTo} means they
 *       cannot double-eject, but it does mean n copies of the work and n
 *       exporters writing over each other's files. An operation is triggered
 *       once, by whoever decides to.
 *   <li><b>The operator already has a scheduler.</b> cron, systemd timers, a
 *       Kubernetes CronJob, a console button, a person. Writing a worse one
 *       inside this server would be new machinery — a lifecycle to own, a
 *       cancellation story, an overlap guard — to replace something every
 *       deployment already has. What this server owes them is an operation that
 *       is safe to call twice, and that is what a sweep is.
 * </ul>
 *
 * <p>This is not the design "reading as though something is built": there is no
 * schedule here, no interval key, and no claim of one. The verb exists; when it
 * runs is not this server's business.
 *
 * <h2>Two stages, one call, and the window between them is the point</h2>
 *
 * <p>Mark, then export, then null — and <b>a sweep does not eject what it has
 * just marked</b>. It ejects what an <em>earlier</em> sweep marked, and then
 * marks what is newly eligible. That ordering is the whole of what the staged
 * path buys: a conversation the policy selected is in {@link
 * ConversationLifecycle#TO_BE_EJECTED} for at least one interval, holding all
 * its payloads, and anybody can cancel it back to {@link
 * ConversationLifecycle#ACTIVE} in that time. A sweep that marked and ejected in
 * one pass would have a "staged path" whose recoverable window is the few
 * milliseconds between two statements, which is not a window.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p><b>No learner and no fold condition.</b> Ejection is not gated on
 * learning, and the case that settles it is measurable rather than a preference:
 * tool results do not push a fold at all — {@code sent} and {@code added}
 * exclude them by design — so <em>ask a question, read one 100 000-character
 * file, answer, done</em> never folds, and that conversation holds the largest
 * payload in the system. A rule that reached payloads through folding would
 * reach every payload except the ones that matter most. Tagging owes nothing to
 * a fold, which is why it is what this is built on.
 *
 * <p><b>Nothing here deletes a CONVERSATION row.</b> Demote the record, eject
 * the payload. See {@code V19__conversation_lifecycle.sql}, which argues the
 * three reasons at length.
 *
 * <h2>And a third stage, which does delete</h2>
 *
 * <p>Job records are <b>pruned</b>: rows older than {@code
 * plowshare.jobs.retention-after-days} are deleted outright. That sentence used
 * to read "nothing here deletes a row" without the qualifier, and the qualifier
 * is the whole distinction rather than a hedge. A conversation is the record of
 * what was said, to and by a person, and a hole in it is worse than its size. A
 * job row holds no text at all — an identifier, an agent's name, a project, two
 * instants and three counts — and everything readable the run produced is in
 * another table under its own policy. So there is nothing to eject, nothing to
 * stage, and no lifecycle to move it through; {@code V24__jobs.sql} and {@link
 * RetentionPolicy#jobs()} carry the argument, including why reusing {@link
 * ConversationLifecycle}'s vocabulary here would be a mistake this project has
 * already been corrected for once.
 *
 * <p>It is <b>here</b> rather than in a second operation because every reason
 * the trigger is an explicit verb applies to it unchanged; {@code
 * implementation rationale} §2 says the same in one line — pruning belongs beside retention.
 */
public final class Retention {

    private static final Logger log = LoggerFactory.getLogger(Retention.class);

    private java.util.function.Predicate<String> digestCoverage = id -> true;
    public Retention protectingDigests(java.util.function.Predicate<String> coverage) {
        digestCoverage=coverage;
        return this;
    }
    private final ConversationStore conversations;
    private final EntryStore entries;
    private final JobLog jobs;
    private final PayloadExport exports;
    private final RetentionPolicy policy;
    private final Supplier<Instant> now;

    public Retention(
            ConversationStore conversations, EntryStore entries, JobLog jobs,
            PayloadExport exports, RetentionPolicy policy, Supplier<Instant> now) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.exports = Objects.requireNonNull(exports, "exports");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.now = Objects.requireNonNull(now, "now");
    }

    /**
     * Run one sweep and say what it did.
     *
     * <p><b>Safe to call twice, and that is what makes an explicit trigger
     * workable.</b> Ejecting is guarded by {@code ejected_at IS NULL} on every
     * row and by the transition table on every conversation, so a second call
     * against the same state finds nothing to do and reports nothing done. An
     * operator who is not sure whether last night's cron ran can simply run it.
     *
     * @return what was marked and what went, so an operator has an account of a
     *     run that removed data rather than a silent success
     */
    public SweepReport sweep() {
        Instant at = now.get();
        SweepReport ejected = ejectWhatWasMarked(at);
        int marked = markWhatPolicySelects(at);
        int pruned = pruneOldJobRecords(at);
        SweepReport report = new SweepReport(marked, ejected.conversations(), ejected.payloads(),
                ejected.characters(), pruned);
        log.info("retention sweep: {}", report.said());
        return report;
    }

    /**
     * The eject stage: every tree an earlier sweep, or a person, left marked.
     *
     * <p><b>Whatever its origin</b>, because by this point the decision has been
     * taken — a person marked their own conversation by hand, or a policy marked
     * a machine's log by age, and re-asking the origin here would be re-deciding
     * something that has been decided. {@link
     * ConversationStore#rootsAt(ConversationLifecycle)} is the read that says
     * so by not taking one.
     *
     * <p><b>The tree and not the root.</b> A delegated child's tool results are
     * the same file bodies its parent's are — a {@code code_reviewer} reading a
     * repository is the run that stores the most of anything in this server — so
     * a sweep that ejected only the root's payloads would leave the larger half
     * behind and mark the tree done. {@code ConversationStore.treeOf} is the
     * walk, and the lifecycle living on the root is what makes "the tree" a
     * thing there is one of.
     *
     * <p><b>The root moves to {@link ConversationLifecycle#EJECTED} last</b>,
     * after every payload under it is gone. A sweep interrupted halfway leaves
     * the root still marked, so the next one picks it up and finishes; a root
     * moved first would leave a conversation claiming its payloads were ejected
     * while holding them, and nothing would ever come back for them.
     */
    private SweepReport ejectWhatWasMarked(Instant at) {
        int trees = 0;
        int payloads = 0;
        long characters = 0;
        for (String root : conversations.rootsAt(ConversationLifecycle.TO_BE_EJECTED)) {
            if (conversations.treeOf(root).stream().anyMatch(id -> !digestCoverage.test(id))) {
                throw new ArchiveRefusedException("Retention refused for " + root
                        + ": a folded span has no durable digest. Run memory digest before ejecting it.");
            }
            for (String id : conversations.treeOf(root)) {
                ConversationRecord conversation = conversations.find(id).orElse(null);
                if (conversation == null) {
                    // Read between the two queries and gone -- which nothing in
                    // this server does, since nothing deletes a conversation.
                    // Skipped rather than raised: a sweep that stopped on it
                    // would leave the rest of a tree half ejected.
                    continue;
                }
                for (EntryRecord payload : entries.payloadsHeldBy(id)) {
                    String where = exports.write(root, conversation, payload, at);
                    if (entries.ejectPayload(id, payload.ordinal(), at, where)) {
                        payloads++;
                        characters += payload.content().length();
                    }
                }
            }
            conversations.moveTo(root, ConversationLifecycle.EJECTED);
            trees++;
        }
        return new SweepReport(0, trees, payloads, characters, 0);
    }

    /**
     * The mark stage: what each origin's policy selects, and nothing else.
     *
     * <p><b>{@link Origin#TURN} is not here and its absence is the policy.</b> A
     * person's conversation is never marked automatically — §5 of the design
     * says the person chooses and the export is explicit — so there is no key to
     * set and no age to configure, and a sweep run on a server full of people's
     * conversations marks none of them. What a person can do is mark their own,
     * through {@code PUT /v1/conversations/&#123;id&#125;/lifecycle}, and the
     * next sweep then ejects it like any other marked tree.
     *
     * <p><b>{@link Origin#DELEGATION} is not here either, and for the opposite
     * reason: it is already handled.</b> A child follows its root because the
     * lifecycle <em>is</em> the root's — there is no rule to apply and no row to
     * select. That is the whole return on putting the state on the root rather
     * than on every conversation.
     *
     * <p>So two origins have keys, and both are unset by default. See {@link
     * RetentionPolicy}, which argues why a retention age is the one number in
     * this server that does not get a shipped default.
     */
    private int markWhatPolicySelects(Instant at) {
        int marked = 0;
        for (Origin origin : List.of(Origin.CURATOR, Origin.MEMORY, Origin.SUBMISSION)) {
            Duration after = policy.ejectAfter(origin);
            if (after == null) {
                continue;
            }
            for (String root : conversations.roots(
                    origin, ConversationLifecycle.ACTIVE, at.minus(after))) {
                conversations.moveTo(root, ConversationLifecycle.TO_BE_EJECTED);
                marked++;
            }
        }
        return marked;
    }

    /**
     * The prune stage: job records older than the policy, deleted.
     *
     * <h2>Here rather than in a mechanism of its own</h2>
     *
     * <p>{@code implementation rationale} §2 decides it in one line — "pruning belongs
     * beside retention" — and the class javadoc above is the argument in full.
     * Every one of the four reasons the trigger is an explicit operation applies
     * unchanged to job rows: a prune deletes data and a timer deletes it with
     * nobody watching; a timer started from a bean fires in a suite that stands
     * up dozens of Spring contexts; several servers on one database is several
     * prunes racing; and the operator already has cron. A second mechanism would
     * be a second thing to get that wrong about, for no new argument.
     *
     * <h2>It is a delete, and it has no staged path</h2>
     *
     * <p>Not because staging was thought too expensive here, but because there
     * is nothing to stage. The window {@link #ejectWhatWasMarked} buys is a
     * window in which somebody can say no to losing a <em>payload</em>; a job
     * row has none — an identifier, an agent's name, a project, two instants and
     * three counts — so a mark-then-delete pass would be two sweeps' delay
     * protecting nothing. And there is deliberately no lifecycle column to mark
     * it with: {@link ConversationLifecycle} is the vocabulary of a conversation
     * archive, jobs are not conversations, and {@code V24__jobs.sql} records
     * that borrowing it here would be the same mistake an earlier draft of the
     * retention design was corrected for.
     *
     * <p><b>Unset means nothing is pruned</b>, on {@link RetentionPolicy}'s
     * reasoning: too large costs disk, too small deletes something, the two are
     * not symmetric, and there is no arithmetic for how many days a job record
     * is worth keeping.
     *
     * <p><b>Last, after both conversation stages</b>, and it does not depend on
     * either — the ordering is only so that a sweep interrupted partway has done
     * the stage that keeps a person's recoverable window intact rather than the
     * one that deletes.
     */
    private int pruneOldJobRecords(Instant at) {
        Duration after = policy.jobs();
        if (after == null) {
            return 0;
        }
        return jobs.pruneStartedBefore(at.minus(after));
    }

    /**
     * What one sweep did.
     *
     * @param marked how many trees this sweep selected for ejection, whose
     *     payloads are all still here until the next one
     * @param conversations how many trees it ejected, which were marked before
     *     it ran
     * @param payloads how many stored results it took the content out of
     * @param characters how many characters those held, which is the number an
     *     operator is actually trying to bring down
     * @param prunedJobs how many job records it deleted. <b>Deleted and not
     *     ejected</b>, and the report keeps the two words apart for the reason
     *     {@link RetentionPolicy#jobs()} sets out: an ejected payload leaves a
     *     row behind and a pruned job does not
     */
    public record SweepReport(
            int marked, int conversations, int payloads, long characters, int prunedJobs) {

        /** The one line an operator reads, and the one this class logs. */
        public String said() {
            return "marked " + marked + " conversation" + (marked == 1 ? "" : "s")
                    + " for ejection; ejected " + payloads + " stored result"
                    + (payloads == 1 ? "" : "s") + " holding " + characters
                    + " characters across " + conversations + " conversation"
                    + (conversations == 1 ? "" : "s")
                    // Named separately and never summed into the counts above.
                    // Ejecting empties a column and keeps the row; pruning
                    // removes the row. An operator reading one number for both
                    // would have no way to tell what is still there.
                    + "; pruned " + prunedJobs + " job record"
                    + (prunedJobs == 1 ? "" : "s");
        }
    }
}
