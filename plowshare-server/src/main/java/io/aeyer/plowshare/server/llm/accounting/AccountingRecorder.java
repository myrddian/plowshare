package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.server.llm.accounting.AccountingEvent.AttemptFinished;
import io.aeyer.plowshare.server.llm.accounting.AccountingEvent.AttemptStarted;
import io.aeyer.plowshare.server.llm.accounting.AccountingEvent.CallCreated;
import io.aeyer.plowshare.server.llm.accounting.AccountingEvent.CallFinished;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;

/**
 * Admission is durable before inference. Terminal-storage failure never asks for a second
 * inference: retain bounded metadata retries and expose the durable start as incomplete.
 */
public final class AccountingRecorder {
  private final AccountingJournal journal;
  private final Clock clock;
  private final UUID instanceId = UUID.randomUUID();
  private final int retryLimit;
  private final ArrayDeque<AccountingEvent> retries = new ArrayDeque<>();
  private long lostTerminalEvents;
  private boolean projectionConflict;

  public AccountingRecorder(AccountingJournal journal, Clock clock, int retryLimit) {
    if (retryLimit < 1 || retryLimit > 10_000) {
      throw new IllegalArgumentException("invalid accounting retry limit");
    }
    this.journal = Objects.requireNonNull(journal);
    this.clock = Objects.requireNonNull(clock);
    this.retryLimit = retryLimit;
  }

  public UUID instanceId() {
    return instanceId;
  }

  public synchronized Health health() {
    return new Health(
        retries.size(),
        lostTerminalEvents,
        projectionConflict,
        retries.isEmpty() ? null : retries.getFirst().at());
  }

  /** The metadata parameter cannot contain the chat request or embedding texts. */
  public synchronized Call begin(CallCreated metadata) {
    requireAdmission();
    UUID id = UUID.randomUUID();
    Instant at = clock.instant();
    try {
      journal.append(new AccountingEvent(UUID.randomUUID(), instanceId, id, 1, at, metadata));
    } catch (AccountingUnavailableException unavailable) {
      // An uncertain durable start has no returned handle that could safely close it.
      if (unavailable.problem() == AccountingJournal.Problem.IO_ERROR) {
        lostTerminalEvents++;
      }
      throw unavailable;
    }
    return new Call(id, metadata, at);
  }

  private void requireAdmission() {
    if (!retries.isEmpty() || lostTerminalEvents != 0 || projectionConflict) {
      throw new AccountingUnavailableException(AccountingJournal.Problem.IO_ERROR);
    }
    if (journal.health().problem() != AccountingJournal.Problem.NONE) {
      throw new AccountingUnavailableException(journal.health().problem());
    }
  }

  private synchronized boolean terminal(AccountingEvent event) {
    if (retries.isEmpty()) {
      try {
        journal.append(event);
        return true;
      } catch (AccountingUnavailableException unavailable) {
        if (unavailable.problem() == AccountingJournal.Problem.INVALID_EVENT) {
          lostTerminalEvents++;
          return false;
        }
        // The answer has already been paid for. Keep metadata, not a request to replay it.
      }
    }
    if (retries.size() == retryLimit) {
      lostTerminalEvents++;
      return false;
    }
    retries.addLast(event);
    return true;
  }

  /**
   * Flush only metadata. Uncertain fsyncs may replay an event ID; the SQL projector deduplicates
   * it.
   */
  public synchronized void flushPending() {
    if (journal.health().problem() == AccountingJournal.Problem.IO_ERROR) {
      journal.recover();
    }
    while (!retries.isEmpty()) {
      journal.append(retries.getFirst());
      retries.removeFirst();
    }
  }

  synchronized void projectionConflict() {
    projectionConflict = true;
  }

  public final class Call {
    private final UUID id;
    private final CallCreated metadata;
    private long sequence = 1;
    private int attempts;
    private UUID activeAttempt;
    private Instant lastAt;
    private io.aeyer.plowshare.server.llm.counting.PromptCount preflight;

    public synchronized void preflight(io.aeyer.plowshare.server.llm.counting.PromptCount value) {
      requireLive();
      preflight = value;
    }

    private boolean finished;
    private boolean broken;

    private Call(UUID id, CallCreated metadata, Instant at) {
      this.id = id;
      this.metadata = metadata;
      this.lastAt = at;
    }

