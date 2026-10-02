package io.aeyer.plowshare.server.llm.dispatch;

import io.aeyer.plowshare.server.llm.dispatch.Deltas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A transport that blocks and counts. The whole of this slice's concurrency
 * evidence rests on it, so it measures rather than asserts: the tests decide
 * what the numbers should be.
 */
final class FakeTransport implements LlmTransport {

    private final String name;
    private final CountDownLatch release;

    final CountDownLatch entered;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger peakInFlight = new AtomicInteger();
    /**
     * One row per call, in the order the calls were made.
     *
     * <p>One list and not a parallel list per argument. Parallel lists only
     * line up while every call is sequential and every one of them writes to
     * every list — neither of which the pool's own concurrency tests honour —
     * so "the temperature of the call that saw this model" was a question the
     * previous shape could not actually answer. Read it through {@link
     * #invocations()}, which copies under the monitor; iterating a {@code
     * synchronizedList} directly, as {@code assertEquals} does, takes no lock
     * at all.
     */
    private final List<Invocation> invocations = Collections.synchronizedList(new ArrayList<>());

    /**
     * @param input the batch, for an embedding call; null for a chat one.
     * @param tools what a chat call was offered; empty for an embedding one.
     */
    record Invocation(
            Lane lane,
            String wireModel,
            List<ChatMessage> messages,
            Sampling sampling,
            List<String> input,
            List<ToolSchema> tools) {

        /**
         * The system prompt of a two-message conversation, or null when there
         * is none.
         *
         * <p>Derived rather than stored, since the conversation became the
         * thing a transport is handed. Kept so that every assertion written
         * against {@code systemsSeen()} and {@code promptsSeen()} in slice 2
         * still means what it meant: those tests are about which prompt reached
         * which pool, which is unchanged by how the prompt travels.
         *
         * <p><b>{@code findFirst} is safe because a request cannot carry two.</b>
         * It would otherwise show one of them and hide the other from every
         * slice-2 assertion resting on this — a silent wrong answer rather than a
         * failure. What makes it safe is not this method: {@code ChatRequest}'s
         * constructor refuses a second system message outright, and a transport
         * is only ever handed {@code ChatRequest.messages()}. Written down
         * because the guarantee lives a package away, and because until slice 3e
         * it did not exist and this was simply lucky.
         */
        String system() {
            return messages.stream()
                    .filter(message -> message.role() == ChatMessage.Role.SYSTEM)
                    .map(ChatMessage::content)
                    .findFirst()
                    .orElse(null);
        }

        /** The last user message — for a two-message conversation, the prompt. */
        String user() {
            return messages.stream()
                    .filter(message -> message.role() == ChatMessage.Role.USER)
                    .map(ChatMessage::content)
                    .reduce((first, second) -> second)
                    .orElse(null);
        }
    }

    List<Invocation> invocations() {
        synchronized (invocations) {
            return List.copyOf(invocations);
        }
    }

    private <T> List<T> of(Lane lane, java.util.function.Function<Invocation, T> field) {
        return invocations().stream().filter(call -> call.lane() == lane).map(field).toList();
    }

    List<String> modelsSeen() {
        return invocations().stream().map(Invocation::wireModel).toList();
    }

    List<String> systemsSeen() {
        return of(Lane.CHAT, Invocation::system);
    }

    List<String> promptsSeen() {
        return of(Lane.CHAT, Invocation::user);
    }

    List<Sampling> samplingSeen() {
        return of(Lane.CHAT, Invocation::sampling);
    }

    List<List<String>> inputsSeen() {
        return of(Lane.EMBEDDING, Invocation::input);
    }

    List<List<ToolSchema>> toolsSeen() {
        return of(Lane.CHAT, Invocation::tools);
    }

    volatile RuntimeException failWith;
    /** Makes close() throw, which is what a pool that fails to shut down does. */
    volatile RuntimeException failCloseWith;
    volatile boolean closed;

