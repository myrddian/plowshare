package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.archive.CompactionRecord;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A request is a projection of the log, and it is the request the {@code turns} table used to
 * build.
 *
 * <h2>What this file is for</h2>
 *
 * <p>{@code Compaction} reads the log. This is the instrument that had to go green <em>before</em>
 * it did, because it is the difference between a refactor and a rewrite: {@link
 * #a_projected_request_is_the_request_the_turns_table_builds_across_a_fold} asserts that a
 * conversation with a fold in it reaches the model as the same request either way.
 *
 * <p><b>The other side of that comparison now lives here.</b> It is {@link
 * #whatTheTurnsTableBuilds}, a frozen copy of the method {@code Compaction} deleted when it
 * switched over, and it is a copy on purpose: once the shipped path reads the log, calling the
 * shipped path for "what the old path built" asks one witness the same question twice and it agrees
 * with itself whatever it does. That is the one way this test could have gone quietly vacuous, and
 * it is the reason the copy is worth its upkeep.
 *
 * <h2>What the equivalence covers, and what it does not</h2>
 *
 * <p><b>It covers a fold</b>, which is the case the design asks for by name and the one that is not
 * merely a copy: the folded turns are skipped, the summary stands in their place, and the seam
 * sentence names how far back it reaches.
 *
 * <p><b>It holds for conversations whose turns made no tool calls, and the design did not say
 * so.</b> The {@code turns} table renders a turn as two messages — what was said and what came back
 * — because it has no column for a turn's working. The log has the working. So an
 * <em>unnarrowed</em> projection of a conversation whose turns used tools is a strict superset of
 * what the turns table builds, by exactly that working, and the two cannot be equal.
 *
 * <p><b>That divergence was the decision the switch had to take, and it was taken by narrowing
 * rather than by replaying.</b> {@code Compaction.whatWasSaidAndWhatCameBack} drops a turn's
 * working before projecting, so what a model reads did not change; the argument is in that method
 * and the short of it is that this class's headroom arithmetic is written against the working not
 * being replayed, and inverting it under a refactor would turn a measurement that over-states into
 * one that under-states. {@link
 * #a_turn_that_used_a_tool_projects_its_working_and_the_current_path_drops_it} still pins both
 * sides of it, so replaying the working later is a red test here rather than a prompt that quietly
 * grew in production.
 *
 * <p><b>The comparison is at the request and not at the message list</b>, and that is forced by the
 * log being append-only. A fold's summary is appended when the fold happens, which is
 * <em>after</em> the entries it covers, so a projection in log order ends with the seam where
 * {@code Compaction} begins with it. There is no way to put it earlier without renumbering the log,
 * and there is no need: {@code JobRuntime.oneSystemMessageFirst} lifts every system message to
 * index zero and merges it with the agent's prompt, which it already had to do for {@code
 * Compaction}'s seam and which {@code ChatRequest} requires of the result. Comparing the requests
 * compares the thing a model actually receives.
 *
 * <h2>No test here reaches a model</h2>
 *
 * <p>{@link Scripted} answers from a queue and reports whatever context length a test asked for. No
 * socket is opened and no port is bound; the only remote thing in the file is the Postgres
 * container Testcontainers chose a port for.
 */
@Tag("full-db")
@Testcontainers
class ProjectionTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  /**
   * What this fixture's model reports being loaded at, small enough that two turns of prose can
   * cross a third of it. {@code CompactionTest} uses the same number for the same reason.
   */
  private static final int LOADED_CONTEXT = 1000;

  /**
   * The two prompt costs that make the third turn fold. The arithmetic they have to satisfy is
   * stated where they are used.
   */
  private static final int FIRST_TURN_COST = 400;

  private static final int SECOND_TURN_COST = 900;

  private static final Home PAYMENTS = Home.of("payments");

  private static final String SUMMARY = "They discussed the deploy and agreed to roll back.";

  private static final String AGENT_PROMPT = "You talk.";

  /**
   * Every tool name any fixture agent declares; see {@code DelegationTest} on why this is written
   * out rather than taken from a runtime.
   */
  private static final Set<String> FIXTURE_TOOLS =
      Set.of("probe_read", "probe_write", AgentRegistry.AGENT_RUN, MemoryTools.WRITE_NAME);

  private static JdbcTemplate jdbc;

  private ConversationStore conversations;
  private TurnStore turns;
  private CompactionStore compactions;
  private EntryStore entries;

  private final List<JobStore> opened = new ArrayList<>();

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(dataSource()).load().migrate();
    jdbc = new JdbcTemplate(dataSource());
  }

  @BeforeEach
  void freshConversations() {
    // All four, because of the keys between them: turns references
    // conversations, compactions references turns, and entries references
    // conversations and itself. Postgres refuses to truncate a referenced
    // table unless its referrer goes with it. Named rather than CASCADE, so
    // a table that joins this graph later cannot be emptied by a fixture
    // that does not know about it. board_topics, board_messages and
    // board_seats are here for the same reason: V74 gave each a foreign key
    // into conversations. firings is a further hop out, through its V74
    // foreign key into board_topics, and user_inbox a hop past that,
    // through its pre-existing V40 foreign key into firings.
    jdbc.execute(
        "TRUNCATE TABLE board_message_route_bindings, outgoing_work, message_external_tasks, message_external_contexts, board_message_routes, board_message_instances, board_post_receipts, skill_executions, command_invocations, orchestration_start_receipts, orchestration_script_steps, board_reads, board_notices, board_decisions, digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, conversations, turns, compactions, entries, citations, orchestrations, orchestration_messages, board_topics, board_messages, board_seats, firings, user_inbox");
    conversations = new ConversationStore(jdbc);
    turns = new TurnStore(jdbc);
    compactions = new CompactionStore(jdbc);
    entries = new EntryStore(jdbc);
  }

  @AfterEach
  void stopEveryRun() {
    opened.forEach(JobStore::close);
    opened.clear();
  }

  // --- the equivalence -----------------------------------------------------------

  /**
   * The deliverable. A request built from the log is the request the current path builds, for a
   * conversation that has folded.
   *
   * <h2>How the fold is reached</h2>
   *
   * <p>Two turns are spoken, costing {@link #FIRST_TURN_COST} and {@link #SECOND_TURN_COST} tokens.
   * Before a third, {@code Compaction} asks whether the last measurement plus this conversation's
   * own headroom would exceed the size it folds at: 900 plus the 500 this conversation grew by is
   * past a third of a 1000-token window, so it folds, reaching through the turn before the last —
   * turn one.
   *
   * <p>The third turn is not spoken. Its transcript is asked for {@code before()} directly, which
   * is exactly what a third turn would do first and is where the fold happens; stopping there is
   * what keeps the two sides comparable, since a turn that ran would append its own entries to one
   * side only.
   *
   * <h2>What is compared</h2>
   *
   * <p>The whole request, assembled the way production assembles one: {@code oneSystemMessageFirst}
   * over the history, then the utterance. Both sides go through {@link ChatRequest}, so both are
   * subject to its rules — one system message and it is first, every tool result answering a
   * declared call — and a projection that produced a shape no endpoint accepts fails here rather
   * than on a wire.
   *
   * <p><b>Three assertions guard against a green run that proved nothing.</b> A fold really
   * happened, the log really holds a summary, and the request really carries more than the agent's
   * prompt and the utterance. Without them a bug that emptied both sides would pass.
   */
  @Test
  void a_projected_request_is_the_request_the_turns_table_builds_across_a_fold() {
    Scripted model = new Scripted(LOADED_CONTEXT).costing(FIRST_TURN_COST, SECOND_TURN_COST);
    Fixture fixture = new Fixture(model);
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(20));
    String id = conversation.id();

    fixture.speakAndWait(id, "what broke the deploy?");
    fixture.speakAndWait(id, "and who was on call?");

    // The third turn's opening, which is where the fold falls. This is the
    // shipped path and it now reads the log.
    List<ChatMessage> shipped =
        fixture.compaction.transcriptFor(id, fixture.definition, Speaker.person(null)).before();

    assertEquals(
        1,
        compactions.forConversation(id).size(),
        "the fixture is meant to fold exactly once before the third turn");
    assertTrue(
        log(id).stream().anyMatch(entry -> entry.kind() == EntryKind.SUMMARY),
        "the fold left no summary in the log, so this compares two unfolded histories");

    String utterance = "and what did we tell the customer?";
    ChatRequest fromTurns =
        requestOver(
            fixture.definition,
            whatTheTurnsTableBuilds(compactions.latest(id), turns.forConversation(id)),
            utterance);
    ChatRequest fromLog = requestOver(fixture.definition, shipped, utterance);
    ChatRequest fromTheWholeLog =
        requestOver(fixture.definition, Projection.of(log(id), false), utterance);

    assertTrue(
        fromTurns.messages().size() > 2,
        "a request of just a system message and an utterance would make this vacuous");
    assertEquals(
        fromTurns.messages(),
        fromLog.messages(),
        "the request the shipped path builds is not the request the turns table builds");
    assertEquals(
        fromTurns.messages(),
        fromTheWholeLog.messages(),
        "this conversation used no tools, so even the unnarrowed projection of its log"
            + " must equal what the turns table builds");
  }

  /**
   * What the equivalence above does not cover, pinned so that it cannot be discovered later.
   *
   * <p>{@code Compaction} renders a turn as what was said and what came back. It used to say "tool
   * calls and tool results are <b>not</b> replayed. They are a turn's working", and it dropped
   * both. It now <em>substitutes</em>: the result is replaced by a reference naming the tool, the
   * size and a handle that reads it back. The log holds the result itself, because the whole point
   * of it is that everything model-visible is written down. So the two still disagree, and they
   * disagree by exactly the substitution.
   *
   * <p>This is not a bug on either side. It is the change the log makes, and the slice that
   * switched over owned the decision about it.
   *
   * <p><b>Both halves are still asserted here.</b> The unnarrowed projection carries what the tool
   * returned; the shipped path carries a reference to it. A change that quietly replayed the whole
   * result into every later prompt — the growth the fold arithmetic is written against — arrives as
   * a red assertion rather than as a prompt that grew in production.
   *
   * <p>The probe returns something long on purpose. A result shorter than the line describing it is
   * kept as itself, which is the right behaviour and is pinned in {@code CompactionTest}; a fixture
   * that returned eleven characters would make the two paths agree and would assert nothing.
   */
  @Test
  void a_turn_that_used_a_tool_projects_its_working_and_the_current_path_references_it() {
    String roots = "roots: /tmp and a great many other things besides. ".repeat(10);
    Scripted model = new Scripted(Scripted.NO_CONTEXT_LENGTH).asksForATool();
    Fixture fixture = new Fixture(model, new Probe(home -> roots));
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(20));
    String id = conversation.id();

    assertEquals(Ending.ANSWERED, fixture.speakAndWait(id, "what broke the deploy?").ending());

    List<ChatMessage> built =
        fixture.compaction.transcriptFor(id, fixture.definition, Speaker.person(null)).before();
    List<ChatMessage> projected = Projection.of(log(id), false);

    // What the tool ACTUALLY returned, and not merely that some tool message
    // is there. Measured: an implementation that recorded no tool result at
    // all passed the weaker assertion, because the projection then pairs the
    // unanswered call and produces a tool message of its own. Two mechanisms
    // put a tool message in a request and only one of them is the log
    // holding a turn's working.
    assertEquals(
        List.of(roots),
        projected.stream()
            .filter(said -> said.role() == ChatMessage.Role.TOOL)
            .map(ChatMessage::content)
            .toList(),
        "the log holds the working, so a projection of it carries what the tool"
            + " returned rather than a stand-in for a call nothing answered");
    List<String> shipped =
        built.stream()
            .filter(said -> said.role() == ChatMessage.Role.TOOL)
            .map(ChatMessage::content)
            .toList();
    assertEquals(1, shipped.size(), "the result the turn got did not come back at all: " + built);
    assertFalse(
        shipped.get(0).contains(roots),
        "the shipped path replayed the whole result into a later turn's prompt: " + shipped.get(0));
    assertTrue(shipped.get(0).contains(ResultTools.READ_NAME), shipped.get(0));
    assertFalse(
        built.equals(projected),
        "these two are equal only for a conversation whose turns used no tools, which"
            + " is what the equivalence test above is limited to");
  }

  // --- the dangling call ---------------------------------------------------------

  /**
   * A turn that died inside a delegated call still projects a conversation every declared call is
   * answered in.
   *
   * <p>{@code foreman} hands work to {@code dogged}, and the child's first model call throws — so
   * the child ends {@code UNAVAILABLE}, {@code AgentRunTool} raises {@code SubAgentFailed} rather
   * than rendering a result, and {@code JobRuntime} returns {@code SUB_AGENT_FAILED} before
   * appending anything. The parent's log therefore holds an answer declaring {@code agent_run} and
   * nothing answering it.
   *
   * <p>The assertion is the invariant, and it is asserted over the projection rather than over a
   * hand-built list: every call any assistant message declares has a tool message for it.
   */
  @Test
  void a_run_that_died_inside_a_delegated_call_projects_an_answer_for_it() throws Exception {
    Scripted model =
        new Scripted(Scripted.NO_CONTEXT_LENGTH)
            .then(
                () ->
                    asking(
                        "handing it over",
                        new ToolCall("c1", AgentRegistry.AGENT_RUN, delegate("dogged", "go"))))
            .thenAlways(
                () -> {
                  throw new LlmException("pool 'scripted' is shut down and cannot take work");
                });
    Fixture fixture = new Fixture(model, registry(), "foreman");
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(20));
    String id = conversation.id();

    Outcome outcome = fixture.speakAndWait(id, "hand this to dogged");

    assertEquals(Ending.SUB_AGENT_FAILED, outcome.ending());
    assertTrue(
        declaredAndUnanswered(log(id)).contains("c1"),
        "the fixture is meant to leave a declared call with no result in the log");
    assertEquals(
        List.of(),
        unansweredCallsIn(Projection.of(log(id), false)),
        "the projection left a declared call with nothing answering it");
  }

  /**
   * The same invariant, reached by the other ending that returns before appending results.
   *
   * <p>Two paths and not one, because they are two clauses in {@code JobRuntime}'s tool dispatch
   * and only one of them is delegation. A projection repaired on the {@code SUB_AGENT_FAILED} path
   * alone would pass the test above and leave a real conversation dangling.
   */
  @Test
  void a_run_whose_session_went_away_mid_call_projects_an_answer_for_it() throws Exception {
    Scripted model =
        new Scripted(Scripted.NO_CONTEXT_LENGTH)
            .then(() -> asking("reading a file", new ToolCall("c9", "probe_read", "{}")));
    Fixture fixture =
        new Fixture(
            model,
            registry(),
            "dogged",
            new Probe(
                home -> {
                  throw new SessionGoneException("the client session went away");
                }));
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(20));
    String id = conversation.id();

    Outcome outcome = fixture.speakAndWait(id, "read something");

    assertEquals(Ending.SESSION_GONE, outcome.ending());
    assertTrue(
        declaredAndUnanswered(log(id)).contains("c9"),
        "the fixture is meant to leave a declared call with no result in the log");
    assertEquals(
        List.of(),
        unansweredCallsIn(Projection.of(log(id), false)),
        "the projection left a declared call with nothing answering it");
  }

  /**
   * A call the model sent without an id is logged under the id the runtime sent back, not the one
   * it received.
   *
   * <p>{@code JobRuntime} mints a stand-in for a blank id and puts it on <em>both</em> halves — the
   * assistant turn declares it and the result answers it — because filing a result under an id the
   * assistant turn does not declare is a conversation a strict endpoint rejects outright. A log
   * that recorded {@code Completion.toolCalls()} instead of what was sent would record the blank id
   * on the answer and the minted one on the result, so the projection would carry a result nothing
   * declared <em>and</em> a declared call with a blank id that {@link ChatMessage#tool} cannot even
   * be paired against.
   *
   * <p><b>Measured, and this test exists because it was not.</b> Recording the received ids passed
   * every other test in this file; nothing else in the suite reaches a blank id at all, because
   * {@code OpenAiTransport} refuses one before it can, so it is a fixture-only path and it was
   * uncovered.
   */
  @Test
  void a_call_that_arrived_with_no_id_is_logged_under_the_one_that_was_sent() {
    Scripted model =
        new Scripted(Scripted.NO_CONTEXT_LENGTH)
            .then(() -> asking("", new ToolCall("", "probe_read", "{}")));
    Fixture fixture = new Fixture(model, new Probe(home -> "roots: /tmp"));
    String id = conversations.open(PAYMENTS, Budget.of(20)).id();

    assertEquals(Ending.ANSWERED, fixture.speakAndWait(id, "read something").ending());

    List<ChatMessage> projected = Projection.of(log(id), false);
    assertEquals(List.of(), unansweredCallsIn(projected));
    assertEquals(
        List.of("call_1"),
        projected.stream()
            .filter(said -> said.role() == ChatMessage.Role.TOOL)
            .map(ChatMessage::toolCallId)
            .toList(),
        "the result was filed under an id the answer does not declare");
    // And the whole thing is a request a strict endpoint would accept, which
    // is what requireEveryToolResultAnswersACall is checking on its way past.
    assertEquals(
        projected.size() + 2,
        requestOver(fixture.definition, projected, "and now?").messages().size());
  }

  /**
   * What a model is told in place of the result that never came, verbatim.
   *
   * <p>Pinned because it is <b>model-visible content this class owns</b>. It is the only sentence
   * in {@code Projection} a model reads, nothing else in this server pins it — {@code
   * ModelSurfaceTest} records each agent's system message and tool schemas and no tool result at
   * all — so an edit to it would otherwise arrive as a green build rather than as a diff of what a
   * model reads.
   */
  @Test
  void the_answer_a_never_completed_call_gets_is_what_it_is() {
    String id = conversations.open(PAYMENTS, Budget.of(20)).id();
    entries.append(id, 1, LoggedEntry.answer("", List.of(new ToolCall("c1", "probe_read", "{}"))));

    List<ChatMessage> projected = Projection.of(log(id), false);

    assertEquals(2, projected.size());
    assertEquals(ChatMessage.Role.TOOL, projected.get(1).role());
    assertEquals("c1", projected.get(1).toolCallId());
    assertEquals(
        "This call did not complete. The run that made it stopped before this tool"
            + " returned anything, so there is no result here: nothing was learned"
            + " from this call, and nothing about what it would have returned can be"
            + " assumed. This sentence is from the harness that owns this"
            + " conversation and is not the tool's answer.",
        projected.get(1).content());
  }

  /**
   * Why the other answer was not available, measured rather than argued.
   *
   * <p>The alternative to pairing is dropping the calls, so that no unanswered call sits in the
   * history. On the shape that matters — an assistant turn that is entirely tool calls, which is
   * what a model asking for a tool without saying anything produces — there is nothing left to
   * keep: {@code ChatMessage} refuses a message with neither content nor tool calls, on the grounds
   * that it is a turn that did not happen. So "drop the calls" is really "drop the message", and
   * with it whatever the model said alongside them on the turns where it said something.
   */
  @Test
  void dropping_a_dying_turns_calls_would_have_to_drop_the_message_too() {
    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> ChatMessage.assistant("", List.of()));

    assertTrue(refused.getMessage().contains("says nothing"), refused.getMessage());
  }

  /**
   * Nothing on this side of the wire would have caught a dangling call, which is why the projection
   * has to.
   *
   * <p><b>The design assumed the opposite</b> — that {@code ChatRequest}'s constructor refuses a
   * request carrying a declared call with no result. It does not, and its own javadoc says so in as
   * many words: "<em>The converse is deliberately not enforced</em> ... It is left representable
   * knowingly rather than by omission." So a projection that produced one would pass every check
   * this server has and be refused by the endpoint, on a later request, for a reason nothing in the
   * message explains.
   *
   * <p>This test is here to fail if that ever changes. If {@code ChatRequest} grows the rule, the
   * projection's repair is still right — a model reading a declared call with no answer is being
   * shown a turn that did not happen — but the argument for it changes, and the argument is in
   * {@code Projection}'s javadoc.
   */
  @Test
  void a_chat_request_does_not_refuse_a_call_that_nothing_answers() {
    ChatRequest built =
        ChatRequest.of(
            "fast",
            List.of(
                ChatMessage.user("do it"),
                ChatMessage.assistant("", List.of(new ToolCall("c1", "probe_read", "{}")))));

    assertEquals(
        2,
        built.messages().size(),
        "ChatRequest accepted a declared call with nothing answering it, which is what"
            + " makes the repair the projection's to make");
  }

  // --- recorded and invisible ------------------------------------------------------

  /**
   * A failed attempt is recorded and never reaches a request.
   *
   * <p>Per kind and not in aggregate, on the design's own instruction: an aggregate assertion goes
   * green when a kind stops being written at all. Each of the three tests below writes an entry of
   * one kind between two that do project, then asserts that the two are all that comes out.
   */
  @Test
  void an_attempt_that_failed_is_recorded_and_never_projected() {
    assertRecordedAndInvisible(
        LoggedEntry.attemptFailed(Ending.SESSION_GONE, "the session went away"));
  }

  @Test
  void a_runtime_note_is_recorded_and_never_projected() {
    assertRecordedAndInvisible(LoggedEntry.runtimeNote("[Runtime note] you keep doing that"));
  }

  /**
   * Built through the canonical constructor and not a factory, because there is no factory: nothing
   * in this server writes a plan yet, and a {@code LoggedEntry.plan(...)} with no caller would be a
   * shape invented for its own test. The kind is classified so that the shape is reserved, which is
   * exactly what this asserts about it.
   */
  @Test
  void a_plan_is_recorded_and_never_projected() {
    assertRecordedAndInvisible(
        new LoggedEntry(EntryKind.PLAN, "step one: read the file", null, List.of()));
  }

  /**
   * A diagnostic is recorded and never reaches a request.
   *
   * <p>Built through the canonical constructor for the reason the plan above is: nothing writes one
   * yet, and a {@code LoggedEntry.diagnostic(...)} with no caller would be a shape invented for its
   * own test. What is being reserved is a place to note what the harness did to a conversation —
   * "compaction triggered" — between the entries it did it between, and the whole of the
   * reservation is that a model never reads it.
   */
  @Test
  void a_diagnostic_is_recorded_and_never_projected() {
    assertRecordedAndInvisible(
        new LoggedEntry(
            EntryKind.DIAGNOSTIC, "compaction triggered at 12 000 tokens", null, List.of()));
  }

  /**
   * A turn that really stopped writes the entry the test above writes by hand.
   *
   * <p>The pair the design asks for. The per-kind assertions prove the projection ignores a kind;
   * this proves the kind is one a run actually produces, so those assertions are not passing over
   * something nothing writes.
   */
  @Test
  void a_turn_that_stopped_records_that_it_did() throws Exception {
    Scripted model =
        new Scripted(Scripted.NO_CONTEXT_LENGTH)
            .then(() -> asking("reading a file", new ToolCall("c9", "probe_read", "{}")));
    Fixture fixture =
        new Fixture(
            model,
            registry(),
            "dogged",
            new Probe(
                home -> {
                  throw new SessionGoneException("the client session went away");
                }));
    String id = conversations.open(PAYMENTS, Budget.of(20)).id();

    fixture.speakAndWait(id, "read something");

    EntryRecord failed =
        log(id).stream()
            .filter(entry -> entry.kind() == EntryKind.ATTEMPT_FAILED)
            .findFirst()
            .orElseThrow(() -> new AssertionError("a stopped turn recorded no failed attempt"));
    assertTrue(failed.content().startsWith(Ending.SESSION_GONE.name()), failed.content());
    assertFalse(
        Projection.of(log(id), false).stream()
            .anyMatch(said -> said.content().contains(Ending.SESSION_GONE.name())),
        "the ending reached a request, and an attempt that never reached the model must"
            + " not be replayed as though it had");
  }

  /**
   * Every kind that projects has a role and every kind that does not has none, asked of the enum
   * rather than of a projection.
   *
   * <p>The guarantee is that an <em>unclassified</em> kind does not project, and this is what makes
   * it checkable: a constant added without a decision has no role, so it is invisible. The counted
   * half — that each kind is writable at all — is {@code EntryStoreTest}'s.
   */
  @Test
  void a_kind_projects_only_if_it_was_given_a_role() {
    for (EntryKind kind : EntryKind.values()) {
      assertEquals(
          kind.role().isPresent(),
          kind.projects(),
          kind + " disagrees with itself about whether it reaches a model");
    }
    assertEquals(
        List.of(
            EntryKind.UTTERANCE,
            EntryKind.ANSWER,
            EntryKind.TOOL_RESULT,
            EntryKind.SUMMARY,
            EntryKind.TURN_SUMMARY,
            EntryKind.NOTICE),
        java.util.Arrays.stream(EntryKind.values()).filter(EntryKind::projects).toList(),
        "the set of kinds that reach a model has changed; that is a decision about what"
            + " a model is shown and belongs in review, not in a green build");
  }

  // --- supersession -----------------------------------------------------------------

  /**
   * Two summaries that both still stand reach a model oldest-first, whichever of them was written
   * down first.
   *
   * <p>A fold is becoming asynchronous — a summary can be appended after entries from a later turn
   * are already in the log — so <b>arrival order stops being conversation order</b>, and a
   * projection that took the rows in the order they landed would put the later-reaching summary in
   * front of the earlier one. That is a history the model reads backwards: it is told turns 1 to 2
   * were folded and then, underneath, that turns 1 to 1 were.
   *
   * <p>The fixture is that race and not a re-telling of it. The summary reaching through turn two
   * is appended <em>first</em>, which is a thing only an asynchronous fold can do, and the ordering
   * that puts it back where it belongs is {@code turn_ordinal} — the last turn a summary stands
   * for.
   *
   * <p>Written at the log and not through a fold, because a fold cannot produce this arrival order
   * yet. What is being asserted is that the projection survives it when one can.
   */
  @Test
  void two_live_summaries_project_in_reach_order_however_they_arrived() {
    String id = conversations.open(PAYMENTS, Budget.of(20)).id();
    entries.append(id, 1, LoggedEntry.utterance("what broke the deploy?", Speaker.person(null)));
    entries.append(id, 2, LoggedEntry.utterance("and who was on call?", Speaker.person(null)));
    entries.append(id, 2, LoggedEntry.summary("the second half"));
    entries.append(id, 1, LoggedEntry.summary("the first half"));

    List<String> seams =
        Projection.of(log(id), false).stream()
            .filter(message -> message.role() == ChatMessage.Role.SYSTEM)
            .map(ChatMessage::content)
            .toList();

    assertEquals(2, seams.size(), "both folds still stand and both should be shown: " + seams);
    assertTrue(
        seams.get(0).contains("the first half"),
        "the later-reaching summary was projected first, so the model reads the"
            + " conversation's own history out of order: "
            + seams);
    assertTrue(seams.get(1).contains("the second half"), seams.toString());
  }

  // --- a fold inside a turn (spec 2026-09-30-fold-at-60-and-80 §1-§2) --------------------

  /**
   * An in-turn summary is read where the steps it stands for were: after the turn's opening
   * request, which is kept word for word, and before the steps kept whole — although it was
   * appended after them. Every tool message still answers a call made above it.
   */
  @Test
  void an_in_turn_summary_is_read_between_the_request_and_the_steps_kept() {
    String id = conversations.open(PAYMENTS, Budget.of(20)).id();
    entries.append(id, 1, LoggedEntry.utterance("build the thing", Speaker.person(null)));
    EntryRecord first =
        entries.append(
            id,
            1,
            LoggedEntry.answer("", List.of(new ToolCall("a", "file_read", "{\"path\":\"a\"}"))));
    EntryRecord firstResult = entries.append(id, 1, LoggedEntry.toolResult("a", "alpha"));
    entries.append(
        id, 1, LoggedEntry.answer("", List.of(new ToolCall("b", "file_read", "{\"path\":\"b\"}"))));
    entries.append(id, 1, LoggedEntry.toolResult("b", "beta"));
    entries.foldWithinATurn(
        id,
        0,
        1,
        first.ordinal(),
        firstResult.ordinal(),
        LoggedEntry.turnSummary("read a, which holds alpha"));

    List<ChatMessage> projected = Projection.of(entries.thatProjectFor(id), true);

    assertEquals(4, projected.size(), projected.toString());
    assertEquals(
        "build the thing",
        projected.get(0).content(),
        "the opening request is kept word for word, and first");
    assertEquals(ChatMessage.Role.USER, projected.get(1).role());
    assertEquals(
        Compaction.turnSeam(true, 1, 0, "read a, which holds alpha"), projected.get(1).content());
    assertTrue(
        projected.get(1).content().contains("earlier work in this turn"),
        "the summary is not marked as the harness's summary of earlier work in this"
            + " turn: "
            + projected.get(1).content());
    assertEquals("b", projected.get(2).toolCalls().get(0).id(), "the kept step, whole");
    assertEquals("beta", projected.get(3).content());
    assertEquals(List.of(), unansweredCallsIn(projected));
  }

  /**
   * An in-turn fold that also took earlier turns says which, in the turn-number words a
   * between-turn seam uses, and the earlier turns are not shown.
   */
  @Test
  void an_in_turn_summary_that_took_earlier_turns_names_them() {
    String id = conversations.open(PAYMENTS, Budget.of(20)).id();
    entries.append(id, 1, LoggedEntry.utterance("turn one", Speaker.person(null)));
    entries.append(id, 1, LoggedEntry.answer("answered one", List.of()));
    entries.append(id, 2, LoggedEntry.utterance("turn two", Speaker.person(null)));
    EntryRecord call =
        entries.append(
            id, 2, LoggedEntry.answer("", List.of(new ToolCall("a", "file_read", "{}"))));
    EntryRecord result = entries.append(id, 2, LoggedEntry.toolResult("a", "alpha"));
    entries.append(id, 2, LoggedEntry.answer("", List.of(new ToolCall("b", "file_read", "{}"))));
    entries.append(id, 2, LoggedEntry.toolResult("b", "beta"));
    entries.foldWithinATurn(
        id,
        0,
        2,
        call.ordinal(),
        result.ordinal(),
        LoggedEntry.turnSummary("turn one answered; a holds alpha"));

    List<ChatMessage> projected = Projection.of(entries.thatProjectFor(id), false);

    assertEquals("turn two", projected.get(0).content(), projected.toString());
    assertTrue(
        projected.get(1).content().contains("turns 1 to 1 of this conversation"),
        projected.toString());
    assertFalse(
        projected.stream().anyMatch(message -> "turn one".equals(message.content())),
        "a turn the in-turn fold took is still shown");
    assertEquals(List.of(), unansweredCallsIn(projected));
  }

  /**
   * Two in-turn summaries of one turn are read in the order they were written, both before the
   * steps still kept.
   */
  @Test
  void two_in_turn_summaries_are_read_oldest_first_before_the_kept_steps() {
    String id = conversations.open(PAYMENTS, Budget.of(20)).id();
    entries.append(id, 1, LoggedEntry.utterance("the task", Speaker.person(null)));
    EntryRecord first =
        entries.append(
            id, 1, LoggedEntry.answer("", List.of(new ToolCall("a", "file_read", "{}"))));
    EntryRecord firstResult = entries.append(id, 1, LoggedEntry.toolResult("a", "one"));
    entries.foldWithinATurn(
        id,
        0,
        1,
        first.ordinal(),
        firstResult.ordinal(),
        LoggedEntry.turnSummary("the first span"));
    EntryRecord second =
        entries.append(
            id, 1, LoggedEntry.answer("", List.of(new ToolCall("b", "file_read", "{}"))));
    EntryRecord secondResult = entries.append(id, 1, LoggedEntry.toolResult("b", "two"));
    entries.foldWithinATurn(
        id,
        0,
        1,
        second.ordinal(),
        secondResult.ordinal(),
        LoggedEntry.turnSummary("the second span"));
    entries.append(id, 1, LoggedEntry.answer("", List.of(new ToolCall("c", "file_read", "{}"))));
    entries.append(id, 1, LoggedEntry.toolResult("c", "three"));

    List<ChatMessage> projected = Projection.of(entries.thatProjectFor(id), false);

    assertEquals(5, projected.size(), projected.toString());
    assertTrue(projected.get(1).content().endsWith("the first span"), projected.toString());
    assertTrue(projected.get(2).content().endsWith("the second span"), projected.toString());
    assertEquals("c", projected.get(3).toolCalls().get(0).id());
  }

  /**
   * A folded range is skipped, the summary stands in its place, and the log itself is unchanged
   * apart from appends.
   *
   * <p>The third clause is the one an append-only design lives or dies on, so it is asserted
   * against the rows and not against the projection: every entry written before the fold is still
   * there, at its own ordinal, with its own text.
   */
  @Test
  void a_folded_range_is_skipped_and_the_log_keeps_every_word_of_it() {
    Scripted model = new Scripted(LOADED_CONTEXT).costing(FIRST_TURN_COST, SECOND_TURN_COST);
    // The fold is held rather than run, so that the log can be read at the
    // one moment that matters: dispatched by the second turn's ending and
    // not yet landed. It runs below, on this thread.
    List<Runnable> held = new ArrayList<>();
    Fixture fixture = new Fixture(model, (Executor) held::add);
    String id = conversations.open(PAYMENTS, Budget.of(20)).id();

    fixture.speakAndWait(id, "what broke the deploy?");
    fixture.speakAndWait(id, "and who was on call?");
    List<EntryRecord> before = log(id);
    held.forEach(Runnable::run);

    List<EntryRecord> after = log(id);
    assertTrue(after.size() > before.size(), "the fold appended nothing");
    // Matched by ordinal and not by position. The log comes back in
    // conversation order now, so the summary the fold appended sorts in
    // among the turns it stands for rather than onto the end, and the two
    // lists no longer line up index for index. The claim being made is about
    // each entry and not about where it sits: every row written before the
    // fold is still there, at its own ordinal, saying what it said.
    for (EntryRecord was : before) {
      EntryRecord is =
          after.stream()
              .filter(entry -> entry.ordinal() == was.ordinal())
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError("entry " + was.ordinal() + " is not in the log any more"));
      assertEquals(was.kind(), is.kind());
      assertEquals(
          was.content(), is.content(), "entry " + was.ordinal() + " was rewritten by a fold");
    }
    // The first turn is behind the seam and the second is not.
    List<String> said =
        Projection.of(after, false).stream()
            .filter(message -> message.role() == ChatMessage.Role.USER)
            .map(ChatMessage::content)
            .toList();
    assertEquals(
        List.of("and who was on call?"),
        said,
        "the fold reaches through turn one, so only the second utterance is projected");
    assertTrue(
        Projection.of(after, false).stream()
            .anyMatch(
                message ->
                    message.role() == ChatMessage.Role.SYSTEM
                        && message.content().contains(SUMMARY)),
        "the summary did not stand in for what it folded away");
  }

  /**
   * A model that answered with nothing is still an answer in the projection.
   *
   * <p>{@code Outcome} permits an {@code ANSWERED} turn to carry the empty string and {@code
   * ChatMessage} refuses an assistant message with neither content nor calls, so the absence has to
   * be rendered. {@code Compaction} already renders it, with this wording; a projection that
   * dropped it would leave an utterance with no reply after it and show a model a conversation that
   * never took place.
   */
  @Test
  void a_turn_that_answered_with_nothing_is_still_a_turn() {
    String id = conversations.open(PAYMENTS, Budget.of(20)).id();
    entries.append(id, 1, LoggedEntry.utterance("say nothing", Speaker.person(null)));
    entries.append(id, 1, LoggedEntry.answer("", List.of()));

    assertEquals(
        List.of("say nothing", Compaction.SAID_NOTHING),
        Projection.of(log(id), false).stream().map(ChatMessage::content).toList());
  }

  /**
   * The messages a conversation projects are the same whichever read builds them.
   *
   * <p><b>The assertion that makes moving the filter into SQL a refactor.</b> {@code
   * EntryStore.thatProjectFor} fetches strictly fewer rows than {@code EntryStore.forConversation}
   * — everything a fold covered and every kind that carries no role stays in the database — and
   * what a model reads is unchanged, because those are exactly the rows {@link Projection#of}
   * already discarded. The point of the change is what the server stops hauling out of Postgres,
   * and the point of this test is that the model cannot tell.
   *
   * <p><b>Built row by row rather than by running turns</b>, because what is being asserted over is
   * the whole cross product of the filter: a folded span and a standing one, a summary, a tool
   * result the projection keeps, a declared call nothing answers, and all four kinds that carry no
   * role. A fixture that ran turns would produce whichever of those the fixture happens to produce.
   *
   * <p><b>Both filters stay.</b> {@link Projection#of} still skips a superseded entry and a kind
   * with no role, which costs nothing on rows that are no longer fetched and is what keeps this
   * equivalence checkable at all — the left-hand side of it is the projection reading the raw log.
   */
  @Test
  void the_messages_a_conversation_projects_are_the_same_read_either_way() {
    String id = conversations.open(PAYMENTS, Budget.of(20)).id();
    entries.append(id, 1, LoggedEntry.utterance("what broke the deploy?", Speaker.person(null)));
    entries.append(
        id, 1, LoggedEntry.answer("looking", List.of(new ToolCall("c1", "probe_read", "{}"))));
    entries.append(id, 1, LoggedEntry.toolResult("c1", "the migration did"));
    entries.append(id, 1, LoggedEntry.answer("the migration did", List.of()));
    entries.append(id, 1, LoggedEntry.attemptFailed(Ending.SESSION_GONE, "it went away"));
    entries.append(id, 1, LoggedEntry.diagnostic("compaction triggered at 12 000 tokens"));
    EntryRecord summary = entries.append(id, 1, LoggedEntry.summary("they talked about it"));
    entries.supersede(id, 0, 1, summary.ordinal());
    entries.append(id, 2, LoggedEntry.utterance("and who was on call?", Speaker.person(null)));
    entries.append(id, 2, LoggedEntry.runtimeNote("[Runtime note] you keep doing that"));
    entries.append(
        id, 2, new LoggedEntry(EntryKind.PLAN, "step one: read the file", null, List.of()));
    entries.append(
        id, 2, LoggedEntry.answer("nobody yet", List.of(new ToolCall("c9", "probe_read", "{}"))));

    List<EntryRecord> whole = entries.forConversation(id);
    List<EntryRecord> projecting = entries.thatProjectFor(id);

    assertTrue(
        projecting.size() < whole.size(),
        "the filtered read fetched the whole log, so this proves nothing");
    assertEquals(
        Projection.of(whole, false),
        Projection.of(projecting, false),
        "the model was shown something different by the read that fetches less");
    assertFalse(Projection.of(projecting, false).isEmpty(), "both reads projected nothing");
  }

  // --- scaffolding ---------------------------------------------------------------------

  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private List<EntryRecord> log(String conversationId) {
    return entries.forConversation(conversationId);
  }

  /**
   * An entry of one kind, between two that project, is recorded and comes out of the projection
   * unmentioned.
   */
  private void assertRecordedAndInvisible(LoggedEntry invisible) {
    assertNotNull(invisible);
    String id = conversations.open(PAYMENTS, Budget.of(20)).id();
    entries.append(id, 1, LoggedEntry.utterance("before", Speaker.person(null)));
    entries.append(id, 1, invisible);
    entries.append(id, 1, LoggedEntry.answer("after", List.of()));

    assertTrue(
        log(id).stream().anyMatch(entry -> entry.kind() == invisible.kind()),
        invisible.kind()
            + " was not recorded at all, so this proves nothing about the"
            + " projection ignoring it");
    assertEquals(
        List.of("before", "after"),
        Projection.of(log(id), false).stream().map(ChatMessage::content).toList(),
        invisible.kind() + " reached a request");
  }

  /**
   * What {@code turns} and {@code compactions} between them said a conversation was, frozen.
   *
   * <p><b>This is {@code Compaction.messages(standing, spoken)} as it stood before {@code
   * Compaction} read the log</b>, copied here word for word when that method was deleted. It is in
   * a test file because nothing in the server builds a history this way any more, and it is kept
   * because the equivalence it anchors is the whole argument that the switch changed nothing: once
   * the shipped path reads the log, asking the shipped path what the old path built is asking one
   * witness two questions.
   *
   * <p><b>The seam's lower bound is the literal 1 here</b>, which is not a translation loss: this
   * construction knew one standing fold and that fold always covered the conversation from its
   * first turn. A span that starts anywhere else is what the incremental fold introduced, and it is
   * exactly what the shipped path has to be asked about rather than this copy.
   *
   * <p>It is a copy and not a call, and copies rot. What stops this one rotting quietly is that it
   * is compared against the shipped path on every run — a change to what a turn projects fails
   * {@link #a_projected_request_is_the_request_the_turns_table_builds_across_a_fold} here rather
   * than passing because both sides moved together, which is exactly what a shared implementation
   * would have allowed.
   */
  private static List<ChatMessage> whatTheTurnsTableBuilds(
      Optional<CompactionRecord> standing, List<TurnRecord> spoken) {
    List<ChatMessage> history = new ArrayList<>();
    int from = 0;
    if (standing.isPresent()) {
      CompactionRecord folded = standing.get();
      history.add(
          ChatMessage.system(
              Compaction.SEAM.formatted(1, folded.throughOrdinal(), folded.summary())));
      from = folded.throughOrdinal();
    }
    for (TurnRecord turn : spoken) {
      if (turn.ordinal() <= from) {
        continue;
      }
      history.add(ChatMessage.user(turn.utterance()));
      history.add(
          ChatMessage.assistant(
              turn.answer().isBlank() ? Compaction.SAID_NOTHING : turn.answer(), List.of()));
    }
    return history;
  }

  /**
   * The request production would build from this history and this utterance: one system message
   * first, then everything said, then what is being answered.
   */
  private static ChatRequest requestOver(
      AgentDefinition definition, List<ChatMessage> history, String utterance) {
    List<ChatMessage> messages = JobRuntime.oneSystemMessageFirst(definition.prompt(), history);
    messages.add(ChatMessage.user(utterance));
    return ChatRequest.of(definition.model(), messages);
  }

  /** Call ids some answer declared and no result answers, read off the log. */
  private static List<String> declaredAndUnanswered(List<EntryRecord> log) {
    List<String> declared = new ArrayList<>();
    for (EntryRecord entry : log) {
      entry.toolCalls().forEach(call -> declared.add(call.id()));
      if (entry.kind() == EntryKind.TOOL_RESULT) {
        declared.remove(entry.toolCallId());
      }
    }
    return declared;
  }

  /**
   * The same question asked of a projection: every call an assistant message declared, less every
   * call a tool message answers.
   */
  private static List<String> unansweredCallsIn(List<ChatMessage> messages) {
    List<String> declared = new ArrayList<>();
    for (ChatMessage message : messages) {
      message.toolCalls().forEach(call -> declared.add(call.id()));
      if (message.role() == ChatMessage.Role.TOOL) {
        declared.remove(message.toolCallId());
      }
    }
    return declared;
  }

  private static AgentRegistry registry() throws Exception {
    return AgentRegistry.of(
        Path.of(ProjectionTest.class.getResource("/agents").toURI()), FIXTURE_TOOLS);
  }

  /**
   * The fixture agent, declaring the tools it is allowed to call.
   *
   * <p>The list is not decoration. {@code JobRuntime.offeredTo} dispatches against what the
   * <em>definition</em> declares and not against what the runtime holds, so an agent that declares
   * nothing gets "there is no tool called ..." for every call a test scripted — a tool result,
   * which reads as a working turn and is not one. Measured: a version of this file that handed the
   * runtime a tool and left this list empty went green on a turn whose only tool result was that
   * refusal.
   */
  /**
   * The fixture agent, which declares whatever tools it was handed <b>and {@code result_read}</b>.
   *
   * <p>The second half is what makes it the shape of an agent that holds a conversation across
   * turns, which is what every fixture here is standing in for. {@code Compaction} substitutes a
   * reference for an earlier turn's result only for an agent that can redeem one, so a talker
   * without it would get the old drop and {@code a_turn_that_used_a_tool_projects_its_working_
   * and_the_current_path_references_it} would be asserting about the wrong path. {@code
   * CompactionTest} is where the two paths are told apart.
   */
  private static AgentDefinition talker(String... tools) {
    List<String> declared = new ArrayList<>(List.of(tools));
    declared.add(ResultTools.READ_NAME);
    return new AgentDefinition(
        "talker", "a fixture agent", "fast", declared, List.of(), List.of(), 4, 20, AGENT_PROMPT);
  }

  private static Completion asking(String content, ToolCall... wanted) {
    return new Completion(content, "tool_calls", TokenUsage.UNKNOWN, List.of(wanted));
  }

  private static String delegate(String agent, String task) {
    return "{\"agent\":\"" + agent + "\",\"task\":\"" + task + "\"}";
  }

  private static LlmDispatcher dispatcherOver(LlmTransport transport) {
    return new LlmDispatcher(
        List.of(
            new LlmPool(
                "scripted",
                List.of("model-fast"),
                Map.of("fast", "model-fast"),
                4,
                1,
                Duration.ofSeconds(5),
                transport)),
        new NoOpTokenLedger());
  }

  /**
   * A conversation that can be spoken into, wired the way production wires one apart from the
   * transport.
   */
  private final class Fixture {

    private final JobStore jobs;
    private final Turn turn;
    private final Compaction compaction;
    private final AgentDefinition definition;

    /**
     * A fixture whose folds run on the thread that ends the turn.
     *
     * <p>A fold is asynchronous in production and happens at the end of the turn that triggers it,
     * so a test asserting on a folded log has to know when the fold finished. {@code Runnable::run}
     * answers that by construction: {@code speakAndWait} returns with the log already settled, and
     * nothing here sleeps.
     */
    Fixture(Scripted model, AgentTool... tools) {
      this(model, null, null, Runnable::run, tools);
    }

    /**
     * The same, with the folds held wherever the test wants them, so that a test can look at a log
     * between a fold being dispatched and it landing.
     */
    Fixture(Scripted model, Executor folds, AgentTool... tools) {
      this(model, null, null, folds, tools);
    }

    /**
     * A fixture whose agent declares every tool it was handed, so a scripted call reaches the tool
     * rather than the refusal.
     */
    Fixture(Scripted model, AgentRegistry agents, String agent, AgentTool... tools) {
      this(model, agents, agent, Runnable::run, tools);
    }

    Fixture(
        Scripted model, AgentRegistry agents, String agent, Executor folds, AgentTool... tools) {
      LlmDispatcher models = dispatcherOver(model);
      JobRuntime runtime =
          new JobRuntime(
              models,
              List.of(tools),
              agents == null ? null : () -> agents,
              (home, grants, sessionId, owner) -> List.<FileProvider>of());
      this.jobs = new JobStore(runtime);
      // A default context length no fixture here reaches: these tests are
      // about what a turn opens with, and a fold firing in the middle of
      // one would spend a call none of their arithmetic accounts for.
      this.compaction =
          new Compaction(
              models, ProjectionTest::folder, turns, compactions, entries, 1_000_000, folds);
      this.turn = new Turn(jobs, conversations, turns, compaction);
      this.definition =
          agent == null
              ? talker(
                  java.util.Arrays.stream(tools)
                      .map(tool -> tool.schema().name())
                      .toArray(String[]::new))
              : agents.get(agent);
      opened.add(jobs);
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
   * A tool that answers, or throws, whatever the test said. Named {@code probe_read} because that
   * is what the fixture agents declare.
   */
  private static final class Probe implements AgentTool {

    private final java.util.function.Function<Home, String> behaviour;

    Probe(java.util.function.Function<Home, String> behaviour) {
      this.behaviour = behaviour;
    }

    @Override
    public ToolSchema schema() {
      return new ToolSchema(
          "probe_read", "reads something", Map.of("type", "object", "properties", Map.of()));
    }

    @Override
    public String run(String argumentsJson, Home home) {
      return behaviour.apply(home);
    }
  }

  /**
   * A transport that answers from a queue, reports whatever context length the test asked for, and
   * knows a summarising call by its last message.
   *
   * <p>{@code Compaction.FROM_THE_HARNESS} opens the one this server writes and nothing else sends
   * it, so it is a reliable discriminator; {@code CompactionTest.Recorder} tells them apart the
   * same way and says why it has to be the opening of the last message rather than the whole of it.
   */
  private static final class Scripted implements LlmTransport {

    static final int NO_CONTEXT_LENGTH = -1;

    private final int loadedContext;
    private final Deque<java.util.function.Supplier<Completion>> steps = new ArrayDeque<>();
    private final Deque<Integer> costs = new ArrayDeque<>();
    private final List<List<ChatMessage>> seen = Collections.synchronizedList(new ArrayList<>());
    private volatile java.util.function.Supplier<Completion> fallback;
    private volatile boolean asksForATool;

    Scripted(int loadedContext) {
      this.loadedContext = loadedContext;
    }

    Scripted then(java.util.function.Supplier<Completion> step) {
      steps.add(step);
      return this;
    }

    Scripted thenAlways(java.util.function.Supplier<Completion> step) {
      this.fallback = step;
      return this;
    }

    /**
     * Ask for a file tool once per turn, so a turn makes two model calls and its history carries a
     * call and a result.
     */
    Scripted asksForATool() {
      this.asksForATool = true;
      return this;
    }

    /** The prompt costs to report, one per turn call, in order. */
    Scripted costing(int... perCall) {
      for (int cost : perCall) {
        costs.add(cost);
      }
      return this;
    }

    @Override
    public OptionalInt contextLength(String wireModel) {
      return loadedContext == NO_CONTEXT_LENGTH
          ? OptionalInt.empty()
          : OptionalInt.of(loadedContext);
    }

    /**
     * A fold due at a third of the window, configured: the rule this suite was written against,
     * kept through {@code compaction-thresholds} since spec 2026-09-30-fold-at-60-and-80 made a
     * window this small fold only inside a turn. See {@code CompactionTest.Recorder}.
     */
    @Override
    public OptionalInt compactionThreshold(String wireModel) {
      return loadedContext > 0 ? OptionalInt.of(loadedContext / 3) : OptionalInt.empty();
    }

    /** And the fold inside a turn held at the wall, so these folds are between turns. */
    @Override
    public OptionalInt compactionNowThreshold(String wireModel) {
      return loadedContext > 0 ? OptionalInt.of(loadedContext) : OptionalInt.empty();
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      if (messages.get(messages.size() - 1).content().startsWith(Compaction.FROM_THE_HARNESS)) {
        return new Completion(SUMMARY, "stop", TokenUsage.UNKNOWN, List.of());
      }
      seen.add(List.copyOf(messages));
      if (!steps.isEmpty()) {
        return steps.poll().get();
      }
      if (fallback != null) {
        return fallback.get();
      }
      boolean toolAnswered =
          messages.stream().anyMatch(message -> message.role() == ChatMessage.Role.TOOL);
      TokenUsage usage =
          costs.isEmpty() ? TokenUsage.UNKNOWN : TokenUsage.of(costs.poll(), 2, null);
      if (asksForATool && !toolAnswered) {
        return new Completion(
            "", "tool_calls", usage, List.of(new ToolCall("t1", "probe_read", "{}")));
      }
      return new Completion("an answer", "stop", usage, List.of());
    }

    @Override
    public Completion stream(
        String wireModel,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      Completion streamed = complete(wireModel, messages, sampling, tools);
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
    public void close() {}
  }

  /**
   * The agent a fold runs as, which nothing in this class asks for.
   *
   * <p>Named rather than left out all the same. A fold that could not say what it runs as would run
   * as the agent being folded, which is the defect {@code Compaction.FOLDER} exists to end --
   * {@code implementation rationale} §6.1 -- and a fixture that quietly had no folder would be the
   * one place that could not tell the two apart.
   */
  private static AgentDefinition folder() {
    return new AgentDefinition(
        Compaction.FOLDER,
        "a fixture folder",
        "fast",
        List.of(),
        List.of(),
        List.of(),
        1,
        1,
        "You summarise a span of a recorded conversation.");
  }
}