    public UUID id() {
      return id;
    }

    /** Must succeed before sending this particular upstream attempt, including retries. */
    public synchronized UUID startAttempt() {
      synchronized (AccountingRecorder.this) {
        requireAdmission();
        requireLive();
        if (broken) {
          throw new AccountingUnavailableException(AccountingJournal.Problem.IO_ERROR);
        }
        if (activeAttempt != null) {
          throw new IllegalStateException("an accounting attempt is already active");
        }
        UUID attempt = UUID.randomUUID();
        AccountingEvent event = event(new AttemptStarted(attempt, attempts + 1));
        try {
          journal.append(event);
        } catch (AccountingUnavailableException unavailable) {
          if (unavailable.problem() == AccountingJournal.Problem.IO_ERROR) {
            // This frame may already exist. Do not reuse its call sequence for an
            // apparent zero-attempt finish; restart recovery resolves the open start.
            broken = true;
            lostTerminalEvents++;
          }
          throw unavailable;
        }
        sequence++;
        attempts++;
        activeAttempt = attempt;
        return attempt;
      }
    }

    /**
     * Returns persistence status without throwing a storage failure into usable inference content.
     */
    public synchronized boolean finishAttempt(
        CallLifecycle outcome,
        UsageObservation usage,
        String providerRequestId,
        Integer httpStatus,
        AccountingEvent.FinishReason reason,
        Long firstOutputMillis,
        long durationMillis) {
      return finishAttempt(
          outcome,
          usage,
          providerRequestId,
          httpStatus,
          reason,
          firstOutputMillis,
          durationMillis,
          null);
    }

    public synchronized boolean finishAttempt(
        CallLifecycle outcome,
        UsageObservation usage,
        String providerRequestId,
        Integer httpStatus,
        AccountingEvent.FinishReason reason,
        Long firstOutputMillis,
        long durationMillis,
        Long startAccountingMillis) {
      requireLive();
      if (activeAttempt == null) {
        throw new IllegalStateException("no accounting attempt is active");
      }
      CostResult cost =
          metadata.price() == null
              ? CostResult.unpriced()
              : metadata.price().quote(usage, metadata.lane());
      AccountingEvent event =
          event(
              new AttemptFinished(
                  activeAttempt,
                  outcome,
                  usage,
                  cost,
                  providerRequestId,
                  httpStatus,
                  reason,
                  firstOutputMillis,
                  durationMillis,
                  startAccountingMillis));
      boolean retained = !broken && terminal(event);
      broken |= !retained;
      sequence++;
      activeAttempt = null;
      return retained
          && health().bufferedTerminalEvents() == 0
          && journal.health().problem() == AccountingJournal.Problem.NONE;
    }

    public synchronized boolean finish(CallLifecycle outcome) {
      return finish(outcome, null, null);
    }

    public synchronized boolean finish(
        CallLifecycle outcome, Long queueMillis, Long admissionAccountingMillis) {
      requireLive();
      if (broken) {
        finished = true;
        return false;
      }
      if (activeAttempt != null) {
        throw new IllegalStateException("finish the active attempt first");
      }
      AccountingEvent event =
          event(
              new CallFinished(
                  attempts == 0 ? CallLifecycle.NOT_DISPATCHED : outcome,
                  queueMillis,
                  admissionAccountingMillis,
                  preflight));
      boolean retained = !broken && terminal(event);
      broken |= !retained;
      sequence++;
      finished = true;
      return retained
          && health().bufferedTerminalEvents() == 0
          && journal.health().problem() == AccountingJournal.Problem.NONE;
    }

    private AccountingEvent event(AccountingEvent.Payload payload) {
      Instant now = clock.instant();
      // Wall-clock correction cannot reverse this call's durable ordering.
      if (now.isAfter(lastAt)) {
        lastAt = now;
      }
      return new AccountingEvent(UUID.randomUUID(), instanceId, id, sequence + 1, lastAt, payload);
    }

    private void requireLive() {
      if (finished) {
        throw new IllegalStateException("accounting call is finished");
      }
    }
  }

  public record Health(
      int bufferedTerminalEvents,
      long lostTerminalEvents,
      boolean projectionConflict,
      Instant oldestBufferedAt) {}
}
