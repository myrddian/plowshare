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
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.llm.tokens.TokenCount;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
 * A fold inside a turn — spec 2026-09-30-fold-at-60-and-80 §4, driven through a real turn: {@code
 * Turn} over {@code JobRuntime} over {@code Compaction}, against a scripted model and Postgres.
 *
 * <p><b>The thresholds are configured</b> — due at 60 000 and now at 80 000 of a 100 000 window —
 * so that the percentages the spec's tests are written in are the numbers the fixtures cross;
 * {@code FoldThresholdsTest} is where the defaults are pinned. The model's prompt counts are
 * scripted per call, so where the turn stands at a boundary is a number the test chose. With no
 * {@link Tokenizer} handed in, what a step appended is not counted and the turn stands at the
 * model's own counts; the tests that weigh steps hand one in.
 */
@Testcontainers
class FoldInsideATurnTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final Home PAYMENTS = Home.of("payments");
    private static final String SUMMARY = "It read files one to three and learned the layout.";
    private static final String PROMPT = "You work.";
    private static final String FOLDER_PROMPT =
            "You summarise a span of a recorded conversation so that the turns it covers can"
                    + " stop being sent to a model.";
    private static final String FOLDING = "folding";
    private static final String TOOL = "fetchy";

    private static final int WINDOW = 100_000;
    private static final int DUE = 60_000;
    private static final int NOW = 80_000;

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
    void fresh() {
        // The board's tables (V74) each hold a foreign key into `conversations`, `firings` one
        // into `board_topics`, and `user_inbox` one into `firings`, so all five are named here
        // or Postgres refuses to empty `conversations` at all.
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
    void close() {
        opened.forEach(JobStore::close);
        opened.clear();
        folding.forEach(Compaction::close);
        folding.clear();
    }

    @Test
    void an_in_turn_fold_keeps_the_worker_execution_and_is_billed_as_the_folder() {
        Model model = new Model().stepping(6)
                .costing(10_000, 20_000, 30_000, 40_000, 50_000, 85_000, 30_000);
        String id = open(7);
        var owner = io.aeyer.plowshare.server.llm.accounting.UsageAttribution.project("alice", "42",
                io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Operation.AGENT_CHAT)
                .withExecution(io.aeyer.plowshare.server.llm.accounting.UsageLineage.root(id),
                        io.aeyer.plowshare.server.llm.accounting.UsageLineage.root("execution"),
                        io.aeyer.plowshare.server.llm.accounting.UsageLineage.NONE, "worker", 1L, null);
        var captures = new java.util.concurrent.CopyOnWriteArrayList<io.aeyer.plowshare.server.llm.accounting.UsageAttribution>();
        var fixture = new Fixture(model, worker(7), owner, captures);
        Outcome result = fixture.speakAndWait(id, "build the thing");
        assertEquals(Ending.ANSWERED, result.ending());
        assertEquals(8, captures.size());
        var folds = captures.stream().filter(capture -> capture.operation()
                == io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Operation.FOLD).toList();
        assertEquals(1, folds.size());
        assertEquals(folder().name(), folds.getFirst().agentName());
        assertEquals(owner.runs(), folds.getFirst().runs());
        assertEquals(owner.conversations(), folds.getFirst().conversations());
        assertEquals("alice", folds.getFirst().accountHandle());
        assertEquals("42", folds.getFirst().projectId());
        assertEquals(7, captures.stream().filter(capture -> capture.operation()
                == io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Operation.AGENT_CHAT).count());
    }

    // --- due, and nothing more ----------------------------------------------------------------

    /** 59% is nothing: no note, no summary, nothing folded. */
    @Test
    void at_59_percent_nothing_is_noted_and_nothing_folds() {
        Model model = new Model().stepping(4).costing(10_000, 30_000, 45_000, 59_000, 20_000);
        String id = open(20);

        Outcome outcome = new Fixture(model, worker(20)).speakAndWait(id, "build the thing");

        assertEquals(Ending.ANSWERED, outcome.ending());
        assertEquals(0, model.summaries());
        assertEquals(List.of(), diagnosticsIn(id));
        assertTrue(kinds(id).stream().noneMatch(kind -> kind == EntryKind.TURN_SUMMARY));
    }

    /**
     * 60% marks a fold due, in the log and nowhere else: no summary is bought inside the turn and
     * nothing is said to the model.
     */
    @Test
    void at_60_percent_a_fold_becomes_due_and_nothing_runs_inside_the_turn() {
        Model model = new Model().stepping(4).costing(10_000, 30_000, 45_000, 60_000, 20_000);
        String id = open(20);

        new Fixture(model, worker(20)).speakAndWait(id, "build the thing");

        List<String> noted = diagnosticsIn(id);
        assertEquals(1, noted.size(), noted.toString());
        assertTrue(noted.get(0).startsWith("A fold became due in turn 1, against a threshold of"
                + " 60000."), noted.get(0));
        assertEquals(0, model.summaries(), "a due fold ran inside the turn");
        assertTrue(model.seen().stream().flatMap(List::stream)
                        .noneMatch(message -> message.content().contains("fold")),
                "the model was told something about a fold");
    }

    /**
     * A fold marked due inside a turn runs at the turn's end only if the conversation is still
     * over when the turn returns: a turn whose context grew past due while it worked and that
     * returned small folds nothing, and one that sent past due folds.
     */
    @Test
    void a_due_fold_runs_at_the_turns_end_only_if_the_conversation_is_still_over() {
        Model small = new Model().costing(5_000).stepping(0);
        String returnedSmall = open(20);
        Fixture fixture = new Fixture(small, worker(20));
        fixture.speakAndWait(returnedSmall, "first thing");
        small.stepping(3).costing(5_000, 30_000, 61_000, 5_000);
        fixture.speakAndWait(returnedSmall, "second thing");

        Model large = new Model().costing(5_000).stepping(0);
        String stillOver = open(20);
        Fixture other = new Fixture(large, worker(20));
        other.speakAndWait(stillOver, "first thing");
        large.stepping(1).costing(61_000, 62_000);
        other.speakAndWait(stillOver, "second thing");

        assertTrue(diagnosticsIn(returnedSmall).get(0).startsWith("A fold became due"),
                "the fixture never crossed due inside the turn");
        assertTrue(compactions.forConversation(returnedSmall).isEmpty(),
                "a turn that sent 5 000 and returned small was folded at its end");
        assertEquals(0, small.summaries());
        assertEquals(1, compactions.forConversation(stillOver).size(),
                "a turn that sent 61 000 against a due threshold of 60 000 was not folded");
    }

    // --- now ----------------------------------------------------------------------------------

    /**
     * A single turn crossing 80% at a tool boundary folds before its next model call: the older
     * steps become one summary where they were, the opening request and the last three whole
     * steps stay, every result still answers a call above it, and the log holds the fold.
     */
    @Test
    void a_single_turn_crossing_now_folds_before_its_next_model_call() {
        Model model = new Model().stepping(6)
                .costing(10_000, 20_000, 30_000, 40_000, 50_000, 85_000, 30_000);
        String id = open(20);

        Outcome outcome = new Fixture(model, worker(20)).speakAndWait(id, "build the thing");

        assertEquals(Ending.ANSWERED, outcome.ending(), outcome.text());
        assertEquals(1, model.summaries(), "one summariser call");
        List<ChatMessage> before = model.seen().get(5);
        List<ChatMessage> after = model.seen().get(6);
        assertEquals(List.of("call_1", "call_2", "call_3", "call_4", "call_5"), callsIn(before));
        assertEquals(List.of("call_4", "call_5", "call_6"), callsIn(after),
                "the last three whole steps are what stays");
        assertEquals("build the thing", after.get(1).content(), "the opening request stays");
        assertEquals(ChatMessage.Role.USER, after.get(2).role());
        assertEquals(Compaction.turnSeam(true, 1, 0, SUMMARY), after.get(2).content(),
                "the summary stands where the steps were, marked as the harness's");
        assertEquals(List.of(), unanswered(after), "a result lost its call, or a call its result");
        assertTrue(model.lastSummarising().get(1).content().contains("Turn 1, in progress"));
        assertTrue(model.lastSummarising().get(1).content().contains("{\"n\":3}"),
                "the folded steps were not in what the folder read");
        assertFalse(model.lastSummarising().get(1).content().contains("{\"n\":4}"),
                "a kept step was sent to be summarised");

        List<EntryRecord> log = entries.forConversation(id);
        EntryRecord summary = log.stream().filter(entry -> entry.kind() == EntryKind.TURN_SUMMARY)
                .findFirst().orElseThrow();
        assertEquals(SUMMARY, summary.content());
        Set<String> foldedCalls = Set.of("call_1", "call_2", "call_3");
        for (EntryRecord entry : log) {
            if (entry.kind() == EntryKind.ANSWER || entry.kind() == EntryKind.TOOL_RESULT) {
                boolean ofAFoldedStep = (entry.toolCallId() != null
                        && foldedCalls.contains(entry.toolCallId()))
                        || entry.toolCalls().stream()
                                .anyMatch(call -> foldedCalls.contains(call.id()));
                assertEquals(ofAFoldedStep, entry.supersededBy() != null,
                        "entry " + entry.ordinal() + " " + entry.kind() + " "
                                + entry.toolCalls() + entry.toolCallId());
            } else if (entry.kind() == EntryKind.UTTERANCE) {
                assertEquals(null, entry.supersededBy(), "the opening request was folded");
            }
        }
        assertTrue(diagnosticsIn(id).stream().anyMatch(noted -> noted.startsWith(
                "A fold ran inside turn 1, before its next model call, against a threshold of"
                        + " 80000: 3 earlier steps of this turn were folded")), diagnosticsIn(id)
                .toString());
    }

    /**
     * The log is the source: a projection rebuilt from it — the reading a resumed turn gets — is
     * the history the next model call was sent, and it stays that way after the turn.
     */
    @Test
    void a_projection_rebuilt_from_the_log_is_what_the_next_call_was_sent() {
        Model model = new Model().stepping(6)
                .costing(10_000, 20_000, 30_000, 40_000, 50_000, 85_000, 30_000);
        String id = open(20);
        Fixture fixture = new Fixture(model, worker(20));

        fixture.speakAndWait(id, "build the thing");

        List<ChatMessage> sent = model.seen().get(6);
        List<ChatMessage> rebuilt = fixture.compaction
                .resumedTranscriptFor(id, worker(20), 1).before();
        // The rebuilt reading ends with the answer the turn came to, which no call was sent.
        assertEquals(sent.subList(1, sent.size()), rebuilt.subList(0, rebuilt.size() - 1));
    }

    /** The kept tail is whole steps within about 30% of the window, weighed through the
     *  tokenizer: 8 steps of about 1 000 tokens against room of 6 000 keeps five. */
    @Test
    void the_kept_tail_is_the_whole_steps_that_fit_in_30_percent_of_the_window() {
        Model model = new Model().window(20_000).stepping(8).results(1_000)
                .costing(2_000, 4_000, 6_000, 8_000, 10_000, 12_000, 14_000, 18_500, 9_000);
        String id = open(20);
        Fixture fixture = new Fixture(model, worker(20));
        fixture.compaction.useTokenizer(ONE_PER_CHARACTER);

        fixture.speakAndWait(id, "build the thing");

        assertEquals(1, model.summaries());
        assertEquals(List.of("call_4", "call_5", "call_6", "call_7", "call_8"),
                callsIn(model.seen().get(8)));
    }

    /** And never fewer than three, however much they weigh. */
    @Test
    void the_kept_tail_is_at_least_the_last_three_steps_however_large() {
        Model model = new Model().window(20_000).stepping(6).results(5_000)
                .costing(2_000, 4_000, 6_000, 8_000, 10_000, 18_500, 9_000);
        String id = open(20);
        Fixture fixture = new Fixture(model, worker(20));
        fixture.compaction.useTokenizer(ONE_PER_CHARACTER);

        fixture.speakAndWait(id, "build the thing");

        assertEquals(1, model.summaries());
        assertEquals(List.of("call_4", "call_5", "call_6"), callsIn(model.seen().get(6)));
    }

    /** What a step appended is counted through the tokenizer, not left out: a turn whose last
     *  prompt measured 75 000 crosses 80 000 on its step's results. */
    @Test
    void what_a_step_appended_is_counted_through_the_tokenizer() {
        Model model = new Model().stepping(6).results(6_000)
                .costing(10_000, 20_000, 30_000, 40_000, 50_000, 75_000, 30_000);
        String id = open(20);
        Fixture fixture = new Fixture(model, worker(20));
        fixture.compaction.useTokenizer(ONE_PER_CHARACTER);

        fixture.speakAndWait(id, "build the thing");

        assertEquals(1, model.summaries(),
                "75 000 measured and 6 000 appended is past 80 000, and nothing folded");
    }

    /**
     * A fold inside the turn is not a step and not a call: a turn capped at seven steps and a
     * conversation allowed seven calls still answers on its seventh, with a summary bought in
     * between.
     */
    @Test
    void a_fold_inside_the_turn_counts_against_neither_the_step_cap_nor_the_budget() {
        Model model = new Model().stepping(6)
                .costing(10_000, 20_000, 30_000, 40_000, 50_000, 85_000, 30_000);
        String id = open(7);

        Outcome outcome = new Fixture(model, worker(7)).speakAndWait(id, "build the thing");

        assertEquals(1, model.summaries());
        assertEquals(Ending.ANSWERED, outcome.ending(), outcome.text());
        assertEquals(7, outcome.steps());
        assertEquals(7, outcome.modelCalls());
        assertEquals(7, conversations.find(id).orElseThrow().budget().spent());
    }

    /**
     * A summariser that fails inside the turn does not fail the turn: it goes on with its whole
     * history, the log says so, and the fold is not tried at every step after — once per
     * crossing, and again only after the turn has fallen below the threshold and crossed it
     * again.
     */
    @Test
    void a_summariser_failure_inside_the_turn_is_recorded_and_tried_once_per_crossing() {
        Model model = new Model().stepping(9).failingEverySummary()
                .costing(10_000, 20_000, 30_000, 40_000, 50_000, 85_000, 86_000, 70_000, 85_000,
                        40_000);
        String id = open(20);

        Outcome outcome = new Fixture(model, worker(20)).speakAndWait(id, "build the thing");

        assertEquals(Ending.ANSWERED, outcome.ending(), outcome.text());
        assertEquals(2, model.summaries(),
                "two crossings, two tries: not one per step over the threshold");
        assertEquals(9, callsIn(model.seen().get(9)).size(), "the turn lost steps to no fold");
        long failures = diagnosticsIn(id).stream()
                .filter(noted -> noted.startsWith("A fold was triggered inside turn 1")).count();
        assertEquals(2, failures, diagnosticsIn(id).toString());
        assertTrue(kinds(id).stream().noneMatch(kind -> kind == EntryKind.TURN_SUMMARY));
    }

    /**
     * A fold inside a turn takes the earlier turns no fold stands for yet, along with the
     * turn's older steps, and the fold at that turn's end has nothing left to take.
     */
    @Test
    void a_fold_inside_a_turn_takes_the_earlier_turns_and_leaves_the_turns_end_nothing() {
        Model model = new Model().costing(5_000).stepping(0);
        String id = open(20);
        Fixture fixture = new Fixture(model, worker(20));
        fixture.speakAndWait(id, "first thing");
        model.stepping(5).costing(65_000, 66_000, 67_000, 68_000, 85_000, 30_000);

        fixture.speakAndWait(id, "second thing");

        assertEquals(1, model.summaries(), "the turn's end bought a second summary");
        assertTrue(compactions.forConversation(id).isEmpty(), compactions.forConversation(id)
                .toString());
        assertEquals(1, entries.foldedThrough(id));
        List<ChatMessage> after = model.seen().get(model.seen().size() - 1);
        assertTrue(after.stream().noneMatch(message -> "first thing".equals(message.content())),
                "an earlier turn the fold took is still sent");
        assertTrue(after.stream().anyMatch(message -> message.content().startsWith(
                        "This message is from the harness that owns this conversation, not from"
                                + " the person: turns 1 to 1 of this conversation")),
                after.toString());
        assertEquals(List.of("call_3", "call_4", "call_5"), callsIn(after));
    }

    /**
     * A fold between turns reads a turn that folded inside itself as that turn read itself —
     * its in-turn summary in its own block — and covers the summary with the rest of the turn.
     */
    @Test
    void a_fold_between_turns_reads_an_in_turn_summary_in_its_turn_and_covers_it() {
        Model model = new Model().stepping(6)
                .costing(10_000, 20_000, 30_000, 40_000, 50_000, 85_000, 30_000);
        String id = open(20);
        Fixture fixture = new Fixture(model, worker(20));
        fixture.speakAndWait(id, "build the thing");
        model.stepping(0).costing(61_000);

        fixture.speakAndWait(id, "and now what");

        assertEquals(2, model.summaries(), "one fold inside turn one, one after turn two");
        String span = model.lastSummarising().get(1).content();
        assertTrue(span.contains("Turn 1\nSaid: build the thing\nSummarised: "), span);
        assertTrue(span.contains(SUMMARY), span);
        assertFalse(span.contains("and now what"), "the turn in progress was summarised");
        assertEquals(1, compactions.forConversation(id).size());
        List<ChatMessage> next = fixture.compaction.transcriptFor(id, worker(20),
                Speaker.person(null)).before();
        assertEquals(List.of("and now what", "done"), next.stream()
                .filter(message -> message.role() != ChatMessage.Role.SYSTEM)
                .map(ChatMessage::content).toList(),
                "turn one, its in-turn summary included, is behind the seam");
    }

    /** An estimate is weighed with a third again, and a measurement as it is. */
    @Test
    void an_estimate_is_weighed_with_room_for_its_error() {
        assertEquals(400, Compaction.withHeadroom(TokenCount.estimated(300, "a guess")));
        assertEquals(402, Compaction.withHeadroom(TokenCount.estimated(301, "a guess")),
                "401.3 rounds up: an estimate is never let down");
        assertEquals(300, Compaction.withHeadroom(TokenCount.measured(300, "counted")));
    }

    // --- fixtures -----------------------------------------------------------------------------

    /** A tokenizer that measures one token per character, so a test's arithmetic is its own. */
    private static final Tokenizer ONE_PER_CHARACTER = new Tokenizer() {
        @Override
        public TokenCount count(String text) {
            return TokenCount.measured(text == null ? 0 : text.length(), "one per character");
        }

        @Override
        public String describe() {
            return "one per character";
        }
    };

    private String open(int calls) {
        return conversations.open(PAYMENTS, Budget.of(calls)).id();
    }

    private List<String> diagnosticsIn(String id) {
        return entries.forConversation(id).stream()
                .filter(entry -> entry.kind() == EntryKind.DIAGNOSTIC)
                .map(EntryRecord::content)
                .toList();
    }

    private List<EntryKind> kinds(String id) {
        return entries.forConversation(id).stream().map(EntryRecord::kind).toList();
    }

    private static List<String> callsIn(List<ChatMessage> messages) {
        return messages.stream().flatMap(message -> message.toolCalls().stream())
                .map(ToolCall::id).toList();
    }

    /** Calls declared with no result after them, and results with no call before them. */
    private static List<String> unanswered(List<ChatMessage> messages) {
        List<String> wrong = new ArrayList<>();
        Set<String> open = new HashSet<>();
        for (ChatMessage message : messages) {
            if (message.role() == ChatMessage.Role.TOOL) {
                if (!open.remove(message.toolCallId())) {
                    wrong.add("result " + message.toolCallId());
                }
            } else {
                open.forEach(call -> wrong.add("call " + call));
                open.clear();
                message.toolCalls().forEach(call -> open.add(call.id()));
            }
        }
        open.forEach(call -> wrong.add("call " + call));
        return wrong;
    }

    private static AgentDefinition worker(int maxTurns) {
        return new AgentDefinition("worker", "a fixture worker", "fast",
                List.of(TOOL, ResultTools.READ_NAME, ResultTools.LIST_NAME),
                List.of(), List.of(), maxTurns, 40, PROMPT);
    }

    private static AgentDefinition folder() {
        return new AgentDefinition(Compaction.FOLDER, "a fixture folder", FOLDING,
                AgentDefinition.DEFAULT_INTENT, Sampling.NONE, List.of(), List.of(), List.of(),
                1, 1, FOLDER_PROMPT, false, false);
    }

    /** A tool that answers with as many characters as the model is scripted to be shown. */
    private record Fetchy(Model model) implements AgentTool {

        @Override
        public ToolSchema schema() {
            return new ToolSchema(TOOL, "fetches a thing",
                    Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public String run(String argumentsJson, Home home) {
            return "x".repeat(model.resultChars);
        }
    }

    private final class Fixture {

        private final JobStore jobs;
        private final Turn turn;
        private final AgentDefinition definition;
        private final Compaction compaction;

        Fixture(Model model, AgentDefinition definition) {
            this(model, definition, null, null);
        }

        Fixture(Model model, AgentDefinition definition,
                io.aeyer.plowshare.server.llm.accounting.UsageAttribution owner,
                java.util.List<io.aeyer.plowshare.server.llm.accounting.UsageAttribution> captures) {
            this.definition = definition;
            LlmDispatcher models = new LlmDispatcher(
                    List.of(new LlmPool("scripted", List.of("model-fast", "model-folding"),
                            Map.of("fast", "model-fast", FOLDING, "model-folding"),
                            4, 1, Duration.ofSeconds(5), model)),
                    new NoOpTokenLedger(), name -> "", (pool, wire, specifier, lane, attribution) -> {
                        if (captures != null) { captures.add(attribution); }
                        return io.aeyer.plowshare.server.llm.dispatch.InferenceObserver.NONE;
                    });
            JobRuntime runtime = new JobRuntime(models, List.of(new Fetchy(model)), null,
                    (home, grants, sessionId, account) -> List.<FileProvider>of());
            if (owner != null) {
                runtime.useRunUsage((home, transcript, agent, account) -> {
                    transcript.accounted(owner);
                    return new AttributedTranscript(transcript, owner,
                            io.aeyer.plowshare.server.llm.accounting.UsageAttribution.LEGACY);
                });
            }
            this.jobs = new JobStore(runtime);
            this.compaction = new Compaction(models, FoldInsideATurnTest::folder, turns,
                    compactions, entries, 1_000_000, Runnable::run);
            this.turn = new Turn(jobs, conversations, turns, compaction);
            opened.add(jobs);
            folding.add(compaction);
        }

        Outcome speakAndWait(String conversation, String utterance) {
            String job = turn.speak(conversation, definition, utterance, null);
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
    }

    /**
     * A model that asks for {@link #TOOL} a scripted number of times, with arguments that differ
     * every time, then answers; reports the prompt counts it is scripted to, and two generated
     * tokens a call; and answers a fold — told apart by {@code Compaction.FROM_THE_HARNESS} —
     * with {@link #SUMMARY}, or with nothing.
     */
    private static final class Model implements LlmTransport {

        private int window = WINDOW;
        private int stepsLeft;
        private int called;
        private volatile int resultChars = 10;
        private boolean failSummaries;
        private final Deque<Integer> costs = new ArrayDeque<>();
        private final List<List<ChatMessage>> seen =
                Collections.synchronizedList(new ArrayList<>());
        private final List<List<ChatMessage>> summarising =
                Collections.synchronizedList(new ArrayList<>());

        Model window(int tokens) {
            this.window = tokens;
            return this;
        }

        Model stepping(int steps) {
            this.stepsLeft = steps;
            return this;
        }

        Model results(int chars) {
            this.resultChars = chars;
            return this;
        }

        Model costing(int... perCall) {
            costs.clear();
            for (int cost : perCall) {
                costs.add(cost);
            }
            return this;
        }

        Model failingEverySummary() {
            this.failSummaries = true;
            return this;
        }

        int summaries() {
            return summarising.size();
        }

        List<ChatMessage> lastSummarising() {
            return summarising.get(summarising.size() - 1);
        }

        List<List<ChatMessage>> seen() {
            synchronized (seen) {
                return List.copyOf(seen);
            }
        }

        @Override
        public OptionalInt contextLength(String wireModel) {
            return OptionalInt.of(window);
        }

        @Override
        public OptionalInt compactionThreshold(String wireModel) {
            return window == WINDOW ? OptionalInt.of(DUE) : OptionalInt.empty();
        }

        @Override
        public OptionalInt compactionNowThreshold(String wireModel) {
            return window == WINDOW ? OptionalInt.of(NOW) : OptionalInt.empty();
        }

        @Override
        public String poolName() {
            return "scripted";
        }

        @Override
        public synchronized Completion complete(String wireModel, List<ChatMessage> messages,
                Sampling sampling, List<ToolSchema> tools) {
            if (messages.get(messages.size() - 1).content()
                    .startsWith(Compaction.FROM_THE_HARNESS)) {
                summarising.add(List.copyOf(messages));
                return new Completion(failSummaries ? "" : SUMMARY, "stop", TokenUsage.UNKNOWN,
                        List.of());
            }
            seen.add(List.copyOf(messages));
            TokenUsage usage = TokenUsage.of(costs.isEmpty() ? 64 : costs.poll(), 2, null);
            if (stepsLeft > 0) {
                stepsLeft--;
                called++;
                return new Completion("", "tool_calls", usage, List.of(
                        new ToolCall("call_" + called, TOOL, "{\"n\":" + called + "}")));
            }
            called = 0;
            return new Completion("done", "stop", usage, List.of());
        }

        @Override
        public Completion stream(String wireModel, List<ChatMessage> messages, Sampling sampling,
                List<ToolSchema> tools, Deltas sink, BooleanSupplier abandoned) {
            Completion streamed = complete(wireModel, messages, sampling, tools);
            if (abandoned.getAsBoolean()) {
                throw new CallerAbandonedException(poolName());
            }
            return streamed;
        }

        @Override
        public Embeddings embed(String wireModel, List<String> input) {
            throw new UnsupportedOperationException("a turn does not embed");
        }

        @Override
        public void close() {
        }
    }
}
