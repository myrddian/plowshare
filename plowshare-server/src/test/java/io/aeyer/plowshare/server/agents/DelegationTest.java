package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.images.ImageFence;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmSaturatedException;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Delegation: an agent calling an agent it declared, and only that one.
 *
 * <p>No model and no socket. Every completion here is one this test wrote, and
 * every agent is a fixture in {@code src/test/resources/agents}.
 *
 * <p><b>The scaffolding is this file's own rather than {@code JobRuntimeTest}'s,
 * and the reason is a capability difference and not an oversight.</b> That
 * file's {@code Scripted} blocks only on its <em>first</em> call, which is the
 * parent's here and never the child's, and its {@code Probe} records the thread
 * it ran on but not how many callers were inside it at once. Both of those are
 * what the delegation tests need. The two fakes are deliberately narrow: each
 * pins its own file's behaviour, and a shared one growing a flag per test is how
 * a fake stops being readable.
 */
class DelegationTest {

    /**
     * How long a blocked fake waits before failing the test itself.
     *
     * <p>Far above every outer latch here, which wait ten seconds at most. An
     * inner deadline shorter than the outer one turns a slow machine into a
     * wrong diagnosis: the fake throws, the runtime absorbs it as an ordinary
     * tool result, and the red appears somewhere that says nothing about what
     * went wrong. Copied from {@code JobRuntimeTest} along with the reasoning.
     */
    private static final int BLOCK_SECONDS = 60;

    /**
     * Every tool name any fixture declares.
     *
     * <p>Written out rather than taken from {@code runtime.knownTools()}, for
     * the reason {@code JobRuntimeTest} records: {@link AgentRegistry#load}
     * validates a whole directory at once, so loading the fixtures from a
     * runtime holding one tool would be refused over a <em>different</em>
     * fixture's declaration, and a misconfiguration message would stand in for
     * the behaviour under test in every test in this file.
     */
    private static final Set<String> FIXTURE_TOOLS =
            Set.of("probe_read", "probe_write", AgentRegistry.AGENT_RUN, MemoryTools.WRITE_NAME);

    private static Path fixtures() throws Exception {
        return Path.of(DelegationTest.class.getResource("/agents").toURI());
    }

    private static AgentRegistry registry() throws Exception {
        return AgentRegistry.of(fixtures(), FIXTURE_TOOLS);
    }

    // --- scaffolding -------------------------------------------------------------

    /**
     * A transport that returns what the test queued, in order, and can be made
     * to block on a chosen call.
     *
     * <p>Steps are consumed by index. Every test in this file that scripts more
     * than one step drives one job tree at a time — a parent and its children
     * run on one thread, one after another — so the index order is the order the
     * tree makes its calls. The one test that issues a call from a second thread
     * does so while the tree is demonstrably blocked inside a tool, which is
     * what keeps that step's index deterministic too; it says so where it does
     * it.
     */
    private static final class Scripted implements LlmTransport {

        private final List<Supplier<Completion>> steps = new ArrayList<>();
        private final List<List<ChatMessage>> calls =
                Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger index = new AtomicInteger();
        private volatile Supplier<Completion> fallback = () -> answer("nothing left to say");
        private volatile Function<List<ChatMessage>, Completion> directed;
        private volatile int blockAt = -1;
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;

        Scripted then(Supplier<Completion> step) {
            steps.add(step);
            return this;
        }

        Scripted thenAlways(Supplier<Completion> step) {
            fallback = step;
            return this;
        }

        /** A fallback that decides from the conversation rather than from a
         *  call index. The only way to script two job trees running at once:
         *  an index says nothing about which tree's turn it is holding. */
        Scripted thenAlwaysFor(Function<List<ChatMessage>, Completion> step) {
            this.directed = step;
            return this;
        }

        /** Makes the {@code nth} call (zero-based) block until {@code release}
         *  counts down, so a test can act while a job is demonstrably inside a
         *  model call. */
        Scripted blockingOn(int nth, CountDownLatch entered, CountDownLatch release) {
            this.blockAt = nth;
            this.entered = entered;
            this.release = release;
            return this;
        }

        List<List<ChatMessage>> calls() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }

        @Override
        public String poolName() {
            return "scripted";
        }

        @Override
        public Completion complete(
                String wireModel, List<ChatMessage> messages, Sampling sampling,
                List<ToolSchema> tools) {
            int at = index.getAndIncrement();
            calls.add(messages);
            if (at == blockAt) {
                entered.countDown();
                await(release, "the scripted transport was never released");
            }
            if (directed != null) {
                return directed.apply(messages);
            }
            return at < steps.size() ? steps.get(at).get() : fallback.get();
        }

        @Override
        public Completion stream(
                String wireModel, List<ChatMessage> messages, Sampling sampling,
                List<ToolSchema> tools, io.aeyer.plowshare.server.llm.dispatch.Deltas sink,
                java.util.function.BooleanSupplier abandoned) {
            // The job runtime streams now. This double answers the same
            // thing either way, on purpose: reconciling two wire formats is the
            // transport's problem and OpenAiTransportTest is where it is
            // proved, so a fake that answered differently down this path would
            // only be testing itself. Delegating to complete(...) keeps every
            // assertion in this class — what a turn was offered, what it sent,
            // what came back — meaning exactly what it meant.
            Completion streamed = complete(wireModel, messages, sampling, tools);
            // Asked after the call, which is where a fake can honestly ask it:
            // the real transport asks once per chunk, and this one has exactly
            // one chunk. See LlmTransport.stream and CallerAbandonedException.
            if (abandoned.getAsBoolean()) {
                throw new CallerAbandonedException(poolName());
            }
            String content = streamed.content();
            if (content != null && !content.isEmpty()) {
                sink.answered(content);
            }
            return streamed;
        }

        @Override
        public Embeddings embed(String wireModel, List<String> input) {
            throw new UnsupportedOperationException("the job runtime does not embed");
        }

