package io.aeyer.plowshare.server.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.CallValidator;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The validator's one model call: what it is sent, what it reads back, and when it gives up. */
class ModelCallValidatorTest {

    private static final ToolSchema TODO_WRITE = new ToolSchema("todo_write",
            "Change the todo list.", Map.of("type", "object",
                    "properties", Map.of("ops", Map.of("type", "array")),
                    "required", List.of("ops")));

    private static final AgentDefinition VALIDATOR = new AgentDefinition(ModelCallValidator.AGENT,
            "decides whether a written call was meant", "fast", List.of(), List.of(), List.of(),
            1, 1, "YOU DECIDE WHETHER A REPLY WAS A CALL.");

    private static final CallValidator.Question QUESTION = new CallValidator.Question(
            "{\"ops\": [{\"op\": \"add\", \"text\": \"phase one\"}]}", TODO_WRITE, "keep the list");

    /** Answers every call with {@code answer}, records what it was sent, and may block. */
    private static final class Answering implements LlmTransport {

        final List<List<ChatMessage>> sent = Collections.synchronizedList(new ArrayList<>());
        private final Supplier<String> answer;
        volatile CountDownLatch hold;

        Answering(Supplier<String> answer) {
            this.answer = answer;
        }

        @Override
        public String poolName() {
            return "scripted";
        }

        @Override
        public Completion complete(String wireModel, List<ChatMessage> messages,
                Sampling sampling, List<ToolSchema> tools) {
            sent.add(messages);
            CountDownLatch latch = hold;
            if (latch != null) {
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new Completion(answer.get(), "stop", TokenUsage.UNKNOWN, List.of());
        }

        @Override
        public Completion stream(String wireModel, List<ChatMessage> messages, Sampling sampling,
                List<ToolSchema> tools, Deltas sink, BooleanSupplier abandoned) {
            return complete(wireModel, messages, sampling, tools);
        }

        @Override
        public Embeddings embed(String wireModel, List<String> input) {
            throw new UnsupportedOperationException("the validator does not embed");
        }

        @Override
        public void close() {
        }
    }

    private static LlmDispatcher over(LlmTransport transport) {
        return new LlmDispatcher(List.of(new LlmPool("scripted", List.of("model-fast"),
                Map.of("fast", "model-fast"), 2, 1, Duration.ofSeconds(5), transport)),
                new NoOpTokenLedger());
    }

    @Test
    void one_call_with_the_validator_s_prompt_and_a_brief_of_the_held_reply() {
        Answering transport = new Answering(() -> "{\"verdict\": \"call\", \"arguments\":"
                + " {\"ops\": []}, \"reason\": \"bare arguments\"}");

        CallValidator.Verdict verdict;
        try (ModelCallValidator validator =
                new ModelCallValidator(over(transport), () -> VALIDATOR)) {
            verdict = validator.validate(QUESTION, () -> false);
        }

        assertTrue(verdict.isCall());
        assertEquals("{\"ops\":[]}", verdict.arguments());
        assertEquals("bare arguments", verdict.reason());
        assertEquals(1, transport.sent.size());
        List<ChatMessage> sent = transport.sent.get(0);
        assertEquals(ChatMessage.Role.SYSTEM, sent.get(0).role());
        assertEquals("YOU DECIDE WHETHER A REPLY WAS A CALL.", sent.get(0).content());
        String brief = sent.get(1).content();
        assertTrue(brief.contains("keep the list"), brief);
        assertTrue(brief.contains("The tool: todo_write"), brief);
        assertTrue(brief.contains("\"required\""), brief);
        assertTrue(brief.contains(QUESTION.reply()), brief);
    }

    @Test
    void a_validator_that_does_not_answer_in_time_is_a_failure() {
        CountDownLatch release = new CountDownLatch(1);
        Answering transport = new Answering(() -> "{\"verdict\": \"call\"}");
        transport.hold = release;
        try (ModelCallValidator validator = new ModelCallValidator(over(transport),
                () -> VALIDATOR, Duration.ofMillis(100))) {
            IllegalStateException late = assertThrows(IllegalStateException.class,
                    () -> validator.validate(QUESTION, () -> false));
            assertEquals("no verdict within 100 ms", late.getMessage());
        } finally {
            release.countDown();
        }
    }

