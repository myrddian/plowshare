package io.aeyer.plowshare.server.harness;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.hooks.HarnessHook;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.PromptPre;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.Step;
import io.aeyer.plowshare.server.hooks.StepPost;
import io.aeyer.plowshare.server.hooks.Tier;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * {@code harness:stuck}: a turn that shows a sign of being stuck is shown to another model,
 * and what it has to say about that sign -- usually nothing -- is handed back as a runtime note.
 *
 * <p><b>Built for a measured failure.</b> Aristoxenus on gpt-oss-120b searched twelve
 * times for a Fed hike "today", each query reworded, every result unrelated, and never
 * questioned the date — a Sunday. {@code Repeats} never fired: no two calls were
 * identical.
 *
 * <p><b>It fires on signs, not on a step count.</b> The first version consulted at a step
 * count, and measured 2026-09-29/30 that meant a hint to nearly every coder four steps into
 * ordinary work; {@link StuckSignals} says what it fires on now and why. <b>The advisor may
 * have nothing to say, and then nothing is sent</b>: {@link StuckVerdict} reads its answer, and
 * an answer that is null, empty, unreadable or not advice leaves only this hook's record,
 * decision {@code swallowed}, which is a {@code hook} entry and never reaches a model.
 *
 * <p>One instance per run (see {@code HarnessRun}), so every field is this run's.
 * The run's thread calls {@link #promptPre}, {@link #stepPost} and {@link #finish};
 * only the consult runs elsewhere, and its result is read here through the future.
 */
public final class StuckTrap implements HarnessHook {

    public static final String NAME = "harness:stuck";

    /** Asks the advisor about a brief. The test seam; the factory's calls the dispatcher. */
    public interface Advisor {
        /**
         * @param abandoned true once nobody will read the answer — the consult was
         *     cancelled, timed out, or the turn ended — so a call still generating can
         *     stop and give its inference slot back to the run it was meant to help
         */
        Advice advise(String specifier, String brief, BooleanSupplier abandoned);

        default Advice advise(String specifier, String brief, BooleanSupplier abandoned,
                UsageAttribution owner) {
            return advise(specifier, brief, abandoned);
        }
    }

    /** What the advisor said, and what served it. */
    public record Advice(String text, String wireModel, Integer completionTokens) {}

    private final StuckSignals signals;
    private final int mostPerTurn;
    private final String advisorSpecifier;
    private final long timeoutMillis;
    private final int argumentsExcerpt;
    private final int resultExcerpt;
    private final int thinkingExcerpt;
    private final int adviceMost;
    private final Advisor advisor;
    private final Executor executor;
    private final Supplier<ZonedDateTime> now;
    private final LongSupplier millis;

    private String question = "";
    private final List<Step> trail = new ArrayList<>();
    private int consults;
    private boolean gaveUp;
    private CompletableFuture<Advice> pending;
    private StuckSignals.Signal pendingSignal;
    private String pendingBrief;
    /** The tools the run was offered when the consult went out, and what its steps showed. */
    private Set<String> pendingTools = Set.of();
    private String pendingEvidence = "";
    private long pendingSince;
    /** When the advisor returned or threw, or -1; written on the consult's thread. */
    private AtomicLong pendingDoneAt;

    public StuckTrap(Parameters parameters, Advisor advisor, Executor executor,
            Supplier<ZonedDateTime> now, LongSupplier millis) {
        this.signals = new StuckSignals(parameters.integer(StuckSignals.REPEAT_FAILURES),
                parameters.integer(StuckSignals.FAILURE_STREAK), parameters.integer(StuckSignals.REPEAT_CALLS),
                parameters.integer(StuckSignals.READ_ONLY_STEPS));
        this.mostPerTurn = parameters.integer("most-per-turn");
        this.advisorSpecifier = parameters.text("advisor");
        this.timeoutMillis = parameters.duration("timeout").toMillis();
        this.argumentsExcerpt = parameters.integer("arguments-excerpt");
        this.resultExcerpt = parameters.integer("result-excerpt");
        this.thinkingExcerpt = parameters.integer("thinking-excerpt");
        this.adviceMost = parameters.integer("advice-most");
        this.advisor = advisor;
        this.executor = executor;
        this.now = now;
        this.millis = millis;
    }