        @Override
        public void close() {
        }
    }

    /**
     * A tool that records how many callers were inside it at once, and can be
     * made to block.
     *
     * <p>The peak is what {@code two_agent_run_calls_in_one_batch_run_one_at_a_time}
     * reads: sub-agents dispatched in one batch must not overlap.
     */
    private static final class Probe implements AgentTool {

        private final ToolSchema schema;
        private final Function<String, String> behaviour;
        private final AtomicInteger inside = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        private final List<Home> homes = Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;
        private volatile CountDownLatch rendezvous;
        private volatile long dwellMillis;

        Probe(String name, Function<String, String> behaviour) {
            this.schema = new ToolSchema(name, "a probe called " + name,
                    Map.of("type", "object", "properties", Map.of()));
            this.behaviour = behaviour;
        }

        static Probe returning(String name, String result) {
            return new Probe(name, args -> result);
        }

        Probe blockingOn(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
            return this;
        }

        /**
         * Hold each caller until {@code expected} of them are inside, or until
         * {@code dwellMillis} passes.
         *
         * <p><b>Without this the peak is blind, and that was a real finding
         * rather than a hypothetical.</b> A probe that returns the instant it is
         * entered records a peak of one against a genuinely concurrent
         * dispatcher, because two children have to be inside the same few
         * microseconds to be seen at all — measured: a concurrent batch loop
         * passed {@code two_agent_run_calls_in_one_batch_run_one_at_a_time}
         * three runs out of four, and the peak assertion did not fire in any of
         * the four. The rendezvous removes the race in the direction that
         * matters: concurrent callers meet and the latch opens at once, while a
         * sequential dispatcher pays the timeout per call and still records one.
         */
        Probe holdingFor(int expected, long dwellMillis) {
            this.rendezvous = new CountDownLatch(expected);
            this.dwellMillis = dwellMillis;
            return this;
        }

        int peak() {
            return peak.get();
        }

        List<Thread> threads() {
            synchronized (threads) {
                return List.copyOf(threads);
            }
        }

        List<Home> homes() {
            synchronized (homes) {
                return List.copyOf(homes);
            }
        }

        @Override
        public ToolSchema schema() {
            return schema;
        }

        @Override
        public String run(String argumentsJson, Home home) {
            threads.add(Thread.currentThread());
            homes.add(home);
            peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
            try {
                if (rendezvous != null) {
                    rendezvous.countDown();
                    try {
                        // The return value is deliberately ignored: a timeout is
                        // the ordinary sequential outcome, not a failure.
                        boolean unusedMet = rendezvous.await(dwellMillis, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted at the probe rendezvous", e);
                    }
                }
                if (entered != null) {
                    entered.countDown();
                    await(release, "the probe tool was never released");
                }
                return behaviour.apply(argumentsJson);
            } finally {
                inside.decrementAndGet();
            }
        }
    }

    private static void await(CountDownLatch latch, String complaint) {
        try {
            if (!latch.await(BLOCK_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(complaint);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting: " + complaint, e);
        }
    }

    private static Completion answer(String content) {
        return new Completion(content, "stop", TokenUsage.UNKNOWN, List.of());
    }

    private static Completion cutOff(String content) {
        return new Completion(content, "length", TokenUsage.UNKNOWN, List.of());
    }

    private static Completion asking(String content, ToolCall... wanted) {
        return new Completion(content, "tool_calls", TokenUsage.UNKNOWN, List.of(wanted));
    }

    private static ToolCall call(String id, String name, String arguments) {
        return new ToolCall(id, name, arguments);
    }

    /** {@code agent_run} arguments, as the model would send them. */
    private static String delegate(String agent, String task) {
        return "{\"agent\": \"" + agent + "\", \"task\": \"" + task + "\"}";
    }

    private static LlmDispatcher dispatcherOver(LlmTransport transport, int chatSlots) {
        return new LlmDispatcher(
                List.of(new LlmPool("scripted", List.of("model-fast"), Map.of("fast", "model-fast"),
                        chatSlots, 1, Duration.ofSeconds(5), transport)),
                new NoOpTokenLedger());
    }

    private static JobRuntime delegating(LlmTransport transport, AgentTool... tools)
            throws Exception {
        AgentRegistry registry = registry();
        return new JobRuntime(dispatcherOver(transport, 4), List.of(tools), () -> registry);
    }

    /** The same runtime over a tier that actually holds images, for the three
     *  tests about an id a caller was told rather than shown. Every other
     *  fixture here gets {@link ImageStore#NONE} from the constructor above,
     *  which is the state this class ran in before an id could be named. */
    private static JobRuntime delegating(LlmTransport transport, ImageStore images)
            throws Exception {
        AgentRegistry registry = registry();
        return new JobRuntime(dispatcherOver(transport, 4), List.of(), () -> registry, null,
                Instant::now, Reminding.NONE, images);
    }

    /** {@code ImageStoreTest.storeIn}'s fixture: the tier names the directory,
     *  with the project's name standing in for the id its own binding would
     *  resolve. Under a {@code @TempDir}, so nothing here writes into the module. */
    private static ImageStore imagesIn(Path root) {
        return new ImageStore(
                home -> home.isGlobal() ? root.resolve("global") : root.resolve(home.project()),
                4096);
    }

    /**
     * The same fixture, able to name a picture in a project's own file.
     *
     * <p>The fence is a lambda over a mutable set rather than {@code
     * ImagesConfig.fenceOver} — this class has no database and no {@code
     * ProjectStore}, and what these three tests are about is what {@code
     * agent_run} <em>says</em> for each of the three outcomes. {@code
     * WorkspaceImagesTest} is where the production binding is driven against a
     * real {@code projects} row, which is the half a lambda could not honestly
     * stand in for.
     */
    private static ImageStore imagesIn(Path root, Set<Path> reachable) {
        return new ImageStore(
                home -> home.isGlobal() ? root.resolve("global") : root.resolve(home.project()),
                4096,
                (home, file) -> reachable.contains(file));
    }

    /** Eight bytes with a PNG signature on the front, which is all {@code
     *  ImageFormat} reads to decide what it is. */
    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    private static Budget generous() {
        return Budget.of(100);
    }

    /** Every tool result the {@code index}-th request carried, joined. */
    private static String toolResults(Scripted transport, int index) {
        return transport.calls().get(index).stream()
                .filter(message -> message.role() == ChatMessage.Role.TOOL)
                .map(ChatMessage::content)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** The last tool result the {@code index}-th request carried. A request
     *  carries every result said so far, so a test about one particular refusal
     *  has to name the one that had just arrived. */
    private static String lastToolResult(Scripted transport, int index) {
        List<ChatMessage> messages = transport.calls().get(index);
        for (int at = messages.size() - 1; at >= 0; at--) {
            if (messages.get(at).role() == ChatMessage.Role.TOOL) {
                return messages.get(at).content();
            }
        }
        throw new AssertionError("request " + index + " carried no tool result");
    }

    /** Every tool result every request carried, joined. */
    private static String allToolResults(Scripted transport) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < transport.calls().size(); i++) {
            out.append(toolResults(transport, i)).append('\n');
        }
        return out.toString();
    }

    /** The parent's first turn, delegating one task to {@code agent}. */
    private static Completion delegates(String agent, String task) {
        return asking("handing it over", call("c1", AgentRegistry.AGENT_RUN,
                delegate(agent, task)));
    }

    // --- an agent gets its child's answer ----------------------------------------

    @Test
    void a_parent_gets_its_child_s_answer_as_a_tool_result() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "which programme?"))
                .then(() -> answer("Sundial"))
                .then(() -> answer("helper says Sundial"));
        JobRuntime runtime = delegating(transport);

        Outcome outcome = runtime.run(agent("boss"), "ask helper", Home.global(), generous(), null);

        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals("helper says Sundial", outcome.text());
        // Three calls: the parent's first turn, the child's whole run, the
        // parent's second turn. The child's is not the parent's — see
        // a_child_s_model_calls_are_not_counted_as_the_parent_s.
        assertEquals(3, transport.calls().size());
        assertEquals("the agent 'helper' answered:\nSundial", toolResults(transport, 2));
    }

    /**
     * A returned delegation is the caller's work landing: the runtime tells its seam the caller's
     * conversation, which the orchestration wiring turns into a reset of that conductor's nudges.
     * Measured 2026-09-28, {@code orc_3187D648AC346812}: an hour of coder and reviewer work between
     * three prose endings counted for nothing, and the run was failed {@code stuck}.
     */
    @Test
    void a_returned_delegation_tells_the_runtime_s_seam_the_caller_s_conversation()
            throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "which programme?"))
                .then(() -> answer("Sundial"))
                .then(() -> answer("helper says Sundial"));
        JobRuntime runtime = delegating(transport);
        List<String> returned = new java.util.concurrent.CopyOnWriteArrayList<>();
        runtime.useDelegationReturned(returned::add);
        Transcript boss = new Transcript() {
            @Override
            public List<ChatMessage> before() {
                return List.of();
            }

            @Override
            public void promptMeasured(int promptTokens) {
            }

            @Override
            public String conversationId() {
                return "cnv_boss";
            }
        };

        Outcome outcome = runtime.run(agent("boss"), "ask helper", Home.global(), generous(),
                () -> false, null, JobWatch.UNWATCHED, boss);

        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals(List.of("cnv_boss"), returned);
    }

    @Test
    void a_delegation_is_told_going_out_and_coming_back() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "which programme?"))
                .then(() -> answer("Sundial"))
                .then(() -> answer("helper says Sundial"));
        JobRuntime runtime = delegating(transport);
        ToldActivity activity = new ToldActivity();
        runtime.useActivity(activity);

        runtime.run(agent("boss"), "ask helper", Home.global(), generous(), null);

        assertEquals(List.of(
                "called null boss agent_run [helper]",
                "delegated boss helper which programme?",
                "delegate returned boss helper ANSWERED Sundial",
                "returned agent_run ok"), activity.told());
    }

    /** A delegate that did not answer is its call's outcome in its own ending's word, not
     *  {@code ok}; and the call that started none is refused. */
    @Test
    void a_delegate_that_did_not_answer_is_told_by_its_ending() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .then(() -> asking("looking", call("c9", "probe_read", "{}")))
                .then(() -> asking("looking again", call("c9", "probe_read", "{}")))
                .then(() -> asking("and again", call("c9", "probe_read", "{}")))
                .then(() -> delegates("nobody", "go"))
                .then(() -> answer("parent answer"));
        JobRuntime runtime = delegating(transport, Probe.returning("probe_read", "a memory"));
        ToldActivity activity = new ToldActivity();
        runtime.useActivity(activity);

        runtime.run(agent("boss"), "ask helper", Home.global(), generous(), null);

        assertEquals(List.of("returned agent_run turn_cap", "returned agent_run refused"),
                activity.told().stream().filter(line -> line.startsWith("returned agent_run"))
                        .toList());
        assertTrue(activity.told().stream().anyMatch(line -> line.startsWith(
                "delegate returned boss helper TURN_CAP This run stopped at its cap of 3 steps")),
                activity.told().toString());
    }

    /** The record's delegation lines sit beside the progress seam and not in place of it: one
     *  returned delegation tells both, on the caller's conversation. */
    @Test
    void a_returned_delegation_tells_the_record_and_the_progress_seam_both() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "which programme?"))
                .then(() -> answer("Sundial"))
                .then(() -> answer("helper says Sundial"));
        JobRuntime runtime = delegating(transport);
        List<String> returned = new java.util.concurrent.CopyOnWriteArrayList<>();
        runtime.useDelegationReturned(returned::add);
        List<String> told = new java.util.concurrent.CopyOnWriteArrayList<>();
        runtime.useActivity(new RunActivity() {
            @Override
            public void delegated(String conversation, String agent, String callee, String task,
                    String calleeConversation) {
                told.add("delegated " + conversation);
            }

            @Override
            public void delegateReturned(String conversation, String agent, String callee,
                    Outcome outcome) {
                told.add("returned " + conversation);
            }
        });
        Transcript boss = new Transcript() {
            @Override
            public List<ChatMessage> before() {
                return List.of();
            }

            @Override
            public void promptMeasured(int promptTokens) {
            }

            @Override
            public String conversationId() {
                return "cnv_boss";
            }
        };

        runtime.run(agent("boss"), "ask helper", Home.global(), generous(), () -> false, null,
                JobWatch.UNWATCHED, boss);

        assertEquals(List.of("delegated cnv_boss", "returned cnv_boss"), told);
        assertEquals(List.of("cnv_boss"), returned);
    }

    /**
     * Rule 6 (spec 2026-09-29 §3): the harness hands the run's paths down with the task. Measured
     * 2026-09-28: a code_reviewer, given none, built a path from the root's folder name and the
     * phase's id and read a directory that did not exist. The record's line keeps the task as the
     * model wrote it: the note is the harness's, not the conductor's.
     */
    @Test
    void a_conductor_s_delegation_carries_the_run_s_directory_in_the_task() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "write the tests"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport);
        runtime.useHandoffs(conversation -> "cnv_boss".equals(conversation)
                ? java.util.Optional.of(
                        "[harness] The run's artifacts directory, in the project, is d/.")
                : java.util.Optional.empty());
        List<String> delegated = new java.util.concurrent.CopyOnWriteArrayList<>();
        runtime.useActivity(new RunActivity() {
            @Override
            public void delegated(String conversation, String agent, String callee, String task,
                    String calleeConversation) {
                delegated.add(task);
            }
        });
        Transcript boss = new Transcript() {
            @Override
            public List<ChatMessage> before() {
                return List.of();
            }

            @Override
            public void promptMeasured(int promptTokens) {
            }

            @Override
            public String conversationId() {
                return "cnv_boss";
            }
        };

        runtime.run(agent("boss"), "ask helper", Home.global(), generous(), () -> false, null,
                JobWatch.UNWATCHED, boss);

        assertEquals("write the tests\n\n[harness] The run's artifacts directory, in the project,"
                + " is d/.", transport.calls().get(1).get(1).content());
        assertEquals(List.of("write the tests"), delegated);
    }

    /**
     * Measured 2026-09-30, orc_3190C667F18B8E57: a reviewer that runs nothing claimed a test
     * fails that the harness's check had just passed with. The check's result goes ABOVE the task,
     * as the harness's, whatever the conductor's task says about it; the paths note stays below,
     * and the record's line keeps the task as the model wrote it. The seam is asked with the
     * callee's own definition, which is how it tells a reviewer from the coder.
     */
    @Test
    void a_conductor_s_delegation_carries_the_harness_s_facts_above_the_task() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "the check failed; review the change"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport);
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        runtime.useTaskFacts((conversation, callee) -> {
            asked.add(conversation + " " + callee.name());
            return java.util.Optional.of("[harness] The run's check `make test` last ran at"
                    + " 2026-09-30T14:05:12Z: passed (exit 0).");
        });
        runtime.useHandoffs(conversation -> java.util.Optional.of(
                "[harness] The run's artifacts directory, in the project, is d/."));
        List<String> delegated = new java.util.concurrent.CopyOnWriteArrayList<>();
        runtime.useActivity(new RunActivity() {
            @Override
            public void delegated(String conversation, String agent, String callee, String task,
                    String calleeConversation) {
                delegated.add(task);
            }
        });

        runtime.run(agent("boss"), "ask helper", Home.global(), generous(), () -> false, null,
                JobWatch.UNWATCHED, conducting("cnv_boss"));

        assertEquals("[harness] The run's check `make test` last ran at 2026-09-30T14:05:12Z:"
                + " passed (exit 0).\n\nthe check failed; review the change\n\n[harness] The"
                + " run's artifacts directory, in the project, is d/.",
                transport.calls().get(1).get(1).content());
        assertEquals(List.of("cnv_boss helper"), asked);
        assertEquals(List.of("the check failed; review the change"), delegated);
    }

    /** A facts seam that throws costs the delegation its facts and nothing else. */
    @Test
    void a_facts_seam_that_throws_sends_the_task_as_written() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "review the change"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport);
        runtime.useTaskFacts((conversation, callee) -> {
            throw new IllegalStateException("store down");
        });

        runtime.run(agent("boss"), "ask helper", Home.global(), generous(), () -> false, null,
                JobWatch.UNWATCHED, conducting("cnv_boss"));

        assertEquals("review the change", transport.calls().get(1).get(1).content());
    }

    private static Transcript conducting(String id) {
        return new Transcript() {
            @Override
            public List<ChatMessage> before() {
                return List.of();
            }

            @Override
            public void promptMeasured(int promptTokens) {
            }

            @Override
            public String conversationId() {
                return id;
            }
        };
    }

    /** A seam that throws costs the delegation its note and nothing else. */
    @Test
    void a_hand_off_seam_that_throws_sends_the_task_as_written() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "write the tests"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport);
        runtime.useHandoffs(conversation -> {
            throw new IllegalStateException("store down");
        });
        Transcript boss = new Transcript() {
            @Override
            public List<ChatMessage> before() {
                return List.of();
            }

            @Override
            public void promptMeasured(int promptTokens) {
            }

            @Override
            public String conversationId() {
                return "cnv_boss";
            }
        };

        runtime.run(agent("boss"), "ask helper", Home.global(), generous(), () -> false, null,
                JobWatch.UNWATCHED, boss);

        assertEquals("write the tests", transport.calls().get(1).get(1).content());
    }

    /** The task the model wrote is the child's user prompt, and the child's own
     *  prompt is still its system message: a child is a run, not a rendered
     *  continuation of its parent's conversation. */
    @Test
    void the_task_the_parent_wrote_is_the_child_s_prompt() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "count the sundials"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("boss"), "ask helper", Home.global(), generous(), null);

        List<ChatMessage> child = transport.calls().get(1);
        assertEquals(List.of(ChatMessage.Role.SYSTEM, ChatMessage.Role.USER),
                child.stream().map(ChatMessage::role).toList());
        assertEquals("count the sundials", child.get(1).content());
        assertEquals(agent("helper").prompt(), child.get(0).content());
    }

    /** A child's model calls are the tree's spending and not the parent's own
     *  count. {@code Outcome.modelCalls} is documented as this job only; the
     *  shared {@link Budget} is what knows about the tree. */
    @Test
    void a_child_s_model_calls_are_not_counted_as_the_parent_s() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .then(() -> asking("looking", call("c9", "probe_read", "{}")))
                .then(() -> answer("child answer"))
                .then(() -> answer("parent answer"));
        Budget budget = generous();
        JobRuntime runtime = delegating(transport, Probe.returning("probe_read", "a memory"));

        Outcome outcome = runtime.run(agent("boss"), "ask helper", Home.global(), budget, null);

        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals(2, outcome.modelCalls(), "the parent counted its child's calls as its own");
        assertEquals(2, outcome.steps());
        assertEquals(4, budget.spent(), "the tree's spending is what the budget knows");
    }

    /**
     * <b>A child that stopped is never dressed as a child that answered.</b>
     *
     * <p>The child's own text is a sentence the runtime wrote naming the ending
     * and the tools it called, and it travels to the parent unchanged. The
     * prefix says the same thing in the tool result's own first clause, so a
     * model reading only the first line is not misled either.
     */
    @Test
    void a_child_that_did_not_answer_is_not_reported_as_one() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .then(() -> asking("looking", call("c9", "probe_read", "{}")))
                .then(() -> asking("looking again", call("c9", "probe_read", "{}")))
                .then(() -> asking("and again", call("c9", "probe_read", "{}")))
                .then(() -> answer("parent answer"));
        JobRuntime runtime = delegating(transport, Probe.returning("probe_read", "a memory"));

        Outcome outcome = runtime.run(agent("boss"), "ask helper", Home.global(), generous(), null);

        assertEquals(Ending.ANSWERED, outcome.ending());
        String result = toolResults(transport, 4);
        assertTrue(result.startsWith("the agent 'helper' did not reach an answer."), result);
        assertTrue(result.contains("stopped at its cap of 3 steps"), result);
        assertTrue(result.contains("probe_read, probe_read, probe_read"), result);
        assertFalse(result.contains("answered:"), result);
        // The child's own prose — "looking", "and again" — is nowhere in what
        // the parent was told. That is Outcome's rule, arriving here.
        assertFalse(result.contains("and again"), result);
    }

    /** A child cut off mid-sentence answered, but not wholly: the note {@code
     *  Outcome.detail} carries for that case is the one thing separating a
     *  finished answer from a truncated one, and it must reach the parent. */
    @Test
    void a_child_answer_the_model_was_cut_off_mid_way_through_says_so() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .then(() -> cutOff("the three programmes are Sundial, Gnomon and"))
                .thenAlways(() -> answer("parent answer"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("boss"), "ask helper", Home.global(), generous(), null);

        String result = toolResults(transport, 2);
        assertTrue(result.startsWith("the agent 'helper' answered:\n"), result);
        assertTrue(result.contains("cut off part-way through"), result);
    }

    /** The ordinary answer carries no note, which is what makes the note above
     *  worth reading when it does appear. */
    @Test
    void an_ordinary_child_answer_carries_no_note() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .then(() -> answer("Sundial"))
                .thenAlways(() -> answer("parent answer"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("boss"), "ask helper", Home.global(), generous(), null);

        assertEquals("the agent 'helper' answered:\nSundial", toolResults(transport, 2));
    }

    /** 1a: whatever the child said, the conductor reads what it did — told by the runtime's
     *  activity, which the record answers in production. */
    @Test
    void a_delegation_s_result_ends_with_the_facts_the_activity_gives() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "fix it"))
                .then(() -> answer("All tests pass."))
                .thenAlways(() -> answer("ok"));
        JobRuntime runtime = delegating(transport);
        runtime.useActivity(new RunActivity() {
            @Override
            public String delegationFacts(String conversation, String callee) {
                return "[harness] " + callee + " edited nothing and ran nothing";
            }
        });

        runtime.run(agent("boss"), "ask helper", Home.global(), generous(), null);

        assertEquals("the agent 'helper' answered:\nAll tests pass.\n\n[harness] helper edited"
                + " nothing and ran nothing", toolResults(transport, 2));
    }

    /**
     * A child runs against the tier its parent was given, and the model never
     * gets to say which.
     *
     * <p>The schema has no {@code project} field for the same reason {@code
     * MemoryTools}' has none: <b>a tool must not let an agent name its own
     * tier.</b> Delegation is the way that rule would most easily be lost — an
     * agent that could start a child anywhere would have widened its own reach
     * to global by typing a word — so the home is taken from {@link
     * AgentTool#run}'s parameter and passed down, and this is what would notice
     * a child started against a default instead.
     */
    @Test
    void a_child_runs_against_the_home_its_parent_was_given() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .then(() -> asking("looking", call("c9", "probe_read", "{}")))
                .thenAlways(() -> answer("done"));
        Probe read = Probe.returning("probe_read", "a memory");
        JobRuntime runtime = delegating(transport, read);

        runtime.run(agent("boss"), "go", Home.of("excalibur"), generous(), null);

        assertEquals(List.of(Home.of("excalibur")), read.homes());
    }

    // --- the declared list, enforced at run time ---------------------------------

    /**
     * The declared list is the guard, and it is checked at run time as well as
     * at load: a model can name any string it likes.
     *
     * <p>{@code echo} is a real agent this registry serves. Being real is not
     * the question — being declared is.
     */
    @Test
    void an_agent_not_in_calls_is_refused_as_a_tool_result() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("echo", "do my work"))
                .then(() -> answer("I will ask helper instead"));
        JobRuntime runtime = delegating(transport);

        Outcome outcome = runtime.run(agent("boss"), "ask echo", Home.global(), generous(), null);

        // Not an exception: the model can correct this on its next turn, and the
        // run does exactly that.
        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals("I will ask helper instead", outcome.text());
        assertEquals("the agent 'boss' may not call 'echo'. The agents it may call are"
                + " [frugal, helper, middle].", toolResults(transport, 1));
        // Two calls only: the refusal cost no child run.
        assertEquals(2, transport.calls().size());
    }

    /** An agent nobody wrote and an agent this one may not call get the same
     *  answer, because from where the model stands they are the same fact —
     *  the reasoning {@code JobRuntime.offeredTo} gives for a withheld tool. */
    @Test
    void an_agent_nobody_wrote_is_refused_in_the_same_words() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("stranger", "do my work"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("boss"), "ask a stranger", Home.global(), generous(), null);

        assertEquals("the agent 'boss' may not call 'stranger'. The agents it may call are"
                + " [frugal, helper, middle].", toolResults(transport, 1));
    }

    /** An invented name full of newlines does not turn one sentence into
     *  twenty. Not a forgery defence — a tool result is the content of a JSON
     *  field and has no way out of it — but the same tidiness {@code
     *  JobRuntime.noSuchTool} applies for the same reason. */
    @Test
    void an_invented_agent_name_is_flattened_into_one_line() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> asking("handing it over", call("c1", AgentRegistry.AGENT_RUN,
                        "{\"agent\": \"a\\nb\\nc\", \"task\": \"go\"}")))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("boss"), "go", Home.global(), generous(), null);

        assertTrue(toolResults(transport, 1).contains("may not call 'a b c'"),
                toolResults(transport, 1));
    }

    /**
     * A declared callee this process does not serve.
     *
     * <p>Unreachable through any registry the boot builds — {@code
     * AgentRegistry}'s constructor and {@code load} both check every declared
     * callee over the whole set, and a boot's {@code read} drops any name no
     * file defines out of the caller's list, so the caller of a run always
     * comes from a registry where every name it holds was written somewhere. It is reachable, and pinned here, through
     * a definition built directly: the point is that the run continues with
     * something readable rather than dying, and that no separate guard is
     * needed to get that.
     *
     * <p><b>The schema is the half that has to be built before the run has a
     * turn to report a failure against.</b> {@code AgentRunTool} quotes each
     * callee's own {@code description:} into it, and a lookup that threw on a
     * miss would end this run at construction with {@code 0, 0} for a count.
     * The name arrives with nothing after it instead, which is the whole of what
     * the registry knows about an agent it does not serve.
     */
    @Test
    void a_declared_callee_this_process_does_not_serve_is_a_tool_result() throws Exception {
        // Reachable in production since the disable rule, where it used to be
        // reachable only from a definition built by hand: a callee can now be
        // defined, refused for its own reasons and left out of the set while its
        // caller keeps running. This is "the edge fails at call time" as an
        // actual code path rather than as a sentence in a design note.
        AgentDefinition stray = new AgentDefinition("stray", "declares a callee nobody serves",
                "fast", List.of(AgentRegistry.AGENT_RUN), List.of("nowhere"), List.of(), 4, 8,
                "You call an agent that is not here.");
        Scripted transport = new Scripted()
                .then(() -> delegates("nowhere", "go"))
                .then(() -> answer("nothing came back"));
        JobRuntime runtime = delegating(transport);

        Outcome outcome = runtime.run(stray, "go", Home.global(), generous(), null);

        assertEquals(Ending.ANSWERED, outcome.ending());
        String result = toolResults(transport, 1);
        assertTrue(result.contains("'nowhere'"), result);
        assertTrue(result.contains("not in the set this run can delegate into"), result);
        // And the sentence names all three ways to get here rather than the two
        // it used to. A project-tier agent calling another project-tier agent
        // takes this path every time -- this tool holds the boot set -- and was
        // being told its callee "was read and refused, or this process defines
        // no such agent", both of which are false for it.
        assertTrue(result.contains("project's own agents/ or bots/"), result);
        // And NOT through JobRuntime's generic tool-failure clause, which says
        // "this is a fault in the tool rather than in what you sent it". That is
        // exactly backwards: nothing is wrong with agent_run, and one agent this
        // caller may reach is not there.
        assertFalse(result.contains("fault in the tool"), result);

        String description = new AgentRunTool(registry(), runtime, stray, generous(),
                () -> false, null, Transcript.NONE, List.of(), ImageStore.NONE)
                .schema().description();
        assertTrue(description.contains("\n- nowhere"), description);
        assertFalse(description.contains("- nowhere:"), description);
    }

    /** An agent with the tool and an empty callee list is refused at load, so
     *  this shape arrives only from a definition built directly. The sentence
     *  still has to be one somebody can read. */
    @Test
    void an_agent_that_may_call_nobody_is_told_so() throws Exception {
        AgentDefinition alone = new AgentDefinition("alone", "has the tool and no callees",
                "fast", List.of(AgentRegistry.AGENT_RUN), List.of(), List.of(), 4, 8,
                "You are alone.");
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport);

        runtime.run(alone, "go", Home.global(), generous(), null);

        assertEquals("the agent 'alone' may not call 'helper'. It may call no other agent.",
                toolResults(transport, 1));
        // And the schema it was shown says the same thing, rather than offering
        // an empty list for it to pick from.
        assertTrue(new AgentRunTool(registry(), runtime, alone, generous(), () -> false, null,
                        Transcript.NONE, List.of(), ImageStore.NONE)
                        .schema().description().contains("may call no other agent"),
                "the schema offered a callee list it did not have");
    }

    // --- arguments ---------------------------------------------------------------

    @Test
    void arguments_that_are_not_json_are_a_tool_result() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> asking("handing it over",
                        call("c1", AgentRegistry.AGENT_RUN, "helper, please")))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport);

        Outcome outcome = runtime.run(agent("boss"), "go", Home.global(), generous(), null);

        assertEquals(Ending.ANSWERED, outcome.ending());
        String result = toolResults(transport, 1);
        assertTrue(result.contains("not valid JSON"), result);
        assertTrue(result.contains(AgentRegistry.AGENT_RUN), result);
    }

    @Test
    void a_call_with_no_agent_or_no_task_is_a_tool_result() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> asking("handing it over",
                        call("c1", AgentRegistry.AGENT_RUN, "{\"task\": \"go\"}"),
                        call("c2", AgentRegistry.AGENT_RUN, "{\"agent\": \"helper\"}"),
                        call("c3", AgentRegistry.AGENT_RUN,
                                "{\"agent\": \"helper\", \"task\": \"   \"}")))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("boss"), "go", Home.global(), generous(), null);

        List<ChatMessage> second = transport.calls().get(1);
        List<String> results = second.stream()
                .filter(m -> m.role() == ChatMessage.Role.TOOL)
                .map(ChatMessage::content)
                .toList();
        assertEquals(3, results.size());
        // "a 'agent'", not "an 'agent'": ToolArguments writes one sentence for
        // every tool's every required argument and does not inflect the article.
        // Asserted as it actually reads, because a test written against the
        // grammar somebody expected would be pinning nothing.
        assertTrue(results.get(0).contains("needs a 'agent'"), results.get(0));
        assertTrue(results.get(1).contains("needs a 'task'"), results.get(1));
        assertTrue(results.get(2).contains("needs a 'task'"), results.get(2));
        // Not one child was started for any of the three.
        assertEquals(2, transport.calls().size());
    }

    @Test
    void the_tool_names_the_argument_that_was_missing() throws Exception {
        AgentRegistry registry = registry();
        AgentTool tool = new AgentRunTool(registry, delegating(new Scripted()),
                agent("boss"), generous(), () -> false, null, Transcript.NONE, List.of(),
                ImageStore.NONE);

        assertEquals("argumentsJson", assertThrows(NullPointerException.class,
                () -> tool.run(null, Home.global())).getMessage());
        assertEquals("home", assertThrows(NullPointerException.class,
                () -> tool.run("{}", null)).getMessage());
    }

    // --- the budget is a property of the tree ------------------------------------

    /**
     * Budget is a property of the tree, not of one agent: two agents each under
     * their own limit can still eat the box between them.
     *
     * <p>Three calls in the whole tree. The parent spends one delegating, the
     * child spends two, and the parent's second turn has nothing left to spend
     * — so it ends at the budget rather than making its own second call. A
     * per-agent copy of the budget passes every single-agent test in {@code
     * JobRuntimeTest} and fails only here.
     */
    @Test
    void the_call_budget_is_shared_between_parent_and_child() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .then(() -> asking("looking", call("c9", "probe_read", "{}")))
                .then(() -> answer("child answer"))
                .thenAlways(() -> answer("this must never be reached"));
        Budget budget = Budget.of(3);
        JobRuntime runtime = delegating(transport, Probe.returning("probe_read", "a memory"));

        Outcome outcome = runtime.run(agent("boss"), "ask helper", Home.global(), budget, null);

        assertEquals(Ending.CALL_BUDGET, outcome.ending());
        assertEquals(3, transport.calls().size(), "the parent made a call it could not afford");
        assertEquals(3, budget.spent());
        assertEquals(0, budget.remaining());
        // The parent's own numbers, which are not the tree's.
        assertEquals(1, outcome.modelCalls());
        assertEquals(1, outcome.steps());
        assertTrue(outcome.text().contains("whole budget of 3 model calls"), outcome.text());
        assertTrue(outcome.text().contains(AgentRegistry.AGENT_RUN), outcome.text());
    }

    /**
     * <b>A child that was stopped for going nowhere is its parent's reading and
     * not its parent's death.</b>
     *
     * <p>{@code AgentRunTool.propagates} lists the endings that kill a parent,
     * and they are all conditions of the <em>tree</em>: a dead endpoint, a
     * failure further down, a client that went away. A child that repeated one
     * call is a condition of one agent — it reached no answer, which is what a
     * capped or out-of-budget child also means — so the parent is told in a tool
     * result and decides what to do about it.
     *
     * <p><b>A list of enum constants is not a compile check</b>, which is the
     * sentence that method's javadoc already carries about a set that grows.
     * This is the instrument for the constant that grew it: with {@code STUCK}
     * added to {@code propagates}, the parent ends {@code SUB_AGENT_FAILED} and
     * this fails.
     *
     * <p>{@code dogged} exists for this and says so: every other leaf fixture
     * stops at its own cap before a repeated call could be counted far enough,
     * so a stuck child is not reachable over any of them.
     */
    @Test
    void a_child_that_kept_repeating_itself_is_reported_and_not_propagated() throws Exception {
        // One queued step -- the delegation -- and then the same call for ever,
        // for the child and afterwards for the parent. The steps are consumed
        // by whichever run asks next, so a queued answer here would be taken by
        // the child on its first call and there would be nothing to be stuck
        // about.
        Scripted transport = new Scripted()
                .then(() -> delegates("dogged", "find the codename"))
                .thenAlways(() -> asking("again", call("c9", "probe_read", "{\"p\":\"a\"}")));
        JobRuntime runtime = delegating(transport, Probe.returning("probe_read", "nothing here"));

        Outcome outcome = runtime.run(agent("foreman"), "ask dogged", Home.global(),
                generous(), null);

        assertEquals(Ending.TURN_CAP, outcome.ending(),
                "the parent went on until its own cap rather than dying with its child");
        // Scanned rather than indexed: parent and child share this transport, so
        // the request that carries the child's outcome sits after however many
        // calls the child made, and pinning that position would be pinning the
        // futility threshold from the outside.
        StringBuilder everything = new StringBuilder();
        for (int at = 0; at < transport.calls().size(); at++) {
            everything.append(toolResults(transport, at));
        }
        String reported = everything.toString();
        assertTrue(reported.contains("the agent 'dogged' did not reach an answer"), reported);
        assertTrue(reported.contains("same arguments"), reported);
    }

    /**
     * A child that kept writing its calls as text is its parent's reading, not its death: for
     * STUCK's reason, CALL_FAILURES is a condition of one agent and is not in {@code
     * AgentRunTool.propagates}. {@code dogged} has twelve steps, so its fourth written call in a
     * row finds the allowance empty and ends it CALL_FAILURES -- a leaf with fewer, like {@code
     * helper}'s three, would reach its last step first and end TURN_CAP; the parent is not offered
     * probe_read, so the same text from it is an ordinary answer.
     */
    @Test
    void a_child_that_kept_writing_calls_as_text_is_reported_and_not_propagated()
            throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("dogged", "find the codename"))
                .thenAlways(() -> answer("probe_read({\"id\": \"mem_1\"})"));
        JobRuntime runtime = delegating(transport, Probe.returning("probe_read", "a memory"));

        Outcome outcome = runtime.run(agent("foreman"), "ask dogged", Home.global(),
                generous(), null);

        assertEquals(Ending.ANSWERED, outcome.ending(), "the parent went on");
        StringBuilder everything = new StringBuilder();
        for (int at = 0; at < transport.calls().size(); at++) {
            everything.append(toolResults(transport, at));
        }
        String reported = everything.toString();
        assertTrue(reported.contains("the agent 'dogged' did not reach an answer"), reported);
        assertTrue(reported.contains("kept writing tool calls as text"), reported);
        assertFalse(AgentRunTool.propagates(Ending.CALL_FAILURES));
    }

    /**
     * A child runs against the budget its parent was given, and its own {@code
     * max-model-calls} is not consulted.
     *
     * <p>{@code frugal.md} names one model call and takes two. Under a {@code
     * min} of the two numbers it would stop at its own limit and never answer;
     * under the shared budget alone it answers. The second is what this runtime
     * does, and {@link Budget}'s javadoc is where the reasoning lives: a
     * definition's own number is what a job started on its own behalf is given,
     * and re-reading it down the tree is the per-agent copy the shared object
     * exists to prevent. What still bounds a runaway child is its own {@code
     * max-turns}, which the loop does read every turn.
     */
    @Test
    void a_child_is_governed_by_the_shared_budget_and_not_by_its_own_limit() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("frugal", "go"))
                .then(() -> asking("looking", call("c9", "probe_read", "{}")))
                .then(() -> answer("frugal answer"))
                .then(() -> answer("parent answer"));
        Budget budget = generous();
        JobRuntime runtime = delegating(transport, Probe.returning("probe_read", "a memory"));

        Outcome outcome = runtime.run(agent("boss"), "ask frugal", Home.global(), budget, null);

        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals(4, budget.spent(), "the child was capped by its own max-model-calls");
        assertEquals("the agent 'frugal' answered:\nfrugal answer", toolResults(transport, 3));
    }

    // --- a child that fails ------------------------------------------------------

    /**
     * A child that could not reach what it depends on ends the parent too, and
     * this is where {@link Ending#SUB_AGENT_FAILED} earns its place.
     *
     * <p>The rule is {@code JobRuntime.dependencyFailure}'s, one level up: an
     * ending that means <em>something this run depends on could not be
     * reached</em> is not a mistake the model made and not one it can correct by
     * calling again. Rendering it as an ordinary tool result would leave the
     * parent to answer around a dead endpoint out of its own head — the
     * confident-empty-answer shape — and to spend the rest of the tree's budget
     * discovering the endpoint is still dead.
     */
    @Test
    void a_child_that_fails_is_reported_and_does_not_kill_the_parent_silently() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .thenAlways(() -> {
                    throw new LlmException("pool 'scripted' is shut down and cannot take work");
                });
        JobRuntime runtime = delegating(transport);

        Outcome outcome = runtime.run(agent("boss"), "ask helper", Home.global(), generous(), null);

        assertEquals(Ending.SUB_AGENT_FAILED, outcome.ending());
        assertTrue(outcome.text().contains("helper"), outcome.text());
        // The endpoint arm of the sentence, in as many words. It became an arm
        // when SESSION_GONE landed, and without this line a build whose two arms
        // said the same thing would tell an operator whose endpoint is down that
        // somebody closed a laptop, with the session test still green.
        assertTrue(outcome.text().contains("something it depends on could not be reached"),
                outcome.text());
        // The child's detail, carried through rather than summarised away.
        assertTrue(outcome.detail().contains("helper"), outcome.detail());
        assertTrue(outcome.detail().contains("LlmException"), outcome.detail());
        assertTrue(outcome.detail().contains("shut down"), outcome.detail());
        // The turn the child failed in never completed: its tool results were
        // never appended. The model call the parent made is still counted.
        assertEquals(0, outcome.steps());
        assertEquals(1, outcome.modelCalls());
        // And the trail says how far it got.
        assertTrue(outcome.text().contains(AgentRegistry.AGENT_RUN), outcome.text());
    }

    /** Two levels down is the same rule applied twice, and the detail chains so
     *  an operator can see which agent actually hit the dead endpoint. */
    @Test
    void a_failure_two_levels_down_reaches_the_top() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("middle", "pass it on"))
                .then(() -> asking("handing it on", call("c2", AgentRegistry.AGENT_RUN,
                        delegate("helper", "go"))))
                .thenAlways(() -> {
                    throw new LlmException("the endpoint refused the connection");
                });
        JobRuntime runtime = delegating(transport);

        Outcome outcome = runtime.run(agent("boss"), "ask middle", Home.global(), generous(), null);

        assertEquals(Ending.SUB_AGENT_FAILED, outcome.ending());
        assertTrue(outcome.detail().startsWith("middle: helper: "), outcome.detail());
        assertTrue(outcome.detail().contains("refused the connection"), outcome.detail());
    }

    /**
     * A child whose client session went away kills the parent too, and says
     * which of the two failures it was.
     *
     * <p>{@code AgentRunTool.propagates} is a list of endings and not a compile
     * check, so {@link Ending#SESSION_GONE} arriving without being added to it
     * would have been <b>silence</b>: the parent would render "the child did not
     * reach an answer" as an ordinary tool result and carry on calling file
     * tools down the same dead channel — the session it shares with the child —
     * once per turn until its budget ran out. Measured: with the constant left
     * out of that list, this test ends {@code ANSWERED} and every other test in
     * this file stays green.
     *
     * <p>The sentence is asserted as well as the ending because the parent's
     * ending is {@code SUB_AGENT_FAILED} either way. That is the shipped rule —
     * the thing to look at is one agent further down, so the parent names the
     * child rather than adopting its ending — and it means the only place the
     * distinction can survive to the top is the words.
     */
    @Test
    void a_child_whose_session_went_away_says_so_rather_than_naming_an_endpoint()
            throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .then(() -> asking("looking", call("c2", "probe_read", "{}")))
                .thenAlways(() -> answer("this must never be reached"));
        JobRuntime runtime = delegating(transport, new Probe("probe_read", args -> {
            throw new SessionGoneException("the session 'laptop' closed while this was waiting,"
                    + " so the files it owned cannot be reached");
        }));

        Outcome outcome = runtime.run(agent("boss"), "ask helper", Home.global(), generous(), null);

        assertEquals(Ending.SUB_AGENT_FAILED, outcome.ending());
        assertTrue(outcome.detail().contains("SessionGoneException"), outcome.detail());
        // Asserted for what the sentence must SAY, not against the spelling of
        // the one it replaces: an assertFalse naming the old wording verbatim
        // passes any mutant that restores half of it.
        assertTrue(outcome.text().contains("session"), outcome.text());
        assertTrue(outcome.text().contains("went away"), outcome.text());
    }

    /**
     * Two levels down, a session that went away reaches the top in the
     * <b>detail</b> and not in the sentence — and that is a limit of the
     * sentence rather than a bug in it.
     *
     * <p>{@code SubAgentFailed.sentence()} keys on the child's ending, and at
     * depth two the child is the <em>middle</em> agent, whose own ending is
     * {@code SUB_AGENT_FAILED}. So the else arm fires and the top-level sentence
     * says "something it depends on could not be reached" about a closed laptop.
     * That paragraph claimed the words carried the difference "to the top"; they
     * carry it one level. <b>The claim was wrong and the behaviour is not</b>:
     * the detail chains, so an operator reads {@code middle: helper:
     * SessionGoneException: …} one line down — exactly what they get for a dead
     * endpoint at the same depth, which is the shape this class shipped with.
     *
     * <p>Untested until now, and the gap was structural rather than accidental:
     * {@code a_failure_two_levels_down_reaches_the_top} uses an {@code
     * LlmException}, so no test in this file had ever driven a session-gone
     * failure through a middle agent.
     */
    @Test
    void a_session_two_levels_down_reaches_the_top_in_its_detail_and_not_its_sentence()
            throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("middle", "pass it on"))
                .then(() -> asking("handing it on", call("c2", AgentRegistry.AGENT_RUN,
                        delegate("helper", "go"))))
                .then(() -> asking("looking", call("c3", "probe_read", "{}")))
                .thenAlways(() -> answer("this must never be reached"));
        JobRuntime runtime = delegating(transport, new Probe("probe_read", args -> {
            throw new SessionGoneException("the session 'laptop' closed while this was waiting,"
                    + " so the files it owned cannot be reached");
        }));

        Outcome outcome = runtime.run(agent("boss"), "ask middle", Home.global(), generous(), null);

        assertEquals(Ending.SUB_AGENT_FAILED, outcome.ending());
        // The chain is what carries it, and it names the agent that actually met
        // the closed socket rather than the one that reported it.
        assertTrue(outcome.detail().startsWith("middle: helper: "), outcome.detail());
        assertTrue(outcome.detail().contains("SessionGoneException"), outcome.detail());
        // And the sentence is the endpoint one, asserted rather than tolerated:
        // a later change that DID carry the distinction further would fail here
        // and should, because it would mean this comment is out of date.
        assertTrue(outcome.text().contains("something it depends on could not be reached"),
                "at depth two the middle's own ending is SUB_AGENT_FAILED, so the ternary"
                        + " cannot see a session: " + outcome.text());
    }

    /** A child out of the tree's budget is not a failure of anything the tree
     *  depends on — it is the tree's own limit, and the parent meets it at its
     *  next boundary without a further call. */
    @Test
    void a_child_that_ran_out_of_the_shared_budget_does_not_end_as_sub_agent_failed()
            throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .then(() -> asking("looking", call("c9", "probe_read", "{}")))
                .thenAlways(() -> answer("this must never be reached"));
        Budget budget = Budget.of(2);
        JobRuntime runtime = delegating(transport, Probe.returning("probe_read", "a memory"));

        Outcome outcome = runtime.run(agent("boss"), "ask helper", Home.global(), budget, null);

        assertNotEquals(Ending.SUB_AGENT_FAILED, outcome.ending());
        assertEquals(Ending.CALL_BUDGET, outcome.ending());
        assertEquals(2, transport.calls().size());
    }

    // --- cancellation ------------------------------------------------------------

    /**
     * A cancelled parent stops the child it is blocked on.
     *
     * <p><b>The decision, and why.</b> The convenience {@code run} — five
     * arguments since the session joined them — passes {@code () -> false}, so
     * a child could have been given no way to hear about cancellation. It is threaded through instead, because a parent blocked
     * inside {@code agent_run} cannot reach its own boundary check to act on the
     * flag — the child is the only place in the tree that is asked. A child left
     * deaf would run its remaining turns against a budget shared with a run that
     * has already been abandoned, and {@code JobStore.close} would ask every job
     * to stop and be obeyed by none of the blocked ones.
     *
     * <p>What makes this test see the difference is the call count, not the
     * ending: the parent ends {@code CANCELLED} either way, at its own boundary,
     * once the child returns. Deaf, the child would take its second turn and
     * answer.
     */
    @Test
    void cancelling_a_parent_stops_the_child_it_is_blocked_on() throws Exception {
        CountDownLatch inTool = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .then(() -> asking("looking", call("c9", "probe_read", "{}")))
                .thenAlways(() -> answer("the child would have said this"));
        Probe read = Probe.returning("probe_read", "a memory").blockingOn(inTool, release);
        JobRuntime runtime = delegating(transport, read);

        try (JobStore store = new JobStore(runtime)) {
            String id = store.submit(agent("boss"), "ask helper", Home.global(), null);
            assertTrue(inTool.await(10, TimeUnit.SECONDS), "the child never reached its tool");
            assertTrue(store.cancel(id));
            release.countDown();

            Outcome outcome = awaitOutcome(store, id);
            assertEquals(Ending.CANCELLED, outcome.ending());
            assertEquals(2, transport.calls().size(),
                    "the child kept going after its tree was cancelled");
        }
    }

    // --- threads and lanes -------------------------------------------------------

    /** A child runs on its parent's own thread. That is what makes blocking on
     *  it free, and it is the premise the lane test below rests on. */
    @Test
    void a_child_runs_on_the_parent_s_own_thread() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .then(() -> asking("looking", call("c9", "probe_read", "{}")))
                .thenAlways(() -> answer("done"));
        Probe read = Probe.returning("probe_read", "a memory");
        JobRuntime runtime = delegating(transport, read);

        try (JobStore store = new JobStore(runtime)) {
            String id = store.submit(agent("boss"), "ask helper", Home.global(), null);
            assertEquals(Ending.ANSWERED, awaitOutcome(store, id).ending());
            assertEquals(1, read.threads().size());
            Thread inChild = read.threads().get(0);
            assertTrue(inChild.isVirtual(), "the child ran on a platform thread");
            assertNotEquals(Thread.currentThread(), inChild);
        }
    }

    /**
     * The whole argument for virtual threads: a parent blocked on a child holds
     * no lane slot.
     *
     * <p>One slot in the chat lane. The parent is blocked inside {@code
     * agent_run}; the child is blocked inside its tool; neither is inside the
     * transport, so the slot is free and a third party's request goes straight
     * through. If a blocked parent held its slot, that request would wait out
     * the pool's submit budget and come back as {@link LlmSaturatedException} —
     * which is not a hypothetical: {@code
     * a_held_lane_slot_does_shed_a_third_party_request} below is the control
     * that fires it, so this test's instrument is known to be able to fail.
     *
     * <p><b>The one slot is load-bearing here</b>, unlike in {@code
     * JobRuntimeTest.nothing_bounds_how_many_jobs_are_blocked_at_once} where the
     * slot count only affected timing. That distinction is worth keeping
     * straight: the same javadoc in that file was wrong about it once.
     */
    @Test
    void a_parent_blocked_on_a_child_holds_no_lane_slot() throws Exception {
        CountDownLatch inTool = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .then(() -> asking("looking", call("c9", "probe_read", "{}")))
                .thenAlways(() -> answer("done"));
        Probe read = Probe.returning("probe_read", "a memory").blockingOn(inTool, release);
        AgentRegistry registry = registry();
        LlmDispatcher dispatcher = dispatcherOver(transport, 1);
        JobRuntime runtime = new JobRuntime(dispatcher, List.of(read), () -> registry);

        try (JobStore store = new JobStore(runtime)) {
            String id = store.submit(agent("boss"), "ask helper", Home.global(), null);
            assertTrue(inTool.await(10, TimeUnit.SECONDS), "the child never reached its tool");

            // The tree is blocked and not in the transport, so this call is the
            // next one the script hands out — index 2 — deterministically.
            Completion third = dispatcher.complete(ChatRequest.of("fast",
                    List.of(ChatMessage.user("an unrelated request"))));
            assertEquals("done", third.content());

            release.countDown();
            assertEquals(Ending.ANSWERED, awaitOutcome(store, id).ending());
        }
    }

    /**
     * The control for the test above: when a slot really is held, a third
     * party's request is shed.
     *
     * <p>Without this, {@code a_parent_blocked_on_a_child_holds_no_lane_slot}
     * would pass just as happily against an instrument that cannot fail — a test
     * helper blind in the dimension its own argument is about. Here the block is
     * inside the transport, which is where a slot is genuinely occupied.
     */
    @Test
    void a_held_lane_slot_does_shed_a_third_party_request() {
        CountDownLatch inTransport = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Scripted transport = new Scripted()
                .thenAlways(() -> answer("done"))
                .blockingOn(0, inTransport, release);
        LlmDispatcher dispatcher = dispatcherOver(transport, 1);

        Thread holder = Thread.ofVirtual().start(() -> dispatcher.complete(
                ChatRequest.of("fast", List.of(ChatMessage.user("the one holding the slot")))));
        try {
            assertTrue(inTransport.await(10, TimeUnit.SECONDS),
                    "the holder never reached the transport");
            assertThrows(LlmSaturatedException.class, () -> dispatcher.complete(
                    ChatRequest.of("fast", List.of(ChatMessage.user("an unrelated request")))
                            .withBudget(Duration.ofMillis(200))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the holder", e);
        } finally {
            release.countDown();
            join(holder);
        }
    }

    // --- a batch of sub-agents ---------------------------------------------------

    /**
     * Sub-agents in one batch run one at a time, and every one of them still
     * runs.
     *
     * <p><b>No lock does this, and that is the finding worth recording.</b> The
     * plan called for one held across the {@code agent_run} dispatches of a
     * turn; {@code JobRuntime}'s batch loop already runs every call in the array
     * in order on one thread, so a lock would serialise nothing that is not
     * already serialised. A lock wide enough to matter would have to span
     * <em>jobs</em>, and that is a different policy and a worse one: a parent
     * blocked on a child would then hold it, and unrelated trees would queue
     * behind a run that is doing nothing but waiting — which is the resource
     * this design spent virtual threads to stop making scarce.
     *
     * <p>So this test pins the property at the seam it matters at rather than
     * the mechanism that provides it. If the batch loop is ever made concurrent,
     * this fails, and the question of a lock becomes live again.
     */
    @Test
    void two_agent_run_calls_in_one_batch_run_one_at_a_time() throws Exception {
        // Conversation-driven and not index-driven, and that is the second half
        // of making this test able to fail. Under a concurrent batch loop the
        // two children interleave, so a script consumed by index hands one of
        // them the *other's* step — the child gets an answer where it should
        // have got a tool call, never reaches the probe at all, and a peak of
        // one is recorded for a run in which nothing was serialised. Measured:
        // with the index-driven script the concurrent mutant survived 2 runs in
        // 4, and the peak assertion fired in none of them.
        Scripted transport = new Scripted().thenAlwaysFor(messages -> {
            boolean afterATool = messages.get(messages.size() - 1).role() == ChatMessage.Role.TOOL;
            if (messages.get(0).content().startsWith("You hand work")) {
                return afterATool
                        ? answer("parent answer")
                        : asking("both at once",
                                call("c1", AgentRegistry.AGENT_RUN, delegate("helper", "first")),
                                call("c2", AgentRegistry.AGENT_RUN, delegate("frugal", "second")));
            }
            // A child: its own task is the user message it opened with, so each
            // answers for itself however the two are interleaved.
            return afterATool
                    ? answer("the " + messages.get(1).content() + " child")
                    : asking("looking", call("c9", "probe_read", "{}"));
        });
        // Held at a two-way rendezvous, which is what makes the peak below mean
        // anything: see Probe.holdingFor, and
        // the_probe_can_see_two_agents_in_a_tool_at_once for the control that
        // proves this instrument can record a two.
        Probe read = Probe.returning("probe_read", "a memory").holdingFor(2, 1_000);
        JobRuntime runtime = delegating(transport, read);

        Outcome outcome = runtime.run(agent("boss"), "ask both", Home.global(), generous(), null);

        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals(1, read.peak(), "two sub-agents were inside a tool at once");
        // Every call in the batch ran and every result came back. Serialisation
        // is a resource policy, not a limit on the contract. Gathered across
        // every request rather than from a fixed index, for the reason above:
        // an index is not stable under the concurrency this test exists to
        // detect, and a content assertion that fails for THAT reason would be
        // standing in for the peak assertion that should have failed.
        String results = allToolResults(transport);
        assertTrue(results.contains("the agent 'helper' answered:\nthe first child"), results);
        assertTrue(results.contains("the agent 'frugal' answered:\nthe second child"), results);
    }

    /**
     * The control for the test above: this probe can record a two.
     *
     * <p><b>Written because the first version of the batch test could not fail.</b>
     * A reviewer replaced the runtime's sequential batch loop with one
     * dispatching every call to a virtual thread and joining in order — no
     * serialisation at all — and the suite passed three runs in four, with the
     * peak assertion firing in none of them. The probe returned too fast to
     * catch an overlap that really was there, which is the blind-instrument trap
     * in the one place the batch test reasons <em>from</em> its instrument.
     *
     * <p>So: two whole job trees, deliberately concurrent, through the same
     * probe at the same rendezvous the batch test uses. A peak of two here and a
     * peak of one there is the pair that carries the claim. The transport is
     * conversation-driven rather than index-driven, because two trees running at
     * once make a call index say nothing about whose turn it is holding.
     */
    @Test
    void the_probe_can_see_two_agents_in_a_tool_at_once() throws Exception {
        Scripted transport = new Scripted().thenAlwaysFor(messages -> {
            boolean afterATool = messages.get(messages.size() - 1).role() == ChatMessage.Role.TOOL;
            boolean isTheParent = messages.get(0).content().startsWith("You hand work");
            if (afterATool) {
                return answer("done");
            }
            return isTheParent
                    ? delegates("helper", "go")
                    : asking("looking", call("c9", "probe_read", "{}"));
        });
        Probe read = Probe.returning("probe_read", "a memory").holdingFor(2, 10_000);
        JobRuntime runtime = delegating(transport, read);

        try (JobStore store = new JobStore(runtime)) {
            String one = store.submit(agent("boss"), "ask helper", Home.global(), null);
            String two = store.submit(agent("boss"), "ask helper", Home.global(), null);
            assertEquals(Ending.ANSWERED, awaitOutcome(store, one).ending());
            assertEquals(Ending.ANSWERED, awaitOutcome(store, two).ending());
        }
        assertEquals(2, read.peak(),
                "the probe cannot record two callers at once, so a peak of one proves nothing");
    }

    // --- wiring ------------------------------------------------------------------

    /**
     * {@code agent_run} is a known tool exactly when delegation is wired.
     *
     * <p>This replaces the hand-written line {@code JobRuntime.knownTools}
     * carried until now, which existed so {@code curator.md} would load before
     * there was a tool behind the name. The set is derived again: too broad is
     * the silent direction, since every name in it is one the registry's
     * unknown-tool refusal waves through.
     */
    @Test
    void agent_run_is_a_known_tool_only_when_delegation_is_wired() throws Exception {
        AgentRegistry registry = registry();
        LlmDispatcher dispatcher = dispatcherOver(new Scripted(), 1);
        List<AgentTool> tools = List.of(Probe.returning("probe_read", "a"));

        // result_read is in both, because it is gated by nothing: every run has
        // a transcript by signature, so unlike agent_run there is no wiring that
        // can withhold it. See JobRuntime.knownTools.
        assertEquals(Set.of("probe_read", ResultTools.READ_NAME, ResultTools.LIST_NAME),
                new JobRuntime(dispatcher, tools).knownTools());
        assertEquals(Set.of("probe_read", ResultTools.READ_NAME, ResultTools.LIST_NAME,
                        AgentRegistry.AGENT_RUN),
                new JobRuntime(dispatcher, tools, () -> registry).knownTools());
    }

    /** A tool registered under {@code agent_run} would be shadowed by
     *  delegation and never reachable — the same fault, and the same refusal,
     *  as two tools sharing a name. */
    @Test
    void a_registered_tool_named_agent_run_is_refused_when_delegation_is_wired() throws Exception {
        AgentRegistry registry = registry();
        LlmDispatcher dispatcher = dispatcherOver(new Scripted(), 1);
        List<AgentTool> tools = List.of(Probe.returning(AgentRegistry.AGENT_RUN, "a"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new JobRuntime(dispatcher, tools, () -> registry));
        assertTrue(e.getMessage().contains(AgentRegistry.AGENT_RUN), e.getMessage());
        // And without delegation there is nothing to clash with.
        assertEquals(Set.of(AgentRegistry.AGENT_RUN, ResultTools.READ_NAME,
                        ResultTools.LIST_NAME),
                new JobRuntime(dispatcher, tools).knownTools());
    }

    /** Delegation is a capability a definition grants itself by declaring the
     *  tool. An agent that does not gets the same answer as for a tool that does
     *  not exist, and cannot reach another agent by naming one. */
    @Test
    void an_agent_that_does_not_declare_agent_run_is_not_offered_it() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport, Probe.returning("probe_read", "a memory"));

        Outcome outcome = runtime.run(agent("reader"), "go", Home.global(), generous(), null);

        assertEquals(Ending.ANSWERED, outcome.ending());
        String result = toolResults(transport, 1);
        assertTrue(result.contains("there is no tool called '" + AgentRegistry.AGENT_RUN + "'"),
                result);
        // Two calls: no child ran.
        assertEquals(2, transport.calls().size());
    }

    /**
     * Delegation wired to a supplier that has nothing in it yet.
     *
     * <p>The supplier exists because the registry validates against {@code
     * knownTools()} and the runtime needs the registry to delegate: one of the
     * two has to be resolved late, and it is this one, because the registry's
     * boot check is what makes the graph safe and has to run first. A wiring
     * that never fills it in is a bug, and it says so rather than arriving as a
     * {@code NullPointerException} several frames down.
     */
    @Test
    void delegation_that_was_never_wired_is_reported_when_it_is_first_needed() throws Exception {
        JobRuntime runtime = new JobRuntime(dispatcherOver(new Scripted(), 1), List.of(),
                () -> null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> runtime.run(agent("boss"), "go", Home.global(), generous(), null));
        assertTrue(e.getMessage().contains(AgentRegistry.AGENT_RUN), e.getMessage());
    }

    /**
     * A runtime built without a graph to delegate over does not offer {@code
     * agent_run} to an agent that declares it.
     *
     * <p>The other arm of the branch {@code
     * agent_run_is_a_known_tool_only_when_delegation_is_wired} reads from the
     * registry's side. Delegation is a capability of the boot as well as of the
     * definition: a definition can ask, and a runtime with nothing to delegate
     * to answers with the tools it does have.
     */
    @Test
    void a_runtime_that_does_not_delegate_offers_no_agent_run() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = new JobRuntime(dispatcherOver(transport, 4),
                List.of(Probe.returning("probe_read", "a memory")));

        Outcome outcome = runtime.run(agent("boss"), "go", Home.global(), generous(), null);

        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals(List.of(), transport.calls().get(0).stream()
                .filter(m -> m.role() == ChatMessage.Role.TOOL).toList());
        assertTrue(toolResults(transport, 1)
                        .contains("there is no tool called '" + AgentRegistry.AGENT_RUN + "'"),
                toolResults(transport, 1));
        assertEquals(2, transport.calls().size());
    }

    /** Each of the seven things a bound tool cannot do without, named. A null
     *  here is the runtime's own bug, and a bare {@code NullPointerException}
     *  out of whichever line touched it first would leave whoever is wiring
     *  guessing which of the seven it was. (It read five until {@code images}
     *  joined and six until {@code store} did; an empty list and {@code
     *  ImageStore.NONE} are the ordinary values and a null is neither.) */
    @Test
    void the_bound_tool_names_what_it_was_built_without() throws Exception {
        AgentRegistry registry = registry();
        JobRuntime runtime = delegating(new Scripted());
        AgentDefinition boss = agent("boss");
        Budget budget = generous();
        BooleanSupplier never = () -> false;
        List<Content.Image> nothing = List.of();
        ImageStore none = ImageStore.NONE;

        assertEquals("agents", assertThrows(NullPointerException.class,
                () -> new AgentRunTool(null, runtime, boss, budget, never, null, Transcript.NONE, nothing, none)).getMessage());
        assertEquals("runtime", assertThrows(NullPointerException.class,
                () -> new AgentRunTool(registry, null, boss, budget, never, null, Transcript.NONE, nothing, none)).getMessage());
        assertEquals("caller", assertThrows(NullPointerException.class,
                () -> new AgentRunTool(registry, runtime, null, budget, never, null, Transcript.NONE, nothing, none)).getMessage());
        assertEquals("budget", assertThrows(NullPointerException.class,
                () -> new AgentRunTool(registry, runtime, boss, null, never, null, Transcript.NONE, nothing, none)).getMessage());
        assertEquals("cancelled", assertThrows(NullPointerException.class,
                () -> new AgentRunTool(registry, runtime, boss, budget, null, null, Transcript.NONE, nothing, none)).getMessage());
        assertEquals("images", assertThrows(NullPointerException.class,
                () -> new AgentRunTool(registry, runtime, boss, budget, never, null, Transcript.NONE, null, none)).getMessage());
        assertEquals("store", assertThrows(NullPointerException.class,
                () -> new AgentRunTool(registry, runtime, boss, budget, never, null, Transcript.NONE, nothing, null)).getMessage());
    }

    /**
     * {@code knownTools()} does not resolve the supplier — which is the entire
     * reason the supplier exists.
     *
     * <p>The other half of {@code
     * delegation_that_was_never_wired_is_reported_when_it_is_first_needed}.
     * That one pins "left unresolved forever"; this one pins "resolved too
     * early", and it is the half with teeth: at boot the registry does not
     * exist yet, because it is built <em>from</em> this set. A change that
     * resolved it here would boot-fail in Task 10's wiring, several tasks from
     * now, with nothing in this slice catching it — measured: moving {@code
     * requireWired()} into {@code knownTools()} survives the whole suite
     * without this test.
     */
    @Test
    void knownTools_does_not_resolve_the_agent_graph() {
        JobRuntime runtime = new JobRuntime(dispatcherOver(new Scripted(), 1),
                List.of(Probe.returning("probe_read", "a")),
                () -> {
                    throw new AssertionError("knownTools() resolved the agent registry, which at"
                            + " boot is built from the set knownTools() returns");
                });

        assertEquals(Set.of("probe_read", AgentRegistry.AGENT_RUN, ResultTools.READ_NAME,
                        ResultTools.LIST_NAME),
                runtime.knownTools());
    }

    /**
     * The schema the model is shown names the agents this caller may reach and
     * says what each one is for, so the run-time refusal is a backstop and not
     * the first anyone hears of the list.
     *
     * <p>Sorted, so the sentence is the same every time and a model cannot read
     * a preference into it — {@code JobRuntime.noSuchTool}'s reasoning for the
     * tool list. <b>{@code boss.md} declares its three callees in the order
     * {@code middle, helper, frugal} on purpose</b>, the way {@code
     * contrarian.md} does for tools: with an already-alphabetical fixture this
     * assertion, and the two refusal assertions above, pass just as happily
     * against a runtime that does not sort at all. Measured: reordering the
     * fixture is what turned that mutant from a survivor into a kill.
     *
     * <p><b>Each line is asserted against the callee's own file</b>, not merely
     * for the name. A description built from names alone hands a delegating
     * model an identifier and nothing to choose on; the three fixtures say
     * different things about themselves, so a rendering that dropped the
     * descriptions, or attached one agent's words to another's name, fails here.
     */
    @Test
    void the_schema_names_the_agents_the_caller_may_call_and_what_each_is_for() throws Exception {
        Scripted transport = new Scripted().then(() -> answer("done"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("boss"), "go", Home.global(), generous(), null);

        AgentTool tool = new AgentRunTool(registry(), runtime, agent("boss"), generous(),
                () -> false, null, Transcript.NONE, List.of(), ImageStore.NONE);
        assertEquals(AgentRegistry.AGENT_RUN, tool.schema().name());
        String description = tool.schema().description();
        assertTrue(description.contains(
                "\n- frugal: " + agent("frugal").description()), description);
        assertTrue(description.contains(
                "\n- helper: " + agent("helper").description()), description);
        assertTrue(description.contains(
                "\n- middle: " + agent("middle").description()), description);
        assertTrue(description.indexOf("- frugal") < description.indexOf("- helper")
                        && description.indexOf("- helper") < description.indexOf("- middle"),
                description);
        Object properties = tool.schema().parameters().get("properties");
        assertTrue(properties instanceof Map<?, ?> map
                        && map.keySet().equals(Set.of("agent", "task", "images")),
                String.valueOf(properties));
        // `images` is offered and not required, and it is offered to a caller
        // holding no picture at all: a schema that appeared and disappeared with
        // an attachment would be a tool no model could learn, and
        // JobRuntime.schemasOfferedTo -- which prices the block -- holds none.
        assertEquals(List.of("agent", "task"), tool.schema().parameters().get("required"));
    }

    // --- a picture handed on ------------------------------------------------------

    /** A well-formed id nothing in these fixtures is ever shown. */
    private static final String UNSEEN = "img_" + "f".repeat(32);

    private static Content.Image picture(char fill) {
        return new Content.Image("img_" + String.valueOf(fill).repeat(32),
                "data:image/png;base64,iVBORw0K");
    }

    /** {@code agent_run} arguments naming pictures, as the model would send
     *  them. */
    private static String delegate(String agent, String task, String... images) {
        return "{\"agent\": \"" + agent + "\", \"task\": \"" + task + "\", \"images\": [\""
                + String.join("\", \"", images) + "\"]}";
    }

    /** The first image id anywhere in a string, or null. What a model has to be
     *  able to do to use this argument at all. */
    private static String firstIdIn(String said) {
        java.util.regex.Matcher found =
                java.util.regex.Pattern.compile("img_[0-9a-f]{32}").matcher(said);
        return found.find() ? found.group() : null;
    }

    /**
     * <b>A caller can hand on a picture it was shown, and the id is the only
     * thing that lets it.</b>
     *
     * <p>The parent here is not given the id by this test. It reads it off its
     * own opening message, the way a model would — {@link #firstIdIn} over the
     * message the transport was handed — and delegates with what it found. So
     * this fails, with an outcome saying so in as many words, if {@code
     * JobRuntime} stops naming ids on the utterance: a run that is shown a
     * picture and never told what it is called has nothing to pass.
     *
     * <p>It is the whole path in one test on purpose. The parts of it that could
     * be asserted separately — that the child is shown the bytes, that the result
     * names the id — have their own tests below; what only this one can say is
     * that the two ends meet, that the id a run is <em>told</em> is an id {@code
     * agent_run} will <em>take</em>.
     */
    @Test
    void a_caller_can_hand_on_a_picture_it_was_shown() throws Exception {
        Content.Image red = picture('a');
        // Directed by WHOSE run this is, not by the call index: both runs are
        // shown the picture, so `carriesAnImage` cannot tell them apart, and the
        // parent's second turn is a third call this fake has to answer as the
        // parent. The system message is the agent's own prompt.
        Scripted transport = new Scripted().thenAlwaysFor(messages -> {
            if (messages.get(0).content().startsWith("You say what is in the picture")) {
                return answer("a red square");
            }
            boolean heardBack = messages.stream()
                    .anyMatch(message -> message.role() == ChatMessage.Role.TOOL);
            if (heardBack) {
                return answer("looker says a red square");
            }
            String id = firstIdIn(messages.get(messages.size() - 1).content());
            return id == null
                    ? answer("I was never told what the picture I am looking at is called")
                    : asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                            delegate("looker", "what is this", id)));
        });
        JobRuntime runtime = delegating(transport);

        Outcome outcome = runtime.run(agent("viewer"), "what am I looking at", Home.global(),
                generous(), () -> false, null, JobWatch.UNWATCHED, Transcript.NONE,
                TurnCap.none(), List.of(red));

        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals("looker says a red square", outcome.text(), outcome.text());
        assertTrue(allToolResults(transport).contains("a red square"),
                allToolResults(transport));
    }

    /**
     * The child is shown the bytes, on its own utterance, exactly as a submitter
     * would have shown it them.
     *
     * <p><b>The same {@code Content.Image} object, not a copy assembled from an
     * id.</b> That is the containment property stated as an assertion: this tool
     * resolves an id by looking it up among the parts its own run is carrying, so
     * there is no path from the string a model typed to a byte on disk. A
     * delegation that reached a store to answer this would pass a value-equal
     * copy and would be a second resolver in a server that has exactly one.
     */
    @Test
    void a_child_handed_a_picture_is_shown_it_on_its_own_utterance() throws Exception {
        Content.Image red = picture('a');
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        delegate("looker", "what is this", red.uid()))))
                .then(() -> answer("a red square"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("viewer"), "what am I looking at", Home.global(), generous(),
                () -> false, null, JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(),
                List.of(red));

        List<ChatMessage> child = transport.calls().get(1);
        ChatMessage utterance = child.get(child.size() - 1);
        assertTrue(utterance.parts().contains(red),
                "the child was handed an id and not the picture behind it: " + utterance.parts());
        assertTrue(utterance.content().startsWith("what is this"),
                "the task its parent wrote is still what the child is asked");
    }

    /**
     * <b>The result names the picture the answer is about.</b>
     *
     * <p>Every other delegation result is self-describing: the task is in the
     * tool call the model can read back. A picture is not, so an answer about one
     * is a sentence with no recoverable subject — for the calling model on its
     * next turn, for a person reading the entry, for an operator reading it back
     * six weeks later. The id is the only handle there is, and this tool is the
     * only place that holds both it and the answer.
     */
    @Test
    void the_result_names_the_picture_the_answer_is_about() throws Exception {
        Content.Image red = picture('a');
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        delegate("looker", "what is this", red.uid()))))
                .then(() -> answer("a red square"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("viewer"), "what am I looking at", Home.global(), generous(),
                () -> false, null, JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(),
                List.of(red));

        assertEquals("the agent 'looker', shown " + red.uid() + ", answered:\na red square",
                toolResults(transport, 2));
    }

    /** A delegation that named no picture renders what it always rendered, which
     *  is what makes the clause above worth reading when it appears. */
    @Test
    void a_delegation_with_no_pictures_reads_as_it_always_did() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("helper", "go"))
                .then(() -> answer("Sundial"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("viewer"), "ask helper", Home.global(), generous(), () -> false, null,
                JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(), List.of(picture('a')));

        assertEquals("the agent 'helper' answered:\nSundial", toolResults(transport, 2));
    }

    /** A child that did not answer still says which picture it was looking at:
     *  that is the reading somebody uses to decide whether to send it again. */
    @Test
    void a_child_that_did_not_answer_still_names_the_picture() throws Exception {
        Content.Image red = picture('a');
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        delegate("looker", "what is this", red.uid()))))
                .then(() -> asking("still looking", call("c9", "probe_read", "{}")))
                .then(() -> asking("still looking", call("c9", "probe_read", "{}")))
                .then(() -> asking("still looking", call("c9", "probe_read", "{}")))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport, Probe.returning("probe_read", "nothing"));

        runtime.run(agent("viewer"), "what am I looking at", Home.global(), generous(),
                () -> false, null, JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(),
                List.of(red));

        String result = allToolResults(transport);
        assertTrue(result.contains("the agent 'looker', shown " + red.uid()
                + ", did not reach an answer."), result);
    }

    // --- three refusals, three sentences ------------------------------------------

    /**
     * <b>An agent that has not declared it can see is not sent a picture.</b>
     *
     * <p>{@code AgentsConfig.requireModelSees} guards the other half of this at
     * boot — an agent <em>declaring</em> vision whose model no pool says can see
     * — and has nothing to say here: {@code helper} is a perfectly good agent,
     * correctly configured, being handed something it has no use for. {@code
     * AgentController.shown} refuses the identical thing for a person at the
     * other door, and until this check existed a delegation was the way round it.
     *
     * <p>What makes it worth a refusal rather than a silent send is that the
     * failure is invisible from every side: the model answers that it saw
     * nothing, which reads as that agent's limitation rather than as the call
     * being misaddressed.
     */
    @Test
    void an_agent_that_cannot_see_is_not_sent_a_picture() throws Exception {
        Content.Image red = picture('a');
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        delegate("helper", "what is this", red.uid()))))
                .then(() -> answer("I will ask looker instead"));
        JobRuntime runtime = delegating(transport);

        Outcome outcome = runtime.run(agent("viewer"), "what am I looking at", Home.global(),
                generous(), () -> false, null, JobWatch.UNWATCHED, Transcript.NONE,
                TurnCap.none(), List.of(red));

        // Not an exception, and not a run: the model corrects itself next turn.
        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals("I will ask looker instead", outcome.text());
        assertEquals(2, transport.calls().size(),
                "the blind callee was started anyway, which is the whole of what this refuses");
        String result = toolResults(transport, 1);
        assertTrue(result.startsWith(
                "the agent 'helper' has not declared 'vision: true'"), result);
    }

    /**
     * An id nothing here answers to is refused, and in its own words.
     *
     * <p>An id is refused when the <em>tier</em> has not got it, which is a
     * different fact from the one this test used to assert — it read {@code
     * an_id_the_caller_was_not_shown_is_refused} and pinned the rule the owner
     * reversed on 2026-09-08. What survives is the half that was doing the work:
     * a run reaches its own tier and nothing else, and a lookup that misses is
     * answered in words rather than by starting the callee anyway.
     *
     * <p>The runtime here holds {@link ImageStore#NONE}, so the tier holds
     * nothing at all — the state of every fixture in this file that does not say
     * otherwise, and the reason the two tests below have to build a store.
     */
    @Test
    void an_id_this_tier_has_not_got_is_refused() throws Exception {
        Content.Image red = picture('a');
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        delegate("looker", "what is this", UNSEEN))))
                .then(() -> answer("I will send the one I have"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("viewer"), "what am I looking at", Home.global(), generous(),
                () -> false, null, JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(),
                List.of(red));

        assertEquals(2, transport.calls().size(), "the callee was started with somebody's else"
                + " picture, or with none where one was named");
        String result = toolResults(transport, 1);
        assertTrue(result.startsWith("this server's global tier has no image " + UNSEEN), result);
        // The recovery: what this run IS looking at, so the next turn can be right.
        assertTrue(result.contains(red.uid()), result);
    }

    /**
     * <b>An id this run was told rather than shown reaches the callee, out of the
     * run's own tier.</b>
     *
     * <p>This is the decision of 2026-09-08 stated as behaviour, and the reason
     * it is worth a test of its own is that the caller here is {@code viewer}: a
     * text agent, shown nothing, holding an id the way every ingress but a
     * submit-time attachment delivers one — as a string in front of it. Under
     * the rule this replaced there was no id such an agent could ever legally
     * pass, which made {@code interlocutor}'s {@code image_reader} edge dead
     * code that shipped.
     *
     * <p>Asserted on the <em>bytes</em> the child was shown and not merely on
     * the call succeeding: what travels has to be the picture the store holds,
     * so a resolution that quietly passed an empty part or the id as text would
     * fail here rather than pass for the wrong reason.
     */
    @Test
    void an_id_the_caller_was_told_is_resolved_out_of_its_own_tier(@TempDir Path tmp)
            throws Exception {
        ImageStore store = imagesIn(tmp);
        Home atlas = Home.of("atlas");
        String uid = store.store(atlas, "red.png", PNG).id();
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        delegate("looker", "what is this", uid))))
                .then(() -> answer("a red square"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport, store);

        // Shown NOTHING. The whole of what this caller has is the id.
        runtime.run(agent("viewer"), "what is img_… ", atlas, generous(), () -> false, null,
                JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(), List.of());

        List<ChatMessage> child = transport.calls().get(1);
        ChatMessage utterance = child.get(child.size() - 1);
        assertTrue(utterance.parts().contains(
                        new Content.Image(uid, store.dataUri(atlas, uid))),
                "the child was not shown the picture its tier holds: " + utterance.parts());
        assertEquals("the agent 'looker', shown " + uid + ", answered:\na red square",
                toolResults(transport, 2));
    }

    /**
     * <b>An id named out of a project's own file reaches a vision agent, end to
     * end, and no byte of it was ever copied.</b>
     *
     * <p>The whole ingress in one test. {@code LocalProvider} reads a PNG the
     * model asked for by name, records where it is and nothing else, and hands
     * back the id; the model passes that id to {@code agent_run}; the callee is
     * shown the picture. This is §6a's second session — <i>"a person asks what an
     * image in the workspace says ... the orchestrating agent passes the id it
     * got from file_stat"</i> — with both halves of it wired together.
     *
     * <p><b>Asserted on the bytes the child was shown</b>, and on the data URI
     * being built from the file that is still sitting in the workspace: a
     * resolution that had quietly copied the picture into the data directory
     * would pass a weaker assertion and would be the thing §6a forbids.
     *
     * <p>The caller here is {@code viewer}, a text agent that has been shown
     * nothing. That is the point: a file read delivers an id <em>as text</em>,
     * which is what made the "only what it was shown" rule coupling to one
     * ingress rather than containment. Nothing here would have been legal before
     * 2026-09-08.
     */
    @Test
    void a_picture_named_in_a_workspace_file_reaches_a_vision_agent(@TempDir Path tmp)
            throws Exception {
        Path picture = Files.write(tmp.resolve("logo.png"), PNG);
        Home atlas = Home.of("atlas");
        ImageStore store = imagesIn(tmp.resolve("data"), Set.of(picture));
        // What LocalProvider.named does on a read the model asked for by name.
        String uid = store.note(atlas, picture, PNG).id();
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        delegate("looker", "what is this", uid))))
                .then(() -> answer("a red square"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport, store);

        runtime.run(agent("viewer"), "what does logo.png say", atlas, generous(), () -> false,
                null, JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(), List.of());

        List<ChatMessage> child = transport.calls().get(1);
        ChatMessage utterance = child.get(child.size() - 1);
        assertTrue(utterance.parts().contains(
                        new Content.Image(uid, store.dataUri(atlas, uid))),
                "the child was not shown the picture the workspace holds: " + utterance.parts());
        assertEquals("the agent 'looker', shown " + uid + ", answered:\na red square",
                toolResults(transport, 2));
        // NOTHING WAS COPIED. The record is in the data directory and the bytes
        // are not: §6a's "we never store bytes on the server unless it's in a
        // server project", asserted from the side that would notice a copy.
        assertTrue(Files.exists(tmp.resolve("data/atlas").resolve(uid + ".json")));
        assertFalse(Files.exists(tmp.resolve("data/atlas").resolve(uid + ".png")),
                "the picture was copied into the data directory; the workspace file is meant to"
                        + " be the only copy");
    }

    /**
     * <b>A picture whose file has gone is said to be gone, and is not called
     * refused.</b>
     *
     * <p>The reason the path is touched at all, and it is Enzo's rather than a
     * security one: <i>"path check still happens because we don't know if it
     * exists."</i> This server never held these bytes, so the only way to know
     * they are still there is to look — and looking is where the fence is, which
     * is why the liveness check and the permission check are one act.
     *
     * <p><b>The sentence must not say refused</b>, on {@code
     * ClientEnforcer.Vanished}'s argument: a run told it lacked permission goes
     * looking for a permission to fix, and there is none — the id was perfectly
     * real and the picture is not there. Asserted as the two words being right
     * <em>and</em> the wrong one being absent, because one sentence for two
     * states is one state to anybody reading it.
     */
    @Test
    void a_picture_whose_workspace_file_is_gone_is_not_called_refused(@TempDir Path tmp)
            throws Exception {
        Path picture = Files.write(tmp.resolve("logo.png"), PNG);
        Home atlas = Home.of("atlas");
        ImageStore store = imagesIn(tmp.resolve("data"), Set.of(picture));
        String uid = store.note(atlas, picture, PNG).id();
        Files.delete(picture);
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        delegate("looker", "what is this", uid))))
                .then(() -> answer("I will look for it"));
        JobRuntime runtime = delegating(transport, store);

        runtime.run(agent("viewer"), "what does logo.png say", atlas, generous(), () -> false,
                null, JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(), List.of());

        assertEquals(2, transport.calls().size(), "the callee was started for a picture that is"
                + " not there");
        String result = toolResults(transport, 1);
        assertTrue(result.contains("no longer there"), result);
        assertFalse(result.contains("refus"),
                "a picture that has gone was called refused: " + result);
    }

    /**
     * <b>A picture this project may no longer reach is refused, is not called
     * gone, and does not travel.</b>
     *
     * <p>The rule {@code ImageFence} exists for, seen from the surface a model
     * reads. An id is content-addressed and therefore globally meaningful, while
     * permission is per path — so an id that resolved without re-asking would
     * outlive the root it came from. Here the file is exactly where it was and
     * the project's reach has changed underneath it.
     *
     * <p><b>The sentence must not say gone</b>: the bytes are sitting right
     * there and an operator can put the root back, so a run told the picture had
     * disappeared would send whoever reads the transcript looking for a file that
     * never moved. And the callee must not be started — asserted, because a
     * refusal that said the right words and passed the picture anyway is the
     * failure that produces no error at all.
     */
    @Test
    void a_picture_this_project_may_no_longer_read_is_refused_and_not_called_gone(
            @TempDir Path tmp) throws Exception {
        Path picture = Files.write(tmp.resolve("logo.png"), PNG);
        Home atlas = Home.of("atlas");
        // Mutable, so the reach can change between the naming and the resolution
        // the way an operator's `unlend` changes it.
        Set<Path> reachable = new java.util.HashSet<>(Set.of(picture));
        ImageStore store = imagesIn(tmp.resolve("data"), reachable);
        String uid = store.note(atlas, picture, PNG).id();
        reachable.clear();
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        delegate("looker", "what is this", uid))))
                .then(() -> answer("I will ask about something else"));
        JobRuntime runtime = delegating(transport, store);

        runtime.run(agent("viewer"), "what does logo.png say", atlas, generous(), () -> false,
                null, JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(), List.of());

        assertEquals(2, transport.calls().size(),
                "the callee was started with a picture this project may no longer read");
        String result = toolResults(transport, 1);
        assertTrue(result.contains("may no longer read that file"), result);
        assertFalse(result.contains("gone"), "a refusal said the picture was gone: " + result);
        assertFalse(result.contains("base64"), "the refusal carried the picture: " + result);
        assertTrue(Files.isRegularFile(picture), "the fixture moved the file; it must not");
    }

    /**
     * <b>The tier is the run's, and a model cannot name another one.</b>
     *
     * <p>The containment property of the permissive rule, and the only one that
     * is code rather than arithmetic: an id is unguessable because it is 128
     * bits of a content hash, and nothing enumerates images, but neither of
     * those would stop a delegating agent naming a picture it had legitimately
     * been told about <em>in another project</em>. What stops it is that {@code
     * home} arrives as a parameter of {@code AgentTool.run} and there is no
     * argument in the schema that could carry a second one.
     *
     * <p>The same store, the same id, the same everything else: only the run's
     * home differs, which is what makes this a test of the scoping rather than
     * of a lookup that happened to fail.
     */
    @Test
    void an_id_in_another_project_is_not_reachable_from_this_run(@TempDir Path tmp)
            throws Exception {
        ImageStore store = imagesIn(tmp);
        String uid = store.store(Home.of("atlas"), "red.png", PNG).id();
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        delegate("looker", "what is this", uid))))
                .then(() -> answer("I will ask about something else"));
        JobRuntime runtime = delegating(transport, store);

        // The picture exists, this store holds it, and this run is somewhere else.
        runtime.run(agent("viewer"), "what is that picture", Home.of("borges"), generous(),
                () -> false, null, JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(),
                List.of());

        assertEquals(2, transport.calls().size(),
                "the callee was started with another project's picture");
        String result = toolResults(transport, 1);
        assertTrue(result.startsWith("this project 'borges' has no image " + uid), result);
    }

    /**
     * <b>A picture the caller is looking at is passed as the part it was shown,
     * without the store being asked.</b>
     *
     * <p>The order of the two arms, pinned. It is not a preference for the
     * faster one: a picture handed to a job — the ephemeral class, bytes that
     * never entered a project — has no tier to be found in, so the shown arm is
     * the <em>only</em> arm that can answer for it, and a resolution that
     * consulted the store first would answer a different question for every
     * image that has both.
     *
     * <p>Driven by giving the store and the run disagreeing bytes under one id,
     * which cannot happen in production — an id is a hash of the bytes — and is
     * exactly what makes the two arms distinguishable here.
     */
    @Test
    void a_picture_the_caller_is_shown_is_passed_without_asking_the_store(@TempDir Path tmp)
            throws Exception {
        ImageStore store = imagesIn(tmp);
        Home atlas = Home.of("atlas");
        String uid = store.store(atlas, "red.png", PNG).id();
        Content.Image shown = new Content.Image(uid, "data:image/png;base64,SHOWNTOTHISRUN");
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        delegate("looker", "what is this", uid))))
                .then(() -> answer("a red square"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport, store);

        runtime.run(agent("viewer"), "what am I looking at", atlas, generous(), () -> false,
                null, JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(), List.of(shown));

        List<ChatMessage> child = transport.calls().get(1);
        ChatMessage utterance = child.get(child.size() - 1);
        assertTrue(utterance.parts().contains(shown),
                "the id was resolved out of the store rather than out of what the run holds: "
                        + utterance.parts());
    }

    /**
     * A string that is not an image id at all gets a different sentence from an
     * id this tier has not got.
     *
     * <p>Two facts about two different things — the string, and the tier — and a
     * model told the same thing for both has no way to tell a typo from a picture
     * that is somewhere else. Asserted as the two messages being <em>different</em>
     * as well as each being right, because one sentence for two states is one
     * state to anybody reading it.
     */
    @Test
    void a_string_that_is_not_an_image_id_is_refused_in_other_words() throws Exception {
        Content.Image red = picture('a');
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        delegate("looker", "what is this", "the red one"))))
                .then(() -> asking("trying again", call("c2", AgentRegistry.AGENT_RUN,
                        delegate("looker", "what is this", UNSEEN))))
                .then(() -> answer("I will send the one I have"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("viewer"), "what am I looking at", Home.global(), generous(),
                () -> false, null, JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(),
                List.of(red));

        // The LAST tool result of each request: a request carries every tool
        // result said so far, so the third one holds both of these.
        String notAnId = lastToolResult(transport, 1);
        String notShown = lastToolResult(transport, 2);
        assertTrue(notAnId.startsWith("'the red one' is not an image id"), notAnId);
        assertNotEquals(notAnId, notShown);
        assertTrue(notShown.startsWith("this server's global tier has no image"), notShown);
    }

    /** An id full of newlines is flattened, for {@code
     *  an_invented_agent_name_is_flattened_into_one_line}'s reason: not a forgery
     *  defence — a JSON string value has no way out of its own field — but an
     *  invented id should not turn one sentence into twenty. */
    @Test
    void an_invented_image_id_is_flattened_into_one_line() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        delegate("looker", "what is this", "one\\ntwo\\nthree"))))
                .then(() -> answer("done"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("viewer"), "go", Home.global(), generous(), () -> false, null,
                JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(), List.of(picture('a')));

        String result = toolResults(transport, 1);
        assertTrue(result.startsWith("'one two three' is not an image id"), result);
    }

    /** A caller holding no picture at all is told what an id here has to name,
     *  rather than being told which of the none it might have meant. <b>And no
     *  longer that it has nothing to pass on</b>, which was true under the old
     *  rule and is not under this one: a run shown nothing may still name a
     *  picture its tier holds, and a sentence closing that door would send a
     *  model away from the one thing it can actually do. */
    @Test
    void a_caller_shown_nothing_is_told_what_an_id_here_has_to_name() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        delegate("looker", "what is this", UNSEEN))))
                .then(() -> answer("done"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("viewer"), "go", Home.global(), generous(), null);

        String result = toolResults(transport, 1);
        assertTrue(result.contains("You are not being shown any picture yourself, so an id here"
                + " has to name one this tier already holds."), result);
    }

    /**
     * {@code images} that is not a list of strings is a tool result and not a
     * lenient reading.
     *
     * <p>{@code ToolArguments.optionalTexts} refuses a bare string where an array
     * belongs, which is the opposite of {@code optionalInt}'s leniency about a
     * number sent as one — and the reason is here: these elements are opaque ids,
     * so {@code "img_a img_b"} read as a list of one would be a run shown one
     * picture named after two, with nothing anywhere to say so.
     */
    @Test
    void images_that_are_not_a_list_are_a_tool_result() throws Exception {
        Content.Image red = picture('a');
        Scripted transport = new Scripted()
                .then(() -> asking("handing it on", call("c1", AgentRegistry.AGENT_RUN,
                        "{\"agent\": \"looker\", \"task\": \"what is this\", \"images\": \""
                                + red.uid() + "\"}")))
                .then(() -> answer("done"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("viewer"), "go", Home.global(), generous(), () -> false, null,
                JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(), List.of(red));

        assertEquals(2, transport.calls().size(), "a bare string was read as a list of one");
        String result = toolResults(transport, 1);
        assertTrue(result.startsWith("agent_run needs 'images' to be a list of image ids"),
                result);
    }

    /** An absent {@code images} is every delegation made before the argument
     *  existed, and reaches the callee as the identical request. */
    @Test
    void a_delegation_naming_no_images_is_the_call_it_always_was() throws Exception {
        Scripted transport = new Scripted()
                .then(() -> delegates("looker", "what do you know"))
                .then(() -> answer("nothing in particular"))
                .thenAlways(() -> answer("done"));
        JobRuntime runtime = delegating(transport);

        runtime.run(agent("viewer"), "go", Home.global(), generous(), () -> false, null,
                JobWatch.UNWATCHED, Transcript.NONE, TurnCap.none(), List.of(picture('a')));

        List<ChatMessage> child = transport.calls().get(1);
        ChatMessage utterance = child.get(child.size() - 1);
        assertEquals(List.of(new Content.Text("what do you know")),
                utterance.parts(),
                "a callee that was named no picture was shown one anyway, or was captioned"
                        + " about one it cannot see");
    }

    // --- helpers -----------------------------------------------------------------

    private static AgentDefinition agent(String name) throws Exception {
        return registry().get(name);
    }

    private static Outcome awaitOutcome(JobStore store, String id) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (store.get(id).state() == Job.State.DONE) {
                return store.get(id).outcome()
                        .orElseThrow(() -> new AssertionError("a DONE job with no outcome"));
            }
            Thread.sleep(50);
        }
        throw new AssertionError("job " + id + " never finished");
    }

    private static void join(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(BLOCK_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted joining " + thread, e);
        }
    }
}