    @Test
    void a_call_that_failed_is_thrown_to_the_runtime() {
        Answering transport = new Answering(() -> {
            throw new IllegalStateException("the endpoint is gone");
        });
        try (ModelCallValidator validator =
                new ModelCallValidator(over(transport), () -> VALIDATOR)) {
            assertThrows(RuntimeException.class, () -> validator.validate(QUESTION, () -> false));
        }
    }

    /**
     * An Error out of the call is a failure at once, not a wait for the timeout: the task
     * completes the future with anything it throws, so the run's own thread is not held for the
     * whole 60 s on a call that is already over.
     */
    @Test
    void an_error_out_of_the_call_fails_at_once_rather_than_at_the_timeout() {
        Answering transport = new Answering(() -> {
            throw new AssertionError("a transport bug");
        });
        try (ModelCallValidator validator = new ModelCallValidator(over(transport),
                () -> VALIDATOR, Duration.ofSeconds(30))) {
            long before = System.nanoTime();
            RuntimeException failed = assertThrows(RuntimeException.class,
                    () -> validator.validate(QUESTION, () -> false));
            long tookMs = Duration.ofNanos(System.nanoTime() - before).toMillis();
            assertTrue(tookMs < 5_000, "took " + tookMs + " ms: the Error was lost and the"
                    + " validator waited out its timeout");
            assertFalse(failed.getMessage() != null
                    && failed.getMessage().startsWith("no verdict within"), failed.getMessage());
        }
    }

    /**
     * The seam {@code StuckTrapFactory.advise} relies on: the run's {@code cancelled} supplier
     * reaches the stream as part of its {@code abandoned} check, so a validator still generating
     * when the run is cancelled stops well before its 60 s timeout, giving its inference slot
     * back rather than holding it to the deadline.
     */
    @Test
    void a_validator_still_working_when_the_run_is_cancelled_is_abandoned_promptly() {
        CountDownLatch started = new CountDownLatch(1);
        LlmTransport transport = new LlmTransport() {

            @Override
            public String poolName() {
                return "scripted";
            }

            @Override
            public Completion complete(String wireModel, List<ChatMessage> messages,
                    Sampling sampling, List<ToolSchema> tools) {
                throw new UnsupportedOperationException("this fake only streams");
            }

            @Override
            public Completion stream(String wireModel, List<ChatMessage> messages,
                    Sampling sampling, List<ToolSchema> tools, Deltas sink,
                    BooleanSupplier abandoned) {
                started.countDown();
                long giveUpAt = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (!abandoned.getAsBoolean()) {
                    if (System.nanoTime() > giveUpAt) {
                        // The test's own failure mode: the caller's cancellation was never
                        // seen, so answer rather than hang the test suite forever.
                        return new Completion("too slow", "stop", TokenUsage.UNKNOWN, List.of());
                    }
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                throw new CallerAbandonedException(poolName());
            }

            @Override
            public Embeddings embed(String wireModel, List<String> input) {
                throw new UnsupportedOperationException("the validator does not embed");
            }

            @Override
            public void close() {
            }
        };

        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                started.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            cancelled.set(true);
        });
        try (ModelCallValidator validator = new ModelCallValidator(over(transport), () -> VALIDATOR,
                Duration.ofSeconds(30))) {
            canceller.start();
            long before = System.nanoTime();
            assertThrows(RuntimeException.class, () -> validator.validate(QUESTION, cancelled::get));
            long tookMs = Duration.ofNanos(System.nanoTime() - before).toMillis();
            assertTrue(tookMs < 5_000,
                    "took " + tookMs + " ms to notice the run was cancelled, well short of the 30"
                            + " s timeout it was given, so the run's own thread was held on a"
                            + " verdict nobody would read");
        } finally {
            try {
                canceller.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