    /** A transport nothing waits on: every call returns at once. */
    static FakeTransport free(String name) {
        return new FakeTransport(name, 0, new CountDownLatch(0));
    }

    /**
     * @param expectedEntries how many calls the test will wait to have started,
     *     via {@link #entered}
     * @param release calls block until this reaches zero
     */
    FakeTransport(String name, int expectedEntries, CountDownLatch release) {
        this.name = name;
        this.entered = new CountDownLatch(expectedEntries);
        this.release = release;
    }

    /**
     * What this transport says a model's context length is, keyed by wire model.
     *
     * <p>Here so that a test can give two transports different numbers for the
     * <em>same</em> model, which is the state {@code context-lengths} stays per
     * pool for: two nodes may load one model at different {@code --ctx-size},
     * and an operator may hold a margin under what one of them reports. The
     * interface default is empty, and that stays the answer for every fixture
     * that puts nothing here.
     */
    final Map<String, Integer> contextLengths = Collections.synchronizedMap(new LinkedHashMap<>());

    @Override
    public OptionalInt contextLength(String wireModel) {
        Integer tokens = contextLengths.get(wireModel);
        return tokens == null ? OptionalInt.empty() : OptionalInt.of(tokens);
    }

    @Override
    public String poolName() {
        return name;
    }

    @Override
    public Completion complete(
            String wireModel,
            List<ChatMessage> messages,
            Sampling sampling,
            List<ToolSchema> tools) {
        Invocation call = new Invocation(Lane.CHAT, wireModel, messages, sampling, null, tools);
        invocations.add(call);
        enter(wireModel);
        return new Completion(
                "answer for " + call.user(), "stop", TokenUsage.of(3, 5, 8), List.of());
    }

    @Override
    public Completion stream(
            String wireModel,
            List<ChatMessage> messages,
            Sampling sampling,
            List<ToolSchema> tools,
            Deltas sink,
            BooleanSupplier abandoned) {
        // The tools this call was offered, recorded like the blocking path's.
        // It used to record List.of() unconditionally, which was truthful while
        // LlmDispatcher.stream refused a tool-carrying request and became a lie
        // the moment it stopped: toolsSeen() would have reported that a request
        // carrying tools offered none, which is the exact confusion the refusal
        // existed to prevent, relocated into the fake.
        invocations.add(
                new Invocation(Lane.CHAT, wireModel, messages, sampling, null, tools));
        enter(wireModel);
        if (abandoned.getAsBoolean()) {
            throw new CallerAbandonedException(name);
        }
        sink.answered("an");
        sink.answered("swer");
        return new Completion("answer", "stop", TokenUsage.of(3, 5, 8), List.of());
    }

    @Override
    public Embeddings embed(String wireModel, List<String> input) {
        invocations.add(
                new Invocation(
                        Lane.EMBEDDING, wireModel, List.of(), Sampling.NONE, input, List.of()));
        enter(wireModel);
        List<float[]> vectors = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            vectors.add(new float[] {1.0f, 2.0f, 3.0f});
        }
        return new Embeddings(vectors, TokenUsage.of(7, null, 7));
    }

    private void enter(String wireModel) {
        calls.incrementAndGet();
        int now = inFlight.incrementAndGet();
        peakInFlight.updateAndGet(previous -> Math.max(previous, now));
        entered.countDown();
        try {
            // Bounded so a mistake in a test fails it rather than hanging the build.
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("fake transport was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted in the fake transport", e);
        } finally {
            inFlight.decrementAndGet();
        }
        if (failWith != null) {
            throw failWith;
        }
    }

    @Override
    public void close() {
        // Nothing to release, but the flag is not decoration: it is the only
        // evidence that LlmPool.close() reaches its transport at all. Deleting
        // transport.close() from the pool left all eight tests green until
        // a_closed_pool_closes_its_transport read this.
        closed = true;
        if (failCloseWith != null) {
            throw failCloseWith;
        }
    }
}
