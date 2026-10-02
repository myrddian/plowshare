package io.aeyer.plowshare.server.llm.accounting;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One bounded asynchronous projector; PostgreSQL outage does not discard journal metadata. */
public final class AccountingProjector implements AutoCloseable {
    private final AccountingJournal journal;
    private final AccountingStore store;
    private final AccountingRecorder recorder;
    private final Clock clock;
    private final long recoveryCutoff;
    private final java.time.Instant trackingStartedAt;
    private boolean trackingRegistered;
    private boolean recovered;
    private ScheduledExecutorService worker;
    private final Object lifecycleLock = new Object();
    private volatile Health health;

    public AccountingProjector(AccountingJournal journal, AccountingStore store, AccountingRecorder recorder, Clock clock) {
        this.journal = Objects.requireNonNull(journal);
        this.store = Objects.requireNonNull(store);
        this.recorder = Objects.requireNonNull(recorder);
        this.clock = Objects.requireNonNull(clock);
        this.recoveryCutoff = journal.health().writtenSequence();
        this.trackingStartedAt = clock.instant();
        this.health = new Health(journal.health().acknowledgedSequence(), null, Problem.NONE);
    }

    public Health health() { return health; }

    public void start(Duration interval) {
        synchronized (lifecycleLock) {
            if (worker != null || interval == null || interval.toMillis() < 1) {
                throw new IllegalArgumentException("projector needs one worker and a positive interval");
            }
            worker = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("inference-accounting-projector").factory());
            worker.scheduleWithFixedDelay(this::projectOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Work on at most 100 events per tick, commit first, acknowledge second. No inference callback exists. */
    public synchronized boolean projectOnce() {
        try {
            if (!trackingRegistered) {
                store.trackingStarted(journal.journalId(), trackingStartedAt);
                trackingRegistered = true;
            }
            long databaseWatermark = store.watermark(journal.journalId());
            if (databaseWatermark < journal.health().acknowledgedSequence()) {
                throw new AccountingStore.ProjectionConflictException("database checkpoint is behind journal acknowledgement");
            }
            if (journal.health().problem() == AccountingJournal.Problem.IO_ERROR) { journal.recover(); }
            var batch = journal.readBatch(100);
            if (!batch.isEmpty()) {
                store.project(journal.journalId(), batch);
                databaseWatermark = Math.max(databaseWatermark, batch.getLast().sequence());
                health = new Health(databaseWatermark, clock.instant(), Problem.NONE);
                journal.acknowledge(batch.getLast().sequence());
            }
            if (!recovered && journal.health().acknowledgedSequence() >= recoveryCutoff) {
                store.recoverPreviousInstances(journal.journalId(), recorder.instanceId());
                recovered = true;
            }
            recorder.flushPending();
            health = new Health(databaseWatermark, clock.instant(), Problem.NONE);
            return true;
        } catch (AccountingStore.ProjectionConflictException invalid) {
            recorder.projectionConflict();
            health = new Health(health.projectedSequence(), health.lastSuccessAt(), Problem.PROJECTION_CONFLICT);
        } catch (AccountingUnavailableException unavailable) {
            health = new Health(health.projectedSequence(), health.lastSuccessAt(), Problem.JOURNAL_ERROR);
        } catch (RuntimeException databaseFailure) {
            // JDBC messages can contain SQL values; expose only this safe classification.
            health = new Health(health.projectedSequence(), health.lastSuccessAt(), Problem.DATABASE_ERROR);
        }
        return false;
    }

    @Override
    public void close() {
        ScheduledExecutorService running;
        synchronized (lifecycleLock) { running = worker; worker = null; }
        if (running != null) { running.shutdownNow(); }
    }

    public record Health(long projectedSequence, Instant lastSuccessAt, Problem problem) { }
    public enum Problem { NONE, DATABASE_ERROR, JOURNAL_ERROR, PROJECTION_CONFLICT }
}
