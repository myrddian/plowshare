package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.hooks.ApprovalAnswer;
import io.aeyer.plowshare.server.hooks.Deadline;
import io.aeyer.plowshare.server.hooks.DeliveryPre;
import io.aeyer.plowshare.server.hooks.FoldPost;
import io.aeyer.plowshare.server.hooks.Handover;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.LogClosing;
import io.aeyer.plowshare.server.hooks.LogOpen;
import io.aeyer.plowshare.server.hooks.LogOpening;
import io.aeyer.plowshare.server.hooks.Notified;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.Summarised;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The log stages over a chain of hooks (spec 2026-09-28-hooks-reach-the-log, slices 1, 3 and 4).
 *
 * <p><b>Records land in the turn they belong to</b> (decision 7): into the log the transition
 * belongs to — the source run's for a delivery, never the destination's — appended with {@link
 * EntryStore#append} directly, on {@code Learner.noted}'s precedent. Between turns that is the
 * log's latest turn, or 1 for a log with none. <b>Some seams are inside a turn</b>: an
 * orchestration's {@code finish}, and the delivery it fires, run in the conductor's own turn, so a
 * log with a turn in flight files at that turn's ordinal (latest completed + 1, the one {@code
 * Compaction} gives it) and {@link LogClosing#turns} counts it. A machine log closed by its own
 * ending turn is not read that way — that turn's row is written while the log still reads as
 * speaking — so its transcript names the turn. A delegated child opens in its
 * parent's turn but records in its own, turnless log.
 *
 * <p><b>The opening</b> (decision 9): {@code log.open}'s additions are stored once with {@link
 * TurnStore#remember} and fixed on the row; {@code JobRuntime} sends them after the prompt.
 *
 * <p><b>Local hooks</b> (spec 2026-09-30-local-hooks-are-served decision 3): pinned by {@link
 * LocalHooks} before {@code log.open} fires; a pin that could not be taken is a {@code HOOK}
 * record at {@code log.open}.
 *
 * <p><b>Notices</b> (decision 8): to the owner's inbox as kind {@code 'hook'}; a log nobody owns
 * records its decisions and tells nobody. Never throws.
 */
public final class HookedLogStages implements LogStages {

    private static final Logger log = LoggerFactory.getLogger(HookedLogStages.class);

    /**
     * The most {@code fold.post}'s hooks keep together, in tokens, the blank lines between keeps
     * included (spec 2026-09-28-hooks-reach-the-log §3, amended 2026-09-29): a summary a fold
     * makes smaller must not grow back by what its hooks append.
     */
    static final int KEPT_CAP = 512;

    /** Where a notice goes: {@code Inbox.notify}, until the channels work item routes it further. */
    @FunctionalInterface
    public interface Notifier {
        void notify(String handle, String kind, String text);
        default void notifyFromLog(String handle,String kind,String text,String log) { notify(handle,kind,text); }
    }

    private final Hooks hooks;
    private final ConversationStore conversations;
    private final TurnStore turns;
    private final Predicate<String> speaking;
    private final EntryStore entries;
    private final Notifier inbox;
    private final Predicate<String> bots;
    private final Supplier<Instant> clock;
    private final Supplier<Duration> timeLimit;
    private final Tokenizer tokenizer;
    private final LocalHooks local;

    public HookedLogStages(Hooks hooks, ConversationStore conversations, TurnStore turns,
            Predicate<String> speaking, EntryStore entries, Notifier inbox,
            Predicate<String> bots, Supplier<Instant> clock, Supplier<Duration> timeLimit,
            Tokenizer tokenizer) {
        this(hooks, conversations, turns, speaking, entries, inbox, bots, clock, timeLimit,
                tokenizer, LocalHooks.NONE);
    }

    /**
     * @param speaking whether a conversation has a turn in flight — {@code Turn.isSpeaking}
     * @param bots whether a named agent is a bot, for the context of a stage that has only the
     *     log's row; false for an agent that no longer resolves, or a registry that cannot answer
     * @param timeLimit the hook time limit, {@code plowshare.hooks.timeout}, read per fold:
     *     {@code fold.post}'s whole chain shares one (spec 2026-09-28-hooks-reach-the-log
     *     decision 4, amended 2026-09-29)
     * @param tokenizer what {@code fold.post}'s kept text is counted with, against {@link
     *     #KEPT_CAP}
     * @param local snapshots a log's local hooks before {@code log.open} fires (spec
     *     2026-09-30-local-hooks-are-served decision 3)
     */
    public HookedLogStages(Hooks hooks, ConversationStore conversations, TurnStore turns,
            Predicate<String> speaking, EntryStore entries, Notifier inbox,
            Predicate<String> bots, Supplier<Instant> clock, Supplier<Duration> timeLimit,
            Tokenizer tokenizer, LocalHooks local) {
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.speaking = Objects.requireNonNull(speaking, "speaking");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.bots = Objects.requireNonNull(bots, "bots");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeLimit = Objects.requireNonNull(timeLimit, "timeLimit");
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.local = Objects.requireNonNull(local, "local");
    }

    @Override
    public void opened(LogOpened opened) {
        try {
            // Spec 2026-09-30-local-hooks-are-served decision 3: pinned before log.open fires, so
            // the local tier sees its own log.open. First, too, because the local tier caches a
            // log's pin lookup, a miss included for a minute (plan choice 13, as amended): a
            // lookup before the pin would leave the log with no local tier until that miss went
            // stale. Its failures are records at log.open.
            List<HookRecord> records = new ArrayList<>(pinned(opened));
            HookContext context = HookContext.forLog(opened.origin().wireName(), opened.agent(),
                    opened.bot(), projectOf(opened.home()), opened.log());
            LogOpen result = hooks.logOpen(context, new LogOpening(opened.parent(),
                    conversations.ownerOf(opened.log()).orElse(null)));
            records.addAll(result.records());
            record(opened.log(), records);
            if (!result.additions().isEmpty()) {
                conversations.fixOpening(opened.log(), turns.remember(result.joined()));
            }
        } catch (RuntimeException failed) {
            log.warn("log {}: log.open could not run, so it opens with no additions. Reason: {}",
                    opened.log(), JobRuntime.describe(failed));
        }
    }

    /** {@link LocalHooks} never throws; one that does leaves the log with no local tier, said. */
    private List<HookRecord> pinned(LogOpened opened) {
        try {
            return local.pin(opened);
        } catch (RuntimeException broken) {
            log.warn("log {}: its local hooks could not be pinned, so it has none. Reason: {}",
                    opened.log(), JobRuntime.describe(broken));
            return List.of(LocalHooks.unsnapshotted(broken));
        }
    }

    /** Between turns or inside an orchestration's: the turn the log reads as being at. */
    @Override
    public void closed(String logId, String ending) {
        try {
            close(logId, ending, currentTurn(logId));
        } catch (RuntimeException failed) {
            log.warn("log {}: log.close could not run. Reason: {}", logId,
                    JobRuntime.describe(failed));
        }
    }

    /** From the ending turn's transcript: its ordinal, read once, for the record and the count. */
    @Override
    public void closed(String logId, String ending, int atTurn) {
        try {
            close(logId, ending, atTurn);
        } catch (RuntimeException failed) {
            log.warn("log {}: log.close could not run. Reason: {}", logId,
                    JobRuntime.describe(failed));
        }
    }

    private void close(String logId, String ending, int atTurn) {
        Optional<ConversationRecord> found = conversations.find(logId);
        if (found.isEmpty() || !conversations.closeLog(logId, clock.get())) {
            return;
        }
        Notified result = hooks.logClose(contextOf(found.get()), new LogClosing(ending, atTurn));
        record(logId, result.records(), atTurn);
        tell(logId, result.notices());
    }

    @Override
    public void moved(ConversationRecord moved) {
        try {
            if (moved.origin() == Origin.TURN && moved.lifecycle() != null
                    && moved.lifecycle() != ConversationLifecycle.ACTIVE) {
                closed(moved.id(), moved.lifecycle().wireName());
            }
        } catch (RuntimeException failed) {
            log.warn("log {}: its move could not be read for a log.close. Reason: {}",
                    moved == null ? null : moved.id(), JobRuntime.describe(failed));
        }
    }

    private io.aeyer.plowshare.server.information.InformationJobs informationInputs;
    public void useInformationInputs(io.aeyer.plowshare.server.information.InformationJobs inputs) { informationInputs=inputs; }

    @Override
    public String deliveryPre(String sourceLog, String destination, String text) {
        if (sourceLog == null) {
            return text;
        }
        if (informationInputs != null && !informationInputs.logAllowed(sourceLog,conversations.ownerOf(sourceLog).orElse(null)))
            return "Information result withheld because an input is unavailable.";
        try {
            Optional<ConversationRecord> found = conversations.find(sourceLog);
            if (found.isEmpty()) {
                return text;
            }
            DeliveryPre result = hooks.deliveryPre(contextOf(found.get()),
                    new Handover(destination, text));
            record(sourceLog, result.records());
            return result.applyTo(text);
        } catch (RuntimeException failed) {
            log.warn("log {}: delivery.pre could not run, so the result goes as it was. Reason: {}",
                    sourceLog, JobRuntime.describe(failed));
            return text;
        }
    }

    @Override
    public void deliveryPost(String sourceLog, String destination, String text) {
        if (sourceLog == null) {
            return;
        }
        try {
            Optional<ConversationRecord> found = conversations.find(sourceLog);
            if (found.isEmpty()) {
                return;
            }
            Notified result = hooks.deliveryPost(contextOf(found.get()),
                    new Handover(destination, text));
            record(sourceLog, result.records());
            tell(sourceLog, result.notices());
        } catch (RuntimeException failed) {
            log.warn("log {}: delivery.post could not run. Reason: {}", sourceLog,
                    JobRuntime.describe(failed));
        }
    }

    @Override
    public void approvalAnswered(String logId, String approval, String decision, String scope) {
        if (logId == null) {
            return;
        }
        try {
            Optional<ConversationRecord> found = conversations.find(logId);
            if (found.isEmpty()) {
                return;
            }
            Notified result = hooks.approvalPost(contextOf(found.get()),
                    new ApprovalAnswer(approval, decision, scope));
            record(logId, result.records());
            tell(logId, result.notices());
        } catch (RuntimeException failed) {
            log.warn("log {}: approval.post for {} could not run. Reason: {}", logId, approval,
                    JobRuntime.describe(failed));
        }
    }

    /**
     * Records now, at the turn the fold names rather than the one the log reads as being at: the
     * fold runs on its own thread after its turn ended (spec 2026-09-28-hooks-reach-the-log
     * decision 7). The notices are held for the fold to send once it is saved (§3, amended
     * 2026-09-29), to the log's owner as every other notice goes (decision 8).
     *
     * <p><b>One time limit for the whole chain, from here</b> (decision 4, amended 2026-09-29):
     * the hooks run for no longer than one limit together, however many there are. Loading a
     * changed hook file or a fresh context is outside it; see {@link Deadline}.
     *
     * @param logId the conversation being folded
     * @param atTurn the ordinal the fold files its diagnostic at
     * @param summarised the span and the folder's summary of it
     * @return the kept text, several a blank line apart, and the held notices; {@link
     *     Held#NOTHING} on any failure
     */
    @Override
    public Held foldPost(String logId, int atTurn, Summarised summarised) {
        if (logId == null) {
            return Held.NOTHING;
        }
        try {
            Deadline deadline = Deadline.after(timeLimit.get());
            Optional<ConversationRecord> found = conversations.find(logId);
            if (found.isEmpty()) {
                return Held.NOTHING;
            }
            FoldPost result = hooks.foldPost(contextOf(found.get()), summarised, deadline);
            List<HookRecord> records = new ArrayList<>(result.records());
            String kept = capped(logId, result.kept(), records);
            record(logId, records, atTurn);
            List<Notified.Notice> notices = result.notices();
            return new Held(kept, () -> {
                // Held's contract is that telling never throws: the owner is read when the fold
                // is saved, and an archive that cannot answer then loses the notices, loudly.
                try {
                    tell(logId, notices);
                } catch (RuntimeException untold) {
                    log.warn("log {}: its fold was saved, and {} fold.post notice(s) could not be"
                            + " sent. Reason: {}", logId, notices.size(),
                            JobRuntime.describe(untold));
                }
            });
        } catch (RuntimeException failed) {
            log.warn("log {}: fold.post could not run, so the fold keeps nothing of the hooks' and"
                    + " tells nobody. Reason: {}", logId, JobRuntime.describe(failed));
            return Held.NOTHING;
        }
    }

    /**
     * The keeps, in chain order, while what they come to together stays within {@link #KEPT_CAP}
     * (spec 2026-09-28-hooks-reach-the-log §3, amended 2026-09-29). The joined text is what is
     * counted, so the blank lines between keeps count too. A keep that would pass the cap is
     * dropped whole, never cut short, and a later one that still fits is kept; each dropped keep
     * gets an {@code unused} record naming its hook, placed just after that hook's {@code keep}.
     *
     * @return the kept text, several a blank line apart, or {@code ""}
     */
    private String capped(String logId, List<FoldPost.Kept> keeps, List<HookRecord> records) {
        List<String> taken = new ArrayList<>();
        // Keeps and their records are both in chain order, so each keep's record is found by
        // searching on from the previous one's.
        int cursor = 0;
        for (FoldPost.Kept keep : keeps) {
            int found = afterItsKeep(records, keep, cursor);
            if (found >= 0) {
                cursor = found;
            }
            List<String> trying = new ArrayList<>(taken);
            trying.add(keep.text());
            HookRecord dropped;
            try {
                int tokens = tokenizer.count(String.join("\n\n", trying)).tokens();
                if (tokens <= KEPT_CAP) {
                    taken = trying;
                    continue;
                }
                dropped = unused(keep, "its keep would take fold.post's kept text to " + tokens
                        + " tokens, past the cap of " + KEPT_CAP + ", so it was dropped whole");
            } catch (RuntimeException uncounted) {
                log.warn("log {}: a fold.post keep from hook '{}' could not be counted, so it was"
                        + " dropped. Reason: {}", logId, keep.hook(),
                        JobRuntime.describe(uncounted));
                dropped = unused(keep, "its keep could not be counted against fold.post's cap of "
                        + KEPT_CAP + " tokens, so it was dropped: "
                        + JobRuntime.describe(uncounted));
            }
            if (found >= 0) {
                records.add(cursor++, dropped);
            } else {
                // A keep no layer recorded: its record goes last, and the search does not move.
                records.add(dropped);
            }
        }
        return String.join("\n\n", taken);
    }

    /**
     * Where a dropped keep's record goes: straight after that hook's {@code keep} record, so the
     * log reads keep then unused for the same hook.
     *
     * @param from where the search starts: just after the previous keep's place
     * @return the index after that {@code keep} record, or -1 when no layer recorded it
     */
    private static int afterItsKeep(List<HookRecord> records, FoldPost.Kept keep, int from) {
        for (int i = from; i < records.size(); i++) {
            HookRecord one = records.get(i);
            if (HookRecord.KEEP.equals(one.decision()) && one.hook().equals(keep.hook())
                    && one.tier() == keep.tier() && Objects.equals(one.file(), keep.file())
                    && keep.text().equals(one.added())) {
                return i + 1;
            }
        }
        return -1;
    }

    /** A keep the fold did not use, recorded against the hook that asked for it. */
    private static HookRecord unused(FoldPost.Kept keep, String reason) {
        return new HookRecord(keep.hook(), keep.file(), keep.tier(), Stage.FOLD_POST, null,
                HookRecord.UNUSED, reason, null, keep.text(), 0);
    }

    private HookContext contextOf(ConversationRecord row) {
        return HookContext.forLog(row.origin().wireName(), row.agent(), isBot(row),
                projectOf(row.home()), row.id());
    }

    /**
     * A registry that cannot answer reads as no bot, so the stage still runs: a hook that misses
     * the bot flag is a smaller loss than a hook that never runs.
     */
    private boolean isBot(ConversationRecord row) {
        if (row.agent() == null) {
            return false;
        }
        try {
            return bots.test(row.agent());
        } catch (RuntimeException unanswered) {
            log.warn("log {}: whether agent '{}' is a bot could not be read, so its hooks see no"
                    + " bot. Reason: {}", row.id(), row.agent(), JobRuntime.describe(unanswered));
            return false;
        }
    }

    private static String projectOf(Home home) {
        return home.isGlobal() ? null : home.project();
    }

    /** Decision 7: the turn in flight if there is one, else the latest completed, else 0. */
    private int currentTurn(String logId) {
        int latest = turns.latestOrdinal(logId);
        return speaking.test(logId) ? latest + 1 : latest;
    }

    /**
     * Decision 7: into this log, at its current turn, or 1 for a log with none yet. A turn that
     * cannot be read loses the records and nothing after them: the opening still gets fixed, the
     * notices still go, the rewrite still applies.
     */
    private void record(String logId, List<HookRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        int atTurn;
        try {
            atTurn = currentTurn(logId);
        } catch (RuntimeException unread) {
            log.warn("log {}: its turn could not be read, so {} hook record(s) were not written."
                    + " Reason: {}", logId, records.size(), JobRuntime.describe(unread));
            return;
        }
        record(logId, records, atTurn);
    }

    /** Decision 7: into this log at {@code atTurn}, or 1 for a log with no turn yet. */
    private void record(String logId, List<HookRecord> records, int atTurn) {
        if (records.isEmpty()) {
            return;
        }
        int ordinal = Math.max(atTurn, 1);
        for (HookRecord one : records) {
            try {
                entries.append(logId, ordinal, LoggedEntry.hook(one));
            } catch (RuntimeException notRecorded) {
                log.warn("log {}: a {} hook record could not be written. Reason: {}", logId,
                        one.stage().wireName(), JobRuntime.describe(notRecorded));
            }
        }
    }

    /** Decision 8: each notice to the owner's inbox, named by its hook; nobody for no owner. */
    private void tell(String logId, List<Notified.Notice> notices) {
        if (notices.isEmpty()) {
            return;
        }
        Optional<String> owner = conversations.ownerOf(logId);
        if (owner.isEmpty()) {
            return;
        }
        for (Notified.Notice notice : notices) {
            try {
                inbox.notifyFromLog(owner.get(), InboxStore.KIND_HOOK, notice.hook() + ": " + notice.text(),logId);
            } catch (RuntimeException undelivered) {
                log.warn("log {}: hook '{}' asked to tell {} and the inbox refused. Reason: {}",
                        logId, notice.hook(), owner.get(), JobRuntime.describe(undelivered));
            }
        }
    }
}
