package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountingRecorderTest {
    @TempDir Path directory;

    @Test
    void a_call_and_each_retry_have_distinct_durable_identities_and_original_price_snapshot() {
        try (var journal = journal(channel -> channel.force(true))) {
            var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
            var call = recorder.begin(AccountingFixtures.metadata());
            var first = call.startAttempt();
            assertTrue(call.finishAttempt(CallLifecycle.FAILED, UsageObservation.UNKNOWN, null, null,
                    AccountingEvent.FinishReason.UNKNOWN, null, 12));
            var second = call.startAttempt();
            assertFalse(first.equals(second));
            assertTrue(call.finishAttempt(CallLifecycle.SUCCEEDED, TokenUsage.of(100, 20, 120).observation(),
                    "req_fixture", 200, AccountingEvent.FinishReason.STOP, 5L, 20));
            assertTrue(call.finish(CallLifecycle.SUCCEEDED));
            var events = journal.readBatch(10);
            assertEquals(6, events.size());
            for (int at = 0; at < events.size(); at++) {
                assertEquals(call.id(), events.get(at).event().callId());
                assertEquals(at + 1, events.get(at).event().callSequence());
            }
            var ended = (AccountingEvent.AttemptFinished) events.get(4).event().payload();
            assertEquals(AccountingFixtures.PRICE.version(), ended.cost().priceVersion());
            assertEquals(CostResult.Kind.ESTIMATED, ended.cost().kind());
        }
    }

    @Test
    void terminal_failure_preserves_the_result_contract_buffers_metadata_and_blocks_new_admission() {
        var fail = new AtomicBoolean();
        try (var journal = journal(channel -> {
            if (fail.getAndSet(false)) { throw new IOException("injected terminal failure"); }
            channel.force(true);
        })) {
            var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
            var call = recorder.begin(AccountingFixtures.metadata());
            call.startAttempt();
            fail.set(true);
            assertFalse(call.finishAttempt(CallLifecycle.SUCCEEDED, TokenUsage.of(100, 20, 120).observation(),
                    null, 200, AccountingEvent.FinishReason.STOP, null, 20));
            assertFalse(call.finish(CallLifecycle.SUCCEEDED));
            assertEquals(2, recorder.health().bufferedTerminalEvents());
            assertThrows(AccountingUnavailableException.class, () -> recorder.begin(AccountingFixtures.metadata()));
            recorder.flushPending();
            assertEquals(0, recorder.health().bufferedTerminalEvents());
            var events = journal.readBatch(10);
            assertEquals(5, events.size()); // The uncertain full frame and its retry share one event ID.
            assertEquals(events.get(2).event().eventId(), events.get(3).event().eventId());
            assertEquals(events.get(2).event(), events.get(3).event());
        }
    }

    @Test
    void retry_buffer_overflow_is_bounded_visible_and_does_not_throw_from_call_completion() {
        var fail = new AtomicBoolean();
        try (var journal = journal(channel -> {
            if (fail.getAndSet(false)) { throw new IOException("injected terminal failure"); }
            channel.force(true);
        })) {
            var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 1);
            var call = recorder.begin(AccountingFixtures.metadata());
            call.startAttempt();
            fail.set(true);
            assertFalse(call.finishAttempt(CallLifecycle.SUCCEEDED, TokenUsage.of(10, 2, 12).observation(),
                    null, 200, AccountingEvent.FinishReason.STOP, null, 10));
            assertFalse(call.finish(CallLifecycle.SUCCEEDED));
            assertEquals(1, recorder.health().bufferedTerminalEvents());
            assertEquals(1, recorder.health().lostTerminalEvents());
            recorder.flushPending();
            assertThrows(AccountingUnavailableException.class, () -> recorder.begin(AccountingFixtures.metadata()));
        }
    }

    @Test
    void no_upstream_attempt_is_explicitly_not_dispatched() {
        try (var journal = journal(channel -> channel.force(true))) {
            var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
            var call = recorder.begin(AccountingFixtures.metadata());
            assertTrue(call.finish(CallLifecycle.CANCELLED));
            var end = (AccountingEvent.CallFinished) journal.readBatch(10).getLast().event().payload();
            assertEquals(CallLifecycle.NOT_DISPATCHED, end.outcome());
        }
    }

    @Test
    void an_uncertain_attempt_start_cannot_be_closed_with_a_conflicting_zero_attempt_event() {
        var fail = new AtomicBoolean();
        try (var journal = journal(channel -> {
            if (fail.getAndSet(false)) { throw new IOException("injected start failure"); }
            channel.force(true);
        })) {
            var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
            var call = recorder.begin(AccountingFixtures.metadata());
            fail.set(true);
            assertThrows(AccountingUnavailableException.class, call::startAttempt);
            assertFalse(call.finish(CallLifecycle.FAILED));
            recorder.flushPending();
            assertEquals(1, recorder.health().lostTerminalEvents());
            assertThrows(AccountingUnavailableException.class, () -> recorder.begin(AccountingFixtures.metadata()));
            var events = journal.readBatch(10);
            assertEquals(2, events.size());
            assertTrue(events.getLast().event().payload() instanceof AccountingEvent.AttemptStarted);
            assertEquals(2, events.getLast().event().callSequence());
        }
    }

    private AccountingJournal journal(AccountingJournal.Sync sync) {
        return new AccountingJournal(directory, 1024 * 1024, 8192, Duration.ofSeconds(1), AccountingFixtures.MAPPER, sync);
    }
}
