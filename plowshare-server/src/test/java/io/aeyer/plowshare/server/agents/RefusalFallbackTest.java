package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
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
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Refusal fallback, end to end: a real {@link Turn}, a real log, and two pools
 * standing for the agent's own model and a low-refusal class served elsewhere.
 *
 * <p>Through {@code Turn} and Postgres rather than a recorded transcript,
 * because the claims here are about what the <em>next</em> request carries, and
 * that is read back out of the log by {@code Compaction} — a fixture transcript
 * would be asserting what the test itself handed over.
 */
@Testcontainers
class RefusalFallbackTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final Home RESEARCH = Home.of("research");

    private static final int ROOMY_CONTEXT = 1_000_000;

    private static final String PROMPT = "You research public sources for an authorised"
            + " engagement.";

    private static final String ASKED = "Who is the registrant of example.org?";
    private static final String REFUSED = "I'm sorry, but I can't help with that.";
    private static final String ANSWERED = "The registrant of example.org is IANA.";
    private static final String NEXT = "Find three more like it.";

    private static final String LOOK = "look";
    private static final String LOOKED = "whois example.org: Registrant: IANA";

    private static JdbcTemplate jdbc;

    private ConversationStore conversations;
    private TurnStore turns;
    private CompactionStore compactions;
    private EntryStore entries;

    private final List<JobStore> opened = new ArrayList<>();
    private final List<Compaction> folding = new ArrayList<>();

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void freshConversations() {
        // board_topics, board_messages and board_seats: V74 gave each a foreign
        // key into conversations. firings: a further hop out, through its V74
        // foreign key into board_topics. user_inbox: a hop past that, through
        // its pre-existing V40 foreign key into firings. Postgres refuses to
        // truncate conversations unless every one of these goes with it.
        jdbc.execute("TRUNCATE TABLE orchestration_start_receipts, orchestration_script_steps, board_reads, board_notices, board_decisions, digests, digest_children, digest_memories, digest_spans,"
                + " digest_revisions, memory_provenance, conversations, turns, compactions,"
                + " entries, citations, orchestrations, orchestration_messages, board_topics,"
                + " board_messages, board_seats, firings, user_inbox");
        conversations = new ConversationStore(jdbc);
        turns = new TurnStore(jdbc);
        compactions = new CompactionStore(jdbc);
        entries = new EntryStore(jdbc);
    }

    @AfterEach
    void stopEveryRun() {
        opened.forEach(JobStore::close);
        opened.clear();
        folding.forEach(Compaction::close);
        folding.clear();
    }

    // --- 1. a primary answer ---------------------------------------------------------------

    @Test
    void a_primary_answer_needs_no_fallback_and_is_the_conversation() {
        Fixture fixture = new Fixture();
        fixture.primary.then(said("The port is 5432."));
        String conversation = fixture.open();

        Outcome outcome = fixture.speak(conversation, fixture.osint, ASKED);

        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals("The port is 5432.", outcome.text());
        assertTrue(fixture.fallback.seen().isEmpty(), "nothing refused, so nothing is rerouted");
        assertEquals(List.of(EntryKind.UTTERANCE, EntryKind.ANSWER), kindsOf(conversation));
        assertEquals(List.of(EntryKind.UTTERANCE, EntryKind.ANSWER), projectedKindsOf(conversation));

        Invocation call = invocationOf(conversation, EntryKind.ANSWER, 0);
        assertEquals(Invocation.Dispatch.PRIMARY, call.dispatch());
        assertEquals(CompletionOutcome.ANSWERED, call.outcome());
        assertEquals("vllm", call.pool());
        assertEquals("gpt-oss-120b", call.wireModel());
    }

    // --- 2, 6, 7. a clear refusal on an agent that may fall back ---------------------------

    @Test
    void a_refusal_is_recorded_and_the_fallbacks_answer_becomes_the_conversation() {
        Fixture fixture = new Fixture();
        fixture.primary.then(said(REFUSED));
        fixture.fallback.then(said(ANSWERED));
        String conversation = fixture.open();

        Outcome outcome = fixture.speak(conversation, fixture.osint, ASKED);

        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals(ANSWERED, outcome.text(), "the person is given the fallback's answer");
        assertEquals(ANSWERED, turns.forConversation(conversation).get(0).answer(),
                "and the turns table, which every client lists, says the same");

        assertEquals(List.of(EntryKind.UTTERANCE, EntryKind.REFUSAL, EntryKind.ANSWER),
                kindsOf(conversation), "the log holds the refusal where it happened");
        assertEquals(REFUSED, contentOf(conversation, EntryKind.REFUSAL, 0),
                "verbatim, for whoever audits it");
        assertEquals(List.of(EntryKind.UTTERANCE, EntryKind.ANSWER),
                projectedKindsOf(conversation),
                "and what a model can be shown holds the question and one answer");
    }

    @Test
    void the_fallbacks_answer_is_attributed_to_the_fallback_and_never_to_the_agents_model() {
        Fixture fixture = new Fixture();
        fixture.primary.then(said(REFUSED));
        fixture.fallback.then(said(ANSWERED));
        String conversation = fixture.open();

        fixture.speak(conversation, fixture.osint, ASKED);

        Invocation refusal = invocationOf(conversation, EntryKind.REFUSAL, 0);
        assertEquals(Invocation.Dispatch.PRIMARY, refusal.dispatch());
        assertEquals(CompletionOutcome.REFUSED, refusal.outcome());
        assertEquals("big", refusal.specifier());
        assertEquals("vllm", refusal.pool());
        assertEquals("gpt-oss-120b", refusal.wireModel());
        assertNotNull(refusal.fallbackReason(), "the refusal says why it was rerouted");

        Invocation answer = invocationOf(conversation, EntryKind.ANSWER, 0);
        assertEquals(Invocation.Dispatch.FALLBACK, answer.dispatch());
        assertEquals(CompletionOutcome.ANSWERED, answer.outcome());
        assertEquals("low_refusal_osint", answer.specifier());
        assertEquals("spark", answer.pool());
        assertEquals("small-osint", answer.wireModel());
        assertEquals(refusal.fallbackReason(), answer.fallbackReason());
        assertEquals("osint", answer.agent());
        assertFalse(refusal.id().equals(answer.id()), "two calls, two invocations");
    }

    @Test
    void the_fallback_is_sent_exactly_what_the_refused_call_was_sent() {
        Fixture fixture = new Fixture();
        fixture.primary.then(said(REFUSED));
        fixture.fallback.then(said(ANSWERED));
        String conversation = fixture.open();

        fixture.speak(conversation, fixture.osint, ASKED);

        List<ChatMessage> refusedCall = fixture.primary.seen().get(0);
        List<ChatMessage> fallbackCall = fixture.fallback.seen().get(0);
        assertEquals(refusedCall, fallbackCall,
                "the same system message, history and utterance, byte for byte: nothing"
                        + " added to explain a reroute, and the prefix does not move");
        assertTrue(fallbackCall.stream().noneMatch(message -> message.content() != null
                        && (message.content().contains(REFUSED)
                                || message.content().toLowerCase().contains("refus"))),
                "the fallback sees an ordinary task, and not the refusal");
    }

    @Test
    void a_fallback_is_shown_what_the_run_had_already_found_out() {
        Fixture fixture = new Fixture();
        fixture.primary
                .then(messages -> new Completion("", "tool_calls", usage(),
                        List.of(new ToolCall("c1", LOOK, "{}"))))
                .then(said(REFUSED));
        fixture.fallback.then(said(ANSWERED));
        String conversation = fixture.open();

        fixture.speak(conversation, fixture.osint, ASKED);

        List<ChatMessage> fallbackCall = fixture.fallback.seen().get(0);
        assertEquals(fixture.primary.seen().get(1), fallbackCall,
                "the refused call's history, tool traffic included");
        assertTrue(fallbackCall.stream().anyMatch(message ->
                        message.role() == ChatMessage.Role.TOOL && LOOKED.equals(message.content())),
                "evidence the run retrieved is part of the task the fallback is handed");
        assertEquals(1, fixture.looked.size(), "and it is not fetched a second time");
    }

    // --- 3. a fallback that fails ----------------------------------------------------------

    @Test
    void a_fallback_that_refuses_too_is_the_output_and_is_not_rerouted() {
        Fixture fixture = new Fixture();
        String alsoRefused = "I cannot help with that request.";
        fixture.primary.then(said(REFUSED));
        fixture.fallback.then(said(alsoRefused));
        String conversation = fixture.open();

        Outcome outcome = fixture.speak(conversation, fixture.osint, ASKED);

        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals(alsoRefused, outcome.text(), "the fallback's refusal is the output");
        assertEquals(1, fixture.primary.seen().size(), "no recursion back to the primary");
        assertEquals(1, fixture.fallback.seen().size(), "and one fallback attempt");

        assertEquals(List.of(EntryKind.UTTERANCE, EntryKind.REFUSAL, EntryKind.ANSWER),
                kindsOf(conversation));
        assertEquals(REFUSED, contentOf(conversation, EntryKind.REFUSAL, 0),
                "the original refusal is still observable");
        assertEquals(List.of(EntryKind.UTTERANCE, EntryKind.ANSWER),
                projectedKindsOf(conversation));
        assertEquals(alsoRefused, contentOf(conversation, EntryKind.ANSWER, 0));

        Invocation output = invocationOf(conversation, EntryKind.ANSWER, 0);
        assertEquals(Invocation.Dispatch.FALLBACK, output.dispatch(),
                "attributed to the model that said it");
        assertEquals(CompletionOutcome.REFUSED, output.outcome(), "and counted as a refusal");
        assertEquals("small-osint", output.wireModel());
    }

    @Test
    void a_fallback_that_cannot_be_reached_behaves_as_though_there_were_none() {
        Fixture fixture = new Fixture();
        fixture.primary.then(said(REFUSED));
        fixture.fallback.then(messages -> {
            throw new LlmException("the spark is powered off");
        });
        String conversation = fixture.open();

        Outcome outcome = fixture.speak(conversation, fixture.osint, ASKED);

        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals(REFUSED, outcome.text());
        assertTrue(outcome.detail().contains("UNAVAILABLE"), outcome.detail());
        assertEquals(1, fixture.primary.seen().size());
        assertEquals(1, fixture.fallback.seen().size());
        assertEquals(List.of(EntryKind.UTTERANCE, EntryKind.REFUSAL, EntryKind.DIAGNOSTIC,
                EntryKind.ANSWER), kindsOf(conversation));
        assertEquals(List.of(EntryKind.UTTERANCE, EntryKind.ANSWER),
                projectedKindsOf(conversation),
                "what a model is shown is what it would be with no fallback: the refusal");
        assertEquals(REFUSED, contentOf(conversation, EntryKind.ANSWER, 0));
        Invocation standing = invocationOf(conversation, EntryKind.ANSWER, 0);
        assertEquals(Invocation.Dispatch.PRIMARY, standing.dispatch(),
                "the words that stand are the agent's model's and are attributed to it");
        assertEquals(invocationOf(conversation, EntryKind.REFUSAL, 0).id(), standing.id(),
                "the same call, recorded when it happened and again as what the turn came to");
    }

    // --- 4. an agent that may not fall back ------------------------------------------------

    @Test
    void a_refusal_from_an_agent_with_no_fallback_is_its_answer_and_nothing_is_rerouted() {
        Fixture fixture = new Fixture();
        fixture.primary.then(said(REFUSED));
        String conversation = fixture.open();

        Outcome outcome = fixture.speak(conversation, fixture.plain, ASKED);

        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals(REFUSED, outcome.text());
        assertTrue(fixture.fallback.seen().isEmpty(),
                "detection is not authorisation: an agent nobody allowed a fallback keeps its"
                        + " refusals");
        assertEquals(List.of(EntryKind.UTTERANCE, EntryKind.ANSWER), kindsOf(conversation));
        Invocation call = invocationOf(conversation, EntryKind.ANSWER, 0);
        assertEquals(CompletionOutcome.REFUSED, call.outcome(),
                "and it is still counted as a refusal");
        assertEquals(Invocation.Dispatch.PRIMARY, call.dispatch());
    }

    // --- 5, 7, 8. the turn after ----------------------------------------------------------

    @Test
    void the_next_turn_reads_the_fallbacks_answer_as_ordinary_conversation() {
        Fixture fixture = new Fixture();
        fixture.primary.then(said(REFUSED)).then(said("Here are three more."));
        fixture.fallback.then(said(ANSWERED));
        String conversation = fixture.open();

        fixture.speak(conversation, fixture.osint, ASKED);
        Outcome next = fixture.speak(conversation, fixture.osint, NEXT);

        assertEquals("Here are three more.", next.text());
        List<ChatMessage> firstRequest = fixture.primary.seen().get(0);
        List<ChatMessage> secondRequest = fixture.primary.seen().get(1);
        assertEquals(List.of(
                        ChatMessage.system(PROMPT),
                        ChatMessage.user(ASKED),
                        ChatMessage.assistant(ANSWERED, List.of()),
                        ChatMessage.user(NEXT)),
                secondRequest,
                "user A, assistant C, user D: one ordinary assistant message and no refusal");
        assertEquals(firstRequest, secondRequest.subList(0, firstRequest.size()),
                "everything up to the utterance that was refused is the same prefix it was");

        assertTrue(kindsOf(conversation).contains(EntryKind.REFUSAL),
                "while the log still holds the refusal for audit");
        assertTrue(entries.lastAnswerWasAFallback(conversation, 2),
                "and the second turn is known to follow a fallback's answer");
    }

    // --- helpers ---------------------------------------------------------------------------

    private List<EntryKind> kindsOf(String conversation) {
        return entries.forConversation(conversation).stream().map(EntryRecord::kind).toList();
    }

    private List<EntryKind> projectedKindsOf(String conversation) {
        return entries.thatProjectFor(conversation).stream().map(EntryRecord::kind).toList();
    }

    private EntryRecord nth(String conversation, EntryKind kind, int nth) {
        return entries.forConversation(conversation).stream()
                .filter(entry -> entry.kind() == kind)
                .toList()
                .get(nth);
    }

    private String contentOf(String conversation, EntryKind kind, int nth) {
        return nth(conversation, kind, nth).content();
    }

    private Invocation invocationOf(String conversation, EntryKind kind, int nth) {
        return entries.invocationOf(conversation, nth(conversation, kind, nth).ordinal())
                .orElseThrow(() -> new AssertionError(kind + " #" + nth + " has no provenance"));
    }

    private static TokenUsage usage() {
        return TokenUsage.of(120, 12, null);
    }

    private static Function<List<ChatMessage>, Completion> said(String content) {
        return messages -> new Completion(content, "stop", usage(), List.of());
    }

    private final class Fixture {

        final Model primary = new Model("vllm");
        final Model fallback = new Model("spark");
        final List<String> looked = Collections.synchronizedList(new ArrayList<>());

        final AgentDefinition plain = new AgentDefinition(
                "plain", "a fixture agent with no fallback", "big", List.of(LOOK), List.of(),
                List.of(), 6, 20, PROMPT);

        final AgentDefinition osint = new AgentDefinition(
                "osint", "a fixture agent that may fall back", "big", List.of(LOOK), List.of(),
                List.of(), 6, 20, PROMPT)
                .fallback(new AgentDefinition.Fallback(
                        Set.of(AgentDefinition.Fallback.Trigger.REFUSAL), "low_refusal_osint", 1,
                        Sampling.NONE));

        private final JobStore jobs;
        private final Turn turn;

        Fixture() {
            LlmDispatcher models = new LlmDispatcher(List.of(
                    new LlmPool("vllm", List.of("gpt-oss-120b"),
                            Map.of("big", "gpt-oss-120b", "fast", "gpt-oss-120b"),
                            4, 1, Duration.ofSeconds(5), primary),
                    new LlmPool("spark", List.of("small-osint"),
                            Map.of("low_refusal_osint", "small-osint"),
                            4, 1, Duration.ofSeconds(5), fallback)),
                    new NoOpTokenLedger());
            JobRuntime runtime = new JobRuntime(models, List.of(new Look()), null,
                    (home, grants, sessionId, owner) -> List.<FileProvider>of());
            this.jobs = new JobStore(runtime);
            Compaction compaction = new Compaction(models, RefusalFallbackTest::folder, turns,
                    compactions, entries, ROOMY_CONTEXT, Runnable::run);
            this.turn = new Turn(jobs, conversations, turns, compaction);
            opened.add(jobs);
            folding.add(compaction);
        }

        String open() {
            return conversations.open(RESEARCH, Budget.of(20)).id();
        }

        Outcome speak(String conversation, AgentDefinition agent, String utterance) {
            String job = turn.speak(conversation, agent, utterance, null);
            for (int attempt = 0; attempt < 400; attempt++) {
                if (jobs.get(job).state() == Job.State.DONE) {
                    Outcome outcome = jobs.get(job).outcome().orElse(null);
                    assertNotNull(outcome, "a DONE job with no outcome");
                    return outcome;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(25);
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted waiting for " + job, stop);
                }
            }
            throw new AssertionError("job " + job + " never finished");
        }

        private final class Look implements AgentTool {

            @Override
            public ToolSchema schema() {
                return new ToolSchema(LOOK, "looks something up in public records", Map.of());
            }

            @Override
            public String run(String argumentsJson, Home home) {
                looked.add(argumentsJson);
                return LOOKED;
            }
        }
    }

    private static AgentDefinition folder() {
        return new AgentDefinition(Compaction.FOLDER, "a fixture folder", "fast",
                List.of(), List.of(), List.of(), 1, 1,
                "You summarise a span of a recorded conversation.");
    }

    /** A pool's transport that answers from a script, in order, and remembers every
     *  message list it was sent. Asked past its script, it fails the test. */
    private static final class Model implements LlmTransport {

        private final String pool;
        private final ConcurrentLinkedQueue<Function<List<ChatMessage>, Completion>> script =
                new ConcurrentLinkedQueue<>();
        private final List<List<ChatMessage>> seen = Collections.synchronizedList(new ArrayList<>());

        Model(String pool) {
            this.pool = pool;
        }

        Model then(Function<List<ChatMessage>, Completion> step) {
            script.add(step);
            return this;
        }

        List<List<ChatMessage>> seen() {
            synchronized (seen) {
                return List.copyOf(seen);
            }
        }

        @Override
        public OptionalInt contextLength(String wireModel) {
            return OptionalInt.of(ROOMY_CONTEXT);
        }

        @Override
        public String poolName() {
            return pool;
        }

        @Override
        public void close() {
            // Nothing was opened.
        }

        @Override
        public Embeddings embed(String wireModel, List<String> input) {
            throw new UnsupportedOperationException("a turn does not embed");
        }

        @Override
        public Completion stream(
                String wireModel, List<ChatMessage> messages, Sampling sampling,
                List<ToolSchema> tools, Deltas sink, BooleanSupplier abandoned) {
            Completion streamed = complete(wireModel, messages, sampling, tools);
            if (abandoned.getAsBoolean()) {
                throw new CallerAbandonedException(poolName());
            }
            return streamed;
        }

        @Override
        public Completion complete(
                String wireModel, List<ChatMessage> messages, Sampling sampling,
                List<ToolSchema> tools) {
            seen.add(List.copyOf(messages));
            Function<List<ChatMessage>, Completion> step = script.poll();
            if (step == null) {
                throw new AssertionError("the pool '" + pool + "' was called more often than"
                        + " its script allows");
            }
            return step.apply(messages);
        }
    }
}
