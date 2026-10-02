package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.server.llm.dispatch.InferenceAccounting;
import io.aeyer.plowshare.server.llm.dispatch.InferenceCapture;
import io.aeyer.plowshare.server.llm.dispatch.InferenceObserver;
import io.aeyer.plowshare.server.llm.dispatch.Lane;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Snapshots admission metadata and observes every actual attempt without retaining request content. */
public final class DurableInferenceAccounting implements InferenceAccounting {
    private static final Logger log = LoggerFactory.getLogger(DurableInferenceAccounting.class);
    private final AccountingRecorder recorder;
    private final PricingCatalog prices;
    private final Clock clock;

    public DurableInferenceAccounting(AccountingRecorder recorder, PricingCatalog prices, Clock clock) {
        this.recorder = Objects.requireNonNull(recorder);
        this.prices = Objects.requireNonNull(prices);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public InferenceObserver begin(String pool, String wireModel, String specifier, Lane lane, UsageAttribution owner) {
        if (owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED) {
            throw new io.aeyer.plowshare.server.llm.dispatch.LlmException("inference accounting requires explicit request ownership");
        }
        var route = prices.routeFor(pool);
        var price = prices.select(route.billingRoute(), wireModel, clock.instant()).orElse(null);
        var metadata = new AccountingEvent.CallCreated(owner, specifier, pool, wireModel,
                route.modelFamilies().get(wireModel), route.billingRoute(), lane, price);
        long start = System.nanoTime();
        var call = recorder.begin(metadata);
        return new ObservedCall(call, elapsed(start));
    }

    private static long elapsed(long start) { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)); }

    private static AccountingEvent.FinishReason reason(String value) {
        if (value == null) { return AccountingEvent.FinishReason.UNKNOWN; }
        return switch (value) {
            case "stop" -> AccountingEvent.FinishReason.STOP;
            case "length" -> AccountingEvent.FinishReason.LENGTH;
            case "tool_calls", "function_call" -> AccountingEvent.FinishReason.TOOL_CALLS;
            case "content_filter" -> AccountingEvent.FinishReason.CONTENT_FILTER;
            default -> AccountingEvent.FinishReason.OTHER;
        };
    }

    private static final class ObservedCall implements InferenceObserver {
        private final AccountingRecorder.Call call;
        private final long admissionMillis;
        private long queuedAt;
        private Long queueMillis;
        private boolean ended;
        private boolean durable = true;
        private ObservedAttempt active;
        private CallLifecycle lastOutcome;

        ObservedCall(AccountingRecorder.Call call, long admissionMillis) {
            this.call = call;
            this.admissionMillis = admissionMillis;
        }
        @Override public synchronized void preflight(io.aeyer.plowshare.server.llm.counting.PromptCount count) { call.preflight(count); }
        @Override public boolean enabled() { return true; }
        @Override public synchronized void queued() { queuedAt = System.nanoTime(); }
        @Override public synchronized void started() { queueMillis = elapsed(queuedAt); }

        @Override public synchronized Attempt attempt() {
            if (ended || active != null) { throw new IllegalStateException("invalid inference accounting attempt lifecycle"); }
            long start = System.nanoTime();
            call.startAttempt(); // No request may be sent before this is durable.
            active = new ObservedAttempt(elapsed(start));
            return active;
        }

        @Override public synchronized void finished(CallLifecycle outcome) {
            if (ended) { return; }
            if (active != null) { active.finished(outcome); }
            if (queueMillis == null && queuedAt != 0) { queueMillis = elapsed(queuedAt); }
            if (outcome == CallLifecycle.FAILED && lastOutcome == CallLifecycle.REFUSED) { outcome = lastOutcome; }
            durable &= call.finish(outcome, queueMillis, admissionMillis);
            ended = true;
            if (!durable) {
                log.warn("inference accounting call {} has incomplete or buffered terminal metadata; its inference result stands", call.id());
            }
        }

        @Override public synchronized InferenceCapture capture() { return new InferenceCapture(call.id(), ended && durable); }

        private final class ObservedAttempt implements Attempt {
            private final long startAccountingMillis;
            private final long startedAt = System.nanoTime();
            private UsageObservation usage = UsageObservation.UNKNOWN;
            private AccountingEvent.FinishReason finishReason = AccountingEvent.FinishReason.UNKNOWN;
            private Integer status;
            private String requestId;
            private Long firstOutputMillis;
            private boolean finished;

            ObservedAttempt(long startAccountingMillis) { this.startAccountingMillis = startAccountingMillis; }

            @Override public void response(Integer code, String id) {
                synchronized (ObservedCall.this) {
                    if (finished) { return; }
                    if (code != null && code >= 100 && code <= 599) { status = code; }
                    if (requestId == null && id != null && id.matches("[A-Za-z0-9._:-]{1,256}")) { requestId = id; }
                }
            }
            @Override public void usage(TokenUsage observation) {
                synchronized (ObservedCall.this) {
                    if (!finished) { usage = observation == null ? UsageObservation.UNKNOWN : observation.observation(); }
                }
            }
            @Override public void reason(String value) {
                synchronized (ObservedCall.this) { if (!finished) { finishReason = DurableInferenceAccounting.reason(value); } }
            }
            @Override public void output() {
                synchronized (ObservedCall.this) {
                    if (!finished && firstOutputMillis == null) { firstOutputMillis = elapsed(startedAt); }
                }
            }
            @Override public void finished(CallLifecycle outcome) {
                synchronized (ObservedCall.this) {
                    if (finished) { return; }
                    if (outcome == CallLifecycle.FAILED && status != null && status >= 300 && status < 500) {
                        outcome = CallLifecycle.REFUSED;
                    }
                    long duration = elapsed(startedAt);
                    durable &= call.finishAttempt(outcome, usage, requestId, status, finishReason,
                            firstOutputMillis, duration, startAccountingMillis);
                    finished = true;
                    lastOutcome = outcome;
                    active = null;
                }
            }
        }
    }
}