    @Override
    public PromptPre promptPre(HookContext context, String utterance) {
        question = utterance;
        return PromptPre.NOTHING;
    }

    @Override
    public StepPost stepPost(HookContext context, Step step) {
        trail.add(step);
        List<String> notes = new ArrayList<>();
        List<HookRecord> records = new ArrayList<>();
        collect(notes, records);
        // Counted at every step, consult or not, so a sign's count is the turn's and not only
        // what came after the last consult.
        Optional<StuckSignals.Signal> signal = signals.step(step, context.tools());
        if (signal.isPresent() && !gaveUp && pending == null && consults < mostPerTurn) {
            signals.acted(signal.get());
            consult(signal.get(), context.tools(), context.usage());
        }
        return new StepPost(notes, records);
    }

    @Override
    public List<HookRecord> finish() {
        if (pending == null) {
            return List.of();
        }
        CompletableFuture<Advice> left = pending;
        pending = null;
        // Cancelling before reading closes the race with a consult finishing now: after
        // this either the cancel won and the advice never arrived, or the future holds
        // whatever did. A queued consult sees the future done and never calls the advisor;
        // one already calling is not interrupted — CompletableFuture ignores the flag —
        // and its answer is dropped — but it sees the future done through its abandoned
        // supplier, so a streaming advisor call stops at its next chunk.
        left.cancel(true);
        if (left.isCancelled()) {
            return List.of(record(HookRecord.UNUSED, "the turn ended before the advice arrived", null,
                    millis.getAsLong() - pendingSince));
        }
        try {
            Advice advice = left.join();
            return List.of(record(HookRecord.UNUSED, advisedBy(advice), advice.text(), took(null)));
        } catch (RuntimeException failed) {
            // A consult that failed after the last step is still a failure, not unused advice.
            return List.of(record(HookRecord.FAILED, failure(failed), null, took(failed)));
        }
    }

