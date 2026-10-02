package io.aeyer.plowshare.server.llm.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * What the pool is for: the local model is serial, and two concurrent calls
 * interleave at the model so that both take longer.
 *
 * <p>Before this class existed, {@code OpenAiEmbeddingClient} called {@code
 * http.newCall(request).execute()} synchronously — and OkHttp's {@code
 * maxRequestsPerHost} gates only asynchronous calls, so every Tomcat worker
 * that reached the embedding path opened its own request, up to the container
 * default of 200.
 *
 * <p>Every caller here is submitted rather than executed, and every {@code
 * Future} is waited on. A caller that throws on a pool thread would otherwise
 * reach the default uncaught-exception handler, and the regression would report
 * as an opaque latch timeout with its real cause visible only on stderr.
 */
class LlmPoolTest {

    private static final Duration GENEROUS = Duration.ofSeconds(5);

    private static LlmPool pool(String name, int chat, int embedding, LlmTransport transport) {
        return new LlmPool(name, List.of("m"), Map.of(), chat, embedding,
                Duration.ofSeconds(5), transport);
    }

    @Test
    void a_pool_of_one_never_runs_two_calls_at_once() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport("studio", 1, release);
        // One embedding slot, which is what "a pool of one" means and what the
        // peak assertion below reads. Two slots — the number this test was first
        // written with — fails it at peak 2, because both workers take a task
        // and sit in the transport together.
        LlmPool pool = pool("studio", 1, 1, transport);
        ExecutorService callers = Executors.newFixedThreadPool(8);

        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(callers.submit(() -> pool.embed("m", List.of("x"), GENEROUS)));
            }
            // Let the first call in, then let every one of them run to completion.
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS));
            release.countDown();
            for (Future<?> call : calls) {
                assertNotNull(call.get(10, TimeUnit.SECONDS));
            }
            callers.shutdown();
            assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            callers.shutdownNow();
            pool.close();
        }

        assertEquals(8, transport.calls.get());
        // The assertion the whole slice exists for.
        assertEquals(1, transport.peakInFlight.get());
    }

    /**
     * The mirror image, and it is not decoration: without it, a pool that
     * accidentally serialises everything — one shared single-threaded executor,
     * a lock in the wrong place — would pass the test above and look correct.
     */
    @Test
    void a_pool_of_three_runs_three_at_once() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport("studio", 3, release);
        LlmPool pool = pool("studio", 1, 3, transport);
        ExecutorService callers = Executors.newFixedThreadPool(3);

        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                calls.add(callers.submit(() -> pool.embed("m", List.of("x"), GENEROUS)));
            }
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS),
                    "three embedding slots, so three calls should be in flight at once");
            assertEquals(3, transport.peakInFlight.get());
            release.countDown();
            for (Future<?> call : calls) {
                assertNotNull(call.get(10, TimeUnit.SECONDS));
            }
            callers.shutdown();
            assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            callers.shutdownNow();
            pool.close();
        }
    }

    @Test
    void a_request_that_cannot_start_within_its_budget_fails() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport("studio", 1, release);
        LlmPool pool = pool("studio", 1, 1, transport);
        ExecutorService occupant = Executors.newSingleThreadExecutor();

        try {
            Future<?> held = occupant.submit(() -> pool.embed("m", List.of("held"), GENEROUS));
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS));

            LlmSaturatedException failed = assertThrows(LlmSaturatedException.class,
                    () -> pool.embed("m", List.of("queued"), Duration.ofMillis(50)));

            // Names the pool and the lane, because "saturated" without either is
            // a message an operator cannot act on.
            assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
            assertTrue(failed.getMessage().contains("embedding"), failed.getMessage());

            // Shedding means dequeued, not merely cancelled. FutureTask.cancel
            // leaves the task sitting in the queue holding its payload, and a
            // depth that counts requests this pool has already refused is a
            // depth the dispatcher would route on.
            assertEquals(0, pool.queueDepth(Lane.EMBEDDING));

            // It has not reached the model yet — but on its own that proves
            // nothing, because the one worker is still held and no queued task
            // could have run either way. The assertion that carries the
            // contract is after the drain.
            assertEquals(1, transport.calls.get());

            release.countDown();
            assertNotNull(held.get(5, TimeUnit.SECONDS));
            occupant.shutdown();
            assertTrue(occupant.awaitTermination(5, TimeUnit.SECONDS));
            // shutdown() runs whatever is still queued, so by the time close()
            // returns an unshed task has been to the model and back.
            pool.close();

            // And it never reached the model at all. A pool that reports
            // saturation while letting the call through eventually is worse than
            // one that waits: the caller has been told it failed and the box is
            // still busy. Removing the claim from LlmPool.submit leaves this at
            // 2 — and leaves every assertion above it passing, which is why it
            // is here and not before the drain.
            assertEquals(1, transport.calls.get());
        } finally {
            release.countDown();
            occupant.shutdownNow();
            pool.close();
        }
    }

    /**
     * A saturation report and the call it denies are mutually exclusive, even
     * when the slot frees at the very moment the budgets expire.
     *
     * <p>This is the interleaving {@code FutureTask.cancel(false)} could not
     * exclude, because a {@code FutureTask} stays {@code NEW} for the whole of
     * its callable: a worker could enter the transport in the few instructions
     * between {@code started.await} returning false and {@code cancel} running,
     * and {@code cancel} would still answer true. The caller was then told the
     * model had not been reached while the box was generating its answer — and
     * since the obvious response to saturation is to retry, that lie doubles
     * the load on a host that is already saturated.
     *
     * <p>Eight callers rather than one, because that is what saturation is, and
     * because each of them is an independent sample of a window a few
     * instructions wide. Their deadlines land microseconds apart by virtue of
     * being submitted in turn, which spreads them across the moment the slot
     * frees far better than any sweep applied to a single caller.
     *
     * <p><b>This test is a probabilistic guard and nothing more.</b> Against the
     * implementation that had the bug it failed on some runs and not others,
     * depending on machine load. It is not what makes the property true — the
     * CAS in {@code LlmPool.submit} is, and the argument for it is in the
     * javadoc there. This is what would eventually notice if someone removed it.
     */
    @Test
    void a_saturation_report_and_the_call_it_denies_are_mutually_exclusive() throws Exception {
        final int queued = 8;
        for (int attempt = 0; attempt < 100; attempt++) {
            CountDownLatch release = new CountDownLatch(1);
            FakeTransport transport = new FakeTransport("studio", 1, release);
            LlmPool pool = pool("studio", 1, 1, transport);
            ExecutorService occupant = Executors.newSingleThreadExecutor();
            ExecutorService callers = Executors.newFixedThreadPool(queued);
            Duration budget = Duration.ofMillis(10);
            // Sweeps the release across the moment the budgets expire, in 25us
            // steps either side of it.
            long delayNanos = TimeUnit.MILLISECONDS.toNanos(budget.toMillis())
                    - TimeUnit.MILLISECONDS.toNanos(1)
                    + attempt * 25_000L;

            try {
                Future<?> held = occupant.submit(() -> pool.embed("m", List.of("held"), GENEROUS));
                assertTrue(transport.entered.await(2, TimeUnit.SECONDS));

                long origin = System.nanoTime();
                Thread releaser = new Thread(() -> {
                    // A spin, not a sleep. Thread.sleep(millis, nanos) rounds
                    // the nanosecond part up to a whole millisecond, which is
                    // far coarser than the window being swept — the first
                    // version of this test slept 8ms against a 10ms deadline
                    // and never landed on it. Nor is this the forbidden
                    // wait-for-a-condition sleep: there is no condition, the
                    // delay is the race, and a latch would remove the very
                    // thing under test.
                    while (System.nanoTime() - origin < delayNanos) {
                        Thread.onSpinWait();
                    }
                    release.countDown();
                }, "releaser-" + attempt);
                releaser.start();

                List<Future<Boolean>> outcomes = new ArrayList<>();
                for (int i = 0; i < queued; i++) {
                    outcomes.add(callers.submit(() -> {
                        try {
                            pool.embed("m", List.of("queued"), budget);
                            return Boolean.FALSE;
                        } catch (LlmSaturatedException e) {
                            return Boolean.TRUE;
                        }
                    }));
                }

                int refused = 0;
                for (Future<Boolean> outcome : outcomes) {
                    if (outcome.get(10, TimeUnit.SECONDS)) {
                        refused++;
                    }
                }

                releaser.join(TimeUnit.SECONDS.toMillis(5));
                release.countDown();
                assertNotNull(held.get(5, TimeUnit.SECONDS));
                callers.shutdown();
                assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
                occupant.shutdown();
                assertTrue(occupant.awaitTermination(5, TimeUnit.SECONDS));
                pool.close();

                // The whole claim, in one line: the model saw the occupant plus
                // exactly those callers that were not turned away. "Turned away,
                // and the model saw it anyway" is the lie.
                assertEquals(1 + queued - refused, transport.calls.get(),
                        "attempt " + attempt + ": " + refused + " of " + queued
                                + " callers were told the model was never reached");
            } finally {
                release.countDown();
                callers.shutdownNow();
                occupant.shutdownNow();
                pool.close();
            }
        }
    }

    @Test
    void the_two_lanes_do_not_share_slots() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport("studio", 2, release);
        LlmPool pool = pool("studio", 1, 1, transport);
        ExecutorService callers = Executors.newFixedThreadPool(2);

        try {
            Future<?> chat = callers.submit(
                    () -> pool.complete("m", ChatMessage.conversation(null, "held"), Sampling.NONE, List.of(), null, GENEROUS));
            Future<?> embedding = callers.submit(() -> pool.embed("m", List.of("free"), GENEROUS));

            // Both in flight at once, on a pool whose lanes are one slot each.
            // A single shared executor would have to be sized at the smaller of
            // the two limits, and this is the test that catches that: the box
            // will happily embed a batch while it is generating tokens.
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS),
                    "the embedding lane must not be blocked by a busy chat lane");
            assertEquals(2, transport.peakInFlight.get());

            release.countDown();
            assertNotNull(chat.get(10, TimeUnit.SECONDS));
            assertNotNull(embedding.get(10, TimeUnit.SECONDS));
            callers.shutdown();
            assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            callers.shutdownNow();
            pool.close();
        }
    }

    /** A failure from the endpoint is the caller's failure, not an
     *  {@code ExecutionException} wrapping it. Callers catch {@link
     *  LlmException}; a wrapper would slip straight past every one of them. */
    @Test
    void a_transport_failure_reaches_the_caller_as_itself() {
        FakeTransport transport = FakeTransport.free("studio");
        transport.failWith = new LlmTransportException("HTTP 503");
        LlmPool pool = pool("studio", 1, 1, transport);
        try {
            LlmTransportException failed = assertThrows(LlmTransportException.class,
                    () -> pool.embed("m", List.of("x"), GENEROUS));
            assertEquals("HTTP 503", failed.getMessage());
        } finally {
            pool.close();
        }
    }

    /**
     * The interrupt path has two halves and both matter: the caller is told
     * which pool it was waiting on, and the interrupt survives the throw.
     *
     * <p>A pool that swallowed the flag would strand a shutdown — the thread
     * carries on as though nothing had asked it to stop, and whatever was
     * joining on it waits out its full timeout.
     */
    @Test
    void an_interrupted_caller_keeps_its_interrupt_and_is_told_which_pool() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport("studio", 1, release);
        LlmPool pool = pool("studio", 1, 1, transport);
        ExecutorService occupant = Executors.newSingleThreadExecutor();
        AtomicReference<LlmException> thrown = new AtomicReference<>();
        AtomicBoolean flagSurvived = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        Thread caller = new Thread(() -> {
            try {
                pool.embed("m", List.of("queued"), GENEROUS);
            } catch (LlmException e) {
                thrown.set(e);
                // Read rather than cleared-and-read: the contract is that the
                // flag is still set when the caller catches.
                flagSurvived.set(Thread.currentThread().isInterrupted());
            } finally {
                done.countDown();
            }
        }, "interrupted-caller");

        try {
            Future<?> held = occupant.submit(() -> pool.embed("m", List.of("held"), GENEROUS));
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS));

            caller.start();
            // Interrupt once, and only after the request is queued — so it lands
            // while the caller is waiting for a slot it cannot get. Earlier is
            // harmless (await checks the flag on entry) but would test less.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (pool.queueDepth(Lane.EMBEDDING) < 1 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, pool.queueDepth(Lane.EMBEDDING));
            caller.interrupt();

            assertTrue(done.await(5, TimeUnit.SECONDS), "the interrupted caller never returned");
            LlmException failed = thrown.get();
            assertNotNull(failed, "an interrupted caller must be told, not left waiting");
            // Plain LlmException and not LlmSaturatedException: the request was
            // not refused for want of capacity, and saying so would send a
            // caller off retrying against a pool that never turned it away.
            assertEquals(LlmException.class, failed.getClass(), failed.toString());
            assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
            assertTrue(failed.getMessage().contains("interrupted"), failed.getMessage());
            assertTrue(flagSurvived.get(), "the interrupt flag must survive the throw");

            // Shed on the way out, exactly as a timed-out caller would.
            assertEquals(0, pool.queueDepth(Lane.EMBEDDING));

            release.countDown();
            assertNotNull(held.get(5, TimeUnit.SECONDS));
            occupant.shutdown();
            assertTrue(occupant.awaitTermination(5, TimeUnit.SECONDS));
            pool.close();

            // And the abandoned request never reached the model.
            assertEquals(1, transport.calls.get());
        } finally {
            release.countDown();
            caller.interrupt();
            occupant.shutdownNow();
            pool.close();
        }
    }

    @Test
    void queue_depth_reports_what_is_waiting() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport("studio", 1, release);
        LlmPool pool = pool("studio", 1, 1, transport);
        ExecutorService callers = Executors.newFixedThreadPool(3);

        try {
            assertEquals(0, pool.queueDepth(Lane.EMBEDDING));
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                calls.add(callers.submit(() -> pool.embed("m", List.of("x"), GENEROUS)));
            }
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS));
            // One running, two queued. Polled rather than asserted once: the
            // third caller may not have submitted yet.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (pool.queueDepth(Lane.EMBEDDING) < 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(2, pool.queueDepth(Lane.EMBEDDING));

            release.countDown();
            for (Future<?> call : calls) {
                assertNotNull(call.get(10, TimeUnit.SECONDS));
            }
            callers.shutdown();
            assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            callers.shutdownNow();
            pool.close();
        }
    }

    /**
     * A request that names no budget gets the pool's, and gets it <em>here</em>
     * rather than at whichever caller remembered to substitute it.
     *
     * <p>{@code ChatRequest.submitTimeout()} is deliberately nullable — null
     * means "the pool decides", and zero would mean the opposite thing — so an
     * unguarded {@code budget.toMillis()} is a NullPointerException raised
     * several frames inside an executor, reported to the caller as a wrapped
     * cause with no mention of the request that caused it.
     */
    @Test
    void no_budget_means_the_pool_s_own() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport("studio", 1, release);
        LlmPool impatient = new LlmPool("studio", List.of("m"), Map.of(), 1, 1,
                Duration.ofMillis(50), transport);
        ExecutorService occupant = Executors.newSingleThreadExecutor();

        try {
            Future<?> held = occupant.submit(() -> impatient.embed("m", List.of("held"), GENEROUS));
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS));

            // Not merely "it did not throw NullPointerException": the 50ms
            // default is what makes this fail, so the pool must have used it.
            LlmSaturatedException failed = assertThrows(LlmSaturatedException.class,
                    () -> impatient.embed("m", List.of("queued"), null));
            assertTrue(failed.getMessage().contains("50ms"), failed.getMessage());

            release.countDown();
            assertNotNull(held.get(5, TimeUnit.SECONDS));
            occupant.shutdown();
            assertTrue(occupant.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            occupant.shutdownNow();
            impatient.close();
        }
    }

    /**
     * The pool owns its transport's lifetime, and nothing above it does.
     *
     * <p>Once the transport holds the OkHttp client, a {@code close()} that
     * drains the two lanes and stops there leaks the connection pool and its
     * dispatcher threads at every shutdown — silently, with nothing left
     * running to report it. {@link
     * #a_closed_pool_refuses_rather_than_dropping_the_call} cannot catch that:
     * it proves only that the lanes were shut down.
     */
    @Test
    void a_closed_pool_closes_its_transport() {
        FakeTransport transport = FakeTransport.free("studio");
        LlmPool pool = pool("studio", 1, 1, transport);

        // Asserted before as well as after, so that a flag stuck at true —
        // or one the fake sets in its constructor — cannot pass this.
        assertFalse(transport.closed, "the transport is not closed before the pool is");
        pool.close();
        assertTrue(transport.closed, "close() must release the transport, not only the lanes");
    }

    @Test
    void a_closed_pool_refuses_rather_than_dropping_the_call() {
        LlmPool pool = pool("studio", 1, 1, FakeTransport.free("studio"));
        pool.close();
        LlmException failed = assertThrows(LlmException.class,
                () -> pool.embed("m", List.of("x"), GENEROUS));
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
    }

    /** A pool whose default queue deadline is short enough to blow in a test. */
    private static LlmPool impatient(LlmTransport transport) {
        return new LlmPool("spark", List.of("m"), Map.of(), 1, 1,
                Duration.ofMillis(50), transport, Set.of(), 0);
    }

    @Test
    void a_call_with_no_deadline_waits_past_the_pools_default_for_its_slot() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        // expectedEntries is the count `entered` waits for: one, the holder.
        FakeTransport transport = new FakeTransport("spark", 1, release);
        LlmPool pool = impatient(transport);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            // Both on the EMBEDDING lane, whose one slot the holder takes.
            Future<?> holder = callers.submit(() -> pool.embed("m", List.of("x"), GENEROUS));
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS));
            Future<?> patient = callers.submit(
                    () -> pool.embed("m", List.of("y"), LlmPool.NO_DEADLINE));
            // Four times the pool's default: a deadline would have shed it by now.
            Thread.sleep(200);
            assertFalse(patient.isDone(), "a call with no deadline was given up on");
            release.countDown();
            assertNotNull(holder.get(5, TimeUnit.SECONDS));
            assertNotNull(patient.get(5, TimeUnit.SECONDS));
        } finally {
            callers.shutdownNow();
            pool.close();
        }
        assertEquals(2, transport.calls.get());
    }

    @Test
    void a_call_with_the_default_deadline_is_still_shed() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport("spark", 1, release);
        LlmPool pool = impatient(transport);
        ExecutorService callers = Executors.newFixedThreadPool(1);
        try {
            Future<?> holder = callers.submit(() -> pool.embed("m", List.of("x"), GENEROUS));
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS));
            assertThrows(LlmSaturatedException.class,
                    () -> pool.embed("m", List.of("y"), null));
            release.countDown();
            assertNotNull(holder.get(5, TimeUnit.SECONDS));
        } finally {
            callers.shutdownNow();
            pool.close();
        }
    }

    @Test
    void a_call_with_no_deadline_abandoned_while_queued_never_reaches_the_transport()
            throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport("spark", 1, release);
        LlmPool pool = impatient(transport);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        AtomicBoolean abandoned = new AtomicBoolean();
        try {
            // Both on the CHAT lane: the holder is a complete(), which FakeTransport blocks in
            // exactly as it blocks in embed() and stream().
            Future<?> holder = callers.submit(() -> pool.complete(
                    "m", List.of(), Sampling.NONE, List.of(), null, GENEROUS));
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS));
            Future<?> patient = callers.submit(() -> pool.stream("m", List.of(), Sampling.NONE,
                    List.of(), Deltas.DISCARDING, abandoned::get, null, LlmPool.NO_DEADLINE));
            Thread.sleep(100);
            abandoned.set(true);
            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> patient.get(5, TimeUnit.SECONDS));
            assertTrue(thrown.getCause() instanceof CallerAbandonedException,
                    String.valueOf(thrown.getCause()));
            release.countDown();
            assertNotNull(holder.get(5, TimeUnit.SECONDS));
        } finally {
            callers.shutdownNow();
            pool.close();
        }
        assertEquals(1, transport.calls.get(), "the abandoned call still reached the transport");
    }

    @Test
    void swarm_slots_are_what_the_pool_was_given_and_none_by_default() {
        LlmTransport transport = FakeTransport.free("spark");
        LlmPool none = pool("spark", 1, 1, transport);
        LlmPool five = new LlmPool("spark", List.of("m"), Map.of(), 8, 1,
                Duration.ofSeconds(5), transport, Set.of(), 5);
        try {
            assertEquals(0, none.swarmSlots());
            assertEquals(5, five.swarmSlots());
        } finally {
            none.close();
            five.close();
        }
    }
}