    private void consult(StuckSignals.Signal signal, Set<String> tools,
            UsageAttribution owner) {
        consults++;
        pendingSignal = signal;
        pendingTools = tools;
        pendingEvidence = evidence(tools);
        pendingBrief = StuckBrief.render(signal.sentence(), question, now.get(), tools, trail,
                argumentsExcerpt, resultExcerpt, thinkingExcerpt);
        pendingSince = millis.getAsLong();
        String brief = pendingBrief;
        AtomicLong doneAt = new AtomicLong(-1);
        pendingDoneAt = doneAt;
        CompletableFuture<Advice> future = new CompletableFuture<>();
        executor.execute(() -> {
            if (future.isDone()) {
                return; // abandoned before it started
            }
            // The clock is read before completing, so the run's thread, which only looks
            // once the future is done, always sees it: the record's tookMs is the advisor's
            // own time, not however long the run took to reach its next step.
            Advice advice;
            try {
                // future::isDone is the whole abandonment signal: a cancel, a timeout and
                // finish() all complete this very future, and each means nobody will read
                // the advice. An advisor that stops on it throws, and completing a future
                // that is already done changes nothing, so the consult keeps the outcome
                // that abandoned it — unused or a timeout — rather than a new failure.
                advice = advisor.advise(advisorSpecifier, brief, future::isDone, owner);
            } catch (RuntimeException failed) {
                doneAt.set(millis.getAsLong());
                future.completeExceptionally(failed);
                return;
            }
            doneAt.set(millis.getAsLong());
            future.complete(advice);
        });
        // orTimeout returns its receiver (JDK source: "return this"), so pending is future:
        // a timeout or a cancel completes the very future the queued work checks first.
        pending = future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void collect(List<String> notes, List<HookRecord> records) {
        if (pending == null || !pending.isDone()) {
            return;
        }
        CompletableFuture<Advice> done = pending;
        pending = null;
        Advice advice;
        try {
            advice = done.join();
        } catch (RuntimeException failed) {
            gaveUp = true;
            records.add(record(HookRecord.FAILED, failure(failed), null, took(failed)));
            return;
        }
        StuckVerdict.Reading reading = StuckVerdict.read(advice.text(), adviceMost, pendingTools,
                pendingEvidence);
        if (reading.note() == null) {
            // NOTHING GOES DOWN TO THE MODEL: no note, so no user message and no runtime_note
            // entry. The record is a hook entry, which never projects (EntryKind.HOOK), and it
            // keeps the whole answer so a person can see what was dropped and why. Not a
            // failure, and consulting goes on: an advisor with nothing to say did its job.
            records.add(record(HookRecord.SWALLOWED, reading.swallowed() + "; " + firedBy() + "; "
                    + advisedBy(advice), advice.text(), took(null)));
            return;
        }
        // A user-role message, so it says whose it is before anything else: the harness's,
        // not the person's and not a tool's -- and that it is not a message to answer, since
        // a coder measured answering one ("I see that I've been looping… The next move is
        // to…") handed back a plan instead of the work. It names the sign the harness saw,
        // which is a fact the model can check, before the advice, which is only another
        // model's view: the brief carried raw tool excerpts, and whatever steered the advisor
        // must not arrive as an order.
        notes.add("[Runtime note from the harness, not from the person and not a tool's answer."
                + " Keep working on your task; this note needs no reply. "
                + capitalised(pendingSignal.sentence()) + ". A second model that looked at the"
                + " recent steps suggests: " + reading.note() + "]");
        records.add(record(HookRecord.NOTE, firedBy() + "; " + advisedBy(advice), reading.note(),
                took(null)));
    }

    /**
     * What the steps show that a note may quote without it being taken for a tool's name: the
     * question, and each call's arguments and result -- but only for a call to a tool the run was
     * offered. A call to one it was not offered was refused by name, and that name, sent or
     * refused, is not a tool the advisor may suggest. The thinking is left out for the same reason:
     * it is where a model plans a call to a tool it does not have.
     */
    private String evidence(Set<String> tools) {
        StringBuilder shown = new StringBuilder(question);
        for (Step step : trail) {
            for (int i = 0; i < step.calls().size(); i++) {
                ToolCall call = step.calls().get(i);
                if (tools.contains(call.name())) {
                    shown.append('\n').append(call.arguments()).append('\n').append(step.results().get(i));
                }
            }
        }
        return shown.toString();
    }

    /** Whether a consult is out and has settled; the timeout test's deterministic wait. */
    boolean consultSettled() {
        return pending != null && pending.isDone();
    }

    /**
     * How long the advisor took. A timeout took the timeout, whatever the advisor does
     * later.
     */
    private long took(RuntimeException failed) {
        if (failed != null && cause(failed) instanceof TimeoutException) {
            return timeoutMillis;
        }
        long doneAt = pendingDoneAt.get();
        return (doneAt < 0 ? millis.getAsLong() : doneAt) - pendingSince;
    }

    /** join() wraps what the consult threw. */
    private static Throwable cause(RuntimeException failed) {
        return failed instanceof CompletionException && failed.getCause() != null
                ? failed.getCause() : failed;
    }

    /** The record names what was thrown. */
    private String failure(RuntimeException failed) {
        Throwable cause = cause(failed);
        if (cause instanceof TimeoutException) {
            // orTimeout's exception carries no message, and "TimeoutException: null" says less.
            return "no advice within " + timeoutMillis + " ms";
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    /** Which sign the consult was asked about, for the record. */
    private String firedBy() {
        return "fired by " + pendingSignal.name() + ": " + pendingSignal.sentence();
    }

    private static String advisedBy(Advice advice) {
        return "advisor " + advice.wireModel() + (advice.completionTokens() == null ? ""
                : ", " + advice.completionTokens() + " completion tokens");
    }

    private static String capitalised(String sentence) {
        return sentence.isEmpty() ? sentence : Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);
    }

    private HookRecord record(String decision, String reason, String added, long tookMs) {
        return new HookRecord(NAME, null, Tier.HARNESS, Stage.STEP_POST, null, decision, reason, added,
                pendingBrief, Math.max(0, tookMs));
    }
}
